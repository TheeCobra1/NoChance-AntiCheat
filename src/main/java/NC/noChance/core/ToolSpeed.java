package NC.noChance.core;

import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

public class ToolSpeed {

    public static class BreakSpeedData {
        public double speedMultiplier;
        public int efficiencyLevel;
        public int hasteLevel;
        public String toolMaterial;
        public boolean hasCorrectTool;
        public boolean canInstamine;
        public double underwaterPenalty;
        public double midairPenalty;
        public String details;

        public BreakSpeedData() {
            this.speedMultiplier = 1.0;
            this.efficiencyLevel = 0;
            this.hasteLevel = 0;
            this.toolMaterial = "HAND";
            this.hasCorrectTool = false;
            this.canInstamine = false;
            this.underwaterPenalty = 1.0;
            this.midairPenalty = 1.0;
            this.details = "None";
        }

        public double getFinalSpeedMultiplier() {
            double speed = 1.0;

            if (hasCorrectTool) {
                switch (toolMaterial) {
                    case "NETHERITE":
                        speed = 9.0;
                        break;
                    case "DIAMOND":
                        speed = 8.0;
                        break;
                    case "GOLDEN":
                        speed = 12.0;
                        break;
                    case "IRON":
                        speed = 6.0;
                        break;
                    case "STONE":
                        speed = 4.0;
                        break;
                    case "WOOD":
                        speed = 2.0;
                        break;
                }
            } else {
                speed = 1.0;
            }

            if (efficiencyLevel > 0) {
                speed += (efficiencyLevel * efficiencyLevel) + 1.0;
            }

            if (hasteLevel > 0) {
                speed *= (1.0 + (0.2 * hasteLevel));
            }

            speed *= speedMultiplier;
            speed *= underwaterPenalty;
            speed *= midairPenalty;

            return speed;
        }
    }

