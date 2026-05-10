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

package ca.tweetzy.flight.gui;

import org.bukkit.Bukkit;
import ca.tweetzy.flight.comp.enums.CompMaterial;
import ca.tweetzy.flight.comp.enums.CompSound;
import ca.tweetzy.flight.gui.events.GuiClickEvent;
import ca.tweetzy.flight.gui.events.GuiCloseEvent;
import ca.tweetzy.flight.gui.events.GuiDropItemEvent;
import ca.tweetzy.flight.gui.events.GuiOpenEvent;
import ca.tweetzy.flight.gui.events.GuiPageEvent;
import ca.tweetzy.flight.gui.methods.Clickable;
import ca.tweetzy.flight.gui.methods.Closable;
import ca.tweetzy.flight.gui.methods.Delayable;
import ca.tweetzy.flight.gui.methods.Droppable;
import ca.tweetzy.flight.gui.methods.Openable;
import ca.tweetzy.flight.gui.methods.Pagable;
import ca.tweetzy.flight.gui.config.GuiConfig;
import ca.tweetzy.flight.gui.config.GuiConfigButton;
import ca.tweetzy.flight.gui.config.GuiConfigContext;
import ca.tweetzy.flight.gui.config.GuiConfigDynamicRenderer;
import ca.tweetzy.flight.gui.config.GuiConfigExpressionEngine;
import ca.tweetzy.flight.gui.config.GuiConfigItemParser;
import ca.tweetzy.flight.gui.config.GuiConfigLoader;
import ca.tweetzy.flight.gui.config.GuiConfigSlotHelper;
import ca.tweetzy.flight.gui.config.action.GuiConfigActionRegistry;
import ca.tweetzy.flight.utils.Common;
import ca.tweetzy.flight.utils.QuickItem;
import ca.tweetzy.flight.utils.input.InputSessionLock;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.stream.IntStream;

public class Gui {

    // Core inventory fields
    protected Inventory inventory;
    protected String title;
    protected GuiType inventoryType = GuiType.STANDARD;
    protected int rows;
    protected int page = 1;
    protected int pages = 1;

    // Behavior flags
    protected boolean acceptsItems = false;
    protected boolean allowDropItems = true;
    protected boolean allowClose = true;
    protected boolean useLockedCells = false;
    protected boolean allowShiftClick = false;
    protected boolean isTransitioning = false;

    // Slot management
    // Thread Safety: These collections use HashMap (not ConcurrentHashMap) because all GUI operations
    // are guaranteed to run on the main thread. Bukkit events (clicks, opens, closes) execute on
    // the main thread, and all GUI manipulation methods should be called from the main thread only.
    // If async access is ever needed, these should be changed to ConcurrentHashMap or properly synchronized.
    protected final Map<Integer, Boolean> unlockedCells = new ConcurrentHashMap<>();
    protected final Map<Integer, ItemStack> cellItems = new ConcurrentHashMap<>();
    protected final Map<Integer, Map<Object, Clickable>> conditionalButtons = new ConcurrentHashMap<>();

    // Sentinel objects for null keys/values in ConcurrentHashMap (which doesn't allow nulls)
    // These are used to represent null ClickType keys and null Clickable values
    private static final Object NULL_CLICK_TYPE_SENTINEL = new Object();
    private static final Clickable NULL_CLICKABLE_SENTINEL = event -> {}; // Empty implementation

    // Performance: Track dirty slots for efficient updates
    // Only slots in this set will be updated when update() is called, improving performance for large GUIs
    protected final Set<Integer> dirtySlots = new HashSet<>();
    protected boolean allSlotsDirty = false; // If true, update all slots (used for full refresh)

    // Default items
    protected ItemStack blankItem = QuickItem.of(CompMaterial.BLACK_STAINED_GLASS_PANE).name(" ").lore(" ").make();
    protected static final ItemStack AIR = CompMaterial.AIR.parseItem();

    // Pagination items
    protected int nextPageIndex = -1, prevPageIndex = -1;
    protected ItemStack nextPageItem = blankItem, prevPageItem = blankItem;
    protected ItemStack nextPage, prevPage;
    protected Gui parent = null;

    // Event handlers
    protected Clickable defaultClicker = null;
    protected Clickable privateDefaultClicker = null;
    protected Clickable playerInvClicker = null;
    protected Delayable delayClicker = null;
    protected Openable opener = null;
    protected Closable closer = null;
    protected Droppable dropper = null;
    protected Pagable pager = null;

    // Sounds
    protected CompSound defaultSound = CompSound.UI_BUTTON_CLICK;
    protected CompSound navigateSound = CompSound.ENTITY_BAT_TAKEOFF;

    // Click delays
    protected final Map<Integer, Long> slotLastClicked = new HashMap<>();
    protected final Map<Integer, Long> slotClickDelays = new HashMap<>();
    protected long globalClickDelay = -1;
    protected long globalLastClicked = -1;
    protected boolean globalClickInitialized = false;

