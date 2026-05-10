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

package ca.tweetzy.flight.database.repository;

import ca.tweetzy.flight.database.Callback;
import ca.tweetzy.flight.database.DatabaseConnector;
import ca.tweetzy.flight.database.MySQLConnector;
import ca.tweetzy.flight.database.SQLiteConnector;
import ca.tweetzy.flight.database.query.DeleteQuery;
import ca.tweetzy.flight.database.query.QueryBuilder;
import ca.tweetzy.flight.database.query.SelectQuery;
import org.bukkit.Bukkit;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;

/**
 * Base implementation of Repository interface
 * 
 * @param <T> The entity type
 * @param <ID> The ID type
 */
public class BaseRepository<T, ID> implements Repository<T, ID> {
    
    protected final DatabaseConnector connector;
    protected final String tablePrefix;
    protected final String tableName;
    protected final EntityMapper<T> mapper;
    protected final QueryBuilder queryBuilder;
    
    public BaseRepository(@NotNull DatabaseConnector connector, 
                         @NotNull String tablePrefix, 
                         @NotNull String tableName,
                         @NotNull EntityMapper<T> mapper) {
        this.connector = connector;
        this.tablePrefix = tablePrefix;
        this.tableName = tableName;
        this.mapper = mapper;
        this.queryBuilder = new QueryBuilder(connector, tablePrefix);
    }
    
    @Override
    public void save(@NotNull T entity, @Nullable Callback<T> callback) {
        Map<String, Object> values = mapper.toMap(entity);
        Object id = mapper.getId(entity);
        
        // Use atomic upsert to prevent race conditions
        // MySQL: INSERT ... ON DUPLICATE KEY UPDATE
        // SQLite: INSERT OR REPLACE
        connector.connect(connection -> {
            try {
                String idColumn = mapper.getIdColumn();
                boolean isMySQL = connector instanceof MySQLConnector;
                boolean isSQLite = connector instanceof SQLiteConnector;
                
                // Build column names and placeholders
                List<String> columns = new java.util.ArrayList<>(values.keySet());
                List<String> placeholders = new java.util.ArrayList<>();
                for (int i = 0; i < columns.size(); i++) {
                    placeholders.add("?");
                }
                
                String sql;
                if (isMySQL) {
                    // MySQL: INSERT ... ON DUPLICATE KEY UPDATE
                    sql = "INSERT INTO " + tablePrefix + tableName + " (" + 
                          String.join(", ", columns) + ") VALUES (" + 
                          String.join(", ", placeholders) + ") " +
                          "ON DUPLICATE KEY UPDATE ";
                    List<String> updateClauses = new java.util.ArrayList<>();
                    for (String column : columns) {
                        if (!column.equals(idColumn)) {
                            updateClauses.add(column + " = VALUES(" + column + ")");
                        }
                    }
                    sql += String.join(", ", updateClauses);
                } else if (isSQLite) {
                    // SQLite: INSERT OR REPLACE
                    sql = "INSERT OR REPLACE INTO " + tablePrefix + tableName + " (" + 
                          String.join(", ", columns) + ") VALUES (" + 
                          String.join(", ", placeholders) + ")";
                } else {
                    // Fallback: try update first, then insert if no rows affected
                    String updateSql = "UPDATE " + tablePrefix + tableName + " SET ";
                    List<String> setClauses = new java.util.ArrayList<>();
                    for (String column : columns) {
                        if (!column.equals(idColumn)) {
                            setClauses.add(column + " = ?");
                        }
                    }
                    updateSql += String.join(", ", setClauses);
                    updateSql += " WHERE " + idColumn + " = ?";
                    
                    try (PreparedStatement updateStmt = connection.prepareStatement(updateSql)) {
                        int paramIndex = 1;
                        for (String column : columns) {
                            if (!column.equals(idColumn)) {
                                setParameter(updateStmt, paramIndex++, values.get(column));
                            }
                        }
                        setParameter(updateStmt, paramIndex, id);
                        
                        int affected = updateStmt.executeUpdate();
                        if (affected > 0) {
                            // Update successful
                            if (callback != null) {
                                callback.accept(null, entity);
                            }
                            return;
                        }
                    }
                    
                    // No update occurred, do insert
                    sql = "INSERT INTO " + tablePrefix + tableName + " (" + 
                          String.join(", ", columns) + ") VALUES (" + 
                          String.join(", ", placeholders) + ")";
                }
                
                try (PreparedStatement stmt = connection.prepareStatement(sql)) {
                    int paramIndex = 1;
                    for (String column : columns) {
                        setParameter(stmt, paramIndex++, values.get(column));
                    }
                    
                    stmt.executeUpdate();
                    
                    if (callback != null) {
                        callback.accept(null, entity);
                    }
                }
            } catch (SQLException ex) {
                if (callback != null) {
                    callback.accept(ex, null);
                }
            }
        });
    }
    
