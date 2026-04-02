package com.meridianbank.repository;

import com.meridianbank.annotation.*;
import com.meridianbank.entity.CustomerEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * Spring Data JPA repository for CustomerEntity.
 * Native queries demonstrate Oracle-specific SQL constructs.
 */
@Repository
public interface CustomerRepository extends JpaRepository<CustomerEntity, Long> {

    // Standard JPQL — no Oracle dependency
    Optional<CustomerEntity> findByEmail(String email);
    List<CustomerEntity> findByStatus(String status);
    List<CustomerEntity> findByLastNameIgnoreCase(String lastName);
    List<CustomerEntity> findByFirstNameAndLastName(String firstName, String lastName);

    // ORACLE-DEP [EMBEDDED_SQL:NVL_FUNCTION] NVL for null-safe contact info display
    @OracleDependency(
        domain = DependencyDomain.EMBEDDED_SQL,
        category = "NVL_FUNCTION",
        description = "NVL(phone,'N/A') and NVL(email,'unknown') provide null-safe defaults for contact fields",
        complexity = MigrationComplexity.LOW,
        automationLevel = AutomationLevel.FULL,
        migrationTarget = "Replace NVL(col, default) with COALESCE(col, default) in PostgreSQL"
    )
    @Query(value =
        "SELECT c.customer_id, c.first_name, c.last_name, " +
        "  NVL(c.phone, 'N/A') AS phone, " +
        "  NVL(c.email, 'unknown@unknown.com') AS email, " +
        "  c.status, c.created_date " +
        "FROM MERIDIAN.CUSTOMERS c WHERE c.status = 'ACTIVE' ORDER BY c.last_name",
        nativeQuery = true)
    List<Object[]> findCustomersWithContactInfo();

    // ORACLE-DEP [EMBEDDED_SQL:DECODE_FUNCTION] DECODE maps status codes to display labels
    @OracleDependency(
        domain = DependencyDomain.EMBEDDED_SQL,
        category = "DECODE_FUNCTION",
        description = "DECODE(status,...) maps Oracle status codes to human-readable labels",
        complexity = MigrationComplexity.LOW,
        automationLevel = AutomationLevel.FULL,
        migrationTarget = "Replace DECODE(col, v1, r1, ...) with CASE WHEN col = v1 THEN r1 ... END in PostgreSQL"
    )
    @Query(value =
        "SELECT c.*, " +
        "  DECODE(c.status, 'ACTIVE','Active Customer','INACTIVE','Former Customer'," +
        "    'FROZEN','Account Frozen','PENDING','Pending Approval','Unknown Status') AS status_label " +
        "FROM MERIDIAN.CUSTOMERS c WHERE c.customer_id = :customerId",
        nativeQuery = true)
    List<Object[]> findByStatusDecoded(@Param("customerId") Long customerId);

    // ORACLE-DEP [EMBEDDED_SQL:ORACLE_TEXT_CONTAINS] Oracle Text CONTAINS for full-text search
    @OracleDependency(
        domain = DependencyDomain.EMBEDDED_SQL,
        category = "ORACLE_TEXT_CONTAINS",
        description = "CONTAINS() invokes Oracle Text CONTEXT index for full-text customer search",
        complexity = MigrationComplexity.VERY_HIGH,
        automationLevel = AutomationLevel.MANUAL,
        migrationTarget = "Replace with PostgreSQL: to_tsvector() @@ to_tsquery() with GIN index"
    )
    @Query(value =
        "SELECT c.customer_id, c.first_name, c.last_name, c.email, c.status " +
        "FROM MERIDIAN.CUSTOMERS c " +
        "WHERE CONTAINS(c.search_text, :query) > 0 " +
        "ORDER BY c.last_name",
        nativeQuery = true)
    List<Object[]> searchByFullText(@Param("query") String query);

