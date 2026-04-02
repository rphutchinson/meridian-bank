package com.meridianbank.dao;

import com.meridianbank.annotation.AutomationLevel;
import com.meridianbank.annotation.DependencyDomain;
import com.meridianbank.annotation.MigrationComplexity;
import com.meridianbank.annotation.OracleDependency;
import com.meridianbank.dto.TransactionDTO;
import com.meridianbank.exception.OracleErrorTranslator;
import com.meridianbank.oracle.OracleArrayHelper;
import com.meridianbank.oracle.OracleConnectionUnwrapper;
import com.meridianbank.oracle.OracleStructHelper;
import oracle.jdbc.OracleCallableStatement;
import oracle.jdbc.OracleConnection;
import oracle.jdbc.OraclePreparedStatement;
import oracle.jdbc.OracleResultSet;
import oracle.jdbc.OracleTypes;
import oracle.sql.ARRAY;
import oracle.sql.STRUCT;
import oracle.sql.TIMESTAMP;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.*;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Raw JDBC DAO for Transaction operations using Oracle-specific APIs.
 *
 * Demonstrates: TRANSACTION_RECORD_TYPE STRUCT, TIMESTAMP oracle.sql type,
 * OracleResultSet for typed access, RETURNING INTO, date arithmetic in SQL,
 * MERGE for upsert, INDEX/PARALLEL hints, OracleTypes.CURSOR.
 *
 * ORACLE-DEP [PLSQL_INVOCATION:PACKAGE_CALL]         TRANSACTION_PKG calls
 * ORACLE-DEP [ORACLE_JAVA_API:ORACLE_TIMESTAMP]       oracle.sql.TIMESTAMP binding
 * ORACLE-DEP [ORACLE_JAVA_API:STRUCT_TYPE]            TRANSACTION_RECORD_TYPE STRUCT
 * ORACLE-DEP [EMBEDDED_SQL:DATE_ARITHMETIC]           transaction_date arithmetic
 * ORACLE-DEP [EMBEDDED_SQL:RETURNING_INTO]            INSERT RETURNING transaction_id
 */
@Repository
// ORACLE-DEP [PLSQL_INVOCATION:PACKAGE_CALL] Transaction DAO with Oracle STRUCT, TIMESTAMP and hints
@OracleDependency(
        domain = DependencyDomain.PLSQL_INVOCATION,
        category = "PACKAGE_CALL",
        description = "Transaction DAO using TRANSACTION_PKG, oracle.sql.TIMESTAMP, STRUCT bindings",
        complexity = MigrationComplexity.HIGH,
        automationLevel = AutomationLevel.PARTIAL,
        migrationTarget = "Replace Oracle package calls with standard JDBC, PostgreSQL types"
)
public class TransactionOracleDAO {

    private static final Logger log = LoggerFactory.getLogger(TransactionOracleDAO.class);

    @Autowired
    private DataSource dataSource;

    @Autowired
    private OracleConnectionUnwrapper connectionUnwrapper;

    @Autowired
    private OracleArrayHelper arrayHelper;

    @Autowired
    private OracleStructHelper structHelper;

    @Autowired
    private OracleErrorTranslator errorTranslator;

    // -----------------------------------------------------------------------
    // Record transaction with RETURNING INTO
    // -----------------------------------------------------------------------

