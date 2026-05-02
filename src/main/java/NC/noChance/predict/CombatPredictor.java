package NC.noChance.predict;

import org.bukkit.Location;
import org.bukkit.entity.*;
import org.bukkit.util.Vector;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

public class CombatPredictor {

    private static final int MAX_TARGET_HISTORY = 10;
    private static final int MAX_HIT_RECORDS = 50;
    private static final double EWMA_ALPHA = 0.12;
    private static final int MIN_HITS_TO_FLAG = 15;
    private static final double PRED_ANGLE_THRESHOLD = 12.0;

    private final Map<UUID, CombatState> states = new ConcurrentHashMap<>();

    public void trackTarget(Player player, Entity target) {
        if (!isTrackable(target)) return;

        CombatState state = states.computeIfAbsent(player.getUniqueId(), k -> new CombatState());
        state.lastActive = System.currentTimeMillis();
        int eid = target.getEntityId();
        Deque<EntPos> history = state.targetHistory.computeIfAbsent(eid, k -> new ConcurrentLinkedDeque<>());
        Location loc = target.getLocation();
        history.addLast(new EntPos(loc.getX(), loc.getY(), loc.getZ(), System.currentTimeMillis()));
        while (history.size() > MAX_TARGET_HISTORY) history.pollFirst();
    }

    public CombatPredResult processHit(Player player, Entity target) {
        if (!isTrackable(target)) return CombatPredResult.clean();
        if (target.getVehicle() != null) return CombatPredResult.clean();
        if (isFarmingTarget(target)) return CombatPredResult.clean();

        UUID pid = player.getUniqueId();
        CombatState state = states.computeIfAbsent(pid, k -> new CombatState());
        state.lastActive = System.currentTimeMillis();

        int eid = target.getEntityId();
        Deque<EntPos> history = state.targetHistory.get(eid);

        if (history == null || history.size() < 3) {
            state.hitCount++;
            return CombatPredResult.clean();
        }

        List<EntPos> posList = new ArrayList<>(history);
        int n = posList.size();
        EntPos last = posList.get(n - 1);
        EntPos prev = posList.get(n - 2);

        double tpDist = Math.sqrt(Math.pow(last.x - prev.x, 2) + Math.pow(last.y - prev.y, 2) + Math.pow(last.z - prev.z, 2));
        if (tpDist > 5.0) {
            state.hitCount++;
            return CombatPredResult.clean();
        }

        double tvx = last.x - prev.x;
        double tvy = last.y - prev.y;
        double tvz = last.z - prev.z;
        long dt = last.time - prev.time;
        if (dt <= 0) dt = 50;
        double factor = 50.0 / dt;

        double predX = last.x + tvx * factor;
        double predY = last.y + tvy * factor;
        double predZ = last.z + tvz * factor;

        Location eyeLoc = player.getEyeLocation();
        Vector lookDir = eyeLoc.getDirection().normalize();
        double targetH = getTargetHeight(target);
        double targetW = getTargetWidth(target);

        Vector toActual = new Vector(
            last.x - eyeLoc.getX(),
            (last.y + targetH * 0.5) - eyeLoc.getY(),
            last.z - eyeLoc.getZ());
        Vector toPredicted = new Vector(
            predX - eyeLoc.getX(),
            (predY + targetH * 0.5) - eyeLoc.getY(),
            predZ - eyeLoc.getZ());

        double actualAngle = angleBetween(lookDir, toActual);
        double predAngle = angleBetween(lookDir, toPredicted);

        double distance = toActual.length();
        double hitboxAngle = Math.toDegrees(Math.atan2(targetW * 0.5, Math.max(1.0, distance)));
        double adjustedThreshold = PRED_ANGLE_THRESHOLD + hitboxAngle;

        int ping = player.getPing();
        double pingAngleTol = Math.max(0, (ping - 30) * 0.015);
        adjustedThreshold += pingAngleTol;

        boolean aimedAtPredicted = predAngle < actualAngle && predAngle < adjustedThreshold;

        synchronized (state.hitRecords) {
            state.hitRecords.addLast(new HitRecord(actualAngle, predAngle, aimedAtPredicted, distance, System.currentTimeMillis()));
            while (state.hitRecords.size() > MAX_HIT_RECORDS) state.hitRecords.pollFirst();
        }

        state.predAccuracy = EWMA_ALPHA * (aimedAtPredicted ? 1.0 : 0.0) + (1.0 - EWMA_ALPHA) * state.predAccuracy;
        state.hitCount++;

        double reactionTime = calculateReactionTime(posList, eyeLoc, lookDir, ping);
        state.avgReaction = EWMA_ALPHA * reactionTime + (1.0 - EWMA_ALPHA) * state.avgReaction;

        double snapScore = calculateSnapScore(player, state);

        boolean flag = false;
        double severity = 0;
        String reason = "";

        if (state.hitCount >= MIN_HITS_TO_FLAG) {
            if (state.predAccuracy > 0.78 && snapScore > 0.72) {
                severity = Math.min(1.0, 0.45 + state.predAccuracy * 0.35 + snapScore * 0.15);
                reason = "Aim prediction acc=" + String.format("%.2f", state.predAccuracy) + " snap=" + String.format("%.2f", snapScore);
                flag = true;
            } else if (state.predAccuracy > 0.90) {
                severity = Math.min(1.0, 0.4 + state.predAccuracy * 0.45);
                reason = "Superhuman prediction acc=" + String.format("%.2f", state.predAccuracy);
                flag = true;
            } else if (snapScore > 0.9 && state.avgReaction < 120) {
                severity = Math.min(1.0, 0.45 + snapScore * 0.35);
                reason = "Inhuman snap snap=" + String.format("%.2f", snapScore) + " react=" + String.format("%.0f", state.avgReaction) + "ms";
                flag = true;
            }
        }

        return new CombatPredResult(state.predAccuracy, snapScore, state.avgReaction, severity, flag, reason);
    }

