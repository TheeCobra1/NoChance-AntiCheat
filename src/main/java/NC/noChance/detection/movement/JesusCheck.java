package NC.noChance.detection.movement;

import NC.noChance.core.*;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Boat;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

public class JesusCheck {
    private final ACConfig config;
    private final Map<UUID, PlayerData> playerDataMap;
    private final LayerFiltering filtering;
    private final Map<UUID, JesusTracker> trackers;

    private static final int REQUIRED_VL = 4;
    private static final int INSTANT_FLAG_VL = 10;
    private static final double WATER_SURFACE_TOLERANCE = 0.25;
    private static final long EXEMPT_GRACE_MS = 600;
    private static final double MIN_HORIZONTAL_SPEED = 0.08;
    private static final double BOB_VELOCITY_THRESHOLD = 0.06;

    private MovementGrace movementGrace;

    public JesusCheck(ACConfig config, Map<UUID, PlayerData> playerDataMap, LayerFiltering filtering) {
        this.config = config;
        this.playerDataMap = playerDataMap;
        this.filtering = filtering;
        this.trackers = new ConcurrentHashMap<>();
    }

    public void setMovementGrace(MovementGrace grace) {
        this.movementGrace = grace;
    }

    public CheckResult check(Player player, Location from, Location to) {
        if (!config.isCheckEnabled("jesus")) {
            return CheckResult.passed();
        }

        PlayerData data = playerDataMap.get(player.getUniqueId());
        if (data == null) return CheckResult.passed();

        if (data.isInGracePeriod(config.getGracePeriod())) {
            return CheckResult.passed();
        }

        UUID uuid = player.getUniqueId();
        JesusTracker tracker = trackers.computeIfAbsent(uuid, k -> new JesusTracker());

        if (shouldExempt(player, tracker)) {
            tracker.decay();
            return CheckResult.passed();
        }

        if (WaterHelper.hasWaterBreathing(player) && WaterHelper.isNearWater(to, 1)) {
            tracker.decay();
            return CheckResult.passed();
        }

        WaterWalkingState state = analyzeWaterWalkingState(player, from, to);

        if (state == WaterWalkingState.NOT_ABOVE_LIQUID) {
            tracker.reset();
            return CheckResult.passed();
        }

        if (state == WaterWalkingState.LEGITIMATELY_IN_LIQUID) {
            tracker.setLastInWater(System.currentTimeMillis());
            tracker.decay();
            return CheckResult.passed();
        }

        if (state == WaterWalkingState.WALKING_ON_LIQUID) {
            double velocityY = to.getY() - from.getY();
            double dx = to.getX() - from.getX();
            double dz = to.getZ() - from.getZ();
            double horizontalSpeed = Math.sqrt(dx * dx + dz * dz);

            tracker.recordSuspiciousPosition(to.getY(), velocityY, horizontalSpeed);

            int violations = tracker.getViolationCount();

            if (violations >= INSTANT_FLAG_VL && tracker.hasStablePattern()) {
                double severity = Math.min(0.96, 0.82 + (violations * 0.02));
                if (WaterHelper.isNearWater(to, 2)) {
                    severity *= 0.85;
                }
                CheckResult result = CheckResult.failed(
                    ViolationType.JESUS,
                    severity,
                    String.format("Walking on water: Y:%.3f vY:%.4f speed:%.3f VL:%d pattern:%s",
                        to.getY(), velocityY, horizontalSpeed, violations, tracker.getPatternDesc())
                );

                if (filtering.passesLayer2HeuristicFiltering(player, ViolationType.JESUS, result)) {
                    return result;
                }
            }

            if (violations >= REQUIRED_VL) {
                double severity = Math.min(0.92, 0.70 + (violations * 0.04));
                String mode = horizontalSpeed > MIN_HORIZONTAL_SPEED ? "moving" : "idle";

                CheckResult result = CheckResult.failed(
                    ViolationType.JESUS,
                    severity,
                    String.format("Water walking (%s): vY:%.4f speed:%.3f VL:%d",
                        mode, velocityY, horizontalSpeed, violations)
                );

                if (filtering.passesLayer2HeuristicFiltering(player, ViolationType.JESUS, result)) {
                    return result;
                }
            }
        }

        return CheckResult.passed();
    }

    private enum WaterWalkingState {
        NOT_ABOVE_LIQUID,
        LEGITIMATELY_IN_LIQUID,
        WALKING_ON_LIQUID
    }

