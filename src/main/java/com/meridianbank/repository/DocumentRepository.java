package com.meridianbank.repository;

import com.meridianbank.annotation.*;
import com.meridianbank.entity.DocumentEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Spring Data JPA repository for DocumentEntity.
 * Demonstrates Oracle Text CONTAINS/SCORE operators for full-text search,
 * DBMS_LOB.SUBSTR and DBMS_LOB.GETLENGTH for CLOB content access,
 * NVL for null-safe defaults, and TO_CHAR for date formatting.
 */
@Repository
public interface DocumentRepository extends JpaRepository<DocumentEntity, Long> {

    // Standard JPQL — no Oracle dependency
    List<DocumentEntity> findByCustomerId(Long customerId);
    List<DocumentEntity> findByCategory(String category);
    List<DocumentEntity> findByCustomerIdAndCategory(Long customerId, String category);

    // ORACLE-DEP [EMBEDDED_SQL:ORACLE_TEXT_CONTAINS] Oracle Text CONTAINS for full-text search
    @OracleDependency(
        domain = DependencyDomain.EMBEDDED_SQL,
        category = "ORACLE_TEXT_CONTAINS",
        description = "CONTAINS(content, :query) invokes Oracle Text CONTEXT index for full-text document search",
        complexity = MigrationComplexity.VERY_HIGH,
        automationLevel = AutomationLevel.MANUAL,
        migrationTarget = "Replace with PostgreSQL full-text: to_tsvector('english', content) @@ plainto_tsquery(:query) with GIN index on tsvector"
    )
    @Query(value =
        "SELECT d.document_id, d.title, d.category, d.customer_id, d.indexed_date, " +
        "  SCORE(1) AS relevance_score " +
        "FROM MERIDIAN.DOCUMENTS d " +
        "WHERE CONTAINS(d.content, :query, 1) > 0 " +
        "ORDER BY SCORE(1) DESC",
        nativeQuery = true)
    List<Object[]> searchDocumentsByContent(@Param("query") String query);

    // ORACLE-DEP [EMBEDDED_SQL:ORACLE_TEXT_CONTAINS] CONTAINS with category filter
    @OracleDependency(
        domain = DependencyDomain.EMBEDDED_SQL,
        category = "ORACLE_TEXT_CONTAINS",
        description = "CONTAINS(content, :query) combined with standard WHERE predicate for filtered full-text search",
        complexity = MigrationComplexity.VERY_HIGH,
        automationLevel = AutomationLevel.MANUAL,
        migrationTarget = "Combine PostgreSQL to_tsvector search with AND WHERE category = :category"
    )
    @Query(value =
        "SELECT d.document_id, d.title, d.category, d.customer_id, d.indexed_date, " +
        "  SCORE(1) AS relevance_score, " +
        "  DBMS_LOB.SUBSTR(d.content, 300, 1) AS content_snippet " +
        "FROM MERIDIAN.DOCUMENTS d " +
        "WHERE CONTAINS(d.content, :query, 1) > 0 " +
        "AND d.customer_id = :customerId " +
        "AND d.category = :category " +
        "ORDER BY SCORE(1) DESC",
        nativeQuery = true)
    List<Object[]> searchDocumentsByCustomerAndCategory(@Param("query") String query,
                                                         @Param("customerId") Long customerId,
                                                         @Param("category") String category);

    // ORACLE-DEP [EMBEDDED_SQL:DBMS_LOB_SUBSTR] DBMS_LOB.SUBSTR for content preview
    @OracleDependency(
        domain = DependencyDomain.EMBEDDED_SQL,
        category = "DBMS_LOB_SUBSTR",
        description = "DBMS_LOB.SUBSTR(content, 500, 1) extracts first 500 chars from CLOB for document preview list",
        complexity = MigrationComplexity.MEDIUM,
        automationLevel = AutomationLevel.PARTIAL,
        migrationTarget = "Replace DBMS_LOB.SUBSTR(clob, n, 1) with LEFT(content, n) or SUBSTRING(content FOR n) in PostgreSQL (TEXT column)"
    )
    @Query(value =
        "SELECT d.document_id, d.title, d.category, d.customer_id, d.indexed_date, " +
        "  NVL(d.title, 'Untitled Document') AS display_title, " +
        "  DBMS_LOB.SUBSTR(d.content, 500, 1) AS preview_text, " +
        "  DBMS_LOB.GETLENGTH(d.content) AS content_length " +
        "FROM MERIDIAN.DOCUMENTS d " +
        "WHERE d.customer_id = :customerId " +
        "ORDER BY d.indexed_date DESC",
        nativeQuery = true)
    List<Object[]> findDocumentPreviewsByCustomer(@Param("customerId") Long customerId);