    protected boolean open = false;
    protected GuiManager guiManager;

    // Config support (optional)
    protected GuiConfig guiConfig;
    protected GuiConfigContext configContext;
    protected GuiConfigLoader configLoader;

    // Constructors
    public Gui() {
        this.rows = 3;
    }

    public Gui(@NotNull GuiType type) {
        this.inventoryType = type;
        this.rows = switch (type) {
            case HOPPER, DISPENSER -> 1;
            default -> 3;
        };
    }

    public Gui(@Nullable Gui parent) {
        this(3, parent);
    }

    public Gui(int rows) {
        this.rows = Math.max(1, Math.min(6, rows));
    }

    public Gui(int rows, @Nullable Gui parent) {
        this.parent = parent;
        this.rows = Math.max(1, Math.min(6, rows));
    }

    // --------------------------------------
    // Public API Methods
    // --------------------------------------

    @NotNull
    public List<Player> getPlayers() {
        return inventory == null ? Collections.emptyList()
                : inventory.getViewers().stream()
                .filter(Player.class::isInstance)
                .map(Player.class::cast)
                .toList();
    }

    /**
     * Returns true if the given player currently has an active input session.
     * This is useful for GUIs to check if they should skip certain operations
     * (like returning items) when an input is active.
     *
     * @param player The player to check
     * @return true if the player has an active input, false otherwise
     */
    public static boolean hasActiveInput(@NotNull Player player) {
        return InputSessionLock.hasActiveInput(player);
    }

    @NotNull
    public Gui setGlobalClickDelay(long delay) {
        this.globalClickDelay = delay;
        return this;
    }

    @NotNull
    public Gui setSlotClickDelay(int slot, long delay) {
        this.slotClickDelays.put(slot, delay);
        return this;
    }

    @NotNull
    public Gui setSlotClickDelay(int row, int col, long delay) {
        final int cell = col + row * inventoryType.columns;
        this.slotClickDelays.put(cell, delay);
        return this;
    }

    @NotNull
    public Gui setAction(int cell, @Nullable Clickable action) {
        setConditional(cell, null, action);
        return this;
    }

    @NotNull
    public Gui setAction(int row, int col, @Nullable Clickable action) {
        setConditional(col + row * inventoryType.columns, null, action);
        return this;
    }

    @NotNull
    public Gui setAction(int cell, @Nullable ClickType type, @Nullable Clickable action) {
        setConditional(cell, type, action);
        return this;
    }

    @NotNull
    public Gui setAction(int row, int col, @Nullable ClickType type, @Nullable Clickable action) {
        setConditional(col + row * inventoryType.columns, type, action);
        return this;
    }

    @NotNull
    public Gui setActionForRange(int cellFirst, int cellLast, @Nullable Clickable action) {
        for (int cell = cellFirst; cell <= cellLast; ++cell) {
            setConditional(cell, null, action);
        }
        return this;
    }

    @NotNull
    public Gui setActionForRange(int cellRowFirst, int cellColFirst, int cellRowLast, int cellColLast, @Nullable Clickable action) {
        final int last = cellColLast + cellRowLast * inventoryType.columns;
        for (int cell = cellColFirst + cellRowFirst * inventoryType.columns; cell <= last; ++cell) {
            setConditional(cell, null, action);
        }
        return this;
    }

    @NotNull
    public Gui setActionForRange(int cellFirst, int cellLast, @Nullable ClickType type, @Nullable Clickable action) {
        for (int cell = cellFirst; cell <= cellLast; ++cell) {
            setConditional(cell, type, action);
        }
        return this;
    }

    @NotNull
    public Gui setActionForRange(int cellRowFirst, int cellColFirst, int cellRowLast, int cellColLast, @Nullable ClickType type, @Nullable Clickable action) {
        final int last = cellColLast + cellRowLast * inventoryType.columns;
        for (int cell = cellColFirst + cellRowFirst * inventoryType.columns; cell <= last; ++cell) {
            setConditional(cell, type, action);
        }
        return this;
    }

    @NotNull
    public Gui clearActions(int cell) {
        conditionalButtons.remove(cell);
        return this;
    }

    @NotNull
    public Gui clearActions(int row, int col) {
        return clearActions(col + row * inventoryType.columns);
    }

    @NotNull
    public Gui setOnOpen(@Nullable Openable action) {
        opener = action;
        return this;
    }

    @NotNull
    public Gui setOnClose(@Nullable Closable action) {
        closer = action;
        return this;
    }

    @NotNull
    public Gui setOnDrop(@Nullable Droppable action) {
        dropper = action;
        return this;
    }

    public boolean isOpen() {
        if (inventory != null && inventory.getViewers().isEmpty()) open = false;
        return open;
    }

    public boolean getAcceptsItems() {
        return acceptsItems;
    }

    public Gui setAcceptsItems(boolean acceptsItems) {
        this.acceptsItems = acceptsItems;
        return this;
    }

    public boolean getAllowDrops() {
        return allowDropItems;
    }

