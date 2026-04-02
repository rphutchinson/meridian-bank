-- ============================================================
-- 04-create-tables.sql
-- All Meridian Bank tables using Oracle-specific data types
-- ORACLE-DEP [EMBEDDED_SQL:VARCHAR2_TYPE] Oracle VARCHAR2 throughout
-- ORACLE-DEP [EMBEDDED_SQL:NUMBER_TYPE] Oracle NUMBER(p,s) throughout
-- ORACLE-DEP [EMBEDDED_SQL:DATE_TYPE] Oracle DATE type (date+time)
-- ORACLE-DEP [EMBEDDED_SQL:TIMESTAMP_TZ] Oracle TIMESTAMP WITH TIME ZONE
-- ORACLE-DEP [EMBEDDED_SQL:CLOB_TYPE] Oracle CLOB for large text
-- ORACLE-DEP [EMBEDDED_SQL:BLOB_TYPE] Oracle BLOB for binary data
-- ORACLE-DEP [EMBEDDED_SQL:RAW_TYPE] Oracle RAW for encrypted data
-- ============================================================

ALTER SESSION SET CONTAINER = FREEPDB1;

-- ----------------------------------------------------------------
-- ADDRESSES
-- ORACLE-DEP [EMBEDDED_SQL:SDO_GEOMETRY] Oracle Spatial geometry type
-- ----------------------------------------------------------------
CREATE TABLE MERIDIAN.ADDRESSES (
    address_id      NUMBER(19)          NOT NULL,  -- ORACLE-DEP [EMBEDDED_SQL:NUMBER_TYPE]
    street          VARCHAR2(200)       NOT NULL,  -- ORACLE-DEP [EMBEDDED_SQL:VARCHAR2_TYPE]
    city            VARCHAR2(100)       NOT NULL,
    state_code      VARCHAR2(50)        NOT NULL,
    zip_code        VARCHAR2(20)        NOT NULL,
    country         VARCHAR2(50)        DEFAULT 'USA' NOT NULL,
    geo_location    MDSYS.SDO_GEOMETRY,            -- ORACLE-DEP [EMBEDDED_SQL:SDO_GEOMETRY]
    created_date    DATE                DEFAULT SYSDATE NOT NULL,  -- ORACLE-DEP [EMBEDDED_SQL:SYSDATE]
    updated_date    DATE,
    CONSTRAINT pk_addresses PRIMARY KEY (address_id)
);

-- ----------------------------------------------------------------
-- CUSTOMERS
-- ORACLE-DEP [EMBEDDED_SQL:RAW_TYPE] RAW(256) for encrypted SSN
-- ----------------------------------------------------------------
CREATE TABLE MERIDIAN.CUSTOMERS (
    customer_id     NUMBER(19)          NOT NULL,  -- ORACLE-DEP [EMBEDDED_SQL:NUMBER_TYPE]
    first_name      VARCHAR2(100)       NOT NULL,  -- ORACLE-DEP [EMBEDDED_SQL:VARCHAR2_TYPE]
    last_name       VARCHAR2(100)       NOT NULL,
    email           VARCHAR2(200)       NOT NULL,
    phone           VARCHAR2(20),                  -- nullable: demonstrates NVL usage
    date_of_birth   DATE                NOT NULL,  -- ORACLE-DEP [EMBEDDED_SQL:DATE_TYPE]
    ssn_encrypted   RAW(256),                      -- ORACLE-DEP [EMBEDDED_SQL:RAW_TYPE] encrypted via DBMS_CRYPTO
    ssn_hash        RAW(32),                       -- ORACLE-DEP [EMBEDDED_SQL:RAW_TYPE] hash for lookup
    address_id      NUMBER(19),
    status          VARCHAR2(20)        DEFAULT 'ACTIVE' NOT NULL,
    credit_score    NUMBER(4),                     -- ORACLE-DEP [EMBEDDED_SQL:NUMBER_TYPE]
    created_date    DATE                DEFAULT SYSDATE NOT NULL,  -- ORACLE-DEP [EMBEDDED_SQL:SYSDATE]
    updated_date    DATE,
    CONSTRAINT pk_customers PRIMARY KEY (customer_id),
    CONSTRAINT uq_customers_email UNIQUE (email),
    CONSTRAINT fk_cust_address FOREIGN KEY (address_id) REFERENCES MERIDIAN.ADDRESSES(address_id),
    CONSTRAINT chk_cust_status CHECK (status IN ('ACTIVE','INACTIVE','SUSPENDED','CLOSED'))
);

