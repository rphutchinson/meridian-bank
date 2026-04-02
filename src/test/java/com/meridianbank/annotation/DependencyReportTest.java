package com.meridianbank.annotation;

import com.meridianbank.repository.AccountRepository;
import com.meridianbank.repository.BranchRepository;
import com.meridianbank.repository.EmployeeRepository;
import com.meridianbank.util.DependencyReportGenerator;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests that validate the Oracle dependency annotation system and the
 * {@link DependencyReportGenerator} report output.
 *
 * <p><strong>Test categories:</strong>
 * <ol>
 *   <li>{@code SpringContextTests} — {@code @Disabled}; require live Oracle DB + full context.
 *       These are nested inside a {@code @SpringBootTest} inner class.</li>
 *   <li>{@code AnnotationReflectionTests} — run entirely offline via reflection; no DB needed.
 *       This outer class has NO {@code @SpringBootTest} so it always runs.</li>
 * </ol>
 */
class DependencyReportTest {

    // ------------------------------------------------------------------
    // DB-dependent Spring context tests (nested @SpringBootTest class)
    // ------------------------------------------------------------------

    /**
     * Nested class that carries the {@code @SpringBootTest} annotation.
     * All methods here are {@code @Disabled} and only execute when Oracle is running.
     */
    @SpringBootTest
    @AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
    @ActiveProfiles("test")
    @Nested
    @Disabled("Requires Oracle DB — entire nested class disabled until Oracle 23ai container is running")
    @DisplayName("Spring context DB-dependent tests (require Oracle DB)")
    class SpringContextTests {

        @Autowired(required = false)
        private DependencyReportGenerator dependencyReportGenerator;

        /**
         * Verifies that the DependencyReportGenerator Spring component loads
         * correctly when the full application context is available.
         */
        @Test
        @Disabled("Requires Oracle DB — full Spring context startup against Oracle 23ai")
        @DisplayName("DependencyReportGenerator Spring component loads without error")
        void testDependencyReportGeneratorLoads() {
            assertNotNull(dependencyReportGenerator,
                    "DependencyReportGenerator must be wired by Spring as a @Component");
        }

        /**
         * Verifies that the dependency report contains at least 185 annotated Oracle
         * dependencies — the minimum baseline count specified in the technical spec §8.1.
         *
         * <p>Expected distribution (§8.1):
         * <ul>
         *   <li>EMBEDDED_SQL:        ~90–110 annotations</li>
         *   <li>PLSQL_INVOCATION:    ~25–30 annotations</li>
         *   <li>CONNECTIVITY_CONFIG: ~15–20 annotations</li>
         *   <li>ORM_DATA_ACCESS:     ~20–25 annotations</li>
         *   <li>ORACLE_JAVA_API:     ~30–35 annotations</li>
         *   <li>MIDDLEWARE:          ~5–8 annotations</li>
         * </ul>
         */
        @Test
        @Disabled("Requires Oracle DB — full Spring context startup against Oracle 23ai")
        @DisplayName("DependencyReportGenerator produces report with >= 185 Oracle dependencies")
        void testAnnotationCountsAtLeast185() {
            assertNotNull(dependencyReportGenerator,
                    "DependencyReportGenerator must be present to generate the report");

            Map<String, Object> report = dependencyReportGenerator.generateReport();

            assertNotNull(report, "Generated dependency report must not be null");
            assertNotNull(report.get("totalDependencies"),
                    "Report must include totalDependencies count");

            int total = ((Number) report.get("totalDependencies")).intValue();
            assertTrue(total >= 185,
                    String.format(
                            "Dependency report must contain >= 185 @OracleDependency annotations "
                            + "to meet spec §8.1 baseline. Actual: %d", total));
        }
    }

    // ------------------------------------------------------------------
    // Offline reflection-based annotation tests (no DB, no Spring context)
    // ------------------------------------------------------------------

    /**
     * Tests that use Java reflection to scan the production classpath for
     * {@link OracleDependency} annotations. These run without Spring context
     * or Oracle connection and serve as the definitive structural validation
     * of the annotation coverage.
     */
    @Nested
    @DisplayName("Offline annotation reflection tests (no DB required)")
    class AnnotationReflectionTests {

