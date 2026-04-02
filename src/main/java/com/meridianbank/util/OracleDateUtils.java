package com.meridianbank.util;

import com.meridianbank.annotation.AutomationLevel;
import com.meridianbank.annotation.DependencyDomain;
import com.meridianbank.annotation.MigrationComplexity;
import com.meridianbank.annotation.OracleDependency;
import oracle.sql.TIMESTAMP;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.sql.Date;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;

/**
 * Java implementations of Oracle-specific date operations.
 *
 * Oracle's date functions have subtly different semantics from Java's date API:
 *   - ADD_MONTHS() handles end-of-month dates differently from LocalDate.plusMonths()
 *   - MONTHS_BETWEEN() computes a fractional month count using a 31-day divisor
 *   - TRUNC(date) truncates to midnight; TRUNC(date, 'MM') truncates to first of month
 *   - Oracle DATE type stores both date and time (unlike SQL DATE which is date only)
 *   - oracle.sql.TIMESTAMP provides nanosecond precision beyond java.sql.Timestamp
 *
 * ORACLE-DEP [EMBEDDED_SQL:ADD_MONTHS]      Oracle ADD_MONTHS() semantics
 * ORACLE-DEP [EMBEDDED_SQL:MONTHS_BETWEEN]  Oracle MONTHS_BETWEEN() fractional months
 * ORACLE-DEP [EMBEDDED_SQL:TRUNC_DATE]      Oracle TRUNC(date) and TRUNC(date,'MM')
 * ORACLE-DEP [ORACLE_JAVA_API:ORACLE_DATE]      Oracle DATE has time component
 * ORACLE-DEP [ORACLE_JAVA_API:ORACLE_TIMESTAMP] oracle.sql.TIMESTAMP nanosecond precision
 */
@Component
// ORACLE-DEP [EMBEDDED_SQL:ADD_MONTHS] Utility implementing Oracle-specific date function semantics
@OracleDependency(
        domain = DependencyDomain.EMBEDDED_SQL,
        category = "ADD_MONTHS",
        description = "Utility class implementing Oracle-specific date function semantics in Java",
        complexity = MigrationComplexity.MEDIUM,
        automationLevel = AutomationLevel.PARTIAL,
        migrationTarget = "Use standard Java date arithmetic; review Oracle end-of-month edge cases"
)
public class OracleDateUtils {

    private static final Logger log = LoggerFactory.getLogger(OracleDateUtils.class);

    // -----------------------------------------------------------------------
    // Oracle ADD_MONTHS equivalent
    // -----------------------------------------------------------------------

    /**
     * Java implementation of Oracle ADD_MONTHS(date, n).
     *
     * Oracle ADD_MONTHS end-of-month rule:
     *   If the input date is the last day of the month, the result is always the
     *   last day of the result month.
     *   e.g. ADD_MONTHS('31-JAN-2026', 1) = '28-FEB-2026'  (not '03-MAR-2026')
     *        ADD_MONTHS('29-FEB-2024', 12) = '28-FEB-2025' (not '01-MAR-2025')
     *
     * ORACLE-DEP [EMBEDDED_SQL:ADD_MONTHS] Oracle ADD_MONTHS end-of-month handling
     */
    @OracleDependency(
            domain = DependencyDomain.EMBEDDED_SQL,
            category = "ADD_MONTHS",
            description = "Implements Oracle ADD_MONTHS end-of-month semantics",
            complexity = MigrationComplexity.LOW,
            automationLevel = AutomationLevel.FULL,
            migrationTarget = "Replace ADD_MONTHS(date, n) with date + INTERVAL 'n months' in PostgreSQL"
    )
    public LocalDate addMonths(LocalDate date, int months) {
        if (date == null) {
            throw new IllegalArgumentException("Date must not be null");
        }
        // ORACLE-DEP [EMBEDDED_SQL:ADD_MONTHS] Oracle handles end-of-month differently from Java plusMonths()
        boolean isLastDayOfMonth = date.equals(date.with(TemporalAdjusters.lastDayOfMonth()));
        LocalDate result = date.plusMonths(months);
        if (isLastDayOfMonth) {
            // Oracle ADD_MONTHS: if input is last day of month, output is also last day of month
            result = result.with(TemporalAdjusters.lastDayOfMonth());
        }
        log.trace("addMonths({}, {}) = {}", date, months, result);
        return result;
    }

