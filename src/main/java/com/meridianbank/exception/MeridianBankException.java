package com.meridianbank.exception;

/**
 * Base application exception for the Meridian Bank application.
 * All domain-specific exceptions extend this class.
 */
public class MeridianBankException extends RuntimeException {

    private final String errorCode;
    private final int httpStatus;

    public MeridianBankException(String message) {
        super(message);
        this.errorCode = "MERIDIAN_ERROR";
        this.httpStatus = 500;
    }

    public MeridianBankException(String message, Throwable cause) {
        super(message, cause);
        this.errorCode = "MERIDIAN_ERROR";
        this.httpStatus = 500;
    }

    public MeridianBankException(String errorCode, String message, int httpStatus) {
        super(message);
        this.errorCode = errorCode;
        this.httpStatus = httpStatus;
    }

    public MeridianBankException(String errorCode, String message, int httpStatus, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
        this.httpStatus = httpStatus;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public int getHttpStatus() {
        return httpStatus;
    }
}
