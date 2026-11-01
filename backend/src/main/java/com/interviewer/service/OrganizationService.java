package com.interviewer.service;

import com.interviewer.dto.CreateOrganizationRequest;
import com.interviewer.dto.OrganizationResponse;
import com.interviewer.entity.Membership;
import com.interviewer.entity.Organization;
import com.interviewer.exception.ResourceNotFoundException;
import com.interviewer.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class OrganizationService {

    private final OrganizationRepository organizationRepository;
    private final MembershipRepository membershipRepository;
    private final CohortRepository cohortRepository;
    private final AssignmentRepository assignmentRepository;
    private final AssignmentAttemptRepository attemptRepository;
    private final InterviewRepository interviewRepository;
    private final ReportRepository reportRepository;
    private final TenantContext tenantContext;
    private final RecordingStorage recordingStorage;

    @Transactional
    public OrganizationResponse create(Long userId, CreateOrganizationRequest request) {
        String slug = slugify(request.getSlug() == null || request.getSlug().isBlank()
                ? request.getName() : request.getSlug());
        slug = uniqueSlug(slug);

        Organization.Plan plan = parsePlan(request.getPlan());
        Organization organization = organizationRepository.save(Organization.builder()
                .name(request.getName().trim())
                .slug(slug)
                .plan(plan)
                .build());

        // The creator is the tenant administrator, not merely a TPO.
        Membership membership = membershipRepository.save(Membership.builder()
                .organization(organization)
                .user(tenantContext.requireUser(userId))
                .role(Membership.Role.ADMIN)
                .build());

        return toResponse(organization, membership.getRole());
    }

    @Transactional(readOnly = true)
    public OrganizationResponse get(Long organizationId) {
        Membership membership = tenantContext.requireMembership(organizationId);
        return toResponse(membership.getOrganization(), membership.getRole());
    }

    @Transactional(readOnly = true)
    public List<OrganizationResponse> listForUser(Long userId) {
        return membershipRepository.findByUserIdOrderByIdAsc(userId).stream()
                .map(m -> toResponse(m.getOrganization(), m.getRole()))
                .toList();
    }

    @Transactional(readOnly = true)
    public Map<String, Object> usage(Long organizationId) {
        tenantContext.requireMembership(organizationId);
        long students = membershipRepository.countByOrganizationIdAndRole(organizationId, Membership.Role.STUDENT);
        long interviews = interviewRepository.countByOrganizationId(organizationId);
        long reports = reportRepository.countByOrganizationId(organizationId);
        long assignments = assignmentRepository.findByCohortOrganizationIdOrderByCreatedAtDesc(organizationId).size();
        long cohorts = cohortRepository.findByOrganizationIdOrderByIdDesc(organizationId).size();
        return Map.of(
                "organizationId", organizationId,
                "plan", organizationRepository.findById(organizationId)
                        .map(org -> org.getPlan().name()).orElse("PILOT"),
                "students", students,
                "cohorts", cohorts,
                "assignments", assignments,
                "interviews", interviews,
                "reports", reports,
                "storageBytes", recordingStorage.totalBytes(),
                "limits", Map.of(
                        "pilotStudents", 100,
                        "starterStudents", 500,
                        "growthStudents", -1));
    }

    private OrganizationResponse toResponse(Organization organization, Membership.Role role) {
        return OrganizationResponse.builder()
                .id(organization.getId())
                .name(organization.getName())
                .slug(organization.getSlug())
                .plan(organization.getPlan().name())
                .role(role.name())
                .createdAt(organization.getCreatedAt())
                .build();
    }

    private String uniqueSlug(String base) {
        String candidate = base;
        int suffix = 2;
        while (organizationRepository.existsBySlug(candidate)) {
            candidate = base + "-" + suffix++;
        }
        return candidate;
    }

    static String slugify(String value) {
        String slug = value == null ? "" : value.trim().toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-|-$", "");
        return slug.isBlank() ? "org" : slug;
    }

    private Organization.Plan parsePlan(String value) {
        if (value == null || value.isBlank()) {
            return Organization.Plan.PILOT;
        }
        try {
            return Organization.Plan.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("plan must be PILOT, STARTER or GROWTH");
        }
    }
}
