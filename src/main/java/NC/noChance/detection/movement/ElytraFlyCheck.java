package NC.noChance.detection.movement;

import NC.noChance.core.*;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffectType;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class ElytraFlyCheck {
    private final ACConfig config;
    private final Map<UUID, PlayerData> playerDataMap;
    private final LayerFiltering filtering;
    private final Map<UUID, ElytraData> elytraDataMap;

    private static final long LAUNCH_GRACE_MS = 3500;
    private static final long LANDING_GRACE_MS = 2000;
    private static final double TURN_SPEED_TOLERANCE = 1.4;
    private static final long SIGNAL_WINDOW_MS = 2500;
    private static final int VL_FLOOR = 4;

    private MovementGrace movementGrace;

    public ElytraFlyCheck(ACConfig config, Map<UUID, PlayerData> playerDataMap, LayerFiltering filtering) {
        this.config = config;
        this.playerDataMap = playerDataMap;
        this.filtering = filtering;
        this.elytraDataMap = new ConcurrentHashMap<>();
    }

    public void setMovementGrace(MovementGrace grace) {
        this.movementGrace = grace;
    }

    private double getMaxElytraSpeed() {
        return config.getMaxElytraSpeed();
    }

    private long getBoostGraceMs() {
        return config.getElytraBoostGraceMs();
    }

    private int getVlThreshold() {
        return Math.max(VL_FLOOR, config.getViolationThreshold("elytrafly"));
    }

    public CheckResult check(Player player, Location from, Location to) {
        if (!config.isCheckEnabled("elytrafly")) {
            return CheckResult.passed();
        }

        if (NC.noChance.core.TPSCache.getTPS() < 18.0) {
            return CheckResult.passed();
        }

        UUID uuid = player.getUniqueId();
        ElytraData elytraData = elytraDataMap.computeIfAbsent(uuid, k -> new ElytraData());

        if (!player.isGliding()) {
            if (elytraData.wasGliding()) {
                elytraData.setLandingTime(System.currentTimeMillis());
                elytraData.clearMovements();
            }
            elytraData.incrementNonGlideTicks();
            if (elytraData.getNonGlideTicks() >= 30) {
                elytraData.resetMomentum();
            }
            elytraData.setWasGliding(false);
            return CheckResult.passed();
        }

        elytraData.resetNonGlideTicks();
        elytraData.setWasGliding(true);

        org.bukkit.inventory.ItemStack chest = player.getInventory().getChestplate();
        if (chest == null || chest.getType() != org.bukkit.Material.ELYTRA) {
            if (player.getGameMode() == org.bukkit.GameMode.CREATIVE
                || player.getGameMode() == org.bukkit.GameMode.SPECTATOR
                || player.getAllowFlight()
                || player.isRiptiding()) {
                return CheckResult.passed();
            }
            CheckResult noElytra = CheckResult.failed(
                ViolationType.ELYTRAFLY,
                0.95,
                "Gliding without elytra equipped"
            );
            if (!filtering.passesLayer2HeuristicFiltering(player, ViolationType.ELYTRAFLY, noElytra)) {
                return CheckResult.passed();
            }
            return noElytra;
        }

        if (player.getGameMode() == org.bukkit.GameMode.SPECTATOR ||
            player.getGameMode() == org.bukkit.GameMode.CREATIVE) {
            return CheckResult.passed();
        }

        PlayerData data = playerDataMap.get(player.getUniqueId());
        if (data == null) return CheckResult.passed();

        if (data.isInGracePeriod(config.getGracePeriod())) {
            return CheckResult.passed();
        }

        long now = System.currentTimeMillis();

        if (player.isRiptiding()) {
            elytraData.setLastBoostTime(now);
            return CheckResult.passed();
        }

        PotionEffectType levType = PotionEffectType.getByName("LEVITATION");
        if (levType != null && player.hasPotionEffect(levType)) {
            return CheckResult.passed();
        }

        PotionEffectType slowFallType = PotionEffectType.getByName("SLOW_FALLING");
        if (slowFallType != null && player.hasPotionEffect(slowFallType)) {
            return CheckResult.passed();
        }

        long timeSinceVelocity = now - data.getLastVelocityTime();
        long pingGrace = Math.min(1000, filtering.getPing(player) * 2L);
        if (timeSinceVelocity < getBoostGraceMs() + pingGrace) {
            if (now - elytraData.getLastBoostTime() > 1000) {
                elytraData.setLastBoostTime(now);
            }
            return CheckResult.passed();
        }

        long timeSinceDamage = now - data.getLastDamageTime();
        if (timeSinceDamage < 1000) {
            return CheckResult.passed();
        }

        long timeSinceTeleport = now - data.getLastTeleportTime();
        if (timeSinceTeleport < 2500) {
            return CheckResult.passed();
        }

        long timeSinceBoost = now - elytraData.getLastBoostTime();
        if (timeSinceBoost < getBoostGraceMs()) {
            return CheckResult.passed();
        }

        long timeSinceLanding = now - elytraData.getLandingTime();
        if (timeSinceLanding < LANDING_GRACE_MS) {
            return CheckResult.passed();
        }

        if (!elytraData.hasGlideStarted()) {
            elytraData.startGlide(now);
            return CheckResult.passed();
        }

        long glideDuration = now - elytraData.getGlideStartTime();
        if (glideDuration < LAUNCH_GRACE_MS) {
            return CheckResult.passed();
        }

        if (isNearLaunchBlock(player)) {
            elytraData.startGlide(now);
            return CheckResult.passed();
        }

        if (isNearGround(player)) {
            elytraData.decaySpeed();
            elytraData.decayHover();
            return CheckResult.passed();
        }

        double deltaX = to.getX() - from.getX();
        double deltaY = to.getY() - from.getY();
        double deltaZ = to.getZ() - from.getZ();

        double horizontalSpeed = Math.sqrt(deltaX * deltaX + deltaZ * deltaZ);
        double totalSpeed = Math.sqrt(deltaX * deltaX + deltaY * deltaY + deltaZ * deltaZ);

        float currentYaw = to.getYaw();
        float currentPitch = to.getPitch();
        float lastYaw = elytraData.getLastYaw();
        float lastPitch = elytraData.getLastPitch();

        float yawDelta = currentYaw - lastYaw;
        while (yawDelta > 180) yawDelta -= 360;
        while (yawDelta < -180) yawDelta += 360;
        yawDelta = Math.abs(yawDelta);
        float pitchDelta = Math.abs(currentPitch - lastPitch);

        boolean isTurning = yawDelta > 15 || pitchDelta > 10;
        boolean isSharpTurn = yawDelta > 35 || pitchDelta > 25;

        elytraData.recordMovement(horizontalSpeed, deltaY, currentPitch, now);
        elytraData.setLastRotation(currentYaw, currentPitch);

        float pitch = player.getLocation().getPitch();
        boolean divingDown = pitch > 30;
        boolean lookingUp = pitch < -20;

        if (divingDown && deltaY < -0.3) {
            elytraData.addMomentum(Math.abs(deltaY) * 0.6);
        }

        double storedMomentum = elytraData.getMomentum();
        if (storedMomentum > 0) {
            elytraData.decayMomentum();
        }

        double maxSpeed = getMaxElytraSpeed();
        int ping = filtering.getPing(player);
        if (ping > 200) {
            maxSpeed += 1.3;
        } else if (ping > 150) {
            maxSpeed += 0.9;
        } else if (ping > 100) {
            maxSpeed += 0.5;
        } else if (ping > 50) {
            maxSpeed += 0.25;
        }

        maxSpeed += storedMomentum;

        if (divingDown) {
            maxSpeed += 2.4 + Math.min(1.2, (pitch - 30) * 0.04);
        }

        if (lookingUp && deltaY > 0) {
            maxSpeed += 0.6;
        }

        if (isTurning) {
            maxSpeed += TURN_SPEED_TOLERANCE;
        }

        if (isSharpTurn) {
            maxSpeed += TURN_SPEED_TOLERANCE * 1.0;
            elytraData.decaySpeed();
            elytraData.decayHover();
            elytraData.decayRise();
            return CheckResult.passed();
        }

        if (totalSpeed > maxSpeed) {
            elytraData.incrementSpeedViolations(now);

            if (elytraData.getSpeedViolations() < getVlThreshold()) {
                return CheckResult.passed();
            }

            boolean corroborated = elytraData.hasRecentSignal(now, SIGNAL_WINDOW_MS, SignalType.HOVER)
                || elytraData.hasRecentSignal(now, SIGNAL_WINDOW_MS, SignalType.RISE);

            double over = totalSpeed - maxSpeed;
            double severity;
            if (corroborated) {
                severity = Math.min(0.88, 0.68 + over * 0.08);
            } else {
                severity = Math.min(0.70, 0.52 + over * 0.06);
            }

            CheckResult prelimResult = CheckResult.failed(
                ViolationType.ELYTRAFLY,
                severity,
                String.format("Speed: %.2f (max: %.2f) vl=%d corr=%s",
                    totalSpeed, maxSpeed, elytraData.getSpeedViolations(), corroborated)
            );

            if (!filtering.passesLayer2HeuristicFiltering(player, ViolationType.ELYTRAFLY, prelimResult)) {
                elytraData.decrementSpeedViolations();
                return CheckResult.passed();
            }

            return prelimResult;
        } else {
            elytraData.decaySpeed();
        }

        List<MovementSample> recent = elytraData.getRecentMovements(24);
        if (recent.size() >= 20) {
            double avgY = recent.stream().mapToDouble(m -> m.yDelta).average().orElse(0);
            long risingOrFlat = recent.stream().filter(m -> m.yDelta > -0.005).count();
            long veryStableCount = recent.stream().filter(m -> Math.abs(m.yDelta) < 0.008).count();

            boolean isHovering = avgY > -0.015 && risingOrFlat >= 20 && veryStableCount >= 12 && horizontalSpeed > 1.0;
            boolean allLookingLevel = recent.stream().allMatch(m -> Math.abs(m.pitch) < 12);
            boolean hSpeedStable = recent.stream().mapToDouble(m -> m.hSpeed).max().orElse(0)
                - recent.stream().mapToDouble(m -> m.hSpeed).min().orElse(0) < 0.28;

            if (isHovering && allLookingLevel && hSpeedStable) {
                elytraData.incrementHoverViolations(now);

                if (elytraData.getHoverViolations() < getVlThreshold()) {
                    return CheckResult.passed();
                }

                boolean corroborated = elytraData.hasRecentSignal(now, SIGNAL_WINDOW_MS, SignalType.SPEED)
                    || elytraData.hasRecentSignal(now, SIGNAL_WINDOW_MS, SignalType.RISE);

                double severity = corroborated ? 0.86 : 0.60;

                CheckResult prelimResult = CheckResult.failed(
                    ViolationType.ELYTRAFLY,
                    severity,
                    String.format("Hover: flat=%d vstable=%d avgY=%.4f hSpeed=%.2f vl=%d corr=%s",
                        risingOrFlat, veryStableCount, avgY, horizontalSpeed,
                        elytraData.getHoverViolations(), corroborated)
                );

                if (!filtering.passesLayer2HeuristicFiltering(player, ViolationType.ELYTRAFLY, prelimResult)) {
                    elytraData.decrementHoverViolations();
                    return CheckResult.passed();
                }

                return prelimResult;
            } else {
                elytraData.decayHover();
            }

            long risingCount = recent.stream().filter(m -> m.yDelta > 0.18).count();
            boolean wasLookingUp = recent.stream().anyMatch(m -> m.pitch < -20);
            boolean hadMomentum = elytraData.hadRecentMomentum();
            long timeSinceBoostEvent = now - data.getLastVelocityTime();

            if (risingCount >= 14 && !wasLookingUp && !hadMomentum && timeSinceBoostEvent > 6000) {
                elytraData.incrementRiseViolations(now);

                if (elytraData.getRiseViolations() < getVlThreshold()) {
                    return CheckResult.passed();
                }

                boolean corroborated = elytraData.hasRecentSignal(now, SIGNAL_WINDOW_MS, SignalType.SPEED)
                    || elytraData.hasRecentSignal(now, SIGNAL_WINDOW_MS, SignalType.HOVER);
                double severity = corroborated ? 0.85 : 0.58;

                CheckResult prelimResult = CheckResult.failed(
                    ViolationType.ELYTRAFLY,
                    severity,
                    String.format("Rising without boost: rises=%d vl=%d corr=%s",
                        risingCount, elytraData.getRiseViolations(), corroborated)
                );

                if (!filtering.passesLayer2HeuristicFiltering(player, ViolationType.ELYTRAFLY, prelimResult)) {
                    elytraData.decrementRiseViolations();
                    return CheckResult.passed();
                }

                return prelimResult;
            } else {
                elytraData.decayRise();
            }
        }

        return CheckResult.passed();
    }

    private enum SignalType { SPEED, HOVER, RISE }

    private boolean isNearLaunchBlock(Player player) {
        Location loc = player.getLocation();
        for (int x = -1; x <= 1; x++) {
            for (int y = -2; y <= 0; y++) {
                for (int z = -1; z <= 1; z++) {
                    Block block = loc.clone().add(x, y, z).getBlock();
                    Material type = block.getType();
                    if (type.isSolid() || type == Material.SCAFFOLDING) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private boolean isNearGround(Player player) {
        Location loc = player.getLocation();
        for (int y = -1; y >= -3; y--) {
            Block block = loc.clone().add(0, y, 0).getBlock();
            if (block.getType().isSolid()) {
                return true;
            }
        }
        return false;
    }

    public void cleanup(UUID playerId) {
        ElytraData data = elytraDataMap.remove(playerId);
        if (data != null) {
            data.reset();
        }
    }

    public void cleanupStale() {
        long now = System.currentTimeMillis();
        elytraDataMap.entrySet().removeIf(entry -> {
            PlayerData data = playerDataMap.get(entry.getKey());
            if (data == null || (now - data.getLastMoveTime()) > 300000) {
                entry.getValue().reset();
                return true;
            }
            return false;
        });
    }

    private static class MovementSample {
        final double hSpeed;
        final double yDelta;
        final float pitch;
        final long time;

        MovementSample(double hSpeed, double yDelta, float pitch, long time) {
            this.hSpeed = hSpeed;
            this.yDelta = yDelta;
            this.pitch = pitch;
            this.time = time;
        }
    }

    private static class ElytraData {
        private final Deque<MovementSample> movements = new ArrayDeque<>(30);
        private int speedViolations;
        private int hoverViolations;
        private int riseViolations;
        private long lastSpeedVl;
        private long lastHoverVl;
        private long lastRiseVl;
        private long lastBoostTime;
        private long glideStartTime;
        private long landingTime;
        private double momentum;
        private long lastMomentumTime;
        private long lastMomentumDecay;
        private boolean wasGliding;
        private float lastYaw;
        private float lastPitch;
        private int nonGlideTicks;

        void incrementNonGlideTicks() {
            nonGlideTicks++;
        }

        void resetNonGlideTicks() {
            nonGlideTicks = 0;
        }

        int getNonGlideTicks() {
            return nonGlideTicks;
        }

        void recordMovement(double hSpeed, double yDelta, float pitch, long time) {
            if (movements.size() >= 30) {
                movements.pollFirst();
            }
            movements.addLast(new MovementSample(hSpeed, yDelta, pitch, time));
        }

        void clearMovements() {
            movements.clear();
        }

        List<MovementSample> getRecentMovements(int count) {
            List<MovementSample> result = new ArrayList<>();
            int idx = 0;
            int start = Math.max(0, movements.size() - count);
            for (MovementSample m : movements) {
                if (idx >= start) {
                    result.add(m);
                }
                idx++;
            }
            return result;
        }

        void setLastBoostTime(long time) {
            this.lastBoostTime = time;
        }

        long getLastBoostTime() {
            return lastBoostTime;
        }

        void setLandingTime(long time) {
            this.landingTime = time;
        }

        long getLandingTime() {
            return landingTime;
        }

        void setWasGliding(boolean gliding) {
            this.wasGliding = gliding;
        }

        boolean wasGliding() {
            return wasGliding;
        }

        void setLastRotation(float yaw, float pitch) {
            this.lastYaw = yaw;
            this.lastPitch = pitch;
        }

        float getLastYaw() {
            return lastYaw;
        }

        float getLastPitch() {
            return lastPitch;
        }

        void startGlide(long time) {
            this.glideStartTime = time;
        }

        long getGlideStartTime() {
            return glideStartTime;
        }

        boolean hasGlideStarted() {
            return glideStartTime > 0;
        }

        void addMomentum(double amount) {
            momentum = Math.min(2.5, momentum + amount);
            lastMomentumTime = System.currentTimeMillis();
        }

        double getMomentum() {
            long now = System.currentTimeMillis();
            if (now - lastMomentumTime > 5000) {
                momentum = 0;
            }
            return momentum;
        }

        void decayMomentum() {
            long now = System.currentTimeMillis();
            if (now - lastMomentumDecay < 50) return;
            long elapsed = lastMomentumDecay == 0 ? 50 : (now - lastMomentumDecay);
            double ticks = Math.min(20, elapsed / 50.0);
            momentum = Math.max(0, momentum - 0.08 * ticks);
            lastMomentumDecay = now;
        }

        void resetMomentum() {
            momentum = 0;
            lastMomentumTime = 0;
        }

        boolean hadRecentMomentum() {
            return System.currentTimeMillis() - lastMomentumTime < 6000;
        }

        void incrementSpeedViolations(long now) {
            if (now - lastSpeedVl > 4500) speedViolations = 0;
            speedViolations++;
            lastSpeedVl = now;
        }

        void incrementHoverViolations(long now) {
            if (now - lastHoverVl > 6000) hoverViolations = 0;
            hoverViolations++;
            lastHoverVl = now;
        }

        void incrementRiseViolations(long now) {
            if (now - lastRiseVl > 5000) riseViolations = 0;
            riseViolations++;
            lastRiseVl = now;
        }

        void decrementSpeedViolations() {
            speedViolations = Math.max(0, speedViolations - 1);
        }

        void decrementHoverViolations() {
            hoverViolations = Math.max(0, hoverViolations - 1);
        }

        void decrementRiseViolations() {
            riseViolations = Math.max(0, riseViolations - 1);
        }

        void decaySpeed() {
            if (System.currentTimeMillis() - lastSpeedVl > 5000) {
                speedViolations = Math.max(0, speedViolations - 1);
            }
        }

        void decayHover() {
            if (System.currentTimeMillis() - lastHoverVl > 7000) {
                hoverViolations = Math.max(0, hoverViolations - 1);
            }
        }

        void decayRise() {
            if (System.currentTimeMillis() - lastRiseVl > 5500) {
                riseViolations = Math.max(0, riseViolations - 1);
            }
        }

        boolean hasRecentSignal(long now, long windowMs, SignalType type) {
            long lastFire = switch (type) {
                case SPEED -> lastSpeedVl;
                case HOVER -> lastHoverVl;
                case RISE -> lastRiseVl;
            };
            return lastFire > 0 && (now - lastFire) < windowMs;
        }

        int getSpeedViolations() { return speedViolations; }
        int getHoverViolations() { return hoverViolations; }
        int getRiseViolations() { return riseViolations; }

        void reset() {
            movements.clear();
            speedViolations = 0;
            hoverViolations = 0;
            riseViolations = 0;
            glideStartTime = 0;
            momentum = 0;
            wasGliding = false;
        }
    }
}