    public static BreakSpeedData calculate(Player player, Block block) {
        BreakSpeedData data = new BreakSpeedData();
        StringBuilder details = new StringBuilder();

        if (player.getGameMode() == org.bukkit.GameMode.CREATIVE) {
            data.canInstamine = true;
            data.details = "Creative Mode";
            return data;
        }

        PotionEffectType hasteType = PotionEffectType.getByName("HASTE");
        if (hasteType == null) hasteType = PotionEffectType.getByName("FAST_DIGGING");
        if (hasteType != null) {
            PotionEffect haste = player.getPotionEffect(hasteType);
            if (haste != null) {
                data.hasteLevel = haste.getAmplifier() + 1;
                details.append("Haste ").append(data.hasteLevel).append(", ");
            }
        }

        PotionEffectType conduitType = PotionEffectType.getByName("CONDUIT_POWER");
        if (conduitType != null) {
            PotionEffect conduit = player.getPotionEffect(conduitType);
            if (conduit != null) {
                int conduitHaste = conduit.getAmplifier() + 1;
                if (conduitHaste > data.hasteLevel) {
                    data.hasteLevel = conduitHaste;
                }
                details.append("Conduit Power ").append(conduitHaste).append(", ");
            }
        }

        PotionEffectType fatigueType = PotionEffectType.getByName("MINING_FATIGUE");
        if (fatigueType == null) fatigueType = PotionEffectType.getByName("SLOW_DIGGING");
        if (fatigueType != null) {
            PotionEffect fatigue = player.getPotionEffect(fatigueType);
            if (fatigue != null) {
                int level = fatigue.getAmplifier() + 1;
                if (level == 1) {
                    data.speedMultiplier *= 0.3;
                } else if (level == 2) {
                    data.speedMultiplier *= 0.09;
                } else if (level == 3) {
                    data.speedMultiplier *= 0.0027;
                } else {
                    data.speedMultiplier *= 0.00081;
                }
                details.append("Mining Fatigue ").append(level).append(", ");
            }
        }

        ItemStack tool = player.getInventory().getItemInMainHand();
        if (tool != null && tool.getType() != Material.AIR && !EnhancementTracker.isToolBroken(tool)) {
            data.efficiencyLevel = EnchantHelper.getEfficiencyLevel(tool);

            String toolName = tool.getType().name().toUpperCase();
            Material blockType = block.getType();

            if (toolName.contains("PICKAXE")) {
                if (isPickaxeBlock(blockType)) {
                    data.hasCorrectTool = true;
                }
                data.toolMaterial = extractToolMaterial(toolName);
            } else if (toolName.contains("AXE") && !toolName.contains("PICKAXE")) {
                if (isAxeBlock(blockType)) {
                    data.hasCorrectTool = true;
                }
                data.toolMaterial = extractToolMaterial(toolName);
            } else if (toolName.contains("SHOVEL") || toolName.contains("SPADE")) {
                if (isShovelBlock(blockType)) {
                    data.hasCorrectTool = true;
                }
                data.toolMaterial = extractToolMaterial(toolName);
            } else if (toolName.contains("HOE")) {
                if (isHoeBlock(blockType)) {
                    data.hasCorrectTool = true;
                }
                data.toolMaterial = extractToolMaterial(toolName);
            } else if (toolName.contains("SWORD")) {
                if (isSwordBlock(blockType)) {
                    data.hasCorrectTool = true;
                }
                data.toolMaterial = extractToolMaterial(toolName);
            } else if (toolName.contains("SHEARS")) {
                if (isShearsBlock(blockType)) {
                    data.hasCorrectTool = true;
                }
                data.toolMaterial = "HAND";
            }

            if (data.hasCorrectTool) {
                details.append(data.toolMaterial).append(" Tool, ");
            }

            if (data.efficiencyLevel > 0) {
                details.append("Efficiency ").append(data.efficiencyLevel).append(", ");
            }

            double toolSpeed = data.getFinalSpeedMultiplier();

            if (toolName.contains("SHEARS") && data.hasCorrectTool) {
                String bn = block.getType().name();
                double shearsBase = 1.5;
                if (bn.contains("WOOL") || bn.contains("CARPET")) shearsBase = 5.0;
                else if (block.getType() == Material.COBWEB) shearsBase = 15.0;
                else if (bn.contains("LEAVES") || bn.contains("VINE") || bn.contains("GLOW_LICHEN")) shearsBase = 2.0;
                toolSpeed = shearsBase;
                if (data.efficiencyLevel > 0) {
                    toolSpeed += (data.efficiencyLevel * data.efficiencyLevel) + 1.0;
                }
                if (data.hasteLevel > 0) {
                    toolSpeed *= (0.2 * data.hasteLevel) + 1.0;
                }
            }

            if (toolName.contains("SWORD") && block.getType() == Material.COBWEB) {
                toolSpeed = 15.0;
                if (data.efficiencyLevel > 0) {
                    toolSpeed += (data.efficiencyLevel * data.efficiencyLevel) + 1.0;
                }
                if (data.hasteLevel > 0) {
                    toolSpeed *= (0.2 * data.hasteLevel) + 1.0;
                }
            }

            if (toolName.contains("SWORD") && block.getType() == Material.BAMBOO) {
                data.canInstamine = true;
                details.append("Sword+Bamboo Instamine, ");
            }

            double blockHardness = getHardness(block.getType());
            if (!data.canInstamine && blockHardness > 0) {
                double divisor = data.hasCorrectTool ? 30.0 : 100.0;
                double damage = toolSpeed / blockHardness / divisor;
                if (damage >= 1.0) {
                    data.canInstamine = true;
                    details.append("Instamine Capable, ");
                }
            } else if (blockHardness <= 0) {
                data.canInstamine = true;
            }
        }

        boolean inLiquid = player.isInWater() || player.getLocation().getBlock().getType() == Material.LAVA;
        if (WaterHelper.isInWater(player) && !EnchantHelper.hasAquaAffinity(player.getInventory().getHelmet())) {
            data.underwaterPenalty = 0.2;
            details.append("Underwater Penalty, ");
        }

        if (!player.isOnGround() && !player.isFlying() && !inLiquid) {
            data.midairPenalty = 0.2;
            details.append("Midair Penalty, ");
        }

        data.details = details.length() > 0 ? details.substring(0, details.length() - 2) : "None";
        return data;
    }