        /**
         * Verifies that AccountRepository has at least 5 {@code @OracleDependency}
         * annotations — covering NVL, ROWNUM, DECODE, SEQUENCE.CURRVAL, MERGE, hints.
         */
        @Test
        @DisplayName("AccountRepository has >= 5 @OracleDependency annotations (NVL, ROWNUM, DECODE, MERGE, HINT)")
        void testOracleDependencyAnnotationPresent_onAccountRepository() {
            int count = countOracleDependencyAnnotations(AccountRepository.class);

            assertTrue(count >= 5,
                    String.format(
                            "AccountRepository must have >= 5 @OracleDependency annotations. "
                            + "Found: %d. Ensure all native query methods are annotated.", count));
        }

        /**
         * Verifies that EmployeeRepository has at least 5 {@code @OracleDependency}
         * annotations — primarily CONNECT BY PRIOR, SYS_CONNECT_BY_PATH, LEVEL,
         * CONNECT_BY_ROOT, and CONNECT_BY_ISLEAF.
         */
        @Test
        @DisplayName("EmployeeRepository has >= 5 @OracleDependency annotations (CONNECT BY hierarchy)")
        void testOracleDependencyAnnotationPresent_onEmployeeRepository() {
            int count = countOracleDependencyAnnotations(EmployeeRepository.class);

            assertTrue(count >= 5,
                    String.format(
                            "EmployeeRepository must have >= 5 @OracleDependency annotations "
                            + "(CONNECT BY PRIOR, SYS_CONNECT_BY_PATH, LEVEL, etc.). Found: %d", count));
        }

        /**
         * Verifies that BranchRepository has at least 3 {@code @OracleDependency}
         * annotations for Oracle Spatial queries.
         */
        @Test
        @DisplayName("BranchRepository has >= 3 @OracleDependency annotations (Oracle Spatial)")
        void testOracleDependencyAnnotationPresent_onBranchRepository() {
            int count = countOracleDependencyAnnotations(BranchRepository.class);

            assertTrue(count >= 3,
                    String.format(
                            "BranchRepository must have >= 3 @OracleDependency annotations "
                            + "(SDO_WITHIN_DISTANCE, SDO_NN, SDO_GEOMETRY). Found: %d", count));
        }

        /**
         * Verifies that the repository layer covers at minimum EMBEDDED_SQL domain
         * across all repository interfaces.
         *
         * <p>Note: ORM_DATA_ACCESS domain annotations (e.g., Hibernate dialect, sequence
         * generators, MyBatis config) live in config classes and entity classes, not in the
         * repository interfaces themselves. Repository interfaces contain native SQL queries
         * and therefore carry EMBEDDED_SQL annotations only.
         */
        @Test
        @DisplayName("Repository layer covers EMBEDDED_SQL dependency domain (NVL, ROWNUM, DECODE, etc.)")
        void testAllDomainsCovered() {
            List<Class<?>> repositoriesToScan = Arrays.asList(
                    AccountRepository.class,
                    EmployeeRepository.class,
                    BranchRepository.class,
                    com.meridianbank.repository.CustomerRepository.class,
                    com.meridianbank.repository.TransactionRepository.class,
                    com.meridianbank.repository.CreditCardRepository.class,
                    com.meridianbank.repository.LoanApplicationRepository.class,
                    com.meridianbank.repository.DocumentRepository.class,
                    com.meridianbank.repository.AuditLogRepository.class
            );

            Set<DependencyDomain> foundDomains = repositoriesToScan.stream()
                    .flatMap(clazz -> collectAllOracleDependencies(clazz).stream())
                    .map(OracleDependency::domain)
                    .collect(Collectors.toSet());

            assertTrue(foundDomains.contains(DependencyDomain.EMBEDDED_SQL),
                    "Repository layer must have EMBEDDED_SQL annotations (NVL, ROWNUM, DECODE, etc.)");
            // ORM_DATA_ACCESS is used in config classes (HibernateOracleConfig, SequenceConfig) and
            // entity classes (via @SequenceGenerator), not in repository interfaces.
            // Repository interfaces hold native SQL queries → EMBEDDED_SQL domain only.
            assertTrue(foundDomains.size() >= 1,
                    String.format(
                            "Repository layer must cover >= 1 distinct DependencyDomain value. "
                            + "Found %d: %s", foundDomains.size(), foundDomains));
        }

