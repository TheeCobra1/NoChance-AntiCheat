package NC.noChance.detection.damage;

import NC.noChance.core.*;
import org.bukkit.Material;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import org.bukkit.util.Vector;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

public class VelocityCheck {
    private final ACConfig config;
    private final Map<UUID, PlayerData> playerDataMap;
    private final LayerFiltering filtering;
    private final Map<UUID, VelocityTracker> trackers;

    private static final double HORIZONTAL_FRICTION = 0.91;
    private static final double VERTICAL_DRAG = 0.98;
    private static final double GRAVITY = 0.08;
    private static final double TICK_MS = 50.0;
    private static final double EWMA_ALPHA = 0.40;
    private static final int MIN_SAMPLES = 3;
    private static final int REQUIRED_CONSISTENT_VIOLATIONS = 2;
    private static final double PERCENTAGE_DETECTION_THRESHOLD = 0.93;

    public VelocityCheck(ACConfig config, Map<UUID, PlayerData> playerDataMap, LayerFiltering filtering) {
        this.config = config;
        this.playerDataMap = playerDataMap;
        this.filtering = filtering;
        this.trackers = new ConcurrentHashMap<>();
    }

    public void recordVelocity(Player player, Vector velocity) {
        UUID uuid = player.getUniqueId();
        PlayerData data = playerDataMap.get(uuid);
        if (data != null && data.isInTeleportGracePeriod(3)) {
            VelocityTracker existing = trackers.get(uuid);
            if (existing != null) existing.clear();
            return;
        }
        VelocityTracker tracker = trackers.computeIfAbsent(uuid, k -> new VelocityTracker());
        int ping = filtering.getPing(player);
        tracker.setExpectedVelocity(velocity, System.currentTimeMillis(), ping);
    }

