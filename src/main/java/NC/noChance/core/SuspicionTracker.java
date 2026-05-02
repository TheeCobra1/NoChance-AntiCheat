package NC.noChance.core;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class SuspicionTracker {

    private final Map<UUID, DetectionEngine.SuspicionLevel> levels = new ConcurrentHashMap<>();

    public DetectionEngine.SuspicionLevel getLevel(UUID id) {
        return levels.getOrDefault(id, DetectionEngine.SuspicionLevel.CLEAN);
    }

    public void ensure(UUID id) {
        levels.computeIfAbsent(id, k -> DetectionEngine.SuspicionLevel.CLEAN);
    }

    public DetectionEngine.SuspicionLevel update(UUID id, boolean shouldFlag, double finalConfidence,
                                                 boolean checkFailed, double highConfidence,
                                                 ViolationType type, ThresholdAdapter thresholds,
                                                 VariantCheck variants, Runnable onConfirmedHook) {
        DetectionEngine.SuspicionLevel current = getLevel(id);

        if (shouldFlag && finalConfidence >= highConfidence) {
            levels.put(id, DetectionEngine.SuspicionLevel.CONFIRMED);
            onConfirmedHook.run();
            thresholds.recordTruePositive(type);
            variants.recordDetection(id, type, true);
        } else if (finalConfidence >= 0.75) {
            levels.put(id, DetectionEngine.SuspicionLevel.HIGH);
            variants.recordDetection(id, type, true);
        } else if (finalConfidence >= 0.62) {
            levels.put(id, DetectionEngine.SuspicionLevel.MEDIUM);
        } else if (finalConfidence >= 0.48) {
            levels.put(id, DetectionEngine.SuspicionLevel.LOW);
        } else if (current == DetectionEngine.SuspicionLevel.CONFIRMED && finalConfidence < 0.40) {
            double cleanConfidence = 1.0 - finalConfidence;
            if (cleanConfidence > 0.55) {
                levels.put(id, DetectionEngine.SuspicionLevel.HIGH);
            }
        } else if (current == DetectionEngine.SuspicionLevel.HIGH && finalConfidence < 0.40) {
            double cleanConfidence = 1.0 - finalConfidence;
            if (cleanConfidence > 0.60) {
                levels.put(id, DetectionEngine.SuspicionLevel.LOW);
            } else if (cleanConfidence > 0.40) {
                levels.put(id, DetectionEngine.SuspicionLevel.MEDIUM);
            }
        } else if (current == DetectionEngine.SuspicionLevel.MEDIUM && finalConfidence < 0.40) {
            double cleanConfidence = 1.0 - finalConfidence;
            if (cleanConfidence > 0.60) {
                levels.put(id, DetectionEngine.SuspicionLevel.CLEAN);
            } else if (cleanConfidence > 0.35) {
                levels.put(id, DetectionEngine.SuspicionLevel.LOW);
            }
        } else if (current == DetectionEngine.SuspicionLevel.LOW && finalConfidence < 0.42) {
            levels.put(id, DetectionEngine.SuspicionLevel.CLEAN);
            if (checkFailed) {
                thresholds.recordFalsePositive(type);
                variants.recordFalsePositive(id, type);
            }
        }

        return getLevel(id);
    }

    public void cleanup(UUID id) {
        levels.remove(id);
    }

    public void retainOnly(java.util.Set<UUID> keep) {
        levels.keySet().retainAll(keep);
    }

    public int size() {
        return levels.size();
    }
}
