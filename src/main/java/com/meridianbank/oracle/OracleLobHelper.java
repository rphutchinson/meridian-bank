package com.meridianbank.oracle;

import com.meridianbank.annotation.AutomationLevel;
import com.meridianbank.annotation.DependencyDomain;
import com.meridianbank.annotation.MigrationComplexity;
import com.meridianbank.annotation.OracleDependency;
import oracle.jdbc.OracleConnection;
import oracle.jdbc.OracleResultSet;
import oracle.sql.BLOB;
import oracle.sql.CLOB;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.Reader;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Oracle-specific LOB (Large Object) handling using oracle.sql.CLOB and oracle.sql.BLOB.
 *
 * The standard JDBC java.sql.Clob and java.sql.Blob interfaces work for basic LOB access,
 * but Oracle's native oracle.sql.CLOB and oracle.sql.BLOB provide additional capabilities:
 *   - createTemporary() for in-memory LOB construction without a table row
 *   - freeTemporary() for explicit resource cleanup
 *   - Direct getSubString()/setString() access without streaming
 *   - 1-based offset indexing (Oracle LOBs are NOT 0-indexed)
 *
 * Migration note: PostgreSQL uses TEXT for character data and BYTEA for binary data;
 * neither requires LOB-style handling for typical use cases.
 *
 * ORACLE-DEP [ORACLE_JAVA_API:ORACLE_CLOB] oracle.sql.CLOB for character LOB operations
 * ORACLE-DEP [ORACLE_JAVA_API:ORACLE_BLOB] oracle.sql.BLOB for binary LOB operations
 */
@Component
// ORACLE-DEP [ORACLE_JAVA_API:ORACLE_CLOB] Oracle-specific LOB helper using oracle.sql.CLOB and BLOB
@OracleDependency(
        domain = DependencyDomain.ORACLE_JAVA_API,
        category = "ORACLE_CLOB",
        description = "Oracle-specific LOB helper using oracle.sql.CLOB and oracle.sql.BLOB APIs",
        complexity = MigrationComplexity.MEDIUM,
        automationLevel = AutomationLevel.PARTIAL,
        migrationTarget = "Replace with PreparedStatement.setString/setBytes for PostgreSQL TEXT/BYTEA"
)
public class OracleLobHelper {

    private static final Logger log = LoggerFactory.getLogger(OracleLobHelper.class);

    // -----------------------------------------------------------------------
    // CLOB operations
    // -----------------------------------------------------------------------

    /**
     * Creates a temporary Oracle CLOB from a Java String.
     *
     * CLOB.createTemporary() allocates the LOB in the temporary tablespace for the
     * session duration. The caller must call freeTemporaryClob() when done.
     *
     * ORACLE-DEP [ORACLE_JAVA_API:ORACLE_CLOB] oracle.sql.CLOB.createTemporary for in-memory CLOB
     */
    @OracleDependency(
            domain = DependencyDomain.ORACLE_JAVA_API,
            category = "ORACLE_CLOB",
            description = "Creates an Oracle temporary CLOB from a Java String using CLOB.createTemporary()",
            complexity = MigrationComplexity.MEDIUM,
            automationLevel = AutomationLevel.FULL,
            migrationTarget = "Replace with PreparedStatement.setString() for PostgreSQL TEXT columns"
    )
    public CLOB stringToCLOB(OracleConnection conn, String content) throws SQLException {
        if (content == null) {
            return null;
        }
        // ORACLE-DEP [ORACLE_JAVA_API:ORACLE_CLOB] CLOB.createTemporary() allocates in Oracle temp tablespace
        CLOB clob = CLOB.createTemporary(conn, true, CLOB.DURATION_SESSION);
        // ORACLE-DEP [ORACLE_JAVA_API:ORACLE_CLOB] CLOB.setString(1, content) — 1-based Oracle LOB offset
        clob.setString(1, content);
        log.debug("Created temporary CLOB ({} chars)", content.length());
        return clob;
    }

