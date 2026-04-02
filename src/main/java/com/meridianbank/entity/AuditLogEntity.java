package com.meridianbank.entity;

import com.meridianbank.annotation.AutomationLevel;
import com.meridianbank.annotation.DependencyDomain;
import com.meridianbank.annotation.MigrationComplexity;
import com.meridianbank.annotation.OracleDependency;
import com.meridianbank.annotation.OracleDependencies;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.NamedNativeQueries;
import jakarta.persistence.NamedNativeQuery;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

/**
 * Immutable audit log record capturing every DML change across the Meridian Bank schema.
 * Stores old and new values as CLOB JSON payloads. The changed_date uses Oracle
 * SYSTIMESTAMP for microsecond precision with time-zone awareness.
 *
 * ORACLE-DEP [EMBEDDED_SQL:SYSTIMESTAMP] SYSTIMESTAMP provides microsecond-precision timestamps
 * ORACLE-DEP [EMBEDDED_SQL:CLOB_TYPE] CLOB columns store old/new JSON audit payloads
 * ORACLE-DEP [EMBEDDED_SQL:INTERVAL_LITERAL] Oracle INTERVAL literal used in date-range queries
 * ORACLE-DEP [EMBEDDED_SQL:NVL_FUNCTION] NVL defaults null ip_address to 'unknown'
 */
@OracleDependencies({
    @OracleDependency(
        domain = DependencyDomain.EMBEDDED_SQL,
        category = "SYSTIMESTAMP",
        description = "SYSTIMESTAMP default for changed_date with microsecond precision",
        complexity = MigrationComplexity.LOW,
        automationLevel = AutomationLevel.FULL,
        migrationTarget = "Use NOW() or CURRENT_TIMESTAMP in PostgreSQL"
    ),
    @OracleDependency(
        domain = DependencyDomain.EMBEDDED_SQL,
        category = "CLOB_TYPE",
        description = "CLOB columns for old_values and new_values JSON audit data",
        complexity = MigrationComplexity.LOW,
        automationLevel = AutomationLevel.FULL,
        migrationTarget = "Use TEXT or JSONB in PostgreSQL"
    )
})
@NamedNativeQueries({
    @NamedNativeQuery(
        name = "AuditLogEntity.findByTableAndDateRange",
        // ORACLE-DEP [EMBEDDED_SQL:SYSTIMESTAMP] SYSTIMESTAMP - INTERVAL for rolling window query
        // ORACLE-DEP [EMBEDDED_SQL:INTERVAL_LITERAL] Oracle INTERVAL 'n' DAY literal syntax
        // ORACLE-DEP [EMBEDDED_SQL:NVL_FUNCTION] NVL defaults null ip_address to display value
        query = "SELECT a.audit_id, a.table_name, a.operation_type, a.record_id, " +
                "       a.old_values, a.new_values, " +
                "       NVL(a.ip_address, 'unknown') as ip_display, " +
                "       a.changed_by, a.changed_date, a.session_id " +
                "FROM MERIDIAN.AUDIT_LOG a " +
                "WHERE a.table_name = :tableName " +
                "AND a.changed_date >= SYSTIMESTAMP - INTERVAL ':days' DAY " +
                "ORDER BY a.changed_date DESC",
        resultClass = AuditLogEntity.class
    ),
    @NamedNativeQuery(
        name = "AuditLogEntity.findRecentByOperationType",
        // ORACLE-DEP [EMBEDDED_SQL:SYSTIMESTAMP] SYSTIMESTAMP for recent-record window
        // ORACLE-DEP [EMBEDDED_SQL:INTERVAL_LITERAL] Oracle INTERVAL '1' HOUR literal
        // ORACLE-DEP [EMBEDDED_SQL:NVL_FUNCTION] NVL on session_id for anonymous sessions
        query = "SELECT a.* FROM MERIDIAN.AUDIT_LOG a " +
                "WHERE a.operation_type = :operationType " +
                "AND a.changed_date >= SYSTIMESTAMP - INTERVAL '1' HOUR " +
                "AND NVL(a.session_id, 'ANONYMOUS') != 'SYSTEM_BATCH' " +
                "ORDER BY a.changed_date DESC",
        resultClass = AuditLogEntity.class
    )
})
@Entity
@Table(name = "AUDIT_LOG", schema = "MERIDIAN")
public class AuditLogEntity {

