package NC.noChance.detection.movement;

import NC.noChance.core.*;
import NC.noChance.core.ViaHelper;
import NC.noChance.predict.PhysicsValidator;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffectType;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

public class FlyCheck {
    private final ACConfig config;
    private final Map<UUID, PlayerData> playerDataMap;
    private final LayerFiltering filtering;
    private final Map<UUID, FlyData> flyDataMap;
    private MovementGrace movementGrace;
    private PhysicsValidator physicsValidator;

    private static final double GRAVITY = 0.08;
    private static final double DRAG = 0.98;
    private static final double HORIZONTAL_DRAG = 0.91;
    private static final double JUMP_VELOCITY = 0.42;
    private static final double JUMP_BOOST_MULTIPLIER = 0.1;
    private static final double TERMINAL_VELOCITY = -3.92;
    private static final int MIN_AIR_TICKS_FOR_CHECK = 2;
    private static final int MIN_SAMPLES_FOR_PATTERN = 4;
    private static final long NORMAL_TICK_MS = 50;
    private static final int MAX_BATCH_TICKS = 6;

    public FlyCheck(ACConfig config, Map<UUID, PlayerData> playerDataMap, LayerFiltering filtering) {
        this.config = config;
        this.playerDataMap = playerDataMap;
        this.filtering = filtering;
        this.flyDataMap = new ConcurrentHashMap<>();
    }

    public void setMovementGrace(MovementGrace grace) {
        this.movementGrace = grace;
    }

    public void setPhysicsValidator(PhysicsValidator validator) {
        this.physicsValidator = validator;
    }

