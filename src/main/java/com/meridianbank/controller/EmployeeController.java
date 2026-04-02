package com.meridianbank.controller;

import com.meridianbank.dto.EmployeeHierarchyDTO;
import com.meridianbank.service.EmployeeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * REST controller for Employee hierarchy operations.
 *
 * <p>Thin HTTP adapter over EmployeeService. All Oracle hierarchical query
 * constructs — CONNECT BY PRIOR, START WITH, LEVEL pseudocolumn,
 * SYS_CONNECT_BY_PATH, CONNECT_BY_ROOT, CONNECT_BY_ISLEAF — are
 * encapsulated in EmployeeRepository native queries and EmployeeService.
 *
 * <p>Every endpoint in this controller directly exercises the Oracle
 * hierarchical query functionality that is one of the most significant
 * Oracle-to-PostgreSQL migration challenges. The PostgreSQL equivalent
 * for all of these is a recursive CTE (WITH RECURSIVE).
 *
 * <p>Oracle dependency summary for this controller's backing service:
 * ORACLE-DEP [EMBEDDED_SQL:CONNECT_BY_PRIOR]   All hierarchy traversal methods
 * ORACLE-DEP [EMBEDDED_SQL:LEVEL_PSEUDOCOLUMN]  Level/depth in getFullOrgHierarchy
 * ORACLE-DEP [EMBEDDED_SQL:SYS_CONNECT_BY_PATH] Path string in getReportingChain
 * ORACLE-DEP [EMBEDDED_SQL:CONNECT_BY_ROOT]      Root node in getHierarchyBelowManager
 */
@RestController
@RequestMapping("/api/employees")
@CrossOrigin(origins = "*")
public class EmployeeController {

    private static final Logger log = LoggerFactory.getLogger(EmployeeController.class);

    private final EmployeeService employeeService;

    @Autowired
    public EmployeeController(EmployeeService employeeService) {
        this.employeeService = employeeService;
    }

    // -------------------------------------------------------------------------
    // GET /api/employees/{id}/hierarchy
    // Returns the full org-chart subtree rooted at manager {id}.
    // EmployeeService.getHierarchyBelowManager() issues:
    //   SELECT e.*, LEVEL, SYS_CONNECT_BY_PATH(e.last_name, '/') path
    //   FROM employees e
    //   START WITH e.employee_id = :managerId
    //   CONNECT BY PRIOR e.employee_id = e.manager_id
    // -------------------------------------------------------------------------
    @GetMapping("/{id}/hierarchy")
    public ResponseEntity<List<EmployeeHierarchyDTO>> getHierarchyBelowManager(
            @PathVariable Long id) {

        log.debug("GET /api/employees/{}/hierarchy", id);
        List<EmployeeHierarchyDTO> hierarchy = employeeService.getHierarchyBelowManager(id);
        return ResponseEntity.ok(hierarchy);
    }

    // -------------------------------------------------------------------------
    // GET /api/employees/{id}/reporting-chain
    // Returns the reporting chain from employee {id} up to the CEO.
    // EmployeeService.getReportingChain() issues a bottom-up CONNECT BY:
    //   START WITH e.employee_id = :employeeId
    //   CONNECT BY PRIOR e.manager_id = e.employee_id
    // with SYS_CONNECT_BY_PATH for full path representation.
    // -------------------------------------------------------------------------
    @GetMapping("/{id}/reporting-chain")
    public ResponseEntity<List<EmployeeHierarchyDTO>> getReportingChain(
            @PathVariable Long id) {

        log.debug("GET /api/employees/{}/reporting-chain", id);
        List<EmployeeHierarchyDTO> chain = employeeService.getReportingChain(id);
        return ResponseEntity.ok(chain);
    }

    // -------------------------------------------------------------------------
    // GET /api/employees/hierarchy/full
    // Returns the entire org hierarchy starting from the top-level node.
    // Uses CONNECT BY PRIOR with START WITH manager_id IS NULL.
    // LEVEL pseudocolumn provides depth for indentation in the UI.
    // -------------------------------------------------------------------------
    @GetMapping("/hierarchy/full")
    public ResponseEntity<List<EmployeeHierarchyDTO>> getFullOrgHierarchy() {
        log.debug("GET /api/employees/hierarchy/full");
        List<EmployeeHierarchyDTO> full = employeeService.getFullOrgHierarchy();
        return ResponseEntity.ok(full);
    }

    // -------------------------------------------------------------------------
    // GET /api/employees/{id}/direct-reports
    // Returns only the direct reports of a manager (depth = 1).
    // -------------------------------------------------------------------------
    @GetMapping("/{id}/direct-reports")
    public ResponseEntity<List<EmployeeHierarchyDTO>> getDirectReports(
            @PathVariable Long id) {

        log.debug("GET /api/employees/{}/direct-reports", id);
        List<EmployeeHierarchyDTO> reports = employeeService.getDirectReports(id);
        return ResponseEntity.ok(reports);
    }

    // -------------------------------------------------------------------------
    // GET /api/employees/{id}
    // Returns a single employee by primary key.
    // -------------------------------------------------------------------------
    @GetMapping("/{id}")
    public ResponseEntity<EmployeeHierarchyDTO> getEmployeeById(@PathVariable Long id) {
        log.debug("GET /api/employees/{}", id);
        EmployeeHierarchyDTO employee = employeeService.getEmployeeById(id);
        return ResponseEntity.ok(employee);
    }

    // -------------------------------------------------------------------------
    // GET /api/employees/department/{deptId}
    // Returns all employees in a department.
    // -------------------------------------------------------------------------
    @GetMapping("/department/{deptId}")
    public ResponseEntity<List<EmployeeHierarchyDTO>> getEmployeesByDepartment(
            @PathVariable Long deptId) {

        log.debug("GET /api/employees/department/{}", deptId);
        List<EmployeeHierarchyDTO> employees = employeeService.getEmployeesByDepartment(deptId);
        return ResponseEntity.ok(employees);
    }

    // -------------------------------------------------------------------------
    // GET /api/employees/individual-contributors
    // Returns leaf-node employees (CONNECT_BY_ISLEAF = 1) — no direct reports.
    // -------------------------------------------------------------------------
    @GetMapping("/individual-contributors")
    public ResponseEntity<List<EmployeeHierarchyDTO>> getIndividualContributors() {
        log.debug("GET /api/employees/individual-contributors");
        List<EmployeeHierarchyDTO> ics = employeeService.getIndividualContributors();
        return ResponseEntity.ok(ics);
    }

    // -------------------------------------------------------------------------
    // GET /api/employees/headcount-by-level
    // Returns headcount grouped by Oracle LEVEL (org depth).
    // -------------------------------------------------------------------------
    @GetMapping("/headcount-by-level")
    public ResponseEntity<List<Map<String, Object>>> getHeadcountByOrgLevel(
            @RequestParam(required = false) Long rootManagerId) {

        log.debug("GET /api/employees/headcount-by-level rootManagerId={}", rootManagerId);
        List<Map<String, Object>> headcount = employeeService.getHeadcountByOrgLevel();
        return ResponseEntity.ok(headcount);
    }
}