    /**
     * Records a transaction using INSERT ... RETURNING transaction_id INTO.
     * Uses oracle.sql.TIMESTAMP for precise timestamp binding.
     *
     * ORACLE-DEP [EMBEDDED_SQL:RETURNING_INTO]      INSERT RETURNING transaction_id
     * ORACLE-DEP [ORACLE_JAVA_API:ORACLE_TIMESTAMP] oracle.sql.TIMESTAMP binding
     */
    @OracleDependency(
            domain = DependencyDomain.EMBEDDED_SQL,
            category = "RETURNING_INTO",
            description = "INSERT transaction with RETURNING transaction_id INTO OUT param",
            complexity = MigrationComplexity.MEDIUM,
            automationLevel = AutomationLevel.PARTIAL,
            migrationTarget = "Replace with INSERT ... RETURNING (PostgreSQL) + getGeneratedKeys()"
    )
    public Long recordTransaction(TransactionDTO dto) throws SQLException {
        // ORACLE-DEP [EMBEDDED_SQL:SEQUENCE] transaction_seq.NEXTVAL for ID
        // ORACLE-DEP [EMBEDDED_SQL:RETURNING_INTO] RETURNING transaction_id INTO OUT param
        // ORACLE-DEP [EMBEDDED_SQL:SYSTIMESTAMP] SYSTIMESTAMP for transaction_date default
        String sql = "INSERT INTO MERIDIAN.transactions " +
                "(transaction_id, account_id, amount, transaction_type, description, " +
                " transaction_date, status, reference_number) " +
                "VALUES (MERIDIAN.transaction_seq.NEXTVAL, ?, ?, ?, ?, ?, 'PENDING', " +
                "  MERIDIAN.transaction_ref_seq.NEXTVAL) " +
                "RETURNING transaction_id INTO ?";
        try (Connection conn = dataSource.getConnection()) {
            OracleConnection oraConn = connectionUnwrapper.unwrap(conn);
            try (OraclePreparedStatement ops =
                         (OraclePreparedStatement) conn.prepareStatement(sql)) {
                ops.setLong(1, dto.getAccountId());
                ops.setBigDecimal(2, dto.getAmount());
                ops.setString(3, dto.getTransactionType());
                ops.setString(4, dto.getDescription());
                // ORACLE-DEP [ORACLE_JAVA_API:ORACLE_TIMESTAMP] oracle.sql.TIMESTAMP for nanosecond precision
                if (dto.getTransactionDate() != null) {
                    Timestamp ts = Timestamp.from(dto.getTransactionDate().toInstant());
                    TIMESTAMP oraTs = new TIMESTAMP(ts);
                    ops.setTIMESTAMP(5, oraTs);
                } else {
                    ops.setNull(5, OracleTypes.TIMESTAMP);
                }
                // ORACLE-DEP [ORACLE_JAVA_API:ORACLE_PREPARED_STMT] registerReturnParameter for RETURNING
                ops.registerReturnParameter(6, OracleTypes.NUMBER);
                ops.executeUpdate();
                try (ResultSet rs = ops.getReturnResultSet()) {
                    if (rs != null && rs.next()) {
                        Long id = rs.getLong(1);
                        log.info("Recorded transaction id={}", id);
                        return id;
                    }
                }
            }
        } catch (SQLException e) {
            throw errorTranslator.translateSQLException(e);
        }
        throw new SQLException("INSERT returned no transaction ID");
    }

    // -----------------------------------------------------------------------
    // Bulk record via TRANSACTION_RECORD_TYPE STRUCT array
    // -----------------------------------------------------------------------

    /**
     * Bulk records transactions by passing a TRANSACTION_TABLE_TYPE array
     * to TRANSACTION_PKG.BULK_RECORD_TRANSACTIONS.
     *
     * ORACLE-DEP [PLSQL_INVOCATION:PACKAGE_CALL] TRANSACTION_PKG.BULK_RECORD_TRANSACTIONS
     * ORACLE-DEP [ORACLE_JAVA_API:STRUCT_TYPE]    TRANSACTION_RECORD_TYPE STRUCT binding
     */
    @OracleDependency(
            domain = DependencyDomain.PLSQL_INVOCATION,
            category = "PACKAGE_CALL",
            description = "Bulk transaction recording via TRANSACTION_PKG.BULK_RECORD_TRANSACTIONS",
            complexity = MigrationComplexity.HIGH,
            automationLevel = AutomationLevel.PARTIAL,
            migrationTarget = "Replace with batch PreparedStatement or PostgreSQL bulk INSERT"
    )
    public int bulkRecordTransactions(List<TransactionDTO> transactions) throws SQLException {
        // ORACLE-DEP [PLSQL_INVOCATION:PACKAGE_CALL] BULK_RECORD_TRANSACTIONS takes TABLE OF STRUCT
        String call = "{call MERIDIAN.TRANSACTION_PKG.BULK_RECORD_TRANSACTIONS(?, ?)}";
        try (Connection conn = dataSource.getConnection()) {
            // ORACLE-DEP [ORACLE_JAVA_API:ORACLE_CONNECTION] unwrap for STRUCT/ARRAY creation
            OracleConnection oraConn = connectionUnwrapper.unwrap(conn);
            // Build TRANSACTION_RECORD_TYPE STRUCTs for each transaction
            // ORACLE-DEP [ORACLE_JAVA_API:STRUCT_TYPE] TRANSACTION_RECORD_TYPE STRUCT per transaction
            STRUCT[] structs = new STRUCT[transactions.size()];
            for (int i = 0; i < transactions.size(); i++) {
                structs[i] = structHelper.transactionDTOtoStruct(oraConn, transactions.get(i));
            }
            try (OracleCallableStatement ocs =
                         (OracleCallableStatement) conn.prepareCall(call)) {
                // ORACLE-DEP [ORACLE_JAVA_API:ORACLE_PREPARED_STMT] setObject with STRUCT array
                ocs.setObject(1, structs);
                // ORACLE-DEP [ORACLE_JAVA_API:ORACLE_TYPES] OracleTypes.NUMBER for count OUT param
                ocs.registerOutParameter(2, OracleTypes.NUMBER);
                ocs.execute();
                int count = ocs.getInt(2);
                log.info("Bulk recorded {} transactions", count);
                return count;
            }
        } catch (SQLException e) {
            throw errorTranslator.translateSQLException(e);
        }
    }

