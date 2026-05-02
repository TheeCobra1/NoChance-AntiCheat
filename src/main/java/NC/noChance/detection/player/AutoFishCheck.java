package NC.noChance.detection.player;

import NC.noChance.core.*;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerFishEvent;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class AutoFishCheck {
    private final ACConfig config;
    private final Map<UUID, PlayerData> playerDataMap;
    private final LayerFiltering filtering;
    private final Map<UUID, State> states;

    private static final long FAST_REEL_MS = 100;
    private static final long DECAY_MS = 30_000;
    private static final long MIN_CAST_TO_BITE_MS = 1500;

    public AutoFishCheck(ACConfig config, Map<UUID, PlayerData> playerDataMap, LayerFiltering filtering) {
        this.config = config;
        this.playerDataMap = playerDataMap;
        this.filtering = filtering;
        this.states = new ConcurrentHashMap<>();
    }

    public CheckResult onFish(Player player, PlayerFishEvent.State fishState) {
        if (!config.isCheckEnabled("autofish")) return CheckResult.passed();
        UUID id = player.getUniqueId();
        State s = states.computeIfAbsent(id, k -> new State());
        long now = System.currentTimeMillis();

        if (fishState == PlayerFishEvent.State.FISHING) {
            s.castTime = now;
            return CheckResult.passed();
        }
        if (fishState == PlayerFishEvent.State.BITE) {
            if (s.castTime > 0 && now - s.castTime < MIN_CAST_TO_BITE_MS) {
                CheckResult r = CheckResult.failed(ViolationType.AUTOCLICKER, 0.82,
                        "AutoFish: bite " + (now - s.castTime) + "ms after cast (min " + MIN_CAST_TO_BITE_MS + "ms)");
                if (filtering.passesLayer2HeuristicFiltering(player, ViolationType.AUTOCLICKER, r)) {
                    s.lastBite = now;
                    return r;
                }
            }
            s.lastBite = now;
            return CheckResult.passed();
        }
        if (fishState == PlayerFishEvent.State.CAUGHT_FISH || fishState == PlayerFishEvent.State.IN_GROUND) {
            if (s.lastBite > 0) {
                long latency = now - s.lastBite;
                s.lastBite = 0;
                if (latency >= 0 && latency < FAST_REEL_MS) {
                    s.fastReels.addLast(now);
                }
            }
            while (!s.fastReels.isEmpty() && now - s.fastReels.peekFirst() > DECAY_MS) {
                s.fastReels.pollFirst();
            }
            int threshold = Math.max(2, config.getAutoFishThreshold());
            if (s.fastReels.size() >= threshold) {
                CheckResult r = CheckResult.failed(ViolationType.AUTOCLICKER, 0.74,
                        "AutoFish: " + s.fastReels.size() + " sub-" + FAST_REEL_MS + "ms reels in " + DECAY_MS + "ms");
                if (filtering.passesLayer2HeuristicFiltering(player, ViolationType.AUTOCLICKER, r)) {
                    return r;
                }
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
            while (!s.fastReels.isEmpty() && now - s.fastReels.peekFirst() > DECAY_MS) {
                s.fastReels.pollFirst();
            }
        }
    }

    private static class State {
        long lastBite = 0;
        long castTime = 0;
        final Deque<Long> fastReels = new ArrayDeque<>();
    }
}
