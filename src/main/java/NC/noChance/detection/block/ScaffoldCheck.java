package NC.noChance.detection.block;

import NC.noChance.core.*;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

public class ScaffoldCheck {
    private final ACConfig config;
    private final Map<UUID, PlayerData> playerDataMap;
    private final LayerFiltering filtering;
    private final Map<UUID, List<BlockPlaceRecord>> recentPlacements;
    private final Map<UUID, Deque<PlaceEvent>> placeEvents;
    private final Map<UUID, Deque<Long>> headcraneHits;

    private static final double LOOK_DOWN_PITCH_THRESHOLD = 60.0;
    private static final double BRIDGING_ANGLE_MIN = 45.0;
    private static final int PLACE_EVENT_WINDOW = 6;
    private static final double SNAP_YAW_DELTA = 170.0;
    private static final double HEADCRANE_PITCH_MAX = -10.0;
    private static final long HEADCRANE_WINDOW_MS = 10_000L;
    private static final int HEADCRANE_MIN_HITS = 3;

    private static final double GODBRIDGE_PITCH_CENTER = 75.65;
    private static final double GODBRIDGE_PITCH_BAND = 1.2;
    private static final int GODBRIDGE_MIN_PLACEMENTS = 3;

    public ScaffoldCheck(ACConfig config, Map<UUID, PlayerData> playerDataMap, LayerFiltering filtering) {
        this.config = config;
        this.playerDataMap = playerDataMap;
        this.filtering = filtering;
        this.recentPlacements = new ConcurrentHashMap<>();
        this.placeEvents = new ConcurrentHashMap<>();
        this.headcraneHits = new ConcurrentHashMap<>();
    }

