package com.meridianbank.service;

import com.meridianbank.annotation.AutomationLevel;
import com.meridianbank.annotation.DependencyDomain;
import com.meridianbank.annotation.MigrationComplexity;
import com.meridianbank.annotation.OracleDependency;
import com.meridianbank.dao.CreditCardOracleDAO;
import com.meridianbank.dto.CreditCardDTO;
import com.meridianbank.entity.CreditCardEntity;
import com.meridianbank.exception.MeridianBankException;
import com.meridianbank.exception.OracleErrorTranslator;
import com.meridianbank.repository.CreditCardRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Service layer for Credit Card operations.
 *
 * Orchestrates CreditCardRepository (Spring Data JPA) and CreditCardOracleDAO
 * (raw JDBC with Oracle-specific APIs). Exercises the following Oracle dependencies:
 *
 * ORACLE-DEP [PLSQL_INVOCATION:PACKAGE_CALL]   CREDIT_CARD_PKG via CreditCardOracleDAO
 * ORACLE-DEP [EMBEDDED_SQL:LISTAGG_FUNCTION]   Card summary uses LISTAGG() aggregation
 * ORACLE-DEP [EMBEDDED_SQL:CONNECT_BY]         Reward tier hierarchy via CONNECT BY PRIOR
 * ORACLE-DEP [EMBEDDED_SQL:NTILE_CUME_DIST]    Spending percentiles via NTILE/CUME_DIST
 * ORACLE-DEP [EMBEDDED_SQL:ADD_MONTHS]         Expiry date calculation via ADD_MONTHS()
 * ORACLE-DEP [EMBEDDED_SQL:DECODE_FUNCTION]    Card type ordering via DECODE()
 * ORACLE-DEP [EMBEDDED_SQL:ANALYTIC_FUNCTIONS] Utilisation rank via RANK() OVER
 */
@Service
@Transactional
public class CreditCardService {

    private static final Logger log = LoggerFactory.getLogger(CreditCardService.class);

    @Autowired
    private CreditCardRepository creditCardRepository;

    @Autowired
    private CreditCardOracleDAO creditCardOracleDAO;

    @Autowired
    private OracleErrorTranslator errorTranslator;

    // -----------------------------------------------------------------------
    // Card application and retrieval
    // -----------------------------------------------------------------------

    /**
     * Submits a credit card application via CREDIT_CARD_PKG.APPLY_FOR_CARD.
     *
     * The DAO uses:
     *  - OracleTypes.NUMBER OUT parameter for the returned card_id
     *  - An Oracle sequence (credit_card_seq.NEXTVAL) for card number generation inside the package
     *  - DBMS_CRYPTO to generate a secure random CVV stub
     */
    @OracleDependency(
        domain = DependencyDomain.PLSQL_INVOCATION,
        category = "PACKAGE_CALL",
        description = "Card application calls CREDIT_CARD_PKG.APPLY_FOR_CARD with OracleTypes.NUMBER OUT for new card_id",
        complexity = MigrationComplexity.HIGH,
        automationLevel = AutomationLevel.PARTIAL,
        migrationTarget = "Replace Oracle package call with direct INSERT using PostgreSQL sequence and RETURNING clause"
    )
    @OracleDependency(
        domain = DependencyDomain.EMBEDDED_SQL,
        category = "SEQUENCE_NEXTVAL",
        description = "Credit card number incorporates credit_card_seq.NEXTVAL generated inside the CREDIT_CARD_PKG",
        complexity = MigrationComplexity.LOW,
        automationLevel = AutomationLevel.FULL,
        migrationTarget = "Replace Oracle sequence with PostgreSQL SEQUENCE or application-tier UUID generation"
    )
    public CreditCardDTO applyForCard(Long customerId, String cardType,
                                      BigDecimal requestedLimit) {
        log.info("Card application: customer={}, type={}, requestedLimit={}",
            customerId, cardType, requestedLimit);

        validateCardType(cardType);
        if (customerId == null) {
            throw new MeridianBankException("VALIDATION_ERROR",
                "Customer ID is required for card application.", 400);
        }
        if (requestedLimit == null || requestedLimit.compareTo(BigDecimal.ZERO) <= 0) {
            throw new MeridianBankException("VALIDATION_ERROR",
                "Requested credit limit must be positive.", 400);
        }

        // ORACLE-DEP [PLSQL_INVOCATION:PACKAGE_CALL] Calls CREDIT_CARD_PKG.APPLY_FOR_CARD
        Long newCardId;
        try {
            newCardId = creditCardOracleDAO.applyForCard(customerId, cardType, requestedLimit);
        } catch (SQLException e) {
            // ORACLE-DEP [EMBEDDED_SQL:ORACLE_ERROR_CODES] ORA-00001 = duplicate card number
            throw errorTranslator.translateSQLException(e);
        }

        log.info("Card {} applied with ID {}", cardType, newCardId);

        CreditCardDTO dto = new CreditCardDTO();
        dto.setCardId(newCardId);
        dto.setCustomerId(customerId);
        dto.setCardType(cardType.toUpperCase());
        dto.setRequestedCreditLimit(requestedLimit);
        dto.setStatus("PENDING");
        dto.setIssueDate(LocalDate.now());
        return dto;
    }

