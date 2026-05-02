package NC.noChance.detection.block;

import NC.noChance.core.*;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

public class FastBreakCheck {
    private final ACConfig config;
    private final Map<UUID, PlayerData> playerDataMap;
    private final LayerFiltering filtering;
    private final BreakProfile profiler;
    private final ToolSpeed toolCalc;
    private final PacketTracker packetAnalyzer;
    private final FalsePositiveFilter falsePositiveFilter;
    private final Map<UUID, Long> lastCheckTime;
    private final Map<UUID, Integer> rapidCheckCount;
    private final Map<UUID, BreakViolationTracker> violationTrackers;

    private static final long MIN_CHECK_INTERVAL = 25;
    private static final int MAX_RAPID_CHECKS = 15;
    private static final int MIN_SAMPLES_BASIC = 2;
    private static final int MIN_SAMPLES_PATTERN = 4;
    private static final double TICK_MS = 50.0;
    private static final Map<Material, Double> BLOCK_HARDNESS = new HashMap<>();
    private static final Map<Material, Double> TOOL_MULTIPLIERS = new HashMap<>();

    private static final Set<String> INSTANT_BREAK_BLOCKS = new HashSet<>();

    static {
        initializeBlockHardness();
        initializeToolMultipliers();
        initializeInstantBreakBlocks();
    }

    public FastBreakCheck(ACConfig config, Map<UUID, PlayerData> playerDataMap, LayerFiltering filtering) {
        this.config = config;
        this.playerDataMap = playerDataMap;
        this.filtering = filtering;
        this.profiler = new BreakProfile();
        this.toolCalc = new ToolSpeed();
        this.packetAnalyzer = new PacketTracker();
        this.falsePositiveFilter = new FalsePositiveFilter();
        this.lastCheckTime = new ConcurrentHashMap<>();
        this.rapidCheckCount = new ConcurrentHashMap<>();
        this.violationTrackers = new ConcurrentHashMap<>();
    }

    public void onStartDigging(Player player, Block block) {
        PlayerData data = playerDataMap.get(player.getUniqueId());
        if (data == null) {
            playerDataMap.put(player.getUniqueId(), new PlayerData(player.getUniqueId()));
            data = playerDataMap.get(player.getUniqueId());
        }

        long now = System.currentTimeMillis();
        data.setBlockBreakStartTime(now);
        data.setLastBlockBreak(block.getLocation());

        ItemStack tool = player.getInventory().getItemInMainHand();
        String toolName = (tool != null && tool.getType() != Material.AIR) ? tool.getType().name() : "HAND";
        int efficiency = (tool != null && tool.getType() != Material.AIR) ? EnchantHelper.getEfficiencyLevel(tool) : 0;
        data.setBlockBreakInitialTool(toolName);
        data.setBlockBreakInitialEfficiency(efficiency);

        packetAnalyzer.recordPacket(player.getUniqueId(), PacketTracker.PacketAction.START_DIG, block.getLocation());
    }

