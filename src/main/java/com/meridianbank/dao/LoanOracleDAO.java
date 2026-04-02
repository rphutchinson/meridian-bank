package com.meridianbank.dao;

import com.meridianbank.annotation.AutomationLevel;
import com.meridianbank.annotation.DependencyDomain;
import com.meridianbank.annotation.MigrationComplexity;
import com.meridianbank.annotation.OracleDependency;
import com.meridianbank.dto.LoanApplicationDTO;
import com.meridianbank.exception.OracleErrorTranslator;
import com.meridianbank.oracle.OracleLobHelper;
import oracle.jdbc.OracleCallableStatement;
import oracle.jdbc.OracleConnection;
import oracle.jdbc.OracleResultSet;
import oracle.jdbc.OracleTypes;
import oracle.sql.CLOB;
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
 * Raw JDBC DAO for Loan Application operations.
 *
 * Demonstrates: LOAN_PKG calls, CLOB handling for notes, DBMS_LOB.SUBSTR,
 * MONTHS_BETWEEN for term calculations, ADD_MONTHS for maturity dates,
 * EMPTY_CLOB() initialization, OracleResultSet.getCLOB(), REF CURSOR,
 * DECODE for status mapping, date arithmetic.
 *
 * ORACLE-DEP [PLSQL_INVOCATION:PACKAGE_CALL]   LOAN_PKG calls
 * ORACLE-DEP [ORACLE_JAVA_API:ORACLE_CLOB]     oracle.sql.CLOB for notes
 * ORACLE-DEP [EMBEDDED_SQL:ADD_MONTHS]         ADD_MONTHS for maturity date
 * ORACLE-DEP [EMBEDDED_SQL:MONTHS_BETWEEN]     MONTHS_BETWEEN for term calc
 * ORACLE-DEP [EMBEDDED_SQL:EMPTY_CLOB]         EMPTY_CLOB() initialization
 */
@Repository
// ORACLE-DEP [PLSQL_INVOCATION:PACKAGE_CALL] Loan DAO using LOAN_PKG, CLOB for notes, date arithmetic
@OracleDependency(
        domain = DependencyDomain.PLSQL_INVOCATION,
        category = "PACKAGE_CALL",
        description = "Loan DAO using LOAN_PKG, oracle.sql.CLOB for notes, ADD_MONTHS/MONTHS_BETWEEN",
        complexity = MigrationComplexity.HIGH,
        automationLevel = AutomationLevel.PARTIAL,
        migrationTarget = "Replace LOAN_PKG with PostgreSQL functions; replace CLOB with TEXT"
)
public class LoanOracleDAO {

    private static final Logger log = LoggerFactory.getLogger(LoanOracleDAO.class);

    @Autowired
    private DataSource dataSource;

    @Autowired
    private OracleLobHelper lobHelper;

    @Autowired
    private OracleErrorTranslator errorTranslator;

    // -----------------------------------------------------------------------
    // Submit loan application with CLOB notes
    // -----------------------------------------------------------------------

