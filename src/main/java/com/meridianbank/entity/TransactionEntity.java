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
import java.time.OffsetDateTime;

/**
 * JPA entity representing a financial transaction on a Meridian Bank account.
 *
 * <p>Oracle dependencies present in this entity:
 * <ul>
 *   <li>TIMESTAMP WITH TIME ZONE column type for {@code transactionDate} — Oracle stores
 *       both the instant and the originating time zone offset, enabling accurate audit trails
 *       for cross-timezone transactions. Mapped to {@link OffsetDateTime}.</li>
 *   <li>Oracle optimizer hint {@code /*+ INDEX(t IDX_TXN_ACCOUNT_DATE) *\/} in native query
 *       to force use of the composite index on (ACCOUNT_ID, TRANSACTION_DATE).</li>
 *   <li>TRUNC(date) for stripping the time component when comparing dates in a range scan.</li>
 *   <li>NVL() for nullable date parameters — defaults to a rolling 90-day window.</li>
 *   <li>SYSDATE as the Oracle-proprietary current-date function.</li>
 *   <li>TO_CHAR() for formatting the timestamp into a display string.</li>
 *   <li>ROWNUM for top-10 pagination of recent transactions.</li>
 *   <li>Oracle sequence MERIDIAN.TRANSACTION_SEQ for PK generation.</li>
 * </ul>
 *
 * <p>Migration notes:
 * <ul>
 *   <li>TIMESTAMP WITH TIME ZONE → TIMESTAMPTZ in PostgreSQL (semantically equivalent)</li>
 *   <li>Oracle hints → remove or replace with PostgreSQL planner hints (pg_hint_plan extension)</li>
 *   <li>TRUNC(date) → DATE_TRUNC('day', timestamp) in PostgreSQL</li>
 *   <li>SYSDATE → CURRENT_DATE or NOW() in PostgreSQL</li>
 *   <li>NVL() → COALESCE() in PostgreSQL</li>
 *   <li>TO_CHAR() → TO_CHAR() (compatible but format codes differ; HH24→HH24 is fine)</li>
 *   <li>ROWNUM ≤ n → LIMIT n in PostgreSQL</li>
 * </ul>
 */