    private WaterWalkingState analyzeWaterWalkingState(Player player, Location from, Location to) {
        Block blockAtFeet = to.getBlock();
        Block blockBelow1 = to.clone().subtract(0, 0.1, 0).getBlock();
        Block blockBelow2 = to.clone().subtract(0, 0.5, 0).getBlock();
        Block blockBelow3 = to.clone().subtract(0, 1.0, 0).getBlock();

        if (isKelpOrSeagrass(blockAtFeet.getType()) || isKelpOrSeagrass(blockBelow1.getType())) {
            return WaterWalkingState.LEGITIMATELY_IN_LIQUID;
        }

        if (isOnWaterloggedSolid(to)) {
            return WaterWalkingState.NOT_ABOVE_LIQUID;
        }

        boolean liquidAtFeet = isLiquid(blockAtFeet);
        boolean liquidBelow1 = isLiquid(blockBelow1);
        boolean liquidBelow2 = isLiquid(blockBelow2);
        boolean liquidBelow3 = isLiquid(blockBelow3);

        if (!liquidAtFeet && !liquidBelow1 && !liquidBelow2 && !liquidBelow3) {
            return WaterWalkingState.NOT_ABOVE_LIQUID;
        }

        if (isOnDripleaf(to)) {
            return WaterWalkingState.NOT_ABOVE_LIQUID;
        }

        Block waistBlock = to.clone().add(0, 0.8, 0).getBlock();
        Block headBlock = to.clone().add(0, 1.5, 0).getBlock();
        Block chestBlock = to.clone().add(0, 1.2, 0).getBlock();

        if (isLiquid(waistBlock) || isLiquid(headBlock) || isLiquid(chestBlock)) {
            return WaterWalkingState.LEGITIMATELY_IN_LIQUID;
        }

        if (player.isSwimming()) {
            return WaterWalkingState.NOT_ABOVE_LIQUID;
        }

        if (player.isInWater()) {
            return WaterWalkingState.LEGITIMATELY_IN_LIQUID;
        }

        if (isOnSolidSurface(to)) {
            return WaterWalkingState.NOT_ABOVE_LIQUID;
        }

        double velocityY = to.getY() - from.getY();

        if (Math.abs(velocityY) > BOB_VELOCITY_THRESHOLD) {
            return WaterWalkingState.LEGITIMATELY_IN_LIQUID;
        }

        double playerFeetY = to.getY();

        Block waterBlock = null;

        if (liquidBelow1) {
            waterBlock = blockBelow1;
        } else if (liquidBelow2) {
            waterBlock = blockBelow2;
        } else if (liquidBelow3) {
            waterBlock = blockBelow3;
        } else if (liquidAtFeet) {
            waterBlock = blockAtFeet;
        }

        if (waterBlock == null) {
            return WaterWalkingState.NOT_ABOVE_LIQUID;
        }

        double waterSurfaceY = waterBlock.getY() + getWaterLevel(waterBlock);
        double distanceAboveSurface = playerFeetY - waterSurfaceY;

        if (liquidAtFeet) {
            return WaterWalkingState.LEGITIMATELY_IN_LIQUID;
        }

        if (distanceAboveSurface < -0.2) {
            return WaterWalkingState.LEGITIMATELY_IN_LIQUID;
        }

        if (distanceAboveSurface >= 0.0 && distanceAboveSurface <= WATER_SURFACE_TOLERANCE) {
            if (Math.abs(velocityY) < 0.12) {
                if (!hasSolidSupport(to)) {
                    return WaterWalkingState.WALKING_ON_LIQUID;
                }
            }
        }

        return WaterWalkingState.LEGITIMATELY_IN_LIQUID;
    }

    private double getWaterLevel(Block block) {
        if (block.getType() != Material.WATER) {
            if (block.getType() == Material.LAVA) {
                return 0.875;
            }
            return 0;
        }

        try {
            org.bukkit.block.data.BlockData blockData = block.getBlockData();
            if (blockData instanceof org.bukkit.block.data.Levelled) {
                int level = ((org.bukkit.block.data.Levelled) blockData).getLevel();
                if (level == 0) {
                    return 0.875;
                }
                return 0.875 - (level * 0.0625);
            }
        } catch (Exception e) {
            Logger.getLogger("NoChance").log(Level.WARNING, "Failed to get liquid level at " + block.getLocation(), e);
        }

        return 0.875;
    }

    private boolean hasSolidSupport(Location loc) {
        double halfWidth = 0.3;
        double[][] offsets = {
            {0, 0}, {-halfWidth, 0}, {halfWidth, 0}, {0, -halfWidth}, {0, halfWidth},
            {-halfWidth, -halfWidth}, {halfWidth, -halfWidth}, {-halfWidth, halfWidth}, {halfWidth, halfWidth}
        };

        for (double[] offset : offsets) {
            Location checkLoc = loc.clone().add(offset[0], -0.01, offset[1]);
            Block block = checkLoc.getBlock();
            Material type = block.getType();

            if (type.isSolid() && !isLiquid(block)) {
                return true;
            }

            if (type.isSolid() && isWaterlogged(block)) {
                return true;
            }

            if (isSurfaceBlock(block)) {
                return true;
            }

            if (type == Material.BIG_DRIPLEAF) {
                return true;
            }
        }

        return false;
    }