    // ORACLE-DEP [EMBEDDED_SQL:DATE_ARITHMETIC] + [EMBEDDED_SQL:ROWNUM]
    @OracleDependency(
        domain = DependencyDomain.EMBEDDED_SQL,
        category = "DATE_ARITHMETIC",
        description = "SYSDATE - :daysBack uses Oracle integer day subtraction from date",
        complexity = MigrationComplexity.LOW,
        automationLevel = AutomationLevel.FULL,
        migrationTarget = "Replace SYSDATE - n with NOW() - INTERVAL 'n days' in PostgreSQL"
    )
    @OracleDependency(
        domain = DependencyDomain.EMBEDDED_SQL,
        category = "ROWNUM",
        description = "ROWNUM <= :maxResults restricts result count — Oracle pre-12c top-N pattern",
        complexity = MigrationComplexity.LOW,
        automationLevel = AutomationLevel.FULL,
        migrationTarget = "Replace ROWNUM <= n with LIMIT n in PostgreSQL"
    )
    @Query(value =
        "SELECT * FROM MERIDIAN.CUSTOMERS " +
        "WHERE created_date >= SYSDATE - :daysBack " +
        "AND status = 'ACTIVE' " +
        "AND ROWNUM <= :maxResults " +
        "ORDER BY created_date DESC",
        nativeQuery = true)
    List<CustomerEntity> findRecentCustomers(@Param("daysBack") int daysBack,
                                              @Param("maxResults") int maxResults);

    // ORACLE-DEP [EMBEDDED_SQL:TO_CHAR_DATE] TO_CHAR for Oracle date formatting
    @OracleDependency(
        domain = DependencyDomain.EMBEDDED_SQL,
        category = "TO_CHAR_DATE",
        description = "TO_CHAR(date_of_birth,'DD-MON-YYYY') formats dates using Oracle date model",
        complexity = MigrationComplexity.LOW,
        automationLevel = AutomationLevel.FULL,
        migrationTarget = "Replace TO_CHAR(date,'DD-MON-YYYY') with TO_CHAR(date,'DD-Mon-YYYY') in PostgreSQL (same syntax)"
    )
    @Query(value =
        "SELECT customer_id, first_name, last_name, " +
        "  TO_CHAR(date_of_birth, 'DD-MON-YYYY') AS dob_formatted, " +
        "  TO_CHAR(created_date, 'YYYY-MM-DD HH24:MI:SS') AS created_formatted " +
        "FROM MERIDIAN.CUSTOMERS WHERE status = :status ORDER BY last_name",
        nativeQuery = true)
    List<Object[]> findWithFormattedDates(@Param("status") String status);

    // ORACLE-DEP [EMBEDDED_SQL:INSTR_FUNCTION] + [EMBEDDED_SQL:SUBSTR_FUNCTION]
    @OracleDependency(
        domain = DependencyDomain.EMBEDDED_SQL,
        category = "INSTR_FUNCTION",
        description = "INSTR(UPPER(last_name), UPPER(:prefix)) searches for substring position (1-based)",
        complexity = MigrationComplexity.LOW,
        automationLevel = AutomationLevel.FULL,
        migrationTarget = "Replace INSTR(str, sub) with POSITION(sub IN str) or strpos(str, sub) in PostgreSQL"
    )
    @OracleDependency(
        domain = DependencyDomain.EMBEDDED_SQL,
        category = "SUBSTR_FUNCTION",
        description = "SUBSTR(last_name, 1, 1) extracts first character — 1-based Oracle indexing",
        complexity = MigrationComplexity.LOW,
        automationLevel = AutomationLevel.FULL,
        migrationTarget = "Replace SUBSTR(str, 1, n) with SUBSTRING(str FROM 1 FOR n) or LEFT(str, n) in PostgreSQL"
    )
    @Query(value =
        "SELECT * FROM MERIDIAN.CUSTOMERS " +
        "WHERE INSTR(UPPER(last_name), UPPER(:prefix)) > 0 " +
        "AND SUBSTR(last_name, 1, 1) = UPPER(SUBSTR(:prefix, 1, 1)) " +
        "ORDER BY last_name",
        nativeQuery = true)
    List<CustomerEntity> findByPartialName(@Param("prefix") String prefix);

