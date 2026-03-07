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

package ca.tweetzy.flight.utils.input;

import ca.tweetzy.flight.gui.GUISessionLock;
import ca.tweetzy.flight.gui.Gui;
import com.cryptomorin.xseries.messages.ActionBar;
import com.cryptomorin.xseries.messages.Titles;
import lombok.NonNull;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.lang.reflect.Method;

/**
 * The current file has been created by Kiran Hart
 * Date Created: November 08 2021
 * Time Created: 4:44 p.m.
 * Usage of any code found within this class is prohibited unless given explicit permission otherwise
 */
public abstract class Input implements Listener, Runnable {

    // Cache the reflection Method object for performance (lookup once, reuse many times)
    private static Method getGUIMethod = null;
    private static final Object methodLock = new Object();

    private final Player player;
    private final JavaPlugin plugin;
    private String title;
    private String subtitle;

    private final BukkitTask task;
    private boolean closed = false;
    private boolean exiting = false;
    private Gui savedGui = null;
    private boolean preserveSession = false;
    protected boolean allowNextGuiOpen = false; // Allow next GUI to open (for transitions after input completes)

    public Input(@NonNull final JavaPlugin plugin, @NonNull final Player player) {
        this.plugin = plugin;
        this.player = player;
        
        // Register this input as active
        InputSessionLock.start(player.getUniqueId(), this);
        
        // Save reference to open GUI and ensure it allows closing
        this.savedGui = GUISessionLock.get(player.getUniqueId());
        if (this.savedGui != null) {
            // Automatically set allowClose to prevent GUI from reopening during TitleInput
            // This preserves close handlers (for item return) while preventing unwanted reopening
            this.savedGui.setAllowClose(true);
            // Preserve the session lock so GUI can reopen after input
            this.preserveSession = true;
        }
        
        // Close inventory automatically - TitleInput handles this
        Bukkit.getServer().getScheduler().runTaskLater(plugin, player::closeInventory, 1L);
        this.task = Bukkit.getServer().getScheduler().runTaskTimer(plugin, this, 1L, 1L);
        Bukkit.getServer().getPluginManager().registerEvents(this, plugin);
    }

    public void onExit(final Player player) {
    }

    public void onDeath(final Player player) {
    }

    public abstract boolean onInput(final String input);

    public abstract String getTitle();

    public abstract String getSubtitle();

    public abstract String getActionBar();

    @Override
    public void run() {
        final String title = this.getTitle();
        final String subTitle = this.getSubtitle();
        final String actionBar = this.getActionBar();

        if (this.title == null || this.subtitle == null || !this.title.equals(title) || !this.subtitle.equals(subTitle)) {
            Titles.sendTitle(this.player, 10, 6000, 0, title, subTitle);
            this.title = title;
            this.subtitle = subTitle;
        }

        if (actionBar != null)
            ActionBar.sendActionBar(this.player, actionBar);
    }

    @EventHandler(
            priority = EventPriority.LOWEST
    )
    public void onChat(AsyncPlayerChatEvent e) {
        if (e.getPlayer().equals(this.player)) {
            e.setCancelled(true);
            final String message = e.getMessage();
            Bukkit.getScheduler().runTask(this.plugin, () -> {
                if (!this.closed && this.player != null && this.player.isOnline()) {
                    this.onInput(message);
                }
            });
        }
    }

    @EventHandler
    public void close(PlayerQuitEvent e) {
        if (e.getPlayer().equals(this.player)) {
            this.close(false);
        }
    }

    private boolean isGuiInventory(Inventory inventory) {
        InventoryHolder holder = inventory.getHolder();
        if (holder == null) return false;
        // Check if holder is a GuiHolder by checking class name (GuiHolder is package-private)
        return holder.getClass().getName().equals("ca.tweetzy.flight.gui.GuiHolder");
    }

