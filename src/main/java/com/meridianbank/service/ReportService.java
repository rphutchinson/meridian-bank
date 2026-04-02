package com.meridianbank.service;

import com.meridianbank.annotation.AutomationLevel;
import com.meridianbank.annotation.DependencyDomain;
import com.meridianbank.annotation.MigrationComplexity;
import com.meridianbank.annotation.OracleDependency;
import com.meridianbank.dao.ReportOracleDAO;
import com.meridianbank.dto.AuditTrailDTO;
import com.meridianbank.dto.EmployeeHierarchyDTO;
import com.meridianbank.dto.FinancialReportDTO;
import com.meridianbank.dto.ReportParameterDTO;
import com.meridianbank.mybatis.mapper.ReportMapper;
import com.meridianbank.repository.AuditLogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * ReportService -- business logic for financial, analytical, and audit reports.
 *
 * <p>This service is the primary orchestration layer for Oracle analytic SQL features.
 * It delegates to {@link ReportOracleDAO} for raw JDBC queries and to
 * {@link ReportMapper} (MyBatis) for XML-mapped queries.  The results are enriched,
 * validated, and shaped into structured DTOs before being returned to controllers.
 *
 * <p>Oracle dependencies exercised here span §2.1 (Embedded SQL) and §2.4 (ORM/Data Access):
 * <ul>
 *   <li>RATIO_TO_REPORT() -- portfolio allocation percentages</li>
 *   <li>WIDTH_BUCKET()    -- transaction amount distribution histograms</li>
 *   <li>NTILE() / CUME_DIST() -- customer ranking by transaction volume</li>
 *   <li>PIVOT             -- monthly transaction summary matrix</li>
 *   <li>CONNECT BY PRIOR  -- employee org-hierarchy report</li>
 *   <li>LISTAGG()         -- aggregated account number summary</li>
 *   <li>(+) outer join    -- legacy account / customer join syntax</li>
 *   <li>Date subtraction  -- aging calculations returning NUMBER of days</li>
 *   <li>LEADING hint      -- optimizer join-order directive</li>
 *   <li>DECODE / NVL / SYSDATE -- audit label and null-safe display</li>
 * </ul>
 */
@Service
@Transactional(readOnly = true)
public class ReportService {

    private static final Logger log = LoggerFactory.getLogger(ReportService.class);

    @Autowired
    private ReportOracleDAO reportOracleDAO;

    @Autowired
    private ReportMapper reportMapper;

    @Autowired
    private AuditLogRepository auditLogRepository;

    // -------------------------------------------------------------------------
    // Analytic function reports
    // -------------------------------------------------------------------------

    /**
     * Computes portfolio allocation ratios for a customer across all their accounts.
     *
     * <p>The underlying query uses Oracle's RATIO_TO_REPORT() analytic window function
     * to compute each account's balance as a proportion of the customer's total
     * portfolio without requiring a self-join or subquery for the total.
     *
     * @param customerId the customer whose portfolio allocation is requested
     * @return list of FinancialReportDTO rows, one per account type
     */
    // ORACLE-DEP [EMBEDDED_SQL:RATIO_TO_REPORT] Portfolio allocation via RATIO_TO_REPORT analytic
    @OracleDependency(
        domain = DependencyDomain.EMBEDDED_SQL,
        category = "RATIO_TO_REPORT",
        description = "Delegates to ReportOracleDAO.getPortfolioAllocation() which uses "
                    + "RATIO_TO_REPORT(SUM(balance)) OVER () to compute each account type's share "
                    + "of the total customer portfolio in a single pass",
        complexity = MigrationComplexity.MEDIUM,
        automationLevel = AutomationLevel.PARTIAL,
        migrationTarget = "Replace RATIO_TO_REPORT(expr) OVER (...) with "
                        + "SUM(expr) OVER (...) / SUM(expr) OVER () in PostgreSQL; "
                        + "standard window functions are supported"
    )
    public List<FinancialReportDTO> getPortfolioAllocation(Long customerId) {
        if (customerId == null) {
            throw new IllegalArgumentException("Customer ID must not be null");
        }
        log.info("Generating portfolio allocation report for customer {} via RATIO_TO_REPORT", customerId);
        // ORACLE-DEP [EMBEDDED_SQL:RATIO_TO_REPORT] getPortfolioAllocation uses RATIO_TO_REPORT()
        List<FinancialReportDTO> rows = reportOracleDAO.getPortfolioAllocation(customerId);

        // Enrich each row with report metadata
        LocalDate today = LocalDate.now();
        for (FinancialReportDTO row : rows) {
            row.setReportType("PORTFOLIO_ALLOCATION");
            row.setReportDate(today);
            if (row.getReportTitle() == null) {
                row.setReportTitle("Portfolio Allocation -- Customer " + customerId);
            }
        }
        return rows;
    }

