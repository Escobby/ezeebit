-- Ezeebit wallet core schema.
-- Money is always stored as integer "minor units" (cents / smallest stablecoin unit)
-- to avoid floating point errors. `currency_definitions.decimals` says how to render it.

CREATE TABLE merchants (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    name        VARCHAR(255)      NOT NULL,
    is_system   BOOLEAN           NOT NULL DEFAULT FALSE,
    created_at  DATETIME(6)       NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
) ENGINE=InnoDB;

CREATE TABLE currency_definitions (
    code            VARCHAR(10)  PRIMARY KEY,
    currency_type   VARCHAR(10)  NOT NULL,   -- FIAT | CRYPTO
    decimals        INT          NOT NULL,
    CONSTRAINT chk_currency_type CHECK (currency_type IN ('FIAT', 'CRYPTO'))
) ENGINE=InnoDB;

-- One row per (merchant, currency). Never mix currencies in a single account.
CREATE TABLE accounts (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    merchant_id     BIGINT       NOT NULL,
    currency_code   VARCHAR(10)  NOT NULL,
    balance_minor   BIGINT       NOT NULL DEFAULT 0,
    version         BIGINT       NOT NULL DEFAULT 0,
    created_at      DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at      DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_accounts_merchant FOREIGN KEY (merchant_id) REFERENCES merchants(id),
    CONSTRAINT fk_accounts_currency FOREIGN KEY (currency_code) REFERENCES currency_definitions(code),
    CONSTRAINT uq_account_merchant_currency UNIQUE (merchant_id, currency_code),
    -- Real merchant balances can never go negative. The one exception is the
    -- SYSTEM merchant's (id 0) per-currency clearing accounts, which are the
    -- counterparty leg of every conversion (see ConversionService) and are
    -- allowed to run negative between the two legs of a conversion - that
    -- negative number *is* the platform's short-term FX exposure, which in
    -- production is hedged/settled the way the brief describes.
    CONSTRAINT chk_balance_non_negative CHECK (merchant_id = 0 OR balance_minor >= 0)
) ENGINE=InnoDB;

-- One row per business operation (deposit / conversion / withdrawal / reversal).
-- The idempotency guard lives here: (merchant_id, idempotency_key) is unique,
-- so a retried request from a flaky mobile connection can never be applied twice.
CREATE TABLE transactions (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    public_id           VARCHAR(36)  NOT NULL,
    merchant_id         BIGINT       NOT NULL,
    type                VARCHAR(30)  NOT NULL,   -- DEPOSIT | CONVERSION | WITHDRAWAL | WITHDRAWAL_REVERSAL
    status              VARCHAR(20)  NOT NULL,   -- PENDING | COMPLETED | FAILED
    idempotency_key     VARCHAR(100) NOT NULL,
    metadata_json       TEXT NULL,
    created_at          DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at          DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_tx_merchant FOREIGN KEY (merchant_id) REFERENCES merchants(id),
    CONSTRAINT uq_tx_public_id UNIQUE (public_id),
    CONSTRAINT uq_tx_merchant_idempotency UNIQUE (merchant_id, idempotency_key)
) ENGINE=InnoDB;

-- Immutable, append-only double-entry ledger. Every transaction produces a
-- balanced set of debit/credit rows. Nothing here is ever updated or deleted;
-- it is the audit trail for "how did this balance reach its current value".
CREATE TABLE ledger_entries (
    id                   BIGINT AUTO_INCREMENT PRIMARY KEY,
    account_id           BIGINT       NOT NULL,
    transaction_id       BIGINT       NOT NULL,
    entry_type           VARCHAR(10)  NOT NULL,  -- DEBIT | CREDIT
    amount_minor         BIGINT       NOT NULL,
    balance_after_minor  BIGINT       NOT NULL,
    created_at           DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_ledger_account FOREIGN KEY (account_id) REFERENCES accounts(id),
    CONSTRAINT fk_ledger_tx FOREIGN KEY (transaction_id) REFERENCES transactions(id),
    CONSTRAINT chk_amount_positive CHECK (amount_minor > 0)
) ENGINE=InnoDB;

CREATE INDEX idx_ledger_account ON ledger_entries(account_id, id);
CREATE INDEX idx_ledger_tx ON ledger_entries(transaction_id);

-- A short-lived, locked-in price for a conversion. The merchant executes
-- against the quote id, not against "the market rate", so the platform
-- never re-prices a conversion mid-flight.
CREATE TABLE conversion_quotes (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    public_id           VARCHAR(36)  NOT NULL,
    merchant_id         BIGINT       NOT NULL,
    from_currency       VARCHAR(10)  NOT NULL,
    to_currency         VARCHAR(10)  NOT NULL,
    from_amount_minor   BIGINT       NOT NULL,
    to_amount_minor     BIGINT       NOT NULL,
    rate                DECIMAL(24,10) NOT NULL,
    status              VARCHAR(20)  NOT NULL,  -- ACTIVE | USED | EXPIRED
    expires_at          DATETIME(6)  NOT NULL,
    created_at          DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_quote_merchant FOREIGN KEY (merchant_id) REFERENCES merchants(id),
    CONSTRAINT uq_quote_public_id UNIQUE (public_id)
) ENGINE=InnoDB;

-- One row per payout request. The payout rail is async, so this row tracks
-- the whole lifecycle: PENDING (funds reserved, rail not yet confirmed),
-- PROCESSING (rail accepted it), COMPLETED, FAILED (funds released back).
CREATE TABLE withdrawals (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    public_id           VARCHAR(36)  NOT NULL,
    merchant_id         BIGINT       NOT NULL,
    account_id          BIGINT       NOT NULL,
    transaction_id      BIGINT       NOT NULL,
    amount_minor        BIGINT       NOT NULL,
    currency_code       VARCHAR(10)  NOT NULL,
    destination         VARCHAR(255) NOT NULL,
    status              VARCHAR(20)  NOT NULL,  -- PENDING | PROCESSING | COMPLETED | FAILED
    idempotency_key     VARCHAR(100) NOT NULL,
    external_ref        VARCHAR(100) NULL,
    failure_reason      VARCHAR(500) NULL,
    created_at          DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at          DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_wd_merchant FOREIGN KEY (merchant_id) REFERENCES merchants(id),
    CONSTRAINT fk_wd_account FOREIGN KEY (account_id) REFERENCES accounts(id),
    CONSTRAINT fk_wd_tx FOREIGN KEY (transaction_id) REFERENCES transactions(id),
    CONSTRAINT uq_wd_public_id UNIQUE (public_id),
    CONSTRAINT uq_wd_merchant_idempotency UNIQUE (merchant_id, idempotency_key)
) ENGINE=InnoDB;
