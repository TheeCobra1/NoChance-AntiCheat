package NC.noChance.core;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import java.util.logging.Level;
import java.util.logging.Logger;

public class EnvironmentHelper {

    public static final class MovementEnv {
        public final boolean inWater;
        public final boolean inLava;
        public final boolean fireResistance;
        public final boolean onLadder;
        public final boolean onVine;
        public final boolean onScaffolding;
        public final boolean onSlimeBlock;
        public final boolean onSoulSand;
        public final boolean onIce;
        public final boolean onPackedIce;
        public final boolean onBlueIce;
        public final boolean onSnowBlock;
        public final boolean onSweetBerries;
        public final boolean inCobweb;
        public final boolean onMagmaBlock;
        public final boolean onHoneyBlock;
        public final boolean inPowderSnowWithLeatherBoots;
        public final int soulSpeedLevel;
        public final int depthStriderLevel;
        public final int frostWalkerLevel;
        public final int jumpBoostLevel;
        public final int levitationLevel;
        public final boolean slowFalling;
        public final boolean dolphinsGrace;
        public final boolean slowness;
        public final boolean speed;

        MovementEnv(Builder b) {
            this.inWater = b.inWater;
            this.inLava = b.inLava;
            this.fireResistance = b.fireResistance;
            this.onLadder = b.onLadder;
            this.onVine = b.onVine;
            this.onScaffolding = b.onScaffolding;
            this.onSlimeBlock = b.onSlimeBlock;
            this.onSoulSand = b.onSoulSand;
            this.onIce = b.onIce;
            this.onPackedIce = b.onPackedIce;
            this.onBlueIce = b.onBlueIce;
            this.onSnowBlock = b.onSnowBlock;
            this.onSweetBerries = b.onSweetBerries;
            this.inCobweb = b.inCobweb;
            this.onMagmaBlock = b.onMagmaBlock;
            this.onHoneyBlock = b.onHoneyBlock;
            this.inPowderSnowWithLeatherBoots = b.inPowderSnowWithLeatherBoots;
            this.soulSpeedLevel = b.soulSpeedLevel;
            this.depthStriderLevel = b.depthStriderLevel;
            this.frostWalkerLevel = b.frostWalkerLevel;
            this.jumpBoostLevel = b.jumpBoostLevel;
            this.levitationLevel = b.levitationLevel;
            this.slowFalling = b.slowFalling;
            this.dolphinsGrace = b.dolphinsGrace;
            this.slowness = b.slowness;
            this.speed = b.speed;
        }

        public boolean anySlippery() {
            return onIce || onPackedIce || onBlueIce;
        }

        public boolean anyClimbable() {
            return onLadder || onVine || onScaffolding;
        }

        static class Builder {
            boolean inWater, inLava, fireResistance;
            boolean onLadder, onVine, onScaffolding;
            boolean onSlimeBlock, onSoulSand;
            boolean onIce, onPackedIce, onBlueIce;
            boolean onSnowBlock, onSweetBerries, inCobweb, onMagmaBlock, onHoneyBlock;
            boolean inPowderSnowWithLeatherBoots;
            int soulSpeedLevel, depthStriderLevel, frostWalkerLevel;
            int jumpBoostLevel, levitationLevel;
            boolean slowFalling, dolphinsGrace, slowness, speed;
        }
    }

