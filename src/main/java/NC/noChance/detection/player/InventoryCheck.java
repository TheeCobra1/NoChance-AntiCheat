package NC.noChance.detection.player;

import NC.noChance.core.*;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class InventoryCheck {
    private final ACConfig config;
    private final Map<UUID, PlayerData> playerDataMap;
    private final LayerFiltering filtering;
    private final Map<UUID, List<Long>> inventoryClicks;

    public InventoryCheck(ACConfig config, Map<UUID, PlayerData> playerDataMap, LayerFiltering filtering) {
        this.config = config;
        this.playerDataMap = playerDataMap;
        this.filtering = filtering;
        this.inventoryClicks = new ConcurrentHashMap<>();
    }

    public CheckResult check(Player player, InventoryClickEvent event) {
        if (!config.isCheckEnabled("inventory")) {
            return CheckResult.passed();
        }

        if (player.getGameMode() == org.bukkit.GameMode.CREATIVE ||
            player.getGameMode() == org.bukkit.GameMode.SPECTATOR) {
            return CheckResult.passed();
        }

        if (player.isDead() || player.isHandRaised()) {
            return CheckResult.passed();
        }

        if (event.isShiftClick() || event.getHotbarButton() >= 0) {
            return CheckResult.passed();
        }

        PlayerData data = playerDataMap.get(player.getUniqueId());
        if (data == null) return CheckResult.passed();

        if (data.isInGracePeriod(config.getGracePeriod())) {
            return CheckResult.passed();
        }

        int ping = filtering.getPing(player);
        if (ping > 350) {
            return CheckResult.passed();
        }

        UUID playerId = player.getUniqueId();
        long now = System.currentTimeMillis();

        List<Long> clicks = inventoryClicks.computeIfAbsent(playerId, k -> java.util.Collections.synchronizedList(new ArrayList<>()));
        clicks.add(now);

        synchronized (clicks) {
            clicks.removeIf(time -> now - time > 1000);
        }

        int maxClicksPerSecond = config.getInventoryMaxClicksPerSecond();
        int clickCount;
        synchronized (clicks) {
            clickCount = clicks.size();
        }
        if (clickCount > maxClicksPerSecond) {
            CheckResult prelimResult = CheckResult.failed(
                    ViolationType.INVENTORY,
                    Math.min(1.0, Math.pow(clickCount / 20.0, 2)),
                    String.format("Inventory clicks too fast: %d clicks/second", clickCount)
            );

            if (!filtering.passesLayer2HeuristicFiltering(player, ViolationType.INVENTORY, prelimResult)) {
                return CheckResult.passed();
            }

            return prelimResult;
        }

        List<Long> intervals = new ArrayList<>();
        synchronized (clicks) {
            if (clicks.size() >= 5) {
                for (int i = 1; i < clicks.size(); i++) {
                    intervals.add(clicks.get(i) - clicks.get(i - 1));
                }
            }
        }
        if (intervals.size() >= 4) {

            double mean = intervals.stream().mapToLong(Long::longValue).average().orElse(0);
            double variance = intervals.stream()
                    .mapToDouble(v -> Math.pow(v - mean, 2))
                    .average()
                    .orElse(0);
            double stdDev = Math.sqrt(variance);

            double baseThreshold = ping > 150 ? 11.0 : 13.0;
            double stdThreshold = baseThreshold + Math.min(20, ping / 8);
            double meanThreshold = ping > 150 ? 45 : 40;
            double minMean = 18.0;
            if (stdDev < stdThreshold && mean < meanThreshold && mean > minMean) {
                CheckResult prelimResult = CheckResult.failed(
                        ViolationType.INVENTORY,
                        Math.min(1.0, (5.0 - stdDev) / 5.0),
                        String.format("Inventory click pattern too consistent: StdDev=%.2fms", stdDev)
                );

                if (!filtering.passesLayer2HeuristicFiltering(player, ViolationType.INVENTORY, prelimResult)) {
                    return CheckResult.passed();
                }

                return prelimResult;
            }
        }

        return CheckResult.passed();
    }

    public void cleanup(UUID playerId) {
        inventoryClicks.remove(playerId);
    }
}
