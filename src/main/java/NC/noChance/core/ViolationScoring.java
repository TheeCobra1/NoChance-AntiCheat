package NC.noChance.core;

import NC.noChance.alerts.StaffAlertManager;
import NC.noChance.database.DatabaseManager;
import NC.noChance.punishment.PunishmentManager;
import NC.noChance.replay.ReplayMgr;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.*;

public class ViolationScoring {
    private final ACConfig config;
    private final Map<UUID, PlayerData> playerDataMap;
    private final DatabaseManager database;
    private final PunishmentManager punishmentManager;
    private final StaffAlertManager alertManager;
    private final DetectionEngine detectionEngine;
    private ReplayMgr replayMgr;

    public ViolationScoring(ACConfig config, Map<UUID, PlayerData> playerDataMap,
                           DatabaseManager database, PunishmentManager punishmentManager,
                           StaffAlertManager alertManager, DetectionEngine detectionEngine) {
        this.config = config;
        this.playerDataMap = playerDataMap;
        this.database = database;
        this.punishmentManager = punishmentManager;
        this.alertManager = alertManager;
        this.detectionEngine = detectionEngine;
    }

    public void setReplayMgr(ReplayMgr replayMgr) {
        this.replayMgr = replayMgr;
    }

    public double calculateViolationScore(UUID playerId, long timeWindow) {
        PlayerData data = playerDataMap.get(playerId);
        if (data == null) return 0.0;

        double finalScore = 0.0;

        for (ViolationType type : ViolationType.values()) {
            List<PlayerData.ViolationRecord> violations = data.getViolations(type, timeWindow);
            if (violations.isEmpty()) continue;

            double typeWeight = type.getWeight();
            double frequency = (double) violations.size() / (timeWindow / 1000.0);
            double avgSeverity = violations.stream()
                    .mapToDouble(v -> v.severity)
                    .average()
                    .orElse(0.0);

            finalScore += typeWeight * frequency * avgSeverity;
        }

        return finalScore;
    }

    public ConfidenceLevel getConfidenceLevel(double score) {
        double extremeThreshold = config.getExtremeConfidenceThreshold();
        double highThreshold = config.getHighConfidenceThreshold();
        double mediumThreshold = config.getMediumConfidenceThreshold();
        double lowThreshold = config.getLowConfidenceThreshold();

        if (score >= extremeThreshold) {
            return ConfidenceLevel.EXTREME;
        } else if (score >= highThreshold) {
            return ConfidenceLevel.HIGH;
        } else if (score >= mediumThreshold) {
            return ConfidenceLevel.MEDIUM;
        } else if (score >= lowThreshold) {
            return ConfidenceLevel.LOW;
        }
        return ConfidenceLevel.NONE;
    }

