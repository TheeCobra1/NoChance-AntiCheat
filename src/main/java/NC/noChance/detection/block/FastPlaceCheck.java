package NC.noChance.detection.block;

import NC.noChance.core.*;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class FastPlaceCheck {
    private final ACConfig config;
    private final Map<UUID, PlayerData> playerDataMap;
    private final LayerFiltering filtering;

    public FastPlaceCheck(ACConfig config, Map<UUID, PlayerData> playerDataMap, LayerFiltering filtering) {
        this.config = config;
        this.playerDataMap = playerDataMap;
        this.filtering = filtering;
    }

    public CheckResult check(Player player, Location blockLocation) {
        if (!config.isCheckEnabled("fastplace")) {
            return CheckResult.passed();
        }

        PlayerData data = playerDataMap.get(player.getUniqueId());
        if (data == null) return CheckResult.passed();

        if (data.isInGracePeriod(config.getGracePeriod())) {
            return CheckResult.passed();
        }

        if (player.getGameMode() == org.bukkit.GameMode.CREATIVE) {
            return CheckResult.passed();
        }

        Material placed = blockLocation.getBlock().getType();
        if (placed == Material.SCAFFOLDING) {
            return CheckResult.passed();
        }

        long now = System.currentTimeMillis();
        long lastPlaceTime = data.getLastBlockPlaceTime();

        if (lastPlaceTime == 0) {
            data.setLastBlockPlaceTime(now);
            data.setLastBlockPlace(blockLocation);
            data.addBlockPlaceLocation(blockLocation);
            return CheckResult.passed();
        }

        long interval = now - lastPlaceTime;
        data.setLastBlockPlaceTime(now);
        data.addBlockPlaceInterval(interval);
        data.setLastBlockPlace(blockLocation);
        data.addBlockPlaceLocation(blockLocation);

        int ping = filtering.getPing(player);

        int currentTick = (int) ((now / 50) % Integer.MAX_VALUE);
        if (currentTick != data.getCurrentTick()) {
            data.setCurrentTick(currentTick);
            data.resetBlocksPlacedInTick();
        }

        data.incrementBlocksPlacedInTick();

        double tps = getCurrentTPS();
        int maxBlocksPerTick = config.getFastPlaceMaxBlocksPerTick();
        if (tps < 19.0) {
            maxBlocksPerTick = (int) Math.ceil(maxBlocksPerTick * (20.0 / tps));
        }
        if (ping > 150) {
            maxBlocksPerTick = (int) Math.ceil(maxBlocksPerTick * 1.2);
        }

        if (data.getBlocksPlacedInTick() > maxBlocksPerTick) {
            CheckResult prelimResult = CheckResult.failed(
                    ViolationType.FASTPLACE,
                    Math.min(1.0, (double) data.getBlocksPlacedInTick() / maxBlocksPerTick),
                    String.format("Placed %d blocks in single tick (max: %d, TPS: %.1f)",
                            data.getBlocksPlacedInTick(), maxBlocksPerTick, tps)
            );

            if (!filtering.passesLayer2HeuristicFiltering(player, ViolationType.FASTPLACE, prelimResult)) {
                return CheckResult.passed();
            }

            return prelimResult;
        }

        Deque<PlayerData.LocationData> placements = data.getBlockPlaceLocations();
        if (placements.size() >= 5) {
            long oneSecondAgo = now - 1000;
            int placesInLastSecond = 0;

            for (PlayerData.LocationData placement : placements) {
                if (placement.timestamp > oneSecondAgo) {
                    placesInLastSecond++;
                }
            }

            int maxPlacesPerSecond = config.getFastPlaceMaxBlocksPerSecond();
            if (tps < 19.0) {
                maxPlacesPerSecond = (int) Math.ceil(maxPlacesPerSecond * (20.0 / tps));
            }
            if (ping > 150) {
                maxPlacesPerSecond = (int) Math.ceil(maxPlacesPerSecond * 1.2);
            }

            if (placesInLastSecond > maxPlacesPerSecond) {
                CheckResult prelimResult = CheckResult.failed(
                        ViolationType.FASTPLACE,
                        Math.min(1.0, (double) placesInLastSecond / maxPlacesPerSecond),
                        String.format("Placed %d blocks/second (max: %d, TPS: %.1f)",
                                placesInLastSecond, maxPlacesPerSecond, tps)
                );

                if (!filtering.passesLayer2HeuristicFiltering(player, ViolationType.FASTPLACE, prelimResult)) {
                    return CheckResult.passed();
                }

                return prelimResult;
            }
        }

        Deque<Long> intervals = data.getBlockPlaceIntervals();
        if (intervals.size() >= 8) {
            List<Long> intervalList = new ArrayList<>(intervals);
            List<Long> recentIntervals = new ArrayList<>();
            for (int i = Math.max(0, intervalList.size() - 12); i < intervalList.size(); i++) {
                recentIntervals.add(intervalList.get(i));
            }

            double mean = recentIntervals.stream().mapToLong(Long::longValue).average().orElse(0);
            double variance = recentIntervals.stream()
                    .mapToDouble(v -> Math.pow(v - mean, 2))
                    .average()
                    .orElse(0);
            double stdDev = Math.sqrt(variance);

            int minInterval = config.getFastPlaceMinIntervalMs();
            if (ping > 200) minInterval = (int)(minInterval * 0.7);
            else if (ping > 100) minInterval = (int)(minInterval * 0.85);

            double stdDevThreshold = 8.0;
            if (ping > 200) stdDevThreshold = 11.0;
            else if (ping > 150) stdDevThreshold = 10.0;
            else if (ping > 100) stdDevThreshold = 9.0;
            stdDevThreshold += ping / 200.0;

            if (stdDev < stdDevThreshold && mean < minInterval && mean > 0 && recentIntervals.size() >= 10) {
                CheckResult prelimResult = CheckResult.failed(
                        ViolationType.FASTPLACE,
                        Math.min(0.92, (minInterval - mean) / minInterval),
                        String.format("Placement too consistent: Mean=%.1fms, StdDev=%.2fms (min: %dms)",
                                mean, stdDev, minInterval)
                );

                if (!filtering.passesLayer2HeuristicFiltering(player, ViolationType.FASTPLACE, prelimResult)) {
                    return CheckResult.passed();
                }

                return prelimResult;
            }
        }

        return CheckResult.passed();
    }

    private double getCurrentTPS() {
        return TPSCache.getTPS();
    }

    public void cleanup(UUID playerId) {
        PlayerData data = playerDataMap.get(playerId);
        if (data != null) {
            data.setLastBlockPlaceTime(0);
            data.resetBlocksPlacedInTick();
        }
    }
}
