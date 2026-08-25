package com.recoverai.dto;

import java.util.List;
import java.util.UUID;

/**
 * Request body for {@code POST /api/recovery/batch/execute}. The client
 * may only select <i>which</i> transactions to consider - it never
 * supplies an amount, currency, action, or authorization decision. Every
 * one of these ids is reloaded from the database and re-run through the
 * full AI + policy pipeline before anything executes (see {@code
 * BatchRecoveryExecutionService}).
 */
public record BatchExecutionRequest(List<UUID> transactionIds) {
}