    // -----------------------------------------------------------------------
    // Oracle MONTHS_BETWEEN equivalent
    // -----------------------------------------------------------------------

    /**
     * Java implementation of Oracle MONTHS_BETWEEN(date1, date2).
     *
     * Oracle MONTHS_BETWEEN uses 31 as the standard month length for fractions.
     * Result is positive if date1 > date2, negative if date1 < date2.
     *
     * e.g. MONTHS_BETWEEN('15-APR-2026', '15-JAN-2026') = 3.0
     *      MONTHS_BETWEEN('30-APR-2026', '15-JAN-2026') = 3.5
     *
     * ORACLE-DEP [EMBEDDED_SQL:MONTHS_BETWEEN] Oracle MONTHS_BETWEEN fractional month calculation
     */
    @OracleDependency(
            domain = DependencyDomain.EMBEDDED_SQL,
            category = "MONTHS_BETWEEN",
            description = "Implements Oracle MONTHS_BETWEEN fractional month calculation",
            complexity = MigrationComplexity.MEDIUM,
            automationLevel = AutomationLevel.PARTIAL,
            migrationTarget = "Replace with DATE_PART/EXTRACT age calculation in PostgreSQL; verify fraction semantics"
    )
    public double monthsBetween(LocalDate date1, LocalDate date2) {
        if (date1 == null || date2 == null) {
            throw new IllegalArgumentException("Dates must not be null");
        }
        int yearDiff  = date1.getYear() - date2.getYear();
        int monthDiff = date1.getMonthValue() - date2.getMonthValue();
        int totalMonths = yearDiff * 12 + monthDiff;

        boolean date1IsLastDay = date1.equals(date1.with(TemporalAdjusters.lastDayOfMonth()));
        boolean date2IsLastDay = date2.equals(date2.with(TemporalAdjusters.lastDayOfMonth()));

        double fraction;
        if (date1IsLastDay && date2IsLastDay) {
            fraction = 0.0;
        } else if (date1.getDayOfMonth() == date2.getDayOfMonth()) {
            fraction = 0.0;
        } else {
            // ORACLE-DEP [EMBEDDED_SQL:MONTHS_BETWEEN] Oracle uses 31 as standard month length for fractional days
            fraction = (double) (date1.getDayOfMonth() - date2.getDayOfMonth()) / 31.0;
        }
        double result = totalMonths + fraction;
        log.trace("monthsBetween({}, {}) = {}", date1, date2, result);
        return result;
    }

    // -----------------------------------------------------------------------
    // Oracle TRUNC(date) equivalents
    // -----------------------------------------------------------------------

    /**
     * Java equivalent of Oracle TRUNC(date) — truncates to start of day (midnight).
     *
     * ORACLE-DEP [EMBEDDED_SQL:TRUNC_DATE] Oracle TRUNC(date) truncation to day boundary
     */
    @OracleDependency(
            domain = DependencyDomain.EMBEDDED_SQL,
            category = "TRUNC_DATE",
            description = "Implements Oracle TRUNC(date) truncation to start of day",
            complexity = MigrationComplexity.LOW,
            automationLevel = AutomationLevel.FULL,
            migrationTarget = "Replace TRUNC(date) with DATE_TRUNC('day', date) in PostgreSQL"
    )
    public LocalDate trunc(LocalDate date) {
        if (date == null) {
            throw new IllegalArgumentException("Date must not be null");
        }
        // ORACLE-DEP [EMBEDDED_SQL:TRUNC_DATE] Oracle TRUNC(date) = truncate to day boundary
        return date; // LocalDate has no time component; already at day boundary
    }

