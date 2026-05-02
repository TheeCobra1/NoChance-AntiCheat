package NC.noChance.core;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class CrossCheckCorrelation {
    private final Map<UUID, CorrelationTracker> playerTrackers;

    private static final long CORRELATION_WINDOW_MS = 5000;
    private static final long EXTENDED_WINDOW_MS = 10000;

    private static final double COMBAT_CLUSTER_WEIGHT = 1.5;
    private static final double MOVEMENT_CLUSTER_WEIGHT = 1.4;
    private static final double BLOCK_CLUSTER_WEIGHT = 1.3;
    private static final double EVASION_CLUSTER_WEIGHT = 1.6;

    public CrossCheckCorrelation() {
        this.playerTrackers = new ConcurrentHashMap<>();
    }

    public void recordViolation(UUID playerId, ViolationType type, double severity) {
        CorrelationTracker tracker = playerTrackers.computeIfAbsent(playerId, k -> new CorrelationTracker());
        tracker.recordViolation(type, severity);
    }

    public CorrelationResult analyzeCorrelation(UUID playerId, ViolationType currentType) {
        CorrelationTracker tracker = playerTrackers.get(playerId);
        if (tracker == null) {
            return new CorrelationResult(0.0, CorrelationType.NONE, new ArrayList<>(), 1.0);
        }

        long now = System.currentTimeMillis();
        List<ViolationEntry> recentViolations = tracker.getRecentViolations(CORRELATION_WINDOW_MS);

        if (recentViolations.isEmpty()) {
            return new CorrelationResult(0.0, CorrelationType.NONE, new ArrayList<>(), 1.0);
        }

        CorrelationType clusterType = determineClusterType(currentType, recentViolations);
        List<ViolationType> correlatedTypes = extractCorrelatedTypes(recentViolations);

        double baseScore = calculateCorrelationScore(currentType, recentViolations, clusterType);
        double timeDecayFactor = calculateTimeDecay(recentViolations, now);
        double severityBonus = calculateSeverityBonus(recentViolations);

        double finalScore = (baseScore * timeDecayFactor) + severityBonus;
        double punishmentMultiplier = calculatePunishmentMultiplier(clusterType, correlatedTypes.size(), finalScore);

        return new CorrelationResult(finalScore, clusterType, correlatedTypes, punishmentMultiplier);
    }

    private CorrelationType determineClusterType(ViolationType current, List<ViolationEntry> recent) {
        Set<ViolationType> allTypes = new HashSet<>();
        allTypes.add(current);
        for (ViolationEntry entry : recent) {
            allTypes.add(entry.type);
        }

        int combatCount = 0;
        int movementCount = 0;
        int blockCount = 0;
        int evasionCount = 0;

        for (ViolationType type : allTypes) {
            if (isCombatType(type)) combatCount++;
            if (isMovementType(type)) movementCount++;
            if (isBlockType(type)) blockCount++;
            if (isEvasionType(type)) evasionCount++;
        }

        if (combatCount >= 2) return CorrelationType.COMBAT_CLUSTER;
        if (movementCount >= 2) return CorrelationType.MOVEMENT_CLUSTER;
        if (blockCount >= 2) return CorrelationType.BLOCK_CLUSTER;
        if (evasionCount >= 2) return CorrelationType.EVASION_CLUSTER;

        if (combatCount >= 1 && movementCount >= 1) return CorrelationType.HYBRID_COMBAT_MOVEMENT;
        if (combatCount >= 1 && evasionCount >= 1) return CorrelationType.HYBRID_COMBAT_EVASION;

        return CorrelationType.MIXED;
    }

    private boolean isCombatType(ViolationType type) {
        return type == ViolationType.KILLAURA ||
               type == ViolationType.KILLAURA_MULTI ||
               type == ViolationType.KILLAURA_ANGLE ||
               type == ViolationType.KILLAURA_ROTATION ||
               type == ViolationType.KILLAURA_PATTERN ||
               type == ViolationType.REACH ||
               type == ViolationType.AUTOCLICKER ||
               type == ViolationType.CRITICALS ||
               type == ViolationType.AIMASSIST ||
               type == ViolationType.AIMASSIST_SILENT;
    }

    private boolean isMovementType(ViolationType type) {
        return type == ViolationType.FLY ||
               type == ViolationType.SPEED ||
               type == ViolationType.NOCLIP ||
               type == ViolationType.JESUS ||
               type == ViolationType.STEP ||
               type == ViolationType.TIMER ||
               type == ViolationType.GROUNDSPOOF ||
               type == ViolationType.ELYTRAFLY ||
               type == ViolationType.STRIDER ||
               type == ViolationType.BOATFLY ||
               type == ViolationType.NOSLOW ||
               type == ViolationType.NOSLOW_ITEM ||
               type == ViolationType.NOSLOW_WEB ||
               type == ViolationType.NOSLOW_HONEY ||
               type == ViolationType.SPEED_GROUND ||
               type == ViolationType.SPEED_AIR ||
               type == ViolationType.SPEED_STRAFE ||
               type == ViolationType.FLY_HOVER ||
               type == ViolationType.FLY_VERTICAL ||
               type == ViolationType.FLY_GLIDE ||
               type == ViolationType.STRAFE ||
               type == ViolationType.SPIDER;
    }

    private boolean isBlockType(ViolationType type) {
        return type == ViolationType.SCAFFOLD ||
               type == ViolationType.FASTPLACE ||
               type == ViolationType.FASTBREAK ||
               type == ViolationType.NUKER ||
               type == ViolationType.SCAFFOLD_BRIDGE ||
               type == ViolationType.SCAFFOLD_TOWER ||
               type == ViolationType.GHOSTHAND;
    }

    private boolean isEvasionType(ViolationType type) {
        return type == ViolationType.BLINK ||
               type == ViolationType.NOFALL ||
               type == ViolationType.PHASE ||
               type == ViolationType.VELOCITY ||


               type == ViolationType.PROTOCOL;
    }

    private List<ViolationType> extractCorrelatedTypes(List<ViolationEntry> violations) {
        List<ViolationType> types = new ArrayList<>();
        for (ViolationEntry entry : violations) {
            types.add(entry.type);
        }
        return types;
    }

    private double calculateCorrelationScore(ViolationType current, List<ViolationEntry> recent, CorrelationType cluster) {
        if (recent.isEmpty()) return 0.0;

        int matchingClusterCount = 0;
        for (ViolationEntry entry : recent) {
            if (isInCluster(entry.type, cluster)) {
                matchingClusterCount++;
            }
        }

        double clusterRatio = (double) matchingClusterCount / Math.max(1, recent.size());
        double baseScore = clusterRatio * 0.60;

        if (recent.size() >= 5) {
            baseScore += 0.25;
        } else if (recent.size() >= 3) {
            baseScore += 0.15;
        }

        Set<ViolationType> uniqueTypes = new HashSet<>();
        for (ViolationEntry entry : recent) {
            uniqueTypes.add(entry.type);
        }

        if (uniqueTypes.size() >= 4) {
            baseScore += 0.20;
        } else if (uniqueTypes.size() >= 3) {
            baseScore += 0.12;
        } else if (uniqueTypes.size() >= 2) {
            baseScore += 0.06;
        }

        return Math.min(1.0, baseScore);
    }

    private boolean isInCluster(ViolationType type, CorrelationType cluster) {
        switch (cluster) {
            case COMBAT_CLUSTER:
                return isCombatType(type);
            case MOVEMENT_CLUSTER:
                return isMovementType(type);
            case BLOCK_CLUSTER:
                return isBlockType(type);
            case EVASION_CLUSTER:
                return isEvasionType(type);
            case HYBRID_COMBAT_MOVEMENT:
                return isCombatType(type) || isMovementType(type);
            case HYBRID_COMBAT_EVASION:
                return isCombatType(type) || isEvasionType(type);
            default:
                return false;
        }
    }

    private double calculateTimeDecay(List<ViolationEntry> violations, long now) {
        if (violations.isEmpty()) return 0.0;

        double totalDecay = 0.0;
        for (ViolationEntry entry : violations) {
            long age = now - entry.timestamp;
            double decay = 1.0 - (age / (double) CORRELATION_WINDOW_MS);
            totalDecay += Math.max(0.0, decay);
        }

        return Math.min(1.0, totalDecay / violations.size());
    }

    private double calculateSeverityBonus(List<ViolationEntry> violations) {
        if (violations.isEmpty()) return 0.0;

        double avgSeverity = violations.stream()
            .mapToDouble(v -> v.severity)
            .average()
            .orElse(0.0);

        if (avgSeverity > 0.85) {
            return 0.15;
        } else if (avgSeverity > 0.70) {
            return 0.10;
        } else if (avgSeverity > 0.55) {
            return 0.05;
        }

        return 0.0;
    }

    private double calculatePunishmentMultiplier(CorrelationType cluster, int correlatedCount, double score) {
        double baseMultiplier = 1.0;

        switch (cluster) {
            case COMBAT_CLUSTER:
                baseMultiplier = COMBAT_CLUSTER_WEIGHT;
                break;
            case MOVEMENT_CLUSTER:
                baseMultiplier = MOVEMENT_CLUSTER_WEIGHT;
                break;
            case BLOCK_CLUSTER:
                baseMultiplier = BLOCK_CLUSTER_WEIGHT;
                break;
            case EVASION_CLUSTER:
                baseMultiplier = EVASION_CLUSTER_WEIGHT;
                break;
            case HYBRID_COMBAT_MOVEMENT:
                baseMultiplier = 1.7;
                break;
            case HYBRID_COMBAT_EVASION:
                baseMultiplier = 1.8;
                break;
            case MIXED:
                baseMultiplier = 1.2;
                break;
        }

        if (correlatedCount >= 5) {
            baseMultiplier += 0.4;
        } else if (correlatedCount >= 3) {
            baseMultiplier += 0.2;
        }

        if (score > 0.80) {
            baseMultiplier += 0.3;
        } else if (score > 0.65) {
            baseMultiplier += 0.15;
        }

        return Math.min(2.5, baseMultiplier);
    }

    public void cleanup(UUID playerId) {
        playerTrackers.remove(playerId);
    }

    public void cleanupOldEntries() {
        long cutoff = System.currentTimeMillis() - EXTENDED_WINDOW_MS;
        for (CorrelationTracker tracker : playerTrackers.values()) {
            tracker.removeOldEntries(cutoff);
        }
    }

    private static class CorrelationTracker {
        private final List<ViolationEntry> violations;

        private CorrelationTracker() {
            this.violations = new ArrayList<>();
        }

        private synchronized void recordViolation(ViolationType type, double severity) {
            if (violations.size() > 200) {
                violations.subList(0, violations.size() - 150).clear();
            }
            violations.add(new ViolationEntry(type, severity, System.currentTimeMillis()));
        }

        private synchronized List<ViolationEntry> getRecentViolations(long windowMs) {
            long cutoff = System.currentTimeMillis() - windowMs;
            List<ViolationEntry> recent = new ArrayList<>();
            for (ViolationEntry entry : violations) {
                if (entry.timestamp > cutoff) {
                    recent.add(entry);
                }
            }
            return recent;
        }

        private synchronized void removeOldEntries(long cutoff) {
            violations.removeIf(entry -> entry.timestamp < cutoff);
        }
    }

    private static class ViolationEntry {
        private final ViolationType type;
        private final double severity;
        private final long timestamp;

        private ViolationEntry(ViolationType type, double severity, long timestamp) {
            this.type = type;
            this.severity = severity;
            this.timestamp = timestamp;
        }
    }

    public enum CorrelationType {
        COMBAT_CLUSTER,
        MOVEMENT_CLUSTER,
        BLOCK_CLUSTER,
        EVASION_CLUSTER,
        HYBRID_COMBAT_MOVEMENT,
        HYBRID_COMBAT_EVASION,
        MIXED,
        NONE
    }

    public static class CorrelationResult {
        public final double score;
        public final CorrelationType clusterType;
        public final List<ViolationType> correlatedTypes;
        public final double punishmentMultiplier;

        private CorrelationResult(double score, CorrelationType clusterType,
                                 List<ViolationType> correlatedTypes, double punishmentMultiplier) {
            this.score = score;
            this.clusterType = clusterType;
            this.correlatedTypes = correlatedTypes;
            this.punishmentMultiplier = punishmentMultiplier;
        }
    }
}
