package com.meridianbank.service;

import com.meridianbank.dao.ReportOracleDAO;
import com.meridianbank.dto.AuditTrailDTO;
import com.meridianbank.dto.EmployeeHierarchyDTO;
import com.meridianbank.dto.FinancialReportDTO;
import com.meridianbank.dto.ReportParameterDTO;
import com.meridianbank.mybatis.mapper.ReportMapper;
import com.meridianbank.repository.AuditLogRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link ReportService} using Mockito mocks.
 *
 * <p>All tests run without a database connection. They validate that the service
 * correctly orchestrates calls to {@link ReportOracleDAO} and maps the Oracle-specific
 * result structures to service-layer DTOs.
 *
 * <p><strong>Oracle analytic SQL functions mocked via ReportOracleDAO:</strong>
 * <ul>
 *   <li>ORACLE-DEP [EMBEDDED_SQL:RATIO_TO_REPORT]  portfolio allocation percentages</li>
 *   <li>ORACLE-DEP [EMBEDDED_SQL:WIDTH_BUCKET]     transaction amount distribution</li>
 *   <li>ORACLE-DEP [EMBEDDED_SQL:NTILE_CUME_DIST]  customer ranking quartiles</li>
 *   <li>ORACLE-DEP [EMBEDDED_SQL:PIVOT_CLAUSE]     monthly transaction pivot</li>
 *   <li>ORACLE-DEP [EMBEDDED_SQL:LISTAGG]          account number aggregation</li>
 *   <li>ORACLE-DEP [EMBEDDED_SQL:CONNECT_BY_PRIOR] employee hierarchy traversal</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ReportService unit tests (Mockito — no DB required)")
class ReportServiceTest {

    @Mock
    private ReportOracleDAO reportOracleDAO;

    @Mock
    private ReportMapper reportMapper;

    @Mock
    private AuditLogRepository auditLogRepository;

    @InjectMocks
    private ReportService reportService;

    // ------------------------------------------------------------------
    // getPortfolioAllocation
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("getPortfolioAllocation() tests")
    class PortfolioAllocationTests {

        /**
         * Verifies that getPortfolioAllocation delegates to ReportOracleDAO.getPortfolioAllocation()
         * and returns a non-empty list of FinancialReportDTOs containing allocation ratios
         * produced by Oracle RATIO_TO_REPORT().
         */
        @Test
        @DisplayName("getPortfolioAllocation success — delegates to RATIO_TO_REPORT DAO query")
        void testGetPortfolioAllocation_success() {
            FinancialReportDTO dto = new FinancialReportDTO();
            dto.setReportType("PORTFOLIO");
            dto.setPortfolioAllocation(Map.of(
                    "CHECKING",     0.40,
                    "SAVINGS",      0.35,
                    "MONEY_MARKET", 0.25));
            when(reportOracleDAO.getPortfolioAllocation(1L)).thenReturn(List.of(dto));

            List<FinancialReportDTO> results = reportService.getPortfolioAllocation(1L);

            assertNotNull(results, "Portfolio allocation result must not be null");
            assertFalse(results.isEmpty(), "Portfolio allocation must return at least one report");
            assertNotNull(results.get(0).getPortfolioAllocation(),
                    "FinancialReportDTO must contain RATIO_TO_REPORT allocation data");
            // All ratios should sum to approximately 1.0
            double sum = results.get(0).getPortfolioAllocation()
                    .values().stream().mapToDouble(Double::doubleValue).sum();
            assertEquals(1.0, sum, 0.001,
                    "RATIO_TO_REPORT portfolio allocation ratios must sum to 1.0");
            verify(reportOracleDAO, times(1)).getPortfolioAllocation(1L);
        }

        /**
         * Verifies that an empty list is returned when no accounts exist for the customer.
         */
        @Test
        @DisplayName("getPortfolioAllocation returns empty list for customer with no accounts")
        void testGetPortfolioAllocation_noAccounts() {
            when(reportOracleDAO.getPortfolioAllocation(999L)).thenReturn(List.of());

            List<FinancialReportDTO> results = reportService.getPortfolioAllocation(999L);

            assertNotNull(results);
            assertTrue(results.isEmpty());
        }
    }

