package com.meridianbank.dao;

import com.meridianbank.annotation.AutomationLevel;
import com.meridianbank.annotation.DependencyDomain;
import com.meridianbank.annotation.MigrationComplexity;
import com.meridianbank.annotation.OracleDependency;
import oracle.jdbc.OracleTypes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * SecurityOracleDAO — Oracle-tier cryptography and credential validation.
 *
 * Demonstrates Oracle security-specific patterns:
 * - DBMS_CRYPTO.ENCRYPT (AES-256 CBC PKCS5) for SSN encryption at database tier
 * - DBMS_CRYPTO.DECRYPT for SSN decryption
 * - DBMS_CRYPTO.HASH (SHA-256) for password hashing at database tier
 * - UTL_RAW.CAST_TO_RAW for string-to-RAW conversion
 * - RAWTOHEX() / HEXTORAW() for RAW ↔ hex string conversion
 * - OracleTypes.VARCHAR, OracleTypes.NUMBER as OUT parameter types
 * - Oracle RAW datatype for in-row binary storage of encrypted SSN
 * - SECURITY_PKG.VALIDATE_USER callable statement with Oracle package reference
 * - ORA-01017 (invalid credentials) and ORA-28000 (locked account) error handling
 */
@Repository
public class SecurityOracleDAO {

    private static final Logger log = LoggerFactory.getLogger(SecurityOracleDAO.class);

    /**
     * 32-byte AES-256 encryption key.
     * In production this would come from Oracle Wallet or a secrets manager;
     * it is inlined here for demonstration of the DBMS_CRYPTO pattern.
     */
    // ORACLE-DEP [PLSQL_INVOCATION:DBMS_CRYPTO] Key passed to DBMS_CRYPTO.ENCRYPT — length must match AES256 (32 bytes)
    private static final String ENCRYPTION_KEY = "MeridianBank2026EncKey32Bytes!!!";

    @Autowired
    private DataSource dataSource;

    // -------------------------------------------------------------------------
    // METHOD 1: encryptSSN
    // -------------------------------------------------------------------------