    /**
     * Reads an Oracle CLOB and returns its full content as a Java String.
     *
     * Uses CLOB.getSubString() with offset 1 (Oracle LOBs are 1-indexed, not 0-indexed).
     *
     * ORACLE-DEP [ORACLE_JAVA_API:ORACLE_CLOB] oracle.sql.CLOB.getSubString with 1-based offset
     */
    @OracleDependency(
            domain = DependencyDomain.ORACLE_JAVA_API,
            category = "ORACLE_CLOB",
            description = "Reads Oracle CLOB to String using CLOB.getSubString() with 1-based Oracle indexing",
            complexity = MigrationComplexity.LOW,
            automationLevel = AutomationLevel.FULL,
            migrationTarget = "Replace with ResultSet.getString() for PostgreSQL TEXT columns"
    )
    public String clobToString(CLOB clob) throws SQLException {
        if (clob == null) {
            return null;
        }
        long length = clob.length();
        if (length == 0) {
            return "";
        }
        // ORACLE-DEP [ORACLE_JAVA_API:ORACLE_CLOB] CLOB.getSubString(1, length) — 1-based LOB offset (Oracle-specific)
        String content = clob.getSubString(1, (int) length);
        log.debug("Read CLOB content ({} chars)", length);
        return content;
    }

    /**
     * Reads a CLOB from a ResultSet column and returns it as a String.
     *
     * Casts to OracleResultSet to access the Oracle-specific getCLOB() method which
     * returns oracle.sql.CLOB rather than the standard java.sql.Clob.
     *
     * ORACLE-DEP [ORACLE_JAVA_API:ORACLE_RESULT_SET] oracle.jdbc.OracleResultSet.getCLOB()
     */
    @OracleDependency(
            domain = DependencyDomain.ORACLE_JAVA_API,
            category = "ORACLE_RESULT_SET",
            description = "Reads CLOB from ResultSet using OracleResultSet.getCLOB() to get oracle.sql.CLOB",
            complexity = MigrationComplexity.MEDIUM,
            automationLevel = AutomationLevel.FULL,
            migrationTarget = "Replace with ResultSet.getString() for PostgreSQL TEXT columns"
    )
    public String readClobFromResultSet(ResultSet rs, String columnName) throws SQLException {
        // ORACLE-DEP [ORACLE_JAVA_API:ORACLE_RESULT_SET] Cast to OracleResultSet for getCLOB() access
        if (rs instanceof OracleResultSet) {
            OracleResultSet ors = (OracleResultSet) rs;
            // ORACLE-DEP [ORACLE_JAVA_API:ORACLE_RESULT_SET] OracleResultSet.getCLOB() returns oracle.sql.CLOB
            CLOB clob = ors.getCLOB(columnName);
            if (clob == null || clob.length() == 0) {
                return null;
            }
            return clobToString(clob);
        } else {
            // Fallback for non-Oracle ResultSet (e.g. in unit tests with mock data)
            log.debug("ResultSet is not OracleResultSet; falling back to standard getClob for column: {}",
                    columnName);
            java.sql.Clob stdClob = rs.getClob(columnName);
            if (stdClob == null) return null;
            try (Reader reader = stdClob.getCharacterStream()) {
                StringBuilder sb = new StringBuilder();
                char[] buffer = new char[4096];
                int read;
                while ((read = reader.read(buffer)) != -1) {
                    sb.append(buffer, 0, read);
                }
                return sb.toString();
            } catch (IOException e) {
                throw new SQLException("Failed to read CLOB character stream for column: " + columnName, e);
            }
        }
    }

