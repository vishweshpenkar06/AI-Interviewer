package com.interviewer.controller;

import com.interviewer.dto.ProfileResponse;
import com.interviewer.dto.ProfileUpdateRequest;
import com.interviewer.entity.User;
import com.interviewer.repository.UserRepository;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private final UserRepository userRepository;

    @GetMapping("/profile")
    public ResponseEntity<ProfileResponse> getProfile(@AuthenticationPrincipal UserDetails principal) {
        return ResponseEntity.ok(toResponse(findUser(principal)));
    }

    @PutMapping("/profile")
    public ResponseEntity<ProfileResponse> updateProfile(
            @AuthenticationPrincipal UserDetails principal,
            @Valid @RequestBody ProfileUpdateRequest request) {
        User user = findUser(principal);
        if (request.getFullName() != null) user.setFullName(request.getFullName());
        String phone = com.interviewer.service.AuthService.normalizePhone(request.getPhone());
        if (phone != null) {
            userRepository.findByPhone(phone).ifPresent(existing -> {
                if (!existing.getId().equals(user.getId())) {
                    throw new com.interviewer.exception.ConflictException("Phone number already exists");
                }
            });
        }
        user.setPhone(phone);
        user.setProfileSummary(request.getProfileSummary());
        user.setLinkedinUrl(request.getLinkedinUrl());
        user.setGithubUrl(request.getGithubUrl());
        return ResponseEntity.ok(toResponse(userRepository.save(user)));
    }

    private User findUser(UserDetails principal) {
        if (principal == null) throw new com.interviewer.exception.ResourceNotFoundException("User not found");
        return userRepository.findByEmail(principal.getUsername())
                .orElseThrow(() -> new com.interviewer.exception.ResourceNotFoundException("User not found"));
    }

    private ProfileResponse toResponse(User user) {
        return ProfileResponse.builder()
                .id(user.getId())
                .email(user.getEmail())
                .fullName(user.getFullName())
                .phone(user.getPhone())
                .profileSummary(user.getProfileSummary())
                .linkedinUrl(user.getLinkedinUrl())
                .githubUrl(user.getGithubUrl())
                .role(user.getRole().name())
                .consentRecording(Boolean.TRUE.equals(user.getConsentRecording()))
                .consentProctoring(Boolean.TRUE.equals(user.getConsentProctoring()))
                .dataRetentionDays(user.getDataRetentionDays())
                .build();
    }
}
