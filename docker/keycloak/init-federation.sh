#!/bin/bash
# Configures the Galaxy User Federation provider in Keycloak using kcadm.sh.
# Runs as a one-off init container after Keycloak is healthy.

set -euo pipefail

KC_URL="http://keycloak:8180"
REALM="galaxy"
KCADM="/opt/keycloak/bin/kcadm.sh"

echo "Waiting for Keycloak to be ready..."
until $KCADM config credentials --server "$KC_URL" --realm master \
      --user admin --password admin 2>/dev/null; do
  sleep 2
done
echo "Keycloak is ready."

# Keycloak 26 enables VERIFY_PROFILE by default, which prompts federated
# users (who lack first/last name) to complete their profile on login.
echo "Disabling VERIFY_PROFILE required action..."
$KCADM update "authentication/required-actions/VERIFY_PROFILE" -r "$REALM" -s enabled=false

GALAXY_DB_URL="${GALAXY_DB_URL:-jdbc:postgresql://galaxy-db:5432/galaxy}"
GALAXY_DB_USER="${GALAXY_DB_USER:-galaxy}"
GALAXY_DB_PASSWORD="${GALAXY_DB_PASSWORD:-}"

# Check if federation provider already exists
FED_ID=$($KCADM get components -r "$REALM" --fields id,name --format csv --noquotes 2>/dev/null \
  | grep ',galaxy-users$' | sed 's/,.*//' || true)

if [ -n "$FED_ID" ]; then
  echo "Galaxy user federation provider already exists (id=$FED_ID)."
else
  echo "Creating Galaxy user federation provider..."
  FED_ID=$($KCADM create components -r "$REALM" \
    -s name=galaxy-users \
    -s providerId=galaxy-user-provider \
    -s providerType=org.keycloak.storage.UserStorageProvider \
    -s 'config.jdbcUrl=["'"$GALAXY_DB_URL"'"]' \
    -s 'config.dbUser=["'"$GALAXY_DB_USER"'"]' \
    -s 'config.dbPassword=["'"$GALAXY_DB_PASSWORD"'"]' \
    -s 'config.priority=["0"]' \
    -s 'config.cachePolicy=["NO_CACHE"]' \
    -i)
  echo "Created Galaxy user federation provider (id=$FED_ID)."
fi

echo "Init complete."
