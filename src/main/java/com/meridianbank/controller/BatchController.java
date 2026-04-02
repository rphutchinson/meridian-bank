package com.meridianbank.controller;

import com.meridianbank.dto.BatchResultDTO;
import com.meridianbank.entity.ScheduledJobEntity;
import com.meridianbank.service.BatchProcessingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
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
 * REST controller for batch processing and scheduled job operations.
 *
 * <p>Thin HTTP adapter over BatchProcessingService. The service layer exercises
 * the heaviest Oracle bulk-operation APIs in the entire application, including:
 *
 * ORACLE-DEP [PLSQL_INVOCATION:PACKAGE_CALL]      BATCH_PKG.RUN_END_OF_DAY via CallableStatement
 * ORACLE-DEP [EMBEDDED_SQL:FORALL_BULK_COLLECT]   FORALL/BULK COLLECT inside PL/SQL packages called from app
 * ORACLE-DEP [EMBEDDED_SQL:MERGE_STATEMENT]       MERGE INTO for transaction summary upserts
 * ORACLE-DEP [EMBEDDED_SQL:ROWID_PSEUDOCOLUMN]    ROWID-based updates in BatchProcessingDAO
 * ORACLE-DEP [EMBEDDED_SQL:PARALLEL_HINT]         Oracle hint PARALLEL(4) in batch report queries
 * ORACLE-DEP [ORACLE_JAVA_API:ORACLE_ARRAY]       oracle.sql.ARRAY for bulk ID arrays
 * ORACLE-DEP [PLSQL_INVOCATION:DBMS_SCHEDULER]    DBMS_SCHEDULER.CREATE_JOB for scheduled jobs
 * ORACLE-DEP [PLSQL_INVOCATION:ANONYMOUS_BLOCK]   PL/SQL anonymous blocks via JDBC in BatchProcessingDAO
 */
@RestController
@RequestMapping("/api/batch")
@CrossOrigin(origins = "*")
public class BatchController {

    private static final Logger log = LoggerFactory.getLogger(BatchController.class);

    private final BatchProcessingService batchProcessingService;

    @Autowired
    public BatchController(BatchProcessingService batchProcessingService) {
        this.batchProcessingService = batchProcessingService;
    }

    // -------------------------------------------------------------------------
    // POST /api/batch/end-of-day
    // Triggers end-of-day batch processing for a business date.
    // Body: { "businessDate": "2026-01-15" }
    // BatchProcessingService.runEndOfDayProcessing() calls BATCH_PKG.RUN_END_OF_DAY
    // which uses FORALL/BULK COLLECT for high-volume transaction processing,
    // MERGE INTO for summary table upserts, and DBMS_SCHEDULER to schedule
    // the next night's run.
    // -------------------------------------------------------------------------
    @PostMapping("/end-of-day")
    public ResponseEntity<BatchResultDTO> runEndOfDay(@RequestBody Map<String, String> body) {
        LocalDate businessDate = LocalDate.parse(body.getOrDefault("businessDate",
                LocalDate.now().toString()));
        log.info("POST /api/batch/end-of-day businessDate={}", businessDate);
        BatchResultDTO result = batchProcessingService.runEndOfDayProcessing(businessDate);
        return ResponseEntity.ok(result);
    }

    // -------------------------------------------------------------------------
    // POST /api/batch/interest-calculation
    // Triggers interest accrual calculation for a business date.
    // Body: { "businessDate": "2026-01-15" }
    // BatchProcessingService.runInterestCalculation() uses Oracle array binding
    // via OraclePreparedStatement.setExecuteBatch() for bulk UPDATE operations,
    // and ADD_MONTHS() for interest period boundary calculations.
    // -------------------------------------------------------------------------
    @PostMapping("/interest-calculation")
    public ResponseEntity<BatchResultDTO> runInterestCalculation(
            @RequestBody Map<String, String> body) {

        LocalDate businessDate = LocalDate.parse(body.getOrDefault("businessDate",
                LocalDate.now().toString()));
        log.info("POST /api/batch/interest-calculation businessDate={}", businessDate);
        BatchResultDTO result = batchProcessingService.runInterestCalculation(businessDate);
        return ResponseEntity.ok(result);
    }

    // -------------------------------------------------------------------------
    // GET /api/batch/jobs
    // Returns the list of DBMS_SCHEDULER jobs registered for this application.
    // BatchProcessingService.getScheduledJobs() queries the USER_SCHEDULER_JOBS
    // data dictionary view and maps results to ScheduledJobEntity.
    // -------------------------------------------------------------------------
    @GetMapping("/jobs")
    public ResponseEntity<List<ScheduledJobEntity>> getScheduledJobs() {
        log.debug("GET /api/batch/jobs");
        List<ScheduledJobEntity> jobs = batchProcessingService.getScheduledJobs();
        return ResponseEntity.ok(jobs);
    }

    // -------------------------------------------------------------------------
    // POST /api/batch/merge-summaries
    // Triggers a MERGE-based transaction summary update for a date.
    // Body: { "date": "2026-01-15" }
    // -------------------------------------------------------------------------
    @PostMapping("/merge-summaries")
    public ResponseEntity<Map<String, Object>> mergeTransactionSummaries(
            @RequestBody Map<String, String> body) {

        LocalDate date = LocalDate.parse(body.getOrDefault("date", LocalDate.now().toString()));
        log.info("POST /api/batch/merge-summaries date={}", date);
        int rowsAffected = batchProcessingService.mergeTransactionSummaries(date);
        Map<String, Object> response = new HashMap<>();
        response.put("date", date.toString());
        response.put("rowsMerged", rowsAffected);
        return ResponseEntity.ok(response);
    }

    // -------------------------------------------------------------------------
    // GET /api/batch/parallel-report?fromDate=yyyy-MM-dd
    // Runs a parallel batch report using {@code /*+ PARALLEL(4) */} hint.
    // -------------------------------------------------------------------------
    @GetMapping("/parallel-report")
    public ResponseEntity<List<Map<String, Object>>> runParallelReport(
            @RequestParam(required = false) String fromDate) {

        LocalDate date = (fromDate != null) ? LocalDate.parse(fromDate) : LocalDate.now().minusDays(30);
        log.debug("GET /api/batch/parallel-report fromDate={}", date);
        List<Map<String, Object>> report = batchProcessingService.runParallelReport(date);
        return ResponseEntity.ok(report);
    }

    // -------------------------------------------------------------------------
    // POST /api/batch/rowid-update
    // Updates a single row by ROWID — demonstrates Oracle ROWID pseudocolumn usage.
    // Body: { "rowId": "AAABvZAAFAAAACDAAA", "balance": 9999.99 }
    // -------------------------------------------------------------------------
    @PostMapping("/rowid-update")
    public ResponseEntity<Map<String, Object>> updateByRowId(
            @RequestBody Map<String, Object> body) {

        String rowId    = (String) body.get("rowId");
        BigDecimal balance = new BigDecimal(body.get("balance").toString());
        log.debug("POST /api/batch/rowid-update rowId={}", rowId);
        int rows = batchProcessingService.updateByRowId(rowId, balance);
        Map<String, Object> response = new HashMap<>();
        response.put("rowId", rowId);
        response.put("rowsUpdated", rows);
        return ResponseEntity.ok(response);
    }
}
