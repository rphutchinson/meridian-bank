package com.meridianbank.service;

import com.meridianbank.annotation.AutomationLevel;
import com.meridianbank.annotation.DependencyDomain;
import com.meridianbank.annotation.MigrationComplexity;
import com.meridianbank.annotation.OracleDependency;
import com.meridianbank.dao.DocumentOracleDAO;
import com.meridianbank.dto.DocumentSearchDTO;
import com.meridianbank.entity.DocumentEntity;
import com.meridianbank.exception.MeridianBankException;
import com.meridianbank.exception.OracleErrorTranslator;
import com.meridianbank.repository.DocumentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * DocumentSearchService -- business logic for Oracle Text-indexed document storage and retrieval.
 *
 * <p>This service is the primary consumer of two categories of Oracle dependencies:
 * <ul>
 *   <li><strong>Oracle Text (CTXSYS)</strong> -- full-text indexing via CONTEXT indexes,
 *       CONTAINS() predicate, SCORE() relevance function, and CTX_DDL utilities.
 *       Migration target: PostgreSQL full-text search (tsvector / tsquery / GIN index)
 *       or Elasticsearch integration.</li>
 *   <li><strong>Oracle LOB API</strong> -- CLOB and BLOB storage via oracle.sql.CLOB /
 *       oracle.sql.BLOB, DBMS_LOB.GETLENGTH, EMPTY_CLOB() / EMPTY_BLOB() initialisation.
 *       Migration target: standard JDBC Clob/Blob or PostgreSQL TEXT / BYTEA columns.</li>
 * </ul>
 */
@Service
public class DocumentSearchService {

    private static final Logger log = LoggerFactory.getLogger(DocumentSearchService.class);

    @Autowired
    private DocumentRepository documentRepository;

    @Autowired
    private DocumentOracleDAO documentOracleDAO;

    @Autowired
    private OracleErrorTranslator oracleErrorTranslator;

    // -------------------------------------------------------------------------
    // Write operations
    // -------------------------------------------------------------------------

    /**
     * Uploads a new document and persists its content as an Oracle CLOB.
     *
     * <p>The DAO implementation uses the two-phase Oracle LOB write pattern:
     * INSERT with EMPTY_CLOB(), SELECT the row back using the RETURNING pseudo-column
     * to obtain the LOB locator, then write content via oracle.sql.CLOB.
     * Standard JDBC Clob does not require this two-phase approach.
     *
     * @param doc DTO containing title, category, customerId, and full text content
     * @return the assigned document ID
     * @throws MeridianBankException if Oracle LOB write fails
     */
    // ORACLE-DEP [ORACLE_JAVA_API:ORACLE_CLOB] Document content written via oracle.sql.CLOB
    @Transactional
    @OracleDependency(
        domain = DependencyDomain.ORACLE_JAVA_API,
        category = "ORACLE_CLOB",
        description = "Delegates to DocumentOracleDAO.saveDocumentWithCLOB() which uses EMPTY_CLOB() "
                    + "to initialise the LOB column then writes content through an oracle.sql.CLOB "
                    + "locator obtained via SELECT ... FOR UPDATE",
        complexity = MigrationComplexity.MEDIUM,
        automationLevel = AutomationLevel.PARTIAL,
        migrationTarget = "Replace EMPTY_CLOB() + oracle.sql.CLOB locator write with standard JDBC "
                        + "PreparedStatement.setClob() or setString() against a PostgreSQL TEXT column"
    )
    public Long uploadDocument(DocumentSearchDTO doc) {
        if (doc == null) {
            throw new MeridianBankException("INVALID_INPUT", "Document DTO must not be null", 400);
        }
        if (doc.getTitle() == null || doc.getTitle().isBlank()) {
            throw new MeridianBankException("INVALID_INPUT", "Document title is required", 400);
        }
        log.info("Uploading document '{}' for customer {} via Oracle CLOB", doc.getTitle(), doc.getCustomerId());
        try {
            // ORACLE-DEP [ORACLE_JAVA_API:ORACLE_CLOB] saveDocumentWithCLOB uses EMPTY_CLOB() + locator write
            Long docId = documentOracleDAO.saveDocumentWithCLOB(doc);
            log.info("Document uploaded successfully with id {}", docId);
            return docId;
        } catch (Exception e) {
            log.error("Failed to upload document for customer {}: {}", doc.getCustomerId(), e.getMessage(), e);
            if (e.getCause() instanceof SQLException sqlEx) {
                throw oracleErrorTranslator.translateSQLException(sqlEx);
            }
            throw new MeridianBankException("DOC_UPLOAD_FAILED",
                "Failed to upload document: " + e.getMessage(), 500, e);
        }
    }

