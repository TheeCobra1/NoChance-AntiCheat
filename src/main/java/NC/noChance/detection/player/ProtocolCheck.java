package NC.noChance.detection.player;

import NC.noChance.core.*;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class ProtocolCheck {
    private final ACConfig config;
    private final Map<UUID, PlayerData> playerDataMap;
    private final LayerFiltering filtering;
    private final Map<UUID, ProtocolTracker> trackers;

    public ProtocolCheck(ACConfig config, Map<UUID, PlayerData> playerDataMap, LayerFiltering filtering) {
        this.config = config;
        this.playerDataMap = playerDataMap;
        this.filtering = filtering;
        this.trackers = new ConcurrentHashMap<>();
    }

    public CheckResult checkState(Player player, StateEvent event) {
        if (!config.isCheckEnabled("protocol")) {
            return CheckResult.passed();
        }

        if (player.isGliding() || player.isRiptiding()) {
            return CheckResult.passed();
        }
        if (BedrockHelper.isBedrockPlayer(player)) {
            return CheckResult.passed();
        }
        if (player.getGameMode() == org.bukkit.GameMode.SPECTATOR
                || player.getGameMode() == org.bukkit.GameMode.CREATIVE) {
            return CheckResult.passed();
        }

        PlayerData data = playerDataMap.get(player.getUniqueId());
        if (data == null) return CheckResult.passed();

        if (data.isInGracePeriod(config.getGracePeriod())) {
            return CheckResult.passed();
        }

        long teleportAge = System.currentTimeMillis() - data.getLastTeleportTime();
        if (data.getLastTeleportTime() > 0 && teleportAge < (config.getTeleportGracePeriod() * 1000L)) {
            return CheckResult.passed();
        }

        UUID uuid = player.getUniqueId();
        ProtocolTracker tracker = trackers.computeIfAbsent(uuid, k -> new ProtocolTracker());

        tracker.recordEvent(event);

        CheckResult stateCheck = checkInvalidState(player, tracker);
        if (stateCheck.isFailed()) return stateCheck;

        CheckResult sequenceCheck = checkInvalidSequence(player, tracker);
        if (sequenceCheck.isFailed()) return sequenceCheck;

        CheckResult duplicateCheck = checkDuplicatePackets(player, tracker);
        if (duplicateCheck.isFailed()) return duplicateCheck;

        return CheckResult.passed();
    }

    private CheckResult checkInvalidState(Player player, ProtocolTracker tracker) {
        if (tracker == null) return CheckResult.passed();
        StateSnapshot current = tracker.getCurrentState();
        if (current == null) return CheckResult.passed();

        if (player.isGliding()) {
            tracker.decayState();
            return CheckResult.passed();
        }

        if (current.riding && current.sneaking && !current.dismounting) {
            tracker.recordStateViolation();

            if (tracker.getStateViolations() >= 5) {
                CheckResult prelimResult = CheckResult.failed(
                    ViolationType.PROTOCOL,
                    0.78,
                    "Invalid riding state"
                );

                if (filtering.passesLayer2HeuristicFiltering(player, ViolationType.PROTOCOL, prelimResult)) {
                    return prelimResult;
                }
            }
        }

        tracker.decayState();
        return CheckResult.passed();
    }

    private CheckResult checkInvalidSequence(Player player, ProtocolTracker tracker) {
        List<StateEvent> recent = tracker.getRecentEvents(10);
        if (recent.size() < 5) return CheckResult.passed();

        int impossibleTransitions = 0;

        for (int i = 1; i < recent.size(); i++) {
            StateEvent prev = recent.get(i - 1);
            StateEvent curr = recent.get(i);

            if (isImpossibleTransition(prev, curr)) {
                impossibleTransitions++;
            }
        }

        if (impossibleTransitions >= 3) {
            tracker.recordSequenceViolation();

            if (tracker.getSequenceViolations() >= 3) {
                double severity = Math.min(0.94, 0.72 + impossibleTransitions * 0.05);

                CheckResult prelimResult = CheckResult.failed(
                    ViolationType.PROTOCOL,
                    severity,
                    String.format("Invalid sequence: %d impossible transitions", impossibleTransitions)
                );

                if (filtering.passesLayer2HeuristicFiltering(player, ViolationType.PROTOCOL, prelimResult)) {
                    return prelimResult;
                }
            }
        }

        return CheckResult.passed();
    }

    private CheckResult checkDuplicatePackets(Player player, ProtocolTracker tracker) {
        List<StateEvent> recent = tracker.getRecentEvents(20);
        if (recent.size() < 10) return CheckResult.passed();

        Map<EventType, Integer> counts = new HashMap<>();
        for (StateEvent event : recent) {
            counts.merge(event.type, 1, Integer::sum);
        }

        for (Map.Entry<EventType, Integer> entry : counts.entrySet()) {
            if (entry.getValue() >= 16 &&
                entry.getKey() != EventType.MOVE &&
                entry.getKey() != EventType.BREAK &&
                entry.getKey() != EventType.PLACE &&
                entry.getKey() != EventType.USE &&
                entry.getKey() != EventType.SNEAK &&
                entry.getKey() != EventType.SPRINT) {
                tracker.recordDuplicateViolation();

                if (tracker.getDuplicateViolations() >= 3) {
                    double severity = Math.min(0.90, 0.70 + entry.getValue() * 0.01);

                    CheckResult prelimResult = CheckResult.failed(
                        ViolationType.PROTOCOL,
                        severity,
                        String.format("Duplicate packets: %s x%d", entry.getKey(), entry.getValue())
                    );

                    if (filtering.passesLayer2HeuristicFiltering(player, ViolationType.PROTOCOL, prelimResult)) {
                        return prelimResult;
                    }
                }
            }
        }

        return CheckResult.passed();
    }

    private boolean isImpossibleTransition(StateEvent from, StateEvent to) {
        if (from.type == EventType.SLOT_CHANGE && to.type == EventType.ATTACK) {
            if (to.timestamp - from.timestamp < 25) return true;
        }

        if (from.type == EventType.ATTACK && to.type == EventType.ATTACK) {
            if (to.timestamp - from.timestamp < 22) return true;
        }

        if (from.type == EventType.PLACE && to.type == EventType.PLACE) {
            if (to.timestamp - from.timestamp < 40) return true;
        }

        if (from.type == EventType.RESPAWN && to.type == EventType.ATTACK) {
            if (to.timestamp - from.timestamp < 60) return true;
        }

        return false;
    }

    public void updateState(Player player, boolean eating, boolean blocking, boolean bow,
                           boolean sprinting, boolean sneaking, boolean riding,
                           boolean attacking, boolean dismounting) {
        UUID uuid = player.getUniqueId();
        ProtocolTracker tracker = trackers.computeIfAbsent(uuid, k -> new ProtocolTracker());
        tracker.updateState(eating, blocking, bow, sprinting, sneaking, riding, attacking, dismounting);
    }

    public void cleanup(UUID playerId) {
        trackers.remove(playerId);
    }

    public void cleanupStale() {
        long now = System.currentTimeMillis();
        trackers.entrySet().removeIf(e -> now - e.getValue().lastActivity > 30000);
    }

    public enum EventType {
        MOVE, ATTACK, PLACE, BREAK, USE, SLOT_CHANGE, SNEAK, SPRINT, JUMP, RESPAWN, TELEPORT
    }

    public static class StateEvent {
        public final EventType type;
        public final long timestamp;

        public StateEvent(EventType type, long timestamp) {
            this.type = type;
            this.timestamp = timestamp;
        }
    }

    private static class StateSnapshot {
        boolean eating;
        boolean blocking;
        boolean bow;
        boolean sprinting;
        boolean sneaking;
        boolean riding;
        boolean attacking;
        boolean dismounting;

        StateSnapshot(boolean eating, boolean blocking, boolean bow, boolean sprinting,
                     boolean sneaking, boolean riding, boolean attacking, boolean dismounting) {
            this.eating = eating;
            this.blocking = blocking;
            this.bow = bow;
            this.sprinting = sprinting;
            this.sneaking = sneaking;
            this.riding = riding;
            this.attacking = attacking;
            this.dismounting = dismounting;
        }
    }

    private static class ProtocolTracker {
        private int stateViolations = 0;
        private int sequenceViolations = 0;
        private int duplicateViolations = 0;
        private long lastStateViolation = 0;
        private long lastSequenceViolation = 0;
        private long lastDuplicateViolation = 0;
        private long lastActivity = System.currentTimeMillis();
        private StateSnapshot currentState = null;
        private final Deque<StateEvent> eventHistory = new ArrayDeque<>(50);

        void recordEvent(StateEvent event) {
            eventHistory.addLast(event);
            if (eventHistory.size() > 50) {
                eventHistory.pollFirst();
            }
            lastActivity = System.currentTimeMillis();
        }

        void updateState(boolean eating, boolean blocking, boolean bow, boolean sprinting,
                        boolean sneaking, boolean riding, boolean attacking, boolean dismounting) {
            currentState = new StateSnapshot(eating, blocking, bow, sprinting, sneaking, riding, attacking, dismounting);
            lastActivity = System.currentTimeMillis();
        }

        StateSnapshot getCurrentState() { return currentState; }

        List<StateEvent> getRecentEvents(int count) {
            List<StateEvent> list = new ArrayList<>(eventHistory);
            int start = Math.max(0, list.size() - count);
            return list.subList(start, list.size());
        }

        void recordStateViolation() {
            long now = System.currentTimeMillis();
            if (now - lastStateViolation < 2000) {
                stateViolations++;
            } else {
                stateViolations = 1;
            }
            lastStateViolation = now;
        }

        void decayState() {
            long now = System.currentTimeMillis();
            if (now - lastStateViolation > 4000) {
                stateViolations = Math.max(0, stateViolations - 1);
            }
        }

        int getStateViolations() { return stateViolations; }

        void recordSequenceViolation() {
            long now = System.currentTimeMillis();
            if (now - lastSequenceViolation < 3000) {
                sequenceViolations++;
            } else {
                sequenceViolations = 1;
            }
            lastSequenceViolation = now;
        }

        int getSequenceViolations() { return sequenceViolations; }

        void recordDuplicateViolation() {
            long now = System.currentTimeMillis();
            if (now - lastDuplicateViolation < 3000) {
                duplicateViolations++;
            } else {
                duplicateViolations = 1;
            }
            lastDuplicateViolation = now;
        }

        int getDuplicateViolations() { return duplicateViolations; }
    }
}
