package NC.noChance.core;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

public class ViolationQueue {
    private final Map<UUID, Map<ViolationType, BufferData>> buffers;
    private final Map<UUID, PlayerTrustData> trustData;
    private final ACConfig config;
    private static final int BUFFER_SIZE = 3;
    private static final long BUFFER_WINDOW_MS = 5000;
    private static final double DECAY_FACTOR = 0.80;
    private static final double EWMA_ALPHA = 0.3;
    private static final double DEFAULT_CUSUM_THRESHOLD = 0.6;
    private static final double DEFAULT_CUSUM_DRIFT = 0.05;
    private static final int DEFAULT_WELFORD_WARMUP = 5;

    public ViolationQueue(ACConfig config) {
        this.buffers = new ConcurrentHashMap<>();
        this.trustData = new ConcurrentHashMap<>();
        this.config = config;
    }

    public ViolationQueue() {
        this(null);
    }

    private double cusumThreshold() {
        return config != null ? config.getCusumThreshold() : DEFAULT_CUSUM_THRESHOLD;
    }

    private double cusumDrift() {
        return config != null ? config.getCusumDrift() : DEFAULT_CUSUM_DRIFT;
    }

    private int welfordWarmup() {
        return config != null ? config.getWelfordWarmupSamples() : DEFAULT_WELFORD_WARMUP;
    }

    private static class PlayerTrustData {
        volatile double trustScore = 0.5;
        volatile long cleanTicks = 0;
        volatile long totalChecks = 0;
        volatile double adaptiveThreshold = 0.75;
        volatile long lastUpdate = System.currentTimeMillis();
        volatile long lastViolationTime = 0;
    }

    private static class BufferData {
        private final Deque<ViolationInstance> instances;
        private final Object lock = new Object();
        private long lastViolationTime;
        private double ewmaSeverity;
        private boolean ewmaInit;
        private double cusum;
        private double cusumNeg;
        private double trendScore;
        private int consecutiveFlags;
        private double peakSeverity;
        private double welfordMean;
        private double welfordM2;
        private long welfordN;
        private long lastEwmaUpdate;
        private long lastFlagTime;

        public BufferData() {
            this.instances = new ConcurrentLinkedDeque<>();
            this.lastViolationTime = 0;
            this.ewmaSeverity = 0;
            this.ewmaInit = false;
            this.cusum = 0;
            this.cusumNeg = 0;
            this.trendScore = 0;
            this.consecutiveFlags = 0;
            this.peakSeverity = 0;
            this.welfordMean = 0;
            this.welfordM2 = 0;
            this.welfordN = 0;
            this.lastEwmaUpdate = 0;
            this.lastFlagTime = 0;
        }

        void updateWelford(double value) {
            synchronized (lock) {
                welfordN++;
                double delta = value - welfordMean;
                welfordMean += delta / welfordN;
                double delta2 = value - welfordMean;
                welfordM2 += delta * delta2;
            }
        }

        double getStdDev() {
            synchronized (lock) {
                if (welfordN < 2) return 0.5;
                return Math.sqrt(welfordM2 / (welfordN - 1));
            }
        }

        double getZScore(double value) {
            synchronized (lock) {
                double stdDev = welfordN < 2 ? 0.5 : Math.sqrt(welfordM2 / (welfordN - 1));
                if (stdDev < 0.01) return 0;
                return (value - welfordMean) / stdDev;
            }
        }
    }

    private static class ViolationInstance {
        public final double severity;
        public final double weightedSeverity;
        public final long timestamp;
        public final String details;

        public ViolationInstance(double severity, double weightedSeverity, String details) {
            this.severity = severity;
            this.weightedSeverity = weightedSeverity;
            this.timestamp = System.currentTimeMillis();
            this.details = details;
        }
    }

