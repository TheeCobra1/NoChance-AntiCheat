package NC.noChance.core;

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class PlayerData {
    private static final long TELEPORT_GRACE_MS = 3000L;
    private static final long ENDER_PEARL_GRACE_MS = 2000L;
    private static final long CHORUS_FRUIT_GRACE_MS = 2000L;
    private static final long KICK_STRICT_WINDOW_MS = 5L * 60L * 1000L;

    private final UUID playerId;
    private final long joinTime;
    private final Map<ViolationType, List<ViolationRecord>> violations;
    private final Map<String, Double> baseline;
    private final BoundedDeque<LocationData> locationHistory;
    private final BoundedDeque<Long> clickHistory;
    private final BoundedDeque<RotationData> rotationHistory;
    private final ArrayDeque<float[]> gazeHistory = new ArrayDeque<>(10);
    private final BoundedDeque<VelocityData> velocityHistory;
    private final BoundedDeque<Long> attackIntervals;
    private final BoundedDeque<TargetSwitch> targetSwitchHistory;
    private final BoundedDeque<Long> packetTimingDeltas;

    private volatile Location lastLocation;
    private volatile Location lastSafeLocation;
    private volatile long lastFlagTime;
    private volatile long lastMoveTime;
    private final AtomicLong totalFallDistanceBits = new AtomicLong(Double.doubleToLongBits(0.0));
    private volatile long lastGroundTime;
    private volatile boolean wasOnGround;
    private volatile long lastVelocityTime;
    private volatile double lastKnockbackVelocity;
    private volatile int airTicks;
    public volatile int flyDisableGraceTicks = 0;
    private volatile double lastVerticalVelocity;

    private volatile long lastBlockBreakTime;
    private Location lastBlockBreak;
    private volatile long blockBreakStartTime;
    private volatile String blockBreakInitialTool;
    private volatile int blockBreakInitialEfficiency;
    private final BoundedDeque<Long> blockBreakIntervals;
    private volatile int consecutiveFastBreaks;

    private volatile long lastBlockPlaceTime;
    private final AtomicInteger blocksPlacedInTick = new AtomicInteger(0);
    private volatile int currentTick;
    private final BoundedDeque<Long> blockPlaceIntervals;
    private final BoundedDeque<LocationData> blockPlaceLocations;
    private Location lastBlockPlace;

    private volatile double averageCPS;
    private volatile double averageRotationSpeed;
    private volatile double averageAccuracy;
    private volatile int totalHits;
    private volatile int totalAttempts;

    private SkillLevel skillLevel;
    private long sessionStartTime;
    private final AtomicInteger outlierCount = new AtomicInteger(0);

    private long lastEnderPearlTime;
    private long lastChorusFruitTime;

    private final AtomicInteger totalViolationCount = new AtomicInteger(0);
    private final AtomicInteger totalCheckCount = new AtomicInteger(0);
    private volatile long lastViolationTime;

    private volatile int historicalViolationCount;
    private volatile long lastKickTime;
    private volatile int effectiveGracePeriod;
    private volatile boolean strictDetectionMode;
    private volatile long lastTeleportTime;
    private final AtomicInteger groundTicks = new AtomicInteger(0);
    private volatile long lastWaterTime;
    private volatile long lastDamageTime;

    private volatile boolean bedrockPlayer;
    private volatile int clientVersion;
    private volatile double viaTolerance;
    private volatile int ping;
    private final BoundedDeque<Integer> pingHistory;
    private volatile double averagePing;
    private volatile boolean frozen;
    private volatile long frozenTime;
    private volatile boolean gliding;
    private volatile double[] lastPredictedPos;

    private volatile long lastSlimeContactTime;
    private volatile long lastHoneyContactTime;
    private volatile long lastRiptideTime;
    public volatile int riptideActiveTicks = 0;
    private volatile long lastBubbleColumnTime;
    private volatile int cachedSpeedAmp = -1;
    private volatile int cachedJumpAmp = -1;
    private volatile int cachedLevitationAmp = -1;
    private volatile long cachedSpeedExpiry;
    private volatile long cachedJumpExpiry;
    private volatile long cachedLevitationExpiry;
    private volatile long cachedSlowFallingExpiry;
    private volatile long cachedDolphinsGraceExpiry;

    public PlayerData(UUID playerId) {
        this.playerId = playerId;
        this.joinTime = System.currentTimeMillis();
        this.violations = new ConcurrentHashMap<>();
        this.baseline = new ConcurrentHashMap<>();
        this.locationHistory = new BoundedDeque<>(30);
        this.clickHistory = new BoundedDeque<>(150);
        this.rotationHistory = new BoundedDeque<>(80);
        this.velocityHistory = new BoundedDeque<>(50);
        this.attackIntervals = new BoundedDeque<>(50);
        this.targetSwitchHistory = new BoundedDeque<>(30);
        this.packetTimingDeltas = new BoundedDeque<>(100);
        this.sessionStartTime = System.currentTimeMillis();
        this.skillLevel = SkillLevel.MEDIUM;
        this.wasOnGround = true;
        this.airTicks = 0;
        this.lastVerticalVelocity = 0.0;
        this.blockBreakIntervals = new BoundedDeque<>(30);
        this.consecutiveFastBreaks = 0;
        this.blockPlaceIntervals = new BoundedDeque<>(40);
        this.blockPlaceLocations = new BoundedDeque<>(20);
        this.historicalViolationCount = 0;
        this.lastKickTime = 0;
        this.effectiveGracePeriod = 5;
        this.strictDetectionMode = false;
        this.bedrockPlayer = false;
        this.clientVersion = -1;
        this.ping = 0;
        this.pingHistory = new BoundedDeque<>(50);
        this.averagePing = 0;
        this.frozen = false;
        this.frozenTime = 0;
    }

    public void addViolation(ViolationType type, double severity, String details) {
        long now = System.currentTimeMillis();
        long cutoff = now - 90000;
        List<ViolationRecord> list = violations.computeIfAbsent(type, k -> java.util.Collections.synchronizedList(new ArrayList<>()));
        synchronized (list) {
            list.removeIf(v -> v.timestamp < cutoff);
            list.add(new ViolationRecord(type, severity, details, now));
        }
    }

    private static final List<ViolationRecord> EMPTY_VIOLATIONS = java.util.Collections.emptyList();

    public List<ViolationRecord> getViolations(ViolationType type, long timeWindow) {
        long cutoff = System.currentTimeMillis() - timeWindow;
        List<ViolationRecord> typeViolations = violations.getOrDefault(type, EMPTY_VIOLATIONS);
        List<ViolationRecord> snapshot;
        synchronized (typeViolations) {
            snapshot = new ArrayList<>(typeViolations);
        }
        List<ViolationRecord> recent = new ArrayList<>();
        for (ViolationRecord v : snapshot) {
            if (v.timestamp > cutoff) {
                recent.add(v);
            }
        }
        return recent;
    }

    public void cleanOldViolations(long timeWindow) {
        long cutoff = System.currentTimeMillis() - timeWindow;
        for (List<ViolationRecord> vList : violations.values()) {
            synchronized (vList) {
                vList.removeIf(v -> v.timestamp < cutoff);
            }
        }
    }

    public void updateLocationHistory(Location loc) {
        locationHistory.add(new LocationData(loc, System.currentTimeMillis()));
        this.lastLocation = loc;
        this.lastMoveTime = System.currentTimeMillis();
    }

    public void updateClickHistory() {
        long now = System.currentTimeMillis();
        clickHistory.add(now);
        updateAverageCPS();
    }

    public void updateRotationHistory(float yaw, float pitch) {
        rotationHistory.add(new RotationData(yaw, pitch, System.currentTimeMillis()));
        synchronized (gazeHistory) {
            if (gazeHistory.size() >= 10) gazeHistory.pollFirst();
            gazeHistory.addLast(new float[]{yaw, pitch});
        }
        updateAverageRotationSpeed();
    }

    public List<float[]> getGazeHistorySnapshot() {
        synchronized (gazeHistory) {
            List<float[]> copy = new ArrayList<>(gazeHistory.size());
            for (float[] g : gazeHistory) copy.add(new float[]{g[0], g[1]});
            return copy;
        }
    }

    public void clearGazeHistory() {
        synchronized (gazeHistory) {
            gazeHistory.clear();
        }
    }

    public void clearRotationHistory() {
        rotationHistory.clear();
    }

    private void updateAverageCPS() {
        if (clickHistory.size() < 2) return;
        long now = System.currentTimeMillis();
        long oneSecondAgo = now - 1000;
        int clicks = 0;
        for (long clickTime : clickHistory) {
            if (clickTime > oneSecondAgo) {
                clicks++;
            }
        }
        averageCPS = clicks;
    }

    private void updateAverageRotationSpeed() {
        if (rotationHistory.size() < 2) return;
        List<RotationData> rotList = new ArrayList<>(rotationHistory.getDeque());
        double totalSpeed = 0;
        int count = 0;
        for (int i = 1; i < rotList.size(); i++) {
            RotationData prev = rotList.get(i - 1);
            RotationData curr = rotList.get(i);
            double deltaYaw = Math.abs(curr.yaw - prev.yaw);
            double deltaPitch = Math.abs(curr.pitch - prev.pitch);
            double deltaTime = (curr.timestamp - prev.timestamp) / 1000.0;
            if (deltaTime > 0) {
                double speed = Math.sqrt(deltaYaw * deltaYaw + deltaPitch * deltaPitch) / deltaTime;
                totalSpeed += speed;
                count++;
            }
        }
        if (count > 0) {
            averageRotationSpeed = totalSpeed / count;
        }
    }

    public synchronized void updateAccuracy(boolean hit) {
        totalAttempts++;
        if (hit) totalHits++;
        averageAccuracy = totalAttempts > 0 ? (double) totalHits / totalAttempts : 0.0;
    }

    public void calibrateSkillLevel() {
        long sessionDuration = System.currentTimeMillis() - sessionStartTime;
        if (sessionDuration < 60000) return;

        if (averageCPS >= 14 && averageRotationSpeed >= 380 && averageAccuracy >= 0.72) {
            skillLevel = SkillLevel.HIGH;
        } else if (averageCPS >= 9 && averageRotationSpeed >= 220 && averageAccuracy >= 0.52) {
            skillLevel = SkillLevel.MEDIUM;
        } else {
            skillLevel = SkillLevel.LOW;
        }
    }

    public boolean isInGracePeriod(int gracePeriodSeconds) {
        long now = System.currentTimeMillis();
        int grace = Math.min(gracePeriodSeconds, effectiveGracePeriod);
        if ((now - joinTime) < (grace * 1000L)) {
            return true;
        }
        if (lastTeleportTime > 0 && (now - lastTeleportTime) < TELEPORT_GRACE_MS) {
            return true;
        }
        if (lastChorusFruitTime > 0 && (now - lastChorusFruitTime) < CHORUS_FRUIT_GRACE_MS) {
            return true;
        }
        if (lastEnderPearlTime > 0 && (now - lastEnderPearlTime) < ENDER_PEARL_GRACE_MS) {
            return true;
        }
        return false;
    }

    public boolean isInTeleportGracePeriod(int gracePeriodSeconds) {
        if (lastTeleportTime == 0) return false;
        return (System.currentTimeMillis() - lastTeleportTime) < (gracePeriodSeconds * 1000L);
    }

    public void setLastTeleportTime(long time) {
        this.lastTeleportTime = time;
    }

    public long getLastTeleportTime() {
        return lastTeleportTime;
    }

    public void setGroundTicks(int ticks) {
        groundTicks.set(ticks);
    }

    public int getGroundTicks() {
        return groundTicks.get();
    }

    public void incrementGroundTicks() {
        groundTicks.incrementAndGet();
    }

    public void setAirTicks(int ticks) {
        this.airTicks = ticks;
    }

    public void setLastWaterTime(long time) {
        this.lastWaterTime = time;
    }

    public long getLastWaterTime() {
        return lastWaterTime;
    }

    public void setLastDamageTime(long time) {
        this.lastDamageTime = time;
    }

    public long getLastDamageTime() {
        return lastDamageTime;
    }

    public void setHistoricalData(int violationCount, long kickTime) {
        this.historicalViolationCount = violationCount;
        this.lastKickTime = kickTime;

        if (violationCount > 20) {
            this.effectiveGracePeriod = 0;
        } else if (violationCount > 10) {
            this.effectiveGracePeriod = 1;
        } else if (violationCount > 5) {
            this.effectiveGracePeriod = 2;
        } else if (violationCount > 0) {
            this.effectiveGracePeriod = 3;
        } else {
            this.effectiveGracePeriod = 5;
        }

        long timeSinceKick = System.currentTimeMillis() - kickTime;
        if (kickTime > 0 && timeSinceKick < KICK_STRICT_WINDOW_MS) {
            this.strictDetectionMode = true;
            this.effectiveGracePeriod = 0;
        }
    }

    public boolean isStrictDetectionMode() {
        if (strictDetectionMode && lastKickTime > 0) {
            long timeSinceKick = System.currentTimeMillis() - lastKickTime;
            if (timeSinceKick >= KICK_STRICT_WINDOW_MS) {
                strictDetectionMode = false;
            }
        }
        return strictDetectionMode;
    }

    public void resetTransientState() {
        totalFallDistanceBits.set(Double.doubleToLongBits(0.0));
        this.airTicks = 0;
        this.lastVerticalVelocity = 0;
        this.wasOnGround = true;
        this.lastKnockbackVelocity = 0;
        this.lastVelocityTime = 0;
        this.lastGroundTime = System.currentTimeMillis();
        groundTicks.set(0);
        this.lastTeleportTime = System.currentTimeMillis();
        this.blockBreakStartTime = 0;
        this.consecutiveFastBreaks = 0;
        this.rotationHistory.clear();
        synchronized (gazeHistory) {
            gazeHistory.clear();
        }
    }

    public int getHistoricalViolationCount() {
        return historicalViolationCount;
    }

    public long getLastKickTime() {
        return lastKickTime;
    }

    public boolean wasRecentlyKicked() {
        if (lastKickTime == 0) return false;
        long timeSinceKick = System.currentTimeMillis() - lastKickTime;
        return timeSinceKick < KICK_STRICT_WINDOW_MS;
    }

    public UUID getPlayerId() {
        return playerId;
    }

    public Location getLastSafeLocation() {
        return lastSafeLocation;
    }

    public void setLastSafeLocation(Location loc) {
        this.lastSafeLocation = loc;
    }

    public long getLastFlagTime() {
        return lastFlagTime;
    }

    public void setLastFlagTime(long time) {
        this.lastFlagTime = time;
    }

    public Location getLastLocation() {
        return lastLocation;
    }

    public long getLastMoveTime() {
        return lastMoveTime;
    }

    public void setLastMoveTime(long time) {
        this.lastMoveTime = time;
    }

    public double getTotalFallDistance() {
        return Double.longBitsToDouble(totalFallDistanceBits.get());
    }

    public void addFallDistance(double distance) {
        totalFallDistanceBits.updateAndGet(bits -> Double.doubleToLongBits(Double.longBitsToDouble(bits) + distance));
    }

    public void resetFallDistance() {
        totalFallDistanceBits.set(Double.doubleToLongBits(0.0));
    }

    public long getLastGroundTime() {
        return lastGroundTime;
    }

    public void setLastGroundTime(long time) {
        this.lastGroundTime = time;
    }

    public boolean wasOnGround() {
        return wasOnGround;
    }

    public void setWasOnGround(boolean onGround) {
        this.wasOnGround = onGround;
    }

    public long getLastBlockBreakTime() {
        return lastBlockBreakTime;
    }

    public void setLastBlockBreakTime(long time) {
        if (this.lastBlockBreakTime > 0) {
            long interval = time - this.lastBlockBreakTime;

            if (interval <= 3000) {
                blockBreakIntervals.add(interval);

                if (interval < 20) {
                    consecutiveFastBreaks++;
                } else if (interval >= 20 && interval <= 350) {
                    consecutiveFastBreaks = Math.max(0, consecutiveFastBreaks - 5);
                } else if (interval > 350) {
                    consecutiveFastBreaks = 0;
                    blockBreakIntervals.clear();
                }
            } else {
                consecutiveFastBreaks = 0;
                blockBreakIntervals.clear();
            }
        }
        this.lastBlockBreakTime = time;
    }

    public Deque<Long> getBlockBreakIntervals() {
        return blockBreakIntervals.getDeque();
    }

    public int getConsecutiveFastBreaks() {
        return consecutiveFastBreaks;
    }

    public void resetConsecutiveFastBreaks() {
        this.consecutiveFastBreaks = 0;
    }

    public Location getLastBlockBreak() {
        return lastBlockBreak;
    }

    public void setLastBlockBreak(Location loc) {
        this.lastBlockBreak = loc;
    }

    public long getBlockBreakStartTime() {
        return blockBreakStartTime;
    }

    public void setBlockBreakStartTime(long time) {
        this.blockBreakStartTime = time;
    }

    public String getBlockBreakInitialTool() {
        return blockBreakInitialTool;
    }

    public void setBlockBreakInitialTool(String tool) {
        this.blockBreakInitialTool = tool;
    }

    public int getBlockBreakInitialEfficiency() {
        return blockBreakInitialEfficiency;
    }

    public void setBlockBreakInitialEfficiency(int efficiency) {
        this.blockBreakInitialEfficiency = efficiency;
    }

    public long getLastBlockPlaceTime() {
        return lastBlockPlaceTime;
    }

    public void setLastBlockPlaceTime(long time) {
        this.lastBlockPlaceTime = time;
    }

    public int getBlocksPlacedInTick() {
        return blocksPlacedInTick.get();
    }

    public void incrementBlocksPlacedInTick() {
        blocksPlacedInTick.incrementAndGet();
    }

    public void resetBlocksPlacedInTick() {
        blocksPlacedInTick.set(0);
    }

    public int getCurrentTick() {
        return currentTick;
    }

    public void setCurrentTick(int tick) {
        this.currentTick = tick;
    }

    public Deque<Long> getBlockPlaceIntervals() {
        return blockPlaceIntervals.getDeque();
    }

    public Deque<LocationData> getBlockPlaceLocations() {
        return blockPlaceLocations.getDeque();
    }

    public Location getLastBlockPlace() {
        return lastBlockPlace;
    }

    public void setLastBlockPlace(Location loc) {
        this.lastBlockPlace = loc;
    }

    public void addBlockPlaceInterval(long interval) {
        blockPlaceIntervals.add(interval);
    }

    public void addBlockPlaceLocation(Location loc) {
        blockPlaceLocations.add(new LocationData(loc, System.currentTimeMillis()));
    }

    public Deque<LocationData> getLocationHistory() {
        return locationHistory.getDeque();
    }

    public Deque<Long> getClickHistory() {
        return clickHistory.getDeque();
    }

    public Deque<RotationData> getRotationHistory() {
        return rotationHistory.getDeque();
    }

    public double getAverageCPS() {
        return averageCPS;
    }

    public double getAverageRotationSpeed() {
        return averageRotationSpeed;
    }

    public double getAverageAccuracy() {
        return averageAccuracy;
    }

    public SkillLevel getSkillLevel() {
        return skillLevel;
    }

    public void incrementOutlierCount() {
        this.outlierCount.incrementAndGet();
    }

    public int getOutlierCount() {
        return outlierCount.get();
    }

    public void resetOutlierCount() {
        this.outlierCount.set(0);
    }

    public void updateBaseline(String key, double value) {
        baseline.put(key, value);
    }

    public double getBaselineValue(String key, double defaultValue) {
        return baseline.getOrDefault(key, defaultValue);
    }

    public void setLastEnderPearlTime(long time) {
        this.lastEnderPearlTime = time;
    }

    public boolean hasRecentEnderPearl(long windowMs) {
        return lastEnderPearlTime > 0 && (System.currentTimeMillis() - lastEnderPearlTime) < windowMs;
    }

    public void setLastChorusFruitTime(long time) {
        this.lastChorusFruitTime = time;
    }

    public boolean hasRecentChorusFruit(long windowMs) {
        return lastChorusFruitTime > 0 && (System.currentTimeMillis() - lastChorusFruitTime) < windowMs;
    }

    public Deque<VelocityData> getVelocityHistory() {
        return velocityHistory.getDeque();
    }

    public void updateVelocityHistory(double velocityX, double velocityY, double velocityZ) {
        velocityHistory.add(new VelocityData(velocityX, velocityY, velocityZ, System.currentTimeMillis()));
        this.lastVerticalVelocity = velocityY;
    }

    public void setLastVelocityTime(long time) {
        this.lastVelocityTime = time;
    }

    public long getLastVelocityTime() {
        return lastVelocityTime;
    }

    public void setLastKnockbackVelocity(double velocity) {
        this.lastKnockbackVelocity = velocity;
    }

    public double getLastKnockbackVelocity() {
        return lastKnockbackVelocity;
    }

    public int getAirTicks() {
        return airTicks;
    }

    public void incrementAirTicks() {
        this.airTicks++;
    }

    public void resetAirTicks() {
        this.airTicks = 0;
    }

    public double getLastVerticalVelocity() {
        return lastVerticalVelocity;
    }

    public void incrementTotalChecks() {
        this.totalCheckCount.incrementAndGet();
    }

    public int getTotalChecks() {
        return totalCheckCount.get();
    }

    public boolean isBaselineEstablished() {
        return totalCheckCount.get() >= 20;
    }

    public void incrementTotalViolations() {
        this.totalViolationCount.incrementAndGet();
        this.lastViolationTime = System.currentTimeMillis();
    }

    public int getTotalViolations() {
        return totalViolationCount.get();
    }

    public double getViolationRatio() {
        int checks = totalCheckCount.get();
        if (checks == 0) return 0.0;
        return (double) totalViolationCount.get() / checks;
    }

    public double getHistoricalConfidenceMultiplier() {
        double ratio = getViolationRatio();
        double multiplier;
        if (ratio > 0.32) {
            multiplier = 1.35;
        } else if (ratio > 0.18) {
            multiplier = 1.18;
        } else if (ratio < 0.06) {
            return 0.82;
        } else {
            return 1.0;
        }

        if (lastViolationTime > 0) {
            long elapsed = System.currentTimeMillis() - lastViolationTime;
            if (elapsed > 30000) {
                double decay = Math.max(0, 1.0 - (elapsed - 30000) / 60000.0);
                multiplier = 1.0 + (multiplier - 1.0) * decay;
            }
        }

        return multiplier;
    }

    private UUID lastTargetId;
    private long lastAttackTime;

    public void recordAttack(UUID targetId) {
        long now = System.currentTimeMillis();
        if (lastAttackTime > 0) {
            long interval = now - lastAttackTime;
            if (interval > 0 && interval < 2000) {
                attackIntervals.add(interval);
            }
        }
        if (lastTargetId != null && !lastTargetId.equals(targetId)) {
            targetSwitchHistory.add(new TargetSwitch(lastTargetId, targetId, now));
        }
        lastTargetId = targetId;
        lastAttackTime = now;
    }

    public void recordPacketDelta(long delta) {
        if (delta < 0) {
            packetTimingDeltas.addFirst(Math.abs(delta));
        } else {
            packetTimingDeltas.add(delta);
        }
    }

    public Deque<Long> getAttackIntervals() {
        return attackIntervals.getDeque();
    }

    public Deque<TargetSwitch> getTargetSwitchHistory() {
        return targetSwitchHistory.getDeque();
    }

    public Deque<Long> getPacketTimingDeltas() {
        return packetTimingDeltas.getDeque();
    }

    public static class TargetSwitch {
        public final UUID fromTarget;
        public final UUID toTarget;
        public final long timestamp;

        public TargetSwitch(UUID fromTarget, UUID toTarget, long timestamp) {
            this.fromTarget = fromTarget;
            this.toTarget = toTarget;
            this.timestamp = timestamp;
        }
    }

    public static class LocationData {
        public final Location location;
        public final long timestamp;

        public LocationData(Location location, long timestamp) {
            this.location = location;
            this.timestamp = timestamp;
        }
    }

    public static class RotationData {
        public final float yaw;
        public final float pitch;
        public final long timestamp;

        public RotationData(float yaw, float pitch, long timestamp) {
            this.yaw = yaw;
            this.pitch = pitch;
            this.timestamp = timestamp;
        }
    }

    public static class ViolationRecord {
        public final ViolationType type;
        public final double severity;
        public final String details;
        public final long timestamp;

        public ViolationRecord(ViolationType type, double severity, String details, long timestamp) {
            this.type = type;
            this.severity = severity;
            this.details = details;
            this.timestamp = timestamp;
        }
    }

    public static class VelocityData {
        public final double x;
        public final double y;
        public final double z;
        public final long timestamp;

        public VelocityData(double x, double y, double z, long timestamp) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.timestamp = timestamp;
        }
    }

    public enum SkillLevel {
        LOW(4, 9, 120, 220, 0.30, 0.52),
        MEDIUM(9, 14, 220, 380, 0.52, 0.72),
        HIGH(14, 22, 380, 650, 0.72, 0.88);

        public final int minCPS;
        public final int maxCPS;
        public final double minRotationSpeed;
        public final double maxRotationSpeed;
        public final double minAccuracy;
        public final double maxAccuracy;

        SkillLevel(int minCPS, int maxCPS, double minRotationSpeed, double maxRotationSpeed,
                   double minAccuracy, double maxAccuracy) {
            this.minCPS = minCPS;
            this.maxCPS = maxCPS;
            this.minRotationSpeed = minRotationSpeed;
            this.maxRotationSpeed = maxRotationSpeed;
            this.minAccuracy = minAccuracy;
            this.maxAccuracy = maxAccuracy;
        }
    }

    public boolean isBedrockPlayer() {
        return bedrockPlayer;
    }

    public void setBedrockPlayer(boolean bedrock) {
        this.bedrockPlayer = bedrock;
    }

    public int getClientVersion() {
        return clientVersion;
    }

    public void setClientVersion(int version) {
        this.clientVersion = version;
    }

    public boolean isLegacyClient() {
        return clientVersion > 0 && clientVersion < ViaHelper.V1_9;
    }

    public double getViaTolerance() {
        return viaTolerance;
    }

    public void setViaTolerance(double tolerance) {
        this.viaTolerance = tolerance;
    }

    public int getPing() {
        return ping;
    }

    public void updatePing(int newPing) {
        this.ping = newPing;
        pingHistory.add(newPing);
        updateAveragePing();
    }

    private void updateAveragePing() {
        Deque<Integer> pings = pingHistory.getDeque();
        if (pings.isEmpty()) {
            averagePing = 0;
            return;
        }
        double sum = 0;
        for (int p : pings) {
            sum += p;
        }
        averagePing = sum / pings.size();
    }

    public double getAveragePing() {
        return averagePing;
    }

    public double getPingMultiplier() {
        if (averagePing <= 50) return 1.0;
        if (averagePing <= 100) return 1.1;
        if (averagePing <= 150) return 1.2;
        if (averagePing <= 200) return 1.35;
        if (averagePing <= 300) return 1.5;
        return 1.7;
    }

    public double getPingTolerance() {
        return Math.max(0, (averagePing - 50) * 0.001);
    }

    public boolean isHighPing() {
        return averagePing > 150;
    }

    public boolean isFrozen() {
        return frozen;
    }

    public void setFrozen(boolean frozen) {
        this.frozen = frozen;
        this.frozenTime = frozen ? System.currentTimeMillis() : 0;
    }

    public long getFrozenTime() {
        return frozenTime;
    }

    public boolean isGliding() {
        return gliding;
    }

    public void setGliding(boolean gliding) {
        this.gliding = gliding;
    }

    public void setLastPredictedPos(double[] pos) {
        this.lastPredictedPos = pos;
    }

    public long getLastSlimeContactTime() { return lastSlimeContactTime; }
    public void touchSlimeContact() { this.lastSlimeContactTime = System.currentTimeMillis(); }

    public long getLastHoneyContactTime() { return lastHoneyContactTime; }
    public void touchHoneyContact() { this.lastHoneyContactTime = System.currentTimeMillis(); }

    public long getLastRiptideTime() { return lastRiptideTime; }
    public void touchRiptide() { this.lastRiptideTime = System.currentTimeMillis(); }
    public boolean isInRiptideGrace() {
        return riptideActiveTicks > 0 || (System.currentTimeMillis() - lastRiptideTime) < 3000;
    }

    private volatile long lastSlowExitTime = 0;
    public long getLastSlowExitTime() { return lastSlowExitTime; }
    public void touchSlowExit() { this.lastSlowExitTime = System.currentTimeMillis(); }

    public long getLastBubbleColumnTime() { return lastBubbleColumnTime; }
    public void touchBubbleColumn() { this.lastBubbleColumnTime = System.currentTimeMillis(); }

    public void cacheSpeedEffect(int ampPlusOne, long expiryMs) { this.cachedSpeedAmp = ampPlusOne; this.cachedSpeedExpiry = expiryMs; }
    public int getCachedSpeedAmp(long graceMs) {
        if (cachedSpeedAmp <= 0) return 0;
        long age = System.currentTimeMillis() - cachedSpeedExpiry;
        if (age <= 0) return cachedSpeedAmp;
        if (age >= graceMs) return 0;
        return cachedSpeedAmp;
    }
    public double getSpeedDecayFactor(long graceMs) {
        if (cachedSpeedAmp <= 0) return 0.0;
        long age = System.currentTimeMillis() - cachedSpeedExpiry;
        if (age <= 0) return 1.0;
        if (age >= graceMs) return 0.0;
        return 1.0 - ((double) age / graceMs);
    }

    public void cacheJumpEffect(int ampPlusOne, long expiryMs) { this.cachedJumpAmp = ampPlusOne; this.cachedJumpExpiry = expiryMs; }
    public int getCachedJumpAmp(long graceMs) {
        if (cachedJumpAmp <= 0) return 0;
        long age = System.currentTimeMillis() - cachedJumpExpiry;
        if (age <= 0) return cachedJumpAmp;
        if (age >= graceMs) return 0;
        return cachedJumpAmp;
    }

    public void cacheLevitationEffect(int ampPlusOne, long expiryMs) { this.cachedLevitationAmp = ampPlusOne; this.cachedLevitationExpiry = expiryMs; }
    public boolean hadRecentLevitation(long graceMs) {
        if (cachedLevitationAmp <= 0) return false;
        return (System.currentTimeMillis() - cachedLevitationExpiry) < graceMs;
    }

    public void cacheSlowFallingExpiry(long expiryMs) { this.cachedSlowFallingExpiry = expiryMs; }
    public boolean hadRecentSlowFalling(long graceMs) {
        if (cachedSlowFallingExpiry == 0) return false;
        return (System.currentTimeMillis() - cachedSlowFallingExpiry) < graceMs;
    }

    public void cacheDolphinsGraceExpiry(long expiryMs) { this.cachedDolphinsGraceExpiry = expiryMs; }
    public boolean hadRecentDolphinsGrace(long graceMs) {
        if (cachedDolphinsGraceExpiry == 0) return false;
        return (System.currentTimeMillis() - cachedDolphinsGraceExpiry) < graceMs;
    }

    public int getStateSize() {
        int n = 0;
        for (List<ViolationRecord> v : violations.values()) {
            synchronized (v) { n += v.size(); }
        }
        n += baseline.size();
        n += locationHistory.size();
        n += clickHistory.size();
        n += rotationHistory.size();
        n += velocityHistory.size();
        n += attackIntervals.size();
        n += targetSwitchHistory.size();
        n += packetTimingDeltas.size();
        n += blockBreakIntervals.size();
        n += blockPlaceIntervals.size();
        n += blockPlaceLocations.size();
        n += pingHistory.size();
        return n;
    }

    public static boolean hasLegitFlight(Player player) {
        GameMode gm = player.getGameMode();
        return player.getAllowFlight()
            || player.isFlying()
            || gm == GameMode.CREATIVE
            || gm == GameMode.SPECTATOR
            || player.hasPermission("nochance.bypass.fly")
            || player.hasPermission("essentials.fly")
            || player.hasPermission("cmi.command.fly");
    }
}
