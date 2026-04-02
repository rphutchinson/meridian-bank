package com.meridianbank.repository;

import com.meridianbank.annotation.*;
import com.meridianbank.entity.NotificationEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Spring Data JPA repository for NotificationEntity.
 * Demonstrates Oracle SQL constructs: SYSDATE for timestamp defaults,
 * DECODE for priority ordering (mapped to numeric ordinals),
 * NVL(processed_date, SYSDATE) for null-coalesced display,
 * and TRUNC for day-level date grouping.
 */
@Repository
public interface NotificationRepository extends JpaRepository<NotificationEntity, Long> {

    // Standard JPQL — no Oracle dependency
    List<NotificationEntity> findByRecipientId(Long recipientId);
    List<NotificationEntity> findByStatus(String status);
    List<NotificationEntity> findByMessageType(String messageType);

    // ORACLE-DEP [EMBEDDED_SQL:SYSDATE] SYSDATE for pending notification query
    @OracleDependency(
        domain = DependencyDomain.EMBEDDED_SQL,
        category = "SYSDATE",
        description = "SYSDATE used to query unprocessed notifications enqueued before now",
        complexity = MigrationComplexity.LOW,
        automationLevel = AutomationLevel.FULL,
        migrationTarget = "Replace SYSDATE with CURRENT_TIMESTAMP in PostgreSQL"
    )
    @OracleDependency(
        domain = DependencyDomain.EMBEDDED_SQL,
        category = "DECODE_FUNCTION",
        description = "DECODE(priority,'CRITICAL',1,'HIGH',2,'MEDIUM',3,'LOW',4,5) assigns numeric sort order for priority queue",
        complexity = MigrationComplexity.LOW,
        automationLevel = AutomationLevel.FULL,
        migrationTarget = "Replace DECODE(priority,...) with CASE WHEN priority=... THEN ... END in PostgreSQL"
    )
    @Query(value =
        "SELECT * FROM MERIDIAN.NOTIFICATIONS " +
        "WHERE status = 'PENDING' " +
        "AND enqueued_date <= SYSDATE " +
        "ORDER BY DECODE(priority, 'CRITICAL', 1, 'HIGH', 2, 'MEDIUM', 3, 'LOW', 4, 5), " +
        "  enqueued_date ASC",
        nativeQuery = true)
    List<NotificationEntity> findPendingNotificationsOrdered();

    // ORACLE-DEP [EMBEDDED_SQL:NVL_FUNCTION] NVL(processed_date, SYSDATE) for display
    @OracleDependency(
        domain = DependencyDomain.EMBEDDED_SQL,
        category = "NVL_FUNCTION",
        description = "NVL(processed_date, SYSDATE) returns SYSDATE when notification not yet processed, enabling age calculation",
        complexity = MigrationComplexity.LOW,
        automationLevel = AutomationLevel.FULL,
        migrationTarget = "Replace NVL(processed_date, NOW()) with COALESCE(processed_date, NOW()) in PostgreSQL"
    )
    @Query(value =
        "SELECT n.notification_id, n.recipient_id, n.message_type, n.priority, " +
        "  n.status, n.enqueued_date, " +
        "  NVL(n.processed_date, SYSDATE) AS effective_processed_date, " +
        "  ROUND(NVL(n.processed_date, SYSDATE) - n.enqueued_date, 4) AS processing_days " +
        "FROM MERIDIAN.NOTIFICATIONS n " +
        "WHERE n.recipient_id = :recipientId " +
        "ORDER BY n.enqueued_date DESC",
        nativeQuery = true)
    List<Object[]> findNotificationsWithProcessingTime(@Param("recipientId") Long recipientId);

