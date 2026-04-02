package com.meridianbank.service;

import com.meridianbank.annotation.AutomationLevel;
import com.meridianbank.annotation.DependencyDomain;
import com.meridianbank.annotation.MigrationComplexity;
import com.meridianbank.annotation.OracleDependency;
import com.meridianbank.dao.AccountOracleDAO;
import com.meridianbank.dao.TransactionOracleDAO;
import com.meridianbank.dto.AccountDTO;
import com.meridianbank.dto.TransactionDTO;
import com.meridianbank.dto.TransferRequestDTO;
import com.meridianbank.entity.AccountEntity;
import com.meridianbank.exception.AccountNotFoundException;
import com.meridianbank.exception.DuplicateCustomerException;
import com.meridianbank.exception.InsufficientFundsException;
import com.meridianbank.exception.MeridianBankException;
import com.meridianbank.exception.OracleErrorTranslator;
import com.meridianbank.repository.AccountRepository;
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
import java.util.Optional;
import java.util.UUID;

/**
 * Service layer for Account operations.
 *
 * Orchestrates Spring Data JPA AccountRepository and Oracle-specific AccountOracleDAO /
 * TransactionOracleDAO. Exercises the following Oracle dependencies:
 *
 * ORACLE-DEP [PLSQL_INVOCATION:PACKAGE_CALL]   ACCOUNT_MGMT_PKG.CREATE_ACCOUNT via DAO
 * ORACLE-DEP [EMBEDDED_SQL:RETURNING_INTO]      INSERT RETURNING account_id in DAO
 * ORACLE-DEP [PLSQL_INVOCATION:REF_CURSOR]      OracleTypes.CURSOR OUT param for account list
 * ORACLE-DEP [ORACLE_JAVA_API:ARRAY_TYPE]       NUMBER_TABLE_TYPE for bulk status update
 * ORACLE-DEP [EMBEDDED_SQL:MERGE_STATEMENT]     MERGE INTO for balance reconciliation
 * ORACLE-DEP [EMBEDDED_SQL:SEQUENCE_CURRVAL]    account_seq.CURRVAL from DUAL
 * ORACLE-DEP [EMBEDDED_SQL:ORACLE_ERROR_CODES]  ORA-00001 unique constraint handling
 */
@Service
@Transactional
public class AccountService {

    private static final Logger log = LoggerFactory.getLogger(AccountService.class);

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private AccountOracleDAO accountOracleDAO;

    @Autowired
    private TransactionOracleDAO transactionOracleDAO;

    @Autowired
    private OracleErrorTranslator errorTranslator;

    // -----------------------------------------------------------------------
    // Account creation
    // -----------------------------------------------------------------------

