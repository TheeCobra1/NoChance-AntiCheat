package NC.noChance.core;

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

public class LayerFiltering {
    private final ACConfig config;
    private final Map<UUID, PingHistory> pingHistoryMap;
    private TransactionTracker transactionTracker;
    private static final double EWMA_ALPHA = 0.25;
    private static final double SIGMOID_STEEPNESS = 0.012;
    private static final double ENTROPY_THRESHOLD = 2.2;
    private static final double AUTOCORR_THRESHOLD = 0.82;
    public LayerFiltering(ACConfig config) {
        this.config = config;
        this.pingHistoryMap = new ConcurrentHashMap<>();
    }

    public void setTransactionTracker(TransactionTracker transactionTracker) {
        this.transactionTracker = transactionTracker;
    }

    public boolean passesLayer2HeuristicFiltering(Player player, ViolationType type, CheckResult preliminaryResult) {
        if (!preliminaryResult.isFailed()) return true;

        if (config.isBedrockExempt() && BedrockHelper.isBedrockPlayer(player)) {
            return false;
        }

        switch (type) {
            case FLY:
                return !isLegitimateFlightScenario(player);
            case SPEED:
                return !isLegitimateSpeedScenario(player);
            case NOCLIP:
                return !isLegitimateNoClipScenario(player);
            case PHASE:
                return !isLegitimatePhaseScenario(player);
            case JESUS:
                return !isLegitimateJesusScenario(player);
            case FASTBREAK:
            case FASTPLACE:
            case NUKER:
                return !isLegitimateBlockInteractionScenario(player, type);
            case KILLAURA:
            case KILLAURA_MULTI:
            case KILLAURA_ANGLE:
            case KILLAURA_ROTATION:
            case KILLAURA_PATTERN:
                return !isLegitimateKillAuraScenario(player);
            case NOFALL:
                return !isLegitimateNoFallScenario(player);
            case NOSLOW:
                return !isLegitimateNoSlowScenario(player);
            case SCAFFOLD:
            case STEP:
                return !isLegitimateScaffoldScenario(player);
            case BLINK:
                return !isLegitimateBlinkScenario(player);
            case CRITICALS:
                return !isLegitimateCriticalsScenario(player);
            case AIMASSIST:
            case AIMASSIST_SILENT:
                return !isLegitimateKillAuraScenario(player);
            case GHOSTHAND:
            case INVALIDINTERACT:
                return !isLegitimateInteractScenario(player);
            case PROTOCOL:
                return !isLegitimateProtocolScenario(player);
            case SPEED_GROUND:
            case SPEED_AIR:
            case SPEED_STRAFE:
                return !isLegitimateSpeedScenario(player);
            case FLY_HOVER:
            case FLY_VERTICAL:
            case FLY_GLIDE:
                return !isLegitimateFlightScenario(player);
            case NOSLOW_ITEM:
            case NOSLOW_WEB:
            case NOSLOW_HONEY:
                return !isLegitimateNoSlowScenario(player);
            case SCAFFOLD_BRIDGE:
            case SCAFFOLD_TOWER:
                return !isLegitimateScaffoldScenario(player);
            default:
                return true;
        }
    }

    private boolean isLegitimateFlightScenario(Player player) {
        if (player.isGliding()) return true;
        if (player.isSwimming()) return true;
        if (player.isClimbing()) return true;
        if (player.isFlying() && player.getAllowFlight()) return true;
        if (player.getVehicle() != null) return true;
        PotionEffectType levType = PotionEffectType.getByName("LEVITATION");
        if (levType != null && player.hasPotionEffect(levType)) return true;
        PotionEffectType slowFallType = PotionEffectType.getByName("SLOW_FALLING");
        if (slowFallType != null && player.hasPotionEffect(slowFallType)) return true;
        if (player.isRiptiding()) return true;
        if (!player.hasGravity()) return true;


        Location loc = player.getLocation();
        Block below = loc.clone().subtract(0, 1, 0).getBlock();
        Material belowType = below.getType();
        if (belowType == Material.SLIME_BLOCK || belowType == Material.HONEY_BLOCK) {
            return true;
        }
        if (belowType.name().contains("BED")) return true;

        Block at = loc.getBlock();
        Material atType = at.getType();
        if (atType == Material.COBWEB || atType == Material.POWDER_SNOW) return true;
        if (atType == Material.BUBBLE_COLUMN) return true;
        if (atType.name().contains("WATER") || atType.name().contains("LAVA")) return true;
        if (atType.name().contains("TRAPDOOR") || atType.name().contains("LADDER") || atType.name().contains("SCAFFOLDING")) return true;

        if (isOnLadderOrVine(player)) return true;
        if (isInLiquid(player)) return true;
        if (isOnScaffolding(player)) return true;
        if (isNearHeavyCore(loc)) return true;

        return false;
    }

