package NC.noChance.core;

import org.bukkit.entity.Player;

import java.util.UUID;

public class VersionAdapter {

    public static double adjustSpeedThreshold(Player player, double baseMax) {
        if (!ViaHelper.isAvailable()) return baseMax;
        return baseMax + ViaHelper.getMovementTolerance(player);
    }

    public static double adjustReachThreshold(Player player, double baseReach) {
        if (!ViaHelper.isAvailable()) return baseReach;
        return baseReach + ViaHelper.getVersionTolerance(player);
    }

    public static int adjustCPSThreshold(Player player, int baseCPS) {
        if (!ViaHelper.isAvailable()) return baseCPS;
        if (!ViaHelper.hasAttackCooldown(player)) {
            return Math.max(baseCPS, 22);
        }
        return baseCPS;
    }

    public static int getMaxCPS(Player player) {
        if (!ViaHelper.isAvailable()) return 16;
        if (!ViaHelper.hasAttackCooldown(player)) return 22;
        return 16;
    }

    public static double adjustTimerThreshold(Player player, double baseRate) {
        if (!ViaHelper.isAvailable()) return baseRate;
        double tolerance = ViaHelper.getVersionTolerance(player);
        return baseRate + (tolerance * 0.5);
    }

    public static double adjustFlyThreshold(Player player, double baseDeviation) {
        if (!ViaHelper.isAvailable()) return baseDeviation;
        return baseDeviation + ViaHelper.getMovementTolerance(player);
    }

    public static long getHitDelay(Player player) {
        if (!ViaHelper.isAvailable()) return 600L;
        if (!ViaHelper.hasAttackCooldown(player)) return 50L;
        return 600L;
    }

    public static double adjustBlockBreakTolerance(Player player, double baseTolerance) {
        if (!ViaHelper.isAvailable()) return baseTolerance;
        double versionTol = ViaHelper.getVersionTolerance(player);
        return baseTolerance + (versionTol * 0.4);
    }

    public static boolean isLegacyCombat(Player player) {
        if (!ViaHelper.isAvailable()) return false;
        return !ViaHelper.hasAttackCooldown(player);
    }

    public static void evict(UUID uuid) {
    }

    public static void cleanup() {
    }
}
