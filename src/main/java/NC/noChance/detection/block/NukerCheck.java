package NC.noChance.detection.block;

import NC.noChance.core.*;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

public class NukerCheck {
    private final ACConfig config;
    private final Map<UUID, PlayerData> playerDataMap;
    private final LayerFiltering filtering;
    private final Map<UUID, List<BlockBreakRecord>> recentBreaks;
    private final BreakProfile breakProfile;

    private static final Set<Material> INSTANT_BREAK_SKIP = new HashSet<>();
    static {
        String[] names = {
            "TALL_GRASS", "SHORT_GRASS", "FERN", "LARGE_FERN", "DEAD_BUSH",
            "DANDELION", "POPPY", "BLUE_ORCHID", "ALLIUM", "AZURE_BLUET",
            "RED_TULIP", "ORANGE_TULIP", "WHITE_TULIP", "PINK_TULIP",
            "OXEYE_DAISY", "CORNFLOWER", "LILY_OF_THE_VALLEY", "WITHER_ROSE",
            "SUNFLOWER", "LILAC", "ROSE_BUSH", "PEONY", "TORCHFLOWER",
            "OAK_SAPLING", "SPRUCE_SAPLING", "BIRCH_SAPLING", "JUNGLE_SAPLING",
            "ACACIA_SAPLING", "DARK_OAK_SAPLING", "CHERRY_SAPLING", "MANGROVE_PROPAGULE",
            "RED_MUSHROOM", "BROWN_MUSHROOM", "CRIMSON_FUNGUS", "WARPED_FUNGUS",
            "SEAGRASS", "TALL_SEAGRASS", "KELP", "KELP_PLANT",
            "WHEAT", "CARROTS", "POTATOES", "BEETROOTS", "NETHER_WART",
            "SUGAR_CANE", "SWEET_BERRY_BUSH",
            "LILY_PAD", "PINK_PETALS", "MOSS_CARPET",
            "TORCH", "WALL_TORCH", "SOUL_TORCH", "SOUL_WALL_TORCH",
            "REDSTONE_TORCH", "REDSTONE_WALL_TORCH",
            "TRIPWIRE", "TRIPWIRE_HOOK", "REDSTONE_WIRE",
            "MELON_STEM", "PUMPKIN_STEM", "ATTACHED_MELON_STEM", "ATTACHED_PUMPKIN_STEM",
            "VINE", "CAVE_VINES", "CAVE_VINES_PLANT", "TWISTING_VINES", "WEEPING_VINES",
            "SNOW", "FIRE", "SOUL_FIRE", "FLOWER_POT"
        };
        for (String n : names) {
            try {
                INSTANT_BREAK_SKIP.add(Material.valueOf(n));
            } catch (IllegalArgumentException e) {
                Logger.getLogger("NoChance").log(Level.WARNING, "NukerCheck: unknown material in instant-break list: " + n, e);
            }
        }
    }

    public NukerCheck(ACConfig config, Map<UUID, PlayerData> playerDataMap, LayerFiltering filtering) {
        this.config = config;
        this.playerDataMap = playerDataMap;
        this.filtering = filtering;
        this.recentBreaks = new ConcurrentHashMap<>();
        this.breakProfile = new BreakProfile();
    }

