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

import java.sql.Connection;
import java.sql.SQLException;
import java.util.logging.Level;

public class MySQLConnector implements DatabaseConnector {

    private final Plugin plugin;
    private HikariDataSource hikari;
    private boolean initializedSuccessfully;

    public MySQLConnector(Plugin plugin, String hostname, int port, String database, String username, String password) {
        this(plugin, hostname, port, database, username, password, "?useUnicode=yes&characterEncoding=UTF-8&useServerPrepStmts=false&rewriteBatchedStatements=true&useSSL=true");
    }


    public MySQLConnector(Plugin plugin, String hostname, int port, String database, String username, String password, String additionalConnectionParams) {
        this.plugin = plugin;

        plugin.getLogger().info("connecting to " + hostname + " : " + port);

        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:mysql://" + hostname + ":" + port + "/" + database + additionalConnectionParams);
        config.setUsername(username);
        config.setPassword(password);
        config.setMaximumPoolSize(3);
        config.setConnectionTimeout(30000); // 30 seconds
        config.setMaxLifetime(900000); // 15 minutes (well below MySQL's typical wait_timeout)
        config.setIdleTimeout(600000); // 10 minutes
        config.setValidationTimeout(5000); // 5 seconds
        config.setLeakDetectionThreshold(60000); // 60 seconds
        config.setConnectionTestQuery("SELECT 1");

        try {
            this.hikari = new HikariDataSource(config);
            this.initializedSuccessfully = true;
        } catch (Exception ex) {
            this.initializedSuccessfully = false;
            this.plugin.getLogger().log(Level.SEVERE, "Failed to initialize MySQL connection pool", ex);
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
            this.plugin.getLogger().info("MySQL connection pool closed");
        }
    }

    @Override
    public void connect(ConnectionCallback callback) {
        try (Connection connection = this.hikari.getConnection()) {
            callback.accept(connection);
        } catch (SQLException ex) {
            this.plugin.getLogger().log(Level.SEVERE, "An error occurred executing a MySQL query", ex);
        }
    }
}
