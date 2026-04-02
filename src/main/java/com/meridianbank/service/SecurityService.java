package com.meridianbank.service;

import com.meridianbank.annotation.AutomationLevel;
import com.meridianbank.annotation.DependencyDomain;
import com.meridianbank.annotation.MigrationComplexity;
import com.meridianbank.annotation.OracleDependency;
import com.meridianbank.dao.SecurityOracleDAO;
import com.meridianbank.exception.MeridianBankException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * SecurityService -- business logic for Oracle-based encryption, hashing, and
 * credential management operations.
 *
 * <p>Oracle provides the {@code DBMS_CRYPTO} PL/SQL package for symmetric encryption,
 * hashing, and MAC generation directly within the database.  Meridian Bank uses this
 * for:
 * <ul>
 *   <li>SSN encryption (AES-256-CBC via DBMS_CRYPTO.ENCRYPT, stored as RAW)</li>
 *   <li>Password hashing (SHA-256 via DBMS_CRYPTO.HASH, stored as RAW)</li>
 *   <li>Credential validation (hash comparison via stored procedure)</li>
 * </ul>
 *
 * <p>Oracle Wallet provides PKI-based authentication and TLS certificate management
 * for Oracle connections.  This service references the Wallet configuration pattern
 * so the migration framework can detect it.
 *
 * <p>Oracle's RAW data type stores binary data as a hex string in JDBC.  It is used
 * for encrypted SSNs, hash values, and AQ message IDs.  PostgreSQL's BYTEA type
 * replaces RAW; the Java handling changes from oracle.sql.RAW / hex string to
 * standard byte[] / PreparedStatement.setBytes().
 *
 * <p>Oracle null semantics: Oracle treats an empty string ({@code ''}) as NULL.
 * This has security implications for SSN / password fields -- a blank SSN would be
 * stored as NULL rather than an empty-string ciphertext.  PostgreSQL distinguishes
 * between NULL and {@code ''}, so this service includes explicit null/empty guards
 * on all crypto inputs.
 *
 * <p>Oracle dependencies: §2.2 (DBMS_CRYPTO package calls), §2.3 (Oracle Wallet),
 * §2.1 (RAW type, empty string = NULL semantics).
 */
@Service
public class SecurityService {

    private static final Logger log = LoggerFactory.getLogger(SecurityService.class);

    /** SSN display mask -- only the last 4 digits are shown. */
    private static final String SSN_MASK_PREFIX = "***-**-";

    @Autowired
    private SecurityOracleDAO securityOracleDAO;

    // -------------------------------------------------------------------------
    // SSN encryption / decryption
    // -------------------------------------------------------------------------

