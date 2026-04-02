package com.meridianbank.oracle;

import com.meridianbank.annotation.AutomationLevel;
import com.meridianbank.annotation.DependencyDomain;
import com.meridianbank.annotation.MigrationComplexity;
import com.meridianbank.annotation.OracleDependency;
import com.meridianbank.dto.CustomerDTO;
import com.meridianbank.dto.TransactionDTO;
import oracle.jdbc.OracleConnection;
import oracle.sql.ARRAY;
import oracle.sql.ArrayDescriptor;
import oracle.sql.STRUCT;
import oracle.sql.StructDescriptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Factory for creating Oracle user-defined types (STRUCT, ARRAY).
 *
 * Caches StructDescriptor and ArrayDescriptor instances since creation is expensive —
 * each descriptor creation requires a round-trip to the Oracle data dictionary.
 *
 * Oracle-specific types used in Meridian Bank:
 *   MERIDIAN.ADDRESS_TYPE            — composite address STRUCT
 *   MERIDIAN.NUMBER_TABLE_TYPE       — collection of NUMBER values (ARRAY)
 *   MERIDIAN.VARCHAR2_TABLE_TYPE     — collection of VARCHAR2 values (ARRAY)
 *   MERIDIAN.TRANSACTION_RECORD_TYPE — transaction detail STRUCT
 *
 * ORACLE-DEP [ORACLE_JAVA_API:STRUCT_TYPE] oracle.sql.STRUCT and StructDescriptor usage
 * ORACLE-DEP [ORACLE_JAVA_API:ARRAY_TYPE]  oracle.sql.ARRAY and ArrayDescriptor usage
 */
@Component
// ORACLE-DEP [ORACLE_JAVA_API:STRUCT_TYPE] Factory class for Oracle STRUCT and ARRAY user-defined types
@OracleDependency(
        domain = DependencyDomain.ORACLE_JAVA_API,
        category = "STRUCT_TYPE",
        description = "Factory class for Oracle STRUCT and ARRAY user-defined types with descriptor caching",
        complexity = MigrationComplexity.HIGH,
        automationLevel = AutomationLevel.PARTIAL,
        migrationTarget = "Replace Oracle UDTs with application-layer DTOs; pass as JSON or individual parameters"
)
public class OracleTypeFactory {

    private static final Logger log = LoggerFactory.getLogger(OracleTypeFactory.class);

    public static final String ADDRESS_TYPE        = "MERIDIAN.ADDRESS_TYPE";
    public static final String NUMBER_TABLE_TYPE   = "MERIDIAN.NUMBER_TABLE_TYPE";
    public static final String VARCHAR2_TABLE_TYPE = "MERIDIAN.VARCHAR2_TABLE_TYPE";
    public static final String TRANSACTION_TYPE    = "MERIDIAN.TRANSACTION_RECORD_TYPE";

    // ORACLE-DEP [ORACLE_JAVA_API:STRUCT_TYPE] StructDescriptor cache — avoids repeated Oracle data dict lookups
    private final Map<String, StructDescriptor> structDescriptorCache = new ConcurrentHashMap<>();
    // ORACLE-DEP [ORACLE_JAVA_API:ARRAY_TYPE] ArrayDescriptor cache — avoids repeated Oracle data dict lookups
    private final Map<String, ArrayDescriptor>  arrayDescriptorCache  = new ConcurrentHashMap<>();

    /**
     * Creates an Oracle ADDRESS_TYPE STRUCT from a CustomerDTO's address fields.
     *
     * Attribute order (positional): street, city, state, zip_code, country.
     *
     * ORACLE-DEP [ORACLE_JAVA_API:STRUCT_TYPE] oracle.sql.STRUCT with ADDRESS_TYPE descriptor
     */
    @OracleDependency(
            domain = DependencyDomain.ORACLE_JAVA_API,
            category = "STRUCT_TYPE",
            description = "Creates Oracle STRUCT for MERIDIAN.ADDRESS_TYPE from DTO address fields",
            complexity = MigrationComplexity.HIGH,
            automationLevel = AutomationLevel.PARTIAL,
            migrationTarget = "Replace with individual address parameters or JSON payload in PostgreSQL"
    )
    public STRUCT createAddressStruct(OracleConnection conn, CustomerDTO dto) throws SQLException {
        // ORACLE-DEP [ORACLE_JAVA_API:STRUCT_TYPE] StructDescriptor.createDescriptor for MERIDIAN.ADDRESS_TYPE
        StructDescriptor desc = getStructDescriptor(ADDRESS_TYPE, conn);
        Object[] attrs = {
            dto.getStreet(),
            dto.getCity(),
            dto.getState(),
            dto.getZipCode(),
            dto.getCountry()
        };
        // ORACLE-DEP [ORACLE_JAVA_API:STRUCT_TYPE] new oracle.sql.STRUCT with positional attribute array
        STRUCT struct = new STRUCT(desc, conn, attrs);
        log.debug("Created ADDRESS_TYPE STRUCT for customer {}", dto.getCustomerId());
        return struct;
    }

