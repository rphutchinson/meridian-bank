package com.meridianbank.mybatis.handler;

import com.meridianbank.annotation.AutomationLevel;
import com.meridianbank.annotation.DependencyDomain;
import com.meridianbank.annotation.MigrationComplexity;
import com.meridianbank.annotation.OracleDependency;
import oracle.sql.STRUCT;
import oracle.sql.StructDescriptor;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedJdbcTypes;
import org.apache.ibatis.type.MappedTypes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Struct;

/**
 * MyBatis TypeHandler for Oracle STRUCT (object type) columns.
 *
 * <p>Uses {@code oracle.sql.STRUCT} and {@code oracle.sql.StructDescriptor} to map
 * Oracle user-defined types (e.g. {@code ADDRESS_TYPE}) to/from Object[].
 * This is used for composite types in stored procedure parameters and columns.
 *
 * <p>Migration: Remove entirely. PostgreSQL composite types require
 * different handling (PGobject), or restructure to use flat columns.
 */
@MappedTypes(Object[].class)
@MappedJdbcTypes(JdbcType.STRUCT)
@OracleDependency(
    domain          = DependencyDomain.ORM_DATA_ACCESS,
    category        = "ORACLE_STRUCT_HANDLER",
    description     = "Custom MyBatis TypeHandler using oracle.sql.STRUCT and StructDescriptor for Oracle UDTs",
    complexity      = MigrationComplexity.HIGH,
    automationLevel = AutomationLevel.PARTIAL,
    migrationTarget = "Remove; flatten Oracle STRUCT to individual columns or use PostgreSQL composite type with PGobject"
)
public class OracleStructTypeHandler extends BaseTypeHandler<Object[]> {

    private static final Logger log = LoggerFactory.getLogger(OracleStructTypeHandler.class);

    // ORACLE-DEP [ORM_DATA_ACCESS:ORACLE_STRUCT_HANDLER] Oracle ADDRESS_TYPE UDT name
    private static final String ADDRESS_TYPE_NAME = "ADDRESS_TYPE"; // ORACLE-DEP [ORM_DATA_ACCESS:ORACLE_STRUCT_HANDLER]

    /**
     * Creates an Oracle STRUCT and binds it to a PreparedStatement parameter.
     *
     * <p>ORACLE-DEP [ORM_DATA_ACCESS:ORACLE_STRUCT_HANDLER] oracle.sql.STRUCT creation via StructDescriptor
     */
    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, Object[] parameter, JdbcType jdbcType)
            throws SQLException {
        Connection conn = ps.getConnection();
        // ORACLE-DEP [ORM_DATA_ACCESS:ORACLE_STRUCT_HANDLER] StructDescriptor lookup for Oracle UDT
        StructDescriptor descriptor = StructDescriptor.createDescriptor(ADDRESS_TYPE_NAME, conn); // ORACLE-DEP [ORM_DATA_ACCESS:ORACLE_STRUCT_HANDLER]
        // ORACLE-DEP [ORM_DATA_ACCESS:ORACLE_STRUCT_HANDLER] oracle.sql.STRUCT creation
        STRUCT oracleStruct = new STRUCT(descriptor, conn, parameter); // ORACLE-DEP [ORM_DATA_ACCESS:ORACLE_STRUCT_HANDLER]
        ps.setObject(i, oracleStruct);
    }

    /**
     * Reads an Oracle STRUCT column by name and returns as Object[].
     * ORACLE-DEP [ORM_DATA_ACCESS:ORACLE_STRUCT_HANDLER] Read Oracle STRUCT by column name
     */
    @Override
    public Object[] getNullableResult(ResultSet rs, String columnName) throws SQLException {
        return toObjectArray(rs.getObject(columnName)); // ORACLE-DEP [ORM_DATA_ACCESS:ORACLE_STRUCT_HANDLER]
    }

    /**
     * Reads an Oracle STRUCT column by index and returns as Object[].
     * ORACLE-DEP [ORM_DATA_ACCESS:ORACLE_STRUCT_HANDLER] Read Oracle STRUCT by column index
     */
    @Override
    public Object[] getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        return toObjectArray(rs.getObject(columnIndex)); // ORACLE-DEP [ORM_DATA_ACCESS:ORACLE_STRUCT_HANDLER]
    }

    /**
     * Reads an Oracle STRUCT OUT parameter from a CallableStatement.
     * ORACLE-DEP [ORM_DATA_ACCESS:ORACLE_STRUCT_HANDLER] CallableStatement STRUCT OUT param
     */
    @Override
    public Object[] getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        return toObjectArray(cs.getObject(columnIndex)); // ORACLE-DEP [ORM_DATA_ACCESS:ORACLE_STRUCT_HANDLER]
    }

    /**
     * Converts an Oracle {@code STRUCT} to an Object[] by calling {@code getAttributes()}.
     * ORACLE-DEP [ORM_DATA_ACCESS:ORACLE_STRUCT_HANDLER] Oracle STRUCT getAttributes() materialization
     */
    private Object[] toObjectArray(Object structObj) throws SQLException {
        if (structObj == null) {
            return null;
        }
        if (structObj instanceof STRUCT) {
            // ORACLE-DEP [ORM_DATA_ACCESS:ORACLE_STRUCT_HANDLER] oracle.sql.STRUCT.getAttributes()
            return ((STRUCT) structObj).getAttributes(); // ORACLE-DEP [ORM_DATA_ACCESS:ORACLE_STRUCT_HANDLER]
        } else if (structObj instanceof Struct) {
            return ((Struct) structObj).getAttributes();
        }
        log.warn("Expected oracle.sql.STRUCT but got: {}", structObj.getClass().getName());
        return null;
    }
}
