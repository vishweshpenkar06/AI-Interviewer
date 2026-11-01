package com.interviewer;

import com.interviewer.dto.AuthResponse;
import com.interviewer.dto.LoginRequest;
import com.interviewer.dto.RegisterRequest;
import com.interviewer.entity.User;
import com.interviewer.exception.ConflictException;
import com.interviewer.repository.MembershipRepository;
import com.interviewer.repository.UserRepository;
import com.interviewer.security.JwtUtil;
import com.interviewer.service.AuthService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * The credential flow should touch the user row once and reuse the loaded
 * entity when signing the JWT. Reloading it through UserDetailsService added a
 * redundant database round trip to every login, registration and Google sign-in.
 */
@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private MembershipRepository membershipRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private JwtUtil jwtUtil;

    @Mock
    private AuthenticationManager authenticationManager;

    @InjectMocks
    private AuthService authService;

    private static User user() {
        return User.builder()
                .id(7L)
                .email("ada@example.com")
                .password("encoded")
                .fullName("Ada Lovelace")
                .role(User.Role.CANDIDATE)
                .build();
    }

    @Test
    void loginReusesTheLoadedUserForTheToken() {
        User user = user();
        when(userRepository.findByEmail("ada@example.com")).thenReturn(Optional.of(user));
        when(authenticationManager.authenticate(any())).thenReturn(mock(Authentication.class));
        when(jwtUtil.generateToken(eq("ada@example.com"), anyMap())).thenReturn("jwt-token");

        LoginRequest request = new LoginRequest();
        request.setEmail("  Ada@Example.com ");
        request.setPassword("Str0ngPassw0rd!x");

        AuthResponse response = authService.login(request);

        assertThat(response.getToken()).isEqualTo("jwt-token");
        assertThat(response.getUserId()).isEqualTo(7L);
        assertThat(response.getEmail()).isEqualTo("ada@example.com");
        assertThat(response.getFullName()).isEqualTo("Ada Lovelace");

        verify(userRepository).findByEmail("ada@example.com");
        verifyNoMoreInteractions(userRepository);
        verify(authenticationManager).authenticate(any());
        verify(jwtUtil).generateToken(eq("ada@example.com"), argThat(claims ->
                claims.get("userId").equals(7L) && "CANDIDATE".equals(claims.get("role"))));
    }

    @Test
    void registerSavesOnceAndSignsTheTokenFromTheSavedUser() {
        when(userRepository.existsByEmail("New@Example.com")).thenReturn(false);
        when(userRepository.existsByPhone("+9199")).thenReturn(false);
        when(passwordEncoder.encode("Str0ngPassw0rd!x")).thenReturn("encoded");
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> {
            User saved = invocation.getArgument(0);
            saved.setId(11L);
            return saved;
        });
        when(jwtUtil.generateToken(eq("new@example.com"), anyMap())).thenReturn("jwt-token");

        RegisterRequest request = new RegisterRequest();
        request.setEmail("New@Example.com");
        request.setPassword("Str0ngPassw0rd!x");
        request.setFullName("New Candidate");
        request.setPhone("+91 99");

        AuthResponse response = authService.register(request);

        assertThat(response.getToken()).isEqualTo("jwt-token");
        assertThat(response.getUserId()).isEqualTo(11L);
        assertThat(response.getEmail()).isEqualTo("new@example.com");

        verify(userRepository).existsByEmail("New@Example.com");
        verify(userRepository).existsByPhone("+9199");
        verify(userRepository).save(argThat(saved ->
                "new@example.com".equals(saved.getEmail())
                        && "encoded".equals(saved.getPassword())
                        && "+9199".equals(saved.getPhone())));
        verify(userRepository, never()).findByEmail(anyString());
    }

    @Test
    void duplicateEmailStopsBeforeHashingOrSaving() {
        when(userRepository.existsByEmail("dup@example.com")).thenReturn(true);

        RegisterRequest request = new RegisterRequest();
        request.setEmail("dup@example.com");
        request.setPassword("Str0ngPassw0rd!x");
        request.setFullName("Duplicate");

        assertThatThrownBy(() -> authService.register(request))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("Email already exists");

        verify(userRepository, never()).save(any(User.class));
        verify(passwordEncoder, never()).encode(anyString());
    }
}
