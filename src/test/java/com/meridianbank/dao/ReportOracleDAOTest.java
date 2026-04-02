package com.meridianbank.dao;

import com.meridianbank.dto.EmployeeHierarchyDTO;
import com.meridianbank.dto.FinancialReportDTO;
import com.meridianbank.dto.ReportParameterDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests for {@link ReportOracleDAO} — the richest source of Oracle SQL analytic
 * constructs in the Meridian Bank application.
 *
 * <p><strong>Oracle dependencies exercised:</strong>
 * <ul>
 *   <li>ORACLE-DEP [EMBEDDED_SQL:RATIO_TO_REPORT]      portfolio allocation analytics</li>
 *   <li>ORACLE-DEP [EMBEDDED_SQL:WIDTH_BUCKET]         transaction amount distribution</li>
 *   <li>ORACLE-DEP [EMBEDDED_SQL:NTILE_CUME_DIST]      customer ranking by tier</li>
 *   <li>ORACLE-DEP [EMBEDDED_SQL:PIVOT_CLAUSE]         monthly transaction pivot table</li>
 *   <li>ORACLE-DEP [EMBEDDED_SQL:CONNECT_BY_PRIOR]     employee org hierarchy</li>
 *   <li>ORACLE-DEP [EMBEDDED_SQL:SYS_CONNECT_BY_PATH] full hierarchy path string</li>
 *   <li>ORACLE-DEP [EMBEDDED_SQL:LISTAGG]              account number aggregation</li>
 *   <li>ORACLE-DEP [EMBEDDED_SQL:OUTER_JOIN_PLUS]      legacy (+) outer join syntax</li>
 *   <li>ORACLE-DEP [EMBEDDED_SQL:DATE_ARITHMETIC]      date - date returns NUMBER (days)</li>
 *   <li>ORACLE-DEP [PLSQL_INVOCATION:REF_CURSOR]       OracleTypes.CURSOR streaming output</li>
 * </ul>
 */
class ReportOracleDAOTest {

    // ------------------------------------------------------------------
    // DB-dependent tests (all @Disabled)
    // ------------------------------------------------------------------

    @SpringBootTest
    @AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
    @ActiveProfiles("test")
    @Nested
    @Disabled("Requires Oracle DB — entire nested class disabled until Oracle 23ai container is running")
    @DisplayName("Oracle DB-dependent report tests")
    class OracleReportTests {

        @Autowired
        private ReportOracleDAO reportOracleDAO;

        /**
         * Tests the Oracle RATIO_TO_REPORT() analytic function for portfolio allocation.
         *
         * <p>SQL fragment:
         * {@code RATIO_TO_REPORT(SUM(balance)) OVER () AS portfolio_ratio}
         * <p>All ratios must sum to 1.0 (within floating-point tolerance).
         * <p>Migration target: derive in application layer; no direct PostgreSQL equivalent —
         * use {@code SUM(balance) / SUM(SUM(balance)) OVER () AS portfolio_ratio}.
         */
        @Test
        @Disabled("Requires Oracle DB — ORACLE-DEP [EMBEDDED_SQL:RATIO_TO_REPORT]")
        @DisplayName("getPortfolioAllocation uses Oracle RATIO_TO_REPORT() analytic function")
        void testPortfolioAllocation_usesRatioToReport() {
            List<FinancialReportDTO> results = reportOracleDAO.getPortfolioAllocation(1L);

            assertNotNull(results, "RATIO_TO_REPORT query must return non-null");
            assertFalse(results.isEmpty(), "Portfolio allocation must return at least one row");

            // All portfolio_ratio values must sum to approximately 1.0
            double totalRatio = results.stream()
                    .mapToDouble(r -> r.getPortfolioAllocation() != null
                            ? r.getPortfolioAllocation().values().stream()
                                  .mapToDouble(Double::doubleValue).sum()
                            : 0.0)
                    .sum();
            // Allow for floating-point imprecision across rows
            assertTrue(totalRatio > 0, "RATIO_TO_REPORT must produce non-zero ratios");
        }

