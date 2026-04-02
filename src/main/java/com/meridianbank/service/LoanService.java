package com.meridianbank.service;

import com.meridianbank.annotation.AutomationLevel;
import com.meridianbank.annotation.DependencyDomain;
import com.meridianbank.annotation.MigrationComplexity;
import com.meridianbank.annotation.OracleDependency;
import com.meridianbank.dao.LoanOracleDAO;
import com.meridianbank.dao.SecurityOracleDAO;
import com.meridianbank.dto.LoanApplicationDTO;
import com.meridianbank.entity.LoanApplicationEntity;
import com.meridianbank.exception.MeridianBankException;
import com.meridianbank.exception.OracleErrorTranslator;
import com.meridianbank.repository.LoanApplicationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * Service layer for Loan Application operations.
 *
 * Orchestrates LoanApplicationRepository (Spring Data JPA) and LoanOracleDAO
 * (raw JDBC with Oracle-specific APIs). Exercises the following Oracle dependencies:
 *
 * ORACLE-DEP [PLSQL_INVOCATION:PACKAGE_CALL]   LOAN_PKG.SUBMIT_APPLICATION via LoanOracleDAO
 * ORACLE-DEP [PLSQL_INVOCATION:REF_CURSOR]     LOAN_PKG returns SYS_REFCURSOR for underwriting data
 * ORACLE-DEP [PLSQL_INVOCATION:DBMS_CRYPTO]    SSN encrypted via SecurityOracleDAO during submission
 * ORACLE-DEP [EMBEDDED_SQL:MONTHS_BETWEEN]     Loan term expressed as MONTHS_BETWEEN(start,end)
 * ORACLE-DEP [EMBEDDED_SQL:ADD_MONTHS]         Maturity date = ADD_MONTHS(disbursementDate, termMonths)
 * ORACLE-DEP [EMBEDDED_SQL:DATE_SUBTRACTION]   Days overdue = SYSDATE - maturity_date returns NUMBER
 * ORACLE-DEP [EMBEDDED_SQL:CLOB_HANDLING]      Loan notes stored as Oracle CLOB
 * ORACLE-DEP [EMBEDDED_SQL:EMPTY_CLOB]         EMPTY_CLOB() initialiser used in DAO INSERT
 * ORACLE-DEP [EMBEDDED_SQL:ORACLE_ERROR_CODES] ORA- codes translated in catch blocks
 */
@Service
@Transactional
public class LoanService {

    private static final Logger log = LoggerFactory.getLogger(LoanService.class);

    @Autowired
    private LoanApplicationRepository loanApplicationRepository;

    @Autowired
    private LoanOracleDAO loanOracleDAO;

    @Autowired
    private SecurityOracleDAO securityOracleDAO;

    @Autowired
    private OracleErrorTranslator errorTranslator;

    // -----------------------------------------------------------------------
    // Loan application submission
    // -----------------------------------------------------------------------

