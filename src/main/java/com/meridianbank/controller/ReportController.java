package com.meridianbank.controller;

import com.meridianbank.dto.FinancialReportDTO;
import com.meridianbank.dto.ReportParameterDTO;
import com.meridianbank.service.ReportService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * REST controller for financial reporting and analytics.
 *
 * <p>Every endpoint in this controller exercises a distinct Oracle analytic
 * or aggregation feature. The endpoints exist primarily to ensure the
 * migration framework's scanner can find every category of reporting-related
 * Oracle dependency through a live HTTP surface.
 *
 * <p>Oracle dependencies covered by this controller's service layer:
 *
 * ORACLE-DEP [EMBEDDED_SQL:RATIO_TO_REPORT]   /portfolio-summary
 * ORACLE-DEP [EMBEDDED_SQL:PIVOT_CLAUSE]       /transaction-pivot
 * ORACLE-DEP [EMBEDDED_SQL:NTILE_FUNCTION]     /customer-ranking (rankBy=ntile)
 * ORACLE-DEP [EMBEDDED_SQL:CUME_DIST]          /customer-ranking (rankBy=cume_dist)
 * ORACLE-DEP [EMBEDDED_SQL:WIDTH_BUCKET]       /transaction-distribution
 * ORACLE-DEP [EMBEDDED_SQL:LISTAGG]            /account-summary
 * ORACLE-DEP [EMBEDDED_SQL:OUTER_JOIN_PLUS]    /legacy-accounts
 * ORACLE-DEP [EMBEDDED_SQL:PARALLEL_HINT]      /transaction-distribution, /legacy-accounts
 * ORACLE-DEP [EMBEDDED_SQL:LEADING_HINT]       /leading-hint-report
 * ORACLE-DEP [EMBEDDED_SQL:CONNECT_BY_PRIOR]   /employee-hierarchy (proxied via ReportService)
 */
@RestController
@RequestMapping("/api/reports")
@CrossOrigin(origins = "*")
public class ReportController {

    private static final Logger log = LoggerFactory.getLogger(ReportController.class);

    private final ReportService reportService;

    @Autowired
    public ReportController(ReportService reportService) {
        this.reportService = reportService;
    }

    // -------------------------------------------------------------------------
    // GET /api/reports/portfolio-summary?customerId=
    // Returns portfolio allocation breakdown for a customer.
    // ReportService.getPortfolioAllocation() uses Oracle RATIO_TO_REPORT()
    // analytic function to compute each account's share of total balance.
    // -------------------------------------------------------------------------
    @GetMapping("/portfolio-summary")
    public ResponseEntity<List<FinancialReportDTO>> getPortfolioSummary(
            @RequestParam Long customerId) {

        log.debug("GET /api/reports/portfolio-summary customerId={}", customerId);
        List<FinancialReportDTO> allocation = reportService.getPortfolioAllocation(customerId);
        return ResponseEntity.ok(allocation);
    }

    // -------------------------------------------------------------------------
    // GET /api/reports/transaction-pivot?year=2025
    // Returns a monthly transaction pivot table for a given calendar year.
    // ReportService.getMonthlyTransactionPivot() uses Oracle PIVOT clause:
    //   PIVOT (SUM(amount) FOR month IN (1,2,...,12))
    // which has no direct equivalent in standard SQL — migrates to conditional
    // aggregation or application-layer reshaping.
    // -------------------------------------------------------------------------
    @GetMapping("/transaction-pivot")
    public ResponseEntity<List<Map<String, Object>>> getTransactionPivot(
            @RequestParam(defaultValue = "2025") int year) {

        log.debug("GET /api/reports/transaction-pivot year={}", year);
        List<Map<String, Object>> pivot = reportService.getMonthlyTransactionPivot(year);
        return ResponseEntity.ok(pivot);
    }

    // -------------------------------------------------------------------------
    // GET /api/reports/customer-ranking?rankBy=balance
    // Returns customers ranked by the specified metric.
    // ReportService.getCustomerRanking() uses NTILE(10) or CUME_DIST() over
    // the balance/transaction-count partition depending on rankBy value.
    // Both NTILE and CUME_DIST are Oracle analytic functions available in
    // PostgreSQL but with subtly different NULL handling.
    // -------------------------------------------------------------------------
    @GetMapping("/customer-ranking")
    public ResponseEntity<List<Map<String, Object>>> getCustomerRanking(
            @RequestParam(defaultValue = "balance") String rankBy) {

        log.debug("GET /api/reports/customer-ranking rankBy={}", rankBy);
        List<Map<String, Object>> ranking = reportService.getCustomerRanking(rankBy);
        return ResponseEntity.ok(ranking);
    }

