package com.meridianbank.repository;

import com.meridianbank.annotation.*;
import com.meridianbank.entity.AccountEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/**
 * Spring Data JPA repository for AccountEntity.
 * Demonstrates Oracle-specific SQL patterns including ROWNUM pagination,
 * DECODE ordering, MERGE upsert, sequence CURRVAL, and query hints.
 */
@Repository
public interface AccountRepository extends JpaRepository<AccountEntity, Long> {

    // Standard JPQL — no Oracle dependency
    Optional<AccountEntity> findByAccountNumber(String accountNumber);
    List<AccountEntity> findByCustomerId(Long customerId);
    List<AccountEntity> findByStatus(String status);
    List<AccountEntity> findByCustomerIdAndStatus(Long customerId, String status);

    // ORACLE-DEP [EMBEDDED_SQL:NVL_FUNCTION] NVL for effective overdraft limit
    @OracleDependency(
        domain = DependencyDomain.EMBEDDED_SQL,
        category = "NVL_FUNCTION",
        description = "NVL(overdraft_limit, 0) provides zero default when overdraft limit is null",
        complexity = MigrationComplexity.LOW,
        automationLevel = AutomationLevel.FULL,
        migrationTarget = "Replace NVL(overdraft_limit, 0) with COALESCE(overdraft_limit, 0) in PostgreSQL"
    )
    @Query(value =
        "SELECT a.*, NVL(a.overdraft_limit, 0) AS effective_overdraft " +
        "FROM MERIDIAN.ACCOUNTS a WHERE a.customer_id = :customerId",
        nativeQuery = true)
    List<Object[]> findAccountsWithEffectiveOverdraft(@Param("customerId") Long customerId);

    // ORACLE-DEP [EMBEDDED_SQL:ROWNUM] Pre-12c ROWNUM pagination pattern
    @OracleDependency(
        domain = DependencyDomain.EMBEDDED_SQL,
        category = "ROWNUM",
        description = "Double-nested ROWNUM subquery for pre-12c Oracle offset/limit pagination",
        complexity = MigrationComplexity.MEDIUM,
        automationLevel = AutomationLevel.FULL,
        migrationTarget = "Replace with LIMIT/OFFSET or FETCH FIRST n ROWS ONLY in PostgreSQL"
    )
    @Query(value =
        "SELECT * FROM (" +
        "  SELECT a.*, ROWNUM AS rn FROM MERIDIAN.ACCOUNTS a " +
        "  WHERE a.status = :status ORDER BY a.balance DESC" +
        ") WHERE rn BETWEEN :startRow AND :endRow",
        nativeQuery = true)
    List<AccountEntity> findByStatusPaginated(@Param("status") String status,
                                               @Param("startRow") int startRow,
                                               @Param("endRow") int endRow);

    // ORACLE-DEP [EMBEDDED_SQL:DECODE_FUNCTION] DECODE for type-based custom ordering
    @OracleDependency(
        domain = DependencyDomain.EMBEDDED_SQL,
        category = "DECODE_FUNCTION",
        description = "DECODE assigns integer sort ordinal to account_type values for custom ordering",
        complexity = MigrationComplexity.LOW,
        automationLevel = AutomationLevel.FULL,
        migrationTarget = "Replace DECODE(account_type,...) with CASE WHEN account_type=... THEN ... END in PostgreSQL"
    )
    @Query(value =
        "SELECT * FROM MERIDIAN.ACCOUNTS " +
        "ORDER BY DECODE(account_type," +
        "  'CHECKING',1,'SAVINGS',2,'MONEY_MARKET',3,'CD',4,'IRA',5, 6)",
        nativeQuery = true)
    List<AccountEntity> findAllOrderedByType();

    // ORACLE-DEP [EMBEDDED_SQL:SEQUENCE] [EMBEDDED_SQL:DUAL_TABLE] CURRVAL from sequence
    @OracleDependency(
        domain = DependencyDomain.EMBEDDED_SQL,
        category = "SEQUENCE",
        description = "SELECT MERIDIAN.ACCOUNT_SEQ.CURRVAL FROM DUAL retrieves last-generated sequence value",
        complexity = MigrationComplexity.LOW,
        automationLevel = AutomationLevel.FULL,
        migrationTarget = "Replace with SELECT currval('meridian.account_seq') in PostgreSQL — no FROM DUAL needed"
    )
    @Query(value = "SELECT MERIDIAN.ACCOUNT_SEQ.CURRVAL FROM DUAL", nativeQuery = true)
    Long getCurrentSequenceValue();