    /**
     * ORACLE-DEP [CONNECTIVITY_CONFIG:SEQUENCE_GENERATOR] Oracle sequence-based ID generation.
     * allocationSize=1 matches the Oracle sequence INCREMENT BY 1 defined in init scripts.
     */
    @Id
    @SequenceGenerator(
        name = "audit_log_seq_gen",
        sequenceName = "MERIDIAN.AUDIT_LOG_SEQ",
        allocationSize = 1
    )
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "audit_log_seq_gen")
    @Column(name = "AUDIT_ID")
    private Long auditId;

    // ORACLE-DEP [EMBEDDED_SQL:VARCHAR2_TYPE] Oracle VARCHAR2 for table name reference
    @Column(name = "TABLE_NAME", columnDefinition = "VARCHAR2(100)", nullable = false)
    private String tableName;

    // ORACLE-DEP [EMBEDDED_SQL:VARCHAR2_TYPE] Oracle VARCHAR2 for DML operation type (INSERT/UPDATE/DELETE)
    @Column(name = "OPERATION_TYPE", columnDefinition = "VARCHAR2(10)", nullable = false)
    private String operationType;

    // ORACLE-DEP [EMBEDDED_SQL:VARCHAR2_TYPE] Oracle VARCHAR2 stores the PK of the changed record
    @Column(name = "RECORD_ID", columnDefinition = "VARCHAR2(100)", nullable = false)
    private String recordId;

    /**
     * JSON snapshot of the row before the change, stored as Oracle CLOB.
     * ORACLE-DEP [EMBEDDED_SQL:CLOB_TYPE] CLOB for potentially large JSON payloads
     */
    @Lob
    @Column(name = "OLD_VALUES")
    private String oldValues;

    /**
     * JSON snapshot of the row after the change, stored as Oracle CLOB.
     * ORACLE-DEP [EMBEDDED_SQL:CLOB_TYPE] CLOB for potentially large JSON payloads
     */
    @Lob
    @Column(name = "NEW_VALUES")
    private String newValues;

    // ORACLE-DEP [EMBEDDED_SQL:VARCHAR2_TYPE] Oracle VARCHAR2 for auditor identity
    @Column(name = "CHANGED_BY", columnDefinition = "VARCHAR2(100)", nullable = false)
    private String changedBy;

    /**
     * Oracle TIMESTAMP WITH TIME ZONE provides microsecond precision with TZ awareness.
     * ORACLE-DEP [EMBEDDED_SQL:SYSTIMESTAMP] Column defaulted to SYSTIMESTAMP in DDL
     * ORACLE-DEP [EMBEDDED_SQL:TIMESTAMP_WITH_TZ] Oracle TIMESTAMP WITH TIME ZONE type
     */
    @Column(name = "CHANGED_DATE", columnDefinition = "TIMESTAMP WITH TIME ZONE", nullable = false)
    private OffsetDateTime changedDate;

    // ORACLE-DEP [EMBEDDED_SQL:VARCHAR2_TYPE] Oracle VARCHAR2 for source IP address
    @Column(name = "IP_ADDRESS", columnDefinition = "VARCHAR2(50)")
    private String ipAddress;

    // ORACLE-DEP [EMBEDDED_SQL:VARCHAR2_TYPE] Oracle VARCHAR2 for application session token
    @Column(name = "SESSION_ID", columnDefinition = "VARCHAR2(100)")
    private String sessionId;
    public AuditLogEntity() {
    }


    public Long getAuditId() {
        return auditId;
    }

    public void setAuditId(Long auditId) {
        this.auditId = auditId;
    }

    public String getTableName() {
        return tableName;
    }

    public void setTableName(String tableName) {
        this.tableName = tableName;
    }

    public String getOperationType() {
        return operationType;
    }

    public void setOperationType(String operationType) {
        this.operationType = operationType;
    }

    public String getRecordId() {
        return recordId;
    }

    public void setRecordId(String recordId) {
        this.recordId = recordId;
    }

    public String getOldValues() {
        return oldValues;
    }

    public void setOldValues(String oldValues) {
        this.oldValues = oldValues;
    }

    public String getNewValues() {
        return newValues;
    }

    public void setNewValues(String newValues) {
        this.newValues = newValues;
    }

    public String getChangedBy() {
        return changedBy;
    }

    public void setChangedBy(String changedBy) {
        this.changedBy = changedBy;
    }

    public OffsetDateTime getChangedDate() {
        return changedDate;
    }

    public void setChangedDate(OffsetDateTime changedDate) {
        this.changedDate = changedDate;
    }

    public String getIpAddress() {
        return ipAddress;
    }

    public void setIpAddress(String ipAddress) {
        this.ipAddress = ipAddress;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

}