    /**
     * Returns a histogram of transaction amounts for an account using WIDTH_BUCKET.
     *
     * <p>The underlying query partitions transactions into equal-width buckets between
     * zero and the maximum observed amount.  Oracle's WIDTH_BUCKET function computes
     * the bucket assignment in a single expression; PostgreSQL supports the same
     * function in its standard library.
     *
     * @param accountId the account to analyse
     * @return list of maps with keys: bucket_num, bucket_min, bucket_max, tx_count, total_amount
     */
    // ORACLE-DEP [EMBEDDED_SQL:WIDTH_BUCKET] Transaction histogram via WIDTH_BUCKET()
    @OracleDependency(
        domain = DependencyDomain.EMBEDDED_SQL,
        category = "WIDTH_BUCKET",
        description = "Delegates to ReportOracleDAO.getTransactionDistribution() which uses "
                    + "WIDTH_BUCKET(amount, 0, max_amount, :buckets) to assign each transaction "
                    + "to an equal-width bucket for histogram display",
        complexity = MigrationComplexity.LOW,
        automationLevel = AutomationLevel.FULL,
        migrationTarget = "WIDTH_BUCKET() is available in PostgreSQL with identical syntax; "
                        + "direct replacement with no modification needed"
    )
    public List<Map<String, Object>> getTransactionDistribution(Long accountId) {
        if (accountId == null) {
            throw new IllegalArgumentException("Account ID must not be null");
        }
        log.debug("Generating transaction distribution for account {} via WIDTH_BUCKET", accountId);
        // ORACLE-DEP [EMBEDDED_SQL:WIDTH_BUCKET] getTransactionDistribution uses WIDTH_BUCKET()
        List<Map<String, Object>> buckets = reportOracleDAO.getTransactionDistribution(accountId);
        // Add percentage column relative to total count
        long totalCount = buckets.stream()
            .mapToLong(b -> b.get("tx_count") != null ? ((Number) b.get("tx_count")).longValue() : 0L)
            .sum();
        for (Map<String, Object> bucket : buckets) {
            if (totalCount > 0 && bucket.get("tx_count") != null) {
                double pct = ((Number) bucket.get("tx_count")).doubleValue() / totalCount * 100.0;
                bucket.put("percentage", Math.round(pct * 100.0) / 100.0);
            } else {
                bucket.put("percentage", 0.0);
            }
        }
        return buckets;
    }