-- ----------------------------------------------------------------
-- BRANCHES
-- ORACLE-DEP [EMBEDDED_SQL:SDO_GEOMETRY] branch geographic location
-- ----------------------------------------------------------------
CREATE TABLE MERIDIAN.BRANCHES (
    branch_id       NUMBER(19)          NOT NULL,
    branch_name     VARCHAR2(200)       NOT NULL,
    branch_code     VARCHAR2(20)        NOT NULL,
    manager_id      NUMBER(19),
    location        MDSYS.SDO_GEOMETRY,            -- ORACLE-DEP [EMBEDDED_SQL:SDO_GEOMETRY]
    region          VARCHAR2(50),
    phone           VARCHAR2(20),
    email           VARCHAR2(200),
    address_id      NUMBER(19),
    status          VARCHAR2(20)        DEFAULT 'OPEN' NOT NULL,
    created_date    DATE                DEFAULT SYSDATE NOT NULL,
    CONSTRAINT pk_branches PRIMARY KEY (branch_id),
    CONSTRAINT uq_branches_code UNIQUE (branch_code),
    CONSTRAINT fk_branch_address FOREIGN KEY (address_id) REFERENCES MERIDIAN.ADDRESSES(address_id),
    CONSTRAINT chk_branch_status CHECK (status IN ('OPEN','CLOSED','RENOVATING'))
);

-- ----------------------------------------------------------------
-- EMPLOYEES (self-referencing hierarchy for CONNECT BY queries)
-- ORACLE-DEP [EMBEDDED_SQL:CONNECT_BY] Self-ref FK enables CONNECT BY PRIOR
-- ----------------------------------------------------------------
CREATE TABLE MERIDIAN.EMPLOYEES (
    employee_id     NUMBER(19)          NOT NULL,
    first_name      VARCHAR2(100)       NOT NULL,
    last_name       VARCHAR2(100)       NOT NULL,
    email           VARCHAR2(200)       NOT NULL,
    manager_id      NUMBER(19),                    -- ORACLE-DEP [EMBEDDED_SQL:CONNECT_BY] self-ref for hierarchy
    department_id   NUMBER(10)          NOT NULL,
    hire_date       DATE                NOT NULL,  -- ORACLE-DEP [EMBEDDED_SQL:DATE_TYPE]
    salary          NUMBER(12,2)        NOT NULL,  -- ORACLE-DEP [EMBEDDED_SQL:NUMBER_TYPE]
    job_title       VARCHAR2(100)       NOT NULL,
    level_num       NUMBER(3)           DEFAULT 1,
    branch_id       NUMBER(19),
    status          VARCHAR2(20)        DEFAULT 'ACTIVE' NOT NULL,
    created_date    DATE                DEFAULT SYSDATE NOT NULL,
    CONSTRAINT pk_employees PRIMARY KEY (employee_id),
    CONSTRAINT uq_employees_email UNIQUE (email),
    CONSTRAINT fk_emp_manager FOREIGN KEY (manager_id) REFERENCES MERIDIAN.EMPLOYEES(employee_id) DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT fk_emp_branch FOREIGN KEY (branch_id) REFERENCES MERIDIAN.BRANCHES(branch_id),
    CONSTRAINT chk_emp_status CHECK (status IN ('ACTIVE','INACTIVE','TERMINATED'))
);

-- Back-fill branch manager FK now that employees table exists
ALTER TABLE MERIDIAN.BRANCHES
    ADD CONSTRAINT fk_branch_manager FOREIGN KEY (manager_id) REFERENCES MERIDIAN.EMPLOYEES(employee_id);

