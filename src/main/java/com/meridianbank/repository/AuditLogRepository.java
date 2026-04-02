package com.meridianbank.repository;

import com.meridianbank.annotation.*;
import com.meridianbank.entity.AuditLogEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Spring Data JPA repository for AuditLogEntity.
 * Demonstrates Oracle SQL constructs: SYSTIMESTAMP interval arithmetic,
 * ROWNUM for recent-record limiting, TO_CHAR for timestamp formatting,
 * NVL for null-safe display, DBMS_LOB.GETLENGTH for CLOB size,
 * and DECODE for operation type labeling.
 */
@Repository
public interface AuditLogRepository extends JpaRepository<AuditLogEntity, Long> {

    // Standard JPQL — no Oracle dependency
    List<AuditLogEntity> findByTableName(String tableName);
    List<AuditLogEntity> findByChangedBy(String changedBy);
    List<AuditLogEntity> findByRecordId(String recordId);
    List<AuditLogEntity> findByTableNameAndRecordId(String tableName, String recordId);

    // ORACLE-DEP [EMBEDDED_SQL:SYSTIMESTAMP] SYSTIMESTAMP - INTERVAL for recent audit window
    @OracleDependency(
        domain = DependencyDomain.EMBEDDED_SQL,
        category = "SYSTIMESTAMP",
        description = "SYSTIMESTAMP - INTERVAL '24' HOUR filters audit records from the last 24 hours",
        complexity = MigrationComplexity.LOW,
        automationLevel = AutomationLevel.FULL,
        migrationTarget = "Replace SYSTIMESTAMP - INTERVAL '24' HOUR with NOW() - INTERVAL '24 hours' in PostgreSQL"
    )
    @Query(value =
        "SELECT * FROM MERIDIAN.AUDIT_LOG " +
        "WHERE changed_date >= SYSTIMESTAMP - INTERVAL '24' HOUR " +
        "ORDER BY changed_date DESC",
        nativeQuery = true)
    List<AuditLogEntity> findLast24HoursAudit();

    // ORACLE-DEP [EMBEDDED_SQL:SYSTIMESTAMP] Multi-interval audit window query
    @OracleDependency(
        domain = DependencyDomain.EMBEDDED_SQL,
        category = "SYSTIMESTAMP",
        description = "SYSTIMESTAMP - INTERVAL ':hours' HOUR uses parameterized interval literal for flexible lookback",
        complexity = MigrationComplexity.MEDIUM,
        automationLevel = AutomationLevel.PARTIAL,
        migrationTarget = "Replace with NOW() - (:hours || ' hours')::INTERVAL in PostgreSQL"
    )
    @OracleDependency(
        domain = DependencyDomain.EMBEDDED_SQL,
        category = "ROWNUM",
        description = "ROWNUM <= :maxRecords limits audit result set to prevent runaway queries",
        complexity = MigrationComplexity.LOW,
        automationLevel = AutomationLevel.FULL,
        migrationTarget = "Replace ROWNUM <= n with LIMIT n in PostgreSQL"
    )
    @Query(value =
        "SELECT * FROM (" +
        "  SELECT al.* FROM MERIDIAN.AUDIT_LOG al " +
        "  WHERE al.table_name = :tableName " +
        "  AND al.changed_date >= SYSTIMESTAMP - :hours * INTERVAL '1' HOUR " +
        "  ORDER BY al.changed_date DESC" +
        ") WHERE ROWNUM <= :maxRecords",
        nativeQuery = true)
    List<AuditLogEntity> findRecentAuditByTable(@Param("tableName") String tableName,
                                                 @Param("hours") int hours,
                                                 @Param("maxRecords") int maxRecords);

    // ORACLE-DEP [EMBEDDED_SQL:TO_CHAR_DATE] TO_CHAR for audit timestamp formatting
    @OracleDependency(
        domain = DependencyDomain.EMBEDDED_SQL,
        category = "TO_CHAR_DATE",
        description = "TO_CHAR(changed_date,'YYYY-MM-DD HH24:MI:SS.FF3') formats TIMESTAMP WITH TZ for display",
        complexity = MigrationComplexity.LOW,
        automationLevel = AutomationLevel.FULL,
        migrationTarget = "Replace TO_CHAR(timestamp,'YYYY-MM-DD HH24:MI:SS.FF3') with TO_CHAR(timestamp,'YYYY-MM-DD HH24:MI:SS.MS') in PostgreSQL"
    )
    @Query(value =
        "SELECT al.audit_id, al.table_name, al.operation_type, al.record_id, al.changed_by, " +
        "  TO_CHAR(al.changed_date, 'YYYY-MM-DD HH24:MI:SS.FF3') AS formatted_date, " +
        "  al.ip_address " +
        "FROM MERIDIAN.AUDIT_LOG al " +
        "WHERE al.record_id = :recordId " +
        "AND al.table_name = :tableName " +
        "ORDER BY al.changed_date DESC",
        nativeQuery = true)
    List<Object[]> findAuditTrailForRecord(@Param("recordId") Long recordId,
                                            @Param("tableName") String tableName);

