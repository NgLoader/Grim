package ac.grim.grimac.manager.violationdatabase;

import ac.grim.grimac.GrimAPI;
import ac.grim.grimac.api.config.ConfigManager;
import ac.grim.grimac.api.plugin.GrimPlugin;
import ac.grim.grimac.manager.init.ReloadableInitable;
import ac.grim.grimac.manager.init.start.StartableInitable;
import ac.grim.grimac.manager.violationdatabase.mysql.MySQLViolationDatabase;
import ac.grim.grimac.manager.violationdatabase.postgresql.PostgresqlViolationDatabase;
import ac.grim.grimac.manager.violationdatabase.sqlite.SQLiteViolationDatabase;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.anticheat.LogUtil;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import lombok.Getter;
import org.jetbrains.annotations.NotNull;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.*;

public class ViolationDatabaseManager implements StartableInitable, ReloadableInitable {

    public static Properties parseConnectionProperties(GrimPlugin plugin, ConfigManager configManager) {
        Properties properties = new Properties();

        Map<String, Object> hikariConfig = configManager.getMapElse("history.database.hikari", Collections.emptyMap());
        if (hikariConfig != null) {
            hikariConfig.forEach((key, value) -> {
                if (key.startsWith("jdbcUrl") && value instanceof String valueString) {
                    value = valueString.replace("%FOLDER%", plugin.getDataFolder().getAbsolutePath());
                }
                properties.setProperty(key, String.valueOf(value));
            });
        }

        Map<String, Object> dataSourceConfig = configManager.getMapElse("history.database.dataSource", Collections.emptyMap());
        if (dataSourceConfig != null) {
            dataSourceConfig.forEach((key, value) -> properties.setProperty("dataSource." + key, String.valueOf(value)));
        }

        return properties;
    }

    private final GrimPlugin plugin;
    @Getter private boolean enabled = false;
    @Getter private boolean loaded = false;

    private Properties currentConfig;

    private HikariDataSource dataSource;
    private @NotNull ViolationDatabase database;

    public ViolationDatabaseManager(GrimPlugin plugin) {
        this.plugin = plugin;
        this.database = NoOpViolationDatabase.INSTANCE;
    }

    @Override
    public void start() {
        load();
    }

    @Override
    public void reload() {
        load();
    }

    public void load() {
        ConfigManager cfg = GrimAPI.INSTANCE.getConfigManager().getConfig();
        this.enabled = cfg.getBooleanElse("history.enabled", false);

        // disconnect if no longer needed
        if (!this.enabled) {
            this.disconnect();
            return;
        }
        LogUtil.info("Loading database configuration...");

        try {
            Properties properties = parseConnectionProperties(this.plugin, cfg);
            if (this.currentConfig != null) {
                // ignore if no config changes exist
                if (this.currentConfig.equals(properties)) {
                    LogUtil.info("No database configuration changes detected.");
                    return;
                }

                LogUtil.info("Database configuration detected...");
                // disconnect because config has changed
                this.disconnect();
            }
            // update current config
            this.currentConfig = properties;

            // load configuration and connect
            HikariConfig config = new HikariConfig(properties);
            this.dataSource = new HikariDataSource(config);

            // load correct implementation
            this.loadDatabase();
        } catch (Exception e) {
            LogUtil.error("Error in database connection", e);

            this.disconnect();

            this.database = NoOpViolationDatabase.INSTANCE;
            this.loaded = false;
        }
    }

    private void loadDatabase() throws SQLException {
        try (Connection connection = this.dataSource.getConnection()) {
            // read current driver
            String driver = connection.getMetaData().getDatabaseProductName();
            LogUtil.info("Detected database driver: " + driver + ".");

            // check if driver is available
            // TODO: use database registry
            this.database = switch (driver) {
                case "SQLite" -> new SQLiteViolationDatabase(this.dataSource);
                case "MySQL" -> new MySQLViolationDatabase(this.dataSource);
                case "PostgreSQL" -> new PostgresqlViolationDatabase(this.dataSource);
                default -> {
                    LogUtil.warn("Detected database driver '" + driver + "' is not supported!");
                    yield NoOpViolationDatabase.INSTANCE;
                }
            };

            // run setup
            this.database.prepare();

            if (this.database != NoOpViolationDatabase.INSTANCE) {
                LogUtil.info("Database connection established.");
            }
        }
    }

    private void disconnect() {
        if (this.dataSource != null && !this.dataSource.isClosed()) {
            LogUtil.info("Disconnected from database.");

            // reset current database
            this.database = NoOpViolationDatabase.INSTANCE;
            this.loaded = false;

            // close connection
            this.dataSource.close();
            this.dataSource = null;
        }
    }

    public void logAlert(GrimPlayer player, String verbose, String checkName, int vls) {
        String grimVersion = GrimAPI.INSTANCE.getExternalAPI().getGrimVersion();
        GrimAPI.INSTANCE.getScheduler().getAsyncScheduler().runNow(plugin, () -> database.logAlert(player, grimVersion, verbose, checkName, vls));
    }

    public int getLogCount(UUID player) {
        return database.getLogCount(player);
    }

    public List<Violation> getViolations(UUID player, int page, int limit) {
        return database.getViolations(player, page, limit);
    }
}
