-- Phase 12: distinguish "execution succeeded" (a provider call went through)
-- from "payment confirmed" (the customer genuinely paid, proven by a
-- verified provider webhook). See PaymentConfirmationStatus /
-- com.recoverai.webhook.PaymentConfirmationService.
--
-- NOT_CONFIRMED is the correct default for every existing row (all seed
-- data, all Phase 6/7 executions to date never had a webhook confirm them)
-- and for every row inserted going forward until a verified webhook says
-- otherwise.
ALTER TABLE recovery_attempts
    ADD COLUMN payment_confirmation_status VARCHAR(20) NOT NULL DEFAULT 'NOT_CONFIRMED',
    ADD COLUMN confirmed_amount NUMERIC(14,2),
    ADD COLUMN confirmed_currency VARCHAR(3),
    ADD COLUMN provider_payment_id VARCHAR(255),
    ADD COLUMN confirmed_at TIMESTAMPTZ;

-- Idempotent webhook processing: a provider (Razorpay) may deliver the same
-- event more than once. provider_event_id is unique per provider so a
-- replayed delivery is rejected by the database itself, not by
-- application-level "check then act" logic that a race could defeat.
CREATE TABLE webhook_events (
    id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    provider             VARCHAR(20)  NOT NULL,
    provider_event_id    VARCHAR(255) NOT NULL,
    event_type           VARCHAR(100) NOT NULL,
    processing_status    VARCHAR(20)  NOT NULL,
    recovery_attempt_id  UUID,
    reason               TEXT,
    received_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    processed_at         TIMESTAMPTZ,

    CONSTRAINT fk_webhook_events_recovery_attempt FOREIGN KEY (recovery_attempt_id)
        REFERENCES recovery_attempts (id) ON DELETE CASCADE,
    CONSTRAINT uq_webhook_events_provider_event UNIQUE (provider, provider_event_id)
);

CREATE INDEX idx_webhook_events_recovery_attempt ON webhook_events (recovery_attempt_id);
