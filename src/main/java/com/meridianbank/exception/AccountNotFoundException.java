package com.meridianbank.exception;

/**
 * Thrown when an account cannot be found by ID or account number.
 */
public class AccountNotFoundException extends MeridianBankException {

    private final Long accountId;
    private final String accountNumber;

    public AccountNotFoundException(Long accountId) {
        super("ACCOUNT_NOT_FOUND",
              "Account not found with ID: " + accountId,
              404);
        this.accountId = accountId;
        this.accountNumber = null;
    }

    public AccountNotFoundException(String accountNumber) {
        super("ACCOUNT_NOT_FOUND",
              "Account not found with number: " + accountNumber,
              404);
        this.accountId = null;
        this.accountNumber = accountNumber;
    }

    public Long getAccountId() {
        return accountId;
    }

    public String getAccountNumber() {
        return accountNumber;
    }
}