    /**
     * Ranks customers by their transaction volume using Oracle NTILE and CUME_DIST.
     *
     * <p>NTILE divides customers into equal-sized groups (quartiles by default).
     * CUME_DIST returns the cumulative distribution -- the fraction of customers
     * with a lower or equal volume.  Both functions are standard SQL:2003 and
     * supported in PostgreSQL.
     *
     * @param rankBy optional column to rank by; defaults to {@code total_volume}
     * @return ranked list with columns: customer_id, customer_name, total_volume,
     *         ntile_rank, cumulative_dist, tier
     */
    // ORACLE-DEP [EMBEDDED_SQL:NTILE_FUNCTION] Customer ranking via NTILE()
    // ORACLE-DEP [EMBEDDED_SQL:CUME_DIST] Cumulative distribution via CUME_DIST()
    @OracleDependency(
        domain = DependencyDomain.EMBEDDED_SQL,
        category = "NTILE_FUNCTION",
        description = "Delegates to ReportOracleDAO.getCustomerRanking() which uses "
                    + "NTILE(4) OVER (ORDER BY SUM(amount) DESC) to assign customers to quartiles",
        complexity = MigrationComplexity.LOW,
        automationLevel = AutomationLevel.FULL,
        migrationTarget = "NTILE() is a standard SQL window function available in PostgreSQL; "
                        + "replace NTILE(4) OVER (...) directly without modification"
    )
    @OracleDependency(
        domain = DependencyDomain.EMBEDDED_SQL,
        category = "CUME_DIST",
        description = "Delegates to ReportOracleDAO.getCustomerRanking() which uses "
                    + "CUME_DIST() OVER (ORDER BY SUM(amount) DESC) for the cumulative percentile",
        complexity = MigrationComplexity.LOW,
        automationLevel = AutomationLevel.FULL,
        migrationTarget = "CUME_DIST() is a standard SQL window function available in PostgreSQL; "
                        + "replace directly without modification"
    )
    public List<Map<String, Object>> getCustomerRanking(String rankBy) {
        String effectiveRankBy = (rankBy != null && !rankBy.isBlank()) ? rankBy : "total_volume";
        log.debug("Generating customer ranking by {} via NTILE / CUME_DIST", effectiveRankBy);
        // ORACLE-DEP [EMBEDDED_SQL:NTILE_FUNCTION] getCustomerRanking uses NTILE()
        // ORACLE-DEP [EMBEDDED_SQL:CUME_DIST] getCustomerRanking uses CUME_DIST()
        List<Map<String, Object>> rows = reportOracleDAO.getCustomerRanking(effectiveRankBy);
        // Enrich tier labels based on ntile_rank
        for (Map<String, Object> row : rows) {
            Object ntile = row.get("ntile_rank");
            if (ntile != null) {
                int rank = ((Number) ntile).intValue();
                String tier = switch (rank) {
                    case 1 -> "PLATINUM";
                    case 2 -> "GOLD";
                    case 3 -> "SILVER";
                    default -> "STANDARD";
                };
                row.put("tier_label", tier);
            }
        }
        return rows;
    }

    /**
     * Returns a monthly pivot of transaction totals by type for the given year.
     *
     * <p>Oracle's PIVOT operator rotates rows into columns: each transaction type
     * (DEBIT, CREDIT, TRANSFER, FEE, INTEREST) becomes a column, and each row
     * represents one month.  The PostgreSQL equivalent uses conditional aggregation
     * (FILTER or CASE WHEN) because PIVOT is not a standard PostgreSQL operator.
     *
     * @param year calendar year (e.g. 2025)
     * @return list of monthly rows with transaction type columns as keys
     */
    // ORACLE-DEP [EMBEDDED_SQL:PIVOT_CLAUSE] Monthly transaction pivot via Oracle PIVOT operator
    @OracleDependency(
        domain = DependencyDomain.EMBEDDED_SQL,
        category = "PIVOT_CLAUSE",
        description = "Delegates to ReportOracleDAO.getMonthlyTransactionPivot() which uses Oracle PIVOT "
                    + "to rotate transaction types into columns for each calendar month",
        complexity = MigrationComplexity.HIGH,
        automationLevel = AutomationLevel.PARTIAL,
        migrationTarget = "Replace Oracle PIVOT with conditional aggregation: "
                        + "SUM(amount) FILTER (WHERE transaction_type = 'DEBIT') AS DEBIT, ... "
                        + "or CASE WHEN transaction_type = 'DEBIT' THEN amount END in PostgreSQL"
    )
    public List<Map<String, Object>> getMonthlyTransactionPivot(int year) {
        if (year < 2000 || year > 2100) {
            throw new IllegalArgumentException("Year must be between 2000 and 2100");
        }
        log.info("Generating monthly transaction pivot for year {} via Oracle PIVOT", year);
        // ORACLE-DEP [EMBEDDED_SQL:PIVOT_CLAUSE] getMonthlyTransactionPivot uses Oracle PIVOT
        List<Map<String, Object>> pivotRows = reportOracleDAO.getMonthlyTransactionPivot(year);
        // Add a year column for reference
        pivotRows.forEach(row -> row.put("report_year", year));
        return pivotRows;
    }