    public CheckResult check(Player player, Block block) {
        if (!config.isCheckEnabled("fastbreak")) {
            return CheckResult.passed();
        }

        if (isInstantBreakBlock(block.getType())) {
            return CheckResult.passed();
        }

        UUID playerId = player.getUniqueId();
        long now = System.currentTimeMillis();

        Long lastCheck = lastCheckTime.get(playerId);
        if (lastCheck != null) {
            long timeSinceLastCheck = now - lastCheck;

            if (timeSinceLastCheck < MIN_CHECK_INTERVAL) {
                int rapid = rapidCheckCount.getOrDefault(playerId, 0) + 1;
                rapidCheckCount.put(playerId, rapid);

                if (rapid > MAX_RAPID_CHECKS) {
                    return CheckResult.passed();
                }
            } else {
                rapidCheckCount.put(playerId, 0);
            }
        }

        lastCheckTime.put(playerId, now);

        PlayerData data = playerDataMap.get(playerId);
        if (data == null) return CheckResult.passed();

        if (data.isInGracePeriod(config.getGracePeriod())) {
            falsePositiveFilter.recordLegitAction(player, ViolationType.FASTBREAK);
            return CheckResult.passed();
        }

        long breakStartTime = data.getBlockBreakStartTime();

        if (breakStartTime == 0 || breakStartTime > now) {
            data.setBlockBreakStartTime(now);
            falsePositiveFilter.recordLegitAction(player, ViolationType.FASTBREAK);
            return CheckResult.passed();
        }

        long actualTime = now - breakStartTime;

        if (actualTime > 5000) {
            data.setBlockBreakStartTime(now);
            return CheckResult.passed();
        }

        falsePositiveFilter.recordBreakTiming(player, actualTime);
        long lastBreakTime = data.getLastBlockBreakTime();
        long timeSinceLastBreak = lastBreakTime > 0 ? now - lastBreakTime : 1000;

        data.setLastBlockBreakTime(now);

        packetAnalyzer.recordPacket(player.getUniqueId(), PacketTracker.PacketAction.FINISH_DIG, block.getLocation());

        PacketTracker.SequenceViolation seqViolation = packetAnalyzer.checkSequence(player.getUniqueId(), player);
        if (seqViolation.violated) {
            CheckResult prelimResult = CheckResult.failed(
                    ViolationType.FASTBREAK,
                    seqViolation.severity,
                    seqViolation.reason
            );
            if (!filtering.passesLayer2HeuristicFiltering(player, ViolationType.FASTBREAK, prelimResult)) {
                return CheckResult.passed();
            }
            return prelimResult;
        }

        String initialTool = data.getBlockBreakInitialTool();
        if (initialTool == null) {
            ItemStack hand = player.getInventory().getItemInMainHand();
            initialTool = hand != null && hand.getType() != Material.AIR ? hand.getType().name() : "HAND";
        }

        ItemStack currentTool = player.getInventory().getItemInMainHand();
        String currentToolName = (currentTool != null && currentTool.getType() != Material.AIR) ?
                                  currentTool.getType().name() : "HAND";
        int currentEfficiency = (currentTool != null && currentTool.getType() != Material.AIR) ?
                                EnchantHelper.getEfficiencyLevel(currentTool) : 0;

        boolean toolWasSwitched = !initialTool.equals(currentToolName);
        boolean efficiencyChanged = data.getBlockBreakInitialEfficiency() != currentEfficiency;

        int initialEfficiency = data.getBlockBreakInitialEfficiency();
        EnhancementTracker.BlockBreakEnhancements initialEnhancements = EnhancementTracker.calculateBlockBreakEnhancements(player, block, initialTool, initialEfficiency);
        EnhancementTracker.BlockBreakEnhancements currentEnhancements = EnhancementTracker.calculateBlockBreakEnhancements(player, block, currentToolName, currentEfficiency);

        EnhancementTracker.BlockBreakEnhancements enhancements = initialEnhancements;
        if (toolWasSwitched || efficiencyChanged) {
            if (currentEnhancements.getBreakSpeedMultiplier() > initialEnhancements.getBreakSpeedMultiplier()) {
                enhancements = currentEnhancements;
            }
        }

        if (enhancements.canInstamine && actualTime <= TICK_MS * 2) {
            data.resetConsecutiveFastBreaks();
            falsePositiveFilter.recordLegitAction(player, ViolationType.FASTBREAK);
            return CheckResult.passed();
        }

        if (isInstantBreakBlock(block.getType()) && actualTime >= 0) {
            data.resetConsecutiveFastBreaks();
            falsePositiveFilter.recordLegitAction(player, ViolationType.FASTBREAK);
            return CheckResult.passed();
        }

        if (!enhancements.canInstamine) {
            double computedExpected = calculateExactBreakTime(player, block, enhancements);
            if (computedExpected <= TICK_MS && actualTime >= 0) {
                data.resetConsecutiveFastBreaks();
                falsePositiveFilter.recordLegitAction(player, ViolationType.FASTBREAK);
                return CheckResult.passed();
            }
        }

        double blockHardness = getBlockHardness(block.getType());
        boolean isShovelBlock = (initialEnhancements.toolType.equals("SHOVEL") || currentEnhancements.toolType.equals("SHOVEL"))
                                && blockHardness <= 0.6;
        boolean isPickaxeBlock = (initialEnhancements.toolType.equals("PICKAXE") || currentEnhancements.toolType.equals("PICKAXE"))
                                && (blockHardness >= 1.0 || block.getType().name().contains("STONE") || block.getType().name().contains("ORE"));

        if (isShovelBlock && !toolWasSwitched && !efficiencyChanged) {
            int minShovelTime = 40;
            if (enhancements.hasteLevel >= 2) {
                if (enhancements.efficiencyLevel >= 5) {
                    minShovelTime = 35;
                } else if (enhancements.efficiencyLevel >= 4) {
                    minShovelTime = 37;
                } else if (enhancements.efficiencyLevel >= 3) {
                    minShovelTime = 39;
                } else if (enhancements.efficiencyLevel >= 2) {
                    minShovelTime = 41;
                } else if (enhancements.efficiencyLevel >= 1) {
                    minShovelTime = 43;
                } else {
                    minShovelTime = 45;
                }
            } else if (enhancements.hasteLevel == 1) {
                if (enhancements.efficiencyLevel >= 5) {
                    minShovelTime = 37;
                } else if (enhancements.efficiencyLevel >= 4) {
                    minShovelTime = 39;
                } else if (enhancements.efficiencyLevel >= 3) {
                    minShovelTime = 41;
                } else if (enhancements.efficiencyLevel >= 2) {
                    minShovelTime = 43;
                } else if (enhancements.efficiencyLevel >= 1) {
                    minShovelTime = 45;
                } else {
                    minShovelTime = 50;
                }
            } else {
                if (enhancements.efficiencyLevel >= 5) {
                    minShovelTime = 40;
                } else if (enhancements.efficiencyLevel >= 4) {
                    minShovelTime = 43;
                } else if (enhancements.efficiencyLevel >= 3) {
                    minShovelTime = 45;
                } else if (enhancements.efficiencyLevel >= 2) {
                    minShovelTime = 47;
                } else if (enhancements.efficiencyLevel >= 1) {
                    minShovelTime = 50;
                } else {
                    minShovelTime = 55;
                }
            }

            if (actualTime < minShovelTime) {
                CheckResult prelimResult = CheckResult.failed(
                    ViolationType.FASTBREAK,
                    0.97,
                    String.format("Shovel too fast (%.0fms, Eff%d, Haste%d, min %dms, hardness:%.1f)",
                        (double)actualTime, enhancements.efficiencyLevel, enhancements.hasteLevel, minShovelTime, blockHardness)
                );
                if (!filtering.passesLayer2HeuristicFiltering(player, ViolationType.FASTBREAK, prelimResult)) {
                    return CheckResult.passed();
                }
                return prelimResult;
            }
        }

        boolean hasProperTool = enhancements.hasCorrectTool;
        boolean hasHighEfficiency = enhancements.efficiencyLevel >= 4;
        boolean hasTopTierTool = enhancements.toolMaterial.equals("GOLDEN") ||
                                 enhancements.toolMaterial.equals("NETHERITE") ||
                                 enhancements.toolMaterial.equals("DIAMOND");
        boolean isLeafBlock = block.getType().name().contains("LEAVES");
        boolean isHoeOrShears = enhancements.toolType.equals("HOE") || enhancements.toolType.equals("SHEARS");

        if (isLeafBlock && !hasProperTool && actualTime < 80) {
            CheckResult prelimResult = CheckResult.failed(
                ViolationType.FASTBREAK,
                0.91,
                String.format("Leaf break without proper tool too fast (%dms, tool: %s)", actualTime, enhancements.toolType)
            );
            if (!filtering.passesLayer2HeuristicFiltering(player, ViolationType.FASTBREAK, prelimResult)) {
                return CheckResult.passed();
            }
            return prelimResult;
        }

        if (isLeafBlock && hasProperTool && isHoeOrShears) {
            int leafMinTime = 25;
            if (enhancements.efficiencyLevel >= 5) {
                leafMinTime = 18;
            } else if (enhancements.efficiencyLevel >= 4) {
                leafMinTime = 20;
            } else if (enhancements.efficiencyLevel >= 3) {
                leafMinTime = 22;
            }

            if (enhancements.hasteLevel >= 2) {
                leafMinTime = Math.max(10, leafMinTime - 8);
            } else if (enhancements.hasteLevel >= 1) {
                leafMinTime = Math.max(12, leafMinTime - 5);
            }

            int leafIntervalMin = 40;
            if (enhancements.hasteLevel >= 2 && enhancements.efficiencyLevel >= 4) {
                leafIntervalMin = 25;
            } else if (enhancements.hasteLevel >= 1 || enhancements.efficiencyLevel >= 5) {
                leafIntervalMin = 30;
            }

            if (actualTime < leafMinTime && timeSinceLastBreak < leafIntervalMin) {
                CheckResult prelimResult = CheckResult.failed(
                    ViolationType.FASTBREAK,
                    0.89,
                    String.format("Rapid leaf breaking (%dms break, %dms interval, Eff%d)", actualTime, timeSinceLastBreak, enhancements.efficiencyLevel)
                );
                if (!filtering.passesLayer2HeuristicFiltering(player, ViolationType.FASTBREAK, prelimResult)) {
                    return CheckResult.passed();
                }
                return prelimResult;
            }
        }


        int instantBreakThreshold = 20;
        if (hasProperTool && enhancements.efficiencyLevel >= 5 && hasTopTierTool) {
            if (blockHardness <= 0.4) {
                instantBreakThreshold = 0;
            } else if (blockHardness <= 1.0) {
                instantBreakThreshold = 3;
            } else if (blockHardness <= 1.5) {
                instantBreakThreshold = 5;
            } else if (blockHardness <= 2.0) {
                instantBreakThreshold = 8;
            }
        } else if (hasProperTool && enhancements.efficiencyLevel >= 4) {
            if (blockHardness <= 0.4) {
                instantBreakThreshold = 3;
            } else if (blockHardness <= 1.5) {
                instantBreakThreshold = 10;
            }
        } else if (hasProperTool && enhancements.efficiencyLevel >= 3) {
            if (blockHardness <= 0.4) {
                instantBreakThreshold = 8;
            }
        }

        if (enhancements.hasteLevel >= 2) {
            instantBreakThreshold = Math.max(0, instantBreakThreshold - 8);
        } else if (enhancements.hasteLevel >= 1) {
            instantBreakThreshold = Math.max(0, instantBreakThreshold - 4);
        }

        if (enhancements.efficiencyLevel >= 4 && !hasProperTool && blockHardness <= 0.6) {
            instantBreakThreshold = 0;
        }

        if (instantBreakThreshold > 0 && actualTime < instantBreakThreshold && blockHardness > 0.1) {
            if (enhancements.toolMaterial.equals("GOLDEN") && actualTime < 15 && blockHardness <= 1.5) {
                falsePositiveFilter.recordLegitAction(player, ViolationType.FASTBREAK);
                return CheckResult.passed();
            }

            CheckResult prelimResult = CheckResult.failed(
                    ViolationType.FASTBREAK,
                    0.95,
                    String.format("Instant break without instamine capability: %dms (hardness: %.1f, threshold: %dms)", actualTime, blockHardness, instantBreakThreshold)
            );
            if (!filtering.passesLayer2HeuristicFiltering(player, ViolationType.FASTBREAK, prelimResult)) {
                return CheckResult.passed();
            }
            return prelimResult;
        }

        double rapidExpected = calculateExactBreakTime(player, block, enhancements);
        if (timeSinceLastBreak < 45 && actualTime < 40 && blockHardness > 0.5 && rapidExpected > 100) {
            if (enhancements.toolMaterial.equals("GOLDEN") && enhancements.efficiencyLevel >= 4) {
                falsePositiveFilter.recordLegitAction(player, ViolationType.FASTBREAK);
                return CheckResult.passed();
            }

            if (enhancements.efficiencyLevel >= 4 && enhancements.hasteLevel >= 1) {
                falsePositiveFilter.recordLegitAction(player, ViolationType.FASTBREAK);
                return CheckResult.passed();
            }

            CheckResult prelimResult = CheckResult.failed(
                    ViolationType.FASTBREAK,
                    0.94,
                    String.format("Rapid succession breaks: %dms interval, %dms break (hardness: %.1f)", timeSinceLastBreak, actualTime, blockHardness)
            );
            if (!filtering.passesLayer2HeuristicFiltering(player, ViolationType.FASTBREAK, prelimResult)) {
                return CheckResult.passed();
            }
            return prelimResult;
        }

        boolean isHandBreakingEarly = enhancements.toolMaterial.equals("HAND") && !enhancements.toolType.equals("SHEARS");
        if (isHandBreakingEarly && blockHardness >= 0.2) {
            int handMinTime = 100;
            if (blockHardness <= 0.3) {
                handMinTime = 60;
            } else if (blockHardness <= 0.5) {
                handMinTime = 90;
            } else if (blockHardness <= 0.8) {
                handMinTime = 140;
            } else {
                handMinTime = 200;
            }

            if (enhancements.hasteLevel >= 2) {
                handMinTime = (int)(handMinTime * 0.5);
            } else if (enhancements.hasteLevel >= 1) {
                handMinTime = (int)(handMinTime * 0.7);
            }

            if (timeSinceLastBreak < 50 && actualTime < handMinTime) {
                CheckResult prelimResult = CheckResult.failed(
                        ViolationType.FASTBREAK,
                        0.92,
                        String.format("Hand nuker pattern: %dms interval, %dms break (hardness: %.1f, min: %dms)",
                            timeSinceLastBreak, actualTime, blockHardness, handMinTime)
                );
                if (!filtering.passesLayer2HeuristicFiltering(player, ViolationType.FASTBREAK, prelimResult)) {
                    return CheckResult.passed();
                }
                return prelimResult;
            }

            int impossibleHandMin = 50;
            if (enhancements.hasteLevel >= 2) {
                impossibleHandMin = 25;
            } else if (enhancements.hasteLevel >= 1) {
                impossibleHandMin = 35;
            }
            if (actualTime < impossibleHandMin && blockHardness >= 0.3) {
                CheckResult prelimResult = CheckResult.failed(
                        ViolationType.FASTBREAK,
                        0.96,
                        String.format("Impossible hand break: %dms for hardness %.1f", actualTime, blockHardness)
                );
                if (!filtering.passesLayer2HeuristicFiltering(player, ViolationType.FASTBREAK, prelimResult)) {
                    return CheckResult.passed();
                }
                return prelimResult;
            }
        }

        int consecutiveFast = data.getConsecutiveFastBreaks();
        double trustScore = falsePositiveFilter.getPlayerTrustScore(playerId);
        int consecutiveThreshold = trustScore > 0.7 ? 15 : (trustScore > 0.5 ? 12 : 9);

        if (isHandBreakingEarly) {
            consecutiveThreshold = trustScore > 0.7 ? 10 : (trustScore > 0.5 ? 8 : 6);
        }

        if (enhancements.efficiencyLevel >= 4 && enhancements.hasteLevel >= 1) {
            consecutiveThreshold += 8;
        } else if (enhancements.efficiencyLevel >= 3 || enhancements.hasteLevel >= 1) {
            consecutiveThreshold += 5;
        }

        if (blockHardness > 10.0) {
            consecutiveThreshold += 2;
        }

        if (consecutiveFast >= consecutiveThreshold) {
            CheckResult prelimResult = CheckResult.failed(
                    ViolationType.FASTBREAK,
                    Math.min(1.0, 0.7 + (consecutiveFast * 0.05)),
                    String.format("Pattern: %d consecutive fast breaks%s (trust:%.2f)", consecutiveFast,
                        isHandBreakingEarly ? " (hand)" : "", trustScore)
            );
            if (!filtering.passesLayer2HeuristicFiltering(player, ViolationType.FASTBREAK, prelimResult)) {
                data.resetConsecutiveFastBreaks();
                return CheckResult.passed();
            }
            return prelimResult;
        }

        Deque<Long> intervals = data.getBlockBreakIntervals();
        if (intervals.size() >= 6 && timeSinceLastBreak < 200) {
            int fastThreshold = isHandBreakingEarly ? 150 : 80;
            int requiredFastCount = isHandBreakingEarly ? 4 : 5;

            if (!isHandBreakingEarly) {
                if (enhancements.efficiencyLevel >= 5 && enhancements.hasteLevel >= 2) {
                    fastThreshold = 40;
                    requiredFastCount = 8;
                } else if (enhancements.efficiencyLevel >= 4 && enhancements.hasteLevel >= 1) {
                    fastThreshold = 50;
                    requiredFastCount = 7;
                } else if (enhancements.efficiencyLevel >= 3 || enhancements.hasteLevel >= 1) {
                    fastThreshold = 60;
                    requiredFastCount = 6;
                }
            }

            final int ft = fastThreshold;
            long veryFastCount = intervals.stream().filter(i -> i < ft).count();
            if (veryFastCount >= requiredFastCount) {
                CheckResult prelimResult = CheckResult.failed(
                        ViolationType.FASTBREAK,
                        isHandBreakingEarly ? 0.91 : 0.86,
                        String.format("Pattern: %d breaks with <%dms intervals%s", veryFastCount, fastThreshold,
                            isHandBreakingEarly ? " (hand)" : "")
                );
                if (!filtering.passesLayer2HeuristicFiltering(player, ViolationType.FASTBREAK, prelimResult)) {
                    return CheckResult.passed();
                }
                return prelimResult;
            }

            List<Long> recentIntervals = new ArrayList<>(intervals);
            if (recentIntervals.size() >= 8) {
                double mean = recentIntervals.stream().mapToLong(Long::longValue).average().orElse(0.0);
                double variance = recentIntervals.stream()
                        .mapToDouble(i -> Math.pow(i - mean, 2))
                        .average()
                        .orElse(0.0);

                double meanThreshold = isHandBreakingEarly ? 200 : 120;
                double varThreshold = isHandBreakingEarly ? 50 : 25;

                if (!isHandBreakingEarly && enhancements.efficiencyLevel >= 4) {
                    meanThreshold = 80;
                    varThreshold = 15;
                    if (enhancements.hasteLevel >= 1) {
                        meanThreshold = 60;
                        varThreshold = 10;
                    }
                }

                if (variance < varThreshold && mean < meanThreshold) {
                    CheckResult prelimResult = CheckResult.failed(
                            ViolationType.FASTBREAK,
                            isHandBreakingEarly ? 0.88 : 0.82,
                            String.format("Pattern: Consistent timing var=%.1f mean=%.1fms%s", variance, mean,
                                isHandBreakingEarly ? " (hand)" : "")
                    );
                    if (!filtering.passesLayer2HeuristicFiltering(player, ViolationType.FASTBREAK, prelimResult)) {
                        return CheckResult.passed();
                    }
                    return prelimResult;
                }
            }
        }

        if (blockHardness == 0 || blockHardness < 0.05 || isInstantBreakBlock(block.getType())) {
            return CheckResult.passed();
        }

        if (player.getGameMode() == org.bukkit.GameMode.CREATIVE) {
            return CheckResult.passed();
        }

        if (player.getGameMode() == org.bukkit.GameMode.SPECTATOR) {
            return CheckResult.passed();
        }

        EnvironmentHelper.EnvironmentalFactors envFactors = EnvironmentHelper.calculate(player);

        double speedMultiplier = 1.0;

        if (enhancements.hasCorrectTool) {
            ItemStack heldTool = player.getInventory().getItemInMainHand();
            Material toolMat = (heldTool != null) ? heldTool.getType() : Material.AIR;
            Double lookupSpeed = TOOL_MULTIPLIERS.get(toolMat);

            switch (enhancements.toolMaterial) {
                case "NETHERITE": speedMultiplier = 9.0; break;
                case "DIAMOND": speedMultiplier = 8.0; break;
                case "GOLDEN": speedMultiplier = 12.0; break;
                case "IRON": speedMultiplier = 6.0; break;
                case "STONE": speedMultiplier = 4.0; break;
                case "WOOD": speedMultiplier = 2.0; break;
                default:
                    if (lookupSpeed != null) {
                        speedMultiplier = lookupSpeed;
                    }
                    break;
            }

            if (lookupSpeed != null && lookupSpeed > speedMultiplier) {
                speedMultiplier = lookupSpeed;
            }
        }

        if (enhancements.toolType.equals("SHEARS") && enhancements.hasCorrectTool) {
            String bn = block.getType().name();
            if (bn.contains("WOOL") || bn.contains("CARPET")) {
                speedMultiplier = 5.0;
            } else if (block.getType() == Material.COBWEB) {
                speedMultiplier = 15.0;
            } else if (bn.contains("LEAVES") || bn.contains("VINE") || bn.contains("GLOW_LICHEN")) {
                speedMultiplier = 2.0;
            } else {
                speedMultiplier = 1.5;
            }
        }

        if (enhancements.toolType.equals("SWORD")) {
            if (block.getType() == Material.COBWEB) {
                speedMultiplier = 15.0;
            } else if (block.getType() == Material.BAMBOO) {
                speedMultiplier = 20.0;
            } else {
                speedMultiplier = 1.0;
            }
        }

        if (enhancements.efficiencyLevel > 0) {
            speedMultiplier += (enhancements.efficiencyLevel * enhancements.efficiencyLevel) + 1.0;
        }

        int hasteLevel = enhancements.hasteLevel;

        if (hasteLevel > 0) {
            speedMultiplier *= (0.2 * hasteLevel) + 1.0;
        }

        PotionEffectType fatigueType = PotionEffectType.getByName("MINING_FATIGUE");
        if (fatigueType == null) fatigueType = PotionEffectType.getByName("SLOW_DIGGING");
        if (fatigueType != null) {
            PotionEffect fatigue = player.getPotionEffect(fatigueType);
            if (fatigue != null) {
                int level = fatigue.getAmplifier() + 1;
                if (level == 1) {
                    speedMultiplier *= 0.3;
                } else if (level == 2) {
                    speedMultiplier *= 0.09;
                } else if (level == 3) {
                    speedMultiplier *= 0.0027;
                } else {
                    speedMultiplier *= 0.00081;
                }
            }
        }

        Block standingBlock = player.getLocation().getBlock();
        boolean inLiquidNow = player.isInWater() || (standingBlock != null && standingBlock.getType() == Material.LAVA);
        if (WaterHelper.isInWater(player) && !EnchantHelper.hasAquaAffinity(player)) {
            speedMultiplier /= 5.0;
        }

        if (!player.isOnGround() && !player.isFlying() && !inLiquidNow) {
            speedMultiplier /= 5.0;
        }

        boolean canHarvestBlock = canToolHarvest(block.getType(), enhancements);

        double damage = speedMultiplier / blockHardness;

        if (canHarvestBlock) {
            damage /= 30.0;
        } else {
            damage /= 100.0;
        }

        double ticksToBreak = damage >= 1.0 ? 1.0 : Math.ceil(1.0 / damage);
        double expectedTime = ticksToBreak * 50.0;

        int ping = filtering.getPing(player);
        double tpsMultiplier = getTPSMultiplier();

        double baseTolerance = 50 * tpsMultiplier;
        double pingComp = Math.min(ping * 0.8, 160);
        baseTolerance += pingComp;

        if (enhancements.efficiencyLevel >= 5) {
            baseTolerance += 30;
        } else if (enhancements.efficiencyLevel >= 4) {
            baseTolerance += 25;
        } else if (enhancements.efficiencyLevel >= 3) {
            baseTolerance += 20;
        } else if (enhancements.efficiencyLevel >= 2) {
            baseTolerance += 15;
        } else if (enhancements.efficiencyLevel >= 1) {
            baseTolerance += 10;
        }

        if (enhancements.hasteLevel >= 2) {
            baseTolerance += 50;
        } else if (enhancements.hasteLevel >= 1) {
            baseTolerance += 30;
        }

        if (enhancements.toolMaterial.equals("GOLDEN")) {
            baseTolerance += 40;
        } else if (enhancements.toolMaterial.equals("NETHERITE") || enhancements.toolMaterial.equals("DIAMOND")) {
            baseTolerance += 20;
        } else if (enhancements.toolMaterial.equals("IRON")) {
            baseTolerance += 15;
        } else if (enhancements.toolMaterial.equals("STONE")) {
            baseTolerance += 10;
        }

        if (WaterHelper.isInWater(player)) {
            if (EnchantHelper.hasAquaAffinity(player)) {
                baseTolerance += 25;
            } else {
                baseTolerance += 50;
            }
        }

        if (envFactors.hasStatusEffects) {
            baseTolerance += 20;
        }

        if (envFactors.onSlipperyBlock || envFactors.inDangerZone) {
            baseTolerance += 15;
        }

        if (blockHardness < 1.0) {
            baseTolerance += 10;
        }

        double percentageTolerance = expectedTime * 0.25;

        if (toolWasSwitched || efficiencyChanged) {
            baseTolerance *= 1.3;
            percentageTolerance *= 1.3;

            boolean shovelToPickaxe = initialTool.contains("SHOVEL") && currentToolName.contains("PICKAXE");
            boolean pickaxeToShovel = initialTool.contains("PICKAXE") && currentToolName.contains("SHOVEL");

            if (shovelToPickaxe || pickaxeToShovel) {
                baseTolerance += 50;
                percentageTolerance *= 1.8;
            } else if (isShovelBlock && currentToolName.contains("PICKAXE")) {
                baseTolerance += 80;
            } else if (!isShovelBlock && currentToolName.contains("SHOVEL")) {
                baseTolerance += 80;
            }
        }
        boolean isInstantMine = expectedTime <= 100;
        boolean isHandBreaking = enhancements.toolMaterial.equals("HAND") && !enhancements.toolType.equals("SHEARS");

        if (isShovelBlock && !toolWasSwitched) {
            int shovelHasteCap = 0;
            if (enhancements.hasteLevel >= 2) shovelHasteCap = 30;
            else if (enhancements.hasteLevel >= 1) shovelHasteCap = 15;
            if (isInstantMine) {
                if (enhancements.efficiencyLevel >= 3) {
                    baseTolerance = Math.min(baseTolerance, 55 + shovelHasteCap) + pingComp;
                    percentageTolerance = expectedTime * 0.25;
                } else if (enhancements.efficiencyLevel >= 1) {
                    baseTolerance = Math.min(baseTolerance, 60 + shovelHasteCap) + pingComp;
                    percentageTolerance = expectedTime * 0.28;
                } else {
                    baseTolerance = Math.min(baseTolerance, 70 + shovelHasteCap) + pingComp;
                    percentageTolerance = expectedTime * 0.32;
                }
            } else {
                baseTolerance = Math.min(baseTolerance, 85 + shovelHasteCap) + pingComp;
                percentageTolerance = expectedTime * 0.25;
            }
        } else if (isHandBreaking && blockHardness >= 0.1) {
            int handHasteCap = 0;
            if (enhancements.hasteLevel >= 2) handHasteCap = 40;
            else if (enhancements.hasteLevel >= 1) handHasteCap = 20;
            if (blockHardness <= 0.3) {
                baseTolerance = Math.min(baseTolerance, 90 + handHasteCap) + pingComp;
                percentageTolerance = expectedTime * 0.35;
            } else if (blockHardness <= 0.8) {
                if (isInstantMine) {
                    baseTolerance = Math.min(baseTolerance, 65 + handHasteCap) + pingComp;
                    percentageTolerance = expectedTime * 0.30;
                } else {
                    baseTolerance = Math.min(baseTolerance, 100 + handHasteCap) + pingComp;
                    percentageTolerance = expectedTime * 0.32;
                }
            } else {
                baseTolerance = Math.min(baseTolerance, 110 + handHasteCap) + pingComp;
                percentageTolerance = expectedTime * 0.30;
            }
        } else if (blockHardness <= 0.6 && enhancements.hasCorrectTool && !toolWasSwitched) {
            int hasteCap = 0;
            if (enhancements.hasteLevel >= 2) hasteCap = 30;
            else if (enhancements.hasteLevel >= 1) hasteCap = 15;
            percentageTolerance = expectedTime * 0.28;
            if (isInstantMine) {
                baseTolerance = Math.min(baseTolerance, 75 + hasteCap) + pingComp;
                percentageTolerance = expectedTime * 0.25;
            } else {
                baseTolerance = Math.min(baseTolerance, 95 + hasteCap) + pingComp;
            }
        }

        baseTolerance = VersionAdapter.adjustBlockBreakTolerance(player, baseTolerance);

        if (ViaHelper.isPre1_13(player)) {
            baseTolerance *= 1.15;
        }

        int unbreakingLevel = EnchantHelper.getUnbreakingLevel(currentTool);
        if (unbreakingLevel > 0) {
            baseTolerance += unbreakingLevel * 5;
        }

        ToolSpeed.BreakSpeedData crossValidation = ToolSpeed.calculate(player, block);
        if (crossValidation.canInstamine && actualTime >= 0 && !enhancements.canInstamine) {
            baseTolerance += 80;
        }

        if (profiler.isConsistentlyFast(playerId)) {
            baseTolerance += 40;
        }

        double handInstantPct = config.getFastBreakHandInstantPercentage();
        if (isHandBreaking && isInstantMine) {
            percentageTolerance = Math.max(expectedTime * handInstantPct, 25);
        }

        double tolerance = Math.max(baseTolerance, percentageTolerance);

        trustScore = falsePositiveFilter.getPlayerTrustScore(playerId);

        int trustBonus = 0;
        if ((isShovelBlock || isHandBreaking) && isInstantMine) {
            if (trustScore > 0.8) {
                trustBonus = 5;
            } else if (trustScore > 0.65) {
                trustBonus = 3;
            } else if (trustScore > 0.5) {
                trustBonus = 1;
            }
        } else {
            if (trustScore > 0.8) {
                trustBonus = isInstantMine ? 7 : 15;
            } else if (trustScore > 0.65) {
                trustBonus = isInstantMine ? 5 : 10;
            } else if (trustScore > 0.5) {
                trustBonus = isInstantMine ? 2 : 5;
            }
        }
        tolerance += trustBonus;

        if (enhancements.hasteLevel >= 2 && enhancements.efficiencyLevel >= 5) {
            tolerance += (isShovelBlock && isInstantMine) ? 20 : (isInstantMine ? 35 : 55);
        } else if (enhancements.hasteLevel >= 2 && enhancements.efficiencyLevel >= 3) {
            tolerance += (isShovelBlock && isInstantMine) ? 15 : (isInstantMine ? 25 : 40);
        } else if (enhancements.hasteLevel >= 1 && enhancements.efficiencyLevel >= 4) {
            tolerance += (isShovelBlock && isInstantMine) ? 12 : (isInstantMine ? 20 : 35);
        } else if (enhancements.hasteLevel >= 1 && enhancements.efficiencyLevel >= 2) {
            tolerance += isInstantMine ? 15 : 25;
        }

        if (enhancements.efficiencyLevel >= 3 && enhancements.hasCorrectTool && expectedTime > 150) {
            double minTimeRatio = 0.25;
            if (isInstantMine) {
                minTimeRatio = 0.15;
            }
            if (enhancements.hasteLevel >= 2) {
                minTimeRatio *= 0.5;
            } else if (enhancements.hasteLevel >= 1) {
                minTimeRatio *= 0.7;
            }
            double hardMinTime = expectedTime * minTimeRatio;
            if (actualTime < hardMinTime) {
                CheckResult prelimResult = CheckResult.failed(
                    ViolationType.FASTBREAK,
                    0.94,
                    String.format("Too fast for Eff%d instant-mine (%.0fms vs min %.0fms for %.0fms expected, hardness: %.1f)",
                        enhancements.efficiencyLevel, (double)actualTime, hardMinTime, expectedTime, blockHardness)
                );
                if (!filtering.passesLayer2HeuristicFiltering(player, ViolationType.FASTBREAK, prelimResult)) {
                    return CheckResult.passed();
                }
                return prelimResult;
            }
        }

        double minTimeFloor = isInstantMine ? 0 : 30;
        double minTime = Math.max(minTimeFloor, expectedTime - tolerance);

        boolean suspicious = actualTime < minTime;

        BreakProfile.Profile profile = profiler.getProfile(player.getUniqueId());
        profiler.recordBreak(player, block, actualTime, suspicious);

        trustScore = falsePositiveFilter.getPlayerTrustScore(playerId);

        if (trustScore > 0.95 && profile.getTotalBreaks() > 200) {
            double extraTolerance = 30;
            if (actualTime >= minTime - extraTolerance) {
                falsePositiveFilter.recordLegitAction(player, ViolationType.FASTBREAK);
                return CheckResult.passed();
            }
        }

        if (profile.hasBaseline(block.getType())) {
            double baseline = profile.getBaselineTime(block.getType());

            if (baseline >= expectedTime * 0.7 && baseline > 200) {
                double deviationThreshold = trustScore > 0.7 ? 0.25 : 0.30;

                if (enhancements.efficiencyLevel >= 3) {
                    deviationThreshold = trustScore > 0.7 ? 0.20 : 0.25;
                }

                if (actualTime < baseline * deviationThreshold) {
                    double percentFaster = (1.0 - actualTime/baseline) * 100;
                    double severity = enhancements.efficiencyLevel >= 3 ? 0.91 : 0.88;

                    CheckResult prelimResult = CheckResult.failed(
                            ViolationType.FASTBREAK,
                            severity,
                            String.format("Breaking %.0f%% faster than baseline (%.0fms vs %.0fms, Eff:%d, trust:%.2f)",
                                percentFaster, (double)actualTime, (double)baseline, enhancements.efficiencyLevel, trustScore)
                    );
                    if (!filtering.passesLayer2HeuristicFiltering(player, ViolationType.FASTBREAK, prelimResult)) {
                        return CheckResult.passed();
                    }
                    return prelimResult;
                }
            }
        }

        double suspiciousRatioThreshold = trustScore > 0.7 ? 0.85 : (trustScore > 0.5 ? 0.78 : 0.72);

        if (enhancements.efficiencyLevel >= 4) {
            suspiciousRatioThreshold += 0.05;
        }
        if (enhancements.hasteLevel >= 2) {
            suspiciousRatioThreshold += 0.05;
        } else if (enhancements.hasteLevel >= 1) {
            suspiciousRatioThreshold += 0.03;
        }

        if (expectedTime >= 100 && !WaterHelper.isInWater(player)) {
            double softRatio = 0.85;
            if (enhancements.efficiencyLevel >= 5) softRatio = 0.65;
            else if (enhancements.efficiencyLevel >= 4) softRatio = 0.72;
            else if (enhancements.hasteLevel >= 2) softRatio = 0.70;
            else if (enhancements.hasteLevel >= 1) softRatio = 0.78;
            int p = filtering.getPing(player);
            if (p > 200) softRatio += 0.07;
            else if (p > 100) softRatio += 0.04;

            long softMin = (long)(expectedTime * softRatio);
            List<BreakProfile.BreakEvent> recent = profile.getRecentBreaks(12);
            int matchedFast = 0;
            int matchedTotal = 0;
            for (BreakProfile.BreakEvent ev : recent) {
                if (ev.material != block.getType()) continue;
                matchedTotal++;
                if (ev.breakTimeMs < softMin) matchedFast++;
            }
            int needed = trustScore > 0.7 ? 9 : (trustScore > 0.5 ? 8 : 7);
            if (matchedTotal >= needed && (double) matchedFast / matchedTotal >= 0.85
                    && actualTime < softMin) {
                double severity = enhancements.efficiencyLevel >= 4 ? 0.84 : 0.80;
                CheckResult prelimResult = CheckResult.failed(
                        ViolationType.FASTBREAK,
                        severity,
                        String.format("Sustained fast pattern: %d/%d under %dms (avg ratio %.2f, Eff:%d trust:%.2f)",
                                matchedFast, matchedTotal, (int) softMin,
                                actualTime / Math.max(expectedTime, 1.0),
                                enhancements.efficiencyLevel, trustScore)
                );
                if (!filtering.passesLayer2HeuristicFiltering(player, ViolationType.FASTBREAK, prelimResult)) {
                    return CheckResult.passed();
                }
                return prelimResult;
            }
        }

        if (profile.getSuspiciousRatio() > suspiciousRatioThreshold && profile.getTotalBreaks() > 50
            && actualTime < expectedTime * 0.55) {
            double severity = 0.81;
            if (enhancements.efficiencyLevel >= 4) {
                severity = 0.86;
            }

            CheckResult prelimResult = CheckResult.failed(
                    ViolationType.FASTBREAK,
                    severity,
                    String.format("Suspicious pattern: %.0f%% of breaks flagged (%d/%d, Eff:%d, trust:%.2f)",
                            profile.getSuspiciousRatio() * 100, profile.getSuspiciousBreaks(),
                            profile.getTotalBreaks(), enhancements.efficiencyLevel, trustScore)
            );
            if (!filtering.passesLayer2HeuristicFiltering(player, ViolationType.FASTBREAK, prelimResult)) {
                return CheckResult.passed();
            }
            return prelimResult;
        }

        if (isHandBreaking && blockHardness >= 0.1) {
            double minRatioForHand = 0.30;
            int absoluteMinHand = 28;

            if (blockHardness <= 0.3) {
                minRatioForHand = 0.35;
                absoluteMinHand = 35;
            } else if (blockHardness <= 0.6) {
                minRatioForHand = 0.30;
                absoluteMinHand = 30;
            } else if (blockHardness <= 1.0) {
                minRatioForHand = 0.25;
                absoluteMinHand = 40;
            } else {
                minRatioForHand = 0.20;
                absoluteMinHand = 50;
            }

            if (enhancements.hasteLevel >= 2) {
                minRatioForHand *= 0.5;
                absoluteMinHand = (int)(absoluteMinHand * 0.5);
            } else if (enhancements.hasteLevel >= 1) {
                minRatioForHand *= 0.7;
                absoluteMinHand = (int)(absoluteMinHand * 0.7);
            }

            if (actualTime < expectedTime * minRatioForHand && expectedTime >= 50) {
                CheckResult prelimResult = CheckResult.failed(
                    ViolationType.FASTBREAK,
                    0.93,
                    String.format("Hand breaking too fast (%.0fms vs %.0fms expected, hardness:%.1f)",
                        (double)actualTime, expectedTime, blockHardness)
                );
                if (!filtering.passesLayer2HeuristicFiltering(player, ViolationType.FASTBREAK, prelimResult)) {
                    return CheckResult.passed();
                }
                return prelimResult;
            }

            if (actualTime < absoluteMinHand && expectedTime >= 50) {
                CheckResult prelimResult = CheckResult.failed(
                    ViolationType.FASTBREAK,
                    0.95,
                    String.format("Impossible hand break speed (%.0fms for hardness %.1f, expected %.0fms)",
                        (double)actualTime, blockHardness, expectedTime)
                );
                if (!filtering.passesLayer2HeuristicFiltering(player, ViolationType.FASTBREAK, prelimResult)) {
                    return CheckResult.passed();
                }
                return prelimResult;
            }
        }

        if (isShovelBlock && !toolWasSwitched && !efficiencyChanged) {
            int absoluteMin = 55;
            if (enhancements.hasteLevel >= 2) {
                if (enhancements.efficiencyLevel >= 5) {
                    absoluteMin = 35;
                } else if (enhancements.efficiencyLevel >= 4) {
                    absoluteMin = 37;
                } else if (enhancements.efficiencyLevel >= 3) {
                    absoluteMin = 39;
                } else {
                    absoluteMin = 41;
                }
            } else if (enhancements.hasteLevel == 1) {
                if (enhancements.efficiencyLevel >= 5) {
                    absoluteMin = 37;
                } else if (enhancements.efficiencyLevel >= 4) {
                    absoluteMin = 39;
                } else if (enhancements.efficiencyLevel >= 3) {
                    absoluteMin = 41;
                } else {
                    absoluteMin = 43;
                }
            } else {
                if (enhancements.efficiencyLevel >= 5) {
                    absoluteMin = 40;
                } else if (enhancements.efficiencyLevel >= 4) {
                    absoluteMin = 43;
                } else if (enhancements.efficiencyLevel >= 3) {
                    absoluteMin = 45;
                } else {
                    absoluteMin = 47;
                }
            }

            if (actualTime < absoluteMin) {
                CheckResult prelimResult = CheckResult.failed(
                    ViolationType.FASTBREAK,
                    0.96,
                    String.format("Shovel break too fast (%.0fms, Eff%d, min %dms, hardness:%.1f)",
                        (double)actualTime, enhancements.efficiencyLevel, absoluteMin, blockHardness)
                );
                if (!filtering.passesLayer2HeuristicFiltering(player, ViolationType.FASTBREAK, prelimResult)) {
                    return CheckResult.passed();
                }
                return prelimResult;
            }

            double minRatio = 0.50;
            if (enhancements.efficiencyLevel >= 5) {
                minRatio = 0.45;
            } else if (enhancements.efficiencyLevel >= 4) {
                minRatio = 0.48;
            } else if (enhancements.efficiencyLevel >= 3) {
                minRatio = 0.50;
            } else if (enhancements.efficiencyLevel >= 1) {
                minRatio = 0.52;
            } else {
                minRatio = 0.55;
            }
            if (enhancements.hasteLevel >= 2) {
                minRatio *= 0.6;
            } else if (enhancements.hasteLevel >= 1) {
                minRatio *= 0.8;
            }

            if (actualTime < expectedTime * minRatio) {
                CheckResult prelimResult = CheckResult.failed(
                    ViolationType.FASTBREAK,
                    0.94,
                    String.format("Shovel breaking too fast (%.0fms vs %.0fms expected, min %.0fms, Eff%d, hardness:%.1f)",
                        (double)actualTime, expectedTime, expectedTime * minRatio, enhancements.efficiencyLevel, blockHardness)
                );
                if (!filtering.passesLayer2HeuristicFiltering(player, ViolationType.FASTBREAK, prelimResult)) {
                    return CheckResult.passed();
                }
                return prelimResult;
            }
        }

        if (expectedTime >= 100 && !WaterHelper.isInWater(player) && !toolWasSwitched && !efficiencyChanged) {
            double immediateRatio = 0.55;
            if (enhancements.efficiencyLevel >= 5 && enhancements.hasteLevel >= 2) {
                immediateRatio = 0.28;
            } else if (enhancements.efficiencyLevel >= 5 || (enhancements.efficiencyLevel >= 4 && enhancements.hasteLevel >= 1)) {
                immediateRatio = 0.36;
            } else if (enhancements.efficiencyLevel >= 4) {
                immediateRatio = 0.44;
            } else if (enhancements.hasteLevel >= 2) {
                immediateRatio = 0.38;
            } else if (enhancements.hasteLevel >= 1) {
                immediateRatio = 0.48;
            }

            int p = filtering.getPing(player);
            double pingRelax = p > 200 ? 0.10 : (p > 100 ? 0.06 : 0.02);
            immediateRatio += pingRelax;

            double immediateMin = expectedTime * immediateRatio;
            if (actualTime < immediateMin) {
                double pct = (1.0 - (actualTime / expectedTime)) * 100;
                CheckResult prelimResult = CheckResult.failed(
                    ViolationType.FASTBREAK,
                    Math.min(0.95, 0.78 + (immediateMin - actualTime) / Math.max(immediateMin, 1.0) * 0.15),
                    String.format("Break too fast (%dms vs %.0fms expected, %.0f%% faster, Eff%d Haste%d hardness:%.1f)",
                        actualTime, expectedTime, pct, enhancements.efficiencyLevel, enhancements.hasteLevel, blockHardness)
                );
                if (!filtering.passesLayer2HeuristicFiltering(player, ViolationType.FASTBREAK, prelimResult)) {
                    return CheckResult.passed();
                }
                return prelimResult;
            }
        }

        if (actualTime < minTime) {
            double diff = minTime - actualTime;

            double impossibleThreshold = 0.20;
            if (enhancements.efficiencyLevel >= 4) {
                impossibleThreshold = 0.15;
            }
            if (enhancements.efficiencyLevel >= 5) {
                impossibleThreshold = 0.10;
            }
            if (enhancements.hasteLevel >= 2) {
                impossibleThreshold *= 0.5;
            } else if (enhancements.hasteLevel >= 1) {
                impossibleThreshold *= 0.7;
            }

            if (!enhancements.canInstamine && blockHardness > 0.5 && expectedTime > 300 && actualTime < expectedTime * impossibleThreshold) {
                CheckResult prelimResult = CheckResult.failed(
                    ViolationType.FASTBREAK,
                    0.96,
                    String.format("Impossible break speed for Eff%d (%.0fms vs expected %.0fms, hardness:%.1f)",
                        enhancements.efficiencyLevel, (double)actualTime, expectedTime, blockHardness)
                );
                if (!filtering.passesLayer2HeuristicFiltering(player, ViolationType.FASTBREAK, prelimResult)) {
                    return CheckResult.passed();
                }
                return prelimResult;
            }

            int minDiffThreshold = 45;
            if ((isShovelBlock || isHandBreaking) && isInstantMine) {
                minDiffThreshold = 20;
                if (trustScore > 0.75) {
                    minDiffThreshold = 25;
                } else if (trustScore > 0.6) {
                    minDiffThreshold = 22;
                }
            } else {
                if (trustScore > 0.75) {
                    minDiffThreshold = 60;
                } else if (trustScore > 0.6) {
                    minDiffThreshold = 50;
                }
            }

            if (ping > 100) {
                minDiffThreshold += Math.min((ping - 100) / 10, 15);
            }

            if (diff < minDiffThreshold) {
                falsePositiveFilter.recordLegitAction(player, ViolationType.FASTBREAK);
                return CheckResult.passed();
            }

            if (trustScore > 0.85) {
                int trustDiffThreshold = 65;
                if ((isShovelBlock || isHandBreaking) && isInstantMine) {
                    trustDiffThreshold = 28;
                }
                if (diff < trustDiffThreshold) {
                    falsePositiveFilter.recordLegitAction(player, ViolationType.FASTBREAK);
                    return CheckResult.passed();
                }
            }

            boolean canActuallyInstamine = enhancements.canInstamine || (expectedTime <= 50);
            if (canActuallyInstamine && actualTime < 50) {
                if (!(isShovelBlock && actualTime < 20)) {
                    falsePositiveFilter.recordLegitAction(player, ViolationType.FASTBREAK);
                    return CheckResult.passed();
                }
            }

            if (blockHardness < 0.3 && !enhancements.hasCorrectTool) {
                if (diff < 25) {
                    falsePositiveFilter.recordLegitAction(player, ViolationType.FASTBREAK);
                    return CheckResult.passed();
                }
            }

            if (ping > 120 && diff < 65) {
                falsePositiveFilter.recordLegitAction(player, ViolationType.FASTBREAK);
                return CheckResult.passed();
            }

            double rawSeverity = Math.min(1.0, diff / Math.max(150, expectedTime * 0.5));

            org.bukkit.inventory.ItemStack tool = player.getInventory().getItemInMainHand();
            String toolInfo = "Hand";
            if (tool != null && tool.getType() != org.bukkit.Material.AIR) {
                toolInfo = String.format("%s (Eff:%d)",
                    tool.getType().name().replace("_", " "),
                    enhancements.efficiencyLevel);
            }

            String blockInfo = block.getType().name().replace("_", " ");
            String potionInfo = enhancements.hasteLevel > 0 ? String.format("Haste:%d", enhancements.hasteLevel) : "None";
            String speedInfo = String.format("Speed:%.1fx", speedMultiplier);
            String envInfo = envFactors.details.equals("None") ? "" : " | Env:" + envFactors.details;

            String reason = String.format("Tool:%s | Block:%s | Haste:%s | %s%s | Break:%dms | Min:%.0fms | Exp:%.0fms | Diff:%.0fms | TPS:%.1f",
                    toolInfo, blockInfo, potionInfo, speedInfo, envInfo, actualTime, minTime, expectedTime, diff, getAverageTPS());

            FalsePositiveFilter.FilterResult filterResult = falsePositiveFilter.evaluate(
                player,
                ViolationType.FASTBREAK,
                rawSeverity,
                reason
            );

            if (filterResult.shouldFilter) {
                return CheckResult.passed();
            }

            double adjustedSeverity = filterResult.adjustedConfidence;
            trustScore = falsePositiveFilter.getPlayerTrustScore(playerId);

            String suspicionLevel;
            if (adjustedSeverity > 0.9) {
                suspicionLevel = "EXTREME";
            } else if (adjustedSeverity > 0.8) {
                suspicionLevel = "HIGH";
            } else if (adjustedSeverity > 0.7) {
                suspicionLevel = "MODERATE";
            } else {
                suspicionLevel = "LOW";
            }

            String detailedReason = String.format("[%s] %s | Trust:%.2f | Conf:%.2f",
                    suspicionLevel, reason, trustScore, adjustedSeverity);

            CheckResult prelimResult = CheckResult.failed(
                    ViolationType.FASTBREAK,
                    adjustedSeverity,
                    detailedReason
            );

            if (!filtering.passesLayer2HeuristicFiltering(player, ViolationType.FASTBREAK, prelimResult)) {
                BreakViolationTracker tracker = getTracker(playerId);
                tracker.recordLegit();
                return CheckResult.passed();
            }

            return checkWithSamples(player, prelimResult, diff, "basic");
        }

        BreakViolationTracker tracker = getTracker(player.getUniqueId());
        tracker.recordLegit();
        falsePositiveFilter.recordLegitAction(player, ViolationType.FASTBREAK);
        return CheckResult.passed();
    }

