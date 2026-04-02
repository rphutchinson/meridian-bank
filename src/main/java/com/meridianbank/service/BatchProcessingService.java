package com.meridianbank.service;

import com.meridianbank.annotation.AutomationLevel;
import com.meridianbank.annotation.DependencyDomain;
import com.meridianbank.annotation.MigrationComplexity;
import com.meridianbank.annotation.OracleDependency;
import com.meridianbank.dao.BatchProcessingDAO;
import com.meridianbank.dto.BatchResultDTO;
import com.meridianbank.dto.TransactionDTO;
import com.meridianbank.entity.ScheduledJobEntity;
import com.meridianbank.exception.MeridianBankException;
import com.meridianbank.exception.OracleErrorTranslator;
import com.meridianbank.repository.AccountRepository;
import com.meridianbank.repository.ScheduledJobRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * BatchProcessingService -- business logic for Oracle-specific batch, bulk, and
 * scheduled job operations.
 *
 * <p>This service exercises the widest range of Oracle batch-specific features:
 * <ul>
 *   <li><strong>DBMS_SCHEDULER</strong> -- Oracle's built-in job scheduler; the
 *       application triggers and monitors scheduler jobs via PL/SQL package calls.</li>
 *   <li><strong>MERGE INTO ... USING ... WHEN MATCHED ... WHEN NOT MATCHED</strong> --
 *       Oracle's upsert statement for efficient bulk reconciliation.</li>
 *   <li><strong>FORALL / BULK COLLECT</strong> -- Oracle PL/SQL bulk operations
 *       called indirectly via stored-procedure invocations from BatchProcessingDAO.</li>
 *   <li><strong>Oracle ARRAY type binding</strong> -- passing a Java list as an
 *       oracle.sql.ARRAY to avoid individual round-trips for bulk status updates.</li>
 *   <li><strong>Optimizer PARALLEL hint</strong> -- /&#42;+ PARALLEL(t,4) &#42;/ applied
 *       to full-table batch query for throughput.</li>
 *   <li><strong>ROWID-based updates</strong> -- using Oracle ROWID for the most
 *       efficient single-row update path in batch operations.</li>
 *   <li><strong>PL/SQL anonymous blocks</strong> -- multi-statement PL/SQL blocks
 *       executed via JDBC for end-of-day atomic processing.</li>
 * </ul>
 *
 * <p>Migration note: these patterns map to very different targets on PostgreSQL:
 * DBMS_SCHEDULER -> pg_cron / Quartz / Temporal; MERGE -> INSERT ON CONFLICT;
 * FORALL/BULK COLLECT -> JDBC batch; Oracle ARRAY -> unnest(ARRAY[...]); ROWID -> ctid
 * (not recommended for use in application code).
 */
@Service
public class BatchProcessingService {

    private static final Logger log = LoggerFactory.getLogger(BatchProcessingService.class);

    @Autowired
    private BatchProcessingDAO batchProcessingDAO;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private OracleErrorTranslator oracleErrorTranslator;

    @Autowired
    private ScheduledJobRepository scheduledJobRepository;

    // -------------------------------------------------------------------------
    // End-of-day processing
    // -------------------------------------------------------------------------

