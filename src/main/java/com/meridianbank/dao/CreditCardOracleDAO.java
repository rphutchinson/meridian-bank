package com.meridianbank.dao;

import com.meridianbank.annotation.AutomationLevel;
import com.meridianbank.annotation.DependencyDomain;
import com.meridianbank.annotation.MigrationComplexity;
import com.meridianbank.annotation.OracleDependency;
import com.meridianbank.dto.CreditCardDTO;
import com.meridianbank.exception.OracleErrorTranslator;
import com.meridianbank.oracle.OracleConnectionUnwrapper;
import oracle.jdbc.OracleCallableStatement;
import oracle.jdbc.OracleTypes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Raw JDBC DAO for Credit Card operations.
 *
 * Demonstrates: CREDIT_CARD_PKG calls, LISTAGG, NTILE analytic,
 * CUME_DIST, ADD_MONTHS, MONTHS_BETWEEN, DECODE, NVL, date arithmetic,
 * OracleTypes.CURSOR, reward tier hierarchy via CONNECT BY.
 *
 * ORACLE-DEP [PLSQL_INVOCATION:PACKAGE_CALL]  CREDIT_CARD_PKG calls
 * ORACLE-DEP [EMBEDDED_SQL:LISTAGG]           LISTAGG for card summary
 * ORACLE-DEP [EMBEDDED_SQL:ANALYTIC_FUNCTIONS] NTILE, CUME_DIST
 * ORACLE-DEP [EMBEDDED_SQL:ADD_MONTHS]        ADD_MONTHS for expiry
 * ORACLE-DEP [EMBEDDED_SQL:CONNECT_BY]        Reward tier hierarchy
 */
@Repository
// ORACLE-DEP [PLSQL_INVOCATION:PACKAGE_CALL] Credit card DAO using CREDIT_CARD_PKG and analytic SQL
@OracleDependency(
        domain = DependencyDomain.PLSQL_INVOCATION,
        category = "PACKAGE_CALL",
        description = "Credit card DAO using CREDIT_CARD_PKG, analytic functions, CONNECT BY reward tiers",
        complexity = MigrationComplexity.HIGH,
        automationLevel = AutomationLevel.PARTIAL,
        migrationTarget = "Replace Oracle package calls and analytic SQL with PostgreSQL equivalents"
)
public class CreditCardOracleDAO {

    private static final Logger log = LoggerFactory.getLogger(CreditCardOracleDAO.class);

    @Autowired
    private DataSource dataSource;

    @Autowired
    private OracleConnectionUnwrapper connectionUnwrapper;

    @Autowired
    private OracleErrorTranslator errorTranslator;

    // -----------------------------------------------------------------------
    // Apply for credit card via package call
    // -----------------------------------------------------------------------

    /**
     * Submits a credit card application via CREDIT_CARD_PKG.APPLY_FOR_CARD.
     *
     * ORACLE-DEP [PLSQL_INVOCATION:PACKAGE_CALL] CREDIT_CARD_PKG.APPLY_FOR_CARD
     */
    @OracleDependency(
            domain = DependencyDomain.PLSQL_INVOCATION,
            category = "PACKAGE_CALL",
            description = "Submits credit card application via CREDIT_CARD_PKG.APPLY_FOR_CARD",
            complexity = MigrationComplexity.HIGH,
            automationLevel = AutomationLevel.PARTIAL,
            migrationTarget = "Replace with INSERT + PostgreSQL stored function"
    )
    public Long applyForCard(Long customerId, String cardType, BigDecimal requestedLimit)
            throws SQLException {
        // ORACLE-DEP [PLSQL_INVOCATION:PACKAGE_CALL] {call PKG.PROC()} Oracle syntax
        String call = "{call MERIDIAN.CREDIT_CARD_PKG.APPLY_FOR_CARD(?, ?, ?, ?, ?)}";
        try (Connection conn = dataSource.getConnection();
             OracleCallableStatement ocs =
                     (OracleCallableStatement) conn.prepareCall(call)) {
            ocs.setLong(1, customerId);
            ocs.setString(2, cardType);
            ocs.setBigDecimal(3, requestedLimit);
            // ORACLE-DEP [ORACLE_JAVA_API:ORACLE_TYPES] OracleTypes.NUMBER for card_id OUT
            ocs.registerOutParameter(4, OracleTypes.NUMBER);
            // ORACLE-DEP [ORACLE_JAVA_API:ORACLE_TYPES] OracleTypes.VARCHAR for approval_status OUT
            ocs.registerOutParameter(5, OracleTypes.VARCHAR);
            ocs.execute();
            Long cardId = ocs.getLong(4);
            String approvalStatus = ocs.getString(5);
            log.info("Card application cardId={} status={}", cardId, approvalStatus);
            return cardId;
        } catch (SQLException e) {
            throw errorTranslator.translateSQLException(e);
        }
    }