    /**
     * Opens a new bank account by delegating to ACCOUNT_MGMT_PKG.CREATE_ACCOUNT.
     *
     * The DAO call uses:
     *  - CallableStatement with OracleTypes.NUMBER as OUT parameter for returned account ID
     *  - INSERT ... RETURNING account_id INTO ? inside the package procedure
     *  - Oracle sequence account_seq.NEXTVAL for ID generation
     *
     * ORA-00001 (ACCOUNTS_NUMBER_UK) is translated to a domain exception.
     */
    @OracleDependency(
        domain = DependencyDomain.PLSQL_INVOCATION,
        category = "PACKAGE_CALL",
        description = "Account creation via ACCOUNT_MGMT_PKG.CREATE_ACCOUNT with OracleTypes.NUMBER OUT param",
        complexity = MigrationComplexity.HIGH,
        automationLevel = AutomationLevel.PARTIAL,
        migrationTarget = "Replace Oracle package call with a direct INSERT and PostgreSQL RETURNING clause"
    )
    @OracleDependency(
        domain = DependencyDomain.EMBEDDED_SQL,
        category = "SEQUENCE_NEXTVAL",
        description = "Account number generated using account_seq.NEXTVAL inside the ACCOUNT_MGMT_PKG procedure",
        complexity = MigrationComplexity.LOW,
        automationLevel = AutomationLevel.FULL,
        migrationTarget = "Replace Oracle sequence with PostgreSQL SEQUENCE or IDENTITY column"
    )
    public AccountDTO openAccount(AccountDTO dto) {
        log.info("Opening {} account for customer {}", dto.getAccountType(), dto.getCustomerId());

        validateAccountType(dto.getAccountType());

        if (dto.getCustomerId() == null) {
            throw new MeridianBankException("VALIDATION_ERROR",
                "Customer ID is required to open an account.", 400);
        }

        // Set defaults
        if (dto.getInitialDeposit() == null) {
            dto.setInitialDeposit(BigDecimal.ZERO);
        }
        if (dto.getOverdraftLimit() == null) {
            dto.setOverdraftLimit(BigDecimal.ZERO);
        }

        // ORACLE-DEP [PLSQL_INVOCATION:PACKAGE_CALL] Calls ACCOUNT_MGMT_PKG.CREATE_ACCOUNT;
        // DAO registers OracleTypes.CURSOR OUT param and OracleTypes.NUMBER OUT param for new ID
        Long newAccountId;
        try {
            newAccountId = accountOracleDAO.createAccount(dto);
        } catch (SQLException e) {
            // ORACLE-DEP [EMBEDDED_SQL:ORACLE_ERROR_CODES] ORA-00001 → duplicate account number
            throw errorTranslator.translateSQLException(e);
        }

        log.info("Account {} opened with ID {}", dto.getAccountType(), newAccountId);

        dto.setAccountId(newAccountId);
        dto.setStatus("ACTIVE");
        dto.setOpenDate(LocalDate.now());
        dto.setBalance(dto.getInitialDeposit());
        return dto;
    }

    // -----------------------------------------------------------------------
    // Retrieval
    // -----------------------------------------------------------------------

    /**
     * Returns an account by its primary key.
     */
    @Transactional(readOnly = true)
    public AccountDTO getAccountById(Long accountId) {
        AccountEntity entity = accountRepository.findById(accountId)
            .orElseThrow(() -> new AccountNotFoundException(accountId));
        return entityToDto(entity);
    }

    /**
     * Returns an account by its account number string.
     */
    @Transactional(readOnly = true)
    public AccountDTO getAccountByNumber(String accountNumber) {
        AccountEntity entity = accountRepository.findByAccountNumber(accountNumber)
            .orElseThrow(() -> new AccountNotFoundException(accountNumber));
        return entityToDto(entity);
    }

    /**
     * Returns accounts for a customer — combines JPA findByCustomerId with
     * the Oracle-specific paginated DAO method that uses ROWNUM double-nesting.
     */
    @OracleDependency(
        domain = DependencyDomain.EMBEDDED_SQL,
        category = "ROWNUM_PAGINATION",
        description = "Paginated account list uses Oracle ROWNUM double-nest via AccountOracleDAO.getAccountsForCustomer",
        complexity = MigrationComplexity.MEDIUM,
        automationLevel = AutomationLevel.FULL,
        migrationTarget = "Replace ROWNUM pagination with LIMIT/OFFSET in PostgreSQL or Spring Data Pageable"
    )
    @Transactional(readOnly = true)
    public List<AccountDTO> getAccountsByCustomer(Long customerId, int page, int size) {
        if (page <= 0) page = 1;
        if (size <= 0 || size > 100) size = 20;

        // ORACLE-DEP [EMBEDDED_SQL:ROWNUM_PAGINATION] Uses ROWNUM nested query in AccountOracleDAO
        try {
            return accountOracleDAO.getAccountsForCustomer(customerId, page, size);
        } catch (SQLException e) {
            throw errorTranslator.translateSQLException(e);
        }
    }

