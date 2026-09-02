package com.orderflow.auth.security;

import org.springframework.security.core.GrantedAuthority;

import java.security.Principal;
import java.util.Collection;
import java.util.UUID;

/**
 * Lightweight principal stored in the SecurityContext after JWT validation.
 * Avoids loading the full User entity on every request.
 */
public record UserPrincipal(
        UUID userId,
        String email,
        Collection<? extends GrantedAuthority> authorities
) implements Principal {

    @Override
    public String getName() {
        return email;
    }
}