    // -----------------------------------------------------------------------
    // Date-range query with Oracle date arithmetic
    // -----------------------------------------------------------------------

    /**
     * Retrieves recent transactions using Oracle date arithmetic (SYSDATE - n days).
     *
     * ORACLE-DEP [EMBEDDED_SQL:DATE_ARITHMETIC] SYSDATE - integer day arithmetic
     */
    @OracleDependency(
            domain = DependencyDomain.EMBEDDED_SQL,
            category = "DATE_ARITHMETIC",
            description = "Recent transaction query using SYSDATE - n Oracle date arithmetic",
            complexity = MigrationComplexity.LOW,
            automationLevel = AutomationLevel.FULL,
            migrationTarget = "Replace SYSDATE - n with NOW() - INTERVAL 'n days' in PostgreSQL"
    )
    public List<TransactionDTO> findRecentTransactions(Long accountId, int daysBack) throws SQLException {
        // ORACLE-DEP [EMBEDDED_SQL:DATE_ARITHMETIC] SYSDATE - n for recent-transaction filter
        // ORACLE-DEP [EMBEDDED_SQL:QUERY_HINT] INDEX hint for transaction lookup
        // ORACLE-DEP [EMBEDDED_SQL:NVL_FUNCTION] NVL for null description
        String sql = "SELECT /*+ INDEX(t MERIDIAN.transactions_account_date_idx) */ " +
                "t.transaction_id, t.account_id, t.amount, t.transaction_type, " +
                "NVL(t.description, 'N/A') AS description, " +
                "t.transaction_date, t.status, t.reference_number " +
                "FROM MERIDIAN.transactions t " +
                "WHERE t.account_id = ? " +
                "AND t.transaction_date >= SYSDATE - ? " +
                "ORDER BY t.transaction_date DESC";
        List<TransactionDTO> results = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, accountId);
            ps.setInt(2, daysBack);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    results.add(mapRowToDTO(rs));
                }
            }
        }
        return results;
    }

    // -----------------------------------------------------------------------
    // Transaction summary via REF CURSOR
    // -----------------------------------------------------------------------

    /**
     * Retrieves a transaction summary for a date range via REF CURSOR from
     * TRANSACTION_PKG.GET_SUMMARY.
     *
     * ORACLE-DEP [PLSQL_INVOCATION:REF_CURSOR] OracleTypes.CURSOR OUT param
     */
    @OracleDependency(
            domain = DependencyDomain.PLSQL_INVOCATION,
            category = "REF_CURSOR",
            description = "Transaction summary via TRANSACTION_PKG.GET_SUMMARY REF CURSOR",
            complexity = MigrationComplexity.HIGH,
            automationLevel = AutomationLevel.PARTIAL,
            migrationTarget = "Replace REF CURSOR with standard aggregation query"
    )
    public List<TransactionDTO> getSummaryForDateRange(Long accountId,
                                                        java.time.LocalDate startDate,
                                                        java.time.LocalDate endDate) throws SQLException {
        // ORACLE-DEP [PLSQL_INVOCATION:PACKAGE_CALL] TRANSACTION_PKG.GET_SUMMARY
        String call = "{call MERIDIAN.TRANSACTION_PKG.GET_SUMMARY(?, ?, ?, ?)}";
        List<TransactionDTO> results = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             OracleCallableStatement ocs =
                     (OracleCallableStatement) conn.prepareCall(call)) {
            ocs.setLong(1, accountId);
            ocs.setDate(2, Date.valueOf(startDate));
            ocs.setDate(3, Date.valueOf(endDate));
            // ORACLE-DEP [ORACLE_JAVA_API:ORACLE_TYPES] OracleTypes.CURSOR for SYS_REFCURSOR
            ocs.registerOutParameter(4, OracleTypes.CURSOR);
            ocs.execute();
            // ORACLE-DEP [ORACLE_JAVA_API:ORACLE_CALLABLE_STMT] getCursor() Oracle-specific
            try (ResultSet rs = ocs.getCursor(4)) {
                while (rs != null && rs.next()) {
                    results.add(mapRowToDTO(rs));
                }
            }
        } catch (SQLException e) {
            throw errorTranslator.translateSQLException(e);
        }
        return results;
    }

    // -----------------------------------------------------------------------
    // Transaction MERGE for idempotent recording
    // -----------------------------------------------------------------------

    /**
     * Idempotently records a transaction using Oracle MERGE on reference_number.
     *
     * ORACLE-DEP [EMBEDDED_SQL:MERGE_STATEMENT] MERGE for idempotent transaction recording
     */
    @OracleDependency(
            domain = DependencyDomain.EMBEDDED_SQL,
            category = "MERGE_STATEMENT",
            description = "Idempotent transaction recording via MERGE on reference_number",
            complexity = MigrationComplexity.MEDIUM,
            automationLevel = AutomationLevel.PARTIAL,
            migrationTarget = "Replace with INSERT ... ON CONFLICT (reference_number) DO UPDATE in PostgreSQL"
    )
    public void upsertByReference(TransactionDTO dto) throws SQLException {
        // ORACLE-DEP [EMBEDDED_SQL:MERGE_STATEMENT] MERGE INTO transactions USING DUAL
        // ORACLE-DEP [EMBEDDED_SQL:DUAL_TABLE] DUAL as single-row source
        // ORACLE-DEP [EMBEDDED_SQL:SYSTIMESTAMP] SYSTIMESTAMP for updated_date
        String sql = "MERGE INTO MERIDIAN.transactions tgt " +
                "USING (SELECT ? AS ref_num FROM DUAL) src " +
                "ON (tgt.reference_number = src.ref_num) " +
                "WHEN MATCHED THEN " +
                "  UPDATE SET tgt.amount = ?, tgt.status = ?, tgt.updated_date = SYSTIMESTAMP " +
                "WHEN NOT MATCHED THEN " +
                "  INSERT (transaction_id, account_id, amount, transaction_type, " +
                "          description, transaction_date, status, reference_number) " +
                "  VALUES (MERIDIAN.transaction_seq.NEXTVAL, ?, ?, ?, ?, ?, ?, src.ref_num)";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, String.valueOf(dto.getTransactionId()));
            ps.setBigDecimal(2, dto.getAmount());
            ps.setString(3, dto.getStatus());
            ps.setLong(4, dto.getAccountId());
            ps.setBigDecimal(5, dto.getAmount());
            ps.setString(6, dto.getTransactionType());
            ps.setString(7, dto.getDescription());
            ps.setTimestamp(8, dto.getTransactionDate() != null ?
                    Timestamp.from(dto.getTransactionDate().toInstant()) : null);
            ps.setString(9, dto.getStatus());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw errorTranslator.translateSQLException(e);
        }
    }

    // -----------------------------------------------------------------------
    // Private helpers
    // -----------------------------------------------------------------------

    private TransactionDTO mapRowToDTO(ResultSet rs) throws SQLException {
        TransactionDTO dto = new TransactionDTO();
        try { dto.setTransactionId(rs.getLong("transaction_id")); } catch (SQLException ignored) {}
        try { dto.setAccountId(rs.getLong("account_id")); } catch (SQLException ignored) {}
        try { dto.setAmount(rs.getBigDecimal("amount")); } catch (SQLException ignored) {}
        try { dto.setTransactionType(rs.getString("transaction_type")); } catch (SQLException ignored) {}
        try { dto.setDescription(rs.getString("description")); } catch (SQLException ignored) {}
        try { dto.setStatus(rs.getString("status")); } catch (SQLException ignored) {}
        try {
            Timestamp ts = rs.getTimestamp("transaction_date");
            if (ts != null) {
                dto.setTransactionDate(ts.toInstant().atOffset(ZoneOffset.UTC));
            }
        } catch (SQLException ignored) {}
        return dto;
    }
}