    private static void initializeBlockHardness() {
        BLOCK_HARDNESS.put(Material.STONE, 1.5);
        BLOCK_HARDNESS.put(Material.COBBLESTONE, 2.0);
        BLOCK_HARDNESS.put(Material.SANDSTONE, 0.8);
        BLOCK_HARDNESS.put(Material.SMOOTH_SANDSTONE, 0.8);
        BLOCK_HARDNESS.put(Material.CHISELED_SANDSTONE, 0.8);
        BLOCK_HARDNESS.put(Material.CUT_SANDSTONE, 0.8);
        BLOCK_HARDNESS.put(Material.RED_SANDSTONE, 0.8);
        BLOCK_HARDNESS.put(Material.SMOOTH_RED_SANDSTONE, 0.8);
        BLOCK_HARDNESS.put(Material.CHISELED_RED_SANDSTONE, 0.8);
        BLOCK_HARDNESS.put(Material.CUT_RED_SANDSTONE, 0.8);

        BLOCK_HARDNESS.put(Material.OBSIDIAN, 50.0);
        BLOCK_HARDNESS.put(Material.CRYING_OBSIDIAN, 50.0);

        BLOCK_HARDNESS.put(Material.DIRT, 0.5);
        BLOCK_HARDNESS.put(Material.GRASS_BLOCK, 0.6);
        BLOCK_HARDNESS.put(Material.PODZOL, 0.5);
        BLOCK_HARDNESS.put(Material.MYCELIUM, 0.5);
        BLOCK_HARDNESS.put(Material.COARSE_DIRT, 0.5);
        BLOCK_HARDNESS.put(Material.ROOTED_DIRT, 0.5);
        BLOCK_HARDNESS.put(Material.SAND, 0.5);
        BLOCK_HARDNESS.put(Material.RED_SAND, 0.5);
        BLOCK_HARDNESS.put(Material.GRAVEL, 0.5);
        BLOCK_HARDNESS.put(Material.SOUL_SAND, 0.5);
        BLOCK_HARDNESS.put(Material.SOUL_SOIL, 0.5);

        BLOCK_HARDNESS.put(Material.CLAY, 0.6);

        BLOCK_HARDNESS.put(Material.DEEPSLATE, 3.0);
        BLOCK_HARDNESS.put(Material.COBBLED_DEEPSLATE, 3.0);
        BLOCK_HARDNESS.put(Material.POLISHED_DEEPSLATE, 3.0);
        BLOCK_HARDNESS.put(Material.REINFORCED_DEEPSLATE, 55.0);

        BLOCK_HARDNESS.put(Material.NETHERRACK, 0.4);
        BLOCK_HARDNESS.put(Material.NETHER_WART_BLOCK, 1.0);
        BLOCK_HARDNESS.put(Material.WARPED_WART_BLOCK, 1.0);

        BLOCK_HARDNESS.put(Material.END_STONE, 3.0);

        BLOCK_HARDNESS.put(Material.DIAMOND_ORE, 3.0);
        BLOCK_HARDNESS.put(Material.DEEPSLATE_DIAMOND_ORE, 4.5);
        BLOCK_HARDNESS.put(Material.EMERALD_ORE, 3.0);
        BLOCK_HARDNESS.put(Material.DEEPSLATE_EMERALD_ORE, 4.5);
        BLOCK_HARDNESS.put(Material.COAL_ORE, 3.0);
        BLOCK_HARDNESS.put(Material.DEEPSLATE_COAL_ORE, 4.5);
        BLOCK_HARDNESS.put(Material.IRON_ORE, 3.0);
        BLOCK_HARDNESS.put(Material.DEEPSLATE_IRON_ORE, 4.5);
        BLOCK_HARDNESS.put(Material.GOLD_ORE, 3.0);
        BLOCK_HARDNESS.put(Material.DEEPSLATE_GOLD_ORE, 4.5);
        BLOCK_HARDNESS.put(Material.LAPIS_ORE, 3.0);
        BLOCK_HARDNESS.put(Material.DEEPSLATE_LAPIS_ORE, 4.5);
        BLOCK_HARDNESS.put(Material.REDSTONE_ORE, 3.0);
        BLOCK_HARDNESS.put(Material.DEEPSLATE_REDSTONE_ORE, 4.5);
        BLOCK_HARDNESS.put(Material.COPPER_ORE, 3.0);
        BLOCK_HARDNESS.put(Material.DEEPSLATE_COPPER_ORE, 4.5);
        BLOCK_HARDNESS.put(Material.NETHER_GOLD_ORE, 3.0);
        BLOCK_HARDNESS.put(Material.NETHER_QUARTZ_ORE, 3.0);

        BLOCK_HARDNESS.put(Material.ANCIENT_DEBRIS, 30.0);
        BLOCK_HARDNESS.put(Material.NETHERITE_BLOCK, 50.0);

        BLOCK_HARDNESS.put(Material.COPPER_BLOCK, 3.0);
        BLOCK_HARDNESS.put(Material.EXPOSED_COPPER, 3.0);
        BLOCK_HARDNESS.put(Material.WEATHERED_COPPER, 3.0);
        BLOCK_HARDNESS.put(Material.OXIDIZED_COPPER, 3.0);
        BLOCK_HARDNESS.put(Material.CUT_COPPER, 3.0);
        BLOCK_HARDNESS.put(Material.WAXED_COPPER_BLOCK, 3.0);
        BLOCK_HARDNESS.put(Material.WAXED_EXPOSED_COPPER, 3.0);
        BLOCK_HARDNESS.put(Material.WAXED_WEATHERED_COPPER, 3.0);
        BLOCK_HARDNESS.put(Material.WAXED_OXIDIZED_COPPER, 3.0);
        BLOCK_HARDNESS.put(Material.WAXED_CUT_COPPER, 3.0);

        BLOCK_HARDNESS.put(Material.AMETHYST_BLOCK, 1.5);
        BLOCK_HARDNESS.put(Material.BUDDING_AMETHYST, 1.5);

        BLOCK_HARDNESS.put(Material.SCULK, 0.2);
        BLOCK_HARDNESS.put(Material.SCULK_CATALYST, 3.0);
        BLOCK_HARDNESS.put(Material.SCULK_SENSOR, 1.5);
        BLOCK_HARDNESS.put(Material.SCULK_SHRIEKER, 3.0);
        BLOCK_HARDNESS.put(Material.SCULK_VEIN, 0.2);

        BLOCK_HARDNESS.put(Material.POINTED_DRIPSTONE, 1.5);
        BLOCK_HARDNESS.put(Material.DRIPSTONE_BLOCK, 1.5);

        BLOCK_HARDNESS.put(Material.TUFF, 1.5);
        BLOCK_HARDNESS.put(Material.CALCITE, 1.5);

        BLOCK_HARDNESS.put(Material.ANDESITE, 1.5);
        BLOCK_HARDNESS.put(Material.DIORITE, 1.5);
        BLOCK_HARDNESS.put(Material.GRANITE, 1.5);
        BLOCK_HARDNESS.put(Material.POLISHED_ANDESITE, 1.5);
        BLOCK_HARDNESS.put(Material.POLISHED_DIORITE, 1.5);
        BLOCK_HARDNESS.put(Material.POLISHED_GRANITE, 1.5);

        BLOCK_HARDNESS.put(Material.BASALT, 1.25);
        BLOCK_HARDNESS.put(Material.SMOOTH_BASALT, 1.25);
        BLOCK_HARDNESS.put(Material.POLISHED_BASALT, 1.25);

        BLOCK_HARDNESS.put(Material.BLACKSTONE, 1.5);
        BLOCK_HARDNESS.put(Material.POLISHED_BLACKSTONE, 1.5);
        BLOCK_HARDNESS.put(Material.GILDED_BLACKSTONE, 1.5);

        BLOCK_HARDNESS.put(Material.SNOW, 0.1);
        BLOCK_HARDNESS.put(Material.SNOW_BLOCK, 0.2);

        BLOCK_HARDNESS.put(Material.ICE, 0.5);
        BLOCK_HARDNESS.put(Material.PACKED_ICE, 0.5);
        BLOCK_HARDNESS.put(Material.BLUE_ICE, 2.8);

        BLOCK_HARDNESS.put(Material.TERRACOTTA, 1.25);
        BLOCK_HARDNESS.put(Material.WHITE_TERRACOTTA, 1.25);
        BLOCK_HARDNESS.put(Material.ORANGE_TERRACOTTA, 1.25);
        BLOCK_HARDNESS.put(Material.MAGENTA_TERRACOTTA, 1.25);
        BLOCK_HARDNESS.put(Material.LIGHT_BLUE_TERRACOTTA, 1.25);
        BLOCK_HARDNESS.put(Material.YELLOW_TERRACOTTA, 1.25);
        BLOCK_HARDNESS.put(Material.LIME_TERRACOTTA, 1.25);
        BLOCK_HARDNESS.put(Material.PINK_TERRACOTTA, 1.25);
        BLOCK_HARDNESS.put(Material.GRAY_TERRACOTTA, 1.25);
        BLOCK_HARDNESS.put(Material.LIGHT_GRAY_TERRACOTTA, 1.25);
        BLOCK_HARDNESS.put(Material.CYAN_TERRACOTTA, 1.25);
        BLOCK_HARDNESS.put(Material.PURPLE_TERRACOTTA, 1.25);
        BLOCK_HARDNESS.put(Material.BLUE_TERRACOTTA, 1.25);
        BLOCK_HARDNESS.put(Material.BROWN_TERRACOTTA, 1.25);
        BLOCK_HARDNESS.put(Material.GREEN_TERRACOTTA, 1.25);
        BLOCK_HARDNESS.put(Material.RED_TERRACOTTA, 1.25);
        BLOCK_HARDNESS.put(Material.BLACK_TERRACOTTA, 1.25);

        BLOCK_HARDNESS.put(Material.GLOWSTONE, 0.3);

        BLOCK_HARDNESS.put(Material.GLASS, 0.3);
        BLOCK_HARDNESS.put(Material.WHITE_STAINED_GLASS, 0.3);
        BLOCK_HARDNESS.put(Material.ORANGE_STAINED_GLASS, 0.3);
        BLOCK_HARDNESS.put(Material.MAGENTA_STAINED_GLASS, 0.3);
        BLOCK_HARDNESS.put(Material.LIGHT_BLUE_STAINED_GLASS, 0.3);
        BLOCK_HARDNESS.put(Material.YELLOW_STAINED_GLASS, 0.3);
        BLOCK_HARDNESS.put(Material.LIME_STAINED_GLASS, 0.3);
        BLOCK_HARDNESS.put(Material.PINK_STAINED_GLASS, 0.3);
        BLOCK_HARDNESS.put(Material.GRAY_STAINED_GLASS, 0.3);
        BLOCK_HARDNESS.put(Material.LIGHT_GRAY_STAINED_GLASS, 0.3);
        BLOCK_HARDNESS.put(Material.CYAN_STAINED_GLASS, 0.3);
        BLOCK_HARDNESS.put(Material.PURPLE_STAINED_GLASS, 0.3);
        BLOCK_HARDNESS.put(Material.BLUE_STAINED_GLASS, 0.3);
        BLOCK_HARDNESS.put(Material.BROWN_STAINED_GLASS, 0.3);
        BLOCK_HARDNESS.put(Material.GREEN_STAINED_GLASS, 0.3);
        BLOCK_HARDNESS.put(Material.RED_STAINED_GLASS, 0.3);
        BLOCK_HARDNESS.put(Material.BLACK_STAINED_GLASS, 0.3);

        BLOCK_HARDNESS.put(Material.TALL_GRASS, 0.0);
        BLOCK_HARDNESS.put(Material.SHORT_GRASS, 0.0);
        BLOCK_HARDNESS.put(Material.FERN, 0.0);
        BLOCK_HARDNESS.put(Material.LARGE_FERN, 0.0);
        BLOCK_HARDNESS.put(Material.DEAD_BUSH, 0.0);
        BLOCK_HARDNESS.put(Material.DANDELION, 0.0);
        BLOCK_HARDNESS.put(Material.POPPY, 0.0);
        BLOCK_HARDNESS.put(Material.BLUE_ORCHID, 0.0);
        BLOCK_HARDNESS.put(Material.ALLIUM, 0.0);
        BLOCK_HARDNESS.put(Material.AZURE_BLUET, 0.0);
        BLOCK_HARDNESS.put(Material.RED_TULIP, 0.0);
        BLOCK_HARDNESS.put(Material.ORANGE_TULIP, 0.0);
        BLOCK_HARDNESS.put(Material.WHITE_TULIP, 0.0);
        BLOCK_HARDNESS.put(Material.PINK_TULIP, 0.0);
        BLOCK_HARDNESS.put(Material.OXEYE_DAISY, 0.0);
        BLOCK_HARDNESS.put(Material.CORNFLOWER, 0.0);
        BLOCK_HARDNESS.put(Material.LILY_OF_THE_VALLEY, 0.0);
        BLOCK_HARDNESS.put(Material.WITHER_ROSE, 0.0);
        BLOCK_HARDNESS.put(Material.SUNFLOWER, 0.0);
        BLOCK_HARDNESS.put(Material.LILAC, 0.0);
        BLOCK_HARDNESS.put(Material.ROSE_BUSH, 0.0);
        BLOCK_HARDNESS.put(Material.PEONY, 0.0);
        BLOCK_HARDNESS.put(Material.OAK_SAPLING, 0.0);
        BLOCK_HARDNESS.put(Material.SPRUCE_SAPLING, 0.0);
        BLOCK_HARDNESS.put(Material.BIRCH_SAPLING, 0.0);
        BLOCK_HARDNESS.put(Material.JUNGLE_SAPLING, 0.0);
        BLOCK_HARDNESS.put(Material.ACACIA_SAPLING, 0.0);
        BLOCK_HARDNESS.put(Material.DARK_OAK_SAPLING, 0.0);
        BLOCK_HARDNESS.put(Material.RED_MUSHROOM, 0.0);
        BLOCK_HARDNESS.put(Material.BROWN_MUSHROOM, 0.0);
        BLOCK_HARDNESS.put(Material.CRIMSON_FUNGUS, 0.0);
        BLOCK_HARDNESS.put(Material.WARPED_FUNGUS, 0.0);
        BLOCK_HARDNESS.put(Material.TORCH, 0.0);
        BLOCK_HARDNESS.put(Material.REDSTONE_TORCH, 0.0);
        BLOCK_HARDNESS.put(Material.FIRE, 0.0);

        BLOCK_HARDNESS.put(Material.WHEAT, 0.0);
        BLOCK_HARDNESS.put(Material.CARROTS, 0.0);
        BLOCK_HARDNESS.put(Material.POTATOES, 0.0);
        BLOCK_HARDNESS.put(Material.BEETROOTS, 0.0);
        BLOCK_HARDNESS.put(Material.NETHER_WART, 0.0);
        BLOCK_HARDNESS.put(Material.SWEET_BERRY_BUSH, 0.0);
        BLOCK_HARDNESS.put(Material.SUGAR_CANE, 0.0);
        BLOCK_HARDNESS.put(Material.KELP, 0.0);
        BLOCK_HARDNESS.put(Material.KELP_PLANT, 0.0);
        BLOCK_HARDNESS.put(Material.SEAGRASS, 0.0);
        BLOCK_HARDNESS.put(Material.TALL_SEAGRASS, 0.0);
        BLOCK_HARDNESS.put(Material.LILY_PAD, 0.0);
        BLOCK_HARDNESS.put(Material.TRIPWIRE, 0.0);
        BLOCK_HARDNESS.put(Material.TRIPWIRE_HOOK, 0.0);
        BLOCK_HARDNESS.put(Material.REDSTONE_WIRE, 0.0);
        BLOCK_HARDNESS.put(Material.REPEATER, 0.0);
        BLOCK_HARDNESS.put(Material.COMPARATOR, 0.0);
        BLOCK_HARDNESS.put(Material.FLOWER_POT, 0.0);
        BLOCK_HARDNESS.put(Material.SLIME_BLOCK, 0.0);
        BLOCK_HARDNESS.put(Material.TNT, 0.0);
        BLOCK_HARDNESS.put(Material.MOSS_CARPET, 0.0);
        BLOCK_HARDNESS.put(Material.COCOA, 0.2);

        BLOCK_HARDNESS.put(Material.MUD, 0.5);
        BLOCK_HARDNESS.put(Material.PACKED_MUD, 1.0);
        BLOCK_HARDNESS.put(Material.MUD_BRICKS, 1.5);

        BLOCK_HARDNESS.put(Material.MANGROVE_ROOTS, 0.7);
        BLOCK_HARDNESS.put(Material.MUDDY_MANGROVE_ROOTS, 0.7);

        BLOCK_HARDNESS.put(Material.SHROOMLIGHT, 1.0);
        BLOCK_HARDNESS.put(Material.OCHRE_FROGLIGHT, 0.3);
        BLOCK_HARDNESS.put(Material.VERDANT_FROGLIGHT, 0.3);
        BLOCK_HARDNESS.put(Material.PEARLESCENT_FROGLIGHT, 0.3);

        try {
            BLOCK_HARDNESS.put(Material.valueOf("CHERRY_LOG"), 2.0);
            BLOCK_HARDNESS.put(Material.valueOf("CHERRY_PLANKS"), 2.0);
            BLOCK_HARDNESS.put(Material.valueOf("CHERRY_LEAVES"), 0.2);
            BLOCK_HARDNESS.put(Material.valueOf("BAMBOO_BLOCK"), 2.0);
            BLOCK_HARDNESS.put(Material.valueOf("BAMBOO_PLANKS"), 2.0);
            BLOCK_HARDNESS.put(Material.valueOf("BAMBOO_MOSAIC"), 2.0);
            BLOCK_HARDNESS.put(Material.valueOf("SUSPICIOUS_SAND"), 0.25);
            BLOCK_HARDNESS.put(Material.valueOf("SUSPICIOUS_GRAVEL"), 0.25);
            BLOCK_HARDNESS.put(Material.valueOf("DECORATED_POT"), 0.0);
            BLOCK_HARDNESS.put(Material.valueOf("CHISELED_BOOKSHELF"), 1.5);
            BLOCK_HARDNESS.put(Material.valueOf("PIGLIN_HEAD"), 1.0);
            BLOCK_HARDNESS.put(Material.valueOf("SNIFFER_EGG"), 0.5);
            BLOCK_HARDNESS.put(Material.valueOf("CALIBRATED_SCULK_SENSOR"), 1.5);
        } catch (IllegalArgumentException e) {
            Logger.getLogger("NoChance").log(Level.WARNING, "Some 1.20 block materials not available on this server version", e);
        }

        try {
            BLOCK_HARDNESS.put(Material.valueOf("TRIAL_SPAWNER"), 50.0);
            BLOCK_HARDNESS.put(Material.valueOf("VAULT"), 50.0);
            BLOCK_HARDNESS.put(Material.valueOf("CRAFTER"), 1.5);
            BLOCK_HARDNESS.put(Material.valueOf("TUFF_BRICKS"), 1.5);
            BLOCK_HARDNESS.put(Material.valueOf("POLISHED_TUFF"), 1.5);
            BLOCK_HARDNESS.put(Material.valueOf("CHISELED_TUFF"), 1.5);
            BLOCK_HARDNESS.put(Material.valueOf("CHISELED_TUFF_BRICKS"), 1.5);
            BLOCK_HARDNESS.put(Material.valueOf("HEAVY_CORE"), 10.0);
            BLOCK_HARDNESS.put(Material.valueOf("COPPER_GRATE"), 3.0);
            BLOCK_HARDNESS.put(Material.valueOf("COPPER_BULB"), 3.0);
            BLOCK_HARDNESS.put(Material.valueOf("COPPER_DOOR"), 3.0);
            BLOCK_HARDNESS.put(Material.valueOf("COPPER_TRAPDOOR"), 3.0);
        } catch (IllegalArgumentException e) {
            Logger.getLogger("NoChance").log(Level.WARNING, "Some 1.21 block materials not available on this server version", e);
        }
    }

