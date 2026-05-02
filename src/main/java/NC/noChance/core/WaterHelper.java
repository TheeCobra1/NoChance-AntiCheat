package NC.noChance.core;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import java.util.logging.Level;
import java.util.logging.Logger;

public class WaterHelper {

    public static boolean isInWater(Player player) {
        if (player.isInWater() || player.isSwimming()) {
            return true;
        }

        Block block = player.getLocation().getBlock();
        if (isWaterBlock(block)) {
            return true;
        }

        Block above = player.getLocation().clone().add(0, 1, 0).getBlock();
        if (isWaterBlock(above)) {
            return true;
        }

        return false;
    }

    public static boolean isNearWater(Location location, int radius) {
        for (int x = -radius; x <= radius; x++) {
            for (int y = -radius; y <= radius; y++) {
                for (int z = -radius; z <= radius; z++) {
                    Block block = location.clone().add(x, y, z).getBlock();
                    if (isWaterBlock(block)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    public static boolean isWaterBlock(Block block) {
        if (block == null) return false;
        Material type = block.getType();

        if (type == Material.WATER ||
            type.name().contains("WATER") ||
            type == Material.BUBBLE_COLUMN ||
            type == Material.SEAGRASS ||
            type == Material.TALL_SEAGRASS ||
            type == Material.KELP ||
            type == Material.KELP_PLANT ||
            (type.name().contains("CORAL") && !type.name().contains("BLOCK"))) {
            return true;
        }

        try {
            org.bukkit.block.data.BlockData data = block.getBlockData();
            if (data instanceof org.bukkit.block.data.Waterlogged) {
                return ((org.bukkit.block.data.Waterlogged) data).isWaterlogged();
            }
        } catch (Exception e) {
            Logger.getLogger("NoChance").log(Level.WARNING, "Failed to check waterlogged state", e);
        }

        return false;
    }

    public static boolean isInLiquid(Player player) {
        Block block = player.getLocation().getBlock();
        Material type = block.getType();

        return type == Material.WATER ||
               type.name().contains("WATER") ||
               type == Material.LAVA ||
               type.name().contains("LAVA") ||
               type == Material.BUBBLE_COLUMN;
    }

    public static double getWaterDragMultiplier(Player player) {
        if (!isInWater(player)) return 1.0;

        boolean hasDepthStrider = false;
        int depthStriderLevel = 0;

        try {
            org.bukkit.inventory.ItemStack boots = player.getInventory().getBoots();
            if (boots != null) {
                org.bukkit.enchantments.Enchantment depthStrider = null;

                try {
                    depthStrider = org.bukkit.enchantments.Enchantment.getByKey(
                        org.bukkit.NamespacedKey.minecraft("depth_strider")
                    );
                } catch (Exception e) {
                }

                if (depthStrider == null) {
                    try {
                        depthStrider = org.bukkit.enchantments.Enchantment.getByName("DEPTH_STRIDER");
                    } catch (Exception e) {
                    }
                }

                if (depthStrider != null && boots.containsEnchantment(depthStrider)) {
                    hasDepthStrider = true;
                    depthStriderLevel = boots.getEnchantmentLevel(depthStrider);
                }
            }
        } catch (Exception e) {
            Logger.getLogger("NoChance").log(Level.WARNING, "Failed to check depth strider for " + player.getName(), e);
        }

        if (hasDepthStrider) {
            return Math.max(0.0, 1.0 - (depthStriderLevel / 3.0));
        }

        return 0.5;
    }

    public static double getBuoyancyEffect(Player player) {
        Block block = player.getLocation().getBlock();

        if (block.getType() == Material.BUBBLE_COLUMN) {
            try {
                String blockData = block.getBlockData().getAsString();
                if (blockData.contains("drag=false")) {
                    return 0.8;
                } else {
                    return -0.6;
                }
            } catch (Exception e) {
                Logger.getLogger("NoChance").log(Level.WARNING, "Failed to read bubble column block data", e);
            }
        }

        if (isWaterBlock(block)) {
            return -0.02;
        }

        return 0.0;
    }

    public static boolean hasWaterBreathing(Player player) {
        try {
            org.bukkit.potion.PotionEffectType waterBreathing = null;

            try {
                waterBreathing = org.bukkit.potion.PotionEffectType.getByName("WATER_BREATHING");
            } catch (Exception e) {
            }

            if (waterBreathing != null && player.hasPotionEffect(waterBreathing)) {
                return true;
            }
        } catch (Exception e) {
            Logger.getLogger("NoChance").log(Level.WARNING, "Failed to check water breathing potion for " + player.getName(), e);
        }

        try {
            org.bukkit.inventory.ItemStack helmet = player.getInventory().getHelmet();
            if (helmet != null && helmet.getType().name().equals("TURTLE_SHELL")) {
                return true;
            }
        } catch (Exception e) {
            Logger.getLogger("NoChance").log(Level.WARNING, "Failed to check turtle shell helmet for " + player.getName(), e);
        }

        return false;
    }

    public static double getSwimmingSpeedMultiplier(Player player) {
        double multiplier = 1.0;

        if (player.isSwimming()) {
            multiplier = 1.3;
        }

        try {
            if (player.hasPotionEffect(org.bukkit.potion.PotionEffectType.DOLPHINS_GRACE)) {
                multiplier *= 1.5;
            }
        } catch (Exception e) {
            Logger.getLogger("NoChance").log(Level.WARNING, "Failed to check dolphins grace for " + player.getName(), e);
        }

        multiplier *= getWaterDragMultiplier(player);

        return multiplier;
    }
}
