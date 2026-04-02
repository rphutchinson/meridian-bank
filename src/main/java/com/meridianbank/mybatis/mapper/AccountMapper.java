package com.meridianbank.mybatis.mapper;

import com.meridianbank.annotation.AutomationLevel;
import com.meridianbank.annotation.DependencyDomain;
import com.meridianbank.annotation.MigrationComplexity;
import com.meridianbank.annotation.OracleDependency;
import com.meridianbank.dto.AccountDTO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * MyBatis mapper interface for Oracle-specific account operations.
 * SQL definitions are in AccountMapper.xml.
 *
 * <p>Migration: Replace Oracle-specific SQL in the XML mapper with
 * PostgreSQL-compatible equivalents.
 */
@Mapper
@OracleDependency(
    domain          = DependencyDomain.ORM_DATA_ACCESS,
    category        = "MYBATIS_ORACLE_MAPPER",
    description     = "MyBatis mapper using Oracle-specific SQL: NVL, DECODE, ROWNUM, LISTAGG, MERGE",
    complexity      = MigrationComplexity.MEDIUM,
    automationLevel = AutomationLevel.PARTIAL,
    migrationTarget = "Rewrite XML SQL using PostgreSQL syntax: COALESCE, CASE, LIMIT/OFFSET, STRING_AGG, ON CONFLICT"
)
public interface AccountMapper {

    // ORACLE-DEP [ORM_DATA_ACCESS:MYBATIS_ORACLE_MAPPER] Insert with SEQUENCE NEXTVAL selectKey
    void insertAccount(AccountDTO account);

    // ORACLE-DEP [ORM_DATA_ACCESS:MYBATIS_ORACLE_MAPPER] Query with NVL and DECODE ORDER BY
    List<AccountDTO> findByCustomerId(@Param("customerId") Long customerId);

    // ORACLE-DEP [ORM_DATA_ACCESS:MYBATIS_ORACLE_MAPPER] ROWNUM pagination query
    List<AccountDTO> findByStatusPaginated(
            @Param("status") String status,
            @Param("startRow") int startRow,
            @Param("endRow") int endRow);

    // ORACLE-DEP [ORM_DATA_ACCESS:MYBATIS_ORACLE_MAPPER] DECODE status label mapping
    List<Map<String, Object>> findAllWithStatusLabel();

    // ORACLE-DEP [ORM_DATA_ACCESS:MYBATIS_ORACLE_MAPPER] SEQUENCE CURRVAL FROM DUAL
    Long getCurrentAccountSeqValue();

    // ORACLE-DEP [ORM_DATA_ACCESS:MYBATIS_ORACLE_MAPPER] MERGE INTO upsert
    void upsertAccountBalance(
            @Param("accountId") Long accountId,
            @Param("balance") BigDecimal balance);

    // ORACLE-DEP [ORM_DATA_ACCESS:MYBATIS_ORACLE_MAPPER] TRUNC/TO_CHAR date grouping
    List<Map<String, Object>> findTransactionSummaryByDay(
            @Param("accountId") Long accountId,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate);

    // ORACLE-DEP [ORM_DATA_ACCESS:MYBATIS_ORACLE_MAPPER] LISTAGG account number aggregation
    String getCustomerAccountSummary(@Param("customerId") Long customerId);

    // ORACLE-DEP [ORM_DATA_ACCESS:MYBATIS_ORACLE_MAPPER] SYSTIMESTAMP/SYSDATE in update
    int updateStatus(
            @Param("accountId") Long accountId,
            @Param("status") String status);
}
