package com.meridianbank.dao;

import com.meridianbank.annotation.AutomationLevel;
import com.meridianbank.annotation.DependencyDomain;
import com.meridianbank.annotation.MigrationComplexity;
import com.meridianbank.annotation.OracleDependency;
import com.meridianbank.dto.DocumentSearchDTO;
import oracle.jdbc.OracleResultSet;
import oracle.sql.BLOB;
import oracle.sql.CLOB;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * DocumentOracleDAO — raw JDBC data access for document storage and retrieval.
 *
 * Demonstrates Oracle LOB patterns:
 * - EMPTY_CLOB() / EMPTY_BLOB() two-phase write
 * - OracleResultSet.getCLOB() / getBLOB() for Oracle-typed LOB access
 * - oracle.sql.CLOB and oracle.sql.BLOB method calls (1-based offsets)
 * - DBMS_LOB.GETLENGTH() and DBMS_LOB.SUBSTR() for server-side LOB operations
 * - Oracle Text CONTAINS() and SCORE() for full-text search
 * - Oracle query hints for index selection
 */
@Repository
public class DocumentOracleDAO {

    private static final Logger log = LoggerFactory.getLogger(DocumentOracleDAO.class);

    @Autowired
    private DataSource dataSource;

    // -------------------------------------------------------------------------
    // METHOD 1: saveDocumentWithCLOB
    // -------------------------------------------------------------------------

    /**
     * Saves a document using the Oracle two-phase CLOB write pattern.
     *
     * Phase 1: INSERT with EMPTY_CLOB() to obtain a LOB locator in the row.
     * Phase 2: SELECT ... FOR UPDATE to lock the row, retrieve the LOB locator,
     *          and write content via oracle.sql.CLOB.setString().
     *
     * This two-phase approach is required because Oracle does not allow direct
     * LOB data in INSERT statements for large CLOB values via the standard JDBC
     * path when using Oracle-native LOB types.
     */
    @OracleDependency(
        domain = DependencyDomain.EMBEDDED_SQL,
        category = "EMPTY_CLOB",
        description = "EMPTY_CLOB() two-phase write: INSERT with locator placeholder, then SELECT FOR UPDATE to write content",
        complexity = MigrationComplexity.MEDIUM,
        automationLevel = AutomationLevel.PARTIAL,
        migrationTarget = "Replace with single PreparedStatement.setString() for PostgreSQL TEXT columns — no two-phase write needed"
    )
    public Long saveDocumentWithCLOB(DocumentSearchDTO doc) {
        // ORACLE-DEP [EMBEDDED_SQL:EMPTY_CLOB] EMPTY_CLOB() initializes CLOB locator — must select FOR UPDATE before writing
        // ORACLE-DEP [EMBEDDED_SQL:SEQUENCE] DOC_SEQ.NEXTVAL for primary key generation
        // ORACLE-DEP [EMBEDDED_SQL:SYSDATE] SYSDATE assigns the current server date as indexed_date
        final String insertSql =
            "INSERT INTO MERIDIAN.DOCUMENTS " +
            "  (document_id, title, content, category, customer_id, indexed_date) " +
            "VALUES " +
            "  (MERIDIAN.DOCUMENT_SEQ.NEXTVAL, ?, EMPTY_CLOB(), ?, ?, SYSDATE)";

        // ORACLE-DEP [EMBEDDED_SQL:DUAL_TABLE] FROM DUAL for scalar sequence value retrieval
        final String currvalSql =
            "SELECT MERIDIAN.DOCUMENT_SEQ.CURRVAL FROM DUAL";

        // ORACLE-DEP [ORACLE_JAVA_API:ORACLE_RESULT_SET] OracleResultSet.getCLOB() returns oracle.sql.CLOB locator
        final String selectForUpdateSql =
            "SELECT content FROM MERIDIAN.DOCUMENTS WHERE document_id = ? FOR UPDATE";

        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);

            // ---- Phase 1: INSERT row with EMPTY_CLOB() locator ----
            try (PreparedStatement insertStmt = conn.prepareStatement(insertSql)) {
                insertStmt.setString(1, doc.getTitle());
                insertStmt.setString(2, doc.getCategory());
                insertStmt.setObject(3, doc.getCustomerId());
                int rows = insertStmt.executeUpdate();
                if (rows != 1) {
                    conn.rollback();
                    throw new SQLException("Document INSERT affected " + rows + " rows — expected 1");
                }
            }

            // ---- Retrieve the generated doc_id from CURRVAL ----
            Long docId;
            try (PreparedStatement currvalStmt = conn.prepareStatement(currvalSql);
                 ResultSet rs = currvalStmt.executeQuery()) {
                if (!rs.next()) {
                    conn.rollback();
                    throw new SQLException("Could not retrieve CURRVAL for DOC_SEQ");
                }
                docId = rs.getLong(1);
            }

