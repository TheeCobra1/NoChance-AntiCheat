package NC.noChance.core;

import org.bukkit.entity.Player;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;

public class FalsePositiveFilter {
    private final Map<UUID, PlayerProfile> profiles;
    private final Map<ViolationType, CheckStatistics> globalStats;

    private static final int PROFILE_WINDOW = 100;
    private static final double BAYESIAN_PRIOR = 0.08;
    private static final double Z_SCORE_THRESHOLD = 2.2;
    private static final double IQR_MULTIPLIER = 1.5;
    private static final double MAD_SCALE = 1.4826;
    private static final double EWMA_ALPHA = 0.28;
    private static final double MIN_CONFIDENCE_PASS = 0.68;
    private static final double HIGH_TRUST_THRESHOLD = 0.82;
    private static final int MIN_CHECKS_FOR_BASELINE = 50;

    public FalsePositiveFilter() {
        this.profiles = new ConcurrentHashMap<>();
        this.globalStats = new ConcurrentHashMap<>();

        for (ViolationType type : ViolationType.values()) {
            globalStats.put(type, new CheckStatistics());
        }
    }

    public static class PlayerProfile {
        private final Map<ViolationType, Deque<ViolationRecord>> violationHistory;
        private final Map<ViolationType, Double> baselineScores;
        private final Map<ViolationType, Long> lastViolationTime;
        private final Deque<Long> breakTimings;
        private final Deque<Double> rotationSpeeds;
        private final Deque<Double> movementSpeeds;
        private volatile double trustScore;
        private final long firstSeen;
        private final AtomicInteger totalChecks = new AtomicInteger();
        private final AtomicInteger totalViolations = new AtomicInteger();
        private final AtomicInteger confirmedLegitActions = new AtomicInteger();

        public PlayerProfile() {
            this.violationHistory = new ConcurrentHashMap<>();
            this.baselineScores = new ConcurrentHashMap<>();
            this.lastViolationTime = new ConcurrentHashMap<>();
            this.breakTimings = new ConcurrentLinkedDeque<>();
            this.rotationSpeeds = new ConcurrentLinkedDeque<>();
            this.movementSpeeds = new ConcurrentLinkedDeque<>();
            this.trustScore = 0.50;
            this.firstSeen = System.currentTimeMillis();

            for (ViolationType type : ViolationType.values()) {
                violationHistory.put(type, new ConcurrentLinkedDeque<>());
                baselineScores.put(type, 0.0);
                lastViolationTime.put(type, 0L);
            }
        }

        public void recordCheck(ViolationType type, boolean violated) {
            totalChecks.incrementAndGet();
            if (!violated) {
                confirmedLegitActions.incrementAndGet();
                long lastViol = lastViolationTime.getOrDefault(type, 0L);
                long elapsed = System.currentTimeMillis() - lastViol;

                double boost;
                if (lastViol > 0 && elapsed > 60000) {
                    boost = 0.01;
                } else if (trustScore < 0.3) {
                    boost = 0.006;
                } else {
                    boost = 0.003;
                }
                adjustTrustScore(boost);
            }
        }

        public void recordViolation(ViolationType type, double severity, String reason) {
            totalViolations.incrementAndGet();
            long now = System.currentTimeMillis();
            lastViolationTime.put(type, now);

            Deque<ViolationRecord> history = violationHistory.get(type);

            while (!history.isEmpty() && now - history.peekFirst().timestamp > 120000) {
                history.pollFirst();
            }

            history.addLast(new ViolationRecord(severity, reason, now));

            if (history.size() > 50) {
                history.pollFirst();
            }

            long recentViolations = history.stream()
                .filter(v -> now - v.timestamp < 5000)
                .count();

            double trustPenalty = -0.04 * severity;
            if (recentViolations >= 3) {
                trustPenalty *= 2.5;
            }

            adjustTrustScore(trustPenalty);
        }

