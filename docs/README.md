# Flight Framework Documentation

Welcome to the Flight Framework documentation! Flight is a comprehensive Spigot plugin framework designed to speed up development by providing powerful, easy-to-use APIs for common plugin development tasks.

## What is Flight?

Flight is a combination of resources and utilities that help speed up Spigot plugin development. It provides:

- **Command System** - Multiple command registration methods (traditional, annotation-based, Brigadier)
- **GUI System** - Powerful GUI framework with pagination, configurable GUIs, and templates
- **Database System** - Repository pattern with SQLite/MySQL support, migrations, and Redis sync
- **Configuration System** - YAML configuration with comments and type-safe access
- **Translation System** - Multi-language support with easy translation management
- **Utility Classes** - Common utilities for items, colors, input, cooldowns, and more
- **Advanced Features** - Dependency loading, metrics integration, and data management

## Getting Started

To use Flight in your plugin, you need to:

1. **Add Flight as a dependency** in your `pom.xml`:

```xml
<dependency>
    <groupId>ca.tweetzy</groupId>
    <artifactId>flight</artifactId>
    <version>3.43.0</version>
</dependency>
```

2. **Extend FlightPlugin** instead of JavaPlugin:

```java
public class MyPlugin extends FlightPlugin {
    @Override
    protected void onFlight() {
        // Your plugin initialization code
    }
}
```

3. **Start using Flight features!** Check out the documentation sections below.

## Documentation Sections

### [Commands](commands.md)

Learn how to create and manage commands using Flight's powerful command system.

- [Basic Commands](commands/basic.md) - Creating commands by extending the Command class
- [CommandContext](commands/command-context.md) - Using CommandContext for cleaner code
- [ArgumentParser](commands/argument-parser.md) - Type-safe argument parsing
- [Annotation-Based Commands](commands/annotations.md) - Using annotations to register commands
- [Brigadier Support](commands/brigadier.md) - Modern command registration (Flight targets 1.16+)
- [Tab Completion](commands/tab-completion.md) - Implementing tab completion

### [GUI System](gui.md)

Create beautiful, functional GUIs with Flight's GUI framework.

- [Basic GUI](gui/basic-gui.md) - Creating basic GUIs
- [Pagination](gui/pagination.md) - Adding pagination to GUIs
- [Configurable GUI](gui/configurable-gui.md) - YAML-based configurable GUIs
- [GUI Templates](gui/gui-templates.md) - Pre-built GUI templates
- [Sign GUI](gui/sign-gui.md) - Using Sign GUI for player input
- [GUI Events](gui/gui-events.md) - Handling GUI events

### [Database System](database.md)

Manage your plugin's data with Flight's database system.

- [Getting Started](database/getting-started.md) - Setting up databases
- [Repositories](database/repositories.md) - Using the repository pattern
- [Entity Mapping](database/entity-mapping.md) - Mapping entities to database tables
- [Migrations](database/migrations.md) - Database migrations
- [Redis Sync](database/redis-sync.md) - Redis synchronization
- [Query Builder](database/query-builder.md) - Building queries

### [Configuration](configuration.md)

Manage your plugin's configuration files with Flight.

- [YAML Config](configuration/yaml-config.md) - Working with YAML configuration
- [Config Entries](configuration/config-entries.md) - Type-safe config access

### [Translations](translations.md)

Add multi-language support to your plugin.

- [Translation Manager](translations/translation-manager.md) - Managing translations

### [Utilities](utilities.md)

Useful utility classes for common tasks.

- [QuickItem](utilities/quick-item.md) - Building items easily
- [Cooldown Manager](utilities/cooldown-manager.md) - Managing cooldowns
- [Input System](utilities/input-system.md) - Player input handling
- [Color Formatting](utilities/color-formatting.md) - Colors and gradients
- [Component Message](utilities/component-message.md) - Component-based messages
- [Common Utilities](utilities/common-utilities.md) - Other utility classes

### [Advanced Features](advanced.md)

Advanced features for power users.

- [Dependency Loading](advanced/dependency-loading.md) - Loading dependencies at runtime
- [Metrics](advanced/metrics.md) - Integrating with bStats
- [Data Manager](advanced/data-manager.md) - Managing data operations

## Quick Examples

### Creating a Simple Command

```java
public class HelloCommand extends Command {
    public HelloCommand() {
        super(AllowedExecutor.PLAYER, "hello");
    }

    @Override
    protected ReturnType execute(CommandContext context) {
        Player player = context.getPlayer();
        player.sendMessage("Hello, " + player.getName() + "!");
        return ReturnType.SUCCESS;
    }

    @Override
    protected List<String> tab(CommandContext context) {
        return Collections.emptyList();
    }

    @Override
    public String getPermissionNode() {
        return "myplugin.hello";
    }

    @Override
    public String getSyntax() {
        return "/hello";
    }

    @Override
    public String getDescription() {
        return "Say hello!";
    }
}
```

### Creating a Simple GUI

```java
public class MyGUI extends Gui {
    public MyGUI(Player player) {
        super(player);
        setTitle("My GUI");
        setRows(3);
        draw();
    }

    @Override
    protected void draw() {
        setItem(13, QuickItem.of(Material.DIAMOND)
            .name("&aClick Me!")
            .lore("&7Click this item to do something!")
            .make(), click -> {
                click.player.sendMessage("You clicked the diamond!");
            });
    }
}
```

### Using QuickItem

```java
ItemStack item = QuickItem.of(Material.DIAMOND_SWORD)
    .name("&a&lLegendary Sword")
    .lore("&7This is a powerful sword!")
    .glow()
    .make();
```

## Version Compatibility

Flight supports:

- **Minecraft**: 1.16+
- **Java**: 16+
- **Server Types**: Spigot, Paper, and compatible forks

## Support

For issues, questions, or contributions, please refer to the project repository.

## License

Flight is licensed under the GNU General Public License v3.0.
