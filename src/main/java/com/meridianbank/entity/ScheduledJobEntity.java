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
import jakarta.persistence.NamedNativeQueries;
import jakarta.persistence.NamedNativeQuery;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;

import java.time.LocalDate;

/**
 * Entity that tracks Oracle DBMS_SCHEDULER jobs managed by the Meridian Bank batch subsystem.
 * Each row mirrors a job registered in Oracle's DBMS_SCHEDULER; the application reads this
 * table to monitor job health and trigger manual runs via BatchProcessingService.
 *
 * ORACLE-DEP [PLSQL_INVOCATION:DBMS_SCHEDULER] Rows correspond to DBMS_SCHEDULER jobs
 * ORACLE-DEP [EMBEDDED_SQL:SYSDATE] SYSDATE used in due-job detection queries
 * ORACLE-DEP [EMBEDDED_SQL:NVL_FUNCTION] NVL defaults null next_run_date to SYSDATE for overdue detection
 */
@OracleDependencies({
    @OracleDependency(
        domain = DependencyDomain.PLSQL_INVOCATION,
        category = "DBMS_SCHEDULER",
        description = "Scheduled jobs correspond to DBMS_SCHEDULER jobs in Oracle; this entity tracks them",
        complexity = MigrationComplexity.HIGH,
        automationLevel = AutomationLevel.PARTIAL,
        migrationTarget = "Replace DBMS_SCHEDULER with Spring @Scheduled, Quartz, or external job scheduler"
    ),
    @OracleDependency(
        domain = DependencyDomain.EMBEDDED_SQL,
        category = "SYSDATE",
        description = "SYSDATE used in job scheduling comparisons",
        complexity = MigrationComplexity.LOW,
        automationLevel = AutomationLevel.FULL,
        migrationTarget = "Replace SYSDATE with NOW() or CURRENT_DATE"
    )
})
@NamedNativeQueries({
    @NamedNativeQuery(
        name = "ScheduledJobEntity.findDueJobs",
        // ORACLE-DEP [EMBEDDED_SQL:NVL_FUNCTION] NVL treats null next_run_date as due immediately
        // ORACLE-DEP [EMBEDDED_SQL:SYSDATE] SYSDATE for current Oracle server date comparison
        query = "SELECT j.* FROM MERIDIAN.SCHEDULED_JOBS j " +
                "WHERE j.status = 'ACTIVE' " +
                "AND NVL(j.next_run_date, SYSDATE) <= SYSDATE " +
                "ORDER BY j.next_run_date ASC",
        resultClass = ScheduledJobEntity.class
    ),
    @NamedNativeQuery(
        name = "ScheduledJobEntity.findFailedWithDecodeStatus",
        // ORACLE-DEP [EMBEDDED_SQL:DECODE] DECODE maps last_status codes to display labels
        // ORACLE-DEP [EMBEDDED_SQL:SYSDATE] SYSDATE for 7-day lookback window
        // ORACLE-DEP [EMBEDDED_SQL:NVL_FUNCTION] NVL defaults null last_run_date to SYSDATE
        query = "SELECT j.job_id, j.job_name, j.job_type, " +
                "       DECODE(j.last_status, " +
                "           'FAILED',    'Requires immediate attention', " +
                "           'TIMEOUT',   'Exceeded maximum run time', " +
                "           'SKIPPED',   'Execution window missed', " +
                "           'Unknown failure state') as status_description, " +
                "       NVL(j.last_run_date, SYSDATE) as effective_last_run, " +
                "       j.run_count " +
                "FROM MERIDIAN.SCHEDULED_JOBS j " +
                "WHERE j.last_status IN ('FAILED', 'TIMEOUT', 'SKIPPED') " +
                "AND NVL(j.last_run_date, SYSDATE - 7) >= SYSDATE - 7 " +
                "ORDER BY j.last_run_date DESC",
        resultClass = ScheduledJobEntity.class
    )
})
@Entity
@Table(name = "SCHEDULED_JOBS", schema = "MERIDIAN")
public class ScheduledJobEntity {

