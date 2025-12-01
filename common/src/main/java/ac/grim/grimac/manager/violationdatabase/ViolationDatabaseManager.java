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
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import lombok.Getter;
import org.jetbrains.annotations.NotNull;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.*;

public class ViolationDatabaseManager implements StartableInitable, ReloadableInitable {

    public static Properties parseConnectionProperties(ConfigManager configManager) {
        Properties properties = new Properties();

        Map<String, Object> hikariConfig = configManager.getMapElse("history.database.hikari", Collections.emptyMap());
        if (hikariConfig != null) {
            hikariConfig.forEach((key, value) -> properties.setProperty(key, String.valueOf(value)));
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

        try {
            Properties properties = parseConnectionProperties(cfg);
            HikariConfig config = new HikariConfig(properties);
            this.dataSource = new HikariDataSource(config);

            this.loadDatabase();
        } catch (Exception e) {
            this.disconnect();

            this.database = NoOpViolationDatabase.INSTANCE;
            this.loaded = false;
        }
    }

    private void loadDatabase() throws SQLException {
        try (Connection connection = this.dataSource.getConnection()) {
            String driver = connection.getMetaData().getDatabaseProductName();

            // TODO: use database registry
            this.database = switch (driver) {
                case "SQLite" -> new SQLiteViolationDatabase(this.dataSource);
                case "MySQL" -> new MySQLViolationDatabase(this.dataSource);
                case "PostgreSQL" -> new PostgresqlViolationDatabase(this.dataSource);
                default -> NoOpViolationDatabase.INSTANCE;
            };
        }
    }

    private void disconnect() {
        if (this.dataSource != null && !this.dataSource.isClosed()) {
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