    private boolean isTrackable(Entity entity) {
        if (entity.isDead() || !entity.isValid()) return false;
        if (entity instanceof ArmorStand) return false;
        if (entity instanceof ItemFrame) return false;
        if (entity instanceof Painting) return false;
        if (entity instanceof Projectile) return false;
        if (!(entity instanceof LivingEntity)) return false;
        return true;
    }

    private boolean isFarmingTarget(Entity target) {
        if (target instanceof Player) return false;
        if (!(target instanceof LivingEntity)) return false;
        if (target instanceof org.bukkit.entity.Monster) return false;
        if (target instanceof org.bukkit.entity.Boss) return false;
        LivingEntity le = (LivingEntity) target;
        return le.getVelocity().length() < 0.08 && le.getNoDamageTicks() == 0;
    }

    private double angleBetween(Vector a, Vector b) {
        double al = a.length();
        double bl = b.length();
        if (al < 0.001 || bl < 0.001) return 90;
        double dot = a.dot(b) / (al * bl);
        dot = Math.max(-1.0, Math.min(1.0, dot));
        return Math.toDegrees(Math.acos(dot));
    }

    private double getTargetHeight(Entity entity) {
        if (entity instanceof LivingEntity) {
            return ((LivingEntity) entity).getEyeHeight();
        }
        return 1.0;
    }

    private double getTargetWidth(Entity entity) {
        if (entity instanceof Player) return 0.6;
        if (entity instanceof Slime) return 0.52 * ((Slime) entity).getSize();
        if (entity instanceof MagmaCube) return 0.52 * ((MagmaCube) entity).getSize();
        if (entity instanceof LivingEntity) return 0.6;
        return 0.5;
    }

    private double calculateReactionTime(List<EntPos> targetPositions, Location eyeLoc, Vector lookDir, int ping) {
        if (targetPositions.size() < 4) return 200;
        int n = targetPositions.size();

        double maxDirChange = 0;
        long changeTime = 0;

        for (int i = 2; i < n; i++) {
            EntPos p0 = targetPositions.get(i - 2);
            EntPos p1 = targetPositions.get(i - 1);
            EntPos p2 = targetPositions.get(i);

            double v1x = p1.x - p0.x;
            double v1z = p1.z - p0.z;
            double v2x = p2.x - p1.x;
            double v2z = p2.z - p1.z;

            if (Math.sqrt(v1x * v1x + v1z * v1z) < 0.01) continue;
            if (Math.sqrt(v2x * v2x + v2z * v2z) < 0.01) continue;

            double dirChange = Math.abs(Math.atan2(v2z, v2x) - Math.atan2(v1z, v1x));
            if (dirChange > Math.PI) dirChange = 2 * Math.PI - dirChange;

            if (dirChange > maxDirChange) {
                maxDirChange = dirChange;
                changeTime = p2.time;
            }
        }

        if (maxDirChange < 0.4) return 200;

        EntPos latest = targetPositions.get(n - 1);
        Vector toTarget = new Vector(latest.x - eyeLoc.getX(), 0, latest.z - eyeLoc.getZ());
        double aimAngle = angleBetween(lookDir, toTarget);

        if (aimAngle < 15) {
            long timeSinceChange = latest.time - changeTime;
            double adjusted = Math.max(0, timeSinceChange - ping * 0.5);
            return Math.max(30, adjusted);
        }

        return 200;
    }

