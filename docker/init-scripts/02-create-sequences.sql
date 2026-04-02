-- ============================================================
-- 02-create-sequences.sql
-- All Oracle sequences used by Meridian Bank
-- ORACLE-DEP [EMBEDDED_SQL:SEQUENCE] Oracle sequence objects
-- ============================================================

ALTER SESSION SET CONTAINER = FREEPDB1;

-- Customer sequence: starts at 1000 for readable IDs
-- ORACLE-DEP [EMBEDDED_SQL:SEQUENCE] customer_seq.NEXTVAL used in CustomerDAO
CREATE SEQUENCE MERIDIAN.CUSTOMER_SEQ
    START WITH 1000
    INCREMENT BY 1
    NOCACHE
    NOCYCLE;

-- Address sequence
-- ORACLE-DEP [EMBEDDED_SQL:SEQUENCE] address_seq.NEXTVAL used in AddressDAO
CREATE SEQUENCE MERIDIAN.ADDRESS_SEQ
    START WITH 4000
    INCREMENT BY 1
    NOCACHE
    NOCYCLE;

-- Account sequence: starts at 2000
-- ORACLE-DEP [EMBEDDED_SQL:SEQUENCE] account_seq.NEXTVAL / CURRVAL used in AccountDAO
CREATE SEQUENCE MERIDIAN.ACCOUNT_SEQ
    START WITH 2000
    INCREMENT BY 1
    NOCACHE
    NOCYCLE;

-- Transaction sequence: high-volume, use cache
-- ORACLE-DEP [EMBEDDED_SQL:SEQUENCE] transaction_seq.NEXTVAL used in TransactionDAO
CREATE SEQUENCE MERIDIAN.TRANSACTION_SEQ
    START WITH 10000
    INCREMENT BY 1
    CACHE 100
    NOCYCLE;

-- Transaction reference number sequence (separate from PK)
-- ORACLE-DEP [EMBEDDED_SQL:SEQUENCE] transaction_ref_seq used for business reference numbers
CREATE SEQUENCE MERIDIAN.TRANSACTION_REF_SEQ
    START WITH 100000
    INCREMENT BY 1
    CACHE 50
    NOCYCLE;

-- Credit card sequence
-- ORACLE-DEP [EMBEDDED_SQL:SEQUENCE] credit_card_seq.NEXTVAL used in CreditCardDAO
CREATE SEQUENCE MERIDIAN.CREDIT_CARD_SEQ
    START WITH 3000
    INCREMENT BY 1
    NOCACHE
    NOCYCLE;

-- Branch sequence
-- ORACLE-DEP [EMBEDDED_SQL:SEQUENCE] branch_seq.NEXTVAL used in BranchDAO
CREATE SEQUENCE MERIDIAN.BRANCH_SEQ
    START WITH 100
    INCREMENT BY 1
    NOCACHE
    NOCYCLE;

-- Employee sequence
-- ORACLE-DEP [EMBEDDED_SQL:SEQUENCE] employee_seq.NEXTVAL used in EmployeeDAO
CREATE SEQUENCE MERIDIAN.EMPLOYEE_SEQ
    START WITH 500
    INCREMENT BY 1
    NOCACHE
    NOCYCLE;

-- Loan application sequence
-- ORACLE-DEP [EMBEDDED_SQL:SEQUENCE] loan_app_seq.NEXTVAL used in LoanDAO
CREATE SEQUENCE MERIDIAN.LOAN_APP_SEQ
    START WITH 7000
    INCREMENT BY 1
    NOCACHE
    NOCYCLE;

-- Audit log sequence: high-volume, use cache
-- ORACLE-DEP [EMBEDDED_SQL:SEQUENCE] audit_log_seq.NEXTVAL used in AuditDAO and DB triggers
CREATE SEQUENCE MERIDIAN.AUDIT_LOG_SEQ
    START WITH 1
    INCREMENT BY 1
    CACHE 200
    NOCYCLE;

-- Document sequence
-- ORACLE-DEP [EMBEDDED_SQL:SEQUENCE] document_seq.NEXTVAL used in DocumentDAO
CREATE SEQUENCE MERIDIAN.DOCUMENT_SEQ
    START WITH 8000
    INCREMENT BY 1
    NOCACHE
    NOCYCLE;

-- Notification sequence
-- ORACLE-DEP [EMBEDDED_SQL:SEQUENCE] notification_seq.NEXTVAL used in NotificationDAO
CREATE SEQUENCE MERIDIAN.NOTIFICATION_SEQ
    START WITH 9000
    INCREMENT BY 1
    CACHE 20
    NOCYCLE;

-- Scheduled job sequence
-- ORACLE-DEP [EMBEDDED_SQL:SEQUENCE] scheduled_job_seq.NEXTVAL used in BatchJobService
CREATE SEQUENCE MERIDIAN.SCHEDULED_JOB_SEQ
    START WITH 200
    INCREMENT BY 1
    NOCACHE
    NOCYCLE;