    private double getBlockHardness(Material material) {
        Double hardness = BLOCK_HARDNESS.get(material);
        if (hardness != null) {
            return hardness;
        }

        String name = material.name();
        if (name.contains("SAPLING") || name.contains("TULIP") || name.contains("ORCHID")
                || name.contains("DAISY") || name.contains("BLUET") || name.contains("ALLIUM")
                || name.contains("DANDELION") || name.contains("POPPY") || name.contains("CORNFLOWER")
                || name.contains("_STEM") && !name.contains("MUSHROOM") && !name.contains("CRIMSON") && !name.contains("WARPED")
                || name.contains("TORCH") || name.contains("CARPET") && name.contains("MOSS")) {
            return 0.0;
        }
        if (name.contains("LOG") || (name.contains("WOOD") && !name.contains("SWORD"))) {
            return 2.0;
        }
        if (name.contains("LEAVES")) {
            return 0.2;
        }
        if (name.contains("PLANKS")) {
            return 2.0;
        }
        if (name.contains("WOOL")) {
            return 0.8;
        }
        if (name.contains("CARPET")) {
            return 0.1;
        }
        if (name.contains("CONCRETE") && !name.contains("POWDER")) {
            return 1.8;
        }
        if (name.contains("CONCRETE_POWDER")) {
            return 0.5;
        }
        if (name.contains("SPONGE")) {
            return 0.6;
        }
        if (name.contains("_STEM")) {
            return 2.0;
        }
        if (name.contains("PUMPKIN") || name.contains("MELON")) {
            return 1.0;
        }
        if (name.contains("TUFF") && (name.contains("BRICK") || name.contains("POLISHED"))) {
            return 1.5;
        }
        if (name.contains("CHERRY")) {
            return 2.0;
        }
        if (name.contains("BAMBOO") && (name.contains("BLOCK") || name.contains("PLANKS") || name.contains("MOSAIC"))) {
            return 2.0;
        }
        if (name.contains("MANGROVE")) {
            return 2.0;
        }
        if (name.contains("MUD") && name.contains("BRICK")) {
            return 1.5;
        }
        if (name.equals("MUD") || name.equals("PACKED_MUD")) {
            return 0.5;
        }
        if (name.contains("FROGLIGHT")) {
            return 0.3;
        }
        if (name.contains("TRIAL_SPAWNER") || name.contains("VAULT")) {
            return 50.0;
        }
        if (name.contains("CRAFTER")) {
            return 1.5;
        }

        try {
            float bukkitHardness = material.getHardness();
            if (bukkitHardness >= 0) {
                return bukkitHardness;
            }
        } catch (Exception e) {
            Logger.getLogger("NoChance").log(Level.WARNING, "Failed to get hardness for material: " + material, e);
        }

        return 1.0;
    }

