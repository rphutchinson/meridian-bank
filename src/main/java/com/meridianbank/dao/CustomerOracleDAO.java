package com.meridianbank.dao;

import com.meridianbank.annotation.AutomationLevel;
import com.meridianbank.annotation.DependencyDomain;
import com.meridianbank.annotation.MigrationComplexity;
import com.meridianbank.annotation.OracleDependency;
import com.meridianbank.dto.CustomerDTO;
import com.meridianbank.exception.OracleErrorTranslator;
import com.meridianbank.oracle.OracleConnectionUnwrapper;
import com.meridianbank.oracle.OracleLobHelper;
import com.meridianbank.oracle.OracleStructHelper;
import oracle.jdbc.OracleCallableStatement;
import oracle.jdbc.OracleConnection;
import oracle.jdbc.OraclePreparedStatement;
import oracle.jdbc.OracleTypes;
import oracle.sql.STRUCT;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.*;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Raw JDBC DAO for Customer operations using Oracle-specific APIs.
 *
 * Demonstrates: STRUCT types for addresses, OracleTypes for OUT parameters,
 * RETURNING INTO, DBMS_CRYPTO calls, Oracle Text search, NVL/DECODE in SQL,
 * ORA-error code handling.
 *
 * ORACLE-DEP [PLSQL_INVOCATION:PACKAGE_CALL]    MERIDIAN.CUSTOMER_MGMT_PKG calls
 * ORACLE-DEP [ORACLE_JAVA_API:ORACLE_CONNECTION] OracleConnection unwrapping
 * ORACLE-DEP [ORACLE_JAVA_API:STRUCT_TYPE]       ADDRESS_TYPE STRUCT binding
 * ORACLE-DEP [EMBEDDED_SQL:RETURNING_INTO]       INSERT RETURNING customer_id
 */
@Repository
// ORACLE-DEP [PLSQL_INVOCATION:PACKAGE_CALL] DAO exercising Oracle-specific JDBC APIs
@OracleDependency(
        domain = DependencyDomain.PLSQL_INVOCATION,
        category = "PACKAGE_CALL",
        description = "Customer DAO using OracleConnection, OracleCallableStatement, STRUCT, and OracleTypes",
        complexity = MigrationComplexity.HIGH,
        automationLevel = AutomationLevel.PARTIAL,
        migrationTarget = "Replace with standard JDBC and PostgreSQL stored functions"
)
public class CustomerOracleDAO {

    private static final Logger log = LoggerFactory.getLogger(CustomerOracleDAO.class);

    @Autowired
    private DataSource dataSource;

    @Autowired
    private OracleConnectionUnwrapper connectionUnwrapper;

    @Autowired
    private OracleStructHelper structHelper;

    @Autowired
    private OracleLobHelper lobHelper;

    @Autowired
    private OracleErrorTranslator errorTranslator;

    // -----------------------------------------------------------------------
    // Customer creation with RETURNING INTO
    // -----------------------------------------------------------------------

    /**
     * Creates a customer using INSERT ... RETURNING customer_id INTO ?.
     *
     * ORACLE-DEP [EMBEDDED_SQL:RETURNING_INTO] INSERT RETURNING to retrieve generated ID
     */
    @OracleDependency(
            domain = DependencyDomain.EMBEDDED_SQL,
            category = "RETURNING_INTO",
            description = "INSERT customer with RETURNING customer_id INTO OUT parameter",
            complexity = MigrationComplexity.MEDIUM,
            automationLevel = AutomationLevel.PARTIAL,
            migrationTarget = "Replace with INSERT ... RETURNING (PostgreSQL) and getGeneratedKeys()"
    )
    public Long createCustomer(CustomerDTO dto) throws SQLException {
        // ORACLE-DEP [EMBEDDED_SQL:RETURNING_INTO] Oracle RETURNING INTO — different from PostgreSQL RETURNING
        // ORACLE-DEP [EMBEDDED_SQL:SEQUENCE] customer_seq.NEXTVAL for ID generation
        String sql = "INSERT INTO MERIDIAN.customers (customer_id, first_name, last_name, email, " +
                "phone, date_of_birth, status, created_date) " +
                "VALUES (MERIDIAN.customer_seq.NEXTVAL, ?, ?, ?, ?, ?, 'ACTIVE', SYSDATE) " +
                "RETURNING customer_id INTO ?";
        // ORACLE-DEP [EMBEDDED_SQL:SYSDATE] SYSDATE for created_date default

        try (Connection conn = dataSource.getConnection();
             // ORACLE-DEP [ORACLE_JAVA_API:ORACLE_PREPARED_STMT] OraclePreparedStatement for RETURNING INTO
             OraclePreparedStatement ops = (OraclePreparedStatement)
                     conn.prepareStatement(sql)) {
            ops.setString(1, dto.getFirstName());
            ops.setString(2, dto.getLastName());
            ops.setString(3, dto.getEmail());
            ops.setString(4, dto.getPhone());
            ops.setDate(5, dto.getDateOfBirth() != null ?
                    Date.valueOf(dto.getDateOfBirth()) : null);
            // ORACLE-DEP [ORACLE_JAVA_API:ORACLE_PREPARED_STMT] registerReturnParameter for RETURNING INTO
            ops.registerReturnParameter(6, OracleTypes.NUMBER);
            ops.executeUpdate();
            // ORACLE-DEP [ORACLE_JAVA_API:ORACLE_PREPARED_STMT] getReturnResultSet() Oracle-specific
            try (ResultSet rs = ops.getReturnResultSet()) {
                if (rs != null && rs.next()) {
                    Long id = rs.getLong(1);
                    log.info("Created customer id={}", id);
                    return id;
                }
            }
        } catch (SQLException e) {
            throw errorTranslator.translateSQLException(e);
        }
        throw new SQLException("INSERT returned no ID");
    }