    /**
     * Persists binary content for an existing document using an Oracle BLOB locator.
     *
     * <p>Similar to the CLOB pattern but for raw binary data (PDFs, images, etc.).
     * The DAO uses oracle.sql.BLOB and EMPTY_BLOB() initialisation.  PostgreSQL
     * equivalent uses BYTEA columns with standard PreparedStatement.setBytes().
     *
     * @param docId    the document to update
     * @param content  the binary content to store
     * @throws MeridianBankException if the document does not exist or Oracle BLOB write fails
     */
    // ORACLE-DEP [ORACLE_JAVA_API:ORACLE_BLOB] Binary document content via oracle.sql.BLOB
    @Transactional
    @OracleDependency(
        domain = DependencyDomain.ORACLE_JAVA_API,
        category = "ORACLE_BLOB",
        description = "Delegates to DocumentOracleDAO.saveBlobDocument() which uses EMPTY_BLOB() "
                    + "initialisation and writes binary content via oracle.sql.BLOB locator obtained "
                    + "from a SELECT ... FOR UPDATE",
        complexity = MigrationComplexity.MEDIUM,
        automationLevel = AutomationLevel.PARTIAL,
        migrationTarget = "Replace EMPTY_BLOB() + oracle.sql.BLOB locator write with "
                        + "PreparedStatement.setBytes() or setBlob() against a PostgreSQL BYTEA column"
    )
    public void saveBinaryDocument(Long docId, byte[] content) {
        if (docId == null) {
            throw new MeridianBankException("INVALID_INPUT", "Document ID must not be null", 400);
        }
        if (content == null || content.length == 0) {
            throw new MeridianBankException("INVALID_INPUT", "Binary content must not be empty", 400);
        }
        // Verify document exists before attempting LOB write
        documentRepository.findById(docId)
            .orElseThrow(() -> new MeridianBankException("DOC_NOT_FOUND",
                "No document found with ID: " + docId, 404));

        log.info("Saving binary content ({} bytes) for document {} via Oracle BLOB", content.length, docId);
        try {
            // ORACLE-DEP [ORACLE_JAVA_API:ORACLE_BLOB] saveBlobDocument uses EMPTY_BLOB() + oracle.sql.BLOB
            documentOracleDAO.saveBlobDocument(docId, content);
            log.info("Binary content saved for document {}", docId);
        } catch (Exception e) {
            log.error("Failed to save binary content for document {}: {}", docId, e.getMessage(), e);
            if (e.getCause() instanceof SQLException sqlEx) {
                throw oracleErrorTranslator.translateSQLException(sqlEx);
            }
            throw new MeridianBankException("BLOB_WRITE_FAILED",
                "Failed to save binary content: " + e.getMessage(), 500, e);
        }
    }

    // -------------------------------------------------------------------------
    // Read operations
    // -------------------------------------------------------------------------

    /**
     * Retrieves the full text content of a document CLOB by document ID.
     *
     * <p>The DAO reads Oracle CLOB data using oracle.sql.CLOB.getSubString() rather
     * than streaming via Reader -- appropriate for documents expected to fit in JVM
     * memory (up to ~100 MB).  For larger CLOBs the streaming pattern with
     * getCharacterStream() should be used.
     *
     * @param docId the document to retrieve
     * @return the full text content of the document
     * @throws MeridianBankException if not found or CLOB read fails
     */
    // ORACLE-DEP [ORACLE_JAVA_API:ORACLE_CLOB] Document content read via oracle.sql.CLOB
    @Transactional(readOnly = true)
    @OracleDependency(
        domain = DependencyDomain.ORACLE_JAVA_API,
        category = "ORACLE_CLOB",
        description = "Delegates to DocumentOracleDAO.readDocumentCLOB() which reads Oracle CLOB "
                    + "content using oracle.sql.CLOB.getSubString() -- bypassing standard JDBC Clob "
                    + "streaming for moderate-sized documents",
        complexity = MigrationComplexity.MEDIUM,
        automationLevel = AutomationLevel.PARTIAL,
        migrationTarget = "Replace oracle.sql.CLOB read with ResultSet.getString() or "
                        + "ResultSet.getClob().getCharacterStream() against a PostgreSQL TEXT column; "
                        + "getSubString() offset/length pattern maps to SUBSTRING() in PostgreSQL"
    )
    public String getDocumentContent(Long docId) {
        if (docId == null) {
            throw new MeridianBankException("INVALID_INPUT", "Document ID must not be null", 400);
        }
        // Verify document exists
        documentRepository.findById(docId)
            .orElseThrow(() -> new MeridianBankException("DOC_NOT_FOUND",
                "No document found with ID: " + docId, 404));

        log.debug("Reading CLOB content for document {}", docId);
        try {
            // ORACLE-DEP [ORACLE_JAVA_API:ORACLE_CLOB] readDocumentCLOB uses oracle.sql.CLOB
            String content = documentOracleDAO.readDocumentCLOB(docId);
            if (content == null) {
                log.warn("Document {} exists but has no CLOB content", docId);
                return "";
            }
            return content;
        } catch (Exception e) {
            log.error("Failed to read CLOB for document {}: {}", docId, e.getMessage(), e);
            if (e.getCause() instanceof SQLException sqlEx) {
                throw oracleErrorTranslator.translateSQLException(sqlEx);
            }
            throw new MeridianBankException("CLOB_READ_FAILED",
                "Failed to read document content: " + e.getMessage(), 500, e);
        }
    }