    public CheckResult check(Player player, Block block) {
        if (!config.isCheckEnabled("scaffold")) {
            return CheckResult.passed();
        }

        PlayerData data = playerDataMap.get(player.getUniqueId());
        if (data == null) return CheckResult.passed();

        if (data.isInGracePeriod(config.getGracePeriod())) {
            return CheckResult.passed();
        }

        UUID playerId = player.getUniqueId();
        long now = System.currentTimeMillis();
        Location blockLoc = block.getLocation();
        int ping = filtering.getPing(player);

        if (hasRecentWindCharge(player)) {
            return CheckResult.passed();
        }

        CheckResult snapResult = checkRotationSnap(player, playerId, blockLoc, now);
        if (snapResult.isFailed()) {
            return snapResult;
        }

        CheckResult headcraneResult = checkHeadcrane(player, playerId, blockLoc, now);
        if (headcraneResult.isFailed()) {
            return headcraneResult;
        }

        Vector velocity = player.getVelocity();
        double horizontalVelocity = Math.sqrt(velocity.getX() * velocity.getX() + velocity.getZ() * velocity.getZ());
        if (horizontalVelocity > 0.8 || velocity.getY() > 0.6) {
            return CheckResult.passed();
        }

        float pitch = player.getLocation().getPitch();
        boolean lookingDown = pitch >= LOOK_DOWN_PITCH_THRESHOLD;

        List<BlockPlaceRecord> placements = recentPlacements.computeIfAbsent(playerId, k -> Collections.synchronizedList(new ArrayList<>()));
        List<BlockPlaceRecord> snapshot;
        synchronized (placements) {
            placements.add(new BlockPlaceRecord(blockLoc, now, pitch));
            placements.removeIf(record -> now - record.timestamp > 3000);
            snapshot = new ArrayList<>(placements);
        }

        CheckResult godBridgeResult = checkGodBridgePitch(player, snapshot);
        if (godBridgeResult.isFailed()) {
            return godBridgeResult;
        }

        if (BlockCache.isType(blockLoc, org.bukkit.Material.SCAFFOLDING)) {
            return CheckResult.passed();
        }

        double maxScaffoldAngle = config.getFastPlaceMaxScaffoldAngle();
        double maxScaffoldDist = config.getFastPlaceMaxScaffoldDistance();
        int scaffoldMinBlocks = config.getFastPlaceScaffoldMinBlocks();

        boolean hasSupport = false;
        for (BlockFace face : new BlockFace[]{BlockFace.DOWN, BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST}) {
            Block adjacent = block.getRelative(face);
            if (adjacent.getType().isSolid()) {
                hasSupport = true;
                break;
            }
        }

        double playerY = player.getLocation().getY();
        double blockY = blockLoc.getY();
        double verticalDiff = blockY - playerY;

        if (!hasSupport && verticalDiff > 2.5 && player.getLocation().getWorld() == blockLoc.getWorld()) {
            double playerDistance = player.getLocation().distance(blockLoc);
            double distanceThreshold = maxScaffoldDist + (ping > 150 ? 1.0 : (ping > 100 ? 0.5 : 0.0));

            if (playerDistance > distanceThreshold) {
                CheckResult prelimResult = CheckResult.failed(
                        ViolationType.SCAFFOLD,
                        0.92,
                        String.format("Impossible placement: %.1f blocks away, %.1f blocks above", playerDistance, verticalDiff)
                );

                if (!filtering.passesLayer2HeuristicFiltering(player, ViolationType.SCAFFOLD, prelimResult)) {
                    return CheckResult.passed();
                }

                return prelimResult;
            }
        }

        if (snapshot.size() >= 3) {
            List<Long> placementTimes = new ArrayList<>();
            for (int i = 1; i < snapshot.size(); i++) {
                placementTimes.add(snapshot.get(i).timestamp - snapshot.get(i - 1).timestamp);
            }

            if (placementTimes.size() >= 2) {
                double avgTime = placementTimes.stream().mapToLong(Long::longValue).average().orElse(0);
                double variance = calculateVariance(placementTimes);
                long minTime = placementTimes.stream().mapToLong(Long::longValue).min().orElse(100);
                long maxTime = placementTimes.stream().mapToLong(Long::longValue).max().orElse(100);

                double pingSpeedBonus = ping > 200 ? 40 : (ping > 150 ? 25 : (ping > 100 ? 15 : 0));
                double tps = TPSCache.getTPS();
                double tpsMultiplier = 20.0 / Math.max(tps, 10.0);

                int sameYCount = 0;
                double avgY = snapshot.stream().mapToDouble(r -> r.location.getY()).average().orElse(0);

                for (BlockPlaceRecord record : snapshot) {
                    if (Math.abs(record.location.getY() - avgY) < 0.5) {
                        sameYCount++;
                    }
                }

                boolean allLookingDown = true;
                for (BlockPlaceRecord record : snapshot) {
                    if (record.pitch < BRIDGING_ANGLE_MIN) {
                        allLookingDown = false;
                        break;
                    }
                }

                double avgPitch = 0;
                for (BlockPlaceRecord record : snapshot) {
                    avgPitch += record.pitch;
                }
                avgPitch /= snapshot.size();

                double adjustedMinAvg = 150 + pingSpeedBonus;
                double adjustedFastAvg = 80 + pingSpeedBonus;

                if (lookingDown && allLookingDown && avgPitch >= LOOK_DOWN_PITCH_THRESHOLD) {
                    adjustedMinAvg -= 20;
                    adjustedFastAvg -= 10;
                }

                boolean hasSuspiciousAngle = !lookingDown && avgPitch < 30.0 && sameYCount >= scaffoldMinBlocks;

                if (avgTime < adjustedMinAvg && sameYCount >= scaffoldMinBlocks) {
                    if (lookingDown && allLookingDown && avgTime > 65 + pingSpeedBonus) {
                        return CheckResult.passed();
                    }

                    if (variance < (120 * tpsMultiplier) || (minTime > 20 && maxTime < 200 && avgTime < (120 + pingSpeedBonus))) {
                        boolean movingBackward = isPlacingBehind(player, snapshot);

                        double severity = 0.72;
                        if (avgTime < 70) severity += 0.10;
                        if (variance < 30) severity += 0.08;
                        if (movingBackward && !lookingDown) severity += 0.07;
                        if (hasSuspiciousAngle) severity += 0.06;

                        if (lookingDown && allLookingDown) {
                            severity -= 0.12;
                        }

                        if (ping > 150) {
                            severity -= 0.05;
                        }

                        severity = Math.max(0.55, Math.min(1.0, severity));

                        CheckResult prelimResult = CheckResult.failed(
                                ViolationType.SCAFFOLD_BRIDGE,
                                severity,
                                String.format("Scaffold: %d blocks, %.0fms avg, var:%.0f, pitch:%.0f%s",
                                    sameYCount, avgTime, variance, avgPitch, movingBackward ? " backward" : "")
                        );

                        if (!filtering.passesLayer2HeuristicFiltering(player, ViolationType.SCAFFOLD_BRIDGE, prelimResult)) {
                            return CheckResult.passed();
                        }

                        return prelimResult;
                    }
                }

                if (avgTime < adjustedFastAvg && sameYCount >= 2 && !lookingDown) {
                    double severity = 0.85;
                    if (ping > 150) severity -= 0.05;

                    CheckResult prelimResult = CheckResult.failed(
                            ViolationType.SCAFFOLD_BRIDGE,
                            severity,
                            String.format("Fast scaffold: %.0fms avg, %d blocks, pitch:%.0f", avgTime, sameYCount, avgPitch)
                    );

                    if (!filtering.passesLayer2HeuristicFiltering(player, ViolationType.SCAFFOLD_BRIDGE, prelimResult)) {
                        return CheckResult.passed();
                    }

                    return prelimResult;
                }
            }
        }

        List<BlockPlaceRecord> towerBlocks = new ArrayList<>();
        for (int i = 1; i < snapshot.size(); i++) {
            BlockPlaceRecord prev = snapshot.get(i - 1);
            BlockPlaceRecord curr = snapshot.get(i);

            if (curr.location.getBlockY() == prev.location.getBlockY() + 1 &&
                    curr.location.getBlockX() == prev.location.getBlockX() &&
                    curr.location.getBlockZ() == prev.location.getBlockZ()) {
                if (towerBlocks.isEmpty() || matchesRecord(towerBlocks.get(towerBlocks.size() - 1), prev)) {
                    if (towerBlocks.isEmpty()) towerBlocks.add(prev);
                    towerBlocks.add(curr);
                }
            }
        }

        if (towerBlocks.size() >= 3) {
            List<Long> towerTimes = new ArrayList<>();
            for (int i = 1; i < towerBlocks.size(); i++) {
                towerTimes.add(towerBlocks.get(i).timestamp - towerBlocks.get(i - 1).timestamp);
            }

            if (!towerTimes.isEmpty()) {
                double avgTime = towerTimes.stream().mapToLong(Long::longValue).average().orElse(0);
                double variance = calculateVariance(towerTimes);

                double towerThreshold = 120 + (ping > 150 ? 30 : (ping > 100 ? 15 : 0));
                double varThreshold = 120 + (ping > 150 ? 40 : 0);

                if (avgTime < towerThreshold && variance < varThreshold && velocity.getY() < 0.1) {
                    double severity = Math.min(1.0, (towerThreshold + 20 - avgTime) / (towerThreshold + 20));
                    if (ping > 150) severity -= 0.05;
                    severity = Math.max(0.5, severity);

                    CheckResult prelimResult = CheckResult.failed(
                            ViolationType.SCAFFOLD_TOWER,
                            severity,
                            String.format("Tower cheat: %d blocks, %.0fms avg, %.0f variance", towerBlocks.size(), avgTime, variance)
                    );

                    if (!filtering.passesLayer2HeuristicFiltering(player, ViolationType.SCAFFOLD_TOWER, prelimResult)) {
                        return CheckResult.passed();
                    }

                    return prelimResult;
                }
            }
        }

        return CheckResult.passed();
    }

