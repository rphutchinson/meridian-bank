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
import jakarta.persistence.Transient;

import java.time.LocalDate;

/**
 * JPA entity representing a Meridian Bank customer.
 *
 * <p>Oracle dependencies present in this entity:
 * <ul>
 *   <li>VARCHAR2 column definitions throughout (Oracle-specific type, not ANSI VARCHAR)</li>
 *   <li>DATE type for date-only fields (Oracle DATE includes time component — subtle difference)</li>
 *   <li>RAW(256) / RAW(32) for binary SSN storage (Oracle binary type)</li>
 *   <li>Oracle sequence for ID generation (MERIDIAN.CUSTOMER_SEQ)</li>
 *   <li>Native queries using NVL(), DECODE(), and ROWNUM pseudocolumn</li>
 * </ul>
 *
 * <p>Migration note: VARCHAR2 → VARCHAR, DATE → DATE (PostgreSQL), RAW → BYTEA,
 * sequences → PostgreSQL sequences or IDENTITY columns.
 */
// ORACLE-DEP [EMBEDDED_SQL:VARCHAR2_TYPE] All String columns use Oracle VARCHAR2 type definitions
// ORACLE-DEP [EMBEDDED_SQL:DATE_TYPE] dateOfBirth/createdDate use Oracle DATE column type
// ORACLE-DEP [EMBEDDED_SQL:RAW_TYPE] ssnEncrypted and ssnHash use Oracle RAW binary type
// ORACLE-DEP [ORM_DATA_ACCESS:SEQUENCE_GENERATOR] ID generated via Oracle sequence CUSTOMER_SEQ
@OracleDependencies({
    @OracleDependency(
        domain          = DependencyDomain.EMBEDDED_SQL,
        category        = "VARCHAR2_TYPE",
        description     = "All String fields use Oracle VARCHAR2 column definitions rather than "
                        + "ANSI VARCHAR. Oracle VARCHAR2 has different null/empty-string semantics "
                        + "and a maximum length expressed in bytes or characters depending on NLS settings.",
        complexity      = MigrationComplexity.LOW,
        automationLevel = AutomationLevel.FULL,
        migrationTarget = "Replace columnDefinition=\"VARCHAR2(n)\" with columnDefinition=\"VARCHAR(n)\" "
                        + "or remove columnDefinition entirely and rely on standard JPA mapping"
    ),
    @OracleDependency(
        domain          = DependencyDomain.EMBEDDED_SQL,
        category        = "DATE_TYPE",
        description     = "Oracle DATE stores both date and time components. Mapped to LocalDate here "
                        + "which silently truncates the time component. In PostgreSQL, DATE is purely "
                        + "a date — semantically correct for dateOfBirth but requires care for "
                        + "audit timestamps that are Oracle DATE columns storing time.",
        complexity      = MigrationComplexity.LOW,
        automationLevel = AutomationLevel.FULL,
        migrationTarget = "Oracle DATE → PostgreSQL DATE for date-only fields; for fields that "
                        + "store time, change to TIMESTAMP and LocalDateTime"
    ),
    @OracleDependency(
        domain          = DependencyDomain.EMBEDDED_SQL,
        category        = "RAW_TYPE",
        description     = "Oracle RAW is a fixed-length binary type. Used here to store AES-encrypted "
                        + "SSN bytes (RAW(256)) and a SHA-256 hash (RAW(32)). Oracle automatically "
                        + "handles hex conversion for RAW columns.",
        complexity      = MigrationComplexity.MEDIUM,
        automationLevel = AutomationLevel.PARTIAL,
        migrationTarget = "Replace Oracle RAW with PostgreSQL BYTEA. Update any RAWTOHEX() / "
                        + "HEXTORAW() calls in SQL to encode() / decode() equivalents."
    ),
    @OracleDependency(
        domain          = DependencyDomain.ORM_DATA_ACCESS,
        category        = "SEQUENCE_GENERATOR",
        description     = "Primary key generated via Oracle sequence MERIDIAN.CUSTOMER_SEQ with "
                        + "allocationSize=1 to match Oracle's default increment of 1.",
        complexity      = MigrationComplexity.LOW,
        automationLevel = AutomationLevel.FULL,
        migrationTarget = "Replace @SequenceGenerator referencing Oracle sequence with "
                        + "GenerationType.IDENTITY or a PostgreSQL sequence via @SequenceGenerator "
                        + "pointing to a PostgreSQL sequence"
    )
})
@Entity
@Table(name = "CUSTOMERS", schema = "MERIDIAN")
@NamedNativeQueries({
    // ORACLE-DEP [EMBEDDED_SQL:NVL_FUNCTION] NVL() returns fallback when phone/email is NULL
    @NamedNativeQuery(
        name  = "CustomerEntity.findActiveWithPhone",
        query = "SELECT customer_id, first_name, last_name, "
              + "NVL(phone, 'N/A') AS phone, "                     // ORACLE-DEP: NVL — use COALESCE in PostgreSQL
              + "NVL(email, 'unknown') AS email "                   // ORACLE-DEP: NVL — use COALESCE in PostgreSQL
              + "FROM MERIDIAN.CUSTOMERS "
              + "WHERE status = 'ACTIVE'"
    ),
    // ORACLE-DEP [EMBEDDED_SQL:DECODE_FUNCTION] DECODE replaces CASE WHEN for status label mapping
    @NamedNativeQuery(
        name  = "CustomerEntity.findByStatusDecode",
        query = "SELECT customer_id, first_name, last_name, "
              + "DECODE(status, "                                   // ORACLE-DEP: DECODE — no direct PostgreSQL equivalent; use CASE WHEN
              + "  'ACTIVE',   'Active Customer', "
              + "  'INACTIVE', 'Dormant', "
              + "  'SUSPENDED','Suspended Account', "
              + "  'Unknown') AS status_label, "
              + "credit_score "
              + "FROM MERIDIAN.CUSTOMERS "
              + "WHERE status = :status"
    ),
    // ORACLE-DEP [EMBEDDED_SQL:ROWNUM] ROWNUM pseudocolumn used for top-N pagination (pre-12c style)
    @NamedNativeQuery(
        name  = "CustomerEntity.findTopByBalance",
        query = "SELECT * FROM ("
              + "  SELECT c.customer_id, c.first_name, c.last_name, "
              + "         c.email, c.status, c.credit_score, "
              + "         ROWNUM AS rn "                            // ORACLE-DEP: ROWNUM — use ROW_NUMBER() OVER() or LIMIT in PostgreSQL
              + "  FROM MERIDIAN.CUSTOMERS c "
              + "  WHERE status = 'ACTIVE' "
              + "  ORDER BY credit_score DESC"
              + ") WHERE rn <= :maxRows"
    )
})
public class CustomerEntity {

