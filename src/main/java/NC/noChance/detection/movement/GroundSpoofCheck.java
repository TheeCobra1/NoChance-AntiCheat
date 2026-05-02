package NC.noChance.detection.movement;

import NC.noChance.core.*;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffectType;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

public class GroundSpoofCheck {
    private final ACConfig config;
    private final Map<UUID, PlayerData> playerDataMap;
    private final LayerFiltering filtering;
    private final Map<UUID, SpoofData> spoofDataMap;

    private static final double PLAYER_WIDTH = 0.6;
    private static final double GROUND_CHECK_DEPTH = 0.20;
    private static final double BASE_DISTANCE_THRESHOLD = 1.4;
    private static final double NOFALL_DISTANCE_THRESHOLD = 1.8;
    private static final int SPOOF_VL_REQUIRED = 6;
    private static final int NOFALL_VL_REQUIRED = 6;
    private static final long TELEPORT_GRACE_MS = 3000;
    private static final long VELOCITY_GRACE_MS = 1500;

    private MovementGrace movementGrace;

    public GroundSpoofCheck(ACConfig config, Map<UUID, PlayerData> playerDataMap, LayerFiltering filtering) {
        this.config = config;
        this.playerDataMap = playerDataMap;
        this.filtering = filtering;
        this.spoofDataMap = new ConcurrentHashMap<>();
    }

    public void setMovementGrace(MovementGrace grace) {
        this.movementGrace = grace;
    }

    public CheckResult check(Player player, Location from, Location to, boolean clientGround) {
        if (!config.isCheckEnabled("groundspoof")) {
            return CheckResult.passed();
        }

        PlayerData data = playerDataMap.get(player.getUniqueId());
        if (data == null) return CheckResult.passed();

        if (data.isInGracePeriod(config.getGracePeriod())) {
            return CheckResult.passed();
        }

        if (player.isFlying() || player.getGameMode() == org.bukkit.GameMode.CREATIVE || player.getGameMode() == org.bukkit.GameMode.SPECTATOR) return CheckResult.passed();
        if (data.flyDisableGraceTicks > 0) return CheckResult.passed();

        if (shouldExempt(player)) {
            return CheckResult.passed();
        }

        UUID uuid = player.getUniqueId();
        SpoofData spoofData = spoofDataMap.computeIfAbsent(uuid, k -> new SpoofData());

        int ping = filtering.getPing(player);
        boolean serverGround = isActuallyOnGround(player, to);
        double deltaY = to.getY() - from.getY();

        spoofData.recordMove(clientGround, serverGround, deltaY);

        if (clientGround && !serverGround) {
            return checkFalseGround(player, to, deltaY, spoofData, ping);
        }

        if (!clientGround && serverGround) {
            return checkFalseAir(player, to, deltaY, spoofData);
        }

        spoofData.decay();
        return CheckResult.passed();
    }

    private boolean shouldExempt(Player player) {
        if (WaterHelper.isInWater(player) || player.isSwimming()) return true;
        if (player.isClimbing() || player.isGliding() || player.isRiptiding()) return true;
        if (player.isFlying() && player.getAllowFlight()) return true;
        if (player.getVehicle() != null) return true;
        if (player.hasPotionEffect(PotionEffectType.SLOW_FALLING)) return true;
        if (player.hasPotionEffect(PotionEffectType.LEVITATION)) return true;
        if (player.getGameMode() == org.bukkit.GameMode.SPECTATOR) return true;
        if (player.getGameMode() == org.bukkit.GameMode.CREATIVE && player.isFlying()) return true;
        if (BedrockHelper.isBedrockPlayer(player)) return true;
        if (isInSpecialBlock(player)) return true;

        Location loc = player.getLocation();
        Material blockBelow = loc.clone().subtract(0, 0.2, 0).getBlock().getType();
        if (blockBelow == Material.SOUL_SAND || blockBelow == Material.SOUL_SOIL) return true;

        if (isOnWaterSurface(player)) return true;
        if (isNearClimbable(player)) return true;

        PlayerData data = playerDataMap.get(player.getUniqueId());
        if (data != null) {
            long now = System.currentTimeMillis();
            long timeSinceWater = now - data.getLastWaterTime();
            if (timeSinceWater < 3000) return true;

            long timeSinceTeleport = now - data.getLastTeleportTime();
            if (timeSinceTeleport < TELEPORT_GRACE_MS) return true;

            long timeSinceVelocity = now - data.getLastVelocityTime();
            if (timeSinceVelocity < VELOCITY_GRACE_MS) return true;

            long timeSinceDamage = now - data.getLastDamageTime();
            if (timeSinceDamage < 1000) return true;
        }

        if (movementGrace != null && movementGrace.inMountGrace(player.getUniqueId(), System.currentTimeMillis())) return true;

        return false;
    }

