package NC.noChance.detection.movement;

import NC.noChance.core.*;
import NC.noChance.core.VersionAdapter;
import NC.noChance.predict.PhysicsValidator;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Boat;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

public class SpeedCheck {
    private final ACConfig config;
    private final Map<UUID, PlayerData> playerDataMap;
    private final LayerFiltering filtering;
    private final FalsePositiveFilter falsePositiveFilter;
    private final Map<UUID, MoveData> moveDataMap;
    private MovementGrace movementGrace;
    private PhysicsValidator physicsValidator;

    public void setMovementGrace(MovementGrace grace) {
        this.movementGrace = grace;
    }

    public void setPhysicsValidator(PhysicsValidator validator) {
        this.physicsValidator = validator;
    }

    private static final double JUMP_BOOST_HORIZONTAL = 0.2;
    private static final double EWMA_ALPHA = 0.3;
    private static final double WELFORD_WARMUP = 5;
    private static final int MIN_SAMPLES_BASIC = 3;
    private static final int MIN_SAMPLES_BHOP = 4;
    private static final int MIN_SAMPLES_STRAFE = 5;

    private static class MoveData {
        private final double[] speeds = new double[12];
        private final double[] accelerations = new double[8];
        private final long[] timestamps = new long[12];
        private volatile int speedIdx = 0;
        private volatile int accelIdx = 0;
        private volatile int count = 0;
        private volatile int violations = 0;
        private volatile long lastViolation = 0;
        private volatile double lastSpeed = 0;
        private volatile boolean wasOnIce = false;
        private volatile int ticksOnGround = 0;
        private volatile int ticksInAir = 0;
        private volatile double peakSpeed = 0;
        private final int[] groundContactTimes = new int[10];
        private volatile int groundContactIdx = 0;
        private volatile int groundContactCount = 0;
        private volatile int consecutiveQuickJumps = 0;
        private volatile double speedBeforeLanding = 0;
        private volatile boolean wasInAir = false;
        private volatile int bhopViolations = 0;
        private volatile long lastBhopCheck = 0;
        private volatile int lastGroundTick = 0;
        private volatile int accelViolations = 0;
        private final double[] strafeAngles = new double[8];
        private volatile int strafeIdx = 0;
        private volatile int strafeCount = 0;
        private volatile int strafeViolations = 0;
        private volatile int consistentSpeedViolations = 0;
        private volatile long lastConsistentCheck = 0;
        private final double[] speedRatios = new double[15];
        private volatile int ratioIdx = 0;
        private volatile int ratioCount = 0;
        private volatile long lastActivity = System.currentTimeMillis();

        private volatile long lastWindChargeTime = 0;

        private volatile double ewmaSpeed = 0;
        private volatile boolean ewmaInit = false;
        private volatile double welfordMean = 0;
        private volatile double welfordM2 = 0;
        private volatile long welfordCount = 0;
        private volatile double cusum = 0;

        void addSpeed(double speed, long time) {
            speeds[speedIdx] = speed;
            timestamps[speedIdx] = time;
            speedIdx = (speedIdx + 1) % speeds.length;
            if (count < speeds.length) count++;

            if (count > 1) {
                double accel = Math.abs(speed - lastSpeed);
                accelerations[accelIdx] = accel;
                accelIdx = (accelIdx + 1) % accelerations.length;
            }

            if (speed > peakSpeed) peakSpeed = speed;
            lastSpeed = speed;
            lastActivity = time;

            if (!ewmaInit) {
                ewmaSpeed = speed;
                ewmaInit = true;
            } else {
                ewmaSpeed = EWMA_ALPHA * speed + (1.0 - EWMA_ALPHA) * ewmaSpeed;
            }

            updateWelford(speed);
        }

        private void updateWelford(double value) {
            if (welfordCount > 200) {
                welfordMean = ewmaSpeed;
                welfordM2 = getWelfordVariance() * 50;
                welfordCount = 50;
            }
            welfordCount++;
            double delta = value - welfordMean;
            welfordMean += delta / welfordCount;
            double delta2 = value - welfordMean;
            welfordM2 += delta * delta2;
        }

        double getWelfordVariance() {
            if (welfordCount < 2) return 0;
            return welfordM2 / (welfordCount - 1);
        }

        double getWelfordStdDev() {
            return Math.sqrt(getWelfordVariance());
        }

        double getEWMASpeed() {
            return ewmaSpeed;
        }

        double getAvgSpeed() {
            if (count == 0) return 0;
            double sum = 0;
            for (int i = 0; i < count; i++) sum += speeds[i];
            return sum / count;
        }

        double getMaxSpeed() {
            if (count == 0) return 0;
            double max = speeds[0];
            for (int i = 1; i < count; i++) {
                if (speeds[i] > max) max = speeds[i];
            }
            return max;
        }

        double getMedianSpeed() {
            if (count == 0) return 0;
            double[] sorted = new double[count];
            if (count < speeds.length) {
                System.arraycopy(speeds, 0, sorted, 0, count);
            } else {
                for (int i = 0; i < count; i++) {
                    sorted[i] = speeds[(speedIdx + i) % speeds.length];
                }
            }
            java.util.Arrays.sort(sorted);
            return sorted[count / 2];
        }

        double getAvgAccel() {
            if (count < 2) return 0;
            int accelCount = Math.min(count - 1, accelerations.length);
            double sum = 0;
            for (int i = 0; i < accelCount; i++) sum += accelerations[i];
            return sum / accelCount;
        }

        double getMaxAccel() {
            if (count < 2) return 0;
            int accelCount = Math.min(count - 1, accelerations.length);
            double max = accelerations[0];
            for (int i = 1; i < accelCount; i++) {
                if (accelerations[i] > max) max = accelerations[i];
            }
            return max;
        }

        boolean hasStableData() {
            return count >= 2;
        }

        boolean hasMinimalData() {
            return count >= 1;
        }

        synchronized void addViolation() {
            violations++;
            lastViolation = System.currentTimeMillis();
        }