    public CheckResult check(Player player, Location blockLocation) {
        if (!config.isCheckEnabled("nuker")) {
            return CheckResult.passed();
        }

        if (INSTANT_BREAK_SKIP.contains(blockLocation.getBlock().getType())) {
            return CheckResult.passed();
        }

        PlayerData data = playerDataMap.get(player.getUniqueId());
        if (data == null) return CheckResult.passed();

        if (data.isInGracePeriod(config.getGracePeriod())) {
            List<BlockBreakRecord> graceBreaks = recentBreaks.get(player.getUniqueId());
            double breaksPerSec = 0.0;
            if (graceBreaks != null) {
                synchronized (graceBreaks) {
                    if (graceBreaks.size() >= 2) {
                        long oldest = graceBreaks.get(0).timestamp;
                        long newest = graceBreaks.get(graceBreaks.size() - 1).timestamp;
                        double seconds = (newest - oldest) / 1000.0;
                        if (seconds > 0.0) {
                            breaksPerSec = graceBreaks.size() / seconds;
                        }
                    }
                }
            }
            if (breaksPerSec < 25.0) {
                return CheckResult.passed();
            }
        }

        if (player.getGameMode() == org.bukkit.GameMode.CREATIVE) {
            return CheckResult.passed();
        }

        int ping = filtering.getPing(player);
        PrecisionReach.ReachResult reachResult = PrecisionReach.checkBlockReach(player, blockLocation, ping);
        double reachLimit = (player.getGameMode() == org.bukkit.GameMode.CREATIVE ? 6.0 : 5.2) + Math.min(0.5, ping * 0.001);
        if (reachResult.distance > reachLimit) {
            CheckResult prelimResult = CheckResult.failed(
                    ViolationType.NUKER,
                    Math.min(0.98, 0.80 + (reachResult.distance - reachLimit) * 0.1),
                    String.format("[REACH] Block break at %.2f blocks (max: %.1f)", reachResult.distance, reachLimit)
            );
            if (filtering.passesLayer2HeuristicFiltering(player, ViolationType.NUKER, prelimResult)) {
                return prelimResult;
            }
        }

        UUID playerId = player.getUniqueId();
        long now = System.currentTimeMillis();

        List<BlockBreakRecord> breaks = recentBreaks.computeIfAbsent(playerId, k -> Collections.synchronizedList(new ArrayList<>()));
        synchronized (breaks) {
            breaks.add(new BlockBreakRecord(blockLocation, now));
            breaks.removeIf(record -> now - record.timestamp > 1200);
        }

        if (breaks.size() < 2) {
            return CheckResult.passed();
        }

        BreakProfile.Profile profile = breakProfile.getProfile(playerId);
        profile.recordBreak(blockLocation.getBlock().getType(), 0L, false);
        int breakStreak = profile.getBreakStreak();

        boolean instamine = EnhancementTracker.canBreakInstantly(player, blockLocation.getBlock());

        int simultaneousBreaks = countSimultaneousBreaks(breaks);
        int simThreshold = instamine ? 6 : 4;
        if (breakStreak > 15) {
            simThreshold = Math.max(3, simThreshold - 1);
        }
        boolean multiDir = hasMultiDirectionBreaks(player, breaks);
        if (simultaneousBreaks >= simThreshold && (multiDir || simultaneousBreaks >= simThreshold + 3)) {
            String toolInfo = getToolInfo(player);
            double bps = breaks.size() / 1.2;
            String pattern = analyzeBreakPattern(breaks);

            CheckResult prelimResult = CheckResult.failed(
                    ViolationType.NUKER,
                    Math.min(1.0, 0.7 + simultaneousBreaks * 0.1),
                    String.format("[MULTI-BREAK] %d blocks/tick | Tool: %s | BPS: %.1f | Pattern: %s",
                            simultaneousBreaks, toolInfo, bps, pattern)
            );

            if (!filtering.passesLayer2HeuristicFiltering(player, ViolationType.NUKER, prelimResult)) {
                return CheckResult.passed();
            }

            return prelimResult;
        }

        if (breaks.size() >= 8) {
            long oldest = breaks.get(0).timestamp;
            long newest = breaks.get(breaks.size() - 1).timestamp;
            double seconds = (newest - oldest) / 1000.0;
            if (seconds > 0.2) {
                double bps = breaks.size() / seconds;
                double maxBps = getMaxBreakRate(player, blockLocation.getBlock());
                if (instamine) maxBps *= 2.0;
                if (!multiDir) maxBps *= 1.5;
                if (bps > maxBps) {
                    String toolInfo = getToolInfo(player);
                    String pattern = analyzeBreakPattern(breaks);

                    CheckResult prelimResult = CheckResult.failed(
                            ViolationType.NUKER,
                            Math.min(0.98, 0.75 + (bps - maxBps) * 0.05),
                            String.format("[RATE] %.1f blocks/sec (max: %.1f) | Tool: %s | Pattern: %s",
                                    bps, maxBps, toolInfo, pattern)
                    );

                    if (filtering.passesLayer2HeuristicFiltering(player, ViolationType.NUKER, prelimResult)) {
                        return prelimResult;
                    }
                }
            }
        }

        List<BlockBreakRecord> recentBreakList = getRecentBreaks(breaks, 600);
        if (recentBreakList.size() >= 6 && hasMultiDirectionBreaks(player, recentBreakList)) {
            double avgSpread = calculateAverageSpread(recentBreakList);

            double spreadThreshold = 6.0;
            int eff = getEffLevel(player);
            int hl = getHasteLevel(player);
            if (eff >= 3) spreadThreshold += 2.0;
            if (hl > 0) spreadThreshold += 2.0;
            if (instamine) spreadThreshold += 3.0;

            if (avgSpread > spreadThreshold) {
                String toolInfo = getToolInfo(player);
                double bps = recentBreakList.size() / 0.6;
                String pattern = analyzeBreakPattern(recentBreakList);

                CheckResult prelimResult = CheckResult.failed(
                        ViolationType.NUKER,
                        Math.min(1.0, 0.70 + (avgSpread - spreadThreshold) * 0.06),
                        String.format("[SPREAD] Avg spread: %.1f blocks | Tool: %s | BPS: %.1f | Blocks: %d | Pattern: %s",
                                avgSpread, toolInfo, bps, recentBreakList.size(), pattern)
                );

                if (!filtering.passesLayer2HeuristicFiltering(player, ViolationType.NUKER, prelimResult)) {
                    return CheckResult.passed();
                }

                return prelimResult;
            }
        }

        return CheckResult.passed();
    }

