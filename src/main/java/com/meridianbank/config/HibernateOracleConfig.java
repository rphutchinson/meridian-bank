package com.meridianbank.config;

import com.meridianbank.annotation.AutomationLevel;
import com.meridianbank.annotation.DependencyDomain;
import com.meridianbank.annotation.MigrationComplexity;
import com.meridianbank.annotation.OracleDependency;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.orm.jpa.JpaVendorAdapter;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;

import java.util.HashMap;
import java.util.Map;

/**
 * Hibernate JPA configuration for Oracle Database.
 *
 * <p>Configures Oracle-specific Hibernate settings: dialect, naming strategy,
 * sequence generation, and batch processing optimized for Oracle.
 *
 * <p>Migration: Replace {@code OracleDialect} with {@code PostgreSQLDialect} and
 * update any Oracle-specific naming or sequence strategies.
 */
@Configuration
@OracleDependency(
    domain          = DependencyDomain.ORM_DATA_ACCESS,
    category        = "ORACLE_DIALECT",
    description     = "Hibernate configuration uses OracleDialect and Oracle-specific JPA properties",
    complexity      = MigrationComplexity.LOW,
    automationLevel = AutomationLevel.FULL,
    migrationTarget = "Change dialect to PostgreSQLDialect; verify sequence strategy compatibility"
)
public class HibernateOracleConfig {

    /**
     * JPA vendor adapter configured for Oracle via Hibernate OracleDialect.
     * ORACLE-DEP [ORM_DATA_ACCESS:ORACLE_DIALECT] OracleDialect configuration
     */
    @Bean
    @OracleDependency(
        domain          = DependencyDomain.ORM_DATA_ACCESS,
        category        = "ORACLE_DIALECT",
        description     = "HibernateJpaVendorAdapter configured with OracleDialect",
        complexity      = MigrationComplexity.LOW,
        automationLevel = AutomationLevel.FULL,
        migrationTarget = "Set databasePlatform to PostgreSQLDialect"
    )
    public JpaVendorAdapter jpaVendorAdapter() {
        HibernateJpaVendorAdapter adapter = new HibernateJpaVendorAdapter();
        // ORACLE-DEP [ORM_DATA_ACCESS:ORACLE_DIALECT] Oracle Hibernate dialect
        adapter.setDatabasePlatform("org.hibernate.dialect.OracleDialect"); // ORACLE-DEP [ORM_DATA_ACCESS:ORACLE_DIALECT]
        adapter.setShowSql(false);
        adapter.setGenerateDdl(false);
        return adapter;
    }

    /**
     * Oracle-specific JPA/Hibernate properties.
     * All properties in this method are Oracle-specific.
     * ORACLE-DEP [ORM_DATA_ACCESS:ORACLE_DIALECT] Oracle Hibernate properties map
     */
    @Bean("oracleJpaProperties")
    @OracleDependency(
        domain          = DependencyDomain.ORM_DATA_ACCESS,
        category        = "ORACLE_DIALECT",
        description     = "Oracle-specific Hibernate JPA property set",
        complexity      = MigrationComplexity.LOW,
        automationLevel = AutomationLevel.FULL,
        migrationTarget = "Update dialect, sequence strategy, and remove Oracle-specific properties"
    )
    public Map<String, Object> jpaProperties() {
        Map<String, Object> properties = new HashMap<>();

        // ORACLE-DEP [ORM_DATA_ACCESS:ORACLE_DIALECT] Hibernate Oracle dialect
        properties.put("hibernate.dialect", "org.hibernate.dialect.OracleDialect"); // ORACLE-DEP [ORM_DATA_ACCESS:ORACLE_DIALECT]
        properties.put("hibernate.default_schema", "MERIDIAN");

        // ORACLE-DEP [ORM_DATA_ACCESS:SEQUENCE_GENERATOR] Oracle sequence ID generation
        properties.put("hibernate.id.new_generator_mappings", "true");  // ORACLE-DEP [ORM_DATA_ACCESS:SEQUENCE_GENERATOR]

        // Batch settings optimized for Oracle JDBC
        properties.put("hibernate.jdbc.batch_size", "50");
        properties.put("hibernate.jdbc.fetch_size", "100");
        properties.put("hibernate.order_inserts", "true");
        properties.put("hibernate.order_updates", "true");
        properties.put("hibernate.jdbc.batch_versioned_data", "true");

        // ORACLE-DEP [ORM_DATA_ACCESS:ORACLE_DIALECT] Oracle physical naming — respects Oracle 30-char limit
        properties.put("hibernate.physical_naming_strategy",
                       "org.hibernate.boot.model.naming.PhysicalNamingStrategyStandardImpl");

        // Schema validation
        properties.put("hibernate.hbm2ddl.auto", "validate");

        // Statistics (helpful for Oracle query tuning)
        properties.put("hibernate.generate_statistics", "false");
        properties.put("hibernate.session.events.log", "false");

        return properties;
    }
}
