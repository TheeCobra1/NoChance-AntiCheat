package NC.noChance.detection.player;

import NC.noChance.core.*;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.FluidCollisionMode;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

public class InteractCheck {
    private final ACConfig config;
    private final Map<UUID, PlayerData> playerDataMap;
    private final LayerFiltering filtering;
    private final Map<UUID, InteractTracker> trackers;

    private static final double MAX_BLOCK_REACH = 4.5;
    private static final double MAX_ENTITY_REACH = 3.5;

    public InteractCheck(ACConfig config, Map<UUID, PlayerData> playerDataMap, LayerFiltering filtering) {
        this.config = config;
        this.playerDataMap = playerDataMap;
        this.filtering = filtering;
        this.trackers = new ConcurrentHashMap<>();
    }

    public CheckResult checkBlockInteraction(Player player, Block block, BlockFace face) {
        if (!config.isCheckEnabled("interact")) {
            return CheckResult.passed();
        }

        PlayerData data = playerDataMap.get(player.getUniqueId());
        if (data == null) return CheckResult.passed();

        if (data.isInGracePeriod(config.getGracePeriod())) {
            return CheckResult.passed();
        }

        if (player.getVehicle() != null) {
            return CheckResult.passed();
        }

        UUID uuid = player.getUniqueId();
        InteractTracker tracker = trackers.computeIfAbsent(uuid, k -> new InteractTracker());

        CheckResult ghostCheck = checkGhostHand(player, block, face, tracker);
        if (ghostCheck.isFailed()) return ghostCheck;

        CheckResult reachCheck = checkBlockReach(player, block, tracker);
        if (reachCheck.isFailed()) return reachCheck;

        CheckResult sequenceCheck = checkInteractSequence(player, block, tracker);
        if (sequenceCheck.isFailed()) return sequenceCheck;

        return CheckResult.passed();
    }

    public CheckResult checkEntityInteraction(Player player, Entity target) {
        if (!config.isCheckEnabled("interact")) {
            return CheckResult.passed();
        }

        PlayerData data = playerDataMap.get(player.getUniqueId());
        if (data == null) return CheckResult.passed();

        if (data.isInGracePeriod(config.getGracePeriod())) {
            return CheckResult.passed();
        }

        if (player.getVehicle() != null) {
            return CheckResult.passed();
        }

        UUID uuid = player.getUniqueId();
        InteractTracker tracker = trackers.computeIfAbsent(uuid, k -> new InteractTracker());

        CheckResult reachCheck = checkEntityReach(player, target, tracker);
        if (reachCheck.isFailed()) return reachCheck;

        return CheckResult.passed();
    }

    public CheckResult checkContainerAccess(Player player, Block container) {
        if (!config.isCheckEnabled("interact")) {
            return CheckResult.passed();
        }

        PlayerData data = playerDataMap.get(player.getUniqueId());
        if (data == null) return CheckResult.passed();

        if (data.isInGracePeriod(config.getGracePeriod())) {
            return CheckResult.passed();
        }

        UUID uuid = player.getUniqueId();
        InteractTracker tracker = trackers.computeIfAbsent(uuid, k -> new InteractTracker());

        double distance = player.getEyeLocation().distance(container.getLocation().add(0.5, 0.5, 0.5));

        int ping = filtering.getPing(player);
        double maxReach = MAX_BLOCK_REACH + (ping > 150 ? 0.5 : 0);

        if (distance > maxReach + 1.0) {
            tracker.recordReachViolation();

            if (tracker.getReachViolations() >= 3) {
                double excess = distance - maxReach;
                double severity = Math.min(0.94, 0.70 + excess * 0.1);

                CheckResult prelimResult = CheckResult.failed(
                    ViolationType.INVALIDINTERACT,
                    severity,
                    String.format("Container reach: %.2f blocks (max: %.2f)", distance, maxReach)
                );

                if (filtering.passesLayer2HeuristicFiltering(player, ViolationType.INVALIDINTERACT, prelimResult)) {
                    return prelimResult;
                }
            }
        } else {
            tracker.decayReach();
        }

        if (!hasLineOfSight(player, container)) {
            tracker.recordGhostViolation();

            if (tracker.getGhostViolations() >= 4) {
                CheckResult prelimResult = CheckResult.failed(
                    ViolationType.GHOSTHAND,
                    0.88,
                    "Container through blocks"
                );

                if (filtering.passesLayer2HeuristicFiltering(player, ViolationType.GHOSTHAND, prelimResult)) {
                    return prelimResult;
                }
            }
        } else {
            tracker.decayGhost();
        }

        return CheckResult.passed();
    }

