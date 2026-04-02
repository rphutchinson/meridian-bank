package com.meridianbank.entity;

import com.meridianbank.annotation.AutomationLevel;
import com.meridianbank.annotation.DependencyDomain;
import com.meridianbank.annotation.MigrationComplexity;
import com.meridianbank.annotation.OracleDependencies;
import com.meridianbank.annotation.OracleDependency;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.NamedNativeQueries;
import jakarta.persistence.NamedNativeQuery;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;

import java.time.LocalDate;

/**
 * JPA entity representing a Meridian Bank physical branch location.
 *
 * <p><strong>Oracle Spatial — SDO_GEOMETRY for Branch Location:</strong><br>
 * Each branch has a {@code LOCATION} column of type {@code MDSYS.SDO_GEOMETRY} storing the
 * branch's geographic coordinates as a 2D point (gtype=2001) in WGS-84 (SRID 8307).
 * As with {@link AddressEntity}, this column is intentionally <em>not</em> mapped as a JPA
 * {@code @Column} because Oracle's UDT requires a custom Hibernate {@code UserType}.
 * The column is populated and queried exclusively through {@code BranchService} and
 * the Oracle Spatial query in the named query below.
 *
 * <p>Transient fields {@code latitude} and {@code longitude} carry the coordinate values
 * across the service and controller layers so REST endpoints can accept and return coordinates
 * without exposing the Oracle Spatial internals.
 *
 * <p><strong>Oracle Spatial Named Query:</strong><br>
 * The {@code findNearestBranches} named query uses {@code SDO_WITHIN_DISTANCE} — an Oracle
 * Spatial operator that filters rows where the geometry column is within a specified radius of
 * a reference point. The reference point is constructed inline as an {@code MDSYS.SDO_GEOMETRY}
 * literal using:
 * <ul>
 *   <li>Geometry type 2001 = 2D single point</li>
 *   <li>SRID 8307 = Oracle's SRID for WGS-84 (longitude/latitude, degrees)</li>
 *   <li>{@code MDSYS.SDO_POINT_TYPE(lng, lat, NULL)} = the point's coordinates</li>
 * </ul>
 * The {@code 'distance=:radiusKm unit=KM'} parameter string is Oracle Spatial's proprietary
 * parameter syntax for SDO operator tolerances and units.
 *
 * <p>Oracle dependencies in this entity:
 * <ul>
 *   <li>Oracle Spatial SDO_GEOMETRY for the LOCATION column (DAO-managed)</li>
 *   <li>SDO_WITHIN_DISTANCE operator in the nearest-branch named query</li>
 *   <li>MDSYS.SDO_GEOMETRY / MDSYS.SDO_POINT_TYPE constructors in query</li>
 *   <li>Oracle sequence MERIDIAN.BRANCH_SEQ for PK generation</li>
 *   <li>NVL() in the findByRegion query for nullable phone number</li>
 *   <li>VARCHAR2 column definitions throughout</li>
 * </ul>
 *
 * <p>Migration to PostGIS:
 * <ul>
 *   <li>LOCATION column: MDSYS.SDO_GEOMETRY → GEOMETRY(POINT, 4326)</li>
 *   <li>SDO_WITHIN_DISTANCE → ST_DWithin(location, ST_MakePoint(lng, lat)::geography, radiusMeters)</li>
 *   <li>MDSYS.SDO_GEOMETRY constructor → ST_SetSRID(ST_MakePoint(lng, lat), 4326)</li>
 *   <li>Remove Oracle SRID 8307; PostGIS uses SRID 4326 for WGS-84</li>
 *   <li>Spatial index: CREATE INDEX ... INDEXTYPE IS MDSYS.SPATIAL_INDEX
 *       → CREATE INDEX ... USING GIST(location)</li>
 * </ul>
 */