    public CheckResult check(Player player, Location from, Location to) {
        if (!config.isCheckEnabled("fly")) {
            return CheckResult.passed();
        }

        PlayerData data = playerDataMap.get(player.getUniqueId());
        if (data == null) return CheckResult.passed();

        if (PlayerData.hasLegitFlight(player)) return CheckResult.passed();
        if (data.flyDisableGraceTicks > 0) return CheckResult.passed();

        if (data.isInGracePeriod(config.getGracePeriod())) {
            return CheckResult.passed();
        }

        boolean inRiptideWindow = player.isRiptiding() || data.isInRiptideGrace();
        if (inRiptideWindow) {
            int riptideLevel = 0;
            ItemStack mainHand = player.getInventory().getItemInMainHand();
            ItemStack offHand = player.getInventory().getItemInOffHand();
            if (mainHand != null && mainHand.getType() == Material.TRIDENT) {
                riptideLevel = Math.max(riptideLevel, mainHand.getEnchantmentLevel(Enchantment.RIPTIDE));
            }
            if (offHand != null && offHand.getType() == Material.TRIDENT) {
                riptideLevel = Math.max(riptideLevel, offHand.getEnchantmentLevel(Enchantment.RIPTIDE));
            }
            if (riptideLevel <= 0) riptideLevel = 3;
            double dx = to.getX() - from.getX();
            double dy = to.getY() - from.getY();
            double dz = to.getZ() - from.getZ();
            double total = Math.sqrt(dx * dx + dy * dy + dz * dz);
            double peakBlocksPerTick = 1.0 + 1.0 * riptideLevel;
            double peakBlocksPerMove = peakBlocksPerTick * 1.5;
            if (total > peakBlocksPerMove) {
                return CheckResult.failed(
                    ViolationType.FLY,
                    0.7,
                    String.format("Riptide bound exceeded: %.2f > %.2f (lvl %d)", total, peakBlocksPerMove, riptideLevel)
                );
            }
            return CheckResult.passed();
        }

        double exemptDy = to.getY() - from.getY();
        if (isLegitimateFlightScenario(player, data, exemptDy)) {
            return CheckResult.passed();
        }

        if (movementGrace != null && movementGrace.inMountGrace(player.getUniqueId(), System.currentTimeMillis())) {
            return CheckResult.passed();
        }

        FlyData flyData = flyDataMap.computeIfAbsent(player.getUniqueId(), k -> new FlyData());

        long checkNow = System.currentTimeMillis();
        long lastCheck = flyData.getLastCheckTime();
        int ticksElapsed = 1;
        if (lastCheck > 0) {
            long elapsed = checkNow - lastCheck;
            ticksElapsed = (int) Math.max(1, Math.min(MAX_BATCH_TICKS, Math.round(elapsed / (double) NORMAL_TICK_MS)));
        }
        flyData.setLastCheckTime(checkNow);

        double deltaY = to.getY() - from.getY();
        boolean onGround = isNearGround(to);
        boolean wasOnGround = data.wasOnGround();

        if (ticksElapsed > 1) {
            double avgTickVelocity = deltaY / ticksElapsed;
            flyData.setLastVelocity(avgTickVelocity);
            if (!onGround) {
                data.incrementAirTicks();
                data.setWasOnGround(false);
            }
            flyData.decrementViolationCount();
            return CheckResult.passed();
        }

        if (TPSCache.getTPS() < 17.0) {
            flyData.setLastVelocity(deltaY);
            if (!onGround) {
                data.incrementAirTicks();
                data.setWasOnGround(false);
            }
            return CheckResult.passed();
        }

        if (onGround) {
            data.setLastGroundTime(System.currentTimeMillis());
            data.resetAirTicks();
            flyData.reset();
            data.setWasOnGround(true);
            return CheckResult.passed();
        }

        data.incrementAirTicks();
        int airTicks = data.getAirTicks();

        if (isNearBouncyBlock(to)) {
            flyData.setLastBouncyTime(System.currentTimeMillis());
            Material below = BlockCache.getType(to.getWorld(), to.getBlockX(), to.getBlockY() - 1, to.getBlockZ());
            if (below == Material.SLIME_BLOCK) data.touchSlimeContact();
            else if (below == Material.HONEY_BLOCK) data.touchHoneyContact();
            return CheckResult.passed();
        }

        long now = System.currentTimeMillis();

        long timeSinceBouncy = now - flyData.getLastBouncyTime();
        if (timeSinceBouncy < 1500) {
            return CheckResult.passed();
        }

        if ((now - data.getLastSlimeContactTime()) < 1200 || (now - data.getLastHoneyContactTime()) < 800) {
            return CheckResult.passed();
        }

        if (isNearPiston(to)) {
            flyData.setLastPistonTime(now);
            return CheckResult.passed();
        }

        long timeSincePiston = now - flyData.getLastPistonTime();
        if (timeSincePiston < 800) {
            return CheckResult.passed();
        }

        if (isNearWindCharge(player)) {
            flyData.setLastWindChargeTime(now);
            return CheckResult.passed();
        }

        if (hasRecentBreezeDamage(player, data, now)) {
            flyData.setLastWindChargeTime(now);
            return CheckResult.passed();
        }

        long timeSinceWindCharge = now - flyData.getLastWindChargeTime();
        if (timeSinceWindCharge < 3000) {
            return CheckResult.passed();
        }

        if (player.isGliding() && hasFireworkBoost(player)) {
            flyData.setLastFireworkTime(now);
            return CheckResult.passed();
        }

        long timeSinceFirework = now - flyData.getLastFireworkTime();
        if (timeSinceFirework < 2500) {
            return CheckResult.passed();
        }

        long timeSinceKnockback = now - data.getLastVelocityTime();
        if (timeSinceKnockback < 1500) {
            return CheckResult.passed();
        }

        long timeSinceTeleport = now - data.getLastTeleportTime();
        if (timeSinceTeleport < 600) {
            flyData.resetViolationCount();
            return CheckResult.passed();
        }

        CheckResult spiderCheck = checkSpider(player, from, to, flyData, data);
        if (spiderCheck.isFailed()) return spiderCheck;

        CheckResult lbFingerprint = checkLiquidBounceFingerprints(player, data, flyData, from, to, deltaY, airTicks);
        if (lbFingerprint.isFailed()) return lbFingerprint;

        CheckResult lbBhop = checkMatrix7AndVulcanBhop(player, flyData, deltaY, airTicks);
        if (lbBhop.isFailed()) return lbBhop;

        double predictedVelocityY = calculatePredictedVelocity(player, data, flyData, wasOnGround, deltaY);
        double tolerance = calculateTolerance(player, data, airTicks, to);

        double hDeltaX = to.getX() - from.getX();
        double hDeltaZ = to.getZ() - from.getZ();
        double hSpeed = Math.sqrt(hDeltaX * hDeltaX + hDeltaZ * hDeltaZ);
        double expectedHSpeed = hSpeed * HORIZONTAL_DRAG;
        if (hSpeed > 0.3 && hSpeed > expectedHSpeed * 1.5 && airTicks > 6) {
            tolerance *= 0.85;
        }

        if (config.isHorizontalFlyEnabled()) {
            long sinceDamage = now - data.getLastDamageTime();
            long sinceVelocity = now - data.getLastVelocityTime();
            long sinceTeleport = now - data.getLastTeleportTime();
            double prevDy = flyData.getLastVelocity();
            boolean wasFalling = prevDy < -0.15;
            boolean sustainedLevel = !wasFalling && deltaY > -0.05 && deltaY < 0.05;
            if (hSpeed > 0.5 && sustainedLevel && airTicks > 80
                    && sinceDamage > 1500 && sinceVelocity > 1500 && sinceTeleport > 2000
                    && !player.isInWater() && !player.isClimbing()) {
                CheckResult horizontalFly = CheckResult.failed(
                    ViolationType.FLY,
                    0.80,
                    String.format("FlyHorizontal hSpeed=%.2f dY=%.3f prevDy=%.3f air=%d", hSpeed, deltaY, prevDy, airTicks)
                );
                if (filtering.passesLayer2HeuristicFiltering(player, ViolationType.FLY, horizontalFly)) {
                    flyData.setLastVelocity(deltaY);
                    data.setWasOnGround(false);
                    return horizontalFly;
                }
            }
        }

        data.updateVelocityHistory(hDeltaX, deltaY, hDeltaZ);
        flyData.recordVelocity(deltaY);

        if (wasOnGround) {
            flyData.setJumpStart(System.currentTimeMillis());
            if (deltaY > 0) {
                flyData.setExpectedVelocity(getJumpVelocity(player, to, deltaY));
            } else {
                flyData.setExpectedVelocity(0.0);
            }
            flyData.setLastVelocity(deltaY);
            data.setWasOnGround(false);
            return CheckResult.passed();
        }

        double deviation = deltaY - predictedVelocityY;

        double expectedVel = flyData.getExpectedVelocity();
        double decayedExpected = (expectedVel - GRAVITY) * DRAG;
        flyData.setExpectedVelocity(decayedExpected);

        if (airTicks >= 3) {
            double pureDev = deltaY - decayedExpected;
            if (Math.abs(pureDev) > tolerance && Math.abs(deltaY) > 0.05) {
                if (Math.abs(pureDev) > Math.abs(deviation)) {
                    deviation = pureDev;
                }
            }
        }

        long timeSinceJump = now - flyData.getJumpStartTime();
        boolean inJumpArc = flyData.getJumpStartTime() > 0 && timeSinceJump < 1200;

        if (Math.abs(deviation) > tolerance) {
            boolean extremeDeviation = Math.abs(deviation) > tolerance * 3.5;

            if (inJumpArc && airTicks < 20 && !extremeDeviation) {
                flyData.setLastVelocity(deltaY);
                data.setWasOnGround(false);
                return CheckResult.passed();
            }

            if (deltaY < -0.1 && flyData.getViolationCount() < 1 && airTicks < 12 && !extremeDeviation) {
                flyData.setLastVelocity(deltaY);
                data.setWasOnGround(false);
                return CheckResult.passed();
            }

            flyData.incrementViolationCount();

            boolean highDeviation = Math.abs(deviation) > tolerance * 1.5;

            if (!highDeviation && flyData.getViolationCount() < 2) {
                flyData.setLastVelocity(deltaY);
                data.setWasOnGround(false);
                return CheckResult.passed();
            }

            if (airTicks < MIN_AIR_TICKS_FOR_CHECK) {
                flyData.setLastVelocity(deltaY);
                data.setWasOnGround(false);
                return CheckResult.passed();
            }

            double severityBase = Math.abs(deviation) / Math.max(tolerance, 0.05);
            double severity = Math.min(0.98, 0.62 + (severityBase * 0.18) + (flyData.getViolationCount() * 0.02));

            ViolationType flyType = ViolationType.FLY;
            if (Math.abs(deltaY) < 0.04 && deviation > 0.02 && airTicks > 8) {
                flyType = ViolationType.FLY_HOVER;
                severity = Math.min(0.96, severity + 0.05);
            } else if (deltaY > 0.08 && deviation > 0 && airTicks > 5) {
                flyType = ViolationType.FLY_VERTICAL;
                severity = Math.min(0.98, severity + 0.08);
            } else if (deltaY < -0.08 && deltaY > TERMINAL_VELOCITY && deviation > 0.22) {
                flyType = ViolationType.FLY_GLIDE;
            }

            if (flyData.getVelocityHistorySize() >= MIN_SAMPLES_FOR_PATTERN && isConsistentFlyPattern(flyData)) {
                severity = Math.min(0.98, severity + 0.10);
            }

            CheckResult prelimResult = CheckResult.failed(
                flyType,
                severity,
                String.format("Y:%.3f Pred:%.3f Dev:%.3f Tol:%.3f Air:%d VL:%d",
                    deltaY, predictedVelocityY, deviation, tolerance, airTicks, flyData.getViolationCount())
            );

            if (!filtering.passesLayer2HeuristicFiltering(player, flyType, prelimResult)) {
                flyData.decrementViolationCount();
                flyData.setLastVelocity(deltaY);
                data.setWasOnGround(false);
                return CheckResult.passed();
            }

            flyData.setLastVelocity(deltaY);
            data.setWasOnGround(false);
            return prelimResult;
        } else {
            flyData.decrementViolationCount();
        }

        flyData.setLastVelocity(deltaY);
        data.setWasOnGround(false);

        if (physicsValidator != null) {
            CheckResult phys = physicsValidator.validateFly(player, data, from, to);
            if (phys.isFailed() && filtering.passesLayer2HeuristicFiltering(player, phys.getViolationType(), phys)) {
                return phys;
            }
        }

        return CheckResult.passed();
    }

