# Command System

Flight provides a powerful and flexible command system with multiple registration methods to suit different needs. Whether you prefer traditional class-based commands, annotation-based commands, or modern Brigadier support, Flight has you covered.

## Overview

The Flight command system offers:

- **Traditional Commands** - Extend the `Command` class for full control
- **CommandContext** - Modern, type-safe command execution context
- **ArgumentParser** - Type-safe argument parsing with validation
- **Annotation-Based Commands** - Register commands using annotations
- **Brigadier Support** - Modern command registration (Flight targets Minecraft 1.16+)
- **Tab Completion** - Easy-to-implement tab completion

## Quick Start

The simplest way to create a command is to extend the `Command` class:

```java
public class MyCommand extends Command {
    public MyCommand() {
        super(AllowedExecutor.PLAYER, "mycommand");
    }

    @Override
    protected ReturnType execute(CommandContext context) {
        context.getSender().sendMessage("Command executed!");
        return ReturnType.SUCCESS;
    }

    @Override
    protected List<String> tab(CommandContext context) {
        return Collections.emptyList();
    }

    @Override
    public String getPermissionNode() {
        return "myplugin.mycommand";
    }

    @Override
    public String getSyntax() {
        return "/mycommand";
    }

    @Override
    public String getDescription() {
        return "My command description";
    }
}
```

Then register it:

```java
CommandManager commandManager = new CommandManager(plugin);
commandManager.addCommand(new MyCommand());
```

## Documentation Sections

### [Basic Commands](commands/basic.md)
Learn how to create commands by extending the `Command` class. This is the traditional approach that gives you full control over command execution.

**Topics covered:**
- Creating your first command
- Understanding AllowedExecutor
- ReturnType enum
- Command registration
- Best practices

### [CommandContext](commands/command-context.md)
CommandContext provides a modern, type-safe way to access command execution data. It eliminates the need for manual type casting and provides convenient helper methods.

**Topics covered:**
- What is CommandContext?
- Accessing sender and arguments
- Helper methods (getArg, joinArgs, etc.)
- Type-safe player access
- Migration from legacy methods

### [ArgumentParser](commands/argument-parser.md)
ArgumentParser provides type-safe argument parsing with automatic validation. It supports both optional and required arguments, with clear error handling.

**Topics covered:**
- Optional vs required arguments
- Supported types (String, Int, Double, Boolean, Player, UUID)
- Error handling
- Real-world examples
- Best practices

### [Annotation-Based Commands](commands/annotations.md)
Register commands using annotations instead of extending classes. This approach reduces boilerplate and makes it easy to organize commands in classes.

**Topics covered:**
- @Command annotation
- @SubCommand annotation
- @TabComplete annotation
- Registering annotated commands
- When to use annotations

### [Brigadier Support](commands/brigadier.md)
Brigadier is Minecraft's modern command framework (introduced in 1.13). Flight targets **Minecraft 1.16+** and provides Brigadier integration when Brigadier/NMS types are present.

**Topics covered:**
- What is Brigadier?
- Creating Brigadier commands
- Availability checks (classpath / server implementation)
- Benefits of Brigadier
- Migration guide

### [Tab Completion](commands/tab-completion.md)
Implement tab completion for your commands to improve user experience. Flight makes it easy to provide intelligent suggestions based on context.

**Topics covered:**
- Basic tab completion
- Context-aware suggestions
- Player name suggestions
- Filtering suggestions
- Advanced patterns

## Choosing the Right Approach

### Use Traditional Commands When:
- You need complex command logic
- You want full control over command structure
- You're already using the traditional system
- You need fine-grained control over execution

### Use Annotation-Based Commands When:
- You want cleaner, more organized code
- You have many simple commands
- You want to group related commands in classes
- You prefer declarative style

### Use Brigadier Commands When:
- You're on a server where Brigadier is available (Flight requires Minecraft 1.16+)
- You need better tab completion performance
- You want native Minecraft command integration
- You need advanced command suggestions

## CommandManager

The `CommandManager` is the central class for managing commands. It handles:

- Command registration
- Permission checking
- Executor validation (player/console/both)
- Tab completion
- Error messages

```java
CommandManager commandManager = new CommandManager(plugin);

// Customize error messages
commandManager.setPlayerOnlyMessage("&cOnly players can use this!");
commandManager.setNoPermissionMessage("&cNo permission!");

// Register commands
commandManager.addCommand(new MyCommand());
```

## ReturnType Enum

Commands return a `ReturnType` to indicate the result:

- `SUCCESS` - Command executed successfully
- `FAIL` - Command failed
- `INVALID_SYNTAX` - Invalid command syntax (shows syntax help)
- `REQUIRES_PLAYER` - Command requires a player
- `REQUIRES_CONSOLE` - Command requires console

## AllowedExecutor Enum

Specify who can execute a command:

- `PLAYER` - Only players
- `CONSOLE` - Only console
- `BOTH` - Both players and console

## See Also

- [GUI System](gui.md) - Create GUIs for your commands
- [Utilities](utilities.md) - Useful utilities for command handling
- [Configuration](configuration.md) - Store command settings

