CREATE TABLE revenue_risks (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    transaction_id        UUID          NOT NULL,
    risk_score            NUMERIC(5,2)  NOT NULL,
    recovery_probability  NUMERIC(5,4)  NOT NULL,
    amount_at_risk        NUMERIC(14,2) NOT NULL,
    reason                TEXT,
    detected_at           TIMESTAMPTZ   NOT NULL DEFAULT now(),

    CONSTRAINT fk_revenue_risks_transaction FOREIGN KEY (transaction_id)
        REFERENCES transactions (id) ON DELETE CASCADE,
    CONSTRAINT chk_revenue_risks_risk_score_range CHECK (risk_score BETWEEN 0 AND 100),
    CONSTRAINT chk_revenue_risks_recovery_probability_range CHECK (recovery_probability BETWEEN 0 AND 1),
    CONSTRAINT chk_revenue_risks_amount_at_risk_nonneg CHECK (amount_at_risk >= 0)
);
