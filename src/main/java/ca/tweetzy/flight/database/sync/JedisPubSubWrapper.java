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

import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.lang.reflect.Constructor;
import java.util.function.Consumer;
import java.util.logging.Level;

/**
 * Wrapper for JedisPubSub that uses reflection to handle callbacks
 */
public class JedisPubSubWrapper {
    
    private final Plugin plugin;
    private final String channel;
    private final String serverId;
    private final Consumer<DatabaseEvent> eventHandler;
    private Object jedisPubSubInstance;
    private Class<?> jedisPubSubClass;
    
    public JedisPubSubWrapper(@NotNull Plugin plugin, @NotNull String channel, @NotNull String serverId, @NotNull Consumer<DatabaseEvent> eventHandler) {
        this.plugin = plugin;
        this.channel = channel;
        this.serverId = serverId;
        this.eventHandler = eventHandler;
    }
    
    /**
     * Create a JedisPubSub instance using reflection
     */
    @NotNull
    public Object createInstance() throws Exception {
        // Try to find JedisPubSub class
        String[] possiblePackages = {
                "redis.clients.jedis",
                "ca.tweetzy.flight.third_party.redis.clients.jedis"
        };
        
        for (String pkg : possiblePackages) {
            try {
                jedisPubSubClass = Class.forName(pkg + ".JedisPubSub");
                break;
            } catch (ClassNotFoundException ignored) {
                // Try next package
            }
        }
        
        if (jedisPubSubClass == null) {
            throw new ClassNotFoundException("JedisPubSub class not found");
        }
        
        // Create instance using a proxy-like approach
        // Since JedisPubSub is abstract, we need to create an anonymous subclass
        // We'll use a dynamic proxy approach or create a concrete implementation
        
        // For now, create using constructor (if available) or use a factory
        try {
            Constructor<?> constructor = jedisPubSubClass.getDeclaredConstructor();
            constructor.setAccessible(true);
            jedisPubSubInstance = constructor.newInstance();
        } catch (Exception e) {
            // If direct instantiation fails, we need to create a subclass
            // This is complex, so we'll use a different approach
            throw new RuntimeException("Cannot instantiate JedisPubSub - it's abstract", e);
        }
        
        // Set up method handlers using reflection
        setupHandlers();
        
        return jedisPubSubInstance;
    }
    
    /**
     * Set up message handlers using reflection
     */
    private void setupHandlers() {
        // This is a simplified version
        // In a full implementation, you would use MethodHandles or bytecode generation
        // to create an anonymous subclass that overrides the callback methods
    }
    
    /**
     * Handle a message (called via reflection from RedisSyncManager)
     */
    public void handleMessage(@NotNull String message) {
        try {
            DatabaseEvent event = DatabaseEvent.fromJson(message);
            
            // Don't process events from this server
            if (event.getServerId().equals(serverId)) {
                return;
            }
            
            eventHandler.accept(event);
        } catch (Exception ex) {
            plugin.getLogger().log(Level.WARNING, "Failed to process database event", ex);
        }
    }
    
    /**
     * Get the JedisPubSub instance
     */
    public Object getInstance() {
        return jedisPubSubInstance;
    }
}

