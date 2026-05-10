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

package ca.tweetzy.flight.database.schema;

import ca.tweetzy.flight.database.DatabaseConnector;
import ca.tweetzy.flight.database.SQLiteConnector;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.logging.Level;

/**
 * Manages database schema creation and updates
 */
public class SchemaManager {
    
    private final DatabaseConnector connector;
    private final String tablePrefix;
    private final Plugin plugin;
    private final SchemaGenerator generator;
    private final SchemaComparator comparator;
    
    public SchemaManager(@NotNull DatabaseConnector connector, 
                        @NotNull String tablePrefix,
                        @NotNull Plugin plugin) {
        this.connector = connector;
        this.tablePrefix = tablePrefix;
        this.plugin = plugin;
        this.generator = new SchemaGenerator(connector);
        this.comparator = new SchemaComparator(connector);
    }
    
    /**
     * Initialize or update a table based on entity class
     */
    public void initializeTable(@NotNull Class<?> entityClass, @NotNull String pluginVersion) {
        try {
            TableDefinition entitySchema = generator.generateTableDefinition(entityClass, pluginVersion);
            String tableName = entitySchema.getTableName();
            
            // Check if table exists
            boolean tableExists = tableExists(tableName);
            
            if (!tableExists) {
                // Create new table
                createTable(entitySchema);
                plugin.getLogger().info("Created table: " + tablePrefix + tableName);
            } else {
                // Update existing table
                updateTable(entitySchema, pluginVersion);
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to initialize table for " + entityClass.getName(), e);
        }
    }
    
    /**
     * Check if a table exists
     */
    private boolean tableExists(@NotNull String tableName) {
        boolean[] exists = new boolean[1];
        String fullTableName = tablePrefix + tableName;
        
        connector.connect(connection -> {
            String query;
            if (connector instanceof SQLiteConnector) {
                query = "SELECT name FROM sqlite_master WHERE type='table' AND name=?";
            } else {
                query = "SHOW TABLES LIKE ?";
            }
            
            try (PreparedStatement stmt = connection.prepareStatement(query)) {
                stmt.setString(1, fullTableName);
                try (ResultSet rs = stmt.executeQuery()) {
                    exists[0] = rs.next();
                }
            } catch (SQLException e) {
                exists[0] = false;
            }
        });
        
        return exists[0];
    }
    
    /**
     * Create a new table
     */
    private void createTable(@NotNull TableDefinition tableDef) {
        String sql = generator.generateCreateTableSQL(tableDef, tablePrefix);
        
        connector.connect(connection -> {
            try (PreparedStatement stmt = connection.prepareStatement(sql)) {
                stmt.execute();
            } catch (SQLException e) {
                throw new RuntimeException("Failed to create table: " + sql, e);
            }
        });
    }
    
    /**
     * Update an existing table based on schema differences
     */
    private void updateTable(@NotNull TableDefinition entitySchema, @NotNull String pluginVersion) {
        try {
            TableDefinition dbSchema = comparator.getDatabaseSchema(entitySchema.getTableName(), tablePrefix);
            SchemaComparator.SchemaDiff diff = comparator.compare(entitySchema, dbSchema, pluginVersion);
            
            if (!diff.hasChanges()) {
                return; // No changes needed
            }
            
            String tableName = entitySchema.getTableName();
            
            // Add new columns
            for (ColumnDefinition newColumn : diff.getNewColumns()) {
                addColumn(tableName, newColumn);
                plugin.getLogger().info("Added column " + newColumn.getName() + " to table " + tablePrefix + tableName);
            }
            
            // Remove columns scheduled for removal
            for (String removedColumn : diff.getRemovedColumns()) {
                dropColumn(tableName, removedColumn);
                plugin.getLogger().info("Removed column " + removedColumn + " from table " + tablePrefix + tableName);
            }
            
            // Log orphaned columns (exist in DB but not in entity, not scheduled for removal)
            for (String orphanedColumn : diff.getOrphanedColumns()) {
                plugin.getLogger().warning("Column " + orphanedColumn + " exists in database but not in entity class. " +
                    "It will be preserved. Add @RemovalVersion to schedule removal.");
            }
            
            // Handle type changes
            for (SchemaComparator.SchemaDiff.ColumnChange change : diff.getChangedColumns()) {
                if (change.isSafeConversion()) {
                    // Safe conversion - alter column type
                    alterColumnType(tableName, change.getNewColumn());
                    plugin.getLogger().info("Changed column type for " + change.getNewColumn().getName() + 
                        " from " + change.getOldColumn().getSqlType() + 
                        " to " + change.getNewColumn().getSqlType());
                } else {
                    // Unsafe conversion - log warning
                    plugin.getLogger().warning("Column " + change.getNewColumn().getName() + 
                        " type change from " + change.getOldColumn().getSqlType() + 
                        " to " + change.getNewColumn().getSqlType() + 
                        " may lose data. Manual migration recommended.");
                }
            }
            
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to update table", e);
        }
    }
    
    /**
     * Add a column to a table
     */
    private void addColumn(@NotNull String tableName, @NotNull ColumnDefinition column) {
        String sql = generator.generateAddColumnSQL(column, tableName, tablePrefix);
        
        connector.connect(connection -> {
            try (PreparedStatement stmt = connection.prepareStatement(sql)) {
                stmt.execute();
            } catch (SQLException e) {
                // Column might already exist, check error
                if (!e.getMessage().toLowerCase().contains("duplicate") && 
                    !e.getMessage().toLowerCase().contains("already exists")) {
                    throw new RuntimeException("Failed to add column: " + sql, e);
                }
            }
        });
    }
    
    /**
     * Drop a column from a table
     */
    private void dropColumn(@NotNull String tableName, @NotNull String columnName) {
        String sql = generator.generateDropColumnSQL(columnName, tableName, tablePrefix);
        
        connector.connect(connection -> {
            try (PreparedStatement stmt = connection.prepareStatement(sql)) {
                stmt.execute();
            } catch (SQLException e) {
                // Column might not exist, check error
                if (!e.getMessage().toLowerCase().contains("does not exist") &&
                    !e.getMessage().toLowerCase().contains("unknown column")) {
                    throw new RuntimeException("Failed to drop column: " + sql, e);
                }
            }
        });
    }
    
    /**
     * Alter column type
     */
    private void alterColumnType(@NotNull String tableName, @NotNull ColumnDefinition column) {
        // Note: ALTER COLUMN syntax varies by database
        if (connector instanceof SQLiteConnector) {
            // SQLite doesn't support ALTER COLUMN, would need table recreation
            plugin.getLogger().warning("SQLite does not support ALTER COLUMN. Type change for " + 
                column.getName() + " will be skipped. Manual migration required.");
            return;
        }
        
        // MySQL/MariaDB
        final String sql = "ALTER TABLE " + tablePrefix + tableName + 
                  " MODIFY COLUMN " + column.getName() + " " + column.getSqlType() +
                  (!column.isNullable() ? " NOT NULL" : "");
        
        connector.connect(connection -> {
            try (PreparedStatement stmt = connection.prepareStatement(sql)) {
                stmt.execute();
            } catch (SQLException e) {
                plugin.getLogger().warning("Failed to alter column type: " + e.getMessage());
            }
        });
    }
}

