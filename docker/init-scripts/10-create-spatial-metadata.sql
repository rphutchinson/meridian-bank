-- ============================================================
-- 10-create-spatial-metadata.sql
-- Oracle Spatial metadata for branches and addresses.
-- Insert directly into MDSYS.SDO_GEOM_METADATA_TABLE as SYSTEM
-- specifying SDO_OWNER = 'MERIDIAN' so the metadata is associated
-- with the correct schema.  Spatial indexes are in 13-create-spatial-indexes.sql
-- (after seed data) because Oracle R-tree indexes require populated tables.
-- ORACLE-DEP [EMBEDDED_SQL:SDO_GEOMETRY] Oracle Spatial geometry
-- ORACLE-DEP [EMBEDDED_SQL:SDO_WITHIN_DISTANCE] Spatial query functions
-- ============================================================

ALTER SESSION SET CONTAINER = FREEPDB1;

-- ----------------------------------------------------------------
-- Register spatial metadata for MERIDIAN.BRANCHES.LOCATION
-- ORACLE-DEP [EMBEDDED_SQL:SDO_GEOMETRY] SDO_GEOM_METADATA entry
-- ----------------------------------------------------------------
DELETE FROM MDSYS.SDO_GEOM_METADATA_TABLE
WHERE sdo_owner = 'MERIDIAN' AND sdo_table_name = 'BRANCHES' AND sdo_column_name = 'LOCATION';

INSERT INTO MDSYS.SDO_GEOM_METADATA_TABLE (
    sdo_owner,
    sdo_table_name,
    sdo_column_name,
    sdo_diminfo,
    sdo_srid
) VALUES (
    'MERIDIAN',
    'BRANCHES',
    'LOCATION',
    MDSYS.SDO_DIM_ARRAY(
        MDSYS.SDO_DIM_ELEMENT('LONGITUDE', -180, 180, 0.005),
        MDSYS.SDO_DIM_ELEMENT('LATITUDE',  -90,  90,  0.005)
    ),
    8307  -- WGS84 coordinate system (GPS)
);

-- ----------------------------------------------------------------
-- Register spatial metadata for MERIDIAN.ADDRESSES.GEO_LOCATION
-- ORACLE-DEP [EMBEDDED_SQL:SDO_GEOMETRY] SDO_GEOM_METADATA entry
-- ----------------------------------------------------------------
DELETE FROM MDSYS.SDO_GEOM_METADATA_TABLE
WHERE sdo_owner = 'MERIDIAN' AND sdo_table_name = 'ADDRESSES' AND sdo_column_name = 'GEO_LOCATION';

INSERT INTO MDSYS.SDO_GEOM_METADATA_TABLE (
    sdo_owner,
    sdo_table_name,
    sdo_column_name,
    sdo_diminfo,
    sdo_srid
) VALUES (
    'MERIDIAN',
    'ADDRESSES',
    'GEO_LOCATION',
    MDSYS.SDO_DIM_ARRAY(
        MDSYS.SDO_DIM_ELEMENT('LONGITUDE', -180, 180, 0.005),
        MDSYS.SDO_DIM_ELEMENT('LATITUDE',  -90,  90,  0.005)
    ),
    8307
);

COMMIT;
