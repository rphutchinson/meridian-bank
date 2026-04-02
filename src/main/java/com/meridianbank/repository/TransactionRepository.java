package com.meridianbank.repository;

import com.meridianbank.annotation.*;
import com.meridianbank.entity.TransactionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Spring Data JPA repository for TransactionEntity.
 * Demonstrates Oracle SQL constructs: TRUNC(date) BETWEEN, SYSDATE-n date arithmetic,
 * TO_CHAR/TO_DATE formatting, ROWNUM pagination, query hints, NVL, DECODE,
 * date+integer arithmetic, TO_NUMBER, and SYSTIMESTAMP.
 */
@Repository
public interface TransactionRepository extends JpaRepository<TransactionEntity, Long> {

    // Standard JPQL — no Oracle dependency
    List<TransactionEntity> findByAccountId(Long accountId);
    List<TransactionEntity> findByStatus(String status);
    Optional<TransactionEntity> findByReferenceNumber(String referenceNumber);
    List<TransactionEntity> findByAccountIdAndStatus(Long accountId, String status);

    // ORACLE-DEP [EMBEDDED_SQL:TRUNC_DATE] TRUNC for day-level date range query
    @OracleDependency(
        domain = DependencyDomain.EMBEDDED_SQL,
        category = "TRUNC_DATE",
        description = "TRUNC(transaction_date) strips time component for day-level date range comparison",
        complexity = MigrationComplexity.LOW,
        automationLevel = AutomationLevel.FULL,
        migrationTarget = "Replace TRUNC(date) with DATE_TRUNC('day', date) in PostgreSQL"
    )
    @Query(value =
        "SELECT * FROM MERIDIAN.TRANSACTIONS " +
        "WHERE account_id = :accountId " +
        "AND TRUNC(transaction_date) BETWEEN TO_DATE(:startDate, 'YYYY-MM-DD') " +
        "  AND TO_DATE(:endDate, 'YYYY-MM-DD') " +
        "ORDER BY transaction_date DESC",
        nativeQuery = true)
    List<TransactionEntity> findByAccountIdAndDateRange(@Param("accountId") Long accountId,
                                                         @Param("startDate") String startDate,
                                                         @Param("endDate") String endDate);

    // ORACLE-DEP [EMBEDDED_SQL:DATE_ARITHMETIC] SYSDATE - :days for recent window
    @OracleDependency(
        domain = DependencyDomain.EMBEDDED_SQL,
        category = "DATE_ARITHMETIC",
        description = "SYSDATE - :days uses Oracle date - integer arithmetic to compute a lookback window",
        complexity = MigrationComplexity.LOW,
        automationLevel = AutomationLevel.FULL,
        migrationTarget = "Replace SYSDATE - n with NOW() - INTERVAL 'n days' in PostgreSQL"
    )
    @OracleDependency(
        domain = DependencyDomain.EMBEDDED_SQL,
        category = "ROWNUM",
        description = "ROWNUM <= :maxRows limits result set — Oracle pre-12c top-N approach",
        complexity = MigrationComplexity.LOW,
        automationLevel = AutomationLevel.FULL,
        migrationTarget = "Replace ROWNUM <= n with LIMIT n in PostgreSQL"
    )
    @Query(value =
        "SELECT * FROM MERIDIAN.TRANSACTIONS " +
        "WHERE account_id = :accountId " +
        "AND transaction_date >= SYSDATE - :days " +
        "AND ROWNUM <= :maxRows " +
        "ORDER BY transaction_date DESC",
        nativeQuery = true)
    List<TransactionEntity> findRecentTransactions(@Param("accountId") Long accountId,
                                                    @Param("days") int days,
                                                    @Param("maxRows") int maxRows);

