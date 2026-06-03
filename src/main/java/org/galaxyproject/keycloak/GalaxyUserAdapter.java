package org.galaxyproject.keycloak;

import org.keycloak.component.ComponentModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.storage.StorageId;
import org.keycloak.storage.adapter.AbstractUserAdapterFederatedStorage;

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
     * We key on the email: the inherited default keys on {@link #getUsername()},
     * but {@link GalaxyUserStorageProvider#getUserById} resolves the external id
     * via {@code getUserByEmail}. Without this override the two disagree whenever a
     * Galaxy username differs from its email, so re-resolving the user (e.g. during
     * the authorization-code-to-token exchange) returns null and the login fails
     * with {@code invalid_code}.
     */
    @Override
    public String getId() {
        return StorageId.keycloakId(storageProviderModel, email);
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
     * The Galaxy {@code galaxy_user.id} primary key (distinct from the Keycloak
     * federated id returned by {@link #getId()}).
     */
    public String getGalaxyUserId() {
        return galaxyUserId;
    }
}
