package com.meridianbank.annotation;

/**
 * Migration complexity score for an Oracle dependency.
 * Drives effort estimation in the assessment report.
 */
public enum MigrationComplexity {

    /** Direct 1:1 syntactic replacement. e.g. NVL() → COALESCE(), driver class swap */
    LOW,

    /** Structural transformation with same semantics.
     *  e.g. CONNECT BY → recursive CTE, sequence syntax change, (+) → ANSI join */
    MEDIUM,

    /** Requires understanding business intent.
     *  e.g. PL/SQL block with exception handling → app-tier logic, complex MERGE */
    HIGH,

    /** Architectural re-design required.
     *  e.g. Oracle Forms → React, ADF task flow → React Router, Oracle Reports → reporting solution */
    VERY_HIGH
}