    private CheckResult checkGhostHand(Player player, Block block, BlockFace face, InteractTracker tracker) {
        Location eyeLoc = player.getEyeLocation();
        Location blockCenter = block.getLocation().add(0.5, 0.5, 0.5);

        if (!hasLineOfSight(player, block)) {
            Block faceBlock = block.getRelative(face);
            if (faceBlock.getType().isSolid() && !isPassable(faceBlock.getType())) {
                tracker.recordGhostViolation();

                if (tracker.getGhostViolations() >= 4) {
                    CheckResult prelimResult = CheckResult.failed(
                        ViolationType.GHOSTHAND,
                        0.90,
                        String.format("Interact through solid: %s via %s", block.getType(), faceBlock.getType())
                    );

                    if (filtering.passesLayer2HeuristicFiltering(player, ViolationType.GHOSTHAND, prelimResult)) {
                        return prelimResult;
                    }
                }
            }
        } else {
            tracker.decayGhost();
        }

        return CheckResult.passed();
    }

    private CheckResult checkBlockReach(Player player, Block block, InteractTracker tracker) {
        Location eyeLoc = player.getEyeLocation();
        Location blockCenter = block.getLocation().add(0.5, 0.5, 0.5);
        double distance = eyeLoc.distance(blockCenter);

        int ping = filtering.getPing(player);
        double maxReach = MAX_BLOCK_REACH + (ping > 150 ? 0.3 : 0) + (ping > 250 ? 0.3 : 0);

        Vector pv = player.getVelocity();
        double hSpeed = Math.sqrt(pv.getX() * pv.getX() + pv.getZ() * pv.getZ());
        if (hSpeed > 0.1) {
            maxReach += Math.min(0.5, hSpeed * 0.6);
        }
        if (player.isSprinting()) {
            maxReach += 0.3;
        }

        if (player.getGameMode() == org.bukkit.GameMode.CREATIVE) {
            maxReach = 6.0;
        }

        Location blockLoc = block.getLocation();
        if (blockLoc.getWorld() != null) {
            int cx = blockLoc.getBlockX() >> 4;
            int cz = blockLoc.getBlockZ() >> 4;
            if (!blockLoc.getWorld().isChunkLoaded(cx, cz)) {
                maxReach += 0.5;
            }
        }

        if (distance > maxReach + 0.5) {
            tracker.recordReachViolation();

            if (tracker.getReachViolations() >= 3) {
                double excess = distance - maxReach;
                double severity = Math.min(0.95, 0.72 + excess * 0.08);

                CheckResult prelimResult = CheckResult.failed(
                    ViolationType.INVALIDINTERACT,
                    severity,
                    String.format("Block reach: %.2f blocks (max: %.2f)", distance, maxReach)
                );

                if (filtering.passesLayer2HeuristicFiltering(player, ViolationType.INVALIDINTERACT, prelimResult)) {
                    return prelimResult;
                }
            }
        } else {
            tracker.decayReach();
        }

        return CheckResult.passed();
    }

    private CheckResult checkEntityReach(Player player, Entity target, InteractTracker tracker) {
        Location eyeLoc = player.getEyeLocation();
        Location targetLoc = target.getLocation().add(0, target.getHeight() / 2, 0);
        double distance = eyeLoc.distance(targetLoc);

        int ping = filtering.getPing(player);
        double maxReach = MAX_ENTITY_REACH + (ping > 150 ? 0.4 : 0) + (ping > 250 ? 0.4 : 0);

        Vector pv = player.getVelocity();
        double hSpeed = Math.sqrt(pv.getX() * pv.getX() + pv.getZ() * pv.getZ());
        if (hSpeed > 0.1) {
            maxReach += Math.min(0.5, hSpeed * 0.6);
        }
        if (player.isSprinting()) {
            maxReach += 0.3;
        }

        if (distance > maxReach + 0.5) {
            tracker.recordReachViolation();

            if (tracker.getReachViolations() >= 3) {
                double excess = distance - maxReach;
                double severity = Math.min(0.94, 0.70 + excess * 0.1);

                CheckResult prelimResult = CheckResult.failed(
                    ViolationType.INVALIDINTERACT,
                    severity,
                    String.format("Entity interact reach: %.2f blocks (max: %.2f)", distance, maxReach)
                );

                if (filtering.passesLayer2HeuristicFiltering(player, ViolationType.INVALIDINTERACT, prelimResult)) {
                    return prelimResult;
                }
            }
        } else {
            tracker.decayReach();
        }

        return CheckResult.passed();
    }