    public boolean shouldFlag(UUID playerId, ViolationType type, double severity, String details) {
        Map<ViolationType, BufferData> playerBuffers = buffers.computeIfAbsent(playerId, k -> new ConcurrentHashMap<>());
        BufferData buffer = playerBuffers.computeIfAbsent(type, k -> new BufferData());
        PlayerTrustData trust = trustData.computeIfAbsent(playerId, k -> new PlayerTrustData());

        long now = System.currentTimeMillis();
        trust.totalChecks++;

        synchronized (buffer.lock) {
            buffer.instances.removeIf(instance -> now - instance.timestamp > BUFFER_WINDOW_MS);

            double typeWeight = type.getWeight();
            int complexity = type.getComplexity();
            double adaptiveMultiplier = calculateAdaptiveMultiplier(buffer, trust, complexity);
            double weightedSeverity = severity * typeWeight * adaptiveMultiplier;

            buffer.instances.addLast(new ViolationInstance(severity, weightedSeverity, details));
            buffer.lastViolationTime = now;
            buffer.updateWelford(severity);

            if (buffer.lastEwmaUpdate > 0) {
                double elapsedSeconds = (now - buffer.lastEwmaUpdate) / 1000.0;
                if (elapsedSeconds > 2.0) {
                    buffer.ewmaSeverity *= Math.pow(0.75, elapsedSeconds / 2.0);
                }
            }
            buffer.lastEwmaUpdate = now;

            buffer.peakSeverity *= 0.93;
            if (severity > buffer.peakSeverity) {
                buffer.peakSeverity = severity;
            }

            if (!buffer.ewmaInit) {
                buffer.ewmaSeverity = severity;
                buffer.ewmaInit = true;
            } else {
                buffer.ewmaSeverity = EWMA_ALPHA * severity + (1.0 - EWMA_ALPHA) * buffer.ewmaSeverity;
            }

            updateCUSUM(buffer, severity, buffer.welfordMean);
            updateTrend(buffer);

            if (buffer.instances.size() > 12) {
                buffer.instances.pollFirst();
            }

            double adaptiveThreshold = calculateAdaptiveThreshold(trust, complexity);

            if (severity >= 0.95 || weightedSeverity >= 0.98) {
                buffer.consecutiveFlags++;
                buffer.lastFlagTime = now;
                trust.lastViolationTime = now;
                updateTrust(trust, false, severity);
                return true;
            }

            if (buffer.cusum > cusumThreshold() && buffer.instances.size() >= 2 && buffer.welfordN >= welfordWarmup()) {
                buffer.consecutiveFlags++;
                buffer.lastFlagTime = now;
                trust.lastViolationTime = now;
                buffer.cusum = 0;
                buffer.cusumNeg = 0;
                updateTrust(trust, false, severity);
                return true;
            }

            if (buffer.instances.size() < BUFFER_SIZE) {
                return false;
            }

            double decayWeightedAvg = calculateDecayWeightedAverage(buffer);
            double zScore = buffer.getZScore(severity);

            if (decayWeightedAvg >= adaptiveThreshold) {
                buffer.consecutiveFlags++;
                buffer.lastFlagTime = now;
                trust.lastViolationTime = now;
                updateTrust(trust, false, severity);
                return true;
            }

            if (buffer.trendScore > 0.25 && decayWeightedAvg >= adaptiveThreshold * 0.85) {
                buffer.consecutiveFlags++;
                buffer.lastFlagTime = now;
                trust.lastViolationTime = now;
                updateTrust(trust, false, severity);
                return true;
            }

            long highSeverityCount = buffer.instances.stream()
                    .filter(i -> i.severity >= 0.70)
                    .count();

            if (highSeverityCount >= BUFFER_SIZE) {
                buffer.consecutiveFlags++;
                buffer.lastFlagTime = now;
                trust.lastViolationTime = now;
                updateTrust(trust, false, severity);
                return true;
            }

            if (zScore > 2.5 && severity > 0.6 && buffer.welfordN >= 5) {
                buffer.consecutiveFlags++;
                buffer.lastFlagTime = now;
                trust.lastViolationTime = now;
                updateTrust(trust, false, severity);
                return true;
            }

            trust.cleanTicks++;
            updateTrust(trust, true, 0);
            if (trust.totalChecks % 5 == 0) {
                buffer.consecutiveFlags = Math.max(0, buffer.consecutiveFlags - 1);
            }
            if (buffer.lastFlagTime > 0 && now - buffer.lastFlagTime > 12000) {
                buffer.consecutiveFlags /= 2;
                buffer.lastFlagTime = now;
            }
            return false;
        }
    }

    private double calculateAdaptiveMultiplier(BufferData buffer, PlayerTrustData trust, int complexity) {
        double baseMultiplier = 1.0;

        if (buffer.consecutiveFlags > 0) {
            baseMultiplier += 0.1 * Math.min(buffer.consecutiveFlags, 5);
        }

        if (trust.trustScore < 0.3) {
            baseMultiplier *= 1.2;
        } else if (trust.trustScore > 0.7) {
            baseMultiplier *= 0.9;
        }

        if (complexity >= 4) {
            baseMultiplier *= 0.95;
        }

        return baseMultiplier;
    }

    private double calculateAdaptiveThreshold(PlayerTrustData trust, int complexity) {
        double baseThreshold = 0.75;

        if (trust.trustScore > 0.7) {
            baseThreshold += 0.05;
        } else if (trust.trustScore < 0.3) {
            baseThreshold -= 0.08;
        }

        if (complexity >= 4) {
            baseThreshold += 0.03;
        } else if (complexity <= 2) {
            baseThreshold -= 0.02;
        }

        double historyFactor = Math.min(0.1, trust.totalChecks / 10000.0);
        if (trust.cleanTicks > trust.totalChecks * 0.95) {
            baseThreshold += historyFactor;
        }

        return Math.max(0.60, Math.min(0.88, baseThreshold));
    }

