package com.meridianbank.exception;

/**
 * Thrown when attempting to create a customer that already exists
 * (duplicate email, SSN, or other unique constraint).
 */
public class DuplicateCustomerException extends MeridianBankException {

    private final String field;
    private final String value;

    public DuplicateCustomerException(String field, String value) {
        super("DUPLICATE_CUSTOMER",
              String.format("A customer with %s '%s' already exists", field, value),
              409);
        this.field = field;
        this.value = value;
    }

    public DuplicateCustomerException(String message) {
        super("DUPLICATE_CUSTOMER", message, 409);
        this.field = "unknown";
        this.value = "unknown";
    }

    public String getField() {
        return field;
    }

    public String getValue() {
        return value;
    }
}
