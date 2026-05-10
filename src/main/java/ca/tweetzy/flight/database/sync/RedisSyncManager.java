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

package ca.tweetzy.flight.database.sync;

import org.apache.commons.pool2.impl.GenericObjectPoolConfig;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPubSub;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;

/**
 * Manages Redis-based database synchronization for multi-server setups
 */
public class RedisSyncManager {
    
    private final Plugin plugin;
    private final String serverId;
    private final String channel;
    private JedisPool jedisPool;
    private JedisPubSub pubSub;
    private final List<DatabaseEventListener> listeners = new ArrayList<>();
    private final ExecutorService executorService = Executors.newCachedThreadPool();
    private boolean enabled = false;
    private Thread subscriberThread;
    private Thread healthCheckThread;
    private int reconnectAttempts = 0;
    private static final long HEALTH_CHECK_INTERVAL = 30000; // 30 seconds
    private static final int MAX_RECONNECT_ATTEMPTS = 5;
    
    public RedisSyncManager(@NotNull Plugin plugin, @NotNull String channel) {
        this.plugin = plugin;
        this.serverId = UUID.randomUUID().toString();
        this.channel = channel;
    }
    
    /**
     * Initialize Redis connection
     * 
     * @param host Redis host
     * @param port Redis port
     * @param password Redis password (null if no password)
     * @return true if initialization was successful
     */
    public boolean initialize(@NotNull String host, int port, @Nullable String password) {
        // Check if Jedis classes are available at runtime
        // Try multiple classloaders since dependency loader may have loaded them
        ClassLoader[] classLoaders = {
            plugin.getClass().getClassLoader(),
            Thread.currentThread().getContextClassLoader(),
            ClassLoader.getSystemClassLoader()
        };
        
        boolean classesAvailable = false;
        for (ClassLoader classLoader : classLoaders) {
            if (classLoader == null) continue;
            try {
                classLoader.loadClass("org.apache.commons.pool2.impl.GenericObjectPoolConfig");
                classLoader.loadClass("redis.clients.jedis.JedisPool");
                classLoader.loadClass("redis.clients.jedis.Jedis");
                classesAvailable = true;
                break;
            } catch (ClassNotFoundException ignored) {
                // Try next classloader
            }
        }
        
        if (!classesAvailable) {
            plugin.getLogger().warning("Jedis is not available. Redis sync requires Jedis to be loaded. Make sure to call getOptionalDependencies() in your plugin's onLoad().");
            return false;
        }
        
        try {
            // Create GenericObjectPoolConfig for JedisPool
            GenericObjectPoolConfig<Jedis> poolConfig = new GenericObjectPoolConfig<>();
            poolConfig.setMaxTotal(10);
            poolConfig.setMaxIdle(5);
            poolConfig.setMinIdle(1);
            poolConfig.setTestOnBorrow(true);
            poolConfig.setTestOnReturn(true);
            
            // Create JedisPool
            if (password != null && !password.isEmpty()) {
                this.jedisPool = new JedisPool(poolConfig, host, port, 2000, password);
            } else {
                this.jedisPool = new JedisPool(poolConfig, host, port, 2000);
            }
            
            // Test connection
            try (Jedis jedis = jedisPool.getResource()) {
                String result = jedis.ping();
                if (!"PONG".equals(result)) {
                    throw new Exception("Redis ping returned unexpected result: " + result);
                }
            }
            
            this.enabled = true;
            this.reconnectAttempts = 0;
            startSubscriber();
            startHealthCheck();
            
            plugin.getLogger().info("Redis sync manager initialized successfully");
            return true;
        } catch (Exception ex) {
            plugin.getLogger().log(Level.WARNING, "Failed to initialize Redis sync manager", ex);
            this.enabled = false;
            return false;
        }
    }
    
