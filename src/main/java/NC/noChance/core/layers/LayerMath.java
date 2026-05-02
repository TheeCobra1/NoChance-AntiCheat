package NC.noChance.core.layers;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class LayerMath {
    static final double DECAY_FACTOR = 0.88;
    static final double SIGMOID_MIDPOINT = 140.0;
    static final double SIGMOID_STEEPNESS = 0.018;
    static final double TREND_THRESHOLD = 0.22;
    static final double MOMENTUM_ALPHA = 0.38;

    private LayerMath() {}

    static double weightedAverage(List<Double> values) {
        if (values.isEmpty()) return 0.0;
        double weightedSum = 0.0;
        double totalWeight = 0.0;
        for (int i = 0; i < values.size(); i++) {
            double weight = Math.pow(DECAY_FACTOR, values.size() - 1 - i);
            weightedSum += values.get(i) * weight;
            totalWeight += weight;
        }
        return totalWeight > 0 ? weightedSum / totalWeight : 0.0;
    }

    static double robustVariance(List<Double> values) {
        if (values.isEmpty()) return 0.0;
        List<Double> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        int n = sorted.size();
        int lowerIndex = n / 4;
        int upperIndex = 3 * n / 4;
        List<Double> trimmed = sorted.subList(lowerIndex, Math.min(upperIndex + 1, n));
        double mean = trimmed.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
        return trimmed.stream().mapToDouble(v -> Math.pow(v - mean, 2)).average().orElse(0.0);
    }

    static double trend(List<Double> values) {
        if (values.size() < 3) return 0.0;
        double sumX = 0.0, sumY = 0.0, sumXY = 0.0, sumX2 = 0.0;
        int n = values.size();
        for (int i = 0; i < n; i++) {
            sumX += i;
            sumY += values.get(i);
            sumXY += i * values.get(i);
            sumX2 += i * i;
        }
        double denominator = (n * sumX2 - sumX * sumX);
        if (denominator == 0) return 0.0;
        return (n * sumXY - sumX * sumY) / denominator;
    }

    static double coefficientOfVariation(List<Double> values) {
        if (values.isEmpty()) return 0.0;
        double mean = values.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
        if (mean == 0) return 0.0;
        double variance = values.stream().mapToDouble(v -> Math.pow(v - mean, 2)).average().orElse(0.0);
        return Math.sqrt(variance) / mean;
    }

    static double consistencyMultiplier(List<Double> severities) {
        if (severities.size() < 3) return 1.0;
        double variance = robustVariance(severities);
        double cv = coefficientOfVariation(severities);

        double varianceScore;
        if (variance < 0.005) varianceScore = 1.5;
        else if (variance < 0.02) varianceScore = 1.3;
        else if (variance < 0.05) varianceScore = 1.1;
        else varianceScore = 0.9;

        double cvScore;
        if (cv < 0.1) cvScore = 1.4;
        else if (cv < 0.25) cvScore = 1.2;
        else if (cv < 0.5) cvScore = 1.0;
        else cvScore = 0.85;

        return (varianceScore + cvScore) / 2.0;
    }

    static double momentumMultiplier(List<Double> severities) {
        if (severities.size() < 3) return 1.0;
        double momentum = 0.0;
        for (int i = 1; i < severities.size(); i++) {
            double change = severities.get(i) - severities.get(i - 1);
            momentum = MOMENTUM_ALPHA * change + (1.0 - MOMENTUM_ALPHA) * momentum;
        }
        if (momentum > 0.15) return 1.3;
        if (momentum > 0.05) return 1.15;
        if (momentum < -0.05) return 0.9;
        return 1.0;
    }

    static double sigmoidLagPenalty(int ping) {
        return 1.0 / (1.0 + Math.exp(-SIGMOID_STEEPNESS * (ping - SIGMOID_MIDPOINT)));
    }
}