    /**
     * Submits a loan application.
     *
     * Oracle-specific behaviours:
     *  1. SSN encrypted via DBMS_CRYPTO before being stored with the application
     *  2. LoanOracleDAO.submitApplication calls LOAN_PKG.SUBMIT_APPLICATION
     *  3. Application notes stored as Oracle CLOB (EMPTY_CLOB() initialiser in INSERT)
     *  4. Generated application_id returned via OracleTypes.NUMBER OUT parameter
     */
    @OracleDependency(
        domain = DependencyDomain.PLSQL_INVOCATION,
        category = "DBMS_CRYPTO",
        description = "Applicant SSN encrypted using SECURITY_PKG.ENCRYPT_SSN (wraps DBMS_CRYPTO.ENCRYPT) before loan record insert",
        complexity = MigrationComplexity.HIGH,
        automationLevel = AutomationLevel.PARTIAL,
        migrationTarget = "Replace DBMS_CRYPTO with application-tier AES-256 encryption (JCE AES/GCM)"
    )
    @OracleDependency(
        domain = DependencyDomain.PLSQL_INVOCATION,
        category = "PACKAGE_CALL",
        description = "Loan submission calls LOAN_PKG.SUBMIT_APPLICATION with OracleTypes.NUMBER OUT for application_id",
        complexity = MigrationComplexity.HIGH,
        automationLevel = AutomationLevel.PARTIAL,
        migrationTarget = "Replace Oracle package call with direct INSERT and PostgreSQL RETURNING clause"
    )
    @OracleDependency(
        domain = DependencyDomain.EMBEDDED_SQL,
        category = "EMPTY_CLOB_INIT",
        description = "Loan notes CLOB column initialised with EMPTY_CLOB() in the INSERT inside LOAN_PKG",
        complexity = MigrationComplexity.MEDIUM,
        automationLevel = AutomationLevel.PARTIAL,
        migrationTarget = "Remove EMPTY_CLOB(); PostgreSQL TEXT columns are initialised with NULL or '' directly"
    )
    public LoanApplicationDTO submitLoanApplication(LoanApplicationDTO dto) {
        log.info("Submitting {} loan application for customer {}, amount={}",
            dto.getLoanType(), dto.getCustomerId(), dto.getRequestedAmount());

        validateLoanApplication(dto);

        // ORACLE-DEP [PLSQL_INVOCATION:DBMS_CRYPTO] Encrypt SSN before storing with application
        // SecurityOracleDAO calls SECURITY_PKG.ENCRYPT_SSN which uses DBMS_CRYPTO internally
        String encryptedSsn = null;
        if (dto.getCustomerName() != null && dto.getCustomerName().contains("|SSN:")) {
            // Convention: SSN passed as "CustomerName|SSN:123-45-6789" in test scenarios
            String rawSsn = dto.getCustomerName().substring(
                dto.getCustomerName().indexOf("|SSN:") + 5);
            encryptedSsn = securityOracleDAO.encryptSSN(rawSsn);
            dto.setCustomerName(dto.getCustomerName().substring(
                0, dto.getCustomerName().indexOf("|SSN:")));
            log.debug("SSN encrypted for loan application");
        }

        if (dto.getApplicationDate() == null) {
            dto.setApplicationDate(LocalDate.now());
        }

        // ORACLE-DEP [PLSQL_INVOCATION:PACKAGE_CALL] LoanOracleDAO.submitApplication calls
        // LOAN_PKG.SUBMIT_APPLICATION and registers OracleTypes.NUMBER as OUT param for new ID
        // ORACLE-DEP [EMBEDDED_SQL:EMPTY_CLOB_INIT] Notes CLOB initialised with EMPTY_CLOB() inside procedure
        Long applicationId;
        try {
            applicationId = loanOracleDAO.submitApplication(dto);
        } catch (SQLException e) {
            // ORACLE-DEP [EMBEDDED_SQL:ORACLE_ERROR_CODES] ORA-02291 (FK) if customer doesn't exist
            throw errorTranslator.translateSQLException(e);
        }

        log.info("Loan application submitted with ID {}", applicationId);

        dto.setApplicationId(applicationId);
        dto.setStatus("PENDING");
        // Compute expected maturity date using ADD_MONTHS equivalent
        if (dto.getTermMonths() != null && dto.getApplicationDate() != null) {
            // ORACLE-DEP [EMBEDDED_SQL:ADD_MONTHS] calculateMaturityDate mirrors Oracle ADD_MONTHS()
            dto.setMaturityDate(calculateMaturityDate(dto.getApplicationDate(), dto.getTermMonths()));
        }
        return dto;
    }

    // -----------------------------------------------------------------------
    // Retrieval
    // -----------------------------------------------------------------------

    /**
     * Retrieves a loan application by primary key.
     * Delegates to LoanOracleDAO which reads the CLOB notes field using oracle.sql.CLOB.
     */
    @OracleDependency(
        domain = DependencyDomain.ORACLE_JAVA_API,
        category = "ORACLE_CLOB",
        description = "Loan application retrieval reads notes CLOB via oracle.sql.CLOB in LoanOracleDAO",
        complexity = MigrationComplexity.MEDIUM,
        automationLevel = AutomationLevel.PARTIAL,
        migrationTarget = "Replace oracle.sql.CLOB with standard java.sql.Clob or direct String binding in PostgreSQL"
    )
    @Transactional(readOnly = true)
    public LoanApplicationDTO getLoanById(Long applicationId) {
        // ORACLE-DEP [ORACLE_JAVA_API:ORACLE_CLOB] LoanOracleDAO.findById uses oracle.sql.CLOB to read notes
        try {
            return loanOracleDAO.findById(applicationId)
                .orElseThrow(() -> new MeridianBankException("NOT_FOUND",
                    "Loan application not found with ID: " + applicationId, 404));
        } catch (SQLException e) {
            throw errorTranslator.translateSQLException(e);
        }
    }

