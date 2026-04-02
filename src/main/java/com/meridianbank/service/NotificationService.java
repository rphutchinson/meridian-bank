package com.meridianbank.service;

import com.meridianbank.annotation.AutomationLevel;
import com.meridianbank.annotation.DependencyDomain;
import com.meridianbank.annotation.MigrationComplexity;
import com.meridianbank.annotation.OracleDependency;
import com.meridianbank.dao.NotificationOracleDAO;
import com.meridianbank.dto.NotificationDTO;
import com.meridianbank.entity.NotificationEntity;
import com.meridianbank.exception.MeridianBankException;
import com.meridianbank.repository.NotificationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * NotificationService -- business logic for Oracle Advanced Queuing (AQ) notifications.
 *
 * <p>Oracle Advanced Queuing (AQ) is Oracle's native message queuing system,
 * tightly integrated with the Oracle Database and exposed to Java applications via
 * {@code oracle.jms.*} (JMS bridge) and the PL/SQL {@code DBMS_AQ} / {@code DBMS_AQADM}
 * packages.  It provides transactional messaging, message priority, dequeue wait,
 * and durable pub/sub -- all within the database transaction boundary.
 *
 * <p>Migration note: Oracle AQ is replaced in the migrated architecture by a
 * standalone message broker such as RabbitMQ, Apache Kafka, or Amazon SQS.
 * The key differences are:
 * <ul>
 *   <li>AQ enqueue/dequeue participates in the Oracle XA transaction; broker-based
 *       solutions use two-phase commit or the outbox pattern for transactional messaging.</li>
 *   <li>AQ message priority is a first-class Oracle concept; RabbitMQ supports message
 *       priority via queue arguments; Kafka does not natively support priority queues.</li>
 *   <li>AQ dequeue with {@code DBMS_AQ.WAIT_FOREVER} / timeout is replaced by consumer
 *       group blocking poll in Kafka or basic-consume in RabbitMQ.</li>
 * </ul>
 *
 * <p>Oracle dependencies in this service: §2.2 (DBMS_AQ calls via NotificationOracleDAO)
 * and §2.9 (oracle.jms.* imports in the DAO layer).
 */
