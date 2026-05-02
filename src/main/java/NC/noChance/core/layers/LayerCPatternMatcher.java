package NC.noChance.core.layers;

import NC.noChance.core.CheckResult;
import NC.noChance.core.LayerFiltering;
import NC.noChance.core.PlayerData;
import NC.noChance.core.ViolationType;
import org.bukkit.entity.Player;

import java.util.List;

public class LayerCPatternMatcher {
    private final LayerFiltering filtering;

    public LayerCPatternMatcher(LayerFiltering filtering) {
        this.filtering = filtering;
    }

    public boolean match(Player player, CheckResult result, ViolationType type, LayerState state, PlayerData data) {
        if (result.getSeverity() >= 0.55) {
            state.recordLayerC(type);
            return true;
        }
        int requiredPatternSamples = 3;
        List<Double> patternSamples = state.getRecentSeverities(type, 15000);

        if (patternSamples.size() < requiredPatternSamples) {
            return false;
        }

        double variance = LayerMath.robustVariance(patternSamples);
        double trend = LayerMath.trend(patternSamples);
        double autocorr = filtering.calculateAutocorrelation(patternSamples, 1);

        boolean isSuspiciousPattern = false;
        boolean strictMode = data != null && data.isStrictDetectionMode();

        final double varianceThreshold = strictMode ? 0.02 * 1.2 : 0.02;
        final double allMatchThreshold = strictMode ? 0.45 * 0.80 : 0.45;
        final double consecutiveThreshold = strictMode ? 0.50 * 0.80 : 0.50;
        final double highSeverityThreshold = strictMode ? 0.45 * 0.80 : 0.45;

        if (variance < varianceThreshold) {
            isSuspiciousPattern = true;
        }

        if (trend > LayerMath.TREND_THRESHOLD) {
            isSuspiciousPattern = true;
        }

        if (autocorr > 0.72) {
            isSuspiciousPattern = true;
        }

        if (patternSamples.stream().allMatch(s -> s > allMatchThreshold)) {
            isSuspiciousPattern = true;
        }

        int consecutiveHighSeverity = 0;
        for (int i = Math.max(0, patternSamples.size() - 3); i < patternSamples.size(); i++) {
            if (patternSamples.get(i) > consecutiveThreshold) {
                consecutiveHighSeverity++;
            }
        }
        if (consecutiveHighSeverity >= 2) {
            isSuspiciousPattern = true;
        }

        long highSeverityCount = patternSamples.stream().filter(s -> s > highSeverityThreshold).count();
        if (highSeverityCount >= 2) {
            isSuspiciousPattern = true;
        }

        if (!isSuspiciousPattern) {
            return false;
        }

        if (!filtering.isRealisticHumanBehavior(data)) {
            isSuspiciousPattern = true;
        }

        state.recordLayerC(type);
        return true;
    }
}
