package NC.noChance.detection.movement;

import NC.noChance.core.*;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.util.BoundingBox;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class NoClipCheck {
    private final ACConfig config;
    private final Map<UUID, PlayerData> playerDataMap;
    private final LayerFiltering filtering;
    private final Map<UUID, ClipTracker> trackers;

    private static final double PLAYER_WIDTH = 0.6;
    private static final double PLAYER_HEIGHT = 1.8;
    private static final double HALF_WIDTH = PLAYER_WIDTH / 2.0;

    private MovementGrace movementGrace;

    public NoClipCheck(ACConfig config, Map<UUID, PlayerData> playerDataMap, LayerFiltering filtering) {
        this.config = config;
        this.playerDataMap = playerDataMap;
        this.filtering = filtering;
        this.trackers = new ConcurrentHashMap<>();
    }

    public void setMovementGrace(MovementGrace grace) {
        this.movementGrace = grace;
    }

    public CheckResult check(Player player, Location from, Location to) {
        if (!config.isCheckEnabled("noclip")) {
            return CheckResult.passed();
        }

        PlayerData data = playerDataMap.get(player.getUniqueId());
        if (data == null) return CheckResult.passed();

        if (data.isInGracePeriod(config.getGracePeriod())) {
            return CheckResult.passed();
        }

        if (player.getGameMode() == org.bukkit.GameMode.SPECTATOR) {
            return CheckResult.passed();
        }

        if (WaterHelper.isInWater(player) || player.isSwimming()) {
            return CheckResult.passed();
        }

        EnhancementTracker.MovementEnhancements enhancements = EnhancementTracker.calculateMovementEnhancements(player);
        if (enhancements.hasLegitFlight) {
            return CheckResult.passed();
        }

        if (player.getVehicle() != null) {
            return CheckResult.passed();
        }

        if (player.isGliding() || player.isRiptiding()) {
            return CheckResult.passed();
        }

        if (data.isInTeleportGracePeriod(3)) {
            return CheckResult.passed();
        }

        if (player.isClimbing()) {
            return CheckResult.passed();
        }

        if (movementGrace != null && movementGrace.inMountGrace(player.getUniqueId(), System.currentTimeMillis())) {
            return CheckResult.passed();
        }

        long now = System.currentTimeMillis();
        if (now - data.getLastVelocityTime() < 1000) {
            return CheckResult.passed();
        }

        if (now - data.getLastDamageTime() < 500) {
            return CheckResult.passed();
        }

        if (!isChunkLoaded(to)) {
            return CheckResult.passed();
        }

        int ping = filtering.getPing(player);
        if (ping > 400) ping = 400;

        UUID uuid = player.getUniqueId();
        ClipTracker tracker = trackers.computeIfAbsent(uuid, k -> new ClipTracker());
        tracker.updatePing(ping);

        World world = player.getWorld();
        double playerHeight = PLAYER_HEIGHT;
        if (player.isSneaking()) playerHeight = 1.5;
        if (player.isSwimming() || player.isGliding()) playerHeight = 0.6;
        BoundingBox playerBox = new BoundingBox(
            to.getX() - HALF_WIDTH, to.getY(), to.getZ() - HALF_WIDTH,
            to.getX() + HALF_WIDTH, to.getY() + playerHeight, to.getZ() + HALF_WIDTH
        );

        int solidCount = 0;
        double totalOverlap = 0.0;

        int minX = (int) Math.floor(playerBox.getMinX());
        int minY = (int) Math.floor(playerBox.getMinY());
        int minZ = (int) Math.floor(playerBox.getMinZ());
        int maxX = (int) Math.ceil(playerBox.getMaxX());
        int maxY = (int) Math.ceil(playerBox.getMaxY());
        int maxZ = (int) Math.ceil(playerBox.getMaxZ());

        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    Block block = world.getBlockAt(x, y, z);

                    if (isSolidClipBlock(block)) {
                        if (WaterHelper.isWaterBlock(block)) {
                            continue;
                        }

                        BoundingBox blockBox = block.getBoundingBox();

                        if (blockBox.getMax().equals(blockBox.getMin())) {
                            continue;
                        }

                        if (playerBox.overlaps(blockBox)) {
                            solidCount++;
                            double overlap = calculateOverlapVolume(playerBox, blockBox);
                            totalOverlap += overlap;
                        }
                    }
                }
            }
        }

        tracker.decay();

        int threshold = ping > 200 ? 4 : 3;
        double overlapThreshold = ping > 200 ? 0.08 : 0.06;

        if (solidCount > threshold || totalOverlap > overlapThreshold) {
            tracker.addViolation(solidCount, totalOverlap);

            int vlThreshold = ping > 200 ? 8 : 7;

            if (tracker.getViolationCount() >= vlThreshold) {
                double severity = Math.min(1.0, Math.max(solidCount / 8.0, totalOverlap * 10));

                if (tracker.getPeakOverlap() > 0.15) {
                    severity = Math.min(1.0, severity + tracker.getPeakOverlap() * 2.0);
                }
                if (tracker.getPeakSolidCount() > 5) {
                    severity = Math.min(1.0, severity + (tracker.getPeakSolidCount() - 5) * 0.05);
                }
                severity = Math.max(0.0, severity);

                CheckResult prelimResult = CheckResult.failed(
                    ViolationType.NOCLIP,
                    severity,
                    String.format("NoClip: %d solids | Overlap: %.3f | Peak: %.3f/%d | VL: %d",
                        solidCount, totalOverlap, tracker.getPeakOverlap(), tracker.getPeakSolidCount(), tracker.getViolationCount())
                );

                if (!filtering.passesLayer2HeuristicFiltering(player, ViolationType.NOCLIP, prelimResult)) {
                    return CheckResult.passed();
                }

                tracker.reset();
                return prelimResult;
            }
        }

        CheckResult vertResult = checkVerticalClip(player, from, to, tracker, world, ping);
        if (vertResult.isFailed()) {
            return vertResult;
        }

        return CheckResult.passed();
    }

    private CheckResult checkVerticalClip(Player player, Location from, Location to, ClipTracker tracker, World world, int ping) {
        double dy = to.getY() - from.getY();

        if (Math.abs(dy) < 0.5) {
            return CheckResult.passed();
        }

        if (dy > 0 && dy < 1.0 && player.isOnGround()) {
            return CheckResult.passed();
        }

        int maxSteps = Math.abs(dy) > 1.5 ? 32 : 16;
        int steps = (int) Math.ceil(Math.abs(dy) / 0.3);
        steps = Math.min(steps, maxSteps);

        java.util.Set<Long> penetrated = new java.util.HashSet<>();

        for (int i = 1; i <= steps; i++) {
            double ratio = (double) i / steps;
            double checkY = from.getY() + dy * ratio;

            BoundingBox checkBox = new BoundingBox(
                to.getX() - HALF_WIDTH, checkY, to.getZ() - HALF_WIDTH,
                to.getX() + HALF_WIDTH, checkY + PLAYER_HEIGHT, to.getZ() + HALF_WIDTH
            );

            int minX = (int) Math.floor(checkBox.getMinX());
            int minY = (int) Math.floor(checkBox.getMinY());
            int minZ = (int) Math.floor(checkBox.getMinZ());
            int maxX = (int) Math.ceil(checkBox.getMaxX());
            int maxY = (int) Math.ceil(checkBox.getMaxY());
            int maxZ = (int) Math.ceil(checkBox.getMaxZ());

            for (int x = minX; x <= maxX; x++) {
                for (int y = minY; y <= maxY; y++) {
                    for (int z = minZ; z <= maxZ; z++) {
                        Block block = world.getBlockAt(x, y, z);
                        if (isSolidClipBlock(block)) {
                            if (WaterHelper.isWaterBlock(block)) {
                                continue;
                            }
                            BoundingBox blockBox = block.getBoundingBox();
                            if (!blockBox.getMax().equals(blockBox.getMin()) && checkBox.overlaps(blockBox)) {
                                long key = ((long)(x & 0x3FFFFFF) << 38) | ((long)(y & 0xFFF) << 26) | (long)(z & 0x3FFFFFF);
                                penetrated.add(key);
                            }
                        }
                    }
                }
            }
        }

        int clipsFound = penetrated.size();

        int clipThreshold = ping > 200 ? 4 : 3;

        if (clipsFound >= clipThreshold) {
            tracker.addVerticalViolation(clipsFound);

            int vlThreshold = ping > 200 ? 8 : 7;

            if (tracker.getVerticalViolationCount() >= vlThreshold) {
                double severity = Math.min(1.0, clipsFound / 6.0);

                CheckResult prelimResult = CheckResult.failed(
                    ViolationType.NOCLIP,
                    severity,
                    String.format("Vertical noclip: %d clips | VL: %d",
                        clipsFound, tracker.getVerticalViolationCount())
                );

                if (!filtering.passesLayer2HeuristicFiltering(player, ViolationType.NOCLIP, prelimResult)) {
                    return CheckResult.passed();
                }

                tracker.reset();
                return prelimResult;
            }
        }

        return CheckResult.passed();
    }

    private BoundingBox getPlayerBoundingBox(Location loc) {
        return new BoundingBox(
            loc.getX() - HALF_WIDTH, loc.getY(), loc.getZ() - HALF_WIDTH,
            loc.getX() + HALF_WIDTH, loc.getY() + PLAYER_HEIGHT, loc.getZ() + HALF_WIDTH
        );
    }

    private double calculateOverlapVolume(BoundingBox a, BoundingBox b) {
        double overlapX = Math.max(0, Math.min(a.getMaxX(), b.getMaxX()) - Math.max(a.getMinX(), b.getMinX()));
        double overlapY = Math.max(0, Math.min(a.getMaxY(), b.getMaxY()) - Math.max(a.getMinY(), b.getMinY()));
        double overlapZ = Math.max(0, Math.min(a.getMaxZ(), b.getMaxZ()) - Math.max(a.getMinZ(), b.getMinZ()));
        return overlapX * overlapY * overlapZ;
    }

    private boolean isSolidClipBlock(Block block) {
        Material type = block.getType();
        if (!type.isSolid()) return false;

        String name = type.name();

        if (type == Material.LADDER) return false;
        if (type == Material.VINE) return false;
        if (type == Material.SCAFFOLDING) return false;
        if (type == Material.BELL) return false;
        if (type == Material.COBWEB) return false;
        if (type == Material.HONEY_BLOCK) return false;
        if (type == Material.SLIME_BLOCK) return false;
        if (type == Material.SOUL_SAND) return false;
        if (type == Material.SOUL_SOIL) return false;
        if (type == Material.POWDER_SNOW) return false;
        if (type == Material.SWEET_BERRY_BUSH) return false;
        if (type == Material.SNOW) return false;
        if (type == Material.POINTED_DRIPSTONE) return false;

        if (name.contains("SLAB")) return false;
        if (name.contains("TRAPDOOR")) return false;
        if (name.contains("DOOR")) return false;
        if (name.contains("FENCE_GATE")) return false;
        if (name.contains("CARPET")) return false;
        if (name.contains("PRESSURE_PLATE")) return false;
        if (name.contains("SIGN")) return false;
        if (name.contains("BANNER")) return false;
        if (name.contains("LANTERN")) return false;
        if (name.contains("CORAL")) return false;
        if (name.contains("CANDLE")) return false;
        if (name.contains("BUTTON")) return false;
        if (name.contains("LEVER")) return false;
        if (name.contains("TRIPWIRE")) return false;
        if (name.contains("POT")) return false;
        if (name.contains("HEAD")) return false;
        if (name.contains("SKULL")) return false;
        if (name.contains("TORCH")) return false;
        if (name.contains("CHAIN")) return false;
        if (name.contains("END_ROD")) return false;
        if (name.contains("LIGHTNING_ROD")) return false;
        if (name.contains("AMETHYST")) return false;
        if (name.contains("RAIL")) return false;
        if (name.contains("BED") && !name.contains("BEDROCK")) return false;
        if (name.contains("PISTON")) return false;
        if (name.contains("REDSTONE")) return false;
        if (name.contains("REPEATER")) return false;
        if (name.contains("COMPARATOR")) return false;

        return true;
    }

    private boolean isChunkLoaded(Location loc) {
        return loc.getWorld().isChunkLoaded(loc.getBlockX() >> 4, loc.getBlockZ() >> 4);
    }

    public void cleanup(UUID playerId) {
        trackers.remove(playerId);
    }

    private static class ClipTracker {
        private int violations = 0;
        private int vertViolations = 0;
        private long lastViolation = 0;
        private int avgPing = 50;
        private double peakOverlap = 0;
        private int peakSolidCount = 0;

        void addViolation(int solidCount, double overlap) {
            violations++;
            lastViolation = System.currentTimeMillis();
            if (overlap > peakOverlap) peakOverlap = overlap;
            if (solidCount > peakSolidCount) peakSolidCount = solidCount;
        }

        void addVerticalViolation(int clips) {
            vertViolations++;
            lastViolation = System.currentTimeMillis();
        }

        void updatePing(int ping) {
            avgPing = (avgPing * 3 + ping) / 4;
        }

        void decay() {
            long now = System.currentTimeMillis();
            int decayTime = avgPing > 150 ? 2000 : 1500;

            if (now - lastViolation > decayTime) {
                if (violations > 0) violations--;
                if (vertViolations > 0) vertViolations--;
                if (peakOverlap > 0) peakOverlap *= 0.85;
                if (peakOverlap < 0.02) peakOverlap = 0;
                if (peakSolidCount > 0) peakSolidCount--;
                lastViolation = now;
            }
        }

        void reset() {
            violations = 0;
            vertViolations = 0;
            peakOverlap = 0;
            peakSolidCount = 0;
        }

        int getViolationCount() {
            return violations;
        }

        int getVerticalViolationCount() {
            return vertViolations;
        }

        double getPeakOverlap() {
            return peakOverlap;
        }

        int getPeakSolidCount() {
            return peakSolidCount;
        }
    }
}
