package com.meridianbank.controller;

import com.meridianbank.dto.LoanApplicationDTO;
import com.meridianbank.service.LoanService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * REST controller for Loan Application operations.
 *
 * <p>Thin HTTP adapter over LoanService. Oracle-specific operations —
 * LOAN_PKG.UNDERWRITE_APPLICATION stored procedure calls, DBMS_CRYPTO for
 * sensitive data, MONTHS_BETWEEN() for term calculations, ADD_MONTHS() for
 * maturity date computation, Oracle CLOB storage for loan notes,
 * EMPTY_CLOB() initialization — are encapsulated in LoanOracleDAO and LoanService.
 *
 * <p>The /approve endpoint triggers a PL/SQL package call that runs the
 * full underwriting business logic (LOAN_PKG.APPROVE_APPLICATION) and
 * returns updated application state via an OracleTypes.CURSOR OUT parameter.
 */
@RestController
@RequestMapping("/api/loans")
@CrossOrigin(origins = "*")
public class LoanController {

    private static final Logger log = LoggerFactory.getLogger(LoanController.class);

    private final LoanService loanService;

    @Autowired
    public LoanController(LoanService loanService) {
        this.loanService = loanService;
    }

    // -------------------------------------------------------------------------
    // POST /api/loans/apply
    // Submits a new loan application.
    // LoanService.submitLoanApplication() persists the application, initialises
    // the notes CLOB with EMPTY_CLOB(), and enqueues a credit-check notification
    // via Oracle AQ through NotificationService.
    // Returns HTTP 201 Created.
    // -------------------------------------------------------------------------
    @PostMapping("/apply")
    public ResponseEntity<LoanApplicationDTO> submitLoanApplication(
            @RequestBody LoanApplicationDTO dto) {

        log.debug("POST /api/loans/apply customerId={} loanType={} amount={}",
                dto.getCustomerId(), dto.getLoanType(), dto.getRequestedAmount());
        LoanApplicationDTO created = loanService.submitLoanApplication(dto);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    // -------------------------------------------------------------------------
    // GET /api/loans/{id}
    // Returns the full loan application record by primary key.
    // Includes CLOB notes field read via oracle.sql.CLOB in LoanOracleDAO.
    // -------------------------------------------------------------------------
    @GetMapping("/{id}")
    public ResponseEntity<LoanApplicationDTO> getLoanById(@PathVariable Long id) {
        log.debug("GET /api/loans/{}", id);
        LoanApplicationDTO loan = loanService.getLoanById(id);
        return ResponseEntity.ok(loan);
    }

    // -------------------------------------------------------------------------
    // POST /api/loans/{id}/approve
    // Approves a pending loan application.
    // Body: { "approvedAmount": BigDecimal, "interestRate": BigDecimal, "termMonths": int }
    // LoanService.approveLoan() calls LOAN_PKG.APPROVE_APPLICATION which uses
    // Oracle DBMS_CRYPTO for audit hashing, ADD_MONTHS() for maturity date,
    // and RETURNING INTO for updated record retrieval.
    // -------------------------------------------------------------------------
    @PostMapping("/{id}/approve")
    public ResponseEntity<LoanApplicationDTO> approveLoan(
            @PathVariable Long id,
            @RequestBody Map<String, Object> body) {

        BigDecimal approvedAmount = new BigDecimal(body.get("approvedAmount").toString());
        BigDecimal interestRate   = new BigDecimal(body.get("interestRate").toString());
        int termMonths            = Integer.parseInt(body.get("termMonths").toString());

        log.debug("POST /api/loans/{}/approve amount={} rate={} term={}",
                id, approvedAmount, interestRate, termMonths);
        LoanApplicationDTO approved = loanService.approveLoan(id, approvedAmount, interestRate, termMonths);
        return ResponseEntity.ok(approved);
    }

    // -------------------------------------------------------------------------
    // GET /api/loans/customer/{customerId}
    // Returns all loan applications for a customer.
    // -------------------------------------------------------------------------
    @GetMapping("/customer/{customerId}")
    public ResponseEntity<List<LoanApplicationDTO>> getLoansByCustomer(
            @PathVariable Long customerId) {

        log.debug("GET /api/loans/customer/{}", customerId);
        List<LoanApplicationDTO> loans = loanService.getLoansByCustomer(customerId);
        return ResponseEntity.ok(loans);
    }

    // -------------------------------------------------------------------------
    // GET /api/loans/overdue
    // Returns applications that are overdue based on expected decision date.
    // LoanService.getOverdueLoans() uses Oracle date subtraction:
    //   (SYSDATE - application_date) > :expectedDays
    // which returns a NUMBER in Oracle (days as decimal).
    // -------------------------------------------------------------------------
    @GetMapping("/overdue")
    public ResponseEntity<List<LoanApplicationDTO>> getOverdueLoans() {
        log.debug("GET /api/loans/overdue");
        List<LoanApplicationDTO> overdue = loanService.getOverdueLoans();
        return ResponseEntity.ok(overdue);
    }

    // -------------------------------------------------------------------------
    // GET /api/loans/by-status?status=PENDING
    // Returns applications filtered by status.
    // -------------------------------------------------------------------------
    @GetMapping("/by-status")
    public ResponseEntity<List<LoanApplicationDTO>> getLoansByStatus(
            @RequestParam String status) {

        log.debug("GET /api/loans/by-status status={}", status);
        List<LoanApplicationDTO> loans = loanService.getLoansByStatus(status);
        return ResponseEntity.ok(loans);
    }

    // -------------------------------------------------------------------------
    // GET /api/loans/customer/{customerId}/approved-with-maturity
    // Returns approved loans with computed maturity dates.
    // LoanService.getApprovedLoansWithMaturityDates() uses Oracle ADD_MONTHS()
    // to compute maturity from approval_date + term_months.
    // -------------------------------------------------------------------------
    @GetMapping("/customer/{customerId}/approved-with-maturity")
    public ResponseEntity<List<Object[]>> getApprovedLoansWithMaturityDates(
            @PathVariable Long customerId) {

        log.debug("GET /api/loans/customer/{}/approved-with-maturity", customerId);
        List<Object[]> loans = loanService.getApprovedLoansWithMaturityDates(customerId);
        return ResponseEntity.ok(loans);
    }

    // -------------------------------------------------------------------------
    // GET /api/loans/stats/monthly
    // Returns monthly application counts and totals.
    // Uses Oracle TO_CHAR(application_date, 'YYYY-MM') for period grouping.
    // -------------------------------------------------------------------------
    @GetMapping("/stats/monthly")
    public ResponseEntity<List<Object[]>> getMonthlyLoanStats() {
        log.debug("GET /api/loans/stats/monthly");
        List<Object[]> stats = loanService.getMonthlyLoanApplicationStats();
        return ResponseEntity.ok(stats);
    }

    // -------------------------------------------------------------------------
    // GET /api/loans/pending-with-age
    // Returns pending applications with days-since-submission age.
    // LoanService.getPendingApplicationsWithAge() uses (SYSDATE - application_date)
    // which returns a NUMBER in Oracle representing fractional days.
    // -------------------------------------------------------------------------
    @GetMapping("/pending-with-age")
    public ResponseEntity<List<Object[]>> getPendingApplicationsWithAge() {
        log.debug("GET /api/loans/pending-with-age");
        List<Object[]> pending = loanService.getPendingApplicationsWithAge();
        return ResponseEntity.ok(pending);
    }

    // -------------------------------------------------------------------------
    // GET /api/loans/maturity-date?start=yyyy-MM-dd&termMonths=36
    // Utility endpoint: calculates a maturity date using ADD_MONTHS via LoanService.
    // -------------------------------------------------------------------------
    @GetMapping("/maturity-date")
    public ResponseEntity<Map<String, Object>> calculateMaturityDate(
            @RequestParam String start,
            @RequestParam int termMonths) {

        LocalDate startDate    = LocalDate.parse(start);
        LocalDate maturityDate = loanService.calculateMaturityDate(startDate, termMonths);

        Map<String, Object> response = new HashMap<>();
        response.put("startDate", startDate.toString());
        response.put("termMonths", termMonths);
        response.put("maturityDate", maturityDate.toString());
        return ResponseEntity.ok(response);
    }
}