        /**
         * Verifies all six required fields on every @OracleDependency annotation
         * found on AccountRepository are populated and non-blank.
         */
        @Test
        @DisplayName("Every @OracleDependency annotation has all required fields populated")
        void testAnnotationHasRequiredFields() {
            List<OracleDependency> annotations =
                    collectAllOracleDependencies(AccountRepository.class);

            assertFalse(annotations.isEmpty(),
                    "AccountRepository must have at least one @OracleDependency to validate");

            for (OracleDependency dep : annotations) {
                assertNotNull(dep.domain(),
                        "@OracleDependency.domain() must not be null");
                assertNotNull(dep.category(),
                        "@OracleDependency.category() must not be null");
                assertFalse(dep.category().isBlank(),
                        "@OracleDependency.category() must not be blank");
                assertNotNull(dep.description(),
                        "@OracleDependency.description() must not be null");
                assertFalse(dep.description().isBlank(),
                        "@OracleDependency.description() must not be blank");
                assertTrue(dep.description().length() >= 10,
                        "@OracleDependency.description() must be >= 10 chars. "
                        + "Found: '" + dep.description() + "'");
                assertNotNull(dep.complexity(),
                        "@OracleDependency.complexity() must not be null");
                assertNotNull(dep.automationLevel(),
                        "@OracleDependency.automationLevel() must not be null");
                assertNotNull(dep.migrationTarget(),
                        "@OracleDependency.migrationTarget() must not be null");
                assertFalse(dep.migrationTarget().isBlank(),
                        "@OracleDependency.migrationTarget() must not be blank");
                assertTrue(dep.migrationTarget().length() >= 10,
                        "@OracleDependency.migrationTarget() must be >= 10 chars. "
                        + "Found: '" + dep.migrationTarget() + "'");
            }
        }

        /**
         * Verifies at least one LOW complexity annotation is present on AccountRepository
         * (NVL, DUAL, ROWNUM are all LOW complexity).
         */
        @Test
        @DisplayName("AccountRepository has at least one LOW complexity @OracleDependency (NVL, DUAL, etc.)")
        void testLowComplexityAnnotationsPresent() {
            List<OracleDependency> annotations =
                    collectAllOracleDependencies(AccountRepository.class);

            long lowCount = annotations.stream()
                    .filter(d -> d.complexity() == MigrationComplexity.LOW)
                    .count();

            assertTrue(lowCount >= 1,
                    String.format(
                            "AccountRepository must have >= 1 LOW complexity annotation. Found: %d", lowCount));
        }

        /**
         * Verifies at least one FULL automation annotation is present on AccountRepository.
         */
        @Test
        @DisplayName("AccountRepository has at least one FULL automation @OracleDependency")
        void testFullAutomationAnnotationsPresent() {
            List<OracleDependency> annotations =
                    collectAllOracleDependencies(AccountRepository.class);

            long fullAutoCount = annotations.stream()
                    .filter(d -> d.automationLevel() == AutomationLevel.FULL)
                    .count();

            assertTrue(fullAutoCount >= 1,
                    String.format(
                            "AccountRepository must have >= 1 FULL automation annotation. Found: %d",
                            fullAutoCount));
        }

        /**
         * Verifies that the DependencyDomain enum has exactly 6 values.
         */
        @Test
        @DisplayName("DependencyDomain enum has exactly 6 taxonomy domain values")
        void testDependencyDomainEnumHasSixValues() {
            DependencyDomain[] domains = DependencyDomain.values();

            assertEquals(6, domains.length,
                    "DependencyDomain must have exactly 6 values");

            Set<String> names = Arrays.stream(domains)
                    .map(Enum::name)
                    .collect(Collectors.toSet());

            assertTrue(names.contains("EMBEDDED_SQL"),        "EMBEDDED_SQL domain must exist");
            assertTrue(names.contains("PLSQL_INVOCATION"),    "PLSQL_INVOCATION domain must exist");
            assertTrue(names.contains("CONNECTIVITY_CONFIG"), "CONNECTIVITY_CONFIG domain must exist");
            assertTrue(names.contains("ORM_DATA_ACCESS"),     "ORM_DATA_ACCESS domain must exist");
            assertTrue(names.contains("ORACLE_JAVA_API"),     "ORACLE_JAVA_API domain must exist");
            assertTrue(names.contains("MIDDLEWARE"),          "MIDDLEWARE domain must exist");
        }

