package com.meridianbank.oracle;

import com.meridianbank.annotation.AutomationLevel;
import com.meridianbank.annotation.DependencyDomain;
import com.meridianbank.annotation.MigrationComplexity;
import com.meridianbank.annotation.OracleDependency;
import com.meridianbank.dto.CustomerDTO;
import com.meridianbank.dto.TransactionDTO;
import oracle.jdbc.OracleConnection;
import oracle.jdbc.OraclePreparedStatement;
import oracle.sql.STRUCT;
import oracle.sql.StructDescriptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.ZoneOffset;

/**
 * Converts between Java DTOs and Oracle STRUCT types.
 *
 * Oracle STRUCT is the Java representation of an Oracle object type (created with
 * CREATE TYPE ... AS OBJECT). STRUCTs allow passing composite data to PL/SQL
 * procedures without multiple individual parameters.
 *
 * Oracle object types used:
 *   MERIDIAN.ADDRESS_TYPE            — (street, city, state, zip_code, country)
 *   MERIDIAN.TRANSACTION_RECORD_TYPE — (transaction_id, account_id, amount,
 *                                       transaction_type, description, transaction_date, status)
 *
 * ORACLE-DEP [ORACLE_JAVA_API:STRUCT_TYPE] oracle.sql.STRUCT and StructDescriptor
 */
@Component
// ORACLE-DEP [ORACLE_JAVA_API:STRUCT_TYPE] Converts Java DTOs to/from Oracle STRUCT types
@OracleDependency(
        domain = DependencyDomain.ORACLE_JAVA_API,
        category = "STRUCT_TYPE",
        description = "Converts Java DTOs to/from Oracle STRUCT types for PL/SQL procedure calls",
        complexity = MigrationComplexity.HIGH,
        automationLevel = AutomationLevel.PARTIAL,
        migrationTarget = "Replace Oracle STRUCT parameters with individual JDBC parameters or JSON"
)
public class OracleStructHelper {

    private static final Logger log = LoggerFactory.getLogger(OracleStructHelper.class);

    private static final String ADDRESS_TYPE     = "MERIDIAN.ADDRESS_TYPE";
    private static final String TRANSACTION_TYPE = "MERIDIAN.TRANSACTION_RECORD_TYPE";

    // -----------------------------------------------------------------------
    // ADDRESS_TYPE conversions
    // -----------------------------------------------------------------------

    /**
     * Converts a CustomerDTO's address fields to an Oracle ADDRESS_TYPE STRUCT.
     *
     * Attribute positions are positional (not named) — order must match the CREATE TYPE.
     *
     * ORACLE-DEP [ORACLE_JAVA_API:STRUCT_TYPE] oracle.sql.STRUCT for ADDRESS_TYPE
     */
    @OracleDependency(
            domain = DependencyDomain.ORACLE_JAVA_API,
            category = "STRUCT_TYPE",
            description = "Creates Oracle ADDRESS_TYPE STRUCT from CustomerDTO address fields",
            complexity = MigrationComplexity.HIGH,
            automationLevel = AutomationLevel.PARTIAL,
            migrationTarget = "Replace with individual address parameters (5 columns) or JSON object"
    )
    public STRUCT addressDTOtoStruct(OracleConnection conn, CustomerDTO dto) throws SQLException {
        // ORACLE-DEP [ORACLE_JAVA_API:STRUCT_TYPE] StructDescriptor.createDescriptor for MERIDIAN.ADDRESS_TYPE
        StructDescriptor desc = StructDescriptor.createDescriptor(ADDRESS_TYPE, conn);
        Object[] attributes = {
            dto.getStreet(),    // position 1: street
            dto.getCity(),      // position 2: city
            dto.getState(),     // position 3: state
            dto.getZipCode(),   // position 4: zip_code
            dto.getCountry()    // position 5: country
        };
        // ORACLE-DEP [ORACLE_JAVA_API:STRUCT_TYPE] new oracle.sql.STRUCT with positional attribute array
        STRUCT struct = new STRUCT(desc, conn, attributes);
        log.debug("Created ADDRESS_TYPE STRUCT from CustomerDTO customerId={}", dto.getCustomerId());
        return struct;
    }

