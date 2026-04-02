-- ============================================================
-- 14-create-text-indexes.sql
-- Create Oracle Text CONTEXT index AFTER seed data is loaded.
-- Preferences (lexer, stoplist) were created in 09-create-text-indexes.sql.
-- Running after 12-seed-data.sql guarantees DOCUMENTS is populated,
-- preventing the index from entering a FAILED state on creation.
-- ORACLE-DEP [EMBEDDED_SQL:ORACLE_TEXT] Oracle Text CONTEXT index
-- ORACLE-DEP [EMBEDDED_SQL:CTX_DDL] CTX_DDL package calls
-- ORACLE-DEP [EMBEDDED_SQL:CONTAINS] CONTAINS() used in DocumentRepository queries
-- ============================================================

ALTER SESSION SET CONTAINER = FREEPDB1;

-- ----------------------------------------------------------------
-- Drop any pre-existing (possibly broken) index first, then recreate.
-- A broken domain index can appear in user_indexes but be absent from
-- ctx_user_indexes, causing CONTAINS() to fail with DRG-10599.
-- Always drop and recreate to guarantee a clean state on each run.
-- ORACLE-DEP [EMBEDDED_SQL:ORACLE_TEXT] Oracle Text CONTEXT index
-- ----------------------------------------------------------------
BEGIN
    EXECUTE IMMEDIATE 'DROP INDEX MERIDIAN.IDX_DOC_CONTENT_TEXT';
EXCEPTION
    WHEN OTHERS THEN NULL;  -- ignore if does not exist
END;
/

-- ----------------------------------------------------------------
-- Create Oracle Text CONTEXT index on MERIDIAN.DOCUMENTS.content.
-- Uses default Oracle Text preferences for maximum portability.
-- Custom MERIDIAN_LEXER / MERIDIAN_STOPLIST (from 09-create-text-indexes.sql)
-- are set up separately as optional enhancements; not required here.
-- ORACLE-DEP [EMBEDDED_SQL:ORACLE_TEXT] Oracle Text CONTEXT index
-- ----------------------------------------------------------------
BEGIN
    EXECUTE IMMEDIATE '
        CREATE INDEX MERIDIAN.IDX_DOC_CONTENT_TEXT
        ON MERIDIAN.DOCUMENTS (content)
        INDEXTYPE IS CTXSYS.CONTEXT
        PARAMETERS (''SYNC (ON COMMIT)'')
    ';
EXCEPTION
    WHEN OTHERS THEN
        IF SQLCODE != -955 THEN RAISE; END IF;  -- ignore "already exists"
END;
/

-- ----------------------------------------------------------------
-- Initial sync to index the seed data rows already in DOCUMENTS
-- ORACLE-DEP [EMBEDDED_SQL:CTX_DDL] CTX_DDL.SYNC_INDEX
-- ----------------------------------------------------------------
BEGIN
    CTX_DDL.SYNC_INDEX('MERIDIAN.IDX_DOC_CONTENT_TEXT');
EXCEPTION
    WHEN OTHERS THEN NULL;
END;
/