    // ORACLE-DEP [EMBEDDED_SQL:MERGE_STATEMENT] MERGE for balance upsert
    @OracleDependency(
        domain = DependencyDomain.EMBEDDED_SQL,
        category = "MERGE_STATEMENT",
        description = "MERGE INTO updates existing balance or inserts — Oracle upsert pattern",
        complexity = MigrationComplexity.MEDIUM,
        automationLevel = AutomationLevel.PARTIAL,
        migrationTarget = "Replace with INSERT ... ON CONFLICT (account_id) DO UPDATE SET balance = EXCLUDED.balance in PostgreSQL"
    )
    @Modifying
    @Transactional
    @Query(value =
        "MERGE INTO MERIDIAN.ACCOUNTS tgt " +
        "USING (SELECT :accountId AS id, :balance AS bal FROM DUAL) src " +
        "ON (tgt.account_id = src.id) " +
        "WHEN MATCHED THEN UPDATE SET tgt.balance = src.bal, tgt.updated_date = SYSDATE " +
        "WHEN NOT MATCHED THEN INSERT (account_id, balance) VALUES (src.id, src.bal)",
        nativeQuery = true)
    void upsertAccountBalance(@Param("accountId") Long accountId,
                               @Param("balance") BigDecimal balance);

    // ORACLE-DEP [EMBEDDED_SQL:QUERY_HINT] INDEX hint for customer lookup
    @OracleDependency(
        domain = DependencyDomain.EMBEDDED_SQL,
        category = "QUERY_HINT",
        description = "/*+ INDEX(a ACCOUNTS_CUSTOMER_IDX) */ forces use of customer index",
        complexity = MigrationComplexity.MEDIUM,
        automationLevel = AutomationLevel.PARTIAL,
        migrationTarget = "Remove Oracle hint; add CREATE INDEX if missing; use pg_hint_plan for PostgreSQL hints"
    )
    @Query(value =
        "SELECT /*+ INDEX(a ACCOUNTS_CUSTOMER_IDX) */ * " +
        "FROM MERIDIAN.ACCOUNTS a " +
        "WHERE a.customer_id = :customerId AND a.status = 'ACTIVE' " +
        "ORDER BY a.open_date DESC",
        nativeQuery = true)
    List<AccountEntity> findActiveByCustomerWithHint(@Param("customerId") Long customerId);

    // ORACLE-DEP [EMBEDDED_SQL:NVL2_FUNCTION] NVL2 for open/closed status
    @OracleDependency(
        domain = DependencyDomain.EMBEDDED_SQL,
        category = "NVL2_FUNCTION",
        description = "NVL2(close_date,'CLOSED','OPEN') derives open/closed status from close_date nullability",
        complexity = MigrationComplexity.LOW,
        automationLevel = AutomationLevel.FULL,
        migrationTarget = "Replace with CASE WHEN close_date IS NOT NULL THEN 'CLOSED' ELSE 'OPEN' END in PostgreSQL"
    )
    @Query(value =
        "SELECT account_id, account_number, account_type, balance, " +
        "  NVL2(close_date, 'CLOSED', 'OPEN') AS open_status, " +
        "  NVL(overdraft_limit, 0) AS effective_overdraft " +
        "FROM MERIDIAN.ACCOUNTS WHERE customer_id = :customerId",
        nativeQuery = true)
    List<Object[]> findAccountStatusSummary(@Param("customerId") Long customerId);

    // ORACLE-DEP [EMBEDDED_SQL:ADD_MONTHS] ADD_MONTHS for anniversary dates
    @OracleDependency(
        domain = DependencyDomain.EMBEDDED_SQL,
        category = "ADD_MONTHS",
        description = "ADD_MONTHS(open_date, 12/60) computes anniversary dates for account rewards",
        complexity = MigrationComplexity.LOW,
        automationLevel = AutomationLevel.FULL,
        migrationTarget = "Replace ADD_MONTHS(date, n) with date + INTERVAL 'n months' in PostgreSQL"
    )
    @Query(value =
        "SELECT account_id, account_number, open_date, " +
        "  ADD_MONTHS(open_date, 12) AS one_year_anniversary, " +
        "  ADD_MONTHS(open_date, 60) AS five_year_anniversary " +
        "FROM MERIDIAN.ACCOUNTS WHERE customer_id = :customerId",
        nativeQuery = true)
    List<Object[]> findAccountAnniversaryDates(@Param("customerId") Long customerId);

