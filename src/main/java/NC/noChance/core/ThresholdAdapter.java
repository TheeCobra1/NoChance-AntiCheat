package NC.noChance.core;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.logging.Level;
import java.util.logging.Logger;

public class ThresholdAdapter {
    private final Map<ViolationType, ThresholdData> thresholds;
    private final Deque<Double> tpsHistory;
    private double currentTPSMultiplier;
    private long lastTPSUpdate;

    private static final int TPS_HISTORY_SIZE = 20;
    private static final double TARGET_TPS = 20.0;

    public ThresholdAdapter() {
        this.thresholds = new ConcurrentHashMap<>();
        this.tpsHistory = new ConcurrentLinkedDeque<>();
        this.currentTPSMultiplier = 1.0;
        this.lastTPSUpdate = System.currentTimeMillis();

        for (ViolationType type : ViolationType.values()) {
            thresholds.put(type, new ThresholdData(type));
        }
    }

    private static class ThresholdData {
        private final ViolationType type;
        private double baseThreshold;
        private double currentThreshold;
        private int falsePositiveCount;
        private int truePositiveCount;
        private long lastAdjustment;

        public ThresholdData(ViolationType type) {
            this.type = type;
            this.baseThreshold = 0.70;
            this.currentThreshold = 0.70;
            this.falsePositiveCount = 0;
            this.truePositiveCount = 0;
            this.lastAdjustment = System.currentTimeMillis();
        }
    }

    public void updateTPS() {
        long now = System.currentTimeMillis();
        if (now - lastTPSUpdate < 1000) {
            return;
        }

        lastTPSUpdate = now;

        try {
            double tps = getTPS();

            if (tpsHistory.size() >= TPS_HISTORY_SIZE) {
                tpsHistory.pollFirst();
            }
            tpsHistory.addLast(tps);

            double avgTPS = tpsHistory.stream().mapToDouble(Double::doubleValue).average().orElse(TARGET_TPS);

            if (avgTPS < 15.0) {
                currentTPSMultiplier = 1.5;
            } else if (avgTPS < 17.0) {
                currentTPSMultiplier = 1.3;
            } else if (avgTPS < 19.0) {
                currentTPSMultiplier = 1.15;
            } else {
                currentTPSMultiplier = 1.0;
            }
        } catch (Exception e) {
            Logger.getLogger("NoChance").log(Level.WARNING, "Failed to update TPS multiplier", e);
            currentTPSMultiplier = 1.0;
        }
    }

    private double getTPS() {
        return TPSCache.getTPS();
    }

    public double getAdjustedThreshold(ViolationType type) {
        ThresholdData data = thresholds.get(type);
        if (data == null) return 0.70;

        return Math.min(0.95, data.currentThreshold * currentTPSMultiplier);
    }

    public double getToleranceMultiplier(ViolationType type) {
        return currentTPSMultiplier;
    }

    public void recordFalsePositive(ViolationType type) {
        ThresholdData data = thresholds.get(type);
        if (data == null) return;

        data.falsePositiveCount++;
        adjustThreshold(data);
    }

    public void recordTruePositive(ViolationType type) {
        ThresholdData data = thresholds.get(type);
        if (data == null) return;

        data.truePositiveCount++;
        adjustThreshold(data);
    }

    private void adjustThreshold(ThresholdData data) {
        long now = System.currentTimeMillis();
        if (now - data.lastAdjustment < 120000) {
            return;
        }

        int totalSamples = data.falsePositiveCount + data.truePositiveCount;
        if (totalSamples < 20) {
            return;
        }

        double falsePositiveRate = (double) data.falsePositiveCount / totalSamples;

        if (falsePositiveRate > 0.08) {
            data.currentThreshold = Math.min(0.95, data.currentThreshold + 0.05);
        } else if (falsePositiveRate < 0.03 && data.truePositiveCount > data.falsePositiveCount * 5) {
            data.currentThreshold = Math.max(0.55, data.currentThreshold - 0.03);
        }

        data.lastAdjustment = now;
        data.falsePositiveCount = 0;
        data.truePositiveCount = 0;
    }

    public double getCurrentTPSMultiplier() {
        return currentTPSMultiplier;
    }

    public double getAverageTPS() {
        return tpsHistory.stream().mapToDouble(Double::doubleValue).average().orElse(TARGET_TPS);
    }

    public boolean isServerLagging() {
        return getAverageTPS() < 18.0;
    }
}