    /**
     * Runs the full end-of-day batch for the given business date.
     *
     * <p>The DAO calls the Oracle BATCH_PKG.END_OF_DAY_PROCESSING stored procedure
     * via a PL/SQL anonymous block, then merges transaction summaries using a MERGE
     * statement.  DBMS_SCHEDULER is used to schedule subordinate overnight jobs
     * (interest accrual, statement generation) from within the stored procedure.
     *
     * @param businessDate the date being closed
     * @return BatchResultDTO summarising rows processed, errors, and elapsed time
     * @throws MeridianBankException if the procedure call or MERGE fails
     */
    // ORACLE-DEP [PLSQL_INVOCATION:DBMS_SCHEDULER] EOD invokes DBMS_SCHEDULER via stored proc
    // ORACLE-DEP [EMBEDDED_SQL:PLSQL_ANONYMOUS_BLOCK] EOD PL/SQL block executed via JDBC
    // ORACLE-DEP [EMBEDDED_SQL:MERGE_STATEMENT] mergeTransactionSummaries uses Oracle MERGE
    @Transactional
    @OracleDependency(
        domain = DependencyDomain.PLSQL_INVOCATION,
        category = "DBMS_SCHEDULER",
        description = "Delegates to BatchProcessingDAO.executeEndOfDayProcedure() which calls "
                    + "BATCH_PKG.END_OF_DAY_PROCESSING; that procedure internally calls "
                    + "DBMS_SCHEDULER.CREATE_JOB to schedule overnight interest and statement jobs",
        complexity = MigrationComplexity.VERY_HIGH,
        automationLevel = AutomationLevel.MANUAL,
        migrationTarget = "Replace DBMS_SCHEDULER with a Java-side scheduler: Quartz Scheduler, "
                        + "Spring @Scheduled, pg_cron for PostgreSQL-resident jobs, or Temporal "
                        + "for durable workflow orchestration"
    )
    @OracleDependency(
        domain = DependencyDomain.EMBEDDED_SQL,
        category = "PLSQL_ANONYMOUS_BLOCK",
        description = "BatchProcessingDAO.executeEndOfDayProcedure() wraps the stored procedure call "
                    + "in a PL/SQL anonymous block executed via JDBC CallableStatement to enable "
                    + "multi-step atomic processing within a single database round-trip",
        complexity = MigrationComplexity.HIGH,
        automationLevel = AutomationLevel.MANUAL,
        migrationTarget = "Decompose the PL/SQL anonymous block into individual JDBC statements "
                        + "or application-tier service method calls in PostgreSQL; "
                        + "use Spring @Transactional to maintain atomicity"
    )
    @OracleDependency(
        domain = DependencyDomain.EMBEDDED_SQL,
        category = "MERGE_STATEMENT",
        description = "mergeTransactionSummaries() is called immediately after the EOD procedure "
                    + "and executes a MERGE INTO ... USING ... WHEN MATCHED / WHEN NOT MATCHED "
                    + "to reconcile daily transaction summaries atomically",
        complexity = MigrationComplexity.MEDIUM,
        automationLevel = AutomationLevel.PARTIAL,
        migrationTarget = "Replace Oracle MERGE INTO with PostgreSQL INSERT ... ON CONFLICT (key) "
                        + "DO UPDATE SET col = EXCLUDED.col"
    )
    public BatchResultDTO runEndOfDayProcessing(LocalDate businessDate) {
        if (businessDate == null) {
            throw new MeridianBankException("INVALID_INPUT", "Business date must not be null", 400);
        }
        if (businessDate.isAfter(LocalDate.now())) {
            throw new MeridianBankException("INVALID_INPUT",
                "Cannot run end-of-day for a future date: " + businessDate, 400);
        }
        log.info("Starting end-of-day batch processing for {} via Oracle DBMS_SCHEDULER + PL/SQL block",
                 businessDate);
        long startTime = System.currentTimeMillis();
        try {
            // ORACLE-DEP [PLSQL_INVOCATION:DBMS_SCHEDULER] executeEndOfDayProcedure calls DBMS_SCHEDULER
            // ORACLE-DEP [EMBEDDED_SQL:PLSQL_ANONYMOUS_BLOCK] PL/SQL block via JDBC
            int procRows = batchProcessingDAO.executeEndOfDayProcedure(businessDate);
            log.info("EOD procedure completed: {} rows processed", procRows);

            // ORACLE-DEP [EMBEDDED_SQL:MERGE_STATEMENT] mergeTransactionSummaries uses Oracle MERGE
            int mergeRows = mergeTransactionSummaries(businessDate);
            log.info("Transaction summary MERGE completed: {} rows upserted", mergeRows);

            long elapsedMs = System.currentTimeMillis() - startTime;
            BatchResultDTO result = new BatchResultDTO();
            result.setJobName("END_OF_DAY");
            // businessDate set via jobType/jobName fields
            result.setRecordsProcessed((long) procRows + mergeRows);
            result.setRecordsSucceeded((long) procRows + mergeRows);
            result.setRecordsFailed((long) 0);
            result.setDurationMs(elapsedMs);
            result.setStatus("COMPLETED");
            return result;
        } catch (Exception e) {
            log.error("End-of-day processing failed for {}: {}", businessDate, e.getMessage(), e);
            if (e.getCause() instanceof SQLException sqlEx) {
                throw oracleErrorTranslator.translateSQLException(sqlEx);
            }
            throw new MeridianBankException("EOD_FAILED",
                "End-of-day processing failed: " + e.getMessage(), 500, e);
        }
    }

