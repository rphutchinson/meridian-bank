package com.meridianbank.config;

import com.meridianbank.annotation.AutomationLevel;
import com.meridianbank.annotation.DependencyDomain;
import com.meridianbank.annotation.MigrationComplexity;
import com.meridianbank.annotation.OracleDependency;
import com.meridianbank.mybatis.handler.OracleArrayTypeHandler;
import com.meridianbank.mybatis.handler.OracleClobTypeHandler;
import com.meridianbank.mybatis.handler.OracleStructTypeHandler;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.type.TypeHandlerRegistry;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import javax.sql.DataSource;

/**
 * MyBatis configuration with Oracle-specific type handlers.
 *
 * <p>Registers custom type handlers that use oracle.sql.CLOB, oracle.sql.ARRAY,
 * and oracle.sql.STRUCT for mapping Oracle-specific data types in MyBatis queries.
 *
 * <p>Migration: Replace Oracle type handlers with standard JDBC type handlers
 * or PostgreSQL-specific handlers if needed.
 */
@Configuration
@MapperScan("com.meridianbank.mybatis.mapper")
@OracleDependency(
    domain          = DependencyDomain.ORM_DATA_ACCESS,
    category        = "MYBATIS_ORACLE",
    description     = "MyBatis configuration registers Oracle-specific type handlers for CLOB, ARRAY, STRUCT",
    complexity      = MigrationComplexity.MEDIUM,
    automationLevel = AutomationLevel.PARTIAL,
    migrationTarget = "Replace Oracle type handlers with standard JDBC handlers or PostgreSQL equivalents"
)
public class MyBatisOracleConfig {

    /**
     * Configures SqlSessionFactory with Oracle type handlers and mapper XML files.
     * ORACLE-DEP [ORM_DATA_ACCESS:MYBATIS_ORACLE] SqlSessionFactory with Oracle handlers
     */
    @Bean
    @OracleDependency(
        domain          = DependencyDomain.ORM_DATA_ACCESS,
        category        = "MYBATIS_ORACLE",
        description     = "SqlSessionFactoryBean configured with Oracle CLOB/ARRAY/STRUCT type handlers",
        complexity      = MigrationComplexity.MEDIUM,
        automationLevel = AutomationLevel.PARTIAL,
        migrationTarget = "Update mapper XML with PostgreSQL-compatible SQL; replace type handlers"
    )
    public SqlSessionFactory sqlSessionFactory(DataSource dataSource) throws Exception {
        SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
        factory.setDataSource(dataSource);

        // Register Oracle-specific type handlers
        // ORACLE-DEP [ORM_DATA_ACCESS:ORACLE_CLOB_HANDLER] OracleClobTypeHandler
        org.apache.ibatis.session.Configuration config =
            new org.apache.ibatis.session.Configuration();

        TypeHandlerRegistry registry = config.getTypeHandlerRegistry();

        // ORACLE-DEP [ORM_DATA_ACCESS:ORACLE_CLOB_HANDLER] Maps CLOB via oracle.sql.CLOB
        registry.register(OracleClobTypeHandler.class);   // ORACLE-DEP [ORM_DATA_ACCESS:ORACLE_CLOB_HANDLER]

        // ORACLE-DEP [ORM_DATA_ACCESS:ORACLE_ARRAY_HANDLER] Maps ARRAY via oracle.sql.ARRAY
        registry.register(OracleArrayTypeHandler.class);  // ORACLE-DEP [ORM_DATA_ACCESS:ORACLE_ARRAY_HANDLER]

        // ORACLE-DEP [ORM_DATA_ACCESS:ORACLE_STRUCT_HANDLER] Maps STRUCT via oracle.sql.STRUCT
        registry.register(OracleStructTypeHandler.class); // ORACLE-DEP [ORM_DATA_ACCESS:ORACLE_STRUCT_HANDLER]

        config.setMapUnderscoreToCamelCase(true);
        config.setDefaultFetchSize(100);
        config.setDefaultStatementTimeout(30);

        factory.setConfiguration(config);

        // Load Oracle SQL mapper XML files
        // ORACLE-DEP [ORM_DATA_ACCESS:MYBATIS_ORACLE] Oracle SQL in mapper XML
        factory.setMapperLocations(
            new PathMatchingResourcePatternResolver()
                .getResources("classpath:mybatis/mapper/*.xml")
        );

        return factory.getObject();
    }
}
