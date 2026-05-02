package NC.noChance.core.layers;

import NC.noChance.core.CheckResult;
import NC.noChance.core.PlayerData;
import NC.noChance.core.ViolationType;

import java.util.List;

public class LayerARecorder {
    private static final int MIN_LAYER_A_SAMPLES = 2;

    public boolean record(CheckResult result, ViolationType type, LayerState state, PlayerData data) {
        double severityThreshold = 0.42;
        int complexityFactor = type.getComplexity();

        if (complexityFactor >= 4) {
            severityThreshold = 0.52;
        } else if (complexityFactor >= 3) {
            severityThreshold = 0.48;
        }

        if (data != null && data.isStrictDetectionMode()) {
            severityThreshold *= 0.72;
        }

        if (data != null && data.isHighPing()) {
            severityThreshold += 0.025;
        }

        if (result.getSeverity() < severityThreshold) {
            return false;
        }

        state.recordLayerA(type, result.getSeverity());

        List<Double> recentSeverities = state.getRecentSeverities(type, 10000);
        if (recentSeverities.size() < MIN_LAYER_A_SAMPLES && result.getSeverity() < 0.60) {
            return false;
        }

        return true;
    }
}
