package NC.noChance.core;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.logging.Level;

public class StateSweeper {
    private static final long PERIOD_TICKS = 20L * 60 * 5;
    private static final long MAX_AGE_MS = 30L * 60 * 1000;

    private final Plugin plugin;
    private final List<Entry> entries = new ArrayList<>();
    private BukkitTask task;
    private volatile long lastSweepTime = 0L;
    private volatile int lastSweepRemoved = 0;
    private volatile int lastSweepRemainingEntries = 0;

    public StateSweeper(Plugin plugin) {
        this.plugin = plugin;
    }

    public void register(String name, Map<UUID, ?> map, Function<Object, Long> tsExtractor) {
        if (name == null || map == null || tsExtractor == null) return;
        entries.add(new Entry(name, map, tsExtractor));
    }

    public void start() {
        if (task != null) return;
        task = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::sweep, PERIOD_TICKS, PERIOD_TICKS);
    }

    public BukkitTask getTask() {
        return task;
    }

    public int getRegisteredCount() {
        return entries.size();
    }

    public long getLastSweepTime() {
        return lastSweepTime;
    }

    public int getLastSweepRemoved() {
        return lastSweepRemoved;
    }

    public int getLastSweepRemainingEntries() {
        return lastSweepRemainingEntries;
    }

    private void sweep() {
        Set<UUID> online = new HashSet<>();
        for (Player p : Bukkit.getOnlinePlayers()) online.add(p.getUniqueId());
        long cutoff = System.currentTimeMillis() - MAX_AGE_MS;

        int removed = 0;
        int remaining = 0;
        for (Entry e : entries) {
            try {
                int before = e.map.size();
                e.map.entrySet().removeIf(en -> {
                    if (online.contains(en.getKey())) return false;
                    Object value = en.getValue();
                    if (value == null) return true;
                    Long ts = e.tsExtractor.apply(value);
                    if (ts == null) return false;
                    return ts < cutoff;
                });
                int after = e.map.size();
                removed += (before - after);
                remaining += after;
            } catch (Exception ex) {
                plugin.getLogger().log(Level.WARNING, "StateSweeper failed on " + e.name, ex);
            }
        }
        this.lastSweepTime = System.currentTimeMillis();
        this.lastSweepRemoved = removed;
        this.lastSweepRemainingEntries = remaining;
    }

    private static class Entry {
        final String name;
        final Map<UUID, Object> map;
        final Function<Object, Long> tsExtractor;

        @SuppressWarnings("unchecked")
        Entry(String name, Map<UUID, ?> map, Function<Object, Long> tsExtractor) {
            this.name = name;
            this.map = (Map<UUID, Object>) map;
            this.tsExtractor = tsExtractor;
        }
    }
}
