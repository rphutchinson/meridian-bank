package com.meridianbank.service;

import com.meridianbank.annotation.AutomationLevel;
import com.meridianbank.annotation.DependencyDomain;
import com.meridianbank.annotation.MigrationComplexity;
import com.meridianbank.annotation.OracleDependency;
import com.meridianbank.dao.BatchProcessingDAO;
import com.meridianbank.dao.TransactionOracleDAO;
import com.meridianbank.dto.BatchResultDTO;
import com.meridianbank.dto.TransactionDTO;
import com.meridianbank.entity.TransactionEntity;
import com.meridianbank.exception.MeridianBankException;
import com.meridianbank.exception.OracleErrorTranslator;
import com.meridianbank.repository.AccountRepository;
import com.meridianbank.repository.TransactionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Service layer for Transaction operations.
 *
 * Orchestrates Spring Data JPA TransactionRepository and Oracle-specific
 * TransactionOracleDAO / BatchProcessingDAO. Contains several Oracle-specific
 * behavioural patterns:
 *
 * ORACLE-DEP [EMBEDDED_SQL:DATE_ARITHMETIC]      Settlement date = transactionDate + 2 days
 * ORACLE-DEP [EMBEDDED_SQL:NULL_EMPTY_STRING]    Oracle treats '' as NULL — description validated
 * ORACLE-DEP [EMBEDDED_SQL:MERGE_STATEMENT]      upsertByReference uses MERGE INTO
 * ORACLE-DEP [EMBEDDED_SQL:ORACLE_TIMESTAMP]     oracle.sql.TIMESTAMP binding in DAO calls
 * ORACLE-DEP [PLSQL_INVOCATION:BULK_COLLECT]     bulkRecordTransactions calls FORALL-based PL/SQL
 * ORACLE-DEP [EMBEDDED_SQL:ANALYTIC_FUNCTIONS]   Monthly summary uses TO_CHAR + window functions
 * ORACLE-DEP [EMBEDDED_SQL:ORACLE_ERROR_CODES]   ORA- codes translated in catch blocks
 */
@Service
@Transactional
public class TransactionService {

    private static final Logger log = LoggerFactory.getLogger(TransactionService.class);

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private TransactionOracleDAO transactionOracleDAO;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private BatchProcessingDAO batchProcessingDAO;

    @Autowired
    private OracleErrorTranslator errorTranslator;

    // -----------------------------------------------------------------------
    // Single transaction recording
    // -----------------------------------------------------------------------