    private boolean isLegitimateSpeedScenario(Player player) {
        if (player.getVehicle() != null) return true;
        if (player.isRiptiding()) return true;
        if (player.isGliding()) return true;
        if (player.isFlying() && player.getAllowFlight()) return true;

        Location loc = player.getLocation();
        Block below = loc.clone().subtract(0, 1, 0).getBlock();
        Material belowType = below.getType();

        if (belowType == Material.SLIME_BLOCK) return true;
        if (belowType == Material.ICE || belowType == Material.PACKED_ICE ||
            belowType == Material.BLUE_ICE || belowType == Material.FROSTED_ICE) {
            return true;
        }

        PotionEffectType speedType = PotionEffectType.getByName("SPEED");
        if (speedType == null) speedType = PotionEffectType.getByName("SWIFTNESS");
        if (speedType != null && player.hasPotionEffect(speedType)) {
            PotionEffect speedEffect = player.getPotionEffect(speedType);
            if (speedEffect != null && speedEffect.getAmplifier() >= 4) return true;
        }

        PotionEffectType dolphinsType = PotionEffectType.getByName("DOLPHINS_GRACE");
        if (dolphinsType != null && player.hasPotionEffect(dolphinsType)) return true;

        return false;
    }

    private boolean isLegitimateNoClipScenario(Player player) {
        if (player.getGameMode() == org.bukkit.GameMode.SPECTATOR) return true;
        if (player.getGameMode() == org.bukkit.GameMode.CREATIVE) return true;
        if (player.isSwimming()) return true;
        if (player.getVehicle() != null) return true;
        if (player.isGliding()) return true;
        if (player.isFlying() && player.getAllowFlight()) return true;
        if (player.isRiptiding()) return true;

        Location loc = player.getLocation();
        Block block = loc.getBlock();
        Material type = block.getType();

        if (!type.isSolid()) return true;
        if (type == Material.LADDER || type == Material.VINE) return true;
        if (type == Material.SCAFFOLDING) return true;
        if (type == Material.COBWEB) return true;
        if (type == Material.POWDER_SNOW) return true;
        if (type.name().contains("TRAPDOOR")) return true;
        if (type.name().contains("DOOR")) return true;
        if (type.name().contains("FENCE")) return true;
        if (type.name().contains("GATE")) return true;
        if (type.name().contains("SLAB")) return true;
        if (type.name().contains("STAIRS")) return true;
        if (type.name().contains("CARPET")) return true;
        if (type.name().contains("HEAD")) return true;
        if (type.name().contains("SIGN")) return true;
        if (type.name().contains("BED")) return true;

        return false;
    }

    private boolean isLegitimatePhaseScenario(Player player) {
        if (player.getGameMode() == org.bukkit.GameMode.SPECTATOR) return true;
        if (player.getGameMode() == org.bukkit.GameMode.CREATIVE) return true;
        if (player.isSwimming()) return true;
        if (player.getVehicle() != null) return true;
        if (player.isGliding()) return true;
        if (player.isFlying() && player.getAllowFlight()) return true;
        if (player.isRiptiding()) return true;

        Location loc = player.getLocation();
        Block block = loc.getBlock();
        Material type = block.getType();

        if (!type.isSolid()) return true;
        if (type == Material.LADDER || type == Material.VINE) return true;
        if (type == Material.SCAFFOLDING) return true;
        if (type == Material.COBWEB) return true;
        if (type == Material.POWDER_SNOW) return true;
        if (type == Material.HONEY_BLOCK) return true;
        if (type == Material.SLIME_BLOCK) return true;
        if (type.name().contains("TRAPDOOR")) return true;
        if (type.name().contains("DOOR")) return true;
        if (type.name().contains("FENCE")) return true;
        if (type.name().contains("GATE")) return true;
        if (type.name().contains("SLAB")) return true;
        if (type.name().contains("STAIRS")) return true;
        if (type.name().contains("BED")) return true;
        if (type.name().contains("PISTON")) return true;

        return false;
    }

