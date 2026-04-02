package com.meridianbank.oracle;

import com.meridianbank.annotation.AutomationLevel;
import com.meridianbank.annotation.DependencyDomain;
import com.meridianbank.annotation.MigrationComplexity;
import com.meridianbank.annotation.OracleDependency;
import oracle.jdbc.OracleConnection;
import oracle.jdbc.OraclePreparedStatement;
import oracle.sql.ARRAY;
import oracle.sql.ArrayDescriptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Converts between Java collections and Oracle ARRAY types.
 *
 * Oracle ARRAY types (TABLE OF types in PL/SQL) allow passing collections to stored
 * procedures and receiving them back. Each Oracle array type must be declared in the
 * database schema before use.
 *
 * Types used:
 *   MERIDIAN.NUMBER_TABLE_TYPE   — TABLE OF NUMBER
 *   MERIDIAN.VARCHAR2_TABLE_TYPE — TABLE OF VARCHAR2(4000)
 *
 * ORACLE-DEP [ORACLE_JAVA_API:ARRAY_TYPE] oracle.sql.ARRAY and ArrayDescriptor
 */
@Component
// ORACLE-DEP [ORACLE_JAVA_API:ARRAY_TYPE] Converts Java collections to/from Oracle ARRAY types
@OracleDependency(
        domain = DependencyDomain.ORACLE_JAVA_API,
        category = "ARRAY_TYPE",
        description = "Converts Java collections to/from Oracle ARRAY types for bulk procedure calls",
        complexity = MigrationComplexity.HIGH,
        automationLevel = AutomationLevel.PARTIAL,
        migrationTarget = "Replace with PostgreSQL ARRAY parameters or unnest() batch patterns"
)
public class OracleArrayHelper {

    private static final Logger log = LoggerFactory.getLogger(OracleArrayHelper.class);

    private static final String NUMBER_TABLE_TYPE   = "MERIDIAN.NUMBER_TABLE_TYPE";
    private static final String VARCHAR2_TABLE_TYPE = "MERIDIAN.VARCHAR2_TABLE_TYPE";

    /**
     * Converts a List of Long IDs to an Oracle NUMBER_TABLE_TYPE ARRAY.
     *
     * ORACLE-DEP [ORACLE_JAVA_API:ARRAY_TYPE] oracle.sql.ARRAY with NUMBER_TABLE_TYPE
     */
    @OracleDependency(
            domain = DependencyDomain.ORACLE_JAVA_API,
            category = "ARRAY_TYPE",
            description = "Converts List<Long> to Oracle ARRAY of type NUMBER_TABLE_TYPE",
            complexity = MigrationComplexity.HIGH,
            automationLevel = AutomationLevel.PARTIAL,
            migrationTarget = "Replace with BIGINT[] array parameter in PostgreSQL stored function"
    )
    public ARRAY toOracleNumberArray(OracleConnection conn, List<Long> ids) throws SQLException {
        if (ids == null || ids.isEmpty()) {
            throw new IllegalArgumentException("IDs list must not be null or empty");
        }
        // ORACLE-DEP [ORACLE_JAVA_API:ARRAY_TYPE] ArrayDescriptor.createDescriptor for NUMBER_TABLE_TYPE
        ArrayDescriptor desc = ArrayDescriptor.createDescriptor(NUMBER_TABLE_TYPE, conn);
        Long[] elements = ids.toArray(new Long[0]);
        // ORACLE-DEP [ORACLE_JAVA_API:ARRAY_TYPE] new oracle.sql.ARRAY wrapping Long[] as Oracle NUMBER collection
        ARRAY array = new ARRAY(desc, conn, elements);
        log.debug("Created NUMBER_TABLE_TYPE ARRAY with {} elements", ids.size());
        return array;
    }

    /**
     * Converts a List of Strings to an Oracle VARCHAR2_TABLE_TYPE ARRAY.
     *
     * ORACLE-DEP [ORACLE_JAVA_API:ARRAY_TYPE] oracle.sql.ARRAY with VARCHAR2_TABLE_TYPE
     */
    @OracleDependency(
            domain = DependencyDomain.ORACLE_JAVA_API,
            category = "ARRAY_TYPE",
            description = "Converts List<String> to Oracle ARRAY of type VARCHAR2_TABLE_TYPE",
            complexity = MigrationComplexity.HIGH,
            automationLevel = AutomationLevel.PARTIAL,
            migrationTarget = "Replace with TEXT[] array parameter in PostgreSQL stored function"
    )
    public ARRAY toOracleVarcharArray(OracleConnection conn, List<String> values) throws SQLException {
        if (values == null || values.isEmpty()) {
            throw new IllegalArgumentException("Values list must not be null or empty");
        }
        // ORACLE-DEP [ORACLE_JAVA_API:ARRAY_TYPE] ArrayDescriptor.createDescriptor for VARCHAR2_TABLE_TYPE
        ArrayDescriptor desc = ArrayDescriptor.createDescriptor(VARCHAR2_TABLE_TYPE, conn);
        String[] elements = values.toArray(new String[0]);
        // ORACLE-DEP [ORACLE_JAVA_API:ARRAY_TYPE] new oracle.sql.ARRAY wrapping String[] as Oracle VARCHAR2 collection
        ARRAY array = new ARRAY(desc, conn, elements);
        log.debug("Created VARCHAR2_TABLE_TYPE ARRAY with {} elements", values.size());
        return array;
    }

