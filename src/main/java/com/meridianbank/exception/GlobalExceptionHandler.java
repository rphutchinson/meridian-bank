package com.meridianbank.exception;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.sql.SQLException;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Global exception handler for the Meridian Bank REST API.
 * Converts application and database exceptions to structured JSON error responses.
 *
 * Oracle-specific handling: DataAccessException wrapping Oracle SQLExceptions
 * is intercepted here and delegated to OracleErrorTranslator for ORA- code mapping.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private final OracleErrorTranslator oracleErrorTranslator;

    public GlobalExceptionHandler(OracleErrorTranslator oracleErrorTranslator) {
        this.oracleErrorTranslator = oracleErrorTranslator;
    }

    /**
     * Handle all typed Meridian Bank application exceptions.
     */
    @ExceptionHandler(MeridianBankException.class)
    public ResponseEntity<ErrorResponse> handleMeridianBankException(MeridianBankException ex) {
        log.warn("Application exception [{}]: {}", ex.getErrorCode(), ex.getMessage());
        ErrorResponse error = new ErrorResponse(
            ex.getErrorCode(),
            ex.getMessage(),
            ex.getHttpStatus()
        );
        return ResponseEntity.status(ex.getHttpStatus()).body(error);
    }

    /**
     * Handle Spring validation failures (@Valid, @Validated).
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidationException(MethodArgumentNotValidException ex) {
        String fieldErrors = ex.getBindingResult()
            .getFieldErrors()
            .stream()
            .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
            .collect(Collectors.joining("; "));
        log.warn("Validation failure: {}", fieldErrors);
        ErrorResponse error = new ErrorResponse("VALIDATION_ERROR", "Validation failed: " + fieldErrors, 400);
        return ResponseEntity.badRequest().body(error);
    }

    /**
     * Handle missing request parameters.
     */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ErrorResponse> handleMissingParam(MissingServletRequestParameterException ex) {
        ErrorResponse error = new ErrorResponse(
            "MISSING_PARAMETER",
            "Required parameter '" + ex.getParameterName() + "' is missing",
            400
        );
        return ResponseEntity.badRequest().body(error);
    }

    /**
     * Handle type mismatch (e.g., string where long expected in path variable).
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        ErrorResponse error = new ErrorResponse(
            "INVALID_PARAMETER",
            "Parameter '" + ex.getName() + "' has invalid value: " + ex.getValue(),
            400
        );
        return ResponseEntity.badRequest().body(error);
    }

    /**
     * Handle Spring DataIntegrityViolationException — often wraps Oracle ORA-00001 unique constraint.
     * Delegates to OracleErrorTranslator for Oracle-specific code translation.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ErrorResponse> handleDataIntegrityViolation(DataIntegrityViolationException ex) {
        Throwable rootCause = ex.getRootCause();
        if (rootCause instanceof SQLException sqlEx) {
            // ORACLE-DEP [EMBEDDED_SQL:ORACLE_ERROR_CODES] Oracle SQLException translation path
            log.warn("Oracle constraint violation ORA-{}: {}", sqlEx.getErrorCode(), sqlEx.getMessage());
            MeridianBankException translated = oracleErrorTranslator.translate("data integrity check", sqlEx);
            ErrorResponse error = new ErrorResponse(
                translated.getErrorCode(),
                translated.getMessage(),
                translated.getHttpStatus()
            );
            return ResponseEntity.status(translated.getHttpStatus()).body(error);
        }
        log.warn("Data integrity violation: {}", ex.getMessage());
        ErrorResponse error = new ErrorResponse(
            "DATA_INTEGRITY_ERROR",
            "Data integrity constraint violated",
            409
        );
        return ResponseEntity.status(409).body(error);
    }

    /**
     * Handle generic Spring DataAccessException — wraps Oracle-specific JDBC errors.
     */
    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<ErrorResponse> handleDataAccessException(DataAccessException ex) {
        Throwable cause = ex.getCause();
        if (cause instanceof SQLException sqlEx) {
            // ORACLE-DEP [EMBEDDED_SQL:ORACLE_ERROR_CODES] Oracle error code translation for DAO errors
            log.error("Oracle JDBC error ORA-{}: {}", sqlEx.getErrorCode(), sqlEx.getMessage());
            MeridianBankException translated = oracleErrorTranslator.translate("database operation", sqlEx);
            if (oracleErrorTranslator.isRetryable(sqlEx)) {
                ErrorResponse error = new ErrorResponse(
                    translated.getErrorCode(),
                    translated.getMessage() + " (this operation can be retried)",
                    503
                );
                return ResponseEntity.status(503).body(error);
            }
            ErrorResponse error = new ErrorResponse(
                translated.getErrorCode(),
                translated.getMessage(),
                translated.getHttpStatus()
            );
            return ResponseEntity.status(translated.getHttpStatus()).body(error);
        }
        log.error("Unexpected data access error: {}", ex.getMessage(), ex);
        ErrorResponse error = new ErrorResponse(
            "DATABASE_ERROR",
            "An unexpected database error occurred",
            500
        );
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
    }

    /**
     * Catch-all for unhandled exceptions.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGenericException(Exception ex) {
        log.error("Unhandled exception: {}", ex.getMessage(), ex);
        ErrorResponse error = new ErrorResponse(
            "INTERNAL_ERROR",
            "An unexpected error occurred. Please contact support.",
            500
        );
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
    }

    /**
     * Structured JSON error response body.
     */
    public static class ErrorResponse {
        private final String errorCode;
        private final String message;
        private final int status;
        private final String timestamp;

        public ErrorResponse(String errorCode, String message, int status) {
            this.errorCode = errorCode;
            this.message = message;
            this.status = status;
            this.timestamp = Instant.now().toString();
        }

        public String getErrorCode() { return errorCode; }
        public String getMessage() { return message; }
        public int getStatus() { return status; }
        public String getTimestamp() { return timestamp; }
    }
}
