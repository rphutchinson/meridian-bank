package com.meridianbank.dto;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * DTO for employee hierarchy — populated by Oracle CONNECT BY PRIOR queries.
 *
 * <p>The {@code hierarchyPath} field is populated from Oracle's
 * {@code SYS_CONNECT_BY_PATH()} function, e.g. "/CEO/VP Sales/Regional Manager/John".
 * The {@code level} field comes from Oracle's LEVEL pseudocolumn.
 */
public class EmployeeHierarchyDTO {

    private Long employeeId;
    private String firstName;
    private String lastName;
    private String fullName;
    private String email;
    private String jobTitle;
    private String department;
    private BigDecimal salary;

    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate hireDate;

    private Long managerId;
    private String managerName;

    /** Oracle LEVEL pseudocolumn — depth in hierarchy (1 = root). */
    private Integer level;

    /** Oracle SYS_CONNECT_BY_PATH result — full path from root. */
    private String hierarchyPath;

    /** Oracle CONNECT_BY_ROOT expression — name of root employee. */
    private String rootEmployee;

    /** Oracle CONNECT_BY_ISLEAF — true if no direct reports. */
    private Boolean isLeaf;

    /** Direct reports (populated in hierarchy responses). */
    private List<EmployeeHierarchyDTO> directReports;

    /** Full reporting chain from this employee up to CEO. */
    private List<String> reportingChain;
    public EmployeeHierarchyDTO() {
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

    public String getFullName() {
        return fullName;
    }

    public void setFullName(String fullName) {
        this.fullName = fullName;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getJobTitle() {
        return jobTitle;
    }

    public void setJobTitle(String jobTitle) {
        this.jobTitle = jobTitle;
    }

    public String getDepartment() {
        return department;
    }

    public void setDepartment(String department) {
        this.department = department;
    }

    public BigDecimal getSalary() {
        return salary;
    }

    public void setSalary(BigDecimal salary) {
        this.salary = salary;
    }

    public LocalDate getHireDate() {
        return hireDate;
    }

    public void setHireDate(LocalDate hireDate) {
        this.hireDate = hireDate;
    }

    public Long getManagerId() {
        return managerId;
    }

    public void setManagerId(Long managerId) {
        this.managerId = managerId;
    }

    public String getManagerName() {
        return managerName;
    }

    public void setManagerName(String managerName) {
        this.managerName = managerName;
    }

    public Integer getLevel() {
        return level;
    }

    public void setLevel(Integer level) {
        this.level = level;
    }

    public String getHierarchyPath() {
        return hierarchyPath;
    }

    public void setHierarchyPath(String hierarchyPath) {
        this.hierarchyPath = hierarchyPath;
    }

    public String getRootEmployee() {
        return rootEmployee;
    }

    public void setRootEmployee(String rootEmployee) {
        this.rootEmployee = rootEmployee;
    }

    public Boolean isIsLeaf() {
        return isLeaf;
    }

    public void setIsLeaf(Boolean isLeaf) {
        this.isLeaf = isLeaf;
    }

    public List<EmployeeHierarchyDTO> getDirectReports() {
        return directReports;
    }

    public void setDirectReports(List<EmployeeHierarchyDTO> directReports) {
        this.directReports = directReports;
    }

    public List<String> getReportingChain() {
        return reportingChain;
    }

    public void setReportingChain(List<String> reportingChain) {
        this.reportingChain = reportingChain;
    }

}