    /**
     * Submits a loan application, writing the notes field as an Oracle CLOB
     * using EMPTY_CLOB() initialization pattern followed by CLOB update.
     *
     * ORACLE-DEP [EMBEDDED_SQL:EMPTY_CLOB]         EMPTY_CLOB() for initial CLOB value
     * ORACLE-DEP [ORACLE_JAVA_API:ORACLE_CLOB]     oracle.sql.CLOB writing
     */
    @OracleDependency(
            domain = DependencyDomain.EMBEDDED_SQL,
            category = "EMPTY_CLOB",
            description = "Inserts loan application with EMPTY_CLOB() notes field then updates with content",
            complexity = MigrationComplexity.MEDIUM,
            automationLevel = AutomationLevel.FULL,
            migrationTarget = "Replace EMPTY_CLOB()/LOB locator pattern with direct TEXT binding in PostgreSQL"
    )
    public Long submitApplication(LoanApplicationDTO dto) throws SQLException {
        // ORACLE-DEP [EMBEDDED_SQL:SEQUENCE] loan_application_seq.NEXTVAL for ID
        // ORACLE-DEP [EMBEDDED_SQL:EMPTY_CLOB] EMPTY_CLOB() initializes LOB locator for later write
        // ORACLE-DEP [EMBEDDED_SQL:SYSDATE] SYSDATE for application_date
        String insertSql = "INSERT INTO MERIDIAN.loan_applications " +
                "(application_id, customer_id, loan_type, requested_amount, status, " +
                " application_date, notes) " +
                "VALUES (MERIDIAN.loan_app_seq.NEXTVAL, ?, ?, ?, 'PENDING', SYSDATE, EMPTY_CLOB()) " +
                "RETURNING application_id, notes INTO ?, ?";
        Long appId = null;
        CLOB clobLocator = null;
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            // ORACLE-DEP [ORACLE_JAVA_API:ORACLE_PREPARED_STMT] OraclePreparedStatement for RETURNING CLOB locator
            try (oracle.jdbc.OraclePreparedStatement ops =
                         (oracle.jdbc.OraclePreparedStatement) conn.prepareStatement(insertSql)) {
                ops.setLong(1, dto.getCustomerId());
                ops.setString(2, dto.getLoanType());
                ops.setBigDecimal(3, dto.getRequestedAmount());
                ops.registerReturnParameter(4, OracleTypes.NUMBER);
                // ORACLE-DEP [ORACLE_JAVA_API:ORACLE_CLOB] CLOB locator returned via RETURNING INTO
                ops.registerReturnParameter(5, OracleTypes.CLOB);
                ops.executeUpdate();
                try (ResultSet rs = ops.getReturnResultSet()) {
                    if (rs != null && rs.next()) {
                        appId = rs.getLong(1);
                        // ORACLE-DEP [ORACLE_JAVA_API:ORACLE_CLOB] Cast to oracle.sql.CLOB for write access
                        clobLocator = (CLOB) rs.getClob(2);
                    }
                }
            }
            if (clobLocator != null && dto.getNotes() != null) {
                // ORACLE-DEP [ORACLE_JAVA_API:ORACLE_CLOB] CLOB.setString(1, content) — 1-based offset
                clobLocator.setString(1, dto.getNotes());
            }
            conn.commit();
            log.info("Submitted loan application id={}", appId);
        } catch (SQLException e) {
            throw errorTranslator.translateSQLException(e);
        }
        return appId;
    }

    // -----------------------------------------------------------------------
    // Approve loan via LOAN_PKG
    // -----------------------------------------------------------------------

    /**
     * Approves a loan application via LOAN_PKG.APPROVE_LOAN, which uses
     * ADD_MONTHS internally to calculate the maturity date.
     *
     * ORACLE-DEP [PLSQL_INVOCATION:PACKAGE_CALL] LOAN_PKG.APPROVE_LOAN
     * ORACLE-DEP [EMBEDDED_SQL:ADD_MONTHS]        ADD_MONTHS in PL/SQL for maturity
     */
    @OracleDependency(
            domain = DependencyDomain.PLSQL_INVOCATION,
            category = "PACKAGE_CALL",
            description = "Approves loan via LOAN_PKG.APPROVE_LOAN; PL/SQL uses ADD_MONTHS for maturity",
            complexity = MigrationComplexity.HIGH,
            automationLevel = AutomationLevel.PARTIAL,
            migrationTarget = "Replace with PostgreSQL function; use date + interval for maturity"
    )
    public void approveLoan(Long applicationId, BigDecimal approvedAmount,
                            BigDecimal interestRate, int termMonths) throws SQLException {
        // ORACLE-DEP [PLSQL_INVOCATION:PACKAGE_CALL] LOAN_PKG.APPROVE_LOAN with underwriting params
        String call = "{call MERIDIAN.LOAN_PKG.APPROVE_LOAN(?, ?, ?, ?)}";
        try (Connection conn = dataSource.getConnection();
             OracleCallableStatement ocs =
                     (OracleCallableStatement) conn.prepareCall(call)) {
            ocs.setLong(1, applicationId);
            ocs.setBigDecimal(2, approvedAmount);
            ocs.setBigDecimal(3, interestRate);
            ocs.setInt(4, termMonths);
            ocs.execute();
            log.info("Approved loan application id={}", applicationId);
        } catch (SQLException e) {
            throw errorTranslator.translateSQLException(e);
        }
    }

    // -----------------------------------------------------------------------
    // Read loan with CLOB notes via OracleResultSet
    // -----------------------------------------------------------------------

    /**
     * Reads a loan application, reading the CLOB notes field via OracleResultSet.getCLOB().
     *
     * ORACLE-DEP [ORACLE_JAVA_API:ORACLE_RESULT_SET] OracleResultSet.getCLOB() access
     */
    @OracleDependency(
            domain = DependencyDomain.ORACLE_JAVA_API,
            category = "ORACLE_RESULT_SET",
            description = "Reads CLOB notes field using OracleResultSet.getCLOB()",
            complexity = MigrationComplexity.MEDIUM,
            automationLevel = AutomationLevel.FULL,
            migrationTarget = "Replace with ResultSet.getString() for PostgreSQL TEXT column"
    )
    public Optional<LoanApplicationDTO> findById(Long applicationId) throws SQLException {
        // ORACLE-DEP [EMBEDDED_SQL:NVL_FUNCTION] NVL for null interest_rate display
        // ORACLE-DEP [EMBEDDED_SQL:DECODE_FUNCTION] DECODE for loan status labels
        String sql = "SELECT application_id, customer_id, loan_type, requested_amount, " +
                "NVL(approved_amount, 0) AS approved_amount, " +
                "NVL(interest_rate, 0) AS interest_rate, " +
                "DECODE(status, 'PENDING', 'Under Review', 'APPROVED', 'Approved', " +
                "               'DENIED', 'Declined', status) AS status_label, " +
                "status, application_date, notes " +
                "FROM MERIDIAN.loan_applications WHERE application_id = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, applicationId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    LoanApplicationDTO dto = mapRowToDTO(rs);
                    // ORACLE-DEP [ORACLE_JAVA_API:ORACLE_RESULT_SET] getCLOB via lobHelper
                    String notes = lobHelper.readClobFromResultSet(rs, "notes");
                    dto.setNotes(notes);
                    return Optional.of(dto);
                }
            }
        } catch (SQLException e) {
            throw errorTranslator.translateSQLException(e);
        }
        return Optional.empty();
    }

    // -----------------------------------------------------------------------
    // Overdue loans using date subtraction
    // -----------------------------------------------------------------------

    /**
     * Returns loans overdue using Oracle date - date arithmetic (returns NUMBER of days).
     *
     * ORACLE-DEP [EMBEDDED_SQL:DATE_ARITHMETIC] date - date returns NUMBER in Oracle
     */
    @OracleDependency(
            domain = DependencyDomain.EMBEDDED_SQL,
            category = "DATE_ARITHMETIC",
            description = "Overdue loan detection using Oracle date - date = NUMBER days arithmetic",
            complexity = MigrationComplexity.LOW,
            automationLevel = AutomationLevel.FULL,
            migrationTarget = "Replace with EXTRACT(EPOCH FROM (NOW() - due_date))/86400 in PostgreSQL"
    )
    public List<LoanApplicationDTO> findOverdueLoans() throws SQLException {
        // ORACLE-DEP [EMBEDDED_SQL:DATE_ARITHMETIC] SYSDATE - due_date gives NUMBER of days overdue
        // ORACLE-DEP [EMBEDDED_SQL:SYSDATE] SYSDATE for current date comparison
        // ORACLE-DEP [EMBEDDED_SQL:TRUNC_DATE] TRUNC(due_date) to day boundary
        // ORACLE-DEP [EMBEDDED_SQL:DATE_ARITHMETIC] SYSDATE - application_date gives NUMBER of days pending
        // ORACLE-DEP [EMBEDDED_SQL:TRUNC_DATE] TRUNC(SYSDATE) for day boundary comparison
        String sql = "SELECT application_id, customer_id, loan_type, requested_amount, " +
                "approved_amount, interest_rate, status, application_date, " +
                "TRUNC(SYSDATE - application_date) AS days_overdue " +
                "FROM MERIDIAN.loan_applications " +
                "WHERE status IN ('PENDING','UNDER_REVIEW') AND application_date < TRUNC(SYSDATE) - 7 " +
                "ORDER BY days_overdue DESC";
        List<LoanApplicationDTO> results = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    results.add(mapRowToDTO(rs));
                }
            }
        }
        return results;
    }

    // -----------------------------------------------------------------------
    // Loan underwriting via REF CURSOR
    // -----------------------------------------------------------------------

    /**
     * Retrieves underwriting data via LOAN_PKG.GET_UNDERWRITING_DATA REF CURSOR.
     *
     * ORACLE-DEP [PLSQL_INVOCATION:REF_CURSOR] OracleTypes.CURSOR OUT for underwriting data
     */
    @OracleDependency(
            domain = DependencyDomain.PLSQL_INVOCATION,
            category = "REF_CURSOR",
            description = "Retrieves loan underwriting data via LOAN_PKG.GET_UNDERWRITING_DATA CURSOR",
            complexity = MigrationComplexity.HIGH,
            automationLevel = AutomationLevel.PARTIAL,
            migrationTarget = "Replace REF CURSOR with standard SELECT returning ResultSet"
    )
    public List<LoanApplicationDTO> getUnderwritingData(String loanType) throws SQLException {
        // ORACLE-DEP [PLSQL_INVOCATION:PACKAGE_CALL] LOAN_PKG.GET_UNDERWRITING_DATA
        String call = "{call MERIDIAN.LOAN_PKG.GET_UNDERWRITING_DATA(?, ?)}";
        List<LoanApplicationDTO> results = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             OracleCallableStatement ocs =
                     (OracleCallableStatement) conn.prepareCall(call)) {
            ocs.setString(1, loanType);
            // ORACLE-DEP [ORACLE_JAVA_API:ORACLE_TYPES] OracleTypes.CURSOR for SYS_REFCURSOR
            ocs.registerOutParameter(2, OracleTypes.CURSOR);
            ocs.execute();
            // ORACLE-DEP [ORACLE_JAVA_API:ORACLE_CALLABLE_STMT] getCursor() Oracle-specific method
            try (ResultSet rs = ocs.getCursor(2)) {
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
    // Private helpers
    // -----------------------------------------------------------------------

    private LoanApplicationDTO mapRowToDTO(ResultSet rs) throws SQLException {
        LoanApplicationDTO dto = new LoanApplicationDTO();
        try { dto.setApplicationId(rs.getLong("application_id")); } catch (SQLException ignored) {}
        try { dto.setCustomerId(rs.getLong("customer_id")); } catch (SQLException ignored) {}
        try { dto.setLoanType(rs.getString("loan_type")); } catch (SQLException ignored) {}
        try { dto.setRequestedAmount(rs.getBigDecimal("requested_amount")); } catch (SQLException ignored) {}
        try { dto.setApprovedAmount(rs.getBigDecimal("approved_amount")); } catch (SQLException ignored) {}
        try { dto.setInterestRate(rs.getBigDecimal("interest_rate")); } catch (SQLException ignored) {}
        try { dto.setStatus(rs.getString("status")); } catch (SQLException ignored) {}
        try {
            Date appDate = rs.getDate("application_date");
            if (appDate != null) dto.setApplicationDate(appDate.toLocalDate());
        } catch (SQLException ignored) {}
        return dto;
    }
}