    // -------------------------------------------------------------------------
    // Interest calculation
    // -------------------------------------------------------------------------

    /**
     * Runs the interest calculation batch for all eligible accounts.
     *
     * <p>The DAO executes a MERGE statement (upsert pattern) to post accrued
     * interest to account balances and bulk inserts interest transactions.
     * Oracle FORALL / BULK COLLECT are used inside the called PL/SQL procedure
     * for the actual row processing.
     *
     * @param businessDate the date for which interest is calculated
     * @return BatchResultDTO with rows updated
     * @throws MeridianBankException if the interest calculation fails
     */
    // ORACLE-DEP [EMBEDDED_SQL:MERGE_STATEMENT] Interest calculation uses MERGE for upsert
    // ORACLE-DEP [EMBEDDED_SQL:BULK_OPERATIONS] Interest calculation batch uses FORALL/BULK COLLECT
    @Transactional
    @OracleDependency(
        domain = DependencyDomain.EMBEDDED_SQL,
        category = "MERGE_STATEMENT",
        description = "Delegates to BatchProcessingDAO.calculateInterestBatch() which uses "
                    + "MERGE INTO account_balances USING (calculated interest) ON (account_id) "
                    + "WHEN MATCHED UPDATE / WHEN NOT MATCHED INSERT for atomic upsert",
        complexity = MigrationComplexity.MEDIUM,
        automationLevel = AutomationLevel.PARTIAL,
        migrationTarget = "Replace Oracle MERGE with PostgreSQL INSERT ... ON CONFLICT (account_id) "
                        + "DO UPDATE SET balance = balance + EXCLUDED.interest_amount"
    )
    @OracleDependency(
        domain = DependencyDomain.EMBEDDED_SQL,
        category = "BULK_OPERATIONS",
        description = "calculateInterestBatch() calls a PL/SQL procedure that uses BULK COLLECT "
                    + "to fetch all eligible accounts into a PL/SQL collection, then FORALL to "
                    + "post interest in a single bulk DML operation",
        complexity = MigrationComplexity.HIGH,
        automationLevel = AutomationLevel.PARTIAL,
        migrationTarget = "Replace PL/SQL FORALL with JDBC executeBatch() -- Java-side batch; "
                        + "replace BULK COLLECT with standard JDBC ResultSet iteration"
    )
    public BatchResultDTO runInterestCalculation(LocalDate businessDate) {
        if (businessDate == null) {
            throw new MeridianBankException("INVALID_INPUT", "Business date must not be null", 400);
        }
        log.info("Starting interest calculation batch for {} via Oracle MERGE + FORALL/BULK COLLECT",
                 businessDate);
        long startTime = System.currentTimeMillis();
        try {
            // ORACLE-DEP [EMBEDDED_SQL:MERGE_STATEMENT] calculateInterestBatch uses MERGE
            // ORACLE-DEP [EMBEDDED_SQL:BULK_OPERATIONS] called proc uses FORALL / BULK COLLECT
            int rows = batchProcessingDAO.calculateInterestBatch(businessDate);
            long elapsedMs = System.currentTimeMillis() - startTime;
            log.info("Interest calculation complete: {} accounts updated in {} ms", rows, elapsedMs);

            BatchResultDTO result = new BatchResultDTO();
            result.setJobName("INTEREST_CALCULATION");
            // businessDate set via jobType/jobName fields
            result.setRecordsProcessed((long) rows);
            result.setRecordsSucceeded((long) rows);
            result.setRecordsFailed((long) 0);
            result.setDurationMs(elapsedMs);
            result.setStatus("COMPLETED");
            return result;
        } catch (Exception e) {
            log.error("Interest calculation failed for {}: {}", businessDate, e.getMessage(), e);
            if (e.getCause() instanceof SQLException sqlEx) {
                throw oracleErrorTranslator.translateSQLException(sqlEx);
            }
            throw new MeridianBankException("INTEREST_CALC_FAILED",
                "Interest calculation failed: " + e.getMessage(), 500, e);
        }
    }