    private boolean isLegitimateFlightScenario(Player player, PlayerData data, double dy) {
        if (!player.hasGravity()) return true;
        if (player.getGameMode() == org.bukkit.GameMode.SPECTATOR) return true;
        if (player.getAllowFlight() && player.isFlying()) return true;
        if (player.isGliding()) {
            ItemStack chest = player.getInventory().getChestplate();
            if (chest != null && chest.getType() == Material.ELYTRA) return true;
        }
        if (player.isSwimming()) return true;
        if (player.isClimbing() && hasClimbableBlock(player.getLocation())) return true;
        if (player.isRiptiding()) {
            data.touchRiptide();
            return false;
        }
        if (data.isInRiptideGrace()) return false;
        org.bukkit.entity.Entity vehicle = player.getVehicle();
        if (vehicle instanceof org.bukkit.entity.Vehicle) return true;

        long now = System.currentTimeMillis();
        PotionEffectType levType = PotionEffectType.getByName("LEVITATION");
        if (levType != null && player.hasPotionEffect(levType)) {
            var eff = player.getPotionEffect(levType);
            if (eff != null) {
                data.cacheLevitationEffect(eff.getAmplifier() + 1, now + eff.getDuration() * 50L);
            }
        } else {
            data.cacheLevitationEffect(0, 0);
        }

        PotionEffectType slowFallType = PotionEffectType.getByName("SLOW_FALLING");
        if (slowFallType != null && player.hasPotionEffect(slowFallType)) {
            var eff = player.getPotionEffect(slowFallType);
            if (eff != null) data.cacheSlowFallingExpiry(now + eff.getDuration() * 50L);
            if (dy >= -0.01 && dy <= 0) return true;
        } else {
            data.cacheSlowFallingExpiry(0);
        }
        if (data.hadRecentSlowFalling(1500) && dy >= -0.01 && dy <= 0) return true;

        if (WaterHelper.isInWater(player)) {
            data.setLastWaterTime(System.currentTimeMillis());
            return true;
        }

        long timeSinceWater = System.currentTimeMillis() - data.getLastWaterTime();
        if (timeSinceWater < 1500) return true;

        EnhancementTracker.MovementEnhancements enhancements = EnhancementTracker.calculateMovementEnhancements(player);
        if (enhancements.hasLegitFlight) return true;

        Location loc = player.getLocation();
        Material at = BlockCache.getType(loc);
        Material below = BlockCache.getType(loc.getWorld(), loc.getBlockX(), loc.getBlockY() - 1, loc.getBlockZ());

        if (at == Material.COBWEB || at == Material.POWDER_SNOW || at == Material.SCAFFOLDING || at == Material.BUBBLE_COLUMN) return true;
        if (BlockCache.nameContains(at, "VINE", "LADDER")) return true;

        if (below == Material.SLIME_BLOCK || below == Material.HONEY_BLOCK) return true;
        if (BlockCache.nameContains(below, "BED")) return true;

        if (player.isGliding() && hasFireworkBoost(player)) return true;

        return false;
    }

