-- Phase 7: RecoveryAttempt must record the authorized execution amount as
-- a point-in-time fact (not merely derived via the transactions FK, which
-- could theoretically be edited later) and, once a real provider call has
-- happened, which provider handled it and its reference id for
-- reconciliation - neither existed before Phase 7 actually executed
-- anything.
ALTER TABLE recovery_attempts
    ADD COLUMN amount NUMERIC(14,2),
    ADD COLUMN provider VARCHAR(20),
    ADD COLUMN provider_reference VARCHAR(255);

-- Backfill: every existing row (seed data, Phase 2-6 fixtures) was always
-- authored to match its parent transaction's amount.
UPDATE recovery_attempts ra
SET amount = t.amount
FROM transactions t
WHERE t.id = ra.transaction_id;

ALTER TABLE recovery_attempts
    ALTER COLUMN amount SET NOT NULL;

ALTER TABLE recovery_attempts
    ADD CONSTRAINT chk_recovery_attempts_amount_positive CHECK (amount > 0);

-- provider / provider_reference stay nullable: rows predating a real
-- PaymentGateway call (all seed data) never had a provider at all.
