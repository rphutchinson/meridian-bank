package com.meridianbank.dto;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * DTO for financial report results.
 *
 * <p>Populated from Oracle-specific analytical queries:
 * RATIO_TO_REPORT for portfolio allocation,
 * WIDTH_BUCKET for transaction distribution,
 * LISTAGG for account summaries,
 * PIVOT for monthly breakdowns.
 */
public class FinancialReportDTO {

    private String reportType;
    private String reportTitle;

    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate reportDate;

    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate periodStart;

    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate periodEnd;

    // Summary figures
    private BigDecimal totalAssets;
    private BigDecimal totalDeposits;
    private BigDecimal totalLoans;
    private BigDecimal netInterestIncome;
    private Long transactionCount;
    private BigDecimal totalTransactionVolume;

    /**
     * Portfolio allocation ratios from Oracle RATIO_TO_REPORT().
     * Keys: account type (CHECKING, SAVINGS, etc.), values: ratio (0.0–1.0)
     */
    private Map<String, Double> portfolioAllocation;

    /**
     * Monthly transaction pivot from Oracle PIVOT.
     * Outer key: year-month string; inner map: type -> amount.
     */
    private Map<String, Map<String, BigDecimal>> monthlyTransactionPivot;

    /**
     * Transaction amount distribution from Oracle WIDTH_BUCKET.
     * Each entry: bucket range and count.
     */
    private List<DistributionBucket> transactionDistribution;

    /**
     * Customer ranking data from Oracle NTILE / CUME_DIST.
     */
    private List<CustomerRankingRow> customerRankings;

    /** Aggregated account number list from Oracle LISTAGG. */
    private String accountSummary;

    public static class DistributionBucket {
        private BigDecimal bucketMin;
        private BigDecimal bucketMax;
        private Long count;
        private BigDecimal totalAmount;
        private Double percentage;

        public DistributionBucket() {}

        public BigDecimal getBucketMin() { return bucketMin; }
        public void setBucketMin(BigDecimal bucketMin) { this.bucketMin = bucketMin; }
        public BigDecimal getBucketMax() { return bucketMax; }
        public void setBucketMax(BigDecimal bucketMax) { this.bucketMax = bucketMax; }
        public Long getCount() { return count; }
        public void setCount(Long count) { this.count = count; }
        public BigDecimal getTotalAmount() { return totalAmount; }
        public void setTotalAmount(BigDecimal totalAmount) { this.totalAmount = totalAmount; }
        public Double getPercentage() { return percentage; }
        public void setPercentage(Double percentage) { this.percentage = percentage; }
    }

    public static class CustomerRankingRow {
        private Long customerId;
        private String customerName;
        private BigDecimal totalVolume;
        private Integer ntileRank;
        private Double cumulativeDistribution;
        private String tier;

        public CustomerRankingRow() {}

        public Long getCustomerId() { return customerId; }
        public void setCustomerId(Long customerId) { this.customerId = customerId; }
        public String getCustomerName() { return customerName; }
        public void setCustomerName(String customerName) { this.customerName = customerName; }
        public BigDecimal getTotalVolume() { return totalVolume; }
        public void setTotalVolume(BigDecimal totalVolume) { this.totalVolume = totalVolume; }
        public Integer getNtileRank() { return ntileRank; }
        public void setNtileRank(Integer ntileRank) { this.ntileRank = ntileRank; }
        public Double getCumulativeDistribution() { return cumulativeDistribution; }
        public void setCumulativeDistribution(Double cumulativeDistribution) { this.cumulativeDistribution = cumulativeDistribution; }
        public String getTier() { return tier; }
        public void setTier(String tier) { this.tier = tier; }
    }
    public FinancialReportDTO() {
    }


    public String getReportType() {
        return reportType;
    }

    public void setReportType(String reportType) {
        this.reportType = reportType;
    }

    public String getReportTitle() {
        return reportTitle;
    }

    public void setReportTitle(String reportTitle) {
        this.reportTitle = reportTitle;
    }

    public LocalDate getReportDate() {
        return reportDate;
    }

    public void setReportDate(LocalDate reportDate) {
        this.reportDate = reportDate;
    }

    public LocalDate getPeriodStart() {
        return periodStart;
    }

    public void setPeriodStart(LocalDate periodStart) {
        this.periodStart = periodStart;
    }

    public LocalDate getPeriodEnd() {
        return periodEnd;
    }

    public void setPeriodEnd(LocalDate periodEnd) {
        this.periodEnd = periodEnd;
    }

    public BigDecimal getTotalAssets() {
        return totalAssets;
    }

    public void setTotalAssets(BigDecimal totalAssets) {
        this.totalAssets = totalAssets;
    }

    public BigDecimal getTotalDeposits() {
        return totalDeposits;
    }

    public void setTotalDeposits(BigDecimal totalDeposits) {
        this.totalDeposits = totalDeposits;
    }

    public BigDecimal getTotalLoans() {
        return totalLoans;
    }

    public void setTotalLoans(BigDecimal totalLoans) {
        this.totalLoans = totalLoans;
    }

    public BigDecimal getNetInterestIncome() {
        return netInterestIncome;
    }

    public void setNetInterestIncome(BigDecimal netInterestIncome) {
        this.netInterestIncome = netInterestIncome;
    }

    public Long getTransactionCount() {
        return transactionCount;
    }

    public void setTransactionCount(Long transactionCount) {
        this.transactionCount = transactionCount;
    }

    public BigDecimal getTotalTransactionVolume() {
        return totalTransactionVolume;
    }

    public void setTotalTransactionVolume(BigDecimal totalTransactionVolume) {
        this.totalTransactionVolume = totalTransactionVolume;
    }

    public Map<String, Double> getPortfolioAllocation() {
        return portfolioAllocation;
    }

    public void setPortfolioAllocation(Map<String, Double> portfolioAllocation) {
        this.portfolioAllocation = portfolioAllocation;
    }

    public Map<String, Map<String, BigDecimal>> getMonthlyTransactionPivot() {
        return monthlyTransactionPivot;
    }

    public void setMonthlyTransactionPivot(Map<String, Map<String, BigDecimal>> monthlyTransactionPivot) {
        this.monthlyTransactionPivot = monthlyTransactionPivot;
    }

    public List<DistributionBucket> getTransactionDistribution() {
        return transactionDistribution;
    }

    public void setTransactionDistribution(List<DistributionBucket> transactionDistribution) {
        this.transactionDistribution = transactionDistribution;
    }

    public List<CustomerRankingRow> getCustomerRankings() {
        return customerRankings;
    }

    public void setCustomerRankings(List<CustomerRankingRow> customerRankings) {
        this.customerRankings = customerRankings;
    }

    public String getAccountSummary() {
        return accountSummary;
    }

    public void setAccountSummary(String accountSummary) {
        this.accountSummary = accountSummary;
    }

}
