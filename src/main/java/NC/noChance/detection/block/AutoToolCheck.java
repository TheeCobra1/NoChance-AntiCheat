package NC.noChance.detection.block;

import NC.noChance.core.*;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class AutoToolCheck {
    private final ACConfig config;
    private final Map<UUID, PlayerData> playerDataMap;
    private final LayerFiltering filtering;
    private final Map<UUID, State> states;

    private static final long SWAP_BREAK_WINDOW_MS = 50;
    private static final long DECAY_WINDOW_MS = 10_000;

    public AutoToolCheck(ACConfig config, Map<UUID, PlayerData> playerDataMap, LayerFiltering filtering) {
        this.config = config;
        this.playerDataMap = playerDataMap;
        this.filtering = filtering;
        this.states = new ConcurrentHashMap<>();
    }

    public void onSlotChange(UUID playerId) {
        if (!config.isCheckEnabled("autotool")) return;
        State s = states.computeIfAbsent(playerId, k -> new State());
        s.lastSwapTime = System.currentTimeMillis();
    }

    public CheckResult onBlockBreak(Player player) {
        if (!config.isCheckEnabled("autotool")) {
            return CheckResult.passed();
        }
        UUID id = player.getUniqueId();
        State s = states.computeIfAbsent(id, k -> new State());
        long now = System.currentTimeMillis();

        long swapWindow = SWAP_BREAK_WINDOW_MS + Math.min(150, player.getPing());
        if (s.lastSwapTime > 0 && now - s.lastSwapTime <= swapWindow) {
            s.suspectTimes.addLast(now);
        }
        while (!s.suspectTimes.isEmpty() && now - s.suspectTimes.peekFirst() > DECAY_WINDOW_MS) {
            s.suspectTimes.pollFirst();
        }
        if (s.suspectTimes.isEmpty()) {
            s.lastSwapTime = 0;
        }

        int threshold = config.getAutoToolThreshold();
        if (s.suspectTimes.size() >= threshold) {
            CheckResult r = CheckResult.failed(ViolationType.FASTBREAK, 0.78,
                    "AutoTool: " + s.suspectTimes.size() + " swap+break events in " + DECAY_WINDOW_MS + "ms");
            if (filtering.passesLayer2HeuristicFiltering(player, ViolationType.FASTBREAK, r)) {
                return r;
            }
        }
        return CheckResult.passed();
    }

    public void cleanup(UUID playerId) {
        states.remove(playerId);
    }

    public void cleanupStale() {
        long now = System.currentTimeMillis();
        for (State s : states.values()) {
            while (!s.suspectTimes.isEmpty() && now - s.suspectTimes.peekFirst() > DECAY_WINDOW_MS) {
                s.suspectTimes.pollFirst();
            }
        }
    }

    private static class State {
        long lastSwapTime = 0;
        final Deque<Long> suspectTimes = new ArrayDeque<>();
    }
}