    private boolean hasMultiDirectionBreaks(Player player, List<BlockBreakRecord> breaks) {
        if (breaks.size() < 3) return false;

        Location eye = player.getEyeLocation();
        org.bukkit.util.Vector facing = eye.getDirection().normalize();

        int behind = 0;
        int side = 0;

        for (BlockBreakRecord record : breaks) {
            if (record.location.getWorld() != eye.getWorld()) continue;
            org.bukkit.util.Vector toBlock = record.location.toVector()
                .add(new org.bukkit.util.Vector(0.5, 0.5, 0.5))
                .subtract(eye.toVector()).normalize();

            double dot = facing.dot(toBlock);
            if (dot < -0.2) behind++;
            if (Math.abs(dot) < 0.3) side++;
        }

        return behind >= 2 || (side >= 3 && behind >= 1);
    }

    private int countSimultaneousBreaks(List<BlockBreakRecord> breaks) {
        Map<Long, Integer> breaksPerTick = new HashMap<>();

        for (BlockBreakRecord record : breaks) {
            long tick = record.timestamp / 50;
            breaksPerTick.put(tick, breaksPerTick.getOrDefault(tick, 0) + 1);
        }

        return breaksPerTick.values().stream().max(Integer::compareTo).orElse(0);
    }

    private List<BlockBreakRecord> getRecentBreaks(List<BlockBreakRecord> allBreaks, long windowMs) {
        long cutoff = System.currentTimeMillis() - windowMs;
        List<BlockBreakRecord> recent = new ArrayList<>();
        for (BlockBreakRecord record : allBreaks) {
            if (record.timestamp > cutoff) {
                recent.add(record);
            }
        }
        return recent;
    }

