package com.meridianbank.config;

import com.meridianbank.annotation.AutomationLevel;
import com.meridianbank.annotation.DependencyDomain;
import com.meridianbank.annotation.MigrationComplexity;
import com.meridianbank.annotation.OracleDependency;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import javax.naming.Context;
import javax.naming.InitialContext;
import javax.naming.NamingException;
import javax.sql.DataSource;
import java.util.Properties;

/**
 * Demonstrates WebLogic JNDI DataSource lookup patterns used in Oracle WebLogic deployments.
 *
 * <p>In enterprise Oracle shops, applications typically obtain their DataSource from
 * WebLogic's JNDI tree rather than configuring a connection pool in the application.
 * This class shows that pattern for migration assessment purposes.
 *
 * <p>Active only under the {@code weblogic} Spring profile — not used in standalone mode.
 *
 * <p>Migration: Remove entirely. Spring Boot manages its own connection pool.
 * No JNDI lookup needed when deploying as a standalone Spring Boot application.
 */
@Configuration
@Profile("weblogic")
@ConditionalOnProperty(name = "deployment.weblogic", havingValue = "true")
@OracleDependency(
    domain          = DependencyDomain.MIDDLEWARE,
    category        = "WEBLOGIC_JNDI",
    description     = "WebLogic JNDI DataSource lookup pattern for Oracle-bound DataSources",
    complexity      = MigrationComplexity.MEDIUM,
    automationLevel = AutomationLevel.PARTIAL,
    migrationTarget = "Remove class; Spring Boot manages its own HikariCP pool — no JNDI needed"
)
public class WebLogicCompatConfig {

    private static final Logger log = LoggerFactory.getLogger(WebLogicCompatConfig.class);

    // ORACLE-DEP [MIDDLEWARE:WEBLOGIC_JNDI] Standard WebLogic JNDI DataSource name
    private static final String WL_JNDI_DS_NAME = "jdbc/MeridianOracleDataSource"; // ORACLE-DEP [MIDDLEWARE:WEBLOGIC_JNDI]

    /**
     * Looks up a DataSource from WebLogic's JNDI tree.
     *
     * <p>In WebLogic, Oracle DataSources are configured in the console and bound
     * to JNDI. The application looks them up by name, completely hiding the
     * Oracle driver and connection pool configuration.
     *
     * ORACLE-DEP [MIDDLEWARE:WEBLOGIC_JNDI] JNDI lookup for Oracle DataSource
     */
    @Bean("weblogicDataSource")
    @OracleDependency(
        domain          = DependencyDomain.MIDDLEWARE,
        category        = "WEBLOGIC_JNDI",
        description     = "InitialContext JNDI lookup for WebLogic-bound Oracle DataSource",
        complexity      = MigrationComplexity.MEDIUM,
        automationLevel = AutomationLevel.PARTIAL,
        migrationTarget = "Remove; use spring.datasource.* properties to configure HikariCP directly"
    )
    public DataSource weblogicJndiDataSource() throws NamingException {
        // ORACLE-DEP [MIDDLEWARE:WEBLOGIC_JNDI] WebLogic InitialContext properties
        Properties env = new Properties();
        env.put(Context.INITIAL_CONTEXT_FACTORY,
                "weblogic.jndi.WLInitialContextFactory");  // ORACLE-DEP [MIDDLEWARE:WEBLOGIC_JNDI]
        env.put(Context.PROVIDER_URL, "t3://localhost:7001");

        InitialContext ctx = new InitialContext(env);

        // ORACLE-DEP [MIDDLEWARE:WEBLOGIC_JNDI] JNDI lookup of Oracle DataSource
        DataSource ds = (DataSource) ctx.lookup(WL_JNDI_DS_NAME); // ORACLE-DEP [MIDDLEWARE:WEBLOGIC_JNDI]
        log.info("WebLogic JNDI DataSource obtained: {}", WL_JNDI_DS_NAME);
        return ds;
    }

    /**
     * Alternative JNDI lookup using the standard javax.naming pattern
     * with application server context references.
     *
     * ORACLE-DEP [MIDDLEWARE:WEBLOGIC_DESCRIPTOR] weblogic.xml resource-ref mapping
     */
    @OracleDependency(
        domain          = DependencyDomain.MIDDLEWARE,
        category        = "WEBLOGIC_DESCRIPTOR",
        description     = "java:comp/env JNDI lookup pattern mapped via weblogic.xml resource-ref",
        complexity      = MigrationComplexity.MEDIUM,
        automationLevel = AutomationLevel.PARTIAL,
        migrationTarget = "Remove weblogic.xml; use Spring Boot auto-configuration"
    )
    public DataSource javaCompEnvLookup() throws NamingException {
        // ORACLE-DEP [MIDDLEWARE:WEBLOGIC_DESCRIPTOR] java:comp/env JNDI lookup (weblogic.xml maps this)
        InitialContext ctx = new InitialContext();
        return (DataSource) ctx.lookup("java:comp/env/jdbc/MeridianDS"); // ORACLE-DEP [MIDDLEWARE:WEBLOGIC_JNDI]
    }
}
