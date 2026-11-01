package com.interviewer.service;

import com.interviewer.dto.UpdateConsentRequest;
import com.interviewer.entity.*;
import com.interviewer.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class PrivacyService {

    private final UserRepository userRepository;
    private final MembershipRepository membershipRepository;
    private final InterviewRepository interviewRepository;
    private final ReportRepository reportRepository;
    private final ResumeRepository resumeRepository;
    private final JobDescriptionRepository jobDescriptionRepository;
    private final EnrollmentRepository enrollmentRepository;
    private final AssignmentAttemptRepository attemptRepository;
    private final PracticeItemRepository practiceItemRepository;
    private final RecordingStorage recordingStorage;
    private final TenantContext tenantContext;

    @Value("${app.upload.dir:./uploads}")
    private String uploadDir;

    @Transactional(readOnly = true)
    public Map<String, Object> export(Long userId) {
        User user = tenantContext.requireUser(userId);
        List<Interview> interviews = interviewRepository.findByCandidateIdOrderByCreatedAtDesc(userId);

        List<Map<String, Object>> interviewRows = new ArrayList<>();
        for (Interview interview : interviews) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", interview.getId());
            row.put("title", interview.getTitle());
            row.put("status", interview.getStatus().name());
            row.put("organizationId", interview.getOrganizationId());
            row.put("createdAt", interview.getCreatedAt());
            row.put("completedAt", interview.getCompletedAt());
            Report report = reportRepository.findByInterviewId(interview.getId()).orElse(null);
            if (report != null) {
                row.put("report", Map.of(
                        "overallScore", report.getOverallScore(),
                        "overallGrade", report.getOverallGrade() == null ? "" : report.getOverallGrade(),
                        "summary", report.getSummary() == null ? "" : report.getSummary(),
                        "recommendationLevel", report.getRecommendationLevel() == null
                                ? "" : report.getRecommendationLevel()));
            }
            row.put("answers", interview.getSessions().stream().map(session -> Map.of(
                    "question", session.getQuestion().getText(),
                    "answer", session.getCandidateAnswer() == null ? "" : session.getCandidateAnswer(),
                    "status", session.getStatus().name())).toList());
            interviewRows.add(row);
        }

        List<Map<String, Object>> memberships = membershipRepository.findByUserIdOrderByIdAsc(userId).stream()
                .map(m -> Map.<String, Object>of(
                        "organizationId", m.getOrganization().getId(),
                        "organization", m.getOrganization().getName(),
                        "role", m.getRole().name()))
                .toList();

        List<Map<String, Object>> resumes = resumeRepository.findByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(r -> Map.<String, Object>of(
                        "id", r.getId(),
                        "fileName", r.getFileName(),
                        "createdAt", r.getCreatedAt(),
                        "extractedText", r.getExtractedText() == null ? "" : r.getExtractedText()))
                .toList();

        Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("id", user.getId());
        profile.put("email", user.getEmail());
        profile.put("fullName", user.getFullName());
        profile.put("phone", user.getPhone() == null ? "" : user.getPhone());
        profile.put("profileSummary", user.getProfileSummary() == null ? "" : user.getProfileSummary());
        profile.put("linkedinUrl", user.getLinkedinUrl() == null ? "" : user.getLinkedinUrl());
        profile.put("githubUrl", user.getGithubUrl() == null ? "" : user.getGithubUrl());
        profile.put("consentRecording", Boolean.TRUE.equals(user.getConsentRecording()));
        profile.put("consentProctoring", Boolean.TRUE.equals(user.getConsentProctoring()));
        profile.put("dataRetentionDays", user.getDataRetentionDays());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("exportedAt", java.time.LocalDateTime.now());
        result.put("profile", profile);
        result.put("memberships", memberships);
        result.put("interviews", interviewRows);
        result.put("resumes", resumes);
        return result;
    }

    /** Removes interviews, recordings and resumes, then anonymises the profile. */
    @Transactional
    public Map<String, Object> deleteMyData(Long userId) {
        User user = tenantContext.requireUser(userId);
        List<Interview> interviews = interviewRepository.findByCandidateIdOrderByCreatedAtDesc(userId);

        attemptRepository.deleteByStudentId(userId);
        attemptRepository.flush();
        enrollmentRepository.deleteByStudentId(userId);
        enrollmentRepository.flush();
        practiceItemRepository.deleteByUser_Id(userId);
        practiceItemRepository.flush();

        int recordingsDeleted = 0;
        for (Interview interview : interviews) {
            recordingStorage.deleteForInterview(interview.getId());
            recordingsDeleted++;
        }
        interviewRepository.deleteAll(interviews);

        List<Resume> resumes = resumeRepository.findByUserIdOrderByCreatedAtDesc(userId);
        for (Resume resume : resumes) {
            deleteUploadedFile(resume.getFilePath());
        }
        resumeRepository.deleteAll(resumes);

        List<JobDescription> jobDescriptions =
                jobDescriptionRepository.findByCreatedByIdOrderByCreatedAtDesc(userId);
        jobDescriptionRepository.deleteAll(jobDescriptions);

        user.setFullName("Deleted User");
        user.setPhone(null);
        user.setProfileSummary(null);
        user.setLinkedinUrl(null);
        user.setGithubUrl(null);
        user.setConsentRecording(false);
        user.setConsentProctoring(false);
        userRepository.save(user);

        return Map.of(
                "deletedInterviews", interviews.size(),
                "deletedResumes", resumes.size(),
                "deletedJobDescriptions", jobDescriptions.size(),
                "recordingSetsPurged", recordingsDeleted,
                "anonymizedProfile", true);
    }

    @Transactional
    public Map<String, Object> updateConsent(Long userId, UpdateConsentRequest request) {
        User user = tenantContext.requireUser(userId);
        if (request.getConsentRecording() != null) {
            user.setConsentRecording(request.getConsentRecording());
            if (!request.getConsentRecording()) {
                // Withdrawing consent also deletes recordings already captured.
                for (Interview interview : interviewRepository.findByCandidateIdOrderByCreatedAtDesc(userId)) {
                    recordingStorage.deleteForInterview(interview.getId());
                }
            }
        }
        if (request.getConsentProctoring() != null) {
            user.setConsentProctoring(request.getConsentProctoring());
        }
        if (request.getDataRetentionDays() != null) {
            int days = request.getDataRetentionDays();
            if (days < 1 || days > 365) {
                throw new IllegalArgumentException("dataRetentionDays must be 1-365");
            }
            user.setDataRetentionDays(days);
        }
        userRepository.save(user);
        return Map.of(
                "consentRecording", Boolean.TRUE.equals(user.getConsentRecording()),
                "consentProctoring", Boolean.TRUE.equals(user.getConsentProctoring()),
                "dataRetentionDays", user.getDataRetentionDays());
    }

    @Transactional(readOnly = true)
    public Map<String, Object> consent(Long userId) {
        User user = tenantContext.requireUser(userId);
        return Map.of(
                "consentRecording", Boolean.TRUE.equals(user.getConsentRecording()),
                "consentProctoring", Boolean.TRUE.equals(user.getConsentProctoring()),
                "dataRetentionDays", user.getDataRetentionDays());
    }

    private void deleteUploadedFile(String filePath) {
        if (filePath == null || filePath.isBlank()) {
            return;
        }
        try {
            Path root = Paths.get(uploadDir).toAbsolutePath().normalize();
            Path file = Paths.get(filePath).toAbsolutePath().normalize();
            // Never delete outside the configured upload directory.
            if (file.startsWith(root) && Files.isRegularFile(file)) {
                Files.deleteIfExists(file);
            }
        } catch (Exception e) {
            log.warn("Could not delete resume file {}: {}", filePath, e.getMessage());
        }
    }
}