    private boolean isOnSolidSurface(Location loc) {
        Block below = loc.clone().subtract(0, 0.1, 0).getBlock();
        Material belowType = below.getType();

        if (belowType.isSolid() && !isLiquid(below)) {
            return true;
        }

        if (belowType.isSolid() && isWaterlogged(below)) {
            return true;
        }

        if (belowType == Material.BIG_DRIPLEAF) {
            return true;
        }

        if (isSurfaceBlock(below) || isSurfaceBlock(loc.getBlock())) {
            return true;
        }

        return false;
    }

    private boolean isSurfaceBlock(Block block) {
        Material type = block.getType();
        if (type == Material.LILY_PAD) return true;
        if (type.name().contains("CARPET")) return true;
        if (type.name().contains("PRESSURE_PLATE")) return true;
        if (type == Material.SNOW) return true;
        if (type == Material.BIG_DRIPLEAF) return true;
        if (type.name().equals("BIG_DRIPLEAF_STEM")) return true;
        return false;
    }

    private boolean shouldExempt(Player player, JesusTracker tracker) {
        EnhancementTracker.MovementEnhancements enhancements = EnhancementTracker.calculateMovementEnhancements(player);
        if (enhancements.hasLegitFlight) return true;

        if (player.isGliding()) return true;

        if (player.isInsideVehicle()) return true;

        if (hasFrostWalker(player)) return true;

        if (isNearBoat(player)) return true;

        if (player.isRiptiding()) return true;

        if (player.getGameMode() == org.bukkit.GameMode.SPECTATOR) return true;
        if (player.getGameMode() == org.bukkit.GameMode.CREATIVE && player.isFlying()) return true;

        PlayerData data = playerDataMap.get(player.getUniqueId());
        if (data != null) {
            if (data.isInTeleportGracePeriod(3)) return true;
            long now = System.currentTimeMillis();
            if (now - data.getLastVelocityTime() < 1000) return true;
            if (now - data.getLastDamageTime() < 500) return true;
        }

        if (movementGrace != null && movementGrace.inMountGrace(player.getUniqueId(), System.currentTimeMillis())) return true;

        long timeSinceWater = System.currentTimeMillis() - tracker.getLastInWater();
        if (timeSinceWater < EXEMPT_GRACE_MS) return true;

        if (isInBubbleColumn(player.getLocation())) return true;

        if (isOnDripleaf(player.getLocation())) return true;

        return false;
    }

    private boolean hasFrostWalker(Player player) {
        ItemStack boots = player.getInventory().getBoots();
        if (boots == null) return false;

        if (boots.containsEnchantment(Enchantment.FROST_WALKER)) return true;

        try {
            Enchantment frostWalker = Enchantment.getByName("FROST_WALKER");
            if (frostWalker != null && boots.containsEnchantment(frostWalker)) return true;
        } catch (Exception e) {
            Logger.getLogger("NoChance").log(Level.WARNING, "Failed to check FROST_WALKER enchantment", e);
        }

        Location belowLoc = player.getLocation().clone().subtract(0, 0.1, 0);
        Material belowType = belowLoc.getBlock().getType();
        if (belowType == Material.FROSTED_ICE) return true;

        return false;
    }

    private boolean isNearBoat(Player player) {
        for (Entity entity : player.getNearbyEntities(1.5, 1.5, 1.5)) {
            if (entity instanceof Boat) {
                return true;
            }
        }
        return false;
    }

    private boolean isLiquid(Block block) {
        Material type = block.getType();
        if (type == Material.WATER || type == Material.LAVA) return true;
        if (type.name().contains("WATER") || type.name().contains("LAVA")) return true;

        if (isKelpOrSeagrass(type)) return false;

        return isWaterlogged(block);
    }

    private boolean isKelpOrSeagrass(Material type) {
        return type == Material.KELP || type == Material.KELP_PLANT ||
               type == Material.SEAGRASS || type == Material.TALL_SEAGRASS;
    }

    private boolean isWaterlogged(Block block) {
        try {
            org.bukkit.block.data.BlockData blockData = block.getBlockData();
            if (blockData instanceof org.bukkit.block.data.Waterlogged) {
                return ((org.bukkit.block.data.Waterlogged) blockData).isWaterlogged();
            }
        } catch (Exception e) {
            Logger.getLogger("NoChance").log(Level.WARNING, "Failed to check waterlogged state at " + block.getLocation(), e);
        }
        return false;
    }

    private boolean isInBubbleColumn(Location loc) {
        Block block = loc.getBlock();
        Block below = loc.clone().subtract(0, 0.5, 0).getBlock();
        return block.getType() == Material.BUBBLE_COLUMN || below.getType() == Material.BUBBLE_COLUMN;
    }

