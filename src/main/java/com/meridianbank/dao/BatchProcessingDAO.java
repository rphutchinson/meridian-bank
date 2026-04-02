package com.meridianbank.dao;

import com.meridianbank.annotation.*;
import com.meridianbank.dto.BatchResultDTO;
import com.meridianbank.dto.TransactionDTO;
import com.meridianbank.oracle.OracleConnectionUnwrapper;
import oracle.jdbc.OracleCallableStatement;
import oracle.jdbc.OraclePreparedStatement;
import oracle.jdbc.OracleTypes;
import oracle.sql.ARRAY;
import oracle.sql.ArrayDescriptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.*;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * BatchProcessingDAO — raw JDBC Oracle data access for batch and bulk operations.
 *
 * This class intentionally exercises Oracle-proprietary JDBC extensions:
 *   - OraclePreparedStatement for deferred batch execution
 *   - Oracle ARRAY types (NUMBER_TABLE_TYPE) for bulk updates
 *   - MERGE INTO ... USING for upsert
 *   - ROWID-based updates for batch efficiency
 *   - PL/SQL anonymous blocks executed via JDBC
 *   - DBMS_SCHEDULER-backed package calls
 *
 * Every Oracle dependency is annotated with @OracleDependency and marked with
 * an inline // ORACLE-DEP comment for grep-based discovery.
 */
@Repository
public class BatchProcessingDAO {

    private static final Logger log = LoggerFactory.getLogger(BatchProcessingDAO.class);

    @Autowired
    private DataSource dataSource;

    @Autowired
    private OracleConnectionUnwrapper connectionUnwrapper;

    // ---------------------------------------------------------------------------
    // METHOD 1 — processBatchTransactions
    // ---------------------------------------------------------------------------