// ORACLE-DEP [EMBEDDED_SQL:TIMESTAMP_TZ] transactionDate uses Oracle TIMESTAMP WITH TIME ZONE
// ORACLE-DEP [EMBEDDED_SQL:ORACLE_HINTS] Query hint /*+ INDEX(...) */ in named native query
// ORACLE-DEP [EMBEDDED_SQL:TRUNC_DATE] TRUNC(date) to strip time for date-range comparison
// ORACLE-DEP [EMBEDDED_SQL:SYSDATE] SYSDATE used as current-date default in date range params
// ORACLE-DEP [EMBEDDED_SQL:TO_CHAR] TO_CHAR formats transaction timestamp for display string
// ORACLE-DEP [ORM_DATA_ACCESS:SEQUENCE_GENERATOR] PK via Oracle sequence MERIDIAN.TRANSACTION_SEQ
@OracleDependencies({
    @OracleDependency(
        domain          = DependencyDomain.EMBEDDED_SQL,
        category        = "TIMESTAMP_TZ",
        description     = "transactionDate column declared as Oracle 'TIMESTAMP WITH TIME ZONE'. "
                        + "Oracle stores the literal time zone offset (+05:30, -08:00, etc.) alongside "
                        + "the timestamp. Mapped to OffsetDateTime in Java. Hibernate needs the Oracle "
                        + "dialect to understand this column type during schema validation.",
        complexity      = MigrationComplexity.LOW,
        automationLevel = AutomationLevel.FULL,
        migrationTarget = "Change columnDefinition from 'TIMESTAMP WITH TIME ZONE' to 'TIMESTAMPTZ' "
                        + "in PostgreSQL DDL. OffsetDateTime mapping is compatible; no Java change needed."
    ),
    @OracleDependency(
        domain          = DependencyDomain.EMBEDDED_SQL,
        category        = "ORACLE_HINTS",
        description     = "Native query includes Oracle optimizer hint /*+ INDEX(t IDX_TXN_ACCOUNT_DATE) */ "
                        + "to force use of the composite index on ACCOUNT_ID + TRANSACTION_DATE. "
                        + "These hints are Oracle-proprietary syntax and are silently ignored by other DBs.",
        complexity      = MigrationComplexity.MEDIUM,
        automationLevel = AutomationLevel.PARTIAL,
        migrationTarget = "Remove Oracle hints from queries targeting PostgreSQL. If query-plan steering "
                        + "is needed, use the pg_hint_plan extension with its own hint syntax."
    ),
    @OracleDependency(
        domain          = DependencyDomain.EMBEDDED_SQL,
        category        = "TRUNC_DATE",
        description     = "TRUNC(transaction_date) strips the time component so the date-range predicate "
                        + "compares whole days. Oracle TRUNC on a DATE or TIMESTAMP truncates to midnight. "
                        + "Behaviour differs from TRUNC on a NUMBER (rounds down).",
        complexity      = MigrationComplexity.LOW,
        automationLevel = AutomationLevel.FULL,
        migrationTarget = "Replace TRUNC(col) with DATE_TRUNC('day', col) in PostgreSQL. "
                        + "For TIMESTAMPTZ columns, also consider AT TIME ZONE before truncating."
    ),
    @OracleDependency(
        domain          = DependencyDomain.EMBEDDED_SQL,
        category        = "SYSDATE",
        description     = "SYSDATE returns the Oracle database server's current date and time without "
                        + "timezone information. Used here to define a default 90-day lookback window "
                        + "when the caller passes NULL for fromDate / toDate.",
        complexity      = MigrationComplexity.LOW,
        automationLevel = AutomationLevel.FULL,
        migrationTarget = "Replace SYSDATE with CURRENT_DATE (date only) or NOW() (timestamp). "
                        + "Use CURRENT_TIMESTAMP for timezone-aware equivalent of SYSTIMESTAMP."
    ),
    @OracleDependency(
        domain          = DependencyDomain.EMBEDDED_SQL,
        category        = "TO_CHAR",
        description     = "TO_CHAR(timestamp, 'YYYY-MM-DD HH24:MI:SS') formats an Oracle TIMESTAMP "
                        + "into a display string. The Oracle format model is mostly compatible with "
                        + "PostgreSQL's TO_CHAR but minor differences exist (e.g., timezone modifiers).",
        complexity      = MigrationComplexity.LOW,
        automationLevel = AutomationLevel.FULL,
        migrationTarget = "TO_CHAR() exists in PostgreSQL with compatible format codes for most "
                        + "common patterns. Validate each format string; replace TZR/TZD/TZH "
                        + "Oracle-specific modifiers with PostgreSQL equivalents."
    ),
    @OracleDependency(
        domain          = DependencyDomain.ORM_DATA_ACCESS,
        category        = "SEQUENCE_GENERATOR",
        description     = "Transaction PK generated via Oracle sequence MERIDIAN.TRANSACTION_SEQ. "
                        + "allocationSize=1 matches Oracle's default INCREMENT BY 1.",
        complexity      = MigrationComplexity.LOW,
        automationLevel = AutomationLevel.FULL,
        migrationTarget = "Point @SequenceGenerator to a PostgreSQL sequence or use GenerationType.IDENTITY"
    )
})
@Entity
@Table(name = "TRANSACTIONS", schema = "MERIDIAN")
@NamedNativeQueries({
    // ORACLE-DEP [EMBEDDED_SQL:ORACLE_HINTS] INDEX hint to force composite index on ACCOUNT_ID + date
    // ORACLE-DEP [EMBEDDED_SQL:TRUNC_DATE] TRUNC strips time for date-boundary comparison
    // ORACLE-DEP [EMBEDDED_SQL:NVL_FUNCTION] NVL defaults NULL date params to rolling 90-day window
    // ORACLE-DEP [EMBEDDED_SQL:SYSDATE] SYSDATE provides current date as default boundary
    @NamedNativeQuery(
        name  = "TransactionEntity.findByAccountAndDateRange",
        query = "SELECT /*+ INDEX(t IDX_TXN_ACCOUNT_DATE) */ "         // ORACLE-DEP: Oracle optimizer hint
              + "t.transaction_id, t.account_id, t.amount, t.transaction_type, "
              + "t.description, t.transaction_date, t.status, t.reference_number, "
              + "t.counterparty_id, t.category_code, t.created_date "
              + "FROM MERIDIAN.TRANSACTIONS t "
              + "WHERE t.account_id = :accountId "
              + "AND TRUNC(t.transaction_date) "                        // ORACLE-DEP: TRUNC(date)
              + "  BETWEEN NVL(:fromDate, TRUNC(SYSDATE) - 90) "       // ORACLE-DEP: NVL; SYSDATE; date - integer
              + "  AND     NVL(:toDate,   TRUNC(SYSDATE)) "            // ORACLE-DEP: NVL; SYSDATE
              + "ORDER BY t.transaction_date DESC"
    ),
    // ORACLE-DEP [EMBEDDED_SQL:TO_CHAR] Formats TIMESTAMP for human-readable display string
    // ORACLE-DEP [EMBEDDED_SQL:ROWNUM] Top-10 most-recent transactions (pre-12c style)
    @NamedNativeQuery(
        name  = "TransactionEntity.findRecentFormatted",
        query = "SELECT t.transaction_id, "
              + "TO_CHAR(t.transaction_date, 'YYYY-MM-DD HH24:MI:SS') AS txn_date_str, " // ORACLE-DEP: TO_CHAR
              + "t.amount, t.transaction_type, t.status "
              + "FROM MERIDIAN.TRANSACTIONS t "
              + "WHERE t.account_id = :accountId "
              + "AND ROWNUM <= 10 "                                     // ORACLE-DEP: ROWNUM
              + "ORDER BY t.transaction_date DESC"
    )
})
public class TransactionEntity {

