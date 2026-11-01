package com.interviewer.service;

import com.interviewer.dto.JobDescriptionRequest;
import com.interviewer.dto.JobDescriptionResponse;
import com.interviewer.entity.JobDescription;
import com.interviewer.entity.User;
import com.interviewer.exception.ResourceNotFoundException;
import com.interviewer.repository.JobDescriptionRepository;
import com.interviewer.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class JobDescriptionService {

    private final JobDescriptionRepository jobDescriptionRepository;
    private final UserRepository userRepository;
    private final TenantContext tenantContext;

    @Transactional
    public JobDescriptionResponse create(Long userId, JobDescriptionRequest request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        JobDescription.ExperienceLevel level;
        try {
            level = JobDescription.ExperienceLevel.valueOf(
                    request.getExperienceLevel() != null ? request.getExperienceLevel().toUpperCase() : "MID");
        } catch (IllegalArgumentException e) {
            level = JobDescription.ExperienceLevel.MID;
        }

        JobDescription jd = JobDescription.builder()
                .createdBy(user)
                .organizationId(tenantContext.currentOrganizationId())
                .title(request.getTitle())
                .company(request.getCompany())
                .description(request.getDescription())
                .experienceLevel(level)
                .location(request.getLocation())
                .employmentType(request.getEmploymentType())
                .active(true)
                .build();

        jd = jobDescriptionRepository.save(jd);
        return JobDescriptionResponse.from(jd);
    }

    public List<JobDescriptionResponse> getUserJobDescriptions(Long userId) {
        return jobDescriptionRepository.findByCreatedByIdOrderByCreatedAtDesc(userId).stream()
                .map(JobDescriptionResponse::from)
                .toList();
    }

    public JobDescriptionResponse getJobDescription(Long id, Long userId) {
        return JobDescriptionResponse.from(requireOwned(id, userId));
    }

    @Transactional
    public void deleteJobDescription(Long id, Long userId) {
        jobDescriptionRepository.delete(requireOwned(id, userId));
    }

    private JobDescription requireOwned(Long id, Long userId) {
        JobDescription jd = jobDescriptionRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Job description not found"));
        if (jd.getCreatedBy() == null || !jd.getCreatedBy().getId().equals(userId)) {
            throw new ResourceNotFoundException("Job description not found");
        }
        return jd;
    }
}