    // ORACLE-DEP [ORM_DATA_ACCESS:SEQUENCE_GENERATOR] Oracle sequence for PK generation
    @Id
    @SequenceGenerator(
        name           = "customerSeqGen",
        sequenceName   = "MERIDIAN.CUSTOMER_SEQ",   // ORACLE-DEP: Oracle sequence reference
        allocationSize = 1
    )
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "customerSeqGen")
    @Column(name = "CUSTOMER_ID")
    private Long customerId;

    // ORACLE-DEP [EMBEDDED_SQL:VARCHAR2_TYPE] Oracle VARCHAR2 — migrate to VARCHAR
    @Column(name = "FIRST_NAME", nullable = false, columnDefinition = "VARCHAR2(100)")
    private String firstName;

    // ORACLE-DEP [EMBEDDED_SQL:VARCHAR2_TYPE] Oracle VARCHAR2 — migrate to VARCHAR
    @Column(name = "LAST_NAME", nullable = false, columnDefinition = "VARCHAR2(100)")
    private String lastName;

    // ORACLE-DEP [EMBEDDED_SQL:VARCHAR2_TYPE] Oracle VARCHAR2 — migrate to VARCHAR
    @Column(name = "EMAIL", nullable = false, unique = true, columnDefinition = "VARCHAR2(200)")
    private String email;

    // ORACLE-DEP [EMBEDDED_SQL:VARCHAR2_TYPE] Nullable phone — NVL used in queries above
    @Column(name = "PHONE", columnDefinition = "VARCHAR2(20)")
    private String phone;

    // ORACLE-DEP [EMBEDDED_SQL:DATE_TYPE] Oracle DATE (date+time) mapped as LocalDate (date only)
    @Column(name = "DATE_OF_BIRTH", columnDefinition = "DATE")
    private LocalDate dateOfBirth;

    /**
     * AES-encrypted SSN bytes stored in Oracle RAW(256).
     * Encrypted by MERIDIAN.SECURITY_PKG.ENCRYPT_SSN using DBMS_CRYPTO.
     *
     * ORACLE-DEP [EMBEDDED_SQL:RAW_TYPE] Oracle RAW binary column — migrate to PostgreSQL BYTEA
     */
    // ORACLE-DEP [EMBEDDED_SQL:RAW_TYPE] RAW(256) stores encrypted SSN bytes from DBMS_CRYPTO
    @Column(name = "SSN_ENCRYPTED", columnDefinition = "RAW(256)")
    private byte[] ssnEncrypted;

    /**
     * SHA-256 hash of the SSN for lookup without decryption.
     * Computed via DBMS_CRYPTO.HASH(SSN_RAW, DBMS_CRYPTO.HASH_SH256).
     *
     * ORACLE-DEP [EMBEDDED_SQL:RAW_TYPE] Oracle RAW(32) = 32 raw bytes — migrate to BYTEA
     */
    // ORACLE-DEP [EMBEDDED_SQL:RAW_TYPE] RAW(32) stores SHA-256 hash from DBMS_CRYPTO
    @Column(name = "SSN_HASH", columnDefinition = "RAW(32)")
    private byte[] ssnHash;

    @Column(name = "ADDRESS_ID")
    private Long addressId;

    // ORACLE-DEP [EMBEDDED_SQL:VARCHAR2_TYPE] Status uses VARCHAR2; DECODE used in named query
    @Column(name = "STATUS", nullable = false, columnDefinition = "VARCHAR2(20)")
    private String status;

    // ORACLE-DEP [EMBEDDED_SQL:NUMBER_TYPE] Oracle NUMBER(4) — migrate to SMALLINT or INTEGER
    @Column(name = "CREDIT_SCORE", columnDefinition = "NUMBER(4)")
    private Integer creditScore;

    // ORACLE-DEP [EMBEDDED_SQL:DATE_TYPE] Oracle DATE column for audit timestamps
    @Column(name = "CREATED_DATE", nullable = false, columnDefinition = "DATE")
    private LocalDate createdDate;

    // ORACLE-DEP [EMBEDDED_SQL:DATE_TYPE] Oracle DATE column updated by DB trigger on row change
    @Column(name = "UPDATED_DATE", columnDefinition = "DATE")
    private LocalDate updatedDate;

    /**
     * Transient flag — not persisted. Used to signal that the SSN must be (re-)encrypted
     * before save, triggering a call to SECURITY_PKG.ENCRYPT_SSN via the service layer.
     */
    @Transient
    private boolean ssnDirty;
    public CustomerEntity() {
    }


    public Long getCustomerId() {
        return customerId;
    }

    public void setCustomerId(Long customerId) {
        this.customerId = customerId;
    }

    public String getFirstName() {
        return firstName;
    }

    public void setFirstName(String firstName) {
        this.firstName = firstName;
    }

    public String getLastName() {
        return lastName;
    }

    public void setLastName(String lastName) {
        this.lastName = lastName;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public LocalDate getDateOfBirth() {
        return dateOfBirth;
    }

    public void setDateOfBirth(LocalDate dateOfBirth) {
        this.dateOfBirth = dateOfBirth;
    }

    public byte[] getSsnEncrypted() {
        return ssnEncrypted;
    }

    public void setSsnEncrypted(byte[] ssnEncrypted) {
        this.ssnEncrypted = ssnEncrypted;
    }

    public byte[] getSsnHash() {
        return ssnHash;
    }

    public void setSsnHash(byte[] ssnHash) {
        this.ssnHash = ssnHash;
    }

    public Long getAddressId() {
        return addressId;
    }

    public void setAddressId(Long addressId) {
        this.addressId = addressId;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Integer getCreditScore() {
        return creditScore;
    }

    public void setCreditScore(Integer creditScore) {
        this.creditScore = creditScore;
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

    public boolean isSsnDirty() {
        return ssnDirty;
    }

    public void setSsnDirty(boolean ssnDirty) {
        this.ssnDirty = ssnDirty;
    }

}
