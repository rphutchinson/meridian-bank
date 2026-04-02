package com.meridianbank.entity;

import com.meridianbank.annotation.AutomationLevel;
import com.meridianbank.annotation.DependencyDomain;
import com.meridianbank.annotation.MigrationComplexity;
import com.meridianbank.annotation.OracleDependency;
import com.meridianbank.annotation.OracleDependencies;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.NamedNativeQueries;
import jakarta.persistence.NamedNativeQuery;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

/**
 * Persistent representation of Oracle Advanced Queue (AQ) notification messages.
 * Each row corresponds to a message enqueued into MERIDIAN.NOTIFICATION_QUEUE via
 * DBMS_AQ.ENQUEUE. The payload is stored as an Oracle CLOB initialized with EMPTY_CLOB().
 * Priority mirrors Oracle AQ message priority (1 = highest, 9 = lowest).
 *
 * ORACLE-DEP [EMBEDDED_SQL:EMPTY_CLOB] CLOB payload initialized with EMPTY_CLOB() in DDL default
 * ORACLE-DEP [PLSQL_INVOCATION:ORACLE_AQ] Rows are managed by DBMS_AQ enqueue/dequeue procedures
 * ORACLE-DEP [EMBEDDED_SQL:SYSTIMESTAMP] enqueued_date uses Oracle SYSTIMESTAMP precision
 * ORACLE-DEP [EMBEDDED_SQL:INTERVAL_LITERAL] INTERVAL '24' HOUR in pending query window
 */
@OracleDependencies({
    @OracleDependency(
        domain = DependencyDomain.EMBEDDED_SQL,
        category = "EMPTY_CLOB",
        description = "CLOB payload field initialized with EMPTY_CLOB()",
        complexity = MigrationComplexity.LOW,
        automationLevel = AutomationLevel.FULL,
        migrationTarget = "Remove EMPTY_CLOB(); PostgreSQL TEXT defaults to NULL or empty string"
    ),
    @OracleDependency(
        domain = DependencyDomain.PLSQL_INVOCATION,
        category = "ORACLE_AQ",
        description = "Notification records correspond to Oracle AQ queue messages",
        complexity = MigrationComplexity.HIGH,
        automationLevel = AutomationLevel.PARTIAL,
        migrationTarget = "Replace Oracle AQ with RabbitMQ/Kafka message model"
    )
})
@NamedNativeQueries({
    @NamedNativeQuery(
        name = "NotificationEntity.findPendingByPriority",
        // ORACLE-DEP [EMBEDDED_SQL:SYSTIMESTAMP] SYSTIMESTAMP for 24-hour sliding window
        // ORACLE-DEP [EMBEDDED_SQL:INTERVAL_LITERAL] Oracle INTERVAL '24' HOUR literal
        // Priority ASC: lower number = higher Oracle AQ priority (1 is highest)
        query = "SELECT n.* FROM MERIDIAN.NOTIFICATIONS n " +
                "WHERE n.status = 'PENDING' " +
                "AND n.enqueued_date >= SYSTIMESTAMP - INTERVAL '24' HOUR " +
                "ORDER BY n.priority ASC, n.enqueued_date ASC",
        resultClass = NotificationEntity.class
    ),
    @NamedNativeQuery(
        name = "NotificationEntity.findStaleProcessing",
        // ORACLE-DEP [EMBEDDED_SQL:SYSTIMESTAMP] SYSTIMESTAMP for stale-detection window
        // ORACLE-DEP [EMBEDDED_SQL:INTERVAL_LITERAL] Oracle INTERVAL '30' MINUTE literal
        // ORACLE-DEP [EMBEDDED_SQL:NVL_FUNCTION] NVL on error_message for display
        query = "SELECT n.notification_id, n.recipient_id, n.message_type, n.priority, " +
                "       n.status, n.enqueued_date, " +
                "       NVL(n.error_message, 'No error recorded') as error_display " +
                "FROM MERIDIAN.NOTIFICATIONS n " +
                "WHERE n.status = 'PROCESSING' " +
                "AND n.enqueued_date < SYSTIMESTAMP - INTERVAL '30' MINUTE " +
                "ORDER BY n.enqueued_date ASC",
        resultClass = NotificationEntity.class
    )
})
@Entity
@Table(name = "NOTIFICATIONS", schema = "MERIDIAN")
public class NotificationEntity {

