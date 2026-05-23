package NC.noChance.detection.damage;

import NC.noChance.core.*;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.inventory.ItemStack;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

public class NoFallCheck {
    private final ACConfig config;
    private final Map<UUID, PlayerData> playerDataMap;
    private final LayerFiltering filtering;
    private final Map<UUID, NoFallTracker> trackers;

    private MovementGrace movementGrace;

    public NoFallCheck(ACConfig config, Map<UUID, PlayerData> playerDataMap, LayerFiltering filtering) {
        this.config = config;
        this.playerDataMap = playerDataMap;
        this.filtering = filtering;
        this.trackers = new ConcurrentHashMap<>();
    }

    public void setMovementGrace(MovementGrace grace) {
        this.movementGrace = grace;
    }

    public CheckResult checkMovement(Player player, Location from, Location to) {
        if (!config.isCheckEnabled("nofall")) {
            return CheckResult.passed();
        }

        PlayerData data = playerDataMap.get(player.getUniqueId());
        if (data == null) return CheckResult.passed();

        if (PlayerData.hasLegitFlight(player)) return CheckResult.passed();
        if (data.flyDisableGraceTicks > 0) return CheckResult.passed();

        if (data.isInGracePeriod(config.getGracePeriod())) {
            return CheckResult.passed();
        }

        UUID uuid = player.getUniqueId();
        NoFallTracker tracker = trackers.computeIfAbsent(uuid, k -> new NoFallTracker());

        if (player.isFlying() && player.getAllowFlight()) {
            tracker.reset();
            return CheckResult.passed();
        }

        if (player.isGliding() || player.isSwimming() || player.isRiptiding() || player.isClimbing()) {
            tracker.reset();
            return CheckResult.passed();
        }

        if (player.getVehicle() != null) {
            tracker.reset();
            return CheckResult.passed();
        }

        if (movementGrace != null) {
            long mNow = System.currentTimeMillis();
            if (movementGrace.inMountGrace(player.getUniqueId(), mNow) || movementGrace.inVelocityGrace(player.getUniqueId(), mNow)) {
                tracker.reset();
                return CheckResult.passed();
            }
        }

        if (data.hasRecentEnderPearl(3000)) {
            tracker.reset();
            return CheckResult.passed();
        }

        org.bukkit.potion.PotionEffectType slowFallType = org.bukkit.potion.PotionEffectType.getByName("SLOW_FALLING");
        org.bukkit.potion.PotionEffectType levType = org.bukkit.potion.PotionEffectType.getByName("LEVITATION");
        if ((slowFallType != null && player.hasPotionEffect(slowFallType)) ||
            (levType != null && player.hasPotionEffect(levType))) {
            tracker.reset();
            return CheckResult.passed();
        }

        if (isInSafeLandingBlock(player)) {
            tracker.reset();
            return CheckResult.passed();
        }

        double deltaY = to.getY() - from.getY();
        boolean clientOnGround = player.isOnGround();
        boolean actuallyOnGround = isActuallyOnGround(to);

        tracker.recordMove(deltaY, clientOnGround, actuallyOnGround);

        long sinceKb = System.currentTimeMillis() - data.getLastVelocityTime();
        boolean recentKb = data.getLastVelocityTime() > 0 && sinceKb < 1500;

        if (deltaY < -0.08 && !recentKb) {
            tracker.addFallDistance(Math.abs(deltaY));
        } else if (deltaY >= 0 || actuallyOnGround) {
            double totalFall = tracker.getTotalFall();
            if (totalFall > 3.5 && clientOnGround) {
                double clientFallDist = player.getFallDistance();
                if (clientFallDist < totalFall * 0.3) {
                    tracker.recordSpoofedGround();
                }
            }
            if (clientOnGround && actuallyOnGround) {
                tracker.resetFall();
            }
        }

        if (clientOnGround && tracker.getConsecutiveFallTicks() > 3 && player.getFallDistance() > 2.5) {
            double severity = Math.min(0.95, 0.82 + player.getFallDistance() * 0.01);
            CheckResult lastTickResult = CheckResult.failed(
                ViolationType.NOFALL,
                severity,
                String.format("GroundSpoof LAST: claimed ground airTicks:%d fallDist:%.2f",
                    tracker.getConsecutiveFallTicks(), player.getFallDistance())
            );
            if (filtering.passesLayer2HeuristicFiltering(player, ViolationType.NOFALL, lastTickResult)) {
                tracker.resetGroundSpoof();
                return lastTickResult;
            }
        }

        if (clientOnGround && !actuallyOnGround && deltaY < -0.15 && tracker.getConsecutiveFallTicks() >= 2) {
            tracker.recordGroundSpoof();

            if (deltaY < -0.5 && tracker.getConsecutiveFallTicks() >= 4) {
                double severity = Math.min(0.98, 0.85 + Math.abs(deltaY) * 0.1);
                CheckResult result = CheckResult.failed(
                    ViolationType.NOFALL,
                    severity,
                    String.format("GroundSpoof INSTANT: claimed ground while falling dY:%.2f ticks:%d",
                        deltaY, tracker.getConsecutiveFallTicks())
                );
                if (!filtering.passesLayer2HeuristicFiltering(player, ViolationType.NOFALL, result)) {
                    tracker.resetGroundSpoof();
                    return CheckResult.passed();
                }
                tracker.resetGroundSpoof();
                return result;
            }

            if (tracker.getGroundSpoofCount() >= 4) {
                double severity = Math.min(0.98, 0.72 + (tracker.getGroundSpoofCount() * 0.06));

                if (tracker.getSpoofedGroundCount() >= 3) {
                    severity = Math.min(0.98, severity + tracker.getSpoofedGroundCount() * 0.03);
                }

                long timeSinceLastVl = tracker.timeSinceLastViolation();
                if (timeSinceLastVl > 0 && timeSinceLastVl < 800) {
                    severity = Math.min(0.98, severity + 0.05);
                }
                CheckResult result = CheckResult.failed(
                    ViolationType.NOFALL,
                    severity,
                    String.format("GroundSpoof: claimed ground while falling dY:%.2f spoofs:%d",
                        deltaY, tracker.getGroundSpoofCount())
                );
                tracker.resetGroundSpoof();
                return result;
            }
        } else {
            tracker.decayGroundSpoof();
        }

        if (clientOnGround && tracker.getTotalFall() > 3.5) {
            double fallDist = tracker.getTotalFall();
            double clientFall = player.getFallDistance();

            if (clientFall < 0.15 && fallDist > 10.0 && tracker.getConsecutiveFallTicks() >= 8) {
                double severity = Math.min(0.98, 0.80 + (fallDist / 25.0));
                CheckResult result = CheckResult.failed(
                    ViolationType.NOFALL,
                    severity,
                    String.format("FallMismatch INSTANT: server:%.1f client:%.1f",
                        fallDist, clientFall)
                );
                tracker.reset();
                return result;
            }

            if (clientFall < 0.5 && fallDist > 6.0 && tracker.getConsecutiveFallTicks() >= 5) {
                tracker.recordFallMismatch();

                if (tracker.getFallMismatchCount() >= 4 || fallDist > 10.0) {
                    double severity = Math.min(0.98, 0.75 + (fallDist / 25.0));
                    CheckResult result = CheckResult.failed(
                        ViolationType.NOFALL,
                        severity,
                        String.format("FallMismatch: server:%.1f client:%.1f vl:%d",
                            fallDist, clientFall, tracker.getFallMismatchCount())
                    );
                    tracker.reset();
                    return result;
                }
            }
        }

        double velocityY = deltaY;
        if (tracker.getConsecutiveFallTicks() >= 5 && clientOnGround && !actuallyOnGround) {
            if (velocityY < -0.35) {
                tracker.recordImpossibleGround();

                if (tracker.getImpossibleGroundCount() >= 3) {
                    double severity = Math.min(0.95, 0.85 + Math.abs(velocityY) * 0.1);
                    CheckResult result = CheckResult.failed(
                        ViolationType.NOFALL,
                        severity,
                        String.format("ImpossibleGround: falling at %.2f but claims ground, %d ticks falling",
                            velocityY, tracker.getConsecutiveFallTicks())
                    );
                    tracker.reset();
                    return result;
                }
            }
        }

        return CheckResult.passed();
    }