    /**
     * Gets the GUI from a GuiHolder using cached reflection for performance.
     * Returns null if the holder is not a GuiHolder or if reflection fails.
     */
    private Gui getGuiFromHolder(InventoryHolder holder) {
        if (holder == null) return null;
        
        // Initialize the cached method if needed (thread-safe)
        if (getGUIMethod == null) {
            synchronized (methodLock) {
                if (getGUIMethod == null) {
                    try {
                        getGUIMethod = holder.getClass().getMethod("getGUI");
                        getGUIMethod.setAccessible(true);
                    } catch (NoSuchMethodException e) {
                        return null;
                    }
                }
            }
        }
        
        // Use the cached method to invoke (much faster than getMethod() each time)
        try {
            return (Gui) getGUIMethod.invoke(holder);
        } catch (Exception e) {
            return null;
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInventoryClose(InventoryCloseEvent e) {
        if (e.getPlayer().equals(this.player) && !this.closed) {
            // Prevent GUI from reopening by ensuring allowClose stays true
            if (isGuiInventory(e.getInventory())) {
                Gui gui = getGuiFromHolder(e.getInventory().getHolder());
                if (gui != null) {
                    gui.setAllowClose(true);
                    // Preserve session lock if we have a saved GUI
                    if (this.preserveSession && this.savedGui != null && gui == this.savedGui) {
                        // Restore session lock after GuiManager finishes processing (runs on next tick)
                        // GuiManager's close handler runs at LOW priority and schedules a task, so we run after that
                        Bukkit.getScheduler().runTaskLater(this.plugin, () -> {
                            if (!this.closed && this.preserveSession) {
                                GUISessionLock.start(this.player.getUniqueId(), this.savedGui);
                            }
                        }, 2L);
                    }
                } else if (this.savedGui != null) {
                    // Fallback to saved GUI if reflection fails
                    this.savedGui.setAllowClose(true);
                    // Preserve session lock
                    if (this.preserveSession) {
                        Bukkit.getScheduler().runTaskLater(this.plugin, () -> {
                            if (!this.closed && this.preserveSession) {
                                GUISessionLock.start(this.player.getUniqueId(), this.savedGui);
                            }
                        }, 2L);
                    }
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInventoryOpen(InventoryOpenEvent e) {
        if (e.getPlayer().equals(this.player) && !this.closed && !this.exiting) {
            // Prevent GUI from reopening while input is active
            if (isGuiInventory(e.getInventory())) {
                // Set allowClose on the GUI so it won't try to reopen itself
                Gui gui = getGuiFromHolder(e.getInventory().getHolder());
                if (gui != null) {
                    // Allow GUI to open if:
                    // 1. It's marked as transitioning (intentional transition)
                    // 2. We're allowing the next GUI open (input just completed)
                    // 3. It's the same GUI instance as saved (GUI reopening itself)
                    if (gui.isTransitioning() || this.allowNextGuiOpen || gui == this.savedGui) {
                        // This is an intentional transition or completion, allow it
                        // Reset the flag after allowing one GUI open
                        this.allowNextGuiOpen = false;
                        return;
                    }
                    
                    // Check if this GUI is from the same plugin context by checking if it's transitioning
                    // Give a small delay to allow transition flags to be set properly
                    // This handles the case where a GUI opens right after TitleInput completes
                    final Gui guiToCheck = gui;
                    Bukkit.getScheduler().runTaskLater(this.plugin, () -> {
                        // Re-check after a tick to see if transition flag was set
                        if (!this.closed && !this.exiting && guiToCheck.isTransitioning()) {
                            // Transition flag was set, this is intentional - allow it
                            return;
                        }
                        
                        // Still not transitioning and we're still in input mode - close it
                        if (!this.closed && !this.exiting && this.player.isOnline()) {
                            Inventory currentTop = this.player.getOpenInventory().getTopInventory();
                            if (currentTop != null && currentTop.equals(e.getInventory())) {
                                this.player.closeInventory();
                            }
                        }
                    }, 1L);
                    
                    gui.setAllowClose(true);
                } else if (this.savedGui != null) {
                    // Fallback to saved GUI if reflection fails
                    this.savedGui.setAllowClose(true);
                    
                    // Close with delay to allow transitions
                    Bukkit.getScheduler().runTaskLater(this.plugin, () -> {
                        if (!this.closed && !this.exiting && this.player.isOnline()) {
                            Inventory currentTop = this.player.getOpenInventory().getTopInventory();
                            if (currentTop != null && currentTop.equals(e.getInventory())) {
                                this.player.closeInventory();
                            }
                        }
                    }, 1L);
                } else {
                    // No GUI info available, close immediately
                    Bukkit.getScheduler().runTask(this.plugin, () -> {
                        if (this.player.isOnline() && 
                            this.player.getOpenInventory().getTopInventory().equals(e.getInventory())) {
                            this.player.closeInventory();
                        }
                    });
                }
            } else {
                // Only close input if the opened inventory is NOT a GUI inventory
                // (i.e., player opened a chest or other inventory manually)
                this.close(false);
            }
        }
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent e) {
        if (e.getEntity().equals(this.player)) {
            this.onDeath(e.getEntity());
            close(false);
        }
    }

    public void close(boolean completed) {
        if (this.closed) return; // Prevent double-closing
        this.closed = true;
        
        // Security: Always unregister input session, even if something goes wrong
        try {
            // Unregister this input as active
            InputSessionLock.end(this.player.getUniqueId());
        } catch (Exception e) {
            // Log but continue cleanup
            Bukkit.getLogger().warning("Error ending input session: " + e.getMessage());
        }
        
        try {
            HandlerList.unregisterAll(this);
        } catch (Exception e) {
            // Already unregistered or error, continue
        }
        
        try {
            this.task.cancel();
        } catch (Exception e) {
            // Task already cancelled or error, continue
        }
        
        // Clear the preserve session flag when closing
        this.preserveSession = false;
        
        if (!completed) {
            // Security: Validate player is still online before calling onExit
            if (this.player != null && this.player.isOnline()) {
                // Mark that we're exiting to prevent InventoryOpenEvent from interfering
                this.exiting = true;
                // Delay onExit slightly to ensure input is fully closed and handlers unregistered before GUI reopens
                Bukkit.getScheduler().runTaskLater(
                    this.plugin,
                    () -> {
                        // Double-check player is still online before calling onExit
                        if (this.player != null && this.player.isOnline()) {
                            try {
                                this.onExit(this.player);
                            } catch (Exception e) {
                                Bukkit.getLogger().warning("Error in Input onExit handler: " + e.getMessage());
                                e.printStackTrace();
                            }
                        }
                        this.exiting = false;
                    },
                    3L
                );
            }
        }

        // Clear title/actionbar safely
        if (this.player != null && this.player.isOnline()) {
            try {
                Titles.clearTitle(this.player);
                ActionBar.clearActionBar(this.player);
            } catch (Exception e) {
                // Player may have disconnected, ignore
            }
        }
    }
}