-- ----------------------------------------------------------------
-- ACCOUNTS
-- ORACLE-DEP [EMBEDDED_SQL:NUMBER_TYPE] NUMBER(19,4) for monetary balance
-- ----------------------------------------------------------------
CREATE TABLE MERIDIAN.ACCOUNTS (
    account_id      NUMBER(19)          NOT NULL,
    account_number  VARCHAR2(20)        NOT NULL,
    account_type    VARCHAR2(20)        NOT NULL,
    balance         NUMBER(19,4)        DEFAULT 0 NOT NULL,  -- ORACLE-DEP [EMBEDDED_SQL:NUMBER_TYPE]
    status          VARCHAR2(20)        DEFAULT 'ACTIVE' NOT NULL,
    open_date       DATE                DEFAULT SYSDATE NOT NULL,
    close_date      DATE,
    customer_id     NUMBER(19)          NOT NULL,
    branch_id       NUMBER(19)          NOT NULL,
    interest_rate   NUMBER(6,4)         DEFAULT 0,
    overdraft_limit NUMBER(12,2)        DEFAULT 0,
    created_date    DATE                DEFAULT SYSDATE NOT NULL,
    updated_date    DATE,
    CONSTRAINT pk_accounts PRIMARY KEY (account_id),
    CONSTRAINT uq_accounts_number UNIQUE (account_number),
    CONSTRAINT fk_acct_customer FOREIGN KEY (customer_id) REFERENCES MERIDIAN.CUSTOMERS(customer_id),
    CONSTRAINT fk_acct_branch FOREIGN KEY (branch_id) REFERENCES MERIDIAN.BRANCHES(branch_id),
    CONSTRAINT chk_acct_type CHECK (account_type IN ('CHECKING','SAVINGS','MONEY_MARKET','CD','IRA')),
    CONSTRAINT chk_acct_status CHECK (status IN ('ACTIVE','INACTIVE','FROZEN','CLOSED'))
);

-- ----------------------------------------------------------------
-- TRANSACTIONS
-- ORACLE-DEP [EMBEDDED_SQL:TIMESTAMP_TZ] Oracle TIMESTAMP WITH TIME ZONE
-- ----------------------------------------------------------------
CREATE TABLE MERIDIAN.TRANSACTIONS (
    transaction_id      NUMBER(19)                  NOT NULL,
    account_id          NUMBER(19)                  NOT NULL,
    amount              NUMBER(19,4)                NOT NULL,  -- ORACLE-DEP [EMBEDDED_SQL:NUMBER_TYPE]
    transaction_type    VARCHAR2(20)                NOT NULL,
    description         VARCHAR2(500),
    transaction_date    TIMESTAMP WITH TIME ZONE    DEFAULT SYSTIMESTAMP NOT NULL,  -- ORACLE-DEP [EMBEDDED_SQL:SYSTIMESTAMP]
    status              VARCHAR2(20)                DEFAULT 'COMPLETED' NOT NULL,
    reference_number    VARCHAR2(50),
    counterparty_id     NUMBER(19),
    category_code       VARCHAR2(50),
    created_date        DATE                        DEFAULT SYSDATE NOT NULL,
    CONSTRAINT pk_transactions PRIMARY KEY (transaction_id),
    CONSTRAINT fk_txn_account FOREIGN KEY (account_id) REFERENCES MERIDIAN.ACCOUNTS(account_id),
    CONSTRAINT chk_txn_type CHECK (transaction_type IN ('CREDIT','DEBIT','TRANSFER','FEE','INTEREST','ADJUSTMENT')),
    CONSTRAINT chk_txn_status CHECK (status IN ('PENDING','COMPLETED','FAILED','REVERSED'))
);