    public CheckResult check(Player player, Vector actualVelocity) {
        if (!config.isCheckEnabled("velocity")) {
            return CheckResult.passed();
        }

        PlayerData data = playerDataMap.get(player.getUniqueId());
        if (data == null) return CheckResult.passed();

        if (data.isInGracePeriod(config.getGracePeriod())) {
            return CheckResult.passed();
        }

        if (isBlocking(player)) {
            return CheckResult.passed();
        }

        if (player.isRiptiding() || player.isGliding()) {
            return CheckResult.passed();
        }

        if (player.getVehicle() != null) {
            return CheckResult.passed();
        }

        if (player.isSwimming() || WaterHelper.isInWater(player)) {
            return CheckResult.passed();
        }

        if (data.isInTeleportGracePeriod(3)) {
            return CheckResult.passed();
        }

        if (TPSCache.getTPS() < 18.0) {
            return CheckResult.passed();
        }

        Block below = player.getLocation().subtract(0, 0.5, 0).getBlock();
        if (below.getType() == Material.SLIME_BLOCK || below.getType() == Material.HONEY_BLOCK) {
            return CheckResult.passed();
        }

        Material feetBlock = player.getLocation().getBlock().getType();
        if (feetBlock == Material.COBWEB || feetBlock == Material.POWDER_SNOW) {
            return CheckResult.passed();
        }

        UUID uuid = player.getUniqueId();
        VelocityTracker tracker = trackers.get(uuid);

        if (tracker == null || !tracker.hasExpectedVelocity()) {
            return CheckResult.passed();
        }

        long timeSinceVelocity = System.currentTimeMillis() - tracker.getVelocityTime();

        if (timeSinceVelocity > 1500) {
            tracker.clear();
            return CheckResult.passed();
        }

        double kbResistance = getKnockbackResistance(player);
        if (kbResistance >= 1.0) {
            return CheckResult.passed();
        }

        int ticksElapsed = (int) Math.max(1, timeSinceVelocity / TICK_MS);
        Material blockBelow = player.getLocation().subtract(0, 0.5, 0).getBlock().getType();
        double friction = getFriction(blockBelow);
        Vector predictedVelocity = tracker.predictVelocityAfterTicks(ticksElapsed, player.isOnGround(), friction);

        double horizontalPredicted = Math.sqrt(predictedVelocity.getX() * predictedVelocity.getX() +
                                               predictedVelocity.getZ() * predictedVelocity.getZ());
        double horizontalActual = Math.sqrt(actualVelocity.getX() * actualVelocity.getX() +
                                            actualVelocity.getZ() * actualVelocity.getZ());

        double verticalPredicted = predictedVelocity.getY();
        double verticalActual = actualVelocity.getY();

        double resistanceMult = (1.0 - kbResistance);
        horizontalPredicted *= resistanceMult;
        verticalPredicted *= resistanceMult;

        double horizontalPercent = horizontalPredicted > 0.05 ? horizontalActual / horizontalPredicted : 1.0;
        double verticalPercent = Math.abs(verticalPredicted) > 0.05 ? verticalActual / verticalPredicted : 1.0;

        if (Double.isNaN(horizontalPercent) || Double.isInfinite(horizontalPercent)) horizontalPercent = 1.0;
        if (Double.isNaN(verticalPercent) || Double.isInfinite(verticalPercent)) verticalPercent = 1.0;

        tracker.recordResult(horizontalPercent, verticalPercent, ticksElapsed);

        int ping = tracker.getPing();

        if (horizontalPercent < 0.18 && horizontalPredicted > 0.10 && ticksElapsed <= 3 && ping < 150) {
            double severity = Math.min(0.98, 0.88 + (0.18 - horizontalPercent) * 0.5);
            CheckResult prelimResult = CheckResult.failed(
                ViolationType.VELOCITY,
                severity,
                String.format("Instant cancel: H:%.1f%% (pred:%.3f, ticks:%d)", horizontalPercent * 100, horizontalPredicted, ticksElapsed)
            );
            if (filtering.passesLayer2HeuristicFiltering(player, ViolationType.VELOCITY, prelimResult)) {
                tracker.recordViolation();
                return prelimResult;
            }
        }

        if (verticalPercent < 0.20 && Math.abs(verticalPredicted) > 0.12 && ticksElapsed <= 3 && ping < 150) {
            double severity = Math.min(0.97, 0.86 + (0.20 - verticalPercent) * 0.5);
            CheckResult vResult = CheckResult.failed(
                ViolationType.VELOCITY,
                severity,
                String.format("Instant cancel V: V:%.1f%% (pred:%.3f)", verticalPercent * 100, verticalPredicted)
            );
            if (filtering.passesLayer2HeuristicFiltering(player, ViolationType.VELOCITY, vResult)) {
                tracker.recordViolation();
                return vResult;
            }
        }

        if (tracker.getSampleCount() < MIN_SAMPLES) {
            return CheckResult.passed();
        }

        double ewmaH = tracker.getEWMAHorizontal();
        double ewmaV = tracker.getEWMAVertical();
        double avgH = tracker.getAverageHorizontal();
        double avgV = tracker.getAverageVertical();
        double varianceH = tracker.getHorizontalVariance();

        double hThreshold = 0.68 + getPingToleranceH(ping);
        double vThreshold = 0.55 + getPingToleranceV(ping);

        CheckResult percentageCheck = checkPercentageVelocity(tracker, player, horizontalPercent, verticalPercent, ping, kbResistance);
        if (percentageCheck.isFailed()) {
            return percentageCheck;
        }

        double kbAdjustedThreshold = PERCENTAGE_DETECTION_THRESHOLD * (1.0 - kbResistance);
        boolean lowVariance = varianceH < 0.008 && tracker.getSampleCount() >= 5;
        boolean consistentReduction = avgH < kbAdjustedThreshold && avgH > 0.25 && lowVariance;

        if (consistentReduction && tracker.getSampleCount() >= 5) {
            int detectedPercent = (int) Math.round(avgH * 100);
            double severity = Math.min(0.94, 0.78 + (kbAdjustedThreshold - avgH) * 0.6);

            CheckResult prelimResult = CheckResult.failed(
                ViolationType.VELOCITY,
                severity,
                String.format("Percentage velocity: ~%d%% (var:%.4f, samples:%d)",
                    detectedPercent, varianceH, tracker.getSampleCount())
            );

            if (filtering.passesLayer2HeuristicFiltering(player, ViolationType.VELOCITY, prelimResult)) {
                tracker.recordViolation();
                return prelimResult;
            }
        }

        if (ewmaH < hThreshold || ewmaV < vThreshold) {
            if (tracker.getConsecutiveViolations() < REQUIRED_CONSISTENT_VIOLATIONS) {
                tracker.recordViolation();
                return CheckResult.passed();
            }

            double deviation = Math.max(hThreshold - ewmaH, vThreshold - ewmaV);
            double severity = Math.min(0.96, 0.70 + (deviation * 0.35) + (tracker.getConsecutiveViolations() * 0.025));

            CheckResult prelimResult = CheckResult.failed(
                ViolationType.VELOCITY,
                severity,
                String.format("Anti-KB: H:%.1f%% V:%.1f%% (ewma:%.2f/%.2f, avg:%.2f/%.2f, vl:%d)",
                    horizontalPercent * 100, verticalPercent * 100, ewmaH, ewmaV, avgH, avgV, tracker.getConsecutiveViolations())
            );

            if (!filtering.passesLayer2HeuristicFiltering(player, ViolationType.VELOCITY, prelimResult)) {
                tracker.decayViolations();
                return CheckResult.passed();
            }

            tracker.recordViolation();
            return prelimResult;
        }

        tracker.decayViolations();
        return CheckResult.passed();
    }