    private void updateCUSUM(BufferData buffer, double severity, double target) {
        double deviation = severity - target;
        double drift = cusumDrift();
        buffer.cusum = Math.max(0, buffer.cusum + deviation - drift);
        buffer.cusumNeg = Math.max(0, buffer.cusumNeg - deviation - drift);
    }

    private void updateTrend(BufferData buffer) {
        if (buffer.instances.size() < 3) {
            buffer.trendScore = 0;
            return;
        }

        List<Double> severities = new ArrayList<>();
        for (ViolationInstance vi : buffer.instances) {
            severities.add(vi.severity);
        }

        double sumX = 0, sumY = 0, sumXY = 0, sumX2 = 0;
        int n = severities.size();

        for (int i = 0; i < n; i++) {
            sumX += i;
            sumY += severities.get(i);
            sumXY += i * severities.get(i);
            sumX2 += i * i;
        }

        double denom = (n * sumX2 - sumX * sumX);
        if (Math.abs(denom) < 0.0001) {
            buffer.trendScore = 0;
            return;
        }

        buffer.trendScore = (n * sumXY - sumX * sumY) / denom;
    }

    private double calculateDecayWeightedAverage(BufferData buffer) {
        if (buffer.instances.isEmpty()) return 0.0;

        List<ViolationInstance> list = new ArrayList<>(buffer.instances);
        double weightedSum = 0.0;
        double totalWeight = 0.0;

        for (int i = 0; i < list.size(); i++) {
            double weight = Math.pow(DECAY_FACTOR, list.size() - 1 - i);
            weightedSum += list.get(i).severity * weight;
            totalWeight += weight;
        }

        return totalWeight > 0 ? weightedSum / totalWeight : 0.0;
    }

    private void updateTrust(PlayerTrustData trust, boolean clean, double severity) {
        long now = System.currentTimeMillis();
        trust.lastUpdate = now;

        if (clean) {
            double recovery = 0.006;
            if (trust.lastViolationTime > 0 && now - trust.lastViolationTime > 10000) {
                recovery = 0.03;
            }
            trust.trustScore = Math.min(1.0, trust.trustScore + recovery);
        } else {
            double penalty = 0.05 + (severity * 0.1);
            trust.trustScore = Math.max(0.0, trust.trustScore - penalty);
        }
    }

    public void reset(UUID playerId, ViolationType type) {
        Map<ViolationType, BufferData> playerBuffers = buffers.get(playerId);
        if (playerBuffers != null) {
            playerBuffers.remove(type);
        }
    }

    public void cleanup(UUID playerId) {
        buffers.remove(playerId);
        trustData.remove(playerId);
    }

    public int getViolationCount(UUID playerId, ViolationType type) {
        Map<ViolationType, BufferData> playerBuffers = buffers.get(playerId);
        if (playerBuffers == null) return 0;

        BufferData buffer = playerBuffers.get(type);
        if (buffer == null) return 0;

        synchronized (buffer.lock) {
            return buffer.instances.size();
        }
    }

    public double getAverageSeverity(UUID playerId, ViolationType type) {
        Map<ViolationType, BufferData> playerBuffers = buffers.get(playerId);
        if (playerBuffers == null) return 0.0;

        BufferData buffer = playerBuffers.get(type);
        if (buffer == null) return 0.0;

        synchronized (buffer.lock) {
            return buffer.instances.stream()
                    .mapToDouble(i -> i.severity)
                    .average()
                    .orElse(0.0);
        }
    }

    public double getEWMASeverity(UUID playerId, ViolationType type) {
        Map<ViolationType, BufferData> playerBuffers = buffers.get(playerId);
        if (playerBuffers == null) return 0.0;

        BufferData buffer = playerBuffers.get(type);
        if (buffer == null) return 0.0;

        synchronized (buffer.lock) {
            return buffer.ewmaSeverity;
        }
    }

    public double getCUSUM(UUID playerId, ViolationType type) {
        Map<ViolationType, BufferData> playerBuffers = buffers.get(playerId);
        if (playerBuffers == null) return 0.0;

        BufferData buffer = playerBuffers.get(type);
        if (buffer == null) return 0.0;

        synchronized (buffer.lock) {
            return buffer.cusum;
        }
    }

    public double getTrendScore(UUID playerId, ViolationType type) {
        Map<ViolationType, BufferData> playerBuffers = buffers.get(playerId);
        if (playerBuffers == null) return 0.0;

        BufferData buffer = playerBuffers.get(type);
        if (buffer == null) return 0.0;

        synchronized (buffer.lock) {
            return buffer.trendScore;
        }
    }

    public double getPlayerTrust(UUID playerId) {
        PlayerTrustData trust = trustData.get(playerId);
        return trust != null ? trust.trustScore : 0.5;
    }
}