    private boolean isLegitimateJesusScenario(Player player) {
        Location loc = player.getLocation();
        Block below = loc.clone().subtract(0, 0.1, 0).getBlock();

        if (below.getType() == Material.LILY_PAD) return true;
        if (player.getVehicle() != null) return true;
        if (player.isSwimming()) return true;
        if (player.isInWater()) return true;
        PotionEffectType dolphinsGraceType = PotionEffectType.getByName("DOLPHINS_GRACE");
        if (dolphinsGraceType != null && player.hasPotionEffect(dolphinsGraceType)) return true;

        if (player.getInventory().getBoots() != null &&
                player.getInventory().getBoots().getEnchantments().containsKey(
                        org.bukkit.enchantments.Enchantment.FROST_WALKER)) {
            return true;
        }

        Block feetBlock = loc.getBlock();
        if (feetBlock.getType().name().contains("BUBBLE")) return true;

        Block headBlock = loc.clone().add(0, 1, 0).getBlock();
        if (headBlock.getType() == Material.WATER || headBlock.getType().name().contains("WATER")) {
            return true;
        }

        return false;
    }

    private boolean isLegitimateBlockInteractionScenario(Player player, ViolationType type) {
        if (player.getGameMode() == org.bukkit.GameMode.CREATIVE) return true;

        if (type == ViolationType.FASTBREAK) {
            PotionEffectType hasteType = PotionEffectType.getByName("HASTE");
            if (hasteType == null) hasteType = PotionEffectType.getByName("FAST_DIGGING");
            if (hasteType != null && player.hasPotionEffect(hasteType)) {
                PotionEffect haste = player.getPotionEffect(hasteType);
                if (haste != null && haste.getAmplifier() >= 1) return true;
            }
            PotionEffectType conduitType = PotionEffectType.getByName("CONDUIT_POWER");
            if (conduitType != null && player.hasPotionEffect(conduitType)) return true;

            org.bukkit.inventory.ItemStack tool = player.getInventory().getItemInMainHand();
            if (tool != null && tool.getType() != Material.AIR) {
                String toolName = tool.getType().name();
                if (toolName.contains("NETHERITE") || toolName.contains("GOLDEN")) {
                    org.bukkit.enchantments.Enchantment effEnch = org.bukkit.enchantments.Enchantment.getByName("EFFICIENCY");
                    if (effEnch == null) effEnch = org.bukkit.enchantments.Enchantment.getByName("DIG_SPEED");
                    if (effEnch != null && tool.getEnchantmentLevel(effEnch) >= 5) return true;
                }
            }
        }

        return false;
    }

    private boolean isLegitimateKillAuraScenario(Player player) {
        if (player.getVehicle() != null) return true;
        if (player.isRiptiding()) return true;
        if (player.isGliding()) return true;
        if (player.isSwimming()) return true;
        if (player.getGameMode() == org.bukkit.GameMode.CREATIVE) return true;
        if (player.getGameMode() == org.bukkit.GameMode.SPECTATOR) return true;

        return false;
    }

    private boolean isLegitimateProtocolScenario(Player player) {
        if (player.getVehicle() != null) return true;
        if (player.isRiptiding()) return true;
        if (player.isGliding()) return true;
        if (player.getGameMode() == org.bukkit.GameMode.CREATIVE) return true;
        if (player.getGameMode() == org.bukkit.GameMode.SPECTATOR) return true;
        if (BedrockHelper.isBedrockPlayer(player)) return true;

        return false;
    }