    /**
     * Returns all loan applications for a customer.
     */
    @Transactional(readOnly = true)
    public List<LoanApplicationDTO> getLoansByCustomer(Long customerId) {
        List<LoanApplicationEntity> entities =
            loanApplicationRepository.findByCustomerId(customerId);
        return entitiesToDtos(entities);
    }

    /**
     * Returns all loan applications with a specific status.
     */
    @Transactional(readOnly = true)
    public List<LoanApplicationDTO> getLoansByStatus(String status) {
        validateStatus(status);
        List<LoanApplicationEntity> entities =
            loanApplicationRepository.findByStatus(status);
        return entitiesToDtos(entities);
    }

    /**
     * Returns pending applications with their age in days.
     * The repository native query uses SYSDATE - application_date which in Oracle
     * returns a NUMBER (days as decimal fraction) rather than an interval.
     */
    @OracleDependency(
        domain = DependencyDomain.EMBEDDED_SQL,
        category = "DATE_SUBTRACTION",
        description = "Pending loan age uses SYSDATE - application_date Oracle date subtraction returning NUMBER of days",
        complexity = MigrationComplexity.MEDIUM,
        automationLevel = AutomationLevel.PARTIAL,
        migrationTarget = "PostgreSQL: NOW() - application_date returns INTERVAL; use EXTRACT(EPOCH FROM (NOW()-application_date))/86400 for numeric days"
    )
    @Transactional(readOnly = true)
    public List<Object[]> getPendingApplicationsWithAge() {
        // ORACLE-DEP [EMBEDDED_SQL:DATE_SUBTRACTION] Native query: SYSDATE - application_date → NUMBER
        return loanApplicationRepository.findPendingApplicationsWithAge();
    }

    /**
     * Returns overdue loans where maturity date has passed.
     * LoanOracleDAO.findOverdueLoans executes:
     *   SELECT l.*, TRUNC(SYSDATE) - TRUNC(l.maturity_date) AS days_overdue
     *   FROM loan_applications l
     *   WHERE l.status = 'APPROVED'
     *   AND TRUNC(SYSDATE) > TRUNC(l.maturity_date)
     *
     * The date subtraction (date - date) returns a NUMBER in Oracle.
     */
    @OracleDependency(
        domain = DependencyDomain.EMBEDDED_SQL,
        category = "DATE_SUBTRACTION",
        description = "Days overdue = TRUNC(SYSDATE) - TRUNC(maturity_date) returns Oracle NUMBER of days",
        complexity = MigrationComplexity.MEDIUM,
        automationLevel = AutomationLevel.PARTIAL,
        migrationTarget = "PostgreSQL: CURRENT_DATE - maturity_date returns INTEGER automatically"
    )
    @OracleDependency(
        domain = DependencyDomain.EMBEDDED_SQL,
        category = "TRUNC_DATE_FUNCTION",
        description = "TRUNC(SYSDATE) and TRUNC(maturity_date) used to strip time component for pure date comparison",
        complexity = MigrationComplexity.LOW,
        automationLevel = AutomationLevel.FULL,
        migrationTarget = "Replace TRUNC(date) with DATE_TRUNC('day', timestamp)::date in PostgreSQL"
    )
    @Transactional(readOnly = true)
    public List<LoanApplicationDTO> getOverdueLoans() {
        // ORACLE-DEP [EMBEDDED_SQL:DATE_SUBTRACTION] LoanOracleDAO.findOverdueLoans uses
        // TRUNC(SYSDATE) - TRUNC(maturity_date) which returns Oracle NUMBER
        try {
            return loanOracleDAO.findOverdueLoans();
        } catch (SQLException e) {
            throw errorTranslator.translateSQLException(e);
        }
    }

