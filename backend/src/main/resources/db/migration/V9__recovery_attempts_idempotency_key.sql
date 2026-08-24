ALTER TABLE recovery_attempts
    ADD COLUMN idempotency_key VARCHAR(200);

-- Nullable + unique: existing (Phase 2-5) rows never set this column, and
-- PostgreSQL treats multiple NULLs as distinct under a UNIQUE constraint,
-- so historical rows are unaffected. Only Phase 6+ callers that populate
-- idempotency_key get real duplicate-execution protection - see
-- com.recoverai.payment.IdempotencyKeys.
ALTER TABLE recovery_attempts
    ADD CONSTRAINT uq_recovery_attempts_idempotency_key UNIQUE (idempotency_key);
