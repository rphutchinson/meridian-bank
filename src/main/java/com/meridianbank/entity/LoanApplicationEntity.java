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
import jakarta.persistence.Lob;
import jakarta.persistence.NamedNativeQueries;
import jakarta.persistence.NamedNativeQuery;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Entity representing a customer's loan application through the underwriting pipeline.
 * Uses Oracle-specific date functions (SYSDATE, ADD_MONTHS, MONTHS_BETWEEN, NVL) in
 * named native queries and CLOB for long-form underwriting notes.
 *
 * ORACLE-DEP [EMBEDDED_SQL:CLOB_TYPE] CLOB field for loan underwriting notes
 * ORACLE-DEP [EMBEDDED_SQL:ADD_MONTHS] ADD_MONTHS used for loan maturity date calculation
 * ORACLE-DEP [EMBEDDED_SQL:MONTHS_BETWEEN] MONTHS_BETWEEN used to calculate processing time
 * ORACLE-DEP [EMBEDDED_SQL:SYSDATE] SYSDATE used as default application date reference
 * ORACLE-DEP [EMBEDDED_SQL:NVL_FUNCTION] NVL used extensively for nullable field defaults
 */
@OracleDependencies({
    @OracleDependency(
        domain = DependencyDomain.EMBEDDED_SQL,
        category = "CLOB_TYPE",
        description = "CLOB field for loan underwriting notes",
        complexity = MigrationComplexity.MEDIUM,
        automationLevel = AutomationLevel.FULL,
        migrationTarget = "Use TEXT type in PostgreSQL; remove EMPTY_CLOB() initialization"
    ),
    @OracleDependency(
        domain = DependencyDomain.EMBEDDED_SQL,
        category = "ADD_MONTHS",
        description = "ADD_MONTHS for loan maturity date calculation",
        complexity = MigrationComplexity.LOW,
        automationLevel = AutomationLevel.FULL,
        migrationTarget = "Replace ADD_MONTHS(d,n) with d + CAST(n||' months' AS interval)"
    )
})
@NamedNativeQueries({
    @NamedNativeQuery(
        name = "LoanApplicationEntity.findPendingWithDaysOpen",
        // ORACLE-DEP [EMBEDDED_SQL:SYSDATE] SYSDATE minus application_date gives days pending
        // ORACLE-DEP [EMBEDDED_SQL:NVL_FUNCTION] NVL defaults null approved_amount to 0
        // ORACLE-DEP [EMBEDDED_SQL:ADD_MONTHS] ADD_MONTHS projects loan maturity from application date
        query = "SELECT la.application_id, la.customer_id, la.loan_type, la.requested_amount, la.status, " +
                "       ROUND(SYSDATE - la.application_date) as days_pending, " +
                "       NVL(la.approved_amount, 0) as effective_amount, " +
                "       ADD_MONTHS(la.application_date, NVL(la.term_months, 12)) as projected_maturity " +
                "FROM MERIDIAN.LOAN_APPLICATIONS la " +
                "WHERE la.status IN ('SUBMITTED','UNDER_REVIEW') " +
                "ORDER BY la.application_date",
        resultClass = LoanApplicationEntity.class
    ),
    @NamedNativeQuery(
        name = "LoanApplicationEntity.findByCustomerWithMonthsBetween",
        // ORACLE-DEP [EMBEDDED_SQL:MONTHS_BETWEEN] MONTHS_BETWEEN calculates loan processing time
        // ORACLE-DEP [EMBEDDED_SQL:NVL_FUNCTION] NVL substitutes SYSDATE when decision_date is null
        // ORACLE-DEP [EMBEDDED_SQL:SYSDATE] SYSDATE as fallback for open applications
        query = "SELECT la.*, MONTHS_BETWEEN(NVL(la.decision_date, SYSDATE), la.application_date) as processing_months " +
                "FROM MERIDIAN.LOAN_APPLICATIONS la " +
                "WHERE la.customer_id = :customerId " +
                "ORDER BY la.application_date DESC",
        resultClass = LoanApplicationEntity.class
    )
})
@Entity
@Table(name = "LOAN_APPLICATIONS", schema = "MERIDIAN")
public class LoanApplicationEntity {