    public void updateFallDistance(Player player) {
        PlayerData data = playerDataMap.get(player.getUniqueId());
        if (data == null) return;

        if (data.flyDisableGraceTicks > 0) return;

        UUID uuid = player.getUniqueId();
        NoFallTracker tracker = trackers.computeIfAbsent(uuid, k -> new NoFallTracker());

        if (player.isGliding() || player.isSwimming() || player.isRiptiding() ||
            player.isFlying() || player.isClimbing()) {
            data.resetFallDistance();
            tracker.reset();
            return;
        }

        if (isInSafeLandingBlock(player)) {
            data.resetFallDistance();
            tracker.reset();
            return;
        }

        if (player.isOnGround()) {
            if (!data.wasOnGround()) {
                data.setLastGroundTime(System.currentTimeMillis());
                tracker.landed();
            }
            data.setWasOnGround(true);
        } else {
            data.setWasOnGround(false);

            if (data.getLastLocation() != null) {
                double deltaY = data.getLastLocation().getY() - player.getLocation().getY();
                if (deltaY > 0) {
                    data.addFallDistance(deltaY);
                    tracker.recordFall(deltaY);
                }
            }
        }
    }

    public CheckResult checkOnLanding(Player player, EntityDamageEvent event) {
        if (!config.isCheckEnabled("nofall")) {
            return CheckResult.passed();
        }

        PlayerData data = playerDataMap.get(player.getUniqueId());
        if (data == null) return CheckResult.passed();

        if (data.isInGracePeriod(config.getGracePeriod())) {
            return CheckResult.passed();
        }

        UUID uuid = player.getUniqueId();
        NoFallTracker tracker = trackers.computeIfAbsent(uuid, k -> new NoFallTracker());

        if (player.hasPotionEffect(org.bukkit.potion.PotionEffectType.SLOW_FALLING)) {
            return CheckResult.passed();
        }

        if (player.hasPotionEffect(org.bukkit.potion.PotionEffectType.LEVITATION)) {
            return CheckResult.passed();
        }

        org.bukkit.potion.PotionEffectType resistType = org.bukkit.potion.PotionEffectType.getByName("RESISTANCE");
        if (resistType == null) resistType = org.bukkit.potion.PotionEffectType.getByName("DAMAGE_RESISTANCE");
        if (resistType != null && player.hasPotionEffect(resistType)) {
            var eff = player.getPotionEffect(resistType);
            if (eff != null && eff.getAmplifier() >= 4) {
                return CheckResult.passed();
            }
        }

        if (data.hasRecentEnderPearl(3000)) {
            return CheckResult.passed();
        }

        if (isHoldingMace(player)) {
            return CheckResult.passed();
        }

        EnhancementTracker.MovementEnhancements enhancements = EnhancementTracker.calculateMovementEnhancements(player);
        if (enhancements.hasLegitFlight || player.isGliding() || player.isRiptiding()) {
            return CheckResult.passed();
        }

        if (isInSafeLandingBlock(player)) {
            return CheckResult.passed();
        }

        double fallDistance = Math.max(player.getFallDistance(), Math.max(data.getTotalFallDistance(), tracker.getTotalFall()));

        if (fallDistance < 3.5) {
            data.resetFallDistance();
            return CheckResult.passed();
        }

        double expectedDamage = calculateExpectedFallDamage(fallDistance);
        expectedDamage = applyDamageReduction(player, expectedDamage);
        expectedDamage = applyArmorReduction(player, expectedDamage);

        double actualDamage = 0.0;
        double rawDamage = 0.0;
        boolean externallyReduced = false;
        if (event != null && event.getCause() == EntityDamageEvent.DamageCause.FALL) {
            actualDamage = event.getFinalDamage();
            rawDamage = event.getDamage();
            externallyReduced = rawDamage < expectedDamage * 0.5;
        }

        data.resetFallDistance();
        tracker.resetFall();

        if (expectedDamage < 1.0) {
            return CheckResult.passed();
        }

        if (externallyReduced) {
            return CheckResult.passed();
        }

        if (fallDistance >= 7.0 && expectedDamage >= 4.0 && actualDamage == 0) {
            CheckResult instantResult = CheckResult.failed(
                ViolationType.NOFALL,
                0.98,
                String.format("INSTANT: No damage from %.1f fall (expected: %.1f)",
                    fallDistance, expectedDamage)
            );
            if (!filtering.passesLayer2HeuristicFiltering(player, ViolationType.NOFALL, instantResult)) {
                return CheckResult.passed();
            }
            return instantResult;
        }

        if (expectedDamage > 1.5 && actualDamage == 0) {
            tracker.recordViolation();

            if (fallDistance >= 5.0 && expectedDamage >= 2.0) {
                return CheckResult.failed(
                    ViolationType.NOFALL,
                    Math.min(0.95, 0.72 + (expectedDamage / 12.0)),
                    String.format("No damage from %.1f fall (expected: %.1f)",
                        fallDistance, expectedDamage)
                );
            }

            if (tracker.getViolationCount() >= 1 && fallDistance >= 4.0) {
                return CheckResult.failed(
                    ViolationType.NOFALL,
                    Math.min(0.92, 0.65 + (expectedDamage / 15.0)),
                    String.format("No damage from %.1f fall (expected: %.1f, vl:%d)",
                        fallDistance, expectedDamage, tracker.getViolationCount())
                );
            }
        }

        if (expectedDamage > 2.5 && actualDamage < expectedDamage * 0.5) {
            double damageReduction = 1.0 - (actualDamage / expectedDamage);

            if (damageReduction > 0.7 && fallDistance >= 4.5) {
                return CheckResult.failed(
                    ViolationType.NOFALL,
                    Math.min(0.95, 0.72 + damageReduction * 0.25),
                    String.format("Reduced damage: %.1f fall, %.0f%% blocked (exp:%.1f act:%.1f)",
                        fallDistance, damageReduction * 100, expectedDamage, actualDamage)
                );
            }
        }

        return CheckResult.passed();
    }

