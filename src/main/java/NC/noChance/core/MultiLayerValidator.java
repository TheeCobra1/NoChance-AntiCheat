package NC.noChance.core;

import NC.noChance.core.layers.LayerARecorder;
import NC.noChance.core.layers.LayerBContextualizer;
import NC.noChance.core.layers.LayerCPatternMatcher;
import NC.noChance.core.layers.LayerDConfidenceCalculator;
import NC.noChance.core.layers.LayerState;
import NC.noChance.packets.PacketAnalyzer;
import NC.noChance.predict.PredictionEngine;
import NC.noChance.sim.SimEngine;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class MultiLayerValidator {
    private final ACConfig config;
    private final Map<UUID, PlayerData> playerDataMap;
    private final LayerFiltering filtering;
    private final Map<UUID, LayerState> layerStates;
    private final DetectionEngine advancedEngine;
    private final SessionProfile sessionProfile;
    private final LayerARecorder layerA;
    private final LayerBContextualizer layerB;
    private final LayerCPatternMatcher layerC;
    private final LayerDConfidenceCalculator layerD;
    private PacketAnalyzer packetAnalyzer;
    private PredictionEngine predictionEngine;
    private SimEngine simEngine;

    public MultiLayerValidator(ACConfig config, Map<UUID, PlayerData> playerDataMap, LayerFiltering filtering,
                               DetectionEngine detectionEngine) {
        this.config = config;
        this.playerDataMap = playerDataMap;
        this.filtering = filtering;
        this.layerStates = new ConcurrentHashMap<>();
        this.advancedEngine = detectionEngine;
        this.sessionProfile = new SessionProfile();
        this.packetAnalyzer = null;
        this.layerA = new LayerARecorder();
        this.layerB = new LayerBContextualizer(filtering);
        this.layerC = new LayerCPatternMatcher(filtering);
        this.layerD = new LayerDConfidenceCalculator(filtering, sessionProfile);
    }

    public void setPacketAnalyzer(PacketAnalyzer packetAnalyzer) {
        this.packetAnalyzer = packetAnalyzer;
    }

    public void setPredictionEngine(PredictionEngine predictionEngine) {
        this.predictionEngine = predictionEngine;
    }

    public void setSimEngine(SimEngine simEngine) {
        this.simEngine = simEngine;
    }

    public ValidationResult validate(Player player, CheckResult checkResult, ViolationType type) {
        if (!checkResult.isFailed()) {
            return ValidationResult.passed();
        }

        UUID playerId = player.getUniqueId();
        LayerState state = layerStates.computeIfAbsent(playerId, k -> new LayerState());
        PlayerData data = playerDataMap.get(playerId);

        if (data == null) {
            return ValidationResult.inconclusive();
        }

        if (!layerA.record(checkResult, type, state, data)) {
            return ValidationResult.passed();
        }

        if (!layerB.contextualize(player, checkResult, type, state, data)) {
            return ValidationResult.passed();
        }

        if (!layerC.match(player, checkResult, type, state, data)) {
            return ValidationResult.passed();
        }

        ValidationResult baseResult = layerD.compute(player, checkResult, type, state, data);

        DetectionEngine.AnalysisResult advancedResult =
                advancedEngine.analyze(player, data, checkResult, type);

        PacketAnalyzer.PacketViolationResult packetResult = null;
        if (packetAnalyzer != null) {
            packetResult = packetAnalyzer.analyzeForViolations(player, type);
        }

        PredictionEngine.PredScore predScore = null;
        if (predictionEngine != null) {
            predScore = predictionEngine.getLatestScore(playerId);
        }

        ValidationResult combined = combineResults(baseResult, advancedResult, packetResult, predScore, checkResult);

        if (combined.shouldFlag()) {
            sessionProfile.recordViolation(playerId, type);
        }

        return combined;
    }

    private ValidationResult combineResults(ValidationResult baseResult,
                                            DetectionEngine.AnalysisResult advancedResult,
                                            PacketAnalyzer.PacketViolationResult packetResult,
                                            PredictionEngine.PredScore predScore,
                                            CheckResult originalCheck) {

        boolean baseFlag = baseResult.shouldFlag();
        boolean advancedFlag = advancedResult.shouldFlag;
        boolean packetFlag = packetResult != null && packetResult.violated;
        boolean predFlag = predScore != null && predScore.shouldFlag;

        if (!baseFlag && !advancedFlag && !packetFlag && !predFlag) {
            return ValidationResult.passed();
        }

        int systemsTriggered = 0;
        if (baseFlag) systemsTriggered++;
        if (advancedFlag) systemsTriggered++;
        if (packetFlag) systemsTriggered++;
        if (predFlag) systemsTriggered++;

        double baseConfidence = baseResult.getScore();
        double advancedConfidence = advancedResult.confidence;
        double packetConfidence = packetResult != null ? packetResult.severity : 0.0;
        double predConfidence = predScore != null ? predScore.combined : 0.0;

        double combinedConfidence;
        if (packetResult != null && packetResult.violated) {
            if (systemsTriggered >= 3) {
                combinedConfidence = (baseConfidence * 0.32) + (advancedConfidence * 0.28) +
                        (packetConfidence * 0.25) + (predConfidence * 0.15);
            } else if (systemsTriggered >= 2) {
                combinedConfidence = (baseConfidence * 0.36) + (advancedConfidence * 0.30) + (packetConfidence * 0.27) +
                        (predConfidence * 0.07);
            } else {
                combinedConfidence = packetConfidence * 0.70;
            }
        } else if (predFlag && systemsTriggered >= 2) {
            combinedConfidence = (baseConfidence * 0.44) + (advancedConfidence * 0.38) + (predConfidence * 0.18);
        } else {
            combinedConfidence = Math.max(baseConfidence, advancedConfidence) * 0.80 +
                    Math.min(baseConfidence, advancedConfidence) * 0.20;
        }

        if (predScore != null && predScore.crossCorrelation > 0.15) {
            combinedConfidence = Math.min(1.0, combinedConfidence + predScore.crossCorrelation * 0.35);
        }

        double originalSeverity = originalCheck != null ? originalCheck.getSeverity() : 0.0;
        if (originalSeverity >= 0.80) {
            combinedConfidence = Math.max(combinedConfidence, originalSeverity * 0.92);
        } else {
            combinedConfidence = Math.max(combinedConfidence, originalSeverity * 0.65);
        }
        double effectiveThreshold = originalSeverity >= 0.80 ? 0.22 : 0.30;
        if (combinedConfidence < effectiveThreshold) {
            boolean rescue = originalSeverity >= 0.80
                    || (baseFlag && baseConfidence >= 0.70)
                    || (packetFlag && packetConfidence >= 0.85)
                    || (advancedFlag && advancedConfidence >= 0.80);
            if (rescue) {
                combinedConfidence = Math.max(combinedConfidence, originalSeverity * 0.85);
            } else {
                return ValidationResult.inconclusive();
            }
        }

        if (systemsTriggered >= 3) {
            combinedConfidence = Math.min(1.0, combinedConfidence * 1.08);
        }

        if (packetFlag && packetConfidence > 0.85) {
            combinedConfidence = Math.min(1.0, combinedConfidence + 0.08);
        }

        if (predFlag && predConfidence > 0.8) {
            combinedConfidence = Math.min(1.0, combinedConfidence + 0.04);
        }

        ConfidenceLevel finalConfidence = layerD.determineConfidenceLevel(combinedConfidence);

        String detectionMethod = advancedResult.detectionMethod;
        if (packetFlag && packetResult.severity > advancedConfidence) {
            detectionMethod = "Packet-" + packetResult.reason;
        }
        if (predFlag && predConfidence > packetConfidence && predConfidence > advancedConfidence) {
            String predReason = predScore.moveFlag ? predScore.moveReason : predScore.combatReason;
            detectionMethod = "Prediction-" + predReason;
        }

        return new ValidationResult(
                true,
                finalConfidence,
                combinedConfidence,
                originalCheck,
                detectionMethod,
                advancedResult.entropyScore,
                advancedResult.consistencyScore,
                advancedResult.correlationScore,
                advancedResult.variant,
                advancedResult.punishmentMultiplier
        );
    }

    public void cleanupPlayer(UUID playerId) {
        layerStates.remove(playerId);
        advancedEngine.cleanup(playerId);
        sessionProfile.cleanup(playerId);
        if (packetAnalyzer != null) {
            packetAnalyzer.cleanup(playerId);
        }
        if (predictionEngine != null) {
            predictionEngine.resetPlayer(playerId);
        }
        if (simEngine != null) {
            simEngine.resetPlayer(playerId);
        }
    }

    public void cleanupOldData() {
        layerStates.values().forEach(state -> state.cleanupOld(System.currentTimeMillis(), 60000));
        sessionProfile.cleanupStale(600000);
    }

    public enum ConfidenceLevel {
        NONE,
        LOW,
        MEDIUM,
        HIGH,
        EXTREME
    }

    public static class ValidationResult {
        private final boolean shouldFlag;
        private final ConfidenceLevel confidence;
        private final double score;
        private final CheckResult originalResult;
        private final String detectionMethod;
        private final double entropyScore;
        private final double consistencyScore;
        private final double correlationScore;
        private final String variant;
        private final double punishmentMultiplier;

        public ValidationResult(boolean shouldFlag, ConfidenceLevel confidence, double score,
                                CheckResult originalResult, String detectionMethod,
                                double entropyScore, double consistencyScore, double correlationScore,
                                String variant, double punishmentMultiplier) {
            this.shouldFlag = shouldFlag;
            this.confidence = confidence;
            this.score = score;
            this.originalResult = originalResult;
            this.detectionMethod = detectionMethod;
            this.entropyScore = entropyScore;
            this.consistencyScore = consistencyScore;
            this.correlationScore = correlationScore;
            this.variant = variant;
            this.punishmentMultiplier = punishmentMultiplier;
        }

        public ValidationResult(boolean shouldFlag, ConfidenceLevel confidence, double score, CheckResult originalResult) {
            this(shouldFlag, confidence, score, originalResult, "Standard", 0.0, 0.0, 0.0, "", 1.0);
        }

        public static ValidationResult passed() {
            return new ValidationResult(false, ConfidenceLevel.NONE, 0.0, null);
        }

        public static ValidationResult inconclusive() {
            return new ValidationResult(false, ConfidenceLevel.NONE, 0.0, null);
        }

        public boolean shouldFlag() {
            return shouldFlag;
        }

        public ConfidenceLevel getConfidence() {
            return confidence;
        }

        public double getScore() {
            return score;
        }

        public CheckResult getOriginalResult() {
            return originalResult;
        }

        public String getDetectionMethod() {
            return detectionMethod;
        }

        public String getVariant() {
            return variant;
        }

        public double getPunishmentMultiplier() {
            return punishmentMultiplier;
        }
    }
}
