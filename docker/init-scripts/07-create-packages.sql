-- ============================================================
-- 07-create-packages.sql
-- PL/SQL packages called from the Java application tier
-- ORACLE-DEP [PLSQL_INVOCATION:PACKAGE_CALL] Package.Procedure references
-- ORACLE-DEP [PLSQL_INVOCATION:REF_CURSOR] REF CURSOR OUT parameters
-- ORACLE-DEP [EMBEDDED_SQL:BULK_COLLECT] FORALL / BULK COLLECT operations
-- ============================================================

ALTER SESSION SET CONTAINER = FREEPDB1;

-- ================================================================
-- ACCOUNT_MGMT_PKG
-- Called from AccountOracleDAO via {call ACCOUNT_MGMT_PKG.xxx()}
-- ================================================================
CREATE OR REPLACE PACKAGE MERIDIAN.ACCOUNT_MGMT_PKG AS

    -- Ref cursor type returned to Java as OracleTypes.CURSOR
    -- ORACLE-DEP [PLSQL_INVOCATION:REF_CURSOR] REF CURSOR type definition
    TYPE t_account_cursor IS REF CURSOR;

    PROCEDURE create_account(
        p_customer_id    IN  NUMBER,
        p_account_type   IN  VARCHAR2,
        p_initial_deposit IN NUMBER,
        p_branch_id      IN  NUMBER,
        p_account_cursor OUT t_account_cursor,  -- ORACLE-DEP [PLSQL_INVOCATION:REF_CURSOR]
        p_new_account_id OUT NUMBER
    );

    PROCEDURE close_account(
        p_account_id  IN  NUMBER,
        p_reason      IN  VARCHAR2,
        p_result_code OUT NUMBER,
        p_result_msg  OUT VARCHAR2
    );

    PROCEDURE get_balance(
        p_account_id    IN  NUMBER,
        p_balance       OUT NUMBER,
        p_available_bal OUT NUMBER
    );

    PROCEDURE transfer_funds(
        p_from_account_id IN  NUMBER,
        p_to_account_id   IN  NUMBER,
        p_amount          IN  NUMBER,
        p_description     IN  VARCHAR2,
        p_result_code     OUT NUMBER,
        p_result_msg      OUT VARCHAR2
    );

    PROCEDURE get_customer_accounts(
        p_customer_id    IN  NUMBER,
        p_account_cursor OUT t_account_cursor  -- ORACLE-DEP [PLSQL_INVOCATION:REF_CURSOR]
    );

END ACCOUNT_MGMT_PKG;
/