    // -------------------------------------------------------------------------
    // Bulk transaction processing
    // -------------------------------------------------------------------------

    /**
     * Processes a batch of transactions using Oracle array binding / FORALL.
     *
     * <p>The DAO uses OraclePreparedStatement.setExecuteBatch() and Oracle's
     * OraclePreparedStatement array binding to submit all transactions in a single
     * network round-trip.  The underlying PL/SQL uses FORALL for a bulk INSERT.
     *
     * @param transactions list of transactions to process
     * @return BatchResultDTO with success/failure counts
     * @throws MeridianBankException if the bulk operation fails
     */
    // ORACLE-DEP [EMBEDDED_SQL:BULK_OPERATIONS] FORALL array binding via OraclePreparedStatement
    @Transactional
    @OracleDependency(
        domain = DependencyDomain.EMBEDDED_SQL,
        category = "BULK_OPERATIONS",
        description = "Delegates to BatchProcessingDAO.processBatchTransactions() which uses "
                    + "OraclePreparedStatement.setExecuteBatch() and Oracle array binding to submit "
                    + "all transactions in a single round-trip, calling a PL/SQL FORALL bulk INSERT",
        complexity = MigrationComplexity.HIGH,
        automationLevel = AutomationLevel.PARTIAL,
        migrationTarget = "Replace OraclePreparedStatement array binding with standard JDBC "
                        + "PreparedStatement.addBatch() / executeBatch() against PostgreSQL; "
                        + "replace FORALL with JDBC batch for the INSERT statements"
    )
    public BatchResultDTO processBatchTransactions(List<TransactionDTO> transactions) {
        if (transactions == null || transactions.isEmpty()) {
            BatchResultDTO empty = new BatchResultDTO();
            empty.setJobName("BATCH_TRANSACTIONS");
            empty.setRecordsProcessed(0L);
            empty.setRecordsSucceeded(0L);
            empty.setRecordsFailed(0L);
            empty.setStatus("SKIPPED");
            return empty;
        }
        log.info("Processing batch of {} transactions via Oracle array binding / FORALL", transactions.size());
        long startTime = System.currentTimeMillis();
        try {
            // ORACLE-DEP [EMBEDDED_SQL:BULK_OPERATIONS] processBatchTransactions uses array binding + FORALL
            BatchResultDTO result = batchProcessingDAO.processBatchTransactions(transactions);
            long elapsedMs = System.currentTimeMillis() - startTime;
            result.setDurationMs(elapsedMs);
            log.info("Batch transaction processing complete: {}/{} succeeded in {} ms",
                     result.getRecordsSucceeded(), transactions.size(), elapsedMs);
            return result;
        } catch (Exception e) {
            log.error("Batch transaction processing failed: {}", e.getMessage(), e);
            if (e.getCause() instanceof SQLException sqlEx) {
                throw oracleErrorTranslator.translateSQLException(sqlEx);
            }
            throw new MeridianBankException("BATCH_TX_FAILED",
                "Batch transaction processing failed: " + e.getMessage(), 500, e);
        }
    }

    // -------------------------------------------------------------------------
    // Bulk status update using Oracle ARRAY type
    // -------------------------------------------------------------------------

