package NC.noChance.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.scheduler.BukkitTask;

public class LifecycleRegistry {
    private final Logger logger;
    private final List<NamedExecutor> executors = new ArrayList<>();
    private final List<BukkitTask> tasks = new ArrayList<>();
    private final List<Integer> taskIds = new ArrayList<>();
    private final List<Listener> listeners = new ArrayList<>();

    public static final class NamedExecutor {
        public final String name;
        public final ExecutorService executor;
        public NamedExecutor(String name, ExecutorService executor) {
            this.name = name;
            this.executor = executor;
        }
    }

    public LifecycleRegistry(Logger logger) {
        this.logger = logger;
    }

    public <T extends ExecutorService> T register(T executor) {
        return registerExecutor(executor);
    }

    public <T extends ExecutorService> T register(String name, T executor) {
        return registerExecutor(name, executor);
    }

    public <T extends ExecutorService> T registerExecutor(T executor) {
        return registerExecutor("executor-" + executors.size(), executor);
    }

    public <T extends ExecutorService> T registerExecutor(String name, T executor) {
        if (executor != null) executors.add(new NamedExecutor(name, executor));
        return executor;
    }

    public <T extends ScheduledExecutorService> T registerScheduledExecutor(T executor) {
        return registerScheduledExecutor("sched-" + executors.size(), executor);
    }

    public <T extends ScheduledExecutorService> T registerScheduledExecutor(String name, T executor) {
        if (executor != null) executors.add(new NamedExecutor(name, executor));
        return executor;
    }

    public BukkitTask register(BukkitTask task) {
        return registerBukkitTask(task);
    }

    public BukkitTask registerBukkitTask(BukkitTask task) {
        if (task != null) tasks.add(task);
        return task;
    }

    public int registerBukkitTask(int taskId) {
        if (taskId > 0) taskIds.add(taskId);
        return taskId;
    }

    public <T extends Listener> T registerListener(T listener) {
        if (listener != null) listeners.add(listener);
        return listener;
    }

    public List<NamedExecutor> getNamedExecutors() {
        return Collections.unmodifiableList(executors);
    }

    public void shutdown() {
        shutdownAll();
    }

    public void shutdownAll() {
        for (BukkitTask task : tasks) {
            try {
                task.cancel();
            } catch (Exception e) {
                logger.log(Level.WARNING, "Failed to cancel BukkitTask", e);
            }
        }
        tasks.clear();

        for (Integer id : taskIds) {
            try {
                Bukkit.getScheduler().cancelTask(id);
            } catch (Exception e) {
                logger.log(Level.WARNING, "Failed to cancel task id " + id, e);
            }
        }
        taskIds.clear();

        for (NamedExecutor ne : executors) {
            ExecutorService exec = ne.executor;
            try {
                exec.shutdown();
                if (!exec.awaitTermination(5, TimeUnit.SECONDS)) {
                    exec.shutdownNow();
                }
            } catch (InterruptedException e) {
                exec.shutdownNow();
                Thread.currentThread().interrupt();
                logger.log(Level.WARNING, "Interrupted while awaiting executor termination", e);
            } catch (Exception e) {
                logger.log(Level.WARNING, "Failed to shut down executor", e);
            }
        }
        executors.clear();

        for (Listener listener : listeners) {
            try {
                HandlerList.unregisterAll(listener);
            } catch (Exception e) {
                logger.log(Level.WARNING, "Failed to unregister listener " + listener.getClass().getName(), e);
            }
        }
        listeners.clear();
    }
}