CREATE OR REPLACE PACKAGE BODY MERIDIAN.ACCOUNT_MGMT_PKG AS

    PROCEDURE create_account(
        p_customer_id     IN  NUMBER,
        p_account_type    IN  VARCHAR2,
        p_initial_deposit IN  NUMBER,
        p_branch_id       IN  NUMBER,
        p_account_cursor  OUT t_account_cursor,
        p_new_account_id  OUT NUMBER
    ) AS
        v_account_number VARCHAR2(20);
        v_interest_rate  NUMBER(6,4);
    BEGIN
        -- Generate account number using sequence
        -- ORACLE-DEP [EMBEDDED_SQL:SEQUENCE] account_seq.NEXTVAL
        SELECT MERIDIAN.ACCOUNT_SEQ.NEXTVAL INTO p_new_account_id FROM DUAL;  -- ORACLE-DEP [EMBEDDED_SQL:DUAL_TABLE]

        v_account_number := 'MB' || LPAD(p_new_account_id, 10, '0');

        -- ORACLE-DEP [EMBEDDED_SQL:DECODE] Map account type to default interest rate
        SELECT DECODE(p_account_type,
                      'SAVINGS',      2.50,
                      'MONEY_MARKET', 3.20,
                      'CD',           4.50,
                      'IRA',          5.00,
                      0.00)           -- ORACLE-DEP [EMBEDDED_SQL:DECODE_FUNCTION]
        INTO v_interest_rate
        FROM DUAL;                    -- ORACLE-DEP [EMBEDDED_SQL:DUAL_TABLE]

        INSERT INTO MERIDIAN.ACCOUNTS (
            account_id, account_number, account_type, balance,
            status, open_date, customer_id, branch_id, interest_rate
        ) VALUES (
            p_new_account_id, v_account_number, p_account_type,
            NVL(p_initial_deposit, 0),  -- ORACLE-DEP [EMBEDDED_SQL:NVL_FUNCTION]
            'ACTIVE', SYSDATE,          -- ORACLE-DEP [EMBEDDED_SQL:SYSDATE]
            p_customer_id, p_branch_id, v_interest_rate
        );

        -- Return created account via REF CURSOR
        -- ORACLE-DEP [PLSQL_INVOCATION:REF_CURSOR] Open cursor for Java getCursor()
        OPEN p_account_cursor FOR
            SELECT a.account_id, a.account_number, a.account_type,
                   a.balance, a.status, a.interest_rate,
                   NVL(a.overdraft_limit, 0) AS overdraft_limit  -- ORACLE-DEP [EMBEDDED_SQL:NVL_FUNCTION]
            FROM MERIDIAN.ACCOUNTS a
            WHERE a.account_id = p_new_account_id;

    EXCEPTION
        WHEN DUP_VAL_ON_INDEX THEN
            RAISE_APPLICATION_ERROR(-20001, 'Account number already exists');
        WHEN OTHERS THEN
            RAISE_APPLICATION_ERROR(-20000, 'create_account failed: ' || SQLERRM);
    END create_account;

    PROCEDURE close_account(
        p_account_id  IN  NUMBER,
        p_reason      IN  VARCHAR2,
        p_result_code OUT NUMBER,
        p_result_msg  OUT VARCHAR2
    ) AS
        v_balance NUMBER(19,4);
    BEGIN
        SELECT balance INTO v_balance
        FROM MERIDIAN.ACCOUNTS
        WHERE account_id = p_account_id;

        IF v_balance != 0 THEN
            p_result_code := -1;
            p_result_msg  := 'Cannot close account with non-zero balance: ' ||
                             TO_CHAR(v_balance, 'FM$999,999,990.00');  -- ORACLE-DEP [EMBEDDED_SQL:TO_CHAR]
            RETURN;
        END IF;

        UPDATE MERIDIAN.ACCOUNTS
        SET status = 'CLOSED', close_date = SYSDATE  -- ORACLE-DEP [EMBEDDED_SQL:SYSDATE]
        WHERE account_id = p_account_id;

        p_result_code := 0;
        p_result_msg  := 'Account closed successfully';
    EXCEPTION
        WHEN NO_DATA_FOUND THEN
            p_result_code := -2;
            p_result_msg  := 'Account not found: ' || p_account_id;
    END close_account;

    PROCEDURE get_balance(
        p_account_id    IN  NUMBER,
        p_balance       OUT NUMBER,
        p_available_bal OUT NUMBER
    ) AS
        v_overdraft NUMBER(12,2);
    BEGIN
        SELECT balance, NVL(overdraft_limit, 0)  -- ORACLE-DEP [EMBEDDED_SQL:NVL_FUNCTION]
        INTO p_balance, v_overdraft
        FROM MERIDIAN.ACCOUNTS
        WHERE account_id = p_account_id
          AND status = 'ACTIVE';

        p_available_bal := p_balance + v_overdraft;
    EXCEPTION
        WHEN NO_DATA_FOUND THEN
            RAISE_APPLICATION_ERROR(-20002, 'Active account not found: ' || p_account_id);
    END get_balance;

    PROCEDURE transfer_funds(
        p_from_account_id IN  NUMBER,
        p_to_account_id   IN  NUMBER,
        p_amount          IN  NUMBER,
        p_description     IN  VARCHAR2,
        p_result_code     OUT NUMBER,
        p_result_msg      OUT VARCHAR2
    ) AS
        v_from_balance  NUMBER(19,4);
        v_overdraft_lim NUMBER(12,2);
        v_txn_id        NUMBER(19);
    BEGIN
        -- Lock source account
        SELECT balance, NVL(overdraft_limit, 0)  -- ORACLE-DEP [EMBEDDED_SQL:NVL_FUNCTION]
        INTO v_from_balance, v_overdraft_lim
        FROM MERIDIAN.ACCOUNTS
        WHERE account_id = p_from_account_id
          AND status = 'ACTIVE'
        FOR UPDATE;

        IF (v_from_balance + v_overdraft_lim) < p_amount THEN
            p_result_code := -1;
            p_result_msg  := 'Insufficient funds';
            ROLLBACK;
            RETURN;
        END IF;

        -- Debit source
        UPDATE MERIDIAN.ACCOUNTS
        SET balance = balance - p_amount
        WHERE account_id = p_from_account_id;

        -- Credit destination
        UPDATE MERIDIAN.ACCOUNTS
        SET balance = balance + p_amount
        WHERE account_id = p_to_account_id;

        -- Record debit transaction with RETURNING INTO
        -- ORACLE-DEP [EMBEDDED_SQL:RETURNING_INTO] INSERT ... RETURNING transaction_id INTO variable
        INSERT INTO MERIDIAN.TRANSACTIONS (
            transaction_id, account_id, amount, transaction_type,
            description, transaction_date, status, reference_number
        ) VALUES (
            MERIDIAN.TRANSACTION_SEQ.NEXTVAL,  -- ORACLE-DEP [EMBEDDED_SQL:SEQUENCE]
            p_from_account_id, p_amount, 'DEBIT',
            NVL(p_description, 'Transfer out'),  -- ORACLE-DEP [EMBEDDED_SQL:NVL_FUNCTION]
            SYSTIMESTAMP, 'COMPLETED',           -- ORACLE-DEP [EMBEDDED_SQL:SYSTIMESTAMP]
            'TRF-' || TO_CHAR(MERIDIAN.TRANSACTION_REF_SEQ.NEXTVAL)
        ) RETURNING transaction_id INTO v_txn_id;  -- ORACLE-DEP [EMBEDDED_SQL:RETURNING_INTO]

        -- Record credit transaction
        INSERT INTO MERIDIAN.TRANSACTIONS (
            transaction_id, account_id, amount, transaction_type,
            description, transaction_date, status, reference_number, counterparty_id
        ) VALUES (
            MERIDIAN.TRANSACTION_SEQ.NEXTVAL,
            p_to_account_id, p_amount, 'CREDIT',
            NVL(p_description, 'Transfer in'),
            SYSTIMESTAMP, 'COMPLETED',
            'TRF-' || TO_CHAR(MERIDIAN.TRANSACTION_REF_SEQ.NEXTVAL),
            p_from_account_id
        );

        p_result_code := 0;
        p_result_msg  := 'Transfer completed. Transaction ID: ' || v_txn_id;
        COMMIT;
    EXCEPTION
        WHEN OTHERS THEN
            ROLLBACK;
            p_result_code := -99;
            p_result_msg  := 'Transfer failed: ' || SQLERRM;
    END transfer_funds;

    PROCEDURE get_customer_accounts(
        p_customer_id    IN  NUMBER,
        p_account_cursor OUT t_account_cursor
    ) AS
    BEGIN
        -- ORACLE-DEP [PLSQL_INVOCATION:REF_CURSOR] REF CURSOR for multiple rows
        OPEN p_account_cursor FOR
            SELECT a.account_id, a.account_number, a.account_type,
                   a.balance, a.status,
                   NVL(a.overdraft_limit, 0) AS overdraft_limit,  -- ORACLE-DEP [EMBEDDED_SQL:NVL_FUNCTION]
                   a.open_date,
                   MONTHS_BETWEEN(SYSDATE, a.open_date) AS months_open  -- ORACLE-DEP [EMBEDDED_SQL:MONTHS_BETWEEN]
            FROM MERIDIAN.ACCOUNTS a
            WHERE a.customer_id = p_customer_id
            ORDER BY a.open_date;
    END get_customer_accounts;

