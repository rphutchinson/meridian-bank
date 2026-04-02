package com.meridianbank.config;

import com.meridianbank.annotation.AutomationLevel;
import com.meridianbank.annotation.DependencyDomain;
import com.meridianbank.annotation.MigrationComplexity;
import com.meridianbank.annotation.OracleDependency;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cache.CacheManager;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Cache configuration demonstrating Oracle Coherence integration patterns.
 *
 * <p>Many Oracle enterprise applications use Oracle Coherence as their distributed
 * cache. This class shows the Coherence API patterns and configuration points
 * that the migration framework must identify and replace with Redis/Hazelcast/etc.
 *
 * <p>The actual Coherence bean creation is commented out to avoid requiring
 * the Coherence JAR at runtime (not on Maven Central freely). The patterns are
 * preserved for assessment purposes.
 *
 * <p>Migration: Replace Oracle Coherence with Redis (Spring Data Redis) or
 * Hazelcast. The Spring Cache abstraction layer makes this largely transparent
 * to business code.
 */
@Configuration
@OracleDependency(
    domain          = DependencyDomain.MIDDLEWARE,
    category        = "ORACLE_COHERENCE",
    description     = "Oracle Coherence distributed cache configuration patterns",
    complexity      = MigrationComplexity.HIGH,
    automationLevel = AutomationLevel.PARTIAL,
    migrationTarget = "Replace with Redis (spring-boot-starter-data-redis) or Hazelcast"
)
public class CacheConfig {

    private static final Logger log = LoggerFactory.getLogger(CacheConfig.class);

    // ORACLE-DEP [MIDDLEWARE:ORACLE_COHERENCE] Coherence cache names
    private static final String CUSTOMER_CACHE   = "meridian-customers";  // ORACLE-DEP [MIDDLEWARE:ORACLE_COHERENCE]
    private static final String ACCOUNT_CACHE    = "meridian-accounts";
    private static final String REFERENCE_CACHE  = "meridian-reference";

    /**
     * Default in-process cache manager used when Coherence is not available.
     * Replaced with Coherence or Redis CacheManager in production.
     */
    @Bean
    @Primary
    public CacheManager cacheManager() {
        return new ConcurrentMapCacheManager(CUSTOMER_CACHE, ACCOUNT_CACHE, REFERENCE_CACHE);
    }

    /**
     * Oracle Coherence CacheFactory-based cache manager.
     * Active only when {@code oracle.coherence.enabled=true}.
     *
     * <p>ORACLE-DEP [MIDDLEWARE:ORACLE_COHERENCE] com.tangosol.net.CacheFactory
     */
    @Bean("coherenceCacheManager")
    @ConditionalOnProperty(name = "oracle.coherence.enabled", havingValue = "true")
    @OracleDependency(
        domain          = DependencyDomain.MIDDLEWARE,
        category        = "ORACLE_COHERENCE",
        description     = "com.tangosol.net.CacheFactory.getCache() — Oracle Coherence named cache access",
        complexity      = MigrationComplexity.HIGH,
        automationLevel = AutomationLevel.PARTIAL,
        migrationTarget = "Replace with RedisTemplate or HazelcastInstance cache operations"
    )
    public CacheManager coherenceCacheManager() {
        // ORACLE-DEP [MIDDLEWARE:ORACLE_COHERENCE] Oracle Coherence CacheFactory pattern
        // In production with Coherence JAR:
        //   com.tangosol.net.CacheFactory.getCache(CUSTOMER_CACHE)   -- ORACLE-DEP [MIDDLEWARE:ORACLE_COHERENCE]
        //   com.tangosol.net.CacheFactory.ensureCluster()            -- ORACLE-DEP [MIDDLEWARE:ORACLE_COHERENCE]
        //   com.tangosol.net.ConfigurableCacheFactory ccf =
        //       com.tangosol.net.CacheFactory.getConfigurableCacheFactory() -- ORACLE-DEP [MIDDLEWARE:ORACLE_COHERENCE]
        log.warn("Coherence enabled=true but Coherence JAR not present; falling back to ConcurrentMapCacheManager");
        return new ConcurrentMapCacheManager(CUSTOMER_CACHE, ACCOUNT_CACHE, REFERENCE_CACHE);
    }
}