        synchronized void decayViolations() {
            if (System.currentTimeMillis() - lastViolation > 10000 && violations > 0) {
                violations--;
            }
        }

        int getViolations() {
            return violations;
        }

        void resetViolations() {
            violations = 0;
        }

        void recordGroundContact(int ticks) {
            groundContactTimes[groundContactIdx] = ticks;
            groundContactIdx = (groundContactIdx + 1) % groundContactTimes.length;
            if (groundContactCount < groundContactTimes.length) groundContactCount++;
        }

        double getAvgGroundContact() {
            if (groundContactCount == 0) return 10;
            int sum = 0;
            for (int i = 0; i < groundContactCount; i++) sum += groundContactTimes[i];
            return (double) sum / groundContactCount;
        }

        int getQuickJumpCount() {
            int quick = 0;
            for (int i = 0; i < groundContactCount; i++) {
                if (groundContactTimes[i] <= 2) quick++;
            }
            return quick;
        }

        void addBhopViolation() {
            bhopViolations++;
            lastBhopCheck = System.currentTimeMillis();
        }

        void decayBhopViolations() {
            if (System.currentTimeMillis() - lastBhopCheck > 2000 && bhopViolations > 0) {
                bhopViolations--;
            }
        }

        int getBhopViolations() {
            return bhopViolations;
        }

        void resetBhop() {
            consecutiveQuickJumps = 0;
            bhopViolations = 0;
        }

        void recordStrafeAngle(double angle) {
            strafeAngles[strafeIdx] = angle;
            strafeIdx = (strafeIdx + 1) % strafeAngles.length;
            if (strafeCount < strafeAngles.length) strafeCount++;
        }

        int getPerpendicularCount() {
            int perp = 0;
            for (int i = 0; i < strafeCount; i++) {
                double angle = Math.abs(strafeAngles[i]);
                if (angle > 60 && angle < 120) perp++;
            }
            return perp;
        }

        void addStrafeViolation() {
            strafeViolations++;
        }

        void decayStrafeViolations() {
            if (strafeViolations > 0) strafeViolations--;
        }

        int getStrafeViolations() {
            return strafeViolations;
        }

        void resetStrafe() {
            strafeCount = 0;
            strafeViolations = 0;
        }

        void addSpeedRatio(double ratio) {
            speedRatios[ratioIdx] = ratio;
            ratioIdx = (ratioIdx + 1) % speedRatios.length;
            if (ratioCount < speedRatios.length) ratioCount++;
        }

        boolean hasConsistentSpeedViolation() {
            if (ratioCount < 8) return false;

            int aboveLimit = 0;
            double avgRatio = 0;
            for (int i = 0; i < ratioCount; i++) {
                avgRatio += speedRatios[i];
                if (speedRatios[i] > 1.01) aboveLimit++;
            }
            avgRatio /= ratioCount;

            return aboveLimit >= 6 && avgRatio > 1.03 && avgRatio < 1.30;
        }

        void addConsistentViolation() {
            consistentSpeedViolations++;
            lastConsistentCheck = System.currentTimeMillis();
        }

        void decayConsistent() {
            if (System.currentTimeMillis() - lastConsistentCheck > 5000 && consistentSpeedViolations > 0) {
                consistentSpeedViolations--;
            }
        }

        int getConsistentViolations() {
            return consistentSpeedViolations;
        }

        void resetConsistent() {
            consistentSpeedViolations = 0;
            ratioCount = 0;
        }

        void updateCUSUM(double deviation, double threshold) {
            cusum = Math.max(0, cusum + deviation - threshold);
        }

        double getCUSUM() {
            return cusum;
        }

        void resetCUSUM() {
            cusum = 0;
        }

        double getZScore(double speed) {
            if (welfordCount < WELFORD_WARMUP) return 0;
            double stdDev = getWelfordStdDev();
            if (stdDev < 0.01) return 0;
            return (speed - welfordMean) / stdDev;
        }
    }

    public SpeedCheck(ACConfig config, Map<UUID, PlayerData> playerDataMap, LayerFiltering filtering) {
        this.config = config;
        this.playerDataMap = playerDataMap;
        this.filtering = filtering;
        this.falsePositiveFilter = new FalsePositiveFilter();
        this.moveDataMap = new ConcurrentHashMap<>();
    }

