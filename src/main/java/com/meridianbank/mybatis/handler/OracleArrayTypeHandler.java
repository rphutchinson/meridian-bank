package com.meridianbank.mybatis.handler;

import com.meridianbank.annotation.AutomationLevel;
import com.meridianbank.annotation.DependencyDomain;
import com.meridianbank.annotation.MigrationComplexity;
import com.meridianbank.annotation.OracleDependency;
import oracle.sql.ARRAY;
import oracle.sql.ArrayDescriptor;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedJdbcTypes;
import org.apache.ibatis.type.MappedTypes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Array;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * MyBatis TypeHandler for Oracle ARRAY / TABLE types (NUMBER_TABLE_TYPE, VARCHAR2_TABLE_TYPE).
 *
 * <p>Uses {@code oracle.sql.ARRAY} and {@code oracle.sql.ArrayDescriptor} directly.
 * These Oracle-proprietary classes are needed to bind Java collections to Oracle
 * PL/SQL TABLE-type parameters and read back Oracle ARRAY results.
 *
 * <p>Migration: Remove entirely — PostgreSQL uses native arrays or JSON for multi-value
 * parameters; no Oracle ARRAY descriptor mechanism is needed.
 */
@MappedTypes(Long[].class)
@MappedJdbcTypes(JdbcType.ARRAY)
@OracleDependency(
    domain          = DependencyDomain.ORM_DATA_ACCESS,
    category        = "ORACLE_ARRAY_HANDLER",
    description     = "Custom MyBatis TypeHandler using oracle.sql.ARRAY and ArrayDescriptor",
    complexity      = MigrationComplexity.MEDIUM,
    automationLevel = AutomationLevel.PARTIAL,
    migrationTarget = "Remove; use native PostgreSQL ARRAY or unnest() for bulk ID operations"
)
public class OracleArrayTypeHandler extends BaseTypeHandler<Long[]> {

    private static final Logger log = LoggerFactory.getLogger(OracleArrayTypeHandler.class);

    // ORACLE-DEP [ORM_DATA_ACCESS:ORACLE_ARRAY_HANDLER] Oracle NUMBER_TABLE_TYPE name
    private static final String NUMBER_TABLE_TYPE = "NUMBER_TABLE_TYPE"; // ORACLE-DEP [ORM_DATA_ACCESS:ORACLE_ARRAY_HANDLER]

    /**
     * Creates an Oracle ARRAY from a Long[] and binds it to a PreparedStatement parameter.
     *
     * <p>ORACLE-DEP [ORM_DATA_ACCESS:ORACLE_ARRAY_HANDLER] oracle.sql.ARRAY creation and binding
     */
    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, Long[] parameter, JdbcType jdbcType)
            throws SQLException {
        Connection conn = ps.getConnection();
        // ORACLE-DEP [ORM_DATA_ACCESS:ORACLE_ARRAY_HANDLER] ArrayDescriptor for Oracle type lookup
        ArrayDescriptor descriptor = ArrayDescriptor.createDescriptor(NUMBER_TABLE_TYPE, conn); // ORACLE-DEP [ORM_DATA_ACCESS:ORACLE_ARRAY_HANDLER]
        // ORACLE-DEP [ORM_DATA_ACCESS:ORACLE_ARRAY_HANDLER] oracle.sql.ARRAY creation
        ARRAY oracleArray = new ARRAY(descriptor, conn, parameter); // ORACLE-DEP [ORM_DATA_ACCESS:ORACLE_ARRAY_HANDLER]
        ps.setArray(i, oracleArray);
    }

    /**
     * Reads an Oracle ARRAY column by name and converts to Long[].
     * ORACLE-DEP [ORM_DATA_ACCESS:ORACLE_ARRAY_HANDLER] Read Oracle ARRAY by column name
     */
    @Override
    public Long[] getNullableResult(ResultSet rs, String columnName) throws SQLException {
        return toArray(rs.getArray(columnName)); // ORACLE-DEP [ORM_DATA_ACCESS:ORACLE_ARRAY_HANDLER]
    }

    /**
     * Reads an Oracle ARRAY column by index and converts to Long[].
     * ORACLE-DEP [ORM_DATA_ACCESS:ORACLE_ARRAY_HANDLER] Read Oracle ARRAY by column index
     */
    @Override
    public Long[] getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        return toArray(rs.getArray(columnIndex)); // ORACLE-DEP [ORM_DATA_ACCESS:ORACLE_ARRAY_HANDLER]
    }

    /**
     * Reads an Oracle ARRAY OUT parameter from a CallableStatement.
     * ORACLE-DEP [ORM_DATA_ACCESS:ORACLE_ARRAY_HANDLER] CallableStatement ARRAY OUT param
     */
    @Override
    public Long[] getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        return toArray(cs.getArray(columnIndex)); // ORACLE-DEP [ORM_DATA_ACCESS:ORACLE_ARRAY_HANDLER]
    }

    /**
     * Converts a {@code java.sql.Array} (Oracle {@code oracle.sql.ARRAY} at runtime) to Long[].
     * ORACLE-DEP [ORM_DATA_ACCESS:ORACLE_ARRAY_HANDLER] Oracle ARRAY materialization
     */
    private Long[] toArray(Array array) throws SQLException {
        if (array == null) {
            return null;
        }
        // ORACLE-DEP [ORM_DATA_ACCESS:ORACLE_ARRAY_HANDLER] getArray() materializes Oracle ARRAY
        Object[] rawArray = (Object[]) array.getArray(); // ORACLE-DEP [ORM_DATA_ACCESS:ORACLE_ARRAY_HANDLER]
        List<Long> result = new ArrayList<>(rawArray.length);
        for (Object o : rawArray) {
            if (o instanceof Number) {
                result.add(((Number) o).longValue());
            }
        }
        return result.toArray(new Long[0]);
    }
}
