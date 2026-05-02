package NC.noChance.core;

import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import java.util.logging.Level;
import java.util.logging.Logger;

public class EnchantHelper {

    public static int getEfficiencyLevel(ItemStack item) {
        if (item == null) return 0;

        try {
            Enchantment effEnch = null;

            try {
                effEnch = Enchantment.getByKey(org.bukkit.NamespacedKey.minecraft("efficiency"));
            } catch (Exception e) {
            }

            if (effEnch == null) {
                try {
                    effEnch = Enchantment.getByName("DIG_SPEED");
                } catch (Exception e) {
                }
            }

            if (effEnch == null) {
                try {
                    effEnch = Enchantment.getByName("EFFICIENCY");
                } catch (Exception e) {
                }
            }

            if (effEnch != null && item.containsEnchantment(effEnch)) {
                return item.getEnchantmentLevel(effEnch);
            }

            for (Enchantment ench : item.getEnchantments().keySet()) {
                try {
                    String enchKey = ench.getKey().getKey().toLowerCase();
                    if (enchKey.contains("efficiency") || enchKey.equals("dig_speed")) {
                        return item.getEnchantmentLevel(ench);
                    }
                } catch (Exception e) {
                    String enchName = ench.getName();
                    if (enchName != null && (enchName.toUpperCase().contains("EFFICIENCY") || enchName.toUpperCase().contains("DIG"))) {
                        return item.getEnchantmentLevel(ench);
                    }
                }
            }
        } catch (Exception e) {
            Logger.getLogger("NoChance").log(Level.WARNING, "Failed to get efficiency level", e);
        }

        return 0;
    }

    public static int getSharpnessLevel(ItemStack item) {
        if (item == null) return 0;

        try {
            Enchantment sharpEnch = null;

            try {
                sharpEnch = Enchantment.getByKey(org.bukkit.NamespacedKey.minecraft("sharpness"));
            } catch (Exception e) {
            }

            if (sharpEnch == null) {
                try {
                    sharpEnch = Enchantment.getByName("DAMAGE_ALL");
                } catch (Exception e) {
                }
            }

            if (sharpEnch == null) {
                try {
                    sharpEnch = Enchantment.getByName("SHARPNESS");
                } catch (Exception e) {
                }
            }

            if (sharpEnch != null && item.containsEnchantment(sharpEnch)) {
                return item.getEnchantmentLevel(sharpEnch);
            }

            for (Enchantment ench : item.getEnchantments().keySet()) {
                try {
                    String enchKey = ench.getKey().getKey().toLowerCase();
                    if (enchKey.contains("sharpness") || enchKey.equals("damage_all")) {
                        return item.getEnchantmentLevel(ench);
                    }
                } catch (Exception e) {
                    String enchName = ench.getName();
                    if (enchName != null && (enchName.toUpperCase().contains("SHARPNESS") || enchName.toUpperCase().contains("DAMAGE"))) {
                        return item.getEnchantmentLevel(ench);
                    }
                }
            }
        } catch (Exception e) {
            Logger.getLogger("NoChance").log(Level.WARNING, "Failed to get sharpness level", e);
        }

        return 0;
    }

    public static boolean hasAquaAffinity(ItemStack helmet) {
        if (helmet == null) return false;

        try {
            Enchantment aquaAffinityEnch = null;

            try {
                aquaAffinityEnch = Enchantment.getByKey(org.bukkit.NamespacedKey.minecraft("aqua_affinity"));
            } catch (Exception e) {
            }

            if (aquaAffinityEnch == null) {
                try {
                    aquaAffinityEnch = Enchantment.getByName("WATER_WORKER");
                } catch (Exception e) {
                }
            }

            if (aquaAffinityEnch == null) {
                try {
                    aquaAffinityEnch = Enchantment.getByName("AQUA_AFFINITY");
                } catch (Exception e) {
                }
            }

            if (aquaAffinityEnch != null && helmet.containsEnchantment(aquaAffinityEnch)) {
                return true;
            }

            for (Enchantment ench : helmet.getEnchantments().keySet()) {
                try {
                    String enchKey = ench.getKey().getKey().toLowerCase();
                    if (enchKey.contains("aqua") || enchKey.contains("water")) {
                        return true;
                    }
                } catch (Exception e) {
                    String enchName = ench.getName();
                    if (enchName != null && (enchName.toUpperCase().contains("AQUA") || enchName.toUpperCase().contains("WATER"))) {
                        return true;
                    }
                }
            }
        } catch (Exception e) {
            Logger.getLogger("NoChance").log(Level.WARNING, "Failed to check aqua affinity", e);
        }

        return false;
    }

