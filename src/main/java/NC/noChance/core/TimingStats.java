package NC.noChance.core;

import java.util.*;

public class TimingStats {
    private static final int MAX_SAMPLES = 50;
    private static final double ZSCORE_THRESHOLD = 3.2;

    public static class TimingPattern {
        public final double mean;
        public final double stdDev;
        public final double variance;
        public final double coefficientOfVariation;
        public final double zScore;
        public final boolean suspicious;
        public final double severity;
        public final boolean macroDetected;
        public final String reason;

        public TimingPattern(double mean, double stdDev, double variance, double cv, double zScore, boolean suspicious, double severity, boolean macroDetected, String reason) {
            this.mean = mean;
            this.stdDev = stdDev;
            this.variance = variance;
            this.coefficientOfVariation = cv;
            this.zScore = zScore;
            this.suspicious = suspicious;
            this.severity = severity;
            this.macroDetected = macroDetected;
            this.reason = reason;
        }
    }

    public static TimingPattern analyzeTimings(Deque<Long> timings) {
        if (timings.size() < 5) {
            return new TimingPattern(0, 0, 0, 0, 0, false, 0.0, false, "Insufficient data");
        }

        List<Long> samples = new ArrayList<>(timings);
        if (samples.size() > MAX_SAMPLES) {
            samples = samples.subList(samples.size() - MAX_SAMPLES, samples.size());
        }

        double mean = samples.stream().mapToLong(Long::longValue).average().orElse(0.0);

        double variance = samples.stream()
                .mapToDouble(v -> Math.pow(v - mean, 2))
                .average()
                .orElse(0.0);

        double stdDev = Math.sqrt(variance);
        double cv = mean > 0.01 ? stdDev / mean : 0.0;

        long lastValue = samples.get(samples.size() - 1);
        double zScore = stdDev > 0 ? Math.abs((lastValue - mean) / stdDev) : 0.0;

        boolean suspicious = false;
        double severity = 0.0;
        boolean macroDetected = false;
        String reason = "Clean";

        if (cv < 0.08 && mean < 120) {
            suspicious = true;
            severity = Math.min(1.0, (0.08 - cv) / 0.08 * 0.95);
            reason = String.format("Bot-like consistency: CV=%.3f, Mean=%.1fms", cv, mean);
        } else if (zScore > ZSCORE_THRESHOLD && lastValue < mean * 0.5) {
            suspicious = true;
            severity = Math.min(1.0, zScore / 10.0);
            reason = String.format("Anomalous fast timing: Z=%.2f", zScore);
        } else if (samples.stream().filter(v -> v < 50).count() >= samples.size() * 0.7) {
            suspicious = true;
            severity = 0.82;
            reason = "70%+ timings under 50ms";
        }

        if (detectMacroPattern(new ArrayDeque<>(samples))) {
            macroDetected = true;
            if (!suspicious) {
                severity = 0.88;
            }
        }

        return new TimingPattern(mean, stdDev, variance, cv, zScore, suspicious, severity, macroDetected, reason);
    }

    public static boolean hasConsistentPattern(List<Long> intervals, int minCount) {
        if (intervals.size() < minCount) return false;

        Map<Long, Integer> frequencyMap = new HashMap<>();
        for (Long interval : intervals) {
            long rounded = (interval / 10) * 10;
            frequencyMap.put(rounded, frequencyMap.getOrDefault(rounded, 0) + 1);
        }

        int maxFrequency = frequencyMap.values().stream().max(Integer::compareTo).orElse(0);
        return maxFrequency >= minCount && maxFrequency >= intervals.size() * 0.6;
    }

    public static double calculateEntropy(List<Long> values) {
        if (values.isEmpty()) return 0.0;

        Map<Long, Integer> frequency = new HashMap<>();
        for (Long value : values) {
            long bucket = (value / 10) * 10;
            frequency.put(bucket, frequency.getOrDefault(bucket, 0) + 1);
        }

        double entropy = 0.0;
        int total = values.size();

        for (int count : frequency.values()) {
            double probability = (double) count / total;
            if (probability > 0) {
                entropy -= probability * (Math.log(probability) / Math.log(2));
            }
        }

        return entropy;
    }

    public static boolean detectMacroPattern(Deque<Long> intervals) {
        if (intervals.size() < 8) return false;

        List<Long> list = new ArrayList<>(intervals);
        List<Long> recentIntervals = list.subList(Math.max(0, list.size() - 10), list.size());

        double entropy = calculateEntropy(recentIntervals);

        if (entropy < 1.2) {
            return true;
        }

        long mode = findMode(recentIntervals);
        long modeCount = recentIntervals.stream().filter(v -> Math.abs(v - mode) < 15).count();

        return modeCount >= recentIntervals.size() * 0.65;
    }

    private static long findMode(List<Long> values) {
        Map<Long, Integer> frequency = new HashMap<>();
        for (Long value : values) {
            long bucket = (value / 10) * 10;
            frequency.put(bucket, frequency.getOrDefault(bucket, 0) + 1);
        }

        return frequency.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(0L);
    }
}