    public Gui setAllowDrops(boolean allow) {
        this.allowDropItems = allow;
        return this;
    }

    public boolean getAllowClose() {
        return allowClose;
    }

    public Gui setAllowClose(boolean allow) {
        this.allowClose = allow;
        return this;
    }

    public boolean isTransitioning() {
        return isTransitioning;
    }

    public boolean isAllowShiftClick() {
        return allowShiftClick;
    }

    public void setAllowShiftClick(boolean allowShiftClick) {
        this.allowShiftClick = allowShiftClick;
    }

    @NotNull
    public Gui setTitle(String title) {
        if (title == null) title = "";
        if (!Objects.equals(title, this.title)) {
            this.title = title;
            if (inventory != null) {
                List<Player> viewers = getPlayers();
                boolean prevAllowClose = allowClose;
                exit();
                Inventory old = inventory;
                createInventory();
                inventory.setContents(old.getContents());
                viewers.forEach(p -> p.openInventory(inventory));
                allowClose = prevAllowClose;
            }
        }
        return this;
    }

    public int getRows() {
        return rows;
    }

    @NotNull
    public Gui setRows(int rows) {
        if (inventoryType != GuiType.HOPPER && inventoryType != GuiType.DISPENSER)
            this.rows = Math.max(1, Math.min(6, rows));
        return this;
    }

    @NotNull
    public Gui setDefaultAction(@Nullable Clickable action) {
        defaultClicker = action;
        return this;
    }

    @NotNull
    protected Gui setPrivateDefaultAction(@Nullable Clickable action) {
        privateDefaultClicker = action;
        return this;
    }

    @NotNull
    protected Gui setPlayerInventoryAction(@Nullable Clickable action) {
        playerInvClicker = action;
        return this;
    }

    @NotNull
    protected Gui setClickDelayAction(@Nullable Delayable action) {
        delayClicker = action;
        return this;
    }

    @NotNull
    public Gui setDefaultItem(@Nullable ItemStack item) {
        blankItem = item;
        return this;
    }

    @Nullable
    public ItemStack getDefaultItem() {
        return blankItem;
    }

    public Gui close() {
        allowClose = true;
        getPlayers().forEach(Player::closeInventory);
        return this;
    }

    /**
     * Safely transitions from this GUI to a new GUI.
     * This prevents setOnClose handlers from running during the transition.
     *
     * @param manager The GuiManager instance
     * @param player The player to transition
     * @param newGui The new GUI to show
     */
    /**
     * Safely transitions from this GUI to a new GUI.
     * This method sets the transition flag to prevent setOnClose handlers from running
     * and ensures proper session lock management.
     *
     * Security features:
     * - Validates that this GUI is the active session for the player
     * - Prevents transition if player is offline
     * - Ensures new GUI is not null
     *
     * @param manager The GuiManager instance
     * @param player The player transitioning
     * @param newGui The new GUI to transition to (must not be null)
     * @throws IllegalArgumentException if newGui is null or player is offline
     */
    public void transitionTo(@NotNull GuiManager manager, @NotNull Player player, @NotNull Gui newGui) {
        // Security validation: ensure player is online and valid
        if (player == null || !player.isOnline()) {
            throw new IllegalArgumentException("Cannot transition GUI: player is offline or null");
        }

        // Security validation: ensure new GUI is not null
        if (newGui == null) {
            throw new IllegalArgumentException("Cannot transition to null GUI");
        }

        // Security validation: ensure this GUI is still the active session
        // This prevents transitions from stale or non-current GUI instances
        if (!GUISessionLock.isValid(player.getUniqueId(), this)) {
            // If this GUI is not valid, just show the new GUI without transition flag
            // This handles edge cases where the session is no longer valid
            manager.showGUI(player, newGui);
            return;
        }

        // CRITICAL: Set transition flags BEFORE showing new GUI
        // This ensures that:
        // 1. setOnClose handlers won't run during transition
        // 2. Any clicks that arrive during transition will see the flags set
        // 3. The GUI is marked as transitioning before session lock is updated
        this.isTransitioning = true;
        this.allowClose = true;
        this.open = false; // Mark as closed to prevent further interactions

        // Show the new GUI - it will replace this one
        // The showGUI method will handle updating the session lock atomically
        manager.showGUI(player, newGui);
    }


    public Gui exit() {
        allowClose = true;
        open = false;
        getPlayers().forEach(Player::closeInventory);
        return this;
    }

    @Nullable
    public Gui getParent() {
        return parent;
    }

    @NotNull
    public GuiType getType() {
        return inventoryType;
    }

    @NotNull
    public Gui setDefaultSound(CompSound sound) {
        defaultSound = sound;
        return this;
    }

    @NotNull
    public Gui setNavigateSound(CompSound sound) {
        navigateSound = sound;
        return this;
    }

    public CompSound getDefaultSound() {
        return defaultSound;
    }

    public CompSound getNavigateSound() {
        return navigateSound;
    }

    public void setUseLockedCells(boolean useLockedCells) {
        this.useLockedCells = useLockedCells;
    }