    public static int getProtectionLevel(ItemStack item) {
        if (item == null) return 0;

        try {
            Enchantment protEnch = null;

            try {
                protEnch = Enchantment.getByKey(org.bukkit.NamespacedKey.minecraft("protection"));
            } catch (Exception e) {
            }

            if (protEnch == null) {
                try {
                    protEnch = Enchantment.getByName("PROTECTION_ENVIRONMENTAL");
                } catch (Exception e) {
                }
            }

            if (protEnch == null) {
                try {
                    protEnch = Enchantment.getByName("PROTECTION");
                } catch (Exception e) {
                }
            }

            if (protEnch != null && item.containsEnchantment(protEnch)) {
                return item.getEnchantmentLevel(protEnch);
            }

            for (Enchantment ench : item.getEnchantments().keySet()) {
                try {
                    String enchKey = ench.getKey().getKey().toLowerCase();
                    if (enchKey.contains("protection") && !enchKey.contains("fire") && !enchKey.contains("blast") && !enchKey.contains("projectile")) {
                        return item.getEnchantmentLevel(ench);
                    }
                } catch (Exception e) {
                    String enchName = ench.getName();
                    if (enchName != null && enchName.toUpperCase().contains("PROTECTION")
                            && !enchName.toUpperCase().contains("FIRE")
                            && !enchName.toUpperCase().contains("BLAST")
                            && !enchName.toUpperCase().contains("PROJECTILE")) {
                        return item.getEnchantmentLevel(ench);
                    }
                }
            }
        } catch (Exception e) {
            Logger.getLogger("NoChance").log(Level.WARNING, "Failed to get protection level", e);
        }

        return 0;
    }

    public static int getUnbreakingLevel(ItemStack item) {
        if (item == null) return 0;

        try {
            Enchantment unbreakingEnch = null;

            try {
                unbreakingEnch = Enchantment.getByKey(org.bukkit.NamespacedKey.minecraft("unbreaking"));
            } catch (Exception e) {
            }

            if (unbreakingEnch == null) {
                try {
                    unbreakingEnch = Enchantment.getByName("DURABILITY");
                } catch (Exception e) {
                }
            }

            if (unbreakingEnch == null) {
                try {
                    unbreakingEnch = Enchantment.getByName("UNBREAKING");
                } catch (Exception e) {
                }
            }

            if (unbreakingEnch != null && item.containsEnchantment(unbreakingEnch)) {
                return item.getEnchantmentLevel(unbreakingEnch);
            }
        } catch (Exception e) {
            Logger.getLogger("NoChance").log(Level.WARNING, "Failed to get unbreaking level", e);
        }

        return 0;
    }

    public static int getSmiteLevel(ItemStack item) {
        if (item == null) return 0;

        try {
            Enchantment smiteEnch = null;

            try {
                smiteEnch = Enchantment.getByKey(org.bukkit.NamespacedKey.minecraft("smite"));
            } catch (Exception e) {
            }

            if (smiteEnch == null) {
                try {
                    smiteEnch = Enchantment.getByName("DAMAGE_UNDEAD");
                } catch (Exception e) {
                }
            }

            if (smiteEnch == null) {
                try {
                    smiteEnch = Enchantment.getByName("SMITE");
                } catch (Exception e) {
                }
            }

            if (smiteEnch != null && item.containsEnchantment(smiteEnch)) {
                return item.getEnchantmentLevel(smiteEnch);
            }

            for (Enchantment ench : item.getEnchantments().keySet()) {
                try {
                    String enchKey = ench.getKey().getKey().toLowerCase();
                    if (enchKey.contains("smite") || enchKey.equals("damage_undead")) {
                        return item.getEnchantmentLevel(ench);
                    }
                } catch (Exception e) {
                    String enchName = ench.getName();
                    if (enchName != null && (enchName.toUpperCase().contains("SMITE") || enchName.toUpperCase().contains("UNDEAD"))) {
                        return item.getEnchantmentLevel(ench);
                    }
                }
            }
        } catch (Exception e) {
            Logger.getLogger("NoChance").log(Level.WARNING, "Failed to get smite level", e);
        }

        return 0;
    }

    public static int getBaneLevel(ItemStack item) {
        if (item == null) return 0;

        try {
            Enchantment baneEnch = null;

            try {
                baneEnch = Enchantment.getByKey(org.bukkit.NamespacedKey.minecraft("bane_of_arthropods"));
            } catch (Exception e) {
            }

            if (baneEnch == null) {
                try {
                    baneEnch = Enchantment.getByName("DAMAGE_ARTHROPODS");
                } catch (Exception e) {
                }
            }

            if (baneEnch == null) {
                try {
                    baneEnch = Enchantment.getByName("BANE_OF_ARTHROPODS");
                } catch (Exception e) {
                }
            }

            if (baneEnch != null && item.containsEnchantment(baneEnch)) {
                return item.getEnchantmentLevel(baneEnch);
            }

            for (Enchantment ench : item.getEnchantments().keySet()) {
                try {
                    String enchKey = ench.getKey().getKey().toLowerCase();
                    if (enchKey.contains("bane") || enchKey.contains("arthropod")) {
                        return item.getEnchantmentLevel(ench);
                    }
                } catch (Exception e) {
                    String enchName = ench.getName();
                    if (enchName != null && (enchName.toUpperCase().contains("BANE") || enchName.toUpperCase().contains("ARTHROPOD"))) {
                        return item.getEnchantmentLevel(ench);
                    }
                }
            }
        } catch (Exception e) {
            Logger.getLogger("NoChance").log(Level.WARNING, "Failed to get bane of arthropods level", e);
        }

        return 0;
    }

    public static boolean hasAquaAffinity(org.bukkit.entity.Player player) {
        if (player == null) return false;
        ItemStack helmet = player.getInventory().getHelmet();
        return hasAquaAffinity(helmet);
    }
}