        public void recordBreakTiming(long timing) {
            breakTimings.addLast(timing);
            while (breakTimings.size() > PROFILE_WINDOW) {
                breakTimings.pollFirst();
            }
        }

        public void recordRotation(double speed) {
            rotationSpeeds.addLast(speed);
            while (rotationSpeeds.size() > PROFILE_WINDOW) {
                rotationSpeeds.pollFirst();
            }
        }

        public void recordMovement(double speed) {
            movementSpeeds.addLast(speed);
            while (movementSpeeds.size() > PROFILE_WINDOW) {
                movementSpeeds.pollFirst();
            }
        }

        void adjustTrustScore(double delta) {
            trustScore = Math.max(0.0, Math.min(1.0, trustScore + delta));
        }

        public double getTrustScore() {
            return trustScore;
        }

        public double getViolationRate(ViolationType type) {
            Deque<ViolationRecord> history = violationHistory.get(type);
            if (history.isEmpty() || totalChecks.get() == 0) return 0.0;
            long now = System.currentTimeMillis();
            long recentCount = history.stream()
                .filter(v -> now - v.timestamp < 120000)
                .count();
            int checks = totalChecks.get();
            int effectiveChecks = Math.max(checks, 1);
            return (double) recentCount / effectiveChecks;
        }

        public long getRecentViolationCount(long windowMs) {
            long now = System.currentTimeMillis();
            long count = 0;
            for (Deque<ViolationRecord> history : violationHistory.values()) {
                count += history.stream()
                    .filter(v -> now - v.timestamp < windowMs)
                    .count();
            }
            return count;
        }

        public boolean hasEstablishedBaseline() {
            return totalChecks.get() >= MIN_CHECKS_FOR_BASELINE && (System.currentTimeMillis() - firstSeen) > 45000;
        }

        public double getBreakTimingVariance() {
            if (breakTimings.size() < 10) return Double.MAX_VALUE;

            double mean = breakTimings.stream().mapToLong(Long::longValue).average().orElse(0.0);
            double variance = breakTimings.stream()
                .mapToDouble(v -> Math.pow(v - mean, 2))
                .average()
                .orElse(0.0);

            return variance;
        }

        public double getRotationVariance() {
            if (rotationSpeeds.size() < 10) return Double.MAX_VALUE;

            double mean = rotationSpeeds.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
            double variance = rotationSpeeds.stream()
                .mapToDouble(v -> Math.pow(v - mean, 2))
                .average()
                .orElse(0.0);

            return variance;
        }

        public long getPlayTime() {
            return System.currentTimeMillis() - firstSeen;
        }
    }

    public static class ViolationRecord {
        public final double severity;
        public final String reason;
        public final long timestamp;

        public ViolationRecord(double severity, String reason, long timestamp) {
            this.severity = severity;
            this.reason = reason;
            this.timestamp = timestamp;
        }
    }

    public static class CheckStatistics {
        private final Deque<Double> severities;
        private volatile double meanSeverity;
        private volatile double stdDevSeverity;
        private volatile int totalChecks;
        private volatile int totalViolations;

        private double welfordMean = 0;
        private double welfordM2 = 0;
        private long welfordN = 0;
        private double ewmaSeverity = 0;
        private boolean ewmaInit = false;
        private volatile double q1 = 0;
        private volatile double q3 = 0;
        private volatile double iqr = 0;
        private volatile double median = 0;

        public CheckStatistics() {
            this.severities = new java.util.concurrent.ConcurrentLinkedDeque<>();
            this.meanSeverity = 0.0;
            this.stdDevSeverity = 0.0;
            this.totalChecks = 0;
            this.totalViolations = 0;
        }

        public synchronized void record(double severity, boolean violated) {
            totalChecks++;
            if (violated) {
                totalViolations++;
                severities.addLast(severity);
                if (severities.size() > 1000) {
                    severities.pollFirst();
                }
                updateWelford(severity);
                updateEWMA(severity);
                updateStatistics();
                updateIQR();
            }
        }