    /**
     * Encrypts a plaintext SSN using Oracle DBMS_CRYPTO.ENCRYPT (AES-256-CBC).
     *
     * <p>The DAO calls a stored procedure that invokes DBMS_CRYPTO.ENCRYPT with
     * the AES256 algorithm constant and a database-resident encryption key.
     * The ciphertext is stored as Oracle RAW and returned as a hex string.
     *
     * <p>Oracle empty-string = NULL applies here: if {@code plainSsn} is blank
     * after trimming, an exception is thrown rather than allowing a null encrypted
     * value to be stored, which would mask a data quality issue.
     *
     * @param plainSsn the 9-digit SSN (no dashes) to encrypt
     * @return hex-encoded ciphertext suitable for storage in the RAW(200) column
     * @throws MeridianBankException if input is blank or encryption fails
     */
    // ORACLE-DEP [PLSQL_INVOCATION:DBMS_CRYPTO] SSN encryption via DBMS_CRYPTO.ENCRYPT
    @Transactional
    @OracleDependency(
        domain = DependencyDomain.PLSQL_INVOCATION,
        category = "DBMS_CRYPTO",
        description = "Delegates to SecurityOracleDAO.encryptSSN() which calls the SECURITY_PKG.ENCRYPT_SSN "
                    + "stored procedure; that procedure invokes DBMS_CRYPTO.ENCRYPT using algorithm "
                    + "DBMS_CRYPTO.ENCRYPT_AES256 + CHAIN_CBC + PAD_PKCS5 with the DB-resident master key",
        complexity = MigrationComplexity.HIGH,
        automationLevel = AutomationLevel.MANUAL,
        migrationTarget = "Replace DBMS_CRYPTO.ENCRYPT with application-tier AES-256 encryption using "
                        + "javax.crypto.Cipher (AES/CBC/PKCS5Padding) with a key stored in AWS KMS, "
                        + "HashiCorp Vault, or Java KeyStore; store ciphertext in PostgreSQL BYTEA column"
    )
    public String encryptSsn(String plainSsn) {
        // ORACLE-DEP [EMBEDDED_SQL:EMPTY_STRING_NULL] Oracle treats '' as NULL; guard against blank input
        if (plainSsn == null || plainSsn.isBlank()) {
            throw new MeridianBankException("INVALID_INPUT",
                "SSN must not be blank (Oracle empty string = NULL semantics)", 400);
        }
        // Normalise: strip dashes if present
        String normalised = plainSsn.replaceAll("[^0-9]", "");
        if (normalised.length() != 9) {
            throw new MeridianBankException("INVALID_SSN",
                "SSN must be exactly 9 digits after normalisation", 400);
        }
        log.debug("Encrypting SSN via Oracle DBMS_CRYPTO.ENCRYPT");
        try {
            // ORACLE-DEP [PLSQL_INVOCATION:DBMS_CRYPTO] encryptSSN calls DBMS_CRYPTO.ENCRYPT
            String ciphertext = securityOracleDAO.encryptSSN(normalised);
            if (ciphertext == null || ciphertext.isBlank()) {
                throw new MeridianBankException("ENCRYPT_FAILED",
                    "Oracle DBMS_CRYPTO.ENCRYPT returned null ciphertext", 500);
            }
            return ciphertext;
        } catch (MeridianBankException mbe) {
            throw mbe;
        } catch (Exception e) {
            log.error("SSN encryption failed: {}", e.getMessage(), e);
            throw new MeridianBankException("ENCRYPT_FAILED",
                "SSN encryption failed: " + e.getMessage(), 500, e);
        }
    }