    // ------------------------------------------------------------------
    // getTransactionDistribution
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("getTransactionDistribution() tests")
    class TransactionDistributionTests {

        /**
         * Verifies that getTransactionDistribution delegates to the WIDTH_BUCKET DAO query
         * and maps the result into service-consumable map entries.
         */
        @Test
        @DisplayName("getTransactionDistribution success — delegates to WIDTH_BUCKET DAO query")
        void testGetTransactionDistribution_success() {
            List<Map<String, Object>> daoResult = buildDistributionResult();
            when(reportOracleDAO.getTransactionDistribution(1L)).thenReturn(daoResult);

            List<Map<String, Object>> results = reportService.getTransactionDistribution(1L);

            assertNotNull(results, "Transaction distribution result must not be null");
            assertFalse(results.isEmpty(), "Distribution must return at least one bucket row");
            // Verify bucket values are present
            results.forEach(row -> assertNotNull(row.get("BUCKET"),
                    "Each WIDTH_BUCKET result row must have a BUCKET column"));
            verify(reportOracleDAO, times(1)).getTransactionDistribution(1L);
        }
    }

    // ------------------------------------------------------------------
    // getCustomerRanking
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("getCustomerRanking() tests")
    class CustomerRankingTests {

        /**
         * Verifies that getCustomerRanking delegates to the NTILE/CUME_DIST DAO query
         * and returns ranking data with quartile and cumulative distribution values.
         */
        @Test
        @DisplayName("getCustomerRanking success — delegates to NTILE/CUME_DIST DAO query")
        void testGetCustomerRanking_success() {
            List<Map<String, Object>> daoResult = buildRankingResult();
            when(reportOracleDAO.getCustomerRanking("total_volume"))
                    .thenReturn(daoResult);

            List<Map<String, Object>> results =
                    reportService.getCustomerRanking("total_volume");

            assertNotNull(results, "Customer ranking result must not be null");
            assertFalse(results.isEmpty(), "Ranking must return at least one row");

            // Verify NTILE(4) quartile is present and within valid range
            results.forEach(row -> {
                Object quartile = row.get("QUARTILE");
                assertNotNull(quartile, "NTILE(4) quartile column must be present");
                if (quartile instanceof Number) {
                    int q = ((Number) quartile).intValue();
                    assertTrue(q >= 1 && q <= 4, "NTILE(4) must produce values 1–4");
                }
            });
            verify(reportOracleDAO, times(1)).getCustomerRanking("total_volume");
        }

        /**
         * Verifies that CUME_DIST values are present in the ranking result.
         */
        @Test
        @DisplayName("getCustomerRanking includes CUME_DIST values between 0 and 1")
        void testGetCustomerRanking_includescumeDist() {
            List<Map<String, Object>> daoResult = buildRankingResult();
            when(reportOracleDAO.getCustomerRanking(anyString())).thenReturn(daoResult);

            List<Map<String, Object>> results = reportService.getCustomerRanking("balance");

            results.forEach(row -> {
                Object cumDist = row.get("CUM_DIST");
                if (cumDist instanceof Number) {
                    double cd = ((Number) cumDist).doubleValue();
                    assertTrue(cd > 0.0 && cd <= 1.0,
                            "CUME_DIST values must be in (0.0, 1.0]");
                }
            });
        }
    }

    // ------------------------------------------------------------------
    // getMonthlyTransactionPivot
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("getMonthlyTransactionPivot() tests")
    class MonthlyPivotTests {

        /**
         * Verifies that getMonthlyTransactionPivot delegates to the Oracle PIVOT DAO query
         * and returns data with DEBIT_TOTAL and CREDIT_TOTAL pivot columns.
         */
        @Test
        @DisplayName("getMonthlyTransactionPivot success — delegates to Oracle PIVOT DAO query")
        void testGetMonthlyPivot_success() {
            List<Map<String, Object>> daoResult = buildPivotResult();
            when(reportOracleDAO.getMonthlyTransactionPivot(2026)).thenReturn(daoResult);

            List<Map<String, Object>> results = reportService.getMonthlyTransactionPivot(2026);

            assertNotNull(results, "Monthly pivot result must not be null");
            assertFalse(results.isEmpty(), "Pivot must return at least one month row");
            // Verify pivot columns exist
            results.forEach(row -> assertTrue(
                    row.containsKey("DEBIT_TOTAL") || row.containsKey("CREDIT_TOTAL"),
                    "Oracle PIVOT result must contain DEBIT_TOTAL or CREDIT_TOTAL"));
            verify(reportOracleDAO, times(1)).getMonthlyTransactionPivot(2026);
        }

