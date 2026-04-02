package com.meridianbank;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * Meridian Bank — Oracle Migration Test Application.
 *
 * <p>This Spring Boot application intentionally exercises every category of Oracle
 * application-tier dependency defined in the Oracle Migration Framework taxonomy (§2.1–2.10).
 * It serves as the primary test harness for validating the agentic migration framework's
 * dependency detection and transformation capabilities.
 *
 * <p>Every Oracle dependency is annotated with {@code @OracleDependency} and marked with
 * an inline {@code // ORACLE-DEP [DOMAIN:CATEGORY]} comment for grep-based discovery.
 *
 * <p>Start: {@code mvn spring-boot:run -Dspring-boot.run.profiles=dev}
 * <p>Validate: {@code curl http://localhost:8080/api/admin/dependency-report}
 */
@SpringBootApplication
@EnableConfigurationProperties
@EnableAsync
public class MeridianBankApplication {

    public static void main(String[] args) {
        SpringApplication.run(MeridianBankApplication.class, args);
    }
}