    private boolean isOnDripleaf(Location loc) {
        for (int dy = 0; dy >= -2; dy--) {
            Block check = loc.clone().add(0, dy, 0).getBlock();
            Material type = check.getType();
            if (type == Material.BIG_DRIPLEAF || type.name().equals("BIG_DRIPLEAF_STEM")) {
                return true;
            }
        }
        return false;
    }

    private boolean isOnWaterloggedSolid(Location loc) {
        Block below1 = loc.clone().subtract(0, 0.1, 0).getBlock();
        if (below1.getType().isSolid() && isWaterlogged(below1)) return true;

        Block below2 = loc.clone().subtract(0, 0.5, 0).getBlock();
        if (below2.getType().isSolid() && isWaterlogged(below2)) return true;

        Block atFeet = loc.getBlock();
        if (atFeet.getType().isSolid() && isWaterlogged(atFeet)) return true;

        return false;
    }

    public void cleanup(UUID playerId) {
        trackers.remove(playerId);
    }

    private static class JesusTracker {
        private int violations = 0;
        private long lastViolation = 0;
        private long lastInWater = 0;
        private final double[] yPositions = new double[16];
        private final double[] yVelocities = new double[16];
        private final double[] horizontalSpeeds = new double[16];
        private int sampleIdx = 0;
        private int sampleCount = 0;
        private double avgVelocityY = 0;
        private int stablePositionCount = 0;
        private int consistentWalkingCount = 0;

        void setLastInWater(long time) {
            this.lastInWater = time;
        }

        long getLastInWater() {
            return lastInWater;
        }

        void recordSuspiciousPosition(double y, double velocityY, double horizontalSpeed) {
            long now = System.currentTimeMillis();

            yPositions[sampleIdx] = y;
            yVelocities[sampleIdx] = velocityY;
            horizontalSpeeds[sampleIdx] = horizontalSpeed;
            sampleIdx = (sampleIdx + 1) % yPositions.length;
            if (sampleCount < yPositions.length) sampleCount++;

            double sum = 0;
            for (int i = 0; i < sampleCount; i++) {
                sum += yVelocities[i];
            }
            avgVelocityY = sum / sampleCount;

            if (Math.abs(velocityY) < 0.025) {
                stablePositionCount++;
            } else {
                stablePositionCount = Math.max(0, stablePositionCount - 2);
            }

            if (horizontalSpeed >= MIN_HORIZONTAL_SPEED) {
                consistentWalkingCount++;
            } else {
                consistentWalkingCount = Math.max(0, consistentWalkingCount - 1);
            }

            if (now - lastViolation < 2000) {
                violations++;
            } else {
                violations = 1;
                stablePositionCount = 1;
                consistentWalkingCount = 1;
            }
            lastViolation = now;
        }

        boolean hasStablePattern() {
            if (sampleCount < 5) return false;

            double avgY = 0;
            for (int i = 0; i < sampleCount; i++) {
                avgY += yPositions[i];
            }
            avgY /= sampleCount;

            double yVariance = 0;
            for (int i = 0; i < sampleCount; i++) {
                yVariance += Math.pow(yPositions[i] - avgY, 2);
            }
            yVariance = Math.sqrt(yVariance / sampleCount);

            double velAvg = 0;
            for (int i = 0; i < sampleCount; i++) {
                velAvg += yVelocities[i];
            }
            velAvg /= sampleCount;

            double velVariance = 0;
            for (int i = 0; i < sampleCount; i++) {
                velVariance += Math.pow(yVelocities[i] - velAvg, 2);
            }
            velVariance = Math.sqrt(velVariance / sampleCount);

            boolean lowYVariance = yVariance < 0.05;
            boolean lowVelVariance = velVariance < 0.03;
            boolean isWalking = consistentWalkingCount >= 3;

            return lowYVariance && lowVelVariance && stablePositionCount >= 4 && isWalking;
        }

        String getPatternDesc() {
            if (sampleCount < 2) return "init";
            return String.format("stable:%d walk:%d avgVY:%.3f", stablePositionCount, consistentWalkingCount, avgVelocityY);
        }

        void decay() {
            long now = System.currentTimeMillis();
            if (now - lastViolation > 1800) {
                violations = Math.max(0, violations - 1);
                stablePositionCount = Math.max(0, stablePositionCount - 1);
                consistentWalkingCount = Math.max(0, consistentWalkingCount - 1);
            }
        }

        void reset() {
            violations = 0;
            stablePositionCount = 0;
            consistentWalkingCount = 0;
            sampleCount = 0;
            sampleIdx = 0;
        }

        int getViolationCount() {
            return violations;
        }
    }
}