    /**
     * Encrypts a Social Security Number using Oracle DBMS_CRYPTO at the database tier.
     *
     * The PL/SQL block:
     * 1. Converts the plaintext string and key to RAW using UTL_RAW.CAST_TO_RAW
     * 2. Calls DBMS_CRYPTO.ENCRYPT with AES-256 + CBC mode + PKCS5 padding
     * 3. Converts the encrypted RAW result to a hex string via RAWTOHEX() for storage
     *
     * Migration note: This pattern moves encryption from the database tier into
     * the application tier using javax.crypto.Cipher, eliminating the PL/SQL
     * round-trip and the DBMS_CRYPTO package dependency entirely.
     */
    @OracleDependency(
        domain = DependencyDomain.PLSQL_INVOCATION,
        category = "DBMS_CRYPTO",
        description = "DBMS_CRYPTO.ENCRYPT with AES-256 CBC PKCS5 padding encrypts SSN at Oracle database tier via PL/SQL anonymous block",
        complexity = MigrationComplexity.HIGH,
        automationLevel = AutomationLevel.MANUAL,
        migrationTarget = "Replace with Java javax.crypto.Cipher AES/CBC/PKCS5Padding — move encryption entirely to application tier, remove DBMS_CRYPTO dependency"
    )
    @OracleDependency(
        domain = DependencyDomain.ORACLE_JAVA_API,
        category = "ORACLE_TYPES",
        description = "OracleTypes.VARCHAR used to register the OUT parameter for RAWTOHEX-encoded ciphertext result",
        complexity = MigrationComplexity.LOW,
        automationLevel = AutomationLevel.FULL,
        migrationTarget = "Replace OracleTypes.VARCHAR with java.sql.Types.VARCHAR"
    )
    public String encryptSSN(String plainSSN) {
        // ORACLE-DEP [PLSQL_INVOCATION:DBMS_CRYPTO] DBMS_CRYPTO.ENCRYPT AES256+CHAIN_CBC+PAD_PKCS5 encrypts SSN at DB tier
        // ORACLE-DEP [EMBEDDED_SQL:RAWTOHEX] RAWTOHEX converts encrypted RAW ciphertext to storable hex string
        // ORACLE-DEP [ORACLE_JAVA_API:ORACLE_TYPES] OracleTypes.VARCHAR for OUT parameter registration
        final String block =
            "DECLARE " +
            "  v_key    RAW(32)   := UTL_RAW.CAST_TO_RAW(:p_key); " +
            "  v_input  RAW(2000) := UTL_RAW.CAST_TO_RAW(:p_plain); " +
            "  v_result RAW(2000); " +
            "BEGIN " +
            "  v_result := DBMS_CRYPTO.ENCRYPT( " +
            "    src => v_input, " +
            "    typ => DBMS_CRYPTO.AES256 + DBMS_CRYPTO.CHAIN_CBC + DBMS_CRYPTO.PAD_PKCS5, " +
            "    key => v_key); " +
            "  :p_result := RAWTOHEX(v_result); " +
            "END;";

        try (Connection conn = dataSource.getConnection();
             CallableStatement cs = conn.prepareCall(block)) {

            cs.setString("p_key", ENCRYPTION_KEY);
            cs.setString("p_plain", plainSSN);
            // ORACLE-DEP [ORACLE_JAVA_API:ORACLE_TYPES] OracleTypes.VARCHAR registers OUT param for hex-encoded ciphertext
            cs.registerOutParameter("p_result", OracleTypes.VARCHAR);

            cs.execute();

            String encrypted = cs.getString("p_result");
            log.debug("SSN encrypted successfully via DBMS_CRYPTO.ENCRYPT (result length={})",
                encrypted != null ? encrypted.length() : 0);
            return encrypted;

        } catch (SQLException e) {
            log.error("Failed to encrypt SSN via DBMS_CRYPTO.ENCRYPT", e);
            throw new RuntimeException("Error encrypting SSN at Oracle tier", e);
        }
    }

    // -------------------------------------------------------------------------
    // METHOD 2: decryptSSN
    // -------------------------------------------------------------------------

