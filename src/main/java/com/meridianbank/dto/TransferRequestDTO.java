package com.meridianbank.dto;

import java.math.BigDecimal;

/**
 * DTO for account-to-account transfer requests.
 */
public class TransferRequestDTO {

    private Long fromAccountId;
    private Long toAccountId;
    private BigDecimal amount;
    private String description;
    private String referenceNote;

    // Response fields
    private Long transactionId;
    private String referenceNumber;
    private String status;
    private BigDecimal fromAccountBalance;
    private BigDecimal toAccountBalance;
    public TransferRequestDTO() {
    }


    public Long getFromAccountId() {
        return fromAccountId;
    }

    public void setFromAccountId(Long fromAccountId) {
        this.fromAccountId = fromAccountId;
    }

    public Long getToAccountId() {
        return toAccountId;
    }

    public void setToAccountId(Long toAccountId) {
        this.toAccountId = toAccountId;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getReferenceNote() {
        return referenceNote;
    }

    public void setReferenceNote(String referenceNote) {
        this.referenceNote = referenceNote;
    }

    public Long getTransactionId() {
        return transactionId;
    }

    public void setTransactionId(Long transactionId) {
        this.transactionId = transactionId;
    }

    public String getReferenceNumber() {
        return referenceNumber;
    }

    public void setReferenceNumber(String referenceNumber) {
        this.referenceNumber = referenceNumber;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public BigDecimal getFromAccountBalance() {
        return fromAccountBalance;
    }

    public void setFromAccountBalance(BigDecimal fromAccountBalance) {
        this.fromAccountBalance = fromAccountBalance;
    }

    public BigDecimal getToAccountBalance() {
        return toAccountBalance;
    }

    public void setToAccountBalance(BigDecimal toAccountBalance) {
        this.toAccountBalance = toAccountBalance;
    }

}
