package com.meridianbank.entity;

import com.meridianbank.annotation.AutomationLevel;
import com.meridianbank.annotation.DependencyDomain;
import com.meridianbank.annotation.MigrationComplexity;
import com.meridianbank.annotation.OracleDependencies;
import com.meridianbank.annotation.OracleDependency;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.NamedNativeQueries;
import jakarta.persistence.NamedNativeQuery;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * JPA entity representing a Meridian Bank credit card.
 *
 * <p>Oracle dependencies present in this entity:
 * <ul>
 *   <li>Oracle sequence MERIDIAN.CREDIT_CARD_SEQ for PK generation</li>
 *   <li>NUMBER(12,2) for credit limit and current balance (monetary amounts)</li>
 *   <li>NUMBER(12) for reward points (large integer)</li>
 *   <li>DECODE() for tier name derivation from card_type in named queries</li>
 *   <li>LISTAGG() analytic function aggregating card numbers per customer</li>
 *   <li>SYSDATE + ADD_MONTHS() for expiry window calculation</li>
 *   <li>NTILE(4) window function for spending quartile assignment</li>
 *   <li>CUME_DIST() window function for cumulative spending percentile</li>
 * </ul>
 *
 * <p>Migration notes:
 * <ul>
 *   <li>DECODE() → CASE WHEN ... END in PostgreSQL</li>
 *   <li>LISTAGG(x, y) WITHIN GROUP (ORDER BY ...) → STRING_AGG(x, y ORDER BY ...) in PostgreSQL</li>
 *   <li>SYSDATE → CURRENT_DATE in PostgreSQL</li>
 *   <li>ADD_MONTHS(date, n) → date + (n || ' months')::interval in PostgreSQL</li>
 *   <li>NTILE() and CUME_DIST() are ANSI SQL and exist in PostgreSQL unchanged</li>
 * </ul>
 */
