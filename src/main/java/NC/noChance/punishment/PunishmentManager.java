package NC.noChance.punishment;

import NC.noChance.alerts.StaffAlertManager;
import NC.noChance.core.ACConfig;
import NC.noChance.core.PlayerData;
import NC.noChance.core.ViolationType;
import NC.noChance.database.DatabaseManager;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class PunishmentManager {
    private final ACConfig config;
    private final PunishmentLadder ladder;
    private final PunishmentExecutor executor;
    private final PunishmentHistory history;
    private final Map<UUID, Object> playerLocks = new ConcurrentHashMap<>();
    private final Map<UUID, Long> inFlightUntil = new ConcurrentHashMap<>();
    private static final long IN_FLIGHT_GUARD_MS = 2_000L;

    public PunishmentManager(Plugin plugin, ACConfig config, DatabaseManager database) {
        this.config = config;
        this.ladder = new PunishmentLadder(config);
        this.executor = new PunishmentExecutor(plugin, config);
        this.history = new PunishmentHistory(plugin, database);
    }

    public void setPlayerDataMap(Map<UUID, PlayerData> playerDataMap) {
        executor.setPlayerDataMap(playerDataMap);
    }

    public void setAlertManager(StaffAlertManager alertManager) {
        executor.setAlertManager(alertManager);
    }

    public void handleViolation(Player player, ViolationType type, double severity,
                                String details, String confidenceLevel) {
        UUID uuid = player.getUniqueId();
        Object lock = playerLocks.computeIfAbsent(uuid, k -> new Object());
        synchronized (lock) {
            PunishmentHistory.PunishmentTracker tracker = history.tracker(uuid);

            boolean isOp = player.isOp();
            boolean hasBypass = player.hasPermission("nochance.bypass")
                    || player.hasPermission("nochance.bypass." + type.name().toLowerCase());

            boolean opShouldExempt = isOp && config.isOpExempt();
            boolean shouldExempt = opShouldExempt || hasBypass;

            if (shouldExempt) {
                if (config.isOpDetectOnly()) {
                    tracker.addViolation(type, severity);
                    executor.broadcastDetectOnly(player.getName(), type.name());
                }
                return;
            }

            tracker.addViolation(type, severity);

            if (executor.trySetback(player, type)) {
                history.logSetback(uuid, player.getName(), type);
            }
            if (executor.runCustomCommands(player, type, severity, details, confidenceLevel)) {
                history.logCustomCommand(uuid, player.getName(), type, confidenceLevel);
            }

            int totalViolations = tracker.getTotalViolations();
            int typeViolations = tracker.getTypeViolations(type);

            PunishmentLadder.LadderResult result = ladder.decide(totalViolations, typeViolations, confidenceLevel);

            if (result.decision != PunishmentDecision.NONE) {
                long now = System.currentTimeMillis();
                Long blockUntil = inFlightUntil.get(uuid);
                if (blockUntil != null && now < blockUntil) {
                    return;
                }
                inFlightUntil.put(uuid, now + IN_FLIGHT_GUARD_MS);
                String reason = executor.formatReason(player, result.reasonKey, type, confidenceLevel);
                history.record(uuid, player.getName(), result.dbType, reason, result.duration);
                executor.execute(player, result, type, confidenceLevel);
                tracker.reset();
            }
        }
    }

    public void cleanup(UUID uuid) {
        history.cleanup(uuid);
        executor.cleanup(uuid);
        playerLocks.remove(uuid);
        inFlightUntil.remove(uuid);
    }

    public Map<UUID, Long> getLastWarningTimeMap() {
        return executor.getLastWarningTimeMap();
    }

    public Map<UUID, Long> getLastSetbackTimeMap() {
        return executor.getLastSetbackTimeMap();
    }
}
