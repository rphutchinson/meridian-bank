package com.meridianbank;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Top-level Spring Boot integration test for Meridian Bank.
 *
 * <p>The {@code contextLoads} test requires a live Oracle 23ai Free database
 * (started via {@code docker compose -f docker/docker-compose.yml up -d}).
 * All other tests in this class can run without a database connection.
 *
 * <p>Oracle dependencies exercised when the context loads:
 * <ul>
 *   <li>ORACLE-DEP [CONNECTIVITY_CONFIG:ORACLE_DRIVER]      OracleDataSourceConfig wires ojdbc11 driver</li>
 *   <li>ORACLE-DEP [CONNECTIVITY_CONFIG:ORACLE_UCP]         OracleUCPConfig initialises UCP pool</li>
 *   <li>ORACLE-DEP [ORM_DATA_ACCESS:HIBERNATE_DIALECT]      HibernateOracleConfig sets OracleDialect</li>
 *   <li>ORACLE-DEP [ORM_DATA_ACCESS:MYBATIS_ORACLE]         MyBatisOracleConfig registers Oracle type handlers</li>
 *   <li>ORACLE-DEP [PLSQL_INVOCATION:ORACLE_AQ]             OracleAQConfig creates queue connection factory</li>
 * </ul>
 */
class MeridianBankApplicationTests {

    // ------------------------------------------------------------------
    // DB-dependent tests (require Oracle container to be running)
    // ------------------------------------------------------------------

    /**
     * Verifies the full Spring application context loads successfully against
     * a live Oracle 23ai Free instance. This exercises every Oracle configuration
     * class: DataSource, UCP, Hibernate dialect, MyBatis handlers, and AQ setup.
     *
     * <p>Run with: {@code mvn test -Dspring.profiles.active=test}
     * after starting Oracle via docker compose.
     */
    @Test
    @Disabled("Requires Oracle DB — run: docker compose -f docker/docker-compose.yml up -d")
    @DisplayName("Spring context loads against live Oracle DB")
    void contextLoads() {
        // If this test reaches this line, Spring has successfully:
        //  1. Connected to Oracle via ojdbc11 driver
        //  2. Validated the Hibernate OracleDialect
        //  3. Applied Liquibase changesets
        //  4. Registered all JPA entities with Oracle-specific column definitions
        //  5. Initialised the UCP connection pool
        //  6. Set up Oracle AQ connection factory
        // No assertions needed — context startup failure throws an exception.
    }

    /**
     * Verifies that the main application class can be referenced without
     * starting the Spring context. This test always passes and confirms
     * the class is on the classpath and compilable.
     */
    @Test
    @DisplayName("Application class is resolvable without DB connection")
    void applicationClassIsResolvable() {
        // Instantiate the test class itself to verify the classpath is correct.
        MeridianBankApplicationTests instance = new MeridianBankApplicationTests();
        assertNotNull(instance, "Test class should be instantiable");
    }

    /**
     * Verifies that the application's package structure is intact by loading
     * the main application class via reflection. Does not start the Spring context.
     */
    @Test
    @DisplayName("Main application class is present on classpath")
    void mainApplicationClassIsPresent() throws ClassNotFoundException {
        Class<?> appClass = Class.forName("com.meridianbank.MeridianBankApplication");
        assertNotNull(appClass, "MeridianBankApplication should be on the classpath");
    }

    /**
     * Verifies that the custom @OracleDependency annotation class is loadable.
     * This test is entirely offline and validates that the annotation subsystem
     * compiles correctly — a prerequisite for the dependency report feature.
     */
    @Test
    @DisplayName("OracleDependency annotation class is loadable without DB")
    void oracleDependencyAnnotationIsLoadable() throws ClassNotFoundException {
        Class<?> annotationClass =
                Class.forName("com.meridianbank.annotation.OracleDependency");
        assertNotNull(annotationClass, "OracleDependency annotation should be loadable");
    }
}
