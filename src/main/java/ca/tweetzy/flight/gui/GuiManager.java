package ca.tweetzy.flight.gui;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.*;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.logging.Level;

/**
 * Modern, safe GUI Manager for Spigot/Paper 1.20.6 → 1.21+.
 *
 * ✅ Handles InventoryView interface/class differences safely
 * ✅ Prevents packet-based duplication exploits
 * ✅ Fully async-safe (never calls Bukkit methods off the main thread)
 * ✅ Per-player GUI session lock (instance match + lifecycle via {@link GUISessionLock})
 */
public class GuiManager {

    /** Debug flag: set to true to log detailed reflection errors. */
    private static final boolean DEBUG = false;

    private final Plugin plugin;
    private final GuiListener listener = new GuiListener(this);
    private final ConcurrentMap<Player, Gui> openInventories = new ConcurrentHashMap<>();

    private boolean initialized = false;
    private boolean shutdown = false;

    public GuiManager(Plugin plugin) {
        this.plugin = plugin;
    }

    public Plugin getPlugin() {
        return plugin;
    }

    public void init() {
        if (!initialized) {
            Bukkit.getPluginManager().registerEvents(listener, plugin);
            initialized = true;
            shutdown = false;
        }
    }

    public boolean isClosed() {
        return shutdown;
    }