    /**
     * Converts an Oracle NUMBER_TABLE_TYPE ARRAY back to a Java List of Long values.
     *
     * Oracle NUMBER columns are returned as BigDecimal by the JDBC driver.
     *
     * ORACLE-DEP [ORACLE_JAVA_API:ARRAY_TYPE] oracle.sql.ARRAY.getArray() returns Object[]
     */
    @OracleDependency(
            domain = DependencyDomain.ORACLE_JAVA_API,
            category = "ARRAY_TYPE",
            description = "Converts Oracle NUMBER_TABLE_TYPE ARRAY back to List<Long>",
            complexity = MigrationComplexity.MEDIUM,
            automationLevel = AutomationLevel.FULL,
            migrationTarget = "Replace with standard ResultSet array handling for PostgreSQL BIGINT[]"
    )
    public List<Long> fromOracleNumberArray(ARRAY oracleArray) throws SQLException {
        if (oracleArray == null) {
            return new ArrayList<>();
        }
        // ORACLE-DEP [ORACLE_JAVA_API:ARRAY_TYPE] ARRAY.getArray() returns Object[] with BigDecimal elements
        Object[] rawElements = (Object[]) oracleArray.getArray();
        List<Long> result = new ArrayList<>(rawElements.length);
        for (Object element : rawElements) {
            if (element == null) {
                result.add(null);
            } else if (element instanceof BigDecimal) {
                // ORACLE-DEP [ORACLE_JAVA_API:ARRAY_TYPE] Oracle NUMBER returned as BigDecimal — must convert to Long
                result.add(((BigDecimal) element).longValueExact());
            } else if (element instanceof Long) {
                result.add((Long) element);
            } else {
                result.add(Long.parseLong(element.toString()));
            }
        }
        log.debug("Converted NUMBER_TABLE_TYPE ARRAY to List<Long> with {} elements", result.size());
        return result;
    }

    /**
     * Converts an Oracle VARCHAR2_TABLE_TYPE ARRAY back to a Java List of Strings.
     *
     * ORACLE-DEP [ORACLE_JAVA_API:ARRAY_TYPE] oracle.sql.ARRAY.getArray() returns Object[] of String
     */
    @OracleDependency(
            domain = DependencyDomain.ORACLE_JAVA_API,
            category = "ARRAY_TYPE",
            description = "Converts Oracle VARCHAR2_TABLE_TYPE ARRAY back to List<String>",
            complexity = MigrationComplexity.MEDIUM,
            automationLevel = AutomationLevel.FULL,
            migrationTarget = "Replace with standard ResultSet array handling for PostgreSQL TEXT[]"
    )
    public List<String> fromOracleVarcharArray(ARRAY oracleArray) throws SQLException {
        if (oracleArray == null) {
            return new ArrayList<>();
        }
        // ORACLE-DEP [ORACLE_JAVA_API:ARRAY_TYPE] ARRAY.getArray() returns Object[] with String elements
        Object[] rawElements = (Object[]) oracleArray.getArray();
        List<String> result = new ArrayList<>(rawElements.length);
        for (Object element : rawElements) {
            result.add(element != null ? element.toString() : null);
        }
        log.debug("Converted VARCHAR2_TABLE_TYPE ARRAY to List<String> with {} elements", result.size());
        return result;
    }

    /**
     * Binds an Oracle ARRAY to a PreparedStatement parameter using the Oracle-specific
     * OraclePreparedStatement.setARRAY() method.
     *
     * Standard JDBC does not support Oracle ARRAY binding directly.
     *
     * ORACLE-DEP [ORACLE_JAVA_API:ORACLE_PREPARED_STMT] OraclePreparedStatement.setARRAY()
     */
    @OracleDependency(
            domain = DependencyDomain.ORACLE_JAVA_API,
            category = "ORACLE_PREPARED_STMT",
            description = "Binds Oracle ARRAY to parameter slot using OraclePreparedStatement.setARRAY()",
            complexity = MigrationComplexity.HIGH,
            automationLevel = AutomationLevel.PARTIAL,
            migrationTarget = "Replace with setArray() or setObject() for PostgreSQL ARRAY parameters"
    )
    public void bindArrayParameter(PreparedStatement ps, int paramIndex, ARRAY array)
            throws SQLException {
        // ORACLE-DEP [ORACLE_JAVA_API:ORACLE_PREPARED_STMT] Cast to OraclePreparedStatement to call setARRAY()
        if (ps instanceof OraclePreparedStatement) {
            OraclePreparedStatement ops = (OraclePreparedStatement) ps;
            // ORACLE-DEP [ORACLE_JAVA_API:ORACLE_PREPARED_STMT] OraclePreparedStatement.setARRAY() — Oracle-specific
            ops.setARRAY(paramIndex, array);
            log.debug("Bound Oracle ARRAY to parameter index {}", paramIndex);
        } else {
            log.warn("PreparedStatement is not OraclePreparedStatement ({}); using setObject fallback",
                    ps.getClass().getName());
            ps.setObject(paramIndex, array);
        }
    }
}