    @Override
    public void saveAll(@NotNull Collection<T> entities, @Nullable Callback<Integer> callback) {
        if (entities.isEmpty()) {
            if (callback != null) {
                callback.accept(null, 0);
            }
            return;
        }
        
        connector.connect(connection -> {
            int totalAffected = 0;
            Exception lastException = null;
            
            try {
                connection.setAutoCommit(false);
                
                for (T entity : entities) {
                    Map<String, Object> values = mapper.toMap(entity);
                    Object id = mapper.getId(entity);
                    
                    // Simple existence check - try update first, if no rows affected, insert
                    String updateSql = "UPDATE " + tablePrefix + tableName + " SET ";
                    List<String> setClauses = new java.util.ArrayList<>();
                    for (String column : values.keySet()) {
                        setClauses.add(column + " = ?");
                    }
                    updateSql += String.join(", ", setClauses);
                    updateSql += " WHERE " + mapper.getIdColumn() + " = ?";
                    
                    try (PreparedStatement updateStmt = connection.prepareStatement(updateSql)) {
                        int paramIndex = 1;
                        for (Object value : values.values()) {
                            setParameter(updateStmt, paramIndex++, value);
                        }
                        setParameter(updateStmt, paramIndex, id);
                        
                        int affected = updateStmt.executeUpdate();
                        
                        if (affected == 0) {
                            // No update occurred, do insert
                            String insertSql = "INSERT INTO " + tablePrefix + tableName + " (";
                            insertSql += String.join(", ", values.keySet()) + ") VALUES (";
                            List<String> placeholders = new java.util.ArrayList<>();
                            for (int i = 0; i < values.size(); i++) {
                                placeholders.add("?");
                            }
                            insertSql += String.join(", ", placeholders) + ")";
                            
                            try (PreparedStatement insertStmt = connection.prepareStatement(insertSql)) {
                                paramIndex = 1;
                                for (Object value : values.values()) {
                                    setParameter(insertStmt, paramIndex++, value);
                                }
                                insertStmt.executeUpdate();
                                totalAffected++;
                            }
                        } else {
                            totalAffected += affected;
                        }
                    }
                }
                
                connection.commit();
                
                if (callback != null) {
                    callback.accept(null, totalAffected);
                }
            } catch (Exception ex) {
                lastException = ex;
                try {
                    connection.rollback();
                } catch (SQLException rollbackEx) {
                    Bukkit.getLogger().log(Level.SEVERE, "Batch save rollback failed", rollbackEx);
                }
            } finally {
                try {
                    connection.setAutoCommit(true);
                } catch (SQLException ex) {
                    Bukkit.getLogger().log(Level.SEVERE, "Failed to restore auto-commit after batch save", ex);
                }
            }
            
            if (lastException != null && callback != null) {
                callback.accept(lastException, null);
            }
        });
    }
    
    @Override
    public void findById(@NotNull ID id, @NotNull Callback<T> callback) {
        SelectQuery selectQuery = queryBuilder.select(tableName)
            .where(mapper.getIdColumn(), id);
        selectQuery.fetchFirst(rs -> {
            try {
                return mapper.map(rs);
            } catch (SQLException ex) {
                throw new RuntimeException(ex);
            }
        }, callback);
    }
    
    @Override
    public void findAll(@NotNull Callback<List<T>> callback) {
        queryBuilder.select(tableName)
            .fetch(rs -> {
                try {
                    return mapper.map(rs);
                } catch (SQLException ex) {
                    throw new RuntimeException(ex);
                }
            }, callback);
    }
    
