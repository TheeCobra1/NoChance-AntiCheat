package NC.noChance.detection.movement;

import NC.noChance.core.*;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffectType;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class StepCheck {
    private final ACConfig config;
    private final Map<UUID, PlayerData> playerDataMap;
    private final LayerFiltering filtering;
    private final Map<UUID, StepTracker> trackers;

    private MovementGrace movementGrace;

    public StepCheck(ACConfig config, Map<UUID, PlayerData> playerDataMap, LayerFiltering filtering) {
        this.config = config;
        this.playerDataMap = playerDataMap;
        this.filtering = filtering;
        this.trackers = new ConcurrentHashMap<>();
    }

    public void setMovementGrace(MovementGrace grace) {
        this.movementGrace = grace;
    }

    public CheckResult check(Player player, Location from, Location to) {
        if (!config.isCheckEnabled("step")) {
            return CheckResult.passed();
        }

        PlayerData data = playerDataMap.get(player.getUniqueId());
        if (data == null) return CheckResult.passed();

        if (data.isInGracePeriod(config.getGracePeriod())) {
            return CheckResult.passed();
        }

        UUID uuid = player.getUniqueId();
        StepTracker tracker = trackers.computeIfAbsent(uuid, k -> new StepTracker());

        double verticalDistance = to.getY() - from.getY();

        if (verticalDistance < 0.5) {
            return CheckResult.passed();
        }

        if (player.getGameMode() == org.bukkit.GameMode.CREATIVE || player.getGameMode() == org.bukkit.GameMode.SPECTATOR) {
            return CheckResult.passed();
        }

        if (player.isFlying() || player.isGliding() || player.isRiptiding()) {
            return CheckResult.passed();
        }

        if (data.getAirTicks() > 1) {
            return CheckResult.passed();
        }

        if (tracker.lastVerticalDistance > 0.3) {
            tracker.lastVerticalDistance = verticalDistance;
            return CheckResult.passed();
        }

        if (player.getVehicle() != null) {
            return CheckResult.passed();
        }

        if (WaterHelper.isInWater(player) || player.isClimbing()) {
            return CheckResult.passed();
        }

        if (data.isInTeleportGracePeriod(2)) {
            return CheckResult.passed();
        }

        long timeSinceVelocity = System.currentTimeMillis() - data.getLastVelocityTime();
        if (timeSinceVelocity < 1000) {
            return CheckResult.passed();
        }

        if (movementGrace != null && movementGrace.inMountGrace(player.getUniqueId(), System.currentTimeMillis())) {
            return CheckResult.passed();
        }

        Block blockAt = player.getLocation().getBlock();
        if (blockAt.getType() == Material.LAVA || blockAt.getType().name().contains("LAVA")) {
            return CheckResult.passed();
        }

        if (blockAt.getType() == Material.SCAFFOLDING || blockAt.getType() == Material.POWDER_SNOW) {
            return CheckResult.passed();
        }

        if (blockAt.getType() == Material.BUBBLE_COLUMN) {
            return CheckResult.passed();
        }

        long timeSinceDamage = System.currentTimeMillis() - data.getLastDamageTime();
        if (timeSinceDamage < 500) {
            return CheckResult.passed();
        }

        Block blockBelow = from.clone().subtract(0, 1, 0).getBlock();
        Material belowType = blockBelow.getType();
        if (belowType == Material.SLIME_BLOCK || belowType == Material.HONEY_BLOCK) {
            return CheckResult.passed();
        }

        if (belowType.name().contains("BED") && !belowType.name().contains("BEDROCK")) {
            return CheckResult.passed();
        }

        if (belowType.name().contains("PISTON")) {
            return CheckResult.passed();
        }

        long timeSincePiston = System.currentTimeMillis() - tracker.lastPistonExtendTick;
        if (timeSincePiston < 800) {
            return CheckResult.passed();
        }

        double maxStep = 0.6;

        if (player.hasPotionEffect(PotionEffectType.JUMP_BOOST)) {
            int level = player.getPotionEffect(PotionEffectType.JUMP_BOOST).getAmplifier() + 1;
            int capped = Math.min(5, level);
            maxStep += Math.min(0.8, capped * 0.4);
        }

        if (player.hasPotionEffect(PotionEffectType.LEVITATION)) {
            return CheckResult.passed();
        }

        if (BlockCache.isSolid(to.clone().add(0, -0.5, 0))) {
            maxStep += 0.1;
        }

        Material belowMat = from.clone().subtract(0, 1, 0).getBlock().getType();
        if (belowMat == Material.SOUL_SAND || belowMat == Material.SOUL_SOIL) {
            maxStep += 0.15;
        }

        int ping = filtering.getPing(player);
        if (ping > 200) {
            maxStep += 0.25;
        } else if (ping > 100) {
            maxStep += 0.12;
        }

        boolean hasStairOrSlab = false;
        for (int y = -1; y <= 0; y++) {
            Block check = from.clone().add(0, y, 0).getBlock();
            String type = check.getType().name();
            if (type.contains("STAIRS") || type.contains("SLAB")) {
                hasStairOrSlab = true;
                break;
            }
        }

        if (hasStairOrSlab) {
            maxStep += 0.3;
            if (player.isSprinting() && verticalDistance > 0.5) {
                maxStep += 0.5;
            }
        }

        boolean nearSlime = false;
        for (int dx = -2; dx <= 2 && !nearSlime; dx++) {
            for (int dz = -2; dz <= 2 && !nearSlime; dz++) {
                for (int dy = -2; dy <= -1 && !nearSlime; dy++) {
                    Block check = from.clone().add(dx, dy, dz).getBlock();
                    if (check.getType() == Material.SLIME_BLOCK) {
                        nearSlime = true;
                    }
                }
            }
        }
        if (nearSlime && maxStep < 1.5) {
            maxStep = 1.5;
        }

        tracker.lastVerticalDistance = verticalDistance;

        if (verticalDistance > maxStep) {
            tracker.recordViolation(verticalDistance);

            int configThreshold = config.getViolationThreshold("step");
            int floor = ping >= 200 ? 4 : 3;
            int vlThreshold = Math.max(floor, configThreshold);
            if (tracker.getViolationCount() >= vlThreshold) {
                double severity = Math.min(1.0, (verticalDistance - maxStep) / maxStep);

                CheckResult prelimResult = CheckResult.failed(
                    ViolationType.STEP,
                    severity,
                    String.format("Step: %.2f blocks (max: %.2f) - %d violations",
                        verticalDistance, maxStep, tracker.getViolationCount())
                );

                if (!filtering.passesLayer2HeuristicFiltering(player, ViolationType.STEP, prelimResult)) {
                    tracker.decay();
                    return CheckResult.passed();
                }

                tracker.reset();
                return prelimResult;
            }
        }

        tracker.decay();
        return CheckResult.passed();
    }

    public void cleanup(UUID uuid) {
        trackers.remove(uuid);
    }

    public void notePistonExtend(UUID uuid) {
        StepTracker tracker = trackers.computeIfAbsent(uuid, k -> new StepTracker());
        tracker.lastPistonExtendTick = System.currentTimeMillis();
    }

    private static class StepTracker {
        private int violations = 0;
        private long lastViolation = 0;
        private long lastPistonExtendTick = 0;
        private double lastVerticalDistance = 0.0;

        void recordViolation(double height) {
            violations++;
            lastViolation = System.currentTimeMillis();
        }

        void decay() {
            if (System.currentTimeMillis() - lastViolation > 800) {
                violations = Math.max(0, violations - 1);
            }
        }

        void reset() {
            violations = 0;
        }

        int getViolationCount() {
            return violations;
        }
    }
}