    /**
     * Returns accounts for a customer with NVL-applied effective overdraft limit.
     * The repository native query uses NVL(overdraft_limit, 0).
     */
    @OracleDependency(
        domain = DependencyDomain.EMBEDDED_SQL,
        category = "NVL_FUNCTION",
        description = "Account list query uses NVL(overdraft_limit,0) to default null overdraft to zero",
        complexity = MigrationComplexity.LOW,
        automationLevel = AutomationLevel.FULL,
        migrationTarget = "Replace NVL(overdraft_limit,0) with COALESCE(overdraft_limit,0)"
    )
    @Transactional(readOnly = true)
    public List<AccountDTO> getAccountsWithEffectiveOverdraft(Long customerId) {
        // ORACLE-DEP [EMBEDDED_SQL:NVL_FUNCTION] Native query: NVL(a.overdraft_limit, 0)
        List<Object[]> rows = accountRepository.findAccountsWithEffectiveOverdraft(customerId);
        List<AccountDTO> results = new ArrayList<>();
        for (Object[] row : rows) {
            AccountDTO dto = new AccountDTO();
            dto.setAccountId(toLong(row[0]));
            dto.setAccountNumber(toString(row[1]));
            dto.setAccountType(toString(row[2]));
            dto.setBalance(toBigDecimal(row[3]));
            dto.setStatus(toString(row[4]));
            // row[5] = effective_overdraft (NVL result)
            if (row.length > 5) {
                dto.setEffectiveOverdraftLimit(toBigDecimal(row[5]));
            }
            results.add(dto);
        }
        return results;
    }

    /**
     * Returns the available balance for an account via a stored procedure call.
     * The procedure returns the balance using an OracleTypes.NUMBER OUT parameter.
     */
    @OracleDependency(
        domain = DependencyDomain.PLSQL_INVOCATION,
        category = "ORACLE_TYPES_OUT",
        description = "Balance retrieved via ACCOUNT_MGMT_PKG.GET_BALANCE with OracleTypes.NUMBER as OUT parameter",
        complexity = MigrationComplexity.MEDIUM,
        automationLevel = AutomationLevel.PARTIAL,
        migrationTarget = "Replace stored procedure OUT param with a direct SELECT balance FROM accounts WHERE account_id=?"
    )
    @Transactional(readOnly = true)
    public BigDecimal getBalance(Long accountId) {
        // ORACLE-DEP [PLSQL_INVOCATION:ORACLE_TYPES_OUT] AccountOracleDAO.getBalance registers
        // OracleTypes.NUMBER as CALL OUT parameter
        try {
            return accountOracleDAO.getBalance(accountId);
        } catch (SQLException e) {
            throw errorTranslator.translateSQLException(e);
        }
    }

    // -----------------------------------------------------------------------
    // Transfer funds
    // -----------------------------------------------------------------------