    /**
     * Records a single financial transaction.
     *
     * Oracle-specific behaviours:
     *  1. Settlement date computed as transactionDate + 2 (Oracle date + integer arithmetic)
     *  2. Oracle empty string == NULL: blank description is rejected explicitly
     *  3. TransactionOracleDAO uses INSERT ... RETURNING transaction_id INTO ?
     *  4. oracle.sql.TIMESTAMP binding used for the OffsetDateTime in DAO layer
     */
    @OracleDependency(
        domain = DependencyDomain.EMBEDDED_SQL,
        category = "DATE_ARITHMETIC",
        description = "Settlement date calculated as transaction_date + 2 — mirrors Oracle date + integer day arithmetic",
        complexity = MigrationComplexity.LOW,
        automationLevel = AutomationLevel.FULL,
        migrationTarget = "PostgreSQL date + integer is also valid; or use date + interval '2 days'"
    )
    @OracleDependency(
        domain = DependencyDomain.EMBEDDED_SQL,
        category = "NULL_EMPTY_STRING",
        description = "Oracle treats '' as NULL in VARCHAR2 — blank transaction description normalised to null before INSERT",
        complexity = MigrationComplexity.MEDIUM,
        automationLevel = AutomationLevel.PARTIAL,
        migrationTarget = "PostgreSQL distinguishes '' from NULL; application must decide whether to NULLIF or validate"
    )
    @OracleDependency(
        domain = DependencyDomain.EMBEDDED_SQL,
        category = "RETURNING_INTO",
        description = "TransactionOracleDAO.recordTransaction uses INSERT...RETURNING transaction_id INTO ?",
        complexity = MigrationComplexity.MEDIUM,
        automationLevel = AutomationLevel.PARTIAL,
        migrationTarget = "PostgreSQL: INSERT...RETURNING transaction_id (no INTO keyword, different binding pattern)"
    )
    public TransactionDTO recordTransaction(TransactionDTO dto) {
        log.info("Recording {} transaction of {} on account {}",
            dto.getTransactionType(), dto.getAmount(), dto.getAccountId());

        // Validate required fields
        if (dto.getAccountId() == null) {
            throw new MeridianBankException("VALIDATION_ERROR",
                "Account ID is required for a transaction.", 400);
        }
        if (dto.getAmount() == null) {
            throw new MeridianBankException("VALIDATION_ERROR",
                "Transaction amount is required.", 400);
        }
        validateTransactionType(dto.getTransactionType());

        // ORACLE-DEP [EMBEDDED_SQL:NULL_EMPTY_STRING] Oracle VARCHAR2 treats '' as NULL;
        // explicitly reject blank description so behaviour is explicit in the migrated app
        if (dto.getDescription() != null && dto.getDescription().isEmpty()) {
            dto.setDescription(null);  // normalise '' → null to match Oracle VARCHAR2 semantics
        }
        if (dto.getDescription() == null) {
            // Provide a default description when none supplied
            dto.setDescription(dto.getTransactionType() + " transaction");
        }

        // Set transaction date if not provided
        if (dto.getTransactionDate() == null) {
            // ORACLE-DEP [EMBEDDED_SQL:SYSDATE] Equivalent of Oracle SYSDATE — current timestamp
            dto.setTransactionDate(OffsetDateTime.now(ZoneOffset.UTC));
        }

        // ORACLE-DEP [EMBEDDED_SQL:DATE_ARITHMETIC] Settlement date = transactionDate + 2 days;
        // in Oracle SQL this is written as: transaction_date + 2 (date arithmetic adds days)
        OffsetDateTime settlementDate = dto.getTransactionDate().plusDays(2);
        dto.setProcessedDate(settlementDate);

        // Generate reference number if absent
        if (dto.getReferenceNumber() == null || dto.getReferenceNumber().isBlank()) {
            dto.setReferenceNumber(generateReferenceNumber());
        }

        if (dto.getStatus() == null) {
            dto.setStatus("PENDING");
        }

        // ORACLE-DEP [EMBEDDED_SQL:RETURNING_INTO] TransactionOracleDAO.recordTransaction executes
        // INSERT INTO transactions (...) VALUES (...) RETURNING transaction_id INTO ?
        Long txnId;
        try {
            txnId = transactionOracleDAO.recordTransaction(dto);
        } catch (SQLException e) {
            // ORACLE-DEP [EMBEDDED_SQL:ORACLE_ERROR_CODES] ORA-01400 (NOT NULL), ORA-02291 (FK)
            throw errorTranslator.translateSQLException(e);
        }

        log.info("Transaction recorded with ID {}", txnId);
        dto.setTransactionId(txnId);
        return dto;
    }

    // -----------------------------------------------------------------------
    // Retrieval
    // -----------------------------------------------------------------------

    /**
     * Retrieves a transaction by primary key using Spring Data JPA.
     */
    @Transactional(readOnly = true)
    public TransactionDTO getTransactionById(Long transactionId) {
        TransactionEntity entity = transactionRepository.findById(transactionId)
            .orElseThrow(() -> new MeridianBankException("NOT_FOUND",
                "Transaction not found with ID: " + transactionId, 404));
        return entityToDto(entity);
    }

    /**
     * Retrieves a transaction by its unique reference number.
     */
    @Transactional(readOnly = true)
    public TransactionDTO getTransactionByReference(String referenceNumber) {
        return transactionRepository.findByReferenceNumber(referenceNumber)
            .map(this::entityToDto)
            .orElseThrow(() -> new MeridianBankException("NOT_FOUND",
                "Transaction not found with reference: " + referenceNumber, 404));
    }