    /**
     * Returns the employee org-chart hierarchy rooted at {@code rootId} using
     * the ReportOracleDAO's CONNECT BY implementation.
     *
     * <p>Unlike the EmployeeService which delegates to the JPA repository,
     * this method uses the raw-JDBC DAO so that reports can be generated without
     * Spring Data context and can be streamed for large orgs.
     *
     * @param rootId root employee ID; pass null for the full org from CEO
     * @return ordered list of employees with LEVEL and SYS_CONNECT_BY_PATH data
     */
    // ORACLE-DEP [EMBEDDED_SQL:CONNECT_BY] Org hierarchy for reports via ReportOracleDAO CONNECT BY
    @OracleDependency(
        domain = DependencyDomain.EMBEDDED_SQL,
        category = "CONNECT_BY",
        description = "Delegates to ReportOracleDAO.getEmployeeOrgHierarchy() which contains a "
                    + "CONNECT BY PRIOR / LEVEL / SYS_CONNECT_BY_PATH query for the reporting context",
        complexity = MigrationComplexity.HIGH,
        automationLevel = AutomationLevel.PARTIAL,
        migrationTarget = "Replace with recursive CTE in PostgreSQL; ReportOracleDAO method must be rewritten "
                        + "alongside the EmployeeRepository counterpart"
    )
    public List<EmployeeHierarchyDTO> getEmployeeOrgHierarchy(Long rootId) {
        Long effectiveRoot = (rootId != null && rootId > 0) ? rootId : null;
        log.debug("Generating employee org hierarchy report via CONNECT BY, rootId={}", effectiveRoot);
        // ORACLE-DEP [EMBEDDED_SQL:CONNECT_BY] getEmployeeOrgHierarchy uses CONNECT BY PRIOR
        return reportOracleDAO.getEmployeeOrgHierarchy(effectiveRoot);
    }

    /**
     * Returns an aggregated account summary for a customer using LISTAGG.
     *
     * <p>Oracle's LISTAGG function concatenates account numbers into a single
     * comma-separated string within a GROUP BY context, avoiding a separate
     * application-level join.  PostgreSQL's equivalent is STRING_AGG.
     *
     * @param customerId the customer to summarise
     * @return map with keys: customer_id, account_count, account_list (LISTAGG result),
     *         total_balance, account_types
     */
    // ORACLE-DEP [EMBEDDED_SQL:LISTAGG_FUNCTION] Account summary via Oracle LISTAGG()
    @OracleDependency(
        domain = DependencyDomain.EMBEDDED_SQL,
        category = "LISTAGG_FUNCTION",
        description = "Delegates to ReportOracleDAO.getAccountSummaryWithListagg() which uses "
                    + "LISTAGG(account_number, ', ') WITHIN GROUP (ORDER BY account_number) "
                    + "to produce a comma-delimited list of all account numbers for the customer",
        complexity = MigrationComplexity.LOW,
        automationLevel = AutomationLevel.FULL,
        migrationTarget = "Replace LISTAGG(col, ',') WITHIN GROUP (ORDER BY col) with "
                        + "STRING_AGG(col, ',' ORDER BY col) in PostgreSQL"
    )
    public Map<String, Object> getAccountSummaryWithListagg(Long customerId) {
        if (customerId == null) {
            throw new IllegalArgumentException("Customer ID must not be null");
        }
        log.debug("Generating account summary with LISTAGG for customer {}", customerId);
        // ORACLE-DEP [EMBEDDED_SQL:LISTAGG_FUNCTION] getAccountSummaryWithListagg uses LISTAGG()
        Map<String, Object> summary = reportOracleDAO.getAccountSummaryWithListagg(customerId);
        // Enrich with metadata
        summary.put("report_generated_at", LocalDateTime.now().toString());
        summary.put("report_type", "ACCOUNT_SUMMARY");
        return summary;
    }

