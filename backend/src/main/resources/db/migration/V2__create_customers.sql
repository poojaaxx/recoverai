CREATE TABLE customers (
    id                        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    merchant_id               UUID          NOT NULL,
    name                      VARCHAR(255)  NOT NULL,
    email                     VARCHAR(255)  NOT NULL,
    phone                     VARCHAR(30),
    successful_payment_count  INTEGER       NOT NULL DEFAULT 0,
    failed_payment_count      INTEGER       NOT NULL DEFAULT 0,
    total_historical_value    NUMERIC(14,2) NOT NULL DEFAULT 0,
    created_at                TIMESTAMPTZ   NOT NULL DEFAULT now(),

    CONSTRAINT fk_customers_merchant FOREIGN KEY (merchant_id)
        REFERENCES merchants (id) ON DELETE CASCADE,
    CONSTRAINT chk_customers_successful_payment_count_nonneg CHECK (successful_payment_count >= 0),
    CONSTRAINT chk_customers_failed_payment_count_nonneg CHECK (failed_payment_count >= 0),
    CONSTRAINT chk_customers_total_historical_value_nonneg CHECK (total_historical_value >= 0)
);
