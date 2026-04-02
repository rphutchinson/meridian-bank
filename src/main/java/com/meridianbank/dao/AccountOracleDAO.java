package com.meridianbank.dao;

import com.meridianbank.annotation.AutomationLevel;
import com.meridianbank.annotation.DependencyDomain;
import com.meridianbank.annotation.MigrationComplexity;
import com.meridianbank.annotation.OracleDependency;
import com.meridianbank.dto.AccountDTO;
import com.meridianbank.dto.CustomerDTO;
import com.meridianbank.exception.OracleErrorTranslator;
import com.meridianbank.oracle.OracleArrayHelper;
import com.meridianbank.oracle.OracleConnectionUnwrapper;
import com.meridianbank.oracle.OracleStructHelper;
import oracle.jdbc.OracleCallableStatement;
import oracle.jdbc.OracleConnection;
import oracle.jdbc.OraclePreparedStatement;
import oracle.jdbc.OracleTypes;
import oracle.sql.ARRAY;
import oracle.sql.STRUCT;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Raw JDBC DAO for Account operations using Oracle-specific APIs.
 *
 * Demonstrates: package calls, OracleTypes.CURSOR, STRUCT for address,
 * ARRAY for bulk account IDs, RETURNING INTO, MERGE, optimizer hints,
 * Oracle error code handling.
 *
 * ORACLE-DEP [PLSQL_INVOCATION:PACKAGE_CALL]         ACCOUNT_MGMT_PKG calls
 * ORACLE-DEP [PLSQL_INVOCATION:REF_CURSOR]            SYS_REFCURSOR OUT param
 * ORACLE-DEP [ORACLE_JAVA_API:ARRAY_TYPE]              NUMBER_TABLE_TYPE array binding
 * ORACLE-DEP [EMBEDDED_SQL:MERGE_STATEMENT]            MERGE INTO for balance upsert
 * ORACLE-DEP [EMBEDDED_SQL:RETURNING_INTO]             INSERT RETURNING account_id
 */
@Repository
// ORACLE-DEP [PLSQL_INVOCATION:PACKAGE_CALL] Account DAO with Oracle package calls and type bindings
@OracleDependency(
        domain = DependencyDomain.PLSQL_INVOCATION,
        category = "PACKAGE_CALL",
        description = "Account DAO using ACCOUNT_MGMT_PKG, OracleTypes, ARRAY, and STRUCT bindings",
        complexity = MigrationComplexity.HIGH,
        automationLevel = AutomationLevel.PARTIAL,
        migrationTarget = "Replace Oracle package calls with standard JDBC and PostgreSQL functions"
)
public class AccountOracleDAO {

    private static final Logger log = LoggerFactory.getLogger(AccountOracleDAO.class);

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
    // Account creation via package call with RETURNING INTO
    // -----------------------------------------------------------------------

    /**
     * Creates an account by calling ACCOUNT_MGMT_PKG.CREATE_ACCOUNT which returns
     * the new account_id via OracleTypes.NUMBER OUT parameter.
     *
     * ORACLE-DEP [PLSQL_INVOCATION:PACKAGE_CALL] ACCOUNT_MGMT_PKG.CREATE_ACCOUNT
     */
    @OracleDependency(
            domain = DependencyDomain.PLSQL_INVOCATION,
            category = "PACKAGE_CALL",
            description = "Creates account via ACCOUNT_MGMT_PKG.CREATE_ACCOUNT with NUMBER OUT param",
            complexity = MigrationComplexity.HIGH,
            automationLevel = AutomationLevel.PARTIAL,
            migrationTarget = "Replace with INSERT statement + getGeneratedKeys() or PostgreSQL function"
    )
    public Long createAccount(AccountDTO dto) throws SQLException {
        // ORACLE-DEP [PLSQL_INVOCATION:PACKAGE_CALL] {call package.procedure()} syntax
        String call = "{call MERIDIAN.ACCOUNT_MGMT_PKG.CREATE_ACCOUNT(?, ?, ?, ?, ?)}";
        try (Connection conn = dataSource.getConnection();
             OracleCallableStatement ocs =
                     (OracleCallableStatement) conn.prepareCall(call)) {
            ocs.setLong(1, dto.getCustomerId());
            ocs.setString(2, dto.getAccountType());
            ocs.setBigDecimal(3, dto.getBalance() != null ? dto.getBalance() : BigDecimal.ZERO);
            // ORACLE-DEP [ORACLE_JAVA_API:ORACLE_TYPES] OracleTypes.NUMBER for numeric OUT parameter
            ocs.registerOutParameter(4, OracleTypes.NUMBER);
            // ORACLE-DEP [ORACLE_JAVA_API:ORACLE_TYPES] OracleTypes.VARCHAR for status OUT parameter
            ocs.registerOutParameter(5, OracleTypes.VARCHAR);
            ocs.execute();
            Long accountId = ocs.getLong(4);
            String status = ocs.getString(5);
            log.info("Created account id={} status={}", accountId, status);
            return accountId;
        } catch (SQLException e) {
            throw errorTranslator.translateSQLException(e);
        }
    }