    private boolean hasRecentWindCharge(Player player) {
        try {
            Vector vel = player.getVelocity();
            double speed = vel.length();
            if (speed > 1.2) {
                return true;
            }
        } catch (Exception e) {
            Logger.getLogger("NoChance").log(Level.WARNING, "Failed to get velocity for " + player.getName(), e);
        }

        long lastDamage = 0;
        PlayerData data = playerDataMap.get(player.getUniqueId());
        if (data != null) {
            lastDamage = data.getLastDamageTime();
        }
        if (lastDamage > 0 && (System.currentTimeMillis() - lastDamage) < 1500) {
            Vector vel = player.getVelocity();
            if (vel.length() > 0.5) {
                return true;
            }
        }

        return false;
    }

    private double calculateVariance(List<Long> values) {
        if (values.isEmpty()) return 0;
        double mean = values.stream().mapToLong(Long::longValue).average().orElse(0);
        return values.stream()
                .mapToDouble(v -> Math.pow(v - mean, 2))
                .average()
                .orElse(0);
    }

    private boolean matchesRecord(BlockPlaceRecord a, BlockPlaceRecord b) {
        return a.location.getBlockX() == b.location.getBlockX()
                && a.location.getBlockY() == b.location.getBlockY()
                && a.location.getBlockZ() == b.location.getBlockZ()
                && a.timestamp == b.timestamp;
    }

