package NC.noChance.detection.block;

import NC.noChance.core.*;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class XRayCheck {
    private final ACConfig config;
    private final Map<UUID, PlayerData> playerDataMap;
    private final LayerFiltering filtering;
    private final Map<UUID, Stats> stats;

    private static final Set<String> RARE_ORES = new HashSet<>(Arrays.asList(
            "DIAMOND_ORE", "DEEPSLATE_DIAMOND_ORE", "ANCIENT_DEBRIS"
    ));
    private static final Set<String> STONE_TYPES = new HashSet<>(Arrays.asList(
            "STONE", "DEEPSLATE", "COBBLED_DEEPSLATE", "TUFF", "GRANITE", "DIORITE", "ANDESITE",
            "NETHERRACK", "BLACKSTONE", "BASALT", "END_STONE"
    ));

    public XRayCheck(ACConfig config, Map<UUID, PlayerData> playerDataMap, LayerFiltering filtering) {
        this.config = config;
        this.playerDataMap = playerDataMap;
        this.filtering = filtering;
        this.stats = new ConcurrentHashMap<>();
    }

    public CheckResult onBlockBreak(Player player, Material type) {
        if (!config.isCheckEnabled("xray")) return CheckResult.passed();
        if (type == null) return CheckResult.passed();
        UUID id = player.getUniqueId();
        Stats s = stats.computeIfAbsent(id, k -> new Stats());
        String name = type.name();
        boolean rare = RARE_ORES.contains(name);
        boolean stone = STONE_TYPES.contains(name);

        if (rare) s.rare++;
        if (stone) s.stone++;

        int minStone = config.getXRayMinStone();
        double ratioThreshold = config.getXRayRatioThreshold();

        if (s.stone < minStone) return CheckResult.passed();
        double ratio = s.rare / (double) s.stone;
        if (ratio < ratioThreshold) return CheckResult.passed();
        long now = System.currentTimeMillis();
        if (s.flagged && now - s.flagTime < 300_000L) return CheckResult.passed();
        if (s.flagged && now - s.flagTime >= 300_000L) {
            s.flagged = false;
        }

        s.flagged = true;
        s.flagTime = now;
        CheckResult r = CheckResult.failed(ViolationType.NUKER, 0.72,
                String.format("XRay: rare=%d stone=%d ratio=%.3f (>%.2f)", s.rare, s.stone, ratio, ratioThreshold));
        if (filtering.passesLayer2HeuristicFiltering(player, ViolationType.NUKER, r)) {
            return r;
        }
        return CheckResult.passed();
    }

    public void cleanup(UUID playerId) {
        stats.remove(playerId);
    }

    public void cleanupStale() {
    }

    private static class Stats {
        int rare = 0;
        int stone = 0;
        boolean flagged = false;
        long flagTime = 0L;
    }
}
