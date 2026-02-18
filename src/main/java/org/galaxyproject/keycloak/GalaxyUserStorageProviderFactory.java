package org.galaxyproject.keycloak;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.keycloak.component.ComponentModel;
import org.keycloak.component.ComponentValidationException;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.provider.ProviderConfigProperty;
import org.keycloak.provider.ProviderConfigurationBuilder;
import org.keycloak.storage.UserStorageProviderFactory;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Factory for GalaxyUserStorageProvider. Manages configuration properties
 * (JDBC URL, credentials) and creates provider instances per transaction.
 */
public class GalaxyUserStorageProviderFactory
        implements UserStorageProviderFactory<GalaxyUserStorageProvider> {

    private static final Logger LOG = Logger.getLogger(GalaxyUserStorageProviderFactory.class.getName());

    public static final String PROVIDER_ID = "galaxy-user-provider";

    public static final String CONFIG_JDBC_URL = "jdbcUrl";
    public static final String CONFIG_DB_USER = "dbUser";
    public static final String CONFIG_DB_PASSWORD = "dbPassword";

    private static final int POOL_MAX_SIZE = 5;
    private static final int POOL_MIN_IDLE = 1;
    private static final long CONNECTION_TIMEOUT_MS = 5000;
    private static final long IDLE_TIMEOUT_MS = 300_000;
    private static final long MAX_LIFETIME_MS = 600_000;

    // Pool per component model ID (supports multiple realms with different configs)
    private final ConcurrentHashMap<String, HikariDataSource> pools = new ConcurrentHashMap<>();

    private static final List<ProviderConfigProperty> CONFIG_PROPERTIES;

    static {
        CONFIG_PROPERTIES = ProviderConfigurationBuilder.create()
                .property()
                    .name(CONFIG_JDBC_URL)
                    .type(ProviderConfigProperty.STRING_TYPE)
                    .label("JDBC URL")
                    .defaultValue("jdbc:postgresql://localhost:5432/galaxy")
                    .helpText("JDBC connection URL for the Galaxy PostgreSQL database")
                    .add()
                .property()
                    .name(CONFIG_DB_USER)
                    .type(ProviderConfigProperty.STRING_TYPE)
                    .label("Database User")
                    .defaultValue("galaxy")
                    .helpText("Username for the Galaxy database connection")
                    .add()
                .property()
                    .name(CONFIG_DB_PASSWORD)
                    .type(ProviderConfigProperty.PASSWORD)
                    .label("Database Password")
                    .helpText("Password for the Galaxy database connection")
                    .add()
                .build();
    }

    @Override
    public String getId() {
        return PROVIDER_ID;
    }

    @Override
    public List<ProviderConfigProperty> getConfigProperties() {
        return CONFIG_PROPERTIES;
    }

    @Override
    public GalaxyUserStorageProvider create(KeycloakSession session, ComponentModel model) {
        try {
            HikariDataSource ds = pools.computeIfAbsent(model.getId(), id -> createPool(model));
            Connection connection = ds.getConnection();
            return new GalaxyUserStorageProvider(session, model, connection);
        } catch (SQLException e) {
            LOG.log(Level.SEVERE, "Failed to obtain connection from Galaxy database pool", e);
            throw new RuntimeException("Cannot connect to Galaxy database", e);
        }
    }

    private HikariDataSource createPool(ComponentModel model) {
        String jdbcUrl = model.get(CONFIG_JDBC_URL);
        String dbUser = model.get(CONFIG_DB_USER);
        String dbPassword = model.get(CONFIG_DB_PASSWORD);

        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(jdbcUrl);
        config.setUsername(dbUser);
        config.setPassword(dbPassword);
        config.setMaximumPoolSize(POOL_MAX_SIZE);
        config.setMinimumIdle(POOL_MIN_IDLE);
        config.setConnectionTimeout(CONNECTION_TIMEOUT_MS);
        config.setIdleTimeout(IDLE_TIMEOUT_MS);
        config.setMaxLifetime(MAX_LIFETIME_MS);
        config.setPoolName("galaxy-user-pool-" + model.getId());

        LOG.info("Creating Galaxy database connection pool for component " + model.getId());
        return new HikariDataSource(config);
    }

    @Override
    public void onUpdate(KeycloakSession session, RealmModel realm,
                         ComponentModel oldModel, ComponentModel newModel) {
        // Config may have changed, close the old pool so it gets recreated on next create()
        HikariDataSource oldPool = pools.remove(oldModel.getId());
        if (oldPool != null) {
            LOG.info("Closing Galaxy database pool due to config update for component " + oldModel.getId());
            oldPool.close();
        }
    }

    @Override
    public void validateConfiguration(KeycloakSession session, RealmModel realm, ComponentModel model)
            throws ComponentValidationException {
        String jdbcUrl = model.get(CONFIG_JDBC_URL);
        if (jdbcUrl == null || jdbcUrl.isBlank()) {
            throw new ComponentValidationException("JDBC URL is required");
        }
        if (!jdbcUrl.startsWith("jdbc:postgresql:")) {
            throw new ComponentValidationException(
                    "JDBC URL must use the jdbc:postgresql: driver");
        }

        String dbUser = model.get(CONFIG_DB_USER);
        String dbPassword = model.get(CONFIG_DB_PASSWORD);
        try (Connection conn = DriverManager.getConnection(jdbcUrl, dbUser, dbPassword)) {
            LOG.info("Successfully validated Galaxy database connection");
        } catch (SQLException e) {
            LOG.log(Level.SEVERE, "Galaxy database connection validation failed", e);
            throw new ComponentValidationException("Cannot connect to Galaxy database");
        }
    }

    @Override
    public void close() {
        pools.forEach((id, ds) -> {
            LOG.info("Shutting down Galaxy database pool for component " + id);
            ds.close();
        });
        pools.clear();
    }
}