    // ORACLE-DEP [CONNECTIVITY_CONFIG:SEQUENCE_GENERATOR] Oracle sequence for PK generation
    @Id
    @SequenceGenerator(
        name = "notification_seq_gen",
        sequenceName = "MERIDIAN.NOTIFICATION_SEQ",
        allocationSize = 1
    )
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "notification_seq_gen")
    @Column(name = "NOTIFICATION_ID")
    private Long notificationId;

    @Column(name = "RECIPIENT_ID", nullable = false)
    private Long recipientId;

    // ORACLE-DEP [EMBEDDED_SQL:VARCHAR2_TYPE] Oracle VARCHAR2 for message type classification
    @Column(name = "MESSAGE_TYPE", columnDefinition = "VARCHAR2(50)", nullable = false)
    private String messageType;

    /**
     * Oracle AQ message payload stored as CLOB. In Oracle DDL this column defaults to
     * EMPTY_CLOB() so a non-null LOB locator always exists before the payload is written.
     * ORACLE-DEP [EMBEDDED_SQL:EMPTY_CLOB] Oracle CLOB initialized with EMPTY_CLOB() in DDL
     * ORACLE-DEP [EMBEDDED_SQL:CLOB_TYPE] Oracle CLOB for potentially large message payloads
     */
    @Lob
    @Column(name = "PAYLOAD")
    private String payload;

    /**
     * Oracle AQ message priority: 1 (highest) to 9 (lowest). Stored as NUMBER(3).
     * ORACLE-DEP [EMBEDDED_SQL:NUMBER_TYPE] Oracle NUMBER(3) mirrors AQ priority range
     */
    @Column(name = "PRIORITY", columnDefinition = "NUMBER(3)")

    private Integer priority = 5;

    // ORACLE-DEP [EMBEDDED_SQL:VARCHAR2_TYPE] Oracle VARCHAR2 for message lifecycle status
    @Column(name = "STATUS", columnDefinition = "VARCHAR2(20)", nullable = false)

    private String status = "PENDING";

    /**
     * Oracle TIMESTAMP WITH TIME ZONE for enqueue time — matches Oracle AQ message timestamp precision.
     * ORACLE-DEP [EMBEDDED_SQL:SYSTIMESTAMP] enqueued_date defaults to SYSTIMESTAMP in DDL
     * ORACLE-DEP [EMBEDDED_SQL:TIMESTAMP_WITH_TZ] Oracle TIMESTAMP WITH TIME ZONE type
     */
    @Column(name = "ENQUEUED_DATE", columnDefinition = "TIMESTAMP WITH TIME ZONE", nullable = false)
    private OffsetDateTime enqueuedDate;

    /**
     * Oracle TIMESTAMP WITH TIME ZONE, nullable until the message is consumed.
     * ORACLE-DEP [EMBEDDED_SQL:TIMESTAMP_WITH_TZ] Oracle TIMESTAMP WITH TIME ZONE type
     */
    @Column(name = "PROCESSED_DATE", columnDefinition = "TIMESTAMP WITH TIME ZONE")
    private OffsetDateTime processedDate;

    // ORACLE-DEP [EMBEDDED_SQL:VARCHAR2_TYPE] Oracle VARCHAR2 for dequeue error details
    @Column(name = "ERROR_MESSAGE", columnDefinition = "VARCHAR2(2000)")
    private String errorMessage;
    public NotificationEntity() {
    }


    public Long getNotificationId() {
        return notificationId;
    }

    public void setNotificationId(Long notificationId) {
        this.notificationId = notificationId;
    }

    public Long getRecipientId() {
        return recipientId;
    }

    public void setRecipientId(Long recipientId) {
        this.recipientId = recipientId;
    }

    public String getMessageType() {
        return messageType;
    }

    public void setMessageType(String messageType) {
        this.messageType = messageType;
    }

    public String getPayload() {
        return payload;
    }

    public void setPayload(String payload) {
        this.payload = payload;
    }

    public Integer getPriority() {
        return priority;
    }

    public void setPriority(Integer priority) {
        this.priority = priority;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public OffsetDateTime getEnqueuedDate() {
        return enqueuedDate;
    }

    public void setEnqueuedDate(OffsetDateTime enqueuedDate) {
        this.enqueuedDate = enqueuedDate;
    }

    public OffsetDateTime getProcessedDate() {
        return processedDate;
    }

    public void setProcessedDate(OffsetDateTime processedDate) {
        this.processedDate = processedDate;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

}