    // -----------------------------------------------------------------------
    // Get account balance via stored procedure
    // -----------------------------------------------------------------------

    /**
     * Retrieves the current balance by calling ACCOUNT_MGMT_PKG.GET_BALANCE
     * which returns a NUMBER OUT parameter.
     *
     * ORACLE-DEP [PLSQL_INVOCATION:PACKAGE_CALL] ACCOUNT_MGMT_PKG.GET_BALANCE
     */
    @OracleDependency(
            domain = DependencyDomain.PLSQL_INVOCATION,
            category = "PACKAGE_CALL",
            description = "Gets current balance via ACCOUNT_MGMT_PKG.GET_BALANCE OUT parameter",
            complexity = MigrationComplexity.MEDIUM,
            automationLevel = AutomationLevel.PARTIAL,
            migrationTarget = "Replace with SELECT balance FROM accounts WHERE account_id = ?"
    )
    public BigDecimal getBalance(Long accountId) throws SQLException {
        // ORACLE-DEP [PLSQL_INVOCATION:PACKAGE_CALL] GET_BALANCE returns balance as NUMBER OUT
        String call = "{call MERIDIAN.ACCOUNT_MGMT_PKG.GET_BALANCE(?, ?)}";
        try (Connection conn = dataSource.getConnection();
             OracleCallableStatement ocs =
                     (OracleCallableStatement) conn.prepareCall(call)) {
            ocs.setLong(1, accountId);
            // ORACLE-DEP [ORACLE_JAVA_API:ORACLE_TYPES] OracleTypes.NUMBER for balance OUT
            ocs.registerOutParameter(2, OracleTypes.NUMBER);
            ocs.execute();
            return ocs.getBigDecimal(2);
        } catch (SQLException e) {
            throw errorTranslator.translateSQLException(e);
        }
    }

    // -----------------------------------------------------------------------
    // Bulk status update using Oracle ARRAY
    // -----------------------------------------------------------------------

