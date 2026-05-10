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

import ca.tweetzy.flight.utils.Common;
import lombok.NonNull;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandMap;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;

/**
 * Brigadier command manager for Minecraft 1.13+
 * Provides modern command registration with better tab completion
 * 
 * Note: Only works on Minecraft 1.13+ servers
 */
public final class BrigadierCommandManager {

    private static final boolean BRIGADIER_AVAILABLE;
    
    static {
        boolean available = false;
        try {
            Class.forName("com.mojang.brigadier.CommandDispatcher");
            available = true;
        } catch (ClassNotFoundException | NoClassDefFoundError e) {
            available = false;
        }
        BRIGADIER_AVAILABLE = available;
    }

    private final JavaPlugin plugin;
    private final Map<String, Command> knownCommands;

    public BrigadierCommandManager(@NonNull JavaPlugin plugin) {
        this.plugin = plugin;
        
        if (!BRIGADIER_AVAILABLE) {
            Common.log("&cBrigadier is not available on this server (missing com.mojang.brigadier).");
            this.knownCommands = null;
            return;
        }
        
        this.knownCommands = getKnownCommands();
    }

    /**
     * Check if Brigadier is available on this server
     */
    public static boolean isAvailable() {
        return BRIGADIER_AVAILABLE;
    }

    /**
     * Register a command with Brigadier support
     * This provides better tab completion and command suggestions
     */
    public void registerBrigadierCommand(@NonNull String commandName, @NonNull Command command) {
        if (!BRIGADIER_AVAILABLE) {
            Common.log("&cCannot register Brigadier command: Server version too old (requires 1.13+)");
            return;
        }

        try {
            // Get the server's command map
            Object server = Bukkit.getServer();
            Method getCommandMap = server.getClass().getMethod("getCommandMap");
            Object commandMap = getCommandMap.invoke(server);

            // Register the command
            Method register = commandMap.getClass().getMethod("register", String.class, String.class, Command.class);
            register.invoke(commandMap, plugin.getName().toLowerCase(), commandName, command);

            Common.log("&aRegistered Brigadier command: /" + commandName);
        } catch (Exception e) {
            Common.log("&cFailed to register Brigadier command: " + e.getMessage());
            plugin.getLogger().log(Level.SEVERE, "Failed to register Brigadier command", e);
        }
    }

    /**
     * Unregister a Brigadier command
     */
    public void unregisterBrigadierCommand(@NonNull String commandName) {
        if (!BRIGADIER_AVAILABLE || knownCommands == null) {
            return;
        }

        Command command = knownCommands.remove(commandName.toLowerCase());
        if (command != null) {
            Object commandMap = getCommandMap();
            if (commandMap != null) {
                try {
                    command.unregister((org.bukkit.command.CommandMap) commandMap);
                    Common.log("&aUnregistered Brigadier command: /" + commandName);
                } catch (Exception e) {
                    Common.log("&cFailed to unregister command: " + e.getMessage());
                }
            }
        }
    }

    /**
     * Get the command map using reflection
     */
    private Object getCommandMap() {
        try {
            Object server = Bukkit.getServer();
            Method getCommandMap = server.getClass().getMethod("getCommandMap");
            return getCommandMap.invoke(server);
        } catch (Exception e) {
            Common.log("&cFailed to get command map: " + e.getMessage());
            return null;
        }
    }

    /**
     * Get known commands map using reflection
     */
    @SuppressWarnings("unchecked")
    private Map<String, Command> getKnownCommands() {
        try {
            Object commandMap = getCommandMap();
            if (commandMap == null) {
                return null;
            }

            try {
                Method getKnownCommands = commandMap.getClass().getMethod("getKnownCommands");
                return (Map<String, Command>) getKnownCommands.invoke(commandMap);
            } catch (NoSuchMethodException e) {
                // Fallback to field access
                Field knownCommandsField = commandMap.getClass().getDeclaredField("knownCommands");
                knownCommandsField.setAccessible(true);
                return (Map<String, Command>) knownCommandsField.get(commandMap);
            }
        } catch (Exception e) {
            Common.log("&cFailed to get known commands: " + e.getMessage());
            return null;
        }
    }

    /**
     * Create a Brigadier-compatible command with better tab completion
     */
    public Command createBrigadierCommand(@NonNull String name, 
                                          @NonNull String description,
                                          @NonNull String permission,
                                          @NonNull List<String> aliases,
                                          @NonNull BrigadierCommandExecutor executor,
                                          @NonNull BrigadierTabCompleter tabCompleter) {
        if (!BRIGADIER_AVAILABLE) {
            return null;
        }

        return new Command(name, description, "/" + name, new ArrayList<>(aliases)) {
            @Override
            public boolean execute(@NonNull CommandSender sender, @NonNull String commandLabel, @NonNull String[] args) {
                if (!permission.isEmpty() && !sender.hasPermission(permission)) {
                    sender.sendMessage("§cYou do not have permission to use this command.");
                    return false;
                }

                return executor.execute(sender, commandLabel, args);
            }

            @Override
            public @NonNull List<String> tabComplete(@NonNull CommandSender sender, @NonNull String alias, @NonNull String[] args) {
                List<String> completions = tabCompleter.onTabComplete(sender, alias, args);
                return completions != null ? completions : super.tabComplete(sender, alias, args);
            }
        };
    }

    /**
     * Functional interface for Brigadier command execution
     */
    @FunctionalInterface
    public interface BrigadierCommandExecutor {
        boolean execute(@NonNull CommandSender sender, @NonNull String commandLabel, @NonNull String[] args);
    }

    /**
     * Functional interface for Brigadier tab completion
     */
    @FunctionalInterface
    public interface BrigadierTabCompleter {
        List<String> onTabComplete(@NonNull CommandSender sender, @NonNull String alias, @NonNull String[] args);
    }
}

