-- ============================================================
-- 09-create-text-indexes.sql
-- Oracle Text preferences only (no index creation here).
-- The CONTEXT index is created in 14-create-text-indexes.sql,
-- which runs after seed data so the table is populated and the
-- index does not end up in a FAILED state.
-- ORACLE-DEP [EMBEDDED_SQL:CTX_DDL] CTX_DDL package calls
-- ============================================================

ALTER SESSION SET CONTAINER = FREEPDB1;

-- ----------------------------------------------------------------
-- Create Oracle Text lexer preference
-- ORACLE-DEP [EMBEDDED_SQL:CTX_DDL] CTX_DDL.CREATE_PREFERENCE
-- ----------------------------------------------------------------
BEGIN
    CTX_DDL.CREATE_PREFERENCE(
        preference_name => 'MERIDIAN_LEXER',
        object_name     => 'BASIC_LEXER'
    );
    CTX_DDL.SET_ATTRIBUTE('MERIDIAN_LEXER', 'MIXED_CASE', 'NO');
    CTX_DDL.SET_ATTRIBUTE('MERIDIAN_LEXER', 'BASE_LETTER', 'YES');
EXCEPTION
    WHEN OTHERS THEN
        IF SQLCODE != -20000 THEN RAISE; END IF;  -- ignore "already exists"
END;
/

-- ----------------------------------------------------------------
-- Create stoplist (common words to exclude from indexing)
-- ORACLE-DEP [EMBEDDED_SQL:CTX_DDL] CTX_DDL.CREATE_STOPLIST
-- ----------------------------------------------------------------
BEGIN
    CTX_DDL.CREATE_STOPLIST(
        stoplist_name => 'MERIDIAN_STOPLIST',
        stoplist_type => 'BASIC_STOPLIST'
    );
    CTX_DDL.ADD_STOPWORD('MERIDIAN_STOPLIST', 'the');
    CTX_DDL.ADD_STOPWORD('MERIDIAN_STOPLIST', 'and');
    CTX_DDL.ADD_STOPWORD('MERIDIAN_STOPLIST', 'for');
    CTX_DDL.ADD_STOPWORD('MERIDIAN_STOPLIST', 'with');
EXCEPTION
    WHEN OTHERS THEN
        IF SQLCODE != -20000 THEN RAISE; END IF;
END;
/