    private CheckResult checkInteractSequence(Player player, Block block, InteractTracker tracker) {
        long now = System.currentTimeMillis();
        tracker.recordInteraction(now);

        List<Long> intervals = tracker.getRecentIntervals();
        if (intervals.size() < 8) return CheckResult.passed();

        double avg = intervals.stream().mapToLong(Long::longValue).average().orElse(100);
        double variance = intervals.stream()
            .mapToDouble(i -> Math.pow(i - avg, 2))
            .average()
            .orElse(100);

        if (avg < 25 && variance < 10 && intervals.size() >= 12) {
            double severity = Math.min(0.92, 0.75 + (25 - avg) / 100);

            CheckResult prelimResult = CheckResult.failed(
                ViolationType.INVALIDINTERACT,
                severity,
                String.format("Interact spam: %.1fms avg, %.1f var", avg, variance)
            );

            if (filtering.passesLayer2HeuristicFiltering(player, ViolationType.INVALIDINTERACT, prelimResult)) {
                return prelimResult;
            }
        }

        return CheckResult.passed();
    }

    private boolean hasLineOfSight(Player player, Block block) {
        Location eyeLoc = player.getEyeLocation();
        Location blockCenter = block.getLocation().add(0.5, 0.5, 0.5);
        Vector direction = blockCenter.toVector().subtract(eyeLoc.toVector());
        double distance = direction.length();

        if (distance < 0.5) return true;
        if (distance < 0.001) return true;

        direction.normalize();

        try {
            RayTraceResult result = player.getWorld().rayTraceBlocks(
                eyeLoc,
                direction,
                distance + 0.1,
                FluidCollisionMode.NEVER,
                true
            );

            if (result == null) return true;
            Block hit = result.getHitBlock();
            if (hit == null) return true;
            if (hit.equals(block)) return true;
            if (isPassable(hit.getType())) return true;
            return false;
        } catch (Exception e) {
            Logger.getLogger("NoChance").log(Level.WARNING, "Failed to check block visibility for " + block.getLocation(), e);
            return true;
        }
    }

    private boolean isPassable(Material type) {
        if (!type.isSolid()) return true;
        String name = type.name();
        if (name.equals("GLASS") || name.equals("GLASS_PANE") || name.endsWith("_GLASS") || name.endsWith("_GLASS_PANE")) return true;
        if (name.equals("IRON_BARS") || name.endsWith("_BARS")) return false;
        if (name.endsWith("_FENCE") || name.endsWith("_FENCE_GATE") || name.endsWith("_WALL")) return false;
        if (name.endsWith("_SLAB") || name.endsWith("_STAIRS")) return false;
        if (name.endsWith("_TRAPDOOR") || name.endsWith("_DOOR")) return false;
        return type == Material.WATER || type == Material.LAVA;
    }

    public void cleanup(UUID playerId) {
        trackers.remove(playerId);
    }

    public void cleanupStale() {
        long now = System.currentTimeMillis();
        trackers.entrySet().removeIf(e -> now - e.getValue().lastActivity > 30000);
    }

    private static class InteractTracker {
        private int ghostViolations = 0;
        private int reachViolations = 0;
        private long lastGhostViolation = 0;
        private long lastReachViolation = 0;
        private long lastActivity = System.currentTimeMillis();
        private final Deque<Long> interactionTimes = new ArrayDeque<>(20);

        void recordGhostViolation() {
            long now = System.currentTimeMillis();
            if (now - lastGhostViolation < 2000) {
                ghostViolations++;
            } else {
                ghostViolations = 1;
            }
            lastGhostViolation = now;
            lastActivity = now;
        }

        void decayGhost() {
            long now = System.currentTimeMillis();
            if (now - lastGhostViolation > 4000) {
                ghostViolations = Math.max(0, ghostViolations - 1);
            }
        }

        int getGhostViolations() { return ghostViolations; }

        void recordReachViolation() {
            long now = System.currentTimeMillis();
            if (now - lastReachViolation < 2000) {
                reachViolations++;
            } else {
                reachViolations = 1;
            }
            lastReachViolation = now;
            lastActivity = now;
        }

        void decayReach() {
            long now = System.currentTimeMillis();
            if (now - lastReachViolation > 4000) {
                reachViolations = Math.max(0, reachViolations - 1);
            }
        }

        int getReachViolations() { return reachViolations; }

        void recordInteraction(long time) {
            if (!interactionTimes.isEmpty()) {
                long last = interactionTimes.peekLast();
                if (time - last < 1000) {
                    interactionTimes.addLast(time);
                    if (interactionTimes.size() > 20) {
                        interactionTimes.pollFirst();
                    }
                } else {
                    interactionTimes.clear();
                    interactionTimes.addLast(time);
                }
            } else {
                interactionTimes.addLast(time);
            }
            lastActivity = time;
        }

        List<Long> getRecentIntervals() {
            List<Long> intervals = new ArrayList<>();
            List<Long> times = new ArrayList<>(interactionTimes);
            for (int i = 1; i < times.size(); i++) {
                intervals.add(times.get(i) - times.get(i - 1));
            }
            return intervals;
        }
    }
}
