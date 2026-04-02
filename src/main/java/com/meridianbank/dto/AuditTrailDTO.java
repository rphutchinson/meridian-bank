package com.meridianbank.dto;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * DTO for audit trail data from the AUDIT_LOG table.
 *
 * <p>Audit records are written by Oracle database triggers using
 * SYSTIMESTAMP and :NEW/:OLD pseudorecord values. The old/new values
 * are stored as Oracle CLOB columns.
 * Migration: replace database triggers with application-tier audit interceptors.
 */
public class AuditTrailDTO {

    private Long auditId;
    private String tableName;
    private String operationType;    // INSERT, UPDATE, DELETE

    private Long recordId;

    /** De-serialized from Oracle CLOB — original JSON-serialized row values. */
    private Map<String, Object> oldValues;

    /** De-serialized from Oracle CLOB — new JSON-serialized row values. */
    private Map<String, Object> newValues;

    /** Changed fields for UPDATE operations. */
    private List<FieldChange> changedFields;

    private String changedBy;
    private String ipAddress;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime changedDate;

    // List result
    private List<AuditTrailDTO> entries;
    private Integer totalCount;

    public static class FieldChange {
        private String fieldName;
        private String oldValue;
        private String newValue;

        public FieldChange() {}

        public String getFieldName() { return fieldName; }
        public void setFieldName(String fieldName) { this.fieldName = fieldName; }
        public String getOldValue() { return oldValue; }
        public void setOldValue(String oldValue) { this.oldValue = oldValue; }
        public String getNewValue() { return newValue; }
        public void setNewValue(String newValue) { this.newValue = newValue; }
    }
    public AuditTrailDTO() {
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

    public Long getRecordId() {
        return recordId;
    }

    public void setRecordId(Long recordId) {
        this.recordId = recordId;
    }

    public Map<String, Object> getOldValues() {
        return oldValues;
    }

    public void setOldValues(Map<String, Object> oldValues) {
        this.oldValues = oldValues;
    }

    public Map<String, Object> getNewValues() {
        return newValues;
    }

    public void setNewValues(Map<String, Object> newValues) {
        this.newValues = newValues;
    }

    public List<FieldChange> getChangedFields() {
        return changedFields;
    }

    public void setChangedFields(List<FieldChange> changedFields) {
        this.changedFields = changedFields;
    }

    public String getChangedBy() {
        return changedBy;
    }

    public void setChangedBy(String changedBy) {
        this.changedBy = changedBy;
    }

    public String getIpAddress() {
        return ipAddress;
    }

    public void setIpAddress(String ipAddress) {
        this.ipAddress = ipAddress;
    }

    public LocalDateTime getChangedDate() {
        return changedDate;
    }

    public void setChangedDate(LocalDateTime changedDate) {
        this.changedDate = changedDate;
    }

    public List<AuditTrailDTO> getEntries() {
        return entries;
    }

    public void setEntries(List<AuditTrailDTO> entries) {
        this.entries = entries;
    }

    public Integer getTotalCount() {
        return totalCount;
    }

    public void setTotalCount(Integer totalCount) {
        this.totalCount = totalCount;
    }

}