            // ---- Phase 2: SELECT FOR UPDATE and write CLOB content ----
            try (PreparedStatement selectStmt = conn.prepareStatement(selectForUpdateSql)) {
                selectStmt.setLong(1, docId);
                try (ResultSet rs = selectStmt.executeQuery()) {
                    if (!rs.next()) {
                        conn.rollback();
                        throw new SQLException("Document row not found for doc_id=" + docId + " in FOR UPDATE query");
                    }

                    // ORACLE-DEP [ORACLE_JAVA_API:ORACLE_RESULT_SET] Cast to OracleResultSet to access getCLOB()
                    OracleResultSet oracleRs = (OracleResultSet) rs;
                    java.sql.Clob clob = oracleRs.getCLOB("content"); // ORACLE-DEP [ORACLE_JAVA_API:ORACLE_CLOB] getCLOB() retrieves CLOB locator

                    if (clob == null) {
                        conn.rollback();
                        throw new SQLException("CLOB locator is null after EMPTY_CLOB() initialization for doc_id=" + docId);
                    }

                    String content = doc.getHighlightedExcerpt() != null ? doc.getHighlightedExcerpt() : "";
                    // ORACLE-DEP [ORACLE_JAVA_API:ORACLE_CLOB] CLOB.setString(1, content) — 1-based Oracle LOB offset for write
                    clob.setString(1, content);
                }
            }

