package com.interviewer.security;

import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Resolves the authenticated user id from the JWT. Controllers use this instead of
 * parsing headers themselves (the old fallback parsed a subject that is an e-mail).
 */
@Component
@RequiredArgsConstructor
public class CurrentUser {

    private final JwtUtil jwtUtil;

    public Long id() {
        try {
            return jwtUtil.extractUserId(token());
        } catch (InsufficientAuthenticationException e) {
            throw e;
        } catch (Exception e) {
            throw new InsufficientAuthenticationException("Invalid or expired token");
        }
    }

    /** Default tenant hint from the JWT; membership is still re-checked by TenantContext. */
    public Long organizationId() {
        try {
            return jwtUtil.extractOrganizationId(token());
        } catch (Exception e) {
            return null;
        }
    }

    private String token() {
        ServletRequestAttributes attrs =
                (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        String auth = attrs != null ? attrs.getRequest().getHeader("Authorization") : null;
        if (auth == null || !auth.startsWith("Bearer ")) {
            throw new InsufficientAuthenticationException("Authentication required");
        }
        return auth.substring(7);
    }
}
