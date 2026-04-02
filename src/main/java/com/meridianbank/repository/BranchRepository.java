package com.meridianbank.repository;

import com.meridianbank.annotation.*;
import com.meridianbank.entity.BranchEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Spring Data JPA repository for BranchEntity.
 * Demonstrates Oracle Spatial constructs: SDO_WITHIN_DISTANCE, SDO_NN,
 * SDO_GEOM.SDO_DISTANCE, and SDO_GEOMETRY literal construction.
 * Also uses NVL, DECODE for branch data normalization.
 */
@Repository
public interface BranchRepository extends JpaRepository<BranchEntity, Long> {

    // Standard JPQL — no Oracle dependency
    Optional<BranchEntity> findByBranchCode(String branchCode);
    List<BranchEntity> findByStatus(String status);
    List<BranchEntity> findByRegion(String region);

    // ORACLE-DEP [EMBEDDED_SQL:ORACLE_SPATIAL] SDO_WITHIN_DISTANCE for radius search
    @OracleDependency(
        domain = DependencyDomain.EMBEDDED_SQL,
        category = "ORACLE_SPATIAL",
        description = "SDO_WITHIN_DISTANCE compares branch SDO_GEOMETRY location against constructed point geometry",
        complexity = MigrationComplexity.VERY_HIGH,
        automationLevel = AutomationLevel.MANUAL,
        migrationTarget = "Replace with PostGIS ST_DWithin(location::geography, ST_SetSRID(ST_MakePoint(lng,lat),4326)::geography, radius_meters)"
    )
    @Query(value =
        "SELECT b.branch_id, b.branch_name, b.branch_code, b.address, b.phone, b.status, " +
        "  SDO_GEOM.SDO_DISTANCE(" +
        "    b.location, " +
        "    SDO_GEOMETRY(2001, 8307, SDO_POINT_TYPE(:lng, :lat, NULL), NULL, NULL), " +
        "    0.5, 'unit=KM'" +
        "  ) AS distance_km " +
        "FROM MERIDIAN.BRANCHES b " +
        "WHERE SDO_WITHIN_DISTANCE(" +
        "    b.location, " +
        "    SDO_GEOMETRY(2001, 8307, SDO_POINT_TYPE(:lng, :lat, NULL), NULL, NULL), " +
        "    'distance=' || :radiusKm || ' unit=KM'" +
        "  ) = 'TRUE' " +
        "AND b.status = 'OPEN' " +
        "ORDER BY distance_km ASC",
        nativeQuery = true)
    List<Object[]> findBranchesWithinRadius(@Param("lat") double lat,
                                             @Param("lng") double lng,
                                             @Param("radiusKm") double radiusKm);

    // ORACLE-DEP [EMBEDDED_SQL:ORACLE_SPATIAL] SDO_NN for nearest-neighbor search
    @OracleDependency(
        domain = DependencyDomain.EMBEDDED_SQL,
        category = "ORACLE_SPATIAL",
        description = "SDO_NN finds the n nearest branches using Oracle Spatial nearest-neighbor operator",
        complexity = MigrationComplexity.VERY_HIGH,
        automationLevel = AutomationLevel.MANUAL,
        migrationTarget = "Replace with PostGIS ORDER BY location <-> ST_SetSRID(ST_MakePoint(lng,lat),4326) LIMIT n"
    )
    @Query(value =
        "SELECT b.branch_id, b.branch_name, b.branch_code, b.region, b.status, " +
        "  SDO_NN_DISTANCE(1) AS distance_meters " +
        "FROM MERIDIAN.BRANCHES b " +
        "WHERE SDO_NN(" +
        "    b.location, " +
        "    SDO_GEOMETRY(2001, 8307, SDO_POINT_TYPE(:lng, :lat, NULL), NULL, NULL), " +
        "    'sdo_num_res=' || :maxResults || ' unit=M', " +
        "    1" +
        "  ) = 'TRUE' " +
        "AND b.status = 'OPEN' " +
        "ORDER BY distance_meters ASC",
        nativeQuery = true)
    List<Object[]> findNearestBranches(@Param("lat") double lat,
                                        @Param("lng") double lng,
                                        @Param("maxResults") int maxResults);