    private boolean isPlacingBehind(Player player, List<BlockPlaceRecord> placements) {
        if (placements.size() < 3) return false;

        Location playerLoc = player.getLocation();
        float yaw = playerLoc.getYaw();

        double facingX = -Math.sin(Math.toRadians(yaw));
        double facingZ = Math.cos(Math.toRadians(yaw));

        int behindCount = 0;
        for (int i = 1; i < placements.size(); i++) {
            BlockPlaceRecord prev = placements.get(i - 1);
            BlockPlaceRecord curr = placements.get(i);

            double moveX = curr.location.getX() - prev.location.getX();
            double moveZ = curr.location.getZ() - prev.location.getZ();

            double dot = moveX * facingX + moveZ * facingZ;
            if (dot < -0.3) {
                behindCount++;
            }
        }

        return behindCount >= placements.size() / 2;
    }

    public void cleanup(UUID playerId) {
        recentPlacements.remove(playerId);
        placeEvents.remove(playerId);
        headcraneHits.remove(playerId);
    }

    public void cleanupStale() {
        long now = System.currentTimeMillis();
        recentPlacements.entrySet().removeIf(entry -> {
            List<BlockPlaceRecord> placements = entry.getValue();
            synchronized (placements) {
                placements.removeIf(record -> now - record.timestamp > 5000);
                return placements.isEmpty();
            }
        });
        placeEvents.entrySet().removeIf(entry -> {
            Deque<PlaceEvent> q = entry.getValue();
            synchronized (q) {
                q.removeIf(e -> now - e.time > 8000);
                return q.isEmpty();
            }
        });
        headcraneHits.entrySet().removeIf(entry -> {
            Deque<Long> q = entry.getValue();
            synchronized (q) {
                q.removeIf(t -> now - t > HEADCRANE_WINDOW_MS);
                return q.isEmpty();
            }
        });
    }

    private CheckResult checkRotationSnap(Player player, UUID playerId, Location blockLoc, long now) {
        Location pl = player.getLocation();
        float yaw = normalizeYaw(pl.getYaw());
        PlaceEvent ev = new PlaceEvent(yaw, now, blockLoc.getX(), blockLoc.getZ(), pl.getX(), pl.getZ());

        Deque<PlaceEvent> q = placeEvents.computeIfAbsent(playerId, k -> new ArrayDeque<>());
        PlaceEvent prev;
        synchronized (q) {
            prev = q.peekLast();
            q.addLast(ev);
            while (q.size() > PLACE_EVENT_WINDOW) {
                q.pollFirst();
            }
        }

        if (prev == null) {
            return CheckResult.passed();
        }

        double delta = Math.abs(yawDelta(prev.yaw, yaw));
        if (delta <= SNAP_YAW_DELTA) {
            return CheckResult.passed();
        }

        long dt = now - prev.time;
        if (dt > 600) {
            return CheckResult.passed();
        }

        double facingX = -Math.sin(Math.toRadians(yaw));
        double facingZ = Math.cos(Math.toRadians(yaw));
        double dx = blockLoc.getX() - pl.getX();
        double dz = blockLoc.getZ() - pl.getZ();
        double dot = dx * facingX + dz * facingZ;
        if (dot >= -0.2) {
            return CheckResult.passed();
        }

        Vector vel = player.getVelocity();
        if (vel.length() > 0.9) {
            return CheckResult.passed();
        }

        double severity = 0.78;
        if (delta > 178.0) severity += 0.05;
        if (dt < 250) severity += 0.05;
        severity = Math.max(0.55, Math.min(1.0, severity));

        CheckResult prelim = CheckResult.failed(
                ViolationType.SCAFFOLD_BRIDGE,
                severity,
                String.format("SCAFFOLD_180_SNAP yawDelta:%.0f dt:%dms dot:%.2f", delta, dt, dot)
        );

        if (!filtering.passesLayer2HeuristicFiltering(player, ViolationType.SCAFFOLD_BRIDGE, prelim)) {
            return CheckResult.passed();
        }
        return prelim;
    }

