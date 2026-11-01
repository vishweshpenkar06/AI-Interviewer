package com.interviewer.controller;

import com.interviewer.dto.AssignmentResponse;
import com.interviewer.dto.CreateAssignmentRequest;
import com.interviewer.dto.StudentAssignmentResponse;
import com.interviewer.security.CurrentUser;
import com.interviewer.service.AssignmentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class AssignmentController {

    private final AssignmentService assignmentService;
    private final CurrentUser currentUser;

    @PostMapping("/orgs/{orgId}/assignments")
    @PreAuthorize("@orgAuth.isTpo(authentication.name, #orgId)")
    public ResponseEntity<AssignmentResponse> create(@PathVariable Long orgId,
                                                     @Valid @RequestBody CreateAssignmentRequest request) {
        return ResponseEntity.ok(assignmentService.create(orgId, request));
    }

    @GetMapping("/orgs/{orgId}/assignments")
    @PreAuthorize("@orgAuth.isTpo(authentication.name, #orgId)")
    public ResponseEntity<List<AssignmentResponse>> list(@PathVariable Long orgId) {
        return ResponseEntity.ok(assignmentService.list(orgId));
    }

    @GetMapping("/me/assignments")
    public ResponseEntity<List<StudentAssignmentResponse>> mine() {
        return ResponseEntity.ok(assignmentService.myAssignments(currentUser.id()));
    }

    @PostMapping("/orgs/{orgId}/assignments/{assignmentId}/start")
    @PreAuthorize("@orgAuth.isMember(authentication.name, #orgId)")
    public ResponseEntity<StudentAssignmentResponse> start(@PathVariable Long orgId,
                                                           @PathVariable Long assignmentId) {
        return ResponseEntity.ok(assignmentService.start(orgId, assignmentId, currentUser.id()));
    }
}