    public boolean isUseLockedCells() {
        return useLockedCells;
    }

    // --------------------------------------
    // Item and slot methods
    // --------------------------------------

    @Nullable
    public ItemStack getItem(int cell) {
        return inventory != null && unlockedCells.getOrDefault(cell, false) ? inventory.getItem(cell) : cellItems.get(cell);
    }

    @Nullable
    public ItemStack getItem(int row, int col) {
        return getItem(col + row * inventoryType.columns);
    }

    @NotNull
    public Gui setItem(int cell, @Nullable ItemStack item) {
        // Security: Validate slot bounds even when inventory is null
        int maxSlot = rows * inventoryType.columns - 1;
        if (cell < 0 || cell > maxSlot) {
            return this; // Invalid slot, ignore
        }

        // Security: Clone ItemStack to prevent reference sharing and potential duplication exploits
        // This ensures that external modifications to the original ItemStack don't affect the GUI
        ItemStack clonedItem = item != null ? item.clone() : null;
        cellItems.put(cell, clonedItem);

        // Performance: Mark slot as dirty for efficient updates
        dirtySlots.add(cell);

        if (inventory != null && cell >= 0 && cell < inventory.getSize()) {
            inventory.setItem(cell, clonedItem);
        }
        return this;
    }

    @NotNull
    public Gui setItem(int row, int col, @Nullable ItemStack item) {
        return setItem(col + row * inventoryType.columns, item);
    }

    @NotNull
    public Gui setItems(int[] cells, @Nullable ItemStack item) {
        Arrays.stream(cells).forEach(i -> setItem(i, item));
        return this;
    }

    @NotNull
    public Gui setItems(int start, int end, @Nullable ItemStack item) {
        IntStream.rangeClosed(start, end).forEach(i -> setItem(i, item));
        return this;
    }

    @NotNull
    public Gui setButton(int cell, @Nullable ItemStack item, @Nullable Clickable action) {
        setItem(cell, item);
        setConditional(cell, null, action);
        return this;
    }

    @NotNull
    public Gui setButton(int row, int col, @Nullable ItemStack item, @Nullable Clickable action) {
        return setButton(col + row * inventoryType.columns, item, action);
    }

    @NotNull
    public Gui setButton(int cell, @Nullable ItemStack item, @Nullable ClickType type, @Nullable Clickable action) {
        setItem(cell, item);
        setConditional(cell, type, action);
        return this;
    }

    @NotNull
    public Gui setButton(int row, int col, @Nullable ItemStack item, @Nullable ClickType type, @Nullable Clickable action) {
        return setButton(col + row * inventoryType.columns, item, type, action);
    }

    // --------------------------------------
    // Unlock cells
    // --------------------------------------

    @NotNull
    public Gui setUnlocked(int cell) {
        unlockedCells.put(cell, true);
        return this;
    }

    @NotNull
    public Gui setUnlocked(int row, int col) {
        return setUnlocked(col + row * inventoryType.columns);
    }

    @NotNull
    public Gui setUnlocked(int cell, boolean open) {
        unlockedCells.put(cell, open);
        return this;
    }

    @NotNull
    public Gui setUnlocked(int row, int col, boolean open) {
        return setUnlocked(col + row * inventoryType.columns, open);
    }

    @NotNull
    public Gui setUnlockedRange(int start, int end) {
        return setUnlockedRange(start, end, true);
    }

    @NotNull
    public Gui setUnlockedRange(int start, int end, boolean open) {
        IntStream.rangeClosed(start, end).forEach(i -> unlockedCells.put(i, open));
        return this;
    }

    @NotNull
    public Gui setUnlockedRange(int rowStart, int colStart, int rowEnd, int colEnd) {
        return setUnlockedRange(rowStart, colStart, rowEnd, colEnd, true);
    }

    @NotNull
    public Gui setUnlockedRange(int rowStart, int colStart, int rowEnd, int colEnd, boolean open) {
        int last = colEnd + rowEnd * inventoryType.columns;
        for (int i = colStart + rowStart * inventoryType.columns; i <= last; i++) unlockedCells.put(i, open);
        return this;
    }

    @NotNull
    public Gui mirrorFill(int row, int col, boolean mirrorRow, boolean mirrorCol, ItemStack item) {
        setItem(row, col, item);
        if (mirrorRow)
            setItem(rows - row - 1, col, item);
        if (mirrorCol)
            setItem(row, 8 - col, item);
        if (mirrorRow && mirrorCol)
            setItem(rows - row - 1, 8 - col, item);
        return this;
    }

    // --------------------------------------
    // Pagination
    // --------------------------------------

    public void setPages(int pages) {
        this.pages = Math.max(1, pages);
        if (page > pages) setPage(pages);
    }

    public void setPage(int page) {
        changePage(page - this.page);
    }

    public void changePage(int direction) {
        setPageInternal(this.page + direction);
    }

