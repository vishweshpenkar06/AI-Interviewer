package com.interviewer.repository;

import com.interviewer.entity.AssignmentAttempt;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AssignmentAttemptRepository extends JpaRepository<AssignmentAttempt, Long> {
    List<AssignmentAttempt> findByAssignmentIdOrderByIdAsc(Long assignmentId);
    List<AssignmentAttempt> findByStudentIdOrderByCreatedAtDesc(Long studentId);
    List<AssignmentAttempt> findByAssignmentIdAndStudentIdOrderByCreatedAtDesc(Long assignmentId, Long studentId);
    long countByAssignmentIdAndStudentId(Long assignmentId, Long studentId);
    List<AssignmentAttempt> findByAssignmentCohortOrganizationId(Long organizationId);
    Optional<AssignmentAttempt> findByInterviewId(Long interviewId);
    void deleteByStudentId(Long studentId);
}
