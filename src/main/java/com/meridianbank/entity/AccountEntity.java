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
 * JPA entity representing a Meridian Bank deposit or investment account.
 *
 * <p>Oracle dependencies present in this entity:
 * <ul>
 *   <li>NUMBER(19,4) for monetary balance — Oracle fixed-point numeric type</li>
 *   <li>NUMBER(6,4) for interest rate — Oracle numeric with scale</li>
 *   <li>NUMBER(12,2) for overdraft limit</li>
 *   <li>Oracle sequence MERIDIAN.ACCOUNT_SEQ for PK generation</li>
 *   <li>Native queries using NVL(), ROWNUM, DECODE(), sequence CURRVAL, and FROM DUAL</li>
 * </ul>
 *
 * <p>Migration note: NUMBER(p,s) → NUMERIC(p,s) in PostgreSQL. ROWNUM pagination →
 * standard LIMIT/OFFSET or ROW_NUMBER() OVER(). DECODE() → CASE WHEN.
 * CURRVAL FROM DUAL → currval('sequence_name').
 */
// ORACLE-DEP [EMBEDDED_SQL:NUMBER_TYPE] Monetary fields use Oracle NUMBER(p,s) definitions
// ORACLE-DEP [ORM_DATA_ACCESS:SEQUENCE_GENERATOR] PK via Oracle sequence MERIDIAN.ACCOUNT_SEQ
// ORACLE-DEP [EMBEDDED_SQL:SEQUENCE] Named query references ACCOUNT_SEQ.CURRVAL FROM DUAL
@OracleDependencies({
    @OracleDependency(
        domain          = DependencyDomain.EMBEDDED_SQL,
        category        = "NUMBER_TYPE",
        description     = "Monetary balance field uses Oracle NUMBER(19,4) — Oracle's fixed-point "
                        + "numeric type. NUMBER without scale is an exact integer; NUMBER(p,s) is "
                        + "fixed-point decimal. Semantically equivalent to NUMERIC(p,s) in PostgreSQL "
                        + "but the DDL syntax differs.",
        complexity      = MigrationComplexity.LOW,
        automationLevel = AutomationLevel.FULL,
        migrationTarget = "Replace NUMBER(19,4) with NUMERIC(19,4) in PostgreSQL DDL. "
                        + "JPA @Column(columnDefinition) values must be updated accordingly."
    ),
    @OracleDependency(
        domain          = DependencyDomain.ORM_DATA_ACCESS,
        category        = "SEQUENCE_GENERATOR",
        description     = "Account primary key generated via Oracle sequence MERIDIAN.ACCOUNT_SEQ. "
                        + "Oracle sequences require allocationSize=1 when the DB sequence increments "
                        + "by 1 (the default), otherwise Hibernate caches IDs that will never be used.",
        complexity      = MigrationComplexity.LOW,
        automationLevel = AutomationLevel.FULL,
        migrationTarget = "Create equivalent PostgreSQL sequence; update @SequenceGenerator sequenceName "
                        + "or switch to GenerationType.IDENTITY with a BIGSERIAL column."
    ),
    @OracleDependency(
        domain          = DependencyDomain.EMBEDDED_SQL,
        category        = "SEQUENCE",
        description     = "Named query 'getCurrentSequenceValue' reads ACCOUNT_SEQ.CURRVAL FROM DUAL. "
                        + "CURRVAL returns the most-recently-generated value in the current session. "
                        + "DUAL is Oracle's special one-row utility table.",
        complexity      = MigrationComplexity.LOW,
        automationLevel = AutomationLevel.FULL,
        migrationTarget = "Replace 'SELECT MERIDIAN.ACCOUNT_SEQ.CURRVAL FROM DUAL' with "
                        + "'SELECT currval(''meridian.account_seq'')' in PostgreSQL (no FROM DUAL needed)"
    ),
    @OracleDependency(
        domain          = DependencyDomain.EMBEDDED_SQL,
        category        = "ROWNUM",
        description     = "Named query 'findByStatusPaginatedRownum' uses Oracle ROWNUM pseudocolumn "
                        + "for pagination in the pre-12c style (double-nested SELECT with ROWNUM). "
                        + "Oracle 12c introduced OFFSET/FETCH but legacy ROWNUM patterns remain common.",
        complexity      = MigrationComplexity.MEDIUM,
        automationLevel = AutomationLevel.FULL,
        migrationTarget = "Replace double-nested ROWNUM pagination with standard "
                        + "LIMIT :pageSize OFFSET :offset syntax in PostgreSQL."
    )
})
@Entity
@Table(name = "ACCOUNTS", schema = "MERIDIAN")
@NamedNativeQueries({
    // ORACLE-DEP [EMBEDDED_SQL:NVL_FUNCTION] NVL(overdraft_limit, 0) defaults NULL to zero
    @NamedNativeQuery(
        name  = "AccountEntity.findWithEffectiveOverdraft",
        query = "SELECT a.account_id, a.account_number, a.account_type, a.balance, "
              + "a.status, a.open_date, a.close_date, a.customer_id, a.branch_id, "
              + "a.interest_rate, a.overdraft_limit, a.created_date, a.updated_date, "
              + "NVL(a.overdraft_limit, 0) AS effective_overdraft "   // ORACLE-DEP: NVL — COALESCE in PostgreSQL
              + "FROM MERIDIAN.ACCOUNTS a "
              + "WHERE a.customer_id = :customerId"
    ),
    // ORACLE-DEP [EMBEDDED_SQL:ROWNUM] Pre-12c double-nested ROWNUM pagination pattern
    @NamedNativeQuery(
        name  = "AccountEntity.findByStatusPaginatedRownum",
        query = "SELECT * FROM ("
              + "  SELECT a.*, ROWNUM AS rn "                          // ORACLE-DEP: ROWNUM pseudocolumn
              + "  FROM MERIDIAN.ACCOUNTS a "
              + "  WHERE a.status = :status "
              + "  ORDER BY a.balance DESC"
              + ") WHERE rn BETWEEN :startRow AND :endRow"
    ),
    // ORACLE-DEP [EMBEDDED_SQL:DECODE_FUNCTION] DECODE assigns sort ordinal by account type
    @NamedNativeQuery(
        name  = "AccountEntity.findAllOrderedByTypeDecode",
        query = "SELECT * FROM MERIDIAN.ACCOUNTS "
              + "ORDER BY DECODE(account_type, "                       // ORACLE-DEP: DECODE — use CASE WHEN in PostgreSQL
              + "  'CHECKING',     1, "
              + "  'SAVINGS',      2, "
              + "  'MONEY_MARKET', 3, "
              + "  'CD',           4, "
              + "  'IRA',          5, "
              + "  6)"
    ),
    // ORACLE-DEP [EMBEDDED_SQL:SEQUENCE] [EMBEDDED_SQL:DUAL_TABLE] CURRVAL from Oracle sequence
    @NamedNativeQuery(
        name  = "AccountEntity.getCurrentSequenceValue",
        query = "SELECT MERIDIAN.ACCOUNT_SEQ.CURRVAL FROM DUAL"         // ORACLE-DEP: sequence.CURRVAL; FROM DUAL
    )
})
public class AccountEntity {