    /**
     * Releases a temporary Oracle CLOB, returning its space to the temp tablespace.
     * Should always be called in a finally block after stringToCLOB().
     *
     * ORACLE-DEP [ORACLE_JAVA_API:ORACLE_CLOB] CLOB.freeTemporary() — Oracle-specific cleanup
     */
    public void freeTemporaryClob(CLOB clob) {
        if (clob != null) {
            try {
                // ORACLE-DEP [ORACLE_JAVA_API:ORACLE_CLOB] CLOB.freeTemporary() releases Oracle temp tablespace space
                if (clob.isTemporary()) {
                    clob.freeTemporary();
                    log.debug("Freed temporary CLOB");
                }
            } catch (SQLException e) {
                log.warn("Failed to free temporary CLOB: {}", e.getMessage());
            }
        }
    }

    // -----------------------------------------------------------------------
    // BLOB operations
    // -----------------------------------------------------------------------

    /**
     * Creates a temporary Oracle BLOB from a byte array.
     *
     * ORACLE-DEP [ORACLE_JAVA_API:ORACLE_BLOB] oracle.sql.BLOB.createTemporary for in-memory BLOB
     */
    @OracleDependency(
            domain = DependencyDomain.ORACLE_JAVA_API,
            category = "ORACLE_BLOB",
            description = "Creates an Oracle temporary BLOB from a byte array using BLOB.createTemporary()",
            complexity = MigrationComplexity.MEDIUM,
            automationLevel = AutomationLevel.FULL,
            migrationTarget = "Replace with PreparedStatement.setBytes() for PostgreSQL BYTEA columns"
    )
    public BLOB bytesToBLOB(OracleConnection conn, byte[] data) throws SQLException {
        if (data == null) {
            return null;
        }
        // ORACLE-DEP [ORACLE_JAVA_API:ORACLE_BLOB] BLOB.createTemporary() allocates in Oracle temp tablespace
        BLOB blob = BLOB.createTemporary(conn, true, BLOB.DURATION_SESSION);
        // ORACLE-DEP [ORACLE_JAVA_API:ORACLE_BLOB] BLOB.setBytes(1, data) — 1-based Oracle LOB offset
        blob.setBytes(1, data);
        log.debug("Created temporary BLOB ({} bytes)", data.length);
        return blob;
    }

    /**
     * Reads an Oracle BLOB and returns its full content as a byte array.
     *
     * ORACLE-DEP [ORACLE_JAVA_API:ORACLE_BLOB] oracle.sql.BLOB.getBytes with 1-based offset
     */
    @OracleDependency(
            domain = DependencyDomain.ORACLE_JAVA_API,
            category = "ORACLE_BLOB",
            description = "Reads Oracle BLOB to byte[] using BLOB.getBytes() with 1-based Oracle indexing",
            complexity = MigrationComplexity.LOW,
            automationLevel = AutomationLevel.FULL,
            migrationTarget = "Replace with ResultSet.getBytes() for PostgreSQL BYTEA columns"
    )
    public byte[] blobToBytes(BLOB blob) throws SQLException {
        if (blob == null) {
            return null;
        }
        long length = blob.length();
        if (length == 0) {
            return new byte[0];
        }
        // ORACLE-DEP [ORACLE_JAVA_API:ORACLE_BLOB] BLOB.getBytes(1, length) — 1-based LOB offset (Oracle-specific)
        byte[] data = blob.getBytes(1, (int) length);
        log.debug("Read BLOB content ({} bytes)", length);
        return data;
    }

    /**
     * Releases a temporary Oracle BLOB, returning its space to the temp tablespace.
     *
     * ORACLE-DEP [ORACLE_JAVA_API:ORACLE_BLOB] BLOB.freeTemporary() — Oracle-specific cleanup
     */
    public void freeTemporaryBlob(BLOB blob) {
        if (blob != null) {
            try {
                // ORACLE-DEP [ORACLE_JAVA_API:ORACLE_BLOB] BLOB.freeTemporary() releases Oracle temp tablespace space
                if (blob.isTemporary()) {
                    blob.freeTemporary();
                    log.debug("Freed temporary BLOB");
                }
            } catch (SQLException e) {
                log.warn("Failed to free temporary BLOB: {}", e.getMessage());
            }
        }
    }
}
