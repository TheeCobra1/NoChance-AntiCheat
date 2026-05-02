package NC.noChance.punishment;

import NC.noChance.core.ViolationType;
import NC.noChance.database.DatabaseManager;
import org.bukkit.plugin.Plugin;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public class PunishmentHistory {
    private final Plugin plugin;
    private final DatabaseManager database;
    private final Map<UUID, PunishmentTracker> trackers;

    public PunishmentHistory(Plugin plugin, DatabaseManager database) {
        this.plugin = plugin;
        this.database = database;
        this.trackers = new ConcurrentHashMap<>();
    }

    public PunishmentTracker tracker(UUID uuid) {
        return trackers.computeIfAbsent(uuid, k -> new PunishmentTracker());
    }

    public void cleanup(UUID uuid) {
        trackers.remove(uuid);
    }

    public void logSetback(UUID uuid, String playerName, ViolationType type) {
        database.logPunishment(uuid, playerName, DatabaseManager.PunishmentType.SETBACK,
                        "Setback for " + type.name(), 0L, "NoChance")
                .exceptionally(ex -> {
                    plugin.getLogger().warning("Failed to log setback: " + ex.getMessage());
                    return null;
                });
    }

    public void logCustomCommand(UUID uuid, String playerName, ViolationType type, String confidenceLevel) {
        database.logPunishment(uuid, playerName, DatabaseManager.PunishmentType.CUSTOM_CMD,
                        "Custom cmd for " + type.name() + " (" + confidenceLevel + ")", 0L, "NoChance")
                .exceptionally(ex -> {
                    plugin.getLogger().warning("Failed to log custom_cmd: " + ex.getMessage());
                    return null;
                });
    }

    public void record(UUID uuid, String playerName, DatabaseManager.PunishmentType type,
                       String reason, long duration) {
        database.logPunishment(uuid, playerName, type, reason, duration, "NoChance")
                .exceptionally(ex -> {
                    plugin.getLogger().warning("Failed to log punishment: " + ex.getMessage());
                    return null;
                });
    }

    public static final class PunishmentTracker {
        private final Map<ViolationType, Integer> violationCounts = new ConcurrentHashMap<>();
        private final AtomicInteger totalViolations = new AtomicInteger(0);
        private volatile long lastDecayTime = System.currentTimeMillis();
        private static final long DECAY_INTERVAL = 45000;

        public void addViolation(ViolationType type, double severity) {
            decay();
            violationCounts.merge(type, 1, Integer::sum);
            totalViolations.incrementAndGet();
        }

        private void decay() {
            synchronized (this) {
                long now = System.currentTimeMillis();
                long elapsed = now - lastDecayTime;
                if (elapsed < DECAY_INTERVAL) return;

                int periods = (int) (elapsed / DECAY_INTERVAL);
                lastDecayTime = now;

                for (int p = 0; p < periods; p++) {
                    int current = totalViolations.get();
                    if (current <= 0) break;
                    int decayed = Math.max(0, current - 2);
                    totalViolations.set(decayed);
                    violationCounts.replaceAll((t, c) -> Math.max(0, c - 1));
                    violationCounts.values().removeIf(v -> v <= 0);
                }
            }
        }

        public int getTotalViolations() {
            decay();
            return totalViolations.get();
        }

        public int getTypeViolations(ViolationType type) {
            return violationCounts.getOrDefault(type, 0);
        }

        public void reset() {
            violationCounts.clear();
            totalViolations.set(0);
            lastDecayTime = System.currentTimeMillis();
        }
    }
}
