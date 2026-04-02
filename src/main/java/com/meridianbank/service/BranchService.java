package com.meridianbank.service;

import com.meridianbank.annotation.AutomationLevel;
import com.meridianbank.annotation.DependencyDomain;
import com.meridianbank.annotation.MigrationComplexity;
import com.meridianbank.annotation.OracleDependency;
import com.meridianbank.dto.BranchSearchDTO;
import com.meridianbank.entity.BranchEntity;
import com.meridianbank.exception.MeridianBankException;
import com.meridianbank.exception.OracleErrorTranslator;
import com.meridianbank.repository.BranchRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Service layer for Branch operations.
 *
 * Primarily uses BranchRepository (Spring Data JPA) with Oracle-specific native queries.
 * The key Oracle dependencies in this service are Oracle Spatial functions used for
 * geographic branch search, plus NVL/DECODE used in standard display queries.
 *
 * ORACLE-DEP [EMBEDDED_SQL:ORACLE_SPATIAL]     SDO_WITHIN_DISTANCE for radius search
 * ORACLE-DEP [EMBEDDED_SQL:ORACLE_SPATIAL]     SDO_NN for nearest-N branch lookup
 * ORACLE-DEP [EMBEDDED_SQL:ORACLE_SPATIAL]     SDO_GEOM.SDO_DISTANCE for distance calculation
 * ORACLE-DEP [EMBEDDED_SQL:NVL_FUNCTION]       NVL used in manager display queries
 * ORACLE-DEP [EMBEDDED_SQL:DECODE_FUNCTION]    DECODE used for region/status display mapping
 * ORACLE-DEP [EMBEDDED_SQL:SYSDATE]            Branch list query references SYSDATE for active check
 */
@Service
@Transactional(readOnly = true)
public class BranchService {

    private static final Logger log = LoggerFactory.getLogger(BranchService.class);

    @Autowired
    private BranchRepository branchRepository;

    @Autowired
    private OracleErrorTranslator errorTranslator;

    // -----------------------------------------------------------------------
    // Basic retrieval
    // -----------------------------------------------------------------------

    /**
     * Returns all branches with manager name and display-formatted status.
     *
     * The repository native query uses:
     *  - NVL(e.first_name || ' ' || e.last_name, 'Unassigned') for manager name
     *  - DECODE(b.status,'ACTIVE','Open','INACTIVE','Closed','Renovating') for status display
     *  - SYSDATE in a WHERE clause to filter by open hours (b.close_date IS NULL OR b.close_date > SYSDATE)
     *
     * Returns Object[] rows: [branch_id, branch_code, branch_name, region, phone, status_display, manager_name]
     */
    @OracleDependency(
        domain = DependencyDomain.EMBEDDED_SQL,
        category = "NVL_FUNCTION",
        description = "Branch list uses NVL(e.first_name||' '||e.last_name,'Unassigned') to default null manager to 'Unassigned'",
        complexity = MigrationComplexity.LOW,
        automationLevel = AutomationLevel.FULL,
        migrationTarget = "Replace NVL(expr,'default') with COALESCE(expr,'default') in PostgreSQL"
    )
    @OracleDependency(
        domain = DependencyDomain.EMBEDDED_SQL,
        category = "DECODE_FUNCTION",
        description = "Branch status display uses DECODE(b.status,'ACTIVE','Open','INACTIVE','Closed','Renovating')",
        complexity = MigrationComplexity.LOW,
        automationLevel = AutomationLevel.FULL,
        migrationTarget = "Replace DECODE with CASE WHEN b.status='ACTIVE' THEN 'Open' WHEN ... END in PostgreSQL"
    )
    @OracleDependency(
        domain = DependencyDomain.EMBEDDED_SQL,
        category = "SYSDATE",
        description = "Branch list filters by close_date IS NULL OR close_date > SYSDATE to show only currently active branches",
        complexity = MigrationComplexity.LOW,
        automationLevel = AutomationLevel.FULL,
        migrationTarget = "Replace SYSDATE with CURRENT_DATE or NOW() in PostgreSQL"
    )
    public List<BranchSearchDTO> getAllBranches() {
        // ORACLE-DEP [EMBEDDED_SQL:NVL_FUNCTION] Repository native: NVL(manager_name,'Unassigned')
        // ORACLE-DEP [EMBEDDED_SQL:DECODE_FUNCTION] Repository native: DECODE(b.status,...)
        // ORACLE-DEP [EMBEDDED_SQL:SYSDATE] Repository native: close_date > SYSDATE
        List<Object[]> rows = branchRepository.findAllBranchesWithManager();
        List<BranchSearchDTO> results = new ArrayList<>();
        for (Object[] row : rows) {
            BranchSearchDTO dto = mapRowToBranchDTO(row);
            results.add(dto);
        }
        log.debug("getAllBranches returned {} branches", results.size());
        return results;
    }