    /**
     * Searches transactions for an account within a date range.
     * Delegates to TransactionOracleDAO which uses TO_DATE() conversion and
     * Oracle TIMESTAMP WITH TIME ZONE binding via oracle.sql.TIMESTAMP.
     */
    @OracleDependency(
        domain = DependencyDomain.EMBEDDED_SQL,
        category = "TO_DATE_FUNCTION",
        description = "Date range search uses TO_DATE(:startDate,'YYYY-MM-DD') in the native query for Oracle type binding",
        complexity = MigrationComplexity.LOW,
        automationLevel = AutomationLevel.FULL,
        migrationTarget = "PostgreSQL: TO_DATE(:startDate,'YYYY-MM-DD') is identical; or cast directly with CAST(? AS DATE)"
    )
    @Transactional(readOnly = true)
    public List<TransactionDTO> searchTransactions(Long accountId, LocalDate start, LocalDate end) {
        if (start == null) start = LocalDate.now().minusMonths(3);
        if (end == null) end = LocalDate.now();
        if (start.isAfter(end)) {
            throw new MeridianBankException("VALIDATION_ERROR",
                "Start date must be before or equal to end date.", 400);
        }

        // ORACLE-DEP [EMBEDDED_SQL:TO_DATE_FUNCTION] TransactionOracleDAO.getSummaryForDateRange
        // uses TO_DATE(:date,'YYYY-MM-DD') in the WHERE clause
        try {
            return transactionOracleDAO.getSummaryForDateRange(accountId, start, end);
        } catch (SQLException e) {
            throw errorTranslator.translateSQLException(e);
        }
    }

    /**
     * Returns the most recent N days of transactions for an account.
     * Uses Oracle INDEX hint in the underlying DAO query.
     */
    @OracleDependency(
        domain = DependencyDomain.EMBEDDED_SQL,
        category = "OPTIMIZER_HINTS",
        description = "Recent transactions query uses /*+ INDEX(t TXN_ACCOUNT_DATE_IDX) */ hint in TransactionOracleDAO",
        complexity = MigrationComplexity.LOW,
        automationLevel = AutomationLevel.PARTIAL,
        migrationTarget = "Remove Oracle hints; rely on PostgreSQL planner or add index hints via pg_hint_plan extension"
    )
    @Transactional(readOnly = true)
    public List<TransactionDTO> getRecentTransactions(Long accountId, int daysBack) {
        if (daysBack <= 0 || daysBack > 730) daysBack = 30;
        // ORACLE-DEP [EMBEDDED_SQL:OPTIMIZER_HINTS] INDEX hint in TransactionOracleDAO
        try {
            return transactionOracleDAO.findRecentTransactions(accountId, daysBack);
        } catch (SQLException e) {
            throw errorTranslator.translateSQLException(e);
        }
    }

    /**
     * Returns monthly transaction summary using Oracle TO_CHAR and analytic functions.
     * The repository native query uses TO_CHAR(transaction_date,'YYYY-MM') for period grouping
     * and SUM() window functions for running totals.
     */
    @OracleDependency(
        domain = DependencyDomain.EMBEDDED_SQL,
        category = "TO_CHAR_FUNCTION",
        description = "Monthly summary groups by TO_CHAR(transaction_date,'YYYY-MM') and uses SUM() OVER for running total",
        complexity = MigrationComplexity.LOW,
        automationLevel = AutomationLevel.FULL,
        migrationTarget = "TO_CHAR works in PostgreSQL; SUM() OVER window functions are also PostgreSQL-compatible"
    )
    @Transactional(readOnly = true)
    public List<Object[]> getTransactionSummaryByPeriod(Long accountId, int year) {
        if (year <= 0) year = LocalDate.now().getYear();
        // ORACLE-DEP [EMBEDDED_SQL:TO_CHAR_FUNCTION] Repository native: TO_CHAR(t.transaction_date,'YYYY') = :year
        // ORACLE-DEP [EMBEDDED_SQL:TO_CHAR_FUNCTION] Repository uses TO_CHAR(date,'YYYY') for year filtering
        // findMonthlySummaryByAccount takes int months param (rolling window), not a year string
        return transactionRepository.findMonthlySummaryByAccount(accountId, 12);
    }

