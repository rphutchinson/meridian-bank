package com.meridianbank.controller;

import com.meridianbank.dto.DocumentSearchDTO;
import com.meridianbank.service.DocumentSearchService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * REST controller for Document storage and full-text search operations.
 *
 * <p>Thin HTTP adapter over DocumentSearchService. Oracle Text — the primary
 * Oracle dependency exercised by this controller — uses a CONTEXT index
 * created by CTX_DDL.CREATE_INDEX and queried via the CONTAINS() operator.
 * The migrated application would use PostgreSQL full-text search (tsvector /
 * tsquery) or a dedicated search engine (Elasticsearch).
 *
 * <p>Oracle dependencies in the backing service layer:
 * ORACLE-DEP [EMBEDDED_SQL:ORACLE_TEXT_CONTAINS]   searchDocuments() — CONTAINS() operator
 * ORACLE-DEP [EMBEDDED_SQL:ORACLE_TEXT_CTX_DDL]    index management in DocumentOracleDAO
 * ORACLE-DEP [EMBEDDED_SQL:CLOB_TYPE]              content stored as Oracle CLOB
 * ORACLE-DEP [EMBEDDED_SQL:EMPTY_CLOB]             EMPTY_CLOB() initialisation in DAO
 * ORACLE-DEP [ORACLE_JAVA_API:ORACLE_CLOB]         oracle.sql.CLOB in DocumentOracleDAO
 * ORACLE-DEP [ORACLE_JAVA_API:ORACLE_BLOB]         oracle.sql.BLOB for binary attachments
 */
@RestController
@RequestMapping("/api/documents")
@CrossOrigin(origins = "*")
public class DocumentController {

    private static final Logger log = LoggerFactory.getLogger(DocumentController.class);

    private final DocumentSearchService documentSearchService;

    @Autowired
    public DocumentController(DocumentSearchService documentSearchService) {
        this.documentSearchService = documentSearchService;
    }

    // -------------------------------------------------------------------------
    // POST /api/documents
    // Uploads / indexes a document.
    // Body: DocumentSearchDTO { customerId, title, content, category }
    // DocumentSearchService.uploadDocument() writes the content to an Oracle CLOB
    // using EMPTY_CLOB() initialisation and oracle.sql.CLOB streaming, then
    // updates the Oracle Text CONTEXT index via CTX_DDL.SYNC_INDEX.
    // Returns HTTP 201 Created with the assigned document ID.
    // -------------------------------------------------------------------------
    @PostMapping
    public ResponseEntity<Map<String, Object>> uploadDocument(
            @RequestBody DocumentSearchDTO doc) {

        log.debug("POST /api/documents customerId={} title={}", doc.getCustomerId(), doc.getTitle());
        Long documentId = documentSearchService.uploadDocument(doc);
        Map<String, Object> response = new HashMap<>();
        response.put("documentId", documentId);
        response.put("title", doc.getTitle());
        response.put("status", "INDEXED");
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    // -------------------------------------------------------------------------
    // GET /api/documents/search?q=mortgage+application
    // Full-text search using Oracle Text CONTAINS() operator.
    // DocumentSearchService.searchDocuments() issues:
    //   WHERE CONTAINS(content, :query, 1) > 0
    //   ORDER BY SCORE(1) DESC
    // The SCORE() function is an Oracle Text built-in with no direct PostgreSQL
    // equivalent — migrates to ts_rank(tsvector, tsquery).
    // -------------------------------------------------------------------------
    @GetMapping("/search")
    public ResponseEntity<List<DocumentSearchDTO>> searchDocuments(
            @RequestParam("q") String query) {

        log.debug("GET /api/documents/search q={}", query);
        List<DocumentSearchDTO> results = documentSearchService.searchDocuments(query);
        return ResponseEntity.ok(results);
    }

    // -------------------------------------------------------------------------
    // GET /api/documents/{id}/content
    // Returns the raw CLOB content of a document as a string.
    // DocumentSearchService.getDocumentContent() reads an oracle.sql.CLOB
    // using getSubString() and length() Oracle-specific CLOB methods.
    // -------------------------------------------------------------------------
    @GetMapping("/{id}/content")
    public ResponseEntity<Map<String, Object>> getDocumentContent(@PathVariable Long id) {
        log.debug("GET /api/documents/{}/content", id);
        String content = documentSearchService.getDocumentContent(id);
        Map<String, Object> response = new HashMap<>();
        response.put("documentId", id);
        response.put("content", content);
        return ResponseEntity.ok(response);
    }

    // -------------------------------------------------------------------------
    // GET /api/documents
    // Returns all documents (without CLOB content for performance).
    // -------------------------------------------------------------------------
    @GetMapping
    public ResponseEntity<List<DocumentSearchDTO>> getAllDocuments() {
        log.debug("GET /api/documents");
        List<DocumentSearchDTO> docs = documentSearchService.getAllDocuments();
        return ResponseEntity.ok(docs);
    }

    // -------------------------------------------------------------------------
    // GET /api/documents/{id}/stats
    // Returns Oracle Text metadata for a document (word count, index state, etc.).
    // Uses CTX_USER_INDEX_ERRORS / CTX_USER_INDEXES system views.
    // -------------------------------------------------------------------------
    @GetMapping("/{id}/stats")
    public ResponseEntity<Map<String, Object>> getDocumentStats(@PathVariable Long id) {
        log.debug("GET /api/documents/{}/stats", id);
        Map<String, Object> stats = documentSearchService.getDocumentStats(id);
        return ResponseEntity.ok(stats);
    }

    // -------------------------------------------------------------------------
    // GET /api/documents/customer/{customerId}/search?q=
    // Searches documents scoped to a single customer.
    // Uses Oracle Text CONTAINS() with an additional WHERE customerId filter.
    // -------------------------------------------------------------------------
    @GetMapping("/customer/{customerId}/search")
    public ResponseEntity<List<DocumentSearchDTO>> searchDocumentsByCustomer(
            @PathVariable Long customerId,
            @RequestParam("q") String query) {

        log.debug("GET /api/documents/customer/{}/search q={}", customerId, query);
        List<DocumentSearchDTO> results = documentSearchService.fullTextSearchByCustomer(customerId, query);
        return ResponseEntity.ok(results);
    }
}
