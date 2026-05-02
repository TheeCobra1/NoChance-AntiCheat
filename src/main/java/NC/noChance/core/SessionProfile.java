package NC.noChance.core;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

public class SessionProfile {
    private final Map<UUID, Profile> profiles;

    public SessionProfile() {
        this.profiles = new ConcurrentHashMap<>();
    }

    public void recordCPS(UUID playerId, double cps) {
        getProfile(playerId).recordCPS(cps);
    }

    public void recordRotationSpeed(UUID playerId, double speed) {
        getProfile(playerId).recordRotationSpeed(speed);
    }

    public void recordViolation(UUID playerId, ViolationType type) {
        getProfile(playerId).recordViolation(type);
    }

    public void recordCheck(UUID playerId) {
        getProfile(playerId).recordCheck();
    }

    public double getSessionCPSAverage(UUID playerId) {
        return getProfile(playerId).getCPSAverage();
    }

    public double getSessionRotationAverage(UUID playerId) {
        return getProfile(playerId).getRotationAverage();
    }

    public double getViolationRatio(UUID playerId) {
        return getProfile(playerId).getViolationRatio();
    }

    public boolean detectToggle(UUID playerId) {
        return getProfile(playerId).detectToggle();
    }

    public boolean detectSkillJump(UUID playerId) {
        return getProfile(playerId).detectSkillJump();
    }

    public double getTrustScore(UUID playerId) {
        return getProfile(playerId).getTrustScore();
    }

    public BehaviorSnapshot getSnapshot(UUID playerId) {
        return getProfile(playerId).getSnapshot();
    }

    public double compareToBaseline(UUID playerId, BehaviorSnapshot baseline) {
        return getProfile(playerId).compareToBaseline(baseline);
    }

    private Profile getProfile(UUID playerId) {
        return profiles.computeIfAbsent(playerId, k -> new Profile());
    }

    public void cleanup(UUID playerId) {
        profiles.remove(playerId);
    }

    public void cleanupStale(long maxAge) {
        long now = System.currentTimeMillis();
        profiles.entrySet().removeIf(e -> now - e.getValue().lastActivity > maxAge);
    }

    public static class Profile {
        private final Deque<TimedValue> cpsHistory = new ConcurrentLinkedDeque<>();
        private final Deque<TimedValue> rotationHistory = new ConcurrentLinkedDeque<>();
        private final Map<ViolationType, Integer> violationCounts = new ConcurrentHashMap<>();
        private final Deque<Long> violationTimestamps = new ConcurrentLinkedDeque<>();
        private final java.util.concurrent.atomic.AtomicInteger totalChecks = new java.util.concurrent.atomic.AtomicInteger(0);
        private final java.util.concurrent.atomic.AtomicInteger totalViolations = new java.util.concurrent.atomic.AtomicInteger(0);
        private long sessionStart = System.currentTimeMillis();
        private volatile long lastActivity = System.currentTimeMillis();

        private volatile double baselineCPS = -1;
        private volatile double baselineRotation = -1;
        private volatile long baselineSetTime = 0;
        private final java.util.concurrent.atomic.AtomicBoolean baselineCPSInitialized = new java.util.concurrent.atomic.AtomicBoolean(false);
        private final java.util.concurrent.atomic.AtomicBoolean baselineRotationInitialized = new java.util.concurrent.atomic.AtomicBoolean(false);

        void recordCPS(double cps) {
            long now = System.currentTimeMillis();
            cpsHistory.addLast(new TimedValue(cps, now));
            if (cpsHistory.size() > 100) cpsHistory.pollFirst();
            lastActivity = now;

            if (!baselineCPSInitialized.get() && cpsHistory.size() >= 20) {
                if (baselineCPSInitialized.compareAndSet(false, true)) {
                    baselineCPS = getCPSAverage();
                    baselineSetTime = now;
                }
            }
        }

        void recordRotationSpeed(double speed) {
            long now = System.currentTimeMillis();
            rotationHistory.addLast(new TimedValue(speed, now));
            if (rotationHistory.size() > 100) rotationHistory.pollFirst();
            lastActivity = now;

            if (!baselineRotationInitialized.get() && rotationHistory.size() >= 20) {
                if (baselineRotationInitialized.compareAndSet(false, true)) {
                    baselineRotation = getRotationAverage();
                }
            }
        }

        void recordViolation(ViolationType type) {
            violationCounts.merge(type, 1, Integer::sum);
            totalViolations.incrementAndGet();
            long now = System.currentTimeMillis();
            violationTimestamps.addLast(now);
            if (violationTimestamps.size() > 100) violationTimestamps.pollFirst();
            lastActivity = now;
        }

        void recordCheck() {
            totalChecks.incrementAndGet();
            lastActivity = System.currentTimeMillis();
        }

        double getCPSAverage() {
            if (cpsHistory.isEmpty()) return 0;
            return cpsHistory.stream().mapToDouble(v -> v.value).average().orElse(0);
        }