    /**
     * Returns LOB statistics for a document including CLOB and BLOB sizes
     * computed via Oracle DBMS_LOB.GETLENGTH.
     *
     * <p>DBMS_LOB.GETLENGTH is a PL/SQL package function called inline in a
     * SELECT statement.  PostgreSQL uses the standard LENGTH() / OCTET_LENGTH()
     * built-in functions instead.
     *
     * @param docId the document to inspect
     * @return map with keys: document_id, clob_length, blob_length, has_text_content, has_binary_content
     */
    // ORACLE-DEP [EMBEDDED_SQL:DBMS_LOB] LOB size via DBMS_LOB.GETLENGTH()
    @Transactional(readOnly = true)
    @OracleDependency(
        domain = DependencyDomain.EMBEDDED_SQL,
        category = "DBMS_LOB",
        description = "Delegates to DocumentOracleDAO.getDocumentLobStats() which uses "
                    + "DBMS_LOB.GETLENGTH(content) and DBMS_LOB.GETLENGTH(binary_content) inline "
                    + "in a SELECT to return LOB byte lengths without reading the full data",
        complexity = MigrationComplexity.MEDIUM,
        automationLevel = AutomationLevel.PARTIAL,
        migrationTarget = "Replace DBMS_LOB.GETLENGTH(col) with OCTET_LENGTH(col) for BYTEA or "
                        + "LENGTH(col) for TEXT in PostgreSQL; no PL/SQL package call needed"
    )
    public Map<String, Object> getDocumentStats(Long docId) {
        if (docId == null) {
            throw new MeridianBankException("INVALID_INPUT", "Document ID must not be null", 400);
        }
        log.debug("Fetching LOB stats for document {} via DBMS_LOB.GETLENGTH", docId);
        try {
            // ORACLE-DEP [EMBEDDED_SQL:DBMS_LOB] getDocumentLobStats uses DBMS_LOB.GETLENGTH
            return documentOracleDAO.getDocumentLobStats(docId);
        } catch (Exception e) {
            log.error("Failed to retrieve LOB stats for document {}: {}", docId, e.getMessage(), e);
            if (e.getCause() instanceof SQLException sqlEx) {
                throw oracleErrorTranslator.translateSQLException(sqlEx);
            }
            throw new MeridianBankException("LOB_STAT_FAILED",
                "Failed to get document stats: " + e.getMessage(), 500, e);
        }
    }

    // -------------------------------------------------------------------------
    // Full-text search operations
    // -------------------------------------------------------------------------

    /**
     * Searches documents using an Oracle Text CONTAINS query.
     *
     * <p>Oracle Text's CONTAINS() predicate queries a CONTEXT index on the
     * content CLOB column.  The query string supports Boolean operators
     * (AND, OR, NOT, NEAR, WITHIN) and thesaurus expansion -- none of which
     * are directly portable.  PostgreSQL equivalent uses tsvector / tsquery
     * with to_tsquery() and @@ match operator against a GIN index.
     *
     * @param query Oracle Text query string (e.g., "mortgage AND approval")
     * @return list of matching document DTOs with relevance scores
     * @throws MeridianBankException if the CONTAINS query execution fails
     */
    // ORACLE-DEP [EMBEDDED_SQL:ORACLE_TEXT_CONTAINS] Full-text search via Oracle Text CONTAINS()
    @Transactional(readOnly = true)
    @OracleDependency(
        domain = DependencyDomain.EMBEDDED_SQL,
        category = "ORACLE_TEXT_CONTAINS",
        description = "Delegates to DocumentOracleDAO.searchDocuments() which executes "
                    + "WHERE CONTAINS(content, :query) > 0 ORDER BY SCORE(1) DESC against a "
                    + "CONTEXT index created with CTX_DDL on the document content CLOB column",
        complexity = MigrationComplexity.HIGH,
        automationLevel = AutomationLevel.PARTIAL,
        migrationTarget = "Replace Oracle Text CONTAINS() with PostgreSQL full-text search: "
                        + "WHERE to_tsvector('english', content) @@ to_tsquery('english', :query) "
                        + "with a GIN index on to_tsvector(content); rewrite Boolean operators "
                        + "from Oracle Text syntax to PostgreSQL tsquery syntax"
    )
    public List<DocumentSearchDTO> searchDocuments(String query) {
        if (query == null || query.isBlank()) {
            return Collections.emptyList();
        }
        log.info("Executing Oracle Text CONTAINS search: '{}'", query);
        try {
            // ORACLE-DEP [EMBEDDED_SQL:ORACLE_TEXT_CONTAINS] searchDocuments uses CONTAINS() + SCORE()
            List<DocumentSearchDTO> results = documentOracleDAO.searchDocuments(query);
            log.info("Oracle Text search returned {} results for query '{}'", results.size(), query);
            return results;
        } catch (Exception e) {
            log.error("Oracle Text CONTAINS search failed for query '{}': {}", query, e.getMessage(), e);
            if (e.getCause() instanceof SQLException sqlEx) {
                throw oracleErrorTranslator.translateSQLException(sqlEx);
            }
            throw new MeridianBankException("TEXT_SEARCH_FAILED",
                "Full-text search failed: " + e.getMessage(), 500, e);
        }
    }