    /**
     * Java equivalent of Oracle TRUNC(date, 'MM') — truncates to first day of month.
     *
     * e.g. TRUNC(DATE '2026-03-15', 'MM') = DATE '2026-03-01'
     *
     * ORACLE-DEP [EMBEDDED_SQL:TRUNC_DATE] Oracle TRUNC(date, 'MM') truncation to month boundary
     */
    @OracleDependency(
            domain = DependencyDomain.EMBEDDED_SQL,
            category = "TRUNC_DATE",
            description = "Implements Oracle TRUNC(date, 'MM') truncation to first day of month",
            complexity = MigrationComplexity.LOW,
            automationLevel = AutomationLevel.FULL,
            migrationTarget = "Replace TRUNC(date,'MM') with DATE_TRUNC('month', date) in PostgreSQL"
    )
    public LocalDate truncToMonth(LocalDate date) {
        if (date == null) {
            throw new IllegalArgumentException("Date must not be null");
        }
        // ORACLE-DEP [EMBEDDED_SQL:TRUNC_DATE] Oracle TRUNC(date,'MM') returns first day of month
        return date.with(TemporalAdjusters.firstDayOfMonth());
    }

    // -----------------------------------------------------------------------
    // Oracle DATE / TIMESTAMP type conversions
    // -----------------------------------------------------------------------

    /**
     * Converts a Java LocalDate to a java.sql.Date for Oracle DATE column binding.
     *
     * Oracle DATE stores both date AND time (unlike SQL DATE which is date-only).
     * When storing a LocalDate, the time defaults to 00:00:00.
     *
     * ORACLE-DEP [ORACLE_JAVA_API:ORACLE_DATE] Oracle DATE has time component unlike SQL DATE
     */
    @OracleDependency(
            domain = DependencyDomain.ORACLE_JAVA_API,
            category = "ORACLE_DATE",
            description = "Converts LocalDate to java.sql.Date noting Oracle DATE stores time component",
            complexity = MigrationComplexity.LOW,
            automationLevel = AutomationLevel.FULL,
            migrationTarget = "Use standard PreparedStatement.setDate() for PostgreSQL DATE columns"
    )
    public Date toOracleDate(LocalDate javaDate) {
        if (javaDate == null) {
            return null;
        }
        // ORACLE-DEP [ORACLE_JAVA_API:ORACLE_DATE] Oracle DATE has time; java.sql.Date maps to midnight 00:00:00
        return Date.valueOf(javaDate);
    }

    /**
     * Converts a Java LocalDateTime to oracle.sql.TIMESTAMP.
     *
     * oracle.sql.TIMESTAMP provides nanosecond precision and is the correct type
     * for Oracle TIMESTAMP WITH TIME ZONE columns.
     *
     * ORACLE-DEP [ORACLE_JAVA_API:ORACLE_TIMESTAMP] oracle.sql.TIMESTAMP for nanosecond precision
     */
    @OracleDependency(
            domain = DependencyDomain.ORACLE_JAVA_API,
            category = "ORACLE_TIMESTAMP",
            description = "Converts LocalDateTime to oracle.sql.TIMESTAMP for nanosecond precision",
            complexity = MigrationComplexity.MEDIUM,
            automationLevel = AutomationLevel.FULL,
            migrationTarget = "Replace with standard PreparedStatement.setTimestamp() for PostgreSQL TIMESTAMP"
    )
    public TIMESTAMP toOracleTimestamp(LocalDateTime javaDateTime) throws SQLException {
        if (javaDateTime == null) {
            return null;
        }
        // ORACLE-DEP [ORACLE_JAVA_API:ORACLE_TIMESTAMP] oracle.sql.TIMESTAMP wraps java.sql.Timestamp
        Timestamp sqlTimestamp = Timestamp.valueOf(javaDateTime);
        // ORACLE-DEP [ORACLE_JAVA_API:ORACLE_TIMESTAMP] new oracle.sql.TIMESTAMP preserves nanosecond precision
        return new TIMESTAMP(sqlTimestamp);
    }

