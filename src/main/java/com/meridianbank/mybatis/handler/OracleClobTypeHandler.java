package com.meridianbank.mybatis.handler;

import com.meridianbank.annotation.AutomationLevel;
import com.meridianbank.annotation.DependencyDomain;
import com.meridianbank.annotation.MigrationComplexity;
import com.meridianbank.annotation.OracleDependency;
import oracle.sql.CLOB;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedJdbcTypes;
import org.apache.ibatis.type.MappedTypes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Reader;
import java.io.StringReader;
import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * MyBatis TypeHandler for Oracle CLOB columns.
 *
 * <p>Uses {@code oracle.sql.CLOB} directly rather than the standard JDBC
 * {@code java.sql.Clob} interface to handle Oracle-specific CLOB creation
 * and materialization behaviour, particularly for empty CLOBs and
 * large content that cannot be set via a simple string parameter.
 *
 * <p>Migration: Replace with a standard {@code StringTypeHandler} or
 * {@code ClobTypeHandler} — PostgreSQL TEXT columns accept plain strings.
 */
@MappedTypes(String.class)
@MappedJdbcTypes(JdbcType.CLOB)
@OracleDependency(
    domain          = DependencyDomain.ORM_DATA_ACCESS,
    category        = "ORACLE_CLOB_HANDLER",
    description     = "Custom MyBatis TypeHandler using oracle.sql.CLOB for CLOB read/write",
    complexity      = MigrationComplexity.LOW,
    automationLevel = AutomationLevel.FULL,
    migrationTarget = "Replace with standard ClobTypeHandler or StringTypeHandler for PostgreSQL TEXT"
)
public class OracleClobTypeHandler extends BaseTypeHandler<String> {

    private static final Logger log = LoggerFactory.getLogger(OracleClobTypeHandler.class);

    /**
     * Writes a String value into an Oracle CLOB column.
     *
     * <p>ORACLE-DEP [ORM_DATA_ACCESS:ORACLE_CLOB_HANDLER] oracle.sql.CLOB write path
     */
    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, String parameter, JdbcType jdbcType)
            throws SQLException {
        // ORACLE-DEP [ORM_DATA_ACCESS:ORACLE_CLOB_HANDLER] Set CLOB via Reader for large content
        try {
            ps.setCharacterStream(i, new StringReader(parameter), parameter.length()); // ORACLE-DEP [ORM_DATA_ACCESS:ORACLE_CLOB_HANDLER]
        } catch (Exception e) {
            log.warn("Failed to set CLOB via character stream, falling back to setString: {}", e.getMessage());
            ps.setString(i, parameter);
        }
    }

    /**
     * Reads an Oracle CLOB column by column name into a String.
     *
     * <p>ORACLE-DEP [ORM_DATA_ACCESS:ORACLE_CLOB_HANDLER] oracle.sql.CLOB read path
     */
    @Override
    public String getNullableResult(ResultSet rs, String columnName) throws SQLException {
        return readClob(rs.getClob(columnName)); // ORACLE-DEP [ORM_DATA_ACCESS:ORACLE_CLOB_HANDLER]
    }

    /**
     * Reads an Oracle CLOB column by column index into a String.
     *
     * <p>ORACLE-DEP [ORM_DATA_ACCESS:ORACLE_CLOB_HANDLER] oracle.sql.CLOB read path by index
     */
    @Override
    public String getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        return readClob(rs.getClob(columnIndex)); // ORACLE-DEP [ORM_DATA_ACCESS:ORACLE_CLOB_HANDLER]
    }

    /**
     * Reads a CLOB OUT parameter from a CallableStatement.
     * ORACLE-DEP [ORM_DATA_ACCESS:ORACLE_CLOB_HANDLER] CallableStatement CLOB OUT param
     */
    @Override
    public String getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        return readClob(cs.getClob(columnIndex)); // ORACLE-DEP [ORM_DATA_ACCESS:ORACLE_CLOB_HANDLER]
    }

    /**
     * Materializes a {@code java.sql.Clob} (Oracle's {@code oracle.sql.CLOB} at runtime)
     * into a Java String.
     *
     * <p>ORACLE-DEP [ORM_DATA_ACCESS:ORACLE_CLOB_HANDLER] Oracle CLOB materialization
     */
    private String readClob(java.sql.Clob clob) throws SQLException {
        if (clob == null) {
            return null;
        }
        // ORACLE-DEP [ORM_DATA_ACCESS:ORACLE_CLOB_HANDLER] Read CLOB via character stream
        try (Reader reader = clob.getCharacterStream()) { // ORACLE-DEP [ORM_DATA_ACCESS:ORACLE_CLOB_HANDLER]
            StringBuilder sb = new StringBuilder();
            char[] buffer = new char[4096];
            int charsRead;
            while ((charsRead = reader.read(buffer)) != -1) {
                sb.append(buffer, 0, charsRead);
            }
            return sb.toString();
        } catch (java.io.IOException e) {
            throw new SQLException("Failed to read Oracle CLOB content", e);
        }
    }
}