    /**
     * Decrypts an AES-256-CBC ciphertext SSN using Oracle DBMS_CRYPTO.DECRYPT.
     *
     * <p>The DAO calls SECURITY_PKG.DECRYPT_SSN which invokes DBMS_CRYPTO.DECRYPT
     * with the same algorithm constants as encryption.  The function reads the
     * database-resident key and returns the plaintext SSN.
     *
     * <p>Important: the returned SSN should never be logged.  This method is intended
     * only for display to authorised users with explicit consent (e.g., identity
     * verification workflows).
     *
     * @param ciphertext hex-encoded ciphertext from the RAW(200) column
     * @return the decrypted 9-digit SSN
     * @throws MeridianBankException if ciphertext is blank or decryption fails
     */
    // ORACLE-DEP [PLSQL_INVOCATION:DBMS_CRYPTO] SSN decryption via DBMS_CRYPTO.DECRYPT
    @Transactional(readOnly = true)
    @OracleDependency(
        domain = DependencyDomain.PLSQL_INVOCATION,
        category = "DBMS_CRYPTO",
        description = "Delegates to SecurityOracleDAO.decryptSSN() which calls SECURITY_PKG.DECRYPT_SSN, "
                    + "invoking DBMS_CRYPTO.DECRYPT with DBMS_CRYPTO.ENCRYPT_AES256 + CHAIN_CBC + PAD_PKCS5",
        complexity = MigrationComplexity.HIGH,
        automationLevel = AutomationLevel.MANUAL,
        migrationTarget = "Replace DBMS_CRYPTO.DECRYPT with javax.crypto.Cipher.doFinal() decryption; "
                        + "retrieve the AES key from the same secure key store used for encryption; "
                        + "handle BYTEA input from PostgreSQL rather than Oracle RAW hex string"
    )
    public String decryptSsn(String ciphertext) {
        // ORACLE-DEP [EMBEDDED_SQL:EMPTY_STRING_NULL] Oracle treats '' as NULL; prevent blank decrypt
        if (ciphertext == null || ciphertext.isBlank()) {
            throw new MeridianBankException("INVALID_INPUT",
                "Ciphertext must not be blank (Oracle empty string = NULL semantics)", 400);
        }
        log.debug("Decrypting SSN via Oracle DBMS_CRYPTO.DECRYPT (length={})", ciphertext.length());
        try {
            // ORACLE-DEP [PLSQL_INVOCATION:DBMS_CRYPTO] decryptSSN calls DBMS_CRYPTO.DECRYPT
            String plainSsn = securityOracleDAO.decryptSSN(ciphertext);
            if (plainSsn == null || plainSsn.isBlank()) {
                throw new MeridianBankException("DECRYPT_FAILED",
                    "Oracle DBMS_CRYPTO.DECRYPT returned blank plaintext", 500);
            }
            return plainSsn;
        } catch (MeridianBankException mbe) {
            throw mbe;
        } catch (Exception e) {
            log.error("SSN decryption failed: {}", e.getMessage(), e);
            throw new MeridianBankException("DECRYPT_FAILED",
                "SSN decryption failed: " + e.getMessage(), 500, e);
        }
    }

    // -------------------------------------------------------------------------
    // Password hashing
    // -------------------------------------------------------------------------

    /**
     * Hashes a plaintext password using Oracle DBMS_CRYPTO.HASH (SHA-256).
     *
     * <p>The DAO calls SECURITY_PKG.HASH_PASSWORD which invokes
     * DBMS_CRYPTO.HASH(UTL_I18N.STRING_TO_RAW(plaintext, 'AL32UTF8'), DBMS_CRYPTO.HASH_SH256).
     * The result is stored as Oracle RAW and returned as a hex string.
     *
     * <p>Note: Oracle empty-string = NULL means a blank password would produce a NULL
     * hash, not a hash of an empty string.  This service rejects blank passwords
     * explicitly.
     *
     * @param plainPassword the password to hash
     * @return hex-encoded SHA-256 hash of the password
     * @throws MeridianBankException if password is blank or hashing fails
     */
    // ORACLE-DEP [PLSQL_INVOCATION:DBMS_CRYPTO] Password hashing via DBMS_CRYPTO.HASH
    @OracleDependency(
        domain = DependencyDomain.PLSQL_INVOCATION,
        category = "DBMS_CRYPTO",
        description = "Delegates to SecurityOracleDAO.hashPassword() which calls SECURITY_PKG.HASH_PASSWORD, "
                    + "invoking DBMS_CRYPTO.HASH with DBMS_CRYPTO.HASH_SH256; input is first converted "
                    + "from VARCHAR2 to RAW via UTL_I18N.STRING_TO_RAW",
        complexity = MigrationComplexity.HIGH,
        automationLevel = AutomationLevel.MANUAL,
        migrationTarget = "Replace DBMS_CRYPTO.HASH with java.security.MessageDigest.getInstance('SHA-256'); "
                        + "or use BCrypt / Argon2 (preferred for passwords) from Spring Security; "
                        + "store result in PostgreSQL BYTEA or TEXT column"
    )
    public String hashPassword(String plainPassword) {
        // ORACLE-DEP [EMBEDDED_SQL:EMPTY_STRING_NULL] Oracle '' = NULL; reject blank password
        if (plainPassword == null || plainPassword.isEmpty()) {
            throw new MeridianBankException("INVALID_INPUT",
                "Password must not be empty (Oracle empty string = NULL semantics)", 400);
        }
        log.debug("Hashing password via Oracle DBMS_CRYPTO.HASH (SHA-256)");
        try {
            // ORACLE-DEP [PLSQL_INVOCATION:DBMS_CRYPTO] hashPassword calls DBMS_CRYPTO.HASH
            String hash = securityOracleDAO.hashPassword(plainPassword);
            if (hash == null || hash.isBlank()) {
                throw new MeridianBankException("HASH_FAILED",
                    "Oracle DBMS_CRYPTO.HASH returned null result", 500);
            }
            return hash;
        } catch (MeridianBankException mbe) {
            throw mbe;
        } catch (Exception e) {
            log.error("Password hashing failed: {}", e.getMessage(), e);
            throw new MeridianBankException("HASH_FAILED",
                "Password hashing failed: " + e.getMessage(), 500, e);
        }
    }

