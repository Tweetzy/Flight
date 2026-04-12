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

package ca.tweetzy.flight.command;

import ca.tweetzy.flight.comp.enums.ServerProject;
import ca.tweetzy.flight.comp.enums.ServerVersion;
import ca.tweetzy.flight.utils.Common;
import lombok.Setter;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandMap;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Date Created: April 09 2022
 * Time Created: 11:44 p.m.
 *
 * @author Kiran Hart
 */
public final class CommandManager implements CommandExecutor, TabCompleter {

    private final Map<String, NestedCommand> commands = new HashMap<>();

    @Setter
    private String playerOnlyMessage = "&cOnly players can use this command!";

    @Setter
    private String consoleOnlyMessage = "&cThis can only be ran by the console!";

    @Setter
    private String noPermissionMessage = "&cYou do not have permission to use that command!";

    @Setter
    private String unknownCommandMessage = "&cThat command does not exist!";

    @Setter
    private List<String> syntaxErrorMessages = List.of(
            "&8&m-----------------------------------------------------",
            "<center>%pl_name%",
            "<center>&cSeems like you entered that command incorrectly.",
            "",
            "<center>&6<> &f- &7Required arguments",
            "<center>&8[] &f- &7Optional arguments",
            "",
            "<center>&aHere is the correct usage&F:",
            "<center>&f/&e%syntax%",
            "",
            "&8&m-----------------------------------------------------"
    );


    private final JavaPlugin plugin;
    private boolean allowLooseCommands = false;