    private static void initializeInstantBreakBlocks() {
        String[] names = {
            "TALL_GRASS", "SHORT_GRASS", "FERN", "LARGE_FERN", "DEAD_BUSH",
            "DANDELION", "POPPY", "BLUE_ORCHID", "ALLIUM", "AZURE_BLUET",
            "RED_TULIP", "ORANGE_TULIP", "WHITE_TULIP", "PINK_TULIP",
            "OXEYE_DAISY", "CORNFLOWER", "LILY_OF_THE_VALLEY", "WITHER_ROSE",
            "SUNFLOWER", "LILAC", "ROSE_BUSH", "PEONY", "TORCHFLOWER",
            "OAK_SAPLING", "SPRUCE_SAPLING", "BIRCH_SAPLING", "JUNGLE_SAPLING",
            "ACACIA_SAPLING", "DARK_OAK_SAPLING", "CHERRY_SAPLING", "MANGROVE_PROPAGULE",
            "RED_MUSHROOM", "BROWN_MUSHROOM", "CRIMSON_FUNGUS", "WARPED_FUNGUS",
            "TORCH", "WALL_TORCH", "SOUL_TORCH", "SOUL_WALL_TORCH",
            "REDSTONE_TORCH", "REDSTONE_WALL_TORCH",
            "FIRE", "SOUL_FIRE",
            "WHEAT", "CARROTS", "POTATOES", "BEETROOTS", "NETHER_WART",
            "SWEET_BERRY_BUSH", "SUGAR_CANE", "KELP", "KELP_PLANT",
            "SEAGRASS", "TALL_SEAGRASS",
            "LILY_PAD", "SNOW", "TRIPWIRE", "TRIPWIRE_HOOK",
            "REDSTONE_WIRE", "REPEATER", "COMPARATOR",
            "FLOWER_POT", "SLIME_BLOCK", "TNT",
            "ATTACHED_MELON_STEM", "ATTACHED_PUMPKIN_STEM",
            "MELON_STEM", "PUMPKIN_STEM",
            "MOSS_CARPET", "PINK_PETALS",
            "DECORATED_POT"
        };
        for (String name : names) {
            INSTANT_BREAK_BLOCKS.add(name);
        }
    }