    // ORACLE-DEP [EMBEDDED_SQL:NVL_FUNCTION] NVL for null-safe branch display
    @OracleDependency(
        domain = DependencyDomain.EMBEDDED_SQL,
        category = "NVL_FUNCTION",
        description = "NVL(manager_name,'Unassigned') provides default when branch manager is not set",
        complexity = MigrationComplexity.LOW,
        automationLevel = AutomationLevel.FULL,
        migrationTarget = "Replace NVL(manager_name,'Unassigned') with COALESCE(manager_name,'Unassigned') in PostgreSQL"
    )
    @OracleDependency(
        domain = DependencyDomain.EMBEDDED_SQL,
        category = "DECODE_FUNCTION",
        description = "DECODE(b.status,...) maps branch status codes to display labels",
        complexity = MigrationComplexity.LOW,
        automationLevel = AutomationLevel.FULL,
        migrationTarget = "Replace DECODE with CASE WHEN in PostgreSQL"
    )
    @Query(value =
        // ORACLE-DEP [EMBEDDED_SQL:NVL_FUNCTION] NVL(manager,'Unassigned') — use COALESCE in PostgreSQL
        // ORACLE-DEP [EMBEDDED_SQL:DECODE_FUNCTION] DECODE(status,...) — use CASE WHEN in PostgreSQL
        // Column order matches BranchService.mapRowToBranchDTO:
        //   [0]=branch_id [1]=branch_code [2]=branch_name [3]=region [4]=phone [5]=status_label [6]=manager_name
        "SELECT b.branch_id, b.branch_code, b.branch_name, b.region, b.phone, " +
        "  DECODE(b.status, 'OPEN', 'Open', 'CLOSED', 'Closed', " +
        "    'RENOVATING', 'Temporarily Closed', 'Under Review') AS status_label, " +
        "  NVL(e.first_name || ' ' || e.last_name, 'Unassigned') AS manager_name " +
        "FROM MERIDIAN.BRANCHES b " +
        "LEFT JOIN MERIDIAN.EMPLOYEES e ON b.manager_id = e.employee_id " +
        "ORDER BY b.region, b.branch_name",
        nativeQuery = true)
    List<Object[]> findAllBranchesWithManager();

    // ORACLE-DEP [EMBEDDED_SQL:ORACLE_SPATIAL] SDO_GEOM.SDO_DISTANCE for distance calculation
    @OracleDependency(
        domain = DependencyDomain.EMBEDDED_SQL,
        category = "ORACLE_SPATIAL",
        description = "SDO_GEOM.SDO_DISTANCE calculates precise geodetic distance between two branch locations",
        complexity = MigrationComplexity.VERY_HIGH,
        automationLevel = AutomationLevel.MANUAL,
        migrationTarget = "Replace with PostGIS ST_Distance(geom1::geography, geom2::geography) in meters"
    )
    @Query(value =
        "SELECT b1.branch_id AS branch1_id, b1.branch_name AS branch1_name, " +
        "  b2.branch_id AS branch2_id, b2.branch_name AS branch2_name, " +
        "  SDO_GEOM.SDO_DISTANCE(b1.location, b2.location, 0.5, 'unit=KM') AS distance_km " +
        "FROM MERIDIAN.BRANCHES b1, MERIDIAN.BRANCHES b2 " +
        "WHERE b1.branch_id = :branchId " +
        "AND b2.branch_id != :branchId " +
        "AND b1.status = 'OPEN' AND b2.status = 'OPEN' " +
        "ORDER BY distance_km ASC",
        nativeQuery = true)
    List<Object[]> findDistancesToBranch(@Param("branchId") Long branchId);

    // ORACLE-DEP [EMBEDDED_SQL:NVL_FUNCTION] NVL for branch region summary
    @OracleDependency(
        domain = DependencyDomain.EMBEDDED_SQL,
        category = "NVL_FUNCTION",
        description = "NVL(region,'UNASSIGNED') ensures null regions are counted in summary",
        complexity = MigrationComplexity.LOW,
        automationLevel = AutomationLevel.FULL,
        migrationTarget = "Replace NVL(region,'UNASSIGNED') with COALESCE(region,'UNASSIGNED') in PostgreSQL"
    )
    @Query(value =
        "SELECT NVL(region, 'UNASSIGNED') AS region_name, " +
        "  COUNT(*) AS total_branches, " +
        "  SUM(DECODE(status, 'OPEN', 1, 0)) AS active_count, " +
        "  SUM(DECODE(status, 'CLOSED', 1, 0)) AS inactive_count " +
        "FROM MERIDIAN.BRANCHES " +
        "GROUP BY NVL(region, 'UNASSIGNED') " +
        "ORDER BY total_branches DESC",
        nativeQuery = true)
    List<Object[]> findBranchCountByRegion();
}