    private CheckResult checkHeadcrane(Player player, UUID playerId, Location blockLoc, long now) {
        Location pl = player.getLocation();
        float pitch = pl.getPitch();
        if (pitch > HEADCRANE_PITCH_MAX) {
            return CheckResult.passed();
        }
        if (blockLoc.getY() >= pl.getY()) {
            return CheckResult.passed();
        }

        Deque<Long> q = headcraneHits.computeIfAbsent(playerId, k -> new ArrayDeque<>());
        int hits;
        synchronized (q) {
            q.addLast(now);
            while (!q.isEmpty() && now - q.peekFirst() > HEADCRANE_WINDOW_MS) {
                q.pollFirst();
            }
            hits = q.size();
        }

        if (hits < HEADCRANE_MIN_HITS) {
            return CheckResult.passed();
        }

        double severity = 0.7;
        if (pitch <= -30.0) severity += 0.08;
        if (hits >= 5) severity += 0.07;
        severity = Math.max(0.55, Math.min(1.0, severity));

        CheckResult prelim = CheckResult.failed(
                ViolationType.SCAFFOLD,
                severity,
                String.format("SCAFFOLD_HEADCRANE pitch:%.1f hits:%d/10s placeY:%.1f playerY:%.1f",
                        pitch, hits, blockLoc.getY(), pl.getY())
        );

        if (!filtering.passesLayer2HeuristicFiltering(player, ViolationType.SCAFFOLD, prelim)) {
            return CheckResult.passed();
        }
        return prelim;
    }

    private CheckResult checkGodBridgePitch(Player player, List<BlockPlaceRecord> snapshot) {
        if (snapshot == null || snapshot.size() < GODBRIDGE_MIN_PLACEMENTS) return CheckResult.passed();

        int hits = 0;
        double pitchSum = 0;
        double pitchMin = Double.POSITIVE_INFINITY;
        double pitchMax = Double.NEGATIVE_INFINITY;
        for (BlockPlaceRecord rec : snapshot) {
            if (Math.abs(rec.pitch - GODBRIDGE_PITCH_CENTER) <= GODBRIDGE_PITCH_BAND) {
                hits++;
                pitchSum += rec.pitch;
                if (rec.pitch < pitchMin) pitchMin = rec.pitch;
                if (rec.pitch > pitchMax) pitchMax = rec.pitch;
            }
        }

        if (hits < GODBRIDGE_MIN_PLACEMENTS) return CheckResult.passed();

        double spread = pitchMax - pitchMin;
        double avg = pitchSum / hits;
        double severity = 0.78;
        if (spread < 0.5) severity += 0.10;
        if (hits >= 5) severity += 0.07;
        severity = Math.max(0.6, Math.min(0.98, severity));

        CheckResult prelim = CheckResult.failed(
                ViolationType.SCAFFOLD_BRIDGE,
                severity,
                String.format("SCAFFOLD_GODBRIDGE pitch:%.2f spread:%.2f hits:%d", avg, spread, hits)
        );

        if (!filtering.passesLayer2HeuristicFiltering(player, ViolationType.SCAFFOLD_BRIDGE, prelim)) {
            return CheckResult.passed();
        }
        return prelim;
    }

    private static float normalizeYaw(float yaw) {
        float y = yaw % 360.0f;
        if (y >= 180.0f) y -= 360.0f;
        if (y < -180.0f) y += 360.0f;
        return y;
    }

    private static double yawDelta(float a, float b) {
        double d = b - a;
        while (d > 180.0) d -= 360.0;
        while (d < -180.0) d += 360.0;
        return d;
    }

    private static class PlaceEvent {
        final float yaw;
        final long time;
        final double placedX;
        final double placedZ;
        final double playerX;
        final double playerZ;

        PlaceEvent(float yaw, long time, double placedX, double placedZ, double playerX, double playerZ) {
            this.yaw = yaw;
            this.time = time;
            this.placedX = placedX;
            this.placedZ = placedZ;
            this.playerX = playerX;
            this.playerZ = playerZ;
        }
    }

    private static class BlockPlaceRecord {
        final Location location;
        final long timestamp;
        final float pitch;

        BlockPlaceRecord(Location location, long timestamp, float pitch) {
            this.location = location;
            this.timestamp = timestamp;
            this.pitch = pitch;
        }
    }
}