        double getRotationAverage() {
            if (rotationHistory.isEmpty()) return 0;
            return rotationHistory.stream().mapToDouble(v -> v.value).average().orElse(0);
        }

        double getViolationRatio() {
            int checks = totalChecks.get();
            if (checks == 0) return 0;
            return (double) totalViolations.get() / checks;
        }

        boolean detectToggle() {
            if (baselineCPS < 0 || cpsHistory.size() < 30) return false;

            long now = System.currentTimeMillis();
            if (now - baselineSetTime < 30000) return false;

            double recentCPS = getRecentCPSAverage(10);
            double diff = Math.abs(recentCPS - baselineCPS);

            if (diff > baselineCPS * 0.5 && diff > 3.0) {
                return true;
            }

            double recentRotation = getRecentRotationAverage(10);
            if (baselineRotation > 0) {
                double rotDiff = Math.abs(recentRotation - baselineRotation);
                if (rotDiff > baselineRotation * 0.6 && rotDiff > 150) {
                    return true;
                }
            }

            return false;
        }

        boolean detectSkillJump() {
            if (cpsHistory.size() < 40) return false;

            long now = System.currentTimeMillis();
            long sessionDuration = now - sessionStart;
            if (sessionDuration < 120000) return false;

            List<TimedValue> cpsList = new ArrayList<>(cpsHistory);
            int halfSize = cpsList.size() / 2;

            double firstHalfAvg = cpsList.subList(0, halfSize).stream()
                .mapToDouble(v -> v.value).average().orElse(0);
            double secondHalfAvg = cpsList.subList(halfSize, cpsList.size()).stream()
                .mapToDouble(v -> v.value).average().orElse(0);

            double improvement = secondHalfAvg - firstHalfAvg;
            if (improvement > 4.0 && secondHalfAvg > 16.0) {
                return true;
            }

            return false;
        }

        double getTrustScore() {
            double score = 1.0;

            long now = System.currentTimeMillis();
            long cutoff = now - 180000;
            int recentViolations = 0;
            for (Long ts : violationTimestamps) {
                if (ts > cutoff) recentViolations++;
            }
            int checks = totalChecks.get();
            double recentRatio = checks > 0 ? (double) recentViolations / checks : 0;

            if (recentRatio > 0.3) score -= 0.4;
            else if (recentRatio > 0.15) score -= 0.2;
            else if (recentRatio > 0.05) score -= 0.1;

            if (detectToggle()) score -= 0.25;
            if (detectSkillJump()) score -= 0.2;

            long sessionDuration = now - sessionStart;
            if (sessionDuration > 600000) score += 0.1;

            return Math.max(0, Math.min(1.0, score));
        }

        BehaviorSnapshot getSnapshot() {
            return new BehaviorSnapshot(
                getCPSAverage(),
                getRotationAverage(),
                getViolationRatio(),
                System.currentTimeMillis() - sessionStart
            );
        }

        double compareToBaseline(BehaviorSnapshot baseline) {
            if (baseline == null) return 0;

            double cpsDiff = Math.abs(getCPSAverage() - baseline.avgCPS);
            double rotDiff = Math.abs(getRotationAverage() - baseline.avgRotation);

            double cpsDeviation = baseline.avgCPS > 0 ? cpsDiff / baseline.avgCPS : 0;
            double rotDeviation = baseline.avgRotation > 0 ? rotDiff / baseline.avgRotation : 0;

            return (cpsDeviation + rotDeviation) / 2.0;
        }

        private double getRecentCPSAverage(int count) {
            if (cpsHistory.isEmpty()) return 0;
            List<TimedValue> list = new ArrayList<>(cpsHistory);
            int start = Math.max(0, list.size() - count);
            return list.subList(start, list.size()).stream()
                .mapToDouble(v -> v.value).average().orElse(0);
        }

        private double getRecentRotationAverage(int count) {
            if (rotationHistory.isEmpty()) return 0;
            List<TimedValue> list = new ArrayList<>(rotationHistory);
            int start = Math.max(0, list.size() - count);
            return list.subList(start, list.size()).stream()
                .mapToDouble(v -> v.value).average().orElse(0);
        }
    }

    private static class TimedValue {
        final double value;
        final long timestamp;

        TimedValue(double value, long timestamp) {
            this.value = value;
            this.timestamp = timestamp;
        }
    }

    public static class BehaviorSnapshot {
        public final double avgCPS;
        public final double avgRotation;
        public final double violationRatio;
        public final long sessionDuration;

        public BehaviorSnapshot(double avgCPS, double avgRotation, double violationRatio, long sessionDuration) {
            this.avgCPS = avgCPS;
            this.avgRotation = avgRotation;
            this.violationRatio = violationRatio;
            this.sessionDuration = sessionDuration;
        }
    }
}
