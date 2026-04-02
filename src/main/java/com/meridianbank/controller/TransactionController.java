package com.meridianbank.controller;

import com.meridianbank.dto.TransactionDTO;
import com.meridianbank.service.TransactionService;
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
 * REST controller for Transaction operations.
 *
 * <p>Thin HTTP adapter over TransactionService. All Oracle-specific logic —
 * Oracle TIMESTAMP WITH TIME ZONE, sequence-based reference numbers,
 * date arithmetic (SYSDATE - N), MERGE for upsert, BULK COLLECT/FORALL
 * via PL/SQL package calls, analytic window functions — lives in
 * TransactionOracleDAO and TransactionService.
 *
 * <p>Date range parameters from query strings are parsed as ISO-8601
 * LocalDate (yyyy-MM-dd) before being passed to the service layer.
 */
@RestController
@RequestMapping("/api/transactions")
@CrossOrigin(origins = "*")
public class TransactionController {

    private static final Logger log = LoggerFactory.getLogger(TransactionController.class);

    private final TransactionService transactionService;

    @Autowired
    public TransactionController(TransactionService transactionService) {
        this.transactionService = transactionService;
    }

    // -------------------------------------------------------------------------
    // POST /api/transactions
    // Records a new transaction (debit or credit).
    // TransactionService.recordTransaction() uses Oracle sequence for reference
    // number generation and Oracle TIMESTAMP WITH TIME ZONE for timestamps.
    // Returns HTTP 201 Created.
    // -------------------------------------------------------------------------
    @PostMapping
    public ResponseEntity<TransactionDTO> recordTransaction(
            @RequestBody TransactionDTO dto) {

        log.debug("POST /api/transactions accountId={} type={} amount={}",
                dto.getAccountId(), dto.getTransactionType(), dto.getAmount());
        TransactionDTO created = transactionService.recordTransaction(dto);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    // -------------------------------------------------------------------------
    // GET /api/transactions/{id}
    // Returns a single transaction by primary key.
    // -------------------------------------------------------------------------
    @GetMapping("/{id}")
    public ResponseEntity<TransactionDTO> getTransactionById(@PathVariable Long id) {
        log.debug("GET /api/transactions/{}", id);
        TransactionDTO transaction = transactionService.getTransactionById(id);
        return ResponseEntity.ok(transaction);
    }

    // -------------------------------------------------------------------------
    // GET /api/transactions/by-reference/{reference}
    // Returns a transaction by its Oracle sequence-generated reference number.
    // -------------------------------------------------------------------------
    @GetMapping("/by-reference/{reference}")
    public ResponseEntity<TransactionDTO> getTransactionByReference(
            @PathVariable String reference) {

        log.debug("GET /api/transactions/by-reference/{}", reference);
        TransactionDTO transaction = transactionService.getTransactionByReference(reference);
        return ResponseEntity.ok(transaction);
    }

    // -------------------------------------------------------------------------
    // GET /api/transactions/search?accountId=&start=yyyy-MM-dd&end=yyyy-MM-dd
    // Searches transactions for an account within a date range.
    // TransactionService.searchTransactions() uses Oracle TRUNC(transaction_date)
    // for day-boundary comparisons and date arithmetic in the WHERE clause.
    // Dates are parsed from ISO-8601 strings at the controller boundary.
    // -------------------------------------------------------------------------
    @GetMapping("/search")
    public ResponseEntity<List<TransactionDTO>> searchTransactions(
            @RequestParam Long accountId,
            @RequestParam(required = false) String start,
            @RequestParam(required = false) String end) {

        LocalDate startDate = (start != null) ? LocalDate.parse(start) : LocalDate.now().minusDays(90);
        LocalDate endDate   = (end   != null) ? LocalDate.parse(end)   : LocalDate.now();

        log.debug("GET /api/transactions/search accountId={} start={} end={}", accountId, startDate, endDate);
        List<TransactionDTO> results = transactionService.searchTransactions(accountId, startDate, endDate);
        return ResponseEntity.ok(results);
    }

    // -------------------------------------------------------------------------
    // GET /api/transactions/recent?accountId=&daysBack=30
    // Returns transactions within the last N days for an account.
    // Internally translates to: WHERE transaction_date >= SYSDATE - :daysBack
    // -------------------------------------------------------------------------
    @GetMapping("/recent")
    public ResponseEntity<List<TransactionDTO>> getRecentTransactions(
            @RequestParam Long accountId,
            @RequestParam(defaultValue = "30") int daysBack) {

        log.debug("GET /api/transactions/recent accountId={} daysBack={}", accountId, daysBack);
        List<TransactionDTO> transactions = transactionService.getRecentTransactions(accountId, daysBack);
        return ResponseEntity.ok(transactions);
    }

    // -------------------------------------------------------------------------
    // GET /api/transactions/daily-summary?accountId=&start=&end=
    // Returns a day-by-day credit/debit summary.
    // TransactionService.getDailySummary() uses Oracle TRUNC(date) in GROUP BY.
    // -------------------------------------------------------------------------
    @GetMapping("/daily-summary")
    public ResponseEntity<List<Object[]>> getDailySummary(
            @RequestParam Long accountId,
            @RequestParam(required = false) String start,
            @RequestParam(required = false) String end) {

        LocalDate startDate = (start != null) ? LocalDate.parse(start) : LocalDate.now().minusDays(30);
        LocalDate endDate   = (end   != null) ? LocalDate.parse(end)   : LocalDate.now();

        log.debug("GET /api/transactions/daily-summary accountId={}", accountId);
        List<Object[]> summary = transactionService.getDailySummary(accountId, startDate, endDate);
        return ResponseEntity.ok(summary);
    }

    // -------------------------------------------------------------------------
    // GET /api/transactions/high-volume-debits?threshold=1000
    // Returns accounts with total debits above a threshold.
    // Uses Oracle analytic SUM() OVER() window function.
    // -------------------------------------------------------------------------
    @GetMapping("/high-volume-debits")
    public ResponseEntity<List<Object[]>> getHighVolumeDebitAccounts(
            @RequestParam(defaultValue = "1000") BigDecimal threshold) {

        log.debug("GET /api/transactions/high-volume-debits threshold={}", threshold);
        List<Object[]> accounts = transactionService.getHighVolumeDebitAccounts(threshold);
        return ResponseEntity.ok(accounts);
    }

    // -------------------------------------------------------------------------
    // GET /api/transactions/overdue-settlements
    // Returns transactions past their settlement deadline.
    // TransactionService.getOverdueSettlements() uses Oracle date subtraction
    // (transaction_date - SYSDATE returning a NUMBER of days).
    // -------------------------------------------------------------------------
    @GetMapping("/overdue-settlements")
    public ResponseEntity<List<Object[]>> getOverdueSettlements() {
        log.debug("GET /api/transactions/overdue-settlements");
        List<Object[]> overdue = transactionService.getOverdueSettlements();
        return ResponseEntity.ok(overdue);
    }

    // -------------------------------------------------------------------------
    // GET /api/transactions/{id}/running-balance
    // Returns recent completed transactions with a running balance.
    // Uses Oracle analytic SUM() OVER (ORDER BY transaction_date ROWS UNBOUNDED PRECEDING).
    // -------------------------------------------------------------------------
    @GetMapping("/{id}/running-balance")
    public ResponseEntity<List<Object[]>> getRecentWithRunningBalance(
            @PathVariable Long id) {

        log.debug("GET /api/transactions/{}/running-balance", id);
        List<Object[]> data = transactionService.getRecentCompletedWithRunningBalance(id);
        return ResponseEntity.ok(data);
    }

    // -------------------------------------------------------------------------
    // GET /api/transactions/summary/by-period?accountId=&year=2025
    // Returns a transaction summary grouped by period (quarter, month).
    // Uses Oracle TO_CHAR(date, 'YYYY-MM') for period grouping.
    // -------------------------------------------------------------------------
    @GetMapping("/summary/by-period")
    public ResponseEntity<List<Object[]>> getTransactionSummaryByPeriod(
            @RequestParam Long accountId,
            @RequestParam(defaultValue = "2025") int year) {

        log.debug("GET /api/transactions/summary/by-period accountId={} year={}", accountId, year);
        List<Object[]> summary = transactionService.getTransactionSummaryByPeriod(accountId, year);
        return ResponseEntity.ok(summary);
    }

    // -------------------------------------------------------------------------
    // GET /api/transactions/stats?accountId=
    // Returns aggregate statistics for a controller-level quick summary.
    // -------------------------------------------------------------------------
    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getTransactionStats(
            @RequestParam Long accountId) {

        log.debug("GET /api/transactions/stats accountId={}", accountId);
        List<TransactionDTO> last30 = transactionService.getRecentTransactions(accountId, 30);
        Map<String, Object> stats = new HashMap<>();
        stats.put("accountId", accountId);
        stats.put("transactionsLast30Days", last30.size());
        return ResponseEntity.ok(stats);
    }
}
