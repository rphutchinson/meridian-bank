package com.meridianbank.dao;

import com.meridianbank.annotation.AutomationLevel;
import com.meridianbank.annotation.DependencyDomain;
import com.meridianbank.annotation.MigrationComplexity;
import com.meridianbank.annotation.OracleDependency;
import com.meridianbank.dto.NotificationDTO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;

/**
 * NotificationOracleDAO — raw JDBC data access for Oracle Advanced Queuing (AQ)
 * and notification persistence.
 *
 * Demonstrates Oracle-specific patterns:
 * - DBMS_AQ.ENQUEUE / DBMS_AQ.DEQUEUE via PL/SQL anonymous blocks
 * - SYS.AQ$_JMS_TEXT_MESSAGE for JMS message construction at database tier
 * - RAWTOHEX() for converting RAW(16) message handles to hex strings
 * - NVL(), DECODE(), SYSDATE, SYSTIMESTAMP in NOTIFICATIONS table queries
 * - Oracle AQ queue table (AQ$_*) direct count query
 */
@Repository
public class NotificationOracleDAO {

    private static final Logger log = LoggerFactory.getLogger(NotificationOracleDAO.class);

    @Autowired
    private DataSource dataSource;

    // -------------------------------------------------------------------------
    // METHOD 1: enqueueNotification
    // -------------------------------------------------------------------------

    /**
     * Enqueues a notification as a JMS text message into Oracle Advanced Queuing.
     *
     * The PL/SQL anonymous block:
     * 1. Constructs a SYS.AQ$_JMS_TEXT_MESSAGE with the notification payload
     * 2. Sets message priority via MESSAGE_PROPERTIES_T
     * 3. Calls DBMS_AQ.ENQUEUE to place the message on MERIDIAN.NOTIFICATION_QUEUE
     * 4. Returns the RAW(16) message handle as a hex string via RAWTOHEX()
     *
     * The entire Oracle AQ infrastructure (queue table, queue, JMS provider)
     * is replaced during migration with a standard message broker (Kafka, RabbitMQ,
     * or SQS) and a standard JMS or cloud SDK client.
     */
    @OracleDependency(
        domain = DependencyDomain.PLSQL_INVOCATION,
        category = "DBMS_AQ",
        description = "PL/SQL anonymous block calling DBMS_AQ.ENQUEUE to insert a JMS text message into MERIDIAN.NOTIFICATION_QUEUE with configurable priority",
        complexity = MigrationComplexity.VERY_HIGH,
        automationLevel = AutomationLevel.MANUAL,
        migrationTarget = "Replace Oracle AQ with RabbitMQ, Apache Kafka, or AWS SQS; replace SYS.AQ$_JMS_TEXT_MESSAGE with standard broker message type and producer client"
    )
    public String enqueueNotification(NotificationDTO notification) {
        // ORACLE-DEP [PLSQL_INVOCATION:DBMS_AQ] DBMS_AQ.ENQUEUE enqueues a JMS text message to the Oracle AQ queue
        // ORACLE-DEP [EMBEDDED_SQL:RAWTOHEX] RAWTOHEX(v_message_handle) converts RAW(16) message ID to storable hex string
        final String block =
            "DECLARE " +
            "  v_enqueue_options    DBMS_AQ.ENQUEUE_OPTIONS_T; " +
            "  v_message_properties DBMS_AQ.MESSAGE_PROPERTIES_T; " +
            "  v_message_handle     RAW(16); " +
            "  v_message            SYS.AQ$_JMS_TEXT_MESSAGE; " +
            "BEGIN " +
            "  v_message := SYS.AQ$_JMS_TEXT_MESSAGE.CONSTRUCT(); " +
            "  v_message.set_text(:p_payload); " +
            "  v_message_properties.priority := :p_priority; " +
            "  DBMS_AQ.ENQUEUE( " +
            "    queue_name         => 'MERIDIAN.NOTIFICATION_QUEUE', " +
            "    enqueue_options    => v_enqueue_options, " +
            "    message_properties => v_message_properties, " +
            "    payload            => v_message, " +
            "    msgid              => v_message_handle); " +
            "  :p_msg_id := RAWTOHEX(v_message_handle); " +
            "END;";

        try (Connection conn = dataSource.getConnection();
             CallableStatement cs = conn.prepareCall(block)) {

            cs.setString("p_payload", notification.getPayload());
            cs.setInt("p_priority", notification.getPriority() != null ? notification.getPriority() : 2);
            cs.registerOutParameter("p_msg_id", Types.VARCHAR);

            cs.execute();

            String msgHandle = cs.getString("p_msg_id");
            log.debug("Enqueued notification for recipientId={} with msgHandle={}",
                notification.getRecipientId(), msgHandle);
            return msgHandle;

        } catch (SQLException e) {
            log.error("Failed to enqueue notification for recipientId={}", notification.getRecipientId(), e);
            throw new RuntimeException("Error enqueuing notification via DBMS_AQ for recipientId="
                + notification.getRecipientId(), e);
        }
    }

    // -------------------------------------------------------------------------
    // METHOD 2: dequeueNotification
    // -------------------------------------------------------------------------