        /**
         * Verifies that an empty pivot result is returned when no transactions exist for the year.
         */
        @Test
        @DisplayName("getMonthlyTransactionPivot returns empty list for year with no data")
        void testGetMonthlyPivot_emptyYear() {
            // Use a valid future year that has no transaction data
            when(reportOracleDAO.getMonthlyTransactionPivot(2099)).thenReturn(List.of());

            List<Map<String, Object>> results = reportService.getMonthlyTransactionPivot(2099);

            assertNotNull(results);
            assertTrue(results.isEmpty());
        }
    }

    // ------------------------------------------------------------------
    // getAccountSummaryWithListagg
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("getAccountSummaryWithListagg() tests")
    class ListaggTests {

        /**
         * Verifies that getAccountSummaryWithListagg delegates to the Oracle LISTAGG DAO query
         * and returns a map containing the comma-separated account list.
         */
        @Test
        @DisplayName("getAccountSummaryWithListagg success — delegates to LISTAGG DAO query")
        void testGetAccountSummaryWithListagg_success() {
            Map<String, Object> listaggMap = new HashMap<>();
            listaggMap.put("CUSTOMER_ID", 1L);
            listaggMap.put("ACCOUNT_LIST", "CHK-001, SAV-001, SAV-002");
            listaggMap.put("ACCOUNT_COUNT", 3L);
            when(reportOracleDAO.getAccountSummaryWithListagg(1L))
                    .thenReturn(listaggMap);

            Map<String, Object> result = reportService.getAccountSummaryWithListagg(1L);

            assertNotNull(result, "LISTAGG account summary must not be null");
            assertNotNull(result.get("ACCOUNT_LIST"),
                    "LISTAGG result must contain ACCOUNT_LIST key");
            String accountList = result.get("ACCOUNT_LIST").toString();
            assertTrue(accountList.contains(","),
                    "LISTAGG result must be a comma-separated list");
            verify(reportOracleDAO, times(1)).getAccountSummaryWithListagg(1L);
        }
    }

    // ------------------------------------------------------------------
    // getEmployeeOrgHierarchy
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("getEmployeeOrgHierarchy() tests")
    class EmployeeHierarchyTests {

        /**
         * Verifies that getEmployeeOrgHierarchy delegates to the CONNECT BY PRIOR DAO query
         * and returns a properly structured hierarchy with LEVEL and path data.
         */
        @Test
        @DisplayName("getEmployeeOrgHierarchy success — delegates to CONNECT BY PRIOR DAO query")
        void testGetEmployeeHierarchy_success() {
            List<EmployeeHierarchyDTO> daoResult = buildHierarchyResult();
            when(reportOracleDAO.getEmployeeOrgHierarchy(1L)).thenReturn(daoResult);

            List<EmployeeHierarchyDTO> results = reportService.getEmployeeOrgHierarchy(1L);

            assertNotNull(results, "CONNECT BY hierarchy result must not be null");
            assertFalse(results.isEmpty(), "Hierarchy must contain at least the root employee");

            // The root should be at level 1 (Oracle LEVEL pseudocolumn)
            EmployeeHierarchyDTO root = results.stream()
                    .filter(e -> e.getLevel() != null && e.getLevel() == 1)
                    .findFirst()
                    .orElse(null);
            assertNotNull(root, "Oracle LEVEL = 1 root employee must be present in hierarchy");
            assertNotNull(root.getHierarchyPath(),
                    "SYS_CONNECT_BY_PATH must populate hierarchyPath on root employee");

            verify(reportOracleDAO, times(1)).getEmployeeOrgHierarchy(1L);
        }