    // ORACLE-DEP [EMBEDDED_SQL:TRUNC_DATE] TRUNC for daily notification counts
    @OracleDependency(
        domain = DependencyDomain.EMBEDDED_SQL,
        category = "TRUNC_DATE",
        description = "TRUNC(enqueued_date) groups notifications by calendar day for throughput reporting",
        complexity = MigrationComplexity.LOW,
        automationLevel = AutomationLevel.FULL,
        migrationTarget = "Replace TRUNC(enqueued_date) with DATE_TRUNC('day', enqueued_date) in PostgreSQL"
    )
    @Query(value =
        "SELECT TRUNC(enqueued_date) AS notification_day, " +
        "  message_type, " +
        "  COUNT(*) AS total_sent, " +
        "  SUM(DECODE(status, 'PROCESSED', 1, 0)) AS processed_count, " +
        "  SUM(DECODE(status, 'FAILED', 1, 0)) AS failed_count " +
        "FROM MERIDIAN.NOTIFICATIONS " +
        "WHERE enqueued_date >= SYSDATE - :days " +
        "GROUP BY TRUNC(enqueued_date), message_type " +
        "ORDER BY notification_day DESC",
        nativeQuery = true)
    List<Object[]> findDailyNotificationStats(@Param("days") int days);

    // ORACLE-DEP [EMBEDDED_SQL:SYSDATE] SYSDATE + threshold for stale notification detection
    @OracleDependency(
        domain = DependencyDomain.EMBEDDED_SQL,
        category = "DATE_ARITHMETIC",
        description = "SYSDATE - :staleDays uses Oracle date - integer arithmetic to find stuck pending notifications",
        complexity = MigrationComplexity.LOW,
        automationLevel = AutomationLevel.FULL,
        migrationTarget = "Replace SYSDATE - n with NOW() - INTERVAL 'n days' in PostgreSQL"
    )
    @Query(value =
        "SELECT * FROM MERIDIAN.NOTIFICATIONS " +
        "WHERE status = 'PENDING' " +
        "AND enqueued_date < SYSDATE - :staleDays " +
        "ORDER BY DECODE(priority, 'CRITICAL', 1, 'HIGH', 2, 'MEDIUM', 3, 'LOW', 4, 5) ASC",
        nativeQuery = true)
    List<NotificationEntity> findStaleNotifications(@Param("staleDays") int staleDays);

    // ORACLE-DEP [EMBEDDED_SQL:SYSTIMESTAMP] SYSTIMESTAMP for processed timestamp update
    @OracleDependency(
        domain = DependencyDomain.EMBEDDED_SQL,
        category = "SYSTIMESTAMP",
        description = "SYSTIMESTAMP provides sub-second precision for notification processed_date audit trail",
        complexity = MigrationComplexity.LOW,
        automationLevel = AutomationLevel.FULL,
        migrationTarget = "Replace SYSTIMESTAMP with CLOCK_TIMESTAMP() or CURRENT_TIMESTAMP in PostgreSQL"
    )
    @Modifying
    @Transactional
    @Query(value =
        "UPDATE MERIDIAN.NOTIFICATIONS " +
        "SET status = :status, processed_date = SYSTIMESTAMP " +
        "WHERE notification_id = :notificationId",
        nativeQuery = true)
    int markNotificationProcessed(@Param("notificationId") Long notificationId,
                                   @Param("status") String status);

    // ORACLE-DEP [EMBEDDED_SQL:DECODE_FUNCTION] DECODE for priority-based SLA threshold
    @OracleDependency(
        domain = DependencyDomain.EMBEDDED_SQL,
        category = "DECODE_FUNCTION",
        description = "DECODE(priority,...) maps priority to SLA threshold hours for breach detection",
        complexity = MigrationComplexity.LOW,
        automationLevel = AutomationLevel.FULL,
        migrationTarget = "Replace DECODE(priority,...) with CASE WHEN priority=... END in PostgreSQL"
    )
    @Query(value =
        "SELECT n.notification_id, n.recipient_id, n.priority, n.message_type, " +
        "  n.enqueued_date, " +
        "  DECODE(n.priority, 'CRITICAL', 1, 'HIGH', 4, 'MEDIUM', 24, 'LOW', 72, 168) AS sla_hours, " +
        "  (SYSDATE - n.enqueued_date) * 24 AS hours_pending " +
        "FROM MERIDIAN.NOTIFICATIONS n " +
        "WHERE n.status = 'PENDING' " +
        "AND (SYSDATE - n.enqueued_date) * 24 > " +
        "    DECODE(n.priority, 'CRITICAL', 1, 'HIGH', 4, 'MEDIUM', 24, 'LOW', 72, 168)",
        nativeQuery = true)
    List<Object[]> findSlaBreachedNotifications();
}