    public CheckResult check(Player player, Location from, Location to) {
        if (!config.isCheckEnabled("speed")) {
            return CheckResult.passed();
        }

        PlayerData data = playerDataMap.get(player.getUniqueId());
        if (data == null) return CheckResult.passed();

        if (data.isInGracePeriod(config.getGracePeriod())) {
            falsePositiveFilter.recordLegitAction(player, ViolationType.SPEED);
            return CheckResult.passed();
        }

        if (player.isFlying() || player.getGameMode() == org.bukkit.GameMode.CREATIVE || player.getGameMode() == org.bukkit.GameMode.SPECTATOR) {
            falsePositiveFilter.recordLegitAction(player, ViolationType.SPEED);
            return CheckResult.passed();
        }

        if (data.flyDisableGraceTicks > 0) return CheckResult.passed();

        if (movementGrace != null && movementGrace.inMountGrace(player.getUniqueId(), System.currentTimeMillis())) {
            return CheckResult.passed();
        }

        if (WaterHelper.isInWater(player)) {
            return checkWaterSpeed(player, from, to);
        }

        if (WaterHelper.isInLiquid(player)) {
            return checkWaterSpeed(player, from, to);
        }

        Entity vehicle = player.getVehicle();
        if (vehicle != null) {
            return checkVehicleSpeed(player, vehicle, from, to);
        }

        if (player.isGliding()) {
            return checkElytraSpeed(player, from, to);
        }

        if (player.isRiptiding() || data.isInRiptideGrace()) {
            int riptideLevel = 0;
            org.bukkit.inventory.ItemStack mainHand = player.getInventory().getItemInMainHand();
            org.bukkit.inventory.ItemStack offHand = player.getInventory().getItemInOffHand();
            if (mainHand != null && mainHand.getType() == Material.TRIDENT) {
                riptideLevel = Math.max(riptideLevel, mainHand.getEnchantmentLevel(Enchantment.RIPTIDE));
            }
            if (offHand != null && offHand.getType() == Material.TRIDENT) {
                riptideLevel = Math.max(riptideLevel, offHand.getEnchantmentLevel(Enchantment.RIPTIDE));
            }
            if (riptideLevel <= 0) riptideLevel = 3;
            double dx = to.getX() - from.getX();
            double dz = to.getZ() - from.getZ();
            double horizontal = Math.sqrt(dx * dx + dz * dz);
            double peakBlocksPerTick = 1.0 + 1.0 * riptideLevel;
            double peakBlocksPerMove = peakBlocksPerTick * 1.5;
            if (horizontal > peakBlocksPerMove) {
                CheckResult riptideResult = CheckResult.failed(
                    ViolationType.SPEED_GROUND,
                    0.7,
                    String.format("Riptide bound exceeded: %.2f > %.2f (lvl %d)", horizontal, peakBlocksPerMove, riptideLevel)
                );
                if (filtering.passesLayer2HeuristicFiltering(player, ViolationType.SPEED_GROUND, riptideResult)) {
                    return riptideResult;
                }
            }
            falsePositiveFilter.recordLegitAction(player, ViolationType.SPEED);
            return CheckResult.passed();
        }

        if (isNearWindCharge(player)) {
            MoveData md = moveDataMap.computeIfAbsent(player.getUniqueId(), k -> new MoveData());
            md.lastWindChargeTime = System.currentTimeMillis();
            return CheckResult.passed();
        }

        return checkGroundSpeed(player, from, to, data);
    }

    private boolean isNearWindCharge(Player player) {
        try {
            return player.getNearbyEntities(5, 5, 5).stream()
                .anyMatch(e -> e.getType().name().contains("WIND_CHARGE"));
        } catch (Exception e) {
            Logger.getLogger("NoChance").log(Level.WARNING, "Failed to check nearby wind charges for " + player.getName(), e);
            return false;
        }
    }

