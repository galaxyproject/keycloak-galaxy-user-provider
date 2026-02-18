# Keycloak Galaxy User Storage Provider

A [Keycloak User Storage SPI](https://www.keycloak.org/docs/latest/server_development/#_user-storage-spi) provider that authenticates users against Galaxy's PostgreSQL `galaxy_user` table. This lets Keycloak verify Galaxy user credentials (email + password) without migrating users out of Galaxy's database.

**Read-only** — Galaxy's database stays the source of truth. Passwords are verified in their native format but never written to by Keycloak. Password changes still happen through Galaxy.

## How it works

1. User attempts to log in to Keycloak with their Galaxy email and password
2. The provider queries `galaxy_user` for an active, non-deleted user matching the email
3. The password is verified against Galaxy's PBKDF2-SHA256 hash (also handles legacy SHA-1)
4. On success, Keycloak creates a federated user session — SSO works from there

## Galaxy's password format

Galaxy stores passwords as `PBKDF2$sha256$100000$<base64-salt>$<base64-hash>` with a subtle detail: the base64-encoded salt string is used directly as the PBKDF2 salt input (not decoded back to raw bytes). The provider handles this correctly.

See `lib/galaxy/security/passwords.py` in [galaxyproject/galaxy](https://github.com/galaxyproject/galaxy) for the reference implementation.

## Building

Requires Java 17+ and Maven 3.9+:

```bash
mvn package
```

The output JAR is `target/galaxy-user-provider.jar`.

## Installation

Drop the JAR into Keycloak's `providers/` directory:

```bash
cp target/galaxy-user-provider.jar /opt/keycloak/providers/
```

If running in production mode, rebuild Keycloak after adding the provider:

```bash
/opt/keycloak/bin/kc.sh build
```

In dev mode (`start-dev`), the provider is picked up automatically on restart.

## Configuration

After deploying the JAR, configure the federation provider in Keycloak's Admin Console under **User Federation**, or via `kcadm.sh`:

```bash
kcadm.sh create components -r <realm> \
  -s name=galaxy-users \
  -s providerId=galaxy-user-provider \
  -s providerType=org.keycloak.storage.UserStorageProvider \
  -s 'config.jdbcUrl=["jdbc:postgresql://<host>:<port>/<database>"]' \
  -s 'config.dbUser=["<username>"]' \
  -s 'config.dbPassword=["<password>"]' \
  -s 'config.priority=["0"]' \
  -s 'config.cachePolicy=["NO_CACHE"]'
```

### Configuration properties

| Property | Description | Default |
|----------|-------------|---------|
| `jdbcUrl` | JDBC connection URL for Galaxy's PostgreSQL database | `jdbc:postgresql://localhost:5432/galaxy` |
| `dbUser` | Database username | `galaxy` |
| `dbPassword` | Database password | (empty) |

### Keycloak 26 note

Keycloak 26 enables `VERIFY_PROFILE` by default, which requires users to have a complete profile (first name, last name) on first login. Since Galaxy users typically don't have these fields, you may need to disable this required action:

```bash
kcadm.sh update authentication/required-actions/VERIFY_PROFILE -r <realm> -s enabled=false
```

## Docker usage

Multi-stage build example:

```dockerfile
FROM maven:3.9-eclipse-temurin-17 AS builder
WORKDIR /build
COPY pom.xml .
RUN mvn dependency:go-offline -B
COPY src src
RUN mvn package -B -DskipTests

FROM quay.io/keycloak/keycloak:26.0
COPY --from=builder /build/target/galaxy-user-provider.jar /opt/keycloak/providers/
```

## Compatibility

- **Keycloak**: 26.x (tested with 26.0)
- **Java**: 17+
- **Galaxy**: Any version using the `PBKDF2$sha256$...` password format (standard since Galaxy 15.x). Legacy hex SHA-1 hashes are also supported.

## License

MIT
