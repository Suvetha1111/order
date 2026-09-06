package com.orderflow.auth.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JwtTokenProviderTest {

    private JwtTokenProvider tokenProvider;

    @BeforeEach
    void setUp() {
        // 64-char secret for HS256 (>= 256 bits)
        String secret = "test-secret-key-that-is-long-enough-for-hmac-sha256-algorithm!!";
        tokenProvider = new JwtTokenProvider(secret, 3600000);
    }

    @Test
    void generateAndParseToken() {
        UUID userId = UUID.randomUUID();
        String email = "test@example.com";
        List<String> roles = List.of("CUSTOMER");

        String token = tokenProvider.generateToken(userId, email, roles);

        assertTrue(tokenProvider.validateToken(token));
        assertEquals(userId, tokenProvider.getUserIdFromToken(token));
        assertEquals(email, tokenProvider.getEmailFromToken(token));
        assertEquals(roles, tokenProvider.getRolesFromToken(token));
    }

    @Test
    void tokenWithMultipleRoles() {
        UUID userId = UUID.randomUUID();
        List<String> roles = List.of("CUSTOMER", "ADMIN");

        String token = tokenProvider.generateToken(userId, "admin@example.com", roles);

        assertEquals(roles, tokenProvider.getRolesFromToken(token));
    }

    @Test
    void expiredTokenIsInvalid() {
        // Create provider with 0ms expiration
        JwtTokenProvider expiredProvider = new JwtTokenProvider(
                "test-secret-key-that-is-long-enough-for-hmac-sha256-algorithm!!",
                0
        );

        String token = expiredProvider.generateToken(
                UUID.randomUUID(), "test@example.com", List.of("CUSTOMER"));

        assertFalse(expiredProvider.validateToken(token));
    }

    @Test
    void tamperedTokenIsInvalid() {
        String token = tokenProvider.generateToken(
                UUID.randomUUID(), "test@example.com", List.of("CUSTOMER"));

        // Tamper with the token
        String tampered = token.substring(0, token.length() - 5) + "XXXXX";

        assertFalse(tokenProvider.validateToken(tampered));
    }

    @Test
    void garbageStringIsInvalid() {
        assertFalse(tokenProvider.validateToken("not-a-jwt-token"));
    }
}