    public static double getHardness(Material type) {
        if (type == null || type == Material.AIR) return 0.0;
        String name = type.name();

        if (type == Material.BEDROCK || type == Material.END_PORTAL_FRAME ||
            type == Material.COMMAND_BLOCK || type == Material.BARRIER ||
            name.contains("COMMAND_BLOCK") || type == Material.STRUCTURE_BLOCK ||
            type == Material.JIGSAW) {
            return -1.0;
        }

        if (type == Material.OBSIDIAN || type == Material.CRYING_OBSIDIAN ||
            type == Material.RESPAWN_ANCHOR || type == Material.ENCHANTING_TABLE) {
            return 50.0;
        }
        if (name.equals("TRIAL_SPAWNER") || name.equals("VAULT")) return 50.0;
        if (name.equals("HEAVY_CORE")) return 10.0;
        if (type == Material.NETHERITE_BLOCK) return 50.0;
        if (type == Material.ANCIENT_DEBRIS) return 30.0;
        if (type == Material.ENDER_CHEST) return 22.5;
        if (type == Material.ANVIL || name.contains("ANVIL")) return 5.0;

        if (type == Material.DIAMOND_BLOCK || type == Material.EMERALD_BLOCK ||
            type == Material.IRON_BLOCK || type == Material.REDSTONE_BLOCK ||
            type == Material.SPAWNER) {
            return 5.0;
        }

        if (name.contains("IRON_DOOR") || name.contains("IRON_TRAPDOOR")) return 5.0;
        if (name.contains("_ORE")) return name.contains("DEEPSLATE") ? 4.5 : 3.0;
        if (name.equals("REINFORCED_DEEPSLATE")) return 55.0;
        if (name.equals("DEEPSLATE")) return 3.0;
        if (name.contains("DEEPSLATE") && !name.contains("_ORE")) return 3.5;

        if (name.equals("STONE")) return 1.5;
        if (type == Material.COBBLESTONE) return 2.0;
        if (type == Material.END_STONE) return 3.0;
        if (name.contains("TUFF")) return 1.5;
        if (name.equals("CRAFTER")) return 1.5;
        if (name.equals("COPPER_GRATE") || name.equals("COPPER_BULB")) return 3.0;
        if (name.contains("STONE") && !name.contains("SAND") && !name.contains("RED")) return 1.5;
        if (name.contains("BRICKS") || name.contains("BRICK_")) return 2.0;
        if (name.contains("COBBLESTONE")) return 2.0;
        if (name.contains("TERRACOTTA")) return 1.25;
        if (name.contains("SANDSTONE")) return 0.8;
        if (name.contains("CONCRETE") && !name.contains("POWDER")) return 1.8;

        if (name.contains("LOG") || name.contains("WOOD") || name.contains("PLANKS") ||
            name.contains("_STEM") || name.contains("HYPHAE")) {
            return 2.0;
        }
        if (name.contains("FENCE") && !name.contains("NETHER")) return 2.0;
        if (type == Material.CRAFTING_TABLE || name.contains("CHEST")) return 2.5;
        if (name.contains("BOOKSHELF")) return 1.5;

        if (type == Material.GRASS_BLOCK) return 0.6;
        if (type == Material.DIRT || type == Material.COARSE_DIRT || type == Material.PODZOL ||
            type == Material.MYCELIUM || type == Material.ROOTED_DIRT ||
            type == Material.MUD || name.contains("FARMLAND")) {
            return 0.5;
        }
        if (name.contains("SAND") || name.contains("GRAVEL")) return 0.5;
        if (type == Material.CLAY) return 0.6;
        if (name.contains("CONCRETE_POWDER")) return 0.5;

        if (type == Material.SOUL_SAND || type == Material.SOUL_SOIL) return 0.5;
        if (type == Material.NETHERRACK) return 0.4;
        if (name.contains("NYLIUM")) return 0.4;

        if (name.contains("GLASS") || name.contains("GLOWSTONE") || type == Material.SEA_LANTERN) return 0.3;
        if (type == Material.ICE || type == Material.PACKED_ICE) return 0.5;
        if (type == Material.BLUE_ICE) return 2.8;
        if (type == Material.SNOW_BLOCK) return 0.2;
        if (type == Material.SNOW) return 0.1;

        if (name.contains("WOOL")) return 0.8;
        if (name.contains("CARPET")) return 0.1;
        if (name.contains("LEAVES")) return 0.2;
        if (type == Material.COBWEB) return 4.0;
        if (type == Material.HAY_BLOCK) return 0.5;
        if (type == Material.SPONGE || type == Material.WET_SPONGE) return 0.6;

        if (name.contains("CORAL_BLOCK")) return 1.5;
        if (type.name().equals("SCULK") || type.name().equals("SCULK_VEIN")) return 0.2;
        if (type.name().equals("SCULK_CATALYST") || type.name().equals("SCULK_SHRIEKER")) return 3.0;
        if (type.name().equals("SCULK_SENSOR")) return 1.5;
        if (type == Material.HONEY_BLOCK) return 0.0;
        if (type == Material.SLIME_BLOCK) return 0.0;

        if (type == Material.MELON) return 1.0;
        if (type == Material.PUMPKIN || type == Material.JACK_O_LANTERN) return 1.0;

        if (type == Material.BAMBOO) return 1.0;
        if (type == Material.CACTUS) return 0.4;
        if (type == Material.SUGAR_CANE) return 0.0;

        if (type.name().contains("TORCH") || type.name().contains("FLOWER") ||
            type == Material.TALL_GRASS || type == Material.FERN ||
            type == Material.DEAD_BUSH || name.contains("SAPLING") ||
            (name.contains("MUSHROOM") && !name.contains("BLOCK"))) {
            return 0.0;
        }

        return 1.5;
    }

