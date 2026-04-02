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
import jakarta.persistence.NamedNativeQuery;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;

import java.time.LocalDate;

/**
 * JPA entity representing a physical postal address associated with a customer or branch.
 *
 * <p><strong>Oracle Spatial Note — SDO_GEOMETRY:</strong><br>
 * The ADDRESSES table has a {@code LOCATION} column of type {@code MDSYS.SDO_GEOMETRY}, which
 * stores the geocoded latitude/longitude of the address as a 2D point geometry in the
 * WGS-84 coordinate system (SRID 8307). This column is <em>not mapped as a JPA @Column</em>
 * because Oracle SDO_GEOMETRY is a complex Oracle object type (UDT) that requires a custom
 * Hibernate {@code UserType} implementation to serialize/deserialize correctly. Attempting to
 * map it with a standard JPA column definition causes Hibernate schema-validation failures.
 *
 * <p>The SDO_GEOMETRY column is populated at INSERT time via the
 * {@code AddressOracleDAO.insertWithGeometry()} method, which constructs the geometry using
 * raw JDBC and an Oracle STRUCT:
 * <pre>{@code
 *   // ORACLE-DEP [ORACLE_JAVA_API:STRUCT_TYPE] Oracle STRUCT used to build SDO_GEOMETRY
 *   STRUCT sdoGeometry = new STRUCT(
 *       StructDescriptor.createDescriptor("MDSYS.SDO_GEOMETRY", oracleConn),
 *       oracleConn,
 *       new Object[]{ 2001, 8307, sdoPoint, null, null }
 *   );
 * }</pre>
 *
 * <p>A transient {@code geoWkt} field is provided for carrying the WKT representation
 * ("POINT(lng lat)") between layers without triggering JPA persistence.
 *
 * <p><strong>Migration to PostGIS:</strong><br>
 * Replace the Oracle SDO_GEOMETRY column with a PostGIS {@code GEOMETRY(POINT, 4326)} column.
 * The Hibernate {@code UserType} must be replaced with the Hibernate Spatial
 * ({@code org.hibernate.spatial}) type handler for PostGIS. The coordinate system changes
 * from Oracle SRID 8307 (WGS-84) to PostGIS SRID 4326 (also WGS-84 — same datum, just
 * a different SRID registry entry). Spatial queries such as {@code SDO_WITHIN_DISTANCE} →
 * {@code ST_DWithin}, {@code SDO_NN} → {@code ST_ClosestPoint / ORDER BY ST_Distance LIMIT n}.
 *
 * <p>Oracle dependencies in this entity:
 * <ul>
 *   <li>Oracle Spatial SDO_GEOMETRY type for the LOCATION column (not JPA-mapped; DAO-managed)</li>
 *   <li>Oracle sequence MERIDIAN.ADDRESS_SEQ for PK generation</li>
 *   <li>VARCHAR2 column definitions for all String fields</li>
 *   <li>NVL() in the named query for nullable stateCode</li>
 * </ul>
 */