    /**
     * Decrypts an AES-256 encrypted SSN using Oracle DBMS_CRYPTO.DECRYPT.
     *
     * The hex ciphertext is converted back to RAW using HEXTORAW() before
     * passing to DBMS_CRYPTO.DECRYPT. The decrypted RAW is then converted
     * back to a VARCHAR2 using UTL_RAW.CAST_TO_VARCHAR2().
     */
    @OracleDependency(
        domain = DependencyDomain.PLSQL_INVOCATION,
        category = "DBMS_CRYPTO",
        description = "DBMS_CRYPTO.DECRYPT reverses AES-256 CBC PKCS5 encryption using HEXTORAW to convert hex ciphertext back to RAW before decryption",
        complexity = MigrationComplexity.HIGH,
        automationLevel = AutomationLevel.MANUAL,
        migrationTarget = "Replace with Java javax.crypto.Cipher.doFinal() decryption in application tier"
    )
    @OracleDependency(
        domain = DependencyDomain.EMBEDDED_SQL,
        category = "RAWTOHEX",
        description = "HEXTORAW(:p_hex) converts hex-encoded ciphertext string back to Oracle RAW type before DBMS_CRYPTO.DECRYPT call",
        complexity = MigrationComplexity.LOW,
        automationLevel = AutomationLevel.FULL,
        migrationTarget = "Replace HEXTORAW(hex_col) with decode(hex_col, 'hex') for PostgreSQL BYTEA operations"
    )
    public String decryptSSN(String hexCiphertext) {
        // ORACLE-DEP [PLSQL_INVOCATION:DBMS_CRYPTO] DBMS_CRYPTO.DECRYPT AES256+CHAIN_CBC+PAD_PKCS5 decrypts at DB tier
        // ORACLE-DEP [EMBEDDED_SQL:RAWTOHEX] HEXTORAW() converts hex string back to RAW for DBMS_CRYPTO.DECRYPT input
        // ORACLE-DEP [ORACLE_JAVA_API:ORACLE_TYPES] OracleTypes.VARCHAR for OUT parameter registration
        final String block =
            "DECLARE " +
            "  v_key    RAW(32)   := UTL_RAW.CAST_TO_RAW(:p_key); " +
            "  v_input  RAW(2000) := HEXTORAW(:p_hex); " +
            "  v_result RAW(2000); " +
            "BEGIN " +
            "  v_result := DBMS_CRYPTO.DECRYPT( " +
            "    src => v_input, " +
            "    typ => DBMS_CRYPTO.AES256 + DBMS_CRYPTO.CHAIN_CBC + DBMS_CRYPTO.PAD_PKCS5, " +
            "    key => v_key); " +
            "  :p_plain := UTL_RAW.CAST_TO_VARCHAR2(v_result); " +
            "END;";

        try (Connection conn = dataSource.getConnection();
             CallableStatement cs = conn.prepareCall(block)) {

            cs.setString("p_key", ENCRYPTION_KEY);
            cs.setString("p_hex", hexCiphertext);
            // ORACLE-DEP [ORACLE_JAVA_API:ORACLE_TYPES] OracleTypes.VARCHAR for OUT parameter holding decrypted plaintext
            cs.registerOutParameter("p_plain", OracleTypes.VARCHAR);

            cs.execute();

            String plaintext = cs.getString("p_plain");
            log.debug("SSN decrypted successfully via DBMS_CRYPTO.DECRYPT");
            return plaintext;

        } catch (SQLException e) {
            log.error("Failed to decrypt SSN via DBMS_CRYPTO.DECRYPT", e);
            throw new RuntimeException("Error decrypting SSN at Oracle tier", e);
        }
    }

    // -------------------------------------------------------------------------
    // METHOD 3: hashPassword
    // -------------------------------------------------------------------------

    /**
     * Computes a SHA-256 hash of a password using Oracle DBMS_CRYPTO.HASH at the
     * database tier. The hash is returned as a hex string via RAWTOHEX().
     *
     * Migration note: SHA-256 hashing should be performed in the application tier
     * using java.security.MessageDigest or a dedicated password hashing library
     * (bcrypt, Argon2) — there is no benefit to performing this at the DB tier.
     */
    @OracleDependency(
        domain = DependencyDomain.PLSQL_INVOCATION,
        category = "DBMS_CRYPTO",
        description = "DBMS_CRYPTO.HASH with HASH_SH256 computes SHA-256 password hash at Oracle database tier",
        complexity = MigrationComplexity.HIGH,
        automationLevel = AutomationLevel.MANUAL,
        migrationTarget = "Replace with java.security.MessageDigest.getInstance('SHA-256') in application tier; ideally use bcrypt/Argon2 for password hashing"
    )
    public String hashPassword(String plainPassword) {
        // ORACLE-DEP [PLSQL_INVOCATION:DBMS_CRYPTO] DBMS_CRYPTO.HASH HASH_SH256 computes SHA-256 at DB tier
        // ORACLE-DEP [EMBEDDED_SQL:RAWTOHEX] RAWTOHEX(v_hash) converts binary hash digest to storable hex string
        // ORACLE-DEP [ORACLE_JAVA_API:ORACLE_TYPES] OracleTypes.VARCHAR for OUT parameter holding hex hash
        final String block =
            "DECLARE " +
            "  v_input RAW(2000) := UTL_RAW.CAST_TO_RAW(:p_input); " +
            "  v_hash  RAW(32); " +
            "BEGIN " +
            "  v_hash := DBMS_CRYPTO.HASH( " +
            "    src => v_input, " +
            "    typ => DBMS_CRYPTO.HASH_SH256); " +
            "  :p_result := RAWTOHEX(v_hash); " +
            "END;";

        try (Connection conn = dataSource.getConnection();
             CallableStatement cs = conn.prepareCall(block)) {

            cs.setString("p_input", plainPassword);
            // ORACLE-DEP [ORACLE_JAVA_API:ORACLE_TYPES] OracleTypes.VARCHAR for OUT parameter receiving SHA-256 hex digest
            cs.registerOutParameter("p_result", OracleTypes.VARCHAR);

            cs.execute();

            String hash = cs.getString("p_result");
            log.debug("Password hashed via DBMS_CRYPTO.HASH_SH256 (hash length={})",
                hash != null ? hash.length() : 0);
            return hash;

        } catch (SQLException e) {
            log.error("Failed to hash password via DBMS_CRYPTO.HASH", e);
            throw new RuntimeException("Error hashing password at Oracle tier", e);
        }
    }