    /**
     * Retrieves a credit card by primary key.
     */
    @Transactional(readOnly = true)
    public CreditCardDTO getCardById(Long cardId) {
        CreditCardEntity entity = creditCardRepository.findById(cardId)
            .orElseThrow(() -> new MeridianBankException("NOT_FOUND",
                "Credit card not found with ID: " + cardId, 404));
        return entityToDto(entity);
    }

    /**
     * Retrieves all credit cards for a customer.
     */
    @Transactional(readOnly = true)
    public List<CreditCardDTO> getCardsByCustomer(Long customerId) {
        List<CreditCardEntity> entities = creditCardRepository.findByCustomerId(customerId);
        List<CreditCardDTO> results = new ArrayList<>();
        for (CreditCardEntity e : entities) {
            results.add(entityToDto(e));
        }
        return results;
    }

    /**
     * Returns cards ordered by reward tier using Oracle DECODE in the native query.
     */
    @OracleDependency(
        domain = DependencyDomain.EMBEDDED_SQL,
        category = "DECODE_FUNCTION",
        description = "Cards ordered by tier priority using DECODE(rewards_tier,'PLATINUM',1,'GOLD',2,'SILVER',3,'BRONZE',4,5)",
        complexity = MigrationComplexity.LOW,
        automationLevel = AutomationLevel.FULL,
        migrationTarget = "Replace DECODE with CASE WHEN rewards_tier='PLATINUM' THEN 1 ... END in PostgreSQL"
    )
    @Transactional(readOnly = true)
    public List<CreditCardDTO> getCardsByCustomerOrderedByTier(Long customerId) {
        // ORACLE-DEP [EMBEDDED_SQL:DECODE_FUNCTION] Native query: ORDER BY DECODE(rewards_tier,...)
        List<CreditCardEntity> entities =
            creditCardRepository.findByCustomerOrderedByTier(customerId);
        List<CreditCardDTO> results = new ArrayList<>();
        for (CreditCardEntity e : entities) {
            results.add(entityToDto(e));
        }
        return results;
    }

    // -----------------------------------------------------------------------
    // Payments
    // -----------------------------------------------------------------------

    /**
     * Processes a credit card payment.
     *
     * The DAO calls CREDIT_CARD_PKG.PROCESS_PAYMENT and returns the new balance
     * via an OracleTypes.NUMBER OUT parameter.
     */
    @OracleDependency(
        domain = DependencyDomain.PLSQL_INVOCATION,
        category = "PACKAGE_CALL",
        description = "Card payment processed via CREDIT_CARD_PKG.PROCESS_PAYMENT with OracleTypes.NUMBER OUT for new balance",
        complexity = MigrationComplexity.HIGH,
        automationLevel = AutomationLevel.PARTIAL,
        migrationTarget = "Replace package call with direct UPDATE and SELECT; return new balance from application logic"
    )
    public CreditCardDTO processPayment(Long cardId, BigDecimal paymentAmount) {
        log.info("Processing payment of {} on card {}", paymentAmount, cardId);

        if (paymentAmount == null || paymentAmount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new MeridianBankException("VALIDATION_ERROR",
                "Payment amount must be positive.", 400);
        }

        CreditCardEntity entity = creditCardRepository.findById(cardId)
            .orElseThrow(() -> new MeridianBankException("NOT_FOUND",
                "Credit card not found: " + cardId, 404));

        if (!"ACTIVE".equals(entity.getStatus())) {
            throw new MeridianBankException("CARD_NOT_ACTIVE",
                "Cannot process payment — card status is: " + entity.getStatus(), 422);
        }

        // ORACLE-DEP [PLSQL_INVOCATION:PACKAGE_CALL] CreditCardOracleDAO.processPayment calls
        // CREDIT_CARD_PKG.PROCESS_PAYMENT(p_card_id, p_amount, p_new_balance OUT)
        BigDecimal newBalance;
        try {
            newBalance = creditCardOracleDAO.processPayment(cardId, paymentAmount);
        } catch (SQLException e) {
            throw errorTranslator.translateSQLException(e);
        }

        entity.setCurrentBalance(newBalance);
        CreditCardEntity saved = creditCardRepository.save(entity);
        log.info("Payment processed: card={}, newBalance={}", cardId, newBalance);
        return entityToDto(saved);
    }