    /**
     * Converts an Oracle ADDRESS_TYPE STRUCT back to CustomerDTO address fields.
     *
     * ORACLE-DEP [ORACLE_JAVA_API:STRUCT_TYPE] oracle.sql.STRUCT.getAttributes() returns Object[]
     */
    @OracleDependency(
            domain = DependencyDomain.ORACLE_JAVA_API,
            category = "STRUCT_TYPE",
            description = "Converts Oracle ADDRESS_TYPE STRUCT back to CustomerDTO address fields",
            complexity = MigrationComplexity.MEDIUM,
            automationLevel = AutomationLevel.FULL,
            migrationTarget = "Replace with standard ResultSet column reads after removing STRUCT return type"
    )
    public CustomerDTO structToAddressDTO(STRUCT struct) throws SQLException {
        if (struct == null) {
            return null;
        }
        // ORACLE-DEP [ORACLE_JAVA_API:STRUCT_TYPE] STRUCT.getAttributes() returns Object[] in type-definition order
        Object[] attributes = struct.getAttributes();
        CustomerDTO dto = new CustomerDTO();
        if (attributes.length >= 1) dto.setStreet(toStr(attributes[0]));
        if (attributes.length >= 2) dto.setCity(toStr(attributes[1]));
        if (attributes.length >= 3) dto.setState(toStr(attributes[2]));
        if (attributes.length >= 4) dto.setZipCode(toStr(attributes[3]));
        if (attributes.length >= 5) dto.setCountry(toStr(attributes[4]));
        log.debug("Converted ADDRESS_TYPE STRUCT to CustomerDTO address fields");
        return dto;
    }

    // -----------------------------------------------------------------------
    // TRANSACTION_RECORD_TYPE conversions
    // -----------------------------------------------------------------------

    /**
     * Converts a TransactionDTO to an Oracle TRANSACTION_RECORD_TYPE STRUCT.
     *
     * ORACLE-DEP [ORACLE_JAVA_API:STRUCT_TYPE] oracle.sql.STRUCT for TRANSACTION_RECORD_TYPE
     */
    @OracleDependency(
            domain = DependencyDomain.ORACLE_JAVA_API,
            category = "STRUCT_TYPE",
            description = "Creates Oracle TRANSACTION_RECORD_TYPE STRUCT from TransactionDTO",
            complexity = MigrationComplexity.HIGH,
            automationLevel = AutomationLevel.PARTIAL,
            migrationTarget = "Replace with individual transaction parameters or JSON payload"
    )
    public STRUCT transactionDTOtoStruct(OracleConnection conn, TransactionDTO tx) throws SQLException {
        // ORACLE-DEP [ORACLE_JAVA_API:STRUCT_TYPE] StructDescriptor for MERIDIAN.TRANSACTION_RECORD_TYPE
        StructDescriptor desc = StructDescriptor.createDescriptor(TRANSACTION_TYPE, conn);
        Timestamp txDate = null;
        if (tx.getTransactionDate() != null) {
            txDate = Timestamp.from(tx.getTransactionDate().toInstant());
        }
        Object[] attributes = {
            tx.getTransactionId() != null ? BigDecimal.valueOf(tx.getTransactionId()) : null,
            tx.getAccountId()     != null ? BigDecimal.valueOf(tx.getAccountId())     : null,
            tx.getAmount(),
            tx.getTransactionType(),
            tx.getDescription(),
            txDate,
            tx.getStatus()
        };
        // ORACLE-DEP [ORACLE_JAVA_API:STRUCT_TYPE] new oracle.sql.STRUCT for transaction record
        STRUCT struct = new STRUCT(desc, conn, attributes);
        log.debug("Created TRANSACTION_RECORD_TYPE STRUCT for txId={}", tx.getTransactionId());
        return struct;
    }

