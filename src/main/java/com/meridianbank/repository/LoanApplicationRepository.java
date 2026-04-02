package com.meridianbank.repository;

import com.meridianbank.annotation.*;
import com.meridianbank.entity.LoanApplicationEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/**
 * Spring Data JPA repository for LoanApplicationEntity.
 * Demonstrates Oracle SQL constructs: MONTHS_BETWEEN for term calculations,
 * ADD_MONTHS for maturity dates, DECODE for loan type mapping,
 * NVL for null-safe defaults, TRUNC for date truncation,
 * TO_CHAR for formatting, and DBMS_LOB.SUBSTR for CLOB preview.
 */
@Repository
public interface LoanApplicationRepository extends JpaRepository<LoanApplicationEntity, Long> {

    // Standard JPQL — no Oracle dependency
    List<LoanApplicationEntity> findByCustomerId(Long customerId);
    List<LoanApplicationEntity> findByStatus(String status);
    List<LoanApplicationEntity> findByLoanType(String loanType);
    List<LoanApplicationEntity> findByCustomerIdAndStatus(Long customerId, String status);

    // ORACLE-DEP [EMBEDDED_SQL:MONTHS_BETWEEN] MONTHS_BETWEEN for loan term tracking
    @OracleDependency(
        domain = DependencyDomain.EMBEDDED_SQL,
        category = "MONTHS_BETWEEN",
        description = "MONTHS_BETWEEN(SYSDATE, application_date) computes months elapsed since application",
        complexity = MigrationComplexity.MEDIUM,
        automationLevel = AutomationLevel.FULL,
        migrationTarget = "Replace MONTHS_BETWEEN(d1,d2) with EXTRACT(EPOCH FROM d1-d2)/2592000 or date_part calculations in PostgreSQL"
    )
    @Query(value =
        "SELECT la.application_id, la.customer_id, la.loan_type, la.requested_amount, " +
        "  la.status, la.application_date, " +
        "  FLOOR(MONTHS_BETWEEN(SYSDATE, la.application_date)) AS months_pending, " +
        "  CASE WHEN MONTHS_BETWEEN(SYSDATE, la.application_date) > 3 THEN 'STALE' ELSE 'CURRENT' END AS age_flag " +
        "FROM MERIDIAN.LOAN_APPLICATIONS la " +
        "WHERE la.status IN ('PENDING', 'UNDER_REVIEW') " +
        "ORDER BY la.application_date ASC",
        nativeQuery = true)
    List<Object[]> findPendingApplicationsWithAge();

    // ORACLE-DEP [EMBEDDED_SQL:ADD_MONTHS] ADD_MONTHS for loan maturity date
    @OracleDependency(
        domain = DependencyDomain.EMBEDDED_SQL,
        category = "ADD_MONTHS",
        description = "ADD_MONTHS(approval_date, loan_term_months) computes loan maturity date",
        complexity = MigrationComplexity.LOW,
        automationLevel = AutomationLevel.FULL,
        migrationTarget = "Replace ADD_MONTHS(date, n) with date + (n || ' months')::INTERVAL in PostgreSQL"
    )
    @Query(value =
        "SELECT la.application_id, la.customer_id, la.approved_amount, " +
        "  la.approval_date, la.term_months, la.interest_rate, " +
        "  ADD_MONTHS(la.approval_date, la.term_months) AS maturity_date, " +
        "  ADD_MONTHS(la.approval_date, ROUND(la.term_months / 2)) AS midpoint_date " +
        "FROM MERIDIAN.LOAN_APPLICATIONS la " +
        "WHERE la.status = 'APPROVED' " +
        "AND la.customer_id = :customerId",
        nativeQuery = true)
    List<Object[]> findApprovedLoansWithMaturityDates(@Param("customerId") Long customerId);

