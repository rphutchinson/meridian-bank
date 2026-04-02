package com.meridianbank.exception;

import com.meridianbank.annotation.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.support.SQLErrorCodeSQLExceptionTranslator;
import org.springframework.stereotype.Component;

import java.sql.SQLException;

/**
 * Translates Oracle-specific error codes (ORA-XXXXX) to typed application exceptions.
 * This class is a critical Oracle dependency: the entire switch table of ORA- codes
 * must be replaced with target-database error codes upon migration.
 *
 * Oracle error codes are embedded as integer constants matching ORA-XXXXX suffix.
 * For example, ORA-00001 → code 1, ORA-01400 → code 1400.
 */
// ORACLE-DEP [EMBEDDED_SQL:ORACLE_ERROR_CODES] Complete ORA- error code translation table
@OracleDependency(
    domain = DependencyDomain.EMBEDDED_SQL,
    category = "ORACLE_ERROR_CODES",
    description = "Full switch table of Oracle ORA- error codes used for exception translation",
    complexity = MigrationComplexity.MEDIUM,
    automationLevel = AutomationLevel.PARTIAL,
    migrationTarget = "Replace ORA- codes with PostgreSQL SQLSTATE codes (e.g. 23505 for unique violation, 23502 for not-null violation)"
)
@Component
public class OracleErrorTranslator {

    private static final Logger log = LoggerFactory.getLogger(OracleErrorTranslator.class);

    // ORACLE-DEP [EMBEDDED_SQL:ORACLE_ERROR_CODES] Oracle error code constants
    // ORA-00001: unique constraint violated
    private static final int ORA_UNIQUE_CONSTRAINT_VIOLATED = 1;
    // ORA-00054: resource busy and acquire with NOWAIT
    private static final int ORA_RESOURCE_BUSY = 54;
    // ORA-00060: deadlock detected
    private static final int ORA_DEADLOCK = 60;
    // ORA-00942: table or view does not exist
    private static final int ORA_TABLE_NOT_EXIST = 942;
    // ORA-01000: maximum open cursors exceeded
    private static final int ORA_MAX_CURSORS_EXCEEDED = 1000;
    // ORA-01017: invalid username/password; logon denied
    private static final int ORA_INVALID_CREDENTIALS = 1017;
    // ORA-01031: insufficient privileges
    private static final int ORA_INSUFFICIENT_PRIVILEGES = 1031;
    // ORA-01400: cannot insert NULL into column
    private static final int ORA_CANNOT_INSERT_NULL = 1400;
    // ORA-01401: inserted value too large for column
    private static final int ORA_VALUE_TOO_LARGE = 1401;
    // ORA-01403: no data found
    private static final int ORA_NO_DATA_FOUND = 1403;
    // ORA-01422: exact fetch returns more than requested number of rows
    private static final int ORA_TOO_MANY_ROWS = 1422;
    // ORA-01476: divisor is equal to zero
    private static final int ORA_DIVISOR_ZERO = 1476;
    // ORA-01555: snapshot too old
    private static final int ORA_SNAPSHOT_TOO_OLD = 1555;
    // ORA-01722: invalid number
    private static final int ORA_INVALID_NUMBER = 1722;
    // ORA-01843: not a valid month
    private static final int ORA_INVALID_MONTH = 1843;
    // ORA-01858: non-numeric character found where numeric expected
    private static final int ORA_NON_NUMERIC_CHAR = 1858;
    // ORA-02291: integrity constraint violated — parent key not found
    private static final int ORA_PARENT_KEY_NOT_FOUND = 2291;
    // ORA-02292: integrity constraint violated — child record found
    private static final int ORA_CHILD_RECORD_FOUND = 2292;
    // ORA-04031: unable to allocate shared memory
    private static final int ORA_SHARED_MEMORY_EXHAUSTED = 4031;
    // ORA-04061: existing state of package has been discarded
    private static final int ORA_PACKAGE_STATE_DISCARDED = 4061;
    // ORA-04068: existing state of packages has been discarded (RAC)
    private static final int ORA_PACKAGE_STATE_DISCARDED_RAC = 4068;
    // ORA-06502: PL/SQL: numeric or value error
    private static final int ORA_PLSQL_VALUE_ERROR = 6502;
    // ORA-06512: at line
    private static final int ORA_PLSQL_CALL_STACK = 6512;
    // ORA-12154: TNS:could not resolve the connect identifier
    private static final int ORA_TNS_RESOLVE_FAILED = 12154;
    // ORA-12541: TNS:no listener
    private static final int ORA_TNS_NO_LISTENER = 12541;
    // ORA-12560: TNS:protocol adapter error
    private static final int ORA_TNS_PROTOCOL_ERROR = 12560;
    // ORA-28000: account is locked
    private static final int ORA_ACCOUNT_LOCKED = 28000;
    // ORA-28001: password expired
    private static final int ORA_PASSWORD_EXPIRED = 28001;
    // ORA-28003: password verification failed
    private static final int ORA_PASSWORD_VERIFY_FAILED = 28003;

