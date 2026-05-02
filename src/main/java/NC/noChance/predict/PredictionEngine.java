package NC.noChance.predict;

import NC.noChance.core.ViolationType;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

public class PredictionEngine {

    private final MovementPredictor movePred;
    private final CombatPredictor combatPred;
    private final Map<UUID, CrossCheckState> crossStates = new ConcurrentHashMap<>();
    private final Map<UUID, ScoredEntry> latestScores = new ConcurrentHashMap<>();

    private static final long CORRELATION_WINDOW = 8000;
    private static final double CROSS_BOOST_BASE = 0.10;
    private static final double CROSS_BOOST_CAP = 0.35;

    private static final int[][] CORRELATED_PAIRS = {
        {0, 1},
        {0, 7},
        {1, 7},
        {1, 3},
        {3, 4},
        {3, 2},
        {5, 3},
        {5, 6},
        {0, 2}
    };

    private static final ViolationType[] TRACKED_TYPES = {
        ViolationType.SPEED,
        ViolationType.FLY,
        ViolationType.REACH,
        ViolationType.KILLAURA,
        ViolationType.AUTOCLICKER,
        ViolationType.BLINK,
        ViolationType.AIMASSIST,
        ViolationType.TIMER
    };

    public PredictionEngine() {
        this.movePred = new MovementPredictor();
        this.combatPred = new CombatPredictor();
    }

    public MovementPredictor.PredResult processMovement(Player player, Location from, Location to) {
        MovementPredictor.PredResult result = movePred.process(player, from, to);
        updateLatestScore(player.getUniqueId(), result, null);
        return result;
    }

    public CombatPredictor.CombatPredResult processHit(Player player, Entity target) {
        CombatPredictor.CombatPredResult result = combatPred.processHit(player, target);
        updateLatestScore(player.getUniqueId(), null, result);
        return result;
    }

    public void trackTarget(Player player, Entity target) {
        combatPred.trackTarget(player, target);
    }

    public void recordViolation(UUID playerId, ViolationType type, double severity) {
        CrossCheckState state = crossStates.computeIfAbsent(playerId, k -> new CrossCheckState());
        int idx = typeIndex(type);
        if (idx >= 0) {
            Deque<ViolationMark> marks = state.recentViolations.computeIfAbsent(idx, k -> new ConcurrentLinkedDeque<>());
            marks.addLast(new ViolationMark(severity, System.currentTimeMillis()));
            while (marks.size() > 20) marks.pollFirst();
        }
        state.lastViolationTime = System.currentTimeMillis();
    }

    public double getCrossCorrelation(UUID playerId) {
        CrossCheckState state = crossStates.get(playerId);
        if (state == null) return 0;

        long now = System.currentTimeMillis();
        long cutoff = now - CORRELATION_WINDOW;

        state.recentViolations.forEach((idx, marks) -> {
            marks.removeIf(m -> m.time < cutoff);
        });
        state.recentViolations.entrySet().removeIf(e -> e.getValue().isEmpty());

        double maxBoost = 0;

        for (int[] pair : CORRELATED_PAIRS) {
            Deque<ViolationMark> marksA = state.recentViolations.get(pair[0]);
            Deque<ViolationMark> marksB = state.recentViolations.get(pair[1]);

            if (marksA != null && !marksA.isEmpty() && marksB != null && !marksB.isEmpty()) {
                double avgSevA = marksA.stream().mapToDouble(m -> m.severity).average().orElse(0);
                double avgSevB = marksB.stream().mapToDouble(m -> m.severity).average().orElse(0);
                double combinedSev = (avgSevA + avgSevB) / 2.0;
                double pairBoost = CROSS_BOOST_BASE + combinedSev * 0.15;

                long minTimeA = marksA.stream().mapToLong(m -> m.time).min().orElse(now);
                long maxTimeA = marksA.stream().mapToLong(m -> m.time).max().orElse(now);
                long minTimeB = marksB.stream().mapToLong(m -> m.time).min().orElse(now);
                long maxTimeB = marksB.stream().mapToLong(m -> m.time).max().orElse(now);

                boolean overlapping = minTimeA <= maxTimeB && minTimeB <= maxTimeA;
                if (overlapping) {
                    pairBoost *= 1.2;
                }

                maxBoost = Math.max(maxBoost, pairBoost);
            }
        }

        int activeTypes = 0;
        for (Deque<ViolationMark> marks : state.recentViolations.values()) {
            if (!marks.isEmpty()) activeTypes++;
        }
        if (activeTypes >= 3) {
            maxBoost = Math.max(maxBoost, 0.15 + activeTypes * 0.04);
        }

        return Math.min(CROSS_BOOST_CAP, maxBoost);
    }

    public PredScore getLatestScore(UUID playerId) {
        ScoredEntry entry = latestScores.get(playerId);
        if (entry == null) return PredScore.EMPTY;
        if (System.currentTimeMillis() - entry.time > 30000) {
            latestScores.remove(playerId);
            return PredScore.EMPTY;
        }
        return entry.score;
    }