    // -------------------------------------------------------------------------
    // GET /api/reports/transaction-distribution?accountId=
    // Returns a transaction-amount distribution histogram.
    // ReportService.getTransactionDistribution() uses Oracle WIDTH_BUCKET()
    // to assign each transaction to a histogram bucket — the function
    // signature is compatible with PostgreSQL but semantics differ slightly
    // for boundary values.
    // -------------------------------------------------------------------------
    @GetMapping("/transaction-distribution")
    public ResponseEntity<List<Map<String, Object>>> getTransactionDistribution(
            @RequestParam Long accountId) {

        log.debug("GET /api/reports/transaction-distribution accountId={}", accountId);
        List<Map<String, Object>> distribution = reportService.getTransactionDistribution(accountId);
        return ResponseEntity.ok(distribution);
    }

    // -------------------------------------------------------------------------
    // GET /api/reports/account-summary?customerId=
    // Returns a single-row account summary with comma-delimited account numbers.
    // ReportService.getAccountSummaryWithListagg() uses Oracle LISTAGG():
    //   LISTAGG(account_number, ', ') WITHIN GROUP (ORDER BY open_date)
    // which migrates to STRING_AGG() in PostgreSQL.
    // -------------------------------------------------------------------------
    @GetMapping("/account-summary")
    public ResponseEntity<Map<String, Object>> getAccountSummary(
            @RequestParam Long customerId) {

        log.debug("GET /api/reports/account-summary customerId={}", customerId);
        Map<String, Object> summary = reportService.getAccountSummaryWithListagg(customerId);
        return ResponseEntity.ok(summary);
    }

    // -------------------------------------------------------------------------
    // GET /api/reports/legacy-accounts
    // Returns a legacy account report using Oracle (+) outer join syntax.
    // ReportService.getLegacyAccountReport() deliberately uses the Oracle
    // proprietary (+) join operator (4+ usages) to exercise that migration
    // category; migrates to ANSI LEFT/RIGHT/FULL OUTER JOIN.
    // -------------------------------------------------------------------------
    @GetMapping("/legacy-accounts")
    public ResponseEntity<List<Map<String, Object>>> getLegacyAccountReport() {
        log.debug("GET /api/reports/legacy-accounts");
        List<Map<String, Object>> report = reportService.getLegacyAccountReport();
        return ResponseEntity.ok(report);
    }

    // -------------------------------------------------------------------------
    // GET /api/reports/transaction-aging?accountId=
    // Returns transaction aging buckets (current, 30, 60, 90+ days).
    // Uses Oracle DECODE() for aging category assignment.
    // -------------------------------------------------------------------------
    @GetMapping("/transaction-aging")
    public ResponseEntity<List<Map<String, Object>>> getTransactionAging(
            @RequestParam Long accountId) {

        log.debug("GET /api/reports/transaction-aging accountId={}", accountId);
        List<Map<String, Object>> aging = reportService.getTransactionAging(accountId);
        return ResponseEntity.ok(aging);
    }

    // -------------------------------------------------------------------------
    // GET /api/reports/leading-hint?fromDate=yyyy-MM-dd
    // Returns a report that uses /*+ LEADING(t c) */ and /*+ FULL(a) */ hints.
    // Exercises §2.1 Oracle optimizer hints.
    // -------------------------------------------------------------------------
    @GetMapping("/leading-hint")
    public ResponseEntity<List<Map<String, Object>>> getLeadingHintReport(
            @RequestParam(required = false) String fromDate) {

        log.debug("GET /api/reports/leading-hint fromDate={}", fromDate);
        ReportParameterDTO params = new ReportParameterDTO();
        if (fromDate != null) {
            params.setStartDate(LocalDate.parse(fromDate));
        }
        List<Map<String, Object>> result = reportService.getLeadingHintReport(params);
        return ResponseEntity.ok(result);
    }

    // -------------------------------------------------------------------------
    // GET /api/reports/employee-hierarchy?rootId=
    // Returns an org-chart report — delegates to ReportService which uses
    // CONNECT BY PRIOR internally.
    // -------------------------------------------------------------------------
    @GetMapping("/employee-hierarchy")
    public ResponseEntity<List<?>> getEmployeeHierarchyReport(
            @RequestParam(defaultValue = "1") Long rootId) {

        log.debug("GET /api/reports/employee-hierarchy rootId={}", rootId);
        List<?> hierarchy = reportService.getEmployeeOrgHierarchy(rootId);
        return ResponseEntity.ok(hierarchy);
    }

    // -------------------------------------------------------------------------
    // GET /api/reports/recent-audit?days=7
    // Returns recent audit log entries.  ReportService.getRecentAuditActivity()
    // uses Oracle SYSTIMESTAMP - INTERVAL for the lookback window.
    // -------------------------------------------------------------------------
    @GetMapping("/recent-audit")
    public ResponseEntity<?> getRecentAuditActivity(
            @RequestParam(defaultValue = "7") int days) {

        log.debug("GET /api/reports/recent-audit days={}", days);
        return ResponseEntity.ok(reportService.getRecentAuditActivity(days));
    }
}
