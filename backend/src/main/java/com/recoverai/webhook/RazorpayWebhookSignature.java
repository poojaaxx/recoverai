package com.recoverai.webhook;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Razorpay webhook signature verification: {@code HMAC-SHA256(payload,
 * webhookSecret)}, hex-encoded, compared against the {@code
 * X-Razorpay-Signature} header - exactly as
 * <a href="https://razorpay.com/docs/webhooks/validate-test/">Razorpay's
 * documented verification algorithm</a>. Verification must run against the
 * <b>raw</b> request body, before it is parsed as JSON - re-serializing a
 * parsed object is not guaranteed to reproduce the exact bytes Razorpay
 * signed.
 * <p>
 * {@link #sign} is the same computation, exposed so tests can build
 * realistic signed fixtures for the real {@code POST /api/webhooks/razorpay}
 * endpoint rather than needing a separate, unsigned test-only bypass route.
 */
public final class RazorpayWebhookSignature {

    private static final String ALGORITHM = "HmacSHA256";

    private RazorpayWebhookSignature() {
    }

    /** {@code true} only for a well-formed signature that was computed with this exact secret over this exact payload. Fails closed (false) on any error, blank input, or blank secret. */
    public static boolean isValid(String payload, String signatureHeader, String secret) {
        if (payload == null || signatureHeader == null || signatureHeader.isBlank()
                || secret == null || secret.isBlank()) {
            return false;
        }
        String expected;
        try {
            expected = sign(payload, secret);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            return false;
        }
        byte[] expectedBytes = expected.getBytes(StandardCharsets.UTF_8);
        byte[] actualBytes = signatureHeader.trim().getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expectedBytes, actualBytes);
    }

    /** Computes the lowercase-hex HMAC-SHA256 signature Razorpay would send for this payload/secret pair. */
    public static String sign(String payload, String secret) throws NoSuchAlgorithmException, InvalidKeyException {
        Mac mac = Mac.getInstance(ALGORITHM);
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM));
        byte[] computed = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(computed);
    }
}
