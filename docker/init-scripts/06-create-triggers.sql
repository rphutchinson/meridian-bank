-- ============================================================
-- 06-create-triggers.sql
-- Audit triggers on major tables using Oracle-specific features
-- ORACLE-DEP [EMBEDDED_SQL:SYSTIMESTAMP] Triggers use SYSTIMESTAMP
-- ORACLE-DEP [EMBEDDED_SQL:SYSDATE] Triggers use SYSDATE
-- ORACLE-DEP [PLSQL_INVOCATION:ORACLE_TRIGGER] Database triggers called from app tier
-- ============================================================

ALTER SESSION SET CONTAINER = FREEPDB1;

-- ----------------------------------------------------------------
-- Helper procedure to write to audit log
-- Called by all audit triggers
-- ----------------------------------------------------------------
CREATE OR REPLACE PROCEDURE MERIDIAN.WRITE_AUDIT_LOG (
    p_table_name    IN VARCHAR2,
    p_operation     IN VARCHAR2,
    p_record_id     IN VARCHAR2,
    p_old_values    IN CLOB,
    p_new_values    IN CLOB
) AS
BEGIN
    INSERT INTO MERIDIAN.AUDIT_LOG (
        audit_id, table_name, operation_type, record_id,
        old_values, new_values, changed_by, changed_date
    ) VALUES (
        MERIDIAN.AUDIT_LOG_SEQ.NEXTVAL,      -- ORACLE-DEP [EMBEDDED_SQL:SEQUENCE]
        p_table_name,
        p_operation,
        p_record_id,
        p_old_values,
        p_new_values,
        NVL(SYS_CONTEXT('USERENV','CLIENT_IDENTIFIER'), USER),  -- ORACLE-DEP [EMBEDDED_SQL:NVL_FUNCTION]
        SYSTIMESTAMP                          -- ORACLE-DEP [EMBEDDED_SQL:SYSTIMESTAMP]
    );
END WRITE_AUDIT_LOG;
/

-- ----------------------------------------------------------------
-- CUSTOMERS audit trigger
-- ORACLE-DEP [PLSQL_INVOCATION:ORACLE_TRIGGER] :NEW/:OLD pseudorecords
-- ----------------------------------------------------------------
CREATE OR REPLACE TRIGGER MERIDIAN.TRG_CUSTOMERS_AUDIT
    AFTER INSERT OR UPDATE OR DELETE ON MERIDIAN.CUSTOMERS
    FOR EACH ROW
DECLARE
    v_old_values    CLOB;
    v_new_values    CLOB;
    v_operation     VARCHAR2(10);
    v_record_id     VARCHAR2(100);
BEGIN
    -- ORACLE-DEP [PLSQL_INVOCATION:ORACLE_TRIGGER] INSERTING/UPDATING/DELETING predicates
    IF INSERTING THEN
        v_operation := 'INSERT';
        v_record_id := TO_CHAR(:NEW.customer_id);
        v_new_values := '{"customer_id":' || :NEW.customer_id ||
                        ',"email":"' || :NEW.email || '"' ||
                        ',"status":"' || :NEW.status || '"}';
        v_old_values := NULL;
    ELSIF UPDATING THEN
        v_operation := 'UPDATE';
        v_record_id := TO_CHAR(:NEW.customer_id);
        v_old_values := '{"status":"' || :OLD.status ||
                        '","email":"' || :OLD.email || '"}';
        v_new_values := '{"status":"' || :NEW.status ||
                        '","email":"' || :NEW.email || '"}';
    ELSIF DELETING THEN
        v_operation := 'DELETE';
        v_record_id := TO_CHAR(:OLD.customer_id);
        v_old_values := '{"customer_id":' || :OLD.customer_id ||
                        ',"email":"' || :OLD.email || '"}';
        v_new_values := NULL;
    END IF;

    MERIDIAN.WRITE_AUDIT_LOG('CUSTOMERS', v_operation, v_record_id,
                              v_old_values, v_new_values);
EXCEPTION
    WHEN OTHERS THEN
        -- Audit failure should not block DML — log and continue
        NULL;
END TRG_CUSTOMERS_AUDIT;
/

-- ----------------------------------------------------------------
-- ACCOUNTS audit trigger
-- ----------------------------------------------------------------
CREATE OR REPLACE TRIGGER MERIDIAN.TRG_ACCOUNTS_AUDIT
    AFTER INSERT OR UPDATE OR DELETE ON MERIDIAN.ACCOUNTS
    FOR EACH ROW
DECLARE
    v_old_values    CLOB;
    v_new_values    CLOB;
    v_operation     VARCHAR2(10);
    v_record_id     VARCHAR2(100);
