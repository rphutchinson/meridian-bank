package com.meridianbank.entity;

import com.meridianbank.annotation.AutomationLevel;
import com.meridianbank.annotation.DependencyDomain;
import com.meridianbank.annotation.MigrationComplexity;
import com.meridianbank.annotation.OracleDependency;
import com.meridianbank.annotation.OracleDependencies;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.NamedNativeQueries;
import jakarta.persistence.NamedNativeQuery;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;

import java.time.LocalDate;

/**
 * Entity representing customer documents stored in Oracle with full-text search support
 * via Oracle Text CONTEXT indexes. The {@code content} CLOB field is indexed with
 * {@code CTX_DDL.CREATE_PREFERENCE} and queried using the Oracle Text {@code CONTAINS()}
 * operator with relevance scoring via {@code SCORE()}.
 *
 * ORACLE-DEP [EMBEDDED_SQL:ORACLE_TEXT] CLOB content indexed with Oracle Text CONTEXT index
 * ORACLE-DEP [EMBEDDED_SQL:BLOB_TYPE] BLOB for raw binary document storage
 * ORACLE-DEP [EMBEDDED_SQL:NVL_FUNCTION] NVL used for default category and mime-type display
 */
@OracleDependencies({
    @OracleDependency(
        domain = DependencyDomain.EMBEDDED_SQL,
        category = "ORACLE_TEXT",
        description = "CLOB column indexed with Oracle Text CONTEXT index; queried via CONTAINS()",
        complexity = MigrationComplexity.HIGH,
        automationLevel = AutomationLevel.PARTIAL,
        migrationTarget = "Use PostgreSQL full-text search: tsvector/tsquery or pg_trgm"
    ),
    @OracleDependency(
        domain = DependencyDomain.EMBEDDED_SQL,
        category = "BLOB_TYPE",
        description = "BLOB column for binary document data",
        complexity = MigrationComplexity.LOW,
        automationLevel = AutomationLevel.FULL,
        migrationTarget = "Use PostgreSQL bytea or external object storage"
    )
})
@NamedNativeQueries({
    @NamedNativeQuery(
        name = "DocumentEntity.searchByContent",
        // ORACLE-DEP [EMBEDDED_SQL:ORACLE_TEXT] CONTAINS() is the Oracle Text full-text search operator
        // ORACLE-DEP [EMBEDDED_SQL:ORACLE_TEXT] SCORE(label) returns the relevance score for ranking
        // The label integer (1) links CONTAINS() and SCORE() — an Oracle Text convention
        query = "SELECT d.document_id, d.title, d.category, d.customer_id, d.created_date, " +
                "       SCORE(1) as relevance_score " +
                "FROM MERIDIAN.DOCUMENTS d " +
                "WHERE CONTAINS(d.content, :searchTerm, 1) > 0 " +
                "AND d.status = 'ACTIVE' " +
                "ORDER BY SCORE(1) DESC",
        resultClass = DocumentEntity.class
    ),
    @NamedNativeQuery(
        name = "DocumentEntity.findByCustomerWithNvl",
        // ORACLE-DEP [EMBEDDED_SQL:NVL_FUNCTION] NVL defaults null category to 'UNCATEGORIZED'
        // ORACLE-DEP [EMBEDDED_SQL:NVL_FUNCTION] NVL defaults null mime_type to generic binary type
        query = "SELECT d.document_id, d.title, " +
                "       NVL(d.category, 'UNCATEGORIZED') as category, " +
                "       NVL(d.mime_type, 'application/octet-stream') as mime_type, " +
                "       d.created_date " +
                "FROM MERIDIAN.DOCUMENTS d " +
                "WHERE d.customer_id = :customerId AND d.status = 'ACTIVE' " +
                "ORDER BY d.created_date DESC",
        resultClass = DocumentEntity.class
    )
})
@Entity
@Table(name = "DOCUMENTS", schema = "MERIDIAN")
public class DocumentEntity {

