package NC.noChance.core;

import NC.noChance.NoChance;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.*;
import java.util.concurrent.*;

public class AsyncCheckProcessor {
    private final Plugin plugin;
    private final ExecutorService executor;
    private final Map<UUID, Queue<CheckTask>> taskQueues;
    private final Set<UUID> processing;

    public AsyncCheckProcessor(Plugin plugin) {
        this.plugin = plugin;
        ExecutorService raw = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "NoChance-AsyncCheck");
            t.setDaemon(true);
            return t;
        });
        this.executor = (plugin instanceof NoChance)
                ? ((NoChance) plugin).getLifecycleRegistry().register("AsyncCheck", raw)
                : raw;
        this.taskQueues = new ConcurrentHashMap<>();
        this.processing = ConcurrentHashMap.newKeySet();
    }

    @SuppressWarnings("unchecked")
    public <T> CompletableFuture<T> submitCheck(Player player, Callable<T> check, CheckPriority priority) {
        UUID uuid = player.getUniqueId();
        CheckTask task = new CheckTask(check, priority);

        Queue<CheckTask> queue = taskQueues.computeIfAbsent(uuid, k -> new PriorityBlockingQueue<>());
        queue.offer(task);

        processQueue(uuid);

        return (CompletableFuture<T>) (CompletableFuture<?>) task.future;
    }

    private void processQueue(UUID uuid) {
        if (!processing.add(uuid)) {
            return;
        }

        CompletableFuture.runAsync(() -> {
            try {
                while (true) {
                    Queue<CheckTask> queue = taskQueues.get(uuid);
                    if (queue == null) {
                        break;
                    }

                    CheckTask task = queue.poll();
                    if (task == null) {
                        break;
                    }

                    try {
                        Object result = task.callable.call();
                        task.future.complete(result);
                    } catch (Exception e) {
                        task.future.completeExceptionally(e);
                    }

                    if (queue.isEmpty()) {
                        break;
                    }
                }
            } finally {
                processing.remove(uuid);

                Queue<CheckTask> queue = taskQueues.get(uuid);
                if (queue != null && !queue.isEmpty()) {
                    processQueue(uuid);
                }
            }
        }, executor);
    }

    public void cleanup(UUID uuid) {
        taskQueues.remove(uuid);
        processing.remove(uuid);
    }

    public void shutdown() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    public enum CheckPriority {
        LOW(3),
        NORMAL(2),
        HIGH(1),
        CRITICAL(0);

        private final int value;

        CheckPriority(int value) {
            this.value = value;
        }

        public int getValue() {
            return value;
        }
    }

    private static final class CheckTask implements Comparable<CheckTask> {
        private final Callable<?> callable;
        private final CheckPriority priority;
        private final CompletableFuture<Object> future;
        private final long timestamp;

        private CheckTask(Callable<?> callable, CheckPriority priority) {
            this.callable = callable;
            this.priority = priority;
            this.future = new CompletableFuture<>();
            this.timestamp = System.currentTimeMillis();
        }

        @Override
        public int compareTo(CheckTask other) {
            int priorityCompare = Integer.compare(this.priority.getValue(), other.priority.getValue());
            if (priorityCompare != 0) {
                return priorityCompare;
            }
            return Long.compare(this.timestamp, other.timestamp);
        }
    }
}
