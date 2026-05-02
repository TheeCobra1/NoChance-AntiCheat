package NC.noChance.detection.combat;

import NC.noChance.core.*;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class AimAssistCheck {
    private final ACConfig config;
    private final Map<UUID, PlayerData> playerDataMap;
    private final LayerFiltering filtering;
    private final Map<UUID, AimTracker> trackers;

    private static final Set<Material> EXEMPT_ITEMS = Set.of(
        Material.FISHING_ROD, Material.BOW, Material.CROSSBOW,
        Material.TRIDENT, Material.ENDER_PEARL, Material.SNOWBALL,
        Material.EGG, Material.SPLASH_POTION, Material.LINGERING_POTION
    );

    public AimAssistCheck(ACConfig config, Map<UUID, PlayerData> playerDataMap, LayerFiltering filtering) {
        this.config = config;
        this.playerDataMap = playerDataMap;
        this.filtering = filtering;
        this.trackers = new ConcurrentHashMap<>();
    }

    public CheckResult check(Player player, Entity target) {
        if (!config.isCheckEnabled("aimassist")) {
            return CheckResult.passed();
        }

        if (target == null || target.isDead() || !target.isValid()) {
            return CheckResult.passed();
        }

        PlayerData data = playerDataMap.get(player.getUniqueId());
        if (data == null) return CheckResult.passed();

        if (data.isInGracePeriod(config.getGracePeriod())) {
            return CheckResult.passed();
        }

        if (isHoldingExemptItem(player)) {
            return CheckResult.passed();
        }

        if (player.getVehicle() != null) {
            return CheckResult.passed();
        }

        UUID uuid = player.getUniqueId();
        AimTracker tracker = trackers.computeIfAbsent(uuid, k -> new AimTracker());

        int ping = filtering.getPing(player);
        double sensMult = estimateSensitivityMultiplier(data);

        Vector pVel = player.getVelocity();
        double pHSpeed = Math.sqrt(pVel.getX() * pVel.getX() + pVel.getZ() * pVel.getZ());
        if (pHSpeed > 0.15) {
            sensMult /= 1.3;
        }

        CheckResult smoothCheck = checkAimSmoothing(player, target, data, tracker, sensMult);
        if (smoothCheck.isFailed()) return smoothCheck;

        CheckResult snapCheck = checkSnapCorrection(player, target, data, tracker, sensMult, ping);
        if (snapCheck.isFailed()) return snapCheck;

        CheckResult silentCheck = checkSilentAim(player, target, data, tracker, ping);
        if (silentCheck.isFailed()) return silentCheck;

        CheckResult predictionCheck = checkPredictionCorrection(player, target, data, tracker);
        if (predictionCheck.isFailed()) return predictionCheck;

        return CheckResult.passed();
    }

    private boolean isHoldingExemptItem(Player player) {
        ItemStack main = player.getInventory().getItemInMainHand();
        if (main != null && EXEMPT_ITEMS.contains(main.getType())) return true;
        ItemStack off = player.getInventory().getItemInOffHand();
        if (off != null && EXEMPT_ITEMS.contains(off.getType())) return true;
        return false;
    }

    private double estimateSensitivityMultiplier(PlayerData data) {
        double rotSpeed = data.getAverageRotationSpeed();
        if (rotSpeed > 400) return 0.7;
        if (rotSpeed > 280) return 0.85;
        if (rotSpeed > 150) return 1.0;
        return 1.2;
    }

    private CheckResult checkAimSmoothing(Player player, Entity target, PlayerData data, AimTracker tracker, double sensMult) {
        Deque<PlayerData.RotationData> rotations = data.getRotationHistory();
        if (rotations.size() < 15) return CheckResult.passed();

        List<PlayerData.RotationData> rotList = new ArrayList<>(rotations);
        List<Double> jitterValues = new ArrayList<>();

        for (int i = 2; i < rotList.size(); i++) {
            PlayerData.RotationData prev2 = rotList.get(i - 2);
            PlayerData.RotationData prev1 = rotList.get(i - 1);
            PlayerData.RotationData curr = rotList.get(i);

            double delta1 = getRotationDelta(prev2, prev1);
            double delta2 = getRotationDelta(prev1, curr);

            double jitter = Math.abs(delta2 - delta1);
            jitterValues.add(jitter);
        }

        if (jitterValues.size() < 12) return CheckResult.passed();

        for (int i = 1; i < rotList.size(); i++) {
            double yawOnly = Math.abs(normalizeYaw(rotList.get(i).yaw - rotList.get(i - 1).yaw));
            tracker.pushYawDelta(yawOnly);
        }

        double avgJitter = jitterValues.stream().mapToDouble(Double::doubleValue).average().orElse(10.0);
        double variance = jitterValues.stream()
            .mapToDouble(j -> Math.pow(j - avgJitter, 2))
            .average()
            .orElse(10.0);

        double avgRotation = 0;
        for (int i = 1; i < rotList.size(); i++) {
            avgRotation += getRotationDelta(rotList.get(i - 1), rotList.get(i));
        }
        avgRotation /= Math.max(1, rotList.size() - 1);
        if (avgRotation < 3.0) {
            return CheckResult.passed();
        }

        double jitterThreshold = 0.5 * sensMult;
        double varianceThreshold = 0.05 * sensMult;

        if (avgJitter < jitterThreshold && variance < varianceThreshold && jitterValues.size() >= 18) {
            tracker.recordSmoothViolation();

            if (tracker.getSmoothViolations() >= 6) {
                double severity = Math.min(0.94, 0.75 + (jitterThreshold - avgJitter) * 0.5);
                severity *= (isControllerLikeCurve(tracker) ? 0.4 : 1.0);

                CheckResult prelimResult = CheckResult.failed(
                    ViolationType.AIMASSIST,
                    severity,
                    String.format("Smooth aim: jitter=%.3f var=%.4f samples=%d", avgJitter, variance, jitterValues.size())
                );

                if (filtering.passesLayer2HeuristicFiltering(player, ViolationType.AIMASSIST, prelimResult)) {
                    return prelimResult;
                }
            }
        } else {
            tracker.decaySmooth();
        }

        return CheckResult.passed();
    }

    private CheckResult checkSnapCorrection(Player player, Entity target, PlayerData data, AimTracker tracker, double sensMult, int ping) {
        Deque<PlayerData.RotationData> rotations = data.getRotationHistory();
        if (rotations.size() < 8) return CheckResult.passed();

        List<PlayerData.RotationData> rotList = new ArrayList<>(rotations);
        int size = rotList.size();

        List<MicroCorrection> corrections = new ArrayList<>();

        double maxDelta = 3.0 * sensMult;

        for (int i = 2; i < size; i++) {
            PlayerData.RotationData prev = rotList.get(i - 1);
            PlayerData.RotationData curr = rotList.get(i);

            double delta = getRotationDelta(prev, curr);
            long dt = curr.timestamp - prev.timestamp;

            if (dt > 0 && dt < 100 && delta > 0.1 && delta < maxDelta) {
                corrections.add(new MicroCorrection(delta, dt, curr.timestamp));
            }
        }

        long now = System.currentTimeMillis();
        corrections.removeIf(c -> now - c.timestamp > 3000);

        if (corrections.size() >= 6) {
            double avgDelta = corrections.stream().mapToDouble(c -> c.delta).average().orElse(5.0);
            double variance = corrections.stream()
                .mapToDouble(c -> Math.pow(c.delta - avgDelta, 2))
                .average()
                .orElse(5.0);

            long inhumanCount = corrections.stream().filter(c -> c.dt < 35).count();
            if (inhumanCount >= 5) {
                tracker.recordSnapViolation();
                if (tracker.getSnapViolations() >= 6) {
                    double severity = Math.min(0.94, 0.80 + inhumanCount * 0.02);
                    CheckResult prelimResult = CheckResult.failed(
                        ViolationType.AIMASSIST,
                        severity,
                        String.format("Inhuman corrections: %d sub-15ms in %d total", inhumanCount, corrections.size())
                    );
                    if (filtering.passesLayer2HeuristicFiltering(player, ViolationType.AIMASSIST, prelimResult)) {
                        return prelimResult;
                    }
                }
            }

            double varThreshold = 0.1 + (ping * 0.001);

            if (variance < varThreshold && avgDelta < 2.0) {
                tracker.recordSnapViolation();

                if (tracker.getSnapViolations() >= 6) {
                    double severity = Math.min(0.92, 0.72 + corrections.size() * 0.02);

                    CheckResult prelimResult = CheckResult.failed(
                        ViolationType.AIMASSIST,
                        severity,
                        String.format("Snap correction: corrections=%d avgDelta=%.2f var=%.3f", corrections.size(), avgDelta, variance)
                    );

                    if (filtering.passesLayer2HeuristicFiltering(player, ViolationType.AIMASSIST, prelimResult)) {
                        return prelimResult;
                    }
                }
            } else {
                tracker.decaySnap();
            }
        }

        return CheckResult.passed();
    }

    private CheckResult checkSilentAim(Player player, Entity target, PlayerData data, AimTracker tracker, int ping) {
        if (!(target instanceof LivingEntity)) return CheckResult.passed();

        Location eyeLoc = player.getEyeLocation();
        Vector playerView = eyeLoc.getDirection().normalize();

        Location targetCenter = getEntityCenter(target);
        Vector toTarget = targetCenter.toVector().subtract(eyeLoc.toVector()).normalize();

        double dot = playerView.dot(toTarget);
        dot = Math.max(-1.0, Math.min(1.0, dot));
        double angle = Math.toDegrees(Math.acos(dot));

        double angleThreshold = 30.0 + (ping * 0.025);
        double highAngleThreshold = 35.0 + (ping * 0.025);

        if (angle > angleThreshold) {
            tracker.recordOffTargetHit(angle);

            List<Double> recentAngles = tracker.getRecentOffTargetAngles();
            if (recentAngles.size() >= 5) {
                double avgAngle = recentAngles.stream().mapToDouble(Double::doubleValue).average().orElse(0);
                long highAngleHits = recentAngles.stream().filter(a -> a > highAngleThreshold).count();

                if (highAngleHits >= 3 && avgAngle > 38.0) {
                    double severity = Math.min(0.96, 0.80 + (avgAngle - 30.0) / 50.0);

                    CheckResult prelimResult = CheckResult.failed(
                        ViolationType.AIMASSIST_SILENT,
                        severity,
                        String.format("Silent aim: avgAngle=%.1f highHits=%d", avgAngle, highAngleHits)
                    );

                    if (filtering.passesLayer2HeuristicFiltering(player, ViolationType.AIMASSIST_SILENT, prelimResult)) {
                        tracker.clearOffTargetAngles();
                        return prelimResult;
                    }
                }
            }
        }

        return CheckResult.passed();
    }

    private CheckResult checkPredictionCorrection(Player player, Entity target, PlayerData data, AimTracker tracker) {
        if (!(target instanceof LivingEntity)) return CheckResult.passed();

        LivingEntity living = (LivingEntity) target;
        Vector targetVelocity = living.getVelocity();

        if (targetVelocity.length() < 0.1) return CheckResult.passed();

        Deque<PlayerData.RotationData> rotations = data.getRotationHistory();
        if (rotations.size() < 10) return CheckResult.passed();

        Location eyeLoc = player.getEyeLocation();
        Location targetLoc = living.getLocation();

        Vector predictedPos = targetLoc.toVector().add(targetVelocity.clone().multiply(2.0));
        predictedPos.setY(predictedPos.getY() - 0.08 * 2.0);
        Vector toPredicted = predictedPos.subtract(eyeLoc.toVector()).normalize();

        Vector playerView = eyeLoc.getDirection().normalize();
        double dotPredicted = playerView.dot(toPredicted);
        dotPredicted = Math.max(-1.0, Math.min(1.0, dotPredicted));
        double predictionAngle = Math.toDegrees(Math.acos(dotPredicted));

        Vector toActual = targetLoc.toVector().subtract(eyeLoc.toVector()).normalize();
        double dotActual = playerView.dot(toActual);
        dotActual = Math.max(-1.0, Math.min(1.0, dotActual));
        double actualAngle = Math.toDegrees(Math.acos(dotActual));

        if (predictionAngle < 5.0 && actualAngle > 8.0) {
            tracker.recordPredictionViolation();

            if (tracker.getPredictionViolations() >= 10) {
                double severity = Math.min(0.90, 0.70 + tracker.getPredictionViolations() * 0.03);

                CheckResult prelimResult = CheckResult.failed(
                    ViolationType.AIMASSIST,
                    severity,
                    String.format("Prediction aim: predAngle=%.1f actualAngle=%.1f", predictionAngle, actualAngle)
                );

                if (filtering.passesLayer2HeuristicFiltering(player, ViolationType.AIMASSIST, prelimResult)) {
                    return prelimResult;
                }
            }
        } else {
            tracker.decayPrediction();
        }

        return CheckResult.passed();
    }

    private boolean isControllerLikeCurve(AimTracker tracker) {
        double[] deltas = tracker.getYawDeltaSnapshot();
        if (deltas.length < 8) return false;
        int decelCount = 0;
        for (int i = 1; i < deltas.length; i++) {
            double d2 = Math.abs(deltas[i]) - Math.abs(deltas[i - 1]);
            if (d2 < -0.01) decelCount++;
        }
        return decelCount >= (deltas.length - 1) * 0.6;
    }

    private double getRotationDelta(PlayerData.RotationData r1, PlayerData.RotationData r2) {
        float yawDiff = Math.abs(normalizeYaw(r2.yaw - r1.yaw));
        float pitchDiff = Math.abs(r2.pitch - r1.pitch);
        return Math.sqrt(yawDiff * yawDiff + pitchDiff * pitchDiff);
    }

    private float normalizeYaw(float yaw) {
        while (yaw > 180.0f) yaw -= 360.0f;
        while (yaw < -180.0f) yaw += 360.0f;
        return yaw;
    }

    private Location getEntityCenter(Entity entity) {
        return entity.getLocation().add(0, entity.getHeight() * 0.5, 0);
    }

    public void cleanup(UUID playerId) {
        trackers.remove(playerId);
    }

    public void cleanupStale() {
        long now = System.currentTimeMillis();
        trackers.entrySet().removeIf(entry -> now - entry.getValue().lastActivity > 30000);
    }

    private static class MicroCorrection {
        final double delta;
        final long dt;
        final long timestamp;

        MicroCorrection(double delta, long dt, long timestamp) {
            this.delta = delta;
            this.dt = dt;
            this.timestamp = timestamp;
        }
    }

    private static class AimTracker {
        private int smoothViolations = 0;
        private int snapViolations = 0;
        private int predictionViolations = 0;
        private long lastSmoothViolation = 0;
        private long lastSnapViolation = 0;
        private long lastPredictionViolation = 0;
        private long lastActivity = System.currentTimeMillis();
        private final Deque<Double> offTargetAngles = new ArrayDeque<>(6);
        private final Deque<Double> yawDeltas = new ArrayDeque<>(10);

        void pushYawDelta(double delta) {
            if (yawDeltas.size() >= 10) {
                yawDeltas.pollFirst();
            }
            yawDeltas.addLast(delta);
        }

        double[] getYawDeltaSnapshot() {
            double[] arr = new double[yawDeltas.size()];
            int i = 0;
            for (double v : yawDeltas) arr[i++] = v;
            return arr;
        }

        void recordSmoothViolation() {
            long now = System.currentTimeMillis();
            if (now - lastSmoothViolation < 3000) {
                smoothViolations++;
            } else {
                smoothViolations = 1;
            }
            lastSmoothViolation = now;
            lastActivity = now;
        }

        void decaySmooth() {
            long now = System.currentTimeMillis();
            if (now - lastSmoothViolation > 3000) {
                smoothViolations = Math.max(0, smoothViolations - 1);
            }
        }

        int getSmoothViolations() { return smoothViolations; }

        void recordSnapViolation() {
            long now = System.currentTimeMillis();
            if (now - lastSnapViolation < 2500) {
                snapViolations++;
            } else {
                snapViolations = 1;
            }
            lastSnapViolation = now;
            lastActivity = now;
        }

        void decaySnap() {
            long now = System.currentTimeMillis();
            if (now - lastSnapViolation > 4000) {
                snapViolations = Math.max(0, snapViolations - 1);
            }
        }

        int getSnapViolations() { return snapViolations; }

        void recordPredictionViolation() {
            long now = System.currentTimeMillis();
            if (now - lastPredictionViolation < 3000) {
                predictionViolations++;
            } else {
                predictionViolations = 1;
            }
            lastPredictionViolation = now;
            lastActivity = now;
        }

        void decayPrediction() {
            long now = System.currentTimeMillis();
            if (now - lastPredictionViolation > 5000) {
                predictionViolations = Math.max(0, predictionViolations - 1);
            }
        }

        int getPredictionViolations() { return predictionViolations; }

        void recordOffTargetHit(double angle) {
            if (offTargetAngles.size() >= 6) {
                offTargetAngles.pollFirst();
            }
            offTargetAngles.addLast(angle);
            lastActivity = System.currentTimeMillis();
        }

        List<Double> getRecentOffTargetAngles() {
            return new ArrayList<>(offTargetAngles);
        }

        void clearOffTargetAngles() {
            offTargetAngles.clear();
        }
    }
}
