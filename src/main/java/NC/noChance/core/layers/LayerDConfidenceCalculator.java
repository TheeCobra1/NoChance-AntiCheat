package NC.noChance.core.layers;

import NC.noChance.core.CheckResult;
import NC.noChance.core.LayerFiltering;
import NC.noChance.core.MultiLayerValidator;
import NC.noChance.core.PlayerData;
import NC.noChance.core.SessionProfile;
import NC.noChance.core.ViolationType;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.UUID;

public class LayerDConfidenceCalculator {
    private final LayerFiltering filtering;
    private final SessionProfile sessionProfile;

    public LayerDConfidenceCalculator(LayerFiltering filtering, SessionProfile sessionProfile) {
        this.filtering = filtering;
        this.sessionProfile = sessionProfile;
    }

    public MultiLayerValidator.ValidationResult compute(Player player, CheckResult result, ViolationType type,
                                                       LayerState state, PlayerData data) {
        List<Double> allSeverities = state.getRecentSeverities(type, 20000);

        if (allSeverities.isEmpty()) {
            return MultiLayerValidator.ValidationResult.inconclusive();
        }

        double baseScore = LayerMath.weightedAverage(allSeverities);
        double typeWeight = type.getWeight();
        double frequency = Math.max(0.55, Math.min(1.0, allSeverities.size() / 4.0));
        double rawScore = baseScore * typeWeight * frequency;

        double lagPenalty = LayerMath.sigmoidLagPenalty(filtering.getPing(player));
        double behaviorMultiplier = filtering.isRealisticHumanBehavior(data) ? 0.88 : 1.20;
        double consistencyMultiplier = LayerMath.consistencyMultiplier(allSeverities);
        double momentumMultiplier = LayerMath.momentumMultiplier(allSeverities);

        if (data != null && data.isStrictDetectionMode()) {
            behaviorMultiplier *= 1.15;
        }

        if (data != null && data.isHighPing()) {
            lagPenalty = Math.min(lagPenalty + 0.05, 0.25);
        }

        double behavioralMultiplier = behavioralFingerprint(player, data);

        double finalScore = rawScore * (1.0 - lagPenalty) * behaviorMultiplier *
                consistencyMultiplier * momentumMultiplier * behavioralMultiplier;

        MultiLayerValidator.ConfidenceLevel confidence = determineConfidenceLevel(finalScore);
        return new MultiLayerValidator.ValidationResult(true, confidence, finalScore, result);
    }

    public MultiLayerValidator.ConfidenceLevel determineConfidenceLevel(double score) {
        if (score >= 0.85) return MultiLayerValidator.ConfidenceLevel.EXTREME;
        if (score >= 0.68) return MultiLayerValidator.ConfidenceLevel.HIGH;
        if (score >= 0.50) return MultiLayerValidator.ConfidenceLevel.MEDIUM;
        if (score >= 0.30) return MultiLayerValidator.ConfidenceLevel.LOW;
        return MultiLayerValidator.ConfidenceLevel.NONE;
    }

    private double behavioralFingerprint(Player player, PlayerData data) {
        UUID playerId = player.getUniqueId();

        sessionProfile.recordCheck(playerId);
        sessionProfile.recordCPS(playerId, data.getAverageCPS());
        sessionProfile.recordRotationSpeed(playerId, data.getAverageRotationSpeed());

        double multiplier = 1.0;

        if (sessionProfile.detectToggle(playerId)) {
            multiplier *= 1.25;
        }

        if (sessionProfile.detectSkillJump(playerId)) {
            multiplier *= 1.15;
        }

        double trustScore = sessionProfile.getTrustScore(playerId);
        if (trustScore < 0.4) {
            multiplier *= 1.20;
        } else if (trustScore > 0.8) {
            multiplier *= 0.90;
        }

        double sessionCPS = sessionProfile.getSessionCPSAverage(playerId);
        double sessionRot = sessionProfile.getSessionRotationAverage(playerId);
        double sessionVlRatio = sessionProfile.getViolationRatio(playerId);

        if (sessionCPS > 18 && sessionRot > 500 && sessionVlRatio > 0.2) {
            multiplier *= 1.12;
        }

        SessionProfile.BehaviorSnapshot baseline = new SessionProfile.BehaviorSnapshot(
                data.getAverageCPS(), data.getAverageRotationSpeed(), data.getViolationRatio(),
                System.currentTimeMillis() - data.getLastMoveTime()
        );
        double deviation = sessionProfile.compareToBaseline(playerId, baseline);
        if (deviation > 0.5) {
            multiplier *= 1.10;
        }

        return multiplier;
    }
}