    private void updateLatestScore(UUID playerId, MovementPredictor.PredResult moveResult,
                                    CombatPredictor.CombatPredResult combatResult) {
        ScoredEntry existing = latestScores.get(playerId);
        PredScore prev = existing != null ? existing.score : PredScore.EMPTY;
        boolean stale = existing != null && System.currentTimeMillis() - existing.time > 10000;
        double crossCorr = getCrossCorrelation(playerId);

        double moveSev = moveResult != null ? moveResult.severity : (stale ? 0.0 : prev.moveSeverity);
        boolean moveFlag = moveResult != null ? moveResult.shouldFlag : (!stale && prev.moveFlag);
        String moveReason = moveResult != null ? moveResult.reason : (stale ? "" : prev.moveReason);
        double moveEwma = moveResult != null ? moveResult.ewma : (stale ? 0.0 : prev.moveEwma);

        if (moveResult != null && moveResult.consec > 8 && moveResult.adjDev > 0.3) {
            moveSev = Math.min(1.0, moveSev + moveResult.rawDev * 0.05);
        }

        double combatSev = combatResult != null ? combatResult.severity : (stale ? 0.0 : prev.combatSeverity);
        boolean combatFlag = combatResult != null ? combatResult.shouldFlag : (!stale && prev.combatFlag);
        String combatReason = combatResult != null ? combatResult.reason : (stale ? "" : prev.combatReason);

        if (combatResult != null && combatResult.predAccuracy > 0.7) {
            combatSev = Math.min(1.0, combatSev + combatResult.snapScore * 0.08);
            if (combatResult.avgReaction < 100 && combatResult.snapScore > 0.6) {
                combatSev = Math.min(1.0, combatSev + 0.05);
            }
        }

        double combined = Math.max(moveSev, combatSev);
        if (moveSev > 0.3 && combatSev > 0.3) {
            combined = moveSev * 0.5 + combatSev * 0.5 + 0.08;
        }
        combined = Math.min(1.0, combined);

        boolean shouldFlag = moveFlag || combatFlag || combined > 0.75;

        PredScore score = new PredScore(
            combined, shouldFlag,
            moveSev, moveFlag, moveReason, moveEwma,
            combatSev, combatFlag, combatReason,
            crossCorr
        );
        latestScores.put(playerId, new ScoredEntry(score, System.currentTimeMillis()));
    }

    private int typeIndex(ViolationType type) {
        for (int i = 0; i < TRACKED_TYPES.length; i++) {
            if (TRACKED_TYPES[i] == type) return i;
        }
        if (type == ViolationType.KILLAURA_MULTI || type == ViolationType.KILLAURA_ANGLE ||
            type == ViolationType.KILLAURA_ROTATION || type == ViolationType.KILLAURA_PATTERN) {
            return 3;
        }
        if (type == ViolationType.AIMASSIST_SILENT) return 6;
        if (type == ViolationType.SPEED_GROUND || type == ViolationType.SPEED_AIR ||
            type == ViolationType.SPEED_STRAFE) return 0;
        if (type == ViolationType.FLY_HOVER || type == ViolationType.FLY_VERTICAL ||
            type == ViolationType.FLY_GLIDE) return 1;
        return -1;
    }

    public void resetPlayer(UUID id) {
        movePred.reset(id);
        combatPred.reset(id);
        crossStates.remove(id);
        latestScores.remove(id);
    }

    public void cleanup() {
        movePred.cleanup();
        combatPred.cleanup();
        long now = System.currentTimeMillis();
        crossStates.entrySet().removeIf(e -> now - e.getValue().lastViolationTime > 120000);
        latestScores.entrySet().removeIf(e -> now - e.getValue().time > 60000);
    }

    public MovementPredictor getMovementPredictor() {
        return movePred;
    }

    private static class CrossCheckState {
        final Map<Integer, Deque<ViolationMark>> recentViolations = new ConcurrentHashMap<>();
        volatile long lastViolationTime = System.currentTimeMillis();
    }

    private static class ViolationMark {
        final double severity;
        final long time;

        ViolationMark(double severity, long time) {
            this.severity = severity;
            this.time = time;
        }
    }

    private static class ScoredEntry {
        final PredScore score;
        final long time;

        ScoredEntry(PredScore score, long time) {
            this.score = score;
            this.time = time;
        }
    }

    public static class PredScore {
        public static final PredScore EMPTY = new PredScore(0, false, 0, false, "", 0, 0, false, "", 0);

        public final double combined;
        public final boolean shouldFlag;
        public final double moveSeverity;
        public final boolean moveFlag;
        public final String moveReason;
        public final double moveEwma;
        public final double combatSeverity;
        public final boolean combatFlag;
        public final String combatReason;
        public final double crossCorrelation;

        PredScore(double combined, boolean shouldFlag,
                  double moveSeverity, boolean moveFlag, String moveReason, double moveEwma,
                  double combatSeverity, boolean combatFlag, String combatReason,
                  double crossCorrelation) {
            this.combined = combined;
            this.shouldFlag = shouldFlag;
            this.moveSeverity = moveSeverity;
            this.moveFlag = moveFlag;
            this.moveReason = moveReason;
            this.moveEwma = moveEwma;
            this.combatSeverity = combatSeverity;
            this.combatFlag = combatFlag;
            this.combatReason = combatReason;
            this.crossCorrelation = crossCorrelation;
        }
    }
}
