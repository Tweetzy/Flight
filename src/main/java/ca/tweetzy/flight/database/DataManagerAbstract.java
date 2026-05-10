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

import ca.tweetzy.flight.database.annotations.Table;
import ca.tweetzy.flight.database.query.QueryBuilder;
import ca.tweetzy.flight.database.repository.AnnotatedEntityMapper;
import ca.tweetzy.flight.database.repository.BaseRepository;
import ca.tweetzy.flight.database.repository.EntityMapper;
import ca.tweetzy.flight.database.repository.Repository;
import ca.tweetzy.flight.database.schema.SchemaManager;
import ca.tweetzy.flight.database.sync.DatabaseEvent;
import ca.tweetzy.flight.database.sync.DatabaseEventListener;
import ca.tweetzy.flight.database.sync.RedisLockManager;
import ca.tweetzy.flight.database.sync.RedisSyncManager;
import ca.tweetzy.flight.dependency.DependencyLoader;
import ca.tweetzy.flight.dependency.RuntimeDependencies;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.logging.Level;

/**
 * Coordinates database access, optional Redis sync, and a single-worker async queue for tasks.
 * <p>
 * {@link DatabaseConnector#connect} obtains a pooled {@link java.sql.Connection} for the duration of each callback;
 * do not retain or share {@code Connection} instances across {@link #runAsync} tasks or threads. The async executor
 * only serializes when work is submitted through it; misuse of connections outside that contract remains unsafe.
 */
public class DataManagerAbstract {
    protected final DatabaseConnector databaseConnector;
    protected final Plugin plugin;

    private final ThreadPoolExecutor asyncPool;
    
    private QueryBuilder queryBuilder;
    private RedisSyncManager redisSyncManager;
    private RedisLockManager redisLockManager;
    private SchemaManager schemaManager;
    private final Map<Class<?>, Repository<?, ?>> repositories = new ConcurrentHashMap<>();

    @Deprecated
    private static final Map<String, LinkedList<Runnable>> queues = new HashMap<>();

    public DataManagerAbstract(DatabaseConnector databaseConnector, Plugin plugin) {
        this.databaseConnector = databaseConnector;
        this.plugin = plugin;
        this.asyncPool = new ThreadPoolExecutor(
                1,
                1,
                0L,
                TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(),
                runnable -> new Thread(runnable, plugin.getName() + "-Flight-DB")
        );
    }

    /**
     * @return the prefix to be used by all table names
     */
    public String getTablePrefix() {
        return this.plugin.getDescription().getName().toLowerCase() + '_';
    }

    /**
     * Deprecated because it is often times not accurate to its use case. (+race-conditions)
     */
    @Deprecated
    protected int lastInsertedId(Connection connection) {
        return lastInsertedId(connection, null);
    }

    /**
     * Deprecated because it is often times not accurate to its use case. (+race-conditions)
     */
    @Deprecated
    protected int lastInsertedId(Connection connection, String table) {
        String select = "SELECT * FROM " + this.getTablePrefix() + table + " ORDER BY id DESC LIMIT 1";
        String query;
        if (this.databaseConnector instanceof SQLiteConnector) {
            query = table == null ? "SELECT last_insert_rowid()" : select;
        } else {
            query = table == null ? "SELECT LAST_INSERT_ID()" : select;
        }

        int id = -1;
        try (Statement statement = connection.createStatement()) {
            ResultSet result = statement.executeQuery(query);
            result.next();
            id = result.getInt(1);
        } catch (SQLException ex) {
            this.plugin.getLogger().log(Level.SEVERE, "lastInsertedId query failed", ex);
        }

        return id;
    }

    /**
     * Queue a task to be run asynchronously. <br>
     *
     * @param runnable task to run
     */
    @Deprecated
    public void async(Runnable runnable) {
        Bukkit.getScheduler().runTaskAsynchronously(this.plugin, runnable);
    }

    /**
     * Queue a task to be run synchronously.
     *
     * @param runnable task to run on the next server tick
     */
    public void sync(Runnable runnable) {
        Bukkit.getScheduler().runTask(this.plugin, runnable);
    }

    public void runAsync(Runnable runnable) {
        runAsync(runnable, null);
    }