        /**
         * Verifies that Oracle CONNECT_BY_ISLEAF indicator is correctly propagated
         * from the DAO result to the service response.
         */
        @Test
        @DisplayName("getEmployeeOrgHierarchy leaf employees have isLeaf = true (CONNECT_BY_ISLEAF)")
        void testGetEmployeeHierarchy_leafNodesMarked() {
            List<EmployeeHierarchyDTO> daoResult = buildHierarchyResult();
            when(reportOracleDAO.getEmployeeOrgHierarchy(anyLong())).thenReturn(daoResult);

            List<EmployeeHierarchyDTO> results = reportService.getEmployeeOrgHierarchy(1L);

            long leafCount = results.stream()
                    .filter(e -> Boolean.TRUE.equals(e.isIsLeaf()))
                    .count();
            assertTrue(leafCount > 0,
                    "At least one employee must be a leaf node (CONNECT_BY_ISLEAF = 1)");
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private List<Map<String, Object>> buildDistributionResult() {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int bucket = 1; bucket <= 5; bucket++) {
            Map<String, Object> row = new HashMap<>();
            row.put("BUCKET", bucket);
            row.put("TXN_COUNT", (long) (bucket * 10));
            row.put("TOTAL_AMOUNT", new BigDecimal(String.format("%d.00", bucket * 1000)));
            rows.add(row);
        }
        return rows;
    }

    private List<Map<String, Object>> buildRankingResult() {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 1; i <= 4; i++) {
            Map<String, Object> row = new HashMap<>();
            row.put("CUSTOMER_ID", (long) i);
            row.put("CUSTOMER_NAME", "Customer " + i);
            row.put("TOTAL_VOLUME", new BigDecimal(String.format("%d.00", i * 5000)));
            row.put("QUARTILE", i);          // NTILE(4) value
            row.put("CUM_DIST", i * 0.25);   // CUME_DIST() value
            row.put("TIER", "TIER_" + i);
            rows.add(row);
        }
        return rows;
    }

    private List<Map<String, Object>> buildPivotResult() {
        List<Map<String, Object>> rows = new ArrayList<>();
        String[] months = {"2026-01", "2026-02", "2026-03"};
        for (String month : months) {
            Map<String, Object> row = new HashMap<>();
            row.put("REPORT_MONTH", month);
            row.put("DEBIT_TOTAL",    new BigDecimal("5000.00"));
            row.put("CREDIT_TOTAL",   new BigDecimal("7500.00"));
            row.put("TRANSFER_TOTAL", new BigDecimal("2500.00"));
            rows.add(row);
        }
        return rows;
    }

    private List<EmployeeHierarchyDTO> buildHierarchyResult() {
        EmployeeHierarchyDTO ceo = new EmployeeHierarchyDTO();
        ceo.setEmployeeId(1L);
        ceo.setFirstName("Alice");
        ceo.setLastName("CEO");
        ceo.setFullName("Alice CEO");
        ceo.setJobTitle("Chief Executive Officer");
        ceo.setLevel(1);
        ceo.setHierarchyPath("/Alice CEO");
        ceo.setRootEmployee("Alice CEO");
        ceo.setIsLeaf(false);

        EmployeeHierarchyDTO vp = new EmployeeHierarchyDTO();
        vp.setEmployeeId(2L);
        vp.setFirstName("Bob");
        vp.setLastName("VP");
        vp.setFullName("Bob VP");
        vp.setJobTitle("VP Operations");
        vp.setManagerId(1L);
        vp.setLevel(2);
        vp.setHierarchyPath("/Alice CEO/Bob VP");
        vp.setRootEmployee("Alice CEO");
        vp.setIsLeaf(false);

        EmployeeHierarchyDTO mgr = new EmployeeHierarchyDTO();
        mgr.setEmployeeId(3L);
        mgr.setFirstName("Carol");
        mgr.setLastName("Manager");
        mgr.setFullName("Carol Manager");
        mgr.setJobTitle("Branch Manager");
        mgr.setManagerId(2L);
        mgr.setLevel(3);
        mgr.setHierarchyPath("/Alice CEO/Bob VP/Carol Manager");
        mgr.setRootEmployee("Alice CEO");
        mgr.setIsLeaf(true);  // CONNECT_BY_ISLEAF = 1 (no direct reports)

        return Arrays.asList(ceo, vp, mgr);
    }
}