-- ----------------------------------------------------------------
-- CREDIT_CARDS
-- ORACLE-DEP [EMBEDDED_SQL:NUMBER_TYPE] NUMBER(16) for card numbers
-- ----------------------------------------------------------------
CREATE TABLE MERIDIAN.CREDIT_CARDS (
    card_id             NUMBER(19)          NOT NULL,
    card_number         VARCHAR2(20)        NOT NULL,  -- stored masked
    account_id          NUMBER(19)          NOT NULL,
    customer_id         NUMBER(19)          NOT NULL,
    credit_limit        NUMBER(12,2)        NOT NULL,
    current_balance     NUMBER(12,2)        DEFAULT 0 NOT NULL,
    expiry_date         DATE                NOT NULL,  -- ORACLE-DEP [EMBEDDED_SQL:DATE_TYPE]
    status              VARCHAR2(20)        DEFAULT 'ACTIVE' NOT NULL,
    reward_points       NUMBER(12)          DEFAULT 0,
    card_type           VARCHAR2(20)        DEFAULT 'STANDARD',
    issued_date         DATE                DEFAULT SYSDATE,
    CONSTRAINT pk_credit_cards PRIMARY KEY (card_id),
    CONSTRAINT uq_card_number UNIQUE (card_number),
    CONSTRAINT fk_card_account FOREIGN KEY (account_id) REFERENCES MERIDIAN.ACCOUNTS(account_id),
    CONSTRAINT fk_card_customer FOREIGN KEY (customer_id) REFERENCES MERIDIAN.CUSTOMERS(customer_id),
    CONSTRAINT chk_card_type CHECK (card_type IN ('STANDARD','GOLD','PLATINUM','TITANIUM')),
    CONSTRAINT chk_card_status CHECK (status IN ('ACTIVE','INACTIVE','BLOCKED','CANCELLED'))
);

-- ----------------------------------------------------------------
-- LOAN_APPLICATIONS
-- ORACLE-DEP [EMBEDDED_SQL:CLOB_TYPE] CLOB for underwriting notes
-- ORACLE-DEP [EMBEDDED_SQL:EMPTY_CLOB] EMPTY_CLOB() initializer
-- ----------------------------------------------------------------
CREATE TABLE MERIDIAN.LOAN_APPLICATIONS (
    application_id      NUMBER(19)          NOT NULL,
    customer_id         NUMBER(19)          NOT NULL,
    loan_type           VARCHAR2(30)        NOT NULL,
    requested_amount    NUMBER(15,2)        NOT NULL,
    approved_amount     NUMBER(15,2),
    status              VARCHAR2(20)        DEFAULT 'SUBMITTED' NOT NULL,
    application_date    DATE                DEFAULT SYSDATE NOT NULL,
    decision_date       DATE,
    interest_rate       NUMBER(6,4),
    term_months         NUMBER(5),
    notes               CLOB,                          -- ORACLE-DEP [EMBEDDED_SQL:CLOB_TYPE]
    officer_id          NUMBER(19),
    created_date        DATE                DEFAULT SYSDATE NOT NULL,
    CONSTRAINT pk_loan_applications PRIMARY KEY (application_id),
    CONSTRAINT fk_loan_customer FOREIGN KEY (customer_id) REFERENCES MERIDIAN.CUSTOMERS(customer_id),
    CONSTRAINT fk_loan_officer FOREIGN KEY (officer_id) REFERENCES MERIDIAN.EMPLOYEES(employee_id),
    CONSTRAINT chk_loan_type CHECK (loan_type IN ('MORTGAGE','AUTO','PERSONAL','STUDENT','BUSINESS','HOME_EQUITY')),
    CONSTRAINT chk_loan_status CHECK (status IN ('SUBMITTED','UNDER_REVIEW','APPROVED','DENIED','FUNDED','CLOSED'))
);

-- ----------------------------------------------------------------
-- AUDIT_LOG
-- ORACLE-DEP [EMBEDDED_SQL:CLOB_TYPE] CLOBs for old/new JSON values
-- ORACLE-DEP [EMBEDDED_SQL:SYSTIMESTAMP] Oracle SYSTIMESTAMP for audit precision
-- ----------------------------------------------------------------
CREATE TABLE MERIDIAN.AUDIT_LOG (
    audit_id        NUMBER(19)                  NOT NULL,
    table_name      VARCHAR2(100)               NOT NULL,
    operation_type  VARCHAR2(10)                NOT NULL,
    record_id       VARCHAR2(100)               NOT NULL,
    old_values      CLOB,                                   -- ORACLE-DEP [EMBEDDED_SQL:CLOB_TYPE]
    new_values      CLOB,                                   -- ORACLE-DEP [EMBEDDED_SQL:CLOB_TYPE]
    changed_by      VARCHAR2(100)               NOT NULL,
    changed_date    TIMESTAMP WITH TIME ZONE    DEFAULT SYSTIMESTAMP NOT NULL,  -- ORACLE-DEP [EMBEDDED_SQL:SYSTIMESTAMP]
    ip_address      VARCHAR2(50),
    session_id      VARCHAR2(100),
    CONSTRAINT pk_audit_log PRIMARY KEY (audit_id),
    CONSTRAINT chk_audit_op CHECK (operation_type IN ('INSERT','UPDATE','DELETE'))
);