    // -------------------------------------------------------------------------
    // Credential validation
    // -------------------------------------------------------------------------

    /**
     * Validates a username and hashed password against Oracle credential storage.
     *
     * <p>The DAO calls SECURITY_PKG.VALIDATE_CREDENTIALS which compares the provided
     * hash against the stored RAW hash value.  The comparison is done inside the
     * database to avoid transmitting the stored hash to the application layer.
     *
     * @param username       the username to authenticate
     * @param hashedPassword the SHA-256 hash of the password (from hashPassword())
     * @return true if credentials are valid, false otherwise
     * @throws MeridianBankException if either parameter is blank or the database call fails
     */
    // ORACLE-DEP [PLSQL_INVOCATION:DBMS_CRYPTO] Credential validation uses DBMS_CRYPTO hash comparison
    @Transactional(readOnly = true)
    @OracleDependency(
        domain = DependencyDomain.PLSQL_INVOCATION,
        category = "DBMS_CRYPTO",
        description = "Delegates to SecurityOracleDAO.validateUserCredentials() which calls "
                    + "SECURITY_PKG.VALIDATE_CREDENTIALS; the procedure compares the input hash "
                    + "to the stored DBMS_CRYPTO.HASH value using Oracle RAW equality",
        complexity = MigrationComplexity.HIGH,
        automationLevel = AutomationLevel.MANUAL,
        migrationTarget = "Replace with Spring Security PasswordEncoder.matches() or MessageDigest "
                        + "comparison; retrieve stored hash from PostgreSQL and compare in application "
                        + "tier; consider migrating to BCrypt / Argon2 with re-hash on login"
    )
    public boolean validateCredentials(String username, String hashedPassword) {
        // ORACLE-DEP [EMBEDDED_SQL:EMPTY_STRING_NULL] Oracle '' = NULL; guard both inputs
        if (username == null || username.isBlank()) {
            throw new MeridianBankException("INVALID_INPUT",
                "Username must not be blank (Oracle empty string = NULL semantics)", 400);
        }
        if (hashedPassword == null || hashedPassword.isBlank()) {
            throw new MeridianBankException("INVALID_INPUT",
                "Hashed password must not be blank (Oracle empty string = NULL semantics)", 400);
        }
        log.debug("Validating credentials for user '{}' via Oracle DBMS_CRYPTO hash comparison", username);
        try {
            // ORACLE-DEP [PLSQL_INVOCATION:DBMS_CRYPTO] validateUserCredentials calls SECURITY_PKG
            return securityOracleDAO.validateUserCredentials(username, hashedPassword);
        } catch (Exception e) {
            log.error("Credential validation failed for user '{}': {}", username, e.getMessage(), e);
            // Do not expose internal error details for auth failures -- return false
            return false;
        }
    }

    // -------------------------------------------------------------------------
    // Oracle RAW type demonstration
    // -------------------------------------------------------------------------