    /**
     * Bulk-updates account statuses using an Oracle ARRAY type to pass all IDs
     * in a single parameter bind, eliminating N round-trips.
     *
     * <p>The DAO creates an {@code oracle.sql.ARRAY} of type NUMBER_TABLE_TYPE
     * and passes it to a stored procedure that uses the array in a FORALL UPDATE.
     * This is a common Oracle pattern for bulk operations where the ID set is
     * known in application code.
     *
     * @param accountIds list of account IDs to update
     * @param newStatus  the status to set on all accounts
     * @return number of accounts updated
     * @throws MeridianBankException if the Oracle ARRAY operation fails
     */
    // ORACLE-DEP [ORACLE_JAVA_API:ORACLE_ARRAY] Bulk status update via oracle.sql.ARRAY
    @Transactional
    @OracleDependency(
        domain = DependencyDomain.ORACLE_JAVA_API,
        category = "ORACLE_ARRAY",
        description = "Delegates to BatchProcessingDAO.bulkUpdateAccountStatuses() which creates an "
                    + "oracle.sql.ARRAY of type NUMBER_TABLE_TYPE to pass all account IDs as a single "
                    + "bind parameter to a stored procedure that uses FORALL UPDATE",
        complexity = MigrationComplexity.HIGH,
        automationLevel = AutomationLevel.PARTIAL,
        migrationTarget = "Replace oracle.sql.ARRAY + NUMBER_TABLE_TYPE with PostgreSQL unnest(): "
                        + "UPDATE accounts SET status = :status WHERE account_id = ANY(:ids) "
                        + "where :ids is a Long[] Java array bound via PreparedStatement.setArray()"
    )
    public int bulkUpdateAccountStatuses(List<Long> accountIds, String newStatus) {
        if (accountIds == null || accountIds.isEmpty()) {
            return 0;
        }
        if (newStatus == null || newStatus.isBlank()) {
            throw new MeridianBankException("INVALID_INPUT", "New status must not be blank", 400);
        }
        log.info("Bulk-updating status to '{}' for {} accounts via Oracle ARRAY type",
                 newStatus, accountIds.size());
        try {
            // ORACLE-DEP [ORACLE_JAVA_API:ORACLE_ARRAY] bulkUpdateAccountStatuses passes oracle.sql.ARRAY
            int updated = batchProcessingDAO.bulkUpdateAccountStatuses(accountIds, newStatus);
            log.info("Bulk status update complete: {} accounts updated", updated);
            return updated;
        } catch (Exception e) {
            log.error("Bulk account status update failed for {} accounts: {}",
                      accountIds.size(), e.getMessage(), e);
            if (e.getCause() instanceof SQLException sqlEx) {
                throw oracleErrorTranslator.translateSQLException(sqlEx);
            }
            throw new MeridianBankException("BULK_UPDATE_FAILED",
                "Bulk account status update failed: " + e.getMessage(), 500, e);
        }
    }

    // -------------------------------------------------------------------------
    // Parallel batch query
    // -------------------------------------------------------------------------

