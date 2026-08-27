package com.recoverai.payment;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Derives a Razorpay Payment Links {@code reference_id} (Razorpay's documented 40-character
 * maximum) from this project's own, longer-lived idempotency key ({@link
 * IdempotencyKeys#forAttempt}, typically {@code "<transactionId>:<action>:<attemptNumber>"} -
 * already 50+ characters for a single UUID-keyed attempt). The real idempotency key MUST stay
 * exactly as-is in {@code recovery_attempts.idempotency_key} - it backs the database uniqueness
 * constraint (migration V9) that gives duplicate-action protection its real teeth - so this
 * class only ever produces a separate, derived value for the outbound Razorpay API call; it
 * never replaces, shortens, or otherwise touches the stored key itself.
 * <p>
 * A plain truncation of the original string (e.g. keeping only its first 40 characters) was
 * deliberately not used: two different recovery attempts on the same transaction share a long
 * common prefix (same transaction id, only the attempt number differs at the very end), so
 * truncating could silently collide two genuinely different attempts into the same {@code
 * reference_id} - exactly what a reference id is meant to prevent. Hashing the full key with
 * SHA-256 and keeping a fixed-length prefix of the hex digest is deterministic (the same
 * idempotency key always derives the same reference_id) and makes an accidental collision
 * between two different keys astronomically unlikely (128 bits of digest retained).
 */
public final class RazorpayReferenceIds {

    private static final String PREFIX = "RZP";

    /**
     * Hex characters of the SHA-256 digest to keep. 32 hex chars = 128 bits of the digest -
     * the 3-character {@link #PREFIX} plus this stays at 35 characters total, safely under
     * Razorpay's 40-character reference_id limit.
     */
    private static final int HASH_HEX_LENGTH = 32;

    private RazorpayReferenceIds() {
    }

    /** Always &lt;= 40 characters (35 today), deterministic for the same input, and never mutates or reads the original idempotency key elsewhere in the system. */
    public static String forIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("idempotencyKey is required to derive a Razorpay reference_id.");
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(idempotencyKey.getBytes(StandardCharsets.UTF_8));
            String hex = HexFormat.of().formatHex(hash);
            return PREFIX + hex.substring(0, HASH_HEX_LENGTH);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is a JLS-mandated algorithm every JVM must provide - unreachable in practice.
            throw new IllegalStateException("SHA-256 MessageDigest unavailable.", e);
        }
    }
}