    /**
     * Returns a legacy-style report using Oracle's (+) outer-join syntax.
     *
     * <p>The (+) syntax was Oracle's proprietary outer-join operator before
     * SQL:1999 introduced ANSI OUTER JOIN.  It persists in legacy codebases
     * and must be rewritten as a standard LEFT/RIGHT/FULL OUTER JOIN on migration.
     *
     * @return list of account rows with customer data, nulls for unmatched rows
     */
    // ORACLE-DEP [EMBEDDED_SQL:OUTER_JOIN_PLUS] Legacy (+) outer join in account/customer report
    @OracleDependency(
        domain = DependencyDomain.EMBEDDED_SQL,
        category = "OUTER_JOIN_PLUS",
        description = "Delegates to ReportOracleDAO.getLegacyAccountReport() which uses the (+) "
                    + "operator for outer joins in the WHERE clause -- Oracle proprietary syntax from "
                    + "before SQL:1999 ANSI outer join standardisation",
        complexity = MigrationComplexity.MEDIUM,
        automationLevel = AutomationLevel.FULL,
        migrationTarget = "Replace WHERE a.customer_id = c.customer_id(+) with "
                        + "LEFT OUTER JOIN customers c ON a.customer_id = c.customer_id in PostgreSQL"
    )
    public List<Map<String, Object>> getLegacyAccountReport() {
        log.debug("Generating legacy account report with Oracle (+) outer join syntax");
        // ORACLE-DEP [EMBEDDED_SQL:OUTER_JOIN_PLUS] getLegacyAccountReport uses (+) outer join
        return reportOracleDAO.getLegacyAccountReport();
    }

    /**
     * Returns an aging report for transactions on an account showing how many
     * days each transaction has been outstanding.
     *
     * <p>Oracle treats the result of {@code date1 - date2} as a NUMBER of days.
     * PostgreSQL returns an INTERVAL; downstream code must use EXTRACT(EPOCH FROM ...)/86400
     * to get a number of days.
     *
     * @param accountId the account to age
     * @return list of transaction rows with days_old computed via Oracle date arithmetic
     */
    // ORACLE-DEP [EMBEDDED_SQL:DATE_ARITHMETIC] Oracle date subtraction returns NUMBER of days
    @OracleDependency(
        domain = DependencyDomain.EMBEDDED_SQL,
        category = "DATE_ARITHMETIC",
        description = "Delegates to ReportOracleDAO.getTransactionAging() which computes "
                    + "TRUNC(SYSDATE) - TRUNC(transaction_date) to get integer days; "
                    + "Oracle date subtraction returns a NUMBER, not an INTERVAL",
        complexity = MigrationComplexity.LOW,
        automationLevel = AutomationLevel.FULL,
        migrationTarget = "Replace (TRUNC(SYSDATE) - TRUNC(transaction_date)) with "
                        + "EXTRACT(EPOCH FROM (CURRENT_DATE - transaction_date::DATE))/86400 "
                        + "or (CURRENT_DATE - transaction_date::DATE) in PostgreSQL"
    )
    public List<Map<String, Object>> getTransactionAging(Long accountId) {
        if (accountId == null) {
            throw new IllegalArgumentException("Account ID must not be null");
        }
        log.debug("Generating transaction aging for account {} using Oracle date arithmetic", accountId);
        // ORACLE-DEP [EMBEDDED_SQL:DATE_ARITHMETIC] getTransactionAging uses SYSDATE - date
        List<Map<String, Object>> rows = reportOracleDAO.getTransactionAging(accountId);
        // Classify aging buckets
        for (Map<String, Object> row : rows) {
            Object daysOldObj = row.get("days_old");
            if (daysOldObj != null) {
                int daysOld = ((Number) daysOldObj).intValue();
                String bucket = daysOld <= 30 ? "CURRENT"
                    : daysOld <= 60 ? "31-60_DAYS"
                    : daysOld <= 90 ? "61-90_DAYS"
                    : "OVER_90_DAYS";
                row.put("aging_bucket", bucket);
            }
        }
        return rows;
    }

