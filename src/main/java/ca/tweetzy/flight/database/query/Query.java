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

package ca.tweetzy.flight.database.query;

import ca.tweetzy.flight.database.DatabaseConnector;
import org.bukkit.Bukkit;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.logging.Level;

/**
 * Base class for all query types
 */
public abstract class Query {
    
    protected final DatabaseConnector connector;
    protected final String tablePrefix;
    protected final String table;
    protected final List<WhereClause> whereClauses = new ArrayList<>();
    
    protected Query(@NotNull DatabaseConnector connector, @NotNull String tablePrefix, @NotNull String table) {
        this.connector = connector;
        this.tablePrefix = tablePrefix;
        this.table = table;
    }
    
    /**
     * Add a WHERE clause to the query
     * 
     * @param column The column name
     * @param operator The operator (=, !=, <, >, <=, >=, LIKE, IN, etc.)
     * @param value The value to compare
     * @return This query instance for method chaining
     */
    @SuppressWarnings("unchecked")
    protected <Q extends Query> Q where(@NotNull String column, @NotNull String operator, @Nullable Object value) {
        this.whereClauses.add(new WhereClause(column, operator, value));
        return (Q) this;
    }
    
    /**
     * Add a WHERE clause with = operator
     */
    protected <Q extends Query> Q where(@NotNull String column, @Nullable Object value) {
        return where(column, "=", value);
    }
    
    /**
     * Build the SQL query string
     */
    protected abstract String buildSQL();
    
    /**
     * Set parameters on the prepared statement
     */
    protected abstract void setParameters(PreparedStatement statement) throws SQLException;
    
    /**
     * Execute the query synchronously
     */
    public void execute() {
        this.connector.connect(connection -> {
            String sql = buildSQL();
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                setParameters(statement);
                executeStatement(statement);
            } catch (SQLException ex) {
                throw new RuntimeException("Failed to execute query: " + sql, ex);
            }
        });
    }
    
    /**
     * Execute the query asynchronously
     */
    public void executeAsync() {
        executeAsync(null);
    }
    
    /**
     * Execute the query asynchronously with callback
     */
    public void executeAsync(@Nullable Consumer<Exception> callback) {
        new Thread(() -> {
            try {
                execute();
                if (callback != null) {
                    callback.accept(null);
                }
            } catch (Exception ex) {
                if (callback != null) {
                    callback.accept(ex);
                } else {
                    Bukkit.getLogger().log(Level.SEVERE, "Async query execution failed", ex);
                }
            }
        }).start();
    }
    
    /**
     * Execute the prepared statement (override in subclasses)
     */
    protected abstract void executeStatement(PreparedStatement statement) throws SQLException;
    
    /**
     * Build WHERE clause SQL
     */
    protected String buildWhereClause() {
        if (whereClauses.isEmpty()) {
            return "";
        }
        
        StringBuilder sb = new StringBuilder(" WHERE ");
        for (int i = 0; i < whereClauses.size(); i++) {
            WhereClause clause = whereClauses.get(i);
            if (i > 0) {
                sb.append(" AND ");
            }
            sb.append(clause.column).append(" ").append(clause.operator).append(" ?");
        }
        return sb.toString();
    }
    
    /**
     * Set WHERE clause parameters
     */
    protected void setWhereParameters(PreparedStatement statement, int startIndex) throws SQLException {
        int index = startIndex;
        for (WhereClause clause : whereClauses) {
            if (clause.value == null) {
                statement.setNull(index, java.sql.Types.NULL);
            } else if (clause.value instanceof String) {
                statement.setString(index, (String) clause.value);
            } else if (clause.value instanceof Integer) {
                statement.setInt(index, (Integer) clause.value);
            } else if (clause.value instanceof Long) {
                statement.setLong(index, (Long) clause.value);
            } else if (clause.value instanceof Double) {
                statement.setDouble(index, (Double) clause.value);
            } else if (clause.value instanceof Boolean) {
                statement.setBoolean(index, (Boolean) clause.value);
            } else if (clause.value instanceof java.util.UUID) {
                // Convert UUID to string to avoid serialization issues with MySQL
                statement.setString(index, clause.value.toString());
            } else {
                statement.setObject(index, clause.value);
            }
            index++;
        }
    }
    
    /**
     * Get the full table name with prefix
     */
    protected String getFullTableName() {
        return tablePrefix + table;
    }
    
    /**
     * Internal class for WHERE clauses
     */
    protected static class WhereClause {
        final String column;
        final String operator;
        final Object value;
        
        WhereClause(String column, String operator, Object value) {
            this.column = column;
            this.operator = operator;
            this.value = value;
        }
    }
}