    private void setPageInternal(int newPage) {
        int lastPage = page;
        page = Math.max(1, Math.min(pages, newPage));
        if (pager != null && page != lastPage) {
            pager.onPageChange(new GuiPageEvent(this, guiManager, lastPage, page));
            updatePageNavigation();
        }
    }

    /**
     * Set a listener for page change events.
     * Called when the page is changed using next/prev or setPage().
     */
    @NotNull
    public Gui setOnPage(@Nullable Pagable pager) {
        this.pager = pager;
        return this;
    }

    public void nextPage() {
        if (page < pages) setPageInternal(page + 1);
    }

    public void prevPage() {
        if (page > 1) setPageInternal(page - 1);
    }

    public void setNextPage(int cell, @NotNull ItemStack item) {
        nextPageIndex = cell;
        nextPage = item;
        // Unlock the slot so clicks work
        setUnlocked(cell, true);
        // Always set the button, but conditionally set the action
        setButton(cell, page < pages ? item : (nextPageItem != null ? nextPageItem : item), ClickType.LEFT, page < pages ? e -> nextPage() : null);
        // Also set for all click types to ensure it works
        if (page < pages) {
            setConditional(cell, null, e -> nextPage());
        } else {
            setConditional(cell, null, null);
        }
    }

    public void setNextPage(int row, int col, @NotNull ItemStack item) {
        setNextPage(col + row * inventoryType.columns, item);
    }

    public void setPrevPage(int cell, @NotNull ItemStack item) {
        prevPageIndex = cell;
        prevPage = item;
        // Unlock the slot so clicks work
        setUnlocked(cell, true);
        // Always set the button, but conditionally set the action
        setButton(cell, page > 1 ? item : (prevPageItem != null ? prevPageItem : item), ClickType.LEFT, page > 1 ? e -> prevPage() : null);
        // Also set for all click types to ensure it works
        if (page > 1) {
            setConditional(cell, null, e -> prevPage());
        } else {
            setConditional(cell, null, null);
        }
    }

    public void setPrevPage(int row, int col, @NotNull ItemStack item) {
        setPrevPage(col + row * inventoryType.columns, item);
    }

    protected void updatePageNavigation() {
        if (prevPage != null) {
            // Ensure slot is unlocked
            setUnlocked(prevPageIndex, true);
            setButton(prevPageIndex, page > 1 ? prevPage : prevPageItem, ClickType.LEFT, page > 1 ? e -> prevPage() : null);
            // Also set for all click types to ensure it works
            if (page > 1) {
                setConditional(prevPageIndex, null, e -> prevPage());
            } else {
                setConditional(prevPageIndex, null, null);
            }
        }
        if (nextPage != null) {
            // Ensure slot is unlocked
            setUnlocked(nextPageIndex, true);
            setButton(nextPageIndex, page < pages ? nextPage : nextPageItem, ClickType.LEFT, page < pages ? e -> nextPage() : null);
            // Also set for all click types to ensure it works
            if (page < pages) {
                setConditional(nextPageIndex, null, e -> nextPage());
            } else {
                setConditional(nextPageIndex, null, null);
            }
        }
    }

    // --------------------------------------
    // Inventory creation
    // --------------------------------------

    @NotNull
    protected Inventory getOrCreateInventory(@NotNull GuiManager manager) {
        return inventory != null ? inventory : generateInventory(manager);
    }

    @NotNull
    protected Inventory generateInventory(@NotNull GuiManager manager) {
        this.guiManager = manager;
        createInventory();
        int size = rows * inventoryType.columns;
        // Performance: Mark all slots as dirty since we're initializing the entire inventory
        markAllSlotsDirty();
        for (int i = 0; i < size; i++) inventory.setItem(i, cellItems.getOrDefault(i, unlockedCells.getOrDefault(i, false) ? AIR : blankItem));
        // Clear dirty state after initial population
        allSlotsDirty = false;
        dirtySlots.clear();
        return inventory;
    }

    protected void createInventory() {
        InventoryType type = inventoryType == null ? InventoryType.CHEST : inventoryType.type;
        GuiHolder holder = new GuiHolder(guiManager, this);
        if (type == InventoryType.HOPPER || type == InventoryType.DISPENSER)
            inventory = holder.newInventory(type, title == null ? "" : Common.colorize(trimTitle(title)));
        else
            inventory = holder.newInventory(rows * 9, title == null ? "" : Common.colorize(trimTitle(title)));
    }

    // --------------------------------------
    // Event handling
    // --------------------------------------

