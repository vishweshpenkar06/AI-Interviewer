package com.interviewer.repository;

import com.interviewer.entity.CompanyTrack;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface CompanyTrackRepository extends JpaRepository<CompanyTrack, Long> {
    List<CompanyTrack> findByOrganizationIdIsNullOrderByIdAsc();
    List<CompanyTrack> findByOrganizationIdOrderByIdAsc(Long organizationId);
    Optional<CompanyTrack> findBySlugAndOrganizationIdIsNull(String slug);
    Optional<CompanyTrack> findByNameAndOrganizationIdIsNull(String name);

    @Query("select t from CompanyTrack t where t.id = :id and (t.organization is null or t.organization.id = :orgId)")
    Optional<CompanyTrack> findAvailableToOrganization(@Param("id") Long id, @Param("orgId") Long orgId);

    @Query("select t from CompanyTrack t where t.organization is null or t.organization.id = :orgId order by t.id asc")
    List<CompanyTrack> findAllAvailableToOrganization(@Param("orgId") Long orgId);
}