    private static String extractToolMaterial(String toolName) {
        if (toolName.contains("NETHERITE")) return "NETHERITE";
        if (toolName.contains("DIAMOND")) return "DIAMOND";
        if (toolName.contains("GOLDEN")) return "GOLDEN";
        if (toolName.contains("IRON")) return "IRON";
        if (toolName.contains("STONE")) return "STONE";
        if (toolName.contains("WOODEN") || toolName.contains("WOOD")) return "WOOD";
        return "HAND";
    }

    private static boolean isPickaxeBlock(Material type) {
        String name = type.name();
        return name.contains("STONE") || name.contains("COBBLESTONE") ||
               name.contains("ANDESITE") || name.contains("DIORITE") ||
               name.contains("GRANITE") || name.contains("NETHERRACK") ||
               name.contains("BASALT") || name.contains("BLACKSTONE") ||
               name.contains("TERRACOTTA") || name.contains("BRICK") ||
               name.contains("PRISMARINE") || name.contains("SANDSTONE") ||
               name.contains("DEEPSLATE") || type == Material.END_STONE ||
               type == Material.OBSIDIAN || type == Material.CRYING_OBSIDIAN ||
               name.contains("_ORE") || type == Material.ANCIENT_DEBRIS ||
               type == Material.GILDED_BLACKSTONE ||
               name.contains("IRON_BLOCK") || name.contains("GOLD_BLOCK") ||
               name.contains("DIAMOND_BLOCK") || name.contains("EMERALD_BLOCK") ||
               name.contains("NETHERITE_BLOCK") || name.contains("COPPER") ||
               name.contains("REDSTONE") || type == Material.OBSERVER ||
               type == Material.DISPENSER || type == Material.DROPPER ||
               type == Material.HOPPER || type == Material.PISTON ||
               type == Material.STICKY_PISTON ||
               name.contains("RAIL") || type == Material.BREWING_STAND ||
               name.contains("CAULDRON") || type == Material.BELL ||
               type == Material.LANTERN || type == Material.SOUL_LANTERN ||
               type == Material.IRON_BARS || type == Material.GRINDSTONE ||
               type == Material.STONECUTTER || type == Material.LIGHTNING_ROD ||
               type == Material.LODESTONE || type == Material.CONDUIT ||
               type == Material.BEACON || type == Material.ENDER_CHEST ||
               type == Material.SPAWNER || type == Material.ENCHANTING_TABLE ||
               type == Material.RESPAWN_ANCHOR || name.contains("SHULKER_BOX") ||
               name.equals("HEAVY_CORE") || name.equals("TRIAL_SPAWNER") ||
               name.equals("VAULT") || name.equals("CRAFTER");
    }

