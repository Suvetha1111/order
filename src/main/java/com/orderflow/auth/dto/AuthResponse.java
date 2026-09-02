package com.orderflow.auth.dto;

import java.util.List;
import java.util.UUID;

public record AuthResponse(
        UUID userId,
        String email,
        String fullName,
        List<String> roles
) {}
