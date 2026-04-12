package ca.tweetzy.flight.gui;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.lang.ref.WeakReference;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks the active server-side {@link Gui} instance per player.
 * Prevents old or delayed client packets from interacting with a previous GUI instance
 * (UI desync / dupe exploits) by requiring the event's GUI to match the registered one.
 * <p>
 * Sessions end when the GUI closes or a new one opens ({@link #end}, {@link #start}).
 * {@link WeakReference} avoids retaining GUI objects after they are otherwise unreachable.
 */
public final class GUISessionLock {

    /** Debug flag: set to true to log blocked invalid GUI interactions. */
    private static final boolean DEBUG = false;

    private static final Map<UUID, Session> ACTIVE_GUI = new ConcurrentHashMap<>();

    private GUISessionLock() {}

    /** Internal holder: weak ref so we do not leak GUI instances if cleanup is missed. */
    private static final class Session {
        final WeakReference<Gui> guiRef;

        Session(Gui gui) {
            this.guiRef = new WeakReference<>(gui);
        }
    }

    /** Mark this Gui as the current active GUI for the player (overwrites any previous session). */
    public static void start(UUID playerId, Gui gui) {
        if (gui == null) return;
        ACTIVE_GUI.put(playerId, new Session(gui));
    }

    /**
     * Returns true if the given Gui is still the active GUI for the player.
     * Removes the session if the stored reference was cleared or does not match {@code gui}.
     */
    public static boolean isValid(UUID playerId, Gui gui) {
        Session session = ACTIVE_GUI.get(playerId);
        if (session == null) return false;

        Gui stored = session.guiRef.get();

        if (stored == null || stored != gui) {
            ACTIVE_GUI.remove(playerId);
            if (DEBUG) {
                Bukkit.getLogger().info("[GUISessionLock] Invalid GUI packet blocked for " + playerId);
            }
            return false;
        }

        return true;
    }

    /** Ends the player's GUI session (called on GUI close or when a new one opens). */
    public static void end(UUID playerId) {
        ACTIVE_GUI.remove(playerId);
    }

    /** Returns the current GUI for the player, or null if none active or reference cleared. */
    public static Gui get(UUID playerId) {
        Session session = ACTIVE_GUI.get(playerId);
        if (session == null) return null;
        Gui gui = session.guiRef.get();
        if (gui == null) {
            ACTIVE_GUI.remove(playerId);
            return null;
        }
        return gui;
    }

    /** Manually clear all GUI session locks (e.g., on plugin disable). */
    public static void clearAll() {
        ACTIVE_GUI.clear();
    }

    /** Optional debug utility: print all active GUI sessions (safe to call). */
    public static void dumpActiveSessions() {
        if (!DEBUG) return;
        Bukkit.getScheduler().runTaskAsynchronously(Bukkit.getPluginManager().getPlugin("Flight"), () -> {
            Bukkit.getLogger().info("[GUISessionLock] === ACTIVE GUI SESSIONS ===");
            ACTIVE_GUI.forEach((uuid, session) -> {
                Player p = Bukkit.getPlayer(uuid);
                String name = p != null ? p.getName() : "Offline";
                Gui g = session.guiRef.get();
                Bukkit.getLogger().info(" - " + name + " → " + (g != null ? g.getClass().getSimpleName() : "null"));
            });
        });
    }
}