    /**
     * Transfers funds between two accounts.
     *
     * Process:
     *  1. Validate both accounts exist and the source is ACTIVE
     *  2. Check sufficient balance (balance + overdraftLimit) for the debit
     *  3. Record a DEBIT transaction on the source account via TransactionOracleDAO
     *     (uses RETURNING INTO for generated transaction ID)
     *  4. Record a CREDIT transaction on the destination account
     *  5. Update balances via JPA save
     *
     * Oracle dependencies: RETURNING INTO for transaction IDs, Oracle date arithmetic
     * for settlement date (transactionDate + 2), ORA-00001 on duplicate reference number.
     */
    @OracleDependency(
        domain = DependencyDomain.EMBEDDED_SQL,
        category = "RETURNING_INTO",
        description = "Transfer records two transactions via TransactionOracleDAO which uses INSERT...RETURNING transaction_id",
        complexity = MigrationComplexity.MEDIUM,
        automationLevel = AutomationLevel.PARTIAL,
        migrationTarget = "Replace RETURNING INTO :var with INSERT...RETURNING column in PostgreSQL (different bind pattern)"
    )
    @OracleDependency(
        domain = DependencyDomain.EMBEDDED_SQL,
        category = "DATE_ARITHMETIC",
        description = "Settlement date computed as transactionDate + 2 days — Oracle date + integer arithmetic",
        complexity = MigrationComplexity.LOW,
        automationLevel = AutomationLevel.FULL,
        migrationTarget = "PostgreSQL: date + interval '2 days' or date + 2 (also valid in PG for date + integer)"
    )
    public TransferRequestDTO transferFunds(TransferRequestDTO request) {
        log.info("Transfer {} from account {} to account {}",
            request.getAmount(), request.getFromAccountId(), request.getToAccountId());

        if (request.getAmount() == null || request.getAmount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new MeridianBankException("VALIDATION_ERROR",
                "Transfer amount must be positive.", 400);
        }

        // Load both accounts
        AccountEntity source = accountRepository.findById(request.getFromAccountId())
            .orElseThrow(() -> new AccountNotFoundException(request.getFromAccountId()));
        AccountEntity dest = accountRepository.findById(request.getToAccountId())
            .orElseThrow(() -> new AccountNotFoundException(request.getToAccountId()));

        if (!"ACTIVE".equals(source.getStatus())) {
            throw new MeridianBankException("ACCOUNT_NOT_ACTIVE",
                "Source account is not active. Current status: " + source.getStatus(), 422);
        }
        if (!"ACTIVE".equals(dest.getStatus())) {
            throw new MeridianBankException("ACCOUNT_NOT_ACTIVE",
                "Destination account is not active. Current status: " + dest.getStatus(), 422);
        }

        // Effective available balance = balance + overdraftLimit (NVL(overdraftLimit,0))
        // ORACLE-DEP [EMBEDDED_SQL:NVL_FUNCTION] Mirrors Oracle NVL(overdraft_limit,0) logic
        BigDecimal overdraft = source.getOverdraftLimit() != null
            ? source.getOverdraftLimit() : BigDecimal.ZERO;
        BigDecimal available = source.getBalance().add(overdraft);

        if (request.getAmount().compareTo(available) > 0) {
            throw new InsufficientFundsException(
                source.getAccountId(), request.getAmount(), available);
        }

        // Build debit transaction DTO
        String referenceNumber = generateReferenceNumber();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

        // ORACLE-DEP [EMBEDDED_SQL:DATE_ARITHMETIC] Settlement date = transactionDate + 2;
        // Oracle: date_col + integer adds that many days. Mirrored here in Java.
        OffsetDateTime settlementDate = now.plusDays(2);

        TransactionDTO debit = new TransactionDTO();
        debit.setAccountId(source.getAccountId());
        debit.setAmount(request.getAmount().negate());
        debit.setTransactionType("DEBIT");
        debit.setDescription("Transfer to account " + dest.getAccountNumber()
            + (request.getDescription() != null ? ": " + request.getDescription() : ""));
        debit.setStatus("PENDING");
        debit.setReferenceNumber(referenceNumber);
        debit.setTransactionDate(now);
        debit.setProcessedDate(settlementDate);
        debit.setCounterpartyAccountId(dest.getAccountId());
        debit.setCategoryCode("TRANSFER");

        TransactionDTO credit = new TransactionDTO();
        credit.setAccountId(dest.getAccountId());
        credit.setAmount(request.getAmount());
        credit.setTransactionType("CREDIT");
        credit.setDescription("Transfer from account " + source.getAccountNumber()
            + (request.getDescription() != null ? ": " + request.getDescription() : ""));
        credit.setStatus("PENDING");
        credit.setReferenceNumber(referenceNumber + "-CR");
        credit.setTransactionDate(now);
        credit.setProcessedDate(settlementDate);
        credit.setCounterpartyAccountId(source.getAccountId());
        credit.setCategoryCode("TRANSFER");

        // ORACLE-DEP [EMBEDDED_SQL:RETURNING_INTO] TransactionOracleDAO.recordTransaction uses
        // INSERT INTO transactions ... RETURNING transaction_id INTO ?
        Long debitTxnId;
        Long creditTxnId;
        try {
            debitTxnId = transactionOracleDAO.recordTransaction(debit);
            creditTxnId = transactionOracleDAO.recordTransaction(credit);
        } catch (SQLException e) {
            throw errorTranslator.translateSQLException(e);
        }

        // Update balances
        source.setBalance(source.getBalance().subtract(request.getAmount()));
        dest.setBalance(dest.getBalance().add(request.getAmount()));
        accountRepository.save(source);
        accountRepository.save(dest);

        log.info("Transfer complete: debitTxn={} creditTxn={}", debitTxnId, creditTxnId);

        // Build response
        request.setTransactionId(debitTxnId);
        request.setReferenceNumber(referenceNumber);
        request.setStatus("COMPLETED");
        request.setFromAccountBalance(source.getBalance());
        request.setToAccountBalance(dest.getBalance());
        return request;
    }

