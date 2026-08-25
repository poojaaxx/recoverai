-- V11 shipped webhook_events.recovery_attempt_id as ON DELETE SET NULL.
-- Nothing in production ever deletes a recovery_attempts row (no delete
-- endpoint exists), so this only ever mattered for test cleanup - but a
-- recovery_attempt with a referencing webhook_event could not be deleted
-- without also clearing that reference, which broke test fixtures that
-- reset state between runs. CASCADE is the correct semantics: a webhook
-- event's usefulness is tied to the recovery attempt it confirmed/rejected,
-- so it should be removed alongside it, not orphaned with a null reference.
--
-- V11 itself is intentionally left unmodified - it was already applied to
-- production - this migration corrects it forward instead.
ALTER TABLE webhook_events
    DROP CONSTRAINT fk_webhook_events_recovery_attempt;

ALTER TABLE webhook_events
    ADD CONSTRAINT fk_webhook_events_recovery_attempt FOREIGN KEY (recovery_attempt_id)
        REFERENCES recovery_attempts (id) ON DELETE CASCADE;
