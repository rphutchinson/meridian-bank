-- ============================================================
-- 08-create-views.sql
-- Views demonstrating Oracle-specific SQL constructs
-- ORACLE-DEP [EMBEDDED_SQL:NVL_FUNCTION] NVL in view definitions
-- ORACLE-DEP [EMBEDDED_SQL:DECODE_FUNCTION] DECODE in view definitions
-- ORACLE-DEP [EMBEDDED_SQL:CONNECT_BY] Hierarchical view
-- ============================================================

ALTER SESSION SET CONTAINER = FREEPDB1;

-- ----------------------------------------------------------------
-- V_CUSTOMER_SUMMARY — customer view with NVL defaults
-- ORACLE-DEP [EMBEDDED_SQL:NVL_FUNCTION] NVL for nullable columns
-- ----------------------------------------------------------------
CREATE OR REPLACE VIEW MERIDIAN.V_CUSTOMER_SUMMARY AS
SELECT
    c.customer_id,
    c.first_name || ' ' || c.last_name                          AS full_name,
    c.email,
    NVL(c.phone, 'N/A')                                         AS phone,        -- ORACLE-DEP [EMBEDDED_SQL:NVL_FUNCTION]
    NVL(c.credit_score, 0)                                      AS credit_score, -- ORACLE-DEP [EMBEDDED_SQL:NVL_FUNCTION]
    c.status,
    -- ORACLE-DEP [EMBEDDED_SQL:DECODE_FUNCTION] Status label
    DECODE(c.status,
           'ACTIVE',    'Active Customer',
           'INACTIVE',  'Dormant Account',
           'SUSPENDED', 'Account Suspended',
           'CLOSED',    'Account Closed',
           'Unknown')                                            AS status_label,
    -- ORACLE-DEP [EMBEDDED_SQL:MONTHS_BETWEEN] Customer tenure
    ROUND(MONTHS_BETWEEN(SYSDATE, c.created_date))              AS months_as_customer,
    COUNT(a.account_id)                                         AS account_count,
    NVL(SUM(a.balance), 0)                                      AS total_balance  -- ORACLE-DEP [EMBEDDED_SQL:NVL_FUNCTION]
FROM MERIDIAN.CUSTOMERS c
LEFT JOIN MERIDIAN.ACCOUNTS a                                   -- ORACLE-DEP [EMBEDDED_SQL:ANSI_JOIN] ANSI outer join
    ON c.customer_id = a.customer_id
    AND a.status = 'ACTIVE'
GROUP BY
    c.customer_id, c.first_name, c.last_name, c.email,
    c.phone, c.credit_score, c.status, c.created_date;

-- ----------------------------------------------------------------
-- V_ACCOUNT_SUMMARY — account view with DECODE status mapping
-- ORACLE-DEP [EMBEDDED_SQL:NVL2_FUNCTION] NVL2 for conditional display
-- ----------------------------------------------------------------
CREATE OR REPLACE VIEW MERIDIAN.V_ACCOUNT_SUMMARY AS
SELECT
    a.account_id,
    a.account_number,
    -- ORACLE-DEP [EMBEDDED_SQL:DECODE_FUNCTION] Type description
    DECODE(a.account_type,
           'CHECKING',     'Personal Checking',
           'SAVINGS',      'Savings Account',
           'MONEY_MARKET', 'Money Market',
           'CD',           'Certificate of Deposit',
           'IRA',          'Individual Retirement',
           a.account_type)                                        AS type_description,
    a.balance,
    -- ORACLE-DEP [EMBEDDED_SQL:NVL2_FUNCTION] NVL2: show effective overdraft
    NVL2(a.overdraft_limit,
         a.balance + a.overdraft_limit,
         a.balance)                                               AS available_balance, -- ORACLE-DEP [EMBEDDED_SQL:NVL2_FUNCTION]
    a.status,
    a.interest_rate,
    a.customer_id,
    c.first_name || ' ' || c.last_name                           AS customer_name,
    a.branch_id,
    b.branch_name,
    -- ORACLE-DEP [EMBEDDED_SQL:SYSDATE] Age in days
    TRUNC(SYSDATE) - TRUNC(a.open_date)                          AS days_open,   -- ORACLE-DEP [EMBEDDED_SQL:TRUNC_DATE]
    a.open_date
FROM MERIDIAN.ACCOUNTS a
JOIN MERIDIAN.CUSTOMERS c ON a.customer_id = c.customer_id
JOIN MERIDIAN.BRANCHES  b ON a.branch_id   = b.branch_id;

