package com.meridianbank.repository;

import com.meridianbank.annotation.*;
import com.meridianbank.entity.ScheduledJobEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * Spring Data JPA repository for ScheduledJobEntity.
 * Demonstrates Oracle SQL constructs: SYSDATE+(1/24) for hourly scheduling,
 * TRUNC for day-level truncation, NVL(last_run_date, SYSDATE-365) for
 * never-run defaulting, and DECODE for job type and status labeling.
 */
@Repository
public interface ScheduledJobRepository extends JpaRepository<ScheduledJobEntity, Long> {

    // Standard JPQL — no Oracle dependency
    Optional<ScheduledJobEntity> findByJobName(String jobName);
    List<ScheduledJobEntity> findByStatus(String status);
    List<ScheduledJobEntity> findByJobType(String jobType);

    // ORACLE-DEP [EMBEDDED_SQL:DATE_ARITHMETIC] SYSDATE + (1/24) for next hourly run
    @OracleDependency(
        domain = DependencyDomain.EMBEDDED_SQL,
        category = "DATE_ARITHMETIC",
        description = "SYSDATE + (1/24) adds exactly one hour using Oracle fractional day arithmetic",
        complexity = MigrationComplexity.MEDIUM,
        automationLevel = AutomationLevel.PARTIAL,
        migrationTarget = "Replace SYSDATE + (1/24) with NOW() + INTERVAL '1 hour' in PostgreSQL"
    )
    @OracleDependency(
        domain = DependencyDomain.EMBEDDED_SQL,
        category = "SYSDATE",
        description = "SYSDATE used to compute next_run_date for scheduled job queue",
        complexity = MigrationComplexity.LOW,
        automationLevel = AutomationLevel.FULL,
        migrationTarget = "Replace SYSDATE with NOW() or CURRENT_TIMESTAMP in PostgreSQL"
    )
    @Query(value =
        "SELECT * FROM MERIDIAN.SCHEDULED_JOBS " +
        "WHERE next_run_date <= SYSDATE " +
        "AND status = 'ACTIVE' " +
        "ORDER BY next_run_date ASC",
        nativeQuery = true)
    List<ScheduledJobEntity> findJobsDue();

    // ORACLE-DEP [EMBEDDED_SQL:TRUNC_DATE] TRUNC for same-day job run detection
    @OracleDependency(
        domain = DependencyDomain.EMBEDDED_SQL,
        category = "TRUNC_DATE",
        description = "TRUNC(last_run_date) = TRUNC(SYSDATE) detects jobs already run today",
        complexity = MigrationComplexity.LOW,
        automationLevel = AutomationLevel.FULL,
        migrationTarget = "Replace TRUNC(date) = TRUNC(SYSDATE) with last_run_date::DATE = CURRENT_DATE in PostgreSQL"
    )
    @Query(value =
        "SELECT * FROM MERIDIAN.SCHEDULED_JOBS " +
        "WHERE TRUNC(last_run_date) = TRUNC(SYSDATE) " +
        "AND job_type = :jobType " +
        "ORDER BY last_run_date DESC",
        nativeQuery = true)
    List<ScheduledJobEntity> findJobsRunToday(@Param("jobType") String jobType);

    // ORACLE-DEP [EMBEDDED_SQL:NVL_FUNCTION] NVL(last_run_date, SYSDATE-365) for never-run jobs
    @OracleDependency(
        domain = DependencyDomain.EMBEDDED_SQL,
        category = "NVL_FUNCTION",
        description = "NVL(last_run_date, SYSDATE-365) treats never-run jobs as if last run 1 year ago",
        complexity = MigrationComplexity.LOW,
        automationLevel = AutomationLevel.FULL,
        migrationTarget = "Replace NVL(last_run_date, NOW()-INTERVAL '1 year') with COALESCE(last_run_date, NOW()-INTERVAL '1 year') in PostgreSQL"
    )
    @OracleDependency(
        domain = DependencyDomain.EMBEDDED_SQL,
        category = "DATE_ARITHMETIC",
        description = "SYSDATE - NVL(last_run_date, SYSDATE - 365) computes days since last successful run",
        complexity = MigrationComplexity.LOW,
        automationLevel = AutomationLevel.FULL,
        migrationTarget = "Replace with EXTRACT(EPOCH FROM NOW() - COALESCE(last_run_date, NOW() - INTERVAL '365 days'))/86400 in PostgreSQL"
    )
    @Query(value =
        "SELECT j.job_id, j.job_name, j.job_type, j.status, " +
        "  NVL(j.last_run_date, SYSDATE - 365) AS effective_last_run, " +
        "  ROUND(SYSDATE - NVL(j.last_run_date, SYSDATE - 365)) AS days_since_last_run " +
        "FROM MERIDIAN.SCHEDULED_JOBS j " +
        "ORDER BY days_since_last_run DESC",
        nativeQuery = true)
    List<Object[]> findJobsWithRunHistory();

