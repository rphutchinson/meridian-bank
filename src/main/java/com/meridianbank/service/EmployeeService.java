package com.meridianbank.service;

import com.meridianbank.annotation.AutomationLevel;
import com.meridianbank.annotation.DependencyDomain;
import com.meridianbank.annotation.MigrationComplexity;
import com.meridianbank.annotation.OracleDependency;
import com.meridianbank.dto.EmployeeHierarchyDTO;
import com.meridianbank.entity.EmployeeEntity;
import com.meridianbank.exception.MeridianBankException;
import com.meridianbank.exception.OracleErrorTranslator;
import com.meridianbank.repository.EmployeeRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * EmployeeService -- business logic for employee org-chart and hierarchy operations.
 *
 * <p>This service is the primary consumer of Oracle hierarchical query features:
 * CONNECT BY PRIOR, START WITH, LEVEL pseudocolumn, SYS_CONNECT_BY_PATH,
 * CONNECT_BY_ROOT, and CONNECT_BY_ISLEAF.  All of these concepts are surfaced
 * through the EmployeeRepository native queries and mapped into structured DTOs
 * for the REST layer and reports.
 *
 * <p>Migration note: every method that delegates to a repository CONNECT BY query
 * will need the underlying SQL rewritten as a PostgreSQL recursive CTE
 * (WITH RECURSIVE) before the service logic itself changes.
 */
@Service
@Transactional(readOnly = true)
public class EmployeeService {

    private static final Logger log = LoggerFactory.getLogger(EmployeeService.class);

    @Autowired
    private EmployeeRepository employeeRepository;

    @Autowired
    private OracleErrorTranslator oracleErrorTranslator;

    // -------------------------------------------------------------------------
    // Org-hierarchy queries (CONNECT BY family)
    // -------------------------------------------------------------------------

    /**
     * Returns the complete organisational hierarchy starting at the top-most
     * executive (manager_id IS NULL) and traversing every level downward.
     *
     * <p>Delegates to a native Oracle query using CONNECT BY PRIOR and the
     * LEVEL pseudocolumn.  The result rows contain an {@code indented_name}
     * computed with LPAD so callers can render a tree without recursion.
     *
     * @return ordered list of every employee with hierarchy metadata
     */
    // ORACLE-DEP [EMBEDDED_SQL:CONNECT_BY] Full top-down org hierarchy via CONNECT BY PRIOR
    // ORACLE-DEP [EMBEDDED_SQL:LEVEL_PSEUDOCOLUMN] LEVEL pseudocolumn maps to hierarchy_depth col
    @OracleDependency(
        domain = DependencyDomain.EMBEDDED_SQL,
        category = "CONNECT_BY",
        description = "Delegates to EmployeeRepository.findFullHierarchy() which uses CONNECT BY PRIOR "
                    + "e.employee_id = e.manager_id with START WITH e.manager_id IS NULL to traverse "
                    + "the complete org chart top-down",
        complexity = MigrationComplexity.HIGH,
        automationLevel = AutomationLevel.PARTIAL,
        migrationTarget = "Replace with recursive CTE: WITH RECURSIVE org AS (SELECT ..., 1 AS depth "
                        + "FROM employees WHERE manager_id IS NULL UNION ALL SELECT e.*, o.depth+1 "
                        + "FROM employees e JOIN org o ON e.manager_id = o.employee_id) SELECT * FROM org"
    )
    @OracleDependency(
        domain = DependencyDomain.EMBEDDED_SQL,
        category = "LEVEL_PSEUDOCOLUMN",
        description = "LEVEL pseudocolumn provides depth integer (1=CEO, 2=VP, ...) used to compute "
                    + "hierarchy_depth and the LPAD-based indented_name for tree display",
        complexity = MigrationComplexity.HIGH,
        automationLevel = AutomationLevel.PARTIAL,
        migrationTarget = "Replace LEVEL with the depth counter column maintained in the recursive CTE anchor "
                        + "and recursive step in PostgreSQL"
    )
    public List<EmployeeHierarchyDTO> getFullOrgHierarchy() {
        log.info("Fetching full org hierarchy via Oracle CONNECT BY PRIOR");
        // ORACLE-DEP [EMBEDDED_SQL:CONNECT_BY] findFullHierarchy uses CONNECT BY PRIOR
        // ORACLE-DEP [EMBEDDED_SQL:LEVEL_PSEUDOCOLUMN] LEVEL drives hierarchy_depth and indented_name
        List<Object[]> rows = employeeRepository.findFullHierarchy();
        return rows.stream()
                   .map(this::mapFullHierarchyRow)
                   .collect(Collectors.toList());
    }