    /**
     * Check if Redis sync is enabled and connected
     */
    public boolean isEnabled() {
        if (!enabled || jedisPool == null) {
            return false;
        }
        
        try {
            return !jedisPool.isClosed();
        } catch (Exception e) {
            return false;
        }
    }
    
    /**
     * Publish a database event to other servers
     */
    public void publishEvent(@NotNull DatabaseEvent event) {
        if (!isEnabled()) {
            return;
        }
        
        CompletableFuture.runAsync(() -> {
            try (Jedis jedis = jedisPool.getResource()) {
                String json = event.toJson();
                jedis.publish(channel, json);
            } catch (Exception ex) {
                plugin.getLogger().log(Level.WARNING, "Failed to publish database event", ex);
            }
        }, executorService);
    }
    
    /**
     * Register a listener for database events
     */
    public void registerListener(@NotNull DatabaseEventListener listener) {
        synchronized (listeners) {
            listeners.add(listener);
        }
    }
    
    /**
     * Unregister a listener
     */
    public void unregisterListener(@NotNull DatabaseEventListener listener) {
        synchronized (listeners) {
            listeners.remove(listener);
        }
    }
    
    /**
     * Start the Redis subscriber thread
     */
    private void startSubscriber() {
        if (subscriberThread != null && subscriberThread.isAlive()) {
            return;
        }
        
        try {
            // Create JedisPubSub proxy that handles messages
            pubSub = createPubSubProxy();
            
            subscriberThread = new Thread(() -> {
                while (enabled && !Thread.currentThread().isInterrupted()) {
                    try (Jedis jedis = jedisPool.getResource()) {
                        jedis.subscribe(pubSub, channel);
                    } catch (Exception ex) {
                        if (enabled) {
                            plugin.getLogger().warning("Redis subscriber error: " + ex.getMessage());
                            try {
                                Thread.sleep(5000); // Wait before reconnecting
                            } catch (InterruptedException ie) {
                                Thread.currentThread().interrupt();
                                break;
                            }
                        }
                    }
                }
            }, "Redis-Subscriber-" + plugin.getName());
            
            subscriberThread.setDaemon(true);
            subscriberThread.start();
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Failed to start Redis subscriber", e);
        }
    }
    
    /**
     * Create a JedisPubSub instance that handles incoming messages
     */
    private JedisPubSub createPubSubProxy() {
        // JedisPubSub is an abstract class, not an interface, so we need to extend it
        return new JedisPubSub() {
            @Override
            public void onMessage(String channel, String message) {
                handleIncomingMessage(channel, message);
            }
        };
    }
    