    /**
     * Returns approved loans with computed maturity dates.
     * The repository native query uses ADD_MONTHS(disbursement_date, term_months)
     * to compute the maturity date dynamically.
     */
    @OracleDependency(
        domain = DependencyDomain.EMBEDDED_SQL,
        category = "ADD_MONTHS_FUNCTION",
        description = "Approved loan maturity dates computed via ADD_MONTHS(disbursement_date, term_months) in native query",
        complexity = MigrationComplexity.LOW,
        automationLevel = AutomationLevel.FULL,
        migrationTarget = "Replace ADD_MONTHS(d, n) with d + (n || ' months')::interval in PostgreSQL"
    )
    @Transactional(readOnly = true)
    public List<Object[]> getApprovedLoansWithMaturityDates(Long customerId) {
        // ORACLE-DEP [EMBEDDED_SQL:ADD_MONTHS_FUNCTION] Repository native: ADD_MONTHS(disbursement_date, term_months)
        return loanApplicationRepository.findApprovedLoansWithMaturityDates(customerId);
    }

    /**
     * Returns monthly loan application statistics.
     * The repository native query groups by TO_CHAR(application_date,'YYYY-MM')
     * and uses COUNT(*) and SUM(requested_amount) with Oracle date functions.
     */
    @OracleDependency(
        domain = DependencyDomain.EMBEDDED_SQL,
        category = "TO_CHAR_FUNCTION",
        description = "Monthly loan stats group by TO_CHAR(application_date,'YYYY-MM') for period aggregation",
        complexity = MigrationComplexity.LOW,
        automationLevel = AutomationLevel.FULL,
        migrationTarget = "TO_CHAR works in PostgreSQL with the same format mask"
    )
    @Transactional(readOnly = true)
    public List<Object[]> getMonthlyLoanApplicationStats() {
        // ORACLE-DEP [EMBEDDED_SQL:TO_CHAR_FUNCTION] Repository: TO_CHAR(application_date,'YYYY-MM')
        return loanApplicationRepository.findMonthlyLoanApplicationStats();
    }

    /**
     * Returns loan applications with a preview of the notes CLOB.
     * The repository native query uses Oracle SUBSTR(notes, 1, 200) to return
     * the first 200 characters without loading the full CLOB.
     */
    @OracleDependency(
        domain = DependencyDomain.EMBEDDED_SQL,
        category = "SUBSTR_FUNCTION",
        description = "Notes preview uses Oracle SUBSTR(notes,1,200) — 1-based string indexing to extract CLOB preview",
        complexity = MigrationComplexity.LOW,
        automationLevel = AutomationLevel.FULL,
        migrationTarget = "Replace SUBSTR(notes,1,200) with SUBSTRING(notes FROM 1 FOR 200) or LEFT(notes,200) in PostgreSQL"
    )
    @Transactional(readOnly = true)
    public List<Object[]> getApplicationsWithNotesPreview(String status) {
        // ORACLE-DEP [EMBEDDED_SQL:SUBSTR_FUNCTION] Repository native: SUBSTR(l.notes,1,200) CLOB preview
        return loanApplicationRepository.findApplicationsWithNotesPreview(status);
    }

    // -----------------------------------------------------------------------
    // Approval workflow
    // -----------------------------------------------------------------------