END ACCOUNT_MGMT_PKG;
/

-- ================================================================
-- TRANSACTION_PKG
-- Called from TransactionOracleDAO and BatchProcessingDAO
-- ORACLE-DEP [EMBEDDED_SQL:FORALL] FORALL bulk DML
-- ORACLE-DEP [EMBEDDED_SQL:BULK_COLLECT] BULK COLLECT into arrays
-- ================================================================
CREATE OR REPLACE PACKAGE MERIDIAN.TRANSACTION_PKG AS

    TYPE t_txn_cursor IS REF CURSOR;  -- ORACLE-DEP [PLSQL_INVOCATION:REF_CURSOR]
    TYPE t_number_list IS TABLE OF NUMBER INDEX BY PLS_INTEGER;
    TYPE t_status_list IS TABLE OF VARCHAR2(20) INDEX BY PLS_INTEGER;

    PROCEDURE record_transaction(
        p_account_id      IN  NUMBER,
        p_amount          IN  NUMBER,
        p_txn_type        IN  VARCHAR2,
        p_description     IN  VARCHAR2,
        p_new_txn_id      OUT NUMBER,
        p_new_balance     OUT NUMBER
    );

    PROCEDURE batch_update_statuses(
        p_account_ids IN MERIDIAN.NUMBER_TABLE_TYPE,
        p_new_status  IN VARCHAR2,
        p_updated_count OUT NUMBER
    );

    PROCEDURE get_account_transactions(
        p_account_id IN  NUMBER,
        p_from_date  IN  DATE,
        p_to_date    IN  DATE,
        p_cursor     OUT t_txn_cursor  -- ORACLE-DEP [PLSQL_INVOCATION:REF_CURSOR]
    );

    PROCEDURE apply_monthly_interest;

END TRANSACTION_PKG;
/