    /**
     * Inserts a list of transactions using Oracle's deferred batch execution
     * (OraclePreparedStatement.setExecuteBatch / sendBatch) rather than standard
     * JDBC addBatch/executeBatch.  The Oracle API sends rows to the server in
     * a single round-trip when the batch size is reached.
     */
    @OracleDependency(
        domain = DependencyDomain.ORACLE_JAVA_API,
        category = "ORACLE_PREPARED_STMT",
        description = "OraclePreparedStatement.setExecuteBatch() and sendBatch() for deferred "
                    + "batch execution — Oracle-proprietary API that buffers rows client-side "
                    + "and flushes in a single round-trip",
        complexity = MigrationComplexity.HIGH,
        automationLevel = AutomationLevel.PARTIAL,
        migrationTarget = "Replace with standard JDBC addBatch()/executeBatch() or "
                        + "Spring JdbcTemplate.batchUpdate()"
    )
    @OracleDependency(
        domain = DependencyDomain.EMBEDDED_SQL,
        category = "SEQUENCE",
        description = "TRANSACTION_SEQ.NEXTVAL used inline in INSERT for primary key generation",
        complexity = MigrationComplexity.LOW,
        automationLevel = AutomationLevel.FULL,
        migrationTarget = "Replace with nextval('transaction_seq') in PostgreSQL or IDENTITY column"
    )
    @OracleDependency(
        domain = DependencyDomain.EMBEDDED_SQL,
        category = "SYSDATE",
        description = "SYSDATE used for transaction_date default in INSERT statement",
        complexity = MigrationComplexity.LOW,
        automationLevel = AutomationLevel.FULL,
        migrationTarget = "Replace SYSDATE with NOW() or CURRENT_TIMESTAMP in PostgreSQL"
    )
    public BatchResultDTO processBatchTransactions(List<TransactionDTO> transactions) {
        // ORACLE-DEP [EMBEDDED_SQL:SEQUENCE] TRANSACTION_SEQ.NEXTVAL for PK
        // ORACLE-DEP [EMBEDDED_SQL:SYSDATE] SYSDATE for transaction timestamp
        // ORACLE-DEP [EMBEDDED_SQL:TO_CHAR_STRING] TO_CHAR for reference number
        final String sql =
            "INSERT INTO MERIDIAN.TRANSACTIONS "
            + "(transaction_id, account_id, amount, transaction_type, description, "
            + " transaction_date, status, reference_number) "
            + "VALUES (MERIDIAN.TRANSACTION_SEQ.NEXTVAL, ?, ?, ?, ?, SYSDATE, 'PENDING', "
            + "        TO_CHAR(MERIDIAN.TRANSACTION_SEQ.CURRVAL))";

        int processedCount = 0;
        int errorCount = 0;
        long startTime = System.currentTimeMillis();

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            // ORACLE-DEP [ORACLE_JAVA_API:ORACLE_PREPARED_STMT] Cast to OraclePreparedStatement
            // to access setExecuteBatch() — a proprietary deferred-execution API.
            OraclePreparedStatement ops = (OraclePreparedStatement) ps; // ORACLE-DEP [ORACLE_JAVA_API:ORACLE_PREPARED_STMT]
            ops.setExecuteBatch(100); // ORACLE-DEP [ORACLE_JAVA_API:ORACLE_PREPARED_STMT] batch size 100

            for (TransactionDTO tx : transactions) {
                try {
                    ps.setLong(1, tx.getAccountId());
                    ps.setBigDecimal(2, tx.getAmount());
                    ps.setString(3, tx.getTransactionType());
                    ps.setString(4, tx.getDescription());
                    ps.executeUpdate(); // ORACLE-DEP [ORACLE_JAVA_API:ORACLE_PREPARED_STMT] buffered — not sent yet
                    processedCount++;
                } catch (SQLException rowEx) {
                    // ORACLE-DEP [EMBEDDED_SQL:ORA_ERROR_CODES] check for ORA-01400 / ORA-00001
                    log.warn("Row error ORA-{} for account {}: {}",
                             rowEx.getErrorCode(), tx.getAccountId(), rowEx.getMessage()); // ORACLE-DEP [EMBEDDED_SQL:ORA_ERROR_CODES]
                    errorCount++;
                }
            }

            // ORACLE-DEP [ORACLE_JAVA_API:ORACLE_PREPARED_STMT] sendBatch() flushes remaining buffered rows
            ops.sendBatch(); // ORACLE-DEP [ORACLE_JAVA_API:ORACLE_PREPARED_STMT]

            long elapsedMs = System.currentTimeMillis() - startTime;
            log.info("Batch insert complete: {} processed, {} errors, {}ms", processedCount, errorCount, elapsedMs);

            BatchResultDTO result = new BatchResultDTO();
            result.setRecordsProcessed((long) processedCount);
            result.setRecordsFailed((long) errorCount);
            result.setDurationMs(elapsedMs);
            result.setStatus(errorCount == 0 ? "COMPLETED" : "PARTIAL");
            return result;

        } catch (SQLException e) {
            // ORACLE-DEP [EMBEDDED_SQL:ORA_ERROR_CODES] ORA-12899 value too large, ORA-02291 FK violation, etc.
            log.error("Batch transaction insert failed ORA-{}: {}", e.getErrorCode(), e.getMessage()); // ORACLE-DEP [EMBEDDED_SQL:ORA_ERROR_CODES]
            BatchResultDTO result = new BatchResultDTO();
            result.setRecordsProcessed((long) processedCount);
            result.setRecordsFailed((long) (errorCount + (transactions.size() - processedCount - errorCount)));
            result.setStatus("FAILED");
            result.setErrorMessage("ORA-" + e.getErrorCode() + ": " + e.getMessage());
            return result;
        }
    }

    // ---------------------------------------------------------------------------
    // METHOD 2 — bulkUpdateAccountStatuses
    // ---------------------------------------------------------------------------

    /**
     * Passes a list of account IDs to a PL/SQL package as an Oracle NUMBER_TABLE_TYPE
     * ARRAY so the database can process all rows in a single call rather than
     * individual updates.
     */
    @OracleDependency(
        domain = DependencyDomain.ORACLE_JAVA_API,
        category = "ARRAY_TYPE",
        description = "oracle.sql.ARRAY / ArrayDescriptor used to pass a Java List<Long> as "
                    + "Oracle NUMBER_TABLE_TYPE to a PL/SQL package parameter",
        complexity = MigrationComplexity.HIGH,
        automationLevel = AutomationLevel.PARTIAL,
        migrationTarget = "Replace with java.sql.Array via conn.createArrayOf('bigint', ids) in "
                        + "PostgreSQL, or rewrite as batch UPDATE with unnest()"
    )
    @OracleDependency(
        domain = DependencyDomain.PLSQL_INVOCATION,
        category = "PACKAGE_CALL",
        description = "BATCH_PKG.BULK_UPDATE_STATUS accepts Oracle NUMBER_TABLE_TYPE ARRAY and "
                    + "a VARCHAR2 status code, returns count of updated rows as NUMBER OUT param",
        complexity = MigrationComplexity.HIGH,
        automationLevel = AutomationLevel.PARTIAL,
        migrationTarget = "Refactor to a standard UPDATE ... WHERE account_id = ANY(?) statement "
                        + "using PostgreSQL array binding, or individual updates via JDBC batch"
    )
    public int bulkUpdateAccountStatuses(List<Long> accountIds, String newStatus) {
        final String call = "{call MERIDIAN.BATCH_PKG.BULK_UPDATE_STATUS(?,?,?)}";

        try (Connection conn = dataSource.getConnection()) {

            // ORACLE-DEP [ORACLE_JAVA_API:ORACLE_CONNECTION] unwrap to OracleConnection for ARRAY/STRUCT creation
            oracle.jdbc.OracleConnection oracleConn = connectionUnwrapper.unwrap(conn); // ORACLE-DEP [ORACLE_JAVA_API:ORACLE_CONNECTION]

            // ORACLE-DEP [ORACLE_JAVA_API:ARRAY_TYPE] ArrayDescriptor.createDescriptor resolves the named Oracle type
            ArrayDescriptor desc = ArrayDescriptor.createDescriptor("MERIDIAN.NUMBER_TABLE_TYPE", oracleConn); // ORACLE-DEP [ORACLE_JAVA_API:ARRAY_TYPE]

            // ORACLE-DEP [ORACLE_JAVA_API:ARRAY_TYPE] Wrap Java array in Oracle ARRAY wrapper
            ARRAY oracleArray = new ARRAY(desc, oracleConn, accountIds.toArray(new Long[0])); // ORACLE-DEP [ORACLE_JAVA_API:ARRAY_TYPE]

            try (CallableStatement cs = conn.prepareCall(call)) {
                cs.setObject(1, oracleArray); // IN: NUMBER_TABLE_TYPE array
                cs.setString(2, newStatus);    // IN: new status value
                // ORACLE-DEP [PLSQL_INVOCATION:ORACLE_TYPES] OracleTypes.NUMBER for OUT parameter registration
                cs.registerOutParameter(3, OracleTypes.NUMBER); // ORACLE-DEP [PLSQL_INVOCATION:ORACLE_TYPES]
                cs.execute();
                int updatedCount = cs.getInt(3);
                log.info("bulkUpdateAccountStatuses: updated {} accounts to status '{}'", updatedCount, newStatus);
                return updatedCount;
            }

        } catch (SQLException e) {
            // ORACLE-DEP [EMBEDDED_SQL:ORA_ERROR_CODES] e.g. ORA-00942 table does not exist, ORA-04043 object not found
            log.error("bulkUpdateAccountStatuses failed ORA-{}: {}", e.getErrorCode(), e.getMessage()); // ORACLE-DEP [EMBEDDED_SQL:ORA_ERROR_CODES]
            throw new RuntimeException("Bulk status update failed: ORA-" + e.getErrorCode(), e);
        }
    }

    // ---------------------------------------------------------------------------
    // METHOD 3 — mergeTransactionSummaries
    // ---------------------------------------------------------------------------

    /**
     * Upserts daily transaction summaries using Oracle's MERGE INTO ... USING statement.
     * MERGE is an Oracle extension (adopted in SQL:2003 but with differing syntax).
     * The PostgreSQL equivalent is INSERT ... ON CONFLICT DO UPDATE.
     */
    @OracleDependency(
        domain = DependencyDomain.EMBEDDED_SQL,
        category = "MERGE_STATEMENT",
        description = "Oracle MERGE INTO ... USING ... WHEN MATCHED / WHEN NOT MATCHED pattern "
                    + "for upsert of daily transaction summaries — no direct ANSI SQL equivalent",
        complexity = MigrationComplexity.MEDIUM,
        automationLevel = AutomationLevel.PARTIAL,
        migrationTarget = "Replace with INSERT INTO transaction_summary(...) ... ON CONFLICT "
                        + "(account_id, summary_date) DO UPDATE SET daily_total = EXCLUDED.daily_total, "
                        + "tx_count = EXCLUDED.tx_count, updated_date = NOW() in PostgreSQL"
    )
    @OracleDependency(
        domain = DependencyDomain.EMBEDDED_SQL,
        category = "TRUNC_DATE",
        description = "TRUNC(transaction_date) and TRUNC(?) normalize timestamps to midnight for date-level grouping",
        complexity = MigrationComplexity.LOW,
        automationLevel = AutomationLevel.FULL,
        migrationTarget = "Replace TRUNC(date_col) with DATE_TRUNC('day', date_col) in PostgreSQL"
    )
    public int mergeTransactionSummaries(LocalDate summaryDate) {
        // ORACLE-DEP [EMBEDDED_SQL:MERGE_STATEMENT] Oracle MERGE INTO ... USING ... WHEN MATCHED/NOT MATCHED
        // ORACLE-DEP [EMBEDDED_SQL:TRUNC_DATE] TRUNC(transaction_date) and TRUNC(?) for date normalization
        // ORACLE-DEP [EMBEDDED_SQL:SYSDATE] SYSDATE for audit timestamps
        // ORACLE-DEP [EMBEDDED_SQL:SEQUENCE] SUMMARY_SEQ.NEXTVAL for primary key
        final String sql =
            "MERGE INTO MERIDIAN.TRANSACTION_SUMMARY tgt "
            + "USING ( "
            + "  SELECT account_id, "
            + "         SUM(amount)  AS daily_total, "
            + "         COUNT(*)     AS tx_count "
            + "  FROM   MERIDIAN.TRANSACTIONS "
            + "  WHERE  TRUNC(transaction_date) = TRUNC(?) "   // ORACLE-DEP [EMBEDDED_SQL:TRUNC_DATE]
            + "  GROUP  BY account_id "
            + ") src "
            + "ON (tgt.account_id = src.account_id AND tgt.summary_date = TRUNC(?)) " // ORACLE-DEP [EMBEDDED_SQL:TRUNC_DATE]
            + "WHEN MATCHED THEN "
            + "  UPDATE SET tgt.daily_total   = src.daily_total, "
            + "             tgt.tx_count      = src.tx_count, "
            + "             tgt.updated_date  = SYSDATE "       // ORACLE-DEP [EMBEDDED_SQL:SYSDATE]
            + "WHEN NOT MATCHED THEN "
            + "  INSERT (summary_id, account_id, summary_date, daily_total, tx_count, created_date) "
            + "  VALUES (MERIDIAN.SUMMARY_SEQ.NEXTVAL, src.account_id, TRUNC(?), " // ORACLE-DEP [EMBEDDED_SQL:SEQUENCE]
            + "          src.daily_total, src.tx_count, SYSDATE)";                  // ORACLE-DEP [EMBEDDED_SQL:SYSDATE]

        java.sql.Date sqlDate = java.sql.Date.valueOf(summaryDate);

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setDate(1, sqlDate); // TRUNC(?) — source filter
            ps.setDate(2, sqlDate); // TRUNC(?) — ON condition date
            ps.setDate(3, sqlDate); // TRUNC(?) — INSERT value

            int rowsAffected = ps.executeUpdate();
            log.info("mergeTransactionSummaries: {} rows merged for date {}", rowsAffected, summaryDate);
            return rowsAffected;

        } catch (SQLException e) {
            // ORACLE-DEP [EMBEDDED_SQL:ORA_ERROR_CODES] ORA-00001 unique constraint on (account_id, summary_date)
            log.error("mergeTransactionSummaries failed ORA-{}: {}", e.getErrorCode(), e.getMessage()); // ORACLE-DEP [EMBEDDED_SQL:ORA_ERROR_CODES]
            throw new RuntimeException("MERGE transaction summaries failed: ORA-" + e.getErrorCode(), e);
        }
    }

    // ---------------------------------------------------------------------------
    // METHOD 4 — updateByRowId
    // ---------------------------------------------------------------------------

    /**
     * Updates a single account balance by ROWID — the physical row address that
     * Oracle stores alongside every row.  Batch processes first fetch ROWIDs via
     * fetchRowIdsForUpdate(), then call this method for each ROWID to avoid
     * re-evaluating an index predicate on every update.
     */
    @OracleDependency(
        domain = DependencyDomain.EMBEDDED_SQL,
        category = "ROWID",
        description = "ROWID-based UPDATE bypasses PK index lookup — ROWID is the physical "
                    + "row address within the Oracle datafile, faster for sequential batch updates",
        complexity = MigrationComplexity.MEDIUM,
        automationLevel = AutomationLevel.PARTIAL,
        migrationTarget = "Replace ROWID with CTID in PostgreSQL (different semantics — CTID is "
                        + "not stable across VACUUM FULL; prefer PK-based updates in PostgreSQL)"
    )
    @OracleDependency(
        domain = DependencyDomain.EMBEDDED_SQL,
        category = "SYSDATE",
        description = "SYSDATE used for updated_date audit column in batch UPDATE",
        complexity = MigrationComplexity.LOW,
        automationLevel = AutomationLevel.FULL,
        migrationTarget = "Replace SYSDATE with NOW() or CURRENT_TIMESTAMP in PostgreSQL"
    )
    public int updateByRowId(String rowId, BigDecimal newBalance) {
        // ORACLE-DEP [EMBEDDED_SQL:ROWID] ROWID-based UPDATE — faster than PK lookup for batch operations
        // ORACLE-DEP [EMBEDDED_SQL:SYSDATE] SYSDATE for updated_date audit column
        final String sql =
            "UPDATE MERIDIAN.ACCOUNTS "
            + "SET    balance      = ?, "
            + "       updated_date = SYSDATE "   // ORACLE-DEP [EMBEDDED_SQL:SYSDATE]
            + "WHERE  ROWID        = ?";          // ORACLE-DEP [EMBEDDED_SQL:ROWID]

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setBigDecimal(1, newBalance);
            ps.setString(2, rowId); // bind ROWID as string (ROWIDTOCHAR output)

            int updated = ps.executeUpdate();
            if (updated == 0) {
                log.warn("updateByRowId: no row found for ROWID {}", rowId);
            }
            return updated;

        } catch (SQLException e) {
            // ORACLE-DEP [EMBEDDED_SQL:ORA_ERROR_CODES] ORA-01410 invalid ROWID if stale
            log.error("updateByRowId failed ORA-{} for ROWID {}: {}", e.getErrorCode(), rowId, e.getMessage()); // ORACLE-DEP [EMBEDDED_SQL:ORA_ERROR_CODES]
            throw new RuntimeException("ROWID update failed: ORA-" + e.getErrorCode(), e);
        }
    }

    // ---------------------------------------------------------------------------
    // METHOD 5 — fetchRowIdsForUpdate
    // ---------------------------------------------------------------------------

    /**
     * Retrieves ROWIDs for accounts in a given status so subsequent batch updates
     * can use updateByRowId().  ROWIDTOCHAR() converts the internal ROWID type
     * to a character string for storage in a Java String.
     */
    @OracleDependency(
        domain = DependencyDomain.EMBEDDED_SQL,
        category = "ROWID",
        description = "ROWIDTOCHAR(ROWID) retrieves each row's physical address as a VARCHAR2 "
                    + "for use in subsequent targeted batch updates via updateByRowId()",
        complexity = MigrationComplexity.MEDIUM,
        automationLevel = AutomationLevel.PARTIAL,
        migrationTarget = "In PostgreSQL fetch the PK (account_id) instead; use CTID only if "
                        + "absolutely necessary and accept instability across VACUUM operations"
    )
    public List<String> fetchRowIdsForUpdate(String status) {
        // ORACLE-DEP [EMBEDDED_SQL:ROWID] ROWIDTOCHAR() converts ROWID to char for Java String storage
        // ORACLE-DEP [EMBEDDED_SQL:ROWID] ROWID pseudocolumn retrieval for subsequent targeted updates
        final String sql =
            "SELECT ROWIDTOCHAR(ROWID) AS row_id, " // ORACLE-DEP [EMBEDDED_SQL:ROWID]
            + "       account_id, "
            + "       balance "
            + "FROM   MERIDIAN.ACCOUNTS "
            + "WHERE  status  = ? "
            + "AND    balance < 0";

        List<String> rowIds = new ArrayList<>();

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setString(1, status);

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String rowId = rs.getString("row_id");
                    long accountId = rs.getLong("account_id");
                    BigDecimal balance = rs.getBigDecimal("balance");
                    log.debug("Fetched ROWID {} for account {} (balance {})", rowId, accountId, balance);
                    rowIds.add(rowId);
                }
            }

            log.info("fetchRowIdsForUpdate: found {} rows with status='{}' and balance < 0", rowIds.size(), status);
            return rowIds;

        } catch (SQLException e) {
            // ORACLE-DEP [EMBEDDED_SQL:ORA_ERROR_CODES] ORA-00942 table/view does not exist
            log.error("fetchRowIdsForUpdate failed ORA-{}: {}", e.getErrorCode(), e.getMessage()); // ORACLE-DEP [EMBEDDED_SQL:ORA_ERROR_CODES]
            throw new RuntimeException("fetchRowIdsForUpdate failed: ORA-" + e.getErrorCode(), e);
        }
    }

    // ---------------------------------------------------------------------------
    // METHOD 6 — runParallelBatchQuery
    // ---------------------------------------------------------------------------

    /**
     * Runs a summary aggregation query with Oracle parallel-execution hints so
     * the database can spread the work across multiple CPU/IO processes.
     * The PARALLEL(t,4) hint requests degree-4 parallelism; FULL(t) forces a
     * full table scan which is required for Oracle to select a parallel plan.
     */
    @OracleDependency(
        domain = DependencyDomain.EMBEDDED_SQL,
        category = "PARALLEL_HINT",
        description = "/*+ PARALLEL(t,4) */ hint enables Oracle parallel query execution "
                    + "with degree 4 — the database can use up to 4 parallel query slaves",
        complexity = MigrationComplexity.MEDIUM,
        automationLevel = AutomationLevel.PARTIAL,
        migrationTarget = "Remove Oracle hint; PostgreSQL uses max_parallel_workers_per_gather "
                        + "config or pg_hint_plan extension for equivalent control"
    )
    @OracleDependency(
        domain = DependencyDomain.EMBEDDED_SQL,
        category = "FULL_HINT",
        description = "/*+ FULL(t) */ forces a full table scan — prerequisite for Oracle to "
                    + "select a parallel query plan on the TRANSACTIONS table",
        complexity = MigrationComplexity.MEDIUM,
        automationLevel = AutomationLevel.PARTIAL,
        migrationTarget = "Remove Oracle hint; PostgreSQL's planner will choose a seq scan "
                        + "automatically when appropriate for large parallel operations"
    )
    public List<Map<String, Object>> runParallelBatchQuery(LocalDate fromDate) {
        // ORACLE-DEP [EMBEDDED_SQL:PARALLEL_HINT] /*+ PARALLEL(t,4) */ enables parallel query execution
        // ORACLE-DEP [EMBEDDED_SQL:FULL_HINT] /*+ FULL(t) */ forces full table scan for parallel query
        final String sql =
            "SELECT /*+ PARALLEL(t,4) FULL(t) */ " // ORACLE-DEP [EMBEDDED_SQL:PARALLEL_HINT] ORACLE-DEP [EMBEDDED_SQL:FULL_HINT]
            + "  account_id, "
            + "  SUM(amount)    AS total_amount, "
            + "  COUNT(*)       AS tx_count "
            + "FROM  MERIDIAN.TRANSACTIONS t "
            + "WHERE transaction_date >= ? "
            + "GROUP BY account_id "
            + "ORDER BY total_amount DESC";

        List<Map<String, Object>> results = new ArrayList<>();

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setDate(1, java.sql.Date.valueOf(fromDate));

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> row = new HashMap<>();
                    row.put("accountId", rs.getLong("account_id"));
                    row.put("totalAmount", rs.getBigDecimal("total_amount"));
                    row.put("txCount", rs.getInt("tx_count"));
                    results.add(row);
                }
            }

            log.info("runParallelBatchQuery: {} accounts aggregated from {}", results.size(), fromDate);
            return results;

        } catch (SQLException e) {
            // ORACLE-DEP [EMBEDDED_SQL:ORA_ERROR_CODES] ORA-12801 parallel query error
            log.error("runParallelBatchQuery failed ORA-{}: {}", e.getErrorCode(), e.getMessage()); // ORACLE-DEP [EMBEDDED_SQL:ORA_ERROR_CODES]
            throw new RuntimeException("Parallel batch query failed: ORA-" + e.getErrorCode(), e);
        }
    }

    // ---------------------------------------------------------------------------
    // METHOD 7 — executeEndOfDayProcedure
    // ---------------------------------------------------------------------------

    /**
     * Executes an anonymous PL/SQL block via JDBC CallableStatement.
     * Anonymous blocks (DECLARE...BEGIN...END) are Oracle-specific — there is no
     * standard SQL equivalent.  Named parameters (:p_date, :p_result) are bound
     * using the Oracle JDBC driver's named-parameter support.
     */
    @OracleDependency(
        domain = DependencyDomain.EMBEDDED_SQL,
        category = "PLSQL_ANONYMOUS_BLOCK",
        description = "PL/SQL DECLARE...BEGIN...END anonymous block executes "
                    + "BATCH_PKG.END_OF_DAY_PROCESSING via JDBC prepareCall — "
                    + "anonymous blocks are Oracle-specific constructs",
        complexity = MigrationComplexity.HIGH,
        automationLevel = AutomationLevel.PARTIAL,
        migrationTarget = "Refactor to a direct stored procedure call {call batch_pkg.end_of_day(?)} "
                        + "or move business logic entirely to the application service layer"
    )
    @OracleDependency(
        domain = DependencyDomain.PLSQL_INVOCATION,
        category = "PACKAGE_CALL",
        description = "BATCH_PKG.END_OF_DAY_PROCESSING called inside the anonymous block; "
                    + "result passed back via :p_result OUT bind variable",
        complexity = MigrationComplexity.HIGH,
        automationLevel = AutomationLevel.PARTIAL,
        migrationTarget = "Replace with PostgreSQL function call or application-tier business logic"
    )
    public int executeEndOfDayProcedure(LocalDate businessDate) {
        // ORACLE-DEP [EMBEDDED_SQL:PLSQL_ANONYMOUS_BLOCK] PL/SQL DECLARE...BEGIN...END block executed via JDBC
        // ORACLE-DEP [PLSQL_INVOCATION:PACKAGE_CALL] BATCH_PKG.END_OF_DAY_PROCESSING inside anonymous block
        final String block =
            "DECLARE\n"
            + "  v_result NUMBER := 0;\n"
            + "BEGIN\n"
            + "  MERIDIAN.BATCH_PKG.END_OF_DAY_PROCESSING(:p_date, v_result);\n"
            + "  :p_result := v_result;\n"
            + "END;";

        try (Connection conn = dataSource.getConnection();
             CallableStatement cs = conn.prepareCall(block)) {

            cs.setDate("p_date", java.sql.Date.valueOf(businessDate)); // named bind variable
            // ORACLE-DEP [PLSQL_INVOCATION:ORACLE_TYPES] OracleTypes.NUMBER for OUT parameter :p_result
            cs.registerOutParameter("p_result", OracleTypes.NUMBER); // ORACLE-DEP [PLSQL_INVOCATION:ORACLE_TYPES]

            cs.execute();

            int result = cs.getInt("p_result");
            log.info("executeEndOfDayProcedure: processed {} records for date {}", result, businessDate);
            return result;

        } catch (SQLException e) {
            // ORACLE-DEP [EMBEDDED_SQL:ORA_ERROR_CODES] ORA-04088 error during trigger execution,
            //   ORA-20001+ user-defined exceptions from the PL/SQL block
            log.error("executeEndOfDayProcedure failed ORA-{}: {}", e.getErrorCode(), e.getMessage()); // ORACLE-DEP [EMBEDDED_SQL:ORA_ERROR_CODES]
            throw new RuntimeException("End-of-day procedure failed: ORA-" + e.getErrorCode(), e);
        }
    }

    // ---------------------------------------------------------------------------
    // METHOD 8 — calculateInterestBatch
    // ---------------------------------------------------------------------------

    /**
     * Calls the BATCH_PKG.CALCULATE_INTEREST stored procedure to compute and post
     * daily interest for all eligible accounts.  The procedure is defined inside an
     * Oracle PL/SQL package and returns the number of accounts processed as a
     * NUMBER OUT parameter.
     */
    @OracleDependency(
        domain = DependencyDomain.PLSQL_INVOCATION,
        category = "PACKAGE_CALL",
        description = "BATCH_PKG.CALCULATE_INTEREST calculates daily interest for all accounts; "
                    + "IN: business_date DATE, OUT: accounts_processed NUMBER",
        complexity = MigrationComplexity.HIGH,
        automationLevel = AutomationLevel.PARTIAL,
        migrationTarget = "Port interest calculation logic to application service layer or replace "
                        + "with equivalent PostgreSQL function if logic is purely SQL-based"
    )
    @OracleDependency(
        domain = DependencyDomain.PLSQL_INVOCATION,
        category = "ORACLE_TYPES",
        description = "OracleTypes.NUMBER used to register the OUT parameter type for the "
                    + "accounts-processed return value",
        complexity = MigrationComplexity.LOW,
        automationLevel = AutomationLevel.FULL,
        migrationTarget = "Replace OracleTypes.NUMBER with java.sql.Types.NUMERIC"
    )
    public int calculateInterestBatch(LocalDate businessDate) {
        // ORACLE-DEP [PLSQL_INVOCATION:PACKAGE_CALL] BATCH_PKG.CALCULATE_INTEREST calculates daily interest
        final String call = "{call MERIDIAN.BATCH_PKG.CALCULATE_INTEREST(?,?)}";

        try (Connection conn = dataSource.getConnection();
             CallableStatement cs = conn.prepareCall(call)) {

            cs.setDate(1, java.sql.Date.valueOf(businessDate)); // IN: business date
            // ORACLE-DEP [PLSQL_INVOCATION:ORACLE_TYPES] OracleTypes.NUMBER for OUT accounts-processed count
            cs.registerOutParameter(2, OracleTypes.NUMBER); // ORACLE-DEP [PLSQL_INVOCATION:ORACLE_TYPES]

            cs.execute();

            int accountsProcessed = cs.getInt(2);
            log.info("calculateInterestBatch: interest calculated for {} accounts on date {}",
                     accountsProcessed, businessDate);
            return accountsProcessed;

        } catch (SQLException e) {
            // ORACLE-DEP [EMBEDDED_SQL:ORA_ERROR_CODES] ORA-20100+ user-defined exceptions from CALCULATE_INTEREST
            log.error("calculateInterestBatch failed ORA-{} for date {}: {}",
                      e.getErrorCode(), businessDate, e.getMessage()); // ORACLE-DEP [EMBEDDED_SQL:ORA_ERROR_CODES]
            throw new RuntimeException("Interest batch calculation failed: ORA-" + e.getErrorCode(), e);
        }
    }
}