    // ORACLE-DEP [EMBEDDED_SQL:TO_CHAR_DATE] TO_CHAR for month grouping
    @OracleDependency(
        domain = DependencyDomain.EMBEDDED_SQL,
        category = "TO_CHAR_DATE",
        description = "TO_CHAR(transaction_date,'YYYY-MM') groups transactions by calendar month",
        complexity = MigrationComplexity.LOW,
        automationLevel = AutomationLevel.FULL,
        migrationTarget = "Replace TO_CHAR(date,'YYYY-MM') with TO_CHAR(date,'YYYY-MM') in PostgreSQL (compatible)"
    )
    @OracleDependency(
        domain = DependencyDomain.EMBEDDED_SQL,
        category = "ADD_MONTHS",
        description = "ADD_MONTHS(SYSDATE, -:months) computes rolling lookback start date",
        complexity = MigrationComplexity.LOW,
        automationLevel = AutomationLevel.FULL,
        migrationTarget = "Replace ADD_MONTHS(date, -n) with date - INTERVAL 'n months' in PostgreSQL"
    )
    @Query(value =
        "SELECT TO_CHAR(transaction_date, 'YYYY-MM') AS txn_month, " +
        "  transaction_type, " +
        "  COUNT(*) AS txn_count, " +
        "  SUM(amount) AS total_amount, " +
        "  AVG(amount) AS avg_amount " +
        "FROM MERIDIAN.TRANSACTIONS " +
        "WHERE account_id = :accountId " +
        "AND transaction_date >= ADD_MONTHS(SYSDATE, -:months) " +
        "GROUP BY TO_CHAR(transaction_date, 'YYYY-MM'), transaction_type " +
        "ORDER BY txn_month DESC",
        nativeQuery = true)
    List<Object[]> findMonthlySummaryByAccount(@Param("accountId") Long accountId,
                                                @Param("months") int months);

    // ORACLE-DEP [EMBEDDED_SQL:TO_DATE] TO_DATE string-to-date conversion
    @OracleDependency(
        domain = DependencyDomain.EMBEDDED_SQL,
        category = "TO_DATE",
        description = "TO_DATE(:dateStr,'YYYY-MM-DD') converts string parameter to Oracle DATE type",
        complexity = MigrationComplexity.LOW,
        automationLevel = AutomationLevel.FULL,
        migrationTarget = "Use CAST(:dateStr AS DATE) or TO_DATE(:dateStr,'YYYY-MM-DD') in PostgreSQL (compatible)"
    )
    @Query(value =
        "SELECT * FROM MERIDIAN.TRANSACTIONS " +
        "WHERE transaction_date >= TO_DATE(:startDate, 'YYYY-MM-DD') " +
        "AND transaction_date < TO_DATE(:endDate, 'YYYY-MM-DD') + 1 " +
        "AND (:transactionType IS NULL OR transaction_type = :transactionType) " +
        "ORDER BY transaction_date DESC",
        nativeQuery = true)
    List<TransactionEntity> findByDateRangeAndType(@Param("startDate") String startDate,
                                                    @Param("endDate") String endDate,
                                                    @Param("transactionType") String transactionType);

    // ORACLE-DEP [EMBEDDED_SQL:QUERY_HINT] INDEX and PARALLEL hints for performance
    @OracleDependency(
        domain = DependencyDomain.EMBEDDED_SQL,
        category = "QUERY_HINT",
        description = "/*+ INDEX(t TXN_ACCOUNT_DATE_IDX) */ forces use of composite account+date index",
        complexity = MigrationComplexity.MEDIUM,
        automationLevel = AutomationLevel.PARTIAL,
        migrationTarget = "Remove Oracle hint; ensure index exists; pg_hint_plan optional for PostgreSQL"
    )
    @Query(value =
        "SELECT /*+ INDEX(t TXN_ACCOUNT_DATE_IDX) */ " +
        "  t.transaction_id, t.account_id, t.amount, t.transaction_type, " +
        "  t.transaction_date, t.status, t.reference_number " +
        "FROM MERIDIAN.TRANSACTIONS t " +
        "WHERE t.account_id = :accountId " +
        "AND t.status = 'COMPLETED' " +
        "AND t.transaction_date >= SYSDATE - 30 " +
        "ORDER BY t.transaction_date DESC",
        nativeQuery = true)
    List<Object[]> findRecentCompletedWithHint(@Param("accountId") Long accountId);

