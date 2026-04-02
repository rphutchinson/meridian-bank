package com.meridianbank.dto;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.LocalDate;

/**
 * DTO for report query parameters.
 * Date range parameters feed into Oracle date arithmetic and TRUNC() queries.
 */
public class ReportParameterDTO {

    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate startDate;

    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate endDate;

    private Long customerId;
    private Long accountId;
    private Long branchId;
    private String region;
    private String accountType;
    private String transactionType;
    private String status;

    /** Number of buckets for Oracle WIDTH_BUCKET distribution analysis. */
    private Integer buckets;

    /** Number of quantiles for Oracle NTILE ranking. */
    private Integer ntile;

    /** Pagination: page number (1-based). */
    private Integer page;

    /** Pagination: page size. */
    private Integer pageSize;

    /** Report format: JSON, CSV, PDF. */
    private String format;
    public ReportParameterDTO() {
    }


    public LocalDate getStartDate() {
        return startDate;
    }

    public void setStartDate(LocalDate startDate) {
        this.startDate = startDate;
    }

    public LocalDate getEndDate() {
        return endDate;
    }

    public void setEndDate(LocalDate endDate) {
        this.endDate = endDate;
    }

    public Long getCustomerId() {
        return customerId;
    }

    public void setCustomerId(Long customerId) {
        this.customerId = customerId;
    }

    public Long getAccountId() {
        return accountId;
    }

    public void setAccountId(Long accountId) {
        this.accountId = accountId;
    }

    public Long getBranchId() {
        return branchId;
    }

    public void setBranchId(Long branchId) {
        this.branchId = branchId;
    }

    public String getRegion() {
        return region;
    }

    public void setRegion(String region) {
        this.region = region;
    }

    public String getAccountType() {
        return accountType;
    }

    public void setAccountType(String accountType) {
        this.accountType = accountType;
    }

    public String getTransactionType() {
        return transactionType;
    }

    public void setTransactionType(String transactionType) {
        this.transactionType = transactionType;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Integer getBuckets() {
        return buckets;
    }

    public void setBuckets(Integer buckets) {
        this.buckets = buckets;
    }

    public Integer getNtile() {
        return ntile;
    }

    public void setNtile(Integer ntile) {
        this.ntile = ntile;
    }

    public Integer getPage() {
        return page;
    }

    public void setPage(Integer page) {
        this.page = page;
    }

    public Integer getPageSize() {
        return pageSize;
    }

    public void setPageSize(Integer pageSize) {
        this.pageSize = pageSize;
    }

    public String getFormat() {
        return format;
    }

    public void setFormat(String format) {
        this.format = format;
    }

}
