package org.galaxyproject.keycloak;

import org.keycloak.common.util.MultivaluedHashMap;
import org.keycloak.component.ComponentModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;
import org.keycloak.storage.StorageId;
import org.keycloak.storage.adapter.AbstractUserAdapterFederatedStorage;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Maps a galaxy_user row to Keycloak's UserModel interface.
 * Read-only: Galaxy DB is the source of truth.
 */
public class GalaxyUserAdapter extends AbstractUserAdapterFederatedStorage {

    private final String username;
    private final String email;
    private final String galaxyUserId;

    public GalaxyUserAdapter(
            KeycloakSession session,
            RealmModel realm,
            ComponentModel storageProviderModel,
            String galaxyUserId,
            String username,
            String email) {
        super(session, realm, storageProviderModel);
        this.galaxyUserId = galaxyUserId;
        this.username = username;
        this.email = email;
    }

    /**
     * The Keycloak federated user id, of the form {@code f:<component>:<externalId>}.
     *
     * We key on the immutable {@code galaxy_user.id}, which
     * {@link GalaxyUserStorageProvider#getUserById} resolves with {@code WHERE id = ?}.
     * The two must agree: the inherited default keys on {@link #getUsername()}, so
     * without this override re-resolving the user (e.g. during the
     * authorization-code-to-token exchange) fails and the login is rejected with
     * {@code invalid_code}. Keying on the id rather than the email also keeps a
     * user's federated identity stable across email changes.
     */
    @Override
    public String getId() {
        return StorageId.keycloakId(storageProviderModel, galaxyUserId);
    }

    @Override
    public String getUsername() {
        return username;
    }

    @Override
    public void setUsername(String username) {
        // read-only
    }

    @Override
    public String getEmail() {
        return email;
    }

    @Override
    public void setEmail(String email) {
        // read-only
    }

    @Override
    public String getFirstName() {
        return username;
    }

    @Override
    public String getLastName() {
        return "";
    }

    @Override
    public boolean isEmailVerified() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return true;
    }

    /**
     * Surface the standard user properties as Keycloak attributes.
     *
     * Keycloak's stock OIDC mappers for {@code email}, {@code given_name} and
     * {@code family_name} are <em>user-attribute</em> mappers: they resolve a
     * claim through {@link #getAttributes()} / {@link #getFirstAttribute(String)},
     * not through the {@link #getEmail()} / {@link #getFirstName()} getters. The
     * inherited {@link AbstractUserAdapterFederatedStorage} only special-cases
     * {@code username} and otherwise reads the (empty, for this read-only
     * provider) federated attribute store, so without these overrides those
     * claims come back null and the token ships with {@code email_verified} but
     * no {@code email} — which makes Galaxy's OIDC pipeline crash building a user
     * ({@code 'NoneType' object has no attribute 'lower'}). We therefore expose
     * the columns we hold as the canonical attributes.
     */
    @Override
    public Map<String, List<String>> getAttributes() {
        MultivaluedHashMap<String, String> attributes = new MultivaluedHashMap<>();
        attributes.add(UserModel.USERNAME, getUsername());
        attributes.add(UserModel.EMAIL, getEmail());
        attributes.add(UserModel.FIRST_NAME, getFirstName());
        attributes.add(UserModel.LAST_NAME, getLastName());
        return attributes;
    }

    @Override
    public String getFirstAttribute(String name) {
        List<String> values = getAttributes().get(name);
        return (values == null || values.isEmpty()) ? null : values.get(0);
    }

    @Override
    public Stream<String> getAttributeStream(String name) {
        List<String> values = getAttributes().get(name);
        return values == null ? Stream.empty() : values.stream();
    }

    /**
     * The Galaxy {@code galaxy_user.id} primary key (distinct from the Keycloak
     * federated id returned by {@link #getId()}).
     */
    public String getGalaxyUserId() {
        return galaxyUserId;
    }
}