    // -----------------------------------------------------------------------
    // Address update with Oracle STRUCT
    // -----------------------------------------------------------------------

    /**
     * Updates a customer's address by passing an Oracle ADDRESS_TYPE STRUCT
     * to the stored procedure MERIDIAN.CUSTOMER_MGMT_PKG.UPDATE_ADDRESS.
     *
     * ORACLE-DEP [ORACLE_JAVA_API:STRUCT_TYPE] ADDRESS_TYPE STRUCT parameter binding
     */
    @OracleDependency(
            domain = DependencyDomain.ORACLE_JAVA_API,
            category = "STRUCT_TYPE",
            description = "Passes Oracle ADDRESS_TYPE STRUCT to UPDATE_ADDRESS procedure",
            complexity = MigrationComplexity.HIGH,
            automationLevel = AutomationLevel.PARTIAL,
            migrationTarget = "Replace STRUCT with individual address columns or JSON parameter"
    )
    public void updateAddress(Long customerId, CustomerDTO dto) throws SQLException {
        // ORACLE-DEP [PLSQL_INVOCATION:PACKAGE_CALL] CUSTOMER_MGMT_PKG.UPDATE_ADDRESS procedure
        String call = "{call MERIDIAN.CUSTOMER_MGMT_PKG.UPDATE_ADDRESS(?, ?)}";
        try (Connection conn = dataSource.getConnection()) {
            // ORACLE-DEP [ORACLE_JAVA_API:ORACLE_CONNECTION] unwrap for STRUCT creation
            OracleConnection oraConn = connectionUnwrapper.unwrap(conn);
            // ORACLE-DEP [ORACLE_JAVA_API:STRUCT_TYPE] Build ADDRESS_TYPE STRUCT from DTO
            STRUCT addressStruct = structHelper.addressDTOtoStruct(oraConn, dto);
            try (OracleCallableStatement ocs =
                         (OracleCallableStatement) conn.prepareCall(call)) {
                ocs.setLong(1, customerId);
                // ORACLE-DEP [ORACLE_JAVA_API:ORACLE_PREPARED_STMT] setSTRUCT() for ADDRESS_TYPE
                ocs.setSTRUCT(2, addressStruct);
                ocs.execute();
                log.debug("Updated address for customer id={}", customerId);
            }
        } catch (SQLException e) {
            throw errorTranslator.translateSQLException(e);
        }
    }

    // -----------------------------------------------------------------------
    // Customer search with Oracle Text CONTAINS
    // -----------------------------------------------------------------------