    private boolean hasFireworkBoost(Player player) {
        ItemStack mainHand = player.getInventory().getItemInMainHand();
        ItemStack offHand = player.getInventory().getItemInOffHand();
        return isFireworkRocket(mainHand) || isFireworkRocket(offHand);
    }

    private boolean isFireworkRocket(ItemStack item) {
        return item != null && item.getType() == Material.FIREWORK_ROCKET;
    }

    private boolean isNearBouncyBlock(Location loc) {
        World world = loc.getWorld();
        if (world == null) return false;
        int x = loc.getBlockX();
        int z = loc.getBlockZ();
        int baseY = loc.getBlockY();

        for (int dy = -2; dy <= 0; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    Material type = BlockCache.getType(world, x + dx, baseY + dy, z + dz);
                    if (type == Material.SLIME_BLOCK || type == Material.HONEY_BLOCK || BlockCache.nameContains(type, "BED")) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private boolean isNearPiston(Location loc) {
        World world = loc.getWorld();
        if (world == null) return false;
        int x = loc.getBlockX();
        int z = loc.getBlockZ();
        int baseY = loc.getBlockY();

        for (int dy = -2; dy <= 1; dy++) {
            for (int dx = -2; dx <= 2; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    Material type = BlockCache.getType(world, x + dx, baseY + dy, z + dz);
                    if (type == Material.PISTON || type == Material.STICKY_PISTON ||
                        type == Material.PISTON_HEAD || type == Material.MOVING_PISTON) {
                        return true;
                    }
                }
            }
        }
        return false;
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

    private boolean hasRecentBreezeDamage(Player player, PlayerData data, long now) {
        try {
            long timeSinceDamage = now - data.getLastDamageTime();
            if (timeSinceDamage > 3000) return false;

            return player.getNearbyEntities(10, 10, 10).stream()
                .anyMatch(e -> e.getType().name().contains("BREEZE"));
        } catch (Exception e) {
            Logger.getLogger("NoChance").log(Level.WARNING, "Failed to check recent breeze damage for " + player.getName(), e);
            return false;
        }
    }

    private double calculatePredictedVelocity(Player player, PlayerData data, FlyData flyData, boolean wasOnGround, double actualVelocity) {
        double lastVelocity = flyData.getLastVelocity();

        if (wasOnGround) {
            return 0.0;
        }

        if (player.isGliding()) {
            boolean firework = hasFireworkBoost(player);
            if (firework) {
                return Math.min(actualVelocity, 3.5);
            }
            return Math.min(actualVelocity, 0.8);
        }

        double predicted = (lastVelocity - GRAVITY) * DRAG;

        PotionEffectType slowFallType = PotionEffectType.getByName("SLOW_FALLING");
        if (slowFallType != null && player.hasPotionEffect(slowFallType)) {
            predicted = Math.max(predicted, -0.01);
        } else if (data.hadRecentSlowFalling(1500)) {
            predicted = Math.max(predicted, -0.01);
        }

        PotionEffectType levType = PotionEffectType.getByName("LEVITATION");
        if (levType != null && player.hasPotionEffect(levType)) {
            var eff = player.getPotionEffect(levType);
            int amplifier = eff.getAmplifier();
            int durationTicks = eff.getDuration();
            double scale = durationTicks < 20 ? (durationTicks / 20.0) : 1.0;
            predicted += 0.05 * (amplifier + 1) * scale;
        } else if (data.hadRecentLevitation(1500)) {
            predicted += 0.04;
        }

        Location loc = player.getLocation();
        Material blockAt = BlockCache.getType(loc);
        if (blockAt == Material.BUBBLE_COLUMN) {
            return actualVelocity;
        }

        if (blockAt == Material.WATER || BlockCache.nameContains(blockAt, "WATER")) {
            predicted *= 0.5;
            double buoyancy = WaterHelper.getBuoyancyEffect(player);
            if (buoyancy != 0.0) {
                predicted += buoyancy;
            }
        }

        if (blockAt == Material.LAVA || BlockCache.nameContains(blockAt, "LAVA")) {
            predicted *= 0.4;
        }

        if (blockAt == Material.COBWEB) {
            predicted *= 0.15;
        }

        return predicted;
    }

    private double calculateTolerance(Player player, PlayerData data, int airTicks, Location loc) {
        double baseTolerance = 0.06;

        if (player.isGliding()) {
            baseTolerance = 0.20;
        }

        int ping = filtering.getPing(player);
        baseTolerance += Math.min(0.15, Math.max(1, ping) / 3000.0);

        if (airTicks < 4) {
            baseTolerance += 0.10;
        } else if (airTicks < 8) {
            baseTolerance += 0.05;
        } else if (airTicks < 14) {
            baseTolerance += 0.03;
        } else if (airTicks < 20) {
            baseTolerance += 0.02;
        }

        if (player.isSwimming() || player.isClimbing()) {
            baseTolerance += 0.35;
        }

        if (player.getVehicle() != null) {
            baseTolerance += 0.7;
        }

        World world = loc.getWorld();
        if (world != null) {
            Material below = BlockCache.getType(world, loc.getBlockX(), loc.getBlockY() - 1, loc.getBlockZ());
            if (below == Material.SLIME_BLOCK) {
                baseTolerance += 1.2;
            } else if (below == Material.HONEY_BLOCK) {
                baseTolerance += 0.9;
            }
            if (below.name().contains("BED")) {
                baseTolerance += 1.1;
            }

            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    Material nearby = BlockCache.getType(world, loc.getBlockX() + dx, loc.getBlockY() - 1, loc.getBlockZ() + dz);
                    if (nearby.name().contains("BED")) {
                        baseTolerance += 0.3;
                        break;
                    }
                }
            }

            if (BlockCache.nameContains(below, "PISTON") || below == Material.MOVING_PISTON) {
                baseTolerance += 0.8;
            }

            Material at = BlockCache.getType(loc);
            if (at == Material.SCAFFOLDING) {
                baseTolerance += 0.45;
            } else if (at == Material.POWDER_SNOW) {
                baseTolerance += 0.35;
            } else if (at == Material.COBWEB) {
                baseTolerance += 0.40;
            } else if (at == Material.BUBBLE_COLUMN) {
                baseTolerance += 0.80;
            }
        }

        if (player.isRiptiding()) {
            baseTolerance += 1.8;
        }

        long timeSinceVelocity = System.currentTimeMillis() - data.getLastVelocityTime();
        if (timeSinceVelocity < 3500) {
            double knockbackBonus = data.getLastKnockbackVelocity() * Math.max(0, 1.0 - (timeSinceVelocity / 3500.0));
            baseTolerance += knockbackBonus * 1.2;
        }

        double tps = TPSCache.getTPS();
        if (tps < 18.0) {
            baseTolerance += (20.0 - tps) * 0.02;
        }

        baseTolerance += ViaHelper.getMovementTolerance(player);

        baseTolerance = VersionAdapter.adjustFlyThreshold(player, baseTolerance);

        EnvironmentHelper.EnvironmentalFactors envFactors = EnvironmentHelper.calculate(player);
        if (envFactors.gravityMultiplier != 1.0) {
            baseTolerance *= Math.max(1.0, Math.abs(envFactors.gravityMultiplier));
        }

        if (EnvironmentHelper.isInDangerousEnvironment(player)) {
            baseTolerance += 0.12;
        }

        return baseTolerance;
    }