    private boolean isActuallyOnGround(Location loc) {
        double x = loc.getX();
        double y = loc.getY();
        double z = loc.getZ();
        org.bukkit.World world = loc.getWorld();
        if (world == null) return true;

        double halfWidth = 0.3;
        double[][] offsets = {
            {0, 0}, {-halfWidth, 0}, {halfWidth, 0}, {0, -halfWidth}, {0, halfWidth},
            {-halfWidth, -halfWidth}, {halfWidth, -halfWidth}, {-halfWidth, halfWidth}, {halfWidth, halfWidth}
        };

        for (double[] offset : offsets) {
            double checkX = x + offset[0];
            double checkZ = z + offset[1];
            int bx = (int) Math.floor(checkX);
            int bz = (int) Math.floor(checkZ);
            if (!world.isChunkLoaded(bx >> 4, bz >> 4)) continue;

            Block below = world.getBlockAt(bx, (int) Math.floor(y - 0.1), bz);
            if (isSupportBlock(below, y)) return true;

            Block belowMore = world.getBlockAt(bx, (int) Math.floor(y - 0.5), bz);
            if (isSupportBlock(belowMore, y)) return true;
        }

        int fx = (int) Math.floor(x);
        int fz = (int) Math.floor(z);
        if (!world.isChunkLoaded(fx >> 4, fz >> 4)) return false;
        Material atFeet = world.getBlockAt(fx, (int) Math.floor(y), fz).getType();
        if (atFeet.name().contains("CARPET")) return true;
        if (atFeet == Material.SNOW) return true;
        if (atFeet == Material.LILY_PAD) return true;
        if (atFeet == Material.BUBBLE_COLUMN) return true;
        if (atFeet == Material.COBWEB) return true;
        if (atFeet == Material.POWDER_SNOW) return true;

        return false;
    }

