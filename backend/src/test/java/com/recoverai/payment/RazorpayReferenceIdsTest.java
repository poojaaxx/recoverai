package com.recoverai.payment;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link RazorpayReferenceIds} - the fix for Razorpay's Payment Links API 40-character {@code
 * reference_id} limit (see the class javadoc for why a hash-based derivation, not truncation,
 * was used).
 */
class RazorpayReferenceIdsTest {

    @Test
    void generatedReferenceId_isAlwaysWithinRazorpaysFortyCharacterLimit() {
        String longKey = UUID.randomUUID() + ":RETRY_PAYMENT:1";
        assertThat(longKey.length()).isGreaterThan(40); // the exact real-world case that motivated this fix

        String referenceId = RazorpayReferenceIds.forIdempotencyKey(longKey);

        assertThat(referenceId.length()).isLessThanOrEqualTo(40);
    }

    @Test
    void sameIdempotencyKey_alwaysProducesTheSameReferenceId() {
        String key = UUID.randomUUID() + ":CREATE_PAYMENT_LINK:2";

        String first = RazorpayReferenceIds.forIdempotencyKey(key);
        String second = RazorpayReferenceIds.forIdempotencyKey(key);

        assertThat(first).isEqualTo(second);
    }

    @Test
    void differentIdempotencyKeys_doNotTriviallyCollide() {
        // The realistic collision risk this fix addresses: many attempts on the same
        // transaction, differing only in attempt number at the very end of the key - exactly
        // what a naive truncation of a long common prefix could collide.
        UUID transactionId = UUID.randomUUID();
        Set<String> referenceIds = new HashSet<>();
        for (int attempt = 1; attempt <= 200; attempt++) {
            String key = transactionId + ":RETRY_PAYMENT:" + attempt;
            referenceIds.add(RazorpayReferenceIds.forIdempotencyKey(key));
        }

        assertThat(referenceIds).hasSize(200);
    }

    @Test
    void referenceId_isDerivedNotEqualToTheOriginalKeyForLongKeys() {
        String longKey = UUID.randomUUID() + ":RETRY_PAYMENT:1";

        String referenceId = RazorpayReferenceIds.forIdempotencyKey(longKey);

        assertThat(referenceId).isNotEqualTo(longKey);
        assertThat(referenceId).startsWith("RZP");
    }

    @Test
    void blankOrNullKey_isRejected() {
        assertThatThrownBy(() -> RazorpayReferenceIds.forIdempotencyKey(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RazorpayReferenceIds.forIdempotencyKey(" "))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