CREATE OR REPLACE PACKAGE BODY MERIDIAN.TRANSACTION_PKG AS

    PROCEDURE record_transaction(
        p_account_id   IN  NUMBER,
        p_amount       IN  NUMBER,
        p_txn_type     IN  VARCHAR2,
        p_description  IN  VARCHAR2,
        p_new_txn_id   OUT NUMBER,
        p_new_balance  OUT NUMBER
    ) AS
        v_current_balance NUMBER(19,4);
    BEGIN
        SELECT balance INTO v_current_balance
        FROM MERIDIAN.ACCOUNTS
        WHERE account_id = p_account_id
          AND status = 'ACTIVE'
        FOR UPDATE;

        -- ORACLE-DEP [EMBEDDED_SQL:DECODE] Determine balance direction
        p_new_balance := v_current_balance +
            DECODE(p_txn_type, 'CREDIT', p_amount, 'INTEREST', p_amount,
                   'DEBIT', -p_amount, 'FEE', -p_amount, 0);  -- ORACLE-DEP [EMBEDDED_SQL:DECODE_FUNCTION]

        UPDATE MERIDIAN.ACCOUNTS
        SET balance = p_new_balance
        WHERE account_id = p_account_id;

        -- ORACLE-DEP [EMBEDDED_SQL:RETURNING_INTO] Capture generated ID
        INSERT INTO MERIDIAN.TRANSACTIONS (
            transaction_id, account_id, amount, transaction_type,
            description, transaction_date, status, reference_number
        ) VALUES (
            MERIDIAN.TRANSACTION_SEQ.NEXTVAL,
            p_account_id, p_amount, p_txn_type,
            NVL(p_description, p_txn_type || ' transaction'),  -- ORACLE-DEP [EMBEDDED_SQL:NVL_FUNCTION]
            SYSTIMESTAMP, 'COMPLETED',                          -- ORACLE-DEP [EMBEDDED_SQL:SYSTIMESTAMP]
            p_txn_type || '-' || TO_CHAR(MERIDIAN.TRANSACTION_REF_SEQ.NEXTVAL)
        ) RETURNING transaction_id INTO p_new_txn_id;  -- ORACLE-DEP [EMBEDDED_SQL:RETURNING_INTO]

        COMMIT;
    EXCEPTION
        WHEN NO_DATA_FOUND THEN
            RAISE_APPLICATION_ERROR(-20010, 'Active account not found: ' || p_account_id);
        WHEN OTHERS THEN
            ROLLBACK;
            RAISE;
    END record_transaction;

    PROCEDURE batch_update_statuses(
        p_account_ids   IN  MERIDIAN.NUMBER_TABLE_TYPE,
        p_new_status    IN  VARCHAR2,
        p_updated_count OUT NUMBER
    ) AS
        TYPE t_rowid_list IS TABLE OF ROWID INDEX BY PLS_INTEGER;
        v_rowids    t_rowid_list;
        v_ids       MERIDIAN.NUMBER_TABLE_TYPE;
    BEGIN
        -- ORACLE-DEP [EMBEDDED_SQL:BULK_COLLECT] Collect ROWIDs for FORALL update
        SELECT ROWID                -- ORACLE-DEP [EMBEDDED_SQL:ROWID] ROWID-based batch update
        BULK COLLECT INTO v_rowids  -- ORACLE-DEP [EMBEDDED_SQL:BULK_COLLECT]
        FROM MERIDIAN.ACCOUNTS a
        WHERE a.account_id IN (
            SELECT COLUMN_VALUE FROM TABLE(p_account_ids)  -- unnest Oracle array
        );

        -- ORACLE-DEP [EMBEDDED_SQL:FORALL] Bulk DML via FORALL
        FORALL i IN 1..v_rowids.COUNT
            UPDATE MERIDIAN.ACCOUNTS
            SET status = p_new_status, updated_date = SYSDATE  -- ORACLE-DEP [EMBEDDED_SQL:SYSDATE]
            WHERE ROWID = v_rowids(i);                          -- ORACLE-DEP [EMBEDDED_SQL:ROWID]

        p_updated_count := SQL%ROWCOUNT;
        COMMIT;
    END batch_update_statuses;

    PROCEDURE get_account_transactions(
        p_account_id IN  NUMBER,
        p_from_date  IN  DATE,
        p_to_date    IN  DATE,
        p_cursor     OUT t_txn_cursor
    ) AS
    BEGIN
        -- ORACLE-DEP [PLSQL_INVOCATION:REF_CURSOR] Stream large transaction sets
        OPEN p_cursor FOR
            SELECT /*+ INDEX(t IDX_TXN_ACCOUNT_DATE) */  -- ORACLE-DEP [EMBEDDED_SQL:ORACLE_HINTS]
                   t.transaction_id, t.account_id, t.amount,
                   t.transaction_type, t.description,
                   t.transaction_date, t.status, t.reference_number,
                   t.category_code,
                   -- ORACLE-DEP [EMBEDDED_SQL:TO_CHAR] Oracle date formatting
                   TO_CHAR(t.transaction_date, 'YYYY-MM-DD HH24:MI:SS TZH:TZM') AS txn_date_str
            FROM MERIDIAN.TRANSACTIONS t
            WHERE t.account_id = p_account_id
              AND TRUNC(t.transaction_date) BETWEEN                -- ORACLE-DEP [EMBEDDED_SQL:TRUNC_DATE]
                  NVL(p_from_date, TRUNC(SYSDATE) - 90)           -- ORACLE-DEP [EMBEDDED_SQL:NVL_FUNCTION]
                  AND NVL(p_to_date, TRUNC(SYSDATE))
            ORDER BY t.transaction_date DESC;
    END get_account_transactions;

    PROCEDURE apply_monthly_interest AS
        TYPE t_acct_list IS TABLE OF MERIDIAN.ACCOUNTS.account_id%TYPE;
        TYPE t_rate_list IS TABLE OF MERIDIAN.ACCOUNTS.interest_rate%TYPE;
        TYPE t_bal_list  IS TABLE OF MERIDIAN.ACCOUNTS.balance%TYPE;

        v_acct_ids  t_acct_list;
        v_rates     t_rate_list;
        v_balances  t_bal_list;
        v_interest  NUMBER(19,4);
    BEGIN
        -- ORACLE-DEP [EMBEDDED_SQL:BULK_COLLECT] Load interest-bearing accounts
        SELECT account_id, interest_rate, balance
        BULK COLLECT INTO v_acct_ids, v_rates, v_balances  -- ORACLE-DEP [EMBEDDED_SQL:BULK_COLLECT]
        FROM MERIDIAN.ACCOUNTS
        WHERE status = 'ACTIVE'
          AND account_type IN ('SAVINGS','MONEY_MARKET','CD','IRA')
          AND interest_rate > 0;

        -- ORACLE-DEP [EMBEDDED_SQL:FORALL] Bulk insert interest transactions
        FORALL i IN 1..v_acct_ids.COUNT  -- ORACLE-DEP [EMBEDDED_SQL:FORALL]
            INSERT INTO MERIDIAN.TRANSACTIONS (
                transaction_id, account_id, amount, transaction_type,
                description, transaction_date, status, reference_number, category_code
            ) VALUES (
                MERIDIAN.TRANSACTION_SEQ.NEXTVAL,
                v_acct_ids(i),
                ROUND(v_balances(i) * v_rates(i) / 1200, 4),  -- monthly rate
                'INTEREST',
                'Monthly interest credit',
                SYSTIMESTAMP,  -- ORACLE-DEP [EMBEDDED_SQL:SYSTIMESTAMP]
                'COMPLETED',
                'INT-' || TO_CHAR(MERIDIAN.TRANSACTION_REF_SEQ.NEXTVAL),
                'INTEREST'
            );

        -- Bulk update balances
        FORALL i IN 1..v_acct_ids.COUNT
            UPDATE MERIDIAN.ACCOUNTS
            SET balance = balance + ROUND(balance * interest_rate / 1200, 4),
                updated_date = SYSDATE  -- ORACLE-DEP [EMBEDDED_SQL:SYSDATE]
            WHERE account_id = v_acct_ids(i);

        COMMIT;
    END apply_monthly_interest;

END TRANSACTION_PKG;
/

-- ================================================================
-- LOAN_PKG
-- Called from LoanOracleDAO
-- ================================================================
CREATE OR REPLACE PACKAGE MERIDIAN.LOAN_PKG AS

    TYPE t_loan_cursor IS REF CURSOR;  -- ORACLE-DEP [PLSQL_INVOCATION:REF_CURSOR]

    PROCEDURE submit_application(
        p_customer_id       IN  NUMBER,
        p_loan_type         IN  VARCHAR2,
        p_requested_amount  IN  NUMBER,
        p_term_months       IN  NUMBER,
        p_new_app_id        OUT NUMBER
    );

    PROCEDURE underwrite_application(
        p_application_id IN  NUMBER,
        p_officer_id     IN  NUMBER,
        p_result_cursor  OUT t_loan_cursor  -- ORACLE-DEP [PLSQL_INVOCATION:REF_CURSOR]
    );

    PROCEDURE approve_loan(
        p_application_id  IN  NUMBER,
        p_approved_amount IN  NUMBER,
        p_interest_rate   IN  NUMBER,
        p_officer_id      IN  NUMBER,
        p_result_code     OUT NUMBER,
        p_result_msg      OUT VARCHAR2
    );

END LOAN_PKG;
/

