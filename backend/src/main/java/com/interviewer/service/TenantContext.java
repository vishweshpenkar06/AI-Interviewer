package com.interviewer.service;

import com.interviewer.entity.Membership;
import com.interviewer.entity.User;
import com.interviewer.exception.ResourceNotFoundException;
import com.interviewer.repository.MembershipRepository;
import com.interviewer.repository.UserRepository;
import com.interviewer.security.CurrentUser;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Arrays;
import java.util.List;

/**
 * Resolves the caller's tenant and membership. Every org-scoped query goes
 * through here or through an equivalent repository method that takes the
 * organisation id, so a missing filter is a compile-time mistake rather than a
 * silent data leak.
 */
@Component
@RequiredArgsConstructor
public class TenantContext {

    public static final String ORGANIZATION_HEADER = "X-Organization-Id";

    private final MembershipRepository membershipRepository;
    private final UserRepository userRepository;
    private final CurrentUser currentUser;

    public Long userId() {
        return currentUser.id();
    }

    public User requireUser(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
    }

    /** Membership or a 404: never reveal that another tenant's org exists. */
    public Membership requireMembership(Long organizationId) {
        if (organizationId == null) {
            throw new ResourceNotFoundException("Organization not found");
        }
        return membershipRepository.findByOrganizationIdAndUserId(organizationId, currentUser.id())
                .orElseThrow(() -> new ResourceNotFoundException("Organization not found"));
    }

    public Membership requireRole(Long organizationId, Membership.Role... roles) {
        Membership membership = requireMembership(organizationId);
        List<Membership.Role> allowed = Arrays.asList(roles);
        if (!allowed.contains(membership.getRole())) {
            throw new AccessDeniedException("This action needs a different organisation role");
        }
        return membership;
    }

    public boolean hasRole(Long organizationId, Membership.Role... roles) {
        return membershipRepository.findByOrganizationIdAndUserId(organizationId, currentUser.id())
                .map(m -> Arrays.asList(roles).contains(m.getRole()))
                .orElse(false);
    }

    /**
     * Header value when present and valid, otherwise the caller's first
     * membership. Returns null for legacy single-user accounts.
     */
    public Long currentOrganizationId() {
        ServletRequestAttributes attrs =
                (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        String header = attrs != null ? attrs.getRequest().getHeader(ORGANIZATION_HEADER) : null;
        if (header != null && !header.isBlank()) {
            try {
                Long orgId = Long.parseLong(header.trim());
                // A stale header must not break the classic single-user flow.
                if (membershipRepository.existsByOrganizationIdAndUserId(orgId, currentUser.id())) {
                    return orgId;
                }
            } catch (NumberFormatException ignored) {
                // Fall through to the JWT/default lookup.
            }
        }
        // The JWT carries the login-time default tenant; re-validate it.
        Long claimed = currentUser.organizationId();
        if (claimed != null
                && membershipRepository.existsByOrganizationIdAndUserId(claimed, currentUser.id())) {
            return claimed;
        }
        return membershipRepository.findFirstByUserIdOrderByIdAsc(currentUser.id())
                .map(m -> m.getOrganization().getId())
                .orElse(null);
    }
}