    // ORACLE-DEP [EMBEDDED_SQL:DECODE_FUNCTION] DECODE for loan type categorization
    @OracleDependency(
        domain = DependencyDomain.EMBEDDED_SQL,
        category = "DECODE_FUNCTION",
        description = "DECODE(loan_type,...) maps loan type codes to descriptive category labels",
        complexity = MigrationComplexity.LOW,
        automationLevel = AutomationLevel.FULL,
        migrationTarget = "Replace DECODE with CASE WHEN loan_type = ... THEN ... END in PostgreSQL"
    )
    @OracleDependency(
        domain = DependencyDomain.EMBEDDED_SQL,
        category = "NVL_FUNCTION",
        description = "NVL(approved_amount, 0) provides zero default when loan not yet approved",
        complexity = MigrationComplexity.LOW,
        automationLevel = AutomationLevel.FULL,
        migrationTarget = "Replace NVL(approved_amount, 0) with COALESCE(approved_amount, 0) in PostgreSQL"
    )
    @Query(value =
        "SELECT la.application_id, la.customer_id, la.requested_amount, " +
        "  NVL(la.approved_amount, 0) AS approved_amount, " +
        "  DECODE(la.loan_type, " +
        "    'PERSONAL', 'Personal Loan', " +
        "    'AUTO', 'Auto Loan', " +
        "    'MORTGAGE', 'Home Mortgage', " +
        "    'HELOC', 'Home Equity Line', " +
        "    'STUDENT', 'Student Loan', " +
        "    'BUSINESS', 'Business Loan', 'Other') AS loan_category, " +
        "  DECODE(la.status, 'APPROVED', 'green', 'DENIED', 'red', " +
        "    'PENDING', 'yellow', 'UNDER_REVIEW', 'blue', 'grey') AS status_color " +
        "FROM MERIDIAN.LOAN_APPLICATIONS la " +
        "WHERE la.customer_id = :customerId " +
        "ORDER BY la.application_date DESC",
        nativeQuery = true)
    List<Object[]> findLoanSummaryByCustomer(@Param("customerId") Long customerId);

    // ORACLE-DEP [EMBEDDED_SQL:TRUNC_DATE] TRUNC for same-day application count
    @OracleDependency(
        domain = DependencyDomain.EMBEDDED_SQL,
        category = "TRUNC_DATE",
        description = "TRUNC(application_date) and TRUNC(SYSDATE) compare at day granularity",
        complexity = MigrationComplexity.LOW,
        automationLevel = AutomationLevel.FULL,
        migrationTarget = "Replace TRUNC(date) with DATE_TRUNC('day', date) or date::DATE in PostgreSQL"
    )
    @Query(value =
        "SELECT COUNT(*) FROM MERIDIAN.LOAN_APPLICATIONS " +
        "WHERE customer_id = :customerId " +
        "AND TRUNC(application_date) = TRUNC(SYSDATE) " +
        "AND status NOT IN ('CANCELLED', 'DENIED')",
        nativeQuery = true)
    int countTodayApplicationsForCustomer(@Param("customerId") Long customerId);

    // ORACLE-DEP [EMBEDDED_SQL:TO_CHAR_DATE] TO_CHAR for formatted report output
    @OracleDependency(
        domain = DependencyDomain.EMBEDDED_SQL,
        category = "TO_CHAR_DATE",
        description = "TO_CHAR(application_date,'Month YYYY') formats dates for monthly report grouping",
        complexity = MigrationComplexity.LOW,
        automationLevel = AutomationLevel.FULL,
        migrationTarget = "Replace TO_CHAR(date,'Month YYYY') with TO_CHAR(date,'Month YYYY') in PostgreSQL (compatible)"
    )
    @Query(value =
        "SELECT TO_CHAR(application_date, 'YYYY-MM') AS app_month, " +
        "  loan_type, " +
        "  COUNT(*) AS application_count, " +
        "  SUM(requested_amount) AS total_requested, " +
        "  SUM(NVL(approved_amount, 0)) AS total_approved, " +
        "  COUNT(CASE WHEN status = 'APPROVED' THEN 1 END) AS approved_count " +
        "FROM MERIDIAN.LOAN_APPLICATIONS " +
        "WHERE application_date >= ADD_MONTHS(SYSDATE, -12) " +
        "GROUP BY TO_CHAR(application_date, 'YYYY-MM'), loan_type " +
        "ORDER BY app_month DESC",
        nativeQuery = true)
    List<Object[]> findMonthlyLoanApplicationStats();