    /**
     * Returns a branch by primary key.
     */
    public BranchSearchDTO getBranchById(Long branchId) {
        BranchEntity entity = branchRepository.findById(branchId)
            .orElseThrow(() -> new MeridianBankException("NOT_FOUND",
                "Branch not found with ID: " + branchId, 404));
        return entityToDto(entity);
    }

    /**
     * Returns a branch by its unique branch code.
     */
    public BranchSearchDTO getBranchByCode(String branchCode) {
        BranchEntity entity = branchRepository.findByBranchCode(branchCode)
            .orElseThrow(() -> new MeridianBankException("NOT_FOUND",
                "Branch not found with code: " + branchCode, 404));
        return entityToDto(entity);
    }

    /**
     * Returns all branches in the given region.
     */
    public List<BranchSearchDTO> getBranchesByRegion(String region) {
        if (region == null || region.isBlank()) {
            throw new MeridianBankException("VALIDATION_ERROR",
                "Region is required.", 400);
        }
        List<BranchEntity> entities = branchRepository.findByRegion(region.trim().toUpperCase());
        List<BranchSearchDTO> results = new ArrayList<>();
        for (BranchEntity e : entities) {
            results.add(entityToDto(e));
        }
        return results;
    }

    // -----------------------------------------------------------------------
    // Oracle Spatial search methods
    // -----------------------------------------------------------------------

    /**
     * Finds all branches within a given radius of a geographic point.
     *
     * The repository native query uses Oracle Spatial SDO_WITHIN_DISTANCE:
     *   WHERE SDO_WITHIN_DISTANCE(
     *           b.location,
     *           SDO_GEOMETRY(2001, 8307, SDO_POINT_TYPE(:lng, :lat, NULL), NULL, NULL),
     *           'distance=' || :radiusKm || ' unit=KM'
     *         ) = 'TRUE'
     *
     * SDO_WITHIN_DISTANCE leverages the spatial index (MDSYS.SPATIAL_INDEX_V2) on the
     * location column — Oracle Spatial-proprietary index type with no direct PostgreSQL
     * equivalent (must use PostGIS GiST index on geometry column).
     *
     * Returns Object[] rows: [branch_id, branch_code, branch_name, region, phone, status,
     *                         street, city, state, zip_code, distance_km]
     */
    @OracleDependency(
        domain = DependencyDomain.EMBEDDED_SQL,
        category = "ORACLE_SPATIAL_SDO_WITHIN_DISTANCE",
        description = "Radius branch search uses Oracle Spatial SDO_WITHIN_DISTANCE(b.location, SDO_GEOMETRY(...), 'distance=N unit=KM')",
        complexity = MigrationComplexity.VERY_HIGH,
        automationLevel = AutomationLevel.PARTIAL,
        migrationTarget = "Replace with PostGIS ST_DWithin(b.location, ST_SetSRID(ST_MakePoint(:lng,:lat),4326), :radiusMetres) on a geography column"
    )
    @OracleDependency(
        domain = DependencyDomain.EMBEDDED_SQL,
        category = "ORACLE_SPATIAL_SDO_GEOMETRY",
        description = "Point parameter constructed using Oracle SDO_GEOMETRY(2001,8307,SDO_POINT_TYPE(:lng,:lat,NULL),NULL,NULL)",
        complexity = MigrationComplexity.HIGH,
        automationLevel = AutomationLevel.PARTIAL,
        migrationTarget = "Replace SDO_GEOMETRY point construction with ST_SetSRID(ST_MakePoint(:lng,:lat),4326) in PostGIS"
    )
    public List<BranchSearchDTO> findBranchesWithinRadius(double lat, double lng,
                                                           double radiusKm) {
        validateCoordinates(lat, lng);
        if (radiusKm <= 0 || radiusKm > 500) {
            throw new MeridianBankException("VALIDATION_ERROR",
                "Radius must be between 0.1 and 500 km.", 400);
        }

        log.info("Spatial search: lat={}, lng={}, radius={}km", lat, lng, radiusKm);

        // ORACLE-DEP [EMBEDDED_SQL:ORACLE_SPATIAL_SDO_WITHIN_DISTANCE] Repository native query
        // uses SDO_WITHIN_DISTANCE Oracle Spatial operator
        List<Object[]> rows = branchRepository.findBranchesWithinRadius(lat, lng, radiusKm);
        List<BranchSearchDTO> results = new ArrayList<>();
        for (Object[] row : rows) {
            BranchSearchDTO dto = mapSpatialRowToBranchDTO(row);
            results.add(dto);
        }
        log.debug("findBranchesWithinRadius: found {} branches within {}km", results.size(), radiusKm);
        return results;
    }