    protected boolean onClick(@NotNull GuiManager manager, @NotNull Player player,
                              @NotNull Inventory inventory, @NotNull InventoryClickEvent event) {

        // --- SESSION VALIDATION ---
        // Security: Validate GUI session lock - prevents old/delayed packets from interacting
        // This is critical for preventing duplication exploits
        if (!GUISessionLock.isValid(player.getUniqueId(), this)) {
            event.setCancelled(true);
            // Don't process click if session is invalid
            return false;
        }

        int cell = event.getSlot();
        Map<Object, Clickable> conditionals = conditionalButtons.get(cell);

        Clickable button = null;
        if (conditionals != null) {
            // Try to get the button for the specific click type first
            Clickable specificButton = conditionals.get(event.getClick());
            if (specificButton != null) {
                button = specificButton;
            } else {
                // Fall back to the null key (stored as sentinel)
                button = conditionals.get(NULL_CLICK_TYPE_SENTINEL);
            }
            // Convert sentinel back to null if needed
            if (button == NULL_CLICKABLE_SENTINEL) {
                button = null;
            }
        }

        if (button != null) {
            long now = System.currentTimeMillis();

            // Global click delay - properly initialized to prevent first-click bypass
            if (globalClickDelay != -1) {
                if (globalClickInitialized && now - globalLastClicked < globalClickDelay) {
                    if (delayClicker != null)
                        delayClicker.onClick(globalLastClicked, globalClickDelay,
                                new GuiClickEvent(manager, this, player, event, cell, true));
                    return false;
                }
                globalLastClicked = now;
                globalClickInitialized = true;
            }

            // Slot-specific delay
            long slotDelay = slotClickDelays.getOrDefault(cell, -1L);
            long lastSlotClick = slotLastClicked.getOrDefault(cell, -1L);
            if (slotDelay != -1 && lastSlotClick != -1 && now - lastSlotClick < slotDelay) {
                if (delayClicker != null)
                    delayClicker.onClick(lastSlotClick, slotDelay,
                            new GuiClickEvent(manager, this, player, event, cell, true));
                return false;
            }
            slotLastClicked.put(cell, now);

            // --- DOUBLE-CLICK EXPLOIT PREVENTION ---
            if (event.getClick() == ClickType.DOUBLE_CLICK) {
                ItemStack cursor = event.getCursor();
                if (cursor != null && cursor.getType() != Material.AIR) {
                    for (int i = 0; i < inventory.getSize(); i++) {
                        if (!unlockedCells.getOrDefault(i, false) &&
                                cursor.isSimilar(inventory.getItem(i))) {
                            event.setCancelled(true);
                            return false;
                        }
                    }
                }
            }

            // --- SHIFT-CLICK PROTECTION ---
            if ((event.getClick() == ClickType.SHIFT_LEFT || event.getClick() == ClickType.SHIFT_RIGHT)
                    && !allowShiftClick) {
                event.setCancelled(true);
                return false;
            }

            button.onClick(new GuiClickEvent(manager, this, player, event, cell, true));
            return true;
        } else {
            if (defaultClicker != null)
                defaultClicker.onClick(new GuiClickEvent(manager, this, player, event, cell, true));
            else if (privateDefaultClicker != null)
                privateDefaultClicker.onClick(new GuiClickEvent(manager, this, player, event, cell, true));
            return button != null;
        }
    }

    protected boolean onClickPlayerInventory(@NotNull GuiManager manager, @NotNull Player player,
                                             @NotNull Inventory openInv, @NotNull InventoryClickEvent event) {

        // Security: Validate GUI session lock - prevents old/delayed packets from interacting
        // This is critical for preventing duplication exploits
        if (!GUISessionLock.isValid(player.getUniqueId(), this)) {
            event.setCancelled(true);
            // Don't process click if session is invalid
            return false;
        }

        if (playerInvClicker == null) return false;
        playerInvClicker.onClick(new GuiClickEvent(manager, this, player, event, event.getSlot(), true));

        if (!acceptsItems || event.getAction() == InventoryAction.MOVE_TO_OTHER_INVENTORY) {
            event.setCancelled(true);
            return false;
        }

        return true;
    }

    protected boolean onClickOutside(@NotNull GuiManager manager, @NotNull Player player,
                                     @NotNull InventoryClickEvent event) {

        // Security: Validate GUI session lock - prevents old/delayed packets from interacting
        // This is critical for preventing duplication exploits
        if (!GUISessionLock.isValid(player.getUniqueId(), this)) {
            event.setCancelled(true);
            // Don't process click if session is invalid
            return false;
        }

        if (!allowDropItems) {
            event.setCancelled(true);
            return false;
        }

        return dropper == null || dropper.onDrop(new GuiDropItemEvent(manager, this, player, event));
    }

    protected void onOpen(@NotNull GuiManager manager, @NotNull Player player) {
        // Security: Validate player is online before opening
        if (player == null || !player.isOnline()) {
            return;
        }

        // --- REGISTER SESSION ---
        // This prevents old/delayed client packets from interacting with previous GUI instances
        GUISessionLock.start(player.getUniqueId(), this);
        open = true;
        guiManager = manager;

        // Initialize config context if config is loaded
        initConfigContext(player);

        // Re-apply config buttons now that context is initialized
        if (guiConfig != null && configContext != null) {
            for (GuiConfigButton button : guiConfig.getButtons().values()) {
                if (button.isEnabled()) {
                    applyButton(button);
                }
            }

            // Render dynamic content
            GuiConfigDynamicRenderer.renderDynamicContent(this, guiConfig, configContext);
        }

        // Reset transition flag when opening (in case of edge cases)
        isTransitioning = false;

        if (opener != null) {
            try {
                opener.onOpen(new GuiOpenEvent(manager, this, player));
            } catch (Exception e) {
                // Log error but don't crash - GUI is still opened
                Bukkit.getLogger().log(Level.SEVERE,
                        "Error in GUI open handler for " + this.getClass().getSimpleName(), e);
            }
        }
    }