// ORACLE-DEP [EMBEDDED_SQL:SDO_WITHIN_DISTANCE] Named query uses Oracle Spatial proximity operator
// ORACLE-DEP [EMBEDDED_SQL:SDO_GEOMETRY] LOCATION column is MDSYS.SDO_GEOMETRY (not JPA-mapped)
// ORACLE-DEP [ORM_DATA_ACCESS:SEQUENCE_GENERATOR] PK via Oracle sequence MERIDIAN.BRANCH_SEQ
@OracleDependencies({
    @OracleDependency(
        domain          = DependencyDomain.EMBEDDED_SQL,
        category        = "SDO_WITHIN_DISTANCE",
        description     = "Named query 'findNearestBranches' uses Oracle Spatial operator "
                        + "SDO_WITHIN_DISTANCE(geometry_col, reference_geometry, params) to find "
                        + "branches within a given radius of a supplied lat/lng point. "
                        + "The reference geometry is built inline as MDSYS.SDO_GEOMETRY(2001, 8307, "
                        + "MDSYS.SDO_POINT_TYPE(lng, lat, NULL), NULL, NULL). "
                        + "SDO_WITHIN_DISTANCE returns 'TRUE'/'FALSE' and requires a domain index "
                        + "(MDSYS.SPATIAL_INDEX) to be efficient. The distance parameter uses Oracle's "
                        + "proprietary string format: 'distance=n unit=KM'.",
        complexity      = MigrationComplexity.HIGH,
        automationLevel = AutomationLevel.PARTIAL,
        migrationTarget = "Replace with PostGIS: ST_DWithin(location::geography, "
                        + "ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)::geography, :radiusMeters). "
                        + "Note: ST_DWithin with geography type uses meters, not km. "
                        + "Replace MDSYS.SPATIAL_INDEX with CREATE INDEX USING GIST."
    ),
    @OracleDependency(
        domain          = DependencyDomain.EMBEDDED_SQL,
        category        = "SDO_GEOMETRY",
        description     = "BRANCHES.LOCATION column is Oracle Spatial MDSYS.SDO_GEOMETRY storing "
                        + "the branch's geocoded position as a 2D point (gtype=2001) in WGS-84 "
                        + "(SRID 8307). Column is not JPA-mapped; managed by BranchOracleDAO via "
                        + "oracle.sql.STRUCT + StructDescriptor. Spatial metadata must be registered "
                        + "in USER_SDO_GEOM_METADATA for the spatial index to be created.",
        complexity      = MigrationComplexity.HIGH,
        automationLevel = AutomationLevel.PARTIAL,
        migrationTarget = "Replace MDSYS.SDO_GEOMETRY with PostGIS GEOMETRY(POINT, 4326). "
                        + "Use Hibernate Spatial + @Column(columnDefinition='geometry(Point,4326)'). "
                        + "Remove USER_SDO_GEOM_METADATA entries. Create GIST index instead of SPATIAL_INDEX."
    ),
    @OracleDependency(
        domain          = DependencyDomain.ORM_DATA_ACCESS,
        category        = "SEQUENCE_GENERATOR",
        description     = "Branch primary key generated via Oracle sequence MERIDIAN.BRANCH_SEQ.",
        complexity      = MigrationComplexity.LOW,
        automationLevel = AutomationLevel.FULL,
        migrationTarget = "Point @SequenceGenerator to a PostgreSQL sequence or use GenerationType.IDENTITY"
    ),
    @OracleDependency(
        domain          = DependencyDomain.EMBEDDED_SQL,
        category        = "NVL_FUNCTION",
        description     = "Named query 'findByRegion' uses NVL(phone, 'N/A') to return a safe "
                        + "display value when the branch phone number is NULL.",
        complexity      = MigrationComplexity.LOW,
        automationLevel = AutomationLevel.FULL,
        migrationTarget = "Replace NVL(phone, 'N/A') with COALESCE(phone, 'N/A') in PostgreSQL"
    )
})
@Entity
@Table(name = "BRANCHES", schema = "MERIDIAN")
@NamedNativeQueries({
    // ORACLE-DEP [EMBEDDED_SQL:SDO_WITHIN_DISTANCE] Oracle Spatial proximity operator
    // ORACLE-DEP [EMBEDDED_SQL:SDO_GEOMETRY] Inline SDO_GEOMETRY constructor for reference point
    @NamedNativeQuery(
        name  = "BranchEntity.findNearestBranches",
        query = "SELECT b.branch_id, b.branch_name, b.branch_code, b.region, b.phone, "
              + "b.email, b.address_id, b.status, b.created_date "
              + "FROM MERIDIAN.BRANCHES b "
              + "WHERE SDO_WITHIN_DISTANCE("                           // ORACLE-DEP: SDO_WITHIN_DISTANCE operator
              + "  b.location, "
              + "  MDSYS.SDO_GEOMETRY("                                // ORACLE-DEP: SDO_GEOMETRY constructor
              + "    2001, "                                            // gtype: 2D single point
              + "    8307, "                                            // SRID: Oracle WGS-84
              + "    MDSYS.SDO_POINT_TYPE(:lng, :lat, NULL), "         // ORACLE-DEP: SDO_POINT_TYPE
              + "    NULL, NULL"
              + "  ), "
              + "  'distance=' || :radiusKm || ' unit=KM'"             // ORACLE-DEP: Oracle Spatial param string
              + ") = 'TRUE' "
              + "AND b.status = 'OPEN'"
    ),
    // ORACLE-DEP [EMBEDDED_SQL:NVL_FUNCTION] NVL returns 'N/A' when branch phone is NULL
    @NamedNativeQuery(
        name  = "BranchEntity.findByRegion",
        query = "SELECT b.branch_id, b.branch_name, b.branch_code, b.manager_id, "
              + "b.region, b.email, b.address_id, b.status, b.created_date, "
              + "NVL(b.phone, 'N/A') AS contact_phone "               // ORACLE-DEP: NVL — COALESCE in PostgreSQL
              + "FROM MERIDIAN.BRANCHES b "
              + "WHERE b.region = :region "
              + "AND b.status = 'OPEN' "
              + "ORDER BY b.branch_name"
    )
})
public class BranchEntity {