    private boolean isInstantBreakBlock(Material material) {
        if (INSTANT_BREAK_BLOCKS.contains(material.name())) {
            return true;
        }
        double hardness = getBlockHardness(material);
        return hardness <= 0.0;
    }

    private static void initializeToolMultipliers() {
        TOOL_MULTIPLIERS.put(Material.WOODEN_PICKAXE, 2.0);
        TOOL_MULTIPLIERS.put(Material.WOODEN_AXE, 2.0);
        TOOL_MULTIPLIERS.put(Material.WOODEN_SHOVEL, 2.0);
        TOOL_MULTIPLIERS.put(Material.WOODEN_HOE, 2.0);
        TOOL_MULTIPLIERS.put(Material.WOODEN_SWORD, 2.0);

        TOOL_MULTIPLIERS.put(Material.STONE_PICKAXE, 4.0);
        TOOL_MULTIPLIERS.put(Material.STONE_AXE, 4.0);
        TOOL_MULTIPLIERS.put(Material.STONE_SHOVEL, 4.0);
        TOOL_MULTIPLIERS.put(Material.STONE_HOE, 4.0);
        TOOL_MULTIPLIERS.put(Material.STONE_SWORD, 4.0);

        TOOL_MULTIPLIERS.put(Material.IRON_PICKAXE, 6.0);
        TOOL_MULTIPLIERS.put(Material.IRON_AXE, 6.0);
        TOOL_MULTIPLIERS.put(Material.IRON_SHOVEL, 6.0);
        TOOL_MULTIPLIERS.put(Material.IRON_HOE, 6.0);
        TOOL_MULTIPLIERS.put(Material.IRON_SWORD, 6.0);

        TOOL_MULTIPLIERS.put(Material.DIAMOND_PICKAXE, 8.0);
        TOOL_MULTIPLIERS.put(Material.DIAMOND_AXE, 8.0);
        TOOL_MULTIPLIERS.put(Material.DIAMOND_SHOVEL, 8.0);
        TOOL_MULTIPLIERS.put(Material.DIAMOND_HOE, 8.0);
        TOOL_MULTIPLIERS.put(Material.DIAMOND_SWORD, 8.0);

        TOOL_MULTIPLIERS.put(Material.GOLDEN_PICKAXE, 12.0);
        TOOL_MULTIPLIERS.put(Material.GOLDEN_AXE, 12.0);
        TOOL_MULTIPLIERS.put(Material.GOLDEN_SHOVEL, 12.0);
        TOOL_MULTIPLIERS.put(Material.GOLDEN_HOE, 12.0);
        TOOL_MULTIPLIERS.put(Material.GOLDEN_SWORD, 12.0);

        TOOL_MULTIPLIERS.put(Material.NETHERITE_PICKAXE, 9.0);
        TOOL_MULTIPLIERS.put(Material.NETHERITE_AXE, 9.0);
        TOOL_MULTIPLIERS.put(Material.NETHERITE_SHOVEL, 9.0);
        TOOL_MULTIPLIERS.put(Material.NETHERITE_HOE, 9.0);
        TOOL_MULTIPLIERS.put(Material.NETHERITE_SWORD, 9.0);

        TOOL_MULTIPLIERS.put(Material.SHEARS, 1.5);
    }