    private double getJumpVelocity(Player player, Location loc, double deltaY) {
        double jumpVel = JUMP_VELOCITY;

        PotionEffectType jumpType = PotionEffectType.getByName("JUMP_BOOST");
        if (jumpType == null) jumpType = PotionEffectType.getByName("JUMP");
        if (jumpType != null && player.hasPotionEffect(jumpType)) {
            int amplifier = player.getPotionEffect(jumpType).getAmplifier();
            jumpVel += JUMP_BOOST_MULTIPLIER * (amplifier + 1);
        }

        World world = loc.getWorld();
        if (world != null) {
            Material below = BlockCache.getType(world, loc.getBlockX(), loc.getBlockY() - 1, loc.getBlockZ());
            if (below == Material.SLIME_BLOCK) {
                jumpVel = Math.max(Math.max(jumpVel * 2.5, Math.abs(deltaY) * 1.1), 1.0);
            } else if (below == Material.HONEY_BLOCK) {
                jumpVel *= 0.4;
                jumpVel += 0.2;
            }
        }

        return jumpVel;
    }

    private CheckResult checkLiquidBounceFingerprints(Player player, PlayerData data, FlyData flyData,
                                                       Location from, Location to, double deltaY, int airTicks) {
        if (airTicks < 6) {
            flyData.resetFlatTicks();
            flyData.resetVulcanGroundTicks();
            flyData.resetZeroTicks();
            return CheckResult.passed();
        }

        long sinceJump = System.currentTimeMillis() - flyData.getJumpStartTime();
        if (flyData.getJumpStartTime() > 0 && sinceJump < 1000) {
            flyData.resetFlatTicks();
            flyData.resetVulcanGroundTicks();
            flyData.resetZeroTicks();
            return CheckResult.passed();
        }

        double hDx = to.getX() - from.getX();
        double hDz = to.getZ() - from.getZ();
        double hSpeed = Math.sqrt(hDx * hDx + hDz * hDz);

        if (deltaY >= 0.0310 && deltaY <= 0.0325 && hSpeed > 0.6) {
            flyData.bumpFlatTicks();
            if (flyData.getFlatTicks() >= 4) {
                CheckResult r = CheckResult.failed(ViolationType.FLY_VERTICAL, 0.94,
                    String.format("LB.HypixelFlat dy=%.4f h=%.2f air=%d", deltaY, hSpeed, airTicks));
                if (filtering.passesLayer2HeuristicFiltering(player, ViolationType.FLY_VERTICAL, r)) return r;
            }
        } else {
            flyData.resetFlatTicks();
        }

        if (deltaY > 0.0 && deltaY <= 0.006 && hSpeed >= 0.40) {
            flyData.bumpVulcanGroundTicks();
            if (flyData.getVulcanGroundTicks() >= 5) {
                CheckResult r = CheckResult.failed(ViolationType.FLY, 0.95,
                    String.format("LB.VulcanGround dy=%.4f h=%.3f air=%d", deltaY, hSpeed, airTicks));
                if (filtering.passesLayer2HeuristicFiltering(player, ViolationType.FLY, r)) return r;
            }
        } else {
            flyData.resetVulcanGroundTicks();
        }

        if (Math.abs(deltaY) < 0.0008 && hSpeed > 0.08) {
            flyData.bumpZeroTicks();
            if (flyData.getZeroTicks() >= 4) {
                CheckResult r = CheckResult.failed(ViolationType.FLY_HOVER, 0.92,
                    String.format("LB.AirWalk zeroDy ticks=%d air=%d", flyData.getZeroTicks(), airTicks));
                if (filtering.passesLayer2HeuristicFiltering(player, ViolationType.FLY_HOVER, r)) return r;
            }
        } else {
            flyData.resetZeroTicks();
        }

        return CheckResult.passed();
    }