// ORACLE-DEP [EMBEDDED_SQL:ADD_MONTHS] ADD_MONTHS(SYSDATE, n) used in expiry window query
// ORACLE-DEP [EMBEDDED_SQL:LISTAGG] LISTAGG aggregates card numbers within customer partition
// ORACLE-DEP [EMBEDDED_SQL:NTILE] NTILE(4) assigns spending quartile to each card
// ORACLE-DEP [EMBEDDED_SQL:CUME_DIST] CUME_DIST() computes cumulative spending percentile
// ORACLE-DEP [ORM_DATA_ACCESS:SEQUENCE_GENERATOR] PK via Oracle sequence MERIDIAN.CREDIT_CARD_SEQ
@OracleDependencies({
    @OracleDependency(
        domain          = DependencyDomain.EMBEDDED_SQL,
        category        = "ADD_MONTHS",
        description     = "ADD_MONTHS(SYSDATE, :monthsAhead) calculates a future date by adding a "
                        + "whole-month offset. Oracle ADD_MONTHS handles month-end edge cases "
                        + "(e.g., Jan 31 + 1 month = Feb 28/29). This is an Oracle-proprietary "
                        + "function with no ANSI SQL equivalent.",
        complexity      = MigrationComplexity.LOW,
        automationLevel = AutomationLevel.FULL,
        migrationTarget = "Replace ADD_MONTHS(SYSDATE, n) with "
                        + "CURRENT_DATE + (n || ' months')::interval in PostgreSQL. "
                        + "Month-end edge case behaviour is the same."
    ),
    @OracleDependency(
        domain          = DependencyDomain.EMBEDDED_SQL,
        category        = "LISTAGG",
        description     = "LISTAGG(card_number, ', ') WITHIN GROUP (ORDER BY issued_date) OVER "
                        + "(PARTITION BY customer_id) is an Oracle analytic aggregate that produces "
                        + "a delimited list of values within each partition. The OVER() clause makes "
                        + "it a window function rather than a pure aggregate.",
        complexity      = MigrationComplexity.MEDIUM,
        automationLevel = AutomationLevel.PARTIAL,
        migrationTarget = "Replace LISTAGG(x, delim) WITHIN GROUP (ORDER BY y) with "
                        + "STRING_AGG(x, delim ORDER BY y) in PostgreSQL. "
                        + "The window (OVER PARTITION BY ...) form requires wrapping: "
                        + "STRING_AGG(x, delim ORDER BY y) OVER (PARTITION BY z)."
    ),
    @OracleDependency(
        domain          = DependencyDomain.EMBEDDED_SQL,
        category        = "NTILE",
        description     = "NTILE(4) OVER (ORDER BY current_balance DESC) distributes rows evenly "
                        + "into 4 buckets (quartiles). NTILE is ANSI SQL:2003 and is supported "
                        + "by most major databases. However, it is documented here because it "
                        + "appears alongside Oracle-only constructs in the same query.",
        complexity      = MigrationComplexity.LOW,
        automationLevel = AutomationLevel.FULL,
        migrationTarget = "NTILE() is compatible between Oracle and PostgreSQL — no change needed."
    ),
    @OracleDependency(
        domain          = DependencyDomain.EMBEDDED_SQL,
        category        = "CUME_DIST",
        description     = "CUME_DIST() OVER (ORDER BY current_balance) computes the cumulative "
                        + "distribution (fraction of rows ≤ current value). CUME_DIST is ANSI SQL "
                        + "and compatible with PostgreSQL, but documented here as it co-occurs with "
                        + "Oracle-specific syntax in the same reporting query.",
        complexity      = MigrationComplexity.LOW,
        automationLevel = AutomationLevel.FULL,
        migrationTarget = "CUME_DIST() is compatible between Oracle and PostgreSQL — no change needed."
    ),
    @OracleDependency(
        domain          = DependencyDomain.ORM_DATA_ACCESS,
        category        = "SEQUENCE_GENERATOR",
        description     = "Credit card primary key generated via Oracle sequence MERIDIAN.CREDIT_CARD_SEQ.",
        complexity      = MigrationComplexity.LOW,
        automationLevel = AutomationLevel.FULL,
        migrationTarget = "Point @SequenceGenerator to a PostgreSQL sequence or use GenerationType.IDENTITY"
    )
})
@Entity
@Table(name = "CREDIT_CARDS", schema = "MERIDIAN")
@NamedNativeQueries({
    // ORACLE-DEP [EMBEDDED_SQL:DECODE_FUNCTION] DECODE maps card_type code to reward tier name
    // ORACLE-DEP [EMBEDDED_SQL:LISTAGG] Aggregates all card numbers per customer into one string
    @NamedNativeQuery(
        name  = "CreditCardEntity.findWithRewardTierDecode",
        query = "SELECT c.card_id, c.card_number, c.account_id, c.customer_id, "
              + "c.credit_limit, c.current_balance, c.expiry_date, c.status, "
              + "c.reward_points, c.card_type, c.issued_date, "
              + "DECODE(c.card_type, "                                 // ORACLE-DEP: DECODE — CASE WHEN in PostgreSQL
              + "  'STANDARD',  'Bronze', "
              + "  'GOLD',      'Silver', "
              + "  'PLATINUM',  'Gold', "
              + "  'TITANIUM',  'Platinum', "
              + "  'Unknown') AS tier_name, "
              + "LISTAGG(c.card_number, ', ') "                        // ORACLE-DEP: LISTAGG — STRING_AGG in PostgreSQL
              + "  WITHIN GROUP (ORDER BY c.issued_date) "
              + "  OVER (PARTITION BY c.customer_id) AS customer_cards "
              + "FROM MERIDIAN.CREDIT_CARDS c "
              + "WHERE c.customer_id = :customerId"
    ),
    // ORACLE-DEP [EMBEDDED_SQL:SYSDATE] SYSDATE as current date anchor for expiry window
    // ORACLE-DEP [EMBEDDED_SQL:ADD_MONTHS] ADD_MONTHS calculates future date offset in months
    @NamedNativeQuery(
        name  = "CreditCardEntity.findExpiringCards",
        query = "SELECT c.card_id, c.card_number, c.account_id, c.customer_id, "
              + "c.credit_limit, c.current_balance, c.expiry_date, c.status, "
              + "c.reward_points, c.card_type, c.issued_date "
              + "FROM MERIDIAN.CREDIT_CARDS c "
              + "WHERE c.expiry_date BETWEEN SYSDATE "                 // ORACLE-DEP: SYSDATE
              + "  AND ADD_MONTHS(SYSDATE, :monthsAhead) "             // ORACLE-DEP: ADD_MONTHS
              + "AND c.status = 'ACTIVE'"
    ),
    // ORACLE-DEP [EMBEDDED_SQL:NTILE] Assigns spending quartile (1=highest, 4=lowest)
    // ORACLE-DEP [EMBEDDED_SQL:CUME_DIST] Computes cumulative spending percentile
    @NamedNativeQuery(
        name  = "CreditCardEntity.findSpendingPercentile",
        query = "SELECT c.card_id, c.card_number, c.current_balance, "
              + "NTILE(4) OVER (ORDER BY c.current_balance DESC) AS spending_quartile, "  // ORACLE-DEP: NTILE
              + "ROUND(CUME_DIST() OVER (ORDER BY c.current_balance) * 100, 2) AS cume_pct " // ORACLE-DEP: CUME_DIST
              + "FROM MERIDIAN.CREDIT_CARDS c "
              + "WHERE c.status = 'ACTIVE'"
    )
})
public class CreditCardEntity {

