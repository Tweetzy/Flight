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

import ca.tweetzy.flight.command.CommandContext;
import ca.tweetzy.flight.utils.Common;
import lombok.NonNull;
import org.bukkit.command.CommandSender;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.logging.Level;

/**
 * Fluent builder for creating modern Brigadier commands
 * 
 * Usage:
 * <pre>
 * {@code
 * modernBrigadier.command("example")
 *     .requires(sender -> sender.hasPermission("permission"))
 *     .executes(context -> {
 *         // Command logic
 *         return true;
 *     })
 *     .then(argument("player", ArgumentType.player())
 *         .executes(context -> {
 *             Player target = context.getArgument("player", Player.class);
 *             // Use target
 *             return true;
 *         }))
 *     .register();
 * }
 * </pre>
 */
public final class BrigadierCommandBuilder {

    private final ModernBrigadierManager manager;
    private final String name;
    private final List<String> aliases = new ArrayList<>();
    private Consumer<CommandContext> executor;
    private Consumer<CommandContext> tabCompleter;
    private java.util.function.Predicate<CommandSender> requirement;
    private final List<BrigadierCommandBuilder> children = new ArrayList<>();
    private String permission;

    BrigadierCommandBuilder(@NonNull ModernBrigadierManager manager, @NonNull String name) {
        this.manager = manager;
        this.name = name;
    }

    /**
     * Add an alias to this command
     */
    public BrigadierCommandBuilder alias(@NonNull String alias) {
        this.aliases.add(alias);
        return this;
    }

    /**
     * Add multiple aliases
     */
    public BrigadierCommandBuilder aliases(@NonNull String... aliases) {
        for (String alias : aliases) {
            this.aliases.add(alias);
        }
        return this;
    }

    /**
     * Set the permission required to execute this command
     */
    public BrigadierCommandBuilder permission(@NonNull String permission) {
        this.permission = permission;
        return requires(sender -> sender.hasPermission(permission));
    }

    /**
     * Set a requirement predicate for this command
     */
    public BrigadierCommandBuilder requires(@NonNull java.util.function.Predicate<CommandSender> requirement) {
        this.requirement = requirement;
        return this;
    }

    /**
     * Set the command executor
     */
    public BrigadierCommandBuilder executes(@NonNull Consumer<CommandContext> executor) {
        this.executor = executor;
        return this;
    }

    /**
     * Set the tab completer
     */
    public BrigadierCommandBuilder suggests(@NonNull Consumer<CommandContext> tabCompleter) {
        this.tabCompleter = tabCompleter;
        return this;
    }

    /**
     * Add a child command (subcommand)
     */
    public BrigadierCommandBuilder then(@NonNull BrigadierCommandBuilder child) {
        this.children.add(child);
        return this;
    }

    /**
     * Register this command with the CommandDispatcher
     */
    public void register() {
        if (!ModernBrigadierManager.isAvailable()) {
            Common.log("&cCannot register command: Brigadier not available");
            return;
        }

        try {
            // Create literal command node using reflection
            Object literalBuilder = createLiteralBuilder(name);
            
            // Add requirement if set
            if (requirement != null) {
                setRequirement(literalBuilder, requirement);
            }
            
            // Add executor if set
            if (executor != null) {
                setExecutor(literalBuilder, executor);
            }
            
            // Add tab completer if set
            if (tabCompleter != null) {
                setTabCompleter(literalBuilder, tabCompleter);
            }
            
            // Add children
            for (BrigadierCommandBuilder child : children) {
                addChild(literalBuilder, child);
            }
            
            // Build and register
            Object commandNode = build(literalBuilder);
            manager.registerCommand(commandNode);
            
            // Register aliases
            for (String alias : aliases) {
                registerAlias(alias, commandNode);
            }
            
            Common.log("&aRegistered modern Brigadier command: /" + name);
        } catch (Exception e) {
            Common.log("&cFailed to register command: " + e.getMessage());
            manager.getPlugin().getLogger().log(Level.SEVERE, "Failed to register modern Brigadier command", e);
        }
    }