    /**
     * Creates an Oracle NUMBER_TABLE_TYPE ARRAY from a list of Long IDs.
     *
     * ORACLE-DEP [ORACLE_JAVA_API:ARRAY_TYPE] oracle.sql.ARRAY with NUMBER_TABLE_TYPE descriptor
     */
    @OracleDependency(
            domain = DependencyDomain.ORACLE_JAVA_API,
            category = "ARRAY_TYPE",
            description = "Creates Oracle ARRAY of type NUMBER_TABLE_TYPE from List<Long>",
            complexity = MigrationComplexity.HIGH,
            automationLevel = AutomationLevel.PARTIAL,
            migrationTarget = "Replace with BIGINT[] array parameter in PostgreSQL stored function"
    )
    public ARRAY createNumberArray(OracleConnection conn, List<Long> ids) throws SQLException {
        // ORACLE-DEP [ORACLE_JAVA_API:ARRAY_TYPE] ArrayDescriptor.createDescriptor for NUMBER_TABLE_TYPE
        ArrayDescriptor desc = getArrayDescriptor(NUMBER_TABLE_TYPE, conn);
        Long[] elements = ids.toArray(new Long[0]);
        // ORACLE-DEP [ORACLE_JAVA_API:ARRAY_TYPE] new oracle.sql.ARRAY wrapping Long[] as Oracle NUMBER collection
        ARRAY array = new ARRAY(desc, conn, elements);
        log.debug("Created NUMBER_TABLE_TYPE ARRAY with {} elements", ids.size());
        return array;
    }

    /**
     * Creates an Oracle VARCHAR2_TABLE_TYPE ARRAY from a list of Strings.
     *
     * ORACLE-DEP [ORACLE_JAVA_API:ARRAY_TYPE] oracle.sql.ARRAY with VARCHAR2_TABLE_TYPE descriptor
     */
    @OracleDependency(
            domain = DependencyDomain.ORACLE_JAVA_API,
            category = "ARRAY_TYPE",
            description = "Creates Oracle ARRAY of type VARCHAR2_TABLE_TYPE from List<String>",
            complexity = MigrationComplexity.HIGH,
            automationLevel = AutomationLevel.PARTIAL,
            migrationTarget = "Replace with TEXT[] array parameter in PostgreSQL stored function"
    )
    public ARRAY createVarchar2Array(OracleConnection conn, List<String> values) throws SQLException {
        // ORACLE-DEP [ORACLE_JAVA_API:ARRAY_TYPE] ArrayDescriptor for MERIDIAN.VARCHAR2_TABLE_TYPE
        ArrayDescriptor desc = getArrayDescriptor(VARCHAR2_TABLE_TYPE, conn);
        String[] elements = values.toArray(new String[0]);
        // ORACLE-DEP [ORACLE_JAVA_API:ARRAY_TYPE] new oracle.sql.ARRAY wrapping String[] as Oracle VARCHAR2 collection
        ARRAY array = new ARRAY(desc, conn, elements);
        log.debug("Created VARCHAR2_TABLE_TYPE ARRAY with {} elements", values.size());
        return array;
    }

