package com.interviewer.controller;

import com.interviewer.dto.*;
import com.interviewer.service.CohortService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/api/orgs/{orgId}/cohorts")
@RequiredArgsConstructor
public class CohortController {

    private final CohortService cohortService;

    @PostMapping
    @PreAuthorize("@orgAuth.isTpo(authentication.name, #orgId)")
    public ResponseEntity<CohortResponse> create(@PathVariable Long orgId,
                                                 @Valid @RequestBody CreateCohortRequest request) {
        return ResponseEntity.ok(cohortService.create(orgId, request));
    }

    @GetMapping
    @PreAuthorize("@orgAuth.isTpo(authentication.name, #orgId)")
    public ResponseEntity<List<CohortResponse>> list(@PathVariable Long orgId) {
        return ResponseEntity.ok(cohortService.list(orgId));
    }

    @PostMapping("/{cohortId}/students/import")
    @PreAuthorize("@orgAuth.isTpo(authentication.name, #orgId)")
    public ResponseEntity<ImportStudentsResponse> importStudents(@PathVariable Long orgId,
                                                                 @PathVariable Long cohortId,
                                                                 @RequestParam("file") MultipartFile file) {
        return ResponseEntity.ok(cohortService.
                importStudents(orgId, cohortId, file));
    }

    @GetMapping("/{cohortId}/students")
    @PreAuthorize("@orgAuth.isTpo(authentication.name, #orgId)")
    public ResponseEntity<List<StudentResponse>> students(@PathVariable Long orgId,
                                                          @PathVariable Long cohortId) {
        return ResponseEntity.ok(cohortService.students(orgId, cohortId));
    }
}
