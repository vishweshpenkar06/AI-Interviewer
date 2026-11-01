package com.interviewer.repository;

import com.interviewer.entity.Membership;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface MembershipRepository extends JpaRepository<Membership, Long> {
    List<Membership> findByUserIdOrderByIdAsc(Long userId);
    List<Membership> findByOrganizationIdOrderByIdAsc(Long organizationId);
    Optional<Membership> findByOrganizationIdAndUserId(Long organizationId, Long userId);
    Optional<Membership> findFirstByUserIdOrderByIdAsc(Long userId);
    boolean existsByOrganizationIdAndUserId(Long organizationId, Long userId);
    long countByOrganizationIdAndRole(Long organizationId, Membership.Role role);
    void deleteByUserId(Long userId);
}
