package com.meridianbank.mybatis.mapper;

import com.meridianbank.annotation.AutomationLevel;
import com.meridianbank.annotation.DependencyDomain;
import com.meridianbank.annotation.MigrationComplexity;
import com.meridianbank.annotation.OracleDependency;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * MyBatis mapper interface for Oracle-specific reporting queries.
 * SQL definitions are in ReportMapper.xml.
 *
 * <p>Contains the richest collection of Oracle analytical SQL:
 * RATIO_TO_REPORT, NTILE, CUME_DIST, WIDTH_BUCKET, PIVOT,
 * CONNECT BY, SYS_CONNECT_BY_PATH, LISTAGG, and (+) outer joins.
 *
 * <p>Migration: Rewrite all SQL using PostgreSQL analytical functions.
 * RATIO_TO_REPORT → SUM OVER(); PIVOT → conditional aggregation; (+) → LEFT JOIN.
 */
@Mapper
@OracleDependency(
    domain          = DependencyDomain.ORM_DATA_ACCESS,
    category        = "MYBATIS_ORACLE_MAPPER",
    description     = "MyBatis report mapper — RATIO_TO_REPORT, NTILE, CUME_DIST, WIDTH_BUCKET, PIVOT, CONNECT BY, (+) outer join",
    complexity      = MigrationComplexity.HIGH,
    automationLevel = AutomationLevel.PARTIAL,
    migrationTarget = "Rewrite using PostgreSQL analytic functions; replace PIVOT with CASE WHEN; CONNECT BY with recursive CTE"
)
public interface ReportMapper {

    // ORACLE-DEP [ORM_DATA_ACCESS:MYBATIS_ORACLE_MAPPER] RATIO_TO_REPORT portfolio query
    List<Map<String, Object>> getPortfolioAllocation();

    // ORACLE-DEP [ORM_DATA_ACCESS:MYBATIS_ORACLE_MAPPER] WIDTH_BUCKET distribution histogram
    List<Map<String, Object>> getTransactionDistribution(
            @Param("maxAmount") BigDecimal maxAmount,
            @Param("bucketCount") int bucketCount);

    // ORACLE-DEP [ORM_DATA_ACCESS:MYBATIS_ORACLE_MAPPER] NTILE/CUME_DIST/LISTAGG customer ranking
    List<Map<String, Object>> getCustomerRankings(@Param("ntile") int ntile);

    // ORACLE-DEP [ORM_DATA_ACCESS:MYBATIS_ORACLE_MAPPER] Oracle PIVOT monthly transactions
    List<Map<String, Object>> getMonthlyTransactionPivot();

    // ORACLE-DEP [ORM_DATA_ACCESS:MYBATIS_ORACLE_MAPPER] CONNECT BY org hierarchy
    List<Map<String, Object>> getEmployeeHierarchyReport(@Param("rootEmployeeId") Long rootEmployeeId);

    // ORACLE-DEP [ORM_DATA_ACCESS:MYBATIS_ORACLE_MAPPER] (+) outer join legacy report
    List<Map<String, Object>> getLegacyCustomerAccountReport();

    // ORACLE-DEP [ORM_DATA_ACCESS:MYBATIS_ORACLE_MAPPER] MONTHS_BETWEEN loan aging report
    List<Map<String, Object>> getLoanAgingReport();

    // ORACLE-DEP [ORM_DATA_ACCESS:MYBATIS_ORACLE_MAPPER] SUBSTR/INSTR account number parsing
    List<Map<String, Object>> getAccountNumberComponents(@Param("customerId") Long customerId);
}
