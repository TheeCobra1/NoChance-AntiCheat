package NC.noChance.detection.player;

import NC.noChance.core.*;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class DisablerCheck {
    private final ACConfig config;
    private final Map<UUID, PlayerData> playerDataMap;
    private final LayerFiltering filtering;
    private final Map<UUID, Long> joinTimes;
    private final Map<UUID, PacketCounter> packetCounters;

    private static final long ONLINE_REQ_MS = 5_000;
    private static final long SILENT_THRESHOLD_MS = 1_000;
    private static final int SILENT_PACKET_TICKS = 30;

    public DisablerCheck(ACConfig config, Map<UUID, PlayerData> playerDataMap, LayerFiltering filtering) {
        this.config = config;
        this.playerDataMap = playerDataMap;
        this.filtering = filtering;
        this.joinTimes = new ConcurrentHashMap<>();
        this.packetCounters = new ConcurrentHashMap<>();
    }

    public void recordPacket(UUID playerId) {
        PacketCounter pc = packetCounters.computeIfAbsent(playerId, k -> new PacketCounter());
        pc.packets++;
    }

    public void onTick() {
        for (PacketCounter pc : packetCounters.values()) {
            if (pc.packets == 0) {
                pc.silentTicks++;
            } else {
                pc.silentTicks = 0;
            }
            pc.packets = 0;
        }
    }

    public CheckResult check(Player player) {
        if (!config.isCheckEnabled("disabler")) return CheckResult.passed();

        UUID id = player.getUniqueId();
        PlayerData data = playerDataMap.get(id);
        if (data == null) return CheckResult.passed();

        long join = joinTimes.computeIfAbsent(id, k -> System.currentTimeMillis());
        long now = System.currentTimeMillis();

        if (now - join < ONLINE_REQ_MS) return CheckResult.passed();
        if (data.isInGracePeriod(config.getGracePeriod())) return CheckResult.passed();

        long lastMove = data.getLastMoveTime();
        if (lastMove <= 0) return CheckResult.passed();

        long silent = now - lastMove;
        if (silent < SILENT_THRESHOLD_MS) return CheckResult.passed();

        if (player.isInsideVehicle() || player.getVehicle() != null) return CheckResult.passed();
        if (player.isDead() || !player.isOnline()) return CheckResult.passed();
        if (player.getGameMode() == org.bukkit.GameMode.SPECTATOR) return CheckResult.passed();

        boolean serverThinksMoving = player.getVelocity().lengthSquared() > 0.001;
        if (!serverThinksMoving) return CheckResult.passed();

        PacketCounter pc = packetCounters.get(id);
        if (pc != null && pc.silentTicks >= SILENT_PACKET_TICKS) {
            CheckResult silentR = CheckResult.failed(ViolationType.PROTOCOL, 0.78,
                    "Disabler: " + pc.silentTicks + " ticks no packets while velocity active");
            if (filtering.passesLayer2HeuristicFiltering(player, ViolationType.PROTOCOL, silentR)) {
                return silentR;
            }
        }

        CheckResult r = CheckResult.failed(ViolationType.PROTOCOL, 0.70,
                "Disabler: " + silent + "ms with no movement packets while server expects motion");
        if (filtering.passesLayer2HeuristicFiltering(player, ViolationType.PROTOCOL, r)) {
            return r;
        }
        return CheckResult.passed();
    }

    public void cleanup(UUID playerId) {
        joinTimes.remove(playerId);
        packetCounters.remove(playerId);
    }

    public void cleanupStale() {
    }

    private static class PacketCounter {
        int packets = 0;
        int silentTicks = 0;
    }
}
