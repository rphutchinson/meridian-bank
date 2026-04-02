package com.meridianbank.config;

import com.meridianbank.annotation.AutomationLevel;
import com.meridianbank.annotation.DependencyDomain;
import com.meridianbank.annotation.MigrationComplexity;
import com.meridianbank.annotation.OracleDependency;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;

import jakarta.annotation.PostConstruct;

/**
 * Oracle Advanced Security / Oracle Net encryption configuration.
 *
 * <p>Configures Oracle Network Encryption (ANO — Advanced Networking Option) and
 * Oracle Data Integrity checking for the JDBC connection. These are Oracle-proprietary
 * features that encrypt the wire protocol between the JDBC driver and Oracle Database
 * without requiring SSL/TLS certificates.
 *
 * <p>Active only when {@code oracle.security.enabled=true} is set.
 *
 * <p>Migration: Replace Oracle ANO with standard TLS/SSL configuration.
 * PostgreSQL uses standard SSL with certificates — configure via JDBC URL
 * parameters {@code sslmode}, {@code sslcert}, {@code sslkey}.
 */
@Configuration
@ConditionalOnProperty(name = "oracle.security.enabled", havingValue = "true")
@OracleDependency(
    domain          = DependencyDomain.ORACLE_JAVA_API,
    category        = "ORACLE_NET_SECURITY",
    description     = "Oracle Advanced Networking Option (ANO) encryption configuration",
    complexity      = MigrationComplexity.MEDIUM,
    automationLevel = AutomationLevel.PARTIAL,
    migrationTarget = "Replace with standard SSL/TLS: PostgreSQL sslmode=require + certificate config"
)
public class OracleSecurityConfig {

    // ORACLE-DEP [ORACLE_JAVA_API:ORACLE_NET] Oracle Net encryption setting: REQUIRED/REQUESTED/REJECTED
    @Value("${oracle.net.encryption_client:REQUESTED}")
    private String encryptionClient;  // ORACLE-DEP [ORACLE_JAVA_API:ORACLE_NET]

    // ORACLE-DEP [ORACLE_JAVA_API:ORACLE_NET] Oracle Net checksum setting
    @Value("${oracle.net.crypto_checksum_client:REQUESTED}")
    private String checksumClient;    // ORACLE-DEP [ORACLE_JAVA_API:ORACLE_NET]

    @PostConstruct
    @OracleDependency(
        domain          = DependencyDomain.ORACLE_JAVA_API,
        category        = "ORACLE_NET_SECURITY",
        description     = "Sets oracle.net.ano.* system properties for Oracle Network Encryption",
        complexity      = MigrationComplexity.MEDIUM,
        automationLevel = AutomationLevel.PARTIAL,
        migrationTarget = "Remove; configure PostgreSQL SSL via JDBC URL sslmode parameter"
    )
    public void configureOracleNetSecurity() {
        // ORACLE-DEP [ORACLE_JAVA_API:ORACLE_NET] Oracle ANO network encryption
        System.setProperty("oracle.net.encryption_client", encryptionClient);   // ORACLE-DEP [ORACLE_JAVA_API:ORACLE_NET]
        System.setProperty("oracle.net.encryption_types_client", "(AES256, AES128, 3DES168)");
        System.setProperty("oracle.net.crypto_checksum_client", checksumClient); // ORACLE-DEP [ORACLE_JAVA_API:ORACLE_NET]
        System.setProperty("oracle.net.crypto_checksum_types_client", "(SHA256, SHA1)");

        // ORACLE-DEP [ORACLE_JAVA_API:ORACLE_NET] Oracle Net disable out-of-band breaks
        System.setProperty("oracle.net.disableOob", "true");

        // ORACLE-DEP [ORACLE_JAVA_API:ORACLE_NET] Oracle Net TCP connect timeout
        System.setProperty("oracle.net.CONNECT_TIMEOUT", "10000"); // ORACLE-DEP [CONNECTIVITY_CONFIG:ORACLE_CONNECT_TIMEOUT]
        System.setProperty("oracle.jdbc.ReadTimeout", "60000");

        // ORACLE-DEP [ORACLE_JAVA_API:ORACLE_NET] Oracle thin driver TLS version restriction
        System.setProperty("oracle.net.ssl_version", "1.2");
        System.setProperty("oracle.net.ssl_cipher_suites",
                           "(TLS_RSA_WITH_AES_256_CBC_SHA256, TLS_RSA_WITH_AES_128_CBC_SHA256)");
    }
}
