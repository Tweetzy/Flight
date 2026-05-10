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

package ca.tweetzy.flight;

import ca.tweetzy.flight.comp.enums.ServerVersion;
import ca.tweetzy.flight.config.tweetzy.TweetzyYamlConfig;
import ca.tweetzy.flight.database.DataManagerAbstract;
import ca.tweetzy.flight.dependency.Dependency;
import ca.tweetzy.flight.dependency.DependencyLoader;
import ca.tweetzy.flight.dependency.Relocation;
import ca.tweetzy.flight.utils.Common;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

/**
 * Date Created: April 06 2022
 * Time Created: 11:05 a.m.
 *
 * @author Kiran Hart
 */
public abstract class FlightPlugin extends JavaPlugin implements Listener {

    private static TweetzyYamlConfig coreConfig;

    private boolean emergencyStop = false;

    /**
     * The instance of this plugin
     */
    private static volatile FlightPlugin instance;

    public static FlightPlugin getInstance() {
        if (instance == null) {
            instance = JavaPlugin.getPlugin(FlightPlugin.class);
        }
        return instance;
    }

	/*
	-------------------------------------------------------------------------
	Handle the loading & disabling
	-------------------------------------------------------------------------
	 */

    @Override
    public final void onLoad() {
        try {
            getInstance();
            
            // Load optional dependencies at runtime
            Set<Dependency> dependencies = getOptionalDependencies();
            if (!dependencies.isEmpty()) {
                new DependencyLoader(this).loadDependencies(dependencies);
            }
            
            onWake();
        } catch (final Throwable throwable) {
            criticalErrorOnPluginStartup(throwable);
        }
    }
    
    /**
     * Get optional dependencies that should be loaded at runtime
     * Override this method to add plugin-specific optional dependencies
     */
    protected Set<Dependency> getOptionalDependencies() {
        Set<Dependency> dependencies = new HashSet<>();
        
        // Load SQLite JDBC - needed for SQLite database (default)
        dependencies.add(new Dependency(
                "https://repo1.maven.org/maven2",
                "org.xerial",
                "sqlite-jdbc",
                "3.44.1.0",
                true,
                new Relocation("org.sqlite", "ca.tweetzy.flight.third_party.org.sqlite")
        ));
        
        // Gson is provided by Spigot - no need to load it
        // Jedis is loaded lazily when DataManagerAbstract.initializeRedisSync runs

        return dependencies;
    }

    @Override
    public final void onEnable() {
        if (this.emergencyStop) {
            setEnabled(false);
            return;
        }

        if (ServerVersion.isServerVersionBelow(ServerVersion.V1_16)) {
            getLogger().severe("Flight requires Minecraft 1.16 or newer. Detected: "
                    + Bukkit.getServer().getBukkitVersion());
            setEnabled(false);
            return;
        }

        CommandSender console = Bukkit.getConsoleSender();

        console.sendMessage(" "); // blank line to separate chatter
        console.sendMessage(Common.colorize("#00a87f============================="));
        console.sendMessage(Common.colorize(String.format("#00ce74%s &fv&e%s #CBCBCBby #00ce74Tweetzy", getDescription().getName(), getDescription().getVersion())));
        console.sendMessage(Common.colorize(String.format("#00ce74Developer#CBCBCB: &e%s", String.join(", ", getDescription().getAuthors()))));

        try {
            coreConfig = new TweetzyYamlConfig(this, "config.yml");

            onFlight();

            if (emergencyStop) {
                console.sendMessage(Common.colorize("#8C1053~~~~~~~~~~~~~~~~~~~~~~~~~~~~~"));
                console.sendMessage(" ");
                return;
            }

            // metrics
            if (this.getBStatsId() != -1) {
                console.sendMessage(Common.colorize(String.format("&8[#00ce74FlightCore&8]#CBCBCB Enabling metrics for #00ce74%s", getDescription().getName())));
                Metrics metrics = new Metrics(this, this.getBStatsId());

                if (!this.getCustomMetricCharts().isEmpty())
                    this.getCustomMetricCharts().forEach(metrics::addCustomChart);
            }

        } catch (final Throwable throwable) {
            criticalErrorOnPluginStartup(throwable);
            console.sendMessage(Common.colorize("#8C1053~~~~~~~~~~~~~~~~~~~~~~~~~~~~~"));
            console.sendMessage(" ");
            return;
        }

        console.sendMessage(Common.colorize("#00a87f============================="));
        console.sendMessage(" ");
    }

