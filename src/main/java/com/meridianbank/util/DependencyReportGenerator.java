package com.meridianbank.util;

import com.meridianbank.annotation.DependencyDomain;
import com.meridianbank.annotation.MigrationComplexity;
import com.meridianbank.annotation.OracleDependencies;
import com.meridianbank.annotation.OracleDependency;
import org.reflections.Reflections;
import org.reflections.scanners.Scanners;
import org.reflections.util.ClasspathHelper;
import org.reflections.util.ConfigurationBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * Scans the Meridian Bank classpath for all {@link OracleDependency} annotations
 * and produces a structured JSON-compatible report.
 *
 * <p>This report is the <em>ground truth</em> against which the agentic migration
 * framework's assessment is validated.  Every annotation found here represents a
 * known Oracle dependency that the framework must detect, classify, and plan a
 * migration path for.
 *
 * <p>The generator uses reflection to walk all classes in the
 * {@code com.meridianbank} package and collects annotations from:
 * <ul>
 *   <li>Type declarations (class / interface level)</li>
 *   <li>Method declarations</li>
 *   <li>Field declarations</li>
 * </ul>
 *
 * <p>Results are grouped by {@link DependencyDomain} and
 * {@link MigrationComplexity} for easy executive summary consumption.
 */
@Component
public class DependencyReportGenerator {

    private static final Logger log = LoggerFactory.getLogger(DependencyReportGenerator.class);

    private static final String BASE_PACKAGE = "com.meridianbank";

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Scans the classpath and returns a fully-populated report map suitable for
     * JSON serialization.
     *
     * @return a {@link Map} with keys: applicationName, scanDate, totalDependencies,
     *         byDomain, byComplexity, byAutomation, and dependencies (list).
     */
    public Map<String, Object> generateReport() {
        log.info("Generating Oracle dependency report for package: {}", BASE_PACKAGE);

        List<DependencyEntry> entries = scanClasspath();

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("applicationName", "meridian-bank");
        report.put("scanDate", Instant.now().toString());
        report.put("basePackage", BASE_PACKAGE);
        report.put("totalDependencies", entries.size());
        report.put("byDomain",      buildDomainSummary(entries));
        report.put("byComplexity",  buildComplexitySummary(entries));
        report.put("byAutomation",  buildAutomationSummary(entries));
        report.put("dependencies",  entries.stream()
                                           .map(DependencyEntry::toMap)
                                           .collect(Collectors.toList()));

        log.info("Oracle dependency report complete: {} entries found", entries.size());
        return report;
    }

    // -------------------------------------------------------------------------
    // Classpath scanning
    // -------------------------------------------------------------------------

    private List<DependencyEntry> scanClasspath() {
        List<DependencyEntry> entries = new ArrayList<>();
        AtomicInteger idCounter = new AtomicInteger(1);

        try {
            Reflections reflections = new Reflections(
                new ConfigurationBuilder()
                    .setUrls(ClasspathHelper.forPackage(BASE_PACKAGE))
                    .setScanners(Scanners.TypesAnnotated, Scanners.MethodsAnnotated,
                                 Scanners.FieldsAnnotated, Scanners.SubTypes)
            );

            Set<Class<?>> allClasses = reflections.getSubTypesOf(Object.class);
            // also include top-level annotated types that Reflections may skip
            allClasses.addAll(reflections.getTypesAnnotatedWith(OracleDependency.class));
            allClasses.addAll(reflections.getTypesAnnotatedWith(OracleDependencies.class));

            for (Class<?> clazz : allClasses) {
                // --- Class-level annotations ---
                collectFromAnnotations(
                    clazz.getAnnotationsByType(OracleDependency.class),
                    clazz.getName(), null, null,
                    entries, idCounter
                );

                // --- Method-level annotations ---
                for (Method method : clazz.getDeclaredMethods()) {
                    collectFromAnnotations(
                        method.getAnnotationsByType(OracleDependency.class),
                        clazz.getName(), method.getName(), null,
                        entries, idCounter
                    );
                }

                // --- Field-level annotations ---
                for (Field field : clazz.getDeclaredFields()) {
                    collectFromAnnotations(
                        field.getAnnotationsByType(OracleDependency.class),
                        clazz.getName(), null, field.getName(),
                        entries, idCounter
                    );
                }
            }

        } catch (Exception e) {
            log.warn("Classpath scan encountered an error: {} — partial results returned", e.getMessage());
        }

        return entries;
    }