    // -----------------------------------------------------------------------
    // Status and reconciliation
    // -----------------------------------------------------------------------

    /**
     * Updates the status of an account (ACTIVE, INACTIVE, FROZEN, CLOSED).
     */
    public AccountDTO updateAccountStatus(Long accountId, String newStatus) {
        AccountEntity entity = accountRepository.findById(accountId)
            .orElseThrow(() -> new AccountNotFoundException(accountId));

        validateAccountStatus(newStatus);
        entity.setStatus(newStatus);
        AccountEntity saved = accountRepository.save(entity);
        return entityToDto(saved);
    }

    /**
     * Reconciles the balance for an account using an Oracle MERGE statement.
     * The DAO executes:
     *   MERGE INTO accounts tgt
     *   USING (SELECT :accountId id, :balance bal FROM DUAL) src
     *   ON (tgt.account_id = src.id)
     *   WHEN MATCHED THEN UPDATE SET tgt.balance = src.bal
     *
     * The SELECT ... FROM DUAL is another Oracle dependency here.
     */
    @OracleDependency(
        domain = DependencyDomain.EMBEDDED_SQL,
        category = "MERGE_STATEMENT",
        description = "Balance reconciliation uses Oracle MERGE INTO...USING (SELECT...FROM DUAL) for upsert",
        complexity = MigrationComplexity.MEDIUM,
        automationLevel = AutomationLevel.PARTIAL,
        migrationTarget = "Replace with PostgreSQL INSERT...ON CONFLICT DO UPDATE or a plain UPDATE"
    )
    @OracleDependency(
        domain = DependencyDomain.EMBEDDED_SQL,
        category = "DUAL_TABLE",
        description = "MERGE source uses SELECT :v FROM DUAL — Oracle DUAL single-row virtual table",
        complexity = MigrationComplexity.LOW,
        automationLevel = AutomationLevel.FULL,
        migrationTarget = "Remove FROM DUAL; PostgreSQL allows bare SELECT :v"
    )
    public void reconcileBalance(Long accountId, BigDecimal newBalance) {
        if (newBalance == null || newBalance.compareTo(BigDecimal.ZERO) < 0) {
            throw new MeridianBankException("VALIDATION_ERROR",
                "Reconciled balance cannot be null or negative.", 400);
        }
        // ORACLE-DEP [EMBEDDED_SQL:MERGE_STATEMENT] AccountOracleDAO.reconcileBalance runs MERGE INTO
        try {
            accountOracleDAO.reconcileBalance(accountId, newBalance);
        } catch (SQLException e) {
            throw errorTranslator.translateSQLException(e);
        }
        log.info("Balance reconciled for account {} to {}", accountId, newBalance);
    }

    /**
     * Bulk-updates the status of multiple accounts in one Oracle ARRAY-bound call.
     */
    @OracleDependency(
        domain = DependencyDomain.ORACLE_JAVA_API,
        category = "ARRAY_TYPE",
        description = "Bulk account status update passes IDs as NUMBER_TABLE_TYPE Oracle ARRAY via AccountOracleDAO",
        complexity = MigrationComplexity.HIGH,
        automationLevel = AutomationLevel.PARTIAL,
        migrationTarget = "Replace Oracle ARRAY with unnest(:ids::bigint[]) in PostgreSQL batch statement"
    )
    public int bulkUpdateAccountStatuses(List<Long> accountIds, String newStatus) {
        if (accountIds == null || accountIds.isEmpty()) return 0;
        validateAccountStatus(newStatus);

        // ORACLE-DEP [ORACLE_JAVA_API:ARRAY_TYPE] AccountOracleDAO.bulkUpdateStatus uses
        // oracle.sql.ARRAY + ArrayDescriptor for NUMBER_TABLE_TYPE binding
        try {
            return accountOracleDAO.bulkUpdateStatus(accountIds, newStatus);
        } catch (SQLException e) {
            throw errorTranslator.translateSQLException(e);
        }
    }

    // -----------------------------------------------------------------------
    // Sequence / metadata
    // -----------------------------------------------------------------------