    private CheckResult checkMatrix7AndVulcanBhop(Player player, FlyData flyData, double deltaY, int airTicks) {
        if (Math.abs(deltaY - 0.419652) < 1e-4 && airTicks <= 1) {
            CheckResult r = CheckResult.failed(ViolationType.SPEED_GROUND, 0.93,
                String.format("LB.Matrix7 dy=%.6f", deltaY));
            if (filtering.passesLayer2HeuristicFiltering(player, ViolationType.SPEED_GROUND, r)) return r;
        }

        if (deltaY < -0.35 && airTicks >= 2 && airTicks <= 4) {
            double prev = flyData.getLastVelocity();
            if (prev > 0.10) {
                CheckResult r = CheckResult.failed(ViolationType.FLY, 0.90,
                    String.format("LB.Vulcan286BHop dy=%.3f prev=%.3f air=%d", deltaY, prev, airTicks));
                if (filtering.passesLayer2HeuristicFiltering(player, ViolationType.FLY, r)) return r;
            }
        }

        return CheckResult.passed();
    }

    private CheckResult checkSpider(Player player, Location from, Location to, FlyData flyData, PlayerData data) {
        if (player.isClimbing() || player.isGliding() || player.isSwimming()) return CheckResult.passed();

        double deltaY = to.getY() - from.getY();
        if (deltaY <= 0.15) return CheckResult.passed();

        boolean nextToWall = isNextToWall(to);
        if (!nextToWall) return CheckResult.passed();

        boolean hasLadder = hasClimbableBlock(to);
        if (hasLadder) return CheckResult.passed();

        long timeSinceGround = System.currentTimeMillis() - data.getLastGroundTime();
        if (timeSinceGround < 500) return CheckResult.passed();

        long timeSinceVelocity = System.currentTimeMillis() - data.getLastVelocityTime();
        if (timeSinceVelocity < 2000) return CheckResult.passed();

        flyData.recordWallClimb();

        if (flyData.getWallClimbCount() < 4) {
            return CheckResult.passed();
        }

        double severity = Math.min(0.95, 0.75 + (flyData.getWallClimbCount() * 0.05));

        CheckResult prelimResult = CheckResult.failed(
            ViolationType.SPIDER,
            severity,
            String.format("Spider: dY=%.3f climbs=%d", deltaY, flyData.getWallClimbCount())
        );

        if (!filtering.passesLayer2HeuristicFiltering(player, ViolationType.SPIDER, prelimResult)) {
            flyData.resetWallClimb();
            return CheckResult.passed();
        }

        return prelimResult;
    }