    // ORACLE-DEP [EMBEDDED_SQL:NVL_FUNCTION] NVL for null-safe audit display
    @OracleDependency(
        domain = DependencyDomain.EMBEDDED_SQL,
        category = "NVL_FUNCTION",
        description = "NVL(ip_address,'unknown') and NVL(changed_by,'system') provide safe defaults for audit display",
        complexity = MigrationComplexity.LOW,
        automationLevel = AutomationLevel.FULL,
        migrationTarget = "Replace NVL(col, default) with COALESCE(col, default) in PostgreSQL"
    )
    @OracleDependency(
        domain = DependencyDomain.EMBEDDED_SQL,
        category = "DECODE_FUNCTION",
        description = "DECODE(operation_type,...) maps DML operation codes to descriptive labels",
        complexity = MigrationComplexity.LOW,
        automationLevel = AutomationLevel.FULL,
        migrationTarget = "Replace DECODE(operation_type,...) with CASE WHEN operation_type=... END in PostgreSQL"
    )
    @Query(value =
        "SELECT al.audit_id, " +
        "  NVL(al.changed_by, 'system') AS performed_by, " +
        "  NVL(al.ip_address, 'unknown') AS source_ip, " +
        "  DECODE(al.operation_type, 'I', 'INSERT', 'U', 'UPDATE', 'D', 'DELETE', 'T', 'TRUNCATE', al.operation_type) AS op_label, " +
        "  al.table_name, al.record_id, al.changed_date " +
        "FROM MERIDIAN.AUDIT_LOG al " +
        "WHERE al.changed_date >= SYSDATE - :days " +
        "ORDER BY al.changed_date DESC",
        nativeQuery = true)
    List<Object[]> findAuditWithLabels(@Param("days") int days);

    // ORACLE-DEP [EMBEDDED_SQL:DBMS_LOB_GETLENGTH] DBMS_LOB.GETLENGTH for CLOB size
    @OracleDependency(
        domain = DependencyDomain.EMBEDDED_SQL,
        category = "DBMS_LOB_GETLENGTH",
        description = "DBMS_LOB.GETLENGTH(old_values) returns byte length of CLOB audit payload",
        complexity = MigrationComplexity.MEDIUM,
        automationLevel = AutomationLevel.PARTIAL,
        migrationTarget = "Replace DBMS_LOB.GETLENGTH(clob) with LENGTH(text_column) or OCTET_LENGTH(text_column) in PostgreSQL"
    )
    @Query(value =
        "SELECT al.audit_id, al.table_name, al.operation_type, al.changed_date, " +
        "  DBMS_LOB.GETLENGTH(al.old_values) AS old_values_size, " +
        "  DBMS_LOB.GETLENGTH(al.new_values) AS new_values_size " +
        "FROM MERIDIAN.AUDIT_LOG al " +
        "WHERE al.table_name = :tableName " +
        "AND (DBMS_LOB.GETLENGTH(al.old_values) > :sizeThreshold " +
        "  OR DBMS_LOB.GETLENGTH(al.new_values) > :sizeThreshold) " +
        "ORDER BY al.changed_date DESC",
        nativeQuery = true)
    List<Object[]> findLargeAuditPayloads(@Param("tableName") String tableName,
                                           @Param("sizeThreshold") int sizeThreshold);

    // ORACLE-DEP [EMBEDDED_SQL:TO_CHAR_DATE] Audit grouped by day for activity report
    @OracleDependency(
        domain = DependencyDomain.EMBEDDED_SQL,
        category = "TRUNC_DATE",
        description = "TRUNC(changed_date) groups audit records by calendar day for daily activity summary",
        complexity = MigrationComplexity.LOW,
        automationLevel = AutomationLevel.FULL,
        migrationTarget = "Replace TRUNC(changed_date) with DATE_TRUNC('day', changed_date) in PostgreSQL"
    )
    @Query(value =
        "SELECT TRUNC(changed_date) AS audit_day, " +
        "  table_name, operation_type, " +
        "  COUNT(*) AS change_count, " +
        "  COUNT(DISTINCT changed_by) AS distinct_users " +
        "FROM MERIDIAN.AUDIT_LOG " +
        "WHERE changed_date >= SYSDATE - :days " +
        "GROUP BY TRUNC(changed_date), table_name, operation_type " +
        "ORDER BY audit_day DESC, change_count DESC",
        nativeQuery = true)
    List<Object[]> findDailyAuditActivity(@Param("days") int days);
}