    private boolean isLegitimateNoFallScenario(Player player) {
        if (player.getVehicle() != null) return true;
        if (player.isGliding()) return true;
        if (player.isRiptiding()) return true;
        if (player.isSwimming()) return true;
        if (player.isClimbing()) return true;
        if (player.isFlying() && player.getAllowFlight()) return true;

        PotionEffectType slowFallType = PotionEffectType.getByName("SLOW_FALLING");
        if (slowFallType != null && player.hasPotionEffect(slowFallType)) return true;
        PotionEffectType levType = PotionEffectType.getByName("LEVITATION");
        if (levType != null && player.hasPotionEffect(levType)) return true;

        Location loc = player.getLocation();
        Material below = loc.clone().subtract(0, 1, 0).getBlock().getType();
        if (below == Material.SLIME_BLOCK || below == Material.HAY_BLOCK) return true;
        if (below.name().contains("BED")) return true;

        Material at = loc.getBlock().getType();
        if (at == Material.COBWEB || at == Material.POWDER_SNOW) return true;
        if (at == Material.WATER || at.name().contains("WATER")) return true;
        if (isNearHeavyCore(loc)) return true;

        return false;
    }

    private boolean isLegitimateNoSlowScenario(Player player) {
        if (player.isFlying() && player.getAllowFlight()) return true;
        if (player.getGameMode() == org.bukkit.GameMode.CREATIVE) return true;
        if (player.getGameMode() == org.bukkit.GameMode.SPECTATOR) return true;
        if (player.getVehicle() != null) return true;
        if (player.isGliding()) return true;
        if (player.isSwimming()) return true;
        if (player.isRiptiding()) return true;

        return false;
    }

    private boolean isLegitimateScaffoldScenario(Player player) {
        if (player.getGameMode() == org.bukkit.GameMode.CREATIVE) return true;
        if (player.getVehicle() != null) return true;
        if (player.isFlying() && player.getAllowFlight()) return true;

        return false;
    }

    private boolean isLegitimateBlinkScenario(Player player) {
        if (player.getVehicle() != null) return true;
        if (player.isFlying() && player.getAllowFlight()) return true;
        if (player.isGliding()) return true;

        return false;
    }

    private boolean isLegitimateInteractScenario(Player player) {
        if (player.getGameMode() == org.bukkit.GameMode.CREATIVE) return true;
        if (player.getGameMode() == org.bukkit.GameMode.SPECTATOR) return true;

        return false;
    }

    private boolean isLegitimateCriticalsScenario(Player player) {
        if (player.getVehicle() != null) return true;
        if (player.isGliding()) return true;
        if (player.isSwimming()) return true;
        if (player.isClimbing()) return true;
        if (player.isInWater()) return true;
        if (isHoldingMace(player)) return true;

        Location loc = player.getLocation();
        Material type = loc.getBlock().getType();
        if (type == Material.COBWEB) return true;

        return false;
    }

    public double getLagCompensation(Player player) {
        UUID playerId = player.getUniqueId();
        int currentPing = getPing(player);

        PingHistory history = pingHistoryMap.computeIfAbsent(playerId, k -> new PingHistory());
        history.addPing(currentPing);

        double avgPing = history.getAveragePing();
        double jitter = history.getJitter();
        double tps = getCurrentTPS();

        double pingFactor = calculateSigmoidPingPenalty(avgPing);
        double jitterFactor = Math.min(0.2, jitter / 1000.0);
        double tpsFactor = (1.0 - (tps / 20.0)) * 0.4;
        double baseTolerance = 0.05;

        double versionTolerance = ViaHelper.getVersionTolerance(player);

        double total = pingFactor + jitterFactor + tpsFactor + baseTolerance + versionTolerance;

        if (!config.isBedrockExempt() && BedrockHelper.isBedrockPlayer(player) && config.isBedrockRelaxed()) {
            total *= Math.min(1.3, config.getBedrockToleranceMultiplier());
        }

        int pingHighThreshold = config.getPingHighThreshold();
        double pingMaxMult = config.getPingMaxMultiplier();
        if (currentPing > pingHighThreshold) {
            double pingScale = Math.min(pingMaxMult, 1.0 + (currentPing - pingHighThreshold) * 0.003);
            total *= pingScale;
        }

        return total;
    }

    private double calculateSigmoidPingPenalty(double ping) {
        return 1.0 / (1.0 + Math.exp(-SIGMOID_STEEPNESS * (ping - 150)));
    }