    /**
     * Full-text customer search using Oracle Text CONTAINS() operator.
     *
     * ORACLE-DEP [EMBEDDED_SQL:ORACLE_TEXT] Oracle Text CONTAINS() for full-text search
     */
    @OracleDependency(
            domain = DependencyDomain.EMBEDDED_SQL,
            category = "ORACLE_TEXT",
            description = "Full-text customer search using Oracle Text CONTAINS() on customer_search_idx",
            complexity = MigrationComplexity.HIGH,
            automationLevel = AutomationLevel.MANUAL,
            migrationTarget = "Replace with PostgreSQL full-text search using tsvector/tsquery or pg_trgm"
    )
    public List<CustomerDTO> searchCustomers(String searchTerm, int maxResults) throws SQLException {
        // ORACLE-DEP [EMBEDDED_SQL:ORACLE_TEXT] CONTAINS() with customer_ft_idx full-text index
        // ORACLE-DEP [EMBEDDED_SQL:NVL_FUNCTION] NVL for null-safe column display
        // ORACLE-DEP [EMBEDDED_SQL:ROWNUM] ROWNUM for limiting full-text search results
        String sql = "SELECT customer_id, first_name, last_name, email, " +
                "NVL(phone, 'N/A') AS phone, status, " +
                "SCORE(1) AS relevance_score " +
                "FROM MERIDIAN.customers " +
                "WHERE CONTAINS(first_name || ' ' || last_name, ?, 1) > 0 " +
                "AND status = 'ACTIVE' " +
                "AND ROWNUM <= ? " +
                "ORDER BY SCORE(1) DESC";
        List<CustomerDTO> results = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, searchTerm);
            ps.setInt(2, maxResults);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    results.add(mapRowToDTO(rs));
                }
            }
        }
        return results;
    }

    // -----------------------------------------------------------------------
    // REF CURSOR — get customer with accounts summary
    // -----------------------------------------------------------------------

    /**
     * Calls CUSTOMER_MGMT_PKG.GET_CUSTOMER_WITH_ACCOUNTS which returns a
     * REF CURSOR with customer + account summary data.
     *
     * ORACLE-DEP [PLSQL_INVOCATION:REF_CURSOR] OracleTypes.CURSOR OUT parameter
     */
    @OracleDependency(
            domain = DependencyDomain.PLSQL_INVOCATION,
            category = "REF_CURSOR",
            description = "Retrieves customer with accounts summary via CURSOR OUT parameter",
            complexity = MigrationComplexity.HIGH,
            automationLevel = AutomationLevel.PARTIAL,
            migrationTarget = "Replace REF CURSOR with standard JOIN query returning ResultSet"
    )
    public Optional<CustomerDTO> getCustomerWithAccountsSummary(Long customerId) throws SQLException {
        // ORACLE-DEP [PLSQL_INVOCATION:PACKAGE_CALL] GET_CUSTOMER_WITH_ACCOUNTS returns SYS_REFCURSOR
        String call = "{call MERIDIAN.CUSTOMER_MGMT_PKG.GET_CUSTOMER_WITH_ACCOUNTS(?, ?)}";
        try (Connection conn = dataSource.getConnection();
             OracleCallableStatement ocs =
                     (OracleCallableStatement) conn.prepareCall(call)) {
            ocs.setLong(1, customerId);
            // ORACLE-DEP [ORACLE_JAVA_API:ORACLE_TYPES] OracleTypes.CURSOR for SYS_REFCURSOR OUT param
            ocs.registerOutParameter(2, OracleTypes.CURSOR);
            ocs.execute();
            // ORACLE-DEP [ORACLE_JAVA_API:ORACLE_CALLABLE_STMT] getCursor() returns Oracle ResultSet
            try (ResultSet rs = ocs.getCursor(2)) {
                if (rs != null && rs.next()) {
                    return Optional.of(mapRowToDTO(rs));
                }
            }
        } catch (SQLException e) {
            // ORACLE-DEP [EMBEDDED_SQL:ORA_ERROR_CODES] ORA-01403 no data found
            if (e.getErrorCode() == 1403) {
                return Optional.empty();
            }
            throw errorTranslator.translateSQLException(e);
        }
        return Optional.empty();
    }

    // -----------------------------------------------------------------------
    // Paginated customer list using ROWNUM double-wrap
    // -----------------------------------------------------------------------

    /**
     * Returns a page of customers using Oracle pre-12c ROWNUM double-wrap pagination.
     *
     * ORACLE-DEP [EMBEDDED_SQL:ROWNUM] Oracle ROWNUM double-wrap pagination
     */
    @OracleDependency(
            domain = DependencyDomain.EMBEDDED_SQL,
            category = "ROWNUM",
            description = "Paginated customer list using Oracle ROWNUM double-wrap pattern",
            complexity = MigrationComplexity.MEDIUM,
            automationLevel = AutomationLevel.FULL,
            migrationTarget = "Replace with LIMIT/OFFSET in PostgreSQL"
    )
    public List<CustomerDTO> findCustomersPaginated(int pageNumber, int pageSize) throws SQLException {
        int minRow = (pageNumber - 1) * pageSize + 1;
        int maxRow = pageNumber * pageSize;
        // ORACLE-DEP [EMBEDDED_SQL:ROWNUM] Oracle ROWNUM double-wrap for offset pagination
        // ORACLE-DEP [EMBEDDED_SQL:NVL_FUNCTION] NVL for null-safe phone display
        // ORACLE-DEP [EMBEDDED_SQL:ROWNUM] Double-wrap ROWNUM pagination — inner alias 'c' required for c.* reference
        String sql = "SELECT * FROM (" +
                "  SELECT c.*, ROWNUM AS rn FROM (" +
                "    SELECT customer_id, first_name, last_name, email, " +
                "           NVL(phone, 'N/A') AS phone, status " +
                "    FROM MERIDIAN.customers WHERE status = 'ACTIVE' " +
                "    ORDER BY last_name, first_name" +
                "  ) c WHERE ROWNUM <= ?" +
                ") WHERE rn >= ?";
        List<CustomerDTO> results = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, maxRow);
            ps.setInt(2, minRow);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    results.add(mapRowToDTO(rs));
                }
            }
        }
        return results;
    }

    // -----------------------------------------------------------------------
    // PL/SQL anonymous block — bulk status update
    // -----------------------------------------------------------------------

    /**
     * Executes a PL/SQL anonymous block to bulk-update customer status
     * with Oracle-specific error handling via EXCEPTION WHEN OTHERS.
     *
     * ORACLE-DEP [EMBEDDED_SQL:PLSQL_BLOCK] PL/SQL anonymous block executed via JDBC
     */
    @OracleDependency(
            domain = DependencyDomain.EMBEDDED_SQL,
            category = "PLSQL_BLOCK",
            description = "Executes PL/SQL anonymous block for bulk customer status update",
            complexity = MigrationComplexity.HIGH,
            automationLevel = AutomationLevel.MANUAL,
            migrationTarget = "Move logic to application tier or PostgreSQL DO $$ ... $$ block"
    )
    public int bulkUpdateStatus(List<Long> customerIds, String newStatus) throws SQLException {
        // ORACLE-DEP [EMBEDDED_SQL:PLSQL_BLOCK] BEGIN...END PL/SQL anonymous block via JDBC
        // ORACLE-DEP [EMBEDDED_SQL:PLSQL_BLOCK] FORALL-style update through PL/SQL
        StringBuilder sb = new StringBuilder(
                "BEGIN\n" +
                "  FOR i IN (SELECT customer_id FROM MERIDIAN.customers\n" +
                "             WHERE customer_id IN (");
        for (int i = 0; i < customerIds.size(); i++) {
            sb.append(customerIds.get(i));
            if (i < customerIds.size() - 1) sb.append(",");
        }
        // ORACLE-DEP [EMBEDDED_SQL:SYSDATE] SYSDATE for updated_date in PL/SQL block
        sb.append(")) LOOP\n" +
                "    UPDATE MERIDIAN.customers SET status = ?, updated_date = SYSDATE\n" +
                "    WHERE customer_id = i.customer_id;\n" +
                "  END LOOP;\n" +
                "  COMMIT;\n" +
                "END;");
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sb.toString())) {
            ps.setString(1, newStatus);
            ps.execute();
            log.info("Bulk updated {} customers to status={}", customerIds.size(), newStatus);
            return customerIds.size();
        } catch (SQLException e) {
            throw errorTranslator.translateSQLException(e);
        }
    }

    // -----------------------------------------------------------------------
    // Private helpers
    // -----------------------------------------------------------------------

    private CustomerDTO mapRowToDTO(ResultSet rs) throws SQLException {
        CustomerDTO dto = new CustomerDTO();
        try {
            dto.setCustomerId(rs.getLong("customer_id"));
        } catch (SQLException ignored) {}
        try {
            dto.setFirstName(rs.getString("first_name"));
        } catch (SQLException ignored) {}
        try {
            dto.setLastName(rs.getString("last_name"));
        } catch (SQLException ignored) {}
        try {
            dto.setEmail(rs.getString("email"));
        } catch (SQLException ignored) {}
        try {
            dto.setPhone(rs.getString("phone"));
        } catch (SQLException ignored) {}
        try {
            dto.setStatus(rs.getString("status"));
        } catch (SQLException ignored) {}
        try {
            Date dob = rs.getDate("date_of_birth");
            if (dob != null) dto.setDateOfBirth(dob.toLocalDate());
        } catch (SQLException ignored) {}
        return dto;
    }
}