    // -----------------------------------------------------------------------
    // Analytical / Oracle-specific query methods
    // -----------------------------------------------------------------------

    /**
     * Returns a LISTAGG-produced summary string of all cards for a customer.
     *
     * The DAO executes:
     *   SELECT LISTAGG(card_type || ':' || status, ', ')
     *          WITHIN GROUP (ORDER BY card_type)
     *   FROM credit_cards
     *   WHERE customer_id = :customerId
     *
     * LISTAGG is Oracle's string aggregation function; there is no direct SQL standard
     * equivalent — PostgreSQL uses STRING_AGG.
     */
    @OracleDependency(
        domain = DependencyDomain.EMBEDDED_SQL,
        category = "LISTAGG_FUNCTION",
        description = "Card summary uses LISTAGG(card_type||':'||status,', ') WITHIN GROUP (ORDER BY card_type)",
        complexity = MigrationComplexity.MEDIUM,
        automationLevel = AutomationLevel.FULL,
        migrationTarget = "Replace LISTAGG(val,sep) WITHIN GROUP (ORDER BY ...) with STRING_AGG(val,sep ORDER BY ...) in PostgreSQL"
    )
    @Transactional(readOnly = true)
    public String getCardSummaryWithListagg(Long customerId) {
        // ORACLE-DEP [EMBEDDED_SQL:LISTAGG_FUNCTION] CreditCardOracleDAO.getCardSummary
        // executes LISTAGG query directly via OracleResultSet
        try {
            return creditCardOracleDAO.getCardSummary(customerId);
        } catch (SQLException e) {
            throw errorTranslator.translateSQLException(e);
        }
    }

    /**
     * Returns the reward tier hierarchy path for a card using Oracle CONNECT BY.
     *
     * The DAO executes a hierarchical query:
     *   SELECT SYS_CONNECT_BY_PATH(tier_name, '/') AS tier_path, LEVEL AS tier_level
     *   FROM reward_tiers
     *   START WITH tier_code = (SELECT rewards_tier FROM credit_cards WHERE card_id = :cardId)
     *   CONNECT BY PRIOR parent_tier = tier_code
     *
     * Returns the ancestry path from the current tier up to the root (BRONZE).
     */
    @OracleDependency(
        domain = DependencyDomain.EMBEDDED_SQL,
        category = "CONNECT_BY",
        description = "Reward tier hierarchy uses CONNECT BY PRIOR with SYS_CONNECT_BY_PATH and LEVEL pseudocolumn",
        complexity = MigrationComplexity.HIGH,
        automationLevel = AutomationLevel.PARTIAL,
        migrationTarget = "Replace CONNECT BY PRIOR with recursive CTE (WITH RECURSIVE) in PostgreSQL"
    )
    @OracleDependency(
        domain = DependencyDomain.EMBEDDED_SQL,
        category = "SYS_CONNECT_BY_PATH",
        description = "SYS_CONNECT_BY_PATH(tier_name,'/') builds the full hierarchy path string from leaf to root",
        complexity = MigrationComplexity.HIGH,
        automationLevel = AutomationLevel.PARTIAL,
        migrationTarget = "Replace with string_agg() over recursive CTE levels or custom path-building in PostgreSQL"
    )
    @Transactional(readOnly = true)
    public List<String> getRewardTierPath(Long cardId) {
        // ORACLE-DEP [EMBEDDED_SQL:CONNECT_BY] CreditCardOracleDAO.getRewardTierPath uses
        // CONNECT BY PRIOR parent_tier = tier_code with START WITH clause
        // ORACLE-DEP [EMBEDDED_SQL:SYS_CONNECT_BY_PATH] Path built using SYS_CONNECT_BY_PATH()
        try {
            return creditCardOracleDAO.getRewardTierPath(cardId);
        } catch (SQLException e) {
            throw errorTranslator.translateSQLException(e);
        }
    }