    /**
     * Finds the nearest N branches to a geographic point using Oracle SDO_NN.
     *
     * SDO_NN (spatial nearest-neighbour) is the most efficient Oracle Spatial operator
     * for finding the closest objects. Unlike SDO_WITHIN_DISTANCE, it returns exactly
     * the N nearest results regardless of distance.
     *
     * The repository native query uses:
     *   WHERE SDO_NN(
     *           b.location,
     *           SDO_GEOMETRY(2001, 8307, SDO_POINT_TYPE(:lng, :lat, NULL), NULL, NULL),
     *           'sdo_num_res=' || :maxResults || ' unit=KM'
     *         ) = 'TRUE'
     *   AND SDO_NN_DISTANCE(1) IS NOT NULL
     *   ORDER BY SDO_NN_DISTANCE(1)
     *
     * SDO_NN_DISTANCE(1) is an Oracle Spatial ancillary operator that returns the
     * actual distance computed during the SDO_NN evaluation — there is no equivalent
     * in standard SQL or PostGIS (must use ST_Distance separately).
     *
     * Returns Object[] rows: [branch_id, branch_code, branch_name, region, phone, status,
     *                         street, city, state, zip_code, distance_km]
     */
    @OracleDependency(
        domain = DependencyDomain.EMBEDDED_SQL,
        category = "ORACLE_SPATIAL_SDO_NN",
        description = "Nearest-N branch search uses Oracle Spatial SDO_NN operator with SDO_NN_DISTANCE ancillary",
        complexity = MigrationComplexity.VERY_HIGH,
        automationLevel = AutomationLevel.PARTIAL,
        migrationTarget = "Replace with PostGIS: ORDER BY b.location <-> ST_SetSRID(ST_MakePoint(:lng,:lat),4326)::geography LIMIT :maxResults"
    )
    @OracleDependency(
        domain = DependencyDomain.EMBEDDED_SQL,
        category = "ORACLE_SPATIAL_SDO_NN_DISTANCE",
        description = "SDO_NN_DISTANCE(1) ancillary operator returns computed distance from SDO_NN — Oracle-only",
        complexity = MigrationComplexity.HIGH,
        automationLevel = AutomationLevel.PARTIAL,
        migrationTarget = "Replace with ST_Distance(b.location::geography, ST_SetSRID(ST_MakePoint(:lng,:lat),4326)::geography)/1000 in PostGIS"
    )
    public List<BranchSearchDTO> findNearestBranches(double lat, double lng,
                                                      int maxResults) {
        validateCoordinates(lat, lng);
        if (maxResults <= 0 || maxResults > 50) maxResults = 5;

        log.info("SDO_NN search: lat={}, lng={}, maxResults={}", lat, lng, maxResults);

        // ORACLE-DEP [EMBEDDED_SQL:ORACLE_SPATIAL_SDO_NN] Repository native query uses
        // SDO_NN Oracle Spatial operator with SDO_NN_DISTANCE ancillary
        List<Object[]> rows = branchRepository.findNearestBranches(lat, lng, maxResults);
        List<BranchSearchDTO> results = new ArrayList<>();
        for (Object[] row : rows) {
            BranchSearchDTO dto = mapSpatialRowToBranchDTO(row);
            results.add(dto);
        }
        log.debug("findNearestBranches: found {} branches", results.size());
        return results;
    }

    // -----------------------------------------------------------------------
    // Aggregation and reporting
    // -----------------------------------------------------------------------