    // ORACLE-DEP [CONNECTIVITY_CONFIG:SEQUENCE_GENERATOR] Oracle sequence for PK generation
    @Id
    @SequenceGenerator(
        name = "scheduled_job_seq_gen",
        sequenceName = "MERIDIAN.SCHEDULED_JOB_SEQ",
        allocationSize = 1
    )
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "scheduled_job_seq_gen")
    @Column(name = "JOB_ID")
    private Long jobId;

    /**
     * Must match the DBMS_SCHEDULER job_name exactly for correlation.
     * ORACLE-DEP [PLSQL_INVOCATION:DBMS_SCHEDULER] job_name used in DBMS_SCHEDULER.RUN_JOB calls
     */
    @Column(name = "JOB_NAME", columnDefinition = "VARCHAR2(200)", nullable = false, unique = true)
    private String jobName;

    // ORACLE-DEP [EMBEDDED_SQL:VARCHAR2_TYPE] Oracle VARCHAR2 for job classification
    @Column(name = "JOB_TYPE", columnDefinition = "VARCHAR2(50)", nullable = false)
    private String jobType;

    /**
     * Oracle DBMS_SCHEDULER repeat_interval expression (e.g. FREQ=DAILY;BYHOUR=2).
     * ORACLE-DEP [PLSQL_INVOCATION:DBMS_SCHEDULER] Oracle calendar string syntax for repeat_interval
     */
    @Column(name = "SCHEDULE_EXPR", columnDefinition = "VARCHAR2(500)")
    private String scheduleExpr;

    // ORACLE-DEP [EMBEDDED_SQL:VARCHAR2_TYPE] Oracle VARCHAR2 for job lifecycle status
    @Column(name = "STATUS", columnDefinition = "VARCHAR2(20)", nullable = false)

    private String status = "ACTIVE";

    /**
     * Oracle DATE type — last execution date.
     * ORACLE-DEP [EMBEDDED_SQL:ORACLE_DATE_TYPE] Oracle DATE type for last_run_date
     */
    @Column(name = "LAST_RUN_DATE", columnDefinition = "DATE")
    private LocalDate lastRunDate;

    /**
     * Oracle DATE type — next scheduled execution date.
     * ORACLE-DEP [EMBEDDED_SQL:ORACLE_DATE_TYPE] Oracle DATE type for next_run_date
     * ORACLE-DEP [EMBEDDED_SQL:NVL_FUNCTION] next_run_date may be null; NVL used in due-job query
     */
    @Column(name = "NEXT_RUN_DATE", columnDefinition = "DATE")
    private LocalDate nextRunDate;

    // ORACLE-DEP [EMBEDDED_SQL:VARCHAR2_TYPE] Oracle VARCHAR2 for last execution outcome
    @Column(name = "LAST_STATUS", columnDefinition = "VARCHAR2(20)")
    private String lastStatus;

    /**
     * Cumulative execution count — tracked application-side and in DBMS_SCHEDULER run_count.
     * ORACLE-DEP [EMBEDDED_SQL:NUMBER_TYPE] Oracle NUMBER for run_count
     */
    @Column(name = "RUN_COUNT")

    private Long runCount = 0L;

    // ORACLE-DEP [EMBEDDED_SQL:ORACLE_DATE_TYPE] Oracle DATE for record creation timestamp
    @Column(name = "CREATED_DATE", columnDefinition = "DATE", nullable = false)
    private LocalDate createdDate;
    public ScheduledJobEntity() {
    }


    public Long getJobId() {
        return jobId;
    }

    public void setJobId(Long jobId) {
        this.jobId = jobId;
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

    public String getScheduleExpr() {
        return scheduleExpr;
    }

    public void setScheduleExpr(String scheduleExpr) {
        this.scheduleExpr = scheduleExpr;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public LocalDate getLastRunDate() {
        return lastRunDate;
    }

    public void setLastRunDate(LocalDate lastRunDate) {
        this.lastRunDate = lastRunDate;
    }

    public LocalDate getNextRunDate() {
        return nextRunDate;
    }

    public void setNextRunDate(LocalDate nextRunDate) {
        this.nextRunDate = nextRunDate;
    }

    public String getLastStatus() {
        return lastStatus;
    }

    public void setLastStatus(String lastStatus) {
        this.lastStatus = lastStatus;
    }

    public Long getRunCount() {
        return runCount;
    }

    public void setRunCount(Long runCount) {
        this.runCount = runCount;
    }

    public LocalDate getCreatedDate() {
        return createdDate;
    }

    public void setCreatedDate(LocalDate createdDate) {
        this.createdDate = createdDate;
    }

}