    private CheckResult checkPercentageVelocity(VelocityTracker tracker, Player player, double hPercent, double vPercent, int ping, double kbResist) {
        double[] commonPercentages = {0.0, 0.50, 0.75, 0.80, 0.85, 0.90, 0.95};
        double avgH = tracker.getAverageHorizontal();
        double varianceH = tracker.getHorizontalVariance();

        if (tracker.getSampleCount() < 6 || varianceH > 0.015) {
            return CheckResult.passed();
        }

        for (double target : commonPercentages) {
            if (target == 0.0) {
                if (avgH < 0.08 && varianceH < 0.003 && ping < 200) {
                    CheckResult prelimResult = CheckResult.failed(
                        ViolationType.VELOCITY,
                        0.96,
                        String.format("Full anti-KB: %.1f%% (var:%.4f)", avgH * 100, varianceH)
                    );
                    if (filtering.passesLayer2HeuristicFiltering(player, ViolationType.VELOCITY, prelimResult)) {
                        return prelimResult;
                    }
                }
            } else if (target < 0.95) {
                double adjustedTarget = target * (1.0 - kbResist);
                double tolerance = 0.05 + (ping * 0.00015);
                if (Math.abs(avgH - adjustedTarget) < tolerance && varianceH < 0.012) {
                    int detectedPercent = (int) Math.round(target * 100);
                    double severity = Math.min(0.94, 0.76 + (0.95 - target) * 0.5);

                    CheckResult prelimResult = CheckResult.failed(
                        ViolationType.VELOCITY,
                        severity,
                        String.format("Velocity %d%%: actual %.1f%% (var:%.4f, samples:%d)",
                            detectedPercent, avgH * 100, varianceH, tracker.getSampleCount())
                    );

                    if (filtering.passesLayer2HeuristicFiltering(player, ViolationType.VELOCITY, prelimResult)) {
                        return prelimResult;
                    }
                }
            }
        }

        return CheckResult.passed();
    }

    private boolean isBlocking(Player player) {
        ItemStack main = player.getInventory().getItemInMainHand();
        ItemStack off = player.getInventory().getItemInOffHand();
        boolean hasShield = (main != null && main.getType() == Material.SHIELD) ||
                           (off != null && off.getType() == Material.SHIELD);
        return hasShield && player.isHandRaised() && player.isBlocking();
    }

    private double getKnockbackResistance(Player player) {
        try {
            AttributeInstance attr = player.getAttribute(Attribute.GENERIC_KNOCKBACK_RESISTANCE);
            if (attr != null) return attr.getValue();
        } catch (Exception e) {
            Logger.getLogger("NoChance").log(Level.WARNING, "Failed to get knockback resistance for " + player.getName(), e);
        }
        return 0.0;
    }

    private double getPingToleranceH(int ping) {
        if (ping <= 50) return 0.05;
        if (ping <= 100) return 0.10;
        if (ping <= 150) return 0.18;
        if (ping <= 200) return 0.25;
        if (ping <= 300) return 0.35;
        return 0.42;
    }

    private double getFriction(Material block) {
        if (block == Material.ICE || block == Material.PACKED_ICE || block == Material.BLUE_ICE) {
            return 0.98;
        }
        if (block == Material.SLIME_BLOCK) {
            return 0.8;
        }
        return HORIZONTAL_FRICTION;
    }