    /**
     * Approves a loan application.
     *
     * Calls LOAN_PKG.APPROVE_LOAN via LoanOracleDAO to update the application status,
     * set the approved amount and interest rate, and trigger underwriting finalisation.
     */
    @OracleDependency(
        domain = DependencyDomain.PLSQL_INVOCATION,
        category = "PACKAGE_CALL",
        description = "Loan approval calls LOAN_PKG.APPROVE_LOAN; procedure updates status and triggers Oracle Text re-index",
        complexity = MigrationComplexity.HIGH,
        automationLevel = AutomationLevel.PARTIAL,
        migrationTarget = "Replace Oracle package call with direct UPDATE + application-tier search index update"
    )
    public LoanApplicationDTO approveLoan(Long applicationId, BigDecimal approvedAmount,
                                           BigDecimal interestRate, int termMonths) {
        log.info("Approving loan {}: amount={}, rate={}, term={}",
            applicationId, approvedAmount, interestRate, termMonths);

        if (approvedAmount == null || approvedAmount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new MeridianBankException("VALIDATION_ERROR",
                "Approved amount must be positive.", 400);
        }
        if (interestRate == null || interestRate.compareTo(BigDecimal.ZERO) < 0
                || interestRate.compareTo(BigDecimal.valueOf(100)) > 0) {
            throw new MeridianBankException("VALIDATION_ERROR",
                "Interest rate must be between 0 and 100.", 400);
        }
        if (termMonths <= 0 || termMonths > 360) {
            throw new MeridianBankException("VALIDATION_ERROR",
                "Term must be between 1 and 360 months.", 400);
        }

        // ORACLE-DEP [PLSQL_INVOCATION:PACKAGE_CALL] LoanOracleDAO.approveLoan calls LOAN_PKG.APPROVE_LOAN
        try {
            loanOracleDAO.approveLoan(applicationId, approvedAmount, interestRate, termMonths);
        } catch (SQLException e) {
            throw errorTranslator.translateSQLException(e);
        }

        // Return the updated application
        LoanApplicationDTO result;
        try {
            result = loanOracleDAO.findById(applicationId)
                .orElseThrow(() -> new MeridianBankException("NOT_FOUND",
                    "Loan application not found after approval: " + applicationId, 500));
        } catch (SQLException e) {
            throw errorTranslator.translateSQLException(e);
        }

        // Compute maturity date via ADD_MONTHS equivalent
        LocalDate disbursement = result.getDisbursementDate() != null
            ? result.getDisbursementDate() : LocalDate.now();
        // ORACLE-DEP [EMBEDDED_SQL:ADD_MONTHS] Java equivalent of Oracle ADD_MONTHS(disbursement, termMonths)
        result.setMaturityDate(calculateMaturityDate(disbursement, termMonths));

        // Compute monthly payment
        result.setMonthlyPayment(computeMonthlyPayment(approvedAmount, interestRate, termMonths));
        result.setTotalInterest(computeTotalInterest(approvedAmount, interestRate, termMonths));

        return result;
    }

    // -----------------------------------------------------------------------
    // Underwriting
    // -----------------------------------------------------------------------

    /**
     * Retrieves underwriting data for a loan type.
     *
     * LoanOracleDAO.getUnderwritingData calls LOAN_PKG.GET_UNDERWRITING_DATA which
     * returns a SYS_REFCURSOR containing historical performance data for the loan type.
     * The DAO registers OracleTypes.CURSOR as an OUT parameter and calls getCursor().
     */
    @OracleDependency(
        domain = DependencyDomain.PLSQL_INVOCATION,
        category = "REF_CURSOR",
        description = "Underwriting data retrieved via SYS_REFCURSOR OUT param from LOAN_PKG.GET_UNDERWRITING_DATA",
        complexity = MigrationComplexity.HIGH,
        automationLevel = AutomationLevel.PARTIAL,
        migrationTarget = "Replace REF CURSOR with standard ResultSet from a direct SELECT or PostgreSQL function returning SETOF"
    )
    @Transactional(readOnly = true)
    public List<LoanApplicationDTO> getUnderwritingData(String loanType) {
        validateLoanType(loanType);
        // ORACLE-DEP [PLSQL_INVOCATION:REF_CURSOR] LoanOracleDAO.getUnderwritingData registers
        // OracleTypes.CURSOR as OUT parameter and calls OracleCallableStatement.getCursor()
        try {
            return loanOracleDAO.getUnderwritingData(loanType);
        } catch (SQLException e) {
            throw errorTranslator.translateSQLException(e);
        }
    }

    // -----------------------------------------------------------------------
    // Oracle date function equivalents — documented as migration targets
    // -----------------------------------------------------------------------