    private double getCurrentTPS() {
        return TPSCache.getTPS();
    }

    public int getPing(Player player) {
        if (transactionTracker != null && transactionTracker.hasFreshData(player)) {
            long rttMs = transactionTracker.getRoundTripMillis(player);
            if (rttMs >= 0 && rttMs < 2000) {
                return (int) rttMs;
            }
        }
        return Math.max(0, player.getPing());
    }

    public double calculateShannonEntropy(List<Long> timings) {
        if (timings.size() < 5) return 0.0;

        Map<Long, Integer> bucketCounts = new HashMap<>();
        long minVal = Collections.min(timings);
        long maxVal = Collections.max(timings);
        long bucketSize = Math.max(1, (maxVal - minVal) / 10);

        for (Long timing : timings) {
            long bucket = (timing - minVal) / bucketSize;
            bucketCounts.put(bucket, bucketCounts.getOrDefault(bucket, 0) + 1);
        }

        double entropy = 0.0;
        int total = timings.size();

        for (int count : bucketCounts.values()) {
            if (count > 0) {
                double probability = (double) count / total;
                entropy -= probability * (Math.log(probability) / Math.log(2));
            }
        }

        return entropy;
    }

    public double calculateAutocorrelation(List<Double> values, int lag) {
        if (values.size() < lag + 10) return 0.0;

        int n = values.size();
        double mean = values.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);

        double c0 = 0.0;
        for (int i = 0; i < n; i++) {
            c0 += Math.pow(values.get(i) - mean, 2);
        }

        double cLag = 0.0;
        for (int i = 0; i < n - lag; i++) {
            cLag += (values.get(i) - mean) * (values.get(i + lag) - mean);
        }

