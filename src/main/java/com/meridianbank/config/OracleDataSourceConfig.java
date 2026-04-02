package com.meridianbank.config;

import com.meridianbank.annotation.AutomationLevel;
import com.meridianbank.annotation.DependencyDomain;
import com.meridianbank.annotation.MigrationComplexity;
import com.meridianbank.annotation.OracleDependency;
import oracle.jdbc.pool.OracleDataSource; // ORACLE-DEP [ORACLE_JAVA_API:ORACLE_DATA_SOURCE]
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.Properties;

/**
 * Configures an Oracle DataSource using oracle.jdbc.pool.OracleDataSource directly.
 *
 * <p>This class is an Oracle dependency in its entirety — every property set here
 * is Oracle-specific and has no equivalent for other JDBC drivers.
 * In the migrated application this entire class is replaced with standard
 * HikariCP configuration pointing at PostgreSQL.
 *
 * <p>Active only when {@code spring.datasource.oracle.direct=true} is set.
 * The default configuration uses HikariCP (application.yml hikari block).
 */
@Configuration
@ConditionalOnProperty(name = "spring.datasource.oracle.direct", havingValue = "true")
@OracleDependency(
    domain          = DependencyDomain.CONNECTIVITY_CONFIG,
    category        = "ORACLE_DATA_SOURCE",
    description     = "Entire class uses oracle.jdbc.pool.OracleDataSource — Oracle-proprietary DataSource implementation",
    complexity      = MigrationComplexity.LOW,
    automationLevel = AutomationLevel.FULL,
    migrationTarget = "Replace OracleDataSource with HikariDataSource pointing at PostgreSQL driver"
)
public class OracleDataSourceConfig {

    @Value("${spring.datasource.username:MERIDIAN}")
    private String username;

    @Value("${spring.datasource.password:MeridianBank2026}")
    private String password;

    @Value("${spring.datasource.url:jdbc:oracle:thin:@//localhost:1521/FREEPDB1}")
    private String jdbcUrl;

    /**
     * Creates a DataSource using oracle.jdbc.pool.OracleDataSource.
     *
     * <p>Demonstrates both SID-based and service-name-based URL construction,
     * plus Oracle-specific connection properties.
     */
    @Bean("oracleDirectDataSource")
    @OracleDependency(
        domain          = DependencyDomain.ORACLE_JAVA_API,
        category        = "ORACLE_DATA_SOURCE",
        description     = "oracle.jdbc.pool.OracleDataSource direct instantiation",
        complexity      = MigrationComplexity.LOW,
        automationLevel = AutomationLevel.FULL,
        migrationTarget = "Instantiate HikariDataSource or PGSimpleDataSource instead"
    )
    public DataSource oracleDataSource() throws SQLException {
        // ORACLE-DEP [ORACLE_JAVA_API:ORACLE_DATA_SOURCE] Oracle proprietary DataSource
        OracleDataSource dataSource = new OracleDataSource();

        // ORACLE-DEP [CONNECTIVITY_CONFIG:JDBC_URL] Oracle thin JDBC URL (service name format)
        dataSource.setURL(jdbcUrl);
        dataSource.setUser(username);
        dataSource.setPassword(password);

        // ORACLE-DEP [CONNECTIVITY_CONFIG:ORACLE_STMT_CACHE] Statement cache property
        dataSource.setImplicitCachingEnabled(true);              // ORACLE-DEP [CONNECTIVITY_CONFIG:ORACLE_STMT_CACHE]
        dataSource.setMaxStatements(50);

        // ORACLE-DEP [CONNECTIVITY_CONFIG:ORACLE_PROPERTIES] Oracle-specific connection properties
        Properties connProps = new Properties();
        connProps.setProperty("oracle.jdbc.defaultNChar", "false");               // ORACLE-DEP [CONNECTIVITY_CONFIG:ORACLE_NCHAR]
        connProps.setProperty("oracle.jdbc.implicitStatementCacheSize", "50");    // ORACLE-DEP [CONNECTIVITY_CONFIG:ORACLE_STMT_CACHE]
        connProps.setProperty("oracle.net.CONNECT_TIMEOUT", "10000");             // ORACLE-DEP [CONNECTIVITY_CONFIG:ORACLE_CONNECT_TIMEOUT]
        connProps.setProperty("oracle.jdbc.ReadTimeout", "60000");
        connProps.setProperty("oracle.net.disableOob", "true");
        dataSource.setConnectionProperties(connProps);

        return dataSource;
    }

    /**
     * Builds the Oracle JDBC URL in SID format (legacy / pre-12c style).
     * ORACLE-DEP [CONNECTIVITY_CONFIG:JDBC_URL_SID] SID-format JDBC URL
     */
    @OracleDependency(
        domain          = DependencyDomain.CONNECTIVITY_CONFIG,
        category        = "JDBC_URL_SID",
        description     = "Oracle SID-format JDBC URL construction: jdbc:oracle:thin:@host:port:SID",
        complexity      = MigrationComplexity.LOW,
        automationLevel = AutomationLevel.FULL,
        migrationTarget = "Replace with jdbc:postgresql://host:port/database"
    )
    public static String buildSidUrl(String host, int port, String sid) {
        // ORACLE-DEP [CONNECTIVITY_CONFIG:JDBC_URL_SID] SID-format URL
        return "jdbc:oracle:thin:@" + host + ":" + port + ":" + sid;
    }

    /**
     * Builds the Oracle JDBC URL in service name format (recommended).
     * ORACLE-DEP [CONNECTIVITY_CONFIG:JDBC_URL_SERVICE] Service-name JDBC URL
     */
    @OracleDependency(
        domain          = DependencyDomain.CONNECTIVITY_CONFIG,
        category        = "JDBC_URL_SERVICE",
        description     = "Oracle service-name JDBC URL: jdbc:oracle:thin:@//host:port/service",
        complexity      = MigrationComplexity.LOW,
        automationLevel = AutomationLevel.FULL,
        migrationTarget = "Replace with jdbc:postgresql://host:port/database"
    )
    public static String buildServiceUrl(String host, int port, String serviceName) {
        // ORACLE-DEP [CONNECTIVITY_CONFIG:JDBC_URL_SERVICE] Service-name format URL
        return "jdbc:oracle:thin:@//" + host + ":" + port + "/" + serviceName;
    }

    /**
     * Builds the Oracle JDBC URL using TNS descriptor format.
     * ORACLE-DEP [CONNECTIVITY_CONFIG:JDBC_URL_TNS] TNS descriptor inline URL
     */
    @OracleDependency(
        domain          = DependencyDomain.CONNECTIVITY_CONFIG,
        category        = "JDBC_URL_TNS",
        description     = "Oracle TNS inline descriptor URL: jdbc:oracle:thin:@(DESCRIPTION=...)",
        complexity      = MigrationComplexity.LOW,
        automationLevel = AutomationLevel.FULL,
        migrationTarget = "Replace with standard PostgreSQL connection string"
    )
    public static String buildTnsUrl(String host, int port, String serviceName) {
        // ORACLE-DEP [CONNECTIVITY_CONFIG:JDBC_URL_TNS] TNS inline descriptor
        return "jdbc:oracle:thin:@(DESCRIPTION=(ADDRESS=(PROTOCOL=TCP)" +
               "(HOST=" + host + ")(PORT=" + port + "))" +
               "(CONNECT_DATA=(SERVICE_NAME=" + serviceName + ")))";
    }
}