    private boolean isSupportBlock(Block block, double playerY) {
        Material type = block.getType();
        if (type == Material.AIR || type == Material.WATER || type == Material.LAVA) return false;

        if (type.isSolid()) {
            double blockTop = block.getY() + 1.0;
            if (type.name().contains("SLAB") && !block.getBlockData().getAsString().contains("top")) {
                blockTop = block.getY() + 0.5;
            }
            return playerY - blockTop < 0.5 && playerY >= blockTop - 0.1;
        }

        if (type == Material.SCAFFOLDING) return true;
        if (type == Material.LILY_PAD) return true;
        if (type.name().contains("CARPET")) return true;
        if (type.name().contains("FENCE") && !type.name().contains("GATE")) return true;
        if (type.name().contains("WALL")) return true;
        if (type.name().contains("STAIR")) return true;

        return false;
    }

    private double calculateExpectedFallDamage(double fallDistance) {
        return Math.max(0, Math.floor(fallDistance) - 3.0);
    }

    private double applyDamageReduction(Player player, double damage) {
        Location loc = player.getLocation();
        org.bukkit.World w = loc.getWorld();
        if (w == null || !w.isChunkLoaded(loc.getBlockX() >> 4, loc.getBlockZ() >> 4)) return damage;
        Block below = w.getBlockAt(loc.getBlockX(), loc.getBlockY() - 1, loc.getBlockZ());
        Material belowType = below.getType();

        if (belowType == Material.SLIME_BLOCK) return 0.0;
        if (belowType == Material.HAY_BLOCK) return damage * 0.2;
        if (belowType == Material.HONEY_BLOCK) return damage * 0.2;
        if (belowType == Material.POINTED_DRIPSTONE) return damage * 2.0;
        if (belowType == Material.POWDER_SNOW) return 0.0;
        if (belowType == Material.SWEET_BERRY_BUSH) return damage * 0.5;
        if (belowType.name().contains("BED")) return damage * 0.5;
        if (belowType.name().contains("SCAFFOLDING")) return 0.0;

        return damage;
    }