    @Override
    public final void onDisable() {
        if (this.emergencyStop) {
            return;
        }
        onSleep();
    }


	/*
	-------------------------------------------------------------------------
	Loader
	-------------------------------------------------------------------------
	 */

    /**
     * Called during {@link JavaPlugin#onLoad()}
     */
    protected void onWake() {
    }

    /**
     * Called during {@link JavaPlugin#onEnable()}
     */
    protected abstract void onFlight();

    /**
     * Called during {@link JavaPlugin#onDisable()}
     */
    protected void onSleep() {
    }

    /*
    -------------------------------------------------------------------------
    Misc
    -------------------------------------------------------------------------
     */

    public String getPluginName() {
        return getDescription().getName();
    }

    public String getPluginDescription() {
        return getDescription().getDescription();
    }

    public String getVersion() {
        return getDescription().getVersion();
    }

    protected int getBStatsId() {
        return -1;
    }

    protected List<Metrics.CustomChart> getCustomMetricCharts() {
        return Collections.emptyList();
    }

    protected int getSpigotId() {
        return -1;
    }

    public static TweetzyYamlConfig getCoreConfig() {
        return coreConfig;
    }

    protected void emergencyStop() {
        this.emergencyStop = true;
        Bukkit.getPluginManager().disablePlugin(this);
    }


    protected void shutdownDataManager(DataManagerAbstract dataManager) {
        // 3 minutes is overkill, but we just want to make sure
        shutdownDataManager(dataManager, 15, TimeUnit.MINUTES.toSeconds(3));
    }

    protected void shutdownDataManager(DataManagerAbstract dataManager, int reportInterval, long secondsUntilForceShutdown) {
        dataManager.shutdownTaskQueue();

        while (!dataManager.isTaskQueueTerminated() && secondsUntilForceShutdown > 0) {
            long secondsToWait = Math.min(reportInterval, secondsUntilForceShutdown);

            try {
                if (dataManager.waitForShutdown(secondsToWait, TimeUnit.SECONDS)) {
                    break;
                }

                getLogger().info(String.format("A DataManager is currently working on %d tasks... " +
                                "We are giving him another %d seconds until we forcefully shut him down " +
                                "(continuing to report in %d second intervals)",
                        dataManager.getTaskQueueSize(), secondsUntilForceShutdown, reportInterval));
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            } finally {
                secondsUntilForceShutdown -= secondsToWait;
            }
        }

        if (!dataManager.isTaskQueueTerminated()) {
            int unfinishedTasks = dataManager.forceShutdownTaskQueue().size();

            if (unfinishedTasks > 0) {
                getLogger().log(Level.WARNING,
                        String.format("A DataManager has been forcefully terminated with %d unfinished tasks - " +
                                "This can be a serious problem, please report it to us (Tweetzy)!", unfinishedTasks));
            }
        }
    }

    /**
     * Logs one or multiple errors that occurred during plugin startup and calls {@link #emergencyStop()} afterwards
     *
     * @param th The error(s) that occurred
     */
    protected void criticalErrorOnPluginStartup(Throwable th) {
        Bukkit.getLogger().log(Level.SEVERE,
                String.format(
                        "Unexpected error while loading %s v%s c%s: Disabling plugin!",
                        getDescription().getName(),
                        getDescription().getVersion(),
                        FlightConstants.getCoreVersion()
                ), th);

        emergencyStop();
    }
}