    // ORACLE-DEP [EMBEDDED_SQL:QUERY_HINT] PARALLEL hint for large dataset scan
    @OracleDependency(
        domain = DependencyDomain.EMBEDDED_SQL,
        category = "QUERY_HINT",
        description = "/*+ PARALLEL(t, 4) */ enables 4-way parallel query for large transaction scans",
        complexity = MigrationComplexity.MEDIUM,
        automationLevel = AutomationLevel.PARTIAL,
        migrationTarget = "Remove Oracle parallel hint; enable pg_hint_plan for PostgreSQL if needed"
    )
    @Query(value =
        "SELECT /*+ PARALLEL(t, 4) */ " +
        "  account_id, SUM(amount) AS total_debits, COUNT(*) AS debit_count " +
        "FROM MERIDIAN.TRANSACTIONS t " +
        "WHERE transaction_type = 'DEBIT' " +
        "AND TRUNC(transaction_date, 'MM') = TRUNC(SYSDATE, 'MM') " +
        "GROUP BY account_id " +
        "HAVING SUM(amount) > :threshold",
        nativeQuery = true)
    List<Object[]> findHighVolumeDebitAccounts(@Param("threshold") BigDecimal threshold);

    // ORACLE-DEP [EMBEDDED_SQL:NVL_FUNCTION] NVL for null-safe description
    @OracleDependency(
        domain = DependencyDomain.EMBEDDED_SQL,
        category = "NVL_FUNCTION",
        description = "NVL(description,'No description') defaults null transaction descriptions",
        complexity = MigrationComplexity.LOW,
        automationLevel = AutomationLevel.FULL,
        migrationTarget = "Replace NVL(description,'No description') with COALESCE(description,'No description')"
    )
    @OracleDependency(
        domain = DependencyDomain.EMBEDDED_SQL,
        category = "DECODE_FUNCTION",
        description = "DECODE(transaction_type,'DEBIT',-1,1) negates debit amounts for signed balance view",
        complexity = MigrationComplexity.LOW,
        automationLevel = AutomationLevel.FULL,
        migrationTarget = "Replace DECODE(transaction_type,'DEBIT',-1,1) with CASE WHEN ... END in PostgreSQL"
    )
    @Query(value =
        "SELECT t.transaction_id, t.account_id, " +
        "  NVL(t.description, 'No description provided') AS description, " +
        "  t.amount * DECODE(t.transaction_type, 'DEBIT', -1, 1) AS signed_amount, " +
        "  t.transaction_date, t.status " +
        "FROM MERIDIAN.TRANSACTIONS t " +
        "WHERE t.account_id = :accountId " +
        "ORDER BY t.transaction_date DESC",
        nativeQuery = true)
    List<Object[]> findSignedTransactions(@Param("accountId") Long accountId);

    // ORACLE-DEP [EMBEDDED_SQL:DATE_ARITHMETIC] date + 30 for due date calculation
    @OracleDependency(
        domain = DependencyDomain.EMBEDDED_SQL,
        category = "DATE_ARITHMETIC",
        description = "transaction_date + 30 adds 30 days to compute settlement due date",
        complexity = MigrationComplexity.LOW,
        automationLevel = AutomationLevel.FULL,
        migrationTarget = "Replace date + 30 with date + INTERVAL '30 days' in PostgreSQL"
    )
    @OracleDependency(
        domain = DependencyDomain.EMBEDDED_SQL,
        category = "SYSDATE",
        description = "SYSDATE used in WHERE clause to identify unsettled past-due transactions",
        complexity = MigrationComplexity.LOW,
        automationLevel = AutomationLevel.FULL,
        migrationTarget = "Replace SYSDATE with CURRENT_DATE or NOW() in PostgreSQL"
    )
    @Query(value =
        "SELECT t.transaction_id, t.account_id, t.amount, t.transaction_date, " +
        "  t.transaction_date + 30 AS settlement_due_date, " +
        "  SYSDATE - t.transaction_date AS days_pending " +
        "FROM MERIDIAN.TRANSACTIONS t " +
        "WHERE t.status = 'PENDING' " +
        "AND t.transaction_date + 30 < SYSDATE " +
        "ORDER BY t.transaction_date ASC",
        nativeQuery = true)
    List<Object[]> findOverdueSettlements();

