# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Keycloak User Storage SPI provider that authenticates users against Galaxy's PostgreSQL `galaxy_user` table. Read-only — passwords are verified but never written back. Galaxy remains the source of truth for credentials.

**Keycloak 26.0 / Java 17+ / Maven 3.9+**

## Build Commands

```bash
mvn package                    # Build JAR → target/galaxy-user-provider.jar
mvn clean package              # Clean build
```

HikariCP is bundled into the provider JAR via the maven-shade-plugin (Keycloak 26 uses Agroal internally and does not expose HikariCP to providers).

## Integration Tests

```bash
docker compose -f docker-compose.test.yml up \
  --build --abort-on-container-exit --exit-code-from test
```

Requires Docker. Builds the provider, starts Keycloak + a Galaxy-like PostgreSQL with test users, configures the federation provider, then runs 11 authentication tests (PBKDF2, SHA-1, negative cases, native user passthrough).

## Architecture

Four classes in `org.galaxyproject.keycloak` (`src/main/java/org/galaxyproject/keycloak/`):

- **GalaxyUserStorageProviderFactory** — SPI entry point. Registered via `META-INF/services/` as provider ID `galaxy-user-provider`. Manages HikariCP connection pools (per component model) and exposes three config properties: `jdbcUrl`, `dbUser`, `dbPassword`. Validates that JDBC URLs use the `jdbc:postgresql:` driver.

- **GalaxyUserStorageProvider** — Core provider. Implements `UserLookupProvider` and `CredentialInputValidator`. Queries `galaxy_user` table by email (primary) or username (fallback). Caches loaded users and password hashes per-transaction (instance maps, not static). `updateCredential()` throws `ReadOnlyException`.

- **GalaxyUserAdapter** — Maps Galaxy DB rows to Keycloak's `UserModel` via `AbstractUserAdapterFederatedStorage`. Sets `firstName=username`, `lastName=""`, `emailVerified=true` as defaults. Setters for username/email are intentional no-ops (read-only).

- **GalaxyPasswordUtil** — Static password verification. Handles two formats:
  - Modern: `PBKDF2$sha256$100000$<base64-salt>$<base64-hash>` — PBKDF2-HMAC-SHA256, 24-byte key length. **Critical quirk**: Galaxy's salt is the base64 ASCII string used directly as bytes (not decoded), i.e., "double-encoded."
  - Legacy: 40-char hex SHA-1 hashes.
  - Uses constant-time comparison (`MessageDigest.isEqual()`).

## Deployment

Copy `target/galaxy-user-provider.jar` to `/opt/keycloak/providers/`, then `kc.sh build` (production) or restart (dev mode). Configure via Keycloak Admin Console under User Federation.

## Key Implementation Details

- `getUserByUsername()` tries email lookup first, then falls back to username column — matches Galaxy's email-centric login.
- Provider is purely on-demand (no bulk sync or user export).
- Keycloak 26 VERIFY_PROFILE required action may prompt users for name fields on first login despite adapter defaults. Admins may need to disable it.