        private void updateWelford(double value) {
            welfordN++;
            double delta = value - welfordMean;
            welfordMean += delta / welfordN;
            double delta2 = value - welfordMean;
            welfordM2 += delta * delta2;
        }

        private void updateEWMA(double value) {
            if (!ewmaInit) {
                ewmaSeverity = value;
                ewmaInit = true;
            } else {
                ewmaSeverity = EWMA_ALPHA * value + (1.0 - EWMA_ALPHA) * ewmaSeverity;
            }
        }

        private void updateStatistics() {
            if (severities.isEmpty()) return;

            meanSeverity = severities.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
            stdDevSeverity = Math.sqrt(
                severities.stream()
                    .mapToDouble(v -> Math.pow(v - meanSeverity, 2))
                    .average()
                    .orElse(0.0)
            );
        }

        private void updateIQR() {
            if (severities.size() < 4) return;

            List<Double> sorted = new ArrayList<>(severities);
            Collections.sort(sorted);

            int n = sorted.size();
            median = sorted.get(n / 2);
            q1 = sorted.get(n / 4);
            q3 = sorted.get((3 * n) / 4);
            iqr = q3 - q1;
        }

        public synchronized double getZScore(double severity) {
            if (stdDevSeverity == 0) return 0.0;
            return (severity - meanSeverity) / stdDevSeverity;
        }

        public synchronized double getWelfordZScore(double severity) {
            if (welfordN < 2) return 0.0;
            double variance = welfordM2 / (welfordN - 1);
            double stdDev = Math.sqrt(variance);
            if (stdDev < 0.001) return 0.0;
            return (severity - welfordMean) / stdDev;
        }

        public boolean isIQROutlier(double severity) {
            if (severities.size() < 10) return false;
            double lowerBound = q1 - IQR_MULTIPLIER * iqr;
            double upperBound = q3 + IQR_MULTIPLIER * iqr;
            return severity < lowerBound || severity > upperBound;
        }

        public synchronized double getMADScore(double severity) {
            if (severities.size() < 5) return 0.0;

            List<Double> sorted = new ArrayList<>(severities);
            Collections.sort(sorted);
            double med = sorted.get(sorted.size() / 2);

            List<Double> deviations = new ArrayList<>();
            for (Double s : sorted) {
                deviations.add(Math.abs(s - med));
            }
            Collections.sort(deviations);
            double mad = deviations.get(deviations.size() / 2);

            if (mad < 0.001) return 0.0;
            return (severity - med) / (MAD_SCALE * mad);
        }

    }

    public static class FilterResult {
        public final boolean shouldFilter;
        public final double adjustedConfidence;
        public final String filterReason;
        public final double trustMultiplier;

        public FilterResult(boolean shouldFilter, double adjustedConfidence, String filterReason, double trustMultiplier) {
            this.shouldFilter = shouldFilter;
            this.adjustedConfidence = adjustedConfidence;
            this.filterReason = filterReason;
            this.trustMultiplier = trustMultiplier;
        }

        public static FilterResult pass() {
            return new FilterResult(true, 0.0, "Filtered", 1.0);
        }

        public static FilterResult allow(double confidence, double trustMultiplier) {
            return new FilterResult(false, confidence, "Allowed", trustMultiplier);
        }
    }

