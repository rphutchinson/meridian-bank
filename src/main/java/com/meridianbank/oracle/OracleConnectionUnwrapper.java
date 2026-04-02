package com.meridianbank.oracle;

import com.meridianbank.annotation.AutomationLevel;
import com.meridianbank.annotation.DependencyDomain;
import com.meridianbank.annotation.MigrationComplexity;
import com.meridianbank.annotation.OracleDependency;
import oracle.jdbc.OracleConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;

/**
 * Utility for safely unwrapping a JDBC Connection to obtain the underlying OracleConnection.
 *
 * Spring, HikariCP, and Oracle UCP all wrap the physical connection in proxy objects.
 * This class handles each wrapper layer so DAO code can access Oracle-specific APIs
 * (STRUCT creation, ARRAY types, OracleCallableStatement, etc.) without needing to know
 * which connection pool is currently active.
 *
 * Migration note: once the application is migrated off Oracle, all callers of unwrap()
 * should be refactored to use standard JDBC only and this class removed entirely.
 *
 * ORACLE-DEP [ORACLE_JAVA_API:ORACLE_CONNECTION] Requires oracle.jdbc.OracleConnection
 */
@Component
// ORACLE-DEP [ORACLE_JAVA_API:ORACLE_CONNECTION] Class exists solely to expose OracleConnection
@OracleDependency(
        domain = DependencyDomain.ORACLE_JAVA_API,
        category = "ORACLE_CONNECTION",
        description = "Class exists solely to expose OracleConnection from wrapped connection pools",
        complexity = MigrationComplexity.HIGH,
        automationLevel = AutomationLevel.PARTIAL,
        migrationTarget = "Remove this class entirely; refactor all callers to use standard java.sql.Connection"
)
public class OracleConnectionUnwrapper {

    private static final Logger log = LoggerFactory.getLogger(OracleConnectionUnwrapper.class);

    /**
     * Unwraps a connection from any pool (HikariCP, UCP, plain JDBC) to OracleConnection.
     *
     * Resolution order:
     *   1. Direct instanceof check — fastest, covers Oracle UCP.
     *   2. Standard JDBC 4.0 isWrapperFor/unwrap — HikariCP, Spring JDBC, DBCP2.
     *   3. Direct cast as a last resort — pools that do not implement Wrapper correctly.
     *
     * ORACLE-DEP [ORACLE_JAVA_API:ORACLE_CONNECTION] oracle.jdbc.OracleConnection unwrap
     */
    @OracleDependency(
            domain = DependencyDomain.ORACLE_JAVA_API,
            category = "ORACLE_CONNECTION",
            description = "Unwraps Connection proxy to OracleConnection for Oracle-specific API access",
            complexity = MigrationComplexity.HIGH,
            automationLevel = AutomationLevel.PARTIAL,
            migrationTarget = "Remove after migration; replace all callers with standard JDBC operations"
    )
    public OracleConnection unwrap(Connection conn) throws SQLException {
        if (conn == null) {
            throw new IllegalArgumentException("Connection must not be null");
        }
        // ORACLE-DEP [ORACLE_JAVA_API:ORACLE_CONNECTION] Direct instanceof check for oracle.jdbc.OracleConnection
        if (conn instanceof OracleConnection) {
            log.debug("Connection is already an OracleConnection - no unwrapping required");
            return (OracleConnection) conn;
        }
        // ORACLE-DEP [ORACLE_JAVA_API:ORACLE_CONNECTION] JDBC isWrapperFor/unwrap to oracle.jdbc.OracleConnection
        try {
            if (conn.isWrapperFor(OracleConnection.class)) {
                log.debug("Unwrapping via JDBC 4.0 isWrapperFor/unwrap mechanism");
                return conn.unwrap(OracleConnection.class);
            }
        } catch (SQLException ex) {
            log.debug("isWrapperFor failed ({}), attempting direct cast fallback", ex.getMessage());
        }
        // ORACLE-DEP [ORACLE_JAVA_API:ORACLE_CONNECTION] Fallback direct cast to oracle.jdbc.OracleConnection
        try {
            log.warn("Falling back to direct cast for connection type: {}", conn.getClass().getName());
            return (OracleConnection) conn;
        } catch (ClassCastException cce) {
            throw new SQLException(
                    "Cannot unwrap to OracleConnection. Connection type: "
                    + conn.getClass().getName()
                    + ". Verify ojdbc11 is on the classpath and the DataSource targets Oracle DB.",
                    cce);
        }
    }

    /**
     * Obtains a new connection from the DataSource and unwraps it to OracleConnection.
     * The caller is responsible for closing the connection when finished.
     *
     * ORACLE-DEP [ORACLE_JAVA_API:ORACLE_CONNECTION] Obtain OracleConnection directly from DataSource
     */
    @OracleDependency(
            domain = DependencyDomain.ORACLE_JAVA_API,
            category = "ORACLE_CONNECTION",
            description = "Obtains a pooled connection and unwraps it to OracleConnection",
            complexity = MigrationComplexity.HIGH,
            automationLevel = AutomationLevel.PARTIAL,
            migrationTarget = "Replace with standard DataSource.getConnection(); remove Oracle-specific unwrap"
    )
    public OracleConnection unwrapFromDataSource(DataSource dataSource) throws SQLException {
        if (dataSource == null) {
            throw new IllegalArgumentException("DataSource must not be null");
        }
        // ORACLE-DEP [ORACLE_JAVA_API:ORACLE_CONNECTION] getConnection() then unwrap to OracleConnection
        Connection conn = dataSource.getConnection();
        return unwrap(conn);
    }

    /**
     * Returns true if the given connection is already or wraps an OracleConnection.
     */
    public boolean isOracleConnection(Connection conn) {
        if (conn == null) {
            return false;
        }
        // ORACLE-DEP [ORACLE_JAVA_API:ORACLE_CONNECTION] instanceof check for oracle.jdbc.OracleConnection
        if (conn instanceof OracleConnection) {
            return true;
        }
        try {
            return conn.isWrapperFor(OracleConnection.class);
        } catch (SQLException e) {
            log.trace("isWrapperFor threw SQLException - treating as non-Oracle connection", e);
            return false;
        }
    }

    /**
     * Returns the Oracle Database server version string.
     * Uses OracleConnection.getServerRelease() for structured int[5] version data.
     *
     * ORACLE-DEP [ORACLE_JAVA_API:ORACLE_CONNECTION] OracleConnection.getServerRelease() - Oracle-proprietary
     */
    @OracleDependency(
            domain = DependencyDomain.ORACLE_JAVA_API,
            category = "ORACLE_CONNECTION",
            description = "Retrieves Oracle server version via OracleConnection.getServerRelease()",
            complexity = MigrationComplexity.LOW,
            automationLevel = AutomationLevel.FULL,
            migrationTarget = "Replace with standard DatabaseMetaData.getDatabaseProductVersion()"
    )
    public String getServerVersion(Connection conn) throws SQLException {
        // ORACLE-DEP [ORACLE_JAVA_API:ORACLE_CONNECTION] getDatabaseProductVersion() - Oracle-proprietary version info
        // Note: OracleConnection.getServerRelease() was removed in Oracle JDBC 23ai; use standard JDBC
        DatabaseMetaData meta = conn.getMetaData();
        String productVersion = meta.getDatabaseProductVersion();
        log.info("Oracle server version: {}", productVersion);
        return productVersion;
    }
}