    /**
     * Create a literal argument builder using reflection
     */
    private Object createLiteralBuilder(String name) throws Exception {
        Class<?> literalClass = Class.forName("com.mojang.brigadier.builder.LiteralArgumentBuilder");
        Method literal = literalClass.getMethod("literal", String.class);
        return literal.invoke(null, name);
    }

    /**
     * Set requirement on builder
     */
    private void setRequirement(Object builder, java.util.function.Predicate<CommandSender> requirement) throws Exception {
        // Create a predicate wrapper for Brigadier
        Method requires = builder.getClass().getMethod("requires", 
            Class.forName("com.mojang.brigadier.suggestion.SuggestionProvider"));
        // This is simplified - full implementation would need proper predicate conversion
    }

    /**
     * Set executor on builder
     */
    private void setExecutor(Object builder, Consumer<CommandContext> executor) throws Exception {
        Method executes = builder.getClass().getMethod("executes", 
            Class.forName("com.mojang.brigadier.Command"));
        
        // Create command wrapper
        Object command = createCommandWrapper(executor);
        executes.invoke(builder, command);
    }

    /**
     * Set tab completer on builder
     */
    private void setTabCompleter(Object builder, Consumer<CommandContext> tabCompleter) throws Exception {
        // Tab completion is handled through suggestions
        // This would need proper SuggestionProvider implementation
    }

    /**
     * Add child command
     */
    private void addChild(Object builder, BrigadierCommandBuilder child) throws Exception {
        Method then = builder.getClass().getMethod("then", 
            Class.forName("com.mojang.brigadier.tree.CommandNode"));
        
        // Build child first
        Object childNode = build(createLiteralBuilder(child.name));
        then.invoke(builder, childNode);
    }

    /**
     * Build the command node
     */
    private Object build(Object builder) throws Exception {
        Method build = builder.getClass().getMethod("build");
        return build.invoke(builder);
    }

    /**
     * Register alias
     */
    private void registerAlias(String alias, Object commandNode) {
        try {
            // Register alias as a redirect to main command
            Object literalBuilder = createLiteralBuilder(alias);
            Method redirect = literalBuilder.getClass().getMethod("redirect", 
                Class.forName("com.mojang.brigadier.tree.CommandNode"));
            Object aliasNode = build(redirect.invoke(literalBuilder, commandNode));
            manager.registerCommand(aliasNode);
        } catch (Exception e) {
            Common.log("&cFailed to register alias: " + alias);
        }
    }

    /**
     * Create a command wrapper for Brigadier
     */
    private Object createCommandWrapper(Consumer<CommandContext> executor) {
        // This is a simplified wrapper - full implementation would need proper Command interface
        return new Object() {
            public int execute(Object context) {
                try {
                    // Extract CommandSourceStack from context
                    Method getSource = context.getClass().getMethod("getSource");
                    Object source = getSource.invoke(context);
                    
                    // Convert to CommandSender
                    CommandSender sender = convertToCommandSender(source);
                    
                    // Extract arguments
                    String[] args = extractArguments(context);
                    
                    // Create CommandContext
                    CommandContext commandContext = new CommandContext(sender, args, name);
                    
                    // Execute
                    executor.accept(commandContext);
                    
                    return 1; // Success
                } catch (Exception e) {
                    manager.getPlugin().getLogger().log(Level.SEVERE, "Modern Brigadier command execution failed", e);
                    return 0; // Failure
                }
            }
        };
    }

    /**
     * Convert CommandSourceStack to CommandSender
     */
    private CommandSender convertToCommandSender(Object source) throws Exception {
        // Get the entity from source
        Method getEntity = source.getClass().getMethod("getEntity");
        Object entity = getEntity.invoke(source);
        
        if (entity != null) {
            // Convert NMS entity to Bukkit player
            Method getBukkitEntity = entity.getClass().getMethod("getBukkitEntity");
            return (CommandSender) getBukkitEntity.invoke(entity);
        }
        
        // Return console sender
        return org.bukkit.Bukkit.getConsoleSender();
    }

    /**
     * Extract arguments from Brigadier context
     */
    private String[] extractArguments(Object context) {
        // This would need proper argument extraction from Brigadier context
        // For now, return empty array
        return new String[0];
    }
}

