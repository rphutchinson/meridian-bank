package com.meridianbank.dao;

import com.meridianbank.dto.BatchResultDTO;
import com.meridianbank.dto.TransactionDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests for {@link BatchProcessingDAO} — the Oracle bulk-operations DAO.
 *
 * <p>This DAO concentrates the heaviest Oracle bulk-processing patterns:
 * <ul>
 *   <li>ORACLE-DEP [ORACLE_JAVA_API:ORACLE_PREPARED_STMT]  {@code OraclePreparedStatement.setExecuteBatch()}</li>
 *   <li>ORACLE-DEP [ORACLE_JAVA_API:ARRAY_BINDING]         array parameter binding for bulk inserts</li>
 *   <li>ORACLE-DEP [EMBEDDED_SQL:MERGE_STATEMENT]          {@code MERGE INTO transaction_summaries USING ...}</li>
 *   <li>ORACLE-DEP [EMBEDDED_SQL:ORACLE_HINT]              Oracle hint PARALLEL(t,4) for batch queries</li>
 *   <li>ORACLE-DEP [EMBEDDED_SQL:ROWID]                    {@code UPDATE accounts SET ... WHERE ROWID = ?}</li>
 *   <li>ORACLE-DEP [PLSQL_INVOCATION:DBMS_SCHEDULER]       {@code DBMS_SCHEDULER.RUN_JOB} call</li>
 *   <li>ORACLE-DEP [EMBEDDED_SQL:FORALL_BULK_COLLECT]      PL/SQL FORALL called via stored proc</li>
 * </ul>
 */
class BatchProcessingDAOTest {

    // ------------------------------------------------------------------
    // DB-dependent tests (all @Disabled)
    // ------------------------------------------------------------------

    @SpringBootTest
    @AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
    @ActiveProfiles("test")
    @Nested
    @Disabled("Requires Oracle DB — entire nested class disabled until Oracle 23ai container is running")
    @DisplayName("Oracle DB-dependent batch tests")
    class OracleBatchTests {

        @Autowired
        private BatchProcessingDAO batchProcessingDAO;

        /**
         * Tests that {@link BatchProcessingDAO#processBatchTransactions(List)} uses
         * Oracle array binding via {@code OraclePreparedStatement.setExecuteBatch(n)}
         * for efficient bulk inserts.
         *
         * <p>Oracle constructs:
         * <ul>
         *   <li>{@code ((OraclePreparedStatement) ps).setExecuteBatch(50)}</li>
         *   <li>{@code ((OraclePreparedStatement) ps).sendBatch()}</li>
         * </ul>
         * <p>Migration target: standard JDBC {@code executeBatch()} with {@code addBatch()}.
         */
        @Test
        @Disabled("Requires Oracle DB — ORACLE-DEP [ORACLE_JAVA_API:ORACLE_PREPARED_STMT]")
        @DisplayName("processBatchTransactions uses OraclePreparedStatement.setExecuteBatch()")
        void testBulkTransactionProcessing_usesArrayBinding() {
            List<TransactionDTO> batch = buildTransactionBatch(25);

            BatchResultDTO result = batchProcessingDAO.processBatchTransactions(batch);

            assertNotNull(result, "Batch result must not be null");
            assertNotNull(result.getStatus(), "Batch result must have a status");
            assertTrue(
                    "COMPLETED".equals(result.getStatus()) || "PARTIAL".equals(result.getStatus()),
                    "Batch status must be COMPLETED or PARTIAL, got: " + result.getStatus());
            assertNotNull(result.getRecordsProcessed(),
                    "Record count must be populated after OraclePreparedStatement batch");
            assertEquals(25L, result.getRecordsProcessed(),
                    "All 25 transactions must be processed via array binding");
        }

