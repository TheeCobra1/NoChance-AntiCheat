package NC.noChance.core;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class VariantCheck {
    private final Map<UUID, VariantAssignment> playerVariants;
    private final Map<ViolationType, VariantConfig> variantConfigs;
    private final Map<ViolationType, Map<String, VariantMetrics>> metrics;
    private final Random random;

    private static final double VARIANT_A_RATIO = 0.40;
    private static final double VARIANT_B_RATIO = 0.40;
    private static final double VARIANT_C_RATIO = 0.20;

    private static final int MIN_SAMPLES_FOR_ANALYSIS = 100;
    private static final double CONFIDENCE_THRESHOLD = 0.05;

    public VariantCheck() {
        this.playerVariants = new ConcurrentHashMap<>();
        this.variantConfigs = new ConcurrentHashMap<>();
        this.metrics = new ConcurrentHashMap<>();
        this.random = new Random();
        initializeDefaultConfigs();
    }

    private void initializeDefaultConfigs() {
        variantConfigs.put(ViolationType.REACH, new VariantConfig(
            new ThresholdSet(3.1, 0.66, 0.75),
            new ThresholdSet(3.3, 0.70, 0.80),
            new ThresholdSet(3.5, 0.72, 0.82)
        ));

        variantConfigs.put(ViolationType.FLY, new VariantConfig(
            new ThresholdSet(0.18, 0.66, 0.70),
            new ThresholdSet(0.22, 0.68, 0.72),
            new ThresholdSet(0.25, 0.70, 0.74)
        ));

        variantConfigs.put(ViolationType.SPEED, new VariantConfig(
            new ThresholdSet(0.28, 0.65, 0.72),
            new ThresholdSet(0.32, 0.68, 0.74),
            new ThresholdSet(0.35, 0.70, 0.76)
        ));

        variantConfigs.put(ViolationType.KILLAURA, new VariantConfig(
            new ThresholdSet(180.0, 0.70, 0.78),
            new ThresholdSet(160.0, 0.72, 0.80),
            new ThresholdSet(140.0, 0.74, 0.82)
        ));

        variantConfigs.put(ViolationType.AUTOCLICKER, new VariantConfig(
            new ThresholdSet(22.0, 0.68, 0.76),
            new ThresholdSet(24.0, 0.70, 0.78),
            new ThresholdSet(26.0, 0.72, 0.80)
        ));

        variantConfigs.put(ViolationType.FASTBREAK, new VariantConfig(
            new ThresholdSet(0.85, 0.65, 0.72),
            new ThresholdSet(0.80, 0.68, 0.74),
            new ThresholdSet(0.75, 0.70, 0.76)
        ));

        variantConfigs.put(ViolationType.SCAFFOLD, new VariantConfig(
            new ThresholdSet(4.0, 0.66, 0.74),
            new ThresholdSet(5.0, 0.68, 0.76),
            new ThresholdSet(6.0, 0.70, 0.78)
        ));

        variantConfigs.put(ViolationType.VELOCITY, new VariantConfig(
            new ThresholdSet(0.70, 0.68, 0.75),
            new ThresholdSet(0.65, 0.70, 0.77),
            new ThresholdSet(0.60, 0.72, 0.79)
        ));

        for (ViolationType type : variantConfigs.keySet()) {
            Map<String, VariantMetrics> typeMetrics = new ConcurrentHashMap<>();
            typeMetrics.put("A", new VariantMetrics());
            typeMetrics.put("B", new VariantMetrics());
            typeMetrics.put("C", new VariantMetrics());
            metrics.put(type, typeMetrics);
        }
    }

    public String getPlayerVariant(UUID playerId, ViolationType type) {
        VariantAssignment assignment = playerVariants.computeIfAbsent(playerId, k -> assignVariant());
        return assignment.getVariant(type);
    }

    private VariantAssignment assignVariant() {
        Map<ViolationType, String> assignments = new HashMap<>();
        for (ViolationType type : ViolationType.values()) {
            double roll = random.nextDouble();
            String variant;
            if (roll < VARIANT_A_RATIO) {
                variant = "A";
            } else if (roll < VARIANT_A_RATIO + VARIANT_B_RATIO) {
                variant = "B";
            } else if (roll < VARIANT_A_RATIO + VARIANT_B_RATIO + VARIANT_C_RATIO) {
                variant = "C";
            } else {
                variant = "A";
            }
            assignments.put(type, variant);
        }
        return new VariantAssignment(assignments);
    }

    public ThresholdSet getThresholds(UUID playerId, ViolationType type) {
        if (!variantConfigs.containsKey(type)) {
            return new ThresholdSet(0.0, 0.66, 0.75);
        }

        String variant = getPlayerVariant(playerId, type);
        VariantConfig config = variantConfigs.get(type);

        switch (variant) {
            case "A":
                return config.variantA;
            case "B":
                return config.variantB;
            case "C":
                return config.variantC;
            default:
                return config.variantA;
        }
    }

    public void recordDetection(UUID playerId, ViolationType type, boolean truePositive) {
        if (!metrics.containsKey(type)) return;

        String variant = getPlayerVariant(playerId, type);
        VariantMetrics variantMetrics = metrics.get(type).get(variant);

        if (variantMetrics != null) {
            variantMetrics.recordDetection(truePositive);
        }
    }

    public void recordFalsePositive(UUID playerId, ViolationType type) {
        if (!metrics.containsKey(type)) return;

        String variant = getPlayerVariant(playerId, type);
        VariantMetrics variantMetrics = metrics.get(type).get(variant);

        if (variantMetrics != null) {
            variantMetrics.recordFalsePositive();
        }
    }

    public VariantAnalysis analyzeVariants(ViolationType type) {
        if (!metrics.containsKey(type)) {
            return new VariantAnalysis("A", 0.0, false);
        }

        Map<String, VariantMetrics> typeMetrics = metrics.get(type);
        VariantMetrics metricsA = typeMetrics.get("A");
        VariantMetrics metricsB = typeMetrics.get("B");
        VariantMetrics metricsC = typeMetrics.get("C");

        if (metricsA.getTotalSamples() < MIN_SAMPLES_FOR_ANALYSIS ||
            metricsB.getTotalSamples() < MIN_SAMPLES_FOR_ANALYSIS ||
            metricsC.getTotalSamples() < MIN_SAMPLES_FOR_ANALYSIS) {
            return new VariantAnalysis("A", 0.0, false);
        }

        double scoreA = metricsA.getAccuracyScore();
        double scoreB = metricsB.getAccuracyScore();
        double scoreC = metricsC.getAccuracyScore();

        String bestVariant = "A";
        double bestScore = scoreA;

        if (scoreB > bestScore) {
            bestVariant = "B";
            bestScore = scoreB;
        }
        if (scoreC > bestScore) {
            bestVariant = "C";
            bestScore = scoreC;
        }

        double secondBestScore = Math.max(
            bestVariant.equals("A") ? Math.max(scoreB, scoreC) : Math.max(scoreA, bestVariant.equals("B") ? scoreC : scoreB),
            0.0
        );

        double improvement = bestScore - secondBestScore;
        boolean significant = improvement > CONFIDENCE_THRESHOLD && bestScore > 0.80;

        return new VariantAnalysis(bestVariant, improvement, significant);
    }

    public Map<String, String> getMetricsSummary(ViolationType type) {
        if (!metrics.containsKey(type)) {
            return new HashMap<>();
        }

        Map<String, String> summary = new HashMap<>();
        Map<String, VariantMetrics> typeMetrics = metrics.get(type);

        for (Map.Entry<String, VariantMetrics> entry : typeMetrics.entrySet()) {
            String variant = entry.getKey();
            VariantMetrics m = entry.getValue();

            summary.put(variant + "_samples", String.valueOf(m.getTotalSamples()));
            summary.put(variant + "_accuracy", String.format("%.2f%%", m.getAccuracyScore() * 100));
            summary.put(variant + "_fp_rate", String.format("%.2f%%", m.getFalsePositiveRate() * 100));
            summary.put(variant + "_tp_rate", String.format("%.2f%%", m.getTruePositiveRate() * 100));
        }

        return summary;
    }

    public void cleanup(UUID playerId) {
        playerVariants.remove(playerId);
    }

    public static class ThresholdSet {
        public final double primaryThreshold;
        public final double minConfidence;
        public final double highConfidence;

        public ThresholdSet(double primaryThreshold, double minConfidence, double highConfidence) {
            this.primaryThreshold = primaryThreshold;
            this.minConfidence = minConfidence;
            this.highConfidence = highConfidence;
        }
    }

    private static class VariantConfig {
        private final ThresholdSet variantA;
        private final ThresholdSet variantB;
        private final ThresholdSet variantC;

        private VariantConfig(ThresholdSet variantA, ThresholdSet variantB, ThresholdSet variantC) {
            this.variantA = variantA;
            this.variantB = variantB;
            this.variantC = variantC;
        }
    }

    private static class VariantAssignment {
        private final Map<ViolationType, String> assignments;

        private VariantAssignment(Map<ViolationType, String> assignments) {
            this.assignments = assignments;
        }

        private String getVariant(ViolationType type) {
            return assignments.getOrDefault(type, "A");
        }
    }

    private static class VariantMetrics {
        private int truePositives;
        private int falsePositives;
        private int totalDetections;

        private VariantMetrics() {
            this.truePositives = 0;
            this.falsePositives = 0;
            this.totalDetections = 0;
        }

        private synchronized void recordDetection(boolean truePositive) {
            totalDetections++;
            if (truePositive) {
                truePositives++;
            }
        }

        private synchronized void recordFalsePositive() {
            falsePositives++;
        }

        private synchronized int getTotalSamples() {
            return totalDetections + falsePositives;
        }

        private synchronized double getTruePositiveRate() {
            if (totalDetections == 0) return 0.0;
            return (double) truePositives / totalDetections;
        }

        private synchronized double getFalsePositiveRate() {
            int total = getTotalSamples();
            if (total == 0) return 0.0;
            return (double) falsePositives / total;
        }

        private synchronized double getAccuracyScore() {
            int total = getTotalSamples();
            if (total == 0) return 0.0;

            double tpRate = getTruePositiveRate();
            double fpRate = getFalsePositiveRate();

            return (tpRate * 0.70) + ((1.0 - fpRate) * 0.30);
        }
    }

    public static class VariantAnalysis {
        public final String bestVariant;
        public final double improvement;
        public final boolean statistically_significant;

        private VariantAnalysis(String bestVariant, double improvement, boolean significant) {
            this.bestVariant = bestVariant;
            this.improvement = improvement;
            this.statistically_significant = significant;
        }
    }
}
