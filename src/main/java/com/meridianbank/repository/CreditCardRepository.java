package com.meridianbank.repository;

import com.meridianbank.annotation.*;
import com.meridianbank.entity.CreditCardEntity;
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
 * Spring Data JPA repository for CreditCardEntity.
 * Demonstrates Oracle SQL constructs: LISTAGG, NTILE, CUME_DIST, ADD_MONTHS,
 * NVL2, MONTHS_BETWEEN, DECODE, ROWNUM, analytic functions, and SYSDATE.
 */
@Repository
public interface CreditCardRepository extends JpaRepository<CreditCardEntity, Long> {

    // Standard JPQL — no Oracle dependency
    List<CreditCardEntity> findByCustomerId(Long customerId);
    List<CreditCardEntity> findByStatus(String status);
    Optional<CreditCardEntity> findByCardNumber(String cardNumber);
    List<CreditCardEntity> findByCustomerIdAndStatus(Long customerId, String status);

    // ORACLE-DEP [EMBEDDED_SQL:LISTAGG] LISTAGG to aggregate card numbers into one row
    @OracleDependency(
        domain = DependencyDomain.EMBEDDED_SQL,
        category = "LISTAGG",
        description = "LISTAGG(card_number, ', ') WITHIN GROUP aggregates multiple card numbers into a single delimited string",
        complexity = MigrationComplexity.LOW,
        automationLevel = AutomationLevel.FULL,
        migrationTarget = "Replace LISTAGG(col, delim) WITHIN GROUP (ORDER BY ...) with STRING_AGG(col, delim ORDER BY ...) in PostgreSQL"
    )
    @Query(value =
        "SELECT c.customer_id, " +
        "  LISTAGG(cc.masked_card_number, ', ') WITHIN GROUP (ORDER BY cc.open_date) AS all_cards, " +
        "  COUNT(cc.card_id) AS card_count, " +
        "  SUM(cc.credit_limit) AS total_credit_limit, " +
        "  SUM(cc.current_balance) AS total_balance " +
        "FROM MERIDIAN.CUSTOMERS c " +
        "JOIN MERIDIAN.CREDIT_CARDS cc ON c.customer_id = cc.customer_id " +
        "WHERE cc.status = 'ACTIVE' " +
        "GROUP BY c.customer_id " +
        "ORDER BY c.customer_id",
        nativeQuery = true)
    List<Object[]> findCustomerCardSummary();

    // ORACLE-DEP [EMBEDDED_SQL:NTILE] NTILE for spending quartile classification
    @OracleDependency(
        domain = DependencyDomain.EMBEDDED_SQL,
        category = "NTILE",
        description = "NTILE(4) OVER (ORDER BY current_balance) assigns spending quartile 1-4 to each cardholder",
        complexity = MigrationComplexity.LOW,
        automationLevel = AutomationLevel.FULL,
        migrationTarget = "NTILE(n) OVER (ORDER BY ...) is SQL standard — works in PostgreSQL without change"
    )
    @Query(value =
        "SELECT card_id, customer_id, card_type, current_balance, credit_limit, " +
        "  NTILE(4) OVER (ORDER BY current_balance DESC) AS spending_quartile, " +
        "  ROUND(current_balance / NULLIF(credit_limit, 0) * 100, 2) AS utilization_pct " +
        "FROM MERIDIAN.CREDIT_CARDS " +
        "WHERE status = 'ACTIVE' " +
        "ORDER BY current_balance DESC",
        nativeQuery = true)
    List<Object[]> findCardsWithSpendingQuartile();

    // ORACLE-DEP [EMBEDDED_SQL:CUME_DIST] CUME_DIST for balance percentile ranking
    @OracleDependency(
        domain = DependencyDomain.EMBEDDED_SQL,
        category = "CUME_DIST",
        description = "CUME_DIST() OVER (ORDER BY current_balance) computes cumulative distribution percentile",
        complexity = MigrationComplexity.LOW,
        automationLevel = AutomationLevel.FULL,
        migrationTarget = "CUME_DIST() OVER (...) is SQL standard — works in PostgreSQL without change"
    )
    @Query(value =
        "SELECT card_id, customer_id, current_balance, " +
        "  ROUND(CUME_DIST() OVER (ORDER BY current_balance) * 100, 2) AS balance_percentile, " +
        "  NTILE(10) OVER (ORDER BY current_balance) AS balance_decile " +
        "FROM MERIDIAN.CREDIT_CARDS " +
        "WHERE status = 'ACTIVE'",
        nativeQuery = true)
    List<Object[]> findCardsWithBalancePercentile();

