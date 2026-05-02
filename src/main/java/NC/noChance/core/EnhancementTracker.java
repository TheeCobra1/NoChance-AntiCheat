package NC.noChance.core;

import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import java.util.logging.Level;
import java.util.logging.Logger;

public class EnhancementTracker {

    public static class MovementEnhancements {
        public double speedMultiplier = 1.0;
        public double baseSpeed = 0.35;
        public boolean hasLegitFlight = false;
        public boolean inWater = false;
        public boolean onIce = false;
        public String reason = "";

        public double getMaxSpeed() {
            return baseSpeed * speedMultiplier;
        }
    }

    public static class BlockBreakEnhancements {
        public double breakSpeedMultiplier = 1.0;
        public int efficiencyLevel = 0;
        public int hasteLevel = 0;
        public boolean hasCorrectTool = false;
        public String toolMaterial = "HAND";
        public String toolType = "HAND";
        public boolean canInstamine = false;
        public String reason = "";

        public double getBreakSpeedMultiplier() {
            double speedMultiplier = 1.0;

            if (hasCorrectTool) {
                switch (toolMaterial) {
                    case "NETHERITE":
                        speedMultiplier = 9.0;
                        break;
                    case "DIAMOND":
                        speedMultiplier = 8.0;
                        break;
                    case "GOLDEN":
                        speedMultiplier = 12.0;
                        break;
                    case "IRON":
                        speedMultiplier = 6.0;
                        break;
                    case "STONE":
                        speedMultiplier = 4.0;
                        break;
                    case "WOOD":
                        speedMultiplier = 2.0;
                        break;
                }
            } else {
                speedMultiplier = 1.0;
            }

            if (efficiencyLevel > 0) {
                speedMultiplier += (efficiencyLevel * efficiencyLevel) + 1.0;
            }

            if (hasteLevel > 0) {
                speedMultiplier *= (0.2 * hasteLevel) + 1.0;
            }

            return speedMultiplier;
        }
    }

    public static class CombatEnhancements {
        public double damageMultiplier = 1.0;
        public double reachBonus = 0.0;
        public int sharpnessLevel = 0;
        public int strengthLevel = 0;
        public boolean criticalHit = false;
        public String reason = "";

        public double getDamageMultiplier() {
            double multiplier = damageMultiplier;

            if (sharpnessLevel > 0) {
                multiplier *= (1.0 + (sharpnessLevel * 0.125));
            }

            if (strengthLevel > 0) {
                multiplier *= (1.0 + (strengthLevel * 0.3));
            }

            if (criticalHit) {
                multiplier *= 1.5;
            }

            return multiplier;
        }
    }