    private CheckResult checkGroundSpeed(Player player, Location from, Location to, PlayerData data) {
        long timeSinceWater = System.currentTimeMillis() - data.getLastWaterTime();
        if (timeSinceWater < 1500) {
            return CheckResult.passed();
        }

        if (data.isInTeleportGracePeriod(config.getTeleportGracePeriod())) {
            return CheckResult.passed();
        }

        long timeSinceDamage = System.currentTimeMillis() - data.getLastDamageTime();
        if (timeSinceDamage < 500) {
            return CheckResult.passed();
        }

        long timeSinceVelocity = System.currentTimeMillis() - data.getLastVelocityTime();
        if (timeSinceVelocity < 800) {
            return CheckResult.passed();
        }

        long now0 = System.currentTimeMillis();
        if ((now0 - data.getLastSlimeContactTime()) < 1200) {
            return CheckResult.passed();
        }
        if ((now0 - data.getLastRiptideTime()) < 1500) {
            return CheckResult.passed();
        }
        if (data.riptideActiveTicks > 0) {
            return CheckResult.passed();
        }

        UUID playerId = player.getUniqueId();
        MoveData moveData = moveDataMap.computeIfAbsent(playerId, k -> new MoveData());

        long timeSinceWindCharge = System.currentTimeMillis() - moveData.lastWindChargeTime;
        if (timeSinceWindCharge < 3000) {
            return CheckResult.passed();
        }

        double dx = to.getX() - from.getX();
        double dy = to.getY() - from.getY();
        double dz = to.getZ() - from.getZ();
        double horizontalDist = Math.sqrt(dx * dx + dz * dz);
        double totalDist = Math.sqrt(dx * dx + dy * dy + dz * dz);

        falsePositiveFilter.recordMovement(player, totalDist);

        long now = System.currentTimeMillis();
        long timeDelta = now - data.getLastMoveTime();
        if (timeDelta < 20) {
            return CheckResult.passed();
        }

        double tps = getTPS();
        double tpsMultiplier = 20.0 / Math.max(tps, 10.0);

        double timeInSeconds = Math.max(timeDelta / 1000.0, 0.05);
        double actualSpeed = horizontalDist / timeInSeconds;
        double prevSpeedSnapshot = moveData.lastSpeed;
        moveData.addSpeed(actualSpeed, now);

        boolean onGround = player.isOnGround();
        boolean preSustainedAir = moveData.ticksInAir >= 5;

        long timeSinceVelAccel = System.currentTimeMillis() - data.getLastVelocityTime();
        long timeSinceTpAccel = System.currentTimeMillis() - data.getLastTeleportTime();
        if (!preSustainedAir && moveData.count >= 2 && timeSinceVelAccel > 800 && timeSinceTpAccel > 1000
                && actualSpeed > prevSpeedSnapshot + 0.6) {
            moveData.accelViolations++;
            if (moveData.accelViolations >= 3) {
                CheckResult accelResult = CheckResult.failed(
                    ViolationType.SPEED_GROUND,
                    0.85,
                    String.format("Accel cap: %.2f -> %.2f m/s (delta %.2f)",
                        prevSpeedSnapshot, actualSpeed, actualSpeed - prevSpeedSnapshot)
                );
                if (filtering.passesLayer2HeuristicFiltering(player, ViolationType.SPEED_GROUND, accelResult)) {
                    moveData.accelViolations = 0;
                    return accelResult;
                }
            }
        } else if (moveData.accelViolations > 0) {
            moveData.accelViolations--;
        }
        boolean jumping = dy > 0.3;

        if (onGround) {
            if (moveData.wasInAir && moveData.ticksInAir >= 3) {
                moveData.recordGroundContact(moveData.ticksOnGround);
                if (moveData.ticksOnGround <= 2 && moveData.speedBeforeLanding > 4.0) {
                    moveData.consecutiveQuickJumps++;
                } else {
                    moveData.consecutiveQuickJumps = Math.max(0, moveData.consecutiveQuickJumps - 1);
                }
            }
            moveData.ticksOnGround++;
            moveData.ticksInAir = 0;
            moveData.wasInAir = false;
            if (moveData.ticksOnGround > 10) {
                moveData.peakSpeed = Math.max(actualSpeed, moveData.peakSpeed * 0.85);
            }
        } else {
            if (!moveData.wasInAir && moveData.ticksOnGround > 2) {
                moveData.speedBeforeLanding = actualSpeed;
            }
            if (!moveData.wasInAir) {
                moveData.lastGroundTick = moveData.ticksOnGround;
            }
            moveData.ticksInAir++;
            moveData.ticksOnGround = 0;
            moveData.wasInAir = true;
        }

        Location loc = player.getLocation();
        Material belowType = BlockCache.getType(loc.getWorld(), loc.getBlockX(), loc.getBlockY() - 1, loc.getBlockZ());
        boolean onIce = isIceBlock(belowType);

        if (moveData.wasOnIce && !onIce && moveData.ticksOnGround < 10) {
            moveData.wasOnIce = false;
            moveData.peakSpeed /= 1.2;
            return CheckResult.passed();
        }
        moveData.wasOnIce = onIce;

        int ping = filtering.getPing(player);
        double pingMultiplier = 1.0;
        if (ping > 300) {
            pingMultiplier = 1.35;
        } else if (ping > 200) {
            pingMultiplier = 1.25;
        } else if (ping > 150) {
            pingMultiplier = 1.15;
        } else if (ping > 100) {
            pingMultiplier = 1.08;
        }

        double instantMaxSpeed = calculateMaxGroundSpeed(player, dy, timeInSeconds, belowType);
        instantMaxSpeed *= tpsMultiplier;
        double pingBonus = Math.min(0.15, Math.max(1, ping) / 2000.0);
        instantMaxSpeed += pingBonus + filtering.getLagCompensation(player);

        long slowExit = data.getLastSlowExitTime();
        if (slowExit > 0 && (System.currentTimeMillis() - slowExit) < 800) {
            instantMaxSpeed += 0.05;
        }

        boolean sustainedAir = moveData.ticksInAir >= 5;
        if (moveData.hasMinimalData() && !onIce && !sustainedAir) {
            if (actualSpeed > instantMaxSpeed * 1.30) {
                CheckResult instantResult = CheckResult.failed(
                    ViolationType.SPEED,
                    0.98,
                    String.format("INSTANT: Extreme speed %.2f/%.2f m/s (%.1fx)",
                        actualSpeed, instantMaxSpeed, actualSpeed / instantMaxSpeed)
                );
                if (filtering.passesLayer2HeuristicFiltering(player, ViolationType.SPEED, instantResult)) {
                    return instantResult;
                }
            }

            if (actualSpeed > instantMaxSpeed * 1.18 && moveData.count >= 3) {
                double prevSpeed = moveData.lastSpeed;
                if (prevSpeed > instantMaxSpeed * 1.12) {
                    CheckResult sustainedResult = CheckResult.failed(
                        ViolationType.SPEED,
                        0.94,
                        String.format("INSTANT: Sustained high speed %.2f/%.2f m/s",
                            actualSpeed, instantMaxSpeed)
                    );
                    if (filtering.passesLayer2HeuristicFiltering(player, ViolationType.SPEED, sustainedResult)) {
                        return sustainedResult;
                    }
                }
            }
        }

        if (!moveData.hasStableData() || moveData.count < MIN_SAMPLES_BASIC) {
            return CheckResult.passed();
        }

        moveData.decayBhopViolations();

        if (moveData.groundContactCount >= MIN_SAMPLES_BHOP && !onIce) {
            double avgContact = moveData.getAvgGroundContact();
            int quickJumps = moveData.getQuickJumpCount();

            if (avgContact < 2.5 && quickJumps >= 5 && moveData.lastGroundTick >= 1) {
                moveData.addBhopViolation();

                if (moveData.getBhopViolations() >= 6) {
                    double severity = 0.75 + (0.05 * Math.min(5, quickJumps - 4));

                    CheckResult bhopResult = CheckResult.failed(
                        ViolationType.SPEED_AIR,
                        severity,
                        String.format("BHop: AvgGround:%.1f QuickJumps:%d Consec:%d",
                            avgContact, quickJumps, moveData.consecutiveQuickJumps)
                    );

                    if (filtering.passesLayer2HeuristicFiltering(player, ViolationType.SPEED_AIR, bhopResult)) {
                        moveData.resetBhop();
                        return bhopResult;
                    }
                }
            }

            if (moveData.consecutiveQuickJumps >= 5 && player.isSprinting()) {
                double avgSpeed = moveData.getAvgSpeed();
                if (avgSpeed > 8.5) {
                    moveData.addBhopViolation();

                    if (moveData.getBhopViolations() >= 6) {
                        CheckResult bhopResult = CheckResult.failed(
                            ViolationType.SPEED_AIR,
                            0.82,
                            String.format("BHop momentum: %.2f m/s, %d quick jumps",
                                avgSpeed, moveData.consecutiveQuickJumps)
                        );

                        if (filtering.passesLayer2HeuristicFiltering(player, ViolationType.SPEED_AIR, bhopResult)) {
                            moveData.resetBhop();
                            return bhopResult;
                        }
                    }
                }
            }
        }

        if (!onGround && horizontalDist > 0.1 && !onIce) {
            float yaw = player.getLocation().getYaw();
            double facingX = -Math.sin(Math.toRadians(yaw));
            double facingZ = Math.cos(Math.toRadians(yaw));

            double moveMag = Math.sqrt(dx * dx + dz * dz);
            if (moveMag <= 1e-6) {
                return CheckResult.passed();
            }
            double moveNormX = dx / moveMag;
            double moveNormZ = dz / moveMag;

            double dot = facingX * moveNormX + facingZ * moveNormZ;
            double angleRad = Math.acos(Math.max(-1, Math.min(1, dot)));
            double angleDeg = Math.toDegrees(angleRad);

            moveData.recordStrafeAngle(angleDeg);

            if (moveData.strafeCount >= MIN_SAMPLES_STRAFE) {
                int perpCount = moveData.getPerpendicularCount();
                double avgSpeed = moveData.getAvgSpeed();

                if (perpCount >= 5 && avgSpeed > 4.0 && moveData.ticksInAir >= 5) {
                    moveData.addStrafeViolation();

                    if (moveData.getStrafeViolations() >= 6) {
                        CheckResult strafeResult = CheckResult.failed(
                            ViolationType.SPEED_STRAFE,
                            0.80,
                            String.format("Strafe: %d perp moves, %.2f m/s, %d air ticks",
                                perpCount, avgSpeed, moveData.ticksInAir)
                        );

                        if (filtering.passesLayer2HeuristicFiltering(player, ViolationType.SPEED_STRAFE, strafeResult)) {
                            moveData.resetStrafe();
                            return strafeResult;
                        }
                    }
                } else {
                    moveData.decayStrafeViolations();
                }
            }
        }

        double maxAllowedSpeed = calculateMaxGroundSpeed(player, dy, timeInSeconds, belowType);

        maxAllowedSpeed *= tpsMultiplier;
        double sustainedPingBonus = Math.min(0.15, Math.max(1, ping) / 2000.0);
        maxAllowedSpeed += sustainedPingBonus + filtering.getLagCompensation(player);

        if (isOnStairsOrSlabs(player)) {
            maxAllowedSpeed *= player.isSprinting() ? 1.38 : 1.25;
        }

        double avgSpeed = moveData.getAvgSpeed();
        double medianSpeed = moveData.getMedianSpeed();
        double speedToCheck = actualSpeed;

        moveData.decayViolations();
        moveData.decayConsistent();

        double speedRatio = maxAllowedSpeed > 0.01 ? speedToCheck / maxAllowedSpeed : 1.0;
        moveData.addSpeedRatio(speedRatio);

        if (moveData.ewmaInit && moveData.count >= 8 && !onIce && !sustainedAir) {
            double ewmaRatio = moveData.getEWMASpeed() / maxAllowedSpeed;
            moveData.updateCUSUM(ewmaRatio - 1.0, 0.015);

            if (moveData.getCUSUM() > 0.35 && ewmaRatio > 1.015) {
                CheckResult cusumResult = CheckResult.failed(
                    ViolationType.SPEED_GROUND,
                    Math.min(1.0, 0.72 + moveData.getCUSUM() * 0.25),
                    String.format("Sustained speed: EWMA=%.2f CUSUM=%.3f ratio=%.2f",
                        moveData.getEWMASpeed(), moveData.getCUSUM(), ewmaRatio)
                );

                if (filtering.passesLayer2HeuristicFiltering(player, ViolationType.SPEED_GROUND, cusumResult)) {
                    moveData.resetCUSUM();
                    return cusumResult;
                }
            }

            if (moveData.welfordCount >= 10) {
                double zScore = moveData.getZScore(actualSpeed);
                if (zScore > 2.8 && actualSpeed > maxAllowedSpeed * 0.97) {
                    moveData.addViolation();
                    if (moveData.getViolations() >= 2) {
                        CheckResult zResult = CheckResult.failed(
                            ViolationType.SPEED_GROUND,
                            Math.min(1.0, 0.65 + zScore * 0.04),
                            String.format("Anomaly: Z=%.2f speed=%.2f max=%.2f",
                                zScore, actualSpeed, maxAllowedSpeed)
                        );

                        if (filtering.passesLayer2HeuristicFiltering(player, ViolationType.SPEED_GROUND, zResult)) {
                            return zResult;
                        }
                    }
                }
            }
        }

        if (moveData.hasConsistentSpeedViolation() && !onIce && !sustainedAir) {
            moveData.addConsistentViolation();

            if (moveData.getConsistentViolations() >= 3) {
                CheckResult prelimResult = CheckResult.failed(
                    ViolationType.SPEED_GROUND,
                    0.85,
                    String.format("Consistent speed hack: ratio %.2fx over %d samples",
                        speedRatio, moveData.ratioCount)
                );

                if (filtering.passesLayer2HeuristicFiltering(player, ViolationType.SPEED_GROUND, prelimResult)) {
                    moveData.resetConsistent();
                    return prelimResult;
                }
            }
        }

        if (moveData.peakSpeed > maxAllowedSpeed * 3.0 && moveData.count >= 6 && !onIce) {
            double peakSeverity = Math.min(0.98, 0.80 + (moveData.peakSpeed / maxAllowedSpeed - 3.0) * 0.1);
            CheckResult peakResult = CheckResult.failed(
                ViolationType.SPEED_GROUND,
                peakSeverity,
                String.format("Peak speed: %.2f m/s (max: %.2f, ratio: %.1fx)",
                    moveData.peakSpeed, maxAllowedSpeed, moveData.peakSpeed / maxAllowedSpeed)
            );

            if (filtering.passesLayer2HeuristicFiltering(player, ViolationType.SPEED_GROUND, peakResult)) {
                moveData.peakSpeed = 0;
                return peakResult;
            }
        }

        if (actualSpeed > maxAllowedSpeed * 2.5 && !onIce) {
            CheckResult prelimResult = CheckResult.failed(
                ViolationType.SPEED_GROUND,
                0.95,
                String.format("Extreme speed: %.2f m/s (max: %.2f)", actualSpeed, maxAllowedSpeed)
            );

            if (filtering.passesLayer2HeuristicFiltering(player, ViolationType.SPEED_GROUND, prelimResult)) {
                return prelimResult;
            }
        }

        if (speedToCheck > maxAllowedSpeed) {
            double excess = speedToCheck - maxAllowedSpeed;
            double severityBase = excess / maxAllowedSpeed;
            double severity = Math.min(1.0, Math.pow(severityBase, 0.65));

            double avgAccel = moveData.getAvgAccel();
            double maxAccel = moveData.getMaxAccel();
            double maxExpectedAccel = maxAllowedSpeed * 0.6;

            if (maxAccel > maxExpectedAccel * 2.5) {
                severity = Math.min(1.0, severity * 1.3);
            }

            if (moveData.peakSpeed > maxAllowedSpeed * 2.0) {
                severity = Math.min(1.0, severity + (moveData.peakSpeed / maxAllowedSpeed - 2.0) * 0.08);
            }

            moveData.addViolation();

            if (excess < maxAllowedSpeed * 0.025 && moveData.getViolations() < 1) {
                return CheckResult.passed();
            }

            CheckResult prelimResult = CheckResult.failed(
                ViolationType.SPEED_GROUND,
                severity,
                String.format("Speed: %.2f/%.2f m/s | Avg: %.2f | Accel: %.2f | VL: %d",
                    actualSpeed, maxAllowedSpeed, avgSpeed, avgAccel, moveData.getViolations())
            );

            if (!filtering.passesLayer2HeuristicFiltering(player, ViolationType.SPEED_GROUND, prelimResult)) {
                return CheckResult.passed();
            }

            if (moveData.getViolations() >= 2) {
                moveData.resetViolations();
            }

            return prelimResult;
        }

        if (physicsValidator != null) {
            CheckResult phys = physicsValidator.validateSpeed(player, data, from, to);
            if (phys.isFailed() && filtering.passesLayer2HeuristicFiltering(player, phys.getViolationType(), phys)) {
                return phys;
            }
        }

        return CheckResult.passed();
    }