    private boolean isNextToWall(Location loc) {
        World world = loc.getWorld();
        if (world == null) return false;
        int bx = loc.getBlockX();
        int by = loc.getBlockY();
        int bz = loc.getBlockZ();

        if (BlockCache.isSolid(world, bx + 1, by, bz)) return true;
        if (BlockCache.isSolid(world, bx - 1, by, bz)) return true;
        if (BlockCache.isSolid(world, bx, by, bz + 1)) return true;
        if (BlockCache.isSolid(world, bx, by, bz - 1)) return true;
        return false;
    }

    private boolean hasClimbableBlock(Location loc) {
        World world = loc.getWorld();
        if (world == null) return false;
        int x = loc.getBlockX();
        int z = loc.getBlockZ();
        int baseY = loc.getBlockY();

        for (int y = baseY; y <= baseY + 2; y++) {
            Material type = BlockCache.getType(world, x, y, z);
            if (BlockCache.nameContains(type, "LADDER", "VINE", "SCAFFOLDING") ||
                type == Material.TWISTING_VINES || type == Material.WEEPING_VINES || type == Material.CAVE_VINES) {
                return true;
            }
        }
        return false;
    }

    private boolean isNearGround(Location loc) {
        World world = loc.getWorld();
        if (world == null) return false;
        int x = loc.getBlockX();
        int z = loc.getBlockZ();
        int baseY = loc.getBlockY();

        for (int y = baseY; y >= baseY - 1; y--) {
            Material type = BlockCache.getType(world, x, y, z);
            if (type.isSolid() || type == Material.BARRIER ||
                type == Material.GLASS || type == Material.ICE ||
                type == Material.PACKED_ICE || type == Material.BLUE_ICE ||
                BlockCache.nameContains(type, "GLASS", "PANE", "CARPET") || type == Material.SNOW) {
                return true;
            }
        }
        return false;
    }

    private boolean isConsistentFlyPattern(FlyData flyData) {
        List<Double> recentVelocities = flyData.getRecentVelocities(12);

        if (recentVelocities.size() < 6) {
            return false;
        }

        if (flyData.isHovering()) {
            return true;
        }

        if (flyData.isSuspiciousUpward()) {
            return true;
        }

        if (flyData.getCUSUMUp() > 2.5) {
            return true;
        }

        if (flyData.getCUSUMDown() > 2.5) {
            return true;
        }

        double flyVariance = flyData.getVariance();
        if (flyVariance < 0.0005 && recentVelocities.size() >= 10) {
            return true;
        }

        double ewmaVel = flyData.getEWMAVelocity();
        if (ewmaVel > -0.015 && ewmaVel < 0.015 && recentVelocities.size() >= 12) {
            return true;
        }

        double sum = 0;
        int hovering = 0;
        int positive = 0;

        for (Double v : recentVelocities) {
            sum += v;
            if (Math.abs(v) < 0.03) hovering++;
            if (v > 0.15) positive++;
        }

        double avgVelocity = sum / recentVelocities.size();

        if (avgVelocity > -0.02 && avgVelocity < 0.02 && hovering >= 8) {
            return true;
        }

        if (positive >= 7) {
            return true;
        }

        return false;
    }

    public void cleanup(UUID playerId) {
        flyDataMap.remove(playerId);
        if (physicsValidator != null) physicsValidator.resetState(playerId);
    }

    public void cleanupStale() {
        long now = System.currentTimeMillis();
        flyDataMap.entrySet().removeIf(entry -> {
            PlayerData data = playerDataMap.get(entry.getKey());
            return data == null || (now - data.getLastMoveTime()) > 300000;
        });
    }

    private static class FlyData {
        private static final double EWMA_ALPHA = 0.30;
        private static final double CUSUM_DRIFT = 0.02;

        private double lastVelocity;
        private double expectedVelocity;
        private long jumpStartTime;
        private int violationCount;
        private final Deque<Double> velocityHistory;
        private int wallClimbCount;
        private long lastWallClimb;
        private long lastBouncyTime;

        private long lastPistonTime;
        private long lastWindChargeTime;
        private long lastFireworkTime;
        private long lastCheckTime;

        private double ewmaVelocity = 0;
        private boolean ewmaInit = false;
        private double welfordMean = 0;
        private double welfordM2 = 0;
        private long welfordN = 0;
        private double cusumUp = 0;
        private double cusumDown = 0;
        private int hoverTicks = 0;
        private int consecutiveUpTicks = 0;
        private int flatTicks = 0;
        private int vulcanGroundTicks = 0;
        private int zeroTicks = 0;
        public FlyData() {
            this.lastVelocity = 0.0;
            this.expectedVelocity = 0.0;
            this.jumpStartTime = 0;
            this.violationCount = 0;
            this.velocityHistory = new ArrayDeque<>(20);
            this.wallClimbCount = 0;
            this.lastWallClimb = 0;
            this.lastBouncyTime = 0;
            this.lastPistonTime = 0;
            this.lastWindChargeTime = 0;
            this.lastFireworkTime = 0;
        }

        public void recordVelocity(double velocity) {
            if (velocityHistory.size() >= 20) {
                velocityHistory.pollFirst();
            }
            velocityHistory.addLast(velocity);

            if (!ewmaInit) {
                ewmaVelocity = velocity;
                ewmaInit = true;
            } else {
                ewmaVelocity = EWMA_ALPHA * velocity + (1.0 - EWMA_ALPHA) * ewmaVelocity;
            }

            updateWelford(velocity);
            updateCUSUM(velocity);
            updateHoverDetection(velocity);
        }

