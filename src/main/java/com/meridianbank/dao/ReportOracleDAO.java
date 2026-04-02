package com.meridianbank.dao;

import com.meridianbank.annotation.*;
import com.meridianbank.dto.EmployeeHierarchyDTO;
import com.meridianbank.dto.FinancialReportDTO;
import com.meridianbank.dto.ReportParameterDTO;
import oracle.jdbc.OracleCallableStatement;
import oracle.jdbc.OracleTypes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * ReportOracleDAO — the richest single source of Oracle SQL constructs in the
 * Meridian Bank application.
 *
 * Oracle-specific SQL constructs exercised here:
 *   CONNECT BY PRIOR / START WITH / LEVEL / SYS_CONNECT_BY_PATH / CONNECT_BY_ROOT / CONNECT_BY_ISLEAF
 *   RATIO_TO_REPORT()     — portfolio allocation analytic function
 *   WIDTH_BUCKET()        — histogram / bucket distribution
 *   NTILE() / CUME_DIST() — ranking analytic functions
 *   PIVOT operator        — row-to-column monthly report matrix
 *   (+) outer join syntax — legacy comma-join with outer join marker
 *   LISTAGG()             — string aggregation
 *   DECODE()              — conditional expression (Oracle proprietary)
 *   date subtraction returning NUMBER (days_old)
 *   TO_CHAR(date,'format')
 *   TRUNC(SYSDATE) date arithmetic
 *   NVL()                 — null coalescing
 *   LEADING / USE_NL / USE_HASH / PARALLEL optimizer hints (hint syntax)
 *   REF CURSOR OUT parameter via OracleCallableStatement.getCursor()
 *
 * Every Oracle dependency is annotated with @OracleDependency and marked with an
 * inline // ORACLE-DEP comment for grep-based discovery.
 */
@Repository
public class ReportOracleDAO {

    private static final Logger log = LoggerFactory.getLogger(ReportOracleDAO.class);

    @Autowired
    private DataSource dataSource;

    // ---------------------------------------------------------------------------
    // METHOD 1 — getEmployeeOrgHierarchy
    // ---------------------------------------------------------------------------