-- ----------------------------------------------------------------
-- DOCUMENTS (Oracle Text target)
-- ORACLE-DEP [EMBEDDED_SQL:CLOB_TYPE] CLOB for full-text indexed content
-- ORACLE-DEP [EMBEDDED_SQL:BLOB_TYPE] BLOB for binary document data
-- ----------------------------------------------------------------
CREATE TABLE MERIDIAN.DOCUMENTS (
    document_id     NUMBER(19)          NOT NULL,
    title           VARCHAR2(500)       NOT NULL,
    content         CLOB,                          -- ORACLE-DEP [EMBEDDED_SQL:CLOB_TYPE] Oracle Text CONTEXT index
    document_data   BLOB,                          -- ORACLE-DEP [EMBEDDED_SQL:BLOB_TYPE]
    category        VARCHAR2(50),
    mime_type       VARCHAR2(100),
    customer_id     NUMBER(19),
    indexed_date    DATE,
    created_date    DATE                DEFAULT SYSDATE NOT NULL,
    status          VARCHAR2(20)        DEFAULT 'ACTIVE',
    CONSTRAINT pk_documents PRIMARY KEY (document_id),
    CONSTRAINT fk_doc_customer FOREIGN KEY (customer_id) REFERENCES MERIDIAN.CUSTOMERS(customer_id),
    CONSTRAINT chk_doc_status CHECK (status IN ('ACTIVE','ARCHIVED','DELETED'))
);

-- ----------------------------------------------------------------
-- NOTIFICATIONS (Oracle AQ message tracking)
-- ORACLE-DEP [EMBEDDED_SQL:CLOB_TYPE] CLOB for message payload
-- ----------------------------------------------------------------
CREATE TABLE MERIDIAN.NOTIFICATIONS (
    notification_id     NUMBER(19)                  NOT NULL,
    recipient_id        NUMBER(19)                  NOT NULL,
    message_type        VARCHAR2(50)                NOT NULL,
    payload             CLOB                        DEFAULT EMPTY_CLOB(),  -- ORACLE-DEP [EMBEDDED_SQL:EMPTY_CLOB]
    priority            NUMBER(3)                   DEFAULT 5,
    status              VARCHAR2(20)                DEFAULT 'PENDING' NOT NULL,
    enqueued_date       TIMESTAMP WITH TIME ZONE    DEFAULT SYSTIMESTAMP,  -- ORACLE-DEP [EMBEDDED_SQL:SYSTIMESTAMP]
    processed_date      TIMESTAMP WITH TIME ZONE,
    error_message       VARCHAR2(2000),
    CONSTRAINT pk_notifications PRIMARY KEY (notification_id),
    CONSTRAINT chk_notif_status CHECK (status IN ('PENDING','PROCESSING','SENT','FAILED'))
);

-- ----------------------------------------------------------------
-- SCHEDULED_JOBS (DBMS_SCHEDULER tracking)
-- ORACLE-DEP [EMBEDDED_SQL:INTERVAL_LITERAL] Oracle interval schedule expressions
-- ----------------------------------------------------------------
CREATE TABLE MERIDIAN.SCHEDULED_JOBS (
    job_id          NUMBER(19)          NOT NULL,
    job_name        VARCHAR2(200)       NOT NULL,
    job_type        VARCHAR2(50)        NOT NULL,
    schedule_expr   VARCHAR2(500),
    status          VARCHAR2(20)        DEFAULT 'ACTIVE' NOT NULL,
    last_run_date   DATE,
    next_run_date   DATE,
    last_status     VARCHAR2(20),
    run_count       NUMBER(10)          DEFAULT 0,
    created_date    DATE                DEFAULT SYSDATE NOT NULL,
    CONSTRAINT pk_scheduled_jobs PRIMARY KEY (job_id),
    CONSTRAINT uq_job_name UNIQUE (job_name),
    CONSTRAINT chk_job_status CHECK (status IN ('ACTIVE','INACTIVE','SUSPENDED'))
);