    /**
     * Runs work on a single background thread. Combine with {@link DatabaseConnector#connect} inside the task so each
     * unit of work uses its own short-lived connection from the pool.
     */
    public void runAsync(Runnable task, Consumer<Throwable> callback) {
        this.asyncPool.execute(() -> {
            try {
                task.run();

                if (callback != null) {
                    callback.accept(null);
                }
            } catch (Throwable th) {
                if (callback != null) {
                    callback.accept(th);
                    return;
                }

                this.plugin.getLogger().log(Level.SEVERE, "Async database task failed", th);
            }
        });
    }

    public void shutdownTaskQueue() {
        this.asyncPool.shutdown();
    }

    public List<Runnable> forceShutdownTaskQueue() {
        return this.asyncPool.shutdownNow();
    }

    public boolean isTaskQueueTerminated() {
        return this.asyncPool.isTerminated();
    }

    /**
     * Approximate number of async tasks not yet finished: queued plus any currently executing on the worker thread.
     */
    public long getTaskQueueSize() {
        long queued = this.asyncPool.getQueue().size();
        long active = this.asyncPool.getActiveCount();
        return queued + Math.min(active, 1);
    }

    /**
     * @see ExecutorService#awaitTermination(long, TimeUnit)
     */
    public boolean waitForShutdown(long timeout, TimeUnit unit) throws InterruptedException {
        return this.asyncPool.awaitTermination(timeout, unit);
    }

    /**
     * Queue tasks to be run asynchronously.
     *
     * @param runnable task to put into queue.
     * @param queueKey the queue key to add the runnable to.
     */
    @Deprecated
    public void queueAsync(Runnable runnable, String queueKey) {
        if (queueKey == null) {
            return;
        }

        List<Runnable> queue = queues.computeIfAbsent(queueKey, t -> new LinkedList<>());
        queue.add(runnable);

        if (queue.size() == 1) {
            runQueue(queueKey);
        }
    }

    @Deprecated
    private void runQueue(String queueKey) {
        doQueue(queueKey, (s) -> {
            if (!queues.get(queueKey).isEmpty()) {
                runQueue(queueKey);
            }
        });
    }

    @Deprecated
    private void doQueue(String queueKey, Consumer<Boolean> callback) {
        Runnable runnable = queues.get(queueKey).getFirst();

        async(() -> {
            runnable.run();

            sync(() -> {
                queues.get(queueKey).remove(runnable);
                callback.accept(true);
            });
        });
    }
    
    /**
     * Get the QueryBuilder instance for this data manager
     * 
     * @return The QueryBuilder instance
     */
    @NotNull
    public QueryBuilder getQueryBuilder() {
        if (queryBuilder == null) {
            queryBuilder = new QueryBuilder(databaseConnector, getTablePrefix());
        }
        return queryBuilder;
    }
    
    /**
     * Get or create a repository for the given entity type
     * 
     * @param entityClass The entity class
     * @param tableName The table name (without prefix)
     * @param mapper The entity mapper
     * @return The repository instance
     */
    @NotNull
    @SuppressWarnings("unchecked")
    public <T, ID> Repository<T, ID> getRepository(@NotNull Class<T> entityClass,
                                                     @NotNull String tableName,
                                                     @NotNull EntityMapper<T> mapper) {
        return (Repository<T, ID>) repositories.computeIfAbsent(entityClass, k -> 
            new BaseRepository<>(databaseConnector, getTablePrefix(), tableName, mapper)
        );
    }
    
    /**
     * Get or create a repository for an annotated entity class
     * Automatically initializes schema and creates AnnotatedEntityMapper
     * 
     * @param entityClass The annotated entity class
     * @param pluginVersion The current plugin version (e.g., "2.0.0")
     * @return The repository instance
     */
    @NotNull
    @SuppressWarnings("unchecked")
    public <T, ID> Repository<T, ID> getRepository(@NotNull Class<T> entityClass, @NotNull String pluginVersion) {
        // Initialize schema if not already done
        if (schemaManager == null) {
            schemaManager = new SchemaManager(databaseConnector, getTablePrefix(), plugin);
        }
        
        // Initialize table schema
        schemaManager.initializeTable(entityClass, pluginVersion);
        
        // Create annotated mapper
        AnnotatedEntityMapper<T> mapper = new AnnotatedEntityMapper<>(entityClass);
        
        // Get table name from annotation
        Table tableAnnotation = 
            entityClass.getAnnotation(Table.class);
        if (tableAnnotation == null) {
            throw new IllegalArgumentException("Entity class " + entityClass.getName() + " must be annotated with @Table");
        }
        String tableName = tableAnnotation.value();
        
        return (Repository<T, ID>) repositories.computeIfAbsent(entityClass, k -> 
            new BaseRepository<>(databaseConnector, getTablePrefix(), tableName, mapper)
        );
    }
    