    // ORACLE-DEP [ORM_DATA_ACCESS:SEQUENCE_GENERATOR] Oracle sequence drives PK assignment
    @Id
    @SequenceGenerator(
        name           = "accountSeqGen",
        sequenceName   = "MERIDIAN.ACCOUNT_SEQ",   // ORACLE-DEP: Oracle sequence reference
        allocationSize = 1
    )
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "accountSeqGen")
    @Column(name = "ACCOUNT_ID")
    private Long accountId;

    // ORACLE-DEP [EMBEDDED_SQL:VARCHAR2_TYPE] Oracle VARCHAR2 — migrate to VARCHAR
    @Column(name = "ACCOUNT_NUMBER", nullable = false, unique = true, columnDefinition = "VARCHAR2(20)")
    private String accountNumber;

    // ORACLE-DEP [EMBEDDED_SQL:VARCHAR2_TYPE] Account type codes stored in VARCHAR2
    @Column(name = "ACCOUNT_TYPE", nullable = false, columnDefinition = "VARCHAR2(20)")
    private String accountType;

    /**
     * Current account balance.
     * Oracle NUMBER(19,4) allows up to 15 digits before the decimal and 4 after.
     * Maps to BigDecimal with the JPA column definition enforcing Oracle DDL precision.
     *
     * ORACLE-DEP [EMBEDDED_SQL:NUMBER_TYPE] Oracle NUMBER(19,4) for monetary precision
     */
    // ORACLE-DEP [EMBEDDED_SQL:NUMBER_TYPE] NUMBER(19,4) monetary field — NUMERIC(19,4) in PostgreSQL
    @Column(name = "BALANCE", nullable = false, columnDefinition = "NUMBER(19,4)")
    private BigDecimal balance;

    // ORACLE-DEP [EMBEDDED_SQL:VARCHAR2_TYPE] Status VARCHAR2 — DECODE used in ordering query
    @Column(name = "STATUS", nullable = false, columnDefinition = "VARCHAR2(20)")
    private String status;

    // ORACLE-DEP [EMBEDDED_SQL:DATE_TYPE] Oracle DATE for account open date
    @Column(name = "OPEN_DATE", nullable = false, columnDefinition = "DATE")
    private LocalDate openDate;

    // ORACLE-DEP [EMBEDDED_SQL:DATE_TYPE] Oracle DATE, nullable — account may still be open
    @Column(name = "CLOSE_DATE", columnDefinition = "DATE")
    private LocalDate closeDate;

    @Column(name = "CUSTOMER_ID", nullable = false)
    private Long customerId;

    @Column(name = "BRANCH_ID")
    private Long branchId;

    /**
     * Annual interest rate stored as a decimal fraction (e.g., 0.0425 = 4.25%).
     * Oracle NUMBER(6,4) allows values like 9.9999 — sufficient for any realistic rate.
     *
     * ORACLE-DEP [EMBEDDED_SQL:NUMBER_TYPE] Oracle NUMBER(6,4) for interest rate precision
     */
    // ORACLE-DEP [EMBEDDED_SQL:NUMBER_TYPE] NUMBER(6,4) for rate precision — NUMERIC(6,4) in PostgreSQL
    @Column(name = "INTEREST_RATE", columnDefinition = "NUMBER(6,4)")
    private BigDecimal interestRate;

    /**
     * Approved overdraft protection limit.
     * Nullable — not all accounts have overdraft coverage.
     * NVL(overdraft_limit, 0) used in named query to produce a safe effective value.
     *
     * ORACLE-DEP [EMBEDDED_SQL:NUMBER_TYPE] Oracle NUMBER(12,2) for overdraft amount
     */
    // ORACLE-DEP [EMBEDDED_SQL:NUMBER_TYPE] NUMBER(12,2) overdraft — NUMERIC(12,2) in PostgreSQL
    @Column(name = "OVERDRAFT_LIMIT", columnDefinition = "NUMBER(12,2)")
    private BigDecimal overdraftLimit;

    // ORACLE-DEP [EMBEDDED_SQL:DATE_TYPE] Oracle DATE audit column
    @Column(name = "CREATED_DATE", nullable = false, columnDefinition = "DATE")
    private LocalDate createdDate;

    // ORACLE-DEP [EMBEDDED_SQL:DATE_TYPE] Oracle DATE updated by DB trigger on change
    @Column(name = "UPDATED_DATE", columnDefinition = "DATE")
    private LocalDate updatedDate;
    public AccountEntity() {
    }


    public Long getAccountId() {
        return accountId;
    }

    public void setAccountId(Long accountId) {
        this.accountId = accountId;
    }

    public String getAccountNumber() {
        return accountNumber;
    }

    public void setAccountNumber(String accountNumber) {
        this.accountNumber = accountNumber;
    }

    public String getAccountType() {
        return accountType;
    }

    public void setAccountType(String accountType) {
        this.accountType = accountType;
    }

    public BigDecimal getBalance() {
        return balance;
    }

    public void setBalance(BigDecimal balance) {
        this.balance = balance;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public LocalDate getOpenDate() {
        return openDate;
    }

    public void setOpenDate(LocalDate openDate) {
        this.openDate = openDate;
    }

    public LocalDate getCloseDate() {
        return closeDate;
    }

    public void setCloseDate(LocalDate closeDate) {
        this.closeDate = closeDate;
    }

    public Long getCustomerId() {
        return customerId;
    }

    public void setCustomerId(Long customerId) {
        this.customerId = customerId;
    }

    public Long getBranchId() {
        return branchId;
    }

    public void setBranchId(Long branchId) {
        this.branchId = branchId;
    }

    public BigDecimal getInterestRate() {
        return interestRate;
    }

    public void setInterestRate(BigDecimal interestRate) {
        this.interestRate = interestRate;
    }

    public BigDecimal getOverdraftLimit() {
        return overdraftLimit;
    }

    public void setOverdraftLimit(BigDecimal overdraftLimit) {
        this.overdraftLimit = overdraftLimit;
    }

    public LocalDate getCreatedDate() {
        return createdDate;
    }

    public void setCreatedDate(LocalDate createdDate) {
        this.createdDate = createdDate;
    }

    public LocalDate getUpdatedDate() {
        return updatedDate;
    }

    public void setUpdatedDate(LocalDate updatedDate) {
        this.updatedDate = updatedDate;
    }

}
