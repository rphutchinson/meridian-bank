package com.meridianbank.config;

import com.meridianbank.annotation.AutomationLevel;
import com.meridianbank.annotation.DependencyDomain;
import com.meridianbank.annotation.MigrationComplexity;
import com.meridianbank.annotation.OracleDependency;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.support.incrementer.OracleSequenceMaxValueIncrementer; // ORACLE-DEP [ORM_DATA_ACCESS:SEQUENCE_INCREMENTER]

import javax.sql.DataSource;

/**
 * Creates Spring {@code OracleSequenceMaxValueIncrementer} beans for programmatic
 * sequence access outside of JPA entity ID generation.
 *
 * <p>Used in services and DAOs that need sequence values without going through JPA,
 * for example when constructing Oracle-specific reference numbers or batch IDs.
 *
 * <p>Migration: Replace {@code OracleSequenceMaxValueIncrementer} with
 * {@code PostgresSequenceMaxValueIncrementer} and update sequence name references.
 */
@Configuration
@OracleDependency(
    domain          = DependencyDomain.ORM_DATA_ACCESS,
    category        = "SEQUENCE_INCREMENTER",
    description     = "OracleSequenceMaxValueIncrementer beans for all Oracle sequences",
    complexity      = MigrationComplexity.LOW,
    automationLevel = AutomationLevel.FULL,
    migrationTarget = "Replace OracleSequenceMaxValueIncrementer with PostgresSequenceMaxValueIncrementer"
)
public class SequenceConfig {

    // ORACLE-DEP [ORM_DATA_ACCESS:SEQUENCE_INCREMENTER] customer sequence incrementer
    @Bean("customerSeqIncrementer")
    @OracleDependency(
        domain = DependencyDomain.ORM_DATA_ACCESS, category = "SEQUENCE_INCREMENTER",
        description = "OracleSequenceMaxValueIncrementer for CUSTOMER_SEQ",
        complexity = MigrationComplexity.LOW, automationLevel = AutomationLevel.FULL,
        migrationTarget = "new PostgresSequenceMaxValueIncrementer(ds, \"customer_seq\")"
    )
    public OracleSequenceMaxValueIncrementer customerSeq(DataSource ds) {
        return new OracleSequenceMaxValueIncrementer(ds, "MERIDIAN.CUSTOMER_SEQ"); // ORACLE-DEP [ORM_DATA_ACCESS:SEQUENCE_INCREMENTER]
    }

    // ORACLE-DEP [ORM_DATA_ACCESS:SEQUENCE_INCREMENTER] account sequence incrementer
    @Bean("accountSeqIncrementer")
    @OracleDependency(
        domain = DependencyDomain.ORM_DATA_ACCESS, category = "SEQUENCE_INCREMENTER",
        description = "OracleSequenceMaxValueIncrementer for ACCOUNT_SEQ",
        complexity = MigrationComplexity.LOW, automationLevel = AutomationLevel.FULL,
        migrationTarget = "new PostgresSequenceMaxValueIncrementer(ds, \"account_seq\")"
    )
    public OracleSequenceMaxValueIncrementer accountSeq(DataSource ds) {
        return new OracleSequenceMaxValueIncrementer(ds, "MERIDIAN.ACCOUNT_SEQ"); // ORACLE-DEP [ORM_DATA_ACCESS:SEQUENCE_INCREMENTER]
    }

    // ORACLE-DEP [ORM_DATA_ACCESS:SEQUENCE_INCREMENTER] transaction sequence incrementer
    @Bean("transactionSeqIncrementer")
    @OracleDependency(
        domain = DependencyDomain.ORM_DATA_ACCESS, category = "SEQUENCE_INCREMENTER",
        description = "OracleSequenceMaxValueIncrementer for TRANSACTION_SEQ",
        complexity = MigrationComplexity.LOW, automationLevel = AutomationLevel.FULL,
        migrationTarget = "new PostgresSequenceMaxValueIncrementer(ds, \"transaction_seq\")"
    )
    public OracleSequenceMaxValueIncrementer transactionSeq(DataSource ds) {
        return new OracleSequenceMaxValueIncrementer(ds, "MERIDIAN.TRANSACTION_SEQ"); // ORACLE-DEP [ORM_DATA_ACCESS:SEQUENCE_INCREMENTER]
    }

    // ORACLE-DEP [ORM_DATA_ACCESS:SEQUENCE_INCREMENTER] loan application sequence incrementer
    @Bean("loanAppSeqIncrementer")
    @OracleDependency(
        domain = DependencyDomain.ORM_DATA_ACCESS, category = "SEQUENCE_INCREMENTER",
        description = "OracleSequenceMaxValueIncrementer for LOAN_APP_SEQ",
        complexity = MigrationComplexity.LOW, automationLevel = AutomationLevel.FULL,
        migrationTarget = "new PostgresSequenceMaxValueIncrementer(ds, \"loan_app_seq\")"
    )
    public OracleSequenceMaxValueIncrementer loanAppSeq(DataSource ds) {
        return new OracleSequenceMaxValueIncrementer(ds, "MERIDIAN.LOAN_APP_SEQ"); // ORACLE-DEP [ORM_DATA_ACCESS:SEQUENCE_INCREMENTER]
    }

    // ORACLE-DEP [ORM_DATA_ACCESS:SEQUENCE_INCREMENTER] audit log sequence incrementer
    @Bean("auditLogSeqIncrementer")
    @OracleDependency(
        domain = DependencyDomain.ORM_DATA_ACCESS, category = "SEQUENCE_INCREMENTER",
        description = "OracleSequenceMaxValueIncrementer for AUDIT_LOG_SEQ",
        complexity = MigrationComplexity.LOW, automationLevel = AutomationLevel.FULL,
        migrationTarget = "new PostgresSequenceMaxValueIncrementer(ds, \"audit_log_seq\")"
    )
    public OracleSequenceMaxValueIncrementer auditLogSeq(DataSource ds) {
        return new OracleSequenceMaxValueIncrementer(ds, "MERIDIAN.AUDIT_LOG_SEQ"); // ORACLE-DEP [ORM_DATA_ACCESS:SEQUENCE_INCREMENTER]
    }

    // ORACLE-DEP [ORM_DATA_ACCESS:SEQUENCE_INCREMENTER] notification sequence incrementer
    @Bean("notificationSeqIncrementer")
    @OracleDependency(
        domain = DependencyDomain.ORM_DATA_ACCESS, category = "SEQUENCE_INCREMENTER",
        description = "OracleSequenceMaxValueIncrementer for NOTIFICATION_SEQ",
        complexity = MigrationComplexity.LOW, automationLevel = AutomationLevel.FULL,
        migrationTarget = "new PostgresSequenceMaxValueIncrementer(ds, \"notification_seq\")"
    )
    public OracleSequenceMaxValueIncrementer notificationSeq(DataSource ds) {
        return new OracleSequenceMaxValueIncrementer(ds, "MERIDIAN.NOTIFICATION_SEQ"); // ORACLE-DEP [ORM_DATA_ACCESS:SEQUENCE_INCREMENTER]
    }
}