    protected void onClose(@NotNull GuiManager manager, @NotNull Player player) {
        // Security: Validate player is still online before processing close
        if (player == null || !player.isOnline()) {
            // Player disconnected, just clean up session lock
            GUISessionLock.end(player != null ? player.getUniqueId() : null);
            return;
        }

        if (!allowClose) {
            // Security: Validate this GUI is still the active session before reopening
            if (GUISessionLock.isValid(player.getUniqueId(), this)) {
                manager.showGUI(player, this);
            }
            return;
        }

        // End session lock - this prevents old GUI instances from being interacted with
        GUISessionLock.end(player.getUniqueId());
        boolean showParent = open && parent != null;

        // Don't run setOnClose handler if we're transitioning to another GUI
        // This prevents unwanted reopening and allows smooth transitions
        // Security: Always run close handler if not transitioning to ensure items are returned
        if (!isTransitioning && closer != null) {
            try {
                closer.onClose(new GuiCloseEvent(manager, this, player));
            } catch (Exception e) {
                // Log error but don't crash - ensure session is still cleaned up
                Bukkit.getLogger().log(Level.SEVERE,
                        "Error in GUI close handler for " + this.getClass().getSimpleName(), e);
            }
        }

        // Reset transition flag after close handler check
        boolean wasTransitioning = isTransitioning;
        isTransitioning = false;

        // Security: Validate parent GUI before reopening to prevent exploitation
        // Only show parent if we're not transitioning (transitioning means we're going to a specific GUI)
        if (!wasTransitioning && showParent && parent != null) {
            // Security checks:
            // 1. Ensure parent is not the same as current (prevents infinite loops)
            // 2. Ensure parent is a valid GUI instance
            // 3. Ensure player is still online
            if (parent != this && player.isOnline()) {
                // Additional security: validate parent GUI is not null and is a valid instance
                try {
                    manager.showGUI(player, parent);
                } catch (Exception e) {
                    // If showing parent fails, log but don't crash
                    Bukkit.getLogger().warning("Failed to show parent GUI: " + e.getMessage());
                }
            }
        }
    }

    /**
     * Updates the GUI inventory with current item data.
     * Performance: Only updates slots that have been marked as dirty (modified),
     * unless allSlotsDirty is true, in which case all slots are updated.
     */
    public void update() {
        if (inventory == null) return;
        int size = rows * inventoryType.columns;

        if (allSlotsDirty) {
            // Full refresh: update all slots
            for (int i = 0; i < size; i++) {
                inventory.setItem(i, cellItems.getOrDefault(i, unlockedCells.getOrDefault(i, false) ? AIR : blankItem));
            }
            allSlotsDirty = false;
            dirtySlots.clear();
        } else if (!dirtySlots.isEmpty()) {
            // Partial update: only update dirty slots
            for (int i : dirtySlots) {
                if (i >= 0 && i < size) {
                    inventory.setItem(i, cellItems.getOrDefault(i, unlockedCells.getOrDefault(i, false) ? AIR : blankItem));
                }
            }
            dirtySlots.clear();
        }
    }

    /**
     * Marks all slots as dirty, forcing a full refresh on next update().
     * Useful when you want to ensure all slots are updated regardless of dirty tracking.
     */
    public void markAllSlotsDirty() {
        allSlotsDirty = true;
        dirtySlots.clear();
    }

    public void reset() {
        if (inventory != null) inventory.clear();
        cellItems.clear();
        unlockedCells.clear();
        conditionalButtons.clear();
        dirtySlots.clear();
        allSlotsDirty = false;
        page = 1;
    }

    // --------------------------------------
    // Utility
    // --------------------------------------

    private static String trimTitle(String title) {
        if (title == null) return "";
        return title;
    }

    protected void setConditional(int cell, @Nullable ClickType type, @Nullable Clickable clicker) {
        // Convert null to sentinel objects since ConcurrentHashMap doesn't allow null keys/values
        Object key = type != null ? type : NULL_CLICK_TYPE_SENTINEL;
        Clickable value = clicker != null ? clicker : NULL_CLICKABLE_SENTINEL;
        conditionalButtons.computeIfAbsent(cell, k -> new ConcurrentHashMap<>()).put(key, value);
    }

    // --------------------------------------
    // Config Support (Optional)
    // --------------------------------------

    /**
     * Set the config loader for this GUI.
     * Must be called before loadFromConfig().
     */
    @NotNull
    public Gui setConfigLoader(@Nullable GuiConfigLoader loader) {
        this.configLoader = loader;
        return this;
    }