    /**
     * Demonstrates Oracle RAW data type handling for a customer's encrypted fields.
     *
     * <p>Oracle RAW stores fixed-length or variable-length binary data as hex strings
     * in JDBC result sets.  This method surfaces the raw encrypted values (SSN,
     * customer ID as RAW(16) SYS_GUID) for inspection during migration assessment.
     *
     * <p>RAW maps to BYTEA in PostgreSQL.  The primary difference is:
     * Oracle JDBC returns RAW as hex strings; PostgreSQL JDBC returns BYTEA as byte[].
     * Application code that does string processing on RAW values (hex slicing, etc.)
     * must be rewritten for PostgreSQL.
     *
     * @param customerId the customer whose RAW columns are being demonstrated
     * @return list of maps with raw-type column data
     * @throws MeridianBankException if the DAO call fails
     */
    // ORACLE-DEP [ORACLE_JAVA_API:ORACLE_RAW_TYPE] RAW type handling via Oracle JDBC
    @Transactional(readOnly = true)
    @OracleDependency(
        domain = DependencyDomain.ORACLE_JAVA_API,
        category = "ORACLE_RAW_TYPE",
        description = "Delegates to SecurityOracleDAO.getRawTypeDemo() which fetches columns of type "
                    + "RAW(200) (encrypted SSN) and RAW(16) (SYS_GUID-based customer_guid) demonstrating "
                    + "how Oracle RAW data is returned as uppercase hex strings via JDBC",
        complexity = MigrationComplexity.MEDIUM,
        automationLevel = AutomationLevel.PARTIAL,
        migrationTarget = "Replace Oracle RAW columns with PostgreSQL BYTEA; Oracle JDBC returns RAW "
                        + "as hex String, PostgreSQL JDBC returns BYTEA as byte[]; update all code that "
                        + "processes the column value as a hex string to use byte[] operations instead"
    )
    public List<Map<String, Object>> getRawTypeDemo(Long customerId) {
        if (customerId == null) {
            throw new MeridianBankException("INVALID_INPUT", "Customer ID must not be null", 400);
        }
        log.debug("Fetching Oracle RAW type demo for customer {}", customerId);
        try {
            // ORACLE-DEP [ORACLE_JAVA_API:ORACLE_RAW_TYPE] getRawTypeDemo fetches RAW(200) and RAW(16) columns
            return securityOracleDAO.getRawTypeDemo(customerId);
        } catch (Exception e) {
            log.error("RAW type demo failed for customer {}: {}", customerId, e.getMessage(), e);
            throw new MeridianBankException("RAW_TYPE_DEMO_FAILED",
                "RAW type demonstration failed: " + e.getMessage(), 500, e);
        }
    }

    // -------------------------------------------------------------------------
    // Utility
    // -------------------------------------------------------------------------