    /**
     * Runs a full-table parallel batch query using the Oracle PARALLEL hint.
     *
     * <p>The PARALLEL hint instructs the Oracle query optimizer to use parallel
     * execution with the specified degree of parallelism.  This is Oracle-specific
     * and has no direct equivalent in PostgreSQL (which uses cost-based auto-
     * parallelism controlled by max_parallel_workers_per_gather).
     *
     * @param fromDate include transactions from this date onwards
     * @return list of result maps from the parallel scan
     * @throws MeridianBankException if the parallel query fails
     */
    // ORACLE-DEP [EMBEDDED_SQL:PARALLEL_HINT] Full-table batch query with PARALLEL hint
    @Transactional(readOnly = true)
    @OracleDependency(
        domain = DependencyDomain.EMBEDDED_SQL,
        category = "PARALLEL_HINT",
        description = "Delegates to BatchProcessingDAO.runParallelBatchQuery() which contains "
                    + "/*+ PARALLEL(t, 4) PARALLEL(a, 4) */ in the SELECT to use 4-way parallel "
                    + "execution for full-table transaction aggregation",
        complexity = MigrationComplexity.MEDIUM,
        automationLevel = AutomationLevel.PARTIAL,
        migrationTarget = "Remove Oracle PARALLEL hint; configure PostgreSQL parallel query with "
                        + "max_parallel_workers_per_gather and SET parallel_tuple_cost / "
                        + "parallel_setup_cost; or use SET max_parallel_workers_per_gather = 4 "
                        + "at session level before the query"
    )
    public List<Map<String, Object>> runParallelReport(LocalDate fromDate) {
        if (fromDate == null) {
            throw new MeridianBankException("INVALID_INPUT", "From date must not be null", 400);
        }
        log.info("Running parallel batch report from {} via Oracle /*+ PARALLEL */ hint", fromDate);
        try {
            // ORACLE-DEP [EMBEDDED_SQL:PARALLEL_HINT] runParallelBatchQuery uses /*+ PARALLEL(t,4) */
            List<Map<String, Object>> rows = batchProcessingDAO.runParallelBatchQuery(fromDate);
            log.info("Parallel batch report returned {} rows", rows.size());
            return rows;
        } catch (Exception e) {
            log.error("Parallel batch report failed from {}: {}", fromDate, e.getMessage(), e);
            if (e.getCause() instanceof SQLException sqlEx) {
                throw oracleErrorTranslator.translateSQLException(sqlEx);
            }
            throw new MeridianBankException("PARALLEL_REPORT_FAILED",
                "Parallel report failed: " + e.getMessage(), 500, e);
        }
    }

    // -------------------------------------------------------------------------
    // MERGE -- transaction summary upsert
    // -------------------------------------------------------------------------

    /**
     * Merges daily transaction summaries using Oracle MERGE INTO.
     *
     * <p>Oracle MERGE is an ISO SQL:2003 construct but with Oracle-specific syntax
     * extensions.  The WHEN NOT MATCHED THEN INSERT / WHEN MATCHED THEN UPDATE
     * pattern is used here to upsert daily balance summaries without a preceding
     * existence check.
     *
     * @param date the business date whose summaries are being merged
     * @return number of rows inserted or updated
     * @throws MeridianBankException if the MERGE fails
     */
    // ORACLE-DEP [EMBEDDED_SQL:MERGE_STATEMENT] Transaction summary upsert via Oracle MERGE
    @Transactional
    @OracleDependency(
        domain = DependencyDomain.EMBEDDED_SQL,
        category = "MERGE_STATEMENT",
        description = "Delegates to BatchProcessingDAO.mergeTransactionSummaries() which executes "
                    + "MERGE INTO TRANSACTION_SUMMARIES tgt USING (...) src "
                    + "ON (tgt.account_id = src.account_id AND tgt.summary_date = src.summary_date) "
                    + "WHEN MATCHED THEN UPDATE ... WHEN NOT MATCHED THEN INSERT ...",
        complexity = MigrationComplexity.MEDIUM,
        automationLevel = AutomationLevel.PARTIAL,
        migrationTarget = "Replace Oracle MERGE with PostgreSQL: "
                        + "INSERT INTO transaction_summaries (...) "
                        + "SELECT ... ON CONFLICT (account_id, summary_date) "
                        + "DO UPDATE SET total_credits = EXCLUDED.total_credits, ..."
    )
    public int mergeTransactionSummaries(LocalDate date) {
        if (date == null) {
            throw new MeridianBankException("INVALID_INPUT", "Summary date must not be null", 400);
        }
        log.debug("Merging transaction summaries for {} via Oracle MERGE INTO", date);
        try {
            // ORACLE-DEP [EMBEDDED_SQL:MERGE_STATEMENT] mergeTransactionSummaries uses MERGE INTO
            int rows = batchProcessingDAO.mergeTransactionSummaries(date);
            log.debug("MERGE completed: {} rows affected for date {}", rows, date);
            return rows;
        } catch (Exception e) {
            log.error("Transaction summary MERGE failed for {}: {}", date, e.getMessage(), e);
            if (e.getCause() instanceof SQLException sqlEx) {
                throw oracleErrorTranslator.translateSQLException(sqlEx);
            }
            throw new MeridianBankException("MERGE_FAILED",
                "Transaction summary merge failed: " + e.getMessage(), 500, e);
        }
    }