    public static MovementEnhancements calculateMovementEnhancements(Player player) {
        MovementEnhancements enhancements = new MovementEnhancements();
        StringBuilder reasons = new StringBuilder();

        if (player.isFlying() && player.getAllowFlight()) {
            enhancements.hasLegitFlight = true;
            reasons.append("Creative/Spectator Flight, ");
        }

        if (player.isGliding()) {
            enhancements.speedMultiplier *= 3.5;
            enhancements.hasLegitFlight = true;
            reasons.append("Elytra, ");
        }

        if (player.isRiptiding()) {
            enhancements.speedMultiplier *= 4.0;
            reasons.append("Riptide, ");
        }

        if (player.isSprinting()) {
            enhancements.speedMultiplier *= 1.3;
            reasons.append("Sprinting, ");
        }

        PotionEffectType speedType = PotionEffectType.getByName("SPEED");
        if (speedType != null) {
            PotionEffect speedEffect = player.getPotionEffect(speedType);
            if (speedEffect != null) {
                int amplifier = speedEffect.getAmplifier() + 1;
                enhancements.speedMultiplier *= (1.0 + (amplifier * 0.2));
                reasons.append("Speed ").append(amplifier).append(", ");
            }
        }

        PotionEffectType dolphinsGraceType = PotionEffectType.getByName("DOLPHINS_GRACE");
        if (dolphinsGraceType != null) {
            PotionEffect dolphinsGrace = player.getPotionEffect(dolphinsGraceType);
            if (dolphinsGrace != null && (player.isSwimming() || player.isInWater())) {
                enhancements.speedMultiplier *= 1.5;
                enhancements.inWater = true;
                reasons.append("Dolphin's Grace, ");
            }
        }

        if (player.isSwimming() || player.isInWater()) {
            enhancements.inWater = true;
            ItemStack boots = player.getInventory().getBoots();
            if (boots != null) {
                Enchantment depthStriderEnch = Enchantment.getByName("DEPTH_STRIDER");
                if (depthStriderEnch != null) {
                    int depthStrider = boots.getEnchantmentLevel(depthStriderEnch);
                    if (depthStrider > 0) {
                        int dsLevel = Math.min(depthStrider, 3);
                        double waterPenalty = 0.4;
                        double dsMultiplier = waterPenalty + (1.0 - waterPenalty) * (dsLevel / 3.0);
                        enhancements.speedMultiplier *= (dsMultiplier / waterPenalty);
                        reasons.append("Depth Strider ").append(depthStrider).append(", ");
                    }
                }
            }
        }

        Block below = player.getLocation().clone().subtract(0, 0.5, 0).getBlock();
        Material belowType = below.getType();

        if (belowType == Material.ICE || belowType == Material.PACKED_ICE) {
            enhancements.speedMultiplier *= 2.0;
            enhancements.onIce = true;
            reasons.append("Ice, ");
        } else if (belowType == Material.BLUE_ICE) {
            enhancements.speedMultiplier *= 2.5;
            enhancements.onIce = true;
            reasons.append("Blue Ice, ");
        }

        if ((belowType == Material.SOUL_SAND || belowType == Material.SOUL_SOIL)) {
            ItemStack boots = player.getInventory().getBoots();
            int soulSpeed = 0;
            if (boots != null) {
                Enchantment soulSpeedEnch = Enchantment.getByName("SOUL_SPEED");
                if (soulSpeedEnch != null) {
                    soulSpeed = boots.getEnchantmentLevel(soulSpeedEnch);
                }
            }
            if (soulSpeed > 0) {
                enhancements.speedMultiplier *= (1.3 + (soulSpeed * 0.105));
                reasons.append("Soul Speed ").append(soulSpeed).append(", ");
            } else {
                enhancements.speedMultiplier *= 0.4;
                reasons.append("Soul Sand Slow, ");
            }
        }

        PotionEffectType slownessType = PotionEffectType.getByName("SLOWNESS");
        if (slownessType == null) slownessType = PotionEffectType.getByName("SLOW");
        if (slownessType != null) {
            PotionEffect slowness = player.getPotionEffect(slownessType);
            if (slowness != null) {
                int amplifier = slowness.getAmplifier() + 1;
                enhancements.speedMultiplier *= Math.max(0.1, 1.0 - (amplifier * 0.15));
                reasons.append("Slowness ").append(amplifier).append(", ");
            }
        }

        if (player.getVehicle() != null) {
            enhancements.speedMultiplier *= 2.5;
            reasons.append("Vehicle, ");
        }

        enhancements.reason = reasons.length() > 0 ? reasons.substring(0, reasons.length() - 2) : "None";
        return enhancements;
    }

    public static BlockBreakEnhancements calculateBlockBreakEnhancements(Player player, Block block) {
        ItemStack currentTool = player.getInventory().getItemInMainHand();
        String toolName = (currentTool != null && currentTool.getType() != Material.AIR) ? currentTool.getType().name() : "HAND";
        int efficiency = (currentTool != null && currentTool.getType() != Material.AIR) ? EnchantHelper.getEfficiencyLevel(currentTool) : 0;
        return calculateBlockBreakEnhancements(player, block, toolName, efficiency);
    }