    /**
     * Load configuration from a config file.
     * This is an optional feature - GUIs can still be configured programmatically.
     *
     * @param configName The name of the config file (without .yml extension)
     * @return true if config was loaded successfully, false otherwise
     */
    public boolean loadFromConfig(@NotNull String configName) {
        if (configLoader == null) {
            // Try to get loader from GuiManager's plugin
            if (guiManager != null && guiManager.getPlugin() != null) {
                configLoader = new GuiConfigLoader((Plugin) guiManager.getPlugin());
            } else {
                return false;
            }
        }

        guiConfig = configLoader.loadConfig(configName);
        if (guiConfig == null) {
            return false;
        }

        // Apply config settings
        applyConfig();
        return true;
    }

    /**
     * Apply the loaded config to this GUI.
     */
    protected void applyConfig() {
        if (guiConfig == null) {
            return;
        }

        // Apply title
        if (guiConfig.getTitle() != null) {
            setTitle(guiConfig.getTitle());
        }

        // Apply rows
        if (guiConfig.getRows() > 0) {
            setRows(guiConfig.getRows());
        }

        // Apply type
        if (guiConfig.getType() != null) {
            this.inventoryType = guiConfig.getType();
        }

        // Apply background
        if (guiConfig.getBackground() != null && guiConfig.getBackground().isEnabled()) {
            applyBackground(guiConfig.getBackground());
        }

        // Apply buttons
        for (GuiConfigButton button : guiConfig.getButtons().values()) {
            if (button.isEnabled()) {
                applyButton(button);
            }
        }

        // Dynamic content will be rendered when context is initialized (in onOpen)
    }

    /**
     * Apply background from config.
     */
    protected void applyBackground(@NotNull GuiConfig.GuiConfigBackground background) {
        if (background.getMaterial() != null) {
            CompMaterial material = CompMaterial.matchCompMaterial(background.getMaterial()).orElse(CompMaterial.BLACK_STAINED_GLASS_PANE);
            ItemStack bgItem = QuickItem.of(material).name(background.getName() != null ? background.getName() : " ").make();
            setDefaultItem(bgItem);
        }

        // Apply to specific slots if specified
        if (background.getSlots() != null && !background.getSlots().isEmpty()) {
            List<Integer> slots = GuiConfigSlotHelper.parseSlots(background.getSlots());
            ItemStack bgItem = getDefaultItem();
            for (int slot : slots) {
                setItem(slot, bgItem);
            }
        }
    }

    /**
     * Apply a button from config.
     */
    protected void applyButton(@NotNull GuiConfigButton button) {
        if (configContext == null) {
            // Can't apply buttons without context (need player)
            return;
        }

        // Check condition
        if (button.getCondition() != null) {
            if (!GuiConfigExpressionEngine.evaluateBoolean(button.getCondition(), configContext)) {
                return; // Condition not met, skip button
            }
        }

        // Parse item
        ItemStack item = GuiConfigItemParser.parseItem(button, configContext);

        // Apply to slots
        for (int slot : button.getSlots()) {
            if (button.getAction() != null && !button.getAction().isEmpty()) {
                // Set as button with action
                if (button.getClickTypes().isEmpty()) {
                    // No specific click types, apply to all
                    setButton(slot, item, click -> {
                        GuiConfigActionRegistry.executeAction(
                            click, configContext, button.getAction()
                        );
                    });
                } else {
                    // Apply to specific click types
                    for (ClickType clickType : button.getClickTypes()) {
                        setButton(slot, item, clickType, click -> {
                            GuiConfigActionRegistry.executeAction(
                                click, configContext, button.getAction()
                            );
                        });
                    }
                }
            } else {
                // Just set as item (no action)
                setItem(slot, item);
            }
        }
    }

    /**
     * Set a context variable for use in config expressions.
     *
     * @param key The variable key
     * @param value The variable value
     */
    public void setConfigContext(@NotNull String key, @Nullable Object value) {
        if (configContext == null) {
            // Need to initialize context - but we need a player
            // This will be set when GUI is opened or when player is available
            return;
        }
        configContext.setVariable(key, value);
    }

    /**
     * Initialize config context with a player.
     * Called automatically when GUI is opened if config is loaded.
     */
    protected void initConfigContext(@NotNull Player player) {
        if (guiConfig != null) {
            configContext = new GuiConfigContext(player, this);

            // Set variables from config
            for (Map.Entry<String, String> entry : guiConfig.getVariables().entrySet()) {
                String varName = entry.getKey();
                String varExpression = entry.getValue();
                // Resolve expression to get actual value
                String resolved = GuiConfigExpressionEngine.resolveVariables(varExpression, configContext);
                configContext.setVariable(varName, resolved);
            }
        }
    }

    /**
     * Get the GUI config context.
     *
     * @return The context, or null if not initialized
     */
    @Nullable
    public GuiConfigContext getConfigContext() {
        return configContext;
    }
}
