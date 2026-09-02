package com.orderflow.auth.dto;

import java.util.List;
import java.util.UUID;

public record RegisterResponse(
        UUID userId,
        String email,
        String fullName,
        List<String> roles
) {}
