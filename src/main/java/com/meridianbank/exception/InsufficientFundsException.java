package com.meridianbank.exception;

import java.math.BigDecimal;

/**
 * Thrown when a debit or transfer exceeds available balance including overdraft.
 */
public class InsufficientFundsException extends MeridianBankException {

    private final Long accountId;
    private final BigDecimal requestedAmount;
    private final BigDecimal availableBalance;

    public InsufficientFundsException(Long accountId,
                                       BigDecimal requestedAmount,
                                       BigDecimal availableBalance) {
        super("INSUFFICIENT_FUNDS",
              String.format("Insufficient funds in account %d: requested %.2f, available %.2f",
                            accountId, requestedAmount, availableBalance),
              422);
        this.accountId = accountId;
        this.requestedAmount = requestedAmount;
        this.availableBalance = availableBalance;
    }

    public Long getAccountId() {
        return accountId;
    }

    public BigDecimal getRequestedAmount() {
        return requestedAmount;
    }

    public BigDecimal getAvailableBalance() {
        return availableBalance;
    }

    public BigDecimal getShortfall() {
        return requestedAmount.subtract(availableBalance);
    }
}