    /**
     * Returns spending percentile data for all active cards using Oracle NTILE and CUME_DIST.
     *
     * The DAO executes:
     *   SELECT card_id, customer_id, current_balance,
     *          NTILE(4) OVER (ORDER BY current_balance DESC) AS spending_quartile,
     *          CUME_DIST() OVER (ORDER BY current_balance DESC) AS cumulative_dist
     *   FROM credit_cards
     *   WHERE status = 'ACTIVE'
     *
     * Used for risk analysis and targeted marketing segmentation.
     */
    @OracleDependency(
        domain = DependencyDomain.EMBEDDED_SQL,
        category = "NTILE_FUNCTION",
        description = "Spending percentile analysis uses NTILE(4) OVER (ORDER BY balance DESC) for quartile segmentation",
        complexity = MigrationComplexity.MEDIUM,
        automationLevel = AutomationLevel.FULL,
        migrationTarget = "PostgreSQL supports NTILE(n) OVER (...) window function natively"
    )
    @OracleDependency(
        domain = DependencyDomain.EMBEDDED_SQL,
        category = "CUME_DIST_FUNCTION",
        description = "Cumulative distribution uses CUME_DIST() OVER (ORDER BY balance DESC) analytic",
        complexity = MigrationComplexity.MEDIUM,
        automationLevel = AutomationLevel.FULL,
        migrationTarget = "PostgreSQL supports CUME_DIST() OVER (...) window function natively"
    )
    @Transactional(readOnly = true)
    public List<CreditCardDTO> getSpendingPercentiles() {
        // ORACLE-DEP [EMBEDDED_SQL:NTILE_FUNCTION] CreditCardOracleDAO.getSpendingPercentiles uses NTILE(4)
        // ORACLE-DEP [EMBEDDED_SQL:CUME_DIST_FUNCTION] CreditCardOracleDAO uses CUME_DIST() analytic
        try {
            return creditCardOracleDAO.getSpendingPercentiles();
        } catch (SQLException e) {
            throw errorTranslator.translateSQLException(e);
        }
    }

    /**
     * Returns cards expiring within the given number of months.
     *
     * The DAO executes:
     *   SELECT * FROM credit_cards
     *   WHERE expiry_date <= ADD_MONTHS(SYSDATE, :withinMonths)
     *   AND expiry_date >= SYSDATE
     *   AND status = 'ACTIVE'
     *
     * ADD_MONTHS is Oracle-specific; PostgreSQL uses CURRENT_DATE + (n || ' months')::interval.
     */
    @OracleDependency(
        domain = DependencyDomain.EMBEDDED_SQL,
        category = "ADD_MONTHS_FUNCTION",
        description = "Expiry search uses ADD_MONTHS(SYSDATE, :withinMonths) to compute the look-ahead date",
        complexity = MigrationComplexity.LOW,
        automationLevel = AutomationLevel.FULL,
        migrationTarget = "Replace ADD_MONTHS(SYSDATE, n) with CURRENT_DATE + (n || ' months')::interval in PostgreSQL"
    )
    @Transactional(readOnly = true)
    public List<CreditCardDTO> findCardsExpiringSoon(int withinMonths) {
        if (withinMonths <= 0 || withinMonths > 24) withinMonths = 3;
        // ORACLE-DEP [EMBEDDED_SQL:ADD_MONTHS_FUNCTION] CreditCardOracleDAO.findCardsExpiringSoon
        // uses ADD_MONTHS(SYSDATE, :withinMonths) for the expiry window upper bound
        try {
            return creditCardOracleDAO.findCardsExpiringSoon(withinMonths);
        } catch (SQLException e) {
            throw errorTranslator.translateSQLException(e);
        }
    }

