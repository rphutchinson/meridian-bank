-- ============================================================
-- 03-create-types.sql
-- Oracle user-defined object types and collection types
-- ORACLE-DEP [PLSQL_INVOCATION:ORACLE_UDT] Oracle user-defined types
-- ORACLE-DEP [ORACLE_JAVA_API:STRUCT_TYPE] Used with oracle.sql.STRUCT / StructDescriptor
-- ORACLE-DEP [ORACLE_JAVA_API:ARRAY_TYPE] Used with oracle.sql.ARRAY / ArrayDescriptor
-- ============================================================

ALTER SESSION SET CONTAINER = FREEPDB1;

-- ----------------------------------------------------------------
-- ADDRESS_TYPE: composite address structure
-- Used in AddressDAO with oracle.sql.STRUCT and StructDescriptor
-- ORACLE-DEP [PLSQL_INVOCATION:ORACLE_UDT] ADDRESS_TYPE Oracle object type
-- ----------------------------------------------------------------
CREATE OR REPLACE TYPE MERIDIAN.ADDRESS_TYPE AS OBJECT (
    street      VARCHAR2(200),
    city        VARCHAR2(100),
    state_code  VARCHAR2(50),
    zip_code    VARCHAR2(20),
    country     VARCHAR2(50)
);
/

-- ----------------------------------------------------------------
-- NUMBER_TABLE_TYPE: collection of NUMBERs for bulk operations
-- Used in BatchProcessingDAO with oracle.sql.ARRAY / ArrayDescriptor
-- ORACLE-DEP [PLSQL_INVOCATION:ORACLE_UDT] NUMBER_TABLE_TYPE collection
-- ----------------------------------------------------------------
CREATE OR REPLACE TYPE MERIDIAN.NUMBER_TABLE_TYPE AS TABLE OF NUMBER;
/

-- ----------------------------------------------------------------
-- VARCHAR2_TABLE_TYPE: collection of strings for bulk operations
-- ORACLE-DEP [PLSQL_INVOCATION:ORACLE_UDT] VARCHAR2_TABLE_TYPE collection
-- ----------------------------------------------------------------
CREATE OR REPLACE TYPE MERIDIAN.VARCHAR2_TABLE_TYPE AS TABLE OF VARCHAR2(4000);
/

-- ----------------------------------------------------------------
-- TRANSACTION_RECORD_TYPE: composite transaction record
-- Used in TransactionDAO for batch transaction inserts via STRUCT
-- ORACLE-DEP [PLSQL_INVOCATION:ORACLE_UDT] TRANSACTION_RECORD_TYPE object
-- ----------------------------------------------------------------
CREATE OR REPLACE TYPE MERIDIAN.TRANSACTION_RECORD_TYPE AS OBJECT (
    account_id        NUMBER(19),
    amount            NUMBER(19,4),
    transaction_type  VARCHAR2(20),
    description       VARCHAR2(500),
    category_code     VARCHAR2(50)
);
/

-- ----------------------------------------------------------------
-- TRANSACTION_TABLE_TYPE: collection of transaction records
-- Used in BatchProcessingDAO for array-binding bulk inserts
-- ORACLE-DEP [PLSQL_INVOCATION:ORACLE_UDT] TRANSACTION_TABLE_TYPE collection
-- ----------------------------------------------------------------
CREATE OR REPLACE TYPE MERIDIAN.TRANSACTION_TABLE_TYPE AS TABLE OF MERIDIAN.TRANSACTION_RECORD_TYPE;
/

-- ----------------------------------------------------------------
-- ACCOUNT_STATUS_RECORD: composite for account status updates
-- ORACLE-DEP [PLSQL_INVOCATION:ORACLE_UDT] ACCOUNT_STATUS_RECORD object
-- ----------------------------------------------------------------
CREATE OR REPLACE TYPE MERIDIAN.ACCOUNT_STATUS_RECORD AS OBJECT (
    account_id  NUMBER(19),
    new_status  VARCHAR2(20),
    reason      VARCHAR2(200)
);
/

-- ----------------------------------------------------------------
-- ACCOUNT_STATUS_TABLE: collection for bulk status updates
-- ORACLE-DEP [PLSQL_INVOCATION:ORACLE_UDT] ACCOUNT_STATUS_TABLE collection
-- ----------------------------------------------------------------
CREATE OR REPLACE TYPE MERIDIAN.ACCOUNT_STATUS_TABLE AS TABLE OF MERIDIAN.ACCOUNT_STATUS_RECORD;
/