    /**
     * Translates a SQLException containing an Oracle error code to a typed application exception.
     * This is the primary entry point for Oracle error translation.
     */
    // ORACLE-DEP [EMBEDDED_SQL:ORACLE_ERROR_CODES] Full switch block on Oracle vendor error codes
    public MeridianBankException translate(String operation, SQLException sqlEx) {
        int oraCode = sqlEx.getErrorCode();
        String oraMessage = sqlEx.getMessage();

        log.warn("Oracle error during {}: ORA-{:05d} — {}", operation, oraCode, oraMessage);

        // ORACLE-DEP [EMBEDDED_SQL:ORACLE_ERROR_CODES] Each case corresponds to an ORA-XXXXX code
        switch (oraCode) {

            case ORA_UNIQUE_CONSTRAINT_VIOLATED:  // ORA-00001
                return translateUniqueConstraint(oraMessage);

            case ORA_RESOURCE_BUSY:               // ORA-00054
                return new MeridianBankException("RESOURCE_LOCKED",
                    "The requested record is currently locked by another transaction. Please retry.",
                    409, sqlEx);

            case ORA_DEADLOCK:                    // ORA-00060
                return new MeridianBankException("DEADLOCK_DETECTED",
                    "A deadlock was detected. The transaction has been rolled back. Please retry.",
                    503, sqlEx);

            case ORA_TABLE_NOT_EXIST:             // ORA-00942
                return new MeridianBankException("SCHEMA_ERROR",
                    "Database schema error: required table or view does not exist.",
                    500, sqlEx);

            case ORA_MAX_CURSORS_EXCEEDED:        // ORA-01000
                return new MeridianBankException("DATABASE_RESOURCE_ERROR",
                    "Database cursor limit exceeded. Please contact support.",
                    503, sqlEx);

            case ORA_INVALID_CREDENTIALS:         // ORA-01017
                return new MeridianBankException("AUTHENTICATION_FAILED",
                    "Database authentication failed.",
                    401, sqlEx);

            case ORA_INSUFFICIENT_PRIVILEGES:     // ORA-01031
                return new MeridianBankException("AUTHORIZATION_FAILED",
                    "Insufficient database privileges to perform this operation.",
                    403, sqlEx);

            case ORA_CANNOT_INSERT_NULL:          // ORA-01400
                return new MeridianBankException("VALIDATION_ERROR",
                    "A required field is missing. " + extractColumnName(oraMessage),
                    400, sqlEx);

            case ORA_VALUE_TOO_LARGE:             // ORA-01401
                return new MeridianBankException("VALIDATION_ERROR",
                    "A field value exceeds the maximum allowed length.",
                    400, sqlEx);

            case ORA_NO_DATA_FOUND:               // ORA-01403
                return new MeridianBankException("NOT_FOUND",
                    "The requested record was not found.",
                    404, sqlEx);

            case ORA_TOO_MANY_ROWS:               // ORA-01422
                return new MeridianBankException("DATA_INTEGRITY_ERROR",
                    "Query returned unexpected multiple rows where one was expected.",
                    500, sqlEx);

            case ORA_DIVISOR_ZERO:                // ORA-01476
                return new MeridianBankException("CALCULATION_ERROR",
                    "Division by zero in database calculation.",
                    400, sqlEx);

            case ORA_SNAPSHOT_TOO_OLD:            // ORA-01555
                return new MeridianBankException("SNAPSHOT_TOO_OLD",
                    "Long-running query could not read consistent data. Please retry.",
                    503, sqlEx);

            case ORA_INVALID_NUMBER:              // ORA-01722
                return new MeridianBankException("VALIDATION_ERROR",
                    "Invalid numeric value provided.",
                    400, sqlEx);

            case ORA_INVALID_MONTH:               // ORA-01843
                return new MeridianBankException("VALIDATION_ERROR",
                    "Invalid date format — month value is not recognized.",
                    400, sqlEx);

            case ORA_NON_NUMERIC_CHAR:            // ORA-01858
                return new MeridianBankException("VALIDATION_ERROR",
                    "Non-numeric character found in a date field where a number was expected.",
                    400, sqlEx);

            case ORA_PARENT_KEY_NOT_FOUND:        // ORA-02291
                return new MeridianBankException("REFERENTIAL_INTEGRITY_ERROR",
                    "Operation failed: the referenced parent record does not exist.",
                    400, sqlEx);

            case ORA_CHILD_RECORD_FOUND:          // ORA-02292
                return new MeridianBankException("REFERENTIAL_INTEGRITY_ERROR",
                    "Cannot delete: child records exist that reference this record.",
                    409, sqlEx);

            case ORA_SHARED_MEMORY_EXHAUSTED:     // ORA-04031
                return new MeridianBankException("DATABASE_RESOURCE_ERROR",
                    "Database shared memory exhausted. Please contact the DBA team.",
                    503, sqlEx);

            case ORA_PACKAGE_STATE_DISCARDED:     // ORA-04061
            case ORA_PACKAGE_STATE_DISCARDED_RAC: // ORA-04068
                return new MeridianBankException("PACKAGE_STATE_ERROR",
                    "Database package state was reset (likely due to deployment). Please retry.",
                    503, sqlEx);

            case ORA_PLSQL_VALUE_ERROR:           // ORA-06502
                return new MeridianBankException("PLSQL_ERROR",
                    "PL/SQL numeric or value error in stored procedure.",
                    500, sqlEx);

            case ORA_TNS_RESOLVE_FAILED:          // ORA-12154
                return new MeridianBankException("CONNECTION_ERROR",
                    "Cannot resolve database connection identifier.",
                    503, sqlEx);

            case ORA_TNS_NO_LISTENER:             // ORA-12541
                return new MeridianBankException("CONNECTION_ERROR",
                    "No database listener available. The database may be down.",
                    503, sqlEx);

            case ORA_TNS_PROTOCOL_ERROR:          // ORA-12560
                return new MeridianBankException("CONNECTION_ERROR",
                    "TNS protocol adapter error — connection to Oracle failed.",
                    503, sqlEx);

            case ORA_ACCOUNT_LOCKED:              // ORA-28000
                return new MeridianBankException("ACCOUNT_LOCKED",
                    "The database account is locked. Please contact the DBA.",
                    403, sqlEx);

            case ORA_PASSWORD_EXPIRED:            // ORA-28001
                return new MeridianBankException("PASSWORD_EXPIRED",
                    "The database account password has expired.",
                    401, sqlEx);

            case ORA_PASSWORD_VERIFY_FAILED:      // ORA-28003
                return new MeridianBankException("PASSWORD_POLICY_VIOLATION",
                    "Password does not meet Oracle password verification requirements.",
                    400, sqlEx);

            default:
                log.error("Unhandled Oracle error ORA-{:05d} during {}: {}",
                          oraCode, operation, oraMessage);
                return new MeridianBankException("DATABASE_ERROR",
                    "An unexpected database error occurred (ORA-" + String.format("%05d", oraCode) + ")",
                    500, sqlEx);
        }
    }

