package com.meridianbank.controller;

import com.meridianbank.dto.BranchSearchDTO;
import com.meridianbank.service.BranchService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * REST controller for Branch operations.
 *
 * <p>Thin HTTP adapter over BranchService. Oracle Spatial operations —
 * SDO_WITHIN_DISTANCE, SDO_NN (nearest-neighbour), SDO_GEOMETRY point
 * construction, and spatial index metadata — are encapsulated in
 * BranchRepository native queries and BranchService.
 *
 * <p>The /nearest endpoint is the primary Oracle Spatial showcase: it
 * calls BranchService.findBranchesWithinRadius() which issues a query
 * using SDO_WITHIN_DISTANCE(location, SDO_GEOMETRY(...), 'distance=N unit=KM')
 * against the spatial index on BRANCHES.LOCATION.
 */
@RestController
@RequestMapping("/api/branches")
@CrossOrigin(origins = "*")
public class BranchController {

    private static final Logger log = LoggerFactory.getLogger(BranchController.class);

    private final BranchService branchService;

    @Autowired
    public BranchController(BranchService branchService) {
        this.branchService = branchService;
    }

    // -------------------------------------------------------------------------
    // GET /api/branches
    // Returns all branches.
    // -------------------------------------------------------------------------
    @GetMapping
    public ResponseEntity<List<BranchSearchDTO>> getAllBranches() {
        log.debug("GET /api/branches");
        List<BranchSearchDTO> branches = branchService.getAllBranches();
        return ResponseEntity.ok(branches);
    }

    // -------------------------------------------------------------------------
    // GET /api/branches/nearest?lat=37.7749&lng=-122.4194&radius=10
    // Finds branches within a given radius (km) of a coordinate.
    // BranchService.findBranchesWithinRadius() calls a native query using
    // Oracle Spatial SDO_WITHIN_DISTANCE() with SDO_GEOMETRY point construction.
    // This is a §2.1 Oracle-specific SQL dependency and also a §2.9 Oracle
    // Spatial Java API dependency (oracle.spatial.*).
    // -------------------------------------------------------------------------
    @GetMapping("/nearest")
    public ResponseEntity<List<BranchSearchDTO>> findNearestBranches(
            @RequestParam double lat,
            @RequestParam double lng,
            @RequestParam(defaultValue = "25.0") double radius) {

        log.debug("GET /api/branches/nearest lat={} lng={} radius={}", lat, lng, radius);
        List<BranchSearchDTO> branches = branchService.findBranchesWithinRadius(lat, lng, radius);
        return ResponseEntity.ok(branches);
    }

    // -------------------------------------------------------------------------
    // GET /api/branches/{id}
    // Returns a single branch by primary key.
    // -------------------------------------------------------------------------
    @GetMapping("/{id}")
    public ResponseEntity<BranchSearchDTO> getBranchById(@PathVariable Long id) {
        log.debug("GET /api/branches/{}", id);
        BranchSearchDTO branch = branchService.getBranchById(id);
        return ResponseEntity.ok(branch);
    }

    // -------------------------------------------------------------------------
    // GET /api/branches/region/{region}
    // Returns all branches in a named region.
    // -------------------------------------------------------------------------
    @GetMapping("/region/{region}")
    public ResponseEntity<List<BranchSearchDTO>> getBranchesByRegion(
            @PathVariable String region) {

        log.debug("GET /api/branches/region/{}", region);
        List<BranchSearchDTO> branches = branchService.getBranchesByRegion(region);
        return ResponseEntity.ok(branches);
    }

    // -------------------------------------------------------------------------
    // GET /api/branches/active
    // Returns all branches with ACTIVE status.
    // -------------------------------------------------------------------------
    @GetMapping("/active")
    public ResponseEntity<List<BranchSearchDTO>> getActiveBranches() {
        log.debug("GET /api/branches/active");
        List<BranchSearchDTO> branches = branchService.getActiveBranches();
        return ResponseEntity.ok(branches);
    }

    // -------------------------------------------------------------------------
    // GET /api/branches/by-code/{branchCode}
    // Returns a branch by its unique branch code.
    // -------------------------------------------------------------------------
    @GetMapping("/by-code/{branchCode}")
    public ResponseEntity<BranchSearchDTO> getBranchByCode(
            @PathVariable String branchCode) {

        log.debug("GET /api/branches/by-code/{}", branchCode);
        BranchSearchDTO branch = branchService.getBranchByCode(branchCode);
        return ResponseEntity.ok(branch);
    }

    // -------------------------------------------------------------------------
    // GET /api/branches/count-by-region
    // Returns the count of branches grouped by region.
    // -------------------------------------------------------------------------
    @GetMapping("/count-by-region")
    public ResponseEntity<List<Map<String, Object>>> getBranchCountByRegion() {
        log.debug("GET /api/branches/count-by-region");
        List<Map<String, Object>> counts = branchService.getBranchCountByRegion();
        return ResponseEntity.ok(counts);
    }

    // -------------------------------------------------------------------------
    // GET /api/branches/{id}/distances
    // Returns other branches with their SDO_NN-computed distances from branch {id}.
    // Uses Oracle Spatial SDO_NN function (nearest-neighbour operator).
    // -------------------------------------------------------------------------
    @GetMapping("/{id}/distances")
    public ResponseEntity<List<BranchSearchDTO>> getBranchDistances(@PathVariable Long id) {
        log.debug("GET /api/branches/{}/distances", id);
        List<BranchSearchDTO> distances = branchService.getBranchDistances(id);
        return ResponseEntity.ok(distances);
    }
}