    /**
     * Searches documents for a specific customer using Oracle Text CONTAINS,
     * post-filtered by customer ID.
     *
     * <p>This method demonstrates combining Oracle Text search with a relational
     * filter -- a pattern common in Oracle applications where Text indexes are
     * defined on LOB columns that also have relational FK columns.  The Oracle
     * Text CONTEXT index does not natively filter on relational columns during the
     * CONTAINS scan, so the customerId filter is applied in the WHERE clause or
     * in application code after the CONTAINS result set is obtained.
     *
     * @param customerId restrict results to documents owned by this customer
     * @param query      Oracle Text query string
     * @return filtered list of matching document DTOs
     */
    // ORACLE-DEP [EMBEDDED_SQL:ORACLE_TEXT_CONTAINS] Customer-scoped Oracle Text search
    @Transactional(readOnly = true)
    @OracleDependency(
        domain = DependencyDomain.EMBEDDED_SQL,
        category = "ORACLE_TEXT_CONTAINS",
        description = "Executes Oracle Text CONTAINS search then filters results by customer_id; "
                    + "demonstrates relational filtering combined with a CONTEXT index scan",
        complexity = MigrationComplexity.HIGH,
        automationLevel = AutomationLevel.PARTIAL,
        migrationTarget = "In PostgreSQL: WHERE to_tsvector('english', content) @@ to_tsquery(:query) "
                        + "AND customer_id = :customerId; GIN index supports combined predicate efficiently"
    )
    public List<DocumentSearchDTO> fullTextSearchByCustomer(Long customerId, String query) {
        if (customerId == null) {
            throw new MeridianBankException("INVALID_INPUT", "Customer ID must not be null", 400);
        }
        if (query == null || query.isBlank()) {
            return Collections.emptyList();
        }
        log.debug("Oracle Text customer-scoped search: customerId={}, query='{}'", customerId, query);
        // ORACLE-DEP [EMBEDDED_SQL:ORACLE_TEXT_CONTAINS] searchDocuments uses CONTAINS() predicate
        List<DocumentSearchDTO> allResults = searchDocuments(query);
        // Post-filter by customer ID -- the Oracle Text index covers the entire table
        List<DocumentSearchDTO> filtered = allResults.stream()
            .filter(d -> customerId.equals(d.getCustomerId())
                      || customerId.equals(d.getDocumentCustomerId()))
            .collect(Collectors.toList());
        log.debug("Customer {} search: {} of {} results matched", customerId, filtered.size(), allResults.size());
        return filtered;
    }

    // -------------------------------------------------------------------------
    // Generic CRUD
    // -------------------------------------------------------------------------

    /**
     * Returns all documents using the standard JPA repository.
     * No Oracle-specific dependencies -- uses JPQL SELECT e FROM DocumentEntity e.
     *
     * @return list of all document DTOs
     */
    @Transactional(readOnly = true)
    public List<DocumentSearchDTO> getAllDocuments() {
        log.debug("Fetching all documents via JPA findAll");
        List<DocumentEntity> entities = documentRepository.findAll();
        return entities.stream()
                       .map(this::mapEntityToDto)
                       .collect(Collectors.toList());
    }

    // -------------------------------------------------------------------------
    // Private mapping helpers
    // -------------------------------------------------------------------------

    private DocumentSearchDTO mapEntityToDto(DocumentEntity entity) {
        DocumentSearchDTO dto = new DocumentSearchDTO();
        dto.setDocumentId(entity.getDocumentId());
        dto.setTitle(entity.getTitle());
        dto.setDocumentCustomerId(entity.getCustomerId());
        if (entity.getIndexedDate() != null) {
            dto.setIndexedDate(entity.getIndexedDate());
        }
        return dto;
    }
}