    // -------------------------------------------------------------------------
    // ROWID-based update
    // -------------------------------------------------------------------------

    /**
     * Updates the balance for a specific account row by its Oracle ROWID.
     *
     * <p>Oracle ROWID is a physical row address -- the fastest possible single-row
     * access path, bypassing all index lookups.  ROWID-based updates are common
     * in Oracle batch processing to update rows previously identified by a range
     * scan.  PostgreSQL's analogous concept is ctid, but it is not recommended for
     * application use because ctid changes on VACUUM and MVCC updates.
     *
     * @param rowId   the Oracle ROWID string (e.g. "AAABvMAAEAAAAAgAAA")
     * @param balance the new balance to set
     * @return 1 if the update succeeded, 0 if the ROWID was not found
     * @throws MeridianBankException if the update fails
     */
    // ORACLE-DEP [EMBEDDED_SQL:ROWID] Single-row update by Oracle ROWID physical address
    @Transactional
    @OracleDependency(
        domain = DependencyDomain.EMBEDDED_SQL,
        category = "ROWID",
        description = "Delegates to BatchProcessingDAO.updateByRowId() which executes "
                    + "UPDATE accounts SET balance = :balance WHERE ROWID = :rowId -- "
                    + "using Oracle's physical row address for the most efficient update path",
        complexity = MigrationComplexity.HIGH,
        automationLevel = AutomationLevel.PARTIAL,
        migrationTarget = "Replace ROWID-based update with primary-key UPDATE in PostgreSQL; "
                        + "PostgreSQL ctid is not stable across VACUUM -- never use ctid in "
                        + "application SQL; maintain a primary key for all update paths"
    )
    public int updateByRowId(String rowId, BigDecimal balance) {
        if (rowId == null || rowId.isBlank()) {
            throw new MeridianBankException("INVALID_INPUT", "ROWID must not be blank", 400);
        }
        if (balance == null) {
            throw new MeridianBankException("INVALID_INPUT", "Balance must not be null", 400);
        }
        log.debug("Updating balance to {} for Oracle ROWID {}", balance, rowId);
        try {
            // ORACLE-DEP [EMBEDDED_SQL:ROWID] updateByRowId uses WHERE ROWID = :rowId
            int updated = batchProcessingDAO.updateByRowId(rowId, balance);
            if (updated == 0) {
                log.warn("No row found for Oracle ROWID {} -- row may have been migrated or deleted", rowId);
            }
            return updated;
        } catch (Exception e) {
            log.error("ROWID-based balance update failed for rowId {}: {}", rowId, e.getMessage(), e);
            if (e.getCause() instanceof SQLException sqlEx) {
                throw oracleErrorTranslator.translateSQLException(sqlEx);
            }
            throw new MeridianBankException("ROWID_UPDATE_FAILED",
                "ROWID-based update failed: " + e.getMessage(), 500, e);
        }
    }

    // -------------------------------------------------------------------------
    // Scheduled job management
    // -------------------------------------------------------------------------

    /**
     * Returns all scheduled jobs from the SCHEDULED_JOBS table.
     * Uses JPA repository -- no direct Oracle dependencies in this method,
     * though the entity and underlying schema use Oracle-specific column types
     * and the queries for job status use SYSDATE / TRUNC / NVL.
     *
     * @return list of all scheduled job entities
     */
    @Transactional(readOnly = true)
    public List<ScheduledJobEntity> getScheduledJobs() {
        log.debug("Fetching all scheduled jobs");
        return scheduledJobRepository.findAll();
    }
}