    /**
     * Bulk-updates account statuses by passing a NUMBER_TABLE_TYPE ARRAY
     * to ACCOUNT_MGMT_PKG.BULK_STATUS_UPDATE.
     *
     * ORACLE-DEP [ORACLE_JAVA_API:ARRAY_TYPE] NUMBER_TABLE_TYPE array for bulk account IDs
     */
    @OracleDependency(
            domain = DependencyDomain.ORACLE_JAVA_API,
            category = "ARRAY_TYPE",
            description = "Bulk account status update using NUMBER_TABLE_TYPE ARRAY parameter",
            complexity = MigrationComplexity.HIGH,
            automationLevel = AutomationLevel.PARTIAL,
            migrationTarget = "Replace with PostgreSQL BIGINT[] array or individual parameter binding"
    )
    public int bulkUpdateStatus(List<Long> accountIds, String newStatus) throws SQLException {
        // ORACLE-DEP [PLSQL_INVOCATION:PACKAGE_CALL] ACCOUNT_MGMT_PKG.BULK_STATUS_UPDATE
        String call = "{call MERIDIAN.ACCOUNT_MGMT_PKG.BULK_STATUS_UPDATE(?, ?, ?)}";
        try (Connection conn = dataSource.getConnection()) {
            // ORACLE-DEP [ORACLE_JAVA_API:ORACLE_CONNECTION] unwrap for ARRAY creation
            OracleConnection oraConn = connectionUnwrapper.unwrap(conn);
            // ORACLE-DEP [ORACLE_JAVA_API:ARRAY_TYPE] convert List<Long> to Oracle NUMBER_TABLE_TYPE
            ARRAY idArray = arrayHelper.toOracleNumberArray(oraConn, accountIds);
            try (OracleCallableStatement ocs =
                         (OracleCallableStatement) conn.prepareCall(call)) {
                // ORACLE-DEP [ORACLE_JAVA_API:ORACLE_PREPARED_STMT] setARRAY() for NUMBER_TABLE_TYPE
                ocs.setARRAY(1, idArray);
                ocs.setString(2, newStatus);
                // ORACLE-DEP [ORACLE_JAVA_API:ORACLE_TYPES] OracleTypes.NUMBER for updated count OUT
                ocs.registerOutParameter(3, OracleTypes.NUMBER);
                ocs.execute();
                int updatedCount = ocs.getInt(3);
                log.info("Bulk updated {} accounts to status={}", updatedCount, newStatus);
                return updatedCount;
            }
        } catch (SQLException e) {
            throw errorTranslator.translateSQLException(e);
        }
    }

    // -----------------------------------------------------------------------
    // Account query with REF CURSOR — paginated accounts for customer
    // -----------------------------------------------------------------------