    private double calculateMaxGroundSpeed(Player player, double verticalMovement, double timeInSeconds, Material belowType) {
        double baseSpeed = 4.317;
        boolean isSprinting = player.isSprinting();
        boolean isJumping = verticalMovement > 0.08;
        boolean isFalling = verticalMovement < -0.08;

        if (isSprinting && isJumping) {
            baseSpeed = 7.2;
        } else if (isSprinting && isFalling) {
            baseSpeed = 5.612;
        } else if (isSprinting) {
            baseSpeed = 5.612;
        } else if (isJumping) {
            baseSpeed = 5.0;
        }

        PlayerData pd = playerDataMap.get(player.getUniqueId());
        long nowMs = System.currentTimeMillis();
        PotionEffectType speedType = PotionEffectType.getByName("SPEED");
        if (speedType == null) speedType = PotionEffectType.getByName("INCREASE_MOVEMENT_SPEED");
        if (speedType != null) {
            PotionEffect speedEffect = player.getPotionEffect(speedType);
            if (speedEffect != null) {
                int amp = speedEffect.getAmplifier() + 1;
                if (pd != null) pd.cacheSpeedEffect(amp, nowMs + speedEffect.getDuration() * 50L);
                baseSpeed *= (1.0 + (amp * 0.20));
            } else if (pd != null) {
                double decay = pd.getSpeedDecayFactor(2000);
                if (decay > 0) {
                    int amp = pd.getCachedSpeedAmp(2000);
                    baseSpeed *= (1.0 + (amp * 0.20 * decay));
                }
            }
        }

        if (isJumping) {
            PotionEffectType jumpType = PotionEffectType.getByName("JUMP_BOOST");
            if (jumpType == null) jumpType = PotionEffectType.getByName("JUMP");
            if (jumpType != null) {
                PotionEffect jumpEffect = player.getPotionEffect(jumpType);
                if (jumpEffect != null) {
                    int amp = jumpEffect.getAmplifier() + 1;
                    if (pd != null) pd.cacheJumpEffect(amp, nowMs + jumpEffect.getDuration() * 50L);
                    baseSpeed += amp * JUMP_BOOST_HORIZONTAL * 1.15;
                } else if (pd != null) {
                    int amp = pd.getCachedJumpAmp(1000);
                    if (amp > 0) baseSpeed += amp * JUMP_BOOST_HORIZONTAL;
                }
            }
        }

        PotionEffectType slowType = PotionEffectType.getByName("SLOWNESS");
        if (slowType == null) slowType = PotionEffectType.getByName("SLOW");
        if (slowType != null) {
            PotionEffect slowness = player.getPotionEffect(slowType);
            if (slowness != null) {
                int amp = slowness.getAmplifier() + 1;
                baseSpeed *= Math.max(0.08, 1.0 - (amp * 0.15));
            }
        }

        if (isIceBlock(belowType)) {
            double iceMult = config.getIceMultiplier();
            if (belowType == Material.BLUE_ICE) {
                baseSpeed = isSprinting && isJumping ? 19.0 : baseSpeed * iceMult * 1.28;
            } else {
                baseSpeed = isSprinting && isJumping ? 16.5 : baseSpeed * iceMult * 1.04;
            }
        } else if (belowType == Material.SOUL_SAND || belowType == Material.SOUL_SOIL) {
            org.bukkit.inventory.ItemStack boots = player.getInventory().getBoots();
            if (boots != null) {
                int soulSpeed = boots.getEnchantmentLevel(Enchantment.SOUL_SPEED);
                if (soulSpeed > 0) {
                    baseSpeed *= 1.3 + (soulSpeed * 0.105);
                } else {
                    baseSpeed *= 0.45;
                }
            } else {
                baseSpeed *= 0.45;
            }
        } else if (belowType == Material.HONEY_BLOCK) {
            baseSpeed *= 0.38;
        } else if (belowType == Material.COBWEB) {
            baseSpeed *= 0.22;
        } else if (belowType == Material.POWDER_SNOW) {
            org.bukkit.inventory.ItemStack boots = player.getInventory().getBoots();
            boolean hasLeatherBoots = boots != null && boots.getType() == Material.LEATHER_BOOTS;
            if (!hasLeatherBoots) {
                baseSpeed *= 0.55;
            }
        } else if (belowType == Material.SLIME_BLOCK && isJumping) {
            baseSpeed *= 1.8;
        }

        Material atType = BlockCache.getType(player.getLocation());
        if (atType == Material.COBWEB) {
            baseSpeed *= 0.22;
        } else if (atType == Material.SWEET_BERRY_BUSH) {
            baseSpeed *= 0.45;
        } else if (atType == Material.POWDER_SNOW) {
            baseSpeed *= 0.55;
        }

        if (player.getWalkSpeed() != 0.2f) {
            baseSpeed *= (player.getWalkSpeed() / 0.2f);
        }

        EnvironmentHelper.EnvironmentalFactors envFactors = EnvironmentHelper.calculate(player);
        baseSpeed *= envFactors.speedMultiplier;

        if (ViaHelper.isPre1_16(player)) {
            baseSpeed *= 1.08;
        }

        return VersionAdapter.adjustSpeedThreshold(player, baseSpeed * 1.04);
    }