    /**
     * Returns the sub-tree of employees whose root is {@code managerId},
     * including the manager themselves plus every direct and indirect report.
     *
     * <p>The {@code orgPath} field in each DTO is populated from Oracle's
     * SYS_CONNECT_BY_PATH function, e.g. " / Smith / Jones / Doe".
     *
     * @param managerId root of the sub-tree
     * @return list of employees in the sub-tree with path metadata
     */
    // ORACLE-DEP [EMBEDDED_SQL:CONNECT_BY] Sub-tree query with START WITH employee_id = :managerId
    // ORACLE-DEP [EMBEDDED_SQL:SYS_CONNECT_BY_PATH] SYS_CONNECT_BY_PATH builds slash-delimited path
    @OracleDependency(
        domain = DependencyDomain.EMBEDDED_SQL,
        category = "CONNECT_BY",
        description = "Delegates to EmployeeRepository.findHierarchyBelowManager() rooting the "
                    + "CONNECT BY traversal at START WITH e.employee_id = :managerId",
        complexity = MigrationComplexity.HIGH,
        automationLevel = AutomationLevel.PARTIAL,
        migrationTarget = "Replace with recursive CTE anchored at WHERE employee_id = :managerId in PostgreSQL"
    )
    @OracleDependency(
        domain = DependencyDomain.EMBEDDED_SQL,
        category = "SYS_CONNECT_BY_PATH",
        description = "SYS_CONNECT_BY_PATH(e.last_name, ' / ') produces the full name path from root "
                    + "to each node, surfaced as org_path in the result set",
        complexity = MigrationComplexity.HIGH,
        automationLevel = AutomationLevel.PARTIAL,
        migrationTarget = "Replace with STRING_AGG path accumulation maintained in the recursive CTE: "
                        + "path || ' / ' || last_name in PostgreSQL"
    )
    public List<EmployeeHierarchyDTO> getHierarchyBelowManager(Long managerId) {
        if (managerId == null) {
            throw new MeridianBankException("INVALID_INPUT", "Manager ID must not be null", 400);
        }
        log.info("Fetching hierarchy below manager {} via Oracle CONNECT BY / SYS_CONNECT_BY_PATH", managerId);
        // ORACLE-DEP [EMBEDDED_SQL:CONNECT_BY] findHierarchyBelowManager uses CONNECT BY PRIOR
        // ORACLE-DEP [EMBEDDED_SQL:SYS_CONNECT_BY_PATH] org_path column comes from SYS_CONNECT_BY_PATH
        List<Object[]> rows = employeeRepository.findHierarchyBelowManager(managerId);
        return rows.stream()
                   .map(this::mapHierarchyBelowManagerRow)
                   .collect(Collectors.toList());
    }

    /**
     * Returns the reporting chain from {@code employeeId} upward to the
     * top-most executive (the CEO), ordered closest-first.
     *
     * <p>The {@code rootEmployee} field in each DTO is populated from Oracle's
     * CONNECT_BY_ROOT expression -- it always carries the name of the tree root
     * regardless of which node is being inspected.  The {@code hierarchyPath}
     * field carries the title chain built with SYS_CONNECT_BY_PATH.
     *
     * @param employeeId starting employee for the upward traversal
     * @return reporting chain from the employee to the CEO
     */
    // ORACLE-DEP [EMBEDDED_SQL:CONNECT_BY] Upward traversal: CONNECT BY PRIOR e.manager_id = e.employee_id
    // ORACLE-DEP [EMBEDDED_SQL:CONNECT_BY_ROOT] CONNECT_BY_ROOT returns the tree-root value
    @OracleDependency(
        domain = DependencyDomain.EMBEDDED_SQL,
        category = "CONNECT_BY",
        description = "Delegates to EmployeeRepository.findReportingChain() using upward CONNECT BY PRIOR "
                    + "e.manager_id = e.employee_id traversal from the given employee to the CEO",
        complexity = MigrationComplexity.HIGH,
        automationLevel = AutomationLevel.PARTIAL,
        migrationTarget = "Replace with upward recursive CTE in PostgreSQL: anchor on employee_id, "
                        + "recursive step joins on manager_id = employee_id"
    )
    @OracleDependency(
        domain = DependencyDomain.EMBEDDED_SQL,
        category = "CONNECT_BY_ROOT",
        description = "CONNECT_BY_ROOT e.last_name always returns the name of the top-most executive "
                    + "in the traversal regardless of current node depth",
        complexity = MigrationComplexity.HIGH,
        automationLevel = AutomationLevel.PARTIAL,
        migrationTarget = "Replace with FIRST_VALUE(last_name) OVER (ORDER BY depth ASC) or store the "
                        + "anchor row value in the CTE in PostgreSQL"
    )
    public List<EmployeeHierarchyDTO> getReportingChain(Long employeeId) {
        if (employeeId == null) {
            throw new MeridianBankException("INVALID_INPUT", "Employee ID must not be null", 400);
        }
        log.info("Fetching reporting chain for employee {} via Oracle CONNECT BY / CONNECT_BY_ROOT", employeeId);
        // ORACLE-DEP [EMBEDDED_SQL:CONNECT_BY] findReportingChain uses upward CONNECT BY PRIOR
        // ORACLE-DEP [EMBEDDED_SQL:CONNECT_BY_ROOT] top_executive column from CONNECT_BY_ROOT
        List<Object[]> rows = employeeRepository.findReportingChain(employeeId);
        return rows.stream()
                   .map(this::mapReportingChainRow)
                   .collect(Collectors.toList());
    }