    /**
     * Returns transactions with signed amounts (positive for credits, negative for debits)
     * using Oracle DECODE to determine sign.
     */
    @OracleDependency(
        domain = DependencyDomain.EMBEDDED_SQL,
        category = "DECODE_FUNCTION",
        description = "Signed transaction amounts use DECODE(transaction_type,'DEBIT',-1,1) * amount",
        complexity = MigrationComplexity.LOW,
        automationLevel = AutomationLevel.FULL,
        migrationTarget = "Replace DECODE with CASE WHEN transaction_type='DEBIT' THEN -1 ELSE 1 END * amount"
    )
    @Transactional(readOnly = true)
    public List<Object[]> getSignedTransactions(Long accountId) {
        // ORACLE-DEP [EMBEDDED_SQL:DECODE_FUNCTION] Repository native: DECODE(transaction_type,'DEBIT',-1,1)*amount
        return transactionRepository.findSignedTransactions(accountId);
    }

    /**
     * Returns overdue settlement transactions.
     * The repository native query uses SYSDATE and Oracle date arithmetic
     * to find transactions where processed_date < SYSDATE - 1.
     */
    @OracleDependency(
        domain = DependencyDomain.EMBEDDED_SQL,
        category = "SYSDATE",
        description = "Overdue settlement query uses SYSDATE - 1 for cutoff — Oracle date arithmetic",
        complexity = MigrationComplexity.LOW,
        automationLevel = AutomationLevel.FULL,
        migrationTarget = "Replace SYSDATE with NOW() or CURRENT_TIMESTAMP in PostgreSQL"
    )
    @Transactional(readOnly = true)
    public List<Object[]> getOverdueSettlements() {
        // ORACLE-DEP [EMBEDDED_SQL:SYSDATE] Native query: processed_date < SYSDATE - 1
        return transactionRepository.findOverdueSettlements();
    }

    // -----------------------------------------------------------------------
    // Bulk and batch operations
    // -----------------------------------------------------------------------

    /**
     * Bulk-records a list of transactions using Oracle FORALL bulk DML.
     *
     * TransactionOracleDAO.bulkRecordTransactions calls a PL/SQL procedure that
     * internally uses FORALL i IN 1..transactions.COUNT INSERT INTO transactions VALUES transactions(i);
     * This is Oracle's most efficient bulk insert mechanism.
     */
    @OracleDependency(
        domain = DependencyDomain.PLSQL_INVOCATION,
        category = "BULK_COLLECT_FORALL",
        description = "Bulk transaction insert calls TRANSACTION_PKG.BULK_INSERT which uses PL/SQL FORALL for batch DML",
        complexity = MigrationComplexity.HIGH,
        automationLevel = AutomationLevel.PARTIAL,
        migrationTarget = "Replace with JDBC batch PreparedStatement (addBatch/executeBatch) or unnest()+INSERT...SELECT in PostgreSQL"
    )
    public int bulkRecordTransactions(List<TransactionDTO> transactions) {
        if (transactions == null || transactions.isEmpty()) return 0;

        // Normalise all DTOs before bulk insert
        for (TransactionDTO dto : transactions) {
            validateTransactionType(dto.getTransactionType());
            // ORACLE-DEP [EMBEDDED_SQL:NULL_EMPTY_STRING] Normalise empty descriptions for Oracle VARCHAR2
            if (dto.getDescription() != null && dto.getDescription().isEmpty()) {
                dto.setDescription(null);
            }
            if (dto.getTransactionDate() == null) {
                dto.setTransactionDate(OffsetDateTime.now(ZoneOffset.UTC));
            }
            // ORACLE-DEP [EMBEDDED_SQL:DATE_ARITHMETIC] Settlement = transactionDate + 2 days
            if (dto.getProcessedDate() == null) {
                dto.setProcessedDate(dto.getTransactionDate().plusDays(2));
            }
            if (dto.getReferenceNumber() == null || dto.getReferenceNumber().isBlank()) {
                dto.setReferenceNumber(generateReferenceNumber());
            }
        }

        // ORACLE-DEP [PLSQL_INVOCATION:BULK_COLLECT_FORALL] TransactionOracleDAO.bulkRecordTransactions
        // calls TRANSACTION_PKG.BULK_INSERT with Oracle ARRAY binding; procedure uses FORALL for DML
        try {
            return transactionOracleDAO.bulkRecordTransactions(transactions);
        } catch (SQLException e) {
            throw errorTranslator.translateSQLException(e);
        }
    }

