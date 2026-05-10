/*
 * Flight
 * Copyright 2022 Kiran Hart
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package ca.tweetzy.flight.database;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.bukkit.plugin.Plugin;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.logging.Level;

public class SQLiteConnector implements DatabaseConnector {

    private final Plugin plugin;
    private HikariDataSource hikari;
    private boolean initializedSuccessfully;

    public SQLiteConnector(Plugin plugin) {
        this.plugin = plugin;
        
        Path dbPath = plugin.getDataFolder().toPath()
                .resolve(plugin.getDescription().getName().toLowerCase() + ".db")
                .toAbsolutePath();
        String pathForJdbc = dbPath.toUri().getPath();
        if (pathForJdbc == null) {
            pathForJdbc = dbPath.toString().replace('\\', '/');
        }
        String connectionString = "jdbc:sqlite:" + pathForJdbc + "?journal_mode=WAL";

        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException ex) {
            plugin.getLogger().log(Level.SEVERE, "SQLite JDBC driver not found", ex);
        }

        plugin.getLogger().info("Initializing SQLite connection pool for " + plugin.getDescription().getName());

        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(connectionString);
        config.setDriverClassName("org.sqlite.JDBC");
        config.setMaximumPoolSize(3);
        config.setMinimumIdle(1);
        config.setConnectionTimeout(30000);
        config.setIdleTimeout(600000);
        config.setMaxLifetime(900000); // 15 minutes (consistent with MySQL connector)
        config.setLeakDetectionThreshold(60000);
        
        // SQLite-specific optimizations
        config.addDataSourceProperty("cachePrepStmts", "true");
        config.addDataSourceProperty("prepStmtCacheSize", "250");
        config.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");

        try {
            this.hikari = new HikariDataSource(config);
            this.initializedSuccessfully = true;
            plugin.getLogger().info("SQLite connection pool initialized successfully");
        } catch (Exception ex) {
            this.initializedSuccessfully = false;
            plugin.getLogger().log(Level.SEVERE, "Failed to initialize SQLite connection pool", ex);
        }
    }

    @Override
    public boolean isInitialized() {
        return this.initializedSuccessfully;
    }

    @Override
    public void closeConnection() {
        if (this.hikari != null && !this.hikari.isClosed()) {
            this.hikari.close();
            this.plugin.getLogger().info("SQLite connection pool closed");
        }
    }

    @Override
    public void connect(ConnectionCallback callback) {
        if (!this.initializedSuccessfully || this.hikari == null) {
            this.plugin.getLogger().severe("SQLite connection pool is not initialized");
            return;
        }

        try (Connection connection = this.hikari.getConnection()) {
            callback.accept(connection);
        } catch (SQLException ex) {
            this.plugin.getLogger().log(Level.SEVERE, "An error occurred executing an SQLite query", ex);
        }
    }
}
