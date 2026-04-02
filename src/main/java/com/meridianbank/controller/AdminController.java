package com.meridianbank.controller;

import com.meridianbank.dto.AuditTrailDTO;
import com.meridianbank.entity.AuditLogEntity;
import com.meridianbank.repository.AuditLogRepository;
import com.meridianbank.util.DependencyReportGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * REST controller for administrative / introspection operations.
 *
 * <p>This controller exposes two critical endpoints for the Oracle migration framework:
 *
 * <ol>
 *   <li><b>/dependency-report</b> — Scans the compiled classpath via reflection to
 *       find every {@code @OracleDependency} annotation in the application and
 *       returns a structured JSON report.  This report is the ground-truth baseline
 *       against which the framework's automated assessment is validated.</li>
 *   <li><b>/audit-trail/{table}</b> — Returns the Oracle-stored audit log for a
 *       given table. Audit records are written by Oracle database triggers using
 *       {@code :NEW} / {@code :OLD} pseudo-records and stored as Oracle CLOB
 *       JSON payloads.</li>
 * </ol>
 *
 * <p>Oracle dependencies touched by this controller:
 * ORACLE-DEP [EMBEDDED_SQL:CLOB_TYPE]       AuditLogEntity.oldValues / newValues are CLOB
 * ORACLE-DEP [EMBEDDED_SQL:SYSTIMESTAMP]    AuditLogEntity.changedDate uses SYSTIMESTAMP default
 */
@RestController
@RequestMapping("/api/admin")
@CrossOrigin(origins = "*")
public class AdminController {

    private static final Logger log = LoggerFactory.getLogger(AdminController.class);

    private final DependencyReportGenerator dependencyReportGenerator;
    private final AuditLogRepository auditLogRepository;

    @Autowired
    public AdminController(DependencyReportGenerator dependencyReportGenerator,
                           AuditLogRepository auditLogRepository) {
        this.dependencyReportGenerator = dependencyReportGenerator;
        this.auditLogRepository        = auditLogRepository;
    }

    // -------------------------------------------------------------------------
    // GET /api/admin/dependency-report
    // Generates a JSON report of all @OracleDependency annotations found in
    // the Meridian Bank classpath via runtime reflection.
    //
    // This is the primary validation endpoint for the migration framework:
    //   expected total ≥ 185 annotations across §2.1–§2.10 taxonomy domains.
    // -------------------------------------------------------------------------
    @GetMapping("/dependency-report")
    public ResponseEntity<Map<String, Object>> getDependencyReport() {
        log.info("GET /api/admin/dependency-report — scanning classpath");
        Map<String, Object> report = dependencyReportGenerator.generateReport();
        return ResponseEntity.ok(report);
    }

    // -------------------------------------------------------------------------
    // GET /api/admin/audit-trail/{table}
    // Returns all audit log records for a given table name.
    // AuditLogEntity stores old/new values as Oracle CLOB columns written by
    // Oracle database triggers (06-create-triggers.sql).
    // changedDate is set by the trigger using SYSTIMESTAMP for microsecond
    // precision — an Oracle-specific timestamp type.
    // -------------------------------------------------------------------------
    @GetMapping("/audit-trail/{table}")
    public ResponseEntity<List<AuditTrailDTO>> getAuditTrail(@PathVariable String table) {
        log.debug("GET /api/admin/audit-trail/{}", table);
        List<AuditLogEntity> entities = auditLogRepository.findByTableName(table.toUpperCase());
        List<AuditTrailDTO> dtos = entities.stream()
                .map(this::toAuditTrailDTO)
                .collect(Collectors.toList());
        return ResponseEntity.ok(dtos);
    }

    // -------------------------------------------------------------------------
    // Private mapping helper
    // Maps AuditLogEntity to AuditTrailDTO.
    // recordId is stored as String in the entity (Oracle ROWID or numeric PK
    // as VARCHAR2) and exposed as-is in the DTO.
    // -------------------------------------------------------------------------
    private AuditTrailDTO toAuditTrailDTO(AuditLogEntity entity) {
        AuditTrailDTO dto = new AuditTrailDTO();
        dto.setAuditId(entity.getAuditId());
        dto.setTableName(entity.getTableName());
        dto.setOperationType(entity.getOperationType());
        // recordId stored as String (Oracle ROWID or numeric PK cast to VARCHAR2)
        if (entity.getRecordId() != null) {
            try {
                dto.setRecordId(Long.parseLong(entity.getRecordId()));
            } catch (NumberFormatException e) {
                // ROWID format — leave as null numeric, full value is in tableName context
                dto.setRecordId(null);
            }
        }
        dto.setChangedBy(entity.getChangedBy());
        dto.setIpAddress(entity.getIpAddress());
        if (entity.getChangedDate() != null) {
            dto.setChangedDate(entity.getChangedDate().toLocalDateTime());
        }
        return dto;
    }
}
