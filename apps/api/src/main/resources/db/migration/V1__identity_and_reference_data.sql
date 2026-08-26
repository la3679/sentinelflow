-- V1 - demo identity and the reference rows the rest of the schema depends on.
--
-- Minimal by design. SentinelFlow authenticates nobody yet: the authentication
-- decision is deferred to its own ADR, and inventing a credential column before
-- that decision exists would either sit unused or quietly become the thing the
-- decision has to live with. There is no password, no hash, no token, and no
-- email address here, and none of those is an oversight.
--
-- Roles and the system principal are reference data rather than demo data: the
-- foreign key on alert_actions.actor_id has nothing to point at for a
-- system-performed action unless the system principal exists, so it belongs to
-- the schema's meaning and ships with it. Demo customers, accounts and
-- transactions are not reference data and are loaded by application code.

CREATE TABLE roles (
    -- uuidv7() is PostgreSQL 18. Every identifier in this schema is generated
    -- by the application, so this default only fires for a direct SQL insert -
    -- a seed loader, a fixture, a psql session. Having it means such an insert
    -- cannot accidentally introduce a v4 key and the index locality UUIDv7 was
    -- chosen for (ADR-0007) holds for every row regardless of who wrote it.
    id          uuid         PRIMARY KEY DEFAULT uuidv7(),
    code        varchar(32)  NOT NULL,
    description varchar(200) NOT NULL,
    created_at  timestamptz  NOT NULL DEFAULT now(),

    CONSTRAINT roles_code_unique UNIQUE (code),
    CONSTRAINT roles_code_known CHECK (code IN ('ANALYST', 'ADMINISTRATOR', 'AUDITOR', 'SYSTEM'))
);

COMMENT ON TABLE roles IS
    'The fixed set of principal roles. Reference data, inserted by this migration.';

CREATE TABLE users (
    id           uuid        PRIMARY KEY DEFAULT uuidv7(),
    username     varchar(64) NOT NULL,
    display_name varchar(128) NOT NULL,
    status       varchar(16) NOT NULL,
    created_at   timestamptz NOT NULL DEFAULT now(),
    updated_at   timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT users_username_unique UNIQUE (username),
    -- Lower-case, no spaces: a username differing from another only by case or
    -- by a trailing space is an impersonation vector, and the cheapest place to
    -- make that impossible is here.
    CONSTRAINT users_username_format CHECK (username ~ '^[a-z][a-z0-9._-]{2,63}$'),
    CONSTRAINT users_status_known CHECK (status IN ('ACTIVE', 'DISABLED'))
);

COMMENT ON TABLE users IS
    'Minimal demo identity. Carries no credential of any kind; authentication is not yet decided.';

CREATE TABLE user_roles (
    user_id     uuid        NOT NULL,
    role_id     uuid        NOT NULL,
    granted_at  timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT user_roles_pk PRIMARY KEY (user_id, role_id),

    -- CASCADE here and nowhere else in this schema. A role grant is not a
    -- record of anything that happened; it is a statement about a user that
    -- has no meaning once the user is gone. Every other foreign key in
    -- SentinelFlow points at history and is RESTRICT.
    CONSTRAINT user_roles_user_fk FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT user_roles_role_fk FOREIGN KEY (role_id) REFERENCES roles (id) ON DELETE RESTRICT
);

-- Reverse lookup: "who holds this role" is the only query on this table that
-- the primary key does not already serve.
CREATE INDEX user_roles_role_idx ON user_roles (role_id);

INSERT INTO roles (code, description) VALUES
    ('ANALYST',       'Reviews alerts and records dispositions.'),
    ('ADMINISTRATOR', 'Manages configuration and reprocessing.'),
    ('AUDITOR',       'Read-only access to alerts, actions and the audit log.'),
    ('SYSTEM',        'SentinelFlow itself, acting without a human operator.');

-- The system principal. Every automated alert transition, outbox publication
-- and audit entry is attributed to this row, so that "no actor" never has to
-- be representable as NULL on a column whose whole purpose is attribution.
INSERT INTO users (username, display_name, status)
VALUES ('system', 'SentinelFlow', 'ACTIVE');

INSERT INTO user_roles (user_id, role_id)
SELECT u.id, r.id
FROM users u
JOIN roles r ON r.code = 'SYSTEM'
WHERE u.username = 'system';