    // ORACLE-DEP [EMBEDDED_SQL:DECODE_FUNCTION] DECODE for job type and status labeling
    @OracleDependency(
        domain = DependencyDomain.EMBEDDED_SQL,
        category = "DECODE_FUNCTION",
        description = "DECODE(job_type,...) maps job type codes to descriptive category names",
        complexity = MigrationComplexity.LOW,
        automationLevel = AutomationLevel.FULL,
        migrationTarget = "Replace DECODE(job_type,...) with CASE WHEN job_type=... THEN ... END in PostgreSQL"
    )
    @Query(value =
        "SELECT j.job_id, j.job_name, " +
        "  DECODE(j.job_type, " +
        "    'EOD', 'End of Day Processing', " +
        "    'INTEREST', 'Interest Calculation', " +
        "    'STATEMENT', 'Statement Generation', " +
        "    'PURGE', 'Data Purge', " +
        "    'REPORT', 'Report Generation', j.job_type) AS job_description, " +
        "  DECODE(j.status, 'ACTIVE', 'Running', 'INACTIVE', 'Disabled', " +
        "    'ERROR', 'Failed', 'COMPLETED', 'Done', j.status) AS status_label, " +
        "  j.schedule, j.last_run_date, j.next_run_date " +
        "FROM MERIDIAN.SCHEDULED_JOBS j " +
        "ORDER BY j.job_name",
        nativeQuery = true)
    List<Object[]> findAllJobsWithLabels();

    // ORACLE-DEP [EMBEDDED_SQL:DATE_ARITHMETIC] SYSDATE+(1/24)*:hours for next run scheduling
    @OracleDependency(
        domain = DependencyDomain.EMBEDDED_SQL,
        category = "DATE_ARITHMETIC",
        description = "SYSDATE + (1.0/24) * :intervalHours computes next run time using fractional day arithmetic",
        complexity = MigrationComplexity.MEDIUM,
        automationLevel = AutomationLevel.PARTIAL,
        migrationTarget = "Replace with NOW() + (:intervalHours || ' hours')::INTERVAL in PostgreSQL"
    )
    @Modifying
    @Transactional
    @Query(value =
        "UPDATE MERIDIAN.SCHEDULED_JOBS " +
        "SET last_run_date = SYSDATE, " +
        "    next_run_date = SYSDATE + (1.0 / 24) * :intervalHours, " +
        "    status = :newStatus " +
        "WHERE job_id = :jobId",
        nativeQuery = true)
    int updateJobAfterRun(@Param("jobId") Long jobId,
                           @Param("intervalHours") int intervalHours,
                           @Param("newStatus") String newStatus);

    // ORACLE-DEP [EMBEDDED_SQL:NVL_FUNCTION] + TRUNC for overdue job detection
    @OracleDependency(
        domain = DependencyDomain.EMBEDDED_SQL,
        category = "NVL_FUNCTION",
        description = "NVL(next_run_date, SYSDATE) treats null next_run as immediately due",
        complexity = MigrationComplexity.LOW,
        automationLevel = AutomationLevel.FULL,
        migrationTarget = "Replace NVL(next_run_date, SYSDATE) with COALESCE(next_run_date, NOW()) in PostgreSQL"
    )
    @Query(value =
        "SELECT j.job_id, j.job_name, j.job_type, " +
        "  NVL(j.next_run_date, SYSDATE) AS effective_next_run, " +
        "  ROUND((SYSDATE - NVL(j.next_run_date, SYSDATE)) * 24, 2) AS hours_overdue " +
        "FROM MERIDIAN.SCHEDULED_JOBS j " +
        "WHERE NVL(j.next_run_date, SYSDATE) < SYSDATE " +
        "AND j.status = 'ACTIVE' " +
        "ORDER BY hours_overdue DESC",
        nativeQuery = true)
    List<Object[]> findOverdueJobs();
}