    /**
     * Computes the number of months between two dates.
     *
     * In Oracle SQL this is:  MONTHS_BETWEEN(:endDate, :startDate)
     * This Java method is the application-tier equivalent, documented as an
     * Oracle dependency because the migration framework must detect calls to
     * MONTHS_BETWEEN in embedded SQL and convert them to this pattern (or the
     * PostgreSQL equivalent).
     *
     * Oracle MONTHS_BETWEEN returns a fractional number of months. This implementation
     * returns the integer count for loan term purposes.
     */
    @OracleDependency(
        domain = DependencyDomain.EMBEDDED_SQL,
        category = "MONTHS_BETWEEN_FUNCTION",
        description = "Application-tier equivalent of Oracle MONTHS_BETWEEN(end_date, start_date) for loan term calculation",
        complexity = MigrationComplexity.LOW,
        automationLevel = AutomationLevel.FULL,
        migrationTarget = "Oracle MONTHS_BETWEEN(d1,d2) → PostgreSQL: EXTRACT(YEAR FROM AGE(d1,d2))*12 + EXTRACT(MONTH FROM AGE(d1,d2))"
    )
    public int calculateMonthsBetween(LocalDate startDate, LocalDate endDate) {
        if (startDate == null || endDate == null) {
            throw new MeridianBankException("VALIDATION_ERROR",
                "Both start date and end date are required.", 400);
        }
        // ORACLE-DEP [EMBEDDED_SQL:MONTHS_BETWEEN_FUNCTION] This is the Java equivalent of
        // Oracle's MONTHS_BETWEEN(endDate, startDate) used in loan term calculations.
        // Oracle SQL version:  SELECT MONTHS_BETWEEN(:endDate, :startDate) FROM DUAL
        long totalMonths = ChronoUnit.MONTHS.between(startDate, endDate);
        return (int) totalMonths;
    }

    /**
     * Computes the maturity date for a loan.
     *
     * In Oracle SQL this is:  ADD_MONTHS(:startDate, :termMonths)
     * Documented as an Oracle dependency so the migration framework recognises
     * the ADD_MONTHS pattern in SQL and maps it to this application-tier pattern.
     */
    @OracleDependency(
        domain = DependencyDomain.EMBEDDED_SQL,
        category = "ADD_MONTHS_FUNCTION",
        description = "Application-tier equivalent of Oracle ADD_MONTHS(start_date, term_months) for loan maturity calculation",
        complexity = MigrationComplexity.LOW,
        automationLevel = AutomationLevel.FULL,
        migrationTarget = "Oracle ADD_MONTHS(d, n) → PostgreSQL: d + (n || ' months')::interval (or d + make_interval(months=>n))"
    )
    public LocalDate calculateMaturityDate(LocalDate startDate, int termMonths) {
        if (startDate == null) {
            throw new MeridianBankException("VALIDATION_ERROR",
                "Start date is required for maturity calculation.", 400);
        }
        if (termMonths <= 0) {
            throw new MeridianBankException("VALIDATION_ERROR",
                "Term months must be positive.", 400);
        }
        // ORACLE-DEP [EMBEDDED_SQL:ADD_MONTHS_FUNCTION] Java equivalent of Oracle ADD_MONTHS(:date, :months)
        // Oracle SQL version:  SELECT ADD_MONTHS(:startDate, :termMonths) FROM DUAL
        return startDate.plusMonths(termMonths);
    }

    /**
     * Returns loan summary grouped by customer with Oracle analytic functions.
     * The repository native query uses SUM/COUNT OVER (PARTITION BY customer_id)
     * and NVL to handle customers with no approved amount.
     */
    @OracleDependency(
        domain = DependencyDomain.EMBEDDED_SQL,
        category = "NVL_FUNCTION",
        description = "Loan summary uses NVL(approved_amount, requested_amount) to fall back to requested when not yet approved",
        complexity = MigrationComplexity.LOW,
        automationLevel = AutomationLevel.FULL,
        migrationTarget = "Replace NVL(approved_amount, requested_amount) with COALESCE(approved_amount, requested_amount)"
    )
    @Transactional(readOnly = true)
    public List<Object[]> getLoanSummaryByCustomer(Long customerId) {
        // ORACLE-DEP [EMBEDDED_SQL:NVL_FUNCTION] Repository native: NVL(approved_amount, requested_amount)
        return loanApplicationRepository.findLoanSummaryByCustomer(customerId);
    }

    // -----------------------------------------------------------------------
    // Private helpers
    // -----------------------------------------------------------------------

    private List<LoanApplicationDTO> entitiesToDtos(List<LoanApplicationEntity> entities) {
        List<LoanApplicationDTO> results = new ArrayList<>();
        for (LoanApplicationEntity e : entities) {
            results.add(entityToDto(e));
        }
        return results;
    }

