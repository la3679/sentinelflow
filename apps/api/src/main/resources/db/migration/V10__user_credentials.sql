-- V10 - what an operator logs in with.
--
-- V1 created users with the comment "carries no credential of any kind;
-- authentication is not yet decided". ADR-0012 decides it: a username and a
-- password are exchanged for a short-lived bearer token, and this is where the
-- password lives.
--
-- A SEPARATE TABLE RATHER THAN A COLUMN ON users, for two reasons.
--
-- A credential is not an attribute of a user. It is rotated, revoked and
-- sometimes absent, on its own schedule - and a users row without one is an
-- ordinary state rather than a half-populated record.
--
-- More importantly, the system principal must never be able to log in, and this
-- makes that structural. V1 inserts 'system' so that automated actions have an
-- actor; with a nullable password column, "never give the system principal one"
-- is a rule somebody has to remember. As a separate table it is the absence of
-- a row, and the login path cannot find what does not exist.
--
-- NO PASSWORD IS SET HERE, and none ever will be by a migration. A hash
-- committed to this file would be a credential in the repository and the same
-- one on every machine that ever ran it. The demo operators are created by the
-- application seed from SENTINELFLOW_DEMO_OPERATOR_PASSWORD, which
-- `make bootstrap` generates into the git-ignored .env.

CREATE TABLE user_credentials (
    user_id       uuid         PRIMARY KEY,

    -- BCrypt through Spring Security's delegating PasswordEncoder, so the value
    -- stored is '{bcrypt}$2a$10$...' - the algorithm's own identifier followed
    -- by its output. That prefix is what makes a future migration to another
    -- algorithm possible without a data migration: the encoder reads which
    -- algorithm produced a hash from the hash itself. 120 characters leaves room
    -- for one whose output is longer than BCrypt's 60.
    password_hash varchar(120) NOT NULL,

    -- When the password was last set. Not "when it expires": there is no
    -- rotation policy in this project, and a column implying one that nothing
    -- enforces would be a claim rather than a record.
    updated_at    timestamptz  NOT NULL DEFAULT now(),
    created_at    timestamptz  NOT NULL DEFAULT now(),

    -- Every stored hash names the algorithm that produced it. Deliberately not a
    -- BCrypt-shaped pattern: pinning the algorithm in a constraint would block
    -- the upgrade the prefix exists to allow. This catches the one mistake that
    -- would otherwise stay invisible until a login failed for a reason nobody
    -- could reproduce - a plaintext password written straight into the column.
    CONSTRAINT user_credentials_hash_is_identified CHECK (password_hash ~ '^\{[a-z0-9]+\}.+'),

    -- CASCADE, like user_roles and unlike everything else in this schema. A
    -- credential says something about a user and means nothing without one; it
    -- is not a record of anything that happened.
    CONSTRAINT user_credentials_user_fk FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
);

COMMENT ON TABLE user_credentials IS
    'One row per user who can log in. The system principal deliberately has none, which is what '
    'makes authenticating as it impossible rather than merely forbidden.';

COMMENT ON COLUMN user_credentials.password_hash IS
    'An algorithm-identified password hash, {bcrypt} today. Never a plaintext password, never a '
    'reversible encoding, and never set by a migration.';