    private boolean canToolHarvest(Material blockType, EnhancementTracker.BlockBreakEnhancements enhancements) {
        String blockName = blockType.name();
        String toolMaterial = enhancements.toolMaterial;
        boolean hasPickaxe = enhancements.toolType.equals("PICKAXE");

        if (blockName.contains("STONE") || blockName.contains("_ORE") || blockName.contains("COBBLESTONE")
            || blockName.contains("DEEPSLATE") || blockName.contains("BASALT")
            || blockName.contains("BLACKSTONE") || blockName.contains("BRICKS")
            || blockName.contains("TERRACOTTA") || blockName.contains("PRISMARINE")
            || (blockName.contains("CONCRETE") && !blockName.contains("POWDER"))
            || blockName.contains("SANDSTONE") || blockName.contains("COPPER")
            || blockName.contains("IRON_BLOCK") || blockName.contains("GOLD_BLOCK")
            || blockName.contains("DIAMOND_BLOCK") || blockName.contains("EMERALD_BLOCK")
            || blockName.contains("NETHERITE_BLOCK") || blockName.contains("AMETHYST")
            || blockName.contains("DRIPSTONE") || blockName.contains("CALCITE")
            || blockName.contains("TUFF") || blockType == Material.END_STONE
            || blockType == Material.OBSIDIAN || blockType == Material.CRYING_OBSIDIAN
            || blockType == Material.ANCIENT_DEBRIS) {
            if (!enhancements.hasCorrectTool) return false;
            if (blockType == Material.OBSIDIAN || blockType == Material.CRYING_OBSIDIAN
                || blockType == Material.ANCIENT_DEBRIS) {
                return toolMaterial.equals("DIAMOND") || toolMaterial.equals("NETHERITE");
            }
            if (blockName.contains("DIAMOND") || blockName.contains("EMERALD")) {
                return toolMaterial.equals("IRON") || toolMaterial.equals("DIAMOND") || toolMaterial.equals("NETHERITE");
            }
            if (blockName.equals("NETHER_GOLD_ORE")) {
                return hasPickaxe;
            }
            if (blockName.contains("GOLD") || blockName.contains("REDSTONE")) {
                return toolMaterial.equals("IRON") || toolMaterial.equals("DIAMOND") || toolMaterial.equals("NETHERITE");
            }
            if (blockName.contains("IRON_ORE") || blockName.contains("LAPIS") || blockName.contains("COPPER_ORE")) {
                return toolMaterial.equals("STONE") || toolMaterial.equals("IRON") || toolMaterial.equals("DIAMOND") || toolMaterial.equals("NETHERITE");
            }
            return true;
        }

        if (blockName.contains("IRON_DOOR") || blockName.contains("IRON_TRAPDOOR")
            || blockName.equals("LANTERN") || blockName.equals("SOUL_LANTERN")
            || blockName.equals("BREWING_STAND")
            || blockName.contains("CAULDRON") || blockName.equals("BELL")
            || blockName.equals("HOPPER") || blockName.contains("ANVIL")
            || blockName.equals("IRON_BARS") || blockName.equals("CHAIN")) {
            if (!hasPickaxe) return false;
        }

        return true;
    }

