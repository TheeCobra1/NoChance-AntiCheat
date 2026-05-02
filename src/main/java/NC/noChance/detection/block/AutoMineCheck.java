package NC.noChance.detection.block;

import NC.noChance.core.*;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class AutoMineCheck {
    private final ACConfig config;
    private final Map<UUID, PlayerData> playerDataMap;
    private final LayerFiltering filtering;
    private final Map<UUID, Deque<BreakRec>> recent;

    private static final long CHAIN_WINDOW_MS = 2_000;
    private static final double ADJACENT_DIST_SQ = 3.0 + 0.001;

    private static final Set<String> ORE_NAMES = new HashSet<>(Arrays.asList(
            "COAL_ORE", "IRON_ORE", "GOLD_ORE", "DIAMOND_ORE", "EMERALD_ORE",
            "REDSTONE_ORE", "LAPIS_ORE", "COPPER_ORE", "NETHER_QUARTZ_ORE", "NETHER_GOLD_ORE",
            "DEEPSLATE_COAL_ORE", "DEEPSLATE_IRON_ORE", "DEEPSLATE_GOLD_ORE", "DEEPSLATE_DIAMOND_ORE",
            "DEEPSLATE_EMERALD_ORE", "DEEPSLATE_REDSTONE_ORE", "DEEPSLATE_LAPIS_ORE", "DEEPSLATE_COPPER_ORE",
            "ANCIENT_DEBRIS"
    ));

    public AutoMineCheck(ACConfig config, Map<UUID, PlayerData> playerDataMap, LayerFiltering filtering) {
        this.config = config;
        this.playerDataMap = playerDataMap;
        this.filtering = filtering;
        this.recent = new ConcurrentHashMap<>();
    }

    public CheckResult onBlockBreak(Player player, Location loc, Material type) {
        if (!config.isCheckEnabled("automine")) return CheckResult.passed();
        if (type == null || !ORE_NAMES.contains(type.name())) {
            return CheckResult.passed();
        }
        UUID id = player.getUniqueId();
        Deque<BreakRec> dq = recent.computeIfAbsent(id, k -> new ArrayDeque<>());
        long now = System.currentTimeMillis();

        while (!dq.isEmpty() && now - dq.peekFirst().time > CHAIN_WINDOW_MS) {
            dq.pollFirst();
        }
        dq.addLast(new BreakRec(loc.getBlockX(), loc.getBlockY(), loc.getBlockZ(), now));

        int threshold = config.getAutoMineThreshold();
        if (dq.size() < threshold) return CheckResult.passed();

        int chain = longestConnectedChain(dq);
        if (chain >= threshold) {
            CheckResult r = CheckResult.failed(ViolationType.NUKER, 0.72,
                    "AutoMine: ore chain length=" + chain + " in " + CHAIN_WINDOW_MS + "ms");
            if (filtering.passesLayer2HeuristicFiltering(player, ViolationType.NUKER, r)) {
                return r;
            }
        }
        return CheckResult.passed();
    }

    private int longestConnectedChain(Deque<BreakRec> dq) {
        List<BreakRec> list = new ArrayList<>(dq);
        int n = list.size();
        Set<Integer> visited = new HashSet<>(n * 2);
        int best = 0;
        for (int i = 0; i < n; i++) {
            if (visited.contains(i)) continue;
            int size = bfs(list, i, visited);
            if (size > best) best = size;
            if (best >= 3) return best;
        }
        return best;
    }

    private int bfs(List<BreakRec> list, int start, Set<Integer> visited) {
        Deque<Integer> queue = new ArrayDeque<>();
        queue.add(start);
        visited.add(start);
        int count = 0;
        while (!queue.isEmpty()) {
            int cur = queue.poll();
            count++;
            if (count >= 3) return count;
            BreakRec a = list.get(cur);
            for (int j = 0; j < list.size(); j++) {
                if (visited.contains(j)) continue;
                BreakRec b = list.get(j);
                int dx = a.x - b.x, dy = a.y - b.y, dz = a.z - b.z;
                double d2 = dx * dx + dy * dy + dz * dz;
                if (d2 <= ADJACENT_DIST_SQ) {
                    visited.add(j);
                    queue.add(j);
                }
            }
        }
        return count;
    }

    public void cleanup(UUID playerId) {
        recent.remove(playerId);
    }

    public void cleanupStale() {
        long now = System.currentTimeMillis();
        for (Deque<BreakRec> dq : recent.values()) {
            while (!dq.isEmpty() && now - dq.peekFirst().time > CHAIN_WINDOW_MS) {
                dq.pollFirst();
            }
        }
    }

    private static class BreakRec {
        final int x, y, z;
        final long time;
        BreakRec(int x, int y, int z, long time) {
            this.x = x; this.y = y; this.z = z; this.time = time;
        }
    }
}