    // -------------------------------------------------------------------------
    // METHOD 4: validateUserCredentials
    // -------------------------------------------------------------------------

    /**
     * Validates user credentials by calling the Oracle package SECURITY_PKG.VALIDATE_USER.
     * The procedure returns 1 for valid credentials and 0 for invalid.
     *
     * Specifically handles:
     * - ORA-01017: invalid username/password during the JDBC connection used for the call
     * - ORA-28000: Oracle account is locked (too many failed login attempts)
     * These Oracle-specific error codes must be translated to application exceptions
     * during migration.
     */
    @OracleDependency(
        domain = DependencyDomain.PLSQL_INVOCATION,
        category = "PACKAGE_CALL",
        description = "Calls MERIDIAN.SECURITY_PKG.VALIDATE_USER package procedure via CallableStatement; returns 1 for valid, 0 for invalid credentials",
        complexity = MigrationComplexity.HIGH,
        automationLevel = AutomationLevel.PARTIAL,
        migrationTarget = "Replace with application-tier credential validation against a users table using BCrypt.checkpw() or equivalent password verification"
    )
    @OracleDependency(
        domain = DependencyDomain.ORACLE_JAVA_API,
        category = "ORACLE_TYPES",
        description = "OracleTypes.NUMBER registers OUT parameter for the integer validation result (1=valid, 0=invalid) returned by SECURITY_PKG.VALIDATE_USER",
        complexity = MigrationComplexity.LOW,
        automationLevel = AutomationLevel.FULL,
        migrationTarget = "Replace OracleTypes.NUMBER with java.sql.Types.INTEGER or Types.NUMERIC"
    )
    public boolean validateUserCredentials(String username, String hashedPassword) {
        // ORACLE-DEP [PLSQL_INVOCATION:PACKAGE_CALL] {call MERIDIAN.SECURITY_PKG.VALIDATE_USER(?,?,?)} Oracle package procedure
        // ORACLE-DEP [ORACLE_JAVA_API:ORACLE_TYPES] OracleTypes.NUMBER OUT parameter for integer validation result
        final String call = "{call MERIDIAN.SECURITY_PKG.VALIDATE_USER(?, ?, ?)}";

        try (Connection conn = dataSource.getConnection();
             CallableStatement cs = conn.prepareCall(call)) {

            cs.setString(1, username);
            cs.setString(2, hashedPassword);
            // ORACLE-DEP [ORACLE_JAVA_API:ORACLE_TYPES] OracleTypes.NUMBER registers OUT parameter 3 for numeric result
            cs.registerOutParameter(3, OracleTypes.NUMBER);

            cs.execute();

            int result = cs.getInt(3);
            boolean valid = (result == 1);
            log.debug("Credential validation for user='{}': result={} valid={}", username, result, valid);
            return valid;

        } catch (SQLException e) {
            // ORACLE-DEP [EMBEDDED_SQL:ORA_ERROR_CODES] ORA-01017: invalid username/password — authentication failure
            if (e.getErrorCode() == 1017) {
                log.warn("ORA-01017: Invalid credentials for user='{}'", username);
                return false;
            }
            // ORACLE-DEP [EMBEDDED_SQL:ORA_ERROR_CODES] ORA-28000: account locked after repeated failed login attempts
            if (e.getErrorCode() == 28000) {
                log.warn("ORA-28000: Account locked for user='{}'", username);
                throw new RuntimeException("Account is locked for user=" + username
                    + " (ORA-28000). Please contact your administrator.", e);
            }
            log.error("Failed to validate credentials for user='{}'", username, e);
            throw new RuntimeException("Error validating credentials for user=" + username, e);
        }
    }