    /**
     * Dequeues a single notification from Oracle AQ using DBMS_AQ.DEQUEUE.
     * The wait option causes the call to block for up to {@code waitSeconds}
     * seconds if the queue is empty, or return immediately if 0 is passed.
     *
     * Returns null if no message is available within the wait window (ORA-25228).
     */
    @OracleDependency(
        domain = DependencyDomain.PLSQL_INVOCATION,
        category = "DBMS_AQ",
        description = "PL/SQL anonymous block calling DBMS_AQ.DEQUEUE with configurable wait timeout; handles ORA-25228 (timeout/no message) gracefully",
        complexity = MigrationComplexity.VERY_HIGH,
        automationLevel = AutomationLevel.MANUAL,
        migrationTarget = "Replace with standard message broker consumer (e.g. KafkaConsumer.poll(), RabbitMQ BasicConsume) with equivalent timeout semantics"
    )
    public NotificationDTO dequeueNotification(int waitSeconds) {
        // ORACLE-DEP [PLSQL_INVOCATION:DBMS_AQ] DBMS_AQ.DEQUEUE with configurable wait timeout blocks until message available
        // ORACLE-DEP [EMBEDDED_SQL:RAWTOHEX] RAWTOHEX(v_message_handle) converts dequeued RAW(16) message ID to hex
        final String block =
            "DECLARE " +
            "  v_dequeue_options    DBMS_AQ.DEQUEUE_OPTIONS_T; " +
            "  v_message_properties DBMS_AQ.MESSAGE_PROPERTIES_T; " +
            "  v_message_handle     RAW(16); " +
            "  v_message            SYS.AQ$_JMS_TEXT_MESSAGE; " +
            "BEGIN " +
            "  v_dequeue_options.wait := :p_wait; " +
            "  DBMS_AQ.DEQUEUE( " +
            "    queue_name         => 'MERIDIAN.NOTIFICATION_QUEUE', " +
            "    dequeue_options    => v_dequeue_options, " +
            "    message_properties => v_message_properties, " +
            "    payload            => v_message, " +
            "    msgid              => v_message_handle); " +
            "  :p_payload  := v_message.text_vc; " +
            "  :p_priority := v_message_properties.priority; " +
            "  :p_msg_id   := RAWTOHEX(v_message_handle); " +
            "END;";

        try (Connection conn = dataSource.getConnection();
             CallableStatement cs = conn.prepareCall(block)) {

            cs.setInt("p_wait", waitSeconds);
            cs.registerOutParameter("p_payload", Types.VARCHAR);
            cs.registerOutParameter("p_priority", Types.INTEGER);
            cs.registerOutParameter("p_msg_id", Types.VARCHAR);

            cs.execute();

            String payload = cs.getString("p_payload");
            int priority   = cs.getInt("p_priority");
            String msgId   = cs.getString("p_msg_id");

            if (payload == null) {
                // Queue was empty within the wait window
                log.debug("DBMS_AQ.DEQUEUE returned no message within {}s wait window", waitSeconds);
                return null;
            }

            NotificationDTO dto = new NotificationDTO();
            dto.setPayload(payload);
            dto.setPriority(priority);
            log.debug("Dequeued notification msgId={} priority={}", msgId, priority);
            return dto;

        } catch (SQLException e) {
            // ORA-25228: timeout or end-of-fetch on dequeue — not an error condition
            if (e.getErrorCode() == 25228) {
                log.debug("DBMS_AQ.DEQUEUE timed out after {}s — queue empty", waitSeconds);
                return null;
            }
            log.error("Failed to dequeue notification from Oracle AQ", e);
            throw new RuntimeException("Error dequeuing notification via DBMS_AQ", e);
        }
    }

    // -------------------------------------------------------------------------
    // METHOD 3: getQueueDepth
    // -------------------------------------------------------------------------

    /**
     * Returns the number of pending (ready) messages in the Oracle AQ queue table.
     * Queries the underlying AQ queue table directly using state = 0 (READY).
     *
     * Note: This is a direct table query — in production, DBMS_AQ statistics
     * views (V$AQ, USER_QUEUE_STATISTICS) may be more appropriate.
     */
    public int getQueueDepth() {
        final String sql =
            "SELECT COUNT(*) " +
            "FROM   MERIDIAN.NOTIFICATION_QT " +
            "WHERE  q_name = 'NOTIFICATION_QUEUE' " +
            "AND    state  = 0"; // state = 0 is READY in Oracle AQ

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {

            if (rs.next()) {
                int depth = rs.getInt(1);
                log.debug("Oracle AQ queue depth for NOTIFICATION_QUEUE: {}", depth);
                return depth;
            }
            return 0;

        } catch (SQLException e) {
            log.error("Failed to query Oracle AQ queue depth", e);
            throw new RuntimeException("Error retrieving Oracle AQ queue depth", e);
        }
    }

    // -------------------------------------------------------------------------
    // METHOD 4: findPendingNotifications
    // -------------------------------------------------------------------------

