package com.meridianbank.entity;

import com.meridianbank.annotation.AutomationLevel;
import com.meridianbank.annotation.DependencyDomain;
import com.meridianbank.annotation.MigrationComplexity;
import com.meridianbank.annotation.OracleDependency;
import com.meridianbank.annotation.OracleDependencies;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.NamedNativeQueries;
import jakarta.persistence.NamedNativeQuery;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Entity representing bank employees, including the full management hierarchy.
 * Supports Oracle CONNECT BY hierarchical queries for org-chart reporting.
 *
 * ORACLE-DEP [EMBEDDED_SQL:CONNECT_BY] Self-referencing manager_id FK enables
 * CONNECT BY PRIOR hierarchical traversal queries.
 * ORACLE-DEP [EMBEDDED_SQL:LEVEL_PSEUDOCOL] LEVEL pseudocolumn used in hierarchy depth queries.
 */
@OracleDependencies({
    @OracleDependency(
        domain = DependencyDomain.EMBEDDED_SQL,
        category = "CONNECT_BY",
        description = "Self-referencing FK enables CONNECT BY PRIOR hierarchical queries",
        complexity = MigrationComplexity.MEDIUM,
        automationLevel = AutomationLevel.PARTIAL,
        migrationTarget = "Replace CONNECT BY with recursive CTE (WITH RECURSIVE)"
    ),
    @OracleDependency(
        domain = DependencyDomain.EMBEDDED_SQL,
        category = "LEVEL_PSEUDOCOL",
        description = "LEVEL pseudocolumn used in hierarchy depth queries",
        complexity = MigrationComplexity.MEDIUM,
        automationLevel = AutomationLevel.PARTIAL,
        migrationTarget = "Use depth column in recursive CTE"
    )
})
@NamedNativeQueries({
    @NamedNativeQuery(
        name = "EmployeeEntity.findOrgHierarchy",
        // ORACLE-DEP [EMBEDDED_SQL:CONNECT_BY] CONNECT BY PRIOR for org hierarchy traversal
        // ORACLE-DEP [EMBEDDED_SQL:LEVEL_PSEUDOCOL] LEVEL pseudo-column for hierarchy depth
        // ORACLE-DEP [EMBEDDED_SQL:SYS_CONNECT_BY_PATH] SYS_CONNECT_BY_PATH builds full path
        // ORACLE-DEP [EMBEDDED_SQL:CONNECT_BY_ROOT] CONNECT_BY_ROOT returns root node value
        // ORACLE-DEP [EMBEDDED_SQL:CONNECT_BY_ISLEAF] CONNECT_BY_ISLEAF identifies leaf nodes
        // ORACLE-DEP [EMBEDDED_SQL:START_WITH] START WITH clause seeds the hierarchy
        query = "SELECT e.employee_id, e.first_name, e.last_name, e.job_title, e.manager_id, " +
                "       LEVEL as hierarchy_level, " +
                "       SYS_CONNECT_BY_PATH(e.first_name || ' ' || e.last_name, ' > ') as reporting_path, " +
                "       CONNECT_BY_ROOT (e.first_name || ' ' || e.last_name) as ceo_name, " +
                "       CONNECT_BY_ISLEAF as is_leaf " +
                "FROM MERIDIAN.EMPLOYEES e " +
                "START WITH e.employee_id = :rootEmployeeId " +
                "CONNECT BY PRIOR e.employee_id = e.manager_id " +
                "ORDER SIBLINGS BY e.last_name",
        resultClass = EmployeeEntity.class
    ),
    @NamedNativeQuery(
        name = "EmployeeEntity.findByDeptWithIndent",
        // ORACLE-DEP [EMBEDDED_SQL:CONNECT_BY] CONNECT BY PRIOR for department hierarchy
        // ORACLE-DEP [EMBEDDED_SQL:LEVEL_PSEUDOCOL] LEVEL used with LPAD to indent names
        query = "SELECT e.employee_id, e.first_name, e.last_name, e.salary, LEVEL, " +
                "       LPAD(' ', (LEVEL-1)*4) || e.first_name || ' ' || e.last_name as indented_name " +
                "FROM MERIDIAN.EMPLOYEES e " +
                "WHERE department_id = :deptId " +
                "START WITH manager_id IS NULL " +
                "CONNECT BY PRIOR e.employee_id = e.manager_id",
        resultClass = EmployeeEntity.class
    )
})
@Entity
@Table(name = "EMPLOYEES", schema = "MERIDIAN")
public class EmployeeEntity {