    /**
     * Get the schema manager
     */
    @Nullable
    public SchemaManager getSchemaManager() {
        return schemaManager;
    }
    
    /**
     * Initialize schema for an entity class manually
     */
    public void initializeSchema(@NotNull Class<?> entityClass, @NotNull String pluginVersion) {
        if (schemaManager == null) {
            schemaManager = new SchemaManager(databaseConnector, getTablePrefix(), plugin);
        }
        schemaManager.initializeTable(entityClass, pluginVersion);
    }
    
    /**
     * Initialize Redis sync manager (optional)
     * 
     * @param host Redis host
     * @param port Redis port
     * @param password Redis password (null if no password)
     * @param channel Redis pub/sub channel name
     * @return true if initialization was successful
     */
    public boolean initializeRedisSync(@NotNull String host, int port, @Nullable String password, @NotNull String channel) {
        if (redisSyncManager != null) {
            plugin.getLogger().warning("Redis sync manager already initialized");
            return redisSyncManager.isEnabled();
        }

        new DependencyLoader(plugin).loadDependencies(Collections.singleton(RuntimeDependencies.jedis()));

        redisSyncManager = new RedisSyncManager(plugin, channel);
        boolean success = redisSyncManager.initialize(host, port, password);
        
        if (success) {
            // Initialize lock manager
            redisLockManager = new RedisLockManager(plugin, redisSyncManager);
            plugin.getLogger().info("Redis sync manager initialized for multi-server support");
        } else {
            plugin.getLogger().warning("Redis sync manager initialization failed - multi-server sync disabled");
        }
        
        return success;
    }
    
    /**
     * Get the Redis sync manager (may be null if not initialized)
     * 
     * @return The Redis sync manager, or null if not initialized
     */
    @Nullable
    public RedisSyncManager getRedisSyncManager() {
        return redisSyncManager;
    }
    
    /**
     * Get the Redis lock manager (may be null if Redis sync is not initialized)
     * 
     * @return The Redis lock manager, or null if not initialized
     */
    @Nullable
    public RedisLockManager getRedisLockManager() {
        return redisLockManager;
    }
    
    /**
     * Register a database event listener
     * 
     * @param listener The listener to register
     */
    public void registerDatabaseEventListener(@NotNull DatabaseEventListener listener) {
        if (redisSyncManager != null && redisSyncManager.isEnabled()) {
            redisSyncManager.registerListener(listener);
        }
    }
    
    /**
     * Unregister a database event listener
     * 
     * @param listener The listener to unregister
     */
    public void unregisterDatabaseEventListener(@NotNull DatabaseEventListener listener) {
        if (redisSyncManager != null) {
            redisSyncManager.unregisterListener(listener);
        }
    }
    
    /**
     * Publish a database event to other servers (if Redis sync is enabled)
     * 
     * @param eventType The event type (INSERT, UPDATE, DELETE)
     * @param tableName The table name (without prefix)
     * @param data The data map (column name -> value)
     */
    public void publishDatabaseEvent(@NotNull DatabaseEvent.EventType eventType,
                                      @NotNull String tableName,
                                      @NotNull Map<String, Object> data) {
        if (redisSyncManager != null && redisSyncManager.isEnabled()) {
            DatabaseEvent event = new DatabaseEvent(
                redisSyncManager.getServerId(),
                eventType,
                tableName,
                getTablePrefix(),
                data
            );
            redisSyncManager.publishEvent(event);
        }
    }
    
    /**
     * Shutdown the data manager and cleanup resources
     */
    public void shutdown() {
        shutdownTaskQueue();
        
        if (redisSyncManager != null) {
            redisSyncManager.shutdown();
        }
        
        repositories.clear();
    }
}