    /**
     * Upserts a transaction by reference number using Oracle MERGE.
     *
     * The DAO executes:
     *   MERGE INTO transactions tgt
     *   USING (SELECT :ref as ref_num, :amount as amt, ... FROM DUAL) src
     *   ON (tgt.reference_number = src.ref_num)
     *   WHEN MATCHED THEN UPDATE SET tgt.amount = src.amt, ...
     *   WHEN NOT MATCHED THEN INSERT (...) VALUES (...)
     */
    @OracleDependency(
        domain = DependencyDomain.EMBEDDED_SQL,
        category = "MERGE_STATEMENT",
        description = "Transaction upsert by reference uses Oracle MERGE INTO...USING(SELECT...FROM DUAL) pattern",
        complexity = MigrationComplexity.MEDIUM,
        automationLevel = AutomationLevel.PARTIAL,
        migrationTarget = "Replace with PostgreSQL INSERT...ON CONFLICT(reference_number) DO UPDATE SET ..."
    )
    public void upsertByReference(TransactionDTO dto) {
        if (dto.getReferenceNumber() == null || dto.getReferenceNumber().isBlank()) {
            throw new MeridianBankException("VALIDATION_ERROR",
                "Reference number is required for upsert operation.", 400);
        }
        // ORACLE-DEP [EMBEDDED_SQL:MERGE_STATEMENT] TransactionOracleDAO.upsertByReference
        // runs MERGE INTO transactions USING (SELECT ... FROM DUAL) ON (reference_number = ?)
        try {
            transactionOracleDAO.upsertByReference(dto);
        } catch (SQLException e) {
            throw errorTranslator.translateSQLException(e);
        }
    }

    /**
     * Triggers end-of-day processing for a given business date.
     *
     * Delegates to:
     *  1. BatchProcessingDAO.executeEndOfDayProcedure — calls BATCH_PKG.END_OF_DAY via
     *     PL/SQL anonymous block; internally uses DBMS_SCHEDULER job submission
     *  2. BatchProcessingDAO.mergeTransactionSummaries — MERGE for summary aggregation
     */
    @OracleDependency(
        domain = DependencyDomain.PLSQL_INVOCATION,
        category = "DBMS_SCHEDULER",
        description = "End-of-day processing triggers DBMS_SCHEDULER job via BatchProcessingDAO.executeEndOfDayProcedure",
        complexity = MigrationComplexity.HIGH,
        automationLevel = AutomationLevel.MANUAL,
        migrationTarget = "Replace DBMS_SCHEDULER with application-tier job scheduler (Spring @Scheduled, Quartz, or database cron)"
    )
    @OracleDependency(
        domain = DependencyDomain.EMBEDDED_SQL,
        category = "MERGE_STATEMENT",
        description = "Transaction summary aggregation uses MERGE INTO daily_summaries for upsert",
        complexity = MigrationComplexity.MEDIUM,
        automationLevel = AutomationLevel.PARTIAL,
        migrationTarget = "Replace Oracle MERGE with PostgreSQL INSERT...ON CONFLICT DO UPDATE"
    )
    public BatchResultDTO processEndOfDay(LocalDate businessDate) {
        log.info("Processing end-of-day for {}", businessDate);

        if (businessDate == null) {
            businessDate = LocalDate.now();
        }
        if (businessDate.isAfter(LocalDate.now())) {
            throw new MeridianBankException("VALIDATION_ERROR",
                "Cannot process end-of-day for a future date.", 400);
        }

        // ORACLE-DEP [PLSQL_INVOCATION:DBMS_SCHEDULER] BatchProcessingDAO.executeEndOfDayProcedure
        // submits BATCH_PKG.END_OF_DAY which calls DBMS_SCHEDULER.CREATE_JOB internally
        int eodResult = batchProcessingDAO.executeEndOfDayProcedure(businessDate);

        // ORACLE-DEP [EMBEDDED_SQL:MERGE_STATEMENT] Merge daily transaction aggregates into summary table
        int mergeResult = batchProcessingDAO.mergeTransactionSummaries(businessDate);

        log.info("End-of-day complete: eodRows={}, mergeRows={}", eodResult, mergeResult);

        BatchResultDTO result = new BatchResultDTO();
        result.setRecordsProcessed((long) eodResult);   // BatchResultDTO uses recordsProcessed not processedCount
        result.setRecordsSucceeded((long) mergeResult); // BatchResultDTO uses recordsSucceeded not mergedCount
        result.setJobName("END_OF_DAY_" + businessDate.toString()); // businessDate embedded in job name
        result.setStatus("COMPLETED");
        return result;
    }