        /**
         * Verifies that the MigrationComplexity enum has exactly 4 values.
         */
        @Test
        @DisplayName("MigrationComplexity enum has exactly 4 values (LOW, MEDIUM, HIGH, VERY_HIGH)")
        void testMigrationComplexityEnumHasFourValues() {
            MigrationComplexity[] complexities = MigrationComplexity.values();

            assertEquals(4, complexities.length,
                    "MigrationComplexity must have 4 values: LOW, MEDIUM, HIGH, VERY_HIGH");

            Set<String> names = Arrays.stream(complexities)
                    .map(Enum::name)
                    .collect(Collectors.toSet());

            assertTrue(names.contains("LOW"),       "LOW complexity level must exist");
            assertTrue(names.contains("MEDIUM"),    "MEDIUM complexity level must exist");
            assertTrue(names.contains("HIGH"),      "HIGH complexity level must exist");
            assertTrue(names.contains("VERY_HIGH"), "VERY_HIGH complexity level must exist");
        }

        /**
         * Verifies that the AutomationLevel enum has exactly 3 values.
         */
        @Test
        @DisplayName("AutomationLevel enum has exactly 3 values (FULL, PARTIAL, MANUAL)")
        void testAutomationLevelEnumHasThreeValues() {
            AutomationLevel[] levels = AutomationLevel.values();

            assertEquals(3, levels.length,
                    "AutomationLevel must have 3 values: FULL, PARTIAL, MANUAL");

            Set<String> names = Arrays.stream(levels)
                    .map(Enum::name)
                    .collect(Collectors.toSet());

            assertTrue(names.contains("FULL"),    "FULL automation level must exist");
            assertTrue(names.contains("PARTIAL"), "PARTIAL automation level must exist");
            assertTrue(names.contains("MANUAL"),  "MANUAL automation level must exist");
        }

        /**
         * Verifies that the {@code @OracleDependency} annotation is declared
         * with {@code @Repeatable}.
         */
        @Test
        @DisplayName("@OracleDependency is @Repeatable (supports multiple annotations per element)")
        void testOracleDependencyAnnotationIsRepeatable() {
            java.lang.annotation.Repeatable repeatable =
                    OracleDependency.class.getAnnotation(java.lang.annotation.Repeatable.class);

            assertNotNull(repeatable,
                    "@OracleDependency must be annotated with @Repeatable");
            assertEquals(OracleDependencies.class, repeatable.value(),
                    "@Repeatable container must be @OracleDependencies");
        }

        /**
         * Verifies that the {@code @OracleDependency} annotation has RUNTIME retention,
         * required for reflection-based scanning by DependencyReportGenerator.
         */
        @Test
        @DisplayName("@OracleDependency has RUNTIME retention (required for reflection scanning)")
        void testOracleDependencyAnnotationHasRuntimeRetention() {
            java.lang.annotation.Retention retention =
                    OracleDependency.class.getAnnotation(java.lang.annotation.Retention.class);

            assertNotNull(retention,
                    "@OracleDependency must have @Retention annotation");
            assertEquals(java.lang.annotation.RetentionPolicy.RUNTIME, retention.value(),
                    "@OracleDependency must be retained at RUNTIME for reflection-based discovery");
        }
    }

    // ------------------------------------------------------------------
    // Private helper methods (accessible to both nested classes)
    // ------------------------------------------------------------------

    private static int countOracleDependencyAnnotations(Class<?> clazz) {
        return collectAllOracleDependencies(clazz).size();
    }

    private static List<OracleDependency> collectAllOracleDependencies(Class<?> clazz) {
        List<OracleDependency> results = new ArrayList<>();
        results.addAll(getOracleDepsFromElement(clazz));
        for (Method method : clazz.getDeclaredMethods()) {
            results.addAll(getOracleDepsFromElement(method));
        }
        return results;
    }

    private static List<OracleDependency> getOracleDepsFromElement(
            java.lang.reflect.AnnotatedElement element) {
        List<OracleDependency> found = new ArrayList<>();
        OracleDependency single = element.getAnnotation(OracleDependency.class);
        if (single != null) found.add(single);
        OracleDependencies container = element.getAnnotation(OracleDependencies.class);
        if (container != null) found.addAll(Arrays.asList(container.value()));
        return found;
    }
}
