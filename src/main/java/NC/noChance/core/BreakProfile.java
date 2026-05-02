package NC.noChance.core;

import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class BreakProfile {
    private final Map<UUID, Profile> profiles;
    private static final int MAX_BREAKS_PER_MATERIAL = 50;
    private static final int MAX_RECENT_BREAKS = 100;

    public BreakProfile() {
        this.profiles = new ConcurrentHashMap<>();
    }

    public static class TimedBreakEntry {
        public final long breakTimeMs;
        public final long timestamp;

        public TimedBreakEntry(long breakTimeMs, long timestamp) {
            this.breakTimeMs = breakTimeMs;
            this.timestamp = timestamp;
        }
    }

    public static class Profile {
        private final Map<Material, Deque<TimedBreakEntry>> blockBreakTimes;
        private final Deque<BreakEvent> recentBreaks;
        private double averageBreakSpeed;
        private int totalBreaks;
        private int suspiciousBreaks;
        private long lastDecayTime;

        private static final long DECAY_INTERVAL = 20_000;
        private static final double DECAY_FACTOR = 0.5;
        private static final long MAX_BASELINE_AGE = 120_000;

        public Profile() {
            this.blockBreakTimes = new ConcurrentHashMap<>();
            this.recentBreaks = new java.util.concurrent.ConcurrentLinkedDeque<>();
            this.averageBreakSpeed = 0.0;
            this.totalBreaks = 0;
            this.suspiciousBreaks = 0;
            this.lastDecayTime = System.currentTimeMillis();
        }

        public void recordBreak(Material material, long timeMs, boolean suspicious) {
            decayCounters();

            long now = System.currentTimeMillis();
            Deque<TimedBreakEntry> times = blockBreakTimes.computeIfAbsent(material, k -> new ArrayDeque<>());
            times.addLast(new TimedBreakEntry(timeMs, now));

            while (times.size() > MAX_BREAKS_PER_MATERIAL) {
                times.pollFirst();
            }

            recentBreaks.addLast(new BreakEvent(material, timeMs, now, suspicious));

            if (recentBreaks.size() > MAX_RECENT_BREAKS) {
                recentBreaks.pollFirst();
            }

            totalBreaks++;
            if (suspicious) {
                suspiciousBreaks++;
            }

            updateAverageSpeed();
        }

        private void decayCounters() {
            long now = System.currentTimeMillis();
            long elapsed = now - lastDecayTime;
            if (elapsed >= DECAY_INTERVAL) {
                int periods = (int) (elapsed / DECAY_INTERVAL);
                for (int i = 0; i < periods; i++) {
                    suspiciousBreaks = (int) (suspiciousBreaks * DECAY_FACTOR);
                }
                totalBreaks = Math.max(totalBreaks, 1);
                lastDecayTime = now;
            }
        }

        private void cleanOldTimes(Deque<TimedBreakEntry> times) {
            long cutoff = System.currentTimeMillis() - MAX_BASELINE_AGE;
            while (!times.isEmpty() && times.peekFirst().timestamp < cutoff) {
                times.pollFirst();
            }
        }

        private void updateAverageSpeed() {
            if (recentBreaks.isEmpty()) return;

            long total = recentBreaks.stream().mapToLong(e -> e.breakTimeMs).sum();
            averageBreakSpeed = (double) total / recentBreaks.size();
        }

        public double getAverageBreakTime(Material material) {
            Deque<TimedBreakEntry> times = blockBreakTimes.get(material);
            if (times == null || times.isEmpty()) return 0.0;

            cleanOldTimes(times);
            if (times.isEmpty()) return 0.0;

            return times.stream().mapToLong(e -> e.breakTimeMs).average().orElse(0.0);
        }

        public double getSuspiciousRatio() {
            decayCounters();
            return totalBreaks > 0 ? (double) suspiciousBreaks / totalBreaks : 0.0;
        }

        public boolean hasEstablishedBaseline(Material material) {
            Deque<TimedBreakEntry> times = blockBreakTimes.get(material);
            if (times == null) return false;
            cleanOldTimes(times);
            return times.size() >= 10;
        }

        public double getBaselineDeviation(Material material, long currentTime) {
            if (!hasEstablishedBaseline(material)) return 0.0;

            Deque<TimedBreakEntry> times = blockBreakTimes.get(material);
            double avg = times.stream().mapToLong(e -> e.breakTimeMs).average().orElse(0.0);

            return Math.abs(currentTime - avg);
        }

        public List<BreakEvent> getRecentBreaks(int count) {
            List<BreakEvent> result = new ArrayList<>();
            int size = recentBreaks.size();
            int start = Math.max(0, size - count);
            int index = 0;

            for (BreakEvent event : recentBreaks) {
                if (index >= start) {
                    result.add(event);
                }
                index++;
            }

            return result;
        }

        public int getBreakStreak() {
            int streak = 0;
            List<BreakEvent> breakList = new ArrayList<>(recentBreaks);
            for (int i = breakList.size() - 1; i >= 0; i--) {
                if (breakList.get(i).suspicious) {
                    streak++;
                } else {
                    break;
                }
            }
            return streak;
        }

        public boolean hasBaseline(Material material) {
            return hasEstablishedBaseline(material);
        }

        public double getBaselineTime(Material material) {
            return getAverageBreakTime(material);
        }

        public int getTotalBreaks() {
            return totalBreaks;
        }

        public int getSuspiciousBreaks() {
            return suspiciousBreaks;
        }
    }

    public static class BreakEvent {
        public final Material material;
        public final long breakTimeMs;
        public final long timestamp;
        public final boolean suspicious;

        public BreakEvent(Material material, long breakTimeMs, long timestamp, boolean suspicious) {
            this.material = material;
            this.breakTimeMs = breakTimeMs;
            this.timestamp = timestamp;
            this.suspicious = suspicious;
        }
    }

    public Profile getProfile(UUID playerId) {
        return profiles.computeIfAbsent(playerId, k -> new Profile());
    }

    public void recordBreak(Player player, Block block, long breakTimeMs, boolean suspicious) {
        Profile profile = getProfile(player.getUniqueId());
        profile.recordBreak(block.getType(), breakTimeMs, suspicious);
    }

    public boolean isConsistentlyFast(UUID playerId) {
        Profile profile = profiles.get(playerId);
        if (profile == null || profile.totalBreaks < 50) return false;

        if (profile.getSuspiciousRatio() <= 0.85) return false;

        int recentCount = Math.min(15, profile.recentBreaks.size());
        if (recentCount < 10) return false;
        int recentSuspicious = 0;
        int checked = 0;
        for (BreakEvent event : profile.recentBreaks) {
            if (checked >= profile.recentBreaks.size() - recentCount) {
                if (event.suspicious) recentSuspicious++;
            }
            checked++;
        }
        return recentSuspicious >= (recentCount * 0.8);
    }

    public void cleanup(UUID playerId) {
        profiles.remove(playerId);
    }
}
