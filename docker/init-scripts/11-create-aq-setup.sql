-- ============================================================
-- 11-create-aq-setup.sql
-- Oracle Advanced Queuing setup for Notification service
-- ORACLE-DEP [PLSQL_INVOCATION:DBMS_AQ] DBMS_AQ / DBMS_AQADM calls
-- ORACLE-DEP [PLSQL_INVOCATION:ORACLE_AQ_JMS] Oracle AQ JMS integration
-- ============================================================

ALTER SESSION SET CONTAINER = FREEPDB1;

-- ----------------------------------------------------------------
-- Create JMS queue table for text messages
-- ORACLE-DEP [PLSQL_INVOCATION:DBMS_AQADM] DBMS_AQADM.CREATE_QUEUE_TABLE
-- ----------------------------------------------------------------
BEGIN
    DBMS_AQADM.CREATE_QUEUE_TABLE(
        queue_table         => 'MERIDIAN.NOTIFICATION_QT',
        queue_payload_type  => 'SYS.AQ$_JMS_TEXT_MESSAGE',
        sort_list           => 'PRIORITY,ENQ_TIME',
        multiple_consumers  => FALSE,
        message_grouping    => DBMS_AQADM.NONE,
        comment             => 'Meridian Bank notification queue table'
    );
EXCEPTION
    WHEN OTHERS THEN
        IF SQLCODE != -24001 THEN RAISE; END IF;  -- ignore "already exists"
END;
/

-- ----------------------------------------------------------------
-- Create the notification queue
-- ORACLE-DEP [PLSQL_INVOCATION:DBMS_AQADM] DBMS_AQADM.CREATE_QUEUE
-- ----------------------------------------------------------------
BEGIN
    DBMS_AQADM.CREATE_QUEUE(
        queue_name          => 'MERIDIAN.NOTIFICATION_QUEUE',
        queue_table         => 'MERIDIAN.NOTIFICATION_QT',
        queue_type          => DBMS_AQADM.NORMAL_QUEUE,
        max_retries         => 3,
        retry_delay         => 60,   -- retry after 60 seconds
        retention_time      => 86400, -- retain for 24 hours after dequeue
        comment             => 'Meridian Bank customer notification queue'
    );
EXCEPTION
    WHEN OTHERS THEN
        IF SQLCODE != -24006 THEN RAISE; END IF;
END;
/

-- ----------------------------------------------------------------
-- Start the queue (enables enqueue and dequeue)
-- ORACLE-DEP [PLSQL_INVOCATION:DBMS_AQADM] DBMS_AQADM.START_QUEUE
-- ----------------------------------------------------------------
BEGIN
    DBMS_AQADM.START_QUEUE(
        queue_name  => 'MERIDIAN.NOTIFICATION_QUEUE',
        enqueue     => TRUE,
        dequeue     => TRUE
    );
EXCEPTION
    WHEN OTHERS THEN
        IF SQLCODE != -24010 THEN RAISE; END IF;
END;
/

-- ----------------------------------------------------------------
-- Create a priority exception queue (for dead-letter / failed messages)
-- ORACLE-DEP [PLSQL_INVOCATION:DBMS_AQADM] Exception queue for undeliverable messages
-- ----------------------------------------------------------------
BEGIN
    DBMS_AQADM.CREATE_QUEUE(
        queue_name  => 'MERIDIAN.NOTIFICATION_EXCEPTION_QUEUE',
        queue_table => 'MERIDIAN.NOTIFICATION_QT',
        queue_type  => DBMS_AQADM.EXCEPTION_QUEUE,
        comment     => 'Dead-letter queue for failed notification messages'
    );
EXCEPTION
    WHEN OTHERS THEN
        IF SQLCODE != -24006 THEN RAISE; END IF;
END;
/

-- Grant enqueue/dequeue privileges to MERIDIAN user
BEGIN
    DBMS_AQADM.GRANT_QUEUE_PRIVILEGE(
        privilege  => 'ALL',
        queue_name => 'MERIDIAN.NOTIFICATION_QUEUE',
        grantee    => 'MERIDIAN',
        grant_option => FALSE
    );
EXCEPTION
    WHEN OTHERS THEN NULL;
END;
/

COMMIT;