    private double applyArmorReduction(Player player, double damage) {
        double reduction = 1.0;
        int totalFeatherFalling = 0;
        int totalProtection = 0;

        ItemStack boots = player.getInventory().getBoots();
        if (boots != null && boots.getType() != Material.AIR) {
            totalFeatherFalling = boots.getEnchantmentLevel(Enchantment.FEATHER_FALLING);
        }

        for (ItemStack armor : player.getInventory().getArmorContents()) {
            if (armor != null && armor.getType() != Material.AIR) {
                int protLevel = armor.getEnchantmentLevel(Enchantment.PROTECTION);
                totalProtection += protLevel;
            }
        }

        if (totalFeatherFalling > 0) {
            reduction -= Math.min(0.48, totalFeatherFalling * 0.12);
        }

        if (totalProtection > 0) {
            reduction -= Math.min(0.4, totalProtection * 0.04);
        }

        return damage * Math.max(0.0, reduction);
    }

    private boolean isInSafeLandingBlock(Player player) {
        Location loc = player.getLocation();
        org.bukkit.World w = loc.getWorld();
        if (w == null || !w.isChunkLoaded(loc.getBlockX() >> 4, loc.getBlockZ() >> 4)) return false;
        Material type = loc.getBlock().getType();
        Material below = w.getBlockAt(loc.getBlockX(), loc.getBlockY() - 1, loc.getBlockZ()).getType();

        if (type == Material.WATER || type == Material.LAVA) return true;
        if (type == Material.COBWEB) return true;
        if (type == Material.POWDER_SNOW) return true;
        if (type == Material.BUBBLE_COLUMN) return true;
        if (type.name().contains("VINE")) return true;
        if (type.name().contains("LADDER")) return true;
        if (type.name().contains("SCAFFOLDING")) return true;
        if (type.name().contains("KELP")) return true;
        if (type.name().contains("SEAGRASS")) return true;

        if (below == Material.SLIME_BLOCK) return true;
        if (below == Material.HONEY_BLOCK) return true;
        if (below == Material.HAY_BLOCK) return true;
        if (below.name().contains("BED")) return true;
        if (below == Material.WATER || below == Material.LAVA) return true;

        return false;
    }