    // ORACLE-DEP [ORM_DATA_ACCESS:SEQUENCE_GENERATOR] Oracle sequence for PK generation
    @Id
    @SequenceGenerator(
        name           = "transactionSeqGen",
        sequenceName   = "MERIDIAN.TRANSACTION_SEQ",   // ORACLE-DEP: Oracle sequence reference
        allocationSize = 1
    )
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "transactionSeqGen")
    @Column(name = "TRANSACTION_ID")
    private Long transactionId;

    @Column(name = "ACCOUNT_ID", nullable = false)
    private Long accountId;

    /**
     * Transaction amount. Positive for credits, negative for debits.
     * Oracle NUMBER(19,4) provides sufficient precision for micro-transactions.
     *
     * ORACLE-DEP [EMBEDDED_SQL:NUMBER_TYPE] Oracle NUMBER(19,4) monetary field
     */
    // ORACLE-DEP [EMBEDDED_SQL:NUMBER_TYPE] NUMBER(19,4) — NUMERIC(19,4) in PostgreSQL
    @Column(name = "AMOUNT", nullable = false, columnDefinition = "NUMBER(19,4)")
    private BigDecimal amount;

    // ORACLE-DEP [EMBEDDED_SQL:VARCHAR2_TYPE] Transaction type code stored in VARCHAR2
    @Column(name = "TRANSACTION_TYPE", nullable = false, columnDefinition = "VARCHAR2(20)")
    private String transactionType;

    // ORACLE-DEP [EMBEDDED_SQL:VARCHAR2_TYPE] Free-text description in VARCHAR2(500)
    @Column(name = "DESCRIPTION", columnDefinition = "VARCHAR2(500)")
    private String description;

    /**
     * When the transaction occurred, including originating time zone.
     * Oracle stores this as TIMESTAMP(6) WITH TIME ZONE — preserving the exact moment
     * and the offset at which it was recorded (e.g., a branch in a specific time zone).
     * Mapped to OffsetDateTime which preserves the zone offset.
     *
     * ORACLE-DEP [EMBEDDED_SQL:TIMESTAMP_TZ] Oracle TIMESTAMP WITH TIME ZONE column
     */
    // ORACLE-DEP [EMBEDDED_SQL:TIMESTAMP_TZ] TIMESTAMP WITH TIME ZONE — TIMESTAMPTZ in PostgreSQL
    @Column(name = "TRANSACTION_DATE", nullable = false,
            columnDefinition = "TIMESTAMP WITH TIME ZONE")
    private OffsetDateTime transactionDate;

    @Column(name = "processed_date", columnDefinition = "TIMESTAMP WITH TIME ZONE")
    private OffsetDateTime processedDate;

    // ORACLE-DEP [EMBEDDED_SQL:VARCHAR2_TYPE] Status VARCHAR2 — used in DECODE in reporting queries
    @Column(name = "STATUS", nullable = false, columnDefinition = "VARCHAR2(20)")
    private String status;

    /**
     * Externally visible reference number. Generated from a separate Oracle sequence
     * (MERIDIAN.TXN_REF_SEQ) to produce sequential, predictable reference identifiers
     * independent of the internal PK sequence.
     *
     * ORACLE-DEP [EMBEDDED_SQL:VARCHAR2_TYPE] Reference number stored as VARCHAR2(50)
     */
    // ORACLE-DEP [EMBEDDED_SQL:VARCHAR2_TYPE] VARCHAR2(50) reference — migrate to VARCHAR(50)
    @Column(name = "REFERENCE_NUMBER", unique = true, columnDefinition = "VARCHAR2(50)")
    private String referenceNumber;

    @Column(name = "COUNTERPARTY_ID")
    private Long counterpartyId;

    // ORACLE-DEP [EMBEDDED_SQL:VARCHAR2_TYPE] Category code in VARCHAR2 — used in analytic queries
    @Column(name = "CATEGORY_CODE", columnDefinition = "VARCHAR2(50)")
    private String categoryCode;

    // ORACLE-DEP [EMBEDDED_SQL:DATE_TYPE] Oracle DATE audit column
    @Column(name = "CREATED_DATE", nullable = false, columnDefinition = "DATE")
    private LocalDate createdDate;
    public TransactionEntity() {
    }


    public Long getTransactionId() {
        return transactionId;
    }

    public void setTransactionId(Long transactionId) {
        this.transactionId = transactionId;
    }

    public Long getAccountId() {
        return accountId;
    }

    public void setAccountId(Long accountId) {
        this.accountId = accountId;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

    public String getTransactionType() {
        return transactionType;
    }

    public void setTransactionType(String transactionType) {
        this.transactionType = transactionType;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public OffsetDateTime getTransactionDate() {
        return transactionDate;
    }

    public void setTransactionDate(OffsetDateTime transactionDate) {
        this.transactionDate = transactionDate;
    }

    public OffsetDateTime getProcessedDate() {
        return processedDate;
    }

    public void setProcessedDate(OffsetDateTime processedDate) {
        this.processedDate = processedDate;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getReferenceNumber() {
        return referenceNumber;
    }

    public void setReferenceNumber(String referenceNumber) {
        this.referenceNumber = referenceNumber;
    }

    public Long getCounterpartyId() {
        return counterpartyId;
    }

    public void setCounterpartyId(Long counterpartyId) {
        this.counterpartyId = counterpartyId;
    }

    public String getCategoryCode() {
        return categoryCode;
    }

    public void setCategoryCode(String categoryCode) {
        this.categoryCode = categoryCode;
    }

    public LocalDate getCreatedDate() {
        return createdDate;
    }

    public void setCreatedDate(LocalDate createdDate) {
        this.createdDate = createdDate;
    }

}