    public static BlockBreakEnhancements calculateBlockBreakEnhancements(Player player, Block block, String initialToolName, int initialEfficiency) {
        BlockBreakEnhancements enhancements = new BlockBreakEnhancements();
        StringBuilder reasons = new StringBuilder();

        if (player.getGameMode() == org.bukkit.GameMode.CREATIVE) {
            enhancements.canInstamine = true;
            enhancements.reason = "Creative Mode";
            return enhancements;
        }

        PotionEffectType hasteType = PotionEffectType.getByName("HASTE");
        if (hasteType == null) hasteType = PotionEffectType.getByName("FAST_DIGGING");
        if (hasteType != null) {
            PotionEffect haste = player.getPotionEffect(hasteType);
            if (haste != null) {
                enhancements.hasteLevel = haste.getAmplifier() + 1;
                reasons.append("Haste ").append(enhancements.hasteLevel).append(", ");
            }
        }

        PotionEffectType conduitType = PotionEffectType.getByName("CONDUIT_POWER");
        if (conduitType != null) {
            PotionEffect conduit = player.getPotionEffect(conduitType);
            if (conduit != null) {
                int conduitHaste = conduit.getAmplifier() + 1;
                enhancements.hasteLevel = Math.max(enhancements.hasteLevel, conduitHaste);
                reasons.append("Conduit Power ").append(conduitHaste).append(", ");
            }
        }

        PotionEffectType fatigueType = PotionEffectType.getByName("MINING_FATIGUE");
        if (fatigueType == null) fatigueType = PotionEffectType.getByName("SLOW_DIGGING");
        if (fatigueType != null) {
            PotionEffect fatigue = player.getPotionEffect(fatigueType);
            if (fatigue != null) {
                int level = fatigue.getAmplifier() + 1;
                if (level == 1) {
                    enhancements.breakSpeedMultiplier *= 0.3;
                } else if (level == 2) {
                    enhancements.breakSpeedMultiplier *= 0.09;
                } else if (level == 3) {
                    enhancements.breakSpeedMultiplier *= 0.0027;
                } else {
                    enhancements.breakSpeedMultiplier *= 0.00081;
                }
                reasons.append("Mining Fatigue ").append(level).append(", ");
            }
        }

        ItemStack tool = null;
        String toolName = initialToolName;
        if (toolName != null && !toolName.equals("HAND")) {
            try {
                Material toolMat = Material.valueOf(toolName);
                tool = new ItemStack(toolMat);
                ItemStack actualTool = player.getInventory().getItemInMainHand();
                if (actualTool != null && actualTool.getType() == toolMat) {
                    tool = actualTool;
                }
            } catch (IllegalArgumentException e) {
                Logger.getLogger("NoChance").log(Level.WARNING, "Unknown tool material in enhancement tracker: " + toolName, e);
                tool = null;
            }
        }

        if (tool != null && tool.getType() != Material.AIR && !isToolBroken(tool)) {
            if (initialEfficiency > 0) {
                enhancements.efficiencyLevel = initialEfficiency;
            } else {
                enhancements.efficiencyLevel = EnchantHelper.getEfficiencyLevel(tool);
            }

            toolName = tool.getType().name().toUpperCase();
            Material blockType = block.getType();

            if (toolName.contains("PICKAXE")) {
                enhancements.toolType = "PICKAXE";
                if (isStone(blockType) || isOre(blockType) || isMetal(blockType) || isRedstone(blockType) || isIce(blockType)) {
                    enhancements.hasCorrectTool = true;
                }
                enhancements.toolMaterial = extractToolMaterial(toolName);
            } else if (toolName.contains("AXE") && !toolName.contains("PICKAXE")) {
                enhancements.toolType = "AXE";
                if (isWood(blockType)) {
                    enhancements.hasCorrectTool = true;
                }
                enhancements.toolMaterial = extractToolMaterial(toolName);
            } else if (toolName.contains("SHOVEL") || toolName.contains("SPADE")) {
                enhancements.toolType = "SHOVEL";
                if (isDirt(blockType) || isSand(blockType) || isSnow(blockType)) {
                    enhancements.hasCorrectTool = true;
                }
                enhancements.toolMaterial = extractToolMaterial(toolName);
            } else if (toolName.contains("HOE")) {
                enhancements.toolType = "HOE";
                if (isPlant(blockType) || isCrop(blockType) || isLeaves(blockType) || isSculk(blockType)) {
                    enhancements.hasCorrectTool = true;
                }
                enhancements.toolMaterial = extractToolMaterial(toolName);
            } else if (toolName.contains("SWORD")) {
                enhancements.toolType = "SWORD";
                if (blockType == Material.COBWEB || blockType == Material.BAMBOO) {
                    enhancements.hasCorrectTool = true;
                }
                enhancements.toolMaterial = extractToolMaterial(toolName);
            } else if (toolName.contains("SHEARS")) {
                enhancements.toolType = "SHEARS";
                if (isWool(blockType) || isLeaves(blockType) || blockType == Material.COBWEB) {
                    enhancements.hasCorrectTool = true;
                }
                enhancements.toolMaterial = "HAND";
            }

            if (enhancements.hasCorrectTool) {
                reasons.append(enhancements.toolMaterial).append(" Tool, ");
            }

            if (enhancements.efficiencyLevel > 0) {
                reasons.append("Efficiency ").append(enhancements.efficiencyLevel).append(", ");
            }

            double toolSpeed = enhancements.getBreakSpeedMultiplier();

            if (enhancements.toolType.equals("SHEARS") && enhancements.hasCorrectTool) {
                String bn = block.getType().name();
                double shearsBase = 1.5;
                if (bn.contains("WOOL") || bn.contains("CARPET")) shearsBase = 5.0;
                else if (block.getType() == Material.COBWEB) shearsBase = 15.0;
                else if (bn.contains("LEAVES") || bn.contains("VINE") || bn.contains("GLOW_LICHEN")) shearsBase = 2.0;
                toolSpeed = shearsBase;
                if (enhancements.efficiencyLevel > 0) {
                    toolSpeed += (enhancements.efficiencyLevel * enhancements.efficiencyLevel) + 1.0;
                }
                if (enhancements.hasteLevel > 0) {
                    toolSpeed *= (0.2 * enhancements.hasteLevel) + 1.0;
                }
            }

            if (enhancements.toolType.equals("SWORD") && block.getType() == Material.BAMBOO) {
                enhancements.canInstamine = true;
                reasons.append("Sword+Bamboo Instamine, ");
            }

            if (enhancements.toolType.equals("SWORD") && block.getType() == Material.COBWEB) {
                reasons.append("Sword+Cobweb 15x, ");
            }
        }

        boolean inLiquid = player.isInWater() || player.getLocation().getBlock().getType() == Material.LAVA;
        if (player.isInWater() && !hasAquaAffinity(player)) {
            enhancements.breakSpeedMultiplier *= 0.2;
            reasons.append("Underwater Penalty, ");
        }

        if (!player.isOnGround() && !player.isFlying() && !inLiquid) {
            enhancements.breakSpeedMultiplier *= 0.2;
            reasons.append("Midair Penalty, ");
        }

        if (!enhancements.canInstamine) {
            double finalSpeed = enhancements.getBreakSpeedMultiplier() * enhancements.breakSpeedMultiplier;

            if (enhancements.toolType.equals("SHEARS") && enhancements.hasCorrectTool) {
                String bn = block.getType().name();
                double shearsBase = 1.5;
                if (bn.contains("WOOL") || bn.contains("CARPET")) shearsBase = 5.0;
                else if (block.getType() == Material.COBWEB) shearsBase = 15.0;
                else if (bn.contains("LEAVES") || bn.contains("VINE") || bn.contains("GLOW_LICHEN")) shearsBase = 2.0;
                finalSpeed = shearsBase;
                if (enhancements.efficiencyLevel > 0) {
                    finalSpeed += (enhancements.efficiencyLevel * enhancements.efficiencyLevel) + 1.0;
                }
                if (enhancements.hasteLevel > 0) {
                    finalSpeed *= (0.2 * enhancements.hasteLevel) + 1.0;
                }
                finalSpeed *= enhancements.breakSpeedMultiplier;

                double shearsHardness = ToolSpeed.getHardness(block.getType());
                if (shearsHardness > 0) {
                    double shearsDamage = finalSpeed / shearsHardness / 30.0;
                    if (shearsDamage >= 1.0) {
                        enhancements.canInstamine = true;
                        reasons.append("Shears Instamine, ");
                    }
                }
            } else if (enhancements.toolType.equals("SWORD") && block.getType() == Material.COBWEB) {
                finalSpeed = 15.0;
                if (enhancements.efficiencyLevel > 0) {
                    finalSpeed += (enhancements.efficiencyLevel * enhancements.efficiencyLevel) + 1.0;
                }
                if (enhancements.hasteLevel > 0) {
                    finalSpeed *= (0.2 * enhancements.hasteLevel) + 1.0;
                }
                finalSpeed *= enhancements.breakSpeedMultiplier;

                double cobwebHardness = ToolSpeed.getHardness(block.getType());
                if (cobwebHardness > 0) {
                    double damage = finalSpeed / cobwebHardness / 30.0;
                    if (damage >= 1.0) {
                        enhancements.canInstamine = true;
                        reasons.append("Sword+Cobweb Instamine, ");
                    }
                }
            } else {
                double blockHardness = ToolSpeed.getHardness(block.getType());
                if (blockHardness > 0) {
                    double divisor = enhancements.hasCorrectTool ? 30.0 : 100.0;
                    double damage = finalSpeed / blockHardness / divisor;
                    if (damage >= 1.0) {
                        enhancements.canInstamine = true;
                        reasons.append("Instamine Capable, ");
                    }
                } else if (blockHardness <= 0) {
                    enhancements.canInstamine = true;
                }
            }
        }

        enhancements.reason = reasons.length() > 0 ? reasons.substring(0, reasons.length() - 2) : "None";
        return enhancements;
    }