    private static boolean isAxeBlock(Material type) {
        String name = type.name();
        return name.contains("LOG") || (name.contains("WOOD") && !name.contains("SWORD")) ||
               name.contains("PLANKS") || type == Material.CRAFTING_TABLE ||
               name.contains("CHEST") || name.contains("BARREL") ||
               name.contains("BOOKSHELF") || name.contains("LECTERN") ||
               name.contains("CAMPFIRE") || (name.contains("FENCE") && !name.contains("NETHER_BRICK") && !name.contains("IRON")) ||
               name.contains("_STEM") || name.contains("HYPHAE") ||
               name.contains("MUSHROOM_BLOCK") || name.contains("_SIGN") ||
               name.contains("_BANNER") || type == Material.PUMPKIN ||
               type == Material.CARVED_PUMPKIN || type == Material.JACK_O_LANTERN ||
               type == Material.MELON || type == Material.NOTE_BLOCK ||
               type == Material.JUKEBOX || type == Material.LADDER ||
               type == Material.COMPOSTER || type == Material.LOOM ||
               type == Material.CARTOGRAPHY_TABLE || type == Material.FLETCHING_TABLE ||
               type == Material.SMITHING_TABLE || type == Material.BEE_NEST ||
               type == Material.BEEHIVE || type == Material.DAYLIGHT_DETECTOR ||
               type == Material.COCOA;
    }

    private static boolean isShovelBlock(Material type) {
        String name = type.name();
        return name.contains("DIRT") || type == Material.GRASS_BLOCK ||
               type == Material.MYCELIUM || type == Material.PODZOL ||
               type == Material.CLAY || name.contains("FARMLAND") ||
               type == Material.ROOTED_DIRT || type == Material.COARSE_DIRT ||
               type == Material.MUD || name.contains("MUDDY") ||
               name.contains("SAND") || name.contains("GRAVEL") ||
               name.contains("CONCRETE_POWDER") || type == Material.SOUL_SAND ||
               type == Material.SOUL_SOIL ||
               type == Material.SNOW || type == Material.SNOW_BLOCK ||
               type == Material.POWDER_SNOW;
    }

    private static boolean isHoeBlock(Material type) {
        String name = type.name();
        return name.contains("LEAVES") || name.contains("SAPLING") ||
               type == Material.WHEAT || type == Material.CARROTS ||
               type == Material.POTATOES || type == Material.BEETROOTS ||
               type == Material.NETHER_WART || name.contains("WART_BLOCK") ||
               type == Material.HAY_BLOCK || type == Material.DRIED_KELP_BLOCK ||
               type == Material.TARGET || type == Material.SPONGE ||
               type == Material.WET_SPONGE || type == Material.SHROOMLIGHT ||
               type == Material.MOSS_BLOCK || name.contains("SCULK");
    }

    private static boolean isSwordBlock(Material type) {
        return type == Material.COBWEB || type == Material.BAMBOO;
    }

    private static boolean isShearsBlock(Material type) {
        String name = type.name();
        return name.contains("WOOL") || name.contains("CARPET") ||
               name.contains("LEAVES") || type == Material.COBWEB ||
               name.contains("VINE") || name.contains("SEAGRASS") ||
               type == Material.GLOW_LICHEN || name.contains("HANGING_ROOTS");
    }
}