    /**
     * Returns the full employee organizational hierarchy rooted at the given
     * employee ID using Oracle's CONNECT BY PRIOR hierarchical query syntax.
     *
     * Oracle-specific constructs: CONNECT BY PRIOR, START WITH, LEVEL pseudocolumn,
     * SYS_CONNECT_BY_PATH(), CONNECT_BY_ROOT, CONNECT_BY_ISLEAF, ORDER SIBLINGS BY.
     */
    @OracleDependency(
        domain = DependencyDomain.EMBEDDED_SQL,
        category = "CONNECT_BY",
        description = "Oracle CONNECT BY PRIOR hierarchical query for employee org chart — "
                    + "START WITH sets root, CONNECT BY PRIOR traverses parent->child links",
        complexity = MigrationComplexity.MEDIUM,
        automationLevel = AutomationLevel.PARTIAL,
        migrationTarget = "Replace with recursive CTE: WITH RECURSIVE emp AS "
                        + "(SELECT ... UNION ALL SELECT ... FROM emp JOIN employees ...) "
                        + "in PostgreSQL"
    )
    @OracleDependency(
        domain = DependencyDomain.EMBEDDED_SQL,
        category = "LEVEL_PSEUDOCOLUMN",
        description = "LEVEL pseudocolumn returns depth in the hierarchy tree (1 = root)",
        complexity = MigrationComplexity.MEDIUM,
        automationLevel = AutomationLevel.PARTIAL,
        migrationTarget = "Replace with a depth counter column in the recursive CTE anchor/recursive parts"
    )
    @OracleDependency(
        domain = DependencyDomain.EMBEDDED_SQL,
        category = "SYS_CONNECT_BY_PATH",
        description = "SYS_CONNECT_BY_PATH(col, '/') builds the full path from root to current node",
        complexity = MigrationComplexity.MEDIUM,
        automationLevel = AutomationLevel.PARTIAL,
        migrationTarget = "Replace with string concatenation in the recursive CTE: "
                        + "path || '/' || last_name"
    )
    @OracleDependency(
        domain = DependencyDomain.EMBEDDED_SQL,
        category = "CONNECT_BY_ROOT",
        description = "CONNECT_BY_ROOT returns the value of a column in the root row of the hierarchy",
        complexity = MigrationComplexity.MEDIUM,
        automationLevel = AutomationLevel.PARTIAL,
        migrationTarget = "Replace by carrying the root column value in the CTE anchor SELECT"
    )
    public List<EmployeeHierarchyDTO> getEmployeeOrgHierarchy(Long rootEmployeeId) {
        // ORACLE-DEP [EMBEDDED_SQL:CONNECT_BY] START WITH / CONNECT BY PRIOR hierarchical traversal
        // ORACLE-DEP [EMBEDDED_SQL:LEVEL_PSEUDOCOLUMN] LEVEL depth counter
        // ORACLE-DEP [EMBEDDED_SQL:SYS_CONNECT_BY_PATH] full path string from root to current node
        // ORACLE-DEP [EMBEDDED_SQL:CONNECT_BY_ROOT] root row column value propagated down hierarchy
        // ORACLE-DEP [EMBEDDED_SQL:CONNECT_BY_ISLEAF] leaf-node indicator (1 = no children)
        final String sql =
            "SELECT /*+ NO_MERGE */ "
            + "  e.employee_id, "
            + "  e.first_name, "
            + "  e.last_name, "
            + "  e.job_title, "
            + "  e.department_id, "
            + "  LEVEL                                   AS hierarchy_level, "      // ORACLE-DEP [EMBEDDED_SQL:LEVEL_PSEUDOCOLUMN]
            + "  SYS_CONNECT_BY_PATH(e.last_name, '/')  AS full_path, "             // ORACLE-DEP [EMBEDDED_SQL:SYS_CONNECT_BY_PATH]
            + "  CONNECT_BY_ROOT e.last_name             AS org_root, "             // ORACLE-DEP [EMBEDDED_SQL:CONNECT_BY_ROOT]
            + "  CONNECT_BY_ISLEAF                       AS is_leaf, "              // ORACLE-DEP [EMBEDDED_SQL:CONNECT_BY_ISLEAF]
            + "  PRIOR e.employee_id                     AS manager_emp_id "        // ORACLE-DEP [EMBEDDED_SQL:CONNECT_BY] PRIOR reference
            + "FROM   MERIDIAN.EMPLOYEES e "
            + "START WITH e.employee_id = ? "                                       // ORACLE-DEP [EMBEDDED_SQL:CONNECT_BY] START WITH
            + "CONNECT BY PRIOR e.employee_id = e.manager_id "                      // ORACLE-DEP [EMBEDDED_SQL:CONNECT_BY] CONNECT BY PRIOR
            + "ORDER SIBLINGS BY e.last_name";                                      // ORACLE-DEP [EMBEDDED_SQL:CONNECT_BY] ORDER SIBLINGS

        List<EmployeeHierarchyDTO> results = new ArrayList<>();

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setLong(1, rootEmployeeId);

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    EmployeeHierarchyDTO dto = new EmployeeHierarchyDTO();
                    dto.setEmployeeId(rs.getLong("employee_id"));
                    dto.setFirstName(rs.getString("first_name"));
                    dto.setLastName(rs.getString("last_name"));
                    dto.setJobTitle(rs.getString("job_title"));
                    dto.setDepartment(rs.getString("department_id")); // mapped from department_id column
                    dto.setLevel(rs.getInt("hierarchy_level"));       // ORACLE-DEP: LEVEL pseudocolumn
                    dto.setHierarchyPath(rs.getString("full_path"));   // ORACLE-DEP: SYS_CONNECT_BY_PATH
                    dto.setRootEmployee(rs.getString("org_root"));     // ORACLE-DEP: CONNECT_BY_ROOT
                    dto.setIsLeaf(rs.getInt("is_leaf") == 1);          // ORACLE-DEP: CONNECT_BY_ISLEAF
                    dto.setManagerId(rs.getLong("manager_emp_id"));
                    results.add(dto);
                }
            }

            log.info("getEmployeeOrgHierarchy: {} employees returned for root {}", results.size(), rootEmployeeId);
            return results;

        } catch (SQLException e) {
            // ORACLE-DEP [EMBEDDED_SQL:ORA_ERROR_CODES] ORA-01436 CONNECT BY loop in user data
            log.error("getEmployeeOrgHierarchy failed ORA-{}: {}", e.getErrorCode(), e.getMessage()); // ORACLE-DEP [EMBEDDED_SQL:ORA_ERROR_CODES]
            throw new RuntimeException("Employee hierarchy query failed: ORA-" + e.getErrorCode(), e);
        }
    }

    // ---------------------------------------------------------------------------
    // METHOD 2 — getPortfolioAllocation
    // ---------------------------------------------------------------------------

    /**
     * Returns each account type's share of a customer's total portfolio using the
     * Oracle RATIO_TO_REPORT() analytic function.  RATIO_TO_REPORT divides each
     * group's value by the grand total computed over the analytic window.
     */
    @OracleDependency(
        domain = DependencyDomain.EMBEDDED_SQL,
        category = "RATIO_TO_REPORT",
        description = "RATIO_TO_REPORT(SUM(balance)) OVER () computes each account type's "
                    + "proportion of the customer's total portfolio balance",
        complexity = MigrationComplexity.MEDIUM,
        automationLevel = AutomationLevel.FULL,
        migrationTarget = "Replace with SUM(balance) / SUM(SUM(balance)) OVER () in PostgreSQL — "
                        + "equivalent using standard window functions"
    )
    public List<FinancialReportDTO> getPortfolioAllocation(Long customerId) {
        // ORACLE-DEP [EMBEDDED_SQL:RATIO_TO_REPORT] RATIO_TO_REPORT() analytic function for portfolio share
        final String sql =
            "SELECT account_type, "
            + "  SUM(balance)                                        AS total_balance, "
            + "  RATIO_TO_REPORT(SUM(balance)) OVER ()              AS portfolio_ratio, "   // ORACLE-DEP [EMBEDDED_SQL:RATIO_TO_REPORT]
            + "  ROUND(RATIO_TO_REPORT(SUM(balance)) OVER () * 100, 2) AS portfolio_pct "   // ORACLE-DEP [EMBEDDED_SQL:RATIO_TO_REPORT]
            + "FROM   MERIDIAN.ACCOUNTS "
            + "WHERE  customer_id = ? "
            + "AND    status      = 'ACTIVE' "
            + "GROUP  BY account_type "
            + "ORDER  BY total_balance DESC";

        List<FinancialReportDTO> results = new ArrayList<>();

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setLong(1, customerId);

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    // Build a FinancialReportDTO with portfolio allocation map
                    // ORACLE-DEP [EMBEDDED_SQL:RATIO_TO_REPORT] portfolio_ratio from RATIO_TO_REPORT()
                    FinancialReportDTO dto = new FinancialReportDTO();
                    dto.setReportType("PORTFOLIO");
                    Map<String, Double> allocation = new java.util.HashMap<>();
                    allocation.put(rs.getString("account_type"),
                            rs.getBigDecimal("portfolio_ratio") != null
                            ? rs.getBigDecimal("portfolio_ratio").doubleValue() : 0.0);
                    dto.setPortfolioAllocation(allocation);
                    results.add(dto);
                }
            }

            log.info("getPortfolioAllocation: {} account types returned for customer {}", results.size(), customerId);
            return results;

        } catch (SQLException e) {
            // ORACLE-DEP [EMBEDDED_SQL:ORA_ERROR_CODES] ORA-00904 invalid identifier if column missing
            log.error("getPortfolioAllocation failed ORA-{}: {}", e.getErrorCode(), e.getMessage()); // ORACLE-DEP [EMBEDDED_SQL:ORA_ERROR_CODES]
            throw new RuntimeException("Portfolio allocation query failed: ORA-" + e.getErrorCode(), e);
        }
    }

    // ---------------------------------------------------------------------------
    // METHOD 3 — getTransactionDistribution
    // ---------------------------------------------------------------------------

    /**
     * Distributes transaction amounts into 10 equal-width buckets between 0 and
     * 10,000 using the SQL standard WIDTH_BUCKET() function.  WIDTH_BUCKET() is
     * defined in the SQL:2003 standard and is available in PostgreSQL without change.
     */
    @OracleDependency(
        domain = DependencyDomain.EMBEDDED_SQL,
        category = "WIDTH_BUCKET",
        description = "WIDTH_BUCKET(amount, 0, 10000, 10) assigns each transaction to one of "
                    + "10 equal-width histogram buckets between 0 and 10,000",
        complexity = MigrationComplexity.LOW,
        automationLevel = AutomationLevel.FULL,
        migrationTarget = "WIDTH_BUCKET() is SQL standard — available in PostgreSQL without change"
    )
    public List<Map<String, Object>> getTransactionDistribution(Long accountId) {
        // ORACLE-DEP [EMBEDDED_SQL:WIDTH_BUCKET] WIDTH_BUCKET() histogram bucketing function
        final String sql =
            "SELECT WIDTH_BUCKET(amount, 0, 10000, 10)  AS bucket, "  // ORACLE-DEP [EMBEDDED_SQL:WIDTH_BUCKET]
            + "  COUNT(*)                                AS tx_count, "
            + "  MIN(amount)                             AS bucket_min, "
            + "  MAX(amount)                             AS bucket_max, "
            + "  AVG(amount)                             AS bucket_avg "
            + "FROM   MERIDIAN.TRANSACTIONS "
            + "WHERE  account_id = ? "
            + "GROUP  BY WIDTH_BUCKET(amount, 0, 10000, 10) " // ORACLE-DEP [EMBEDDED_SQL:WIDTH_BUCKET]
            + "ORDER  BY bucket";

        List<Map<String, Object>> results = new ArrayList<>();

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setLong(1, accountId);

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> row = new HashMap<>();
                    row.put("bucket", rs.getInt("bucket"));
                    row.put("txCount", rs.getInt("tx_count"));
                    row.put("bucketMin", rs.getBigDecimal("bucket_min"));
                    row.put("bucketMax", rs.getBigDecimal("bucket_max"));
                    row.put("bucketAvg", rs.getBigDecimal("bucket_avg"));
                    results.add(row);
                }
            }

            log.info("getTransactionDistribution: {} buckets returned for account {}", results.size(), accountId);
            return results;

        } catch (SQLException e) {
            // ORACLE-DEP [EMBEDDED_SQL:ORA_ERROR_CODES] ORA-01428 argument out of range for WIDTH_BUCKET
            log.error("getTransactionDistribution failed ORA-{}: {}", e.getErrorCode(), e.getMessage()); // ORACLE-DEP [EMBEDDED_SQL:ORA_ERROR_CODES]
            throw new RuntimeException("Transaction distribution query failed: ORA-" + e.getErrorCode(), e);
        }
    }

    // ---------------------------------------------------------------------------
    // METHOD 4 — getCustomerRanking
    // ---------------------------------------------------------------------------

    /**
     * Ranks customers by total asset value using NTILE() (decile bands) and
     * CUME_DIST() (cumulative distribution) analytic functions.
     */
    @OracleDependency(
        domain = DependencyDomain.EMBEDDED_SQL,
        category = "NTILE_FUNCTION",
        description = "NTILE(10) OVER (ORDER BY total_assets DESC) partitions customers into "
                    + "10 equal-sized decile bands by total asset value",
        complexity = MigrationComplexity.LOW,
        automationLevel = AutomationLevel.FULL,
        migrationTarget = "NTILE() is SQL standard — available in PostgreSQL without change"
    )
    @OracleDependency(
        domain = DependencyDomain.EMBEDDED_SQL,
        category = "CUME_DIST_FUNCTION",
        description = "CUME_DIST() OVER (ORDER BY total_assets) returns cumulative distribution "
                    + "fraction (0..1) for each customer in the asset ranking",
        complexity = MigrationComplexity.LOW,
        automationLevel = AutomationLevel.FULL,
        migrationTarget = "CUME_DIST() is SQL standard — available in PostgreSQL without change"
    )
    public List<Map<String, Object>> getCustomerRanking(String rankBy) {
        // ORACLE-DEP [EMBEDDED_SQL:NTILE_FUNCTION] NTILE(10) for decile band assignment
        // ORACLE-DEP [EMBEDDED_SQL:CUME_DIST_FUNCTION] CUME_DIST() cumulative distribution fraction
        final String sql =
            "SELECT c.customer_id, "
            + "  c.first_name, "
            + "  c.last_name, "
            + "  SUM(a.balance)                                       AS total_assets, "
            + "  NTILE(10) OVER (ORDER BY SUM(a.balance) DESC)        AS asset_decile, "     // ORACLE-DEP [EMBEDDED_SQL:NTILE_FUNCTION]
            + "  CUME_DIST() OVER (ORDER BY SUM(a.balance))           AS cumulative_dist "   // ORACLE-DEP [EMBEDDED_SQL:CUME_DIST_FUNCTION]
            + "FROM   MERIDIAN.CUSTOMERS c "
            + "JOIN   MERIDIAN.ACCOUNTS  a ON c.customer_id = a.customer_id "
            + "WHERE  a.status = 'ACTIVE' "
            + "GROUP  BY c.customer_id, c.first_name, c.last_name "
            + "ORDER  BY total_assets DESC";

        List<Map<String, Object>> results = new ArrayList<>();

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> row = new HashMap<>();
                    row.put("customerId", rs.getLong("customer_id"));
                    row.put("firstName", rs.getString("first_name"));
                    row.put("lastName", rs.getString("last_name"));
                    row.put("totalAssets", rs.getBigDecimal("total_assets"));
                    row.put("assetDecile", rs.getInt("asset_decile"));
                    row.put("cumulativeDist", rs.getBigDecimal("cumulative_dist"));
                    results.add(row);
                }
            }

            log.info("getCustomerRanking: {} customers ranked by '{}'", results.size(), rankBy);
            return results;

        } catch (SQLException e) {
            // ORACLE-DEP [EMBEDDED_SQL:ORA_ERROR_CODES] ORA-00979 not a GROUP BY expression
            log.error("getCustomerRanking failed ORA-{}: {}", e.getErrorCode(), e.getMessage()); // ORACLE-DEP [EMBEDDED_SQL:ORA_ERROR_CODES]
            throw new RuntimeException("Customer ranking query failed: ORA-" + e.getErrorCode(), e);
        }
    }

    // ---------------------------------------------------------------------------
    // METHOD 5 — getMonthlyTransactionPivot
    // ---------------------------------------------------------------------------

    /**
     * Produces a monthly transaction summary pivoted so each calendar month becomes
     * a column.  Oracle's PIVOT operator is a SQL extension; the PostgreSQL
     * equivalent uses crosstab() from the tablefunc extension, or manual
     * CASE WHEN aggregation.
     */
    @OracleDependency(
        domain = DependencyDomain.EMBEDDED_SQL,
        category = "PIVOT_OPERATOR",
        description = "Oracle PIVOT operator rotates month-name rows into columns for a "
                    + "monthly transaction summary matrix keyed by account_type",
        complexity = MigrationComplexity.HIGH,
        automationLevel = AutomationLevel.PARTIAL,
        migrationTarget = "Replace with crosstab() from PostgreSQL tablefunc extension, or "
                        + "manual: SUM(CASE WHEN EXTRACT(MONTH FROM ...) = 1 THEN amount END) AS jan, ..."
    )
    @OracleDependency(
        domain = DependencyDomain.EMBEDDED_SQL,
        category = "TO_CHAR_DATE",
        description = "TO_CHAR(transaction_date,'MON') formats the date as a 3-letter month "
                    + "abbreviation for use as the PIVOT column key",
        complexity = MigrationComplexity.LOW,
        automationLevel = AutomationLevel.FULL,
        migrationTarget = "Replace TO_CHAR(date,'MON') with TO_CHAR(date,'Mon') in PostgreSQL "
                        + "(same function, same syntax — minor format-mask differences)"
    )
    public List<Map<String, Object>> getMonthlyTransactionPivot(int year) {
        // ORACLE-DEP [EMBEDDED_SQL:PIVOT_OPERATOR] Oracle PIVOT row-to-column transformation
        // ORACLE-DEP [EMBEDDED_SQL:TO_CHAR_DATE] TO_CHAR(transaction_date,'MON') 3-letter month key
        final String sql =
            "SELECT * FROM ( "
            + "  SELECT a.account_type, "
            + "         TO_CHAR(t.transaction_date, 'MON') AS month_name, " // ORACLE-DEP [EMBEDDED_SQL:TO_CHAR_DATE]
            + "         t.amount "
            + "  FROM   MERIDIAN.TRANSACTIONS t "
            + "  JOIN   MERIDIAN.ACCOUNTS     a ON t.account_id = a.account_id "
            + "  WHERE  EXTRACT(YEAR FROM t.transaction_date) = ? "
            + ") "
            + "PIVOT ( "                                                     // ORACLE-DEP [EMBEDDED_SQL:PIVOT_OPERATOR]
            + "  SUM(amount) "
            + "  FOR month_name IN ( "
            + "    'JAN' AS jan, 'FEB' AS feb, 'MAR' AS mar, 'APR' AS apr, "
            + "    'MAY' AS may, 'JUN' AS jun, 'JUL' AS jul, 'AUG' AS aug, "
            + "    'SEP' AS sep, 'OCT' AS oct, 'NOV' AS nov, 'DEC' AS dec  "
            + "  ) "
            + ") "
            + "ORDER BY account_type";

        List<Map<String, Object>> results = new ArrayList<>();
        String[] months = {"jan","feb","mar","apr","may","jun","jul","aug","sep","oct","nov","dec"};

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setInt(1, year);

            try (ResultSet rs = ps.executeQuery()) {
                ResultSetMetaData meta = rs.getMetaData();
                while (rs.next()) {
                    Map<String, Object> row = new HashMap<>();
                    for (int col = 1; col <= meta.getColumnCount(); col++) {
                        String colName = meta.getColumnLabel(col).toLowerCase();
                        Object value = rs.getObject(col);
                        row.put(colName, value);
                    }
                    results.add(row);
                }
            }

            log.info("getMonthlyTransactionPivot: {} account-type rows returned for year {}", results.size(), year);
            return results;

        } catch (SQLException e) {
            // ORACLE-DEP [EMBEDDED_SQL:ORA_ERROR_CODES] ORA-56901 non-constant expression in PIVOT IN clause
            log.error("getMonthlyTransactionPivot failed ORA-{}: {}", e.getErrorCode(), e.getMessage()); // ORACLE-DEP [EMBEDDED_SQL:ORA_ERROR_CODES]
            throw new RuntimeException("Monthly pivot query failed: ORA-" + e.getErrorCode(), e);
        }
    }

    // ---------------------------------------------------------------------------
    // METHOD 6 — getLegacyAccountReport
    // ---------------------------------------------------------------------------

    /**
     * Demonstrates the Oracle (+) outer join operator — the pre-ANSI-92 Oracle
     * proprietary syntax for outer joins using a comma-separated FROM clause with
     * the (+) marker on the optional side of the join.
     */
    @OracleDependency(
        domain = DependencyDomain.EMBEDDED_SQL,
        category = "OUTER_JOIN_PLUS",
        description = "Oracle (+) outer join operator used in legacy comma-join syntax — "
                    + "WHERE c.customer_id = a.customer_id(+) means customers LEFT OUTER JOIN accounts",
        complexity = MigrationComplexity.LOW,
        automationLevel = AutomationLevel.FULL,
        migrationTarget = "Rewrite as ANSI JOIN: FROM MERIDIAN.CUSTOMERS c "
                        + "LEFT OUTER JOIN MERIDIAN.ACCOUNTS a ON c.customer_id = a.customer_id"
    )
    @OracleDependency(
        domain = DependencyDomain.EMBEDDED_SQL,
        category = "NVL_FUNCTION",
        description = "NVL(a.balance, 0) and NVL(a.overdraft_limit, 0) default NULLs from the outer join",
        complexity = MigrationComplexity.LOW,
        automationLevel = AutomationLevel.FULL,
        migrationTarget = "Replace NVL(col, default) with COALESCE(col, default) in PostgreSQL"
    )
    public List<Map<String, Object>> getLegacyAccountReport() {
        // ORACLE-DEP [EMBEDDED_SQL:OUTER_JOIN_PLUS] Oracle (+) outer join — legacy comma-join syntax
        // ORACLE-DEP [EMBEDDED_SQL:NVL_FUNCTION] NVL() null-coalescing for outer-joined nulls
        final String sql =
            "SELECT c.customer_id, "
            + "  c.last_name, "
            + "  a.account_number, "
            + "  NVL(a.balance, 0)          AS balance, "          // ORACLE-DEP [EMBEDDED_SQL:NVL_FUNCTION]
            + "  NVL(a.overdraft_limit, 0)  AS overdraft "         // ORACLE-DEP [EMBEDDED_SQL:NVL_FUNCTION]
            + "FROM  MERIDIAN.CUSTOMERS c, "
            + "      MERIDIAN.ACCOUNTS  a "
            + "WHERE c.customer_id = a.customer_id(+) "            // ORACLE-DEP [EMBEDDED_SQL:OUTER_JOIN_PLUS]
            + "AND   c.status      = 'ACTIVE' "
            + "ORDER BY c.last_name, a.account_number";

        List<Map<String, Object>> results = new ArrayList<>();

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> row = new HashMap<>();
                    row.put("customerId", rs.getLong("customer_id"));
                    row.put("lastName", rs.getString("last_name"));
                    row.put("accountNumber", rs.getString("account_number"));
                    row.put("balance", rs.getBigDecimal("balance"));
                    row.put("overdraft", rs.getBigDecimal("overdraft"));
                    results.add(row);
                }
            }

            log.info("getLegacyAccountReport: {} rows returned", results.size());
            return results;

        } catch (SQLException e) {
            // ORACLE-DEP [EMBEDDED_SQL:ORA_ERROR_CODES] ORA-01468 a table may be outer joined to at most one other table
            log.error("getLegacyAccountReport failed ORA-{}: {}", e.getErrorCode(), e.getMessage()); // ORACLE-DEP [EMBEDDED_SQL:ORA_ERROR_CODES]
            throw new RuntimeException("Legacy account report failed: ORA-" + e.getErrorCode(), e);
        }
    }

    // ---------------------------------------------------------------------------
    // METHOD 7 — getAccountSummaryWithListagg
    // ---------------------------------------------------------------------------

    /**
     * Aggregates all of a customer's account numbers and types into
     * comma-delimited strings using LISTAGG() WITHIN GROUP (ORDER BY ...).
     * LISTAGG is an Oracle extension; the PostgreSQL equivalent is STRING_AGG()
     * which uses identical semantics but different syntax.
     */
    @OracleDependency(
        domain = DependencyDomain.EMBEDDED_SQL,
        category = "LISTAGG_FUNCTION",
        description = "LISTAGG(account_number, ', ') WITHIN GROUP (ORDER BY open_date) and "
                    + "LISTAGG(account_type, ' | ') WITHIN GROUP (ORDER BY account_type) "
                    + "aggregate multiple account rows into delimited strings per customer",
        complexity = MigrationComplexity.LOW,
        automationLevel = AutomationLevel.FULL,
        migrationTarget = "Replace LISTAGG(col, sep) WITHIN GROUP (ORDER BY x) with "
                        + "STRING_AGG(col, sep ORDER BY x) in PostgreSQL"
    )
    public Map<String, Object> getAccountSummaryWithListagg(Long customerId) {
        // ORACLE-DEP [EMBEDDED_SQL:LISTAGG_FUNCTION] LISTAGG() aggregates account rows into delimited strings
        final String sql =
            "SELECT customer_id, "
            + "  LISTAGG(account_number, ', ')  WITHIN GROUP (ORDER BY open_date)    AS all_accounts, "  // ORACLE-DEP [EMBEDDED_SQL:LISTAGG_FUNCTION]
            + "  LISTAGG(account_type,   ' | ') WITHIN GROUP (ORDER BY account_type) AS account_types, " // ORACLE-DEP [EMBEDDED_SQL:LISTAGG_FUNCTION]
            + "  COUNT(*)                                                              AS account_count, "
            + "  SUM(balance)                                                          AS total_balance "
            + "FROM   MERIDIAN.ACCOUNTS "
            + "WHERE  customer_id = ? "
            + "GROUP  BY customer_id";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setLong(1, customerId);

            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    Map<String, Object> result = new HashMap<>();
                    result.put("customerId", rs.getLong("customer_id"));
                    result.put("allAccounts", rs.getString("all_accounts"));
                    result.put("accountTypes", rs.getString("account_types"));
                    result.put("accountCount", rs.getInt("account_count"));
                    result.put("totalBalance", rs.getBigDecimal("total_balance"));
                    log.info("getAccountSummaryWithListagg: customer {} has {} accounts", customerId, result.get("accountCount"));
                    return result;
                }
            }

            log.warn("getAccountSummaryWithListagg: no accounts found for customer {}", customerId);
            return new HashMap<>();

        } catch (SQLException e) {
            // ORACLE-DEP [EMBEDDED_SQL:ORA_ERROR_CODES] ORA-01489 result of string concatenation is too long (LISTAGG overflow)
            log.error("getAccountSummaryWithListagg failed ORA-{}: {}", e.getErrorCode(), e.getMessage()); // ORACLE-DEP [EMBEDDED_SQL:ORA_ERROR_CODES]
            throw new RuntimeException("Account summary with LISTAGG failed: ORA-" + e.getErrorCode(), e);
        }
    }

    // ---------------------------------------------------------------------------
    // METHOD 8 — streamLargeReportViaRefCursor
    // ---------------------------------------------------------------------------

    /**
     * Calls REPORT_PKG.GENERATE_REPORT which returns a REF CURSOR (SYS_REFCURSOR)
     * as an OUT parameter.  The REF CURSOR is retrieved using the Oracle-specific
     * OracleCallableStatement.getCursor() method.
     *
     * NOTE: The caller is responsible for closing both the returned ResultSet and
     * the underlying Connection.  Call this method inside a try-with-resources block
     * that also closes the connection obtained from the DataSource.
     */
    @OracleDependency(
        domain = DependencyDomain.PLSQL_INVOCATION,
        category = "REF_CURSOR",
        description = "OracleTypes.CURSOR OUT parameter carries a SYS_REFCURSOR returned by "
                    + "REPORT_PKG.GENERATE_REPORT — cursor is retrieved via getCursor(3)",
        complexity = MigrationComplexity.HIGH,
        automationLevel = AutomationLevel.PARTIAL,
        migrationTarget = "Replace REF CURSOR pattern with a standard SELECT query returning a "
                        + "ResultSet, or use Spring JdbcTemplate.query() — PostgreSQL functions "
                        + "can return SETOF rowtype without a cursor OUT parameter"
    )
    @OracleDependency(
        domain = DependencyDomain.ORACLE_JAVA_API,
        category = "ORACLE_CALLABLE_STMT",
        description = "OracleCallableStatement.getCursor(int) retrieves the REF CURSOR result "
                    + "set from the OUT parameter at the given index",
        complexity = MigrationComplexity.MEDIUM,
        automationLevel = AutomationLevel.PARTIAL,
        migrationTarget = "Replace with cs.getObject(3, ResultSet.class) when using a JDBC 4.2+ "
                        + "driver, or restructure to a direct query returning a ResultSet"
    )
    public ResultSet streamLargeReportViaRefCursor(ReportParameterDTO params) throws SQLException {
        // ORACLE-DEP [PLSQL_INVOCATION:REF_CURSOR] OracleTypes.CURSOR OUT parameter — REF CURSOR from stored procedure
        // ORACLE-DEP [ORACLE_JAVA_API:ORACLE_CALLABLE_STMT] OracleCallableStatement.getCursor() retrieves cursor
        final String call = "{call MERIDIAN.REPORT_PKG.GENERATE_REPORT(?,?,?)}";

        Connection conn = dataSource.getConnection(); // caller responsible for close
        CallableStatement cs = conn.prepareCall(call);

        try {
            cs.setDate(1, java.sql.Date.valueOf(params.getStartDate())); // IN: start date
            cs.setDate(2, java.sql.Date.valueOf(params.getEndDate()));   // IN: end date
            // ORACLE-DEP [PLSQL_INVOCATION:ORACLE_TYPES] OracleTypes.CURSOR registers OUT REF CURSOR param
            cs.registerOutParameter(3, OracleTypes.CURSOR); // ORACLE-DEP [PLSQL_INVOCATION:ORACLE_TYPES]

            cs.execute();

            // ORACLE-DEP [ORACLE_JAVA_API:ORACLE_CALLABLE_STMT] getCursor() is Oracle-specific; retrieves SYS_REFCURSOR
            OracleCallableStatement ocs = (OracleCallableStatement) cs; // ORACLE-DEP [ORACLE_JAVA_API:ORACLE_CALLABLE_STMT]
            ResultSet rs = ocs.getCursor(3); // ORACLE-DEP [ORACLE_JAVA_API:ORACLE_CALLABLE_STMT]

            log.info("streamLargeReportViaRefCursor: REF CURSOR opened for date range {} to {}",
                     params.getStartDate(), params.getEndDate());
            return rs; // caller must close rs and conn

        } catch (SQLException e) {
            try { cs.close(); } catch (SQLException ignore) {}
            try { conn.close(); } catch (SQLException ignore) {}
            // ORACLE-DEP [EMBEDDED_SQL:ORA_ERROR_CODES] ORA-06502 numeric or value error in procedure
            log.error("streamLargeReportViaRefCursor failed ORA-{}: {}", e.getErrorCode(), e.getMessage()); // ORACLE-DEP [EMBEDDED_SQL:ORA_ERROR_CODES]
            throw new RuntimeException("REF CURSOR report streaming failed: ORA-" + e.getErrorCode(), e);
        }
    }

    // ---------------------------------------------------------------------------
    // METHOD 9 — getTransactionAging
    // ---------------------------------------------------------------------------

    /**
     * Returns transactions with an aging bucket label.  Uses Oracle date subtraction
     * (TRUNC(SYSDATE) - TRUNC(transaction_date) returns a NUMBER of days) and
     * DECODE() to convert the day count into a human-readable aging label.
     */
    @OracleDependency(
        domain = DependencyDomain.EMBEDDED_SQL,
        category = "DATE_ARITHMETIC",
        description = "TRUNC(SYSDATE) - TRUNC(transaction_date) returns a NUMBER of days "
                    + "elapsed — Oracle date subtraction always yields a numeric day count",
        complexity = MigrationComplexity.LOW,
        automationLevel = AutomationLevel.FULL,
        migrationTarget = "Replace with EXTRACT(DAY FROM (CURRENT_DATE - "
                        + "DATE_TRUNC('day', transaction_date))) in PostgreSQL — "
                        + "PostgreSQL date subtraction returns an INTERVAL, not a NUMBER"
    )
    @OracleDependency(
        domain = DependencyDomain.EMBEDDED_SQL,
        category = "DECODE_FUNCTION",
        description = "DECODE(expr, val1, result1, ..., default) maps the CASE expression "
                    + "integer (0/1/2/else) to aging-bucket label strings",
        complexity = MigrationComplexity.LOW,
        automationLevel = AutomationLevel.FULL,
        migrationTarget = "Replace DECODE(expr, v1, r1, ...) with CASE WHEN expr = v1 THEN r1 ... END in PostgreSQL"
    )
    @OracleDependency(
        domain = DependencyDomain.EMBEDDED_SQL,
        category = "TRUNC_DATE",
        description = "TRUNC(SYSDATE) and TRUNC(transaction_date) normalize timestamps to midnight for day-level aging",
        complexity = MigrationComplexity.LOW,
        automationLevel = AutomationLevel.FULL,
        migrationTarget = "Replace TRUNC(date_col) with DATE_TRUNC('day', date_col) in PostgreSQL"
    )
    public List<Map<String, Object>> getTransactionAging(Long accountId) {
        // ORACLE-DEP [EMBEDDED_SQL:DATE_ARITHMETIC] TRUNC(SYSDATE) - TRUNC(transaction_date) returns NUMBER of days
        // ORACLE-DEP [EMBEDDED_SQL:DECODE_FUNCTION] DECODE() maps integer bucket to label string
        // ORACLE-DEP [EMBEDDED_SQL:TRUNC_DATE] TRUNC() normalizes dates to midnight
        // ORACLE-DEP [EMBEDDED_SQL:SYSDATE] SYSDATE for current date reference
        final String sql =
            "SELECT transaction_id, "
            + "  amount, "
            + "  description, "
            + "  transaction_date, "
            + "  TRUNC(SYSDATE) - TRUNC(transaction_date)                             AS days_old, "  // ORACLE-DEP [EMBEDDED_SQL:DATE_ARITHMETIC]
            + "  DECODE( "                                                                             // ORACLE-DEP [EMBEDDED_SQL:DECODE_FUNCTION]
            + "    CASE "
            + "      WHEN TRUNC(SYSDATE) - TRUNC(transaction_date) <= 30  THEN 0 "                    // ORACLE-DEP [EMBEDDED_SQL:TRUNC_DATE]
            + "      WHEN TRUNC(SYSDATE) - TRUNC(transaction_date) <= 60  THEN 1 "                    // ORACLE-DEP [EMBEDDED_SQL:TRUNC_DATE]
            + "      WHEN TRUNC(SYSDATE) - TRUNC(transaction_date) <= 90  THEN 2 "
            + "      ELSE 3 "
            + "    END, "
            + "    0, 'CURRENT', "
            + "    1, '30 DAYS', "
            + "    2, '60 DAYS', "
            + "       '90+ DAYS' "
            + "  )                                                                     AS aging_bucket "
            + "FROM   MERIDIAN.TRANSACTIONS "
            + "WHERE  account_id = ? "
            + "ORDER  BY transaction_date DESC";

        List<Map<String, Object>> results = new ArrayList<>();

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setLong(1, accountId);

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> row = new HashMap<>();
                    row.put("transactionId", rs.getLong("transaction_id"));
                    row.put("amount", rs.getBigDecimal("amount"));
                    row.put("description", rs.getString("description"));
                    row.put("transactionDate", rs.getTimestamp("transaction_date"));
                    row.put("daysOld", rs.getInt("days_old"));
                    row.put("agingBucket", rs.getString("aging_bucket"));
                    results.add(row);
                }
            }

            log.info("getTransactionAging: {} transactions returned for account {}", results.size(), accountId);
            return results;

        } catch (SQLException e) {
            // ORACLE-DEP [EMBEDDED_SQL:ORA_ERROR_CODES] ORA-01861 literal does not match format string
            log.error("getTransactionAging failed ORA-{}: {}", e.getErrorCode(), e.getMessage()); // ORACLE-DEP [EMBEDDED_SQL:ORA_ERROR_CODES]
            throw new RuntimeException("Transaction aging query failed: ORA-" + e.getErrorCode(), e);
        }
    }

    // ---------------------------------------------------------------------------
    // METHOD 10 — getLeadingHintReport
    // ---------------------------------------------------------------------------

    /**
     * Runs a complex three-table join report with multiple Oracle optimizer hints:
     *   LEADING(c a t)  — force join order: customers -> accounts -> transactions
     *   USE_NL(a)       — use nested-loop join for accounts
     *   USE_HASH(t)     — use hash join for transactions
     *   PARALLEL(t,4)   — parallelize the transactions table scan
     * Also uses date arithmetic (SYSDATE - 30) for the 30-day rolling window.
     */
    @OracleDependency(
        domain = DependencyDomain.EMBEDDED_SQL,
        category = "LEADING_HINT",
        description = "/*+ LEADING(c a t) USE_NL(a) USE_HASH(t) */ controls join order and "
                    + "join algorithm selection for the 3-table report query",
        complexity = MigrationComplexity.MEDIUM,
        automationLevel = AutomationLevel.PARTIAL,
        migrationTarget = "Remove Oracle hints; install pg_hint_plan extension for PostgreSQL "
                        + "equivalents: /*+ Leading(c a t) NestLoop(c a) HashJoin(c a t) */"
    )
    @OracleDependency(
        domain = DependencyDomain.EMBEDDED_SQL,
        category = "PARALLEL_HINT",
        description = "/*+ PARALLEL(t,4) */ requests degree-4 parallel scan of the TRANSACTIONS table",
        complexity = MigrationComplexity.MEDIUM,
        automationLevel = AutomationLevel.PARTIAL,
        migrationTarget = "Remove Oracle hint; configure max_parallel_workers_per_gather in PostgreSQL"
    )
    @OracleDependency(
        domain = DependencyDomain.EMBEDDED_SQL,
        category = "DATE_ARITHMETIC",
        description = "SYSDATE - 30 produces a DATE 30 days in the past — Oracle date + integer = date",
        complexity = MigrationComplexity.LOW,
        automationLevel = AutomationLevel.FULL,
        migrationTarget = "Replace SYSDATE - 30 with NOW() - INTERVAL '30 days' in PostgreSQL"
    )
    @OracleDependency(
        domain = DependencyDomain.EMBEDDED_SQL,
        category = "NVL_FUNCTION",
        description = "NVL(SUM(t.amount), 0) coalesces NULL totals from customers with no transactions",
        complexity = MigrationComplexity.LOW,
        automationLevel = AutomationLevel.FULL,
        migrationTarget = "Replace NVL(col, 0) with COALESCE(col, 0) in PostgreSQL"
    )
    public List<Map<String, Object>> getLeadingHintReport(ReportParameterDTO params) {
        // ORACLE-DEP [EMBEDDED_SQL:LEADING_HINT] LEADING(c a t) forces join order
        // ORACLE-DEP [EMBEDDED_SQL:PARALLEL_HINT] PARALLEL(t,4) degree-4 parallel scan
        // ORACLE-DEP [EMBEDDED_SQL:DATE_ARITHMETIC] SYSDATE - 30 for 30-day rolling window
        // ORACLE-DEP [EMBEDDED_SQL:NVL_FUNCTION] NVL(SUM(t.amount), 0) null-safe aggregation
        // ORACLE-DEP [EMBEDDED_SQL:SYSDATE] SYSDATE as current date/time reference
        final String sql =
            "SELECT /*+ LEADING(c a t) USE_NL(a) USE_HASH(t) PARALLEL(t,4) */ " // ORACLE-DEP [EMBEDDED_SQL:LEADING_HINT] ORACLE-DEP [EMBEDDED_SQL:PARALLEL_HINT]
            + "  c.customer_id, "
            + "  c.last_name, "
            + "  c.status, "
            + "  COUNT(DISTINCT a.account_id)  AS account_count, "
            + "  COUNT(t.transaction_id)        AS tx_count, "
            + "  NVL(SUM(t.amount), 0)          AS total_amount "                // ORACLE-DEP [EMBEDDED_SQL:NVL_FUNCTION]
            + "FROM   MERIDIAN.CUSTOMERS     c "
            + "JOIN   MERIDIAN.ACCOUNTS      a ON c.customer_id  = a.customer_id "
            + "JOIN   MERIDIAN.TRANSACTIONS  t ON a.account_id   = t.account_id "
            + "WHERE  t.transaction_date >= SYSDATE - 30 "                       // ORACLE-DEP [EMBEDDED_SQL:DATE_ARITHMETIC] ORACLE-DEP [EMBEDDED_SQL:SYSDATE]
            + "GROUP  BY c.customer_id, c.last_name, c.status "
            + "ORDER  BY total_amount DESC";

        List<Map<String, Object>> results = new ArrayList<>();

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> row = new HashMap<>();
                    row.put("customerId", rs.getLong("customer_id"));
                    row.put("lastName", rs.getString("last_name"));
                    row.put("status", rs.getString("status"));
                    row.put("accountCount", rs.getInt("account_count"));
                    row.put("txCount", rs.getInt("tx_count"));
                    row.put("totalAmount", rs.getBigDecimal("total_amount"));
                    results.add(row);
                }
            }

            log.info("getLeadingHintReport: {} customers returned in 30-day rolling window report", results.size());
            return results;

        } catch (SQLException e) {
            // ORACLE-DEP [EMBEDDED_SQL:ORA_ERROR_CODES] ORA-12801 error signalled in parallel query server P001
            log.error("getLeadingHintReport failed ORA-{}: {}", e.getErrorCode(), e.getMessage()); // ORACLE-DEP [EMBEDDED_SQL:ORA_ERROR_CODES]
            throw new RuntimeException("Leading-hint report query failed: ORA-" + e.getErrorCode(), e);
        }
    }
}
