package NC.noChance.detection.damage;

import NC.noChance.core.*;
import org.bukkit.Material;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.enchantments.Enchantment;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

public class CriticalsCheck {
    private final ACConfig config;
    private final Map<UUID, PlayerData> playerDataMap;
    private final LayerFiltering filtering;
    private final Map<UUID, CriticalTracker> trackers;

    public CriticalsCheck(ACConfig config, Map<UUID, PlayerData> playerDataMap, LayerFiltering filtering) {
        this.config = config;
        this.playerDataMap = playerDataMap;
        this.filtering = filtering;
        this.trackers = new ConcurrentHashMap<>();
    }

    public CheckResult check(Player player, Entity target, boolean isCritical) {
        if (!config.isCheckEnabled("criticals")) {
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

        long now = System.currentTimeMillis();
        if (now - data.getLastVelocityTime() < 800) {
            return CheckResult.passed();
        }

        if (data.isInTeleportGracePeriod(3)) {
            return CheckResult.passed();
        }

        UUID uuid = player.getUniqueId();
        CriticalTracker tracker = trackers.computeIfAbsent(uuid, k -> new CriticalTracker());

        if (!isCritical) {
            tracker.recordNormalHit();
            return CheckResult.passed();
        }

        if (player.isSprinting()) {
            tracker.recordLegitCrit();
            return CheckResult.passed();
        }

        boolean onGround = player.isOnGround();
        boolean inLiquid = player.isInWater() || player.isSwimming();
        boolean onLadder = player.isClimbing();
        boolean isGliding = player.isGliding();
        boolean isRiptiding = player.isRiptiding();
        boolean inCobweb = isInCobweb(player);

        double fallDistance = player.getFallDistance();
        double verticalVelocity = data.getLastVerticalVelocity();

        if (inLiquid || onLadder || isGliding || isRiptiding || inCobweb) {
            tracker.recordLegitCrit();
            return CheckResult.passed();
        }

        org.bukkit.potion.PotionEffectType levType = org.bukkit.potion.PotionEffectType.getByName("LEVITATION");
        org.bukkit.potion.PotionEffectType slowFallType = org.bukkit.potion.PotionEffectType.getByName("SLOW_FALLING");
        if ((levType != null && player.hasPotionEffect(levType)) ||
            (slowFallType != null && player.hasPotionEffect(slowFallType))) {
            tracker.recordLegitCrit();
            return CheckResult.passed();
        }

        if (isHoldingMace(player)) {
            tracker.recordLegitCrit();
            return CheckResult.passed();
        }

        int ping = filtering.getPing(player);

        boolean microHop = !onGround && fallDistance > 0 && fallDistance < 0.06 &&
            verticalVelocity > -0.05 && verticalVelocity < 0.05;

        org.bukkit.potion.PotionEffectType jumpType = org.bukkit.potion.PotionEffectType.getByName("JUMP_BOOST");
        if (jumpType == null) jumpType = org.bukkit.potion.PotionEffectType.getByName("JUMP");
        if (jumpType != null && player.hasPotionEffect(jumpType)) {
            tracker.recordLegitCrit();
            return CheckResult.passed();
        }

        int featherLevel = 0;
        ItemStack boots = player.getInventory().getBoots();
        if (boots != null && boots.getType() != Material.AIR) {
            try {
                featherLevel = boots.getEnchantmentLevel(Enchantment.FEATHER_FALLING);
            } catch (Throwable e) {
                Logger.getLogger("NoChance").log(Level.WARNING, "Failed to get feather falling level for " + player.getName(), e);
            }
        }
        double velThreshold = 0.1 + (ping * 0.0005) + (featherLevel > 0 ? 0.04 + featherLevel * 0.025 : 0);

        org.bukkit.block.Block below = player.getLocation().clone().add(0, -1, 0).getBlock();
        String belowName = below.getType().name();
        boolean onStairOrSlab = belowName.contains("STAIRS") || belowName.contains("SLAB");

        if ((onGround && fallDistance < 0.1 && Math.abs(verticalVelocity) < velThreshold) || microHop) {
            tracker.recordSuspiciousCrit();

            int suspThreshold = 6;
            if (onStairOrSlab) suspThreshold += 1;
            if (tracker.getSuspiciousCount() >= suspThreshold) {
                double severity = Math.min(1.0, tracker.getSuspiciousCount() / 5.0);

                String tag = microHop ? "Micro-hop crit" : "Criticals on ground";
                CheckResult prelimResult = CheckResult.failed(
                    ViolationType.CRITICALS,
                    severity,
                    String.format("%s: %d suspicious", tag, tracker.getSuspiciousCount())
                );

                if (!filtering.passesLayer2HeuristicFiltering(player, ViolationType.CRITICALS, prelimResult)) {
                    tracker.reset();
                    return CheckResult.passed();
                }

                tracker.reset();
                return prelimResult;
            }
        } else {
            tracker.recordLegitCrit();
        }

        double critRate = tracker.getCriticalRate();
        if (tracker.getTotalHits() >= 20 && critRate > 0.80) {
            double severity = Math.min(0.98, (critRate - 0.6) / 0.4);

            CheckResult prelimResult = CheckResult.failed(
                ViolationType.CRITICALS,
                severity,
                String.format("Abnormal crit rate: %.1f%% (%d/%d hits)",
                    critRate * 100, tracker.getCriticalHits(), tracker.getTotalHits())
            );

            if (!filtering.passesLayer2HeuristicFiltering(player, ViolationType.CRITICALS, prelimResult)) {
                tracker.reset();
                return CheckResult.passed();
            }

            tracker.reset();
            return prelimResult;
        }

        return CheckResult.passed();
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

    public void cleanup(UUID uuid) {
        trackers.remove(uuid);
    }

    private boolean isInCobweb(Player player) {
        org.bukkit.Location loc = player.getLocation();
        org.bukkit.Material feet = loc.getBlock().getType();
        org.bukkit.Material body = loc.clone().add(0, 1, 0).getBlock().getType();
        return feet == org.bukkit.Material.COBWEB || body == org.bukkit.Material.COBWEB;
    }


    private static class CriticalTracker {
        private int totalHits = 0;
        private int criticalHits = 0;
        private int suspiciousCrits = 0;
        private long lastResetTime = System.currentTimeMillis();

        void recordNormalHit() {
            checkReset();
            totalHits++;
        }

        void recordLegitCrit() {
            checkReset();
            totalHits++;
            criticalHits++;
        }

        void recordSuspiciousCrit() {
            checkReset();
            totalHits++;
            criticalHits++;
            suspiciousCrits++;
        }

        void checkReset() {
            if (System.currentTimeMillis() - lastResetTime > 12000) {
                reset();
            }
        }

        void reset() {
            totalHits = 0;
            criticalHits = 0;
            suspiciousCrits = 0;
            lastResetTime = System.currentTimeMillis();
        }

        int getTotalHits() {
            return totalHits;
        }

        int getCriticalHits() {
            return criticalHits;
        }

        int getSuspiciousCount() {
            return suspiciousCrits;
        }

        double getCriticalRate() {
            return totalHits > 0 ? (double) criticalHits / totalHits : 0.0;
        }
    }
}