    private boolean isIceBlock(Material type) {
        return type == Material.ICE || type == Material.PACKED_ICE ||
               type == Material.BLUE_ICE || type == Material.FROSTED_ICE;
    }

    private boolean isOnStairsOrSlabs(Player player) {
        Location loc = player.getLocation();
        Material below = BlockCache.getType(loc.getWorld(), loc.getBlockX(), loc.getBlockY() - 1, loc.getBlockZ());
        Material at = BlockCache.getType(loc);
        return BlockCache.nameContains(below, "STAIRS", "SLAB") || BlockCache.nameContains(at, "STAIRS", "SLAB");
    }

    private double getTPS() {
        return TPSCache.getTPS();
    }

    private CheckResult checkWaterSpeed(Player player, Location from, Location to) {
        double dx = to.getX() - from.getX();
        double dy = to.getY() - from.getY();
        double dz = to.getZ() - from.getZ();
        double horizontalDist = Math.sqrt(dx * dx + dz * dz);

        PlayerData data = playerDataMap.get(player.getUniqueId());
        if (data == null) return CheckResult.passed();

        if (data.isInTeleportGracePeriod(config.getTeleportGracePeriod())) {
            return CheckResult.passed();
        }

        if (player.isRiptiding()) {
            return CheckResult.passed();
        }

        long timeDelta = System.currentTimeMillis() - data.getLastMoveTime();
        if (timeDelta < 20) {
            return CheckResult.passed();
        }

        double tps = getTPS();
        double tpsMultiplier = 20.0 / Math.max(tps, 10.0);

        double timeInSeconds = Math.max(timeDelta / 1000.0, 0.05);
        double actualSpeed = horizontalDist / timeInSeconds;

        double maxWaterSpeed = 1.5;

        org.bukkit.inventory.ItemStack boots = player.getInventory().getBoots();
        if (boots != null) {
            int depthStrider = boots.getEnchantmentLevel(Enchantment.DEPTH_STRIDER);
            if (depthStrider > 0) {
                int dsLevel = Math.min(depthStrider, 3);
                double waterPenalty = 0.4;
                double dsMultiplier = waterPenalty + (1.0 - waterPenalty) * (dsLevel / 3.0);
                maxWaterSpeed = (1.5 / waterPenalty) * dsMultiplier;
            }
        }

        PotionEffectType dolphinType = PotionEffectType.getByName("DOLPHINS_GRACE");
        if (dolphinType != null) {
            PotionEffect dolphinsGrace = player.getPotionEffect(dolphinType);
            if (dolphinsGrace != null) {
                maxWaterSpeed = 10.0;
                if (boots != null) {
                    int depthStrider = boots.getEnchantmentLevel(Enchantment.DEPTH_STRIDER);
                    if (depthStrider > 0) {
                        maxWaterSpeed = 10.0 + (depthStrider * 9.0);
                    }
                }
            }
        }

        PotionEffectType conduitType = PotionEffectType.getByName("CONDUIT_POWER");
        if (conduitType != null) {
            PotionEffect conduit = player.getPotionEffect(conduitType);
            if (conduit != null) {
                int amp = conduit.getAmplifier() + 1;
                maxWaterSpeed *= (1.0 + (amp * 0.25));
            }
        }

        PotionEffectType speedType = PotionEffectType.getByName("SPEED");
        if (speedType == null) speedType = PotionEffectType.getByName("INCREASE_MOVEMENT_SPEED");
        if (speedType != null) {
            PotionEffect speedEffect = player.getPotionEffect(speedType);
            if (speedEffect != null) {
                int amp = speedEffect.getAmplifier() + 1;
                maxWaterSpeed *= (1.0 + (amp * 0.15));
            }
        }

        maxWaterSpeed *= 1.45 * tpsMultiplier;

        double lagComp = filtering.getLagCompensation(player);
        maxWaterSpeed += lagComp;

        int ping = filtering.getPing(player);
        double waterPingBonus = Math.min(0.15, Math.max(1, ping) / 2000.0);
        maxWaterSpeed += waterPingBonus;

        maxWaterSpeed *= WaterHelper.getSwimmingSpeedMultiplier(player);

        if (player.isSprinting()) {
            maxWaterSpeed *= 1.15;
        }

        if (actualSpeed > maxWaterSpeed) {
            double severity = Math.min(1.0, (actualSpeed - maxWaterSpeed) / maxWaterSpeed);

            CheckResult prelimResult = CheckResult.failed(
                ViolationType.SPEED,
                severity,
                String.format("Water: %.2f/%.2f m/s", actualSpeed, maxWaterSpeed)
            );

            if (!filtering.passesLayer2HeuristicFiltering(player, ViolationType.SPEED, prelimResult)) {
                return CheckResult.passed();
            }

            return prelimResult;
        }

        falsePositiveFilter.recordLegitAction(player, ViolationType.SPEED);
        return CheckResult.passed();
    }