    @Override
    public void deleteById(@NotNull ID id, @Nullable Callback<Boolean> callback) {
        DeleteQuery deleteQuery = queryBuilder.delete(tableName)
            .where(mapper.getIdColumn(), id);
        deleteQuery.execute((ex, affectedRows) -> {
            if (callback != null) {
                callback.accept(ex, affectedRows != null && affectedRows > 0);
            }
        });
    }
    
    @Override
    @SuppressWarnings("unchecked")
    public void delete(@NotNull T entity, @Nullable Callback<Boolean> callback) {
        Object id = mapper.getId(entity);
        deleteById((ID) id, callback);
    }
    
    @Override
    public void deleteAll(@NotNull Collection<T> entities, @Nullable Callback<Integer> callback) {
        if (entities.isEmpty()) {
            if (callback != null) {
                callback.accept(null, 0);
            }
            return;
        }
        
        connector.connect(connection -> {
            int totalDeleted = 0;
            Exception lastException = null;
            
            try {
                connection.setAutoCommit(false);
                String sql = "DELETE FROM " + tablePrefix + tableName + " WHERE " + mapper.getIdColumn() + " = ?";
                
                try (PreparedStatement statement = connection.prepareStatement(sql)) {
                    for (T entity : entities) {
                        Object id = mapper.getId(entity);
                        setParameter(statement, 1, id);
                        statement.addBatch();
                    }
                    
                    int[] results = statement.executeBatch();
                    for (int result : results) {
                        totalDeleted += result;
                    }
                }
                
                connection.commit();
                
                if (callback != null) {
                    callback.accept(null, totalDeleted);
                }
            } catch (Exception ex) {
                lastException = ex;
                try {
                    connection.rollback();
                } catch (SQLException rollbackEx) {
                    Bukkit.getLogger().log(Level.SEVERE, "Batch delete rollback failed", rollbackEx);
                }
            } finally {
                try {
                    connection.setAutoCommit(true);
                } catch (SQLException ex) {
                    Bukkit.getLogger().log(Level.SEVERE, "Failed to restore auto-commit after batch delete", ex);
                }
            }
            
            if (lastException != null && callback != null) {
                callback.accept(lastException, null);
            }
        });
    }
    
    @Override
    public void existsById(@NotNull ID id, @NotNull Callback<Boolean> callback) {
        SelectQuery selectQuery = queryBuilder.select(tableName)
            .columns(mapper.getIdColumn())
            .where(mapper.getIdColumn(), id);
        selectQuery.fetchFirst(rs -> {
            try {
                return true; // If we get a result, it exists
            } catch (Exception ex) {
                throw new RuntimeException(ex);
            }
        }, (ex, result) -> {
            if (callback != null) {
                callback.accept(ex, result != null);
            }
        });
    }
    
    @Override
    public void count(@NotNull Callback<Long> callback) {
        queryBuilder.select(tableName)
            .columns("COUNT(*) as count")
            .fetchFirst(rs -> {
                try {
                    if (rs.next()) {
                        return rs.getLong("count");
                    }
                    return 0L;
                } catch (SQLException ex) {
                    throw new RuntimeException(ex);
                }
            }, callback);
    }
    
    private void setParameter(PreparedStatement statement, int index, Object value) throws SQLException {
        if (value == null) {
            statement.setNull(index, java.sql.Types.NULL);
        } else if (value instanceof String) {
            statement.setString(index, (String) value);
        } else if (value instanceof Integer) {
            statement.setInt(index, (Integer) value);
        } else if (value instanceof Long) {
            statement.setLong(index, (Long) value);
        } else if (value instanceof Double) {
            statement.setDouble(index, (Double) value);
        } else if (value instanceof Float) {
            statement.setFloat(index, (Float) value);
        } else if (value instanceof Boolean) {
            statement.setBoolean(index, (Boolean) value);
        } else if (value instanceof byte[]) {
            statement.setBytes(index, (byte[]) value);
        } else {
            statement.setObject(index, value);
        }
    }
}