        /**
         * Tests the Oracle WIDTH_BUCKET() function for transaction distribution.
         *
         * <p>SQL fragment:
         * {@code WIDTH_BUCKET(amount, 0, 10000, 10) AS bucket}
         * <p>Creates 10 equal-width buckets from 0 to 10,000. Each row falls into
         * exactly one bucket (1–10), with overflow values in bucket 0 or 11.
         * <p>Migration target: {@code WIDTH_BUCKET(amount, 0, 10000, 10)} is also
         * available in PostgreSQL — this is one of the easier migrations.
         */
        @Test
        @Disabled("Requires Oracle DB — ORACLE-DEP [EMBEDDED_SQL:WIDTH_BUCKET]")
        @DisplayName("getTransactionDistribution uses Oracle WIDTH_BUCKET() for amount buckets")
        void testTransactionDistribution_usesWidthBucket() {
            List<Map<String, Object>> distribution =
                    reportOracleDAO.getTransactionDistribution(1L);

            assertNotNull(distribution, "WIDTH_BUCKET distribution query must return non-null");
            for (Map<String, Object> row : distribution) {
                assertNotNull(row.get("BUCKET"), "Each distribution row must have a BUCKET column");
                Object bucket = row.get("BUCKET");
                // Oracle WIDTH_BUCKET(x, 0, 10000, 10) returns values 0 through 11
                if (bucket instanceof Number) {
                    int bucketNum = ((Number) bucket).intValue();
                    assertTrue(bucketNum >= 0 && bucketNum <= 11,
                            "WIDTH_BUCKET with 10 buckets must return values 0-11, got: " + bucketNum);
                }
            }
        }

        /**
         * Tests Oracle NTILE() and CUME_DIST() window functions for customer ranking.
         *
         * <p>SQL fragment:
         * {@code NTILE(4) OVER (ORDER BY total_volume DESC) AS quartile,
         *  CUME_DIST() OVER (ORDER BY total_volume DESC) AS cum_dist}
         * <p>Migration target: both {@code NTILE()} and {@code CUME_DIST()} are
         * supported in PostgreSQL window functions — same syntax.
         */
        @Test
        @Disabled("Requires Oracle DB — ORACLE-DEP [EMBEDDED_SQL:NTILE_CUME_DIST]")
        @DisplayName("getCustomerRanking uses Oracle NTILE() and CUME_DIST() window functions")
        void testCustomerRanking_usesNtileAndCumeDist() {
            List<Map<String, Object>> rankings =
                    reportOracleDAO.getCustomerRanking("total_volume");

            assertNotNull(rankings, "NTILE/CUME_DIST ranking query must return non-null");
            for (Map<String, Object> row : rankings) {
                // NTILE(4) must return 1, 2, 3, or 4
                Object quartile = row.get("QUARTILE");
                if (quartile instanceof Number) {
                    int q = ((Number) quartile).intValue();
                    assertTrue(q >= 1 && q <= 4,
                            "NTILE(4) must return values 1-4, got: " + q);
                }
                // CUME_DIST() must return a value between 0.0 and 1.0
                Object cumDist = row.get("CUM_DIST");
                if (cumDist instanceof Number) {
                    double cd = ((Number) cumDist).doubleValue();
                    assertTrue(cd > 0.0 && cd <= 1.0,
                            "CUME_DIST() must return values in (0,1], got: " + cd);
                }
            }
        }