    private CheckResult checkVehicleSpeed(Player player, Entity vehicle, Location from, Location to) {
        double dx = to.getX() - from.getX();
        double dz = to.getZ() - from.getZ();
        double horizontalDist = Math.sqrt(dx * dx + dz * dz);

        PlayerData data = playerDataMap.get(player.getUniqueId());
        if (data == null) return CheckResult.passed();

        if (data.isInTeleportGracePeriod(config.getTeleportGracePeriod())) {
            return CheckResult.passed();
        }

        long timeDelta = System.currentTimeMillis() - data.getLastMoveTime();
        if (timeDelta < 20) {
            return CheckResult.passed();
        }

        double tps = getTPS();
        double tpsMultiplier = 20.0 / Math.max(tps, 10.0);

        double timeInSeconds = Math.max(timeDelta / 1000.0, 0.05);
        double actualSpeed = horizontalDist / timeInSeconds;

        double maxVehicleSpeed = 8.5;

        if (vehicle instanceof Boat) {
            Block below = vehicle.getLocation().clone().subtract(0, 0.5, 0).getBlock();
            Material belowType = below.getType();

            if (belowType == Material.BLUE_ICE) {
                maxVehicleSpeed = 75.0;
            } else if (isIceBlock(belowType)) {
                maxVehicleSpeed = 42.0;
            } else {
                maxVehicleSpeed = 8.5;
            }
        } else if (vehicle.getType().name().contains("HORSE")) {
            maxVehicleSpeed = 15.0;
        } else if (vehicle.getType().name().contains("STRIDER")) {
            maxVehicleSpeed = 8.5;
        } else if (vehicle.getType().name().contains("PIG")) {
            maxVehicleSpeed = 7.5;
        } else if (vehicle.getType().name().contains("MINECART")) {
            maxVehicleSpeed = 8.5;
        } else if (vehicle.getType().name().contains("CAMEL")) {
            maxVehicleSpeed = 10.0;
        } else {
            maxVehicleSpeed = 13.0;
        }

        maxVehicleSpeed *= 1.35 * tpsMultiplier;

        double lagComp = filtering.getLagCompensation(player);
        maxVehicleSpeed += lagComp;

        if (actualSpeed > maxVehicleSpeed) {
            double severity = Math.min(1.0, (actualSpeed - maxVehicleSpeed) / maxVehicleSpeed);

            CheckResult prelimResult = CheckResult.failed(
                ViolationType.SPEED,
                severity,
                String.format("Vehicle: %.2f/%.2f m/s", actualSpeed, maxVehicleSpeed)
            );

            if (!filtering.passesLayer2HeuristicFiltering(player, ViolationType.SPEED, prelimResult)) {
                return CheckResult.passed();
            }

            return prelimResult;
        }

        falsePositiveFilter.recordLegitAction(player, ViolationType.SPEED);
        return CheckResult.passed();
    }

