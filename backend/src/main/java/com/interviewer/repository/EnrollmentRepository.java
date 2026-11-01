package com.interviewer.repository;

import com.interviewer.entity.Enrollment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface EnrollmentRepository extends JpaRepository<Enrollment, Long> {
    List<Enrollment> findByCohortIdOrderByCreatedAtAsc(Long cohortId);
    List<Enrollment> findByStudentId(Long studentId);
    Optional<Enrollment> findByCohortIdAndStudentId(Long cohortId, Long studentId);
    boolean existsByCohortIdAndStudentId(Long cohortId, Long studentId);
    long countByCohortId(Long cohortId);
    void deleteByStudentId(Long studentId);
}