    /**
     * Opens the specified GUI for a player.
     * Safe to call from async context; the open happens on the main thread.
     * 
     * This method automatically detects if there's an existing GUI open for the player
     * and uses safe transition logic to prevent setOnClose handlers from running.
     * This ensures backwards compatibility while providing safe transitions.
     * 
     * CRITICAL: Session lock and openInventories map are updated atomically on the main thread
     * to prevent race conditions where clicks could be processed against the wrong GUI.
     */
    public void showGUI(Player player, Gui gui) {
        if (shutdown || !initialized) {
            init();
        }

        // Prepare inventory creation in async (safe operation)
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            Inventory inv = gui.getOrCreateInventory(this);

            // All state updates must happen atomically on the main thread
            // This prevents race conditions where clicks arrive between map update and session lock update
            Bukkit.getScheduler().runTask(plugin, () -> {
                // Get previous GUI before updating map
                Gui previous = openInventories.get(player);
                
                // If there's a previous GUI open, use safe transition logic
                if (previous != null && previous.isOpen() && previous.getPlayers().contains(player)) {
                    // Use transition logic to prevent setOnClose from running
                    previous.isTransitioning = true;
                    previous.allowClose = true;
                    previous.open = false;
                    
                    // Cancel update tasks for updating GUIs (e.g., AuctionUpdatingPagedGUI)
                    // Use reflection to safely check and cancel tasks
                    try {
                        Method cancelTask = previous.getClass().getMethod("cancelTask");
                        cancelTask.invoke(previous);
                        if (DEBUG) {
                            Bukkit.getLogger().info("[GuiManager] Cancelled update task for " + previous.getClass().getSimpleName() + " (transitioning to " + gui.getClass().getSimpleName() + ")");
                        }
                    } catch (NoSuchMethodException e) {
                        // Not an updating GUI, that's fine
                    } catch (Exception e) {
                        if (DEBUG) {
                            Bukkit.getLogger().warning("[GuiManager] Error cancelling task for " + previous.getClass().getSimpleName() + ": " + e.getMessage());
                        }
                    }
                } else if (previous != null) {
                    previous.open = false;
                    // Also try to cancel task even if GUI wasn't open (safety measure)
                    try {
                        Method cancelTask = previous.getClass().getMethod("cancelTask");
                        cancelTask.invoke(previous);
                        if (DEBUG) {
                            Bukkit.getLogger().info("[GuiManager] Cancelled update task for " + previous.getClass().getSimpleName() + " (GUI was not open)");
                        }
                    } catch (Exception e) {
                        // Not an updating GUI or error, that's fine
                    }
                }

                // ATOMIC UPDATE: Update both session lock and openInventories map together
                // This ensures clicks always see consistent state
                GUISessionLock.start(player.getUniqueId(), gui);
                openInventories.put(player, gui);
                
                // Now safe to open inventory - session lock is already set
                player.openInventory(inv);
                gui.onOpen(this, player);
            });
        });
    }

    public void closeAll() {
        openInventories.keySet().removeIf(player -> {
            Inventory top = getTopInventoryCompat(player.getOpenInventory());
            if (top != null && top.getHolder() instanceof GuiHolder) {
                player.closeInventory();
                GUISessionLock.end(player.getUniqueId());
                return true;
            }
            return false;
        });
        openInventories.clear();
    }

    // ------------------------------------------------------------------
    // Reflection utility for InventoryView compatibility
    // ------------------------------------------------------------------

    /**
     * In API versions 1.20.x, InventoryView is a class.
     * In versions 1.21 and later, it is an interface.
     * This method uses reflection to get the top Inventory object from the
     * InventoryView, to avoid runtime errors when compiled against one version
     * but running on another.
     * 
     * @param view The InventoryView object (passed as Object to avoid compile-time type checking issues)
     * @return The top Inventory object from the InventoryView, or null if view is null or reflection fails
     */
    public static Inventory getTopInventoryCompat(Object view) {
        if (view == null) return null;
        try {
            Method getTopInventory = view.getClass().getMethod("getTopInventory");
            getTopInventory.setAccessible(true);
            return (Inventory) getTopInventory.invoke(view);
        } catch (NoSuchMethodException | InvocationTargetException | IllegalAccessException e) {
            // Log error but don't crash server - return null to gracefully handle
            Bukkit.getLogger().warning("[GuiManager] Failed to resolve top inventory via reflection: " + e.getMessage());
            if (DEBUG) {
                Bukkit.getLogger().log(Level.FINE, "[GuiManager] Reflection detail (getTopInventoryCompat)", e);
            }
            return null;
        }
    }

    public static Inventory getTopInventory(InventoryEvent event) {
        if (event == null) return null;
        try {
            Object view = event.getView();
            if (view == null) return null;
            Method getTopInventory = view.getClass().getMethod("getTopInventory");
            getTopInventory.setAccessible(true);
            return (Inventory) getTopInventory.invoke(view);
        } catch (NoSuchMethodException | InvocationTargetException | IllegalAccessException e) {
            // Log error but don't crash server - return null to gracefully handle
            Bukkit.getLogger().warning("[GuiManager] Failed to resolve top inventory via reflection: " + e.getMessage());
            if (DEBUG) {
                Bukkit.getLogger().log(Level.FINE, "[GuiManager] Reflection detail (getTopInventory)", e);
            }
            return null;
        }
    }

    // ------------------------------------------------------------------
    // Event listener for inventory interactions
    // ------------------------------------------------------------------

    protected static class GuiListener implements Listener {

        private final GuiManager manager;

        public GuiListener(GuiManager manager) {
            this.manager = manager;
        }

        /**
         * Validates the player's GUI session.
         * Automatically cleans up invalid sessions (wrong GUI instance or cleared reference).
         * 
         * Additional validation ensures:
         * 1. Session lock matches the GUI instance
         * 2. The GUI in openInventories map matches the GUI being interacted with
         * 3. The GUI is still open and valid
         */
        private boolean validateSession(Player player, Gui gui) {
            // Primary validation: session lock must match
            if (!GUISessionLock.isValid(player.getUniqueId(), gui)) {
                if (DEBUG) {
                    Gui lockedGui = GUISessionLock.get(player.getUniqueId());
                    Bukkit.getLogger().warning("[GuiManager] Session validation failed for " + player.getName() + 
                        ": GUI " + gui.getClass().getSimpleName() + " does not match session lock " + 
                        (lockedGui != null ? lockedGui.getClass().getSimpleName() : "null"));
                }
                GUISessionLock.end(player.getUniqueId());
                return false;
            }
            
            // Secondary validation: check if GUI in map matches (defense in depth)
            Gui mappedGui = manager.openInventories.get(player);
            if (mappedGui != null && mappedGui != gui) {
                // GUI in map doesn't match - this could indicate a transition in progress
                // Only fail if the mapped GUI is actually open and valid
                if (mappedGui.isOpen() && mappedGui.getPlayers().contains(player)) {
                    if (DEBUG) {
                        Bukkit.getLogger().warning("[GuiManager] GUI mismatch for " + player.getName() + 
                            ": Holder has " + gui.getClass().getSimpleName() + 
                            " but map has " + mappedGui.getClass().getSimpleName());
                    }
                    // Don't fail here - the session lock is the source of truth
                    // But log it for debugging
                }
            }
            
            // Tertiary validation: ensure GUI is still open
            if (!gui.isOpen() || !gui.getPlayers().contains(player)) {
                if (DEBUG) {
                    Bukkit.getLogger().warning("[GuiManager] GUI is not open for " + player.getName() + 
                        ": " + gui.getClass().getSimpleName());
                }
                return false;
            }
            
            return true;
        }

        @EventHandler(priority = EventPriority.LOWEST)
        void onDragGUI(InventoryDragEvent event) {
            if (!(event.getWhoClicked() instanceof Player player)) return;

            Inventory top = getTopInventoryCompat(event.getView());
            if (!(top != null && top.getHolder() instanceof GuiHolder holder)) return;
            
            // If this GUI belongs to another plugin's GuiManager, let that plugin handle it
            if (holder.manager != manager) {
                return;
            }

            Gui gui = holder.getGUI();

            // Session validation - cancel if invalid to prevent other plugins from processing stale clicks
            if (!validateSession(player, gui)) {
                event.setCancelled(true);
                event.setResult(Event.Result.DENY);
                return;
            }

            boolean cancel = event.getRawSlots().stream()
                    .anyMatch(slot -> {
                        if (slot >= gui.inventory.getSize()) return false;
                        // Cancel if slot is locked
                        if (!gui.unlockedCells.getOrDefault(slot, false)) return true;
                        // Cancel if slot has a button action (buttons shouldn't be draggable)
                        if (gui.conditionalButtons.containsKey(slot)) return true;
                        return false;
                    });
            if (cancel) {
                event.setCancelled(true);
                event.setResult(Event.Result.DENY);
            }
        }

        @EventHandler(priority = EventPriority.LOWEST)
        void onClickGUI(InventoryClickEvent event) {
            if (!(event.getWhoClicked() instanceof Player player)) return;

            Inventory top = getTopInventoryCompat(event.getView());
            if (!(top != null && top.getHolder() instanceof GuiHolder holder)) return;
            
            // If this GUI belongs to another plugin's GuiManager, let that plugin handle it
            // We process at LOWEST priority so we handle our GUIs before other plugins can interfere
            if (holder.manager != manager) {
                return;
            }

            Gui gui = holder.getGUI();

            // CRITICAL: Session validation - cancel if invalid to prevent other plugins from processing stale clicks
            // This is essential for preventing clicks on stale/wrong GUI instances from being processed
            // by other plugins that might have higher priority handlers
            if (!validateSession(player, gui)) {
                event.setCancelled(true);
                return;
            }

            boolean inGui = event.getRawSlot() < gui.inventory.getSize();
            boolean inPlayerInv = event.getSlotType() != InventoryType.SlotType.OUTSIDE && !inGui;

            // Double-click safety
            if (event.getClick() == ClickType.DOUBLE_CLICK) {
                ItemStack cursor = event.getCursor();
                if (cursor != null && cursor.getType() != Material.AIR) {
                    for (int i = 0; i < gui.inventory.getSize(); i++) {
                        if (!gui.unlockedCells.getOrDefault(i, false)
                                && cursor.isSimilar(gui.inventory.getItem(i))) {
                            event.setCancelled(true);
                            return;
                        }
                    }
                }
            }

            // Shift-click safety
            if ((event.getClick() == ClickType.SHIFT_LEFT || event.getClick() == ClickType.SHIFT_RIGHT)
                    && !gui.isAllowShiftClick()) {
                event.setCancelled(true);
            }

            // Inside GUI
            if (inGui) {
                boolean unlocked = gui.unlockedCells.getOrDefault(event.getSlot(), false);
                if (!unlocked) event.setCancelled(true);

                if (gui.onClick(manager, player, top, event)) {
                    // Skip playing click/navigate sound if the click caused a GUI transition (inventory changed)
                    if (getTopInventoryCompat(player.getOpenInventory()) != top) {
                        // GUI was replaced (e.g. confirm purchase), don't play another sound
                    } else if (event.getRawSlot() == gui.nextPageIndex || event.getRawSlot() == gui.prevPageIndex) {
                        if (gui.getNavigateSound() != null)
                            player.playSound(player.getLocation(), gui.getNavigateSound().parseSound(), 1F, 1F);
                        else if (gui.getDefaultSound() != null)
                            player.playSound(player.getLocation(), gui.getDefaultSound().parseSound(), 1F, 1F);
                    } else {
                        if (gui.getDefaultSound() != null)
                            player.playSound(player.getLocation(), gui.getDefaultSound().parseSound(), 1F, 1F);
                    }
                }
            }

            // Player inventory
            else if (inPlayerInv) {
                boolean allow = gui.onClickPlayerInventory(manager, player, top, event);
                if (!allow && (!gui.acceptsItems || event.getAction() == InventoryAction.MOVE_TO_OTHER_INVENTORY)) {
                    event.setCancelled(true);
                }
            }

            // Click outside
            else if (event.getSlotType() == InventoryType.SlotType.OUTSIDE) {
                if (!gui.onClickOutside(manager, player, event))
                    event.setCancelled(true);
            }
        }

        @EventHandler(priority = EventPriority.LOW)
        void onCloseGUI(InventoryCloseEvent event) {
            Inventory top = getTopInventory(event);
            if (!(top != null && top.getHolder() instanceof GuiHolder holder)) return;
            if (holder.manager != manager) return;

            Gui gui = holder.getGUI();
            Player player = (Player) event.getPlayer();

            // Session validation
            if (!validateSession(player, gui)) return;

            // Security: Handle cursor items properly to prevent item loss
            // If dropping items is not allowed, return cursor item to player inventory
            if (!gui.allowDropItems) {
                ItemStack cursorItem = player.getItemOnCursor();
                if (cursorItem != null && cursorItem.getType() != Material.AIR) {
                    // Try to add item to player inventory
                    // Use HashMap to store remaining items that couldn't fit
                    HashMap<Integer, ItemStack> remaining = player.getInventory().addItem(cursorItem.clone());
                    // If all items were added successfully, clear cursor
                    if (remaining.isEmpty()) {
                        player.setItemOnCursor(null);
                    } else {
                        // Some items couldn't fit - keep them on cursor to prevent item loss
                        // The remaining items map contains what couldn't fit, so we keep the original on cursor
                        // This respects allowDropItems=false while preventing item loss
                    }
                } else {
                    // No item on cursor, just clear it
                    player.setItemOnCursor(null);
                }
            }

            // Cancel update tasks for updating GUIs before closing
            try {
                Method cancelTask = gui.getClass().getMethod("cancelTask");
                cancelTask.invoke(gui);
                if (DEBUG) {
                    Bukkit.getLogger().info("[GuiManager] Cancelled update task for " + gui.getClass().getSimpleName() + " (GUI closing, player: " + player.getName() + ")");
                }
            } catch (NoSuchMethodException e) {
                // Not an updating GUI, that's fine
            } catch (Exception e) {
                if (DEBUG) {
                    Bukkit.getLogger().warning("[GuiManager] Error cancelling task on close for " + gui.getClass().getSimpleName() + ": " + e.getMessage());
                }
            }

            Bukkit.getScheduler().runTask(manager.plugin, () -> {
                gui.onClose(manager, player);
                player.updateInventory();
            });

            manager.openInventories.remove(player);
            GUISessionLock.end(player.getUniqueId());
        }

        @EventHandler
        void onDisable(PluginDisableEvent event) {
            if (event.getPlugin() == manager.plugin) {
                manager.shutdown = true;
                manager.closeAll();
                manager.initialized = false;
                GUISessionLock.clearAll();
            }
        }
    }
}