        private void updateWelford(double value) {
            welfordN++;
            double delta = value - welfordMean;
            welfordMean += delta / welfordN;
            double delta2 = value - welfordMean;
            welfordM2 += delta * delta2;
        }

        private void updateCUSUM(double velocity) {
            double expectedDescend = -0.05;
            double deviation = velocity - expectedDescend;
            cusumUp = Math.max(0, cusumUp + deviation - CUSUM_DRIFT);
            cusumDown = Math.max(0, cusumDown - deviation - CUSUM_DRIFT);
        }

        private void updateHoverDetection(double velocity) {
            if (Math.abs(velocity) < 0.03) {
                hoverTicks++;
            } else {
                hoverTicks = Math.max(0, hoverTicks - 1);
            }

            if (velocity > 0.02) {
                consecutiveUpTicks++;
            } else {
                consecutiveUpTicks = 0;
            }
        }

        public boolean isHovering() {
            return hoverTicks >= 12;
        }

        public boolean isSuspiciousUpward() {
            return consecutiveUpTicks >= 8;
        }

        public double getCUSUMUp() {
            return cusumUp;
        }

        public double getCUSUMDown() {
            return cusumDown;
        }

        public double getVariance() {
            return welfordN > 1 ? welfordM2 / (welfordN - 1) : 0;
        }

        public double getExpectedVelocity() {
            return expectedVelocity;
        }

        public double getEWMAVelocity() {
            return ewmaVelocity;
        }

        public List<Double> getRecentVelocities(int count) {
            List<Double> result = new ArrayList<>(count);
            int size = velocityHistory.size();
            int start = Math.max(0, size - count);
            int index = 0;
            for (Double vel : velocityHistory) {
                if (index >= start) {
                    result.add(vel);
                }
                index++;
            }
            return result;
        }

        public int getVelocityHistorySize() { return velocityHistory.size(); }
        public double getLastVelocity() { return lastVelocity; }
        public void setLastVelocity(double velocity) { this.lastVelocity = velocity; }
        public void setExpectedVelocity(double velocity) { this.expectedVelocity = velocity; }
        public long getJumpStartTime() { return jumpStartTime; }
        public void setJumpStart(long time) { this.jumpStartTime = time; }
        public int getViolationCount() { return violationCount; }
        public void incrementViolationCount() { this.violationCount++; }
        public void decrementViolationCount() { if (this.violationCount > 0) this.violationCount--; }
        public void resetViolationCount() { this.violationCount = 0; }
        public long getLastBouncyTime() { return lastBouncyTime; }
        public void setLastBouncyTime(long time) { this.lastBouncyTime = time; }
        public long getLastPistonTime() { return lastPistonTime; }
        public void setLastPistonTime(long time) { this.lastPistonTime = time; }
        public long getLastWindChargeTime() { return lastWindChargeTime; }
        public void setLastWindChargeTime(long time) { this.lastWindChargeTime = time; }
        public long getLastFireworkTime() { return lastFireworkTime; }
        public void setLastFireworkTime(long time) { this.lastFireworkTime = time; }
        public long getLastCheckTime() { return lastCheckTime; }
        public void setLastCheckTime(long time) { this.lastCheckTime = time; }

        public void reset() {
            this.lastVelocity = 0.0;
            this.expectedVelocity = 0.0;
            this.jumpStartTime = 0;
            this.violationCount = 0;
            this.velocityHistory.clear();
            this.wallClimbCount = 0;
            this.ewmaInit = false;
            this.ewmaVelocity = 0;
            this.welfordMean = 0;
            this.welfordM2 = 0;
            this.welfordN = 0;
            this.cusumUp = 0;
            this.cusumDown = 0;
            this.hoverTicks = 0;
            this.consecutiveUpTicks = 0;
            this.flatTicks = 0;
            this.vulcanGroundTicks = 0;
            this.zeroTicks = 0;
            this.lastPistonTime = 0;
            this.lastWindChargeTime = 0;
            this.lastFireworkTime = 0;
            this.lastCheckTime = 0;
        }

        public void recordWallClimb() {
            long now = System.currentTimeMillis();
            if (now - lastWallClimb > 500) {
                wallClimbCount = 1;
            } else {
                wallClimbCount++;
            }
            lastWallClimb = now;
        }

        public int getWallClimbCount() {
            if (System.currentTimeMillis() - lastWallClimb > 1500) {
                wallClimbCount = 0;
            }
            return wallClimbCount;
        }

        public void resetWallClimb() { wallClimbCount = 0; }

        public void bumpFlatTicks() { flatTicks++; }
        public void resetFlatTicks() { flatTicks = 0; }
        public int getFlatTicks() { return flatTicks; }

        public void bumpVulcanGroundTicks() { vulcanGroundTicks++; }
        public void resetVulcanGroundTicks() { vulcanGroundTicks = 0; }
        public int getVulcanGroundTicks() { return vulcanGroundTicks; }

        public void bumpZeroTicks() { zeroTicks++; }
        public void resetZeroTicks() { zeroTicks = 0; }
        public int getZeroTicks() { return zeroTicks; }
    }
}
