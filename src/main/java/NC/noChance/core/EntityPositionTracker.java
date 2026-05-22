package NC.noChance.core;

import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class EntityPositionTracker {
    private static final int HISTORY_SIZE = 16;
    private static final long STALE_NANOS = 5_000_000_000L;

    private final Plugin plugin;
    private final Map<Integer, Deque<Snapshot>> history = new ConcurrentHashMap<>();
    private volatile BukkitTask task;
    private volatile boolean running;

    public EntityPositionTracker(Plugin plugin) {
        this.plugin = plugin;
    }

    public void start() {
        if (running) return;
        running = true;
        try {
            task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
        } catch (Throwable t) {
            running = false;
        }
    }

    public void shutdown() {
        running = false;
        BukkitTask t = task;
        if (t != null) {
            try { t.cancel(); } catch (Throwable ignored) {}
            task = null;
        }
        history.clear();
    }

    public void onEntityRemoved(int entityId) {
        history.remove(entityId);
    }

    public Snapshot getNearestSnapshot(int entityId, long maxAgeNanos) {
        Deque<Snapshot> q = history.get(entityId);
        if (q == null) return null;
        long oldestAllowed = System.nanoTime() - maxAgeNanos;
        synchronized (q) {
            for (Snapshot s : q) {
                if (s.time >= oldestAllowed) return s;
            }
        }
        return null;
    }

    public BoundingBox getClosestBoxTo(Entity target, Vector eyePos, int pingMs) {
        if (target == null) return null;
        Deque<Snapshot> q = history.get(target.getEntityId());
        BoundingBox liveBox;
        try { liveBox = target.getBoundingBox(); } catch (Throwable t) { liveBox = null; }
        if (q == null) return liveBox;

        long pingNanos = Math.max(0L, pingMs) * 1_000_000L;
        long ceilingNanos = pingNanos + 200_000_000L;
        long oldestAllowed = System.nanoTime() - ceilingNanos;

        BoundingBox best = liveBox;
        double bestDistSq = liveBox == null ? Double.MAX_VALUE : distanceSqToBox(eyePos, liveBox);

        synchronized (q) {
            for (Snapshot s : q) {
                if (s.time < oldestAllowed) break;
                double d = distanceSqToBox(eyePos, s.box);
                if (d < bestDistSq) {
                    bestDistSq = d;
                    best = s.box;
                }
            }
        }
        return best;
    }

    private static double distanceSqToBox(Vector p, BoundingBox b) {
        double dx = Math.max(0, Math.max(b.getMinX() - p.getX(), p.getX() - b.getMaxX()));
        double dy = Math.max(0, Math.max(b.getMinY() - p.getY(), p.getY() - b.getMaxY()));
        double dz = Math.max(0, Math.max(b.getMinZ() - p.getZ(), p.getZ() - b.getMaxZ()));
        return dx * dx + dy * dy + dz * dz;
    }

    private void tick() {
        if (!running) return;
        long now = System.nanoTime();
        long staleBefore = now - STALE_NANOS;

        for (Player p : Bukkit.getOnlinePlayers()) {
            try {
                BoundingBox box = p.getBoundingBox();
                int id = p.getEntityId();
                Deque<Snapshot> q = history.computeIfAbsent(id, k -> new ArrayDeque<>());
                synchronized (q) {
                    q.addFirst(new Snapshot(now, box));
                    while (q.size() > HISTORY_SIZE) q.pollLast();
                }
            } catch (Throwable ignored) {}
        }

        history.entrySet().removeIf(e -> {
            Deque<Snapshot> q = e.getValue();
            Snapshot newest;
            synchronized (q) {
                newest = q.peekFirst();
            }
            return newest == null || newest.time < staleBefore;
        });
    }

    public static class Snapshot {
        public final long time;
        public final BoundingBox box;

        Snapshot(long time, BoundingBox box) {
            this.time = time;
            this.box = box;
        }
    }
}