    /**
     * Masks an SSN to show only the last 4 digits.
     *
     * <p>Demonstrates the Oracle empty-string = NULL semantic edge case:
     * in Oracle, {@code WHERE ssn = ''} never matches any row because Oracle
     * converts {@code ''} to {@code NULL}, and {@code NULL = NULL} is UNKNOWN,
     * not TRUE.  The same comparison in PostgreSQL with a VARCHAR column that
     * contains an actual empty string WOULD match.  Application code that relies
     * on blank-string checks for SSN absence must be audited on migration.
     *
     * @param ssn the SSN to mask (may be 9 digits with or without dashes)
     * @return masked SSN string, e.g. "***-**-1234"
     */
    // ORACLE-DEP [EMBEDDED_SQL:EMPTY_STRING_NULL] Oracle '' = NULL semantics affect SSN blank check
    @OracleDependency(
        domain = DependencyDomain.EMBEDDED_SQL,
        category = "EMPTY_STRING_NULL",
        description = "Oracle treats '' as NULL, so a customer with a blank SSN in Oracle has a "
                    + "NULL value in the column, not an empty string.  PostgreSQL distinguishes "
                    + "NULL from ''.  Any WHERE ssn IS NULL check in Oracle may need to also "
                    + "handle ssn = '' after migration to PostgreSQL.",
        complexity = MigrationComplexity.HIGH,
        automationLevel = AutomationLevel.MANUAL,
        migrationTarget = "Audit all WHERE ssn IS NULL / WHERE ssn = '' predicates; in PostgreSQL "
                        + "add COALESCE(ssn, '') = '' to catch both cases, or add a CHECK constraint "
                        + "to prevent empty-string SSNs at the database level"
    )
    public String maskSsn(String ssn) {
        // ORACLE-DEP [EMBEDDED_SQL:EMPTY_STRING_NULL] Oracle: '' = NULL; PostgreSQL: '' != NULL
        if (ssn == null || ssn.isBlank()) {
            // Oracle would store this as NULL; PostgreSQL could store as ''
            return "***-**-****";
        }
        // Strip any formatting characters
        String digits = ssn.replaceAll("[^0-9]", "");
        if (digits.length() < 4) {
            return "***-**-****";
        }
        String lastFour = digits.substring(digits.length() - 4);
        return SSN_MASK_PREFIX + lastFour;
    }

    // -------------------------------------------------------------------------
    // Oracle Wallet reference
    // -------------------------------------------------------------------------

    /**
     * Demonstrates the Oracle Wallet authentication pattern used in production.
     *
     * <p>Oracle Wallet is an auto-login PKCS#12 keystore that stores database
     * credentials and TLS certificates.  The application sets the
     * {@code oracle.net.wallet_location} JVM system property to point to the wallet
     * directory, and Oracle JDBC reads the credentials automatically -- no username
     * or password appears in the connection string.
     *
     * <p>This method does not actually connect; it documents the pattern for the
     * migration framework's dependency detection so that Wallet references are
     * included in the assessment report.
     *
     * @return a string describing the wallet configuration that would be used
     */
    // ORACLE-DEP [CONNECTIVITY_CONFIG:ORACLE_WALLET] Oracle Wallet auto-login credential storage
    @OracleDependency(
        domain = DependencyDomain.CONNECTIVITY_CONFIG,
        category = "ORACLE_WALLET",
        description = "Documents the Oracle Wallet configuration pattern: oracle.net.wallet_location "
                    + "JVM property points to a PKCS#12 wallet directory; Oracle JDBC reads username, "
                    + "password, and TLS certificates from the wallet without exposing credentials in code",
        complexity = MigrationComplexity.MEDIUM,
        automationLevel = AutomationLevel.MANUAL,
        migrationTarget = "Replace Oracle Wallet with: standard Java KeyStore for TLS certificates; "
                        + "AWS Secrets Manager / HashiCorp Vault / Kubernetes Secrets for database "
                        + "credentials; configure PostgreSQL JDBC SSL with sslmode=verify-full and "
                        + "sslrootcert, sslcert, sslkey connection properties"
    )
    public String demonstrateOracleWalletAuth() {
        // ORACLE-DEP [CONNECTIVITY_CONFIG:ORACLE_WALLET] Oracle Wallet location referenced here
        String walletLocation = System.getProperty("oracle.net.wallet_location", "/opt/oracle/wallet");
        String walletDescription = String.format(
            "Oracle Wallet authentication is configured for location: %s. "
          + "In production, oracle.net.wallet_location=%s is set so that Oracle JDBC "
          + "reads credentials and TLS certificates from the auto-login wallet. "
          + "JDBC URL pattern: jdbc:oracle:thin:/@wallet_alias (no password in URL). "
          + "Migration target: replace with PostgreSQL SSL + Vault/Secrets Manager.",
            walletLocation, walletLocation);
        log.debug("Oracle Wallet auth pattern: wallet_location={}", walletLocation);
        return walletDescription;
    }
}