    /**
     * Returns high-volume debit accounts using Oracle analytic functions.
     * The repository native query uses SUM(amount) OVER (PARTITION BY account_id)
     * along with DECODE for transaction type filtering.
     */
    @OracleDependency(
        domain = DependencyDomain.EMBEDDED_SQL,
        category = "ANALYTIC_FUNCTIONS",
        description = "High-volume debit report uses SUM(amount) OVER (PARTITION BY account_id) Oracle analytic",
        complexity = MigrationComplexity.MEDIUM,
        automationLevel = AutomationLevel.FULL,
        migrationTarget = "PostgreSQL supports SUM() OVER (PARTITION BY) window functions natively"
    )
    @Transactional(readOnly = true)
    public List<Object[]> getHighVolumeDebitAccounts(BigDecimal threshold) {
        // ORACLE-DEP [EMBEDDED_SQL:ANALYTIC_FUNCTIONS] Native query uses SUM() OVER PARTITION BY
        return transactionRepository.findHighVolumeDebitAccounts(threshold);
    }

    /**
     * Returns daily transaction summary for an account using Oracle TRUNC() date function.
     * The repository native query uses TRUNC(transaction_date) to group by calendar day.
     */
    @OracleDependency(
        domain = DependencyDomain.EMBEDDED_SQL,
        category = "TRUNC_DATE_FUNCTION",
        description = "Daily summary groups by TRUNC(transaction_date) — Oracle date truncation to midnight",
        complexity = MigrationComplexity.LOW,
        automationLevel = AutomationLevel.FULL,
        migrationTarget = "Replace Oracle TRUNC(date) with DATE_TRUNC('day', timestamp) in PostgreSQL"
    )
    @Transactional(readOnly = true)
    public List<Object[]> getDailySummary(Long accountId, LocalDate start, LocalDate end) {
        if (start == null) start = LocalDate.now().minusMonths(1);
        if (end == null) end = LocalDate.now();
        // ORACLE-DEP [EMBEDDED_SQL:TRUNC_DATE_FUNCTION] Native query: TRUNC(t.transaction_date) for day grouping
        // ORACLE-DEP [EMBEDDED_SQL:TRUNC_DATE_FUNCTION] Repository uses TRUNC(date) with days rolling window
        // findDailySummary takes int days param (rolling window from today, not a date range)
        long days = java.time.temporal.ChronoUnit.DAYS.between(start, end.plusDays(1));
        return transactionRepository.findDailySummary(accountId, (int) days);
    }

    /**
     * Returns transactions in a reference number range, using Oracle BETWEEN on the
     * sequence-generated reference number values.
     */
    @OracleDependency(
        domain = DependencyDomain.EMBEDDED_SQL,
        category = "SEQUENCE_NEXTVAL",
        description = "Reference number range query reflects Oracle sequence-generated numeric reference IDs",
        complexity = MigrationComplexity.LOW,
        automationLevel = AutomationLevel.FULL,
        migrationTarget = "Reference number generation moves to application tier or PostgreSQL sequence; BETWEEN query unchanged"
    )
    @Transactional(readOnly = true)
    public List<TransactionDTO> getTransactionsByReferenceRange(Long startRef, Long endRef) {
        // ORACLE-DEP [EMBEDDED_SQL:SEQUENCE_NEXTVAL] Reference numbers are Oracle sequence values
        List<TransactionEntity> entities =
            transactionRepository.findByReferenceNumberRange(startRef, endRef);
        List<TransactionDTO> results = new ArrayList<>();
        for (TransactionEntity e : entities) {
            results.add(entityToDto(e));
        }
        return results;
    }