    // ORACLE-DEP [EMBEDDED_SQL:TO_NUMBER] TO_NUMBER for string-to-numeric conversion
    @OracleDependency(
        domain = DependencyDomain.EMBEDDED_SQL,
        category = "TO_NUMBER",
        description = "TO_NUMBER(reference_number) converts stored string reference to number for range comparison",
        complexity = MigrationComplexity.LOW,
        automationLevel = AutomationLevel.FULL,
        migrationTarget = "Replace TO_NUMBER(str) with CAST(str AS NUMERIC) or str::NUMERIC in PostgreSQL"
    )
    @Query(value =
        "SELECT * FROM MERIDIAN.TRANSACTIONS " +
        "WHERE TO_NUMBER(reference_number) BETWEEN :startRef AND :endRef " +
        "ORDER BY TO_NUMBER(reference_number) ASC",
        nativeQuery = true)
    List<TransactionEntity> findByReferenceNumberRange(@Param("startRef") Long startRef,
                                                        @Param("endRef") Long endRef);

    // ORACLE-DEP [EMBEDDED_SQL:SYSTIMESTAMP] SYSTIMESTAMP for high-precision audit
    @OracleDependency(
        domain = DependencyDomain.EMBEDDED_SQL,
        category = "SYSTIMESTAMP",
        description = "SYSTIMESTAMP provides sub-second precision timestamp for transaction audit records",
        complexity = MigrationComplexity.LOW,
        automationLevel = AutomationLevel.FULL,
        migrationTarget = "Replace SYSTIMESTAMP with CURRENT_TIMESTAMP or clock_timestamp() in PostgreSQL"
    )
    @Modifying
    @Transactional
    @Query(value =
        "UPDATE MERIDIAN.TRANSACTIONS " +
        "SET status = :newStatus, updated_at = SYSTIMESTAMP " +
        "WHERE transaction_id = :transactionId",
        nativeQuery = true)
    int updateTransactionStatus(@Param("transactionId") Long transactionId,
                                 @Param("newStatus") String newStatus);

    // ORACLE-DEP [EMBEDDED_SQL:TRUNC_DATE] TRUNC day-level grouping for daily totals
    @OracleDependency(
        domain = DependencyDomain.EMBEDDED_SQL,
        category = "TRUNC_DATE",
        description = "TRUNC(transaction_date) groups transactions by calendar day for daily summary",
        complexity = MigrationComplexity.LOW,
        automationLevel = AutomationLevel.FULL,
        migrationTarget = "Replace TRUNC(transaction_date) with DATE_TRUNC('day', transaction_date) in PostgreSQL"
    )
    @Query(value =
        "SELECT TRUNC(transaction_date) AS txn_day, " +
        "  COUNT(*) AS txn_count, " +
        "  SUM(CASE WHEN transaction_type = 'CREDIT' THEN amount ELSE 0 END) AS total_credits, " +
        "  SUM(CASE WHEN transaction_type = 'DEBIT' THEN amount ELSE 0 END) AS total_debits " +
        "FROM MERIDIAN.TRANSACTIONS " +
        "WHERE account_id = :accountId " +
        "AND transaction_date >= SYSDATE - :days " +
        "GROUP BY TRUNC(transaction_date) " +
        "ORDER BY txn_day DESC",
        nativeQuery = true)
    List<Object[]> findDailySummary(@Param("accountId") Long accountId,
                                     @Param("days") int days);
}
