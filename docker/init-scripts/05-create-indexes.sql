-- ============================================================
-- 05-create-indexes.sql
-- Standard, bitmap, function-based, and Oracle Text indexes
-- ORACLE-DEP [EMBEDDED_SQL:ORACLE_HINTS] Indexes referenced by optimizer hints
-- ============================================================

ALTER SESSION SET CONTAINER = FREEPDB1;

-- ----------------------------------------------------------------
-- CUSTOMERS indexes
-- ----------------------------------------------------------------
CREATE INDEX MERIDIAN.IDX_CUST_LAST_NAME
    ON MERIDIAN.CUSTOMERS (last_name);                       -- used in AccountRepository LIKE queries

CREATE INDEX MERIDIAN.IDX_CUST_EMAIL
    ON MERIDIAN.CUSTOMERS (email);

-- Function-based index on UPPER(last_name) for case-insensitive search
-- ORACLE-DEP [EMBEDDED_SQL:FUNCTION_BASED_INDEX] Oracle function-based index
CREATE INDEX MERIDIAN.IDX_CUST_UPPER_LAST
    ON MERIDIAN.CUSTOMERS (UPPER(last_name));

CREATE INDEX MERIDIAN.IDX_CUST_STATUS
    ON MERIDIAN.CUSTOMERS (status);

-- ----------------------------------------------------------------
-- ACCOUNTS indexes
-- ----------------------------------------------------------------
CREATE INDEX MERIDIAN.IDX_ACCT_CUSTOMER
    ON MERIDIAN.ACCOUNTS (customer_id);                      -- FK index

CREATE INDEX MERIDIAN.IDX_ACCT_BRANCH
    ON MERIDIAN.ACCOUNTS (branch_id);

CREATE INDEX MERIDIAN.IDX_ACCT_STATUS_TYPE
    ON MERIDIAN.ACCOUNTS (status, account_type);

-- ORACLE-DEP [EMBEDDED_SQL:BITMAP_INDEX] Bitmap index on low-cardinality status column
CREATE BITMAP INDEX MERIDIAN.BMP_ACCT_STATUS
    ON MERIDIAN.ACCOUNTS (status);

CREATE BITMAP INDEX MERIDIAN.BMP_ACCT_TYPE
    ON MERIDIAN.ACCOUNTS (account_type);

-- ----------------------------------------------------------------
-- TRANSACTIONS indexes — performance-critical, used with hints
-- ----------------------------------------------------------------
CREATE INDEX MERIDIAN.IDX_TXN_ACCOUNT_DATE
    ON MERIDIAN.TRANSACTIONS (account_id, transaction_date DESC);

CREATE INDEX MERIDIAN.IDX_TXN_DATE
    ON MERIDIAN.TRANSACTIONS (transaction_date DESC);        -- ORACLE-DEP [EMBEDDED_SQL:ORACLE_HINTS] INDEX hint target

CREATE INDEX MERIDIAN.IDX_TXN_STATUS
    ON MERIDIAN.TRANSACTIONS (status);

CREATE INDEX MERIDIAN.IDX_TXN_CATEGORY
    ON MERIDIAN.TRANSACTIONS (category_code);

-- ORACLE-DEP [EMBEDDED_SQL:BITMAP_INDEX] Bitmap on transaction status
CREATE BITMAP INDEX MERIDIAN.BMP_TXN_STATUS
    ON MERIDIAN.TRANSACTIONS (status);

CREATE BITMAP INDEX MERIDIAN.BMP_TXN_TYPE
    ON MERIDIAN.TRANSACTIONS (transaction_type);

-- ----------------------------------------------------------------
-- CREDIT_CARDS indexes
-- ----------------------------------------------------------------
CREATE INDEX MERIDIAN.IDX_CARD_CUSTOMER
    ON MERIDIAN.CREDIT_CARDS (customer_id);

CREATE INDEX MERIDIAN.IDX_CARD_ACCOUNT
    ON MERIDIAN.CREDIT_CARDS (account_id);

-- ----------------------------------------------------------------
-- EMPLOYEES indexes (hierarchy queries)
-- ----------------------------------------------------------------
CREATE INDEX MERIDIAN.IDX_EMP_MANAGER
    ON MERIDIAN.EMPLOYEES (manager_id);                      -- critical for CONNECT BY PRIOR

CREATE INDEX MERIDIAN.IDX_EMP_DEPT
    ON MERIDIAN.EMPLOYEES (department_id);

CREATE INDEX MERIDIAN.IDX_EMP_BRANCH
    ON MERIDIAN.EMPLOYEES (branch_id);

-- ----------------------------------------------------------------
-- LOAN_APPLICATIONS indexes
-- ----------------------------------------------------------------
CREATE INDEX MERIDIAN.IDX_LOAN_CUSTOMER
    ON MERIDIAN.LOAN_APPLICATIONS (customer_id);

CREATE INDEX MERIDIAN.IDX_LOAN_STATUS
    ON MERIDIAN.LOAN_APPLICATIONS (status);

CREATE BITMAP INDEX MERIDIAN.BMP_LOAN_STATUS
    ON MERIDIAN.LOAN_APPLICATIONS (status);

-- ----------------------------------------------------------------
-- AUDIT_LOG indexes
-- ----------------------------------------------------------------
CREATE INDEX MERIDIAN.IDX_AUDIT_TABLE_RECORD
    ON MERIDIAN.AUDIT_LOG (table_name, record_id);

CREATE INDEX MERIDIAN.IDX_AUDIT_DATE
    ON MERIDIAN.AUDIT_LOG (changed_date DESC);

-- ----------------------------------------------------------------
-- DOCUMENTS — Oracle Text CONTEXT index placeholder
-- Actual CTX index created in 09-create-text-indexes.sql after data load
-- ----------------------------------------------------------------
CREATE INDEX MERIDIAN.IDX_DOC_CUSTOMER
    ON MERIDIAN.DOCUMENTS (customer_id);

CREATE INDEX MERIDIAN.IDX_DOC_CATEGORY
    ON MERIDIAN.DOCUMENTS (category);

-- ----------------------------------------------------------------
-- NOTIFICATIONS
-- ----------------------------------------------------------------
CREATE INDEX MERIDIAN.IDX_NOTIF_RECIPIENT
    ON MERIDIAN.NOTIFICATIONS (recipient_id);

CREATE INDEX MERIDIAN.IDX_NOTIF_STATUS_DATE
    ON MERIDIAN.NOTIFICATIONS (status, enqueued_date);

-- ----------------------------------------------------------------
-- SPATIAL indexes (created after SDO metadata in script 10)
-- Placeholder — actual creation in 10-create-spatial-metadata.sql
-- ----------------------------------------------------------------
-- CREATE INDEX MERIDIAN.IDX_BRANCH_LOCATION ON MERIDIAN.BRANCHES (location)
--     INDEXTYPE IS MDSYS.SPATIAL_INDEX;