    public CommandManager(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public Set<String> getCommands() {
        return Collections.unmodifiableSet(commands.keySet());
    }

    public List<String> getSubCommands(String command) {
        final NestedCommand nested = command == null ? null : commands.get(command.toLowerCase());

        if (nested == null) {
            return Collections.emptyList();
        }

        return new ArrayList<>(nested.children.keySet());
    }

    public Set<Command> getAllCommands() {
        final HashSet<Command> all = new HashSet<>();

        commands.values().stream()
                .filter(c -> c.parent != null && !all.contains(c.parent))
                .forEach(c -> {
                    all.add(c.parent);
                    c.children.values().stream().filter(s -> !all.contains(s)).forEach(all::add);
                });

        return all;
    }

    public CommandManager registerCommandDynamically(String command) {
        CommandManager.registerCommandDynamically(plugin, command, this, this);
        return this;
    }

    public NestedCommand registerCommandDynamically(Command command) {
        final NestedCommand nested = new NestedCommand(command);
        
        // Set plugin for async support
        command.setPlugin(plugin);

        command.getSubCommands().forEach(cmd -> {
            CommandManager.registerCommandDynamically(plugin, cmd, this, this);
            commands.put(cmd.toLowerCase(), nested);
            final PluginCommand pluginCommand = plugin.getCommand(cmd);

            if (pluginCommand != null) {
                pluginCommand.setExecutor(this);
                pluginCommand.setTabCompleter(this);
            } else {
                Common.log("&cFailed to register command: &f/&e" + cmd);
            }
        });

        return nested;
    }

    public NestedCommand addCommand(Command command) {
        final NestedCommand nested = new NestedCommand(command);
        
        // Set plugin for async support
        command.setPlugin(plugin);

        command.getSubCommands().forEach(cmd -> {
            commands.put(cmd.toLowerCase(), nested);
            final PluginCommand pluginCommand = plugin.getCommand(cmd);

            if (pluginCommand == null) {
                Common.log("&cFailed to register command: &f/&e" + cmd);
                return;
            }

            pluginCommand.setExecutor(this);
            pluginCommand.setTabCompleter(this);
        });

        return nested;
    }

    public MainCommand addMainCommand(String command) {
        final MainCommand nested = new MainCommand(plugin, command);
        commands.put(command.toLowerCase(), nested.nestedCommands);

        final PluginCommand pluginCommand = plugin.getCommand(command);
        if (pluginCommand != null) {
            pluginCommand.setExecutor(this);
            pluginCommand.setTabCompleter(this);
        } else {
            Common.log("&cFailed to register command: &f/&e" + command);
        }

        return nested;
    }

    public MainCommand getMainCommand(String command) {
        final NestedCommand nested = command == null ? null : commands.get(command.toLowerCase());

        if (nested != null && nested.parent instanceof MainCommand) {
            return (MainCommand) nested.parent;
        }

        return null;
    }

    public CommandManager addCommands(Command... commands) {
        for (Command command : commands) {
            addCommand(command);
        }

        return this;
    }

    public CommandManager setExecutor(String command) {
        final PluginCommand pluginCommand = command == null ? null : plugin.getCommand(command);

        if (pluginCommand != null) {
            pluginCommand.setExecutor(this);
        } else {
            Common.log("&cFailed to register command: &f/&e" + command);
        }

        return this;
    }

    public CommandManager setUseClosestCommand(boolean bool) {
        this.allowLooseCommands = bool;
        return this;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender commandSender, @NotNull org.bukkit.command.Command command, @NotNull String s, @NotNull String[] args) {
        // grab the specific command that's being called
        final String commandName = command.getName().toLowerCase();
        final NestedCommand nested = commands.get(commandName);

        if (nested != null) {
            // Create command context
            final CommandContext context = new CommandContext(commandSender, args, commandName);
            
            // check to see if we're trying to call a sub-command
            if (args.length != 0 && !nested.children.isEmpty()) {
                final String subCommand = getSubCommand(nested, args);

                if (subCommand != null) {
                    // we have a subcommand to use!
                    final Command sub = nested.children.get(subCommand);
                    // adjust the arguments to match
                    int i = subCommand.indexOf(' ') == -1 ? 1 : 2;
                    String[] newArgs = new String[args.length - i];
                    System.arraycopy(args, i, newArgs, 0, newArgs.length);

                    // Create new context with adjusted args
                    final CommandContext subContext = new CommandContext(commandSender, newArgs, commandName);
                    
                    // now process the command
                    processRequirements(sub, subContext);
                    return true;
                }
            }

            // if we've gotten this far, then just use the command we have
            if (nested.parent != null) {
                processRequirements(nested.parent, context);
                return true;
            }
        }

        Common.tell(commandSender, unknownCommandMessage);
        return true;
    }

    @Nullable
    @Override
    public List<String> onTabComplete(CommandSender sender, org.bukkit.command.Command command, String alias, String[] args) {
        // grab the specific command that's being called
        final NestedCommand nested = commands.get(command.getName().toLowerCase());

        if (nested != null) {
            if (args.length == 0 || nested.children.isEmpty()) {
                if (nested.parent != null) {
                    final CommandContext context = new CommandContext(sender, args, command.getName());
                    final List<String> result = nested.parent.tabInternal(context);
                    return result != null ? result : Collections.emptyList();
                }
                return Collections.emptyList();
            }

            // check for each sub-command that they have access to
            final boolean op = sender.isOp();
            final boolean console = !(sender instanceof Player);

            if (args.length == 1) {
                // suggest sub-commands that this user has access to
                final String arg = args[0].toLowerCase();
                final List<String> suggestions = new ArrayList<>();
                
                // Optimized: Direct iteration instead of stream for better performance
                for (Map.Entry<String, Command> entry : nested.children.entrySet()) {
                    final String key = entry.getKey();
                    final Command cmd = entry.getValue();
                    
                    if (key.startsWith(arg)) {
                        if ((!console || !cmd.isNoConsole()) && 
                            (op || cmd.getPermissionNode() == null || sender.hasPermission(cmd.getPermissionNode()))) {
                            suggestions.add(key);
                        }
                    }
                }
                
                return suggestions;
            }

            // more than one arg, let's check to see if we have a command here
            final String subCmd = getSubCommand(nested, args);
            Command sub;
            if (subCmd != null && (sub = nested.children.get(subCmd)) != null && (!console || !sub.isNoConsole()) && (op || sub.getPermissionNode() == null || sender.hasPermission(sub.getPermissionNode()))) {
                // adjust the arguments to match
                int i = subCmd.indexOf(' ') == -1 ? 1 : 2;
                final String[] newArgs = new String[args.length - i];
                System.arraycopy(args, i, newArgs, 0, newArgs.length);

                // Create context for tab completion
                final CommandContext context = new CommandContext(sender, newArgs, command.getName());
                
                // we're good to go!
                return fetchList(sub, context);
            }
        }

        return Collections.emptyList();
    }

    /**
     * Optimized subcommand matching using pre-computed keys
     */
    private String getSubCommand(NestedCommand nested, String[] args) {
        if (args.length == 0) {
            return null;
        }
        
        final String cmd = args[0].toLowerCase();
        
        // Fast path: exact single-word match
        if (nested.children.containsKey(cmd)) {
            return cmd;
        }

        // Optimized: Check multi-word subcommands only if they exist
        if (args.length >= 2 && nested.hasMultiWordSubcommands()) {
            // Build command string incrementally (more efficient than copying arrays)
            StringBuilder sb = new StringBuilder(cmd);
            for (int i = 1; i < args.length; i++) {
                sb.append(' ').append(args[i].toLowerCase());
                String candidate = sb.toString();
                if (nested.children.containsKey(candidate)) {
                    return candidate;
                }
            }
        }

        // Loose command matching (if enabled)
        if (allowLooseCommands) {
            return nested.findClosestMatch(cmd);
        }

        return null;
    }

    /**
     * Process command requirements and execute with context
     */
    private void processRequirements(Command command, CommandContext context) {
        final CommandSender sender = context.getSender();
        
        if (sender instanceof Player && command.getAllowedExecutor() == AllowedExecutor.CONSOLE) {
            Common.tell(sender, consoleOnlyMessage);
            return;
        }

        if (!(sender instanceof Player) && command.isNoConsole()) {
            Common.tell(sender, playerOnlyMessage);
            return;
        }

        if (command.getPermissionNode() == null || sender.hasPermission(command.getPermissionNode())) {
            // Use new context-based execution (with backwards compatibility)
            final ReturnType returnType = command.executeInternal(context);

            if (returnType == ReturnType.REQUIRES_PLAYER) {
                Common.tell(sender, playerOnlyMessage);
                return;
            }

            if (returnType == ReturnType.REQUIRES_CONSOLE) {
                Common.tell(sender, consoleOnlyMessage);
                return;
            }

            if (returnType == ReturnType.INVALID_SYNTAX) {
                for (String s : syntaxErrorMessages) {
                    Common.tellNoPrefix(sender, s
                            .replace("%pl_name%", Common.PLUGIN_NAME)
                            .replace("%syntax%", command.getSyntax()));
                }
            }

            return;
        }

        Common.tell(sender, noPermissionMessage);
    }
    
    /**
     * Legacy method for backwards compatibility
     * @deprecated Use {@link #processRequirements(Command, CommandContext)} instead
     */
    @Deprecated
    private void processRequirements(Command command, CommandSender sender, String[] args) {
        processRequirements(command, new CommandContext(sender, args, ""));
    }

    /**
     * Fetch tab completion list with context
     */
    private List<String> fetchList(Command command, CommandContext context) {
        final List<String> list = command.tabInternal(context);

        if (context.getArgCount() != 0) {
            final String str = context.getArg(context.getArgCount() - 1);

            if (list != null && str != null && str.length() >= 1) {
                final String lowerStr = str.toLowerCase();
                try {
                    list.removeIf(s -> !s.toLowerCase().startsWith(lowerStr));
                } catch (UnsupportedOperationException ignore) {
                    // List is immutable, create new filtered list
                    return list.stream()
                            .filter(s -> s.toLowerCase().startsWith(lowerStr))
                            .toList();
                }
            }
        }

        return list != null ? list : Collections.emptyList();
    }
    
    /**
     * Legacy method for backwards compatibility
     * @deprecated Use {@link #fetchList(Command, CommandContext)} instead
     */
    @Deprecated
    private List<String> fetchList(Command command, String[] args, CommandSender sender) {
        return fetchList(command, new CommandContext(sender, args, ""));
    }

    public static void registerCommandDynamically(Plugin plugin, String command, CommandExecutor executor, TabCompleter tabManager) {
        try {
            CommandMap commandMap = getCommandMap();

            Constructor<PluginCommand> constructorPluginCommand = PluginCommand.class.getDeclaredConstructor(String.class, Plugin.class);
            constructorPluginCommand.setAccessible(true);
            PluginCommand commandObject = constructorPluginCommand.newInstance(command, plugin);

            handlePaperTimings(plugin, commandObject);

            commandObject.setExecutor(executor);
            commandObject.setTabCompleter(tabManager);

            commandMap.register(plugin.getName().toLowerCase(Locale.ROOT), commandObject);
        } catch (ReflectiveOperationException ex) {
            plugin.getLogger().severe("Error registering command dynamically: " + ex.getMessage());
            plugin.getLogger().severe("Stack trace:");
            ex.printStackTrace();
        } catch (Exception ex) {
            plugin.getLogger().severe("Unexpected error registering command dynamically: " + ex.getMessage());
            plugin.getLogger().severe("Stack trace:");
            ex.printStackTrace();
        }
    }

    private static void handlePaperTimings(Plugin plugin, PluginCommand commandObject) {
        try {
            if (ServerProject.isServer(ServerProject.PAPER, ServerProject.TACO) && ServerVersion.isServerVersion(ServerVersion.V1_7, ServerVersion.V1_8)) {
                Class<?> timingsManagerClass = Class.forName("co.aikar.timings.TimingsManager");
                Method getCommandTiming = timingsManagerClass.getMethod("getCommandTiming", String.class, org.bukkit.command.Command.class);
                Field timingsField = PluginCommand.class.getDeclaredField("timings");
                timingsField.setAccessible(true);

                Object timing = getCommandTiming.invoke(null, plugin.getName().toLowerCase(), commandObject);
                if (timing != null) {
                    timingsField.set(commandObject, timing);
                }
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to set up command timings: " + e.getMessage());
                e.printStackTrace();
        }
    }


    /// special case for things like shops
    public static boolean unregisterCommands(Collection<? extends org.bukkit.command.Command> cmds) {
        boolean changed = false;
        CommandMap commandMap = getCommandMap();
        Map<String, org.bukkit.command.Command> knownCmds = getKnownCommands(commandMap);

        int estimatedSize = cmds.size() * 3; // label + name + average 1 alias
        HashMap<String, org.bukkit.command.Command> commandsToCheck = new HashMap<>(estimatedSize);

        for (org.bukkit.command.Command c : cmds) {
            commandsToCheck.put(c.getLabel().toLowerCase(), c);
            commandsToCheck.put(c.getName().toLowerCase(), c);
            c.getAliases().forEach(a -> commandsToCheck.put(a.toLowerCase(), c));
        }

        for (Map.Entry<String, org.bukkit.command.Command> check : commandsToCheck.entrySet()) {
            org.bukkit.command.Command mappedCommand = knownCmds.get(check.getKey());
            if (check.getValue().equals(mappedCommand)) {
                mappedCommand.unregister(commandMap);
                knownCmds.remove(check.getKey());
                changed = true;
                } else if (check.getValue() instanceof PluginCommand checkPCmd) {
                if (mappedCommand instanceof PluginCommand mappedPCmd) {
                    CommandExecutor mappedExec = mappedPCmd.getExecutor();

                    if (mappedExec != null && mappedExec.equals(checkPCmd.getExecutor())) {
                        mappedPCmd.setExecutor(null);
                        mappedPCmd.setTabCompleter(null);
                    }
                }
                checkPCmd.setExecutor(emptyExec);
                checkPCmd.setTabCompleter(emptyExec);
            }
        }
        return changed;
    }

    public static CommandMap getCommandMap() {
        Server server = Bukkit.getServer();
        try {
            Method m = server.getClass().getDeclaredMethod("getCommandMap");
            m.setAccessible(true);
            return (CommandMap) m.invoke(Bukkit.getServer());
        } catch (Exception ignored) {
        }
        try {
            Field commandMapField = server.getClass().getDeclaredField("commandMap");
            commandMapField.setAccessible(true);
            return (CommandMap) commandMapField.get(server);
        } catch (Exception e) {
            throw new RuntimeException("Could not get commandMap", e);
        }
    }

    @SuppressWarnings("unchecked")
    public static Map<String, org.bukkit.command.Command> getKnownCommands(CommandMap m) {
        try {
            Method me = m.getClass().getDeclaredMethod("getKnownCommands");
            me.setAccessible(true);
            return (Map<String, org.bukkit.command.Command>) me.invoke(m);
        } catch (Exception ignored) {
        }
        try {
            Field knownCommandsField = m.getClass().getDeclaredField("knownCommands");
            knownCommandsField.setAccessible(true);
            return (Map<String, org.bukkit.command.Command>) knownCommandsField.get(m);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException("Could not get knownCommands", e);
        }
    }

    public static final TabExecutor emptyExec = new TabExecutor() {
        @Nullable
        @Override
        public List<String> onTabComplete(@NotNull CommandSender commandSender, @NotNull org.bukkit.command.Command command, @NotNull String s, @NotNull String[] strings) {
            return null;
        }

        @Override
        public boolean onCommand(@NotNull CommandSender commandSender, @NotNull org.bukkit.command.Command command, @NotNull String s, @NotNull String[] strings) {
            return false;
        }
    };

}