    /**
     * Retrieves paginated accounts for a customer via REF CURSOR OUT parameter
     * from ACCOUNT_MGMT_PKG.GET_ACCOUNTS_FOR_CUSTOMER.
     *
     * ORACLE-DEP [PLSQL_INVOCATION:REF_CURSOR] OracleTypes.CURSOR as OUT parameter
     */
    @OracleDependency(
            domain = DependencyDomain.PLSQL_INVOCATION,
            category = "REF_CURSOR",
            description = "Retrieves paginated customer accounts via SYS_REFCURSOR OUT parameter",
            complexity = MigrationComplexity.HIGH,
            automationLevel = AutomationLevel.PARTIAL,
            migrationTarget = "Replace with standard query returning ResultSet directly"
    )
    public List<AccountDTO> getAccountsForCustomer(Long customerId, int pageNum, int pageSize)
            throws SQLException {
        // ORACLE-DEP [PLSQL_INVOCATION:PACKAGE_CALL] GET_ACCOUNTS_FOR_CUSTOMER returns SYS_REFCURSOR
        String call = "{call MERIDIAN.ACCOUNT_MGMT_PKG.GET_ACCOUNTS_FOR_CUSTOMER(?, ?, ?, ?)}";
        List<AccountDTO> accounts = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             OracleCallableStatement ocs =
                     (OracleCallableStatement) conn.prepareCall(call)) {
            ocs.setLong(1, customerId);
            ocs.setInt(2, pageNum);
            ocs.setInt(3, pageSize);
            // ORACLE-DEP [ORACLE_JAVA_API:ORACLE_TYPES] OracleTypes.CURSOR for SYS_REFCURSOR
            ocs.registerOutParameter(4, OracleTypes.CURSOR);
            ocs.execute();
            // ORACLE-DEP [ORACLE_JAVA_API:ORACLE_CALLABLE_STMT] getCursor() Oracle-specific method
            try (ResultSet rs = ocs.getCursor(4)) {
                while (rs != null && rs.next()) {
                    accounts.add(mapRowToDTO(rs));
                }
            }
        } catch (SQLException e) {
            throw errorTranslator.translateSQLException(e);
        }
        return accounts;
    }

    // -----------------------------------------------------------------------
    // Balance reconciliation using MERGE
    // -----------------------------------------------------------------------

    /**
     * Reconciles account balance using Oracle MERGE INTO ... USING (SELECT FROM DUAL).
     *
     * ORACLE-DEP [EMBEDDED_SQL:MERGE_STATEMENT] Oracle MERGE for balance upsert/update
     */
    @OracleDependency(
            domain = DependencyDomain.EMBEDDED_SQL,
            category = "MERGE_STATEMENT",
            description = "Reconciles account balance using Oracle MERGE INTO ... USING DUAL",
            complexity = MigrationComplexity.MEDIUM,
            automationLevel = AutomationLevel.PARTIAL,
            migrationTarget = "Replace with INSERT ... ON CONFLICT DO UPDATE SET in PostgreSQL"
    )
    public void reconcileBalance(Long accountId, BigDecimal newBalance) throws SQLException {
        // ORACLE-DEP [EMBEDDED_SQL:MERGE_STATEMENT] MERGE INTO accounts USING (SELECT FROM DUAL)
        // ORACLE-DEP [EMBEDDED_SQL:DUAL_TABLE] SELECT 1 FROM DUAL as single-row source
        // ORACLE-DEP [EMBEDDED_SQL:SYSDATE] SYSDATE for last_reconciled timestamp
        String sql = "MERGE INTO MERIDIAN.accounts tgt " +
                "USING (SELECT ? AS acct_id, ? AS bal FROM DUAL) src " +
                "ON (tgt.account_id = src.acct_id) " +
                "WHEN MATCHED THEN " +
                "  UPDATE SET tgt.balance = src.bal, tgt.last_reconciled = SYSDATE " +
                "WHEN NOT MATCHED THEN " +
                "  INSERT (account_id, balance, last_reconciled) " +
                "  VALUES (src.acct_id, src.bal, SYSDATE)";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, accountId);
            ps.setBigDecimal(2, newBalance);
            ps.executeUpdate();
            log.debug("Reconciled balance for account id={}", accountId);
        } catch (SQLException e) {
            throw errorTranslator.translateSQLException(e);
        }
    }

    // -----------------------------------------------------------------------
    // Account search with optimizer hint
    // -----------------------------------------------------------------------

    /**
     * Finds active accounts by type using an Oracle INDEX hint.
     *
     * ORACLE-DEP [EMBEDDED_SQL:QUERY_HINT] Oracle hint syntax on accounts lookup
     */
    @OracleDependency(
            domain = DependencyDomain.EMBEDDED_SQL,
            category = "QUERY_HINT",
            description = "Account lookup with Oracle INDEX hint on accounts_type_status_idx",
            complexity = MigrationComplexity.MEDIUM,
            automationLevel = AutomationLevel.PARTIAL,
            migrationTarget = "Remove hint; rely on PostgreSQL planner or use pg_hint_plan if needed"
    )
    public List<AccountDTO> findByType(String accountType) throws SQLException {
        // ORACLE-DEP [EMBEDDED_SQL:QUERY_HINT] /*+ INDEX(a accounts_type_status_idx) */ hint
        // ORACLE-DEP [EMBEDDED_SQL:NVL_FUNCTION] NVL for null overdraft_limit
        String sql = "SELECT /*+ INDEX(a MERIDIAN.accounts_type_status_idx) */ " +
                "a.account_id, a.account_number, a.account_type, a.balance, " +
                "NVL(a.overdraft_limit, 0) AS overdraft_limit, a.status, a.customer_id " +
                "FROM MERIDIAN.accounts a " +
                "WHERE a.account_type = ? AND a.status = 'ACTIVE'";
        List<AccountDTO> results = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, accountType);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    results.add(mapRowToDTO(rs));
                }
            }
        }
        return results;
    }

    // -----------------------------------------------------------------------
    // Private helpers
    // -----------------------------------------------------------------------

    private AccountDTO mapRowToDTO(ResultSet rs) throws SQLException {
        AccountDTO dto = new AccountDTO();
        try { dto.setAccountId(rs.getLong("account_id")); } catch (SQLException ignored) {}
        try { dto.setAccountNumber(rs.getString("account_number")); } catch (SQLException ignored) {}
        try { dto.setAccountType(rs.getString("account_type")); } catch (SQLException ignored) {}
        try { dto.setBalance(rs.getBigDecimal("balance")); } catch (SQLException ignored) {}
        try { dto.setStatus(rs.getString("status")); } catch (SQLException ignored) {}
        try { dto.setCustomerId(rs.getLong("customer_id")); } catch (SQLException ignored) {}
        return dto;
    }
}