    // ORACLE-DEP [EMBEDDED_SQL:TRUNC_DATE] TRUNC for month-level grouping
    @OracleDependency(
        domain = DependencyDomain.EMBEDDED_SQL,
        category = "TRUNC_DATE",
        description = "TRUNC(created_date,'MM') truncates to first day of month for monthly grouping",
        complexity = MigrationComplexity.LOW,
        automationLevel = AutomationLevel.FULL,
        migrationTarget = "Replace TRUNC(date,'MM') with DATE_TRUNC('month', date) in PostgreSQL"
    )
    @Query(value =
        "SELECT TRUNC(created_date, 'MM') AS month_start, " +
        "  account_type, COUNT(*) AS account_count, " +
        "  SUM(balance) AS total_balance " +
        "FROM MERIDIAN.ACCOUNTS " +
        "WHERE created_date >= ADD_MONTHS(SYSDATE, -12) " +
        "GROUP BY TRUNC(created_date, 'MM'), account_type " +
        "ORDER BY month_start DESC",
        nativeQuery = true)
    List<Object[]> findMonthlyAccountSummary();

    // ORACLE-DEP [EMBEDDED_SQL:ANALYTIC_FUNCTION] Analytic SUM for portfolio allocation
    @OracleDependency(
        domain = DependencyDomain.EMBEDDED_SQL,
        category = "ANALYTIC_FUNCTION",
        description = "SUM(balance) OVER (PARTITION BY customer_id) computes total portfolio balance analytically",
        complexity = MigrationComplexity.LOW,
        automationLevel = AutomationLevel.FULL,
        migrationTarget = "SUM() OVER (PARTITION BY) is SQL standard — works in PostgreSQL without change"
    )
    @Query(value =
        "SELECT account_id, account_number, account_type, balance, " +
        "  SUM(balance) OVER (PARTITION BY customer_id) AS portfolio_total, " +
        "  ROUND(balance / SUM(balance) OVER (PARTITION BY customer_id) * 100, 2) AS portfolio_pct " +
        "FROM MERIDIAN.ACCOUNTS WHERE customer_id = :customerId AND status = 'ACTIVE'",
        nativeQuery = true)
    List<Object[]> findAccountsWithPortfolioAllocation(@Param("customerId") Long customerId);

    // ORACLE-DEP [EMBEDDED_SQL:SEQUENCE] NEXTVAL for next account ID preview
    @OracleDependency(
        domain = DependencyDomain.EMBEDDED_SQL,
        category = "SEQUENCE",
        description = "MERIDIAN.ACCOUNT_SEQ.NEXTVAL from DUAL retrieves next value from Oracle sequence",
        complexity = MigrationComplexity.LOW,
        automationLevel = AutomationLevel.FULL,
        migrationTarget = "Replace with nextval('meridian.account_seq') in PostgreSQL"
    )
    @Query(value = "SELECT MERIDIAN.ACCOUNT_SEQ.NEXTVAL FROM DUAL", nativeQuery = true)
    Long getNextSequenceValue();

    // ORACLE-DEP [EMBEDDED_SQL:SYSDATE] Balance query with SYSDATE for as-of timestamp
    @OracleDependency(
        domain = DependencyDomain.EMBEDDED_SQL,
        category = "SYSDATE",
        description = "SYSDATE included in balance snapshot query to record as-of time",
        complexity = MigrationComplexity.LOW,
        automationLevel = AutomationLevel.FULL,
        migrationTarget = "Replace SYSDATE with CURRENT_TIMESTAMP or NOW() in PostgreSQL"
    )
    @Query(value =
        "SELECT a.account_id, a.account_number, a.balance, " +
        "  NVL(a.overdraft_limit, 0) AS overdraft_limit, " +
        "  SYSDATE AS as_of_time " +
        "FROM MERIDIAN.ACCOUNTS a " +
        "WHERE a.account_id = :accountId",
        nativeQuery = true)
    Optional<Object[]> findBalanceSnapshot(@Param("accountId") Long accountId);
}