    /**
     * Returns branch count grouped by region with Oracle NVL and DECODE formatting.
     *
     * The repository native query uses:
     *  - NVL(b.region, 'UNKNOWN') to handle null region values
     *  - DECODE(COUNT(*), 0, 'No branches', 1, '1 branch', COUNT(*)||' branches') for display
     *
     * Returns a list of maps: [{region: "NORTHEAST", count: 5, display: "5 branches"}, ...]
     */
    @OracleDependency(
        domain = DependencyDomain.EMBEDDED_SQL,
        category = "NVL_FUNCTION",
        description = "Region count query uses NVL(b.region,'UNKNOWN') to group null regions together",
        complexity = MigrationComplexity.LOW,
        automationLevel = AutomationLevel.FULL,
        migrationTarget = "Replace NVL(b.region,'UNKNOWN') with COALESCE(b.region,'UNKNOWN') in PostgreSQL"
    )
    @OracleDependency(
        domain = DependencyDomain.EMBEDDED_SQL,
        category = "DECODE_FUNCTION",
        description = "Branch count display uses DECODE(COUNT(*),0,'No branches',1,'1 branch',COUNT(*)||' branches')",
        complexity = MigrationComplexity.LOW,
        automationLevel = AutomationLevel.FULL,
        migrationTarget = "Replace DECODE with CASE WHEN COUNT(*)=0 THEN 'No branches' ... END in PostgreSQL"
    )
    public List<Map<String, Object>> getBranchCountByRegion() {
        // ORACLE-DEP [EMBEDDED_SQL:NVL_FUNCTION] Repository native: NVL(b.region,'UNKNOWN')
        // ORACLE-DEP [EMBEDDED_SQL:DECODE_FUNCTION] Repository native: DECODE(COUNT(*),...)
        List<Object[]> rows = branchRepository.findBranchCountByRegion();
        List<Map<String, Object>> results = new ArrayList<>();
        for (Object[] row : rows) {
            Map<String, Object> entry = new HashMap<>();
            entry.put("region", row[0] != null ? row[0].toString() : "UNKNOWN");
            entry.put("branchCount", row[1] != null ? ((Number) row[1]).intValue() : 0);
            // row[2] = DECODE result: "N branches" display string
            if (row.length > 2) {
                entry.put("displayLabel", row[2] != null ? row[2].toString() : "0 branches");
            }
            results.add(entry);
        }
        return results;
    }

    /**
     * Returns distances from a reference branch to all other branches.
     *
     * The repository native query uses Oracle Spatial SDO_GEOM.SDO_DISTANCE:
     *   SELECT b.branch_id, b.branch_name,
     *          SDO_GEOM.SDO_DISTANCE(ref.location, b.location, 0.005, 'unit=KM') AS dist_km
     *   FROM branches b, branches ref
     *   WHERE ref.branch_id = :branchId
     *   AND b.branch_id != :branchId
     *   ORDER BY dist_km
     *
     * SDO_GEOM.SDO_DISTANCE is Oracle Spatial's point-to-point distance function.
     * The 0.005 parameter is the tolerance in degrees. The 'unit=KM' is Oracle-specific
     * unit specification syntax.
     *
     * Returns Object[] rows: [branch_id, branch_name, distance_km]
     */
    @OracleDependency(
        domain = DependencyDomain.EMBEDDED_SQL,
        category = "ORACLE_SPATIAL_SDO_DISTANCE",
        description = "Branch distance matrix uses Oracle Spatial SDO_GEOM.SDO_DISTANCE(loc1, loc2, 0.005, 'unit=KM')",
        complexity = MigrationComplexity.VERY_HIGH,
        automationLevel = AutomationLevel.PARTIAL,
        migrationTarget = "Replace with PostGIS ST_Distance(loc1::geography, loc2::geography)/1000 for distance in km"
    )
    public List<BranchSearchDTO> getBranchDistances(Long branchId) {
        branchRepository.findById(branchId)
            .orElseThrow(() -> new MeridianBankException("NOT_FOUND",
                "Reference branch not found with ID: " + branchId, 404));

        // ORACLE-DEP [EMBEDDED_SQL:ORACLE_SPATIAL_SDO_DISTANCE] Repository native query uses
        // SDO_GEOM.SDO_DISTANCE with Oracle tolerance parameter and unit=KM specifier
        List<Object[]> rows = branchRepository.findDistancesToBranch(branchId);
        List<BranchSearchDTO> results = new ArrayList<>();
        for (Object[] row : rows) {
            BranchSearchDTO dto = new BranchSearchDTO();
            if (row[0] != null) dto.setBranchId(((Number) row[0]).longValue());
            if (row[1] != null) dto.setBranchName(row[1].toString());
            // row[2] = SDO_GEOM.SDO_DISTANCE result in km
            if (row.length > 2 && row[2] != null) {
                dto.setDistanceKm(((Number) row[2]).doubleValue());
            }
            results.add(dto);
        }
        return results;
    }