    /**
     * Returns a report generated with an Oracle LEADING optimizer hint to force a
     * specific join order.  Optimizer hints are Oracle-proprietary; they have no
     * standard SQL equivalent and must be removed or replaced with target-DB
     * equivalents (e.g., PostgreSQL's SET enable_hashjoin, pg_hint_plan extension).
     *
     * @param params report parameters including date range and optional filters
     * @return list of transaction summary rows generated with LEADING hint
     */
    // ORACLE-DEP [EMBEDDED_SQL:OPTIMIZER_HINTS] Oracle LEADING hint for join-order control
    @OracleDependency(
        domain = DependencyDomain.EMBEDDED_SQL,
        category = "OPTIMIZER_HINTS",
        description = "Delegates to ReportOracleDAO.getLeadingHintReport() which contains "
                    + "/*+ LEADING(t a c) USE_NL(a) USE_HASH(c) */ to force join order and "
                    + "access method for performance-critical multi-table report query",
        complexity = MigrationComplexity.MEDIUM,
        automationLevel = AutomationLevel.PARTIAL,
        migrationTarget = "Remove Oracle hints from SQL; use EXPLAIN ANALYZE in PostgreSQL to evaluate "
                        + "planner choices; if needed, use pg_hint_plan extension or SET parameters "
                        + "such as enable_hashjoin=off within the session"
    )
    public List<Map<String, Object>> getLeadingHintReport(ReportParameterDTO params) {
        if (params == null) {
            throw new IllegalArgumentException("Report parameters must not be null");
        }
        log.info("Generating leading-hint report with params: startDate={}, endDate={}",
                 params.getStartDate(), params.getEndDate());
        // ORACLE-DEP [EMBEDDED_SQL:OPTIMIZER_HINTS] getLeadingHintReport uses LEADING / USE_NL / USE_HASH hints
        List<Map<String, Object>> rows = reportOracleDAO.getLeadingHintReport(params);
        // Attach request parameters as metadata on the first row for debugging
        if (!rows.isEmpty()) {
            rows.get(0).put("_query_start_date", params.getStartDate() != null ? params.getStartDate().toString() : null);
            rows.get(0).put("_query_end_date", params.getEndDate() != null ? params.getEndDate().toString() : null);
        }
        return rows;
    }

    // -------------------------------------------------------------------------
    // Audit report
    // -------------------------------------------------------------------------

    /**
     * Returns recent audit activity with Oracle-derived display labels.
     *
     * <p>The underlying repository query uses:
     * <ul>
     *   <li>DECODE(operation_type, 'I', 'INSERT', ...) -- Oracle proprietary conditional</li>
     *   <li>NVL(ip_address, 'unknown') -- Oracle null coalescing</li>
     *   <li>SYSDATE - :days -- Oracle date integer arithmetic</li>
     * </ul>
     *
     * @param days look-back window in days
     * @return list of AuditTrailDTO rows with decoded operation labels
     */
    // ORACLE-DEP [EMBEDDED_SQL:DECODE_FUNCTION] Audit labels via Oracle DECODE()
    // ORACLE-DEP [EMBEDDED_SQL:NVL_FUNCTION] Null-safe ip/user display via Oracle NVL()
    // ORACLE-DEP [EMBEDDED_SQL:DATE_ARITHMETIC] SYSDATE - :days date integer arithmetic
    @OracleDependency(
        domain = DependencyDomain.EMBEDDED_SQL,
        category = "DECODE_FUNCTION",
        description = "Delegates to AuditLogRepository.findAuditWithLabels() which uses DECODE to map "
                    + "single-char operation codes (I/U/D/T) to readable labels (INSERT/UPDATE/DELETE/TRUNCATE)",
        complexity = MigrationComplexity.LOW,
        automationLevel = AutomationLevel.FULL,
        migrationTarget = "Replace DECODE(operation_type,'I','INSERT',...) with "
                        + "CASE WHEN operation_type='I' THEN 'INSERT' ... END in PostgreSQL"
    )
    @OracleDependency(
        domain = DependencyDomain.EMBEDDED_SQL,
        category = "NVL_FUNCTION",
        description = "NVL(ip_address,'unknown') and NVL(changed_by,'system') provide safe display "
                    + "defaults when audit columns are null",
        complexity = MigrationComplexity.LOW,
        automationLevel = AutomationLevel.FULL,
        migrationTarget = "Replace NVL(col, default) with COALESCE(col, default) in PostgreSQL"
    )
    @OracleDependency(
        domain = DependencyDomain.EMBEDDED_SQL,
        category = "DATE_ARITHMETIC",
        description = "SYSDATE - :days uses Oracle date - integer arithmetic for the look-back window",
        complexity = MigrationComplexity.LOW,
        automationLevel = AutomationLevel.FULL,
        migrationTarget = "Replace SYSDATE - :days with NOW() - (:days || ' days')::INTERVAL in PostgreSQL"
    )
    public List<AuditTrailDTO> getRecentAuditActivity(int days) {
        if (days <= 0 || days > 365) {
            throw new IllegalArgumentException("Days look-back must be between 1 and 365");
        }
        log.info("Fetching audit activity for last {} days via DECODE/NVL/SYSDATE arithmetic", days);
        // ORACLE-DEP [EMBEDDED_SQL:DECODE_FUNCTION] findAuditWithLabels uses DECODE for op labels
        // ORACLE-DEP [EMBEDDED_SQL:NVL_FUNCTION] findAuditWithLabels uses NVL for null defaults
        // ORACLE-DEP [EMBEDDED_SQL:DATE_ARITHMETIC] findAuditWithLabels uses SYSDATE - :days
        List<Object[]> rows = auditLogRepository.findAuditWithLabels(days);
        return rows.stream()
                   .map(this::mapAuditWithLabelsRow)
                   .collect(Collectors.toList());
    }

