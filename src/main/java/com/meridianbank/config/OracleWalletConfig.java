package com.meridianbank.config;

import com.meridianbank.annotation.AutomationLevel;
import com.meridianbank.annotation.DependencyDomain;
import com.meridianbank.annotation.MigrationComplexity;
import com.meridianbank.annotation.OracleDependency;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;

import jakarta.annotation.PostConstruct;
import java.security.Security;

/**
 * Configures Oracle Wallet-based authentication.
 *
 * <p>Oracle Wallet is Oracle's credential storage mechanism providing passwordless
 * database connections in production environments. This class demonstrates the
 * application-tier configuration patterns — setting system properties and registering
 * Oracle PKI security providers.
 *
 * <p>In the migrated application, Oracle Wallet is replaced by standard Java KeyStore,
 * pgpass file, or a secrets vault (HashiCorp Vault, AWS Secrets Manager, etc.).
 *
 * <p>Active only when {@code spring.datasource.oracle.wallet=true} is set.
 */
@Configuration
@ConditionalOnProperty(name = "spring.datasource.oracle.wallet", havingValue = "true")
@OracleDependency(
    domain          = DependencyDomain.CONNECTIVITY_CONFIG,
    category        = "ORACLE_WALLET",
    description     = "Oracle Wallet credential storage for passwordless DB authentication",
    complexity      = MigrationComplexity.MEDIUM,
    automationLevel = AutomationLevel.PARTIAL,
    migrationTarget = "Replace with Java KeyStore, pgpass, or secrets vault integration"
)
public class OracleWalletConfig {

    // ORACLE-DEP [CONNECTIVITY_CONFIG:ORACLE_WALLET] Wallet directory path
    @Value("${oracle.net.wallet_location:/opt/oracle/wallet}")
    private String walletLocation;               // ORACLE-DEP [CONNECTIVITY_CONFIG:ORACLE_WALLET]

    @Value("${oracle.net.wallet_password:}")
    private String walletPassword;

    /**
     * Registers Oracle PKI security provider and sets wallet system properties.
     *
     * <p>Must run before any database connection is obtained, hence @PostConstruct.
     */
    @PostConstruct
    @OracleDependency(
        domain          = DependencyDomain.ORACLE_JAVA_API,
        category        = "ORACLE_PKI",
        description     = "Registers oracle.security.pki.OraclePKIProvider for wallet authentication",
        complexity      = MigrationComplexity.MEDIUM,
        automationLevel = AutomationLevel.PARTIAL,
        migrationTarget = "Remove; use standard Java PKCS12 KeyStore or secrets manager"
    )
    public void configureOracleWallet() {
        // ORACLE-DEP [ORACLE_JAVA_API:ORACLE_PKI] Register Oracle PKI security provider
        // In production code: Security.insertProviderAt(new oracle.security.pki.OraclePKIProvider(), 1)
        // Commented here to avoid compile dependency on oraclepki at runtime without wallet:
        // Security.insertProviderAt(new oracle.security.pki.OraclePKIProvider(), 3);

        // ORACLE-DEP [CONNECTIVITY_CONFIG:ORACLE_WALLET] oracle.net.wallet_location system property
        System.setProperty("oracle.net.wallet_location",
                           "(SOURCE=(METHOD=FILE)(METHOD_DATA=(DIRECTORY=" + walletLocation + ")))");
                                                     // ORACLE-DEP [CONNECTIVITY_CONFIG:ORACLE_WALLET]

        // ORACLE-DEP [CONNECTIVITY_CONFIG:ORACLE_WALLET] Oracle SSL/TLS wallet properties
        System.setProperty("oracle.net.ssl_client_authentication", "false");
        System.setProperty("oracle.net.ssl_version", "1.2");
        System.setProperty("oracle.net.authentication_services", "(TCPS)");

        // ORACLE-DEP [ORACLE_JAVA_API:ORACLE_NET] Oracle Net encryption properties
        System.setProperty("oracle.net.encryption_client", "REQUIRED");  // ORACLE-DEP [ORACLE_JAVA_API:ORACLE_NET]
        System.setProperty("oracle.net.encryption_types_client", "(AES256)");
        System.setProperty("oracle.net.crypto_checksum_client", "REQUIRED");
        System.setProperty("oracle.net.crypto_checksum_types_client", "(SHA256)");
    }

    /**
     * Returns the wallet-based JDBC URL.
     * The URL must reference a wallet-bound alias, not an explicit host/port.
     * ORACLE-DEP [CONNECTIVITY_CONFIG:ORACLE_WALLET] Wallet connection URL format
     */
    @OracleDependency(
        domain          = DependencyDomain.CONNECTIVITY_CONFIG,
        category        = "ORACLE_WALLET",
        description     = "Wallet JDBC URL uses TCPS protocol and wallet alias",
        complexity      = MigrationComplexity.MEDIUM,
        automationLevel = AutomationLevel.PARTIAL,
        migrationTarget = "Replace with standard SSL PostgreSQL JDBC URL with cert parameters"
    )
    public String getWalletJdbcUrl(String walletAlias) {
        // ORACLE-DEP [CONNECTIVITY_CONFIG:ORACLE_WALLET] Wallet-alias JDBC URL
        return "jdbc:oracle:thin:/@" + walletAlias;  // ORACLE-DEP [CONNECTIVITY_CONFIG:ORACLE_WALLET]
    }
}
