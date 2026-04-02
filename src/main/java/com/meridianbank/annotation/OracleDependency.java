package com.meridianbank.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a class, method, field, or parameter as containing an Oracle-specific dependency.
 *
 * <p>Every Oracle-specific code point in Meridian Bank carries this annotation so that
 * the migration framework can discover all dependencies via runtime reflection and produce
 * a validated ground-truth report (see {@code DependencyReportGenerator}).
 *
 * <p>Usage example:
 * <pre>{@code
 * @OracleDependency(
 *     domain          = DependencyDomain.EMBEDDED_SQL,
 *     category        = "NVL_FUNCTION",
 *     description     = "Uses Oracle NVL() to default null phone to 'N/A'",
 *     complexity      = MigrationComplexity.LOW,
 *     automationLevel = AutomationLevel.FULL,
 *     migrationTarget = "Replace NVL(phone,'N/A') with COALESCE(phone,'N/A')"
 * )
 * public List<Customer> findActive() { ... }
 * }</pre>
 *
 * <p>Each annotated element should also carry an inline comment:
 * <pre>{@code
 * // ORACLE-DEP [EMBEDDED_SQL:NVL_FUNCTION] NVL() for null defaulting
 * }</pre>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({
    ElementType.TYPE,
    ElementType.METHOD,
    ElementType.FIELD,
    ElementType.PARAMETER,
    ElementType.CONSTRUCTOR
})
@Repeatable(OracleDependencies.class)
public @interface OracleDependency {

    /**
     * Taxonomy domain from the migration framework (§2.1–2.10).
     * Maps to {@link DependencyDomain}.
     */
    DependencyDomain domain();

    /**
     * Specific Oracle construct category within the domain.
     * Examples: "NVL_FUNCTION", "CONNECT_BY", "REF_CURSOR", "STRUCT_TYPE",
     * "JDBC_DRIVER", "ORACLE_DIALECT", "DBMS_CRYPTO".
     */
    String category();

    /**
     * Human-readable description of the Oracle dependency at this location.
     */
    String description();

    /**
     * Estimated migration complexity for this dependency.
     */
    MigrationComplexity complexity();

    /**
     * How much of the migration can be automated by the framework.
     */
    AutomationLevel automationLevel();

    /**
     * Recommended replacement or migration approach.
     * Should be a concrete, actionable description of the target construct.
     */
    String migrationTarget();
}