    // ORACLE-DEP [EMBEDDED_SQL:ADD_MONTHS] ADD_MONTHS for expiry date calculation
    @OracleDependency(
        domain = DependencyDomain.EMBEDDED_SQL,
        category = "ADD_MONTHS",
        description = "ADD_MONTHS(issue_date, :months) computes card expiry date from issue date",
        complexity = MigrationComplexity.LOW,
        automationLevel = AutomationLevel.FULL,
        migrationTarget = "Replace ADD_MONTHS(date, n) with date + INTERVAL 'n months' in PostgreSQL"
    )
    @Query(value =
        "SELECT card_id, card_number, issue_date, " +
        "  ADD_MONTHS(issue_date, :validityMonths) AS computed_expiry, " +
        "  expiry_date AS stored_expiry " +
        "FROM MERIDIAN.CREDIT_CARDS " +
        "WHERE ADD_MONTHS(issue_date, :validityMonths) BETWEEN SYSDATE AND ADD_MONTHS(SYSDATE, 3) " +
        "AND status = 'ACTIVE' " +
        "ORDER BY expiry_date ASC",
        nativeQuery = true)
    List<Object[]> findCardsExpiringSoon(@Param("validityMonths") int validityMonths);

    // ORACLE-DEP [EMBEDDED_SQL:NVL2_FUNCTION] NVL2 for reward tier classification
    @OracleDependency(
        domain = DependencyDomain.EMBEDDED_SQL,
        category = "NVL2_FUNCTION",
        description = "NVL2(reward_tier, 'Enrolled', 'Not Enrolled') classifies reward program participation",
        complexity = MigrationComplexity.LOW,
        automationLevel = AutomationLevel.FULL,
        migrationTarget = "Replace NVL2(col, v1, v2) with CASE WHEN col IS NOT NULL THEN v1 ELSE v2 END in PostgreSQL"
    )
    @OracleDependency(
        domain = DependencyDomain.EMBEDDED_SQL,
        category = "NVL_FUNCTION",
        description = "NVL(reward_points, 0) defaults null reward points to zero for calculations",
        complexity = MigrationComplexity.LOW,
        automationLevel = AutomationLevel.FULL,
        migrationTarget = "Replace NVL(reward_points, 0) with COALESCE(reward_points, 0) in PostgreSQL"
    )
    @Query(value =
        "SELECT card_id, customer_id, card_type, " +
        "  NVL2(reward_tier, 'Enrolled', 'Not Enrolled') AS reward_status, " +
        "  NVL(reward_points, 0) AS effective_points, " +
        "  DECODE(reward_tier, 'PLATINUM', 3.0, 'GOLD', 2.0, 'SILVER', 1.5, 'STANDARD', 1.0, 0) AS points_multiplier " +
        "FROM MERIDIAN.CREDIT_CARDS " +
        "WHERE customer_id = :customerId",
        nativeQuery = true)
    List<Object[]> findCardRewardStatus(@Param("customerId") Long customerId);

    // ORACLE-DEP [EMBEDDED_SQL:MONTHS_BETWEEN] MONTHS_BETWEEN for card age calculation
    @OracleDependency(
        domain = DependencyDomain.EMBEDDED_SQL,
        category = "MONTHS_BETWEEN",
        description = "MONTHS_BETWEEN(SYSDATE, issue_date) computes card age in fractional months",
        complexity = MigrationComplexity.MEDIUM,
        automationLevel = AutomationLevel.FULL,
        migrationTarget = "Replace MONTHS_BETWEEN(d1,d2) with EXTRACT(YEAR FROM AGE(d1,d2))*12 + EXTRACT(MONTH FROM AGE(d1,d2)) in PostgreSQL"
    )
    @Query(value =
        "SELECT card_id, customer_id, issue_date, " +
        "  FLOOR(MONTHS_BETWEEN(SYSDATE, issue_date)) AS card_age_months, " +
        "  FLOOR(MONTHS_BETWEEN(expiry_date, SYSDATE)) AS months_until_expiry " +
        "FROM MERIDIAN.CREDIT_CARDS " +
        "WHERE status = 'ACTIVE' " +
        "ORDER BY card_age_months DESC",
        nativeQuery = true)
    List<Object[]> findCardsWithAgeMetrics();