CREATE OR REPLACE PACKAGE BODY MERIDIAN.LOAN_PKG AS

    PROCEDURE submit_application(
        p_customer_id      IN  NUMBER,
        p_loan_type        IN  VARCHAR2,
        p_requested_amount IN  NUMBER,
        p_term_months      IN  NUMBER,
        p_new_app_id       OUT NUMBER
    ) AS
    BEGIN
        SELECT MERIDIAN.LOAN_APP_SEQ.NEXTVAL INTO p_new_app_id FROM DUAL;  -- ORACLE-DEP [EMBEDDED_SQL:SEQUENCE]

        INSERT INTO MERIDIAN.LOAN_APPLICATIONS (
            application_id, customer_id, loan_type,
            requested_amount, status, application_date,
            term_months, notes
        ) VALUES (
            p_new_app_id, p_customer_id, p_loan_type,
            p_requested_amount, 'SUBMITTED', SYSDATE,  -- ORACLE-DEP [EMBEDDED_SQL:SYSDATE]
            p_term_months, EMPTY_CLOB()                -- ORACLE-DEP [EMBEDDED_SQL:EMPTY_CLOB]
        );
        COMMIT;
    END submit_application;

    PROCEDURE underwrite_application(
        p_application_id IN  NUMBER,
        p_officer_id     IN  NUMBER,
        p_result_cursor  OUT t_loan_cursor
    ) AS
    BEGIN
        UPDATE MERIDIAN.LOAN_APPLICATIONS
        SET status = 'UNDER_REVIEW', officer_id = p_officer_id
        WHERE application_id = p_application_id
          AND status = 'SUBMITTED';

        -- ORACLE-DEP [PLSQL_INVOCATION:REF_CURSOR] Return application + customer data
        OPEN p_result_cursor FOR
            SELECT la.application_id, la.loan_type,
                   la.requested_amount, la.status,
                   la.application_date,
                   -- ORACLE-DEP [EMBEDDED_SQL:MONTHS_BETWEEN] Days since application
                   ROUND(SYSDATE - la.application_date) AS days_pending,
                   c.first_name || ' ' || c.last_name AS customer_name,
                   c.credit_score,
                   NVL(c.credit_score, 0) AS effective_credit_score  -- ORACLE-DEP [EMBEDDED_SQL:NVL_FUNCTION]
            FROM MERIDIAN.LOAN_APPLICATIONS la
            JOIN MERIDIAN.CUSTOMERS c ON la.customer_id = c.customer_id
            WHERE la.application_id = p_application_id;
        COMMIT;
    END underwrite_application;

    PROCEDURE approve_loan(
        p_application_id  IN  NUMBER,
        p_approved_amount IN  NUMBER,
        p_interest_rate   IN  NUMBER,
        p_officer_id      IN  NUMBER,
        p_result_code     OUT NUMBER,
        p_result_msg      OUT VARCHAR2
    ) AS
        v_loan_type VARCHAR2(30);
        v_maturity  DATE;
        v_term      NUMBER;
    BEGIN
        SELECT loan_type, term_months
        INTO v_loan_type, v_term
        FROM MERIDIAN.LOAN_APPLICATIONS
        WHERE application_id = p_application_id;

        -- ORACLE-DEP [EMBEDDED_SQL:ADD_MONTHS] Calculate loan maturity date
        v_maturity := ADD_MONTHS(SYSDATE, NVL(v_term, 12));  -- ORACLE-DEP [EMBEDDED_SQL:ADD_MONTHS]

        UPDATE MERIDIAN.LOAN_APPLICATIONS
        SET status          = 'APPROVED',
            approved_amount = p_approved_amount,
            interest_rate   = p_interest_rate,
            decision_date   = SYSDATE,   -- ORACLE-DEP [EMBEDDED_SQL:SYSDATE]
            officer_id      = p_officer_id
        WHERE application_id = p_application_id;

        p_result_code := 0;
        p_result_msg  := 'Approved. Maturity: ' ||
                         TO_CHAR(v_maturity, 'DD-MON-YYYY');  -- ORACLE-DEP [EMBEDDED_SQL:TO_CHAR]
        COMMIT;
    EXCEPTION
        WHEN NO_DATA_FOUND THEN
            p_result_code := -1;
            p_result_msg  := 'Application not found: ' || p_application_id;
    END approve_loan;

END LOAN_PKG;
/

-- ================================================================
-- CREDIT_CARD_PKG
-- Called from CreditCardOracleDAO
-- ================================================================
CREATE OR REPLACE PACKAGE MERIDIAN.CREDIT_CARD_PKG AS

    TYPE t_card_cursor IS REF CURSOR;  -- ORACLE-DEP [PLSQL_INVOCATION:REF_CURSOR]

    PROCEDURE apply_for_card(
        p_customer_id   IN  NUMBER,
        p_account_id    IN  NUMBER,
        p_card_type     IN  VARCHAR2,
        p_new_card_id   OUT NUMBER,
        p_card_number   OUT VARCHAR2
    );

    PROCEDURE process_payment(
        p_card_id     IN  NUMBER,
        p_amount      IN  NUMBER,
        p_result_code OUT NUMBER,
        p_result_msg  OUT VARCHAR2
    );

    PROCEDURE get_reward_tiers(
        p_card_id       IN  NUMBER,
        p_tier_cursor   OUT t_card_cursor  -- ORACLE-DEP [PLSQL_INVOCATION:REF_CURSOR]
    );

END CREDIT_CARD_PKG;
/