    // ORACLE-DEP [CONNECTIVITY_CONFIG:SEQUENCE_GENERATOR] Oracle sequence for PK generation
    @Id
    @SequenceGenerator(
        name = "loan_app_seq_gen",
        sequenceName = "MERIDIAN.LOAN_APP_SEQ",
        allocationSize = 1
    )
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "loan_app_seq_gen")
    @Column(name = "APPLICATION_ID")
    private Long applicationId;

    @Column(name = "CUSTOMER_ID", nullable = false)
    private Long customerId;

    // ORACLE-DEP [EMBEDDED_SQL:VARCHAR2_TYPE] Oracle VARCHAR2 column definition
    @Column(name = "LOAN_TYPE", columnDefinition = "VARCHAR2(30)", nullable = false)
    private String loanType;

    // ORACLE-DEP [EMBEDDED_SQL:NUMBER_TYPE] Oracle NUMBER(15,2) for monetary amounts
    @Column(name = "REQUESTED_AMOUNT", columnDefinition = "NUMBER(15,2)", nullable = false)
    private BigDecimal requestedAmount;

    // ORACLE-DEP [EMBEDDED_SQL:NUMBER_TYPE] Oracle NUMBER(15,2) nullable until approved
    @Column(name = "APPROVED_AMOUNT", columnDefinition = "NUMBER(15,2)")
    private BigDecimal approvedAmount;

    // ORACLE-DEP [EMBEDDED_SQL:VARCHAR2_TYPE] Oracle VARCHAR2 column definition
    @Column(name = "STATUS", columnDefinition = "VARCHAR2(20)", nullable = false)

    private String status = "SUBMITTED";

    /**
     * Oracle DATE type — date only, no time component.
     * ORACLE-DEP [EMBEDDED_SQL:ORACLE_DATE_TYPE] Oracle DATE used for application_date
     */
    @Column(name = "APPLICATION_DATE", columnDefinition = "DATE", nullable = false)
    private LocalDate applicationDate;

    /**
     * Oracle DATE type — null until a decision is reached.
     * ORACLE-DEP [EMBEDDED_SQL:ORACLE_DATE_TYPE] Oracle DATE used for decision_date
     */
    @Column(name = "DECISION_DATE", columnDefinition = "DATE")
    private LocalDate decisionDate;

    // ORACLE-DEP [EMBEDDED_SQL:NUMBER_TYPE] Oracle NUMBER(6,4) for precise interest rate
    @Column(name = "INTEREST_RATE", columnDefinition = "NUMBER(6,4)")
    private BigDecimal interestRate;

    @Column(name = "TERM_MONTHS")
    private Integer termMonths;

    /**
     * Long-form underwriting notes stored as Oracle CLOB.
     * ORACLE-DEP [EMBEDDED_SQL:CLOB_TYPE] Oracle CLOB for large text; initialized with EMPTY_CLOB()
     * in the DDL default. PostgreSQL replacement: TEXT column.
     */
    @Lob
    @Column(name = "NOTES")
    private String notes;

    @Column(name = "OFFICER_ID")
    private Long officerId;

    // ORACLE-DEP [EMBEDDED_SQL:ORACLE_DATE_TYPE] Oracle DATE for record creation timestamp
    @Column(name = "CREATED_DATE", columnDefinition = "DATE")
    private LocalDate createdDate;
    public LoanApplicationEntity() {
    }


    public Long getApplicationId() {
        return applicationId;
    }

    public void setApplicationId(Long applicationId) {
        this.applicationId = applicationId;
    }

    public Long getCustomerId() {
        return customerId;
    }

    public void setCustomerId(Long customerId) {
        this.customerId = customerId;
    }

    public String getLoanType() {
        return loanType;
    }

    public void setLoanType(String loanType) {
        this.loanType = loanType;
    }

    public BigDecimal getRequestedAmount() {
        return requestedAmount;
    }

    public void setRequestedAmount(BigDecimal requestedAmount) {
        this.requestedAmount = requestedAmount;
    }

    public BigDecimal getApprovedAmount() {
        return approvedAmount;
    }

    public void setApprovedAmount(BigDecimal approvedAmount) {
        this.approvedAmount = approvedAmount;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public LocalDate getApplicationDate() {
        return applicationDate;
    }

    public void setApplicationDate(LocalDate applicationDate) {
        this.applicationDate = applicationDate;
    }

    public LocalDate getDecisionDate() {
        return decisionDate;
    }

    public void setDecisionDate(LocalDate decisionDate) {
        this.decisionDate = decisionDate;
    }

    public BigDecimal getInterestRate() {
        return interestRate;
    }

    public void setInterestRate(BigDecimal interestRate) {
        this.interestRate = interestRate;
    }

    public Integer getTermMonths() {
        return termMonths;
    }

    public void setTermMonths(Integer termMonths) {
        this.termMonths = termMonths;
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }

    public Long getOfficerId() {
        return officerId;
    }

    public void setOfficerId(Long officerId) {
        this.officerId = officerId;
    }

    public LocalDate getCreatedDate() {
        return createdDate;
    }

    public void setCreatedDate(LocalDate createdDate) {
        this.createdDate = createdDate;
    }

}