    /**
     * Updates the status of a transaction using a Spring Data native query.
     * The native query uses SYSTIMESTAMP for the updated_date timestamp.
     */
    @OracleDependency(
        domain = DependencyDomain.EMBEDDED_SQL,
        category = "SYSTIMESTAMP",
        description = "Transaction status update sets updated_date = SYSTIMESTAMP in native query",
        complexity = MigrationComplexity.LOW,
        automationLevel = AutomationLevel.FULL,
        migrationTarget = "Replace SYSTIMESTAMP with NOW() or CURRENT_TIMESTAMP in PostgreSQL"
    )
    public int updateTransactionStatus(Long transactionId, String newStatus) {
        validateTransactionStatus(newStatus);
        // ORACLE-DEP [EMBEDDED_SQL:SYSTIMESTAMP] Repository native query uses SYSTIMESTAMP
        return transactionRepository.updateTransactionStatus(transactionId, newStatus);
    }

    /**
     * Returns transactions with running balance using Oracle SUM() OVER analytic.
     * The repository native query uses:
     *   SUM(DECODE(transaction_type,'CREDIT',amount,-amount))
     *     OVER (PARTITION BY account_id ORDER BY transaction_date
     *     ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW) AS running_balance
     */
    @OracleDependency(
        domain = DependencyDomain.EMBEDDED_SQL,
        category = "ANALYTIC_FUNCTIONS",
        description = "Running balance uses SUM() OVER (ORDER BY...ROWS BETWEEN...) Oracle windowed analytic",
        complexity = MigrationComplexity.MEDIUM,
        automationLevel = AutomationLevel.FULL,
        migrationTarget = "PostgreSQL supports identical SUM() OVER (ORDER BY...ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW)"
    )
    @Transactional(readOnly = true)
    public List<Object[]> getRecentCompletedWithRunningBalance(Long accountId) {
        // ORACLE-DEP [EMBEDDED_SQL:ANALYTIC_FUNCTIONS] SUM() OVER window for running balance
        return transactionRepository.findRecentCompletedWithHint(accountId);
    }

    // -----------------------------------------------------------------------
    // Private helpers
    // -----------------------------------------------------------------------

    private TransactionDTO entityToDto(TransactionEntity entity) {
        TransactionDTO dto = new TransactionDTO();
        dto.setTransactionId(entity.getTransactionId());
        dto.setReferenceNumber(entity.getReferenceNumber());
        dto.setAccountId(entity.getAccountId());
        dto.setAmount(entity.getAmount());
        dto.setTransactionType(entity.getTransactionType());
        dto.setDescription(entity.getDescription());
        dto.setStatus(entity.getStatus());
        dto.setCategoryCode(entity.getCategoryCode());
        dto.setTransactionDate(entity.getTransactionDate());
        dto.setProcessedDate(entity.getProcessedDate());
        dto.setCounterpartyAccountId(entity.getCounterpartyId());
        return dto;
    }

    private void validateTransactionType(String type) {
        if (type == null) {
            throw new MeridianBankException("VALIDATION_ERROR",
                "Transaction type is required.", 400);
        }
        switch (type.toUpperCase()) {
            case "DEBIT", "CREDIT", "TRANSFER", "FEE", "INTEREST", "REVERSAL" -> { /* valid */ }
            default -> throw new MeridianBankException("VALIDATION_ERROR",
                "Invalid transaction type: " + type, 400);
        }
    }

    private void validateTransactionStatus(String status) {
        switch (status != null ? status.toUpperCase() : "") {
            case "PENDING", "COMPLETED", "FAILED", "REVERSED" -> { /* valid */ }
            default -> throw new MeridianBankException("VALIDATION_ERROR",
                "Invalid transaction status: " + status, 400);
        }
    }

    private String generateReferenceNumber() {
        return "TXN-" + System.currentTimeMillis() + "-"
            + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }
}
