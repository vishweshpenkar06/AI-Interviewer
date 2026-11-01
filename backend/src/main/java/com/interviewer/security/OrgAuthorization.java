package com.interviewer.security;

import com.interviewer.entity.Membership;
import com.interviewer.repository.MembershipRepository;
import com.interviewer.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * SpEL target for {@code @PreAuthorize("@orgAuth.isTpo(authentication.name, #orgId)")}.
 * Membership, not the global user role, decides tenant permissions.
 */
@Component("orgAuth")
@RequiredArgsConstructor
public class OrgAuthorization {

    private static final List<Membership.Role> TPO_ROLES =
            List.of(Membership.Role.TPO, Membership.Role.ADMIN);

    private final MembershipRepository membershipRepository;
    private final UserRepository userRepository;

    public boolean isMember(String email, Long organizationId) {
        return membership(email, organizationId) != null;
    }

    public boolean isTpo(String email, Long organizationId) {
        Membership membership = membership(email, organizationId);
        return membership != null && TPO_ROLES.contains(membership.getRole());
    }

    public boolean isStudent(String email, Long organizationId) {
        Membership membership = membership(email, organizationId);
        return membership != null && membership.getRole() == Membership.Role.STUDENT;
    }

    private Membership membership(String email, Long organizationId) {
        if (email == null || organizationId == null) {
            return null;
        }
        return userRepository.findByEmail(email)
                .flatMap(user -> membershipRepository.findByOrganizationIdAndUserId(organizationId, user.getId()))
                .orElse(null);
    }
}