    public FilterResult evaluate(Player player, ViolationType type, double rawSeverity, String reason) {
        UUID playerId = player.getUniqueId();
        PlayerProfile profile = profiles.computeIfAbsent(playerId, k -> new PlayerProfile());
        CheckStatistics stats = globalStats.get(type);

        stats.record(rawSeverity, true);
        profile.recordCheck(type, true);

        double bayesianConfidence = calculateBayesianProbability(profile, type, rawSeverity);
        double zScore = stats.getZScore(rawSeverity);
        double trustMultiplier = calculateTrustMultiplier(profile, type);
        double varianceScore = calculateVarianceScore(profile, type);
        double temporalScore = calculateTemporalScore(profile, type);

        double aggregatedConfidence = aggregateConfidence(
            bayesianConfidence,
            rawSeverity,
            trustMultiplier,
            varianceScore,
            temporalScore,
            zScore
        );

        if (shouldFilterOut(profile, type, aggregatedConfidence, zScore, trustMultiplier)) {
            return FilterResult.pass();
        }

        profile.recordViolation(type, rawSeverity, reason);

        return FilterResult.allow(aggregatedConfidence, trustMultiplier);
    }

    private double calculateBayesianProbability(PlayerProfile profile, ViolationType type, double severity) {
        double prior = BAYESIAN_PRIOR;

        if (profile.hasEstablishedBaseline()) {
            double violationRate = profile.getViolationRate(type);
            prior = Math.max(0.01, Math.min(0.5, violationRate));
        }

        double likelihood = Math.max(0.0, Math.min(1.0, severity));
        double evidence = (likelihood * prior) + ((1.0 - likelihood) * (1.0 - prior));

        if (evidence == 0) return prior;

        return (likelihood * prior) / evidence;
    }

    private double calculateTrustMultiplier(PlayerProfile profile, ViolationType type) {
        double trustScore = profile.getTrustScore();
        double playTime = Math.min(1.0, profile.getPlayTime() / 3600000.0);
        double checkRatio = profile.totalChecks.get() > 0 ?
            (double) profile.confirmedLegitActions.get() / profile.totalChecks.get() : 0.5;

        double trustMultiplier = (trustScore * 0.5) + (playTime * 0.25) + (checkRatio * 0.25);

        return Math.max(0.1, Math.min(2.0, trustMultiplier));
    }

    private double calculateVarianceScore(PlayerProfile profile, ViolationType type) {
        if (!profile.hasEstablishedBaseline()) return 1.0;

        double variance = 0.0;

        switch (type) {
            case FASTBREAK:
                variance = profile.getBreakTimingVariance();
                if (variance < 100) return 0.3;
                if (variance < 500) return 0.6;
                return 1.0;

            case KILLAURA:
            case KILLAURA_MULTI:
            case KILLAURA_ANGLE:
            case KILLAURA_ROTATION:
            case KILLAURA_PATTERN:
                variance = profile.getRotationVariance();
                if (variance < 50) return 0.3;
                if (variance < 200) return 0.6;
                return 1.0;

            default:
                return 1.0;
        }
    }

    private double calculateTemporalScore(PlayerProfile profile, ViolationType type) {
        Deque<ViolationRecord> history = profile.violationHistory.get(type);
        if (history.size() < 3) return 1.0;

        List<ViolationRecord> recent = new ArrayList<>(history);
        if (recent.size() > 10) {
            recent = recent.subList(recent.size() - 10, recent.size());
        }

        List<Long> intervals = new ArrayList<>();
        for (int i = 1; i < recent.size(); i++) {
            intervals.add(recent.get(i).timestamp - recent.get(i - 1).timestamp);
        }

        if (intervals.isEmpty()) return 1.0;

        double mean = intervals.stream().mapToLong(Long::longValue).average().orElse(0.0);
        double variance = intervals.stream()
            .mapToDouble(v -> Math.pow(v - mean, 2))
            .average()
            .orElse(0.0);

        double cv = mean > 0 ? Math.sqrt(variance) / mean : 0.0;

        if (cv < 0.15) return 0.4;
        if (cv < 0.3) return 0.7;
        return 1.0;
    }

