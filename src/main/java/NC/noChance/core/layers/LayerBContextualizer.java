package NC.noChance.core.layers;

import NC.noChance.core.CheckResult;
import NC.noChance.core.LayerFiltering;
import NC.noChance.core.PlayerData;
import NC.noChance.core.ViolationType;
import org.bukkit.entity.Player;

import java.util.List;

public class LayerBContextualizer {
    private final LayerFiltering filtering;

    public LayerBContextualizer(LayerFiltering filtering) {
        this.filtering = filtering;
    }

    public boolean contextualize(Player player, CheckResult result, ViolationType type, LayerState state, PlayerData data) {
        if (!filtering.passesLayer2HeuristicFiltering(player, type, result)) {
            return false;
        }

        boolean highSeverityBypass = result.getSeverity() >= 0.80;

        int requiredViolations = result.getSeverity() >= 0.55 ? 1 : 2;
        List<Double> recentSeverities = state.getRecentSeverities(type, 8000);

        if (recentSeverities.size() < requiredViolations && !highSeverityBypass) {
            return false;
        }

        double avgSeverity = recentSeverities.isEmpty() ? result.getSeverity() : LayerMath.weightedAverage(recentSeverities);
        double avgThreshold = 0.42;
        if (data != null && data.isStrictDetectionMode()) {
            avgThreshold *= 0.72;
        }
        if (data != null && data.isHighPing()) {
            avgThreshold += 0.03;
        }
        if (avgSeverity < avgThreshold && result.getSeverity() < 0.55) {
            return false;
        }

        if (!validateCrossMetrics(type, data, result.getSeverity())) {
            return false;
        }

        state.recordLayerB(type, avgSeverity);
        return true;
    }

    private boolean validateCrossMetrics(ViolationType type, PlayerData data, double severity) {
        switch (type) {
            case KILLAURA:
            case KILLAURA_ANGLE:
            case KILLAURA_MULTI:
            case KILLAURA_ROTATION:
            case KILLAURA_PATTERN:
                return true;
            case SPEED:
                return data.getLocationHistory().size() > 3 || severity >= 0.85;
            case FLY:
            case FLY_HOVER:
            case FLY_VERTICAL:
            case FLY_GLIDE:
                if (severity >= 0.80) return true;
                if (data.getAirTicks() < 1) return false;
                return true;
            default:
                return true;
        }
    }
}
