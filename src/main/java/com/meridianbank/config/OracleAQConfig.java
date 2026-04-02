package com.meridianbank.config;

import com.meridianbank.annotation.AutomationLevel;
import com.meridianbank.annotation.DependencyDomain;
import com.meridianbank.annotation.MigrationComplexity;
import com.meridianbank.annotation.OracleDependency;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import jakarta.jms.ConnectionFactory;
import jakarta.jms.QueueConnectionFactory;
import javax.sql.DataSource;

/**
 * Oracle Advanced Queuing (AQ) JMS configuration.
 *
 * <p>Oracle AQ provides database-integrated message queuing. The JMS ConnectionFactory
 * is obtained from Oracle's AQ JMS adapter, tying message queuing directly to the
 * Oracle database transaction context.
 *
 * <p>Migration: Replace Oracle AQ with RabbitMQ, Apache Kafka, or Amazon SQS.
 * The JMS API contract is similar, but the factory class and JNDI binding differ.
 */
@Configuration
@ConditionalOnProperty(name = "oracle.aq.enabled", havingValue = "true", matchIfMissing = false)
@OracleDependency(
    domain          = DependencyDomain.CONNECTIVITY_CONFIG,
    category        = "ORACLE_AQ",
    description     = "Oracle Advanced Queuing JMS configuration — database-integrated message queue",
    complexity      = MigrationComplexity.HIGH,
    automationLevel = AutomationLevel.PARTIAL,
    migrationTarget = "Replace with RabbitMQ, Kafka, or SQS JMS provider; update queue names and factory"
)
public class OracleAQConfig {

    @Value("${spring.datasource.url:jdbc:oracle:thin:@//localhost:1521/FREEPDB1}")
    private String jdbcUrl;

    @Value("${spring.datasource.username:MERIDIAN}")
    private String username;

    @Value("${spring.datasource.password:MeridianBank2026}")
    private String password;

    /**
     * Creates Oracle AQ JMS QueueConnectionFactory.
     *
     * <p>Uses oracle.jms.AQjmsFactory to create a factory backed by the Oracle database.
     * All JMS messages are stored in Oracle AQ queue tables and participate in Oracle transactions.
     *
     * <p>ORACLE-DEP [ORACLE_JAVA_API:ORACLE_AQ_JMS] oracle.jms.AQjmsFactory
     */
    @Bean("oracleAQConnectionFactory")
    @OracleDependency(
        domain          = DependencyDomain.ORACLE_JAVA_API,
        category        = "ORACLE_AQ_JMS",
        description     = "AQjmsFactory.getQueueConnectionFactory() — Oracle AQ JMS factory",
        complexity      = MigrationComplexity.HIGH,
        automationLevel = AutomationLevel.PARTIAL,
        migrationTarget = "Replace with standard JMS ConnectionFactory (RabbitMQ/ActiveMQ/SQS)"
    )
    public QueueConnectionFactory oracleAQConnectionFactory(DataSource dataSource) throws Exception {
        // ORACLE-DEP [ORACLE_JAVA_API:ORACLE_AQ_JMS] Oracle AQ JMS factory
        // oracle.jms.AQjmsFactory.getQueueConnectionFactory(dataSource, null)
        // Using reflection to avoid hard compile dependency when AQ jar not on classpath:
        Class<?> aqjmsFactory = Class.forName("oracle.jms.AQjmsFactory");
        return (QueueConnectionFactory) aqjmsFactory
            .getMethod("getQueueConnectionFactory", DataSource.class, java.util.Properties.class)
            .invoke(null, dataSource, null);
    }

    /**
     * Creates Oracle AQ JMS ConnectionFactory for pub/sub (topic) messaging.
     * ORACLE-DEP [ORACLE_JAVA_API:ORACLE_AQ_JMS] AQjmsFactory for topic connections
     */
    @Bean("oracleAQTopicConnectionFactory")
    @OracleDependency(
        domain          = DependencyDomain.ORACLE_JAVA_API,
        category        = "ORACLE_AQ_JMS",
        description     = "AQjmsFactory.getTopicConnectionFactory() — Oracle AQ JMS topic factory",
        complexity      = MigrationComplexity.HIGH,
        automationLevel = AutomationLevel.PARTIAL,
        migrationTarget = "Replace with standard JMS TopicConnectionFactory for publish-subscribe"
    )
    public ConnectionFactory oracleAQTopicConnectionFactory(DataSource dataSource) throws Exception {
        // ORACLE-DEP [ORACLE_JAVA_API:ORACLE_AQ_JMS] Oracle AQ topic factory
        Class<?> aqjmsFactory = Class.forName("oracle.jms.AQjmsFactory");
        return (ConnectionFactory) aqjmsFactory
            .getMethod("getTopicConnectionFactory", DataSource.class, java.util.Properties.class)
            .invoke(null, dataSource, null);
    }
}