    /**
     * Returns the direct reports (one level down) for the given manager.
     *
     * <p>Although the repository query still uses CONNECT BY with LEVEL <= 2
     * to restrict depth, the semantics are equivalent to a simple
     * {@code WHERE manager_id = :managerId} -- making this a medium-complexity
     * migration candidate.
     *
     * @param managerId the manager whose immediate direct reports are requested
     * @return list of direct-report employees
     */
    // ORACLE-DEP [EMBEDDED_SQL:CONNECT_BY] Direct reports via CONNECT BY PRIOR with LEVEL <= 2
    @OracleDependency(
        domain = DependencyDomain.EMBEDDED_SQL,
        category = "CONNECT_BY",
        description = "Delegates to EmployeeRepository.findDirectReports() which uses CONNECT BY PRIOR "
                    + "restricted to LEVEL <= 2 to return only immediate direct reports",
        complexity = MigrationComplexity.MEDIUM,
        automationLevel = AutomationLevel.PARTIAL,
        migrationTarget = "Replace with simple SELECT WHERE manager_id = :managerId in PostgreSQL; "
                        + "no CTE required for a single-level query"
    )
    public List<EmployeeHierarchyDTO> getDirectReports(Long managerId) {
        if (managerId == null) {
            throw new MeridianBankException("INVALID_INPUT", "Manager ID must not be null", 400);
        }
        log.debug("Fetching direct reports for manager {}", managerId);
        // ORACLE-DEP [EMBEDDED_SQL:CONNECT_BY] findDirectReports uses CONNECT BY + LEVEL <= 2
        List<Object[]> rows = employeeRepository.findDirectReports(managerId);
        // Skip the first row: the START WITH manager row is included by the query
        return rows.stream()
                   .skip(1)
                   .map(this::mapDirectReportRow)
                   .collect(Collectors.toList());
    }

    /**
     * Returns all individual contributors -- employees who have no direct reports
     * (leaf nodes in the org chart).
     *
     * <p>The repository query uses Oracle's CONNECT_BY_ISLEAF pseudocolumn
     * filtered to 1 to identify leaves in a single pass rather than a
     * correlated sub-query.
     *
     * @return list of leaf employees with their org depth
     */
    // ORACLE-DEP [EMBEDDED_SQL:CONNECT_BY_ISLEAF] CONNECT_BY_ISLEAF = 1 identifies leaf employees
    @OracleDependency(
        domain = DependencyDomain.EMBEDDED_SQL,
        category = "CONNECT_BY_ISLEAF",
        description = "Delegates to EmployeeRepository.findIndividualContributors() using CONNECT_BY_ISLEAF = 1 "
                    + "to identify employees who have no direct reports (leaf nodes)",
        complexity = MigrationComplexity.HIGH,
        automationLevel = AutomationLevel.PARTIAL,
        migrationTarget = "Replace with NOT EXISTS (SELECT 1 FROM employees sub WHERE sub.manager_id = e.employee_id) "
                        + "in PostgreSQL; no direct CONNECT_BY_ISLEAF equivalent"
    )
    public List<EmployeeHierarchyDTO> getIndividualContributors() {
        log.debug("Fetching individual contributors (leaf nodes) via Oracle CONNECT_BY_ISLEAF");
        // ORACLE-DEP [EMBEDDED_SQL:CONNECT_BY_ISLEAF] findIndividualContributors filters CONNECT_BY_ISLEAF=1
        List<Object[]> rows = employeeRepository.findIndividualContributors();
        return rows.stream()
                   .map(this::mapIndividualContributorRow)
                   .collect(Collectors.toList());
    }