    // ORACLE-DEP [CONNECTIVITY_CONFIG:SEQUENCE_GENERATOR] Oracle sequence for PK generation
    @Id
    @SequenceGenerator(
        name = "employee_seq_gen",
        sequenceName = "MERIDIAN.EMPLOYEE_SEQ",
        allocationSize = 1
    )
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "employee_seq_gen")
    @Column(name = "EMPLOYEE_ID")
    private Long employeeId;

    // ORACLE-DEP [EMBEDDED_SQL:VARCHAR2_TYPE] Oracle VARCHAR2 column definition
    @Column(name = "FIRST_NAME", columnDefinition = "VARCHAR2(100)", nullable = false)
    private String firstName;

    // ORACLE-DEP [EMBEDDED_SQL:VARCHAR2_TYPE] Oracle VARCHAR2 column definition
    @Column(name = "LAST_NAME", columnDefinition = "VARCHAR2(100)", nullable = false)
    private String lastName;

    // ORACLE-DEP [EMBEDDED_SQL:VARCHAR2_TYPE] Oracle VARCHAR2 column definition
    @Column(name = "EMAIL", columnDefinition = "VARCHAR2(200)", nullable = false, unique = true)
    private String email;

    /**
     * Self-referencing FK to support CONNECT BY PRIOR hierarchical queries.
     * ORACLE-DEP [EMBEDDED_SQL:CONNECT_BY] managerId is the key column in CONNECT BY PRIOR traversal.
     */
    @Column(name = "MANAGER_ID")
    private Long managerId;

    @Column(name = "DEPARTMENT_ID")
    private Integer departmentId;

    /**
     * Oracle DATE type (date only, no time component in Oracle DATE for this use-case).
     * ORACLE-DEP [EMBEDDED_SQL:ORACLE_DATE_TYPE] Oracle DATE stored as LocalDate
     */
    @Column(name = "HIRE_DATE", columnDefinition = "DATE")
    private LocalDate hireDate;

    // ORACLE-DEP [EMBEDDED_SQL:NUMBER_TYPE] Oracle NUMBER(12,2) for monetary salary value
    @Column(name = "SALARY", columnDefinition = "NUMBER(12,2)")
    private BigDecimal salary;

    // ORACLE-DEP [EMBEDDED_SQL:VARCHAR2_TYPE] Oracle VARCHAR2 column definition
    @Column(name = "JOB_TITLE", columnDefinition = "VARCHAR2(100)")
    private String jobTitle;

    /**
     * Renamed to avoid collision with JPA reserved keyword 'level'.
     * ORACLE-DEP [EMBEDDED_SQL:LEVEL_PSEUDOCOL] Oracle LEVEL pseudo-column conflicts with this field name
     */
    @Column(name = "LEVEL_NUM", columnDefinition = "NUMBER(3)")
    private Integer levelNum;

    @Column(name = "BRANCH_ID")
    private Long branchId;

    // ORACLE-DEP [EMBEDDED_SQL:VARCHAR2_TYPE] Oracle VARCHAR2 column definition
    @Column(name = "STATUS", columnDefinition = "VARCHAR2(20)", nullable = false)

    private String status = "ACTIVE";

    // ORACLE-DEP [EMBEDDED_SQL:ORACLE_DATE_TYPE] Oracle DATE type mapped to LocalDate
    @Column(name = "CREATED_DATE", columnDefinition = "DATE")
    private LocalDate createdDate;
    public EmployeeEntity() {
    }


    public Long getEmployeeId() {
        return employeeId;
    }

    public void setEmployeeId(Long employeeId) {
        this.employeeId = employeeId;
    }

    public String getFirstName() {
        return firstName;
    }

    public void setFirstName(String firstName) {
        this.firstName = firstName;
    }

    public String getLastName() {
        return lastName;
    }

    public void setLastName(String lastName) {
        this.lastName = lastName;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public Long getManagerId() {
        return managerId;
    }

    public void setManagerId(Long managerId) {
        this.managerId = managerId;
    }

    public Integer getDepartmentId() {
        return departmentId;
    }

    public void setDepartmentId(Integer departmentId) {
        this.departmentId = departmentId;
    }

    public LocalDate getHireDate() {
        return hireDate;
    }

    public void setHireDate(LocalDate hireDate) {
        this.hireDate = hireDate;
    }

    public BigDecimal getSalary() {
        return salary;
    }

    public void setSalary(BigDecimal salary) {
        this.salary = salary;
    }

    public String getJobTitle() {
        return jobTitle;
    }

    public void setJobTitle(String jobTitle) {
        this.jobTitle = jobTitle;
    }

    public Integer getLevelNum() {
        return levelNum;
    }

    public void setLevelNum(Integer levelNum) {
        this.levelNum = levelNum;
    }

    public Long getBranchId() {
        return branchId;
    }

    public void setBranchId(Long branchId) {
        this.branchId = branchId;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public LocalDate getCreatedDate() {
        return createdDate;
    }

    public void setCreatedDate(LocalDate createdDate) {
        this.createdDate = createdDate;
    }

}