    private double getPingToleranceV(int ping) {
        if (ping <= 50) return 0.03;
        if (ping <= 100) return 0.06;
        if (ping <= 150) return 0.12;
        if (ping <= 200) return 0.18;
        if (ping <= 300) return 0.25;
        return 0.32;
    }

    public void cleanup(UUID uuid) {
        trackers.remove(uuid);
    }

    private static class VelocityTracker {
        private Vector expectedVelocity;
        private long velocityTime;
        private int ping;
        private final List<Double> horizontalResults = new ArrayList<>();
        private final List<Double> verticalResults = new ArrayList<>();
        private double ewmaHorizontal = 1.0;
        private double ewmaVertical = 1.0;
        private boolean ewmaInit = false;
        private int consecutiveViolations = 0;
        private long lastViolation = 0;
        private double welfordMeanH = 0;
        private double welfordM2H = 0;
        private long welfordNH = 0;

        void setExpectedVelocity(Vector velocity, long time, int ping) {
            this.expectedVelocity = velocity.clone();
            this.velocityTime = time;
            this.ping = ping;
            horizontalResults.clear();
            verticalResults.clear();
            welfordMeanH = 0;
            welfordM2H = 0;
            welfordNH = 0;
        }

        Vector predictVelocityAfterTicks(int ticks, boolean onGround, double friction) {
            if (expectedVelocity == null) return new Vector(0, 0, 0);

            double vx = expectedVelocity.getX();
            double vy = expectedVelocity.getY();
            double vz = expectedVelocity.getZ();

            for (int i = 0; i < ticks; i++) {
                if (onGround) {
                    vx *= 0.6 * friction;
                    vz *= 0.6 * friction;
                } else {
                    vx *= 0.91;
                    vz *= 0.91;
                }

                vy = (vy - GRAVITY) * VERTICAL_DRAG;
            }

            return new Vector(vx, vy, vz);
        }

        void recordResult(double horizontal, double vertical, int ticks) {
            horizontalResults.add(horizontal);
            verticalResults.add(vertical);

            if (horizontalResults.size() > 4) {
                horizontalResults.remove(0);
                verticalResults.remove(0);
            }

            if (!ewmaInit) {
                ewmaHorizontal = horizontal;
                ewmaVertical = vertical;
                ewmaInit = true;
            } else {
                ewmaHorizontal = EWMA_ALPHA * horizontal + (1.0 - EWMA_ALPHA) * ewmaHorizontal;
                ewmaVertical = EWMA_ALPHA * vertical + (1.0 - EWMA_ALPHA) * ewmaVertical;
            }

            updateWelford(horizontal);
        }

        private void updateWelford(double value) {
            welfordNH++;
            double delta = value - welfordMeanH;
            welfordMeanH += delta / welfordNH;
            double delta2 = value - welfordMeanH;
            welfordM2H += delta * delta2;
        }

        void recordViolation() {
            consecutiveViolations++;
            lastViolation = System.currentTimeMillis();
        }

        void decayViolations() {
            if (System.currentTimeMillis() - lastViolation > 2500 && consecutiveViolations > 0) {
                consecutiveViolations--;
            }
        }

        boolean hasExpectedVelocity() {
            return expectedVelocity != null;
        }

        long getVelocityTime() {
            return velocityTime;
        }

        int getPing() {
            return ping;
        }

        int getSampleCount() {
            return horizontalResults.size();
        }

        int getConsecutiveViolations() {
            return consecutiveViolations;
        }

        double getEWMAHorizontal() {
            return ewmaHorizontal;
        }

        double getEWMAVertical() {
            return ewmaVertical;
        }

        double getAverageHorizontal() {
            if (horizontalResults.isEmpty()) return 1.0;
            double sum = 0;
            for (double d : horizontalResults) sum += d;
            return sum / horizontalResults.size();
        }

        double getAverageVertical() {
            if (verticalResults.isEmpty()) return 1.0;
            double sum = 0;
            for (double d : verticalResults) sum += d;
            return sum / verticalResults.size();
        }

        double getHorizontalVariance() {
            if (welfordNH < 2) return 1.0;
            return welfordM2H / (welfordNH - 1);
        }

        void clear() {
            expectedVelocity = null;
            horizontalResults.clear();
            verticalResults.clear();
            ewmaInit = false;
            ewmaHorizontal = 1.0;
            ewmaVertical = 1.0;
            welfordMeanH = 0;
            welfordM2H = 0;
            welfordNH = 0;
        }
    }
}
