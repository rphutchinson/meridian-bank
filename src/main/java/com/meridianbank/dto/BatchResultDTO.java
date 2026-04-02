package com.meridianbank.dto;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.LocalDateTime;
import java.util.List;

/**
 * DTO for batch processing job results.
 *
 * <p>Batch jobs are managed via Oracle DBMS_SCHEDULER and execute
 * bulk operations using Oracle FORALL / BULK COLLECT and MERGE statements.
 * Migration: replace DBMS_SCHEDULER with Spring Batch or Quartz.
 */
public class BatchResultDTO {

    private String jobName;
    private String jobType;        // END_OF_DAY, INTEREST_CALC, STATEMENT_GEN
    private String status;         // RUNNING, COMPLETED, FAILED, PARTIAL

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime startTime;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime endTime;

    private Long durationMs;

    // Counts
    private Long recordsProcessed;
    private Long recordsSucceeded;
    private Long recordsFailed;
    private Long recordsSkipped;

    // Oracle DBMS_SCHEDULER job tracking
    private String oracleJobName;
    private String oracleJobState;
    private Long oracleRunCount;
    private Long oracleFailureCount;

    private String errorMessage;
    private List<String> warnings;
    private List<BatchErrorDetail> errors;

    public static class BatchErrorDetail {
        private Long recordId;
        private String errorCode;    // e.g. ORA-00001 for unique constraint
        private String errorMessage;
        private String recordData;

        public BatchErrorDetail() {}

        public Long getRecordId() { return recordId; }
        public void setRecordId(Long recordId) { this.recordId = recordId; }
        public String getErrorCode() { return errorCode; }
        public void setErrorCode(String errorCode) { this.errorCode = errorCode; }
        public String getErrorMessage() { return errorMessage; }
        public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
        public String getRecordData() { return recordData; }
        public void setRecordData(String recordData) { this.recordData = recordData; }
    }
    public BatchResultDTO() {
    }


    public String getJobName() {
        return jobName;
    }

    public void setJobName(String jobName) {
        this.jobName = jobName;
    }

    public String getJobType() {
        return jobType;
    }

    public void setJobType(String jobType) {
        this.jobType = jobType;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public LocalDateTime getStartTime() {
        return startTime;
    }

    public void setStartTime(LocalDateTime startTime) {
        this.startTime = startTime;
    }

    public LocalDateTime getEndTime() {
        return endTime;
    }

    public void setEndTime(LocalDateTime endTime) {
        this.endTime = endTime;
    }

    public Long getDurationMs() {
        return durationMs;
    }

    public void setDurationMs(Long durationMs) {
        this.durationMs = durationMs;
    }

    public Long getRecordsProcessed() {
        return recordsProcessed;
    }

    public void setRecordsProcessed(Long recordsProcessed) {
        this.recordsProcessed = recordsProcessed;
    }

    public Long getRecordsSucceeded() {
        return recordsSucceeded;
    }

    public void setRecordsSucceeded(Long recordsSucceeded) {
        this.recordsSucceeded = recordsSucceeded;
    }

    public Long getRecordsFailed() {
        return recordsFailed;
    }

    public void setRecordsFailed(Long recordsFailed) {
        this.recordsFailed = recordsFailed;
    }

    public Long getRecordsSkipped() {
        return recordsSkipped;
    }

    public void setRecordsSkipped(Long recordsSkipped) {
        this.recordsSkipped = recordsSkipped;
    }

    public String getOracleJobName() {
        return oracleJobName;
    }

    public void setOracleJobName(String oracleJobName) {
        this.oracleJobName = oracleJobName;
    }

    public String getOracleJobState() {
        return oracleJobState;
    }

    public void setOracleJobState(String oracleJobState) {
        this.oracleJobState = oracleJobState;
    }

    public Long getOracleRunCount() {
        return oracleRunCount;
    }

    public void setOracleRunCount(Long oracleRunCount) {
        this.oracleRunCount = oracleRunCount;
    }

    public Long getOracleFailureCount() {
        return oracleFailureCount;
    }

    public void setOracleFailureCount(Long oracleFailureCount) {
        this.oracleFailureCount = oracleFailureCount;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public List<String> getWarnings() {
        return warnings;
    }

    public void setWarnings(List<String> warnings) {
        this.warnings = warnings;
    }

    public List<BatchErrorDetail> getErrors() {
        return errors;
    }

    public void setErrors(List<BatchErrorDetail> errors) {
        this.errors = errors;
    }

}