    // ORACLE-DEP [EMBEDDED_SQL:DECODE_FUNCTION] DECODE for card type priority ordering
    @OracleDependency(
        domain = DependencyDomain.EMBEDDED_SQL,
        category = "DECODE_FUNCTION",
        description = "DECODE(card_type,...) assigns sort priority to card tier for display ordering",
        complexity = MigrationComplexity.LOW,
        automationLevel = AutomationLevel.FULL,
        migrationTarget = "Replace DECODE(card_type,...) with CASE WHEN card_type=... THEN ... END in PostgreSQL"
    )
    @Query(value =
        "SELECT * FROM MERIDIAN.CREDIT_CARDS " +
        "WHERE customer_id = :customerId " +
        "ORDER BY DECODE(card_type, 'PLATINUM', 1, 'GOLD', 2, 'REWARDS', 3, 'SECURED', 4, 5), " +
        "  issue_date DESC",
        nativeQuery = true)
    List<CreditCardEntity> findByCustomerOrderedByTier(@Param("customerId") Long customerId);

    // ORACLE-DEP [EMBEDDED_SQL:ROWNUM] ROWNUM top-N high-balance cards
    @OracleDependency(
        domain = DependencyDomain.EMBEDDED_SQL,
        category = "ROWNUM",
        description = "ROWNUM <= :topN restricts to top N highest-balance cards — Oracle pre-12c pattern",
        complexity = MigrationComplexity.LOW,
        automationLevel = AutomationLevel.FULL,
        migrationTarget = "Replace ROWNUM <= n with LIMIT n or FETCH FIRST n ROWS ONLY in PostgreSQL"
    )
    @Query(value =
        "SELECT * FROM (" +
        "  SELECT cc.*, ROWNUM AS rn FROM MERIDIAN.CREDIT_CARDS cc " +
        "  WHERE cc.status = 'ACTIVE' " +
        "  ORDER BY cc.current_balance DESC" +
        ") WHERE rn <= :topN",
        nativeQuery = true)
    List<CreditCardEntity> findTopHighBalanceCards(@Param("topN") int topN);

    // ORACLE-DEP [EMBEDDED_SQL:SYSDATE] SYSDATE for expiry check
    @OracleDependency(
        domain = DependencyDomain.EMBEDDED_SQL,
        category = "SYSDATE",
        description = "SYSDATE used to identify currently expired cards for renewal processing",
        complexity = MigrationComplexity.LOW,
        automationLevel = AutomationLevel.FULL,
        migrationTarget = "Replace SYSDATE with CURRENT_DATE in PostgreSQL"
    )
    @Modifying
    @Transactional
    @Query(value =
        "UPDATE MERIDIAN.CREDIT_CARDS " +
        "SET status = 'EXPIRED' " +
        "WHERE expiry_date < SYSDATE " +
        "AND status = 'ACTIVE'",
        nativeQuery = true)
    int markExpiredCards();

    // ORACLE-DEP [EMBEDDED_SQL:ANALYTIC_FUNCTION] Analytic RANK for utilization ranking
    @OracleDependency(
        domain = DependencyDomain.EMBEDDED_SQL,
        category = "ANALYTIC_FUNCTION",
        description = "RANK() OVER (PARTITION BY customer_id ORDER BY utilization DESC) ranks cards within customer portfolio",
        complexity = MigrationComplexity.LOW,
        automationLevel = AutomationLevel.FULL,
        migrationTarget = "RANK() OVER (...) is SQL standard — works in PostgreSQL without change"
    )
    @Query(value =
        "SELECT card_id, customer_id, card_type, credit_limit, current_balance, " +
        "  ROUND(current_balance / NULLIF(credit_limit, 0) * 100, 2) AS utilization_pct, " +
        "  RANK() OVER (PARTITION BY customer_id ORDER BY " +
        "    current_balance / NULLIF(credit_limit, 0) DESC) AS utilization_rank " +
        "FROM MERIDIAN.CREDIT_CARDS " +
        "WHERE customer_id = :customerId AND status = 'ACTIVE'",
        nativeQuery = true)
    List<Object[]> findCardsWithUtilizationRank(@Param("customerId") Long customerId);
}
