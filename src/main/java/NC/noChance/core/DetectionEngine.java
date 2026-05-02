package NC.noChance.core;

import org.bukkit.entity.Player;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public class DetectionEngine {
    private final Map<UUID, BehavioralProfile> profiles;
    private final SuspicionTracker suspicionTracker;
    private final AIVerdictCache aiVerdictCache;
    private final ACConfig config;
    private final ViolationQueue violationBuffer;
    private final ThresholdAdapter thresholdManager;
    private final VariantCheck variantCheck;
    private final CrossCheckCorrelation correlationSystem;

    public static final class AIVerdict {
        public final String verdict;
        public final double adjust;
        public final double agreement;
        public final long ts;
        public AIVerdict(String verdict, double adjust, double agreement, long ts) {
            this.verdict = verdict; this.adjust = adjust; this.agreement = agreement; this.ts = ts;
        }
    }

    public void applyAIVerdict(UUID uuid, ViolationType type, String verdict, double adjust, double agreement) {
        aiVerdictCache.apply(uuid, type, verdict, adjust, agreement);
    }

    public AIVerdict peekAIVerdict(UUID uuid, ViolationType type) {
        long ttl = (long) (config.getAIVerdictTTL() * 1000);
        return aiVerdictCache.peek(uuid, type, ttl);
    }

    private static final double HUMAN_ENTROPY_MIN = 0.35;
    private static final double BOT_ENTROPY_MAX = 0.12;

    public DetectionEngine(ACConfig config) {
        this.config = config;
        this.profiles = new ConcurrentHashMap<>();
        this.suspicionTracker = new SuspicionTracker();
        this.aiVerdictCache = new AIVerdictCache();
        this.violationBuffer = new ViolationQueue(config);
        this.thresholdManager = new ThresholdAdapter();
        this.variantCheck = new VariantCheck();
        this.correlationSystem = new CrossCheckCorrelation();
    }

    public AnalysisResult analyze(Player player, PlayerData data, CheckResult checkResult, ViolationType type) {
        UUID playerId = player.getUniqueId();
        BehavioralProfile profile = profiles.computeIfAbsent(playerId, k -> new BehavioralProfile());
        suspicionTracker.ensure(playerId);
        SuspicionLevel snapshotSuspicion = suspicionTracker.getLevel(playerId);

        thresholdManager.updateTPS();

        String variant = variantCheck.getPlayerVariant(playerId, type);
        VariantCheck.ThresholdSet thresholds = variantCheck.getThresholds(playerId, type);

        profile.recordCheck(checkResult, type);

        if (checkResult.isFailed()) {
            correlationSystem.recordViolation(playerId, type, checkResult.getSeverity());
        }

        CrossCheckCorrelation.CorrelationResult correlation = correlationSystem.analyzeCorrelation(playerId, type);

        double entropyScore = calculateMovementEntropy(data, type);
        double consistencyScore = calculateConsistencyScore(profile, type);
        double correlationScore = calculateCrossMetricCorrelation(data, profile, type);
        double anomalyScore = calculateStatisticalAnomaly(data, profile, type);
        double fingerprintScore = calculateBehavioralFingerprint(data, profile);

        correlationScore = Math.max(correlationScore, correlation.score);

        EnsembleVoter.Vote vote = EnsembleVoter.vote(
            entropyScore, consistencyScore, correlationScore,
            anomalyScore, fingerprintScore, snapshotSuspicion, type
        );

        double finalConfidence = vote.confidence;
        boolean shouldFlag = vote.shouldFlag;

        if (config.isAIEnabled()) {
            AIVerdict aiv = peekAIVerdict(playerId, type);
            if (aiv != null && Math.abs(aiv.adjust) > 0.01) {
                double capped = Math.max(-config.getAIMaxAdjustment(), Math.min(config.getAIMaxAdjustment(), aiv.adjust));
                finalConfidence = Math.max(0.0, Math.min(1.0, finalConfidence + capped));
            }
        }

        double adjustedThreshold = Math.max(thresholdManager.getAdjustedThreshold(type), thresholds.minConfidence);

        boolean highSeverityCheck = checkResult.isFailed() && checkResult.getSeverity() >= 0.80;

        if (finalConfidence < adjustedThreshold && !thresholdManager.isServerLagging() && !highSeverityCheck) {
            shouldFlag = false;
        }

        if (highSeverityCheck) {
            shouldFlag = true;
            finalConfidence = Math.max(finalConfidence, checkResult.getSeverity() * 0.85);
        }

        if (checkResult.isFailed()) {
            boolean bufferDecision = violationBuffer.shouldFlag(
                playerId,
                type,
                checkResult.getSeverity() * correlation.punishmentMultiplier,
                checkResult.getDetails()
            );
            if (!bufferDecision && !highSeverityCheck) {
                shouldFlag = false;
            }

            int queueCount = violationBuffer.getViolationCount(playerId, type);
            double queueAvgSev = violationBuffer.getAverageSeverity(playerId, type);
            double queueEwma = violationBuffer.getEWMASeverity(playerId, type);
            double queueCusum = violationBuffer.getCUSUM(playerId, type);
            double queueTrend = violationBuffer.getTrendScore(playerId, type);
            double queueTrust = violationBuffer.getPlayerTrust(playerId);

            if (queueCount >= 5 && queueEwma > 0.7 && queueCusum > 1.0) {
                finalConfidence = Math.min(1.0, finalConfidence + 0.08);
            }
            if (queueTrend > 0.3 && queueAvgSev > 0.6) {
                finalConfidence = Math.min(1.0, finalConfidence + 0.05);
            }
            if (queueTrust < 0.25) {
                finalConfidence = Math.min(1.0, finalConfidence + 0.06);
            }
        }

        SuspicionLevel updated = suspicionTracker.update(
            playerId, shouldFlag, finalConfidence, checkResult.isFailed(),
            thresholds.highConfidence, type, thresholdManager, variantCheck,
            profile::incrementConfirmedViolations
        );

        return new AnalysisResult(
            shouldFlag,
            finalConfidence,
            vote.detectionMethod,
            updated,
            entropyScore,
            consistencyScore,
            correlationScore,
            anomalyScore,
            fingerprintScore,
            variant,
            correlation.clusterType,
            correlation.punishmentMultiplier
        );
    }

    private double calculateMovementEntropy(PlayerData data, ViolationType type) {
        if (type == ViolationType.FLY || type == ViolationType.SPEED ||
            type == ViolationType.NOCLIP || type == ViolationType.JESUS ||
            type == ViolationType.NOSLOW) {

            Deque<PlayerData.VelocityData> velocities = data.getVelocityHistory();
            if (velocities.size() < 15) return 0.5;

            double[] magnitudes = new double[velocities.size()];
            int i = 0;
            for (PlayerData.VelocityData v : velocities) {
                magnitudes[i++] = Math.sqrt(v.x * v.x + v.y * v.y + v.z * v.z);
            }

            double entropy = calculateShannonEntropy(magnitudes);

            if (entropy < BOT_ENTROPY_MAX) {
                return 0.95;
            } else if (entropy > HUMAN_ENTROPY_MIN) {
                return 0.1;
            }

            return Math.max(0.0, (HUMAN_ENTROPY_MIN - entropy) / HUMAN_ENTROPY_MIN);
        }

        if (type == ViolationType.KILLAURA || type == ViolationType.KILLAURA_ROTATION ||
            type == ViolationType.KILLAURA_ANGLE || type == ViolationType.KILLAURA_PATTERN) {

            Deque<Long> clicks = data.getClickHistory();
            if (clicks.size() < 30) return 0.5;

            double sum = 0;
            double sumSquared = 0;
            int count = 0;
            Long prev = null;

            for (Long click : clicks) {
                if (prev != null) {
                    long interval = click - prev;
                    sum += interval;
                    sumSquared += interval * interval;
                    count++;
                }
                prev = click;
            }

            if (count == 0) return 0.5;
            double mean = sum / count;
            double variance = Math.max(0.0, (sumSquared / count) - (mean * mean));
            double cv = mean > 0.01 ? Math.sqrt(variance) / mean : 0.0;

            if (cv < 0.05) {
                return 0.96;
            } else if (cv > 0.40) {
                return 0.08;
            }

            return Math.max(0.0, (0.40 - cv) / 0.40);
        }

        return 0.5;
    }

    private double calculateConsistencyScore(BehavioralProfile profile, ViolationType type) {
        List<Double> recentSeverities = profile.getRecentSeverities(type, 30);

        if (recentSeverities.size() < 8) {
            return 0.3;
        }

        double mean = recentSeverities.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
        double variance = calculateVariance(recentSeverities.stream().mapToDouble(Double::doubleValue).toArray());
        double stdDev = Math.sqrt(variance);

        if (stdDev < 0.04 && mean > 0.75) {
            return 0.95;
        }

        if (stdDev > 0.35) {
            return 0.15;
        }

        double consistencyRatio = mean > 0.01 ? 1.0 - Math.min(1.0, stdDev / mean) : 0.5;
        return mean * 0.7 + consistencyRatio * 0.3;
    }

    private double calculateCrossMetricCorrelation(PlayerData data, BehavioralProfile profile, ViolationType type) {
        int correlatedViolations = 0;
        int totalChecks = 0;

        Map<ViolationType, Integer> violationCounts = profile.getViolationCounts();

        if (type == ViolationType.KILLAURA || type.name().startsWith("KILLAURA")) {
            if (violationCounts.getOrDefault(ViolationType.AUTOCLICKER, 0) > 5) correlatedViolations++;
            if (violationCounts.getOrDefault(ViolationType.REACH, 0) > 3) correlatedViolations++;
            if (data.getAverageCPS() > 20) correlatedViolations++;
            if (data.getAverageRotationSpeed() > 500) correlatedViolations++;
            totalChecks = 4;
        } else if (type == ViolationType.FLY) {
            if (violationCounts.getOrDefault(ViolationType.SPEED, 0) > 5) correlatedViolations++;
            if (violationCounts.getOrDefault(ViolationType.NOCLIP, 0) > 3) correlatedViolations++;
            if (data.getAirTicks() > 100) correlatedViolations++;
            totalChecks = 3;
        } else if (type == ViolationType.SCAFFOLD) {
            if (violationCounts.getOrDefault(ViolationType.FASTPLACE, 0) > 5) correlatedViolations++;
            if (violationCounts.getOrDefault(ViolationType.KILLAURA, 0) > 3) correlatedViolations++;
            totalChecks = 2;
        }

        if (totalChecks == 0) return 0.5;

        return (double) correlatedViolations / totalChecks;
    }

    private double calculateStatisticalAnomaly(PlayerData data, BehavioralProfile profile, ViolationType type) {
        List<Double> recentSeverities = profile.getRecentSeverities(type, 50);

        if (recentSeverities.isEmpty()) return 0.0;

        double[] values = recentSeverities.stream().mapToDouble(Double::doubleValue).toArray();
        double mean = recentSeverities.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
        double variance = calculateVariance(values);
        double stdDev = Math.sqrt(variance);

        double currentSeverity = recentSeverities.get(recentSeverities.size() - 1);

        WelfordStats ws = profile.getWelfordStats(type);
        double zScore;
        if (ws != null && ws.getN() >= 3) {
            zScore = welfordZScore(currentSeverity, ws.getMean(), ws.getM2(), ws.getN());
        } else {
            zScore = stdDev > 0 ? (currentSeverity - mean) / stdDev : 0.0;
        }

        CUSUMTracker cusum = profile.getCUSUMTracker(type);
        double cusumBonus = 0.0;
        if (cusum != null && cusum.hasSignificantTrend()) {
            cusumBonus = 0.12;
        }

        double cusumPeak = calculateCUSUM(values, mean, 0.05);
        if (cusumPeak > 3.0) {
            cusumBonus = Math.max(cusumBonus, 0.15);
        }

        if (zScore > 4.0) {
            return Math.min(1.0, 0.96 + cusumBonus);
        } else if (zScore > 3.0) {
            return Math.min(1.0, 0.78 + cusumBonus);
        } else if (zScore > 2.2) {
            return Math.min(1.0, 0.50 + cusumBonus);
        } else if (zScore < -2.0) {
            return 0.05;
        }

        return 0.15 + cusumBonus;
    }

    private double calculateBehavioralFingerprint(PlayerData data, BehavioralProfile profile) {
        double fingerprintScore = 0.0;
        int factors = 0;

        if (data.getClickHistory().size() >= 20) {
            Deque<Long> clicks = data.getClickHistory();
            Set<Long> uniqueIntervals = new HashSet<>();
            int intervalCount = 0;
            Long prev = null;
            for (Long click : clicks) {
                if (prev != null) {
                    uniqueIntervals.add(click - prev);
                    intervalCount++;
                }
                prev = click;
            }
            double intervalDiversity = intervalCount > 0 ? (double) uniqueIntervals.size() / intervalCount : 0;

            if (intervalDiversity < 0.1) {
                fingerprintScore += 1.0;
            } else if (intervalDiversity > 0.6) {
                fingerprintScore -= 0.5;
            }
            factors++;
        }

        if (data.getRotationHistory().size() >= 10) {
            Deque<PlayerData.RotationData> rotations = data.getRotationHistory();
            double sum = 0;
            double sumSquared = 0;
            int count = 0;
            PlayerData.RotationData prev = null;
            for (PlayerData.RotationData rot : rotations) {
                if (prev != null) {
                    float diff = rot.yaw - prev.yaw;
                    while (diff > 180) diff -= 360;
                    while (diff < -180) diff += 360;
                    float change = Math.abs(diff);
                    sum += change;
                    sumSquared += change * change;
                    count++;
                }
                prev = rot;
            }
            double mean = count > 0 ? sum / count : 0;
            double yawVariance = count > 0 ? (sumSquared / count) - (mean * mean) : 0;

            if (yawVariance < 1.0) {
                fingerprintScore += 0.8;
            } else if (yawVariance > 100.0) {
                fingerprintScore -= 0.3;
            }
            factors++;
        }

        double violationRatio = data.getViolationRatio();
        if (violationRatio > 0.5) {
            fingerprintScore += 1.0;
            factors++;
        } else if (violationRatio < 0.05) {
            fingerprintScore -= 0.5;
            factors++;
        }

        if (profile.getConfirmedViolations() > 10) {
            fingerprintScore += 1.5;
            factors++;
        }

        return factors > 0 ? Math.max(0.0, Math.min(1.0, (fingerprintScore + factors * 0.5) / (factors * 2))) : 0.5;
    }

    private double calculateShannonEntropy(double[] values) {
        if (values.length == 0) return 0.0;

        int bins = 10;
        double min = Double.MAX_VALUE;
        double max = -Double.MAX_VALUE;
        for (double v : values) {
            if (v < min) min = v;
            if (v > max) max = v;
        }
        double range = max - min;

        if (range == 0) return 0.0;

        int[] histogram = new int[bins];
        for (double value : values) {
            int bin = Math.min(bins - 1, (int) ((value - min) / range * bins));
            histogram[bin]++;
        }

        double entropy = 0.0;
        int total = values.length;
        int usedBins = 0;
        for (int count : histogram) {
            if (count > 0) {
                usedBins++;
                double p = (double) count / total;
                entropy -= p * (Math.log(p) / Math.log(2));
            }
        }

        if (usedBins <= 1) return 0.0;
        return entropy / (Math.log(usedBins) / Math.log(2));
    }

    private double calculateVariance(double[] values) {
        if (values.length == 0) return 0.0;
        return welfordVariance(values);
    }

    private double welfordVariance(double[] values) {
        if (values.length < 2) return 0.0;

        double mean = 0.0;
        double m2 = 0.0;

        for (int i = 0; i < values.length; i++) {
            double delta = values[i] - mean;
            mean += delta / (i + 1);
            double delta2 = values[i] - mean;
            m2 += delta * delta2;
        }

        return m2 / (values.length - 1);
    }

    private double calculateCUSUM(double[] values, double target, double drift) {
        if (values.length == 0) return 0.0;

        double cusumPos = 0.0;
        double cusumNeg = 0.0;
        double maxCusum = 0.0;

        for (double value : values) {
            double deviation = value - target;
            cusumPos = Math.max(0, cusumPos + deviation - drift);
            cusumNeg = Math.max(0, cusumNeg - deviation - drift);
            maxCusum = Math.max(maxCusum, Math.max(cusumPos, cusumNeg));
        }

        return maxCusum;
    }

    private double welfordZScore(double value, double mean, double m2, long n) {
        if (n < 2) return 0.0;
        double variance = m2 / (n - 1);
        double stdDev = Math.sqrt(variance);
        if (stdDev < 0.001) return 0.0;
        return (value - mean) / stdDev;
    }

    public ThresholdAdapter getThresholdAdapter() {
        return thresholdManager;
    }

    public VariantCheck getVariantCheck() {
        return variantCheck;
    }

    public void cleanup(UUID playerId) {
        profiles.remove(playerId);
        suspicionTracker.cleanup(playerId);
        aiVerdictCache.cleanup(playerId);
        for (ViolationType type : ViolationType.values()) {
            violationBuffer.reset(playerId, type);
        }
        violationBuffer.cleanup(playerId);
        variantCheck.cleanup(playerId);
        correlationSystem.cleanup(playerId);
    }

    public void cleanupOldData() {
        correlationSystem.cleanupOldEntries();
        long now = System.currentTimeMillis();
        profiles.entrySet().removeIf(e -> e.getValue().isStale(now, 900000));
        suspicionTracker.retainOnly(profiles.keySet());
        long verdictTtl = (long) (config.getAIVerdictTTL() * 1000);
        aiVerdictCache.evictExpired(verdictTtl);
        profiles.forEach((uuid, profile) -> {
            profile.cleanupOldSeverities(now, 60000);
            profile.decayViolationCounts(now);
        });
    }

    public Map<UUID, Map<ViolationType, AIVerdict>> getAiVerdictsMap() {
        return aiVerdictCache.getMap();
    }

    public SuspicionLevel getSuspicionLevel(UUID playerId) {
        return suspicionTracker.getLevel(playerId);
    }

    public int getStateSize() {
        return profiles.size() + suspicionTracker.size() + aiVerdictCache.size();
    }

    public enum SuspicionLevel {
        CLEAN,
        LOW,
        MEDIUM,
        HIGH,
        CONFIRMED
    }

    public static final class AnalysisResult {
        public final boolean shouldFlag;
        public final double confidence;
        public final String detectionMethod;
        public final SuspicionLevel suspicionLevel;
        public final double entropyScore;
        public final double consistencyScore;
        public final double correlationScore;
        public final double anomalyScore;
        public final double fingerprintScore;
        public final String variant;
        public final CrossCheckCorrelation.CorrelationType clusterType;
        public final double punishmentMultiplier;

        AnalysisResult(boolean shouldFlag, double confidence, String detectionMethod,
                            SuspicionLevel suspicionLevel, double entropyScore, double consistencyScore,
                            double correlationScore, double anomalyScore, double fingerprintScore,
                            String variant, CrossCheckCorrelation.CorrelationType clusterType,
                            double punishmentMultiplier) {
            this.shouldFlag = shouldFlag;
            this.confidence = confidence;
            this.detectionMethod = detectionMethod;
            this.suspicionLevel = suspicionLevel;
            this.entropyScore = entropyScore;
            this.consistencyScore = consistencyScore;
            this.correlationScore = correlationScore;
            this.anomalyScore = anomalyScore;
            this.fingerprintScore = fingerprintScore;
            this.variant = variant;
            this.clusterType = clusterType;
            this.punishmentMultiplier = punishmentMultiplier;
        }
    }

    private static class BehavioralProfile {
        private final Map<ViolationType, List<SeverityRecord>> severityHistory;
        private final Map<ViolationType, Integer> violationCounts;
        private final Map<ViolationType, WelfordStats> welfordStats;
        private final Map<ViolationType, CUSUMTracker> cusumTrackers;
        private final AtomicInteger confirmedViolations;
        private volatile long lastActivity;
        private volatile long lastDecayTime;
        private static final long DECAY_INTERVAL = 25000;
        private static final double DECAY_FACTOR = 0.55;

        public BehavioralProfile() {
            this.severityHistory = new ConcurrentHashMap<>();
            this.violationCounts = new ConcurrentHashMap<>();
            this.welfordStats = new ConcurrentHashMap<>();
            this.cusumTrackers = new ConcurrentHashMap<>();
            this.confirmedViolations = new AtomicInteger(0);
            this.lastActivity = System.currentTimeMillis();
            this.lastDecayTime = System.currentTimeMillis();
        }

        public void recordCheck(CheckResult result, ViolationType type) {
            lastActivity = System.currentTimeMillis();
            if (result.isFailed()) {
                double severity = result.getSeverity();
                severityHistory.computeIfAbsent(type, k -> Collections.synchronizedList(new ArrayList<>()))
                    .add(new SeverityRecord(severity, System.currentTimeMillis()));
                violationCounts.merge(type, 1, Integer::sum);

                welfordStats.computeIfAbsent(type, k -> new WelfordStats()).update(severity);
                cusumTrackers.computeIfAbsent(type, k -> new CUSUMTracker()).update(severity);
            }
        }

        public boolean isStale(long now, long maxAge) {
            return now - lastActivity > maxAge;
        }

        public List<Double> getRecentSeverities(ViolationType type, int count) {
            List<SeverityRecord> records = severityHistory.getOrDefault(type, Collections.emptyList());
            List<Double> result = new ArrayList<>();
            long cutoff = System.currentTimeMillis() - 60000;
            synchronized (records) {
                int start = Math.max(0, records.size() - count);
                for (int i = start; i < records.size(); i++) {
                    if (records.get(i).timestamp >= cutoff) {
                        result.add(records.get(i).severity);
                    }
                }
            }
            return result;
        }

        public Map<ViolationType, Integer> getViolationCounts() {
            return violationCounts;
        }

        public int getConfirmedViolations() {
            return confirmedViolations.get();
        }

        public void incrementConfirmedViolations() {
            this.confirmedViolations.incrementAndGet();
        }

        public WelfordStats getWelfordStats(ViolationType type) {
            return welfordStats.get(type);
        }

        public CUSUMTracker getCUSUMTracker(ViolationType type) {
            return cusumTrackers.get(type);
        }

        public void cleanupOldSeverities(long now, long maxAge) {
            long cutoff = now - maxAge;
            for (List<SeverityRecord> records : severityHistory.values()) {
                synchronized (records) {
                    records.removeIf(r -> r.timestamp < cutoff);
                }
            }
        }

        public void decayViolationCounts(long now) {
            if (now - lastDecayTime < DECAY_INTERVAL) return;
            lastDecayTime = now;
            violationCounts.replaceAll((type, count) -> Math.max(0, (int) (count * DECAY_FACTOR)));
            violationCounts.values().removeIf(v -> v <= 0);
            confirmedViolations.updateAndGet(c -> Math.max(0, (int) (c * DECAY_FACTOR)));
        }

        private static final class SeverityRecord {
            private final double severity;
            private final long timestamp;

            private SeverityRecord(double severity, long timestamp) {
                this.severity = severity;
                this.timestamp = timestamp;
            }
        }
    }

    private static class WelfordStats {
        private double mean = 0.0;
        private double m2 = 0.0;
        private long n = 0;

        public synchronized void update(double value) {
            n++;
            double delta = value - mean;
            mean += delta / n;
            double delta2 = value - mean;
            m2 += delta * delta2;
        }

        public double getMean() { return mean; }
        public double getM2() { return m2; }
        public long getN() { return n; }

    }

    private static class CUSUMTracker {
        private static final double DRIFT = 0.05;
        private static final double TARGET = 0.5;

        private double cusumPos = 0.0;
        private double cusumNeg = 0.0;
        private double peakCusum = 0.0;

        public synchronized void update(double value) {
            double deviation = value - TARGET;
            cusumPos = Math.max(0, cusumPos + deviation - DRIFT);
            cusumNeg = Math.max(0, cusumNeg - deviation - DRIFT);
            peakCusum = Math.max(peakCusum, Math.max(cusumPos, cusumNeg));
        }

        public boolean hasSignificantTrend() {
            return cusumPos > 1.5 || cusumNeg > 1.5;
        }

    }
}