    private double calculateSnapScore(Player player, CombatState state) {
        List<HitRecord> recent;
        synchronized (state.hitRecords) {
            if (state.hitRecords.size() < 10) return 0;
            recent = new ArrayList<>(state.hitRecords);
        }

        int n = recent.size();
        int start = Math.max(0, n - 20);

        double totalAngle = 0;
        int lowAngleHits = 0;
        int count = 0;

        for (int i = start; i < n; i++) {
            HitRecord hr = recent.get(i);
            totalAngle += hr.actualAngle;
            double distScaledThreshold = Math.max(3.0, 5.0 + hr.distance * 0.3);
            if (hr.actualAngle < distScaledThreshold) lowAngleHits++;
            count++;
        }

        if (count == 0) return 0;

        double avgAngle = totalAngle / count;
        double lowAngleRatio = (double) lowAngleHits / count;

        double variance = 0;
        for (int i = start; i < n; i++) {
            double diff = recent.get(i).actualAngle - avgAngle;
            variance += diff * diff;
        }
        variance /= count;

        double score = 0;
        if (lowAngleRatio > 0.90 && avgAngle < 3.0) score += 0.35;
        else if (lowAngleRatio > 0.80 && avgAngle < 5.0) score += 0.2;

        if (variance < 1.5 && avgAngle < 4.0) score += 0.25;
        else if (variance < 3.0 && avgAngle < 6.0) score += 0.1;

        if (state.predAccuracy > 0.7) score += 0.2;
        else if (state.predAccuracy > 0.5) score += 0.1;

        if (state.avgReaction < 80 && state.hitCount > 25) score += 0.15;

        return Math.min(1.0, score);
    }

    public void reset(UUID id) {
        states.remove(id);
    }

    public void cleanup() {
        long now = System.currentTimeMillis();
        states.entrySet().removeIf(e -> now - e.getValue().lastActive > 120000);
    }

    public static class CombatState {
        final Map<Integer, Deque<EntPos>> targetHistory = new ConcurrentHashMap<>();
        final Deque<HitRecord> hitRecords = new ConcurrentLinkedDeque<>();
        volatile double predAccuracy;
        volatile double avgReaction = 200;
        volatile int hitCount;
        volatile long lastActive = System.currentTimeMillis();
    }

    static class EntPos {
        final double x, y, z;
        final long time;

        EntPos(double x, double y, double z, long time) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.time = time;
        }
    }

    static class HitRecord {
        final double actualAngle;
        final double predAngle;
        final boolean aimedAtPredicted;
        final double distance;
        final long time;

        HitRecord(double actualAngle, double predAngle, boolean aimedAtPredicted, double distance, long time) {
            this.actualAngle = actualAngle;
            this.predAngle = predAngle;
            this.aimedAtPredicted = aimedAtPredicted;
            this.distance = distance;
            this.time = time;
        }
    }

    public static class CombatPredResult {
        public final double predAccuracy;
        public final double snapScore;
        public final double avgReaction;
        public final double severity;
        public final boolean shouldFlag;
        public final String reason;

        CombatPredResult(double predAccuracy, double snapScore, double avgReaction,
                         double severity, boolean shouldFlag, String reason) {
            this.predAccuracy = predAccuracy;
            this.snapScore = snapScore;
            this.avgReaction = avgReaction;
            this.severity = severity;
            this.shouldFlag = shouldFlag;
            this.reason = reason;
        }

        static CombatPredResult clean() {
            return new CombatPredResult(0, 0, 200, 0, false, "");
        }
    }
}