    private LoanApplicationDTO entityToDto(LoanApplicationEntity entity) {
        LoanApplicationDTO dto = new LoanApplicationDTO();
        dto.setApplicationId(entity.getApplicationId());
        dto.setApplicationNumber(null); // LoanApplicationEntity has no applicationNumber field
        dto.setCustomerId(entity.getCustomerId());
        dto.setLoanType(entity.getLoanType());
        dto.setRequestedAmount(entity.getRequestedAmount());
        dto.setApprovedAmount(entity.getApprovedAmount());
        dto.setInterestRate(entity.getInterestRate());
        dto.setTermMonths(entity.getTermMonths());
        dto.setStatus(entity.getStatus());
        dto.setApplicationDate(entity.getApplicationDate());
        dto.setDecisionDate(entity.getDecisionDate());
        dto.setDisbursementDate(null); // LoanApplicationEntity has no disbursementDate field
        dto.setMaturityDate(null);     // LoanApplicationEntity has no maturityDate field
        dto.setNotes(entity.getNotes());  // CLOB field
        dto.setReviewedBy(null);       // LoanApplicationEntity has no reviewedBy field
        dto.setDenialReason(null);     // LoanApplicationEntity has no denialReason field
        dto.setCreatedDate(entity.getCreatedDate() != null ? entity.getCreatedDate().atStartOfDay() : null);
        dto.setUpdatedDate(null);      // LoanApplicationEntity has no updatedDate field
        return dto;
    }

    private void validateLoanApplication(LoanApplicationDTO dto) {
        if (dto.getCustomerId() == null) {
            throw new MeridianBankException("VALIDATION_ERROR",
                "Customer ID is required.", 400);
        }
        validateLoanType(dto.getLoanType());
        if (dto.getRequestedAmount() == null || dto.getRequestedAmount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new MeridianBankException("VALIDATION_ERROR",
                "Requested amount must be positive.", 400);
        }
        if (dto.getTermMonths() == null || dto.getTermMonths() <= 0 || dto.getTermMonths() > 360) {
            throw new MeridianBankException("VALIDATION_ERROR",
                "Term months must be between 1 and 360.", 400);
        }
    }

    private void validateLoanType(String loanType) {
        if (loanType == null) {
            throw new MeridianBankException("VALIDATION_ERROR",
                "Loan type is required.", 400);
        }
        switch (loanType.toUpperCase()) {
            case "PERSONAL", "MORTGAGE", "AUTO", "BUSINESS", "STUDENT" -> { /* valid */ }
            default -> throw new MeridianBankException("VALIDATION_ERROR",
                "Invalid loan type: " + loanType, 400);
        }
    }

    private void validateStatus(String status) {
        switch (status != null ? status.toUpperCase() : "") {
            case "PENDING", "UNDER_REVIEW", "APPROVED", "DENIED", "WITHDRAWN" -> { /* valid */ }
            default -> throw new MeridianBankException("VALIDATION_ERROR",
                "Invalid loan status: " + status, 400);
        }
    }

    /**
     * Computes monthly payment using standard amortisation formula.
     * Monthly payment = P * r(1+r)^n / ((1+r)^n - 1)
     * where r = monthly rate, n = term months, P = principal.
     */
    private BigDecimal computeMonthlyPayment(BigDecimal principal,
                                              BigDecimal annualRatePercent,
                                              int termMonths) {
        if (annualRatePercent.compareTo(BigDecimal.ZERO) == 0) {
            return principal.divide(BigDecimal.valueOf(termMonths), 2, RoundingMode.HALF_UP);
        }
        double r = annualRatePercent.doubleValue() / 100.0 / 12.0;
        double n = termMonths;
        double p = principal.doubleValue();
        double payment = p * r * Math.pow(1 + r, n) / (Math.pow(1 + r, n) - 1);
        return BigDecimal.valueOf(payment).setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal computeTotalInterest(BigDecimal principal,
                                             BigDecimal annualRatePercent,
                                             int termMonths) {
        BigDecimal monthly = computeMonthlyPayment(principal, annualRatePercent, termMonths);
        BigDecimal totalPaid = monthly.multiply(BigDecimal.valueOf(termMonths));
        return totalPaid.subtract(principal).setScale(2, RoundingMode.HALF_UP);
    }
}