    public static CombatEnhancements calculateCombatEnhancements(Player player) {
        CombatEnhancements enhancements = new CombatEnhancements();
        StringBuilder reasons = new StringBuilder();

        ItemStack weapon = player.getInventory().getItemInMainHand();
        if (weapon != null && weapon.getType() != Material.AIR) {
            enhancements.sharpnessLevel = EnchantHelper.getSharpnessLevel(weapon);
            if (enhancements.sharpnessLevel > 0) {
                reasons.append("Sharpness ").append(enhancements.sharpnessLevel).append(", ");
            }

            int smite = EnchantHelper.getSmiteLevel(weapon);
            if (smite > 0) {
                reasons.append("Smite ").append(smite).append(", ");
            }

            int bane = EnchantHelper.getBaneLevel(weapon);
            if (bane > 0) {
                reasons.append("Bane ").append(bane).append(", ");
            }
        }

        PotionEffectType strengthType = PotionEffectType.getByName("STRENGTH");
        if (strengthType == null) strengthType = PotionEffectType.getByName("INCREASE_DAMAGE");
        if (strengthType != null) {
            PotionEffect strength = player.getPotionEffect(strengthType);
            if (strength != null) {
                enhancements.strengthLevel = strength.getAmplifier() + 1;
                reasons.append("Strength ").append(enhancements.strengthLevel).append(", ");
            }
        }

        if (player.getFallDistance() > 0 && !player.isOnGround()) {
            enhancements.criticalHit = true;
            reasons.append("Critical Hit, ");
        }

        enhancements.reason = reasons.length() > 0 ? reasons.substring(0, reasons.length() - 2) : "None";
        return enhancements;
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

    private static boolean isStone(Material type) {
        String name = type.name();
        return name.contains("STONE") || name.contains("COBBLESTONE") ||
               name.contains("ANDESITE") || name.contains("DIORITE") ||
               name.contains("GRANITE") || name.contains("NETHERRACK") ||
               name.contains("BASALT") || name.contains("BLACKSTONE") ||
               name.contains("TERRACOTTA") || name.contains("BRICK") ||
               name.contains("TUFF") ||
               name.contains("PRISMARINE") || name.contains("SANDSTONE") ||
               name.contains("DEEPSLATE") || type == Material.END_STONE ||
               type == Material.OBSIDIAN || type == Material.CRYING_OBSIDIAN ||
               type == Material.GRINDSTONE || type == Material.LODESTONE ||
               type == Material.CONDUIT || type == Material.BEACON ||
               type == Material.ENDER_CHEST || type == Material.SPAWNER ||
               type == Material.ENCHANTING_TABLE || type == Material.RESPAWN_ANCHOR ||
               name.contains("SHULKER_BOX");
    }

    private static boolean isOre(Material type) {
        String name = type.name();
        return name.contains("_ORE") || type == Material.ANCIENT_DEBRIS ||
               type == Material.GILDED_BLACKSTONE;
    }

    private static boolean isMetal(Material type) {
        String name = type.name();
        return name.contains("IRON_BLOCK") || name.contains("GOLD_BLOCK") ||
               name.contains("DIAMOND_BLOCK") || name.contains("EMERALD_BLOCK") ||
               name.contains("NETHERITE_BLOCK") || name.contains("COPPER_BLOCK") ||
               name.contains("COPPER") || name.contains("HOPPER") ||
               name.contains("ANVIL") || name.contains("CHAIN") ||
               type == Material.IRON_BARS || type == Material.BREWING_STAND ||
               name.contains("CAULDRON") || type == Material.BELL ||
               type == Material.LANTERN || type == Material.SOUL_LANTERN ||
               type == Material.LIGHTNING_ROD ||
               name.contains("HEAVY_CORE") || name.contains("TRIAL_SPAWNER") ||
               name.contains("VAULT") || name.contains("CRAFTER");
    }

    private static boolean isRedstone(Material type) {
        String name = type.name();
        return name.contains("REDSTONE") || type == Material.OBSERVER ||
               type == Material.DISPENSER || type == Material.DROPPER ||
               type == Material.HOPPER || type == Material.PISTON ||
               type == Material.STICKY_PISTON || name.contains("RAIL");
    }

    private static boolean isWood(Material type) {
        String name = type.name();
        return name.contains("LOG") || (name.contains("WOOD") && !name.contains("SWORD")) ||
               name.contains("PLANKS") || type == Material.CRAFTING_TABLE ||
               name.contains("CHEST") || name.contains("BARREL") ||
               name.contains("BOOKSHELF") || name.contains("LECTERN") ||
               name.contains("CAMPFIRE") ||
               (name.contains("FENCE") && !name.contains("NETHER_BRICK") && !name.contains("IRON")) ||
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

    private static boolean isDirt(Material type) {
        String name = type.name();
        return name.contains("DIRT") || type == Material.GRASS_BLOCK ||
               type == Material.MYCELIUM || type == Material.PODZOL ||
               type == Material.CLAY || name.contains("FARMLAND") ||
               type == Material.ROOTED_DIRT || type == Material.COARSE_DIRT ||
               type == Material.MUD || name.contains("MUDDY");
    }

    private static boolean isSand(Material type) {
        String name = type.name();
        return name.contains("SAND") || name.contains("GRAVEL") ||
               name.contains("CONCRETE_POWDER") || type == Material.SOUL_SAND ||
               type == Material.SOUL_SOIL;
    }

    private static boolean isIce(Material type) {
        String name = type.name();
        return name.contains("ICE") && !name.contains("GLOW_LICHEN");
    }

    private static boolean isSnow(Material type) {
        return type == Material.SNOW || type == Material.SNOW_BLOCK ||
               type == Material.POWDER_SNOW;
    }

    private static boolean isPlant(Material type) {
        String name = type.name();
        return name.contains("LEAVES") || name.contains("SAPLING") ||
               name.contains("FLOWER") || (name.contains("GRASS") && !name.contains("_BLOCK")) ||
               name.contains("FERN") || name.contains("VINE") ||
               name.contains("KELP") || name.contains("SEAGRASS") ||
               type == Material.SUGAR_CANE || type == Material.BAMBOO ||
               type == Material.CACTUS || name.contains("FUNGUS") ||
               name.contains("ROOTS") || type == Material.LILY_PAD ||
               type == Material.MOSS_CARPET || type == Material.MOSS_BLOCK ||
               (name.contains("CORAL") && !name.contains("_BLOCK"));
    }

    private static boolean isCrop(Material type) {
        String name = type.name();
        return type == Material.WHEAT || type == Material.CARROTS ||
               type == Material.POTATOES || type == Material.BEETROOTS ||
               type == Material.NETHER_WART || type == Material.COCOA ||
               type == Material.SWEET_BERRY_BUSH || type == Material.PUMPKIN ||
               type == Material.MELON || name.contains("STEM");
    }

    private static boolean isWool(Material type) {
        String name = type.name();
        return name.contains("WOOL") || name.contains("CARPET");
    }

    private static boolean isLeaves(Material type) {
        return type.name().contains("LEAVES");
    }

    private static boolean isSculk(Material type) {
        String name = type.name();
        return name.contains("SCULK") || type == Material.SHROOMLIGHT ||
               type == Material.MOSS_BLOCK;
    }

    private static boolean hasAquaAffinity(Player player) {
        return EnchantHelper.hasAquaAffinity(player);
    }

    public static boolean isToolBroken(ItemStack tool) {
        if (tool == null || tool.getType() == Material.AIR) return false;
        short max = tool.getType().getMaxDurability();
        if (max <= 0) return false;
        ItemMeta meta = tool.getItemMeta();
        if (meta instanceof Damageable) {
            int dmg = ((Damageable) meta).getDamage();
            return dmg >= max;
        }
        return false;
    }

    public static double getExpectedBreakTimeMs(Player player, Block block) {
        ItemStack tool = player.getInventory().getItemInMainHand();
        String toolName = (tool != null && tool.getType() != Material.AIR && !isToolBroken(tool))
                ? tool.getType().name() : "HAND";
        int eff = (tool != null && tool.getType() != Material.AIR && !isToolBroken(tool))
                ? EnchantHelper.getEfficiencyLevel(tool) : 0;
        BlockBreakEnhancements enh = calculateBlockBreakEnhancements(player, block, toolName, eff);
        return computeExpectedBreakTimeMs(player, block, enh);
    }

    public static double computeExpectedBreakTimeMs(Player player, Block block, BlockBreakEnhancements enh) {
        if (player.getGameMode() == org.bukkit.GameMode.CREATIVE) return 50.0;
        if (enh.canInstamine) return 50.0;

        double hardness = ToolSpeed.getHardness(block.getType());
        if (hardness <= 0) return 50.0;

        double speed = 1.0;
        if (enh.hasCorrectTool) {
            switch (enh.toolMaterial) {
                case "NETHERITE": speed = 9.0; break;
                case "DIAMOND": speed = 8.0; break;
                case "GOLDEN": speed = 12.0; break;
                case "IRON": speed = 6.0; break;
                case "STONE": speed = 4.0; break;
                case "WOOD": speed = 2.0; break;
                default: speed = 1.0; break;
            }
        }

        if (enh.toolType.equals("SHEARS") && enh.hasCorrectTool) {
            String bn = block.getType().name();
            if (bn.contains("WOOL") || bn.contains("CARPET")) speed = 5.0;
            else if (block.getType() == Material.COBWEB) speed = 15.0;
            else if (bn.contains("LEAVES") || bn.contains("VINE") || bn.contains("GLOW_LICHEN")) speed = 2.0;
            else speed = 1.5;
        }

        if (enh.toolType.equals("SWORD")) {
            if (block.getType() == Material.COBWEB) speed = 15.0;
            else if (block.getType() == Material.BAMBOO) speed = 20.0;
            else speed = 1.0;
        }

        if (enh.efficiencyLevel > 0) {
            speed += (enh.efficiencyLevel * enh.efficiencyLevel) + 1.0;
        }

        if (enh.hasteLevel > 0) {
            speed *= 1.0 + (0.2 * enh.hasteLevel);
        }

        int fatigueLevel = 0;
        PotionEffectType fatigueType = PotionEffectType.getByName("MINING_FATIGUE");
        if (fatigueType == null) fatigueType = PotionEffectType.getByName("SLOW_DIGGING");
        if (fatigueType != null) {
            PotionEffect fatigue = player.getPotionEffect(fatigueType);
            if (fatigue != null) {
                fatigueLevel = fatigue.getAmplifier() + 1;
            }
        }

        boolean inLiquid = player.isInWater() || player.getLocation().getBlock().getType() == Material.LAVA;
        boolean inWater = player.isInWater();
        boolean aquaAffinity = EnchantHelper.hasAquaAffinity(player);
        boolean inAir = !player.isOnGround() && !player.isFlying() && !inLiquid;

        return computeExpectedBreakTimeMsRaw(hardness, speed, fatigueLevel, inWater, aquaAffinity, inAir, enh.hasCorrectTool);
    }

    static double computeExpectedBreakTimeMsRaw(double hardness, double speedMultiplier, int fatigueLevel,
                                                boolean inWater, boolean aquaAffinity, boolean inAir, boolean canHarvest) {
        if (hardness <= 0) return 50.0;
        double speed = speedMultiplier;
        if (fatigueLevel == 1) speed *= 0.3;
        else if (fatigueLevel == 2) speed *= 0.09;
        else if (fatigueLevel == 3) speed *= 0.0027;
        else if (fatigueLevel >= 4) speed *= 0.00081;

        if (inWater && !aquaAffinity) speed /= 5.0;
        if (inAir) speed /= 5.0;

        double divisor = canHarvest ? 30.0 : 100.0;
        double damage = speed / hardness / divisor;
        if (damage >= 1.0) return 50.0;
        double ticks = Math.ceil(1.0 / damage);
        return ticks * 50.0;
    }

    public static boolean canBreakInstantly(Player player, Block block) {
        if (player.getGameMode() == org.bukkit.GameMode.CREATIVE) return true;
        BlockBreakEnhancements enh = calculateBlockBreakEnhancements(player, block);
        if (enh.canInstamine) return true;
        return computeExpectedBreakTimeMs(player, block, enh) <= 50.0;
    }
}