    /**
     * Returns cards ordered by type with balance percentile information.
     * The repository native query uses DECODE for card type sort order and
     * LISTAGG for building comma-separated reward tier strings.
     */
    @OracleDependency(
        domain = DependencyDomain.EMBEDDED_SQL,
        category = "LISTAGG_FUNCTION",
        description = "Card type summary uses LISTAGG(rewards_tier,',') WITHIN GROUP (ORDER BY rewards_tier)",
        complexity = MigrationComplexity.MEDIUM,
        automationLevel = AutomationLevel.FULL,
        migrationTarget = "Replace LISTAGG with STRING_AGG in PostgreSQL"
    )
    @Transactional(readOnly = true)
    public List<Object[]> getCardsOrderedByType() {
        // ORACLE-DEP [EMBEDDED_SQL:LISTAGG_FUNCTION] Repository native query: LISTAGG reward tiers
        // ORACLE-DEP [EMBEDDED_SQL:DECODE_FUNCTION] Repository native query: DECODE card_type for sort
        return creditCardRepository.findCustomerCardSummary();
    }

    /**
     * Returns balance percentile information for all cards.
     * The repository native query uses Oracle CUME_DIST() and PERCENT_RANK() analytics.
     */
    @OracleDependency(
        domain = DependencyDomain.EMBEDDED_SQL,
        category = "CUME_DIST_FUNCTION",
        description = "Card balance percentile uses CUME_DIST() OVER (ORDER BY current_balance) analytic",
        complexity = MigrationComplexity.MEDIUM,
        automationLevel = AutomationLevel.FULL,
        migrationTarget = "PostgreSQL supports CUME_DIST() OVER window functions natively"
    )
    @Transactional(readOnly = true)
    public List<Object[]> getCardsWithBalancePercentile() {
        // ORACLE-DEP [EMBEDDED_SQL:CUME_DIST_FUNCTION] Native query: CUME_DIST() OVER (ORDER BY balance)
        return creditCardRepository.findCardsWithBalancePercentile();
    }

    /**
     * Returns utilisation rank for all customer cards.
     * The repository native query uses RANK() OVER (PARTITION BY customer_id ORDER BY utilisation DESC).
     */
    @OracleDependency(
        domain = DependencyDomain.EMBEDDED_SQL,
        category = "ANALYTIC_FUNCTIONS",
        description = "Card utilisation rank uses RANK() OVER (PARTITION BY customer_id ORDER BY utilisation DESC)",
        complexity = MigrationComplexity.MEDIUM,
        automationLevel = AutomationLevel.FULL,
        migrationTarget = "PostgreSQL supports RANK() OVER (PARTITION BY ... ORDER BY ...) window functions natively"
    )
    @Transactional(readOnly = true)
    public List<Object[]> getCardsWithUtilizationRank(Long customerId) {
        // ORACLE-DEP [EMBEDDED_SQL:ANALYTIC_FUNCTIONS] Native query: RANK() OVER PARTITION BY customer_id
        return creditCardRepository.findCardsWithUtilizationRank(customerId);
    }

    /**
     * Returns cards with age metrics (months since issue).
     * The repository native query uses MONTHS_BETWEEN(SYSDATE, issue_date) for age calculation.
     */
    @OracleDependency(
        domain = DependencyDomain.EMBEDDED_SQL,
        category = "MONTHS_BETWEEN_FUNCTION",
        description = "Card age metric uses MONTHS_BETWEEN(SYSDATE, issue_date) Oracle date function",
        complexity = MigrationComplexity.LOW,
        automationLevel = AutomationLevel.FULL,
        migrationTarget = "Replace MONTHS_BETWEEN(d1,d2) with EXTRACT(YEAR FROM AGE(d2,d1))*12 + EXTRACT(MONTH FROM AGE(d2,d1)) in PostgreSQL"
    )
    @Transactional(readOnly = true)
    public List<Object[]> getCardsWithAgeMetrics() {
        // ORACLE-DEP [EMBEDDED_SQL:MONTHS_BETWEEN_FUNCTION] Native query: MONTHS_BETWEEN(SYSDATE, issue_date)
        return creditCardRepository.findCardsWithAgeMetrics();
    }