    /**
     * Translates ORA-00001 (unique constraint) based on the constraint name in the message.
     * Maps known constraint names to domain-specific exceptions.
     */
    private MeridianBankException translateUniqueConstraint(String message) {
        if (message != null) {
            if (message.contains("CUSTOMERS_EMAIL_UK")) {
                return new DuplicateCustomerException("email", "provided email address");
            }
            if (message.contains("CUSTOMERS_SSN_UK")) {
                return new DuplicateCustomerException("SSN", "provided SSN");
            }
            if (message.contains("ACCOUNTS_NUMBER_UK")) {
                return new MeridianBankException("DUPLICATE_ACCOUNT",
                    "An account with this account number already exists.", 409);
            }
            if (message.contains("CREDIT_CARDS_NUMBER_UK")) {
                return new MeridianBankException("DUPLICATE_CARD",
                    "A credit card with this card number already exists.", 409);
            }
        }
        return new MeridianBankException("UNIQUE_CONSTRAINT_VIOLATION",
            "Duplicate value violates unique constraint: " + message,
            409);
    }

    /**
     * Extracts the column name from an ORA-01400 error message.
     * Oracle formats this as: ORA-01400: cannot insert NULL into ("SCHEMA"."TABLE"."COLUMN")
     */
    private String extractColumnName(String message) {
        if (message != null && message.contains("(\"")) {
            int start = message.lastIndexOf("\"") + 1;
            int secondToLast = message.lastIndexOf("\"", start - 2);
            if (secondToLast > 0) {
                int fieldStart = message.lastIndexOf("\"", secondToLast - 1) + 1;
                return "Required field: " + message.substring(fieldStart, secondToLast);
            }
        }
        return "";
    }

