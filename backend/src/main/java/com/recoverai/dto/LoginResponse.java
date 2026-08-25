package com.recoverai.dto;

public record LoginResponse(
        String token,
        String tokenType,
        String role,
        long expiresInSeconds
) {
}