    public static MovementEnv classifyMovementEnvironment(Player player) {
        MovementEnv.Builder b = new MovementEnv.Builder();
        try {
            Location loc = player.getLocation();
            Material at = BlockCache.getType(loc);
            Material above = BlockCache.getType(loc.getWorld(), loc.getBlockX(), loc.getBlockY() + 1, loc.getBlockZ());
            Material below = BlockCache.getType(loc.getWorld(), loc.getBlockX(), loc.getBlockY() - 1, loc.getBlockZ());

            b.inWater = WaterHelper.isInWater(player) || at == Material.WATER || BlockCache.nameContains(at, "WATER");
            b.inLava = at == Material.LAVA || BlockCache.nameContains(at, "LAVA");
            b.onLadder = at == Material.LADDER || above == Material.LADDER;
            b.onVine = BlockCache.nameContains(at, "VINE") || at == Material.TWISTING_VINES || at == Material.WEEPING_VINES || at == Material.CAVE_VINES;
            b.onScaffolding = at == Material.SCAFFOLDING || above == Material.SCAFFOLDING || below == Material.SCAFFOLDING;
            b.onSlimeBlock = below == Material.SLIME_BLOCK;
            b.onHoneyBlock = below == Material.HONEY_BLOCK;
            b.onSoulSand = below == Material.SOUL_SAND || below == Material.SOUL_SOIL;
            b.onIce = below == Material.ICE;
            b.onPackedIce = below == Material.PACKED_ICE;
            b.onBlueIce = below == Material.BLUE_ICE;
            b.onSnowBlock = below == Material.SNOW_BLOCK || below == Material.SNOW;
            b.onSweetBerries = at == Material.SWEET_BERRY_BUSH || above == Material.SWEET_BERRY_BUSH;
            b.inCobweb = at == Material.COBWEB || above == Material.COBWEB;
            b.onMagmaBlock = below == Material.MAGMA_BLOCK;

            if (at == Material.POWDER_SNOW || above == Material.POWDER_SNOW) {
                org.bukkit.inventory.ItemStack boots = player.getInventory().getBoots();
                b.inPowderSnowWithLeatherBoots = boots != null && boots.getType() == Material.LEATHER_BOOTS;
            }
        } catch (Exception e) {
            Logger.getLogger("NoChance").log(Level.WARNING, "Failed to classify environment for " + player.getName(), e);
        }

        try {
            org.bukkit.inventory.ItemStack boots = player.getInventory().getBoots();
            if (boots != null) {
                try {
                    org.bukkit.enchantments.Enchantment soulSpeed = org.bukkit.enchantments.Enchantment.getByKey(
                        org.bukkit.NamespacedKey.minecraft("soul_speed"));
                    if (soulSpeed != null) b.soulSpeedLevel = boots.getEnchantmentLevel(soulSpeed);
                } catch (Exception ignored) {}
                try {
                    org.bukkit.enchantments.Enchantment depthStrider = org.bukkit.enchantments.Enchantment.getByKey(
                        org.bukkit.NamespacedKey.minecraft("depth_strider"));
                    if (depthStrider != null) b.depthStriderLevel = boots.getEnchantmentLevel(depthStrider);
                } catch (Exception ignored) {}
                try {
                    org.bukkit.enchantments.Enchantment frostWalker = org.bukkit.enchantments.Enchantment.getByKey(
                        org.bukkit.NamespacedKey.minecraft("frost_walker"));
                    if (frostWalker != null) b.frostWalkerLevel = boots.getEnchantmentLevel(frostWalker);
                } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}

        try {
            PotionEffectType jumpType = getEffectType("JUMP_BOOST", "JUMP");
            if (jumpType != null) {
                PotionEffect e = player.getPotionEffect(jumpType);
                if (e != null) b.jumpBoostLevel = e.getAmplifier() + 1;
            }
            PotionEffectType levType = getEffectType("LEVITATION");
            if (levType != null) {
                PotionEffect e = player.getPotionEffect(levType);
                if (e != null) b.levitationLevel = e.getAmplifier() + 1;
            }
            PotionEffectType slowFallType = getEffectType("SLOW_FALLING");
            b.slowFalling = slowFallType != null && player.hasPotionEffect(slowFallType);
            PotionEffectType dolphinType = getEffectType("DOLPHINS_GRACE");
            b.dolphinsGrace = dolphinType != null && player.hasPotionEffect(dolphinType);
            PotionEffectType slowType = getEffectType("SLOWNESS", "SLOW");
            b.slowness = slowType != null && player.hasPotionEffect(slowType);
            PotionEffectType spdType = getEffectType("SPEED");
            b.speed = spdType != null && player.hasPotionEffect(spdType);
            PotionEffectType fireResType = getEffectType("FIRE_RESISTANCE");
            b.fireResistance = fireResType != null && player.hasPotionEffect(fireResType);
        } catch (Exception e) {
            Logger.getLogger("NoChance").log(Level.WARNING, "Failed to read effects for " + player.getName(), e);
        }

        return new MovementEnv(b);
    }

    public static class EnvironmentalFactors {
        public double speedMultiplier;
        public double gravityMultiplier;
        public double damageMultiplier;
        public double reachBonus;
        public boolean hasStatusEffects;
        public boolean onSlipperyBlock;
        public boolean inDangerZone;
        public String details;

        public EnvironmentalFactors() {
            this.speedMultiplier = 1.0;
            this.gravityMultiplier = 1.0;
            this.damageMultiplier = 1.0;
            this.reachBonus = 0.0;
            this.hasStatusEffects = false;
            this.onSlipperyBlock = false;
            this.inDangerZone = false;
            this.details = "None";
        }
    }

    public static EnvironmentalFactors calculate(Player player) {
        EnvironmentalFactors factors = new EnvironmentalFactors();
        StringBuilder details = new StringBuilder();

        applyPotionEffects(player, factors, details);
        applyBlockEffects(player, factors, details);
        applyBeaconEffects(player, factors, details);
        applyConduitEffects(player, factors, details);

        factors.details = details.length() > 0 ? details.substring(0, Math.min(details.length(), 200)) : "None";

        return factors;
    }

    private static void applyPotionEffects(Player player, EnvironmentalFactors factors, StringBuilder details) {
        try {
            PotionEffectType speedType = getEffectType("SPEED");
            if (speedType != null) {
                PotionEffect speed = player.getPotionEffect(speedType);
                if (speed != null) {
                    int level = speed.getAmplifier() + 1;
                    factors.speedMultiplier *= (1.0 + (level * 0.2));
                    details.append("Speed ").append(level).append(", ");
                    factors.hasStatusEffects = true;
                }
            }

            PotionEffectType slownessType = getEffectType("SLOW", "SLOWNESS");
            if (slownessType != null) {
                PotionEffect slowness = player.getPotionEffect(slownessType);
                if (slowness != null) {
                    int level = slowness.getAmplifier() + 1;
                    factors.speedMultiplier *= Math.max(0.1, 1.0 - (level * 0.15));
                    details.append("Slowness ").append(level).append(", ");
                    factors.hasStatusEffects = true;
                }
            }

            PotionEffectType jumpType = getEffectType("JUMP", "JUMP_BOOST");
            if (jumpType != null) {
                PotionEffect jumpBoost = player.getPotionEffect(jumpType);
                if (jumpBoost != null) {
                    int level = jumpBoost.getAmplifier() + 1;
                    factors.gravityMultiplier *= (1.0 + (level * 0.1));
                    details.append("Jump Boost ").append(level).append(", ");
                    factors.hasStatusEffects = true;
                }
            }

            PotionEffectType levitationType = getEffectType("LEVITATION");
            if (levitationType != null) {
                PotionEffect levitation = player.getPotionEffect(levitationType);
                if (levitation != null) {
                    int level = levitation.getAmplifier() + 1;
                    factors.gravityMultiplier *= (1.0 + (level * 0.3));
                    details.append("Levitation ").append(level).append(", ");
                    factors.hasStatusEffects = true;
                }
            }

            PotionEffectType slowFallingType = getEffectType("SLOW_FALLING");
            if (slowFallingType != null) {
                PotionEffect slowFalling = player.getPotionEffect(slowFallingType);
                if (slowFalling != null) {
                    factors.gravityMultiplier *= 0.1;
                    details.append("Slow Falling, ");
                    factors.hasStatusEffects = true;
                }
            }

            PotionEffectType strengthType = getEffectType("INCREASE_DAMAGE", "STRENGTH");
            if (strengthType != null) {
                PotionEffect strength = player.getPotionEffect(strengthType);
                if (strength != null) {
                    int level = strength.getAmplifier() + 1;
                    factors.damageMultiplier *= (1.0 + (level * 0.3));
                    details.append("Strength ").append(level).append(", ");
                    factors.hasStatusEffects = true;
                }
            }

            PotionEffectType weaknessType = getEffectType("WEAKNESS");
            if (weaknessType != null) {
                PotionEffect weakness = player.getPotionEffect(weaknessType);
                if (weakness != null) {
                    int level = weakness.getAmplifier() + 1;
                    factors.damageMultiplier *= Math.max(0.0, 1.0 - (level * 0.2));
                    details.append("Weakness ").append(level).append(", ");
                    factors.hasStatusEffects = true;
                }
            }
        } catch (Exception e) {
            Logger.getLogger("NoChance").log(Level.WARNING, "Failed to apply potion effects for " + player.getName(), e);
        }
    }

    private static void applyBlockEffects(Player player, EnvironmentalFactors factors, StringBuilder details) {
        try {
            Block blockBelow = player.getLocation().clone().subtract(0, 0.5, 0).getBlock();
            Material belowType = blockBelow.getType();

            if (belowType == Material.ICE || belowType == Material.PACKED_ICE || belowType == Material.BLUE_ICE) {
                factors.speedMultiplier *= 1.8;
                factors.onSlipperyBlock = true;
                details.append("Ice, ");
            }

            if (belowType == Material.SLIME_BLOCK) {
                factors.gravityMultiplier *= 2.0;
                details.append("Slime Block, ");
            }

            if (belowType == Material.HONEY_BLOCK) {
                factors.speedMultiplier *= 0.4;
                factors.gravityMultiplier *= 0.5;
                details.append("Honey Block, ");
            }

            Block blockAt = player.getLocation().getBlock();
            Material atType = blockAt.getType();

            if (atType == Material.COBWEB) {
                factors.speedMultiplier *= 0.15;
                factors.gravityMultiplier *= 0.1;
                details.append("Cobweb, ");
            }

            if (belowType == Material.SOUL_SAND || belowType == Material.SOUL_SOIL) {
                if (!hasSoulSpeed(player)) {
                    factors.speedMultiplier *= 0.4;
                    details.append("Soul Sand, ");
                }
            }

            if (atType == Material.POWDER_SNOW) {
                factors.speedMultiplier *= 0.7;
                details.append("Powder Snow, ");
            }

            if (atType.name().contains("LAVA") || atType == Material.LAVA) {
                factors.speedMultiplier *= 0.3;
                factors.inDangerZone = true;
                details.append("Lava, ");
            }

            if (atType == Material.BUBBLE_COLUMN) {
                String blockData = blockAt.getBlockData().getAsString();
                if (blockData.contains("drag=false")) {
                    factors.gravityMultiplier *= -2.0;
                    details.append("Bubble Column (Up), ");
                } else {
                    factors.gravityMultiplier *= -1.5;
                    details.append("Bubble Column (Down), ");
                }
            }
        } catch (Exception e) {
            Logger.getLogger("NoChance").log(Level.WARNING, "Failed to apply block effects for " + player.getName(), e);
        }
    }

    private static void applyBeaconEffects(Player player, EnvironmentalFactors factors, StringBuilder details) {
        try {
            PotionEffectType hasteType = getEffectType("FAST_DIGGING", "HASTE");
            if (hasteType != null) {
                PotionEffect haste = player.getPotionEffect(hasteType);
                if (haste != null && haste.getDuration() > 200) {
                    int level = haste.getAmplifier() + 1;
                    details.append("Beacon Haste ").append(level).append(", ");
                }
            }

            PotionEffectType resistanceType = getEffectType("DAMAGE_RESISTANCE", "RESISTANCE");
            if (resistanceType != null) {
                PotionEffect resistance = player.getPotionEffect(resistanceType);
                if (resistance != null && resistance.getDuration() > 200) {
                    factors.damageMultiplier *= 0.8;
                    details.append("Beacon Resistance, ");
                }
            }
        } catch (Exception e) {
            Logger.getLogger("NoChance").log(Level.WARNING, "Failed to apply beacon effects for " + player.getName(), e);
        }
    }

    private static void applyConduitEffects(Player player, EnvironmentalFactors factors, StringBuilder details) {
        try {
            PotionEffectType conduitType = getEffectType("CONDUIT_POWER");
            if (conduitType != null) {
                PotionEffect conduitPower = player.getPotionEffect(conduitType);
                if (conduitPower != null) {
                    factors.speedMultiplier *= 1.3;
                    details.append("Conduit Power, ");
                }
            }
        } catch (Exception e) {
            Logger.getLogger("NoChance").log(Level.WARNING, "Failed to apply conduit effects for " + player.getName(), e);
        }
    }

    private static boolean hasSoulSpeed(Player player) {
        try {
            org.bukkit.inventory.ItemStack boots = player.getInventory().getBoots();
            if (boots == null) return false;

            org.bukkit.enchantments.Enchantment soulSpeed = null;

            try {
                soulSpeed = org.bukkit.enchantments.Enchantment.getByKey(
                    org.bukkit.NamespacedKey.minecraft("soul_speed")
                );
            } catch (Exception e) {
            }

            if (soulSpeed == null) {
                try {
                    soulSpeed = org.bukkit.enchantments.Enchantment.getByName("SOUL_SPEED");
                } catch (Exception e) {
                }
            }

            return soulSpeed != null && boots.containsEnchantment(soulSpeed);
        } catch (Exception e) {
            Logger.getLogger("NoChance").log(Level.WARNING, "Failed to check soul speed for " + player.getName(), e);
            return false;
        }
    }

    public static boolean isInDangerousEnvironment(Player player) {
        try {
            Location loc = player.getLocation();
            Block block = loc.getBlock();
            Material type = block.getType();

            if (type == Material.LAVA || type.name().contains("LAVA")) {
                return true;
            }

            if (type == Material.FIRE || type.name().contains("FIRE")) {
                return true;
            }

            if (loc.getY() < 10) {
                return true;
            }

            if (player.getHealth() < 6.0) {
                return true;
            }

            if (player.getFoodLevel() < 6) {
                return true;
            }
        } catch (Exception e) {
            Logger.getLogger("NoChance").log(Level.WARNING, "Failed to check dangerous environment for " + player.getName(), e);
        }

        return false;
    }

    private static PotionEffectType getEffectType(String... names) {
        for (String name : names) {
            try {
                PotionEffectType type = PotionEffectType.getByName(name);
                if (type != null) return type;
            } catch (Exception e) {
                Logger.getLogger("NoChance").log(Level.WARNING, "Failed to resolve PotionEffectType: " + name, e);
            }
        }
        return null;
    }
}
