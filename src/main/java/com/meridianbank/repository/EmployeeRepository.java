package com.meridianbank.repository;

import com.meridianbank.annotation.*;
import com.meridianbank.entity.EmployeeEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Spring Data JPA repository for EmployeeEntity.
 * Demonstrates Oracle hierarchical query constructs: CONNECT BY PRIOR, START WITH,
 * LEVEL pseudocolumn, SYS_CONNECT_BY_PATH, CONNECT_BY_ROOT, CONNECT_BY_ISLEAF,
 * and ORDER SIBLINGS BY. These have no direct equivalent in non-Oracle databases
 * and require recursive CTEs in PostgreSQL.
 */
@Repository
public interface EmployeeRepository extends JpaRepository<EmployeeEntity, Long> {

    // Standard JPQL — no Oracle dependency
    Optional<EmployeeEntity> findByEmail(String email);
    List<EmployeeEntity> findByDepartmentId(Long departmentId);
    List<EmployeeEntity> findByManagerId(Long managerId);
    List<EmployeeEntity> findByStatus(String status);

    // ORACLE-DEP [EMBEDDED_SQL:CONNECT_BY] Full org hierarchy with CONNECT BY PRIOR
    @OracleDependency(
        domain = DependencyDomain.EMBEDDED_SQL,
        category = "CONNECT_BY",
        description = "CONNECT BY PRIOR e.manager_id = e.employee_id traverses the employee hierarchy top-down",
        complexity = MigrationComplexity.HIGH,
        automationLevel = AutomationLevel.PARTIAL,
        migrationTarget = "Replace with recursive CTE: WITH RECURSIVE emp_hier AS (SELECT ... UNION ALL SELECT ... FROM ... JOIN emp_hier) in PostgreSQL"
    )
    @OracleDependency(
        domain = DependencyDomain.EMBEDDED_SQL,
        category = "LEVEL_PSEUDOCOLUMN",
        description = "LEVEL pseudocolumn gives depth in the hierarchy (1=root, 2=direct reports, etc.)",
        complexity = MigrationComplexity.HIGH,
        automationLevel = AutomationLevel.PARTIAL,
        migrationTarget = "Replace LEVEL with depth column in recursive CTE in PostgreSQL"
    )
    @Query(value =
        "SELECT e.employee_id, e.first_name, e.last_name, e.job_title, " +
        "  e.manager_id, e.department_id, e.salary, " +
        "  LEVEL AS hierarchy_depth, " +
        "  LPAD(' ', (LEVEL - 1) * 4) || e.first_name || ' ' || e.last_name AS indented_name " +
        "FROM MERIDIAN.EMPLOYEES e " +
        "START WITH e.manager_id IS NULL " +
        "CONNECT BY PRIOR e.employee_id = e.manager_id " +
        "ORDER SIBLINGS BY e.last_name, e.first_name",
        nativeQuery = true)
    List<Object[]> findFullHierarchy();

    // ORACLE-DEP [EMBEDDED_SQL:CONNECT_BY] Subtree below a specific manager
    @OracleDependency(
        domain = DependencyDomain.EMBEDDED_SQL,
        category = "CONNECT_BY",
        description = "START WITH e.employee_id = :managerId roots the hierarchy at a specific manager",
        complexity = MigrationComplexity.HIGH,
        automationLevel = AutomationLevel.PARTIAL,
        migrationTarget = "Replace with recursive CTE starting at the specific employee_id in PostgreSQL"
    )
    @OracleDependency(
        domain = DependencyDomain.EMBEDDED_SQL,
        category = "SYS_CONNECT_BY_PATH",
        description = "SYS_CONNECT_BY_PATH(last_name, '/') builds full path from root to each node",
        complexity = MigrationComplexity.HIGH,
        automationLevel = AutomationLevel.PARTIAL,
        migrationTarget = "Replace with STRING_AGG path accumulation in recursive CTE in PostgreSQL"
    )
    @Query(value =
        "SELECT e.employee_id, e.first_name, e.last_name, e.job_title, " +
        "  LEVEL AS hierarchy_depth, " +
        "  SYS_CONNECT_BY_PATH(e.last_name, ' / ') AS org_path " +
        "FROM MERIDIAN.EMPLOYEES e " +
        "START WITH e.employee_id = :managerId " +
        "CONNECT BY PRIOR e.employee_id = e.manager_id " +
        "ORDER SIBLINGS BY e.last_name",
        nativeQuery = true)
    List<Object[]> findHierarchyBelowManager(@Param("managerId") Long managerId);

