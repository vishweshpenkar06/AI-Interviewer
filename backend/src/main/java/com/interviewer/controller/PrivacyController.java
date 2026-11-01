package com.interviewer.controller;

import com.interviewer.dto.OrganizationResponse;
import com.interviewer.dto.UpdateConsentRequest;
import com.interviewer.security.CurrentUser;
import com.interviewer.service.OrganizationService;
import com.interviewer.service.PrivacyService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/me")
@RequiredArgsConstructor
public class PrivacyController {

    private final PrivacyService privacyService;
    private final OrganizationService organizationService;
    private final CurrentUser currentUser;

    @GetMapping("/organizations")
    public ResponseEntity<List<OrganizationResponse>> organizations() {
        return ResponseEntity.ok(organizationService.listForUser(currentUser.id()));
    }

    @GetMapping("/data")
    public ResponseEntity<Map<String, Object>> exportData() {
        return ResponseEntity.ok(privacyService.export(currentUser.id()));
    }

    @DeleteMapping("/data")
    public ResponseEntity<Map<String, Object>> deleteData() {
        return ResponseEntity.ok(privacyService.deleteMyData(currentUser.id()));
    }

    @GetMapping("/consent")
    public ResponseEntity<Map<String, Object>> consent() {
        return ResponseEntity.ok(privacyService.consent(currentUser.id()));
    }

    @PostMapping("/consent")
    public ResponseEntity<Map<String, Object>> updateConsent(@RequestBody UpdateConsentRequest request) {
        return ResponseEntity.ok(privacyService.updateConsent(currentUser.id(), request));
    }
}