    /**
     * Returns cards expiring within a window using the repository native query.
     * Uses ADD_MONTHS(SYSDATE, :validityMonths) and MONTHS_BETWEEN for remaining life.
     */
    @OracleDependency(
        domain = DependencyDomain.EMBEDDED_SQL,
        category = "ADD_MONTHS_FUNCTION",
        description = "Repository expiry query uses ADD_MONTHS(SYSDATE, :validityMonths) for upper bound",
        complexity = MigrationComplexity.LOW,
        automationLevel = AutomationLevel.FULL,
        migrationTarget = "Replace ADD_MONTHS with CURRENT_DATE + (:validityMonths || ' months')::interval in PostgreSQL"
    )
    @Transactional(readOnly = true)
    public List<Object[]> getCardsExpiringSoonFromRepository(int validityMonths) {
        // ORACLE-DEP [EMBEDDED_SQL:ADD_MONTHS_FUNCTION] Repository native: ADD_MONTHS(SYSDATE, :validityMonths)
        return creditCardRepository.findCardsExpiringSoon(validityMonths);
    }

    /**
     * Returns the top N highest balance cards.
     * The repository native query uses ROWNUM for Oracle-style top-N filtering:
     *   SELECT * FROM (...ORDER BY balance DESC) WHERE ROWNUM <= :topN
     */
    @OracleDependency(
        domain = DependencyDomain.EMBEDDED_SQL,
        category = "ROWNUM_PAGINATION",
        description = "Top-N cards filtered using classic Oracle ROWNUM <= :topN in outer query",
        complexity = MigrationComplexity.MEDIUM,
        automationLevel = AutomationLevel.FULL,
        migrationTarget = "Replace ROWNUM with LIMIT :topN or FETCH FIRST :topN ROWS ONLY in PostgreSQL"
    )
    @Transactional(readOnly = true)
    public List<CreditCardDTO> getTopHighBalanceCards(int topN) {
        if (topN <= 0 || topN > 100) topN = 10;
        // ORACLE-DEP [EMBEDDED_SQL:ROWNUM_PAGINATION] Native query: WHERE ROWNUM <= :topN
        List<CreditCardEntity> entities = creditCardRepository.findTopHighBalanceCards(topN);
        List<CreditCardDTO> results = new ArrayList<>();
        for (CreditCardEntity e : entities) {
            results.add(entityToDto(e));
        }
        return results;
    }

    // -----------------------------------------------------------------------
    // Private helpers
    // -----------------------------------------------------------------------

    private CreditCardDTO entityToDto(CreditCardEntity entity) {
        CreditCardDTO dto = new CreditCardDTO();
        dto.setCardId(entity.getCardId());
        // Always mask the card number in responses
        String raw = entity.getCardNumber();
        dto.setCardNumberMasked(raw != null && raw.length() >= 4
            ? "****-****-****-" + raw.substring(raw.length() - 4)
            : "****-****-****-****");
        dto.setCardType(entity.getCardType());
        dto.setStatus(entity.getStatus());
        dto.setCreditLimit(entity.getCreditLimit());
        dto.setCurrentBalance(entity.getCurrentBalance());
        if (entity.getCreditLimit() != null && entity.getCurrentBalance() != null) {
            dto.setAvailableCredit(entity.getCreditLimit().subtract(entity.getCurrentBalance()));
        }
        dto.setRewardPoints(entity.getRewardPoints() != null ? entity.getRewardPoints().intValue() : null);
        // entity has no rewardsTier field; derived from DECODE query, set to null for basic mapping
        dto.setRewardsTier(null);
        dto.setExpiryDate(entity.getExpiryDate());
        dto.setIssueDate(entity.getIssuedDate()); // entity field is 'issuedDate'
        dto.setAccountId(entity.getAccountId());
        dto.setCustomerId(entity.getCustomerId());
        // entity.createdDate is LocalDate; DTO expects LocalDateTime
        dto.setCreatedDate(null); // CreditCardEntity has no createdDate field
        return dto;
    }

    private void validateCardType(String cardType) {
        if (cardType == null) {
            throw new MeridianBankException("VALIDATION_ERROR",
                "Card type is required.", 400);
        }
        switch (cardType.toUpperCase()) {
            case "VISA", "MASTERCARD", "AMEX" -> { /* valid */ }
            default -> throw new MeridianBankException("VALIDATION_ERROR",
                "Invalid card type: " + cardType + ". Must be VISA, MASTERCARD, or AMEX.", 400);
        }
    }
}
