package com.meridianbank.config;

import com.meridianbank.annotation.AutomationLevel;
import com.meridianbank.annotation.DependencyDomain;
import com.meridianbank.annotation.MigrationComplexity;
import com.meridianbank.annotation.OracleDependency;
import oracle.ucp.jdbc.PoolDataSource;           // ORACLE-DEP [ORACLE_JAVA_API:UCP]
import oracle.ucp.jdbc.PoolDataSourceFactory;    // ORACLE-DEP [ORACLE_JAVA_API:UCP]
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.sql.SQLException;

/**
 * Configures Oracle Universal Connection Pool (UCP) as the DataSource.
 *
 * <p>UCP is Oracle's proprietary high-performance connection pool optimized for Oracle RAC,
 * Fast Connection Failover (FCF), and Application Continuity. The entire class is an
 * Oracle dependency — migrated applications use HikariCP instead.
 *
 * <p>Active only when {@code spring.datasource.oracle.ucp=true} is set.
 */
@Configuration
@ConditionalOnProperty(name = "spring.datasource.oracle.ucp", havingValue = "true")
@OracleDependency(
    domain          = DependencyDomain.CONNECTIVITY_CONFIG,
    category        = "CONNECTION_POOL",
    description     = "Oracle Universal Connection Pool (UCP) — entire class is Oracle-specific",
    complexity      = MigrationComplexity.LOW,
    automationLevel = AutomationLevel.FULL,
    migrationTarget = "Replace with HikariCP: spring.datasource.hikari.* configuration"
)
@OracleDependency(
    domain          = DependencyDomain.ORACLE_JAVA_API,
    category        = "UCP",
    description     = "Imports oracle.ucp.jdbc.PoolDataSource and PoolDataSourceFactory",
    complexity      = MigrationComplexity.LOW,
    automationLevel = AutomationLevel.FULL,
    migrationTarget = "Remove oracle.ucp dependency; use com.zaxxer:HikariCP"
)
public class OracleUCPConfig {

    @Value("${spring.datasource.url:jdbc:oracle:thin:@//localhost:1521/FREEPDB1}")
    private String jdbcUrl;

    @Value("${spring.datasource.username:MERIDIAN}")
    private String username;

    @Value("${spring.datasource.password:MeridianBank2026}")
    private String password;

    /**
     * Creates an Oracle UCP PoolDataSource.
     *
     * <p>Every property set on the PoolDataSource is Oracle UCP-specific and has
     * no direct equivalent in HikariCP. The naming and semantics differ.
     */
    @Bean("ucpDataSource")
    @OracleDependency(
        domain          = DependencyDomain.ORACLE_JAVA_API,
        category        = "UCP",
        description     = "PoolDataSourceFactory.getPoolDataSource() — Oracle UCP factory method",
        complexity      = MigrationComplexity.LOW,
        automationLevel = AutomationLevel.FULL,
        migrationTarget = "new HikariDataSource(hikariConfig)"
    )
    public DataSource ucpDataSource() throws SQLException {
        // ORACLE-DEP [ORACLE_JAVA_API:UCP] Oracle UCP PoolDataSourceFactory
        PoolDataSource pds = PoolDataSourceFactory.getPoolDataSource();  // ORACLE-DEP [ORACLE_JAVA_API:UCP]

        // ORACLE-DEP [CONNECTIVITY_CONFIG:JDBC_DRIVER] Oracle JDBC driver class
        pds.setConnectionFactoryClassName("oracle.jdbc.pool.OracleDataSource"); // ORACLE-DEP [CONNECTIVITY_CONFIG:JDBC_DRIVER]

        // ORACLE-DEP [CONNECTIVITY_CONFIG:JDBC_URL] Oracle JDBC URL
        pds.setURL(jdbcUrl);          // ORACLE-DEP [CONNECTIVITY_CONFIG:JDBC_URL]
        pds.setUser(username);
        pds.setPassword(password);

        pds.setConnectionPoolName("MeridianUCPPool");

        // ORACLE-DEP [CONNECTIVITY_CONFIG:CONNECTION_POOL] Oracle UCP pool sizing properties
        pds.setInitialPoolSize(5);          // ORACLE-DEP [CONNECTIVITY_CONFIG:ORACLE_UCP_POOL_SIZE]
        pds.setMinPoolSize(5);
        pds.setMaxPoolSize(20);

        // ORACLE-DEP [CONNECTIVITY_CONFIG:ORACLE_UCP_TIMEOUT] UCP connection timeout properties
        pds.setConnectionWaitTimeout(30);           // ORACLE-DEP [CONNECTIVITY_CONFIG:ORACLE_UCP_TIMEOUT]
        pds.setInactiveConnectionTimeout(300);
        pds.setAbandonedConnectionTimeout(600);
        pds.setTimeToLiveConnectionTimeout(1800);

        // ORACLE-DEP [CONNECTIVITY_CONFIG:ORACLE_VALIDATION] Oracle validation query using DUAL
        pds.setSQLForValidateConnection("SELECT 1 FROM DUAL"); // ORACLE-DEP [EMBEDDED_SQL:DUAL_TABLE]

        pds.setValidateConnectionOnBorrow(true);

        // ORACLE-DEP [CONNECTIVITY_CONFIG:ORACLE_UCP_HARVEST] Connection harvest properties (UCP-specific)
        pds.setMaxConnectionReuseTime(900);  // ORACLE-DEP [CONNECTIVITY_CONFIG:ORACLE_UCP_HARVEST]

        return pds;
    }
}
