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

package ca.tweetzy.flight.utils;

import lombok.NonNull;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Level;

/**
 * Task utilities for better async/sync task management.
 * 
 * @author Kiran Hart
 */
public class TaskUtil {
    
    private final Plugin plugin;
    
    public TaskUtil(@NonNull Plugin plugin) {
        this.plugin = plugin;
    }
    
    /**
     * Run a task asynchronously
     * 
     * @param task The task to run
     * @return CompletableFuture
     */
    @NonNull
    public CompletableFuture<Void> runAsync(@NonNull Runnable task) {
        return CompletableFuture.runAsync(() -> {
            try {
                task.run();
            } catch (Exception e) {
                plugin.getLogger().log(Level.SEVERE, "Error in async task", e);
            }
        });
    }
    
    /**
     * Run a task asynchronously and then run a callback on the main thread
     * 
     * @param asyncTask The async task
     * @param syncCallback The sync callback
     * @return CompletableFuture
     */
    @NonNull
    public CompletableFuture<Void> runAsyncThenSync(@NonNull Runnable asyncTask, @NonNull Runnable syncCallback) {
        return runAsync(asyncTask).thenRun(() -> {
            Bukkit.getScheduler().runTask(plugin, syncCallback);
        });
    }
    
    /**
     * Run a task asynchronously with result
     * 
     * @param supplier The supplier that returns a result
     * @param <T> The result type
     * @return CompletableFuture with result
     */
    @NonNull
    public <T> CompletableFuture<T> supplyAsync(@NonNull Supplier<T> supplier) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return supplier.get();
            } catch (Exception e) {
                plugin.getLogger().log(Level.SEVERE, "Error in async supplier", e);
                return null;
            }
        });
    }
    
    /**
     * Run a task asynchronously with result and then run callback on main thread
     * 
     * @param supplier The supplier that returns a result
     * @param syncCallback The sync callback that receives the result
     * @param <T> The result type
     * @return CompletableFuture
     */
    @NonNull
    public <T> CompletableFuture<Void> supplyAsyncThenSync(@NonNull Supplier<T> supplier, @NonNull Consumer<T> syncCallback) {
        return supplyAsync(supplier).thenAccept(result -> {
            Bukkit.getScheduler().runTask(plugin, () -> syncCallback.accept(result));
        });
    }
    
    /**
     * Run a task asynchronously with error handling
     * 
     * @param asyncTask The async task
     * @param syncCallback The sync callback
     * @param errorHandler The error handler
     * @return CompletableFuture
     */
    @NonNull
    public CompletableFuture<Void> runAsyncWithError(@NonNull Runnable asyncTask, 
                                                     @NonNull Runnable syncCallback,
                                                     @NonNull Consumer<Throwable> errorHandler) {
        return CompletableFuture.runAsync(() -> {
            try {
                asyncTask.run();
                Bukkit.getScheduler().runTask(plugin, syncCallback);
            } catch (Exception e) {
                Bukkit.getScheduler().runTask(plugin, () -> errorHandler.accept(e));
            }
        });
    }
    
    /**
     * Run a task on the main thread
     * 
     * @param task The task to run
     * @return BukkitTask
     */
    @NonNull
    public BukkitTask runSync(@NonNull Runnable task) {
        return Bukkit.getScheduler().runTask(plugin, task);
    }
    
    /**
     * Run a task on the main thread later
     * 
     * @param task The task to run
     * @param delay The delay in ticks
     * @return BukkitTask
     */
    @NonNull
    public BukkitTask runSyncLater(@NonNull Runnable task, long delay) {
        return Bukkit.getScheduler().runTaskLater(plugin, task, delay);
    }
    
    /**
     * Run a repeating task on the main thread
     * 
     * @param task The task to run
     * @param delay The initial delay in ticks
     * @param period The period in ticks
     * @return BukkitTask
     */
    @NonNull
    public BukkitTask runSyncRepeating(@NonNull Runnable task, long delay, long period) {
        return Bukkit.getScheduler().runTaskTimer(plugin, task, delay, period);
    }
    
    /**
     * Run a repeating task asynchronously
     * 
     * @param task The task to run
     * @param delay The initial delay in ticks
     * @param period The period in ticks
     * @return BukkitTask
     */
    @NonNull
    public BukkitTask runAsyncRepeating(@NonNull Runnable task, long delay, long period) {
        return Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, task, delay, period);
    }
    
    /**
     * Chain async and sync tasks
     * 
     * @param asyncTask The async task
     * @return TaskChain for chaining
     */
    @NonNull
    public TaskChain chain(@NonNull Runnable asyncTask) {
        return new TaskChain(plugin, asyncTask);
    }
    
    /**
     * Task chain builder
     */
    public static class TaskChain {
        private final Plugin plugin;
        private CompletableFuture<Void> future;
        
        private TaskChain(@NonNull Plugin plugin, @NonNull Runnable asyncTask) {
            this.plugin = plugin;
            this.future = CompletableFuture.runAsync(asyncTask);
        }
        
        /**
         * Then run on main thread
         */
        @NonNull
        public TaskChain thenSync(@NonNull Runnable syncTask) {
            future = future.thenRun(() -> {
                Bukkit.getScheduler().runTask(plugin, syncTask);
            });
            return this;
        }
        
        /**
         * Then run async
         */
        @NonNull
        public TaskChain thenAsync(@NonNull Runnable asyncTask) {
            future = future.thenRunAsync(asyncTask);
            return this;
        }
        
        /**
         * Handle errors
         */
        @NonNull
        public TaskChain onError(@NonNull Consumer<Throwable> errorHandler) {
            future = future.exceptionally(throwable -> {
                Bukkit.getScheduler().runTask(plugin, () -> errorHandler.accept(throwable));
                return null;
            });
            return this;
        }
    }
}