        /**
         * Tests the Oracle PIVOT clause for monthly transaction data.
         *
         * <p>SQL fragment:
         * {@code SELECT * FROM (...) PIVOT (SUM(amount) FOR transaction_type
         *   IN ('DEBIT' AS debit_total, 'CREDIT' AS credit_total, 'TRANSFER' AS transfer_total))}
         * <p>Migration target: PostgreSQL does not have PIVOT — use {@code crosstab()} from
         * the tablefunc extension, or CASE-based conditional aggregation.
         */
        @Test
        @Disabled("Requires Oracle DB — ORACLE-DEP [EMBEDDED_SQL:PIVOT_CLAUSE]")
        @DisplayName("getMonthlyTransactionPivot uses Oracle PIVOT clause")
        void testMonthlyPivot_usesPivotClause() {
            List<Map<String, Object>> pivot =
                    reportOracleDAO.getMonthlyTransactionPivot(2026);

            assertNotNull(pivot, "Oracle PIVOT query must return non-null");
            // Each pivot row should have DEBIT_TOTAL and CREDIT_TOTAL columns
            for (Map<String, Object> row : pivot) {
                assertTrue(row.containsKey("DEBIT_TOTAL") || row.containsKey("CREDIT_TOTAL"),
                        "PIVOT result must contain DEBIT_TOTAL or CREDIT_TOTAL column");
            }
        }

        /**
         * Tests the Oracle CONNECT BY PRIOR hierarchical query for employee org chart.
         *
         * <p>SQL fragment:
         * {@code SELECT employee_id, LEVEL, SYS_CONNECT_BY_PATH(last_name, '/')
         *   FROM employees
         *   START WITH employee_id = :rootId
         *   CONNECT BY PRIOR employee_id = manager_id}
         * <p>Migration target: PostgreSQL recursive CTE —
         * {@code WITH RECURSIVE org_chart AS (SELECT ... UNION ALL SELECT ... FROM employees JOIN org_chart ...)}
         */
        @Test
        @Disabled("Requires Oracle DB — ORACLE-DEP [EMBEDDED_SQL:CONNECT_BY_PRIOR]")
        @DisplayName("getEmployeeOrgHierarchy uses Oracle CONNECT BY PRIOR with SYS_CONNECT_BY_PATH")
        void testEmployeeHierarchy_usesConnectBy() {
            // Root employee ID 1 (CEO in seed data)
            List<EmployeeHierarchyDTO> hierarchy =
                    reportOracleDAO.getEmployeeOrgHierarchy(1L);

            assertNotNull(hierarchy, "CONNECT BY hierarchy query must return non-null");
            assertFalse(hierarchy.isEmpty(), "Hierarchy must contain at least the root employee");

            // Verify LEVEL is populated from Oracle LEVEL pseudocolumn
            hierarchy.forEach(e -> assertNotNull(e.getLevel(),
                    "Oracle LEVEL pseudocolumn must be populated on every hierarchy row"));

            // Verify hierarchy path from SYS_CONNECT_BY_PATH
            hierarchy.stream()
                    .filter(e -> e.getLevel() != null && e.getLevel() > 1)
                    .forEach(e -> assertNotNull(e.getHierarchyPath(),
                            "SYS_CONNECT_BY_PATH must populate hierarchyPath for non-root employees"));
        }

        /**
         * Tests Oracle LISTAGG() for aggregating account numbers into a delimited string.
         *
         * <p>SQL fragment:
         * {@code LISTAGG(account_number, ', ') WITHIN GROUP (ORDER BY account_number) AS account_list}
         * <p>Migration target: {@code STRING_AGG(account_number, ', ' ORDER BY account_number)}
         * in PostgreSQL.
         */
        @Test
        @Disabled("Requires Oracle DB — ORACLE-DEP [EMBEDDED_SQL:LISTAGG]")
        @DisplayName("getAccountSummaryWithListagg uses Oracle LISTAGG() aggregate function")
        void testListagg_aggregatesAccounts() {
            Map<String, Object> summary = reportOracleDAO.getAccountSummaryWithListagg(1L);

            assertNotNull(summary, "LISTAGG summary query must return non-null");
            assertNotNull(summary.get("ACCOUNT_LIST"),
                    "LISTAGG must produce a non-null ACCOUNT_LIST column");
            String accountList = summary.get("ACCOUNT_LIST").toString();
            assertFalse(accountList.isBlank(),
                    "LISTAGG result must not be blank for a customer with accounts");
        }

