package NC.noChance.core;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class AIVerdictCache {

    private final Map<UUID, Map<ViolationType, DetectionEngine.AIVerdict>> verdicts = new ConcurrentHashMap<>();

    public void apply(UUID uuid, ViolationType type, String verdict, double adjust, double agreement) {
        if (uuid == null || type == null) return;
        verdicts.computeIfAbsent(uuid, k -> new ConcurrentHashMap<>())
                .put(type, new DetectionEngine.AIVerdict(verdict, adjust, agreement, System.currentTimeMillis()));
    }

    public DetectionEngine.AIVerdict peek(UUID uuid, ViolationType type, long ttlMs) {
        if (uuid == null || type == null) return null;
        Map<ViolationType, DetectionEngine.AIVerdict> m = verdicts.get(uuid);
        if (m == null) return null;
        long now = System.currentTimeMillis();
        DetectionEngine.AIVerdict[] result = {null};
        m.computeIfPresent(type, (k, v) -> {
            if (now - v.ts > ttlMs) return null;
            result[0] = v;
            return v;
        });
        return result[0];
    }

    public void cleanup(UUID uuid) {
        verdicts.remove(uuid);
    }

    public void evictExpired(long ttlMs) {
        long now = System.currentTimeMillis();
        verdicts.values().forEach(m -> m.entrySet().removeIf(e -> now - e.getValue().ts > ttlMs));
        verdicts.entrySet().removeIf(e -> e.getValue().isEmpty());
    }

    public Map<UUID, Map<ViolationType, DetectionEngine.AIVerdict>> getMap() {
        return verdicts;
    }

    public int size() {
        int n = 0;
        for (Map<ViolationType, DetectionEngine.AIVerdict> m : verdicts.values()) {
            n += m.size();
        }
        return n;
    }
}
