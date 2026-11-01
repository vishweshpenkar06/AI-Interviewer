package com.interviewer.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.interviewer.dto.CreateOrganizationRequest;
import com.interviewer.dto.OrganizationResponse;
import com.interviewer.security.CurrentUser;
import com.interviewer.service.AnalyticsService;
import com.interviewer.service.CompanyTrackService;
import com.interviewer.service.OrganizationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/orgs")
@RequiredArgsConstructor
public class OrganizationController {

    private final OrganizationService organizationService;
    private final AnalyticsService analyticsService;
    private final CompanyTrackService companyTrackService;
    private final CurrentUser currentUser;
    private final ObjectMapper objectMapper;

    @PostMapping
    public ResponseEntity<OrganizationResponse> create(@Valid @RequestBody CreateOrganizationRequest request) {
        return ResponseEntity.ok(organizationService.create(currentUser.id(), request));
    }

    @GetMapping("/{orgId}")
    @PreAuthorize("@orgAuth.isMember(authentication.name, #orgId)")
    public ResponseEntity<OrganizationResponse> get(@PathVariable Long orgId) {
        return ResponseEntity.ok(organizationService.get(orgId));
    }

    @GetMapping("/{orgId}/tracks")
    @PreAuthorize("@orgAuth.isMember(authentication.name, #orgId)")
    public ResponseEntity<List<Map<String, Object>>> tracks(@PathVariable Long orgId) {
        List<Map<String, Object>> tracks = new ArrayList<>();
        for (var track : companyTrackService.availableTracks(orgId)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", track.getId());
            row.put("name", track.getName());
            row.put("slug", track.getSlug());
            row.put("organizationId", track.getOrganization() != null
                    ? track.getOrganization().getId() : null);
            try {
                JsonNode config = objectMapper.readTree(track.getConfigJson());
                row.put("config", config);
            } catch (Exception e) {
                row.put("config", Map.of());
            }
            tracks.add(row);
        }
        return ResponseEntity.ok(tracks);
    }

    @GetMapping("/{orgId}/analytics")
    @PreAuthorize("@orgAuth.isTpo(authentication.name, #orgId)")
    public ResponseEntity<Map<String, Object>> analytics(@PathVariable Long orgId) {
        return ResponseEntity.ok(analyticsService.forOrganization(orgId));
    }

    @GetMapping("/{orgId}/usage")
    @PreAuthorize("@orgAuth.isMember(authentication.name, #orgId)")
    public ResponseEntity<Map<String, Object>> usage(@PathVariable Long orgId) {
        return ResponseEntity.ok(organizationService.usage(orgId));
    }

    @GetMapping("/{orgId}/reports/export")
    @PreAuthorize("@orgAuth.isTpo(authentication.name, #orgId)")
    public ResponseEntity<String> exportReports(@PathVariable Long orgId) {
        String csv = analyticsService.reportsCsv(orgId);
        return ResponseEntity.ok()
                .header("Content-Type", "text/csv; charset=utf-8")
                .header("Content-Disposition", "attachment; filename=placementos-reports.csv")
                .body(csv);
    }
}