        if (c0 == 0) return 0.0;
        return cLag / c0;
    }

    public MultiLagResult calculateMultiLagAutocorrelation(List<Double> values, int maxLag) {
        if (values.size() < maxLag + 10) return new MultiLagResult(new double[0], -1, 0);

        double[] correlations = new double[maxLag];
        int peakLag = -1;
        double peakValue = 0;

        for (int lag = 1; lag <= maxLag; lag++) {
            correlations[lag - 1] = calculateAutocorrelation(values, lag);
            if (correlations[lag - 1] > peakValue) {
                peakValue = correlations[lag - 1];
                peakLag = lag;
            }
        }

        return new MultiLagResult(correlations, peakLag, peakValue);
    }

    public static class MultiLagResult {
        public final double[] correlations;
        public final int peakLag;
        public final double peakValue;

        public MultiLagResult(double[] correlations, int peakLag, double peakValue) {
            this.correlations = correlations;
            this.peakLag = peakLag;
            this.peakValue = peakValue;
        }

        public boolean hasSignificantPeriodicity() {
            return peakValue > 0.70;
        }
    }

    public boolean detectMachinePattern(List<Long> timestamps) {
        if (timestamps.size() < 12) return false;

        List<Long> intervals = new ArrayList<>();
        for (int i = 1; i < timestamps.size(); i++) {
            intervals.add(timestamps.get(i) - timestamps.get(i - 1));
        }

        double entropy = calculateShannonEntropy(intervals);
        if (entropy < ENTROPY_THRESHOLD && intervals.size() >= 15) {
            return true;
        }

        long mean = intervals.stream().mapToLong(Long::longValue).sum() / intervals.size();
        double variance = intervals.stream()
                .mapToDouble(v -> Math.pow(v - mean, 2))
                .average()
                .orElse(0.0);
        double stdDev = Math.sqrt(variance);
        double cv = mean > 0 ? stdDev / mean : 0.0;

        if (cv < 0.10 && intervals.size() >= 15) {
            return true;
        }

        List<Double> doubleIntervals = new ArrayList<>();
        for (Long interval : intervals) {
            doubleIntervals.add(interval.doubleValue());
        }

        double autocorr1 = calculateAutocorrelation(doubleIntervals, 1);
        if (autocorr1 > AUTOCORR_THRESHOLD && intervals.size() >= 12) {
            return true;
        }

        MultiLagResult multiLag = calculateMultiLagAutocorrelation(doubleIntervals, 5);
        if (multiLag.hasSignificantPeriodicity() && intervals.size() >= 12) {
            return true;
        }

        boolean hasOutliers = detectOutliersIQR(doubleIntervals);
        if (!hasOutliers && stdDev < 3.5 && intervals.size() >= 15) {
            return true;
        }

        return false;
    }

    public boolean detectOutliersIQR(List<Double> values) {
        if (values.size() < 4) return false;

        List<Double> sorted = new ArrayList<>(values);
        Collections.sort(sorted);

        int n = sorted.size();
        double q1 = sorted.get(n / 4);
        double q3 = sorted.get(3 * n / 4);
        double iqr = q3 - q1;

        double lowerBound = q1 - 1.5 * iqr;
        double upperBound = q3 + 1.5 * iqr;

        for (Double value : values) {
            if (value < lowerBound || value > upperBound) {
                return true;
            }
        }

        return false;
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

    private boolean isNearHeavyCore(Location loc) {
        try {
            Material heavyCore = Material.valueOf("HEAVY_CORE");
            Block below = loc.clone().subtract(0, 1, 0).getBlock();
            if (below.getType() == heavyCore) return true;
            Block at = loc.getBlock();
            return at.getType() == heavyCore;
        } catch (IllegalArgumentException e) {
            Logger.getLogger("NoChance").log(Level.WARNING, "HEAVY_CORE material not available on this server version", e);
            return false;
        }
    }

    private boolean isOnLadderOrVine(Player player) {
        Location loc = player.getLocation();
        Material type = loc.getBlock().getType();
        return type == Material.LADDER || type == Material.VINE;
    }

    private boolean isInLiquid(Player player) {
        Location loc = player.getLocation();
        Material type = loc.getBlock().getType();
        return type == Material.WATER || type == Material.LAVA ||
                type.name().contains("WATER") || type.name().contains("LAVA");
    }

    private boolean isOnScaffolding(Player player) {
        Location loc = player.getLocation();
        return loc.getBlock().getType() == Material.SCAFFOLDING;
    }

    public boolean isRealisticHumanBehavior(PlayerData data) {
        if (data == null || !data.isBaselineEstablished()) return true;

        double cps = data.getAverageCPS();
        double rotationSpeed = data.getAverageRotationSpeed();
        double accuracy = data.getAverageAccuracy();

        PlayerData.SkillLevel skill = data.getSkillLevel();

        boolean cpsInRange = cps >= skill.minCPS && cps <= skill.maxCPS;
        boolean rotationInRange = rotationSpeed >= skill.minRotationSpeed &&
                rotationSpeed <= skill.maxRotationSpeed;
        boolean accuracyInRange = accuracy >= skill.minAccuracy && accuracy <= skill.maxAccuracy;

        return cpsInRange && rotationInRange && accuracyInRange;
    }

    public void cleanup(UUID playerId) {
        pingHistoryMap.remove(playerId);
    }

    private static class PingHistory {
        private final Deque<Integer> pings;
        private double ewmaPing;
        private boolean initialized;

        public PingHistory() {
            this.pings = new ArrayDeque<>(50);
            this.ewmaPing = 0.0;
            this.initialized = false;
        }

        public void addPing(int ping) {
            pings.addLast(ping);
            if (pings.size() > 50) {
                pings.pollFirst();
            }

            if (!initialized) {
                ewmaPing = ping;
                initialized = true;
            } else {
                ewmaPing = EWMA_ALPHA * ping + (1.0 - EWMA_ALPHA) * ewmaPing;
            }
        }

        public double getAveragePing() {
            return ewmaPing;
        }

        public double getJitter() {
            if (pings.size() < 2) return 0.0;

            List<Integer> pingList = new ArrayList<>(pings);
            List<Integer> deltas = new ArrayList<>();

            for (int i = 1; i < pingList.size(); i++) {
                deltas.add(Math.abs(pingList.get(i) - pingList.get(i - 1)));
            }

            double mean = deltas.stream().mapToInt(Integer::intValue).average().orElse(0.0);
            double variance = deltas.stream()
                    .mapToDouble(v -> Math.pow(v - mean, 2))
                    .average()
                    .orElse(0.0);

            return Math.sqrt(variance);
        }
    }
}
