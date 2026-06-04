package org.galaxyproject.keycloak;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.keycloak.component.ComponentModel;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises the federated-user lookup round-trip against a real SQL engine
 * (in-memory H2 in PostgreSQL mode).
 *
 * The provider takes a {@link Connection} directly and {@link ComponentModel}
 * is a concrete bean, so no mocking framework is needed. The (unused-in-these
 * paths) KeycloakSession/RealmModel are left null.
 *
 * The decisive case is {@link #getIdRoundTripsThroughGetUserById()}: with a
 * username that differs from the email, {@code getUserById(getUserByUsername(..)
 * .getId())} must resolve the same user. Before the {@code getId()} override
 * this returned null (id was keyed on the username, but getUserById resolves the
 * external id via getUserByEmail), which is what made Keycloak reject the
 * authorization code with {@code invalid_code} at the code-to-token exchange.
 */
class GalaxyUserStorageProviderTest {

    private static final String H2_URL =
            "jdbc:h2:mem:galaxy;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
    private static final String COMPONENT_ID = "galaxy-comp";

    // Deliberately username != email != id — the case that triggered the bug.
    private static final String GALAXY_ID = "1";
    private static final String EMAIL = "alice@example.org";
    private static final String USERNAME = "alice";

    private static final String DELETED_ID = "2";
    private static final String DELETED_EMAIL = "deleted@example.org";
    private static final String INACTIVE_ID = "3";
    private static final String INACTIVE_EMAIL = "inactive@example.org";

    // Any valid Galaxy hash; lookups don't validate the password.
    private static final String PW_HASH =
            "PBKDF2$sha256$100000$0fJqtBbIOEV/gsgb$jvl7ztTjRPnA6woFYDckhr91Jp4jHhv+";

    private static final RealmModel REALM = null;

    private Connection connection;
    private ComponentModel model;

    @BeforeEach
    void setUp() throws SQLException {
        connection = DriverManager.getConnection(H2_URL);
        try (Statement st = connection.createStatement()) {
            st.execute("DROP TABLE IF EXISTS galaxy_user");
            st.execute(
                "CREATE TABLE galaxy_user (" +
                "  id BIGINT PRIMARY KEY," +
                "  email VARCHAR(255) NOT NULL," +
                "  username VARCHAR(255) NOT NULL," +
                "  password VARCHAR(255) NOT NULL," +
                "  deleted BOOLEAN NOT NULL DEFAULT FALSE," +
                "  active BOOLEAN NOT NULL DEFAULT TRUE)");
            st.execute(insert(1, EMAIL, USERNAME, false, true));
            st.execute(insert(2, DELETED_EMAIL, "deleteduser", true, true));
            st.execute(insert(3, INACTIVE_EMAIL, "inactiveuser", false, false));
        }
        model = new ComponentModel();
        model.setId(COMPONENT_ID);
    }

    @AfterEach
    void tearDown() throws SQLException {
        if (connection != null) {
            try (Statement st = connection.createStatement()) {
                st.execute("DROP ALL OBJECTS");
            }
            connection.close();
        }
    }

    private static String insert(long id, String email, String username,
                                 boolean deleted, boolean active) {
        return String.format(
            "INSERT INTO galaxy_user (id, email, username, password, deleted, active) " +
            "VALUES (%d, '%s', '%s', '%s', %b, %b)",
            id, email, username, PW_HASH, deleted, active);
    }

    /** A fresh provider per call: empty per-transaction cache, like a new request. */
    private GalaxyUserStorageProvider newProvider() {
        return new GalaxyUserStorageProvider(null, model, connection);
    }

    @Test
    void getIdRoundTripsThroughGetUserById() {
        UserModel loggedIn = newProvider().getUserByUsername(REALM, EMAIL);
        assertNotNull(loggedIn, "login by email should find the user");

        String id = loggedIn.getId();
        assertEquals("f:" + COMPONENT_ID + ":" + GALAXY_ID, id);

        // Fresh provider == empty cache == the code-to-token request path.
        UserModel resolved = newProvider().getUserById(REALM, id);
        assertNotNull(resolved, "getUserById must round-trip the id from getId()");
        assertEquals(EMAIL, resolved.getEmail());
        assertEquals(USERNAME, resolved.getUsername());
    }

    @Test
    void getIdIsKeyedOnImmutableGalaxyId() {
        String id = newProvider().getUserByUsername(REALM, EMAIL).getId();
        assertTrue(id.endsWith(":" + GALAXY_ID), "federated id must use the galaxy_user.id as external key");
        assertFalse(id.endsWith(":" + EMAIL), "regression: id must not be keyed on the (mutable) email");
        assertFalse(id.endsWith(":" + USERNAME), "regression: id must not be keyed on the username");
    }

    @Test
    void loginByUsernameAlsoRoundTrips() {
        UserModel loggedIn = newProvider().getUserByUsername(REALM, USERNAME);
        assertNotNull(loggedIn, "login by username should fall back to the username query");
        assertEquals(EMAIL, loggedIn.getEmail());

        UserModel resolved = newProvider().getUserById(REALM, loggedIn.getId());
        assertNotNull(resolved);
        assertEquals(USERNAME, resolved.getUsername());
    }

    @Test
    void excludesDeletedAndInactiveUsers() {
        assertNull(newProvider().getUserByEmail(REALM, DELETED_EMAIL), "deleted users must not resolve");
        assertNull(newProvider().getUserByEmail(REALM, INACTIVE_EMAIL), "inactive users must not resolve");
        // getUserById applies the same deleted/active filter.
        assertNull(newProvider().getUserById(REALM, "f:" + COMPONENT_ID + ":" + DELETED_ID));
        assertNull(newProvider().getUserById(REALM, "f:" + COMPONENT_ID + ":" + INACTIVE_ID));
    }

    @Test
    void exposesStandardClaimsAsAttributes() {
        // Keycloak's stock email/given_name/family_name mappers are user-ATTRIBUTE
        // mappers: they read getFirstAttribute(...), not getEmail()/getFirstName().
        // A resolved-by-id user (the token-generation path) must surface them, or
        // the token ships without an email claim and Galaxy's pipeline crashes.
        UserModel user = newProvider().getUserById(REALM, "f:" + COMPONENT_ID + ":" + GALAXY_ID);
        assertNotNull(user);
        assertEquals(EMAIL, user.getFirstAttribute(UserModel.EMAIL), "email claim must resolve via attributes");
        assertEquals(USERNAME, user.getFirstAttribute(UserModel.USERNAME));
        assertEquals(USERNAME, user.getFirstAttribute(UserModel.FIRST_NAME));
        assertEquals(EMAIL, user.getAttributeStream(UserModel.EMAIL).findFirst().orElse(null));
        assertEquals(EMAIL, user.getAttributes().get(UserModel.EMAIL).get(0));
    }

    @Test
    void unknownIdReturnsNull() {
        assertNull(newProvider().getUserById(REALM, "f:" + COMPONENT_ID + ":999999"));
    }

    @Test
    void nonNumericExternalIdReturnsNull() {
        // e.g. a stale email-keyed id from a prior scheme — not a galaxy_user.id.
        assertNull(newProvider().getUserById(REALM, "f:" + COMPONENT_ID + ":alice@example.org"));
    }
}
