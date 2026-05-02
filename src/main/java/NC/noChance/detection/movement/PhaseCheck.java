package NC.noChance.detection.movement;

import NC.noChance.core.*;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.util.BoundingBox;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class PhaseCheck {
    private final ACConfig config;
    private final Map<UUID, PlayerData> playerDataMap;
    private final LayerFiltering filtering;
    private final Map<UUID, PhaseTracker> trackers;

    private static final double PLAYER_WIDTH = 0.6;
    private static final double PLAYER_HEIGHT = 1.8;
    private static final double PLAYER_HEIGHT_SNEAKING = 1.5;
    private static final double PLAYER_HEIGHT_SWIMMING = 0.6;
    private static final double HALF_WIDTH = PLAYER_WIDTH / 2.0;

    private MovementGrace movementGrace;

    public PhaseCheck(ACConfig config, Map<UUID, PlayerData> playerDataMap, LayerFiltering filtering) {
        this.config = config;
        this.playerDataMap = playerDataMap;
        this.filtering = filtering;
        this.trackers = new ConcurrentHashMap<>();
    }

    public void setMovementGrace(MovementGrace grace) {
        this.movementGrace = grace;
    }

    public CheckResult check(Player player, Location from, Location to) {
        if (!config.isCheckEnabled("phase")) {
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

        if (player.isFlying() && player.getAllowFlight()) {
            return CheckResult.passed();
        }

        if (player.getVehicle() != null) {
            return CheckResult.passed();
        }

        if (WaterHelper.isInWater(player) || player.isSwimming()) {
            return CheckResult.passed();
        }

        if (player.isGliding() || player.isRiptiding()) {
            return CheckResult.passed();
        }

        if (player.isClimbing()) {
            return CheckResult.passed();
        }

        if (data.isInTeleportGracePeriod(3)) {
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
        if (ping > 350) ping = 350;

        UUID uuid = player.getUniqueId();
        PhaseTracker tracker = trackers.computeIfAbsent(uuid, k -> new PhaseTracker());

        if (System.currentTimeMillis() - tracker.lastRecordTime < 100) {
            return CheckResult.passed();
        }

        tracker.updatePing(ping);

        double playerHeight = getPlayerHeight(player);
        boolean inSolidNow = isInsideSolidBlock(to, player.getWorld(), playerHeight);
        boolean wasInSolid = isInsideSolidBlock(from, player.getWorld(), playerHeight);

        if (inSolidNow && !wasInSolid) {
            tracker.recordPhaseEntry(to);

            int threshold = ping > 200 ? 9 : 8;

            if (tracker.getEntryCount() >= threshold) {
                double severity = Math.max(0.0, Math.min(1.0, tracker.getEntryCount() / 5.0));

                CheckResult prelimResult = CheckResult.failed(
                    ViolationType.PHASE,
                    severity,
                    String.format("Phase entry detected (%d violations)", tracker.getEntryCount())
                );

                if (!filtering.passesLayer2HeuristicFiltering(player, ViolationType.PHASE, prelimResult)) {
                    tracker.decay();
                    return CheckResult.passed();
                }

                tracker.reset();
                return prelimResult;
            }
        }

        if (inSolidNow) {
            tracker.addTickInSolid();

            int tickThreshold = ping > 200 ? 21 : 13;

            if (tracker.getTicksInSolid() >= tickThreshold && System.currentTimeMillis() - tracker.getSolidStartTime() > 500) {
                double severity = Math.min(1.0, tracker.getTicksInSolid() / 10.0);

                CheckResult prelimResult = CheckResult.failed(
                    ViolationType.PHASE,
                    severity,
                    String.format("Extended phase duration (%d ticks inside solid)", tracker.getTicksInSolid())
                );

                if (!filtering.passesLayer2HeuristicFiltering(player, ViolationType.PHASE, prelimResult)) {
                    tracker.decay();
                    return CheckResult.passed();
                }

                tracker.reset();
                return prelimResult;
            }
        } else {
            tracker.resetTicksInSolid();
        }

        CheckResult pathResult = checkPhasePath(player, from, to, tracker, ping);
        if (pathResult.isFailed()) {
            return pathResult;
        }

        tracker.decay();
        return CheckResult.passed();
    }

    private double getPlayerHeight(Player player) {
        if (player.isSwimming() || player.isGliding()) {
            return PLAYER_HEIGHT_SWIMMING;
        }
        if (player.isSneaking()) {
            return PLAYER_HEIGHT_SNEAKING;
        }
        return PLAYER_HEIGHT;
    }

    private CheckResult checkPhasePath(Player player, Location from, Location to, PhaseTracker tracker, int ping) {
        double dx = to.getX() - from.getX();
        double dy = to.getY() - from.getY();
        double dz = to.getZ() - from.getZ();

        double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (distance < 0.1) return CheckResult.passed();

        int maxSteps = distance > 4.0 ? 32 : 16;
        int steps = (int) Math.ceil(distance / 0.25);
        steps = Math.min(steps, maxSteps);

        World world = from.getWorld();
        double playerHeight = getPlayerHeight(player);
        int solidPenetrations = 0;
        double penetrationDepth = 0;

        for (int i = 1; i <= steps; i++) {
            double ratio = (double) i / steps;
            double checkX = from.getX() + dx * ratio;
            double checkY = from.getY() + dy * ratio;
            double checkZ = from.getZ() + dz * ratio;

            if (isPointInsideSolid(checkX, checkY, checkZ, world, playerHeight)) {
                solidPenetrations++;
                penetrationDepth = Math.max(penetrationDepth, ratio);
            }
        }

        if (solidPenetrations >= 2) {
            tracker.recordPathPhase();

            int threshold = ping > 200 ? 13 : 11;

            if (tracker.getPathCount() >= threshold) {
                double severity = Math.min(1.0, (tracker.getPathCount() + solidPenetrations) / 8.0);

                CheckResult prelimResult = CheckResult.failed(
                    ViolationType.PHASE,
                    severity,
                    String.format("Phase through blocks (%d solid hits, depth %.2f)",
                        solidPenetrations, penetrationDepth)
                );

                if (!filtering.passesLayer2HeuristicFiltering(player, ViolationType.PHASE, prelimResult)) {
                    tracker.decay();
                    return CheckResult.passed();
                }

                tracker.reset();
                return prelimResult;
            }
        }

        return CheckResult.passed();
    }

    private boolean isInsideSolidBlock(Location loc, World world, double playerHeight) {
        double x = loc.getX();
        double y = loc.getY();
        double z = loc.getZ();

        BoundingBox playerBox = new BoundingBox(
            x - HALF_WIDTH, y, z - HALF_WIDTH,
            x + HALF_WIDTH, y + playerHeight, z + HALF_WIDTH
        );

        int minX = (int) Math.floor(playerBox.getMinX());
        int minY = (int) Math.floor(playerBox.getMinY());
        int minZ = (int) Math.floor(playerBox.getMinZ());
        int maxX = (int) Math.ceil(playerBox.getMaxX());
        int maxY = (int) Math.ceil(playerBox.getMaxY());
        int maxZ = (int) Math.ceil(playerBox.getMaxZ());

        for (int bx = minX; bx <= maxX; bx++) {
            for (int by = minY; by <= maxY; by++) {
                for (int bz = minZ; bz <= maxZ; bz++) {
                    Block block = world.getBlockAt(bx, by, bz);
                    if (isSolidPhaseBlock(block)) {
                        BoundingBox blockBox = block.getBoundingBox();
                        if (!blockBox.getMax().equals(blockBox.getMin()) && playerBox.overlaps(blockBox)) {
                            return true;
                        }
                    }
                }
            }
        }

        return false;
    }

    private boolean isPointInsideSolid(double x, double y, double z, World world, double playerHeight) {
        BoundingBox playerBox = new BoundingBox(
            x - HALF_WIDTH, y, z - HALF_WIDTH,
            x + HALF_WIDTH, y + playerHeight, z + HALF_WIDTH
        );

        int minX = (int) Math.floor(playerBox.getMinX());
        int minY = (int) Math.floor(playerBox.getMinY());
        int minZ = (int) Math.floor(playerBox.getMinZ());
        int maxX = (int) Math.ceil(playerBox.getMaxX());
        int maxY = (int) Math.ceil(playerBox.getMaxY());
        int maxZ = (int) Math.ceil(playerBox.getMaxZ());

        for (int bx = minX; bx <= maxX; bx++) {
            for (int by = minY; by <= maxY; by++) {
                for (int bz = minZ; bz <= maxZ; bz++) {
                    Block block = world.getBlockAt(bx, by, bz);
                    if (isSolidPhaseBlock(block)) {
                        BoundingBox blockBox = block.getBoundingBox();
                        if (!blockBox.getMax().equals(blockBox.getMin()) && playerBox.overlaps(blockBox)) {
                            return true;
                        }
                    }
                }
            }
        }

        return false;
    }


    private boolean isSolidPhaseBlock(Block block) {
        Material type = block.getType();
        if (!type.isSolid()) return false;

        String name = type.name();

        if (type == Material.COBWEB) return false;
        if (type == Material.HONEY_BLOCK) return false;
        if (type == Material.SLIME_BLOCK) return false;
        if (type == Material.SOUL_SAND) return false;
        if (type == Material.SOUL_SOIL) return false;
        if (type == Material.POWDER_SNOW) return false;
        if (type == Material.SWEET_BERRY_BUSH) return false;
        if (type == Material.SCAFFOLDING) return false;
        if (type == Material.LADDER) return false;
        if (type == Material.VINE) return false;
        if (type == Material.SNOW) return false;
        if (type == Material.BELL) return false;
        if (type == Material.POINTED_DRIPSTONE) return false;
        if (type == Material.BIG_DRIPLEAF) return false;
        if (type == Material.BIG_DRIPLEAF_STEM) return false;
        if (type == Material.SMALL_DRIPLEAF) return false;

        if (name.contains("SLAB")) return false;
        if (name.contains("DOOR")) return false;
        if (name.contains("FENCE_GATE")) return false;
        if (name.contains("TRAPDOOR")) return false;
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
        if (name.contains("AMETHYST_CLUSTER")) return false;
        if (name.contains("AMETHYST_BUD")) return false;
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

    public void handleTeleport(UUID uuid) {
        PhaseTracker tracker = trackers.get(uuid);
        if (tracker == null) return;
        tracker.reset();
        tracker.resetTicksInSolid();
    }

    public void cleanup(UUID uuid) {
        trackers.remove(uuid);
    }

    private static class PhaseTracker {
        private int entryCount = 0;
        private int pathCount = 0;
        private int ticksInSolid = 0;
        private long solidStartTime = 0;
        private long lastViolation = 0;
        private long lastRecordTime = 0;
        private int avgPing = 50;
        private Location lastPhaseLocation = null;
        private int sameSpotReentries = 0;
        private long lastSameSpotTime = 0;

        void recordPhaseEntry(Location loc) {
            long now = System.currentTimeMillis();
            lastRecordTime = now;
            if (lastPhaseLocation != null && lastPhaseLocation.getWorld() == loc.getWorld()) {
                double dist = loc.distance(lastPhaseLocation);
                if (dist < 0.5) {
                    if (now - lastSameSpotTime < 4000) {
                        sameSpotReentries++;
                        if (sameSpotReentries >= 5) {
                            entryCount++;
                            lastViolation = now;
                            sameSpotReentries = 0;
                        }
                    } else {
                        sameSpotReentries = 1;
                    }
                    lastSameSpotTime = now;
                    return;
                }
            }
            sameSpotReentries = 0;
            entryCount++;
            lastViolation = now;
            lastPhaseLocation = loc.clone();
        }

        void recordPathPhase() {
            long now = System.currentTimeMillis();
            pathCount++;
            lastViolation = now;
            lastRecordTime = now;
        }

        void addTickInSolid() {
            if (ticksInSolid == 0) {
                solidStartTime = System.currentTimeMillis();
            }
            ticksInSolid++;
        }

        void resetTicksInSolid() {
            ticksInSolid = 0;
            solidStartTime = 0;
        }

        long getSolidStartTime() {
            return solidStartTime;
        }

        void updatePing(int ping) {
            avgPing = (avgPing * 3 + ping) / 4;
        }

        void decay() {
            long now = System.currentTimeMillis();
            int decayTime = avgPing > 150 ? 1500 : 1000;

            if (now - lastViolation > decayTime) {
                if (entryCount > 0) entryCount--;
                if (pathCount > 0) pathCount--;
                lastViolation = now;
            }
        }

        void reset() {
            entryCount = 0;
            pathCount = 0;
            ticksInSolid = 0;
            solidStartTime = 0;
            lastPhaseLocation = null;
        }

        int getEntryCount() {
            return entryCount;
        }

        int getPathCount() {
            return pathCount;
        }

        int getTicksInSolid() {
            return ticksInSolid;
        }
    }
}