    // ORACLE-DEP [EMBEDDED_SQL:NVL2_FUNCTION] NVL2 for conditional null check
    @OracleDependency(
        domain = DependencyDomain.EMBEDDED_SQL,
        category = "NVL2_FUNCTION",
        description = "NVL2(phone, 'Has Phone', 'No Phone') returns different values based on null/not-null",
        complexity = MigrationComplexity.LOW,
        automationLevel = AutomationLevel.FULL,
        migrationTarget = "Replace NVL2(col, not_null_val, null_val) with CASE WHEN col IS NOT NULL THEN ... ELSE ... END"
    )
    @Query(value =
        "SELECT customer_id, first_name, last_name, status, " +
        "  NVL2(phone, 'Has Phone', 'No Phone') AS phone_status, " +
        "  NVL2(email, 'Has Email', 'No Email') AS email_status, " +
        "  DECODE(status, 'ACTIVE', 'Y', 'INACTIVE', 'N', 'PENDING', 'P', '?') AS status_code " +
        "FROM MERIDIAN.CUSTOMERS ORDER BY last_name",
        nativeQuery = true)
    List<Object[]> findActiveCustomersSummary();

    // ORACLE-DEP [EMBEDDED_SQL:SYSDATE] SYSDATE in UPDATE for last login timestamp
    @OracleDependency(
        domain = DependencyDomain.EMBEDDED_SQL,
        category = "SYSDATE",
        description = "SYSDATE used in UPDATE to record current server time as last_login_date",
        complexity = MigrationComplexity.LOW,
        automationLevel = AutomationLevel.FULL,
        migrationTarget = "Replace SYSDATE with NOW() or CURRENT_TIMESTAMP in PostgreSQL"
    )
    @Modifying
    @Transactional
    @Query(value =
        "UPDATE MERIDIAN.CUSTOMERS SET last_login_date = SYSDATE " +
        "WHERE customer_id = :customerId",
        nativeQuery = true)
    int updateLastLoginDate(@Param("customerId") Long customerId);

    // ORACLE-DEP [EMBEDDED_SQL:TO_CHAR_DATE] + [EMBEDDED_SQL:ADD_MONTHS]
    @OracleDependency(
        domain = DependencyDomain.EMBEDDED_SQL,
        category = "ADD_MONTHS",
        description = "ADD_MONTHS(SYSDATE, -12) computes date 12 months ago for rolling year filter",
        complexity = MigrationComplexity.LOW,
        automationLevel = AutomationLevel.FULL,
        migrationTarget = "Replace ADD_MONTHS(SYSDATE, -12) with NOW() - INTERVAL '12 months' in PostgreSQL"
    )
    @Query(value =
        "SELECT TO_CHAR(created_date, 'YYYY-MM') AS month_year, " +
        "  state, COUNT(*) AS customer_count " +
        "FROM MERIDIAN.CUSTOMERS " +
        "WHERE created_date >= ADD_MONTHS(SYSDATE, -12) " +
        "GROUP BY TO_CHAR(created_date, 'YYYY-MM'), state " +
        "ORDER BY month_year DESC, customer_count DESC",
        nativeQuery = true)
    List<Object[]> findCustomerCountByStateAndMonth();

    // ORACLE-DEP [EMBEDDED_SQL:INSTR_FUNCTION] SUBSTR/INSTR for email domain extraction
    @OracleDependency(
        domain = DependencyDomain.EMBEDDED_SQL,
        category = "INSTR_FUNCTION",
        description = "SUBSTR(email, INSTR(email,'@')+1) extracts domain part of email address",
        complexity = MigrationComplexity.LOW,
        automationLevel = AutomationLevel.FULL,
        migrationTarget = "Replace with SPLIT_PART(email, '@', 2) in PostgreSQL"
    )
    @Query(value =
        "SELECT customer_id, first_name, last_name, email, " +
        "  SUBSTR(email, INSTR(email, '@') + 1) AS email_domain " +
        "FROM MERIDIAN.CUSTOMERS " +
        "WHERE email IS NOT NULL " +
        "AND SUBSTR(email, INSTR(email, '@') + 1) = :domain " +
        "ORDER BY last_name",
        nativeQuery = true)
    List<CustomerEntity> findByEmailDomain(@Param("domain") String domain);
}
