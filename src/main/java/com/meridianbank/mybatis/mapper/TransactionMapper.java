package com.meridianbank.mybatis.mapper;

import com.meridianbank.annotation.AutomationLevel;
import com.meridianbank.annotation.DependencyDomain;
import com.meridianbank.annotation.MigrationComplexity;
import com.meridianbank.annotation.OracleDependency;
import com.meridianbank.dto.TransactionDTO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * MyBatis mapper interface for Oracle-specific transaction operations.
 * SQL definitions are in TransactionMapper.xml.
 *
 * <p>Migration: Replace Oracle-specific SQL in the XML mapper.
 */
@Mapper
@OracleDependency(
    domain          = DependencyDomain.ORM_DATA_ACCESS,
    category        = "MYBATIS_ORACLE_MAPPER",
    description     = "MyBatis mapper using Oracle TIMESTAMP WITH TIME ZONE, ROWNUM, hints, ADD_MONTHS, RETURNING INTO",
    complexity      = MigrationComplexity.MEDIUM,
    automationLevel = AutomationLevel.PARTIAL,
    migrationTarget = "Rewrite XML SQL using PostgreSQL syntax: TIMESTAMPTZ, LIMIT, remove hints, INTERVAL, RETURNING"
)
public interface TransactionMapper {

    // ORACLE-DEP [ORM_DATA_ACCESS:MYBATIS_ORACLE_MAPPER] Insert with dual-sequence selectKey
    void insertTransaction(TransactionDTO transaction);

    // ORACLE-DEP [ORM_DATA_ACCESS:MYBATIS_ORACLE_MAPPER] INDEX hint query
    List<TransactionDTO> findByAccountId(@Param("accountId") Long accountId);

    // ORACLE-DEP [ORM_DATA_ACCESS:MYBATIS_ORACLE_MAPPER] ROWNUM top-N query
    List<TransactionDTO> findRecentByAccountId(
            @Param("accountId") Long accountId,
            @Param("limit") int limit);

    // ORACLE-DEP [ORM_DATA_ACCESS:MYBATIS_ORACLE_MAPPER] TO_DATE/TRUNC date range query
    List<TransactionDTO> findByDateRange(
            @Param("accountId") Long accountId,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate,
            @Param("transactionType") String transactionType,
            @Param("status") String status);

    // ORACLE-DEP [ORM_DATA_ACCESS:MYBATIS_ORACLE_MAPPER] TO_CHAR/ADD_MONTHS monthly summary
    List<Map<String, Object>> getMonthlySummary(@Param("accountId") Long accountId);

    // ORACLE-DEP [ORM_DATA_ACCESS:MYBATIS_ORACLE_MAPPER] RETURNING INTO inserted ID
    void insertAndReturnId(TransactionDTO transaction);

    // ORACLE-DEP [ORM_DATA_ACCESS:MYBATIS_ORACLE_MAPPER] SYSDATE date arithmetic bulk update
    int bulkCompleteTransactions();
}