@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    /** Maximum number of dequeue attempts before giving up in processNotificationBatch(). */
    private static final int MAX_DEQUEUE_EMPTY_RETRIES = 3;

    @Autowired
    private NotificationRepository notificationRepository;

    @Autowired
    private NotificationOracleDAO notificationOracleDAO;

    // -------------------------------------------------------------------------
    // Enqueue -- produce messages
    // -------------------------------------------------------------------------

    /**
     * Enqueues a notification message onto the Oracle AQ notification queue.
     *
     * <p>The DAO calls DBMS_AQ.ENQUEUE using a PL/SQL anonymous block via JDBC.
     * The message priority and delay properties are passed as AQ message options.
     * Enqueue participates in the current Spring transaction -- the message is only
     * visible to dequeue consumers once the transaction commits.
     *
     * @param notification the notification to enqueue
     * @return the Oracle AQ message ID (RAW(16) formatted as hex string)
     * @throws MeridianBankException if enqueue fails
     */
    // ORACLE-DEP [PLSQL_INVOCATION:ORACLE_AQ] Notification enqueue via DBMS_AQ.ENQUEUE
    @Transactional
    @OracleDependency(
        domain = DependencyDomain.PLSQL_INVOCATION,
        category = "ORACLE_AQ",
        description = "Delegates to NotificationOracleDAO.enqueueNotification() which calls "
                    + "DBMS_AQ.ENQUEUE via a PL/SQL anonymous block; message priority and correlation "
                    + "ID are set through the AQ enqueue options object",
        complexity = MigrationComplexity.HIGH,
        automationLevel = AutomationLevel.MANUAL,
        migrationTarget = "Replace DBMS_AQ.ENQUEUE with RabbitMQ basicPublish(), "
                        + "Kafka ProducerRecord send(), or SQS SendMessage; "
                        + "use the outbox pattern for transactional message delivery"
    )
    public String sendNotification(NotificationDTO notification) {
        if (notification == null) {
            throw new MeridianBankException("INVALID_INPUT", "Notification DTO must not be null", 400);
        }
        if (notification.getRecipientId() == null) {
            throw new MeridianBankException("INVALID_INPUT", "Recipient ID is required", 400);
        }
        if (notification.getMessageType() == null || notification.getMessageType().isBlank()) {
            throw new MeridianBankException("INVALID_INPUT", "Message type is required", 400);
        }
        // Default priority to 5 (medium) if not specified
        if (notification.getPriority() == null) {
            notification.setPriority(5);
        }
        log.info("Enqueuing {} notification for recipient {} via Oracle AQ DBMS_AQ.ENQUEUE",
                 notification.getMessageType(), notification.getRecipientId());
        try {
            // ORACLE-DEP [PLSQL_INVOCATION:ORACLE_AQ] enqueueNotification calls DBMS_AQ.ENQUEUE
            String messageId = notificationOracleDAO.enqueueNotification(notification);
            log.info("Notification enqueued with Oracle AQ message ID: {}", messageId);
            return messageId;
        } catch (Exception e) {
            log.error("Failed to enqueue notification for recipient {}: {}",
                      notification.getRecipientId(), e.getMessage(), e);
            throw new MeridianBankException("ENQUEUE_FAILED",
                "Failed to enqueue notification: " + e.getMessage(), 500, e);
        }
    }

    // -------------------------------------------------------------------------
    // Dequeue -- consume messages
    // -------------------------------------------------------------------------

    /**
     * Dequeues the next highest-priority notification from the Oracle AQ queue.
     *
     * <p>The DAO calls DBMS_AQ.DEQUEUE with a configurable wait timeout.
     * Oracle AQ dequeue is blocking -- it waits for a message up to the specified
     * number of seconds before returning null.  This wait-for-message pattern is
     * fundamental to Oracle AQ and replaces polling loops common in other messaging
     * systems.
     *
     * @return the next notification, or null if the queue is empty after the wait timeout
     * @throws MeridianBankException if dequeue encounters a non-empty-queue error
     */
    // ORACLE-DEP [PLSQL_INVOCATION:ORACLE_AQ] Blocking dequeue via DBMS_AQ.DEQUEUE with wait
    @Transactional
    @OracleDependency(
        domain = DependencyDomain.PLSQL_INVOCATION,
        category = "ORACLE_AQ",
        description = "Delegates to NotificationOracleDAO.dequeueNotification() which calls "
                    + "DBMS_AQ.DEQUEUE with WAIT => 5 (seconds) -- Oracle AQ blocking dequeue; "
                    + "returns null if the queue is empty when the wait expires",
        complexity = MigrationComplexity.HIGH,
        automationLevel = AutomationLevel.MANUAL,
        migrationTarget = "Replace DBMS_AQ.DEQUEUE with RabbitMQ basicGet() / basicConsume(), "
                        + "Kafka ConsumerRecord poll(Duration), or SQS ReceiveMessage with "
                        + "WaitTimeSeconds for long-polling; priority ordering requires broker support"
    )
    public NotificationDTO processNextNotification() {
        log.debug("Dequeuing next notification via Oracle AQ DBMS_AQ.DEQUEUE");
        try {
            // ORACLE-DEP [PLSQL_INVOCATION:ORACLE_AQ] dequeueNotification calls DBMS_AQ.DEQUEUE with wait
            NotificationDTO notification = notificationOracleDAO.dequeueNotification(5);
            if (notification == null) {
                log.debug("Oracle AQ dequeue returned empty -- no pending notifications");
                return null;
            }
            log.info("Dequeued notification id={}, type={}, priority={}",
                     notification.getNotificationId(),
                     notification.getMessageType(),
                     notification.getPriority());
            // Mark as processed in the relational table as well
            if (notification.getNotificationId() != null) {
                markProcessed(notification.getNotificationId());
            }
            return notification;
        } catch (Exception e) {
            // ORA-25228 is the expected empty-queue error from DBMS_AQ.DEQUEUE with NO_WAIT
            if (e.getMessage() != null && e.getMessage().contains("ORA-25228")) {
                log.debug("Oracle AQ queue empty (ORA-25228), no messages to dequeue");
                return null;
            }
            log.error("Error during Oracle AQ DBMS_AQ.DEQUEUE: {}", e.getMessage(), e);
            throw new MeridianBankException("DEQUEUE_FAILED",
                "Failed to dequeue notification: " + e.getMessage(), 500, e);
        }
    }

    /**
     * Returns the current number of messages in the Oracle AQ notification queue.
     *
     * <p>The DAO queries the AQ system view or uses DBMS_AQ statistics to obtain
     * the queue depth without dequeuing any messages.
     *
     * @return number of pending messages in the notification queue
     */
    // ORACLE-DEP [PLSQL_INVOCATION:ORACLE_AQ] Queue depth check via Oracle AQ system view
    @Transactional(readOnly = true)
    @OracleDependency(
        domain = DependencyDomain.PLSQL_INVOCATION,
        category = "ORACLE_AQ",
        description = "Delegates to NotificationOracleDAO.getQueueDepth() which queries "
                    + "Oracle AQ system views (AQ$_NOTIFICATION_QT or GV$AQ) to obtain "
                    + "the current pending message count without dequeuing",
        complexity = MigrationComplexity.MEDIUM,
        automationLevel = AutomationLevel.PARTIAL,
        migrationTarget = "Replace with broker-specific queue depth API: "
                        + "RabbitMQ management HTTP API /api/queues, "
                        + "Kafka consumer group lag via AdminClient, or SQS GetQueueAttributes"
    )
    public int getQueueDepth() {
        log.debug("Querying Oracle AQ queue depth");
        try {
            // ORACLE-DEP [PLSQL_INVOCATION:ORACLE_AQ] getQueueDepth queries Oracle AQ views
            return notificationOracleDAO.getQueueDepth();
        } catch (Exception e) {
            log.error("Failed to get Oracle AQ queue depth: {}", e.getMessage(), e);
            throw new MeridianBankException("QUEUE_DEPTH_FAILED",
                "Failed to get queue depth: " + e.getMessage(), 500, e);
        }
    }

    /**
     * Returns all pending notifications from the relational NOTIFICATIONS table.
     *
     * <p>This query bypasses Oracle AQ and reads the mirrored relational rows,
     * allowing status monitoring without consuming messages from the queue.
     *
     * @return list of pending notification DTOs ordered by priority and enqueued date
     */
    // ORACLE-DEP [EMBEDDED_SQL:DECODE_FUNCTION] findPendingNotifications uses DECODE for priority sort
    @Transactional(readOnly = true)
    @OracleDependency(
        domain = DependencyDomain.EMBEDDED_SQL,
        category = "DECODE_FUNCTION",
        description = "Delegates to NotificationOracleDAO.findPendingNotifications() which uses "
                    + "DECODE(priority,'CRITICAL',1,'HIGH',2,...) for priority ordering in the underlying query",
        complexity = MigrationComplexity.LOW,
        automationLevel = AutomationLevel.FULL,
        migrationTarget = "Replace DECODE(priority,...) with CASE WHEN priority='CRITICAL' THEN 1 ... END in PostgreSQL"
    )
    public List<NotificationDTO> getPendingNotifications() {
        log.debug("Fetching pending notifications from relational table");
        try {
            // ORACLE-DEP [EMBEDDED_SQL:DECODE_FUNCTION] findPendingNotifications uses DECODE for ordering
            return notificationOracleDAO.findPendingNotifications();
        } catch (Exception e) {
            log.error("Failed to fetch pending notifications: {}", e.getMessage(), e);
            throw new MeridianBankException("FETCH_NOTIFICATIONS_FAILED",
                "Failed to fetch pending notifications: " + e.getMessage(), 500, e);
        }
    }

    /**
     * Marks a specific notification as processed in the relational NOTIFICATIONS table.
     *
     * <p>Updates the status and processed_date columns.  The DAO uses SYSTIMESTAMP
     * for sub-second precision on the processed_date, as Oracle DATE only has
     * second-level precision.
     *
     * @param notificationId the notification to mark processed
     * @throws MeridianBankException if the update fails
     */
    // ORACLE-DEP [EMBEDDED_SQL:SYSTIMESTAMP] processed_date updated via SYSTIMESTAMP
    @Transactional
    @OracleDependency(
        domain = DependencyDomain.EMBEDDED_SQL,
        category = "SYSTIMESTAMP",
        description = "Delegates to NotificationOracleDAO.markNotificationProcessed() which sets "
                    + "processed_date = SYSTIMESTAMP for sub-second precision audit of message processing",
        complexity = MigrationComplexity.LOW,
        automationLevel = AutomationLevel.FULL,
        migrationTarget = "Replace SYSTIMESTAMP with CLOCK_TIMESTAMP() or CURRENT_TIMESTAMP in PostgreSQL"
    )
    public void markProcessed(Long notificationId) {
        if (notificationId == null) {
            throw new MeridianBankException("INVALID_INPUT", "Notification ID must not be null", 400);
        }
        log.debug("Marking notification {} as processed via SYSTIMESTAMP update", notificationId);
        try {
            // ORACLE-DEP [EMBEDDED_SQL:SYSTIMESTAMP] markNotificationProcessed sets processed_date=SYSTIMESTAMP
            int updated = notificationOracleDAO.markNotificationProcessed(notificationId);
            if (updated == 0) {
                log.warn("No rows updated when marking notification {} as processed -- may already be processed", notificationId);
            }
        } catch (Exception e) {
            log.error("Failed to mark notification {} as processed: {}", notificationId, e.getMessage(), e);
            throw new MeridianBankException("MARK_PROCESSED_FAILED",
                "Failed to mark notification as processed: " + e.getMessage(), 500, e);
        }
    }

    // -------------------------------------------------------------------------
    // Batch processing
    // -------------------------------------------------------------------------

    /**
     * Processes up to {@code batchSize} notifications from the Oracle AQ queue
     * in a single service call.
     *
     * <p>Each dequeue call obtains one message.  If the queue returns empty
     * ({@code null}) for MAX_DEQUEUE_EMPTY_RETRIES consecutive attempts, the
     * batch terminates early.  This pattern avoids tight loops on an empty queue
     * while still processing bursts of arriving messages efficiently.
     *
     * @param batchSize maximum number of messages to process
     * @return list of successfully processed NotificationDTOs
     */
    // ORACLE-DEP [PLSQL_INVOCATION:ORACLE_AQ] Batch dequeue loop via DBMS_AQ.DEQUEUE
    @Transactional
    @OracleDependency(
        domain = DependencyDomain.PLSQL_INVOCATION,
        category = "ORACLE_AQ",
        description = "Calls processNextNotification() in a loop up to batchSize times, each call "
                    + "invoking DBMS_AQ.DEQUEUE; terminates early on consecutive empty-queue results",
        complexity = MigrationComplexity.HIGH,
        automationLevel = AutomationLevel.MANUAL,
        migrationTarget = "Replace with broker consumer batch poll: Kafka poll(Duration) returns up to "
                        + "max.poll.records messages; RabbitMQ prefetch_count controls batch; "
                        + "SQS ReceiveMessage returns up to 10 messages per call"
    )
    public List<NotificationDTO> processNotificationBatch(int batchSize) {
        if (batchSize <= 0 || batchSize > 1000) {
            throw new MeridianBankException("INVALID_INPUT",
                "Batch size must be between 1 and 1000", 400);
        }
        log.info("Starting Oracle AQ notification batch processing, batchSize={}", batchSize);
        List<NotificationDTO> processed = new ArrayList<>();
        int consecutiveEmpty = 0;

        for (int i = 0; i < batchSize && consecutiveEmpty < MAX_DEQUEUE_EMPTY_RETRIES; i++) {
            // ORACLE-DEP [PLSQL_INVOCATION:ORACLE_AQ] Each iteration calls DBMS_AQ.DEQUEUE
            NotificationDTO notification = processNextNotification();
            if (notification == null) {
                consecutiveEmpty++;
                log.debug("Oracle AQ dequeue empty (attempt {}/{})", consecutiveEmpty, MAX_DEQUEUE_EMPTY_RETRIES);
            } else {
                consecutiveEmpty = 0;
                processed.add(notification);
                log.debug("Processed notification {}/{}: id={}, type={}",
                          processed.size(), batchSize,
                          notification.getNotificationId(), notification.getMessageType());
            }
        }
        log.info("Oracle AQ batch complete: {} notifications processed (requested {})",
                 processed.size(), batchSize);
        return processed;
    }

    // -------------------------------------------------------------------------
    // Simple retrieval
    // -------------------------------------------------------------------------

    /**
     * Retrieves a specific notification by primary key from the relational table.
     *
     * @param id the notification primary key
     * @return the notification DTO
     * @throws MeridianBankException with HTTP 404 if not found
     */
    @Transactional(readOnly = true)
    public NotificationDTO getNotificationById(Long id) {
        if (id == null) {
            throw new MeridianBankException("INVALID_INPUT", "Notification ID must not be null", 400);
        }
        log.debug("Fetching notification by id {}", id);
        NotificationEntity entity = notificationRepository.findById(id)
            .orElseThrow(() -> new MeridianBankException(
                "NOTIFICATION_NOT_FOUND",
                "No notification found with ID: " + id,
                404));
        return mapEntityToDto(entity);
    }

    // -------------------------------------------------------------------------
    // Private mapping helpers
    // -------------------------------------------------------------------------

    private NotificationDTO mapEntityToDto(NotificationEntity entity) {
        NotificationDTO dto = new NotificationDTO();
        dto.setNotificationId(entity.getNotificationId());
        dto.setRecipientId(entity.getRecipientId());
        dto.setMessageType(entity.getMessageType());
        dto.setPriority(entity.getPriority());
        dto.setStatus(entity.getStatus());
        dto.setPayload(entity.getPayload());
        if (entity.getEnqueuedDate() != null) {
            dto.setEnqueuedDate(entity.getEnqueuedDate().toLocalDateTime());
        }
        if (entity.getProcessedDate() != null) {
            dto.setProcessedDate(entity.getProcessedDate().toLocalDateTime());
        }
        return dto;
    }
}
