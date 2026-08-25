package com.recoverai.webhook;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Plain unit tests - {@link RazorpayWebhookSignature} has no dependencies, so no Spring context is needed. */
class RazorpayWebhookSignatureTest {

    private static final String SECRET = "test_webhook_secret";
    private static final String PAYLOAD = "{\"event\":\"payment_link.paid\",\"payload\":{}}";

    @Test
    void validSignature_isAccepted() throws Exception {
        String signature = RazorpayWebhookSignature.sign(PAYLOAD, SECRET);
        assertThat(RazorpayWebhookSignature.isValid(PAYLOAD, signature, SECRET)).isTrue();
    }

    @Test
    void tamperedPayload_isRejected() throws Exception {
        String signature = RazorpayWebhookSignature.sign(PAYLOAD, SECRET);
        String tamperedPayload = PAYLOAD.replace("payment_link.paid", "payment_link.cancelled");
        assertThat(RazorpayWebhookSignature.isValid(tamperedPayload, signature, SECRET)).isFalse();
    }

    @Test
    void wrongSecret_isRejected() throws Exception {
        String signature = RazorpayWebhookSignature.sign(PAYLOAD, SECRET);
        assertThat(RazorpayWebhookSignature.isValid(PAYLOAD, signature, "a_different_secret")).isFalse();
    }

    @Test
    void garbageSignature_isRejected() {
        assertThat(RazorpayWebhookSignature.isValid(PAYLOAD, "not-a-real-signature", SECRET)).isFalse();
    }

    @Test
    void missingSignature_isRejected() {
        assertThat(RazorpayWebhookSignature.isValid(PAYLOAD, null, SECRET)).isFalse();
        assertThat(RazorpayWebhookSignature.isValid(PAYLOAD, "", SECRET)).isFalse();
        assertThat(RazorpayWebhookSignature.isValid(PAYLOAD, "   ", SECRET)).isFalse();
    }

    @Test
    void blankSecret_alwaysFailsClosed() throws Exception {
        String signature = RazorpayWebhookSignature.sign(PAYLOAD, SECRET);
        assertThat(RazorpayWebhookSignature.isValid(PAYLOAD, signature, "")).isFalse();
        assertThat(RazorpayWebhookSignature.isValid(PAYLOAD, signature, null)).isFalse();
    }
}
