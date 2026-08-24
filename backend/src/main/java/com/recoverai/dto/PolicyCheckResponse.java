package com.recoverai.dto;

/** One named safety check evaluated by {@code RecoveryPolicyService}, with its outcome and a deterministic reason. */
public record PolicyCheckResponse(String name, boolean passed, String reason) {
}