CREATE OR REPLACE PACKAGE BODY MERIDIAN.CREDIT_CARD_PKG AS

    PROCEDURE apply_for_card(
        p_customer_id IN  NUMBER,
        p_account_id  IN  NUMBER,
        p_card_type   IN  VARCHAR2,
        p_new_card_id OUT NUMBER,
        p_card_number OUT VARCHAR2
    ) AS
        v_credit_limit NUMBER(12,2);
        v_expiry       DATE;
    BEGIN
        -- ORACLE-DEP [EMBEDDED_SQL:DECODE] Map card type to credit limit
        SELECT DECODE(p_card_type,
                      'STANDARD',  5000,
                      'GOLD',     10000,
                      'PLATINUM', 25000,
                      'TITANIUM', 50000,
                      5000) INTO v_credit_limit FROM DUAL;  -- ORACLE-DEP [EMBEDDED_SQL:DUAL_TABLE]

        -- ORACLE-DEP [EMBEDDED_SQL:ADD_MONTHS] 3-year expiry
        v_expiry := ADD_MONTHS(SYSDATE, 36);  -- ORACLE-DEP [EMBEDDED_SQL:ADD_MONTHS]

        SELECT MERIDIAN.CREDIT_CARD_SEQ.NEXTVAL INTO p_new_card_id FROM DUAL;

        p_card_number := '4111111111' || LPAD(p_new_card_id, 6, '0');

        INSERT INTO MERIDIAN.CREDIT_CARDS (
            card_id, card_number, account_id, customer_id,
            credit_limit, current_balance, expiry_date, status,
            reward_points, card_type, issued_date
        ) VALUES (
            p_new_card_id, p_card_number, p_account_id, p_customer_id,
            v_credit_limit, 0, v_expiry, 'ACTIVE',
            0, p_card_type, SYSDATE  -- ORACLE-DEP [EMBEDDED_SQL:SYSDATE]
        );
        COMMIT;
    END apply_for_card;

    PROCEDURE process_payment(
        p_card_id     IN  NUMBER,
        p_amount      IN  NUMBER,
        p_result_code OUT NUMBER,
        p_result_msg  OUT VARCHAR2
    ) AS
        v_current_balance NUMBER(12,2);
    BEGIN
        SELECT current_balance INTO v_current_balance
        FROM MERIDIAN.CREDIT_CARDS
        WHERE card_id = p_card_id AND status = 'ACTIVE'
        FOR UPDATE;

        UPDATE MERIDIAN.CREDIT_CARDS
        SET current_balance = GREATEST(0, current_balance - p_amount),
            reward_points   = reward_points + TRUNC(p_amount)  -- ORACLE-DEP [EMBEDDED_SQL:TRUNC_DATE] TRUNC for integer points
        WHERE card_id = p_card_id;

        p_result_code := 0;
        p_result_msg  := 'Payment processed. New balance: ' ||
                         TO_CHAR(GREATEST(0, v_current_balance - p_amount),  -- ORACLE-DEP [EMBEDDED_SQL:TO_CHAR]
                                 'FM$999,990.00');
        COMMIT;
    EXCEPTION
        WHEN NO_DATA_FOUND THEN
            p_result_code := -1;
            p_result_msg  := 'Active card not found: ' || p_card_id;
    END process_payment;

    PROCEDURE get_reward_tiers(
        p_card_id     IN  NUMBER,
        p_tier_cursor OUT t_card_cursor
    ) AS
    BEGIN
        -- ORACLE-DEP [PLSQL_INVOCATION:REF_CURSOR] REF CURSOR for tier data
        OPEN p_tier_cursor FOR
            SELECT card_type,
                   reward_points,
                   -- ORACLE-DEP [EMBEDDED_SQL:DECODE] Tier name lookup
                   DECODE(card_type,
                          'STANDARD', 'Bronze',
                          'GOLD',     'Silver',
                          'PLATINUM', 'Gold',
                          'TITANIUM', 'Platinum',
                          'Unknown') AS tier_name,
                   -- ORACLE-DEP [EMBEDDED_SQL:DECODE] Points to next tier
                   DECODE(card_type,
                          'STANDARD', GREATEST(0, 5000 - reward_points),
                          'GOLD',     GREATEST(0, 20000 - reward_points),
                          'PLATINUM', GREATEST(0, 50000 - reward_points),
                          0) AS points_to_next_tier
            FROM MERIDIAN.CREDIT_CARDS
            WHERE card_id = p_card_id;
    END get_reward_tiers;

END CREDIT_CARD_PKG;
/

-- ================================================================
-- SECURITY_PKG
-- ORACLE-DEP [PLSQL_INVOCATION:DBMS_CRYPTO] Encryption via DBMS_CRYPTO
-- Called from SecurityOracleDAO
-- ================================================================
CREATE OR REPLACE PACKAGE MERIDIAN.SECURITY_PKG AS

    PROCEDURE encrypt_ssn(
        p_ssn_plain   IN  VARCHAR2,
        p_encrypted   OUT RAW,
        p_hash        OUT RAW
    );

    PROCEDURE decrypt_ssn(
        p_encrypted   IN  RAW,
        p_ssn_plain   OUT VARCHAR2
    );

    FUNCTION hash_value(p_input IN VARCHAR2) RETURN RAW;

END SECURITY_PKG;
/

CREATE OR REPLACE PACKAGE BODY MERIDIAN.SECURITY_PKG AS

    -- AES-256 encryption key (in production would come from Oracle Wallet)
    -- ORACLE-DEP [PLSQL_INVOCATION:DBMS_CRYPTO] DBMS_CRYPTO constants
    C_ENCRYPTION_KEY CONSTANT RAW(32) :=
        HEXTORAW('4D657269646961_6E42616E6B32303236_41455332353621');

    C_ENCRYPT_MODE CONSTANT PLS_INTEGER :=
        DBMS_CRYPTO.ENCRYPT_AES256 +   -- ORACLE-DEP [PLSQL_INVOCATION:DBMS_CRYPTO]
        DBMS_CRYPTO.CHAIN_CBC +
        DBMS_CRYPTO.PAD_PKCS5;

    PROCEDURE encrypt_ssn(
        p_ssn_plain IN  VARCHAR2,
        p_encrypted OUT RAW,
        p_hash      OUT RAW
    ) AS
    BEGIN
        -- ORACLE-DEP [PLSQL_INVOCATION:DBMS_CRYPTO] AES-256 encryption
        p_encrypted := DBMS_CRYPTO.ENCRYPT(
            src => UTL_I18N.STRING_TO_RAW(p_ssn_plain, 'AL32UTF8'),
            typ => C_ENCRYPT_MODE,
            key => C_ENCRYPTION_KEY
        );

        -- ORACLE-DEP [PLSQL_INVOCATION:DBMS_CRYPTO] SHA-256 hash for lookup
        p_hash := DBMS_CRYPTO.HASH(
            src => UTL_I18N.STRING_TO_RAW(p_ssn_plain, 'AL32UTF8'),
            typ => DBMS_CRYPTO.HASH_SH256
        );
    END encrypt_ssn;

    PROCEDURE decrypt_ssn(
        p_encrypted IN  RAW,
        p_ssn_plain OUT VARCHAR2
    ) AS
    BEGIN
        -- ORACLE-DEP [PLSQL_INVOCATION:DBMS_CRYPTO] AES-256 decryption
        p_ssn_plain := UTL_I18N.RAW_TO_CHAR(
            DBMS_CRYPTO.DECRYPT(
                src => p_encrypted,
                typ => C_ENCRYPT_MODE,
                key => C_ENCRYPTION_KEY
            ),
            'AL32UTF8'
        );
    END decrypt_ssn;

    FUNCTION hash_value(p_input IN VARCHAR2) RETURN RAW AS
    BEGIN
        -- ORACLE-DEP [PLSQL_INVOCATION:DBMS_CRYPTO] SHA-256 hash
        RETURN DBMS_CRYPTO.HASH(
            src => UTL_I18N.STRING_TO_RAW(p_input, 'AL32UTF8'),
            typ => DBMS_CRYPTO.HASH_SH256
        );
    END hash_value;

