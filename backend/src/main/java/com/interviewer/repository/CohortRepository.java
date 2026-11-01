package com.interviewer.repository;

import com.interviewer.entity.Cohort;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CohortRepository extends JpaRepository<Cohort, Long> {
    List<Cohort> findByOrganizationIdOrderByIdDesc(Long organizationId);
    Optional<Cohort> findByIdAndOrganizationId(Long id, Long organizationId);
}
