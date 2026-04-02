package com.meridianbank.dto;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.LocalDateTime;

/**
 * DTO for Oracle Advanced Queuing notification messages.
 *
 * <p>Notifications are enqueued via Oracle AQ ({@code DBMS_AQ.ENQUEUE})
 * and dequeued by the notification service.
 * Migration: replace with RabbitMQ or Kafka message DTOs.
 */
public class NotificationDTO {

    private Long notificationId;
    private Long recipientId;
    private String recipientType;   // CUSTOMER, EMPLOYEE
    private String messageType;     // EMAIL, SMS, PUSH, IN_APP
    private String subject;

    /** Maps to Oracle CLOB in notification queue table. */
    private String payload;

    private Integer priority;       // 1–10 (Oracle AQ message priority)
    private String status;          // QUEUED, PROCESSING, SENT, FAILED

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime enqueuedDate;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime processedDate;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime expiryDate;

    /** Oracle AQ message ID (RAW(16) internally). */
    private String messageId;

    private String errorMessage;
    private Integer retryCount;
    public NotificationDTO() {
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

    public String getRecipientType() {
        return recipientType;
    }

    public void setRecipientType(String recipientType) {
        this.recipientType = recipientType;
    }

    public String getMessageType() {
        return messageType;
    }

    public void setMessageType(String messageType) {
        this.messageType = messageType;
    }

    public String getSubject() {
        return subject;
    }

    public void setSubject(String subject) {
        this.subject = subject;
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

    public LocalDateTime getEnqueuedDate() {
        return enqueuedDate;
    }

    public void setEnqueuedDate(LocalDateTime enqueuedDate) {
        this.enqueuedDate = enqueuedDate;
    }

    public LocalDateTime getProcessedDate() {
        return processedDate;
    }

    public void setProcessedDate(LocalDateTime processedDate) {
        this.processedDate = processedDate;
    }

    public LocalDateTime getExpiryDate() {
        return expiryDate;
    }

    public void setExpiryDate(LocalDateTime expiryDate) {
        this.expiryDate = expiryDate;
    }

    public String getMessageId() {
        return messageId;
    }

    public void setMessageId(String messageId) {
        this.messageId = messageId;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public Integer getRetryCount() {
        return retryCount;
    }

    public void setRetryCount(Integer retryCount) {
        this.retryCount = retryCount;
    }

}