    // ORACLE-DEP [EMBEDDED_SQL:DBMS_LOB_GETLENGTH] DBMS_LOB.GETLENGTH for size filtering
    @OracleDependency(
        domain = DependencyDomain.EMBEDDED_SQL,
        category = "DBMS_LOB_GETLENGTH",
        description = "DBMS_LOB.GETLENGTH(content) returns character count of CLOB for size-based filtering",
        complexity = MigrationComplexity.MEDIUM,
        automationLevel = AutomationLevel.PARTIAL,
        migrationTarget = "Replace DBMS_LOB.GETLENGTH(content) with LENGTH(content) in PostgreSQL (TEXT type)"
    )
    @Query(value =
        "SELECT d.document_id, d.title, d.category, " +
        "  DBMS_LOB.GETLENGTH(d.content) AS content_length, " +
        "  d.indexed_date " +
        "FROM MERIDIAN.DOCUMENTS d " +
        "WHERE DBMS_LOB.GETLENGTH(d.content) > :minLength " +
        "AND d.customer_id = :customerId " +
        "ORDER BY DBMS_LOB.GETLENGTH(d.content) DESC",
        nativeQuery = true)
    List<Object[]> findLargeDocuments(@Param("customerId") Long customerId,
                                       @Param("minLength") int minLength);

    // ORACLE-DEP [EMBEDDED_SQL:NVL_FUNCTION] NVL for null-safe document metadata
    @OracleDependency(
        domain = DependencyDomain.EMBEDDED_SQL,
        category = "NVL_FUNCTION",
        description = "NVL(category,'GENERAL') ensures uncategorized documents appear in GENERAL bucket",
        complexity = MigrationComplexity.LOW,
        automationLevel = AutomationLevel.FULL,
        migrationTarget = "Replace NVL(category,'GENERAL') with COALESCE(category,'GENERAL') in PostgreSQL"
    )
    @OracleDependency(
        domain = DependencyDomain.EMBEDDED_SQL,
        category = "TO_CHAR_DATE",
        description = "TO_CHAR(indexed_date,'YYYY-MM') groups documents by index month for archive reporting",
        complexity = MigrationComplexity.LOW,
        automationLevel = AutomationLevel.FULL,
        migrationTarget = "Replace TO_CHAR(date,'YYYY-MM') with TO_CHAR(date,'YYYY-MM') in PostgreSQL (compatible)"
    )
    @Query(value =
        "SELECT NVL(d.category, 'GENERAL') AS document_category, " +
        "  TO_CHAR(d.indexed_date, 'YYYY-MM') AS index_month, " +
        "  COUNT(*) AS document_count, " +
        "  SUM(DBMS_LOB.GETLENGTH(d.content)) AS total_content_size " +
        "FROM MERIDIAN.DOCUMENTS d " +
        "WHERE d.customer_id = :customerId " +
        "GROUP BY NVL(d.category, 'GENERAL'), TO_CHAR(d.indexed_date, 'YYYY-MM') " +
        "ORDER BY index_month DESC",
        nativeQuery = true)
    List<Object[]> findDocumentStatsByCategory(@Param("customerId") Long customerId);

    // ORACLE-DEP [EMBEDDED_SQL:SYSDATE] SYSDATE for indexing timestamp update
    @OracleDependency(
        domain = DependencyDomain.EMBEDDED_SQL,
        category = "SYSDATE",
        description = "SYSDATE stamps reindex operation date in indexed_date column",
        complexity = MigrationComplexity.LOW,
        automationLevel = AutomationLevel.FULL,
        migrationTarget = "Replace SYSDATE with CURRENT_TIMESTAMP in PostgreSQL"
    )
    @Modifying
    @Transactional
    @Query(value =
        "UPDATE MERIDIAN.DOCUMENTS SET indexed_date = SYSDATE " +
        "WHERE document_id = :documentId",
        nativeQuery = true)
    int updateIndexedDate(@Param("documentId") Long documentId);
}