    /**
     * Convenience overload — translates a SQLException with a generic operation label.
     * Delegates to {@link #translate(String, SQLException)}.
     */
    // ORACLE-DEP [EMBEDDED_SQL:ORACLE_ERROR_CODES] Single-arg convenience wrapper for DAO catch blocks
    public MeridianBankException translateSQLException(SQLException sqlEx) {
        return translate("DAO operation", sqlEx);
    }

    /**
     * Checks if a DataAccessException wraps an Oracle-specific error code.
     * Useful for Spring DAO layer exception handling.
     */
    public boolean isOracleErrorCode(DataAccessException dae, int expectedCode) {
        Throwable cause = dae.getCause();
        if (cause instanceof SQLException sqlEx) {
            return sqlEx.getErrorCode() == expectedCode;
        }
        return false;
    }

    /**
     * Returns true if the exception represents a transient error that can be retried.
     * ORACLE-DEP [EMBEDDED_SQL:ORACLE_ERROR_CODES] Retryable ORA- codes list
     */
    // ORACLE-DEP [EMBEDDED_SQL:ORACLE_ERROR_CODES] Oracle-specific transient error codes
    public boolean isRetryable(SQLException sqlEx) {
        int code = sqlEx.getErrorCode();
        return code == ORA_RESOURCE_BUSY          // ORA-00054
            || code == ORA_DEADLOCK              // ORA-00060
            || code == ORA_SNAPSHOT_TOO_OLD      // ORA-01555
            || code == ORA_PACKAGE_STATE_DISCARDED      // ORA-04061
            || code == ORA_PACKAGE_STATE_DISCARDED_RAC  // ORA-04068
            || code == ORA_SHARED_MEMORY_EXHAUSTED;     // ORA-04031
    }
}