    // ORACLE-DEP [ORM_DATA_ACCESS:SEQUENCE_GENERATOR] Oracle sequence for PK generation
    @Id
    @SequenceGenerator(
        name           = "branchSeqGen",
        sequenceName   = "MERIDIAN.BRANCH_SEQ",   // ORACLE-DEP: Oracle sequence reference
        allocationSize = 1
    )
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "branchSeqGen")
    @Column(name = "BRANCH_ID")
    private Long branchId;

    // ORACLE-DEP [EMBEDDED_SQL:VARCHAR2_TYPE] Branch name stored in Oracle VARCHAR2(200)
    @Column(name = "BRANCH_NAME", nullable = false, columnDefinition = "VARCHAR2(200)")
    private String branchName;

    // ORACLE-DEP [EMBEDDED_SQL:VARCHAR2_TYPE] Short branch code (e.g., "NYC-001") in VARCHAR2(20)
    @Column(name = "BRANCH_CODE", nullable = false, unique = true, columnDefinition = "VARCHAR2(20)")
    private String branchCode;

    @Column(name = "MANAGER_ID")
    private Long managerId;

    // ORACLE-DEP [EMBEDDED_SQL:VARCHAR2_TYPE] Geographic region code in VARCHAR2(50)
    @Column(name = "REGION", columnDefinition = "VARCHAR2(50)")
    private String region;

    /**
     * Branch contact phone number.
     * Nullable — some branches may share a regional phone.
     * NVL(phone, 'N/A') used in named query to provide safe display value.
     *
     * ORACLE-DEP [EMBEDDED_SQL:VARCHAR2_TYPE] Nullable phone in VARCHAR2(20)
     */
    // ORACLE-DEP [EMBEDDED_SQL:VARCHAR2_TYPE] VARCHAR2(20) nullable phone — NVL used in query
    @Column(name = "PHONE", columnDefinition = "VARCHAR2(20)")
    private String phone;

    // ORACLE-DEP [EMBEDDED_SQL:VARCHAR2_TYPE] Email in VARCHAR2(200)
    @Column(name = "EMAIL", columnDefinition = "VARCHAR2(200)")
    private String email;

    @Column(name = "ADDRESS_ID")
    private Long addressId;

    // ORACLE-DEP [EMBEDDED_SQL:VARCHAR2_TYPE] Status VARCHAR2 — 'OPEN', 'CLOSED', 'RENOVATING'
    @Column(name = "STATUS", nullable = false, columnDefinition = "VARCHAR2(20)")
    private String status;

    // ORACLE-DEP [EMBEDDED_SQL:DATE_TYPE] Oracle DATE audit column
    @Column(name = "CREATED_DATE", nullable = false, columnDefinition = "DATE")
    private LocalDate createdDate;

    /**
     * NOTE: Oracle SDO_GEOMETRY LOCATION column NOT mapped here.
     *
     * The BRANCHES.LOCATION column (MDSYS.SDO_GEOMETRY) stores the branch's geographic
     * position and is required for the SDO_WITHIN_DISTANCE spatial query above.
     * It is managed exclusively by the DAO layer via oracle.sql.STRUCT.
     * See class-level Javadoc for full migration guidance.
     *
     * ORACLE-DEP [EMBEDDED_SQL:SDO_GEOMETRY] LOCATION column managed by DAO, not JPA
     */
    // ORACLE-DEP [EMBEDDED_SQL:SDO_GEOMETRY] LOCATION (MDSYS.SDO_GEOMETRY) is DAO-managed

    /**
     * Transient latitude component extracted from the SDO_GEOMETRY LOCATION column.
     * Populated by BranchService after the DAO reads the spatial column.
     * Value is in decimal degrees (WGS-84 / EPSG:4326).
     */
    @Transient
    private Double latitude;

    /**
     * Transient longitude component extracted from the SDO_GEOMETRY LOCATION column.
     * Populated by BranchService after the DAO reads the spatial column.
     * Value is in decimal degrees (WGS-84 / EPSG:4326).
     */
    @Transient
    private Double longitude;
    public BranchEntity() {
    }


    public Long getBranchId() {
        return branchId;
    }

    public void setBranchId(Long branchId) {
        this.branchId = branchId;
    }

    public String getBranchName() {
        return branchName;
    }

    public void setBranchName(String branchName) {
        this.branchName = branchName;
    }

    public String getBranchCode() {
        return branchCode;
    }

    public void setBranchCode(String branchCode) {
        this.branchCode = branchCode;
    }

    public Long getManagerId() {
        return managerId;
    }

    public void setManagerId(Long managerId) {
        this.managerId = managerId;
    }

    public String getRegion() {
        return region;
    }

    public void setRegion(String region) {
        this.region = region;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public Long getAddressId() {
        return addressId;
    }

    public void setAddressId(Long addressId) {
        this.addressId = addressId;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public LocalDate getCreatedDate() {
        return createdDate;
    }

    public void setCreatedDate(LocalDate createdDate) {
        this.createdDate = createdDate;
    }

    public Double getLatitude() {
        return latitude;
    }

    public void setLatitude(Double latitude) {
        this.latitude = latitude;
    }

    public Double getLongitude() {
        return longitude;
    }

    public void setLongitude(Double longitude) {
        this.longitude = longitude;
    }

}
