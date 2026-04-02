package com.meridianbank.annotation;

/**
 * Taxonomy domains for Oracle application-tier dependencies.
 * Maps to §2.1–2.10 of the Oracle Migration Framework Strategic Plan.
 */
public enum DependencyDomain {

    /** §2.1 — Oracle-specific SQL syntax embedded in application code */
    EMBEDDED_SQL,

    /** §2.2 — Calls to PL/SQL stored procedures, packages, REF CURSORs, Oracle UDTs */
    PLSQL_INVOCATION,

    /** §2.3 — JDBC driver, URL formats, DataSource classes, connection pools, TNS, Wallet */
    CONNECTIVITY_CONFIG,

    /** §2.4 — Hibernate dialect, JPA mappings, MyBatis handlers, Liquibase Oracle DDL */
    ORM_DATA_ACCESS,

    /** §2.9 — oracle.jdbc.*, oracle.sql.*, oracle.ucp.*, oracle.net.* Java APIs */
    ORACLE_JAVA_API,

    /** §2.10 — WebLogic DataSources, Oracle Coherence, Oracle Service Bus references */
    MIDDLEWARE
}