    public void handleViolationWithAdvancedMetrics(Player player, CheckResult result,
                                                   double advancedScore, String detectionMethod,
                                                   MultiLayerValidator.ConfidenceLevel validationConfidence,
                                                   String validationVariant,
                                                   double validationPunishmentMult) {
        PlayerData data = playerDataMap.get(player.getUniqueId());
        if (data == null) return;

        data.incrementTotalChecks();

        if (!result.isFailed()) return;

        long resultAge = System.currentTimeMillis() - result.getTimestamp();
        if (resultAge > 10000) {
            return;
        }

        if (config.isOpExempt() && player.isOp()) {
            return;
        }

        if (data.isInGracePeriod(config.getGracePeriod())) {
            return;
        }

        double finalScore;
        String finalDetectionMethod;
        String variant = "";

        if (advancedScore > 0) {
            finalScore = advancedScore;
            finalDetectionMethod = detectionMethod;
        } else {
            long timeWindow = config.getTimeWindow() * 1000L;
            finalScore = calculateViolationScore(player.getUniqueId(), timeWindow);
            finalDetectionMethod = detectionMethod;

            double histMult = data.getHistoricalConfidenceMultiplier();
            finalScore *= histMult;
        }

        double baseScore = finalScore;

        if (data.wasRecentlyKicked()) {
            finalScore *= 1.15;
        }

        int histCount = data.getHistoricalViolationCount();
        if (histCount > 10) {
            finalScore *= 1.10;
        } else if (histCount > 5) {
            finalScore *= 1.05;
        }

        if (validationConfidence != null && validationConfidence.ordinal() >= MultiLayerValidator.ConfidenceLevel.HIGH.ordinal()) {
            finalScore *= validationPunishmentMult;
        }

        if (validationVariant != null && !validationVariant.isEmpty() && variant.isEmpty()) {
            variant = validationVariant;
        }

        if (data.isBaselineEstablished() && isStatisticalOutlier(data, result.getSeverity(), result.getViolationType().name())) {
            finalScore *= 1.15;
        }

        finalScore = Math.min(finalScore, baseScore * 1.35);

        String rawName = result.getViolationType().name().toLowerCase();
        int underscore = rawName.indexOf('_');
        String checkName = underscore > 0 ? rawName.substring(0, underscore) : rawName;
        double checkMult = config.getSeverityMultiplier(checkName);
        if (Double.isFinite(checkMult) && checkMult > 0 && Math.abs(checkMult - 1.0) > 0.001) {
            finalScore *= checkMult;
        }

        if (result.getViolationType() == ViolationType.NOSLOW) {
            variant = extractNoSlowVariant(result.getDetails());
        }

        ConfidenceLevel confidence = getConfidenceLevel(finalScore);

        if (confidence == ConfidenceLevel.NONE) {
            return;
        }

        data.incrementTotalViolations();
        data.addViolation(result.getViolationType(), result.getSeverity(), result.getDetails());

        if (replayMgr != null) {
            boolean shouldSave = (confidence == ConfidenceLevel.HIGH && config.shouldSaveReplayOnHigh()) ||
                    (confidence == ConfidenceLevel.EXTREME && config.shouldSaveReplayOnExtreme());
            if (shouldSave) {
                replayMgr.saveReplay(player, result.getViolationType(), confidence.name());
            }
        }

        database.logViolation(
            player.getUniqueId(),
            player.getName(),
            result.getViolationType(),
            result.getSeverity(),
            result.getDetails(),
            confidence.name(),
            finalDetectionMethod
        ).exceptionally(ex -> {
            Bukkit.getLogger().warning("[NoChance] Failed to log violation: " + ex.getMessage());
            return null;
        });

        alertManager.sendAlert(
            player,
            result.getViolationType(),
            result.getSeverity(),
            result.getDetails(),
            confidence.name(),
            finalScore,
            finalDetectionMethod,
            variant
        );

        punishmentManager.handleViolation(
            player,
            result.getViolationType(),
            result.getSeverity(),
            result.getDetails(),
            confidence.name()
        );
    }

    private boolean isStatisticalOutlier(PlayerData data, double value, String metricKey) {
        double baseline = data.getBaselineValue(metricKey, value);
        double stdDev = data.getBaselineValue(metricKey + "_stddev", 0.0);

        if (stdDev == 0.0) {
            updateBaseline(data, metricKey, value);
            return false;
        }

        double deviation = Math.abs(value - baseline);
        double threshold = config.getStandardDeviationMultiplier() * stdDev;

        if (deviation > threshold) {
            data.incrementOutlierCount();
            if (data.getOutlierCount() > config.getOutlierForgiveness()) {
                return true;
            }
        } else {
            data.resetOutlierCount();
        }

        updateBaseline(data, metricKey, value);
        return false;
    }

    private void updateBaseline(PlayerData data, String key, double newValue) {
        synchronized (data) {
            double oldMean = data.getBaselineValue(key, newValue);
            double count = data.getBaselineValue(key + "_count", 0.0) + 1;

            double newMean = ((oldMean * (count - 1)) + newValue) / count;
            data.updateBaseline(key, newMean);
            data.updateBaseline(key + "_count", count);

            double variance = data.getBaselineValue(key + "_variance", 0.0);
            double delta = newValue - oldMean;
            double delta2 = newValue - newMean;
            variance = ((count - 1) * variance + delta * delta2) / count;
            double stdDev = Math.sqrt(variance);

            data.updateBaseline(key + "_variance", variance);
            data.updateBaseline(key + "_stddev", stdDev);
        }
    }

    private String extractNoSlowVariant(String details) {
        if (details == null) return "";

        if (details.contains("[NoWeb]")) {
            return "NoWeb";
        } else if (details.contains("[NoHoney]")) {
            return "NoHoney";
        } else if (details.contains("[NoSoulSand]")) {
            return "NoSoulSand";
        } else if (details.contains("[NoPowderSnow]")) {
            return "NoPowderSnow";
        } else if (details.contains("[NoBerry]")) {
            return "NoBerry";
        } else if (details.contains("[NoItemSlow]")) {
            return "NoItemSlow";
        } else if (details.contains("[NoSneak]")) {
            return "NoSneak";
        } else if (details.contains("[NoSlow]")) {
            return "NoSlow";
        }

        return "";
    }

    public enum ConfidenceLevel {
        NONE,
        LOW,
        MEDIUM,
        HIGH,
        EXTREME
    }
}