    private double calculateAverageSpread(List<BlockBreakRecord> breaks) {
        if (breaks.size() < 2) return 0;

        double totalDistance = 0;
        int comparisons = 0;
        int limit = Math.min(breaks.size(), 30);

        for (int i = 0; i < limit; i++) {
            for (int j = i + 1; j < limit; j++) {
                Location loc1 = breaks.get(i).location;
                Location loc2 = breaks.get(j).location;
                if (loc1.getWorld() != loc2.getWorld()) continue;
                totalDistance += loc1.distance(loc2);
                comparisons++;
            }
        }

        return comparisons > 0 ? totalDistance / comparisons : 0;
    }

    private double getMaxBreakRate(Player player, org.bukkit.block.Block block) {
        double expectedMs = EnhancementTracker.getExpectedBreakTimeMs(player, block);
        if (expectedMs <= 50.0) {
            return 35.0;
        }
        double bps = 1000.0 / expectedMs;
        bps *= 1.05;
        return Math.max(2.0, bps);
    }

    private int getEffLevel(Player player) {
        org.bukkit.inventory.ItemStack tool = player.getInventory().getItemInMainHand();
        if (tool == null || tool.getType() == org.bukkit.Material.AIR) return 0;
        org.bukkit.enchantments.Enchantment effEnch = org.bukkit.enchantments.Enchantment.getByName("EFFICIENCY");
        return effEnch != null ? tool.getEnchantmentLevel(effEnch) : 0;
    }

    private int getHasteLevel(Player player) {
        PotionEffect haste = player.getPotionEffect(PotionEffectType.HASTE);
        return haste != null ? haste.getAmplifier() + 1 : 0;
    }

    private String getToolInfo(Player player) {
        org.bukkit.inventory.ItemStack tool = player.getInventory().getItemInMainHand();
        if (tool == null || tool.getType() == org.bukkit.Material.AIR) {
            return "Hand";
        }

        org.bukkit.enchantments.Enchantment effEnch = org.bukkit.enchantments.Enchantment.getByName("EFFICIENCY");
        int effLevel = 0;
        if (effEnch != null) {
            effLevel = tool.getEnchantmentLevel(effEnch);
        }

        String toolName = tool.getType().name().replace("_", " ");
        if (effLevel > 0) {
            return String.format("%s (Eff:%d)", toolName, effLevel);
        }
        return toolName;
    }

    private String analyzeBreakPattern(List<BlockBreakRecord> breaks) {
        if (breaks.size() < 3) return "Linear";

        Set<Integer> uniqueX = new HashSet<>();
        Set<Integer> uniqueY = new HashSet<>();
        Set<Integer> uniqueZ = new HashSet<>();

        for (BlockBreakRecord record : breaks) {
            uniqueX.add(record.location.getBlockX());
            uniqueY.add(record.location.getBlockY());
            uniqueZ.add(record.location.getBlockZ());
        }

        if (uniqueY.size() == 1 && (uniqueX.size() > 2 || uniqueZ.size() > 2)) {
            return "Horizontal Layer";
        } else if (uniqueX.size() == 1 && uniqueZ.size() == 1) {
            return "Vertical Tunnel";
        } else if (uniqueX.size() > 2 && uniqueY.size() > 2 && uniqueZ.size() > 2) {
            return "3D Sphere/Cube";
        } else if (uniqueX.size() == 1 || uniqueZ.size() == 1) {
            return "Tunnel/Line";
        }

        return "Scattered";
    }

    public void cleanup(UUID playerId) {
        recentBreaks.remove(playerId);
        breakProfile.cleanup(playerId);
    }

    public void cleanupStale() {
        long now = System.currentTimeMillis();
        recentBreaks.entrySet().removeIf(entry -> {
            List<BlockBreakRecord> breaks = entry.getValue();
            breaks.removeIf(record -> now - record.timestamp > 5000);
            return breaks.isEmpty();
        });
    }

    private static class BlockBreakRecord {
        final Location location;
        final long timestamp;

        BlockBreakRecord(Location location, long timestamp) {
            this.location = location;
            this.timestamp = timestamp;
        }
    }
}
