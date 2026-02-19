#!/bin/bash
# Integration tests for the Galaxy User Storage Provider.
# First configures the federation provider via kcadm.sh, then authenticates
# against Keycloak to verify the provider correctly resolves users and
# validates passwords from the Galaxy DB.
#
# Uses kcadm.sh for auth tests since it performs OAuth2 password grants
# internally and is available in the Keycloak image.

set -euo pipefail

KC_URL="http://keycloak:8180"
REALM="galaxy"
CLIENT_ID="test-client"
CLIENT_SECRET="test-client-secret"
KCADM="/opt/keycloak/bin/kcadm.sh"

# --- Phase 1: Configure federation provider ---
echo "=== Setting up Galaxy User Federation provider ==="
bash /scripts/init-federation.sh
echo ""

# --- Phase 2: Run authentication tests ---
PASS=0
FAIL=0

check() {
  local desc="$1"
  local expected="$2"
  local actual="$3"

  if [ "$actual" = "$expected" ]; then
    echo "  PASS: $desc"
    PASS=$((PASS + 1))
  else
    echo "  FAIL: $desc (expected=$expected, got=$actual)"
    FAIL=$((FAIL + 1))
  fi
}

# Try to authenticate; sets $AUTH_OK to "yes" or "no"
try_login() {
  local username="$1"
  local password="$2"

  if $KCADM config credentials --server "$KC_URL" --realm "$REALM" \
       --user "$username" --password "$password" \
       --client "$CLIENT_ID" --secret "$CLIENT_SECRET" 2>/dev/null; then
    AUTH_OK="yes"
  else
    AUTH_OK="no"
  fi
}

echo "=== Galaxy User Storage Provider Integration Tests ==="
echo ""

# --- PBKDF2 user tests ---
echo "[PBKDF2 user: pbkdf2user@test.org / testpass123]"

try_login "pbkdf2user@test.org" "testpass123"
check "PBKDF2 login by email succeeds" "yes" "$AUTH_OK"

try_login "pbkdf2user" "testpass123"
check "PBKDF2 login by username succeeds" "yes" "$AUTH_OK"

try_login "pbkdf2user@test.org" "wrongpassword"
check "PBKDF2 login with wrong password fails" "no" "$AUTH_OK"

echo ""

# --- SHA-1 legacy user tests ---
echo "[SHA-1 user: sha1user@test.org / legacypass]"

try_login "sha1user@test.org" "legacypass"
check "SHA-1 login by email succeeds" "yes" "$AUTH_OK"

try_login "sha1user" "legacypass"
check "SHA-1 login by username succeeds" "yes" "$AUTH_OK"

try_login "sha1user@test.org" "wrongpassword"
check "SHA-1 login with wrong password fails" "no" "$AUTH_OK"

echo ""

# --- Negative tests ---
echo "[Negative cases]"

try_login "deleted@test.org" "testpass123"
check "Deleted user cannot authenticate" "no" "$AUTH_OK"

try_login "inactive@test.org" "testpass123"
check "Inactive user cannot authenticate" "no" "$AUTH_OK"

try_login "nopass@test.org" "anything"
check "User with no password cannot authenticate" "no" "$AUTH_OK"

try_login "nonexistent@test.org" "anything"
check "Non-existent user cannot authenticate" "no" "$AUTH_OK"

echo ""

# --- Keycloak-native user (not from Galaxy) ---
echo "[Keycloak-native user: kcuser@test.org / kcpass]"

try_login "kcuser@test.org" "kcpass"
check "Keycloak-native user can still authenticate" "yes" "$AUTH_OK"

echo ""
echo "=== Results: $PASS passed, $FAIL failed ==="

if [ "$FAIL" -gt 0 ]; then
  exit 1
fi
