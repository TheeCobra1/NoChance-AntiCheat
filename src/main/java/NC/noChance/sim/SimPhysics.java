package NC.noChance.sim;

import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class SimPhysics {

    public static final double GRAVITY = 0.08;
    public static final double DRAG_Y = 0.98;
    public static final double DRAG_H = 0.91;
    public static final double JUMP_VEL = 0.42;
    public static final double AIR_ACCEL = 0.02;
    public static final double WATER_DRAG = 0.8;
    public static final double LAVA_DRAG = 0.5;
    public static final double CLIMB_SPEED = 0.2;

    private SimPhysics() {}

    public static double computeMaxHorizontalSpeed(SimPlayer state, Player player, double slipperiness) {
        double lastHSpeed = Math.sqrt(state.getVelX() * state.getVelX() + state.getVelZ() * state.getVelZ());
        double friction;
        double accel;

        if (state.isOnGround()) {
            friction = slipperiness * DRAG_H;
            accel = 0.1 * Math.pow(0.6 / (slipperiness * DRAG_H), 3);
        } else {
            friction = DRAG_H;
            accel = AIR_ACCEL;
        }

        if (state.isSprinting()) {
            accel *= 1.3;
        }

        if (state.isSneaking()) {
            accel *= 0.3;
        }

        accel *= getSpeedPotionMultiplier(player);
        accel *= getSlownessMultiplier(player);

        double max = lastHSpeed * friction + accel;

        return Math.min(max, 50.0);
    }

    public static double computeNextVerticalVelocity(double currentVelY, SimPlayer state, Player player) {
        double velY = currentVelY;

        if (state.isOnGround() && currentVelY > 0.1) {
            velY = JUMP_VEL + getJumpBoostLevel(player) * 0.1;
        }

        velY = (velY - GRAVITY) * DRAG_Y;

        PotionEffectType levType = resolvePotionType("LEVITATION", "LEVITATION");
        if (levType != null && player.hasPotionEffect(levType)) {
            PotionEffect lev = player.getPotionEffect(levType);
            if (lev != null) {
                velY += 0.05 * (lev.getAmplifier() + 1);
            }
        }

        PotionEffectType slowFallType = resolvePotionType("SLOW_FALLING", "SLOW_FALLING");
        if (slowFallType != null && player.hasPotionEffect(slowFallType)) {
            velY = Math.max(velY, -0.01);
        }

        if (state.isSwimming()) {
            velY *= WATER_DRAG;
        }

        if (player.getLocation().getBlock().getType() == Material.LAVA) {
            velY *= LAVA_DRAG;
        }

        if (state.isClimbing()) {
            velY = Math.max(-0.15, Math.min(velY, CLIMB_SPEED));
        }

        if (state.isOnGround() && velY < 0) {
            velY = -0.0784;
        }

        return velY;
    }

    public static double computeFriction(double slipperiness, boolean onGround) {
        if (onGround) {
            return slipperiness * DRAG_H;
        }
        return DRAG_H;
    }

    public static double getSpeedPotionMultiplier(Player player) {
        PotionEffectType type = resolvePotionType("SPEED", "INCREASE_MOVEMENT_SPEED");
        if (type == null || !player.hasPotionEffect(type)) {
            return 1.0;
        }
        PotionEffect eff = player.getPotionEffect(type);
        if (eff == null) {
            return 1.0;
        }
        return 1.0 + (eff.getAmplifier() + 1) * 0.2;
    }

    public static double getSlownessMultiplier(Player player) {
        PotionEffectType type = resolvePotionType("SLOWNESS", "SLOW");
        if (type == null || !player.hasPotionEffect(type)) {
            return 1.0;
        }
        PotionEffect eff = player.getPotionEffect(type);
        if (eff == null) {
            return 1.0;
        }
        return Math.max(0.0, 1.0 - (eff.getAmplifier() + 1) * 0.15);
    }

    public static int getJumpBoostLevel(Player player) {
        PotionEffectType type = resolvePotionType("JUMP_BOOST", "JUMP");
        if (type == null || !player.hasPotionEffect(type)) {
            return 0;
        }
        PotionEffect eff = player.getPotionEffect(type);
        return eff != null ? eff.getAmplifier() + 1 : 0;
    }

    @SuppressWarnings("deprecation")
    public static double getSoulSpeedMultiplier(Player player) {
        ItemStack boots = player.getInventory().getBoots();
        if (boots == null) {
            return 1.0;
        }
        Enchantment soulSpeed;
        try {
            soulSpeed = Enchantment.getByName("SOUL_SPEED");
        } catch (Exception e) {
            Logger.getLogger("NoChance").log(Level.WARNING, "Failed to resolve SOUL_SPEED enchantment", e);
            return 1.0;
        }
        if (soulSpeed == null) {
            return 1.0;
        }
        int level = boots.getEnchantmentLevel(soulSpeed);
        if (level <= 0) {
            return 1.0;
        }
        return 1.3 + level * 0.105;
    }

    @SuppressWarnings("deprecation")
    private static PotionEffectType resolvePotionType(String primary, String fallback) {
        PotionEffectType type = PotionEffectType.getByName(primary);
        if (type != null) {
            return type;
        }
        return PotionEffectType.getByName(fallback);
    }
}