            conn.commit();
            log.debug("Saved document docId={} with CLOB content length={}", docId,
                doc.getHighlightedExcerpt() != null ? doc.getHighlightedExcerpt().length() : 0);
            return docId;

        } catch (SQLException e) {
            log.error("Failed to save document with CLOB content: title={}", doc.getTitle(), e);
            throw new RuntimeException("Error saving document CLOB for title=" + doc.getTitle(), e);
        }
    }

    // -------------------------------------------------------------------------
    // METHOD 2: readDocumentCLOB
    // -------------------------------------------------------------------------

    /**
     * Reads a document's CLOB content using oracle.sql.CLOB for Oracle-native access.
     * Uses the 1-based getSubString() method rather than standard JDBC Clob methods,
     * demonstrating the Oracle-proprietary CLOB API.
     */
    @OracleDependency(
        domain = DependencyDomain.ORACLE_JAVA_API,
        category = "ORACLE_CLOB",
        description = "oracle.sql.CLOB.getSubString(1, length) reads full CLOB content; 1-based offset is Oracle-specific",
        complexity = MigrationComplexity.MEDIUM,
        automationLevel = AutomationLevel.PARTIAL,
        migrationTarget = "Replace oracle.sql.CLOB with standard java.sql.Clob or ResultSet.getString() which works natively for PostgreSQL TEXT columns"
    )
    public String readDocumentCLOB(Long docId) {
        final String sql =
            "SELECT content FROM MERIDIAN.DOCUMENTS WHERE document_id = ?";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setLong(1, docId);

            try (ResultSet rs = stmt.executeQuery()) {
                if (!rs.next()) {
                    log.warn("No document found for doc_id={}", docId);
                    return null;
                }

                // ORACLE-DEP [ORACLE_JAVA_API:ORACLE_RESULT_SET] Cast to OracleResultSet to use Oracle-typed getCLOB()
                OracleResultSet oracleRs = (OracleResultSet) rs;
                CLOB clob = oracleRs.getCLOB("content"); // ORACLE-DEP [ORACLE_JAVA_API:ORACLE_CLOB] OracleResultSet.getCLOB() returns oracle.sql.CLOB

                if (clob == null || clob.isEmptyLob()) {
                    return "";
                }

                // ORACLE-DEP [ORACLE_JAVA_API:ORACLE_CLOB] CLOB.getSubString(1, length) — 1-based offset, Oracle-specific read method
                long length = clob.length();
                return clob.getSubString(1, (int) length);
            }

        } catch (SQLException e) {
            log.error("Failed to read CLOB content for doc_id={}", docId, e);
            throw new RuntimeException("Error reading document CLOB for docId=" + docId, e);
        }
    }

    // -------------------------------------------------------------------------
    // METHOD 3: getDocumentLobStats
    // -------------------------------------------------------------------------

    /**
     * Retrieves LOB metadata and a content preview using Oracle DBMS_LOB package functions.
     * DBMS_LOB.GETLENGTH() and DBMS_LOB.SUBSTR() operate server-side on the CLOB,
     * avoiding full LOB transfer to the application tier.
     */
    @OracleDependency(
        domain = DependencyDomain.EMBEDDED_SQL,
        category = "DBMS_LOB",
        description = "DBMS_LOB.GETLENGTH() and DBMS_LOB.SUBSTR() for CLOB metadata and preview without reading full LOB into the application",
        complexity = MigrationComplexity.MEDIUM,
        automationLevel = AutomationLevel.FULL,
        migrationTarget = "Replace DBMS_LOB.GETLENGTH(col) with LENGTH(col::TEXT) and DBMS_LOB.SUBSTR(col,n,1) with LEFT(col::TEXT, n) in PostgreSQL"
    )
    public Map<String, Object> getDocumentLobStats(Long docId) {
        // ORACLE-DEP [EMBEDDED_SQL:DBMS_LOB] DBMS_LOB.GETLENGTH() returns CLOB character count server-side
        // ORACLE-DEP [EMBEDDED_SQL:DBMS_LOB] DBMS_LOB.SUBSTR(content, 200, 1) extracts first 200 chars — 1-based offset
        final String sql =
            "SELECT document_id AS doc_id, " +
            "       title, " +
            "       DBMS_LOB.GETLENGTH(content)        AS content_length, " +
            "       DBMS_LOB.SUBSTR(content, 200, 1)   AS content_preview " +
            "FROM   MERIDIAN.DOCUMENTS " +
            "WHERE  document_id = ?";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setLong(1, docId);

            try (ResultSet rs = stmt.executeQuery()) {
                if (!rs.next()) {
                    log.warn("No document found for doc_id={} in LOB stats query", docId);
                    return null;
                }

                Map<String, Object> stats = new HashMap<>();
                stats.put("docId", rs.getLong("doc_id"));
                stats.put("title", rs.getString("title"));
                stats.put("contentLength", rs.getLong("content_length"));
                stats.put("contentPreview", rs.getString("content_preview"));
                return stats;
            }

        } catch (SQLException e) {
            log.error("Failed to retrieve LOB stats for doc_id={}", docId, e);
            throw new RuntimeException("Error retrieving document LOB stats for docId=" + docId, e);
        }
    }

    // -------------------------------------------------------------------------
    // METHOD 4: searchDocuments
    // -------------------------------------------------------------------------

    /**
     * Performs full-text document search using Oracle Text's CONTAINS() predicate.
     *
     * CONTAINS(column, query, label) queries a CONTEXT index built on the CLOB column.
     * SCORE(label) returns a relevance score (0–100) for ordering results.
     * The INDEX hint forces Oracle to use the Oracle Text CONTEXT index rather than
     * a fallback full-table scan.
     */
    @OracleDependency(
        domain = DependencyDomain.EMBEDDED_SQL,
        category = "ORACLE_TEXT_CONTAINS",
        description = "CONTAINS(d.content, ?, 1) > 0 invokes Oracle Text full-text search on CONTEXT index; SCORE(1) returns relevance ranking",
        complexity = MigrationComplexity.VERY_HIGH,
        automationLevel = AutomationLevel.MANUAL,
        migrationTarget = "Replace with PostgreSQL full-text search: to_tsvector('english', content) @@ plainto_tsquery(:query) with ts_rank() for relevance scoring"
    )
    public List<DocumentSearchDTO> searchDocuments(String query) {
        // ORACLE-DEP [EMBEDDED_SQL:QUERY_HINT] /*+ INDEX(d DOC_CONTENT_CTX_IDX) */ forces Oracle Text CONTEXT index usage
        // ORACLE-DEP [EMBEDDED_SQL:ORACLE_TEXT_CONTAINS] CONTAINS(d.content, ?, 1) Oracle Text full-text search predicate
        // ORACLE-DEP [EMBEDDED_SQL:ORACLE_TEXT_CONTAINS] SCORE(1) returns relevance ranking 0-100 from Oracle Text engine
        // ORACLE-DEP [EMBEDDED_SQL:DBMS_LOB] DBMS_LOB.SUBSTR for server-side content preview extraction
        final String sql =
            "SELECT /*+ INDEX(d IDX_DOC_CONTENT_TEXT) */ " +
            "       d.document_id AS doc_id, " +
            "       d.title, " +
            "       d.category, " +
            "       d.customer_id, " +
            "       SCORE(1)                            AS relevance_score, " +
            "       DBMS_LOB.SUBSTR(d.content, 300, 1) AS content_preview " +
            "FROM   MERIDIAN.DOCUMENTS d " +
            "WHERE  CONTAINS(d.content, ?, 1) > 0 " +
            "ORDER  BY SCORE(1) DESC";

        List<DocumentSearchDTO> results = new ArrayList<>();

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, query);

            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    DocumentSearchDTO dto = new DocumentSearchDTO();
                    dto.setDocumentId(rs.getLong("doc_id"));
                    dto.setTitle(rs.getString("title"));
                    dto.setCategory(rs.getString("category"));
                    dto.setDocumentCustomerId(rs.getLong("customer_id"));
                    dto.setHighlightedExcerpt(rs.getString("content_preview")); // content_preview maps to highlighted excerpt
                    results.add(dto);
                }
            }

            log.debug("Oracle Text search for query='{}' returned {} results", query, results.size());
            return results;

        } catch (SQLException e) {
            log.error("Failed to execute Oracle Text search for query='{}'", query, e);
            throw new RuntimeException("Error executing document full-text search for query=" + query, e);
        }
    }

    // -------------------------------------------------------------------------
    // METHOD 5: saveBlobDocument
    // -------------------------------------------------------------------------

    /**
     * Saves binary attachment data using the Oracle two-phase BLOB write pattern.
     * Mirrors the CLOB pattern: INSERT with EMPTY_BLOB() to get a locator,
     * then SELECT FOR UPDATE to write binary data via oracle.sql.BLOB.setBytes().
     */
    @OracleDependency(
        domain = DependencyDomain.EMBEDDED_SQL,
        category = "EMPTY_BLOB",
        description = "EMPTY_BLOB() two-phase write pattern for binary document attachment content",
        complexity = MigrationComplexity.MEDIUM,
        automationLevel = AutomationLevel.PARTIAL,
        migrationTarget = "Replace with standard PreparedStatement.setBytes() for PostgreSQL BYTEA columns — no two-phase write needed"
    )
    public void saveBlobDocument(Long docId, byte[] content) {
        // ORACLE-DEP [EMBEDDED_SQL:EMPTY_BLOB] EMPTY_BLOB() initializes BLOB locator placeholder in the inserted row
        // ORACLE-DEP [EMBEDDED_SQL:SEQUENCE] ATTACH_SEQ.NEXTVAL generates the attachment primary key
        // ORACLE-DEP [EMBEDDED_SQL:SYSDATE] SYSDATE records attachment creation timestamp
        final String insertSql =
            "INSERT INTO MERIDIAN.DOCUMENT_ATTACHMENTS " +
            "  (attach_id, doc_id, content, created_date) " +
            "VALUES " +
            "  (MERIDIAN.ATTACH_SEQ.NEXTVAL, ?, EMPTY_BLOB(), SYSDATE)";

        // ORACLE-DEP [ORACLE_JAVA_API:ORACLE_BLOB] OracleResultSet.getBLOB() returns oracle.sql.BLOB locator
        final String selectForUpdateSql =
            "SELECT content FROM MERIDIAN.DOCUMENT_ATTACHMENTS WHERE document_id = ? FOR UPDATE";

        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);

            // ---- Phase 1: INSERT with EMPTY_BLOB() ----
            try (PreparedStatement insertStmt = conn.prepareStatement(insertSql)) {
                insertStmt.setLong(1, docId);
                int rows = insertStmt.executeUpdate();
                if (rows != 1) {
                    conn.rollback();
                    throw new SQLException("Attachment INSERT affected " + rows + " rows — expected 1 for doc_id=" + docId);
                }
            }

            // ---- Phase 2: SELECT FOR UPDATE and write BLOB content ----
            try (PreparedStatement selectStmt = conn.prepareStatement(selectForUpdateSql)) {
                selectStmt.setLong(1, docId);

                try (ResultSet rs = selectStmt.executeQuery()) {
                    if (!rs.next()) {
                        conn.rollback();
                        throw new SQLException("Attachment row not found for doc_id=" + docId + " in FOR UPDATE query");
                    }

                    // ORACLE-DEP [ORACLE_JAVA_API:ORACLE_RESULT_SET] Cast to OracleResultSet to use Oracle-typed getBLOB()
                    OracleResultSet oracleRs = (OracleResultSet) rs;
                    BLOB blob = oracleRs.getBLOB("content"); // ORACLE-DEP [ORACLE_JAVA_API:ORACLE_BLOB] OracleResultSet.getBLOB() returns oracle.sql.BLOB locator

                    if (blob == null) {
                        conn.rollback();
                        throw new SQLException("BLOB locator is null after EMPTY_BLOB() initialization for doc_id=" + docId);
                    }

                    byte[] data = content != null ? content : new byte[0];
                    // ORACLE-DEP [ORACLE_JAVA_API:ORACLE_BLOB] BLOB.setBytes(1, data) — 1-based Oracle LOB offset for binary write
                    blob.setBytes(1, data);
                }
            }

            conn.commit();
            log.debug("Saved BLOB attachment for doc_id={} length={}", docId,
                content != null ? content.length : 0);

        } catch (SQLException e) {
            log.error("Failed to save BLOB attachment for doc_id={}", docId, e);
            throw new RuntimeException("Error saving document BLOB attachment for docId=" + docId, e);
        }
    }
}