    /**
     * Converts an Oracle TRANSACTION_RECORD_TYPE STRUCT back to a TransactionDTO.
     *
     * ORACLE-DEP [ORACLE_JAVA_API:STRUCT_TYPE] oracle.sql.STRUCT.getAttributes() for transaction record
     */
    @OracleDependency(
            domain = DependencyDomain.ORACLE_JAVA_API,
            category = "STRUCT_TYPE",
            description = "Converts Oracle TRANSACTION_RECORD_TYPE STRUCT back to TransactionDTO",
            complexity = MigrationComplexity.MEDIUM,
            automationLevel = AutomationLevel.FULL,
            migrationTarget = "Replace with standard ResultSet column reads"
    )
    public TransactionDTO structToTransactionDTO(STRUCT struct) throws SQLException {
        if (struct == null) {
            return null;
        }
        // ORACLE-DEP [ORACLE_JAVA_API:STRUCT_TYPE] STRUCT.getAttributes() for TRANSACTION_RECORD_TYPE
        Object[] attributes = struct.getAttributes();
        TransactionDTO dto = new TransactionDTO();
        if (attributes.length >= 1 && attributes[0] != null) {
            dto.setTransactionId(toBigDecimal(attributes[0]).longValue());
        }
        if (attributes.length >= 2 && attributes[1] != null) {
            dto.setAccountId(toBigDecimal(attributes[1]).longValue());
        }
        if (attributes.length >= 3 && attributes[2] != null) {
            dto.setAmount(toBigDecimal(attributes[2]));
        }
        if (attributes.length >= 4) dto.setTransactionType(toStr(attributes[3]));
        if (attributes.length >= 5) dto.setDescription(toStr(attributes[4]));
        if (attributes.length >= 6 && attributes[5] instanceof Timestamp) {
            Timestamp ts = (Timestamp) attributes[5];
            dto.setTransactionDate(ts.toInstant().atOffset(ZoneOffset.UTC));
        }
        if (attributes.length >= 7) dto.setStatus(toStr(attributes[6]));
        return dto;
    }

    // -----------------------------------------------------------------------
    // PreparedStatement binding
    // -----------------------------------------------------------------------

    /**
     * Binds an Oracle STRUCT to a PreparedStatement using OraclePreparedStatement.setSTRUCT().
     *
     * ORACLE-DEP [ORACLE_JAVA_API:ORACLE_PREPARED_STMT] OraclePreparedStatement.setSTRUCT()
     */
    @OracleDependency(
            domain = DependencyDomain.ORACLE_JAVA_API,
            category = "ORACLE_PREPARED_STMT",
            description = "Binds Oracle STRUCT to parameter slot using OraclePreparedStatement.setSTRUCT()",
            complexity = MigrationComplexity.HIGH,
            automationLevel = AutomationLevel.PARTIAL,
            migrationTarget = "Replace with standard setObject() for PostgreSQL composite type parameters"
    )
    public void bindStructParameter(PreparedStatement ps, int paramIndex, STRUCT struct)
            throws SQLException {
        // ORACLE-DEP [ORACLE_JAVA_API:ORACLE_PREPARED_STMT] Cast to OraclePreparedStatement to call setSTRUCT()
        if (ps instanceof OraclePreparedStatement) {
            OraclePreparedStatement ops = (OraclePreparedStatement) ps;
            // ORACLE-DEP [ORACLE_JAVA_API:ORACLE_PREPARED_STMT] OraclePreparedStatement.setSTRUCT() — Oracle-specific
            ops.setSTRUCT(paramIndex, struct);
            log.debug("Bound Oracle STRUCT to parameter index {}", paramIndex);
        } else {
            log.warn("PreparedStatement is not OraclePreparedStatement ({}); using setObject fallback",
                    ps.getClass().getName());
            ps.setObject(paramIndex, struct);
        }
    }

    // -----------------------------------------------------------------------
    // Private helpers
    // -----------------------------------------------------------------------

    private String toStr(Object value) {
        return value != null ? value.toString() : null;
    }

    private BigDecimal toBigDecimal(Object value) {
        if (value instanceof BigDecimal) return (BigDecimal) value;
        if (value instanceof Number) return BigDecimal.valueOf(((Number) value).doubleValue());
        return new BigDecimal(value.toString());
    }
}