// ORACLE-DEP [EMBEDDED_SQL:SDO_GEOMETRY] LOCATION column uses Oracle Spatial SDO_GEOMETRY type
// ORACLE-DEP [ORM_DATA_ACCESS:SEQUENCE_GENERATOR] PK via Oracle sequence MERIDIAN.ADDRESS_SEQ
@OracleDependencies({
    @OracleDependency(
        domain          = DependencyDomain.EMBEDDED_SQL,
        category        = "SDO_GEOMETRY",
        description     = "The ADDRESSES.LOCATION column is Oracle Spatial type MDSYS.SDO_GEOMETRY. "
                        + "It stores 2D point geometry (gtype=2001) in WGS-84 (SRID 8307) representing "
                        + "the geocoded lat/lng of the address. This is an Oracle User-Defined Type "
                        + "and cannot be mapped with standard JPA @Column. The DAO layer uses "
                        + "oracle.sql.STRUCT + StructDescriptor to construct and retrieve the geometry. "
                        + "Spatial queries use Oracle SDO operators: SDO_WITHIN_DISTANCE, SDO_NN, "
                        + "SDO_RELATE. Oracle Spatial also requires USER_SDO_GEOM_METADATA registration "
                        + "and a spatial index (CREATE INDEX ... INDEXTYPE IS MDSYS.SPATIAL_INDEX).",
        complexity      = MigrationComplexity.HIGH,
        automationLevel = AutomationLevel.PARTIAL,
        migrationTarget = "Replace MDSYS.SDO_GEOMETRY with PostGIS GEOMETRY(POINT, 4326). "
                        + "Replace StructDescriptor/STRUCT DAO code with Hibernate Spatial "
                        + "(org.hibernate.spatial) and the @Column(columnDefinition='geometry(Point,4326)') "
                        + "mapping. Replace SDO_WITHIN_DISTANCE with ST_DWithin, SDO_NN with "
                        + "ORDER BY ST_Distance(...) LIMIT n. Remove USER_SDO_GEOM_METADATA entries; "
                        + "PostGIS uses CREATE INDEX ... USING GIST instead of SPATIAL_INDEX."
    ),
    @OracleDependency(
        domain          = DependencyDomain.ORM_DATA_ACCESS,
        category        = "SEQUENCE_GENERATOR",
        description     = "Address primary key generated via Oracle sequence MERIDIAN.ADDRESS_SEQ.",
        complexity      = MigrationComplexity.LOW,
        automationLevel = AutomationLevel.FULL,
        migrationTarget = "Point @SequenceGenerator to a PostgreSQL sequence or use GenerationType.IDENTITY"
    ),
    @OracleDependency(
        domain          = DependencyDomain.EMBEDDED_SQL,
        category        = "NVL_FUNCTION",
        description     = "Named query 'findByCity' uses NVL(state_code, 'N/A') to return a safe "
                        + "display value when stateCode is NULL. PostgreSQL equivalent is COALESCE().",
        complexity      = MigrationComplexity.LOW,
        automationLevel = AutomationLevel.FULL,
        migrationTarget = "Replace NVL(state_code, 'N/A') with COALESCE(state_code, 'N/A')"
    )
})
@Entity
@Table(name = "ADDRESSES", schema = "MERIDIAN")
@NamedNativeQuery(
    // ORACLE-DEP [EMBEDDED_SQL:NVL_FUNCTION] NVL returns 'N/A' when state_code is NULL
    name  = "AddressEntity.findByCity",
    query = "SELECT a.address_id, a.street, a.city, a.state_code, a.zip_code, "
          + "a.country, a.created_date, a.updated_date, "
          + "NVL(a.state_code, 'N/A') AS state_display "              // ORACLE-DEP: NVL — COALESCE in PostgreSQL
          + "FROM MERIDIAN.ADDRESSES a "
          + "WHERE UPPER(a.city) = UPPER(:city)"
)
public class AddressEntity {