    // -------------------------------------------------------------------------
    // MyBatis-backed report methods
    // -------------------------------------------------------------------------

    /**
     * Returns portfolio allocation via the MyBatis XML mapper.
     * This provides a second code path exercising Oracle SQL in MyBatis XML
     * (ReportMapper.xml) rather than inline JDBC strings.
     *
     * @return raw MyBatis result maps with allocation ratio data
     */
    // ORACLE-DEP [ORM_DATA_ACCESS:MYBATIS_ORACLE_MAPPER] RATIO_TO_REPORT via MyBatis XML mapper
    @OracleDependency(
        domain = DependencyDomain.ORM_DATA_ACCESS,
        category = "MYBATIS_ORACLE_MAPPER",
        description = "Delegates to ReportMapper.getPortfolioAllocation() -- Oracle RATIO_TO_REPORT "
                    + "query defined in ReportMapper.xml, demonstrating MyBatis as a second data access "
                    + "path for the same Oracle analytic construct",
        complexity = MigrationComplexity.MEDIUM,
        automationLevel = AutomationLevel.PARTIAL,
        migrationTarget = "Rewrite ReportMapper.xml SQL using SUM(...) OVER () / SUM(...) OVER () pattern "
                        + "in PostgreSQL; no changes to the Java mapper interface required"
    )
    public List<Map<String, Object>> getPortfolioAllocationViaMyBatis() {
        log.debug("Fetching portfolio allocation via MyBatis / Oracle RATIO_TO_REPORT");
        // ORACLE-DEP [ORM_DATA_ACCESS:MYBATIS_ORACLE_MAPPER] MyBatis ReportMapper.xml uses RATIO_TO_REPORT
        return reportMapper.getPortfolioAllocation();
    }

    /**
     * Returns a customer ranking report via MyBatis.
     *
     * @param ntile number of quantiles for NTILE() partitioning
     * @return raw MyBatis result maps with NTILE / CUME_DIST / LISTAGG columns
     */
    // ORACLE-DEP [ORM_DATA_ACCESS:MYBATIS_ORACLE_MAPPER] NTILE/CUME_DIST/LISTAGG via MyBatis XML
    @OracleDependency(
        domain = DependencyDomain.ORM_DATA_ACCESS,
        category = "MYBATIS_ORACLE_MAPPER",
        description = "Delegates to ReportMapper.getCustomerRankings() -- MyBatis XML mapper exercising "
                    + "NTILE, CUME_DIST, and LISTAGG analytics defined in ReportMapper.xml",
        complexity = MigrationComplexity.MEDIUM,
        automationLevel = AutomationLevel.PARTIAL,
        migrationTarget = "NTILE and CUME_DIST are standard PostgreSQL window functions; "
                        + "replace LISTAGG with STRING_AGG in ReportMapper.xml"
    )
    public List<Map<String, Object>> getCustomerRankingsViaMyBatis(int ntile) {
        int effectiveNtile = ntile > 0 ? ntile : 4;
        log.debug("Fetching customer rankings via MyBatis NTILE({}) / CUME_DIST / LISTAGG", effectiveNtile);
        // ORACLE-DEP [ORM_DATA_ACCESS:MYBATIS_ORACLE_MAPPER] MyBatis ReportMapper.xml uses NTILE/CUME_DIST/LISTAGG
        return reportMapper.getCustomerRankings(effectiveNtile);
    }