    /**
     * Creates an Oracle TRANSACTION_RECORD_TYPE STRUCT from a TransactionDTO.
     *
     * Attribute order: transaction_id, account_id, amount, transaction_type,
     *                  description, transaction_date, status.
     *
     * ORACLE-DEP [ORACLE_JAVA_API:STRUCT_TYPE] oracle.sql.STRUCT with TRANSACTION_RECORD_TYPE
     */
    @OracleDependency(
            domain = DependencyDomain.ORACLE_JAVA_API,
            category = "STRUCT_TYPE",
            description = "Creates Oracle STRUCT for MERIDIAN.TRANSACTION_RECORD_TYPE from TransactionDTO",
            complexity = MigrationComplexity.HIGH,
            automationLevel = AutomationLevel.PARTIAL,
            migrationTarget = "Replace with individual parameters or JSON payload"
    )
    public STRUCT createTransactionRecordStruct(OracleConnection conn, TransactionDTO tx)
            throws SQLException {
        // ORACLE-DEP [ORACLE_JAVA_API:STRUCT_TYPE] StructDescriptor for MERIDIAN.TRANSACTION_RECORD_TYPE
        StructDescriptor desc = getStructDescriptor(TRANSACTION_TYPE, conn);
        Timestamp txDate = null;
        if (tx.getTransactionDate() != null) {
            txDate = Timestamp.from(tx.getTransactionDate().toInstant());
        }
        Object[] attrs = {
            tx.getTransactionId() != null ? BigDecimal.valueOf(tx.getTransactionId()) : null,
            tx.getAccountId()     != null ? BigDecimal.valueOf(tx.getAccountId())     : null,
            tx.getAmount(),
            tx.getTransactionType(),
            tx.getDescription(),
            txDate,
            tx.getStatus()
        };
        // ORACLE-DEP [ORACLE_JAVA_API:STRUCT_TYPE] new oracle.sql.STRUCT for transaction record
        STRUCT struct = new STRUCT(desc, conn, attrs);
        log.debug("Created TRANSACTION_RECORD_TYPE STRUCT for txId={}", tx.getTransactionId());
        return struct;
    }

    /**
     * Returns a cached StructDescriptor for the given Oracle type name.
     *
     * ORACLE-DEP [ORACLE_JAVA_API:STRUCT_TYPE] oracle.sql.StructDescriptor.createDescriptor
     */
    @OracleDependency(
            domain = DependencyDomain.ORACLE_JAVA_API,
            category = "STRUCT_TYPE",
            description = "Cached StructDescriptor lookup to avoid repeated Oracle data dictionary queries",
            complexity = MigrationComplexity.MEDIUM,
            automationLevel = AutomationLevel.PARTIAL,
            migrationTarget = "Remove; no equivalent needed for PostgreSQL composite types used via JDBC"
    )
    public StructDescriptor getStructDescriptor(String typeName, OracleConnection conn)
            throws SQLException {
        StructDescriptor cached = structDescriptorCache.get(typeName);
        if (cached != null) {
            return cached;
        }
        // ORACLE-DEP [ORACLE_JAVA_API:STRUCT_TYPE] StructDescriptor.createDescriptor queries Oracle data dictionary
        log.debug("Creating StructDescriptor for type: {}", typeName);
        StructDescriptor desc = StructDescriptor.createDescriptor(typeName, conn);
        structDescriptorCache.put(typeName, desc);
        return desc;
    }

    /**
     * Returns a cached ArrayDescriptor for the given Oracle collection type name.
     *
     * ORACLE-DEP [ORACLE_JAVA_API:ARRAY_TYPE] oracle.sql.ArrayDescriptor.createDescriptor
     */
    @OracleDependency(
            domain = DependencyDomain.ORACLE_JAVA_API,
            category = "ARRAY_TYPE",
            description = "Cached ArrayDescriptor lookup to avoid repeated Oracle data dictionary queries",
            complexity = MigrationComplexity.MEDIUM,
            automationLevel = AutomationLevel.PARTIAL,
            migrationTarget = "Remove; use standard SQL array parameters in PostgreSQL"
    )
    public ArrayDescriptor getArrayDescriptor(String typeName, OracleConnection conn)
            throws SQLException {
        ArrayDescriptor cached = arrayDescriptorCache.get(typeName);
        if (cached != null) {
            return cached;
        }
        // ORACLE-DEP [ORACLE_JAVA_API:ARRAY_TYPE] ArrayDescriptor.createDescriptor queries Oracle data dictionary
        log.debug("Creating ArrayDescriptor for type: {}", typeName);
        ArrayDescriptor desc = ArrayDescriptor.createDescriptor(typeName, conn);
        arrayDescriptorCache.put(typeName, desc);
        return desc;
    }

    /**
     * Clears both descriptor caches. Call after reconnection or in tests that re-initialize schema.
     */
    public void clearDescriptorCache() {
        int structCount = structDescriptorCache.size();
        int arrayCount  = arrayDescriptorCache.size();
        structDescriptorCache.clear();
        arrayDescriptorCache.clear();
        log.info("Cleared descriptor caches: {} StructDescriptors, {} ArrayDescriptors evicted",
                structCount, arrayCount);
    }
}