    private boolean isNearClimbable(Player player) {
        Location loc = player.getLocation();
        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) {
                Block block = loc.clone().add(x, 0, z).getBlock();
                Material type = block.getType();
                if (type == Material.LADDER || type == Material.VINE ||
                    type.name().contains("CAVE_VINES") || type == Material.SCAFFOLDING ||
                    type == Material.TWISTING_VINES || type == Material.WEEPING_VINES) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean isOnWaterSurface(Player player) {
        Location loc = player.getLocation();
        Block feetBlock = loc.getBlock();
        Block belowFeet = loc.clone().subtract(0, 0.3, 0).getBlock();

        boolean hasWaterBelow = belowFeet.getType() == Material.WATER ||
                                feetBlock.getType() == Material.WATER ||
                                belowFeet.getType() == Material.LAVA ||
                                feetBlock.getType() == Material.LAVA;

        if (!hasWaterBelow) return false;

        Block kneeBlock = loc.clone().add(0, 0.5, 0).getBlock();
        boolean submerged = kneeBlock.getType() == Material.WATER ||
                           kneeBlock.getType() == Material.LAVA;

        return !submerged && !player.isInWater() && !player.isSwimming();
    }

    private boolean isInSpecialBlock(Player player) {
        Location loc = player.getLocation();
        Material at = loc.getBlock().getType();
        Material below = loc.clone().subtract(0, 0.5, 0).getBlock().getType();
        Material feet = loc.clone().subtract(0, 0.1, 0).getBlock().getType();

        if (at == Material.COBWEB || at == Material.POWDER_SNOW) return true;
        if (at == Material.SWEET_BERRY_BUSH) return true;
        if (at.name().contains("SCAFFOLDING")) return true;
        if (below == Material.SLIME_BLOCK || below == Material.HONEY_BLOCK) return true;
        if (below.name().contains("BED")) return true;
        if (below == Material.BUBBLE_COLUMN) return true;

        if (at == Material.WATER || at == Material.LAVA) return true;
        if (below == Material.WATER || below == Material.LAVA) return true;
        if (feet == Material.WATER || feet == Material.LAVA) return true;
        if (at.name().contains("WATER") || at.name().contains("LAVA")) return true;

        if (feet == Material.LILY_PAD) return true;

        return false;
    }

    private CheckResult checkFalseGround(Player player, Location to, double deltaY, SpoofData spoofData, int ping) {
        double distanceToGround = getDistanceToGround(to);

        double pingTolerance = Math.min(1.0, Math.max(1, ping) / 150.0);
        double tps = TPSCache.getTPS();
        double tpsTolerance = tps < 19.0 ? Math.min(1.5, (20.0 - tps) * 0.4) : 0.0;
        pingTolerance += tpsTolerance;
        if (pingTolerance > 0.6) pingTolerance = 0.6;
        double jumpBoostTolerance = 0.0;
        if (player.hasPotionEffect(PotionEffectType.JUMP_BOOST) && player.getFallDistance() == 0.0f) {
            int level = player.getPotionEffect(PotionEffectType.JUMP_BOOST).getAmplifier() + 1;
            jumpBoostTolerance = Math.min(1.0, level * 0.3);
        }
        double adjustedBaseThreshold = BASE_DISTANCE_THRESHOLD + pingTolerance + jumpBoostTolerance;
        double adjustedNoFallThreshold = NOFALL_DISTANCE_THRESHOLD + pingTolerance + jumpBoostTolerance;

        if (deltaY > 0.0 && deltaY < 0.08) {
            return CheckResult.passed();
        }

        if (isOnDescendingTerrain(to) && distanceToGround < 1.5) {
            return CheckResult.passed();
        }

        boolean sustainedFall = !spoofData.wasLastServerGround() && spoofData.getLastDeltaY() < -0.1;
        boolean confirmedAirborne = !spoofData.wasLastServerGround() && !spoofData.wasLastClientGround();

        if (distanceToGround > adjustedNoFallThreshold && deltaY < -0.4 && sustainedFall) {
            spoofData.incrementFalseGroundVL();

            if (spoofData.getFalseGroundVL() >= NOFALL_VL_REQUIRED) {
                double severity = Math.min(0.95, 0.75 + (distanceToGround * 0.02));

                severity = Math.min(0.98, severity + 0.04);

                CheckResult result = CheckResult.failed(
                    ViolationType.NOFALL,
                    severity,
                    String.format("NoFall: ground while %.1fb in air, dY=%.2f", distanceToGround, deltaY)
                );

                if (filtering.passesLayer2HeuristicFiltering(player, ViolationType.NOFALL, result)) {
                    spoofData.resetFalseGround();
                    return result;
                }
            }
        }

        if (distanceToGround > adjustedBaseThreshold && deltaY < -0.15 && spoofData.hasRepeatedMismatch()) {
            spoofData.incrementSpoofVL();

            if (spoofData.getSpoofVL() >= SPOOF_VL_REQUIRED) {
                double severity = Math.min(0.90, 0.60 + (distanceToGround * 0.05) + (spoofData.getSpoofVL() * 0.01));

                if (confirmedAirborne && sustainedFall) {
                    severity = Math.min(0.95, severity + 0.06);
                }

                CheckResult result = CheckResult.failed(
                    ViolationType.GROUNDSPOOF,
                    severity,
                    String.format("FalseGround: dist=%.2f vl=%d", distanceToGround, spoofData.getSpoofVL())
                );

                if (filtering.passesLayer2HeuristicFiltering(player, ViolationType.GROUNDSPOOF, result)) {
                    return result;
                }
            }
        }

        return CheckResult.passed();
    }

    private boolean isOnDescendingTerrain(Location loc) {
        double halfWidth = PLAYER_WIDTH / 2.0;
        double[][] outer = {
            {halfWidth + 0.1, 0}, {-halfWidth - 0.1, 0}, {0, halfWidth + 0.1}, {0, -halfWidth - 0.1}
        };

        int blocksBelow = 0;
        for (double[] offset : outer) {
            for (double dy = -0.3; dy >= -1.5; dy -= 0.3) {
                Block block = loc.clone().add(offset[0], dy, offset[1]).getBlock();
                Material type = block.getType();
                if (type.isSolid() || type.name().contains("STAIR") || type.name().contains("SLAB")) {
                    blocksBelow++;
                    break;
                }
            }
        }

        return blocksBelow >= 2;
    }

    private CheckResult checkFalseAir(Player player, Location to, double deltaY, SpoofData spoofData) {
        if (deltaY > 0.08 && deltaY < 0.6) {
            return CheckResult.passed();
        }

        if (player.hasPotionEffect(PotionEffectType.JUMP_BOOST)) {
            return CheckResult.passed();
        }

        Block below = to.clone().subtract(0, 0.2, 0).getBlock();
        Material belowType = below.getType();

        if (belowType == Material.SLIME_BLOCK || belowType == Material.HONEY_BLOCK) {
            return CheckResult.passed();
        }
        if (belowType.name().contains("BED")) {
            return CheckResult.passed();
        }
        if (belowType.name().contains("PISTON")) {
            return CheckResult.passed();
        }

        if (deltaY > 0.8) {
            spoofData.incrementFalseAirVL();

            if (spoofData.getFalseAirVL() >= 4) {
                CheckResult result = CheckResult.failed(
                    ViolationType.GROUNDSPOOF,
                    0.65,
                    String.format("FalseAir: on ground but claims air, dY=%.3f vl=%d", deltaY, spoofData.getFalseAirVL())
                );

                if (filtering.passesLayer2HeuristicFiltering(player, ViolationType.GROUNDSPOOF, result)) {
                    spoofData.resetFalseAir();
                    return result;
                }
            }
        }

        return CheckResult.passed();
    }

    private boolean isActuallyOnGround(Player player, Location loc) {
        double halfWidth = PLAYER_WIDTH / 2.0 - 0.01;

        double[][] offsets = {
            {0, 0}, {-halfWidth, 0}, {halfWidth, 0}, {0, -halfWidth}, {0, halfWidth},
            {-halfWidth, -halfWidth}, {halfWidth, -halfWidth}, {-halfWidth, halfWidth}, {halfWidth, halfWidth}
        };

        for (double[] offset : offsets) {
            Location checkLoc = loc.clone().add(offset[0], -GROUND_CHECK_DEPTH, offset[1]);
            Block block = checkLoc.getBlock();

            if (isSupportingBlock(block, loc.getY())) {
                return true;
            }

            Block belowBlock = checkLoc.clone().subtract(0, 0.4, 0).getBlock();
            if (isPartialSupportBlock(belowBlock, loc.getY())) {
                return true;
            }
        }

        return false;
    }

    private boolean isSupportingBlock(Block block, double playerY) {
        Material type = block.getType();

        if (type.isSolid()) {
            return true;
        }

        if (type == Material.SCAFFOLDING) return true;
        if (type == Material.COBWEB) return false;
        if (type == Material.POWDER_SNOW) return true;
        if (type == Material.LILY_PAD) return true;
        if (type.name().contains("CARPET")) return true;
        if (type == Material.SNOW) {
            try {
                org.bukkit.block.data.type.Snow snowData = (org.bukkit.block.data.type.Snow) block.getBlockData();
                if (snowData.getLayers() >= 1) return true;
            } catch (Exception e) {
                Logger.getLogger("NoChance").log(Level.WARNING, "Failed to read snow layer data at " + block.getLocation(), e);
                return true;
            }
        }

        return false;
    }

    private boolean isPartialSupportBlock(Block block, double playerY) {
        Material type = block.getType();

        if (type.name().contains("FENCE") && !type.name().contains("GATE")) return true;
        if (type.name().contains("WALL")) return true;
        if (type == Material.CHAIN) return true;
        if (type.name().contains("SLAB")) {
            double blockTop = block.getY() + 0.5;
            if (type.name().contains("TOP") || block.getBlockData().getAsString().contains("top")) {
                blockTop = block.getY() + 1.0;
            }
            return Math.abs(playerY - blockTop) < 0.35;
        }
        if (type.name().contains("STAIR")) return true;
        if (type.name().contains("BED")) return true;
        if (type.name().contains("SKULL") || type.name().contains("HEAD")) return true;
        if (type == Material.FLOWER_POT || type.name().contains("POTTED")) return true;
        if (type == Material.BREWING_STAND) return true;
        if (type == Material.ENCHANTING_TABLE) return true;
        if (type.name().contains("CANDLE")) return true;
        if (type == Material.END_ROD) return true;
        if (type.name().contains("LANTERN")) return true;
        if (type.name().contains("CAMPFIRE")) return true;
        if (type.name().contains("ANVIL")) return true;
        if (type == Material.LECTERN) return true;
        if (type == Material.GRINDSTONE) return true;
        if (type == Material.STONECUTTER) return true;
        if (type == Material.BELL) return true;
        if (type == Material.HOPPER) return true;
        if (type == Material.CAULDRON || type.name().contains("CAULDRON")) return true;
        if (type == Material.COMPOSTER) return true;
        if (type == Material.BEACON) return true;
        if (type == Material.CONDUIT) return true;
        if (type == Material.DAYLIGHT_DETECTOR) return true;
        if (type == Material.TRIPWIRE_HOOK) return true;
        if (type.name().contains("TRAPDOOR")) return true;
        if (type.name().contains("DOOR") && !type.name().contains("TRAP")) return true;

        return false;
    }

    private double getDistanceToGround(Location loc) {
        double halfWidth = PLAYER_WIDTH / 2.0 - 0.02;
        double minDistance = 10.0;

        double[][] offsets = {
            {0, 0}, {-halfWidth, 0}, {halfWidth, 0}, {0, -halfWidth}, {0, halfWidth},
            {-1.0, 0}, {1.0, 0}, {0, -1.0}, {0, 1.0}
        };

        for (double[] offset : offsets) {
            for (int step = 0; step <= 60; step++) {
                double y = step * 0.1;
                Location checkLoc = loc.clone().add(offset[0], -y, offset[1]);
                Block block = checkLoc.getBlock();

                if (block.getType().isSolid() || isSupportingBlock(block, loc.getY() - y)) {
                    if (y < minDistance) {
                        minDistance = y;
                    }
                    break;
                }
            }
        }

        return minDistance;
    }

    public void cleanup(UUID playerId) {
        spoofDataMap.remove(playerId);
    }

    public void cleanupStale() {
        long now = System.currentTimeMillis();
        spoofDataMap.entrySet().removeIf(entry -> {
            PlayerData data = playerDataMap.get(entry.getKey());
            return data == null || (now - data.getLastMoveTime()) > 300000;
        });
    }

    private static class SpoofData {
        private int spoofVL;
        private int falseGroundVL;
        private int falseAirVL;
        private long lastViolation;
        private boolean lastClientGround;
        private boolean lastServerGround;
        private double lastDeltaY;
        private int consecutiveMismatches;

        void recordMove(boolean clientGround, boolean serverGround, double deltaY) {
            boolean wasMismatch = lastClientGround != lastServerGround;
            boolean isMismatch = clientGround != serverGround;
            if (wasMismatch && isMismatch && lastDeltaY < -0.1 && deltaY < -0.1) {
                consecutiveMismatches++;
            } else if (!isMismatch) {
                consecutiveMismatches = Math.max(0, consecutiveMismatches - 1);
            }
            lastClientGround = clientGround;
            lastServerGround = serverGround;
            lastDeltaY = deltaY;
        }

        boolean hasRepeatedMismatch() {
            return consecutiveMismatches >= 3;
        }

        void incrementSpoofVL() {
            spoofVL++;
            lastViolation = System.currentTimeMillis();
        }

        void incrementFalseGroundVL() {
            falseGroundVL++;
            lastViolation = System.currentTimeMillis();
        }

        void incrementFalseAirVL() {
            falseAirVL++;
            lastViolation = System.currentTimeMillis();
        }

        void resetFalseGround() {
            falseGroundVL = 0;
        }

        void resetFalseAir() {
            falseAirVL = 0;
        }

        void decay() {
            long now = System.currentTimeMillis();
            if (now - lastViolation > 1500) {
                spoofVL = Math.max(0, spoofVL - 1);
                falseGroundVL = Math.max(0, falseGroundVL - 1);
                falseAirVL = Math.max(0, falseAirVL - 1);
            }
        }

        int getSpoofVL() {
            return spoofVL;
        }

        int getFalseGroundVL() {
            return falseGroundVL;
        }

        int getFalseAirVL() {
            return falseAirVL;
        }

        boolean wasLastClientGround() {
            return lastClientGround;
        }

        boolean wasLastServerGround() {
            return lastServerGround;
        }

        double getLastDeltaY() {
            return lastDeltaY;
        }
    }
}