    // ORACLE-DEP [ORM_DATA_ACCESS:SEQUENCE_GENERATOR] Oracle sequence for PK generation
    @Id
    @SequenceGenerator(
        name           = "creditCardSeqGen",
        sequenceName   = "MERIDIAN.CREDIT_CARD_SEQ",   // ORACLE-DEP: Oracle sequence reference
        allocationSize = 1
    )
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "creditCardSeqGen")
    @Column(name = "CARD_ID")
    private Long cardId;

    /**
     * 16-digit card number stored as a VARCHAR2 to preserve leading zeros and avoid
     * numeric overflow. The card number is masked in all display contexts.
     *
     * ORACLE-DEP [EMBEDDED_SQL:VARCHAR2_TYPE] Card number stored as VARCHAR2(20)
     */
    // ORACLE-DEP [EMBEDDED_SQL:VARCHAR2_TYPE] VARCHAR2(20) — migrate to VARCHAR(20)
    @Column(name = "CARD_NUMBER", nullable = false, unique = true, columnDefinition = "VARCHAR2(20)")
    private String cardNumber;

    @Column(name = "ACCOUNT_ID", nullable = false)
    private Long accountId;

    @Column(name = "CUSTOMER_ID", nullable = false)
    private Long customerId;

    /**
     * Approved credit limit.
     * Oracle NUMBER(12,2) — up to 10 digits before decimal, 2 after.
     *
     * ORACLE-DEP [EMBEDDED_SQL:NUMBER_TYPE] Oracle NUMBER(12,2) monetary amount
     */
    // ORACLE-DEP [EMBEDDED_SQL:NUMBER_TYPE] NUMBER(12,2) — NUMERIC(12,2) in PostgreSQL
    @Column(name = "CREDIT_LIMIT", nullable = false, columnDefinition = "NUMBER(12,2)")
    private BigDecimal creditLimit;

    /**
     * Current outstanding balance on the card.
     * Oracle NUMBER(12,2) — same precision as credit limit for easy comparison.
     *
     * ORACLE-DEP [EMBEDDED_SQL:NUMBER_TYPE] Oracle NUMBER(12,2) monetary amount
     */
    // ORACLE-DEP [EMBEDDED_SQL:NUMBER_TYPE] NUMBER(12,2) — NUMERIC(12,2) in PostgreSQL
    @Column(name = "CURRENT_BALANCE", nullable = false, columnDefinition = "NUMBER(12,2)")
    private BigDecimal currentBalance;

    // ORACLE-DEP [EMBEDDED_SQL:DATE_TYPE] Oracle DATE for card expiry (month/year precision)
    @Column(name = "EXPIRY_DATE", nullable = false, columnDefinition = "DATE")
    private LocalDate expiryDate;

    // ORACLE-DEP [EMBEDDED_SQL:VARCHAR2_TYPE] Status VARCHAR2 used in filtering queries
    @Column(name = "STATUS", nullable = false, columnDefinition = "VARCHAR2(20)")
    private String status;

    /**
     * Accumulated reward points. Can grow large for high-spending customers.
     * Oracle NUMBER(12) — integer, no decimal places.
     *
     * ORACLE-DEP [EMBEDDED_SQL:NUMBER_TYPE] Oracle NUMBER(12) integer — BIGINT in PostgreSQL
     */
    // ORACLE-DEP [EMBEDDED_SQL:NUMBER_TYPE] NUMBER(12) integer reward points — BIGINT in PostgreSQL
    @Column(name = "REWARD_POINTS", columnDefinition = "NUMBER(12)")
    private Long rewardPoints;

    // ORACLE-DEP [EMBEDDED_SQL:VARCHAR2_TYPE] Card type used in DECODE tier-name lookup
    @Column(name = "CARD_TYPE", nullable = false, columnDefinition = "VARCHAR2(20)")
    private String cardType;

    // ORACLE-DEP [EMBEDDED_SQL:DATE_TYPE] Oracle DATE for card issue date
    @Column(name = "ISSUED_DATE", nullable = false, columnDefinition = "DATE")
    private LocalDate issuedDate;
    public CreditCardEntity() {
    }


    public Long getCardId() {
        return cardId;
    }

    public void setCardId(Long cardId) {
        this.cardId = cardId;
    }

    public String getCardNumber() {
        return cardNumber;
    }

    public void setCardNumber(String cardNumber) {
        this.cardNumber = cardNumber;
    }

    public Long getAccountId() {
        return accountId;
    }

    public void setAccountId(Long accountId) {
        this.accountId = accountId;
    }

    public Long getCustomerId() {
        return customerId;
    }

    public void setCustomerId(Long customerId) {
        this.customerId = customerId;
    }

    public BigDecimal getCreditLimit() {
        return creditLimit;
    }

    public void setCreditLimit(BigDecimal creditLimit) {
        this.creditLimit = creditLimit;
    }

    public BigDecimal getCurrentBalance() {
        return currentBalance;
    }

    public void setCurrentBalance(BigDecimal currentBalance) {
        this.currentBalance = currentBalance;
    }

    public LocalDate getExpiryDate() {
        return expiryDate;
    }

    public void setExpiryDate(LocalDate expiryDate) {
        this.expiryDate = expiryDate;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Long getRewardPoints() {
        return rewardPoints;
    }

    public void setRewardPoints(Long rewardPoints) {
        this.rewardPoints = rewardPoints;
    }

    public String getCardType() {
        return cardType;
    }

    public void setCardType(String cardType) {
        this.cardType = cardType;
    }

    public LocalDate getIssuedDate() {
        return issuedDate;
    }

    public void setIssuedDate(LocalDate issuedDate) {
        this.issuedDate = issuedDate;
    }

}