    /**
     * Handle an incoming message from Redis
     */
    private void handleIncomingMessage(@NotNull String channel, @NotNull String message) {
        if (!channel.equals(this.channel)) {
            return; // Ignore messages from other channels
        }
        
        // Check if plugin is enabled before processing
        if (!enabled) {
            return; // Plugin is disabled, ignore messages
        }
        
        // Check if plugin is still enabled (may have been disabled between check and execution)
        // Wrap in try-catch because isEnabled() might throw if classloader is closed
        boolean pluginEnabled;
        try {
            pluginEnabled = plugin.isEnabled();
        } catch (IllegalStateException | NoClassDefFoundError e) {
            // Classloader closed - plugin is disabled/reloading
            return; // Silently ignore
        }
        
        if (!pluginEnabled) {
            return; // Plugin is disabled, ignore messages
        }
        
        executorService.execute(() -> {
            // Double-check plugin state in async thread
            if (!enabled) {
                return; // Plugin was disabled, ignore
            }
            
            // Check plugin state safely
            boolean stillEnabled;
            try {
                stillEnabled = plugin.isEnabled();
            } catch (IllegalStateException | NoClassDefFoundError e) {
                // Classloader closed - silently ignore
                return;
            }
            
            if (!stillEnabled) {
                return; // Plugin was disabled, ignore
            }
            
            try {
                DatabaseEvent event;
                try {
                    event = DatabaseEvent.fromJson(message);
                } catch (IllegalStateException e) {
                    // Classloader closed - plugin likely disabled/reloading
                    // Check if it's a "zip file closed" error - if so, silently ignore
                    String msg = e.getMessage();
                    if (msg != null && (msg.contains("zip file closed") || msg.contains("classloader"))) {
                        return; // Silently ignore classloader closed errors
                    }
                    // For other IllegalStateExceptions, check if plugin is still enabled
                    try {
                        if (plugin.isEnabled()) {
                            plugin.getLogger().warning("Failed to deserialize Redis message: " + msg);
                        }
                    } catch (IllegalStateException | NoClassDefFoundError ignored) {
                        // Classloader closed, silently ignore
                    }
                    return;
                } catch (NoClassDefFoundError e) {
                    // Class not found - classloader likely closed, silently ignore
                    return;
                }
                
                // Don't process events from this server
                if (event.getServerId().equals(serverId)) {
                    return;
                }
                
                // Route event to appropriate listeners
                synchronized (listeners) {
                    for (DatabaseEventListener listener : listeners) {
                        // Check plugin state before each listener
                        if (!enabled) {
                            return; // Plugin was disabled, stop processing
                        }
                        
                        // Check plugin state safely
                        try {
                            if (!plugin.isEnabled()) {
                                return; // Plugin disabled, stop processing
                            }
                        } catch (IllegalStateException | NoClassDefFoundError e) {
                            // Classloader closed, silently ignore
                            return;
                        }
                        
                        try {
                            // Check if listener is interested in this table
                            String listenerTable = listener.getTableName();
                            String listenerPrefix = listener.getTablePrefix();
                            
                            if (listenerTable != null && !listenerTable.equals(event.getTableName())) {
                                continue; // Skip if table doesn't match
                            }
                            
                            if (listenerPrefix != null && !listenerPrefix.equals(event.getTablePrefix())) {
                                continue; // Skip if prefix doesn't match
                            }
                            
                            listener.onDatabaseEvent(event);
                        } catch (IllegalStateException e) {
                            // Plugin classloader closed - check if it's a classloader issue
                            String msg = e.getMessage();
                            if (msg != null && (msg.contains("zip file closed") || msg.contains("classloader"))) {
                                // Silently ignore classloader closed errors
                                continue;
                            }
                            // For other errors, check if plugin is still enabled
                            try {
                                if (plugin.isEnabled()) {
                                    plugin.getLogger().warning("Error in database event listener: " + msg);
                                }
                            } catch (IllegalStateException | NoClassDefFoundError ignored) {
                                // Classloader closed, silently ignore
                            }
                        } catch (NoClassDefFoundError e) {
                            // Classloader issue - silently ignore
                            continue;
                        } catch (Exception ex) {
                            try {
                                if (plugin.isEnabled()) {
                                    plugin.getLogger().log(Level.WARNING, "Error in database event listener", ex);
                                }
                            } catch (IllegalStateException | NoClassDefFoundError ignored) {
                                // Classloader closed, silently ignore
                            }
                        }
                    }
                }
            } catch (IllegalStateException e) {
                // Plugin classloader closed - check if it's a classloader issue
                String msg = e.getMessage();
                if (msg != null && (msg.contains("zip file closed") || msg.contains("classloader"))) {
                    // Silently ignore classloader closed errors
                    return;
                }
                // For other errors, check if plugin is still enabled
                try {
                    if (plugin.isEnabled()) {
                        plugin.getLogger().warning("Failed to process incoming Redis message: " + msg);
                    }
                } catch (IllegalStateException | NoClassDefFoundError ignored) {
                    // Classloader closed, silently ignore
                }
            } catch (NoClassDefFoundError e) {
                // Classloader issue - silently ignore
                return;
            } catch (Exception ex) {
                try {
                    if (plugin.isEnabled()) {
                        plugin.getLogger().log(Level.WARNING, "Failed to process incoming Redis message", ex);
                    }
                } catch (IllegalStateException | NoClassDefFoundError ignored) {
                    // Classloader closed, silently ignore
                }
            }
        });
    }
    