    // ORACLE-DEP [EMBEDDED_SQL:DBMS_LOB_SUBSTR] DBMS_LOB.SUBSTR for CLOB preview
    @OracleDependency(
        domain = DependencyDomain.EMBEDDED_SQL,
        category = "DBMS_LOB_SUBSTR",
        description = "DBMS_LOB.SUBSTR(notes, 200, 1) extracts first 200 chars from CLOB notes field for preview",
        complexity = MigrationComplexity.MEDIUM,
        automationLevel = AutomationLevel.PARTIAL,
        migrationTarget = "Replace DBMS_LOB.SUBSTR(clob, n, 1) with SUBSTRING(notes FROM 1 FOR n) or LEFT(notes, n) in PostgreSQL (TEXT type)"
    )
    @Query(value =
        "SELECT la.application_id, la.customer_id, la.loan_type, la.status, " +
        "  la.requested_amount, la.application_date, " +
        "  DBMS_LOB.SUBSTR(la.notes, 200, 1) AS notes_preview " +
        "FROM MERIDIAN.LOAN_APPLICATIONS la " +
        "WHERE la.status = :status " +
        "ORDER BY la.application_date DESC",
        nativeQuery = true)
    List<Object[]> findApplicationsWithNotesPreview(@Param("status") String status);

    // ORACLE-DEP [EMBEDDED_SQL:MONTHS_BETWEEN] Overdue loan detection
    @OracleDependency(
        domain = DependencyDomain.EMBEDDED_SQL,
        category = "MONTHS_BETWEEN",
        description = "MONTHS_BETWEEN(SYSDATE, disbursement_date) vs term_months to identify overdue loans",
        complexity = MigrationComplexity.MEDIUM,
        automationLevel = AutomationLevel.FULL,
        migrationTarget = "Replace MONTHS_BETWEEN with AGE() or epoch calculation in PostgreSQL"
    )
    @Query(value =
        "SELECT la.application_id, la.customer_id, la.approved_amount, " +
        "  la.disbursement_date, la.term_months, " +
        "  ROUND(MONTHS_BETWEEN(SYSDATE, la.disbursement_date)) AS elapsed_months, " +
        "  la.term_months - ROUND(MONTHS_BETWEEN(SYSDATE, la.disbursement_date)) AS remaining_months " +
        "FROM MERIDIAN.LOAN_APPLICATIONS la " +
        "WHERE la.status = 'DISBURSED' " +
        "AND MONTHS_BETWEEN(SYSDATE, la.disbursement_date) >= la.term_months " +
        "ORDER BY la.disbursement_date ASC",
        nativeQuery = true)
    List<Object[]> findMaturedLoans();

    // ORACLE-DEP [EMBEDDED_SQL:SYSDATE] Status update with SYSDATE
    @OracleDependency(
        domain = DependencyDomain.EMBEDDED_SQL,
        category = "SYSDATE",
        description = "SYSDATE used as decision_date timestamp when approving or denying loan application",
        complexity = MigrationComplexity.LOW,
        automationLevel = AutomationLevel.FULL,
        migrationTarget = "Replace SYSDATE with CURRENT_TIMESTAMP in PostgreSQL"
    )
    @Modifying
    @Transactional
    @Query(value =
        "UPDATE MERIDIAN.LOAN_APPLICATIONS " +
        "SET status = :status, " +
        "    approved_amount = :approvedAmount, " +
        "    decision_date = SYSDATE, " +
        "    updated_date = SYSDATE " +
        "WHERE application_id = :applicationId",
        nativeQuery = true)
    int updateLoanDecision(@Param("applicationId") Long applicationId,
                           @Param("status") String status,
                           @Param("approvedAmount") BigDecimal approvedAmount);
}