    // ORACLE-DEP [ORM_DATA_ACCESS:SEQUENCE_GENERATOR] Oracle sequence for PK generation
    @Id
    @SequenceGenerator(
        name           = "addressSeqGen",
        sequenceName   = "MERIDIAN.ADDRESS_SEQ",   // ORACLE-DEP: Oracle sequence reference
        allocationSize = 1
    )
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "addressSeqGen")
    @Column(name = "ADDRESS_ID")
    private Long addressId;

    // ORACLE-DEP [EMBEDDED_SQL:VARCHAR2_TYPE] Street stored in Oracle VARCHAR2(200)
    @Column(name = "STREET", nullable = false, columnDefinition = "VARCHAR2(200)")
    private String street;

    // ORACLE-DEP [EMBEDDED_SQL:VARCHAR2_TYPE] City stored in Oracle VARCHAR2(100)
    @Column(name = "CITY", nullable = false, columnDefinition = "VARCHAR2(100)")
    private String city;

    /**
     * US state code or international province/region.
     * Nullable — not all countries have a state-equivalent subdivision.
     * NVL(state_code, 'N/A') used in named query for safe display.
     *
     * ORACLE-DEP [EMBEDDED_SQL:VARCHAR2_TYPE] stateCode stored in VARCHAR2(50)
     */
    // ORACLE-DEP [EMBEDDED_SQL:VARCHAR2_TYPE] VARCHAR2(50) nullable state — NVL used in query
    @Column(name = "STATE_CODE", columnDefinition = "VARCHAR2(50)")
    private String stateCode;

    // ORACLE-DEP [EMBEDDED_SQL:VARCHAR2_TYPE] ZIP/postal code in VARCHAR2(20)
    @Column(name = "ZIP_CODE", columnDefinition = "VARCHAR2(20)")
    private String zipCode;

    // ORACLE-DEP [EMBEDDED_SQL:VARCHAR2_TYPE] Country name in VARCHAR2(50)
    @Column(name = "COUNTRY", nullable = false, columnDefinition = "VARCHAR2(50)")
    private String country;

    // ORACLE-DEP [EMBEDDED_SQL:DATE_TYPE] Oracle DATE audit column
    @Column(name = "CREATED_DATE", nullable = false, columnDefinition = "DATE")
    private LocalDate createdDate;

    // ORACLE-DEP [EMBEDDED_SQL:DATE_TYPE] Oracle DATE updated by DB trigger
    @Column(name = "UPDATED_DATE", columnDefinition = "DATE")
    private LocalDate updatedDate;

    /**
     * NOTE: Oracle SDO_GEOMETRY column NOT mapped here.
     *
     * The ADDRESSES.LOCATION column (MDSYS.SDO_GEOMETRY) cannot be expressed as a standard
     * JPA @Column mapping because Oracle's spatial type is a User-Defined Type (UDT) that
     * requires a Hibernate UserType implementation to marshal/unmarshal. Attempts to add:
     *
     *   {@code @Column(name = "LOCATION", columnDefinition = "MDSYS.SDO_GEOMETRY")}
     *
     * will fail at Hibernate schema validation time because Hibernate does not know how to
     * bind a Java type to SDO_GEOMETRY's multi-attribute Oracle STRUCT.
     *
     * The spatial column is managed exclusively through {@code AddressOracleDAO}, which uses
     * {@code oracle.sql.STRUCT} + {@code StructDescriptor.createDescriptor("MDSYS.SDO_GEOMETRY")}
     * to construct and read the geometry value directly via JDBC.
     *
     * ORACLE-DEP [EMBEDDED_SQL:SDO_GEOMETRY] Oracle Spatial type — see class-level javadoc
     * ORACLE-DEP [ORACLE_JAVA_API:STRUCT_TYPE] DAO uses STRUCT to build SDO_GEOMETRY value
     */
    // ORACLE-DEP [EMBEDDED_SQL:SDO_GEOMETRY] LOCATION column managed by DAO, not JPA
    // sdoGeometryNote: The LOCATION column is SDO_GEOMETRY — managed via oracle.sql.STRUCT in DAO

    /**
     * Transient WKT representation of the address's geographic location.
     * Format: "POINT(longitude latitude)" — e.g., "POINT(-73.9857 40.7484)".
     * Populated by the service layer after reading the SDO_GEOMETRY from Oracle
     * and converting via OracleStructHelper.sdoGeometryToWkt().
     * Not persisted by JPA.
     */
    @Transient
    private String geoWkt;
    public AddressEntity() {
    }


    public Long getAddressId() {
        return addressId;
    }

    public void setAddressId(Long addressId) {
        this.addressId = addressId;
    }

    public String getStreet() {
        return street;
    }

    public void setStreet(String street) {
        this.street = street;
    }

    public String getCity() {
        return city;
    }

    public void setCity(String city) {
        this.city = city;
    }

    public String getStateCode() {
        return stateCode;
    }

    public void setStateCode(String stateCode) {
        this.stateCode = stateCode;
    }

    public String getZipCode() {
        return zipCode;
    }

    public void setZipCode(String zipCode) {
        this.zipCode = zipCode;
    }

    public String getCountry() {
        return country;
    }

    public void setCountry(String country) {
        this.country = country;
    }

    public LocalDate getCreatedDate() {
        return createdDate;
    }

    public void setCreatedDate(LocalDate createdDate) {
        this.createdDate = createdDate;
    }

    public LocalDate getUpdatedDate() {
        return updatedDate;
    }

    public void setUpdatedDate(LocalDate updatedDate) {
        this.updatedDate = updatedDate;
    }

    public String getGeoWkt() {
        return geoWkt;
    }

    public void setGeoWkt(String geoWkt) {
        this.geoWkt = geoWkt;
    }

}
