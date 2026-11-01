package com.interviewer.repository;

import com.interviewer.entity.Assignment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AssignmentRepository extends JpaRepository<Assignment, Long> {
    List<Assignment> findByCohortOrganizationIdOrderByCreatedAtDesc(Long organizationId);
    List<Assignment> findByCohortIdOrderByCreatedAtDesc(Long cohortId);
    Optional<Assignment> findByIdAndCohortOrganizationId(Long id, Long organizationId);
}