END SECURITY_PKG;
/

-- ================================================================
-- REPORT_PKG
-- ORACLE-DEP [PLSQL_INVOCATION:REF_CURSOR] REF CURSOR result sets
-- Called from ReportOracleDAO
-- ================================================================
CREATE OR REPLACE PACKAGE MERIDIAN.REPORT_PKG AS

    TYPE t_report_cursor IS REF CURSOR;

    PROCEDURE get_portfolio_summary(
        p_branch_id  IN  NUMBER,
        p_cursor     OUT t_report_cursor
    );

    PROCEDURE get_transaction_pivot(
        p_year       IN  NUMBER,
        p_cursor     OUT t_report_cursor
    );

    PROCEDURE get_customer_ranking(
        p_branch_id  IN  NUMBER,
        p_cursor     OUT t_report_cursor
    );

END REPORT_PKG;
/

CREATE OR REPLACE PACKAGE BODY MERIDIAN.REPORT_PKG AS

    PROCEDURE get_portfolio_summary(
        p_branch_id IN  NUMBER,
        p_cursor    OUT t_report_cursor
    ) AS
    BEGIN
        -- ORACLE-DEP [EMBEDDED_SQL:RATIO_TO_REPORT] Portfolio allocation percentages
        -- ORACLE-DEP [EMBEDDED_SQL:LISTAGG] Aggregate account numbers into list
        OPEN p_cursor FOR
            SELECT account_type,
                   COUNT(*)                                             AS account_count,
                   SUM(balance)                                        AS total_balance,
                   ROUND(RATIO_TO_REPORT(SUM(balance))                 -- ORACLE-DEP [EMBEDDED_SQL:RATIO_TO_REPORT]
                         OVER () * 100, 2)                             AS pct_of_portfolio,
                   LISTAGG(SUBSTR(account_number, 1, 8), ', ')         -- ORACLE-DEP [EMBEDDED_SQL:LISTAGG]
                         WITHIN GROUP (ORDER BY account_id)            AS sample_accounts,
                   NVL(MIN(interest_rate), 0)                          AS min_rate,  -- ORACLE-DEP [EMBEDDED_SQL:NVL_FUNCTION]
                   NVL(MAX(interest_rate), 0)                          AS max_rate
            FROM MERIDIAN.ACCOUNTS
            WHERE status = 'ACTIVE'
              AND (p_branch_id IS NULL OR branch_id = p_branch_id)
            GROUP BY account_type;
    END get_portfolio_summary;

    PROCEDURE get_transaction_pivot(
        p_year   IN  NUMBER,
        p_cursor OUT t_report_cursor
    ) AS
    BEGIN
        -- ORACLE-DEP [EMBEDDED_SQL:PIVOT] Oracle PIVOT for monthly columns
        OPEN p_cursor FOR
            SELECT * FROM (
                SELECT account_type,
                       TO_CHAR(transaction_date, 'MON') AS txn_month,  -- ORACLE-DEP [EMBEDDED_SQL:TO_CHAR]
                       amount
                FROM MERIDIAN.TRANSACTIONS t
                JOIN MERIDIAN.ACCOUNTS a ON t.account_id = a.account_id
                WHERE EXTRACT(YEAR FROM t.transaction_date) =
                      NVL(p_year, EXTRACT(YEAR FROM SYSDATE))  -- ORACLE-DEP [EMBEDDED_SQL:NVL_FUNCTION]
                  AND t.transaction_type = 'CREDIT'
            )
            PIVOT (                                            -- ORACLE-DEP [EMBEDDED_SQL:PIVOT]
                SUM(amount)
                FOR txn_month IN ('JAN' AS jan, 'FEB' AS feb, 'MAR' AS mar,
                                  'APR' AS apr, 'MAY' AS may, 'JUN' AS jun,
                                  'JUL' AS jul, 'AUG' AS aug, 'SEP' AS sep,
                                  'OCT' AS oct, 'NOV' AS nov, 'DEC' AS dec)
            )
            ORDER BY account_type;
    END get_transaction_pivot;

    PROCEDURE get_customer_ranking(
        p_branch_id IN  NUMBER,
        p_cursor    OUT t_report_cursor
    ) AS
    BEGIN
        -- ORACLE-DEP [EMBEDDED_SQL:NTILE] Quartile ranking
        -- ORACLE-DEP [EMBEDDED_SQL:CUME_DIST] Cumulative distribution
        OPEN p_cursor FOR
            SELECT c.customer_id,
                   c.first_name || ' ' || c.last_name AS customer_name,
                   NVL(SUM(a.balance), 0)              AS total_balance,  -- ORACLE-DEP [EMBEDDED_SQL:NVL_FUNCTION]
                   NTILE(4) OVER (ORDER BY SUM(a.balance) DESC)
                                                       AS quartile,       -- ORACLE-DEP [EMBEDDED_SQL:NTILE]
                   ROUND(CUME_DIST() OVER (ORDER BY SUM(a.balance)) * 100, 2)
                                                       AS cume_pct        -- ORACLE-DEP [EMBEDDED_SQL:CUME_DIST]
            FROM MERIDIAN.CUSTOMERS c
            JOIN MERIDIAN.ACCOUNTS a ON c.customer_id = a.customer_id
            WHERE a.status = 'ACTIVE'
              AND (p_branch_id IS NULL OR a.branch_id = p_branch_id)
            GROUP BY c.customer_id, c.first_name, c.last_name
            ORDER BY total_balance DESC;
    END get_customer_ranking;