    /**
     * Returns the current value of the Oracle account sequence.
     * Executes:  SELECT MERIDIAN.ACCOUNT_SEQ.CURRVAL FROM DUAL
     *
     * CURRVAL is only valid within the same session after a NEXTVAL call;
     * this method is for diagnostic/reporting purposes only.
     */
    @OracleDependency(
        domain = DependencyDomain.EMBEDDED_SQL,
        category = "SEQUENCE_CURRVAL",
        description = "Reads account_seq.CURRVAL from DUAL — Oracle sequence current value for diagnostic use",
        complexity = MigrationComplexity.LOW,
        automationLevel = AutomationLevel.FULL,
        migrationTarget = "Replace with SELECT currval('account_seq') in PostgreSQL"
    )
    @Transactional(readOnly = true)
    public Long getCurrentSequenceValue() {
        // ORACLE-DEP [EMBEDDED_SQL:SEQUENCE_CURRVAL] SELECT MERIDIAN.ACCOUNT_SEQ.CURRVAL FROM DUAL
        return accountRepository.getCurrentSequenceValue();
    }

    /**
     * Returns accounts ordered by type using Oracle DECODE for sort priority.
     * The native query uses DECODE(account_type,'CHECKING',1,'SAVINGS',2,'MONEY_MARKET',3,4).
     */
    @OracleDependency(
        domain = DependencyDomain.EMBEDDED_SQL,
        category = "DECODE_FUNCTION",
        description = "Accounts ordered by type priority using Oracle DECODE(account_type,'CHECKING',1,'SAVINGS',2,...)",
        complexity = MigrationComplexity.LOW,
        automationLevel = AutomationLevel.FULL,
        migrationTarget = "Replace DECODE with CASE WHEN account_type='CHECKING' THEN 1 WHEN ... END in PostgreSQL"
    )
    @Transactional(readOnly = true)
    public List<AccountDTO> getAccountsOrderedByType() {
        // ORACLE-DEP [EMBEDDED_SQL:DECODE_FUNCTION] Native query uses DECODE for ordering
        List<AccountEntity> entities = accountRepository.findAllOrderedByType();
        List<AccountDTO> results = new ArrayList<>();
        for (AccountEntity e : entities) {
            results.add(entityToDto(e));
        }
        return results;
    }

    /**
     * Returns recent transactions for an account going back the specified number of days.
     * Delegates to TransactionOracleDAO which uses an INDEX hint and Oracle date arithmetic.
     */
    @OracleDependency(
        domain = DependencyDomain.EMBEDDED_SQL,
        category = "OPTIMIZER_HINTS",
        description = "Recent transaction query uses /*+ INDEX(t TXN_ACCOUNT_DATE_IDX) */ hint in TransactionOracleDAO",
        complexity = MigrationComplexity.LOW,
        automationLevel = AutomationLevel.PARTIAL,
        migrationTarget = "Remove Oracle hints; PostgreSQL planner handles index selection automatically"
    )
    @Transactional(readOnly = true)
    public List<TransactionDTO> getAccountTransactions(Long accountId, int daysBack) {
        if (daysBack <= 0 || daysBack > 365) daysBack = 30;
        // ORACLE-DEP [EMBEDDED_SQL:OPTIMIZER_HINTS] TransactionOracleDAO.findRecentTransactions
        // uses /*+ INDEX(t TXN_ACCOUNT_DATE_IDX) */ for performance on the transactions table
        try {
            return transactionOracleDAO.findRecentTransactions(accountId, daysBack);
        } catch (SQLException e) {
            throw errorTranslator.translateSQLException(e);
        }
    }

    /**
     * Returns the monthly account summary using Oracle analytic functions.
     * The repository native query uses TO_CHAR(transaction_date,'YYYY-MM') and SUM() window.
     */
    @OracleDependency(
        domain = DependencyDomain.EMBEDDED_SQL,
        category = "TO_CHAR_FUNCTION",
        description = "Monthly summary query uses TO_CHAR(transaction_date,'YYYY-MM') for grouping periods",
        complexity = MigrationComplexity.LOW,
        automationLevel = AutomationLevel.FULL,
        migrationTarget = "Replace TO_CHAR(date,'YYYY-MM') with TO_CHAR(date,'YYYY-MM') — same syntax in PostgreSQL"
    )
    @Transactional(readOnly = true)
    public List<Object[]> getMonthlyAccountSummary() {
        // ORACLE-DEP [EMBEDDED_SQL:TO_CHAR_FUNCTION] Native query uses TO_CHAR for month grouping
        return accountRepository.findMonthlyAccountSummary();
    }