    private boolean isHoldingMace(Player player) {
        try {
            Material mace = Material.valueOf("MACE");
            ItemStack hand = player.getInventory().getItemInMainHand();
            return hand != null && hand.getType() == mace;
        } catch (IllegalArgumentException e) {
            Logger.getLogger("NoChance").log(Level.WARNING, "MACE material not available on this server version", e);
            return false;
        }
    }

    public void cleanup(UUID playerId) {
        trackers.remove(playerId);
    }

    private static class NoFallTracker {
        private int violations = 0;
        private long lastViolation = 0;
        private double totalFallTracked = 0;
        private int groundSpoofCount = 0;
        private long lastGroundSpoof = 0;
        private int fallMismatchCount = 0;
        private int impossibleGroundCount = 0;
        private int consecutiveFallTicks = 0;
        private int spoofedGroundCount = 0;

        void recordMove(double deltaY, boolean clientGround, boolean actualGround) {
            if (deltaY < -0.1 && !actualGround) {
                consecutiveFallTicks++;
            } else if (actualGround || deltaY >= 0) {
                consecutiveFallTicks = 0;
            }
        }

        void addFallDistance(double dist) {
            totalFallTracked += dist;
        }

        double getTotalFall() {
            return totalFallTracked;
        }

        void resetFall() {
            totalFallTracked = 0;
            consecutiveFallTicks = 0;
            this.fallMismatchCount = 0;
        }

        void recordGroundSpoof() {
            groundSpoofCount++;
            lastGroundSpoof = System.currentTimeMillis();
        }

        void decayGroundSpoof() {
            if (System.currentTimeMillis() - lastGroundSpoof > 1200 && groundSpoofCount > 0) {
                groundSpoofCount--;
            }
        }

        void resetGroundSpoof() {
            groundSpoofCount = 0;
        }

        int getGroundSpoofCount() {
            return groundSpoofCount;
        }

        void recordFallMismatch() {
            fallMismatchCount++;
        }

        int getFallMismatchCount() {
            return fallMismatchCount;
        }

        void recordImpossibleGround() {
            impossibleGroundCount++;
        }

        int getImpossibleGroundCount() {
            return impossibleGroundCount;
        }

        int getConsecutiveFallTicks() {
            return consecutiveFallTicks;
        }

        void recordSpoofedGround() {
            spoofedGroundCount++;
        }

        int getSpoofedGroundCount() {
            return spoofedGroundCount;
        }

        long timeSinceLastViolation() {
            if (lastViolation == 0) return -1;
            return System.currentTimeMillis() - lastViolation;
        }

        void recordFall(double distance) {
            totalFallTracked += distance;
        }

        void recordViolation() {
            violations++;
            lastViolation = System.currentTimeMillis();
        }

        void landed() {
            resetFall();
        }

        void reset() {
            totalFallTracked = 0;
            consecutiveFallTicks = 0;
            groundSpoofCount = 0;
            fallMismatchCount = 0;
            impossibleGroundCount = 0;
            spoofedGroundCount = 0;
            this.violations = 0;
        }

        int getViolationCount() {
            return violations;
        }
    }
}