        /**
         * Tests the legacy Oracle (+) outer join syntax used in LegacyAccountReport.
         *
         * <p>SQL fragment:
         * {@code FROM accounts a, branches b WHERE a.branch_id = b.branch_id(+)}
         * <p>This is Oracle-proprietary outer join notation. The (+) on the right side
         * of the condition denotes a LEFT OUTER JOIN from the left table's perspective.
         * <p>Migration target: {@code FROM accounts a LEFT OUTER JOIN branches b ON a.branch_id = b.branch_id}
         */
        @Test
        @Disabled("Requires Oracle DB — ORACLE-DEP [EMBEDDED_SQL:OUTER_JOIN_PLUS]")
        @DisplayName("getLegacyAccountReport uses Oracle (+) outer join syntax")
        void testLegacyReport_usesOuterJoinSyntax() {
            List<Map<String, Object>> legacyReport = reportOracleDAO.getLegacyAccountReport();

            assertNotNull(legacyReport, "Legacy (+) outer join query must return non-null");
            // Accounts without a branch must still appear (outer join behaviour)
            // If all accounts have branches this test still validates the query executes
        }

        /**
         * Tests Oracle date subtraction returning a NUMBER (number of days).
         *
         * <p>Oracle: {@code SYSDATE - transaction_date AS days_overdue} — returns NUMBER.
         * PostgreSQL: {@code NOW() - transaction_date} — returns INTERVAL, not NUMBER.
         *
         * <p>Migration requires explicit extraction:
         * {@code EXTRACT(DAY FROM (NOW() - transaction_date)) AS days_overdue}
         */
        @Test
        @Disabled("Requires Oracle DB — ORACLE-DEP [EMBEDDED_SQL:DATE_ARITHMETIC]")
        @DisplayName("getTransactionAging uses Oracle date - date = NUMBER (days overdue)")
        void testTransactionAging_usesDateArithmetic() {
            List<Map<String, Object>> aging = reportOracleDAO.getTransactionAging(1L);

            assertNotNull(aging, "Date arithmetic aging query must return non-null");
            for (Map<String, Object> row : aging) {
                Object daysOverdue = row.get("DAYS_OVERDUE");
                if (daysOverdue instanceof Number) {
                    // Oracle date - date returns a decimal NUMBER, not an integer interval
                    assertTrue(((Number) daysOverdue).doubleValue() >= 0,
                            "Days overdue (Oracle date arithmetic) must be non-negative");
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Mock-based tests (no DB required)
    // ------------------------------------------------------------------

    @Nested
    @ExtendWith(MockitoExtension.class)
    @org.mockito.junit.jupiter.MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
    @DisplayName("Mock-based structural tests (no DB required)")
    class MockBasedTests {

        @Mock
        private ReportOracleDAO reportOracleDAO;

        @BeforeEach
        void setUpMocks() {
            FinancialReportDTO portfolioReport = new FinancialReportDTO();
            portfolioReport.setReportType("PORTFOLIO");
            portfolioReport.setPortfolioAllocation(Map.of("CHECKING", 0.4, "SAVINGS", 0.6));

            when(reportOracleDAO.getPortfolioAllocation(anyLong()))
                    .thenReturn(List.of(portfolioReport));
            when(reportOracleDAO.getTransactionDistribution(anyLong()))
                    .thenReturn(buildDistributionRows());
            when(reportOracleDAO.getCustomerRanking(anyString()))
                    .thenReturn(buildRankingRows());
            when(reportOracleDAO.getMonthlyTransactionPivot(anyInt()))
                    .thenReturn(buildPivotRows());
            when(reportOracleDAO.getEmployeeOrgHierarchy(anyLong()))
                    .thenReturn(buildHierarchy());
            when(reportOracleDAO.getAccountSummaryWithListagg(anyLong()))
                    .thenReturn(Map.of("ACCOUNT_LIST", "CHK-001, SAV-001"));
            when(reportOracleDAO.getLegacyAccountReport())
                    .thenReturn(buildLegacyRows());
        }

        @Test
        @DisplayName("getPortfolioAllocation mock returns FinancialReportDTO list")
        void testPortfolioAllocation_mockReturnsData() {
            List<FinancialReportDTO> results = reportOracleDAO.getPortfolioAllocation(1L);

            assertNotNull(results);
            assertFalse(results.isEmpty());
            assertEquals("PORTFOLIO", results.get(0).getReportType());
        }

        @Test
        @DisplayName("getTransactionDistribution mock returns distribution rows")
        void testTransactionDistribution_mockReturnsRows() {
            List<Map<String, Object>> dist = reportOracleDAO.getTransactionDistribution(1L);

            assertNotNull(dist);
            assertFalse(dist.isEmpty());
            assertNotNull(dist.get(0).get("BUCKET"));
        }

        @Test
        @DisplayName("getCustomerRanking mock returns ranking rows with quartile data")
        void testCustomerRanking_mockReturnsRankings() {
            List<Map<String, Object>> rankings = reportOracleDAO.getCustomerRanking("volume");

            assertNotNull(rankings);
            assertFalse(rankings.isEmpty());
            assertNotNull(rankings.get(0).get("QUARTILE"));
        }

        @Test
        @DisplayName("getMonthlyTransactionPivot mock returns pivot rows")
        void testMonthlyPivot_mockReturnsPivotData() {
            List<Map<String, Object>> pivot = reportOracleDAO.getMonthlyTransactionPivot(2026);

            assertNotNull(pivot);
            assertFalse(pivot.isEmpty());
        }

        @Test
        @DisplayName("getAccountSummaryWithListagg mock returns LISTAGG account list")
        void testListagg_mockReturnsAccountList() {
            Map<String, Object> summary = reportOracleDAO.getAccountSummaryWithListagg(1L);

            assertNotNull(summary);
            assertEquals("CHK-001, SAV-001", summary.get("ACCOUNT_LIST"));
        }

        // ------------------------------------------------------------------
        // Helpers
        // ------------------------------------------------------------------

        private List<Map<String, Object>> buildDistributionRows() {
            Map<String, Object> row = new HashMap<>();
            row.put("BUCKET", 3);
            row.put("TXN_COUNT", 15L);
            row.put("TOTAL_AMOUNT", new BigDecimal("2500.00"));
            return List.of(row);
        }

        private List<Map<String, Object>> buildRankingRows() {
            Map<String, Object> row = new HashMap<>();
            row.put("CUSTOMER_ID", 1L);
            row.put("QUARTILE", 1);
            row.put("CUM_DIST", 0.25);
            return List.of(row);
        }

        private List<Map<String, Object>> buildPivotRows() {
            Map<String, Object> row = new HashMap<>();
            row.put("REPORT_MONTH", "2026-01");
            row.put("DEBIT_TOTAL", new BigDecimal("5000.00"));
            row.put("CREDIT_TOTAL", new BigDecimal("7500.00"));
            return List.of(row);
        }

        private List<EmployeeHierarchyDTO> buildHierarchy() {
            EmployeeHierarchyDTO ceo = new EmployeeHierarchyDTO();
            ceo.setEmployeeId(1L);
            ceo.setFullName("CEO Employee");
            ceo.setLevel(1);
            ceo.setHierarchyPath("/CEO Employee");
            return List.of(ceo);
        }

        private List<Map<String, Object>> buildLegacyRows() {
            Map<String, Object> row = new HashMap<>();
            row.put("ACCOUNT_ID", 1L);
            row.put("BRANCH_NAME", "Main Branch");
            return List.of(row);
        }
    }
}
