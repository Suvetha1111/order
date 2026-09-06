package com.orderflow.auth.service;

import com.orderflow.auth.dto.LoginRequest;
import com.orderflow.auth.dto.RegisterRequest;
import com.orderflow.auth.dto.RegisterResponse;
import com.orderflow.auth.entity.Role;
import com.orderflow.auth.entity.User;
import com.orderflow.auth.repository.RoleRepository;
import com.orderflow.auth.repository.UserRepository;
import com.orderflow.auth.security.JwtTokenProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock private UserRepository userRepository;
    @Mock private RoleRepository roleRepository;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private JwtTokenProvider tokenProvider;

    private AuthService authService;

    @BeforeEach
    void setUp() {
        authService = new AuthService(userRepository, roleRepository, passwordEncoder, tokenProvider);
    }

    @Test
    void register_success() {
        RegisterRequest request = new RegisterRequest("John Doe", "john@example.com", "password123");

        Role customerRole = new Role("CUSTOMER");
        when(userRepository.existsByEmail("john@example.com")).thenReturn(false);
        when(roleRepository.findByName("CUSTOMER")).thenReturn(Optional.of(customerRole));
        when(passwordEncoder.encode("password123")).thenReturn("hashed");
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> {
            User user = invocation.getArgument(0);
            user.setId(UUID.randomUUID());
            return user;
        });

        RegisterResponse response = authService.register(request);

        assertNotNull(response.userId());
        assertEquals("john@example.com", response.email());
        assertEquals("John Doe", response.fullName());
        assertTrue(response.roles().contains("CUSTOMER"));

        // Verify password was hashed
        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(userCaptor.capture());
        assertEquals("hashed", userCaptor.getValue().getPasswordHash());
    }

    @Test
    void register_duplicateEmail_throws() {
        RegisterRequest request = new RegisterRequest("John Doe", "john@example.com", "password123");
        when(userRepository.existsByEmail("john@example.com")).thenReturn(true);

        assertThrows(IllegalStateException.class, () -> authService.register(request));

        verify(userRepository, never()).save(any());
    }

    @Test
    void login_success() {
        LoginRequest request = new LoginRequest("john@example.com", "password123");

        User user = new User();
        user.setId(UUID.randomUUID());
        user.setEmail("john@example.com");
        user.setFullName("John Doe");
        user.setPasswordHash("hashed");
        user.addRole(new Role("CUSTOMER"));

        when(userRepository.findByEmail("john@example.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("password123", "hashed")).thenReturn(true);
        when(tokenProvider.generateToken(any(UUID.class), eq("john@example.com"), anyList()))
                .thenReturn("jwt-token");

        AuthService.LoginResult result = authService.login(request);

        assertEquals(user.getId(), result.response().userId());
        assertEquals("jwt-token", result.token());
    }

    @Test
    void login_wrongEmail_throws() {
        LoginRequest request = new LoginRequest("nobody@example.com", "password123");
        when(userRepository.findByEmail("nobody@example.com")).thenReturn(Optional.empty());

        assertThrows(BadCredentialsException.class, () -> authService.login(request));
    }

    @Test
    void login_wrongPassword_throws() {
        LoginRequest request = new LoginRequest("john@example.com", "wrong-password");

        User user = new User();
        user.setEmail("john@example.com");
        user.setPasswordHash("hashed");

        when(userRepository.findByEmail("john@example.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("wrong-password", "hashed")).thenReturn(false);

        assertThrows(BadCredentialsException.class, () -> authService.login(request));
    }
}