    // ORACLE-DEP [EMBEDDED_SQL:CONNECT_BY] Reporting chain from employee to CEO
    @OracleDependency(
        domain = DependencyDomain.EMBEDDED_SQL,
        category = "CONNECT_BY",
        description = "CONNECT BY PRIOR e.manager_id = e.employee_id traverses upward from employee to root",
        complexity = MigrationComplexity.HIGH,
        automationLevel = AutomationLevel.PARTIAL,
        migrationTarget = "Replace with upward recursive CTE joining on manager_id = employee_id in PostgreSQL"
    )
    @OracleDependency(
        domain = DependencyDomain.EMBEDDED_SQL,
        category = "CONNECT_BY_ROOT",
        description = "CONNECT_BY_ROOT e.last_name returns the root (CEO) name for any node in the tree",
        complexity = MigrationComplexity.HIGH,
        automationLevel = AutomationLevel.PARTIAL,
        migrationTarget = "Replace with first_value() OVER or CTE anchor value in PostgreSQL"
    )
    @Query(value =
        "SELECT e.employee_id, e.first_name, e.last_name, e.job_title, " +
        "  LEVEL AS levels_from_ceo, " +
        "  CONNECT_BY_ROOT e.last_name AS top_executive, " +
        "  SYS_CONNECT_BY_PATH(e.job_title, ' -> ') AS title_chain " +
        "FROM MERIDIAN.EMPLOYEES e " +
        "START WITH e.employee_id = :employeeId " +
        "CONNECT BY PRIOR e.manager_id = e.employee_id " +
        "ORDER BY LEVEL",
        nativeQuery = true)
    List<Object[]> findReportingChain(@Param("employeeId") Long employeeId);

    // ORACLE-DEP [EMBEDDED_SQL:CONNECT_BY_ISLEAF] CONNECT_BY_ISLEAF for leaf detection
    @OracleDependency(
        domain = DependencyDomain.EMBEDDED_SQL,
        category = "CONNECT_BY_ISLEAF",
        description = "CONNECT_BY_ISLEAF = 1 identifies leaf employees (individual contributors with no direct reports)",
        complexity = MigrationComplexity.HIGH,
        automationLevel = AutomationLevel.PARTIAL,
        migrationTarget = "Replace with NOT EXISTS (SELECT 1 FROM employees sub WHERE sub.manager_id = e.employee_id) in PostgreSQL"
    )
    @Query(value =
        "SELECT e.employee_id, e.first_name, e.last_name, e.job_title, " +
        "  LEVEL AS depth, " +
        "  CONNECT_BY_ISLEAF AS is_individual_contributor " +
        "FROM MERIDIAN.EMPLOYEES e " +
        "START WITH e.manager_id IS NULL " +
        "CONNECT BY PRIOR e.employee_id = e.manager_id " +
        "WHERE CONNECT_BY_ISLEAF = 1 " +
        "ORDER SIBLINGS BY e.last_name",
        nativeQuery = true)
    List<Object[]> findIndividualContributors();

    // ORACLE-DEP [EMBEDDED_SQL:CONNECT_BY] Department headcount per level
    @OracleDependency(
        domain = DependencyDomain.EMBEDDED_SQL,
        category = "CONNECT_BY",
        description = "CONNECT BY PRIOR with LEVEL to count employees at each org level per department",
        complexity = MigrationComplexity.HIGH,
        automationLevel = AutomationLevel.PARTIAL,
        migrationTarget = "Replace with recursive CTE + GROUP BY depth in PostgreSQL"
    )
    @Query(value =
        "SELECT LEVEL AS org_level, e.department_id, COUNT(*) AS headcount " +
        "FROM MERIDIAN.EMPLOYEES e " +
        "START WITH e.manager_id IS NULL " +
        "CONNECT BY PRIOR e.employee_id = e.manager_id " +
        "GROUP BY LEVEL, e.department_id " +
        "ORDER BY LEVEL, e.department_id",
        nativeQuery = true)
    List<Object[]> findHeadcountByOrgLevel();

    // ORACLE-DEP [EMBEDDED_SQL:CONNECT_BY] Direct reports count using hierarchy
    @OracleDependency(
        domain = DependencyDomain.EMBEDDED_SQL,
        category = "CONNECT_BY",
        description = "PRIOR keyword with LEVEL=2 to find direct reports of a specific manager",
        complexity = MigrationComplexity.HIGH,
        automationLevel = AutomationLevel.PARTIAL,
        migrationTarget = "Replace with SELECT ... WHERE manager_id = :managerId in PostgreSQL (direct reports only)"
    )
    @Query(value =
        "SELECT e.employee_id, e.first_name, e.last_name, e.job_title, e.salary, " +
        "  LEVEL AS org_depth " +
        "FROM MERIDIAN.EMPLOYEES e " +
        "START WITH e.employee_id = :managerId " +
        "CONNECT BY PRIOR e.employee_id = e.manager_id " +
        "AND LEVEL <= 2 " +
        "ORDER SIBLINGS BY e.last_name",
        nativeQuery = true)
    List<Object[]> findDirectReports(@Param("managerId") Long managerId);
}