    /**
     * Returns a headcount summary broken down by Oracle LEVEL (org depth) and
     * department.  Uses Oracle's CONNECT BY PRIOR with a GROUP BY LEVEL clause --
     * a pattern that has no direct analogue outside hierarchical SQL.
     *
     * @return list of maps with keys: org_level, department_id, headcount
     */
    // ORACLE-DEP [EMBEDDED_SQL:CONNECT_BY] GROUP BY LEVEL for headcount per org depth
    @OracleDependency(
        domain = DependencyDomain.EMBEDDED_SQL,
        category = "CONNECT_BY",
        description = "Delegates to EmployeeRepository.findHeadcountByOrgLevel() which groups by the "
                    + "Oracle LEVEL pseudocolumn to count employees at each hierarchy depth per department",
        complexity = MigrationComplexity.HIGH,
        automationLevel = AutomationLevel.PARTIAL,
        migrationTarget = "Replace with recursive CTE that carries a depth counter; GROUP BY depth, department_id "
                        + "in the outer query in PostgreSQL"
    )
    public List<Map<String, Object>> getHeadcountByOrgLevel() {
        log.debug("Fetching headcount by org level via Oracle CONNECT BY / LEVEL GROUP BY");
        // ORACLE-DEP [EMBEDDED_SQL:CONNECT_BY] findHeadcountByOrgLevel uses LEVEL in GROUP BY
        List<Object[]> rows = employeeRepository.findHeadcountByOrgLevel();
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object[] row : rows) {
            Map<String, Object> entry = new HashMap<>();
            entry.put("orgLevel", row[0] != null ? ((Number) row[0]).intValue() : null);
            entry.put("departmentId", row[1] != null ? ((Number) row[1]).longValue() : null);
            entry.put("headcount", row[2] != null ? ((Number) row[2]).longValue() : null);
            result.add(entry);
        }
        return result;
    }

    // -------------------------------------------------------------------------
    // Simple CRUD / flat queries
    // -------------------------------------------------------------------------

    /**
     * Retrieves a single employee by primary key.
     *
     * @param id the employee primary key
     * @return DTO representation of the employee
     * @throws MeridianBankException with HTTP 404 if not found
     */
    public EmployeeHierarchyDTO getEmployeeById(Long id) {
        if (id == null) {
            throw new MeridianBankException("INVALID_INPUT", "Employee ID must not be null", 400);
        }
        log.debug("Fetching employee by id {}", id);
        EmployeeEntity entity = employeeRepository.findById(id)
            .orElseThrow(() -> new MeridianBankException(
                "EMPLOYEE_NOT_FOUND",
                "No employee found with ID: " + id,
                404));
        return mapEntityToDto(entity);
    }

    /**
     * Returns all employees belonging to the specified department.
     *
     * @param deptId the department primary key
     * @return list of employees in that department
     */
    public List<EmployeeHierarchyDTO> getEmployeesByDepartment(Long deptId) {
        if (deptId == null) {
            throw new MeridianBankException("INVALID_INPUT", "Department ID must not be null", 400);
        }
        log.debug("Fetching employees for department {}", deptId);
        return employeeRepository.findByDepartmentId(deptId)
                                 .stream()
                                 .map(this::mapEntityToDto)
                                 .collect(Collectors.toList());
    }

    // -------------------------------------------------------------------------
    // Private mapping helpers
    // -------------------------------------------------------------------------

    /**
     * Maps a row from {@link EmployeeRepository#findFullHierarchy()} to a DTO.
     *
     * <p>Expected column order (from the CONNECT BY query):
     * 0=employee_id, 1=first_name, 2=last_name, 3=job_title,
     * 4=manager_id, 5=department_id, 6=salary,
     * 7=hierarchy_depth (LEVEL), 8=indented_name (LPAD result)
     */
    private EmployeeHierarchyDTO mapFullHierarchyRow(Object[] row) {
        EmployeeHierarchyDTO dto = new EmployeeHierarchyDTO();
        // col 0: employee_id
        if (row[0] != null) dto.setEmployeeId(((Number) row[0]).longValue());
        // col 1: first_name
        dto.setFirstName(row[1] != null ? row[1].toString() : null);
        // col 2: last_name
        dto.setLastName(row[2] != null ? row[2].toString() : null);
        // col 3: job_title
        dto.setJobTitle(row[3] != null ? row[3].toString() : null);
        // col 4: manager_id
        if (row[4] != null) dto.setManagerId(((Number) row[4]).longValue());
        // col 5: department_id (stored in managerId for now; no dept field in DTO)
        // col 6: salary
        if (row[6] != null) dto.setSalary(new BigDecimal(row[6].toString()));
        // col 7: hierarchy_depth from LEVEL pseudocolumn
        // ORACLE-DEP [EMBEDDED_SQL:LEVEL_PSEUDOCOLUMN] hierarchy_depth sourced from Oracle LEVEL
        if (row[7] != null) dto.setLevel(((Number) row[7]).intValue());
        // col 8: indented_name from LPAD(' ', (LEVEL-1)*4) || first_name || ' ' || last_name
        if (row[8] != null) {
            dto.setFullName(row[8].toString().trim());
            dto.setHierarchyPath(row[8].toString());
        }
        return dto;
    }

    /**
     * Maps a row from {@link EmployeeRepository#findHierarchyBelowManager(Long)}.
     *
     * <p>Expected column order:
     * 0=employee_id, 1=first_name, 2=last_name, 3=job_title,
     * 4=hierarchy_depth (LEVEL), 5=org_path (SYS_CONNECT_BY_PATH)
     */
    private EmployeeHierarchyDTO mapHierarchyBelowManagerRow(Object[] row) {
        EmployeeHierarchyDTO dto = new EmployeeHierarchyDTO();
        if (row[0] != null) dto.setEmployeeId(((Number) row[0]).longValue());
        dto.setFirstName(row[1] != null ? row[1].toString() : null);
        dto.setLastName(row[2] != null ? row[2].toString() : null);
        dto.setJobTitle(row[3] != null ? row[3].toString() : null);
        // col 4: hierarchy_depth from LEVEL
        // ORACLE-DEP [EMBEDDED_SQL:LEVEL_PSEUDOCOLUMN] hierarchy_depth sourced from LEVEL
        if (row[4] != null) dto.setLevel(((Number) row[4]).intValue());
        // col 5: org_path from SYS_CONNECT_BY_PATH(e.last_name, ' / ')
        // ORACLE-DEP [EMBEDDED_SQL:SYS_CONNECT_BY_PATH] org_path from SYS_CONNECT_BY_PATH
        if (row[5] != null) dto.setHierarchyPath(row[5].toString());
        if (dto.getFirstName() != null && dto.getLastName() != null) {
            dto.setFullName(dto.getFirstName() + " " + dto.getLastName());
        }
        return dto;
    }

    /**
     * Maps a row from {@link EmployeeRepository#findReportingChain(Long)}.
     *
     * <p>Expected column order:
     * 0=employee_id, 1=first_name, 2=last_name, 3=job_title,
     * 4=levels_from_ceo (LEVEL), 5=top_executive (CONNECT_BY_ROOT last_name),
     * 6=title_chain (SYS_CONNECT_BY_PATH of job_title)
     */
    private EmployeeHierarchyDTO mapReportingChainRow(Object[] row) {
        EmployeeHierarchyDTO dto = new EmployeeHierarchyDTO();
        if (row[0] != null) dto.setEmployeeId(((Number) row[0]).longValue());
        dto.setFirstName(row[1] != null ? row[1].toString() : null);
        dto.setLastName(row[2] != null ? row[2].toString() : null);
        dto.setJobTitle(row[3] != null ? row[3].toString() : null);
        // col 4: levels_from_ceo from LEVEL pseudocolumn
        // ORACLE-DEP [EMBEDDED_SQL:LEVEL_PSEUDOCOLUMN] levels_from_ceo from Oracle LEVEL
        if (row[4] != null) dto.setLevel(((Number) row[4]).intValue());
        // col 5: top_executive from CONNECT_BY_ROOT e.last_name
        // ORACLE-DEP [EMBEDDED_SQL:CONNECT_BY_ROOT] top_executive from CONNECT_BY_ROOT
        if (row[5] != null) dto.setRootEmployee(row[5].toString());
        // col 6: title_chain from SYS_CONNECT_BY_PATH(e.job_title, ' -> ')
        // ORACLE-DEP [EMBEDDED_SQL:SYS_CONNECT_BY_PATH] title_chain from SYS_CONNECT_BY_PATH
        if (row[6] != null) dto.setHierarchyPath(row[6].toString());
        if (dto.getFirstName() != null && dto.getLastName() != null) {
            dto.setFullName(dto.getFirstName() + " " + dto.getLastName());
        }
        return dto;
    }

    /**
     * Maps a row from {@link EmployeeRepository#findIndividualContributors()}.
     *
     * <p>Expected column order:
     * 0=employee_id, 1=first_name, 2=last_name, 3=job_title,
     * 4=depth (LEVEL), 5=is_individual_contributor (CONNECT_BY_ISLEAF)
     */
    private EmployeeHierarchyDTO mapIndividualContributorRow(Object[] row) {
        EmployeeHierarchyDTO dto = new EmployeeHierarchyDTO();
        if (row[0] != null) dto.setEmployeeId(((Number) row[0]).longValue());
        dto.setFirstName(row[1] != null ? row[1].toString() : null);
        dto.setLastName(row[2] != null ? row[2].toString() : null);
        dto.setJobTitle(row[3] != null ? row[3].toString() : null);
        // col 4: depth from LEVEL
        // ORACLE-DEP [EMBEDDED_SQL:LEVEL_PSEUDOCOLUMN] depth sourced from Oracle LEVEL
        if (row[4] != null) dto.setLevel(((Number) row[4]).intValue());
        // col 5: CONNECT_BY_ISLEAF=1 means this employee is a leaf / individual contributor
        // ORACLE-DEP [EMBEDDED_SQL:CONNECT_BY_ISLEAF] is_individual_contributor from CONNECT_BY_ISLEAF
        if (row[5] != null) dto.setIsLeaf(((Number) row[5]).intValue() == 1);
        if (dto.getFirstName() != null && dto.getLastName() != null) {
            dto.setFullName(dto.getFirstName() + " " + dto.getLastName());
        }
        return dto;
    }

    /**
     * Maps a row from {@link EmployeeRepository#findDirectReports(Long)}.
     *
     * <p>Expected column order:
     * 0=employee_id, 1=first_name, 2=last_name, 3=job_title,
     * 4=salary, 5=org_depth (LEVEL)
     */
    private EmployeeHierarchyDTO mapDirectReportRow(Object[] row) {
        EmployeeHierarchyDTO dto = new EmployeeHierarchyDTO();
        if (row[0] != null) dto.setEmployeeId(((Number) row[0]).longValue());
        dto.setFirstName(row[1] != null ? row[1].toString() : null);
        dto.setLastName(row[2] != null ? row[2].toString() : null);
        dto.setJobTitle(row[3] != null ? row[3].toString() : null);
        if (row[4] != null) dto.setSalary(new BigDecimal(row[4].toString()));
        // col 5: org_depth from LEVEL
        // ORACLE-DEP [EMBEDDED_SQL:LEVEL_PSEUDOCOLUMN] org_depth from Oracle LEVEL
        if (row[5] != null) dto.setLevel(((Number) row[5]).intValue());
        if (dto.getFirstName() != null && dto.getLastName() != null) {
            dto.setFullName(dto.getFirstName() + " " + dto.getLastName());
        }
        return dto;
    }

    /**
     * Maps an {@link EmployeeEntity} JPA entity to an {@link EmployeeHierarchyDTO}.
     * Used for non-hierarchical flat lookups (getById, getByDepartment).
     */
    private EmployeeHierarchyDTO mapEntityToDto(EmployeeEntity entity) {
        EmployeeHierarchyDTO dto = new EmployeeHierarchyDTO();
        dto.setEmployeeId(entity.getEmployeeId());
        dto.setFirstName(entity.getFirstName());
        dto.setLastName(entity.getLastName());
        dto.setFullName(entity.getFirstName() + " " + entity.getLastName());
        dto.setJobTitle(entity.getJobTitle());
        dto.setManagerId(entity.getManagerId());
        dto.setSalary(entity.getSalary());
        if (entity.getHireDate() != null) {
            dto.setHireDate(entity.getHireDate());
        }
        return dto;
    }
}