    /**
     * Returns a monthly transaction pivot via MyBatis.
     *
     * @return raw MyBatis result maps with Oracle PIVOT row-to-column transform
     */
    // ORACLE-DEP [ORM_DATA_ACCESS:MYBATIS_ORACLE_MAPPER] Oracle PIVOT via MyBatis XML mapper
    @OracleDependency(
        domain = DependencyDomain.ORM_DATA_ACCESS,
        category = "MYBATIS_ORACLE_MAPPER",
        description = "Delegates to ReportMapper.getMonthlyTransactionPivot() -- MyBatis XML mapper "
                    + "for the Oracle PIVOT monthly transaction matrix query in ReportMapper.xml",
        complexity = MigrationComplexity.HIGH,
        automationLevel = AutomationLevel.PARTIAL,
        migrationTarget = "Replace Oracle PIVOT in ReportMapper.xml with conditional aggregation using "
                        + "SUM(amount) FILTER (WHERE transaction_type = 'DEBIT') in PostgreSQL"
    )
    public List<Map<String, Object>> getMonthlyTransactionPivotViaMyBatis() {
        log.debug("Fetching monthly transaction pivot via MyBatis Oracle PIVOT mapper");
        // ORACLE-DEP [ORM_DATA_ACCESS:MYBATIS_ORACLE_MAPPER] MyBatis ReportMapper.xml uses Oracle PIVOT
        return reportMapper.getMonthlyTransactionPivot();
    }

    // -------------------------------------------------------------------------
    // Private mapping helpers
    // -------------------------------------------------------------------------

    /**
     * Maps a row from {@link AuditLogRepository#findAuditWithLabels(int)}.
     *
     * <p>Expected column order (from the DECODE/NVL native query):
     * 0=audit_id, 1=performed_by (NVL result), 2=source_ip (NVL result),
     * 3=op_label (DECODE result), 4=table_name, 5=record_id, 6=changed_date
     */
    private AuditTrailDTO mapAuditWithLabelsRow(Object[] row) {
        AuditTrailDTO dto = new AuditTrailDTO();
        // col 0: audit_id
        if (row[0] != null) dto.setAuditId(((Number) row[0]).longValue());
        // col 1: performed_by -- result of NVL(changed_by, 'system')
        // ORACLE-DEP [EMBEDDED_SQL:NVL_FUNCTION] performed_by populated from NVL(changed_by,'system')
        dto.setChangedBy(row[1] != null ? row[1].toString() : "system");
        // col 2: source_ip -- result of NVL(ip_address, 'unknown')
        // ORACLE-DEP [EMBEDDED_SQL:NVL_FUNCTION] source_ip populated from NVL(ip_address,'unknown')
        dto.setIpAddress(row[2] != null ? row[2].toString() : "unknown");
        // col 3: op_label -- result of DECODE(operation_type,'I','INSERT','U','UPDATE',...)
        // ORACLE-DEP [EMBEDDED_SQL:DECODE_FUNCTION] op_label populated from Oracle DECODE()
        dto.setOperationType(row[3] != null ? row[3].toString() : null);
        // col 4: table_name
        dto.setTableName(row[4] != null ? row[4].toString() : null);
        // col 5: record_id
        if (row[5] != null) dto.setRecordId(((Number) row[5]).longValue());
        // col 6: changed_date -- Oracle TIMESTAMP / DATE value
        if (row[6] != null) {
            try {
                if (row[6] instanceof java.sql.Timestamp ts) {
                    dto.setChangedDate(ts.toLocalDateTime());
                } else if (row[6] instanceof java.sql.Date d) {
                    dto.setChangedDate(d.toLocalDate().atStartOfDay());
                }
            } catch (Exception e) {
                log.warn("Could not parse changed_date from audit row: {}", e.getMessage());
            }
        }
        return dto;
    }
}
