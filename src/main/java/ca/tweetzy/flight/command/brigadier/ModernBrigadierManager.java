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

package ca.tweetzy.flight.command.brigadier;

import ca.tweetzy.flight.comp.enums.ServerVersion;
import ca.tweetzy.flight.utils.Common;
import lombok.NonNull;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.logging.Level;

/**
 * Modern Brigadier command manager with true CommandDispatcher integration.
 * Flight targets Minecraft 1.16+; NMS types must be present on the server.
 */
public final class ModernBrigadierManager {

    private static final boolean BRIGADIER_AVAILABLE;
    private static Class<?> COMMAND_DISPATCHER_CLASS;
    private static Class<?> COMMAND_SOURCE_STACK_CLASS;
    private static Class<?> COMMANDS_CLASS;
    private static Class<?> LITERAL_ARGUMENT_BUILDER_CLASS;
    private static Class<?> REQUIRED_ARGUMENT_BUILDER_CLASS;
    
    static {
        boolean available = false;
        try {
            COMMAND_DISPATCHER_CLASS = Class.forName("com.mojang.brigadier.CommandDispatcher");
            COMMAND_SOURCE_STACK_CLASS = Class.forName("net.minecraft.commands.CommandSourceStack");
            COMMANDS_CLASS = Class.forName("net.minecraft.commands.Commands");
            LITERAL_ARGUMENT_BUILDER_CLASS = Class.forName("com.mojang.brigadier.builder.LiteralArgumentBuilder");
            REQUIRED_ARGUMENT_BUILDER_CLASS = Class.forName("com.mojang.brigadier.builder.RequiredArgumentBuilder");
            available = ServerVersion.isServerVersionAtLeast(ServerVersion.V1_13);
        } catch (ClassNotFoundException | NoClassDefFoundError e) {
            available = false;
        }
        BRIGADIER_AVAILABLE = available;
    }

    private final JavaPlugin plugin;
    private Object commandDispatcher;
    private Object commandSourceStack;

    public ModernBrigadierManager(@NonNull JavaPlugin plugin) {
        this.plugin = plugin;
        
        if (!BRIGADIER_AVAILABLE) {
            Common.log("&cModern Brigadier is not available (missing Brigadier or NMS command types).");
            return;
        }
        
        initializeBrigadier();
    }

    /**
     * Check if modern Brigadier is available
     */
    public static boolean isAvailable() {
        return BRIGADIER_AVAILABLE;
    }

    /**
     * Initialize Brigadier components using reflection
     */
    private void initializeBrigadier() {
        try {
            // Get the Minecraft server
            Object server = Bukkit.getServer();
            Method getServer = server.getClass().getMethod("getServer");
            Object minecraftServer = getServer.invoke(server);
            
            // Get CommandDispatcher
            Method getCommands = minecraftServer.getClass().getMethod("getCommands");
            Object commands = getCommands.invoke(minecraftServer);
            
            Field dispatcherField = commands.getClass().getDeclaredField("dispatcher");
            dispatcherField.setAccessible(true);
            this.commandDispatcher = dispatcherField.get(commands);
            
            Common.log("&aModern Brigadier initialized successfully!");
        } catch (Exception e) {
            Common.log("&cFailed to initialize Modern Brigadier: " + e.getMessage());
            plugin.getLogger().log(Level.SEVERE, "Failed to initialize Modern Brigadier", e);
        }
    }

    /**
     * Create a modern Brigadier command builder
     */
    public BrigadierCommandBuilder command(@NonNull String name) {
        if (!BRIGADIER_AVAILABLE) {
            throw new UnsupportedOperationException("Brigadier is not available on this server version");
        }
        return new BrigadierCommandBuilder(this, name);
    }

    /**
     * Register a command with the CommandDispatcher
     */
    void registerCommand(Object commandNode) {
        if (commandDispatcher == null) {
            Common.log("&cCannot register command: CommandDispatcher not initialized");
            return;
        }

        try {
            Method register = COMMAND_DISPATCHER_CLASS.getMethod("register", Object.class);
            register.invoke(commandDispatcher, commandNode);
            Common.log("&aRegistered modern Brigadier command");
        } catch (Exception e) {
            Common.log("&cFailed to register command: " + e.getMessage());
            plugin.getLogger().log(Level.SEVERE, "Failed to register modern Brigadier command node", e);
        }
    }

    JavaPlugin getPlugin() {
        return plugin;
    }

    /**
     * Get the CommandDispatcher instance
     */
    Object getCommandDispatcher() {
        return commandDispatcher;
    }

    /**
     * Execute command asynchronously with callback
     */
    public void executeAsync(@NonNull Runnable task, @NonNull Consumer<Throwable> errorHandler) {
        if (plugin == null) {
            return;
        }

        CompletableFuture.runAsync(() -> {
            try {
                task.run();
            } catch (Exception e) {
                errorHandler.accept(e);
            }
        });
    }

    /**
     * Execute command asynchronously and run callback on main thread
     */
    public void executeAsyncThenSync(@NonNull Runnable asyncTask, 
                                     @NonNull Runnable syncCallback,
                                     @NonNull Consumer<Throwable> errorHandler) {
        if (plugin == null) {
            return;
        }

        CompletableFuture.runAsync(() -> {
            try {
                asyncTask.run();
                // Schedule sync callback on main thread
                Bukkit.getScheduler().runTask(plugin, syncCallback);
            } catch (Exception e) {
                Bukkit.getScheduler().runTask(plugin, () -> errorHandler.accept(e));
            }
        });
    }

    /**
     * Convert CommandSender to CommandSourceStack (for Brigadier)
     */
    Object getCommandSourceStack(@NonNull CommandSender sender) {
        try {
            // This is a simplified version - full implementation would need proper NMS access
            // For now, we'll use a wrapper approach
            if (sender instanceof Player) {
                Player player = (Player) sender;
                // Get CraftPlayer and access NMS
                Object craftPlayer = player.getClass().getMethod("getHandle").invoke(player);
                // Get CommandSourceStack from player
                Method getCommandSource = craftPlayer.getClass().getMethod("createCommandSourceStack");
                return getCommandSource.invoke(craftPlayer);
            } else {
                // Console command source
                Object server = Bukkit.getServer();
                Method getServer = server.getClass().getMethod("getServer");
                Object minecraftServer = getServer.invoke(server);
                Method getServerCommandSource = minecraftServer.getClass().getMethod("createCommandSourceStack");
                return getServerCommandSource.invoke(minecraftServer);
            }
        } catch (Exception e) {
            Common.log("&cFailed to get CommandSourceStack: " + e.getMessage());
            return null;
        }
    }
}