    /**
     * Returns branches with status 'ACTIVE' using standard JPA query.
     */
    public List<BranchSearchDTO> getActiveBranches() {
        List<BranchEntity> entities = branchRepository.findByStatus("ACTIVE");
        List<BranchSearchDTO> results = new ArrayList<>();
        for (BranchEntity e : entities) {
            results.add(entityToDto(e));
        }
        return results;
    }

    // -----------------------------------------------------------------------
    // Private helpers
    // -----------------------------------------------------------------------

    /**
     * Maps a branch entity to BranchSearchDTO.
     */
    private BranchSearchDTO entityToDto(BranchEntity entity) {
        BranchSearchDTO dto = new BranchSearchDTO();
        dto.setBranchId(entity.getBranchId());
        dto.setBranchCode(entity.getBranchCode());
        dto.setBranchName(entity.getBranchName());
        dto.setRegion(entity.getRegion());
        dto.setPhone(entity.getPhone());
        dto.setStatus(entity.getStatus());
        dto.setLatitude(entity.getLatitude());
        dto.setLongitude(entity.getLongitude());
        return dto;
    }

    /**
     * Maps a standard branch Object[] row (from findAllBranchesWithManager) to BranchSearchDTO.
     * Row layout: [branch_id, branch_code, branch_name, region, phone, status_display, manager_name]
     */
    private BranchSearchDTO mapRowToBranchDTO(Object[] row) {
        BranchSearchDTO dto = new BranchSearchDTO();
        if (row[0] != null) dto.setBranchId(((Number) row[0]).longValue());
        if (row[1] != null) dto.setBranchCode(row[1].toString());
        if (row[2] != null) dto.setBranchName(row[2].toString());
        if (row[3] != null) dto.setRegion(row[3].toString());
        if (row[4] != null) dto.setPhone(row[4].toString());
        // row[5] = DECODE status display ("Open", "Closed", "Renovating")
        if (row.length > 5 && row[5] != null) dto.setStatus(row[5].toString());
        // row[6] = NVL manager name ("First Last" or "Unassigned")
        // Stored in street field for convenience — real app would use a richer DTO
        if (row.length > 6 && row[6] != null) dto.setStreet(row[6].toString());
        return dto;
    }

    /**
     * Maps a spatial query Object[] row to BranchSearchDTO.
     * Row layout: [branch_id, branch_code, branch_name, region, phone, status,
     *              street, city, state, zip_code, distance_km]
     */
    private BranchSearchDTO mapSpatialRowToBranchDTO(Object[] row) {
        BranchSearchDTO dto = new BranchSearchDTO();
        if (row[0] != null) dto.setBranchId(((Number) row[0]).longValue());
        if (row[1] != null) dto.setBranchCode(row[1].toString());
        if (row[2] != null) dto.setBranchName(row[2].toString());
        if (row[3] != null) dto.setRegion(row[3].toString());
        if (row[4] != null) dto.setPhone(row[4].toString());
        if (row[5] != null) dto.setStatus(row[5].toString());
        if (row.length > 6 && row[6] != null) dto.setStreet(row[6].toString());
        if (row.length > 7 && row[7] != null) dto.setCity(row[7].toString());
        if (row.length > 8 && row[8] != null) dto.setState(row[8].toString());
        if (row.length > 9 && row[9] != null) dto.setZipCode(row[9].toString());
        // row[10] = SDO_GEOM distance result in km (SDO_NN_DISTANCE or SDO_GEOM.SDO_DISTANCE)
        if (row.length > 10 && row[10] != null) {
            dto.setDistanceKm(((Number) row[10]).doubleValue());
        }
        return dto;
    }

    private void validateCoordinates(double lat, double lng) {
        if (lat < -90 || lat > 90) {
            throw new MeridianBankException("VALIDATION_ERROR",
                "Latitude must be between -90 and 90 degrees.", 400);
        }
        if (lng < -180 || lng > 180) {
            throw new MeridianBankException("VALIDATION_ERROR",
                "Longitude must be between -180 and 180 degrees.", 400);
        }
    }
}