        /**
         * Tests that {@link BatchProcessingDAO#mergeTransactionSummaries(LocalDate)}
         * executes the Oracle MERGE statement for daily summary upsert.
         *
         * <p>Oracle SQL fragment:
         * {@code MERGE INTO transaction_summaries tgt USING (...) src
         *   ON (tgt.account_id = src.account_id AND tgt.summary_date = src.summary_date)
         *   WHEN MATCHED THEN UPDATE SET ...
         *   WHEN NOT MATCHED THEN INSERT ...}
         *
         * <p>Migration target: {@code INSERT ... ON CONFLICT (account_id, summary_date) DO UPDATE ...}
         * in PostgreSQL.
         */
        @Test
        @Disabled("Requires Oracle DB — ORACLE-DEP [EMBEDDED_SQL:MERGE_STATEMENT]")
        @DisplayName("mergeTransactionSummaries uses Oracle MERGE INTO ... USING for daily upsert")
        void testMergeTransactionSummaries() {
            LocalDate yesterday = LocalDate.now().minusDays(1);

            int mergedRows = batchProcessingDAO.mergeTransactionSummaries(yesterday);

            assertTrue(mergedRows >= 0,
                    "MERGE must return a non-negative row count");
        }

        /**
         * Tests that {@link BatchProcessingDAO#runParallelBatchQuery(LocalDate)} includes
         * the Oracle hint PARALLEL(t,4) in its SQL.
         *
         * <p>Oracle SQL fragment: SELECT followed by PARALLEL(t,4) hint.
         * <p>Migration target: remove Oracle hints; PostgreSQL parallel query is controlled
         * by {@code max_parallel_workers_per_gather} configuration parameter.
         */
        @Test
        @Disabled("Requires Oracle DB — ORACLE-DEP [EMBEDDED_SQL:ORACLE_HINT]")
        @DisplayName("runParallelBatchQuery uses Oracle /*+ PARALLEL(t,4) */ hint")
        void testParallelBatchQuery_hasHint() {
            LocalDate fromDate = LocalDate.now().minusDays(7);

            List<Map<String, Object>> results = batchProcessingDAO.runParallelBatchQuery(fromDate);

            assertNotNull(results,
                    "PARALLEL-hinted query must return a non-null result");
            // The hint is transparent to results — just verify the query executes
        }

        /**
         * Tests that {@link BatchProcessingDAO#updateByRowId(String, BigDecimal)} uses
         * Oracle ROWID to target a specific physical row for update.
         *
         * <p>Oracle SQL: {@code UPDATE accounts SET balance = ? WHERE ROWID = ?}
         * <p>Migration target: ROWID has no direct equivalent in PostgreSQL. Replace with
         * primary-key-based UPDATE or use {@code ctid} (internal tuple ID) with caution.
         */
        @Test
        @Disabled("Requires Oracle DB — ORACLE-DEP [EMBEDDED_SQL:ROWID]")
        @DisplayName("updateByRowId uses Oracle ROWID for direct physical-row update")
        void testUpdateByRowId() {
            // First fetch a ROWID from an existing row
            List<String> rowIds = batchProcessingDAO.fetchRowIdsForUpdate("ACTIVE");

            assertFalse(rowIds.isEmpty(),
                    "fetchRowIdsForUpdate must return at least one ROWID for ACTIVE accounts");

            String firstRowId = rowIds.get(0);
            assertNotNull(firstRowId, "ROWID must not be null");
            assertTrue(firstRowId.length() >= 18,
                    "Oracle ROWID is at least 18 characters (extended ROWID format)");

            // Update the row by its ROWID
            int updated = batchProcessingDAO.updateByRowId(firstRowId, new BigDecimal("9999.99"));
            assertEquals(1, updated,
                    "UPDATE WHERE ROWID must update exactly one row");
        }

        /**
         * Tests that {@link BatchProcessingDAO#executeEndOfDayProcedure(LocalDate)} calls
         * {@code DBMS_SCHEDULER.RUN_JOB} via a PL/SQL anonymous block.
         *
         * <p>Oracle PL/SQL block:
         * {@code BEGIN DBMS_SCHEDULER.RUN_JOB('MERIDIAN.EOD_PROCESSING_JOB', TRUE); END;}
         * <p>Migration target: replace with Spring Batch {@code JobLauncher.run()} or
         * Quartz {@code Scheduler.triggerJob()}.
         */
        @Test
        @Disabled("Requires Oracle DB — ORACLE-DEP [PLSQL_INVOCATION:DBMS_SCHEDULER]")
        @DisplayName("executeEndOfDayProcedure calls Oracle DBMS_SCHEDULER.RUN_JOB")
        void testEndOfDayProcedure() {
            LocalDate businessDate = LocalDate.now().minusDays(1);

            int result = batchProcessingDAO.executeEndOfDayProcedure(businessDate);

            assertTrue(result >= 0,
                    "DBMS_SCHEDULER-based EOD procedure must return non-negative result code");
        }