-- ----------------------------------------------------------------
-- V_EMPLOYEE_HIERARCHY — uses CONNECT BY for org chart
-- ORACLE-DEP [EMBEDDED_SQL:CONNECT_BY] Hierarchical query in view
-- ORACLE-DEP [EMBEDDED_SQL:LEVEL_PSEUDOCOL] LEVEL depth indicator
-- ORACLE-DEP [EMBEDDED_SQL:SYS_CONNECT_BY_PATH] Full path display
-- ----------------------------------------------------------------
CREATE OR REPLACE VIEW MERIDIAN.V_EMPLOYEE_HIERARCHY AS
SELECT
    e.employee_id,
    e.first_name || ' ' || e.last_name                              AS employee_name,
    e.job_title,
    e.manager_id,
    m.first_name || ' ' || m.last_name                              AS manager_name,
    -- ORACLE-DEP [EMBEDDED_SQL:LEVEL_PSEUDOCOL] Hierarchy depth
    LEVEL                                                           AS hierarchy_level,
    -- ORACLE-DEP [EMBEDDED_SQL:SYS_CONNECT_BY_PATH] Full reporting chain path
    SYS_CONNECT_BY_PATH(e.first_name || ' ' || e.last_name, ' > ') AS reporting_path,
    -- ORACLE-DEP [EMBEDDED_SQL:CONNECT_BY_ROOT] Root (CEO) name
    CONNECT_BY_ROOT (e.first_name || ' ' || e.last_name)           AS ceo_name,
    -- ORACLE-DEP [EMBEDDED_SQL:CONNECT_BY_ISLEAF] Is this employee a leaf node?
    CONNECT_BY_ISLEAF                                               AS is_leaf_node,
    e.salary,
    e.department_id,
    e.branch_id,
    -- ORACLE-DEP [EMBEDDED_SQL:LPAD] Indent name by hierarchy level
    LPAD(' ', (LEVEL - 1) * 4) || e.first_name || ' ' || e.last_name AS indented_name
FROM MERIDIAN.EMPLOYEES e
LEFT JOIN MERIDIAN.EMPLOYEES m ON e.manager_id = m.employee_id
-- ORACLE-DEP [EMBEDDED_SQL:CONNECT_BY] Hierarchical traversal
START WITH e.manager_id IS NULL           -- ORACLE-DEP [EMBEDDED_SQL:START_WITH]
CONNECT BY PRIOR e.employee_id = e.manager_id  -- ORACLE-DEP [EMBEDDED_SQL:CONNECT_BY]
ORDER SIBLINGS BY e.last_name;

-- ----------------------------------------------------------------
-- V_TRANSACTION_ANALYTICS — analytic functions view
-- ORACLE-DEP [EMBEDDED_SQL:ANALYTIC_FUNCTIONS] Window functions
-- ----------------------------------------------------------------
CREATE OR REPLACE VIEW MERIDIAN.V_TRANSACTION_ANALYTICS AS
SELECT
    t.transaction_id,
    t.account_id,
    t.amount,
    t.transaction_type,
    t.transaction_date,
    t.category_code,
    -- Running total per account
    SUM(DECODE(t.transaction_type,                               -- ORACLE-DEP [EMBEDDED_SQL:DECODE_FUNCTION]
               'CREDIT', t.amount, 'INTEREST', t.amount,
               -t.amount))
        OVER (PARTITION BY t.account_id
              ORDER BY t.transaction_date
              ROWS UNBOUNDED PRECEDING)                         AS running_balance,
    -- Monthly rank within account
    RANK() OVER (PARTITION BY t.account_id,
                              TRUNC(t.transaction_date, 'MM')   -- ORACLE-DEP [EMBEDDED_SQL:TRUNC_DATE]
                 ORDER BY t.amount DESC)                        AS monthly_rank,
    -- ORACLE-DEP [EMBEDDED_SQL:WIDTH_BUCKET] Transaction amount bucket (1-5)
    WIDTH_BUCKET(t.amount, 0, 10000, 5)                        AS amount_bucket,  -- ORACLE-DEP [EMBEDDED_SQL:WIDTH_BUCKET]
    -- ORACLE-DEP [EMBEDDED_SQL:RATIO_TO_REPORT] Share of account's total
    ROUND(RATIO_TO_REPORT(t.amount)                            -- ORACLE-DEP [EMBEDDED_SQL:RATIO_TO_REPORT]
          OVER (PARTITION BY t.account_id) * 100, 2)          AS pct_of_account_total
FROM MERIDIAN.TRANSACTIONS t
WHERE t.status = 'COMPLETED';

-- ----------------------------------------------------------------
-- V_LEGACY_ACCOUNT_REPORT — intentionally uses Oracle outer join (+) syntax
-- ORACLE-DEP [EMBEDDED_SQL:OUTER_JOIN_PLUS] Old-style (+) join syntax
-- ----------------------------------------------------------------
CREATE OR REPLACE VIEW MERIDIAN.V_LEGACY_ACCOUNT_REPORT AS
SELECT
    c.customer_id,
    c.first_name || ' ' || c.last_name     AS customer_name,
    a.account_id,
    a.account_number,
    NVL(a.balance, 0)                      AS balance,        -- ORACLE-DEP [EMBEDDED_SQL:NVL_FUNCTION]
    NVL(a.account_type, 'NONE')            AS account_type,
    NVL(cc.card_type, 'NO CARD')           AS card_type,
    NVL(cc.reward_points, 0)               AS reward_points   -- ORACLE-DEP [EMBEDDED_SQL:NVL_FUNCTION]
FROM MERIDIAN.CUSTOMERS c,
     MERIDIAN.ACCOUNTS  a,
     MERIDIAN.CREDIT_CARDS cc
WHERE c.customer_id = a.customer_id(+)     -- ORACLE-DEP [EMBEDDED_SQL:OUTER_JOIN_PLUS] Oracle (+) outer join
  AND a.account_id  = cc.account_id(+)     -- ORACLE-DEP [EMBEDDED_SQL:OUTER_JOIN_PLUS]
  AND c.status = 'ACTIVE';