    private double getTPSMultiplier() {
        return TPSCache.getMultiplier();
    }

    private double getAverageTPS() {
        return TPSCache.getTPS();
    }

    private double calculateExactBreakTime(Player player, Block block, EnhancementTracker.BlockBreakEnhancements enhancements) {
        return EnhancementTracker.computeExpectedBreakTimeMs(player, block, enhancements);
    }

    private CheckResult checkWithSamples(Player player, CheckResult prelimResult, double diff, String checkType) {
        UUID playerId = player.getUniqueId();
        BreakViolationTracker tracker = getTracker(playerId);

        tracker.recordViolation(diff);

        int required = MIN_SAMPLES_BASIC;
        if (checkType.equals("pattern")) {
            required = MIN_SAMPLES_PATTERN;
        }

        if (prelimResult.getSeverity() >= 0.96) {
            required = 2;
        } else if (prelimResult.getSeverity() >= 0.94) {
            required = Math.max(required, 2);
        }

        if (tracker.getConsecutiveViolations() < required) {
            return CheckResult.passed();
        }

        double variance = tracker.getVariance();
        if (variance < 100 && tracker.getSampleCount() >= 3) {
            double adjustedSeverity = Math.min(1.0, prelimResult.getSeverity() + 0.05);
            return CheckResult.failed(
                prelimResult.getViolationType(),
                adjustedSeverity,
                prelimResult.getDetails() + String.format(" [consistent var:%.1f, samples:%d]", variance, tracker.getSampleCount())
            );
        }

        return prelimResult;
    }

    public int getExpectedBreakTime(Player player, Block block) {
        return (int) EnhancementTracker.getExpectedBreakTimeMs(player, block);
    }

    public void cleanup(UUID playerId) {
        profiler.cleanup(playerId);
        packetAnalyzer.cleanup(playerId);
        falsePositiveFilter.cleanup(playerId);
        lastCheckTime.remove(playerId);
        rapidCheckCount.remove(playerId);
        violationTrackers.remove(playerId);
    }

    public void cleanupStale() {
        long now = System.currentTimeMillis();
        lastCheckTime.entrySet().removeIf(entry -> now - entry.getValue() > 60000);
        rapidCheckCount.entrySet().removeIf(entry -> !lastCheckTime.containsKey(entry.getKey()));
        violationTrackers.entrySet().removeIf(entry -> !lastCheckTime.containsKey(entry.getKey()));
    }

    private BreakViolationTracker getTracker(UUID playerId) {
        return violationTrackers.computeIfAbsent(playerId, k -> new BreakViolationTracker());
    }

    private static class BreakViolationTracker {
        private int consecutiveViolations = 0;
        private long lastViolationTime = 0;
        private final List<Double> recentDiffs = new ArrayList<>();
        private double ewmaDiff = 0;
        private boolean ewmaInit = false;

        void recordViolation(double diff) {
            consecutiveViolations++;
            lastViolationTime = System.currentTimeMillis();
            recentDiffs.add(diff);
            if (recentDiffs.size() > 10) {
                recentDiffs.remove(0);
            }
            if (!ewmaInit) {
                ewmaDiff = diff;
                ewmaInit = true;
            } else {
                ewmaDiff = 0.35 * diff + 0.65 * ewmaDiff;
            }
        }

        void recordLegit() {
            long elapsed = System.currentTimeMillis() - lastViolationTime;
            if (elapsed > 1500) {
                consecutiveViolations = 0;
                ewmaDiff = 0;
                ewmaInit = false;
                recentDiffs.clear();
            } else if (elapsed > 700) {
                consecutiveViolations = Math.max(0, consecutiveViolations - 5);
                ewmaDiff *= 0.25;
            } else {
                consecutiveViolations = Math.max(0, consecutiveViolations - 6);
                ewmaDiff *= 0.4;
            }
        }

        int getConsecutiveViolations() {
            return consecutiveViolations;
        }

        double getEWMADiff() {
            return ewmaDiff;
        }

        int getSampleCount() {
            return recentDiffs.size();
        }

        double getVariance() {
            if (recentDiffs.size() < 2) return 1000;
            double mean = recentDiffs.stream().mapToDouble(Double::doubleValue).average().orElse(0);
            return recentDiffs.stream().mapToDouble(d -> Math.pow(d - mean, 2)).average().orElse(0);
        }

        void reset() {
            consecutiveViolations = 0;
            recentDiffs.clear();
            ewmaInit = false;
            ewmaDiff = 0;
        }
    }
}