    /**
     * Start health check thread
     */
    private void startHealthCheck() {
        if (healthCheckThread != null && healthCheckThread.isAlive()) {
            return;
        }
        
        healthCheckThread = new Thread(() -> {
            while (enabled && !Thread.currentThread().isInterrupted()) {
                try {
                    Thread.sleep(HEALTH_CHECK_INTERVAL);
                    
                    if (!isEnabled()) {
                        plugin.getLogger().warning("Redis connection lost, attempting to reconnect...");
                        attemptReconnect();
                    } else {
                        reconnectAttempts = 0; // Reset on successful check
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    plugin.getLogger().warning("Health check error: " + e.getMessage());
                }
            }
        }, "Redis-HealthCheck-" + plugin.getName());
        
        healthCheckThread.setDaemon(true);
        healthCheckThread.start();
    }
    
    /**
     * Attempt to reconnect to Redis with exponential backoff
     */
    private void attemptReconnect() {
        if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
            plugin.getLogger().severe("Max reconnection attempts reached. Redis sync disabled.");
            enabled = false;
            return;
        }
        
        reconnectAttempts++;
        long backoffDelay = (long) Math.pow(2, reconnectAttempts) * 1000; // Exponential backoff
        
        try {
            Thread.sleep(backoffDelay);
            
            // Test connection
            try (Jedis jedis = jedisPool.getResource()) {
                String result = jedis.ping();
                if ("PONG".equals(result)) {
                    plugin.getLogger().info("Redis reconnection successful");
                    reconnectAttempts = 0;
                    if (!subscriberThread.isAlive()) {
                        startSubscriber();
                    }
                }
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Reconnection attempt " + reconnectAttempts + " failed: " + e.getMessage());
        }
    }
    
    /**
     * Shutdown the Redis sync manager
     */
    public void shutdown() {
        enabled = false;
        
        if (healthCheckThread != null && healthCheckThread.isAlive()) {
            healthCheckThread.interrupt();
            try {
                healthCheckThread.join(5000);
            } catch (InterruptedException ex) {
                // Ignore
            }
        }
        
        if (pubSub != null) {
            try {
                pubSub.unsubscribe();
            } catch (Exception ex) {
                // Ignore
            }
        }
        
        if (subscriberThread != null && subscriberThread.isAlive()) {
            subscriberThread.interrupt();
            try {
                subscriberThread.join(5000);
            } catch (InterruptedException ex) {
                // Ignore
            }
        }
        
        if (jedisPool != null) {
            try {
                if (!jedisPool.isClosed()) {
                    jedisPool.close();
                }
            } catch (Exception ex) {
                // Ignore
            }
        }
        
        // Shutdown executor service gracefully
        executorService.shutdown();
        try {
            // Wait a bit for tasks to complete, but don't wait forever
            if (!executorService.awaitTermination(2, java.util.concurrent.TimeUnit.SECONDS)) {
                executorService.shutdownNow(); // Force shutdown
            }
        } catch (InterruptedException ex) {
            executorService.shutdownNow();
            Thread.currentThread().interrupt();
        }
        
        plugin.getLogger().info("Redis sync manager shut down");
    }
    
    /**
     * Get the server ID
     */
    @NotNull
    public String getServerId() {
        return serverId;
    }
    
    /**
     * Get the JedisPool (for internal use by RedisLockManager and external plugins)
     * @return The JedisPool instance
     */
    @Nullable
    public JedisPool getJedisPool() {
        return jedisPool;
    }
    
    /**
     * Get Jedis class (for internal use by RedisLockManager and external plugins)
     * @return The Jedis class
     */
    @NotNull
    public Class<Jedis> getJedisClass() {
        return Jedis.class;
    }
}