END REPORT_PKG;
/

-- ================================================================
-- BATCH_PKG
-- Called from BatchProcessingService via DBMS_SCHEDULER
-- ORACLE-DEP [EMBEDDED_SQL:MERGE] MERGE for upsert operations
-- ================================================================
CREATE OR REPLACE PACKAGE MERIDIAN.BATCH_PKG AS

    PROCEDURE end_of_day_processing(
        p_process_date IN  DATE,
        p_result_code  OUT NUMBER,
        p_result_msg   OUT VARCHAR2
    );

    PROCEDURE calculate_and_apply_interest(
        p_result_code OUT NUMBER,
        p_rows_affected OUT NUMBER
    );

    PROCEDURE reconcile_account_balances(
        p_result_code OUT NUMBER,
        p_result_msg  OUT VARCHAR2
    );

END BATCH_PKG;
/

CREATE OR REPLACE PACKAGE BODY MERIDIAN.BATCH_PKG AS

    PROCEDURE end_of_day_processing(
        p_process_date IN  DATE,
        p_result_code  OUT NUMBER,
        p_result_msg   OUT VARCHAR2
    ) AS
        v_process_dt DATE;
        v_txn_count  NUMBER;
    BEGIN
        -- ORACLE-DEP [EMBEDDED_SQL:NVL_FUNCTION] Default to today if null
        v_process_dt := NVL(p_process_date, TRUNC(SYSDATE));  -- ORACLE-DEP [EMBEDDED_SQL:TRUNC_DATE]

        -- Count pending transactions for the day
        SELECT COUNT(*) INTO v_txn_count
        FROM MERIDIAN.TRANSACTIONS
        WHERE TRUNC(transaction_date) = v_process_dt  -- ORACLE-DEP [EMBEDDED_SQL:TRUNC_DATE]
          AND status = 'PENDING';

        -- Update pending to completed using MERGE
        -- ORACLE-DEP [EMBEDDED_SQL:MERGE] Oracle MERGE for upsert
        MERGE INTO MERIDIAN.TRANSACTIONS tgt
        USING (
            SELECT transaction_id
            FROM MERIDIAN.TRANSACTIONS
            WHERE TRUNC(transaction_date) = v_process_dt
              AND status = 'PENDING'
        ) src
        ON (tgt.transaction_id = src.transaction_id)
        WHEN MATCHED THEN
            UPDATE SET tgt.status = 'COMPLETED';

        -- Apply interest for the month-end
        IF LAST_DAY(v_process_dt) = v_process_dt THEN  -- ORACLE-DEP [EMBEDDED_SQL:LAST_DAY]
            TRANSACTION_PKG.apply_monthly_interest;
        END IF;

        p_result_code := 0;
        p_result_msg  := 'EOD complete. Processed ' || v_txn_count || ' pending transactions.';
        COMMIT;
    EXCEPTION
        WHEN OTHERS THEN
            ROLLBACK;
            p_result_code := -1;
            p_result_msg  := 'EOD failed: ' || SQLERRM;
    END end_of_day_processing;

    PROCEDURE calculate_and_apply_interest(
        p_result_code   OUT NUMBER,
        p_rows_affected OUT NUMBER
    ) AS
    BEGIN
        TRANSACTION_PKG.apply_monthly_interest;
        p_rows_affected := SQL%ROWCOUNT;
        p_result_code   := 0;
    EXCEPTION
        WHEN OTHERS THEN
            ROLLBACK;
            p_result_code := -1;
    END calculate_and_apply_interest;

    PROCEDURE reconcile_account_balances(
        p_result_code OUT NUMBER,
        p_result_msg  OUT VARCHAR2
    ) AS
        v_discrepancy_count NUMBER := 0;
    BEGIN
        -- ORACLE-DEP [EMBEDDED_SQL:MERGE] Reconcile balance discrepancies
        MERGE INTO MERIDIAN.ACCOUNTS tgt
        USING (
            SELECT /*+ PARALLEL(t,2) */    -- ORACLE-DEP [EMBEDDED_SQL:ORACLE_HINTS]
                   t.account_id,
                   SUM(DECODE(t.transaction_type,         -- ORACLE-DEP [EMBEDDED_SQL:DECODE_FUNCTION]
                              'CREDIT',   t.amount,
                              'INTEREST', t.amount,
                              -t.amount)) AS computed_balance
            FROM MERIDIAN.TRANSACTIONS t
            WHERE t.status = 'COMPLETED'
            GROUP BY t.account_id
        ) src
        ON (tgt.account_id = src.account_id)
        WHEN MATCHED THEN
            UPDATE SET tgt.balance = src.computed_balance,
                       tgt.updated_date = SYSDATE        -- ORACLE-DEP [EMBEDDED_SQL:SYSDATE]
            WHERE ABS(tgt.balance - src.computed_balance) > 0.001;

        v_discrepancy_count := SQL%ROWCOUNT;
        p_result_code := 0;
        p_result_msg  := 'Reconciliation complete. Corrected ' ||
                         v_discrepancy_count || ' accounts.';
        COMMIT;
    EXCEPTION
        WHEN OTHERS THEN
            ROLLBACK;
            p_result_code := -1;
            p_result_msg  := 'Reconciliation failed: ' || SQLERRM;
    END reconcile_account_balances;

END BATCH_PKG;
/