    // -----------------------------------------------------------------------
    // Card summary using LISTAGG
    // -----------------------------------------------------------------------

    /**
     * Returns a summary of all cards for a customer using Oracle LISTAGG
     * to aggregate card numbers into a single string.
     *
     * ORACLE-DEP [EMBEDDED_SQL:LISTAGG] LISTAGG for multi-card summary
     */
    @OracleDependency(
            domain = DependencyDomain.EMBEDDED_SQL,
            category = "LISTAGG",
            description = "Aggregates credit card numbers using Oracle LISTAGG",
            complexity = MigrationComplexity.LOW,
            automationLevel = AutomationLevel.FULL,
            migrationTarget = "Replace LISTAGG(...) WITHIN GROUP with STRING_AGG(...) in PostgreSQL"
    )
    public String getCardSummary(Long customerId) throws SQLException {
        // ORACLE-DEP [EMBEDDED_SQL:LISTAGG] LISTAGG aggregates card numbers into comma-separated string
        // ORACLE-DEP [EMBEDDED_SQL:DECODE_FUNCTION] DECODE for card type label
        String sql = "SELECT LISTAGG(" +
                "  DECODE(card_type, 'VISA', 'Visa', 'MC', 'Mastercard', 'AMEX', 'Amex', card_type) " +
                "  || ':' || SUBSTR(card_number, -4)" +
                ", ', ') WITHIN GROUP (ORDER BY card_type) AS card_summary " +
                "FROM MERIDIAN.credit_cards " +
                "WHERE customer_id = ? AND status = 'ACTIVE'";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, customerId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getString("card_summary");
                }
            }
        }
        return null;
    }

    // -----------------------------------------------------------------------
    // Reward tier hierarchy using CONNECT BY
    // -----------------------------------------------------------------------

    /**
     * Retrieves reward tier hierarchy for a card using Oracle CONNECT BY PRIOR.
     *
     * ORACLE-DEP [EMBEDDED_SQL:CONNECT_BY] CONNECT BY PRIOR for reward tier tree
     */
    @OracleDependency(
            domain = DependencyDomain.EMBEDDED_SQL,
            category = "CONNECT_BY",
            description = "Retrieves reward tier hierarchy using Oracle CONNECT BY PRIOR",
            complexity = MigrationComplexity.MEDIUM,
            automationLevel = AutomationLevel.PARTIAL,
            migrationTarget = "Replace with recursive CTE (WITH RECURSIVE) in PostgreSQL"
    )
    public List<String> getRewardTierPath(Long cardId) throws SQLException {
        // ORACLE-DEP [EMBEDDED_SQL:CONNECT_BY] CONNECT BY PRIOR for tier parent-child traversal
        // ORACLE-DEP [EMBEDDED_SQL:LEVEL] LEVEL pseudocolumn for depth
        // ORACLE-DEP [EMBEDDED_SQL:SYS_CONNECT_BY_PATH] SYS_CONNECT_BY_PATH for full tier path
        String sql = "SELECT tier_name, LEVEL AS tier_level, " +
                "SYS_CONNECT_BY_PATH(tier_name, ' > ') AS full_path " +
                "FROM MERIDIAN.reward_tiers " +
                "START WITH parent_tier_id IS NULL " +
                "CONNECT BY PRIOR tier_id = parent_tier_id " +
                "ORDER SIBLINGS BY sort_order";
        List<String> tierPaths = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    tierPaths.add(rs.getString("full_path"));
                }
            }
        }
        return tierPaths;
    }

    // -----------------------------------------------------------------------
    // Customer spending percentile using CUME_DIST analytic
    // -----------------------------------------------------------------------

    /**
     * Returns spending percentile rank for a cardholder using Oracle CUME_DIST().
     *
     * ORACLE-DEP [EMBEDDED_SQL:ANALYTIC_FUNCTIONS] CUME_DIST for spending percentile
     */
    @OracleDependency(
            domain = DependencyDomain.EMBEDDED_SQL,
            category = "ANALYTIC_FUNCTIONS",
            description = "Spending percentile using Oracle CUME_DIST() analytic function",
            complexity = MigrationComplexity.LOW,
            automationLevel = AutomationLevel.FULL,
            migrationTarget = "CUME_DIST() is SQL standard and available in PostgreSQL unchanged"
    )
    public List<CreditCardDTO> getSpendingPercentiles() throws SQLException {
        // ORACLE-DEP [EMBEDDED_SQL:ANALYTIC_FUNCTIONS] CUME_DIST() OVER (ORDER BY) analytic
        // ORACLE-DEP [EMBEDDED_SQL:ANALYTIC_FUNCTIONS] NTILE(4) for quartile bucketing
        // ORACLE-DEP [EMBEDDED_SQL:NVL_FUNCTION] NVL for null current_balance
        String sql = "SELECT card_id, customer_id, card_type, " +
                "NVL(current_balance, 0) AS current_balance, credit_limit, " +
                "CUME_DIST() OVER (ORDER BY NVL(current_balance, 0) DESC) AS spending_pct, " +
                "NTILE(4) OVER (ORDER BY NVL(current_balance, 0) DESC) AS spending_quartile " +
                "FROM MERIDIAN.credit_cards " +
                "WHERE status = 'ACTIVE' " +
                "ORDER BY current_balance DESC";
        List<CreditCardDTO> results = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    results.add(mapRowToDTO(rs));
                }
            }
        }
        return results;
    }

    // -----------------------------------------------------------------------
    // Process payment via CREDIT_CARD_PKG
    // -----------------------------------------------------------------------

    /**
     * Processes a card payment via CREDIT_CARD_PKG.PROCESS_PAYMENT and uses
     * ADD_MONTHS to calculate the next statement date.
     *
     * ORACLE-DEP [PLSQL_INVOCATION:PACKAGE_CALL] CREDIT_CARD_PKG.PROCESS_PAYMENT
     * ORACLE-DEP [EMBEDDED_SQL:ADD_MONTHS]        ADD_MONTHS for next statement date
     */
    @OracleDependency(
            domain = DependencyDomain.PLSQL_INVOCATION,
            category = "PACKAGE_CALL",
            description = "Processes card payment via CREDIT_CARD_PKG.PROCESS_PAYMENT",
            complexity = MigrationComplexity.HIGH,
            automationLevel = AutomationLevel.PARTIAL,
            migrationTarget = "Replace with PostgreSQL stored function and date + interval arithmetic"
    )
    public BigDecimal processPayment(Long cardId, BigDecimal paymentAmount) throws SQLException {
        // ORACLE-DEP [PLSQL_INVOCATION:PACKAGE_CALL] PROCESS_PAYMENT returns new balance
        // ORACLE-DEP [EMBEDDED_SQL:ADD_MONTHS] ADD_MONTHS(SYSDATE, 1) for next statement date
        String call = "{call MERIDIAN.CREDIT_CARD_PKG.PROCESS_PAYMENT(?, ?, ?, ?)}";
        try (Connection conn = dataSource.getConnection();
             OracleCallableStatement ocs =
                     (OracleCallableStatement) conn.prepareCall(call)) {
            ocs.setLong(1, cardId);
            ocs.setBigDecimal(2, paymentAmount);
            // ORACLE-DEP [ORACLE_JAVA_API:ORACLE_TYPES] OracleTypes.NUMBER for new_balance OUT
            ocs.registerOutParameter(3, OracleTypes.NUMBER);
            // ORACLE-DEP [ORACLE_JAVA_API:ORACLE_TYPES] OracleTypes.TIMESTAMP for next_statement_date OUT
            ocs.registerOutParameter(4, OracleTypes.TIMESTAMP);
            ocs.execute();
            BigDecimal newBalance = ocs.getBigDecimal(3);
            log.info("Processed payment cardId={} newBalance={}", cardId, newBalance);
            return newBalance;
        } catch (SQLException e) {
            throw errorTranslator.translateSQLException(e);
        }
    }

    // -----------------------------------------------------------------------
    // Cards expiring using MONTHS_BETWEEN
    // -----------------------------------------------------------------------

    /**
     * Returns cards expiring within a given number of months using MONTHS_BETWEEN.
     *
     * ORACLE-DEP [EMBEDDED_SQL:MONTHS_BETWEEN] MONTHS_BETWEEN for expiry calculation
     */
    @OracleDependency(
            domain = DependencyDomain.EMBEDDED_SQL,
            category = "MONTHS_BETWEEN",
            description = "Finds cards expiring soon using Oracle MONTHS_BETWEEN(expiry_date, SYSDATE)",
            complexity = MigrationComplexity.MEDIUM,
            automationLevel = AutomationLevel.PARTIAL,
            migrationTarget = "Replace with DATE_PART('year', age(expiry_date)) * 12 + DATE_PART('month', age(...)) in PostgreSQL"
    )
    public List<CreditCardDTO> findCardsExpiringSoon(int withinMonths) throws SQLException {
        // ORACLE-DEP [EMBEDDED_SQL:MONTHS_BETWEEN] MONTHS_BETWEEN(expiry_date, SYSDATE)
        // ORACLE-DEP [EMBEDDED_SQL:SYSDATE] SYSDATE as comparison reference
        // ORACLE-DEP [EMBEDDED_SQL:NVL2] NVL2 for conditional reward points display
        String sql = "SELECT card_id, customer_id, card_type, card_number, " +
                "credit_limit, current_balance, expiry_date, status, " +
                "NVL2(reward_points, reward_points, 0) AS reward_points " +
                "FROM MERIDIAN.credit_cards " +
                "WHERE status = 'ACTIVE' " +
                "AND MONTHS_BETWEEN(expiry_date, SYSDATE) BETWEEN 0 AND ? " +
                "ORDER BY expiry_date ASC";
        List<CreditCardDTO> results = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, withinMonths);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    results.add(mapRowToDTO(rs));
                }
            }
        }
        return results;
    }

    // -----------------------------------------------------------------------
    // Private helpers
    // -----------------------------------------------------------------------

    private CreditCardDTO mapRowToDTO(ResultSet rs) throws SQLException {
        CreditCardDTO dto = new CreditCardDTO();
        try { dto.setCardId(rs.getLong("card_id")); } catch (SQLException ignored) {}
        try { dto.setCustomerId(rs.getLong("customer_id")); } catch (SQLException ignored) {}
        try { dto.setCardType(rs.getString("card_type")); } catch (SQLException ignored) {}
        try {
            String raw = rs.getString("card_number");
            if (raw != null && raw.length() >= 4) {
                dto.setCardNumberMasked("****-****-****-" + raw.substring(raw.length() - 4));
            } else {
                dto.setCardNumberMasked(raw);
            }
        } catch (SQLException ignored) {}
        try { dto.setCreditLimit(rs.getBigDecimal("credit_limit")); } catch (SQLException ignored) {}
        try { dto.setCurrentBalance(rs.getBigDecimal("current_balance")); } catch (SQLException ignored) {}
        try { dto.setStatus(rs.getString("status")); } catch (SQLException ignored) {}
        try {
            Date expiry = rs.getDate("expiry_date");
            if (expiry != null) dto.setExpiryDate(expiry.toLocalDate());
        } catch (SQLException ignored) {}
        try { dto.setRewardPoints(rs.getInt("reward_points")); } catch (SQLException ignored) {}
        return dto;
    }
}
