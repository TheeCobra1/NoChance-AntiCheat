package NC.noChance.core;

import org.bukkit.entity.Player;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class Improbable {
    private static final double SHORT_WINDOW_MS = 3_000.0;
    private static final double LONG_WINDOW_MS = 60_000.0;
    private static final double SHORT_LIMIT = 0.85;
    private static final double LONG_LIMIT = 4.0;
    private static final int MIN_DISTINCT_TYPES = 3;

    private final Map<UUID, State> states = new ConcurrentHashMap<>();

    public void feed(Player player, ViolationType type, double weight) {
        if (player == null || type == null || weight <= 0) return;
        UUID uuid = player.getUniqueId();
        State s = states.computeIfAbsent(uuid, k -> new State());
        long now = System.currentTimeMillis();
        synchronized (s) {
            decay(s, now);
            s.shortScore += weight;
            s.longScore += weight;
            s.recentTypes.add(type);
            s.lastUpdate = now;
        }
    }

    public CheckResult evaluate(Player player) {
        if (player == null) return CheckResult.passed();
        UUID uuid = player.getUniqueId();
        State s = states.get(uuid);
        if (s == null) return CheckResult.passed();
        long now = System.currentTimeMillis();
        synchronized (s) {
            decay(s, now);
            if (s.recentTypes.size() < MIN_DISTINCT_TYPES) return CheckResult.passed();
            boolean shortTrip = s.shortScore >= SHORT_LIMIT;
            boolean longTrip = s.longScore >= LONG_LIMIT;
            if (!shortTrip && !longTrip) return CheckResult.passed();

            double severity = Math.min(0.94, 0.72
                    + Math.min(0.15, (s.shortScore - SHORT_LIMIT) * 0.2)
                    + Math.min(0.15, (s.longScore - LONG_LIMIT) * 0.05));
            String reason = String.format("IMPROBABLE short=%.2f long=%.2f types=%d", s.shortScore, s.longScore, s.recentTypes.size());

            s.shortScore *= 0.5;
            s.longScore *= 0.8;
            s.recentTypes.clear();

            return CheckResult.failed(ViolationType.PROTOCOL, severity, reason);
        }
    }

    private void decay(State s, long now) {
        long elapsed = now - s.lastUpdate;
        if (elapsed <= 0) return;
        double shortRatio = Math.exp(-elapsed / SHORT_WINDOW_MS);
        double longRatio = Math.exp(-elapsed / LONG_WINDOW_MS);
        s.shortScore *= shortRatio;
        s.longScore *= longRatio;
        s.lastUpdate = now;
    }

    public void cleanup(UUID uuid) {
        if (uuid != null) states.remove(uuid);
    }

    private static class State {
        double shortScore = 0;
        double longScore = 0;
        long lastUpdate = System.currentTimeMillis();
        final Set<ViolationType> recentTypes = EnumSet.noneOf(ViolationType.class);
    }
}