BEGIN
    IF INSERTING THEN
        v_operation := 'INSERT';
        v_record_id := TO_CHAR(:NEW.account_id);
        v_new_values := '{"account_id":' || :NEW.account_id ||
                        ',"account_number":"' || :NEW.account_number || '"' ||
                        ',"balance":' || :NEW.balance ||
                        ',"status":"' || :NEW.status || '"}';
        v_old_values := NULL;
    ELSIF UPDATING THEN
        v_operation := 'UPDATE';
        v_record_id := TO_CHAR(:NEW.account_id);
        -- ORACLE-DEP [EMBEDDED_SQL:TO_CHAR] Oracle TO_CHAR for number formatting
        v_old_values := '{"balance":' || TO_CHAR(:OLD.balance,'FM999999999999990.0000') ||
                        ',"status":"' || :OLD.status || '"}';
        v_new_values := '{"balance":' || TO_CHAR(:NEW.balance,'FM999999999999990.0000') ||
                        ',"status":"' || :NEW.status || '"}';
    ELSIF DELETING THEN
        v_operation := 'DELETE';
        v_record_id := TO_CHAR(:OLD.account_id);
        v_old_values := '{"account_id":' || :OLD.account_id ||
                        ',"account_number":"' || :OLD.account_number || '"}';
        v_new_values := NULL;
    END IF;

    MERIDIAN.WRITE_AUDIT_LOG('ACCOUNTS', v_operation, v_record_id,
                              v_old_values, v_new_values);
EXCEPTION
    WHEN OTHERS THEN NULL;
END TRG_ACCOUNTS_AUDIT;
/

-- ----------------------------------------------------------------
-- TRANSACTIONS audit trigger
-- ----------------------------------------------------------------
CREATE OR REPLACE TRIGGER MERIDIAN.TRG_TRANSACTIONS_AUDIT
    AFTER INSERT OR UPDATE OR DELETE ON MERIDIAN.TRANSACTIONS
    FOR EACH ROW
DECLARE
    v_old_values    CLOB;
    v_new_values    CLOB;
    v_operation     VARCHAR2(10);
    v_record_id     VARCHAR2(100);
BEGIN
    IF INSERTING THEN
        v_operation := 'INSERT';
        v_record_id := TO_CHAR(:NEW.transaction_id);
        v_new_values := '{"transaction_id":' || :NEW.transaction_id ||
                        ',"account_id":' || :NEW.account_id ||
                        ',"amount":' || :NEW.amount ||
                        ',"type":"' || :NEW.transaction_type || '"' ||
                        ',"status":"' || :NEW.status || '"}';
        v_old_values := NULL;
    ELSIF UPDATING THEN
        v_operation := 'UPDATE';
        v_record_id := TO_CHAR(:NEW.transaction_id);
        v_old_values := '{"status":"' || :OLD.status || '"}';
        v_new_values := '{"status":"' || :NEW.status || '"}';
    ELSIF DELETING THEN
        v_operation := 'DELETE';
        v_record_id := TO_CHAR(:OLD.transaction_id);
        v_old_values := '{"transaction_id":' || :OLD.transaction_id || '}';
        v_new_values := NULL;
    END IF;

    MERIDIAN.WRITE_AUDIT_LOG('TRANSACTIONS', v_operation, v_record_id,
                              v_old_values, v_new_values);
EXCEPTION
    WHEN OTHERS THEN NULL;
END TRG_TRANSACTIONS_AUDIT;
/

-- ----------------------------------------------------------------
-- ACCOUNTS: auto-update updated_date on modification
-- ORACLE-DEP [EMBEDDED_SQL:SYSDATE] Auto-timestamp via trigger
-- ----------------------------------------------------------------
CREATE OR REPLACE TRIGGER MERIDIAN.TRG_ACCOUNTS_UPDATED
    BEFORE UPDATE ON MERIDIAN.ACCOUNTS
    FOR EACH ROW
BEGIN
    :NEW.updated_date := SYSDATE;  -- ORACLE-DEP [EMBEDDED_SQL:SYSDATE]
END TRG_ACCOUNTS_UPDATED;
/

-- ----------------------------------------------------------------
-- CUSTOMERS: auto-update updated_date on modification
-- ----------------------------------------------------------------
CREATE OR REPLACE TRIGGER MERIDIAN.TRG_CUSTOMERS_UPDATED
    BEFORE UPDATE ON MERIDIAN.CUSTOMERS
    FOR EACH ROW
BEGIN
    :NEW.updated_date := SYSDATE;  -- ORACLE-DEP [EMBEDDED_SQL:SYSDATE]
END TRG_CUSTOMERS_UPDATED;
/