    private double aggregateConfidence(
        double bayesian,
        double rawSeverity,
        double trust,
        double variance,
        double temporal,
        double zScore
    ) {
        double zScoreWeight = Math.min(1.0, Math.abs(zScore) / Z_SCORE_THRESHOLD);

        double weightedConfidence =
            (bayesian * 0.3) +
            (rawSeverity * 0.25) +
            (variance * 0.2) +
            (temporal * 0.15) +
            (zScoreWeight * 0.1);

        double trustAdjust = 0.7 + (trust * 0.3);
        weightedConfidence *= trustAdjust;

        return Math.max(0.0, Math.min(1.0, weightedConfidence));
    }

    private boolean shouldFilterOut(
        PlayerProfile profile,
        ViolationType type,
        double confidence,
        double zScore,
        double trustMultiplier
    ) {
        CheckStatistics stats = globalStats.get(type);

        if (confidence >= 0.94) {
            return false;
        }

        if (profile.getTrustScore() > HIGH_TRUST_THRESHOLD && confidence < 0.55 && profile.hasEstablishedBaseline()) {
            return true;
        }

        if (profile.hasEstablishedBaseline()) {
            double welfordZ = stats.getWelfordZScore(confidence);
            if (Math.abs(welfordZ) < 1.8 && Math.abs(zScore) < 2.0 && confidence < 0.82) {
                return true;
            }
        }

        if (stats.severities.size() >= 20) {
            double madScore = stats.getMADScore(confidence);
            if (Math.abs(madScore) < 2.0 && confidence < 0.82) {
                return true;
            }
        }

        if (trustMultiplier > 1.3 && confidence < 0.80) {
            return true;
        }

        long recentViolationCount = profile.getRecentViolationCount(300000);
        if (profile.confirmedLegitActions.get() > 5000 && recentViolationCount < 3) {
            if (confidence < 0.82) {
                return true;
            }
        }

        Deque<ViolationRecord> history = profile.violationHistory.get(type);
        if (history.size() >= 3) {
            long now = System.currentTimeMillis();
            long recentCount = history.stream()
                .filter(r -> now - r.timestamp < 3000)
                .count();

            if (recentCount >= 3 && confidence >= 0.55) {
                return false;
            }
        }

        if (confidence < MIN_CONFIDENCE_PASS) {
            return true;
        }

        return false;
    }

    public void recordLegitAction(Player player, ViolationType type) {
        UUID playerId = player.getUniqueId();
        PlayerProfile profile = profiles.computeIfAbsent(playerId, k -> new PlayerProfile());

        profile.recordCheck(type, false);
        profile.adjustTrustScore(0.001);

        CheckStatistics stats = globalStats.get(type);
        stats.record(0.0, false);
    }

    public void recordBreakTiming(Player player, long timing) {
        UUID playerId = player.getUniqueId();
        PlayerProfile profile = profiles.computeIfAbsent(playerId, k -> new PlayerProfile());
        profile.recordBreakTiming(timing);
    }

    public void recordRotation(Player player, double speed) {
        UUID playerId = player.getUniqueId();
        PlayerProfile profile = profiles.computeIfAbsent(playerId, k -> new PlayerProfile());
        profile.recordRotation(speed);
    }

    public void recordMovement(Player player, double speed) {
        UUID playerId = player.getUniqueId();
        PlayerProfile profile = profiles.computeIfAbsent(playerId, k -> new PlayerProfile());
        profile.recordMovement(speed);
    }

    public double getPlayerTrustScore(UUID playerId) {
        PlayerProfile profile = profiles.get(playerId);
        return profile != null ? profile.getTrustScore() : 0.5;
    }

    public void cleanup(UUID playerId) {
        profiles.remove(playerId);
    }

    public void cleanupStale(long maxIdleMs) {
        long now = System.currentTimeMillis();
        profiles.entrySet().removeIf(e -> {
            PlayerProfile pp = e.getValue();
            long lastSeen = 0L;
            for (Long ts : pp.lastViolationTime.values()) if (ts != null && ts > lastSeen) lastSeen = ts;
            long anchor = lastSeen > 0 ? lastSeen : pp.firstSeen;
            return (now - anchor) > maxIdleMs;
        });
    }
}