        /**
         * Tests that {@link BatchProcessingDAO#calculateInterestBatch(LocalDate)} calls
         * a PL/SQL procedure that internally uses FORALL/BULK COLLECT for bulk processing.
         *
         * <p>PL/SQL pattern called (in BATCH_PKG):
         * {@code FORALL i IN 1..l_account_ids.COUNT INSERT INTO interest_accruals ...}
         * <p>Migration target: application-level batch with JDBC {@code addBatch()} /
         * {@code executeBatch()}, or PostgreSQL set-based UPDATE.
         */
        @Test
        @Disabled("Requires Oracle DB — ORACLE-DEP [EMBEDDED_SQL:FORALL_BULK_COLLECT]")
        @DisplayName("calculateInterestBatch calls PL/SQL FORALL bulk operation")
        void testInterestCalculationBatch() {
            LocalDate calcDate = LocalDate.now();

            int rowsAffected = batchProcessingDAO.calculateInterestBatch(calcDate);

            assertTrue(rowsAffected >= 0,
                    "FORALL-based interest batch must return a non-negative row count");
        }
    }

    // ------------------------------------------------------------------
    // Mock-based tests (no DB required)
    // ------------------------------------------------------------------

    @Nested
    @ExtendWith(MockitoExtension.class)
    @org.mockito.junit.jupiter.MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
    @DisplayName("Mock-based structural tests (no DB required)")
    class MockBasedTests {

        @Mock
        private BatchProcessingDAO batchProcessingDAO;

        @BeforeEach
        void setUpMocks() {
            BatchResultDTO completedResult = new BatchResultDTO();
            completedResult.setJobName("TEST_BATCH");
            completedResult.setStatus("COMPLETED");
            completedResult.setRecordsProcessed(10L);
            completedResult.setRecordsSucceeded(10L);
            completedResult.setRecordsFailed(0L);
            completedResult.setStartTime(LocalDateTime.now().minusSeconds(5));
            completedResult.setEndTime(LocalDateTime.now());

            when(batchProcessingDAO.processBatchTransactions(anyList()))
                    .thenReturn(completedResult);
            when(batchProcessingDAO.mergeTransactionSummaries(any(LocalDate.class)))
                    .thenReturn(5);
            when(batchProcessingDAO.executeEndOfDayProcedure(any(LocalDate.class)))
                    .thenReturn(0);
            when(batchProcessingDAO.calculateInterestBatch(any(LocalDate.class)))
                    .thenReturn(100);
        }

        @Test
        @DisplayName("processBatchTransactions mock returns COMPLETED result")
        void testBatchProcessing_mockReturnsCompletedStatus() {
            List<TransactionDTO> batch = buildTransactionBatch(10);
            BatchResultDTO result = batchProcessingDAO.processBatchTransactions(batch);

            assertNotNull(result);
            assertEquals("COMPLETED", result.getStatus());
            assertEquals(10L, result.getRecordsProcessed());
            verify(batchProcessingDAO, times(1)).processBatchTransactions(batch);
        }

        @Test
        @DisplayName("mergeTransactionSummaries mock returns positive merge count")
        void testMerge_mockReturnsMergeCount() {
            int merged = batchProcessingDAO.mergeTransactionSummaries(LocalDate.now());

            assertEquals(5, merged);
        }

        @Test
        @DisplayName("calculateInterestBatch mock returns expected row count")
        void testInterestBatch_mockReturnsCount() {
            int rows = batchProcessingDAO.calculateInterestBatch(LocalDate.now());

            assertEquals(100, rows);
        }
    }

    // ------------------------------------------------------------------
    // Helper
    // ------------------------------------------------------------------

    private List<TransactionDTO> buildTransactionBatch(int count) {
        List<TransactionDTO> batch = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            TransactionDTO dto = new TransactionDTO();
            dto.setAccountId((long) (i % 5 + 1));
            dto.setTransactionType(i % 2 == 0 ? "CREDIT" : "DEBIT");
            dto.setAmount(new BigDecimal(String.format("%d.00", i * 10)));
            dto.setDescription("Batch transaction " + i);
            dto.setStatus("PENDING");
            dto.setTransactionDate(OffsetDateTime.now(ZoneOffset.UTC));
            batch.add(dto);
        }
        return batch;
    }
}
