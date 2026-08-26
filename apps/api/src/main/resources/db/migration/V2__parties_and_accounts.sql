-- V2 - the parties a transaction is between: customers, their accounts, and
-- the merchants they transact with.
--
-- Every one of these is synthetic. The columns are deliberately thin: a country
-- code and a risk tier are what a fraud rule reads, and a name, an address or a
-- date of birth is what a fraud rule does not read. Storing personal data this
-- system has no use for would be the failure, not the omission.

CREATE TABLE customers (
    id                 uuid        PRIMARY KEY DEFAULT uuidv7(),
    customer_reference varchar(16) NOT NULL,
    country_code       varchar(2)  NOT NULL,
    risk_tier          varchar(16) NOT NULL,
    status             varchar(16) NOT NULL,
    created_at         timestamptz NOT NULL DEFAULT now(),
    updated_at         timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT customers_reference_unique UNIQUE (customer_reference),
    -- The reference is a human-readable handle for a support conversation, not
    -- a key. It is unique and it is never a foreign key anywhere (ADR-0007).
    CONSTRAINT customers_reference_format CHECK (customer_reference ~ '^CUS-[0-9]{6}$'),
    CONSTRAINT customers_country_format CHECK (country_code ~ '^[A-Z]{2}$'),
    CONSTRAINT customers_risk_tier_known CHECK (risk_tier IN ('STANDARD', 'ENHANCED', 'HIGH')),
    CONSTRAINT customers_status_known CHECK (status IN ('ACTIVE', 'SUSPENDED', 'CLOSED'))
);

COMMENT ON TABLE customers IS
    'Synthetic customers. Holds no personal data: this system has no use for any.';

CREATE TABLE accounts (
    id                uuid          PRIMARY KEY DEFAULT uuidv7(),
    customer_id       uuid          NOT NULL,
    account_reference varchar(16)   NOT NULL,
    currency          varchar(3)    NOT NULL,
    -- NUMERIC(19,4) everywhere money is stored (ADR-0007). Four fractional
    -- digits covers every ISO 4217 minor unit in use, three-decimal currencies
    -- included, and no binary floating point touches the value at any layer.
    balance           numeric(19,4) NOT NULL DEFAULT 0,
    status            varchar(16)   NOT NULL,
    opened_at         timestamptz   NOT NULL,
    created_at        timestamptz   NOT NULL DEFAULT now(),
    updated_at        timestamptz   NOT NULL DEFAULT now(),

    CONSTRAINT accounts_reference_unique UNIQUE (account_reference),
    CONSTRAINT accounts_reference_format CHECK (account_reference ~ '^ACC-[0-9]{6}$'),
    CONSTRAINT accounts_currency_format CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT accounts_status_known CHECK (status IN ('ACTIVE', 'FROZEN', 'CLOSED')),
    -- RESTRICT, not CASCADE. Deleting a customer that still has accounts is
    -- either a mistake or a data-retention operation that has to deal with the
    -- transactions underneath; neither should happen as a side effect.
    CONSTRAINT accounts_customer_fk FOREIGN KEY (customer_id) REFERENCES customers (id) ON DELETE RESTRICT
);

-- "Which accounts does this customer hold" is the only traversal of this
-- relationship, and PostgreSQL does not index a foreign key for you.
CREATE INDEX accounts_customer_idx ON accounts (customer_id);

CREATE TABLE merchants (
    id                 uuid         PRIMARY KEY DEFAULT uuidv7(),
    merchant_reference varchar(16)  NOT NULL,
    name               varchar(128) NOT NULL,
    category_code      varchar(4)   NOT NULL,
    country_code       varchar(2)   NOT NULL,
    created_at         timestamptz  NOT NULL DEFAULT now(),
    updated_at         timestamptz  NOT NULL DEFAULT now(),

    CONSTRAINT merchants_reference_unique UNIQUE (merchant_reference),
    CONSTRAINT merchants_reference_format CHECK (merchant_reference ~ '^MER-[0-9]{4}$'),
    -- ISO 18245 merchant category code. Four digits, and the leading zero is
    -- significant, which is why it is text rather than an integer.
    CONSTRAINT merchants_category_format CHECK (category_code ~ '^[0-9]{4}$'),
    CONSTRAINT merchants_country_format CHECK (country_code ~ '^[A-Z]{2}$')
);

COMMENT ON TABLE merchants IS
    'Synthetic counterparties. Names are invented and match no real business.';

-- Unusual-merchant-category rules read this pair directly.
CREATE INDEX merchants_category_country_idx ON merchants (category_code, country_code);