    /**
     * Queries the NOTIFICATIONS persistence table for pending notification rows.
     * Uses Oracle NVL(), DECODE(), and SYSDATE for null-safe display and label mapping.
     */
    @OracleDependency(
        domain = DependencyDomain.EMBEDDED_SQL,
        category = "DECODE_FUNCTION",
        description = "DECODE(priority, 1,'HIGH', 2,'MEDIUM', 3,'LOW', 'UNKNOWN') maps integer priority to human-readable label",
        complexity = MigrationComplexity.LOW,
        automationLevel = AutomationLevel.FULL,
        migrationTarget = "Replace DECODE(priority,...) with CASE WHEN priority=1 THEN 'HIGH' WHEN priority=2 THEN 'MEDIUM' WHEN priority=3 THEN 'LOW' ELSE 'UNKNOWN' END"
    )
    @OracleDependency(
        domain = DependencyDomain.EMBEDDED_SQL,
        category = "NVL_FUNCTION",
        description = "NVL(processed_date, SYSDATE) defaults NULL processed_date to current server date for display purposes",
        complexity = MigrationComplexity.LOW,
        automationLevel = AutomationLevel.FULL,
        migrationTarget = "Replace NVL(processed_date, SYSDATE) with COALESCE(processed_date, NOW())"
    )
    public List<NotificationDTO> findPendingNotifications() {
        // ORACLE-DEP [EMBEDDED_SQL:NVL_FUNCTION] NVL(processed_date, SYSDATE) defaults NULL to current date for display
        // ORACLE-DEP [EMBEDDED_SQL:DECODE_FUNCTION] DECODE maps priority integer to label string
        // ORACLE-DEP [EMBEDDED_SQL:SYSDATE] SYSDATE as default fallback for NULL processed_date
        final String sql =
            "SELECT notification_id, " +
            "       recipient_id, " +
            "       message_type, " +
            "       priority, " +
            "       status, " +
            "       enqueued_date, " +
            "       NVL(processed_date, SYSDATE) AS effective_date, " +
            "       DECODE(priority, " +
            "              1, 'HIGH', " +
            "              2, 'MEDIUM', " +
            "              3, 'LOW', " +
            "              'UNKNOWN') AS priority_label " +
            "FROM   MERIDIAN.NOTIFICATIONS " +
            "WHERE  status = 'PENDING' " +
            "ORDER  BY priority ASC, enqueued_date ASC";

        List<NotificationDTO> results = new ArrayList<>();

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {

            while (rs.next()) {
                NotificationDTO dto = new NotificationDTO();
                dto.setNotificationId(rs.getLong("notification_id"));
                dto.setRecipientId(rs.getLong("recipient_id"));
                dto.setMessageType(rs.getString("message_type"));
                dto.setPriority(rs.getInt("priority"));
                dto.setStatus(rs.getString("status"));
                dto.setEnqueuedDate(rs.getTimestamp("enqueued_date") != null
                    ? rs.getTimestamp("enqueued_date").toLocalDateTime() : null);
                results.add(dto);
            }

            log.debug("Found {} pending notifications", results.size());
            return results;

        } catch (SQLException e) {
            log.error("Failed to retrieve pending notifications", e);
            throw new RuntimeException("Error retrieving pending notifications", e);
        }
    }

    // -------------------------------------------------------------------------
    // METHOD 5: markNotificationProcessed
    // -------------------------------------------------------------------------

    /**
     * Marks a notification as processed using SYSTIMESTAMP for microsecond-precision
     * audit timestamping. SYSTIMESTAMP includes the database server timezone offset,
     * which is Oracle-specific; PostgreSQL uses CURRENT_TIMESTAMP instead.
     */
    @OracleDependency(
        domain = DependencyDomain.EMBEDDED_SQL,
        category = "SYSTIMESTAMP",
        description = "SYSTIMESTAMP records notification processing time with microsecond precision and timezone offset",
        complexity = MigrationComplexity.LOW,
        automationLevel = AutomationLevel.FULL,
        migrationTarget = "Replace SYSTIMESTAMP with CURRENT_TIMESTAMP in PostgreSQL; both return timezone-aware timestamp but CURRENT_TIMESTAMP is SQL standard"
    )
    public int markNotificationProcessed(Long notificationId) {
        // ORACLE-DEP [EMBEDDED_SQL:SYSTIMESTAMP] SYSTIMESTAMP for microsecond-precision audit timestamp with Oracle DB timezone
        final String sql =
            "UPDATE MERIDIAN.NOTIFICATIONS " +
            "SET    status         = 'PROCESSED', " +
            "       processed_date = SYSTIMESTAMP " +
            "WHERE  notification_id = ?";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setLong(1, notificationId);
            int rowsUpdated = stmt.executeUpdate();

            if (rowsUpdated == 0) {
                log.warn("markNotificationProcessed: no row updated for notification_id={}", notificationId);
            } else {
                log.debug("Marked notification_id={} as PROCESSED via SYSTIMESTAMP", notificationId);
            }

            return rowsUpdated;

        } catch (SQLException e) {
            log.error("Failed to mark notification_id={} as processed", notificationId, e);
            throw new RuntimeException("Error marking notification processed for id=" + notificationId, e);
        }
    }
}