    // -------------------------------------------------------------------------
    // METHOD 5: getRawTypeDemo
    // -------------------------------------------------------------------------

    /**
     * Demonstrates Oracle RAW datatype usage for encrypted SSN binary storage.
     *
     * The CUSTOMERS.ssn_encrypted column is Oracle RAW type, which stores binary
     * data in-row (up to 2000 bytes) without heap storage. RAWTOHEX() converts
     * the binary column to a displayable hex string for the response.
     *
     * In PostgreSQL the RAW column becomes BYTEA, and RAWTOHEX(col) becomes
     * encode(col, 'hex').
     */
    @OracleDependency(
        domain = DependencyDomain.EMBEDDED_SQL,
        category = "RAW_TYPE",
        description = "Oracle RAW datatype column ssn_encrypted stores encrypted SSN binary data in-row. RAWTOHEX() for hex display.",
        complexity = MigrationComplexity.MEDIUM,
        automationLevel = AutomationLevel.PARTIAL,
        migrationTarget = "Replace Oracle RAW column with PostgreSQL BYTEA; replace RAWTOHEX(col) with encode(col, 'hex'); LENGTH() semantics differ (bytes vs chars)"
    )
    @OracleDependency(
        domain = DependencyDomain.EMBEDDED_SQL,
        category = "RAWTOHEX",
        description = "RAWTOHEX(ssn_encrypted) converts RAW binary column to displayable hex string for API response",
        complexity = MigrationComplexity.LOW,
        automationLevel = AutomationLevel.FULL,
        migrationTarget = "Replace RAWTOHEX(col) with encode(col::bytea, 'hex') in PostgreSQL"
    )
    public List<Map<String, Object>> getRawTypeDemo(Long customerId) {
        // ORACLE-DEP [EMBEDDED_SQL:RAW_TYPE] Oracle RAW datatype column for binary SSN storage — in-row up to 2000 bytes
        // ORACLE-DEP [EMBEDDED_SQL:RAWTOHEX] RAWTOHEX() converts RAW bytes to displayable hex string for response
        final String sql =
            "SELECT customer_id, " +
            "       first_name, " +
            "       last_name, " +
            "       RAWTOHEX(ssn_encrypted) AS ssn_hex, " +
            "       LENGTH(ssn_encrypted)   AS ssn_raw_len " +
            "FROM   MERIDIAN.CUSTOMERS " +
            "WHERE  customer_id    = ? " +
            "AND    ssn_encrypted IS NOT NULL";

        List<Map<String, Object>> results = new ArrayList<>();

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setLong(1, customerId);

            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> row = new HashMap<>();
                    row.put("customerId", rs.getLong("customer_id"));
                    row.put("firstName", rs.getString("first_name"));
                    row.put("lastName", rs.getString("last_name"));
                    row.put("ssnHex", rs.getString("ssn_hex"));
                    row.put("ssnRawLen", rs.getInt("ssn_raw_len"));
                    results.add(row);
                }
            }

            log.debug("getRawTypeDemo returned {} rows for customerId={}", results.size(), customerId);
            return results;

        } catch (SQLException e) {
            log.error("Failed to retrieve RAW type demo data for customerId={}", customerId, e);
            throw new RuntimeException("Error retrieving Oracle RAW type demo for customerId=" + customerId, e);
        }
    }
}
