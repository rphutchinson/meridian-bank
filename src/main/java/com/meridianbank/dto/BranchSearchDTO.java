package com.meridianbank.dto;

import java.util.List;

/**
 * DTO for branch search requests and results.
 * Nearest-branch search uses Oracle Spatial SDO_WITHIN_DISTANCE / SDO_NN.
 */
public class BranchSearchDTO {

    // Search parameters
    private Double latitude;
    private Double longitude;
    private Double radiusKm;

    // Single branch result
    private Long branchId;
    private String branchCode;
    private String branchName;
    private String region;
    private String phone;
    private String status;

    private String street;
    private String city;
    private String state;
    private String zipCode;

    /** Distance in km, populated by Oracle Spatial query. */
    private Double distanceKm;

    // List result for search responses
    private List<BranchSearchDTO> nearbyBranches;
    private Integer totalCount;
    public BranchSearchDTO() {
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

    public Double getRadiusKm() {
        return radiusKm;
    }

    public void setRadiusKm(Double radiusKm) {
        this.radiusKm = radiusKm;
    }

    public Long getBranchId() {
        return branchId;
    }

    public void setBranchId(Long branchId) {
        this.branchId = branchId;
    }

    public String getBranchCode() {
        return branchCode;
    }

    public void setBranchCode(String branchCode) {
        this.branchCode = branchCode;
    }

    public String getBranchName() {
        return branchName;
    }

    public void setBranchName(String branchName) {
        this.branchName = branchName;
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

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
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

    public String getState() {
        return state;
    }

    public void setState(String state) {
        this.state = state;
    }

    public String getZipCode() {
        return zipCode;
    }

    public void setZipCode(String zipCode) {
        this.zipCode = zipCode;
    }

    public Double getDistanceKm() {
        return distanceKm;
    }

    public void setDistanceKm(Double distanceKm) {
        this.distanceKm = distanceKm;
    }

    public List<BranchSearchDTO> getNearbyBranches() {
        return nearbyBranches;
    }

    public void setNearbyBranches(List<BranchSearchDTO> nearbyBranches) {
        this.nearbyBranches = nearbyBranches;
    }

    public Integer getTotalCount() {
        return totalCount;
    }

    public void setTotalCount(Integer totalCount) {
        this.totalCount = totalCount;
    }

}