    private CheckResult checkElytraSpeed(Player player, Location from, Location to) {
        double dx = to.getX() - from.getX();
        double dy = to.getY() - from.getY();
        double dz = to.getZ() - from.getZ();
        double totalDist = Math.sqrt(dx * dx + dy * dy + dz * dz);

        PlayerData data = playerDataMap.get(player.getUniqueId());
        if (data == null) return CheckResult.passed();

        if (data.isInTeleportGracePeriod(config.getTeleportGracePeriod())) {
            return CheckResult.passed();
        }

        if (player.isRiptiding()) {
            return CheckResult.passed();
        }

        long now = System.currentTimeMillis();
        long timeSinceVelocity = now - data.getLastVelocityTime();
        if (timeSinceVelocity < 4000) {
            return CheckResult.passed();
        }

        long timeSinceDamage = now - data.getLastDamageTime();
        if (timeSinceDamage < 1500) {
            return CheckResult.passed();
        }

        long timeDelta = now - data.getLastMoveTime();
        if (timeDelta < 20) {
            return CheckResult.passed();
        }

        double tps = getTPS();
        double tpsMultiplier = 20.0 / Math.max(tps, 10.0);

        double timeInSeconds = Math.max(timeDelta / 1000.0, 0.05);
        double actualSpeed = totalDist / timeInSeconds;

        double maxElytraSpeed = 55.0;

        ItemStack mainHand = player.getInventory().getItemInMainHand();
        ItemStack offHand = player.getInventory().getItemInOffHand();
        boolean hasFirework = (mainHand != null && mainHand.getType() == Material.FIREWORK_ROCKET) ||
            (offHand != null && offHand.getType() == Material.FIREWORK_ROCKET);

        if (hasFirework) {
            maxElytraSpeed = 95.0;
        }

        maxElytraSpeed *= 1.4 * tpsMultiplier;

        double lagComp = filtering.getLagCompensation(player);
        maxElytraSpeed += lagComp;

        if (actualSpeed > maxElytraSpeed) {
            double severity = Math.min(1.0, (actualSpeed - maxElytraSpeed) / maxElytraSpeed);

            CheckResult prelimResult = CheckResult.failed(
                ViolationType.SPEED,
                severity,
                String.format("Elytra: %.2f/%.2f m/s", actualSpeed, maxElytraSpeed)
            );

            if (!filtering.passesLayer2HeuristicFiltering(player, ViolationType.SPEED, prelimResult)) {
                return CheckResult.passed();
            }

            return prelimResult;
        }

        falsePositiveFilter.recordLegitAction(player, ViolationType.SPEED);
        return CheckResult.passed();
    }

    public void cleanup(UUID playerId) {
        moveDataMap.remove(playerId);
        falsePositiveFilter.cleanup(playerId);
        if (physicsValidator != null) physicsValidator.resetState(playerId);
    }

    public void cleanupStale() {
        long now = System.currentTimeMillis();
        long maxAge = 300000;
        moveDataMap.entrySet().removeIf(e -> now - e.getValue().lastActivity > maxAge);
    }
}
