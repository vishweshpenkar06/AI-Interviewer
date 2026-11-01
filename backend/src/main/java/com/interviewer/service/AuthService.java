package com.interviewer.service;

import com.interviewer.dto.AuthResponse;
import com.interviewer.dto.LoginRequest;
import com.interviewer.dto.RegisterRequest;
import com.interviewer.entity.User;
import com.interviewer.repository.MembershipRepository;
import com.interviewer.repository.UserRepository;
import com.interviewer.security.JwtUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final MembershipRepository membershipRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;
    private final AuthenticationManager authenticationManager;

    public AuthResponse register(RegisterRequest request) {
        if (userRepository.existsByEmail(request.getEmail())) {
            throw new com.interviewer.exception.ConflictException("Email already exists");
        }
        String phone = normalizePhone(request.getPhone());
        // shortcut: uniqueness is checked in the service, not by a DB index, so
        // two simultaneous registrations could race. Add a unique index once
        // existing rows are cleaned.
        if (phone != null && userRepository.existsByPhone(phone)) {
            throw new com.interviewer.exception.ConflictException("Phone number already exists");
        }

        User user = User.builder()
                .email(request.getEmail().trim().toLowerCase())
                .password(passwordEncoder.encode(request.getPassword()))
                .fullName(request.getFullName())
                .phone(phone)
                .profileSummary(request.getProfileSummary())
                .linkedinUrl(request.getLinkedinUrl())
                .githubUrl(request.getGithubUrl())
                .role(User.Role.CANDIDATE)
                .build();

        userRepository.save(user);

        return generateAuthResponse(user);
    }

    public AuthResponse login(LoginRequest request) {
        String identifier = request.getEmail().trim();
        // Email is the canonical login, but a phone number is accepted as the
        // identifier too. A phone is not SMS-verified yet; it is a username.
        User user = (identifier.contains("@")
                ? userRepository.findByEmail(identifier.toLowerCase())
                : userRepository.findByPhone(normalizePhone(identifier)))
                .orElseThrow(() -> new BadCredentialsException("Invalid email/phone or password"));

        Authentication authentication = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(user.getEmail(), request.getPassword())
        );

        return generateAuthResponse(user);
    }

    /** Sign in with a verified Google account. Creates the account on first use. */
    public AuthResponse loginWithGoogle(String email, String fullName) {
        String normalized = email.trim().toLowerCase();
        User user = userRepository.findByEmail(normalized).orElseGet(() -> {
            String name = fullName == null || fullName.isBlank()
                    ? normalized.substring(0, normalized.indexOf('@'))
                    : fullName.trim();
            // A random password keeps password login closed for OAuth-only accounts.
            return userRepository.save(User.builder()
                    .email(normalized)
                    .password(passwordEncoder.encode(java.util.UUID.randomUUID().toString()))
                    .fullName(name)
                    .role(User.Role.CANDIDATE)
                    .build());
        });

        return generateAuthResponse(user);
    }

    public static String normalizePhone(String phone) {
        if (phone == null || phone.isBlank()) return null;
        return phone.replaceAll("[\\s()\\-]", "");
    }

    private AuthResponse generateAuthResponse(User user) {
        // The caller already holds the persisted user. Reloading it through
        // UserDetailsService added a second database round trip for no benefit:
        // only the email is needed as the JWT subject.
        Map<String, Object> claims = new HashMap<>();
        claims.put("userId", user.getId());
        claims.put("role", user.getRole().name());
        // A user may belong to several organisations; the claim is a default hint.
        // TenantContext still validates every request against the membership row.
        Long organizationId = membershipRepository.findFirstByUserIdOrderByIdAsc(user.getId())
                .map(m -> m.getOrganization().getId())
                .orElse(null);
        if (organizationId != null) {
            claims.put("organizationId", organizationId);
        }

        String token = jwtUtil.generateToken(user.getEmail(), claims);

        return AuthResponse.builder()
                .token(token)
                .tokenType("Bearer")
                .userId(user.getId())
                .email(user.getEmail())
                .fullName(user.getFullName())
                .role(user.getRole().name())
                .organizationId(organizationId)
                .build();
    }
}