    // ORACLE-DEP [CONNECTIVITY_CONFIG:SEQUENCE_GENERATOR] Oracle sequence for PK generation
    @Id
    @SequenceGenerator(
        name = "document_seq_gen",
        sequenceName = "MERIDIAN.DOCUMENT_SEQ",
        allocationSize = 1
    )
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "document_seq_gen")
    @Column(name = "DOCUMENT_ID")
    private Long documentId;

    // ORACLE-DEP [EMBEDDED_SQL:VARCHAR2_TYPE] Oracle VARCHAR2(500) for document title
    @Column(name = "TITLE", columnDefinition = "VARCHAR2(500)", nullable = false)
    private String title;

    /**
     * Oracle Text CONTEXT-indexed CLOB. Queried with CONTAINS(content, query_string, label).
     * ORACLE-DEP [EMBEDDED_SQL:ORACLE_TEXT] CLOB indexed by Oracle Text for full-text search
     * ORACLE-DEP [EMBEDDED_SQL:CLOB_TYPE] Oracle CLOB for large text document content
     */
    @Lob
    @Column(name = "CONTENT")
    private String content;

    /**
     * Raw binary document storage as Oracle BLOB.
     * ORACLE-DEP [EMBEDDED_SQL:BLOB_TYPE] Oracle BLOB for binary document (PDF, DOCX, etc.)
     */
    @Lob
    @Column(name = "DOCUMENT_DATA")
    private byte[] documentData;

    // ORACLE-DEP [EMBEDDED_SQL:VARCHAR2_TYPE] Oracle VARCHAR2 for document category classification
    @Column(name = "CATEGORY", columnDefinition = "VARCHAR2(50)")
    private String category;

    // ORACLE-DEP [EMBEDDED_SQL:VARCHAR2_TYPE] Oracle VARCHAR2 for MIME type string
    @Column(name = "MIME_TYPE", columnDefinition = "VARCHAR2(100)")
    private String mimeType;

    @Column(name = "CUSTOMER_ID", nullable = false)
    private Long customerId;

    /**
     * Date the Oracle Text index was last synchronized for this document.
     * ORACLE-DEP [EMBEDDED_SQL:ORACLE_TEXT] indexedDate tracks Oracle Text CTX_DDL sync status
     */
    @Column(name = "INDEXED_DATE", columnDefinition = "DATE")
    private LocalDate indexedDate;

    // ORACLE-DEP [EMBEDDED_SQL:ORACLE_DATE_TYPE] Oracle DATE for record creation
    @Column(name = "CREATED_DATE", columnDefinition = "DATE", nullable = false)
    private LocalDate createdDate;

    // ORACLE-DEP [EMBEDDED_SQL:VARCHAR2_TYPE] Oracle VARCHAR2 for document lifecycle status
    @Column(name = "STATUS", columnDefinition = "VARCHAR2(20)", nullable = false)

    private String status = "ACTIVE";
    public DocumentEntity() {
    }


    public Long getDocumentId() {
        return documentId;
    }

    public void setDocumentId(Long documentId) {
        this.documentId = documentId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public byte[] getDocumentData() {
        return documentData;
    }

    public void setDocumentData(byte[] documentData) {
        this.documentData = documentData;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public String getMimeType() {
        return mimeType;
    }

    public void setMimeType(String mimeType) {
        this.mimeType = mimeType;
    }

    public Long getCustomerId() {
        return customerId;
    }

    public void setCustomerId(Long customerId) {
        this.customerId = customerId;
    }

    public LocalDate getIndexedDate() {
        return indexedDate;
    }

    public void setIndexedDate(LocalDate indexedDate) {
        this.indexedDate = indexedDate;
    }

    public LocalDate getCreatedDate() {
        return createdDate;
    }

    public void setCreatedDate(LocalDate createdDate) {
        this.createdDate = createdDate;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

}
