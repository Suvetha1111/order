package com.orderflow.auth.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.UUID;

/**
 * Utility for extracting the authenticated user from the SecurityContext.
 * Keeps controllers and services clean.
 */
public final class SecurityUtils {

    private SecurityUtils() {}

    /**
     * Returns the current authenticated user's principal.
     * @throws IllegalStateException if no user is authenticated
     */
    public static UserPrincipal getCurrentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof UserPrincipal principal)) {
            throw new IllegalStateException("No authenticated user in security context");
        }
        return principal;
    }

    public static UUID getCurrentUserId() {
        return getCurrentUser().userId();
    }

    public static String getCurrentUserEmail() {
        return getCurrentUser().email();
    }
}
