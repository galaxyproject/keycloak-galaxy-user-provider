-- Minimal galaxy_user table matching the columns the provider queries.
-- This is NOT the full Galaxy schema — just enough to test the provider.

CREATE TABLE galaxy_user (
    id          SERIAL PRIMARY KEY,
    email       VARCHAR(255) NOT NULL UNIQUE,
    username    VARCHAR(255) NOT NULL UNIQUE,
    password    VARCHAR(255),
    deleted     BOOLEAN NOT NULL DEFAULT false,
    active      BOOLEAN NOT NULL DEFAULT true
);

-- PBKDF2 user: password is "testpass123"
-- Hash generated with Galaxy's format: PBKDF2$sha256$iterations$base64-salt$base64-hash
-- Salt "dGVzdHNhbHQxMjM0" is used as raw ASCII bytes (Galaxy's double-encoding quirk)
INSERT INTO galaxy_user (email, username, password)
VALUES ('pbkdf2user@test.org', 'pbkdf2user',
        'PBKDF2$sha256$100000$dGVzdHNhbHQxMjM0$BeJ2jtgGXLtyAZEa8HMSnkw8haywvhfF');

-- Legacy SHA-1 user: password is "legacypass"
-- Hash is plain hex SHA-1 (unsalted, as used by very old Galaxy accounts)
INSERT INTO galaxy_user (email, username, password)
VALUES ('sha1user@test.org', 'sha1user',
        'c350cf9267c0c4526da70133d6733b04a3b4073c');

-- Deleted user: should NOT be findable by the provider
INSERT INTO galaxy_user (email, username, password, deleted)
VALUES ('deleted@test.org', 'deleteduser',
        'PBKDF2$sha256$100000$dGVzdHNhbHQxMjM0$BeJ2jtgGXLtyAZEa8HMSnkw8haywvhfF',
        true);

-- Inactive user: should NOT be findable by the provider
INSERT INTO galaxy_user (email, username, password, active)
VALUES ('inactive@test.org', 'inactiveuser',
        'PBKDF2$sha256$100000$dGVzdHNhbHQxMjM0$BeJ2jtgGXLtyAZEa8HMSnkw8haywvhfF',
        false);

-- User with no password: should fail authentication
INSERT INTO galaxy_user (email, username, password)
VALUES ('nopass@test.org', 'nopassuser', NULL);