    private void collectFromAnnotations(
            OracleDependency[] annotations,
            String className,
            String methodName,
            String fieldName,
            List<DependencyEntry> entries,
            AtomicInteger counter) {

        for (OracleDependency dep : annotations) {
            entries.add(new DependencyEntry(
                String.format("DEP-%04d", counter.getAndIncrement()),
                dep.domain(),
                dep.category(),
                className,
                methodName,
                fieldName,
                dep.description(),
                dep.complexity(),
                dep.automationLevel().name(),
                dep.migrationTarget()
            ));
        }
    }

    // -------------------------------------------------------------------------
    // Summary builders
    // -------------------------------------------------------------------------

    private Map<String, Object> buildDomainSummary(List<DependencyEntry> entries) {
        Map<DependencyDomain, Map<String, Object>> raw = new EnumMap<>(DependencyDomain.class);

        for (DependencyEntry e : entries) {
            raw.computeIfAbsent(e.domain, d -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("count", 0);
                m.put("LOW", 0);
                m.put("MEDIUM", 0);
                m.put("HIGH", 0);
                m.put("VERY_HIGH", 0);
                return m;
            });
            Map<String, Object> bucket = raw.get(e.domain);
            bucket.put("count", (int) bucket.get("count") + 1);
            String cx = e.complexity.name();
            bucket.put(cx, (int) bucket.get(cx) + 1);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        raw.forEach((domain, stats) -> result.put(domain.name(), stats));
        return result;
    }

    private Map<String, Integer> buildComplexitySummary(List<DependencyEntry> entries) {
        Map<String, Integer> result = new LinkedHashMap<>();
        Arrays.stream(MigrationComplexity.values()).forEach(c -> result.put(c.name(), 0));
        for (DependencyEntry e : entries) {
            result.merge(e.complexity.name(), 1, Integer::sum);
        }
        return result;
    }

    private Map<String, Integer> buildAutomationSummary(List<DependencyEntry> entries) {
        Map<String, Integer> result = new LinkedHashMap<>();
        result.put("FULL",    0);
        result.put("PARTIAL", 0);
        result.put("MANUAL",  0);
        for (DependencyEntry e : entries) {
            result.merge(e.automationLevel, 1, Integer::sum);
        }
        return result;
    }

    // -------------------------------------------------------------------------
    // Inner record
    // -------------------------------------------------------------------------

    private static final class DependencyEntry {
        final String id;
        final DependencyDomain domain;
        final String category;
        final String className;
        final String methodName;   // may be null
        final String fieldName;    // may be null
        final String description;
        final MigrationComplexity complexity;
        final String automationLevel;
        final String migrationTarget;

        DependencyEntry(String id, DependencyDomain domain, String category,
                        String className, String methodName, String fieldName,
                        String description, MigrationComplexity complexity,
                        String automationLevel, String migrationTarget) {
            this.id              = id;
            this.domain          = domain;
            this.category        = category;
            this.className       = className;
            this.methodName      = methodName;
            this.fieldName       = fieldName;
            this.description     = description;
            this.complexity      = complexity;
            this.automationLevel = automationLevel;
            this.migrationTarget = migrationTarget;
        }

        Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id",              id);
            m.put("domain",          domain.name());
            m.put("category",        category);
            m.put("className",       className);
            if (methodName != null) m.put("methodName", methodName);
            if (fieldName  != null) m.put("fieldName",  fieldName);
            m.put("description",     description);
            m.put("complexity",      complexity.name());
            m.put("automationLevel", automationLevel);
            m.put("migrationTarget", migrationTarget);
            return m;
        }
    }
}