    /**
     * Returns portfolio allocation for a customer using Oracle RATIO_TO_REPORT analytic.
     */
    @OracleDependency(
        domain = DependencyDomain.EMBEDDED_SQL,
        category = "RATIO_TO_REPORT",
        description = "Portfolio allocation uses Oracle RATIO_TO_REPORT(balance) OVER () analytic function",
        complexity = MigrationComplexity.MEDIUM,
        automationLevel = AutomationLevel.PARTIAL,
        migrationTarget = "Replace RATIO_TO_REPORT with balance / SUM(balance) OVER () in PostgreSQL"
    )
    @Transactional(readOnly = true)
    public List<Object[]> getPortfolioAllocation(Long customerId) {
        // ORACLE-DEP [EMBEDDED_SQL:RATIO_TO_REPORT] Native query: RATIO_TO_REPORT(balance) OVER (PARTITION BY customer_id)
        return accountRepository.findAccountsWithPortfolioAllocation(customerId);
    }

    // -----------------------------------------------------------------------
    // Private helpers
    // -----------------------------------------------------------------------

    private AccountDTO entityToDto(AccountEntity entity) {
        AccountDTO dto = new AccountDTO();
        dto.setAccountId(entity.getAccountId());
        dto.setAccountNumber(entity.getAccountNumber());
        dto.setAccountType(entity.getAccountType());
        dto.setBalance(entity.getBalance());
        dto.setOverdraftLimit(entity.getOverdraftLimit());
        dto.setInterestRate(entity.getInterestRate());
        dto.setStatus(entity.getStatus());
        dto.setOpenDate(entity.getOpenDate());
        dto.setCustomerId(entity.getCustomerId());
        dto.setBranchId(entity.getBranchId());
        dto.setCreatedDate(entity.getCreatedDate() != null ? entity.getCreatedDate().atStartOfDay() : null);
        // Compute available balance = balance + NVL(overdraftLimit,0)
        BigDecimal overdraft = entity.getOverdraftLimit() != null
            ? entity.getOverdraftLimit() : BigDecimal.ZERO;
        dto.setAvailableBalance(entity.getBalance().add(overdraft));
        return dto;
    }

    private void validateAccountType(String accountType) {
        if (accountType == null) {
            throw new MeridianBankException("VALIDATION_ERROR",
                "Account type is required.", 400);
        }
        switch (accountType.toUpperCase()) {
            case "CHECKING", "SAVINGS", "MONEY_MARKET", "CD" -> { /* valid */ }
            default -> throw new MeridianBankException("VALIDATION_ERROR",
                "Invalid account type: " + accountType
                    + ". Must be CHECKING, SAVINGS, MONEY_MARKET, or CD.", 400);
        }
    }

    private void validateAccountStatus(String status) {
        switch (status != null ? status.toUpperCase() : "") {
            case "ACTIVE", "INACTIVE", "FROZEN", "CLOSED" -> { /* valid */ }
            default -> throw new MeridianBankException("VALIDATION_ERROR",
                "Invalid account status: " + status, 400);
        }
    }

    private String generateReferenceNumber() {
        return "TXN-" + System.currentTimeMillis() + "-"
            + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }

    private Long toLong(Object o) {
        if (o == null) return null;
        if (o instanceof Number n) return n.longValue();
        return Long.parseLong(o.toString());
    }

    private BigDecimal toBigDecimal(Object o) {
        if (o == null) return null;
        if (o instanceof BigDecimal bd) return bd;
        if (o instanceof Number n) return BigDecimal.valueOf(n.doubleValue());
        return new BigDecimal(o.toString());
    }

    private String toString(Object o) {
        return o == null ? null : o.toString();
    }
}
