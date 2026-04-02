-- ============================================================
-- 13-create-spatial-indexes.sql
-- Create Oracle R-tree spatial indexes AFTER seed data is loaded.
-- Oracle requires the table to be non-empty and have spatial
-- metadata registered (done in 10-create-spatial-metadata.sql)
-- before a spatial index can be created.
-- ORACLE-DEP [EMBEDDED_SQL:SPATIAL_INDEX] Oracle R-tree spatial index
-- ============================================================

ALTER SESSION SET CONTAINER = FREEPDB1;

-- ----------------------------------------------------------------
-- Spatial index on MERIDIAN.BRANCHES.LOCATION
-- ORACLE-DEP [EMBEDDED_SQL:SPATIAL_INDEX] Oracle R-tree spatial index
-- Used by BranchService for SDO_WITHIN_DISTANCE and SDO_NN queries
-- ----------------------------------------------------------------
BEGIN
    EXECUTE IMMEDIATE '
        CREATE INDEX MERIDIAN.IDX_BRANCH_LOCATION
        ON MERIDIAN.BRANCHES (location)
        INDEXTYPE IS MDSYS.SPATIAL_INDEX
        PARAMETERS (''SDO_INDX_DIMS=2 LAYER_GTYPE=POINT'')
    ';
EXCEPTION
    WHEN OTHERS THEN
        IF SQLCODE != -955 THEN RAISE; END IF;
END;
/

-- ----------------------------------------------------------------
-- Spatial index on MERIDIAN.ADDRESSES.GEO_LOCATION
-- ORACLE-DEP [EMBEDDED_SQL:SPATIAL_INDEX] Oracle R-tree spatial index
-- All address geo_location values are NULL in seed data (the column
-- exists to demonstrate NVL usage in spatial queries).
-- Wrapping in exception block — spatial index on all-NULL column
-- may raise ORA-13032 or be silently skipped; either is acceptable.
-- ----------------------------------------------------------------
BEGIN
    EXECUTE IMMEDIATE '
        CREATE INDEX MERIDIAN.IDX_ADDR_GEO_LOC
        ON MERIDIAN.ADDRESSES (geo_location)
        INDEXTYPE IS MDSYS.SPATIAL_INDEX
        PARAMETERS (''SDO_INDX_DIMS=2 LAYER_GTYPE=POINT'')
    ';
EXCEPTION
    WHEN OTHERS THEN NULL;  -- acceptable: all-NULL column or already exists
END;
/