    /**
     * Converts oracle.sql.TIMESTAMP back to a Java LocalDateTime.
     *
     * ORACLE-DEP [ORACLE_JAVA_API:ORACLE_TIMESTAMP] oracle.sql.TIMESTAMP.timestampValue() conversion
     */
    @OracleDependency(
            domain = DependencyDomain.ORACLE_JAVA_API,
            category = "ORACLE_TIMESTAMP",
            description = "Converts oracle.sql.TIMESTAMP to LocalDateTime via timestampValue()",
            complexity = MigrationComplexity.LOW,
            automationLevel = AutomationLevel.FULL,
            migrationTarget = "Replace with standard ResultSet.getTimestamp().toLocalDateTime()"
    )
    public LocalDateTime fromOracleTimestamp(TIMESTAMP ts) throws SQLException {
        if (ts == null) {
            return null;
        }
        // ORACLE-DEP [ORACLE_JAVA_API:ORACLE_TIMESTAMP] TIMESTAMP.timestampValue() converts to java.sql.Timestamp
        return ts.timestampValue().toLocalDateTime();
    }

    // -----------------------------------------------------------------------
    // Oracle TO_CHAR date formatting equivalent
    // -----------------------------------------------------------------------

    /**
     * Java equivalent of Oracle TO_CHAR(date, format).
     *
     * Common Oracle date format models:
     *   'DD-MON-YYYY'           → "dd-MMM-yyyy"   e.g. 15-APR-2026
     *   'YYYY-MM-DD'            → "yyyy-MM-dd"
     *   'YYYY-MM-DD HH24:MI:SS' → "yyyy-MM-dd HH:mm:ss"
     *   'DD/MM/YYYY'            → "dd/MM/yyyy"
     *
     * ORACLE-DEP [EMBEDDED_SQL:TO_CHAR_DATE] Oracle TO_CHAR date format model equivalent
     */
    @OracleDependency(
            domain = DependencyDomain.EMBEDDED_SQL,
            category = "TO_CHAR_DATE",
            description = "Implements Oracle TO_CHAR(date, format) using Java DateTimeFormatter",
            complexity = MigrationComplexity.LOW,
            automationLevel = AutomationLevel.PARTIAL,
            migrationTarget = "Replace TO_CHAR(date, format) with TO_CHAR(date, format) in PostgreSQL (same syntax)"
    )
    public String toOracleChar(LocalDate date, String oracleFormat) {
        if (date == null) {
            return null;
        }
        String fmt = (oracleFormat == null || oracleFormat.isEmpty()) ? "YYYY-MM-DD" : oracleFormat;
        // ORACLE-DEP [EMBEDDED_SQL:TO_CHAR_DATE] Translate Oracle TO_CHAR format model to Java DateTimeFormatter
        String javaPattern = translateOracleDateFormat(fmt);
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern(javaPattern);
        return date.format(formatter);
    }

    /**
     * Translates an Oracle date format model string to a Java DateTimeFormatter pattern.
     */
    private String translateOracleDateFormat(String oracleFormat) {
        // ORACLE-DEP [EMBEDDED_SQL:TO_CHAR_DATE] Oracle format model token translation to Java pattern
        return oracleFormat.toUpperCase()
                .replace("YYYY",  "yyyy")
                .replace("MONTH", "MMMM")
                .replace("MON",   "MMM")
                .replace("MM",    "MM")
                .replace("DD",    "dd")
                .replace("HH24",  "HH")
                .replace("HH12",  "hh")
                .replace("HH",    "hh")
                .replace("MI",    "mm")
                .replace("SS",    "ss")
                .replace("AM",    "a")
                .replace("PM",    "a");
    }
}
