package NC.noChance.punishment;

import NC.noChance.alerts.StaffAlertManager;
import NC.noChance.core.ACConfig;
import NC.noChance.core.PlayerData;
import NC.noChance.core.ViolationType;
import NC.noChance.database.DatabaseManager;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public class PunishmentManager {
    private final Plugin plugin;
    private final ACConfig config;
    private final PunishmentLadder ladder;
    private final PunishmentExecutor executor;
    private final PunishmentHistory history;
    private final Map<UUID, Object> playerLocks = new ConcurrentHashMap<>();
    private final Map<UUID, Long> inFlightUntil = new ConcurrentHashMap<>();
    private final Map<UUID, AtomicLong> pendingDecisionToken = new ConcurrentHashMap<>();
    private static final long IN_FLIGHT_GUARD_MS = 2_000L;

    public PunishmentManager(Plugin plugin, ACConfig config, DatabaseManager database) {
        this.plugin = plugin;
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

            boolean mitigated = executor.trySetback(player, type);
            if (mitigated) {
                history.logSetback(uuid, player.getName(), type);
                tracker.markMitigated();
            }
            if (executor.runCustomCommands(player, type, severity, details, confidenceLevel)) {
                history.logCustomCommand(uuid, player.getName(), type, confidenceLevel);
            }

            long now = System.currentTimeMillis();
            Long blockUntil = inFlightUntil.get(uuid);
            if (blockUntil != null && now < blockUntil) {
                return;
            }

            AtomicLong tok = pendingDecisionToken.computeIfAbsent(uuid, k -> new AtomicLong());
            long myToken = tok.incrementAndGet();

            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (tok.get() != myToken) return;
                Object lk = playerLocks.get(uuid);
                if (lk == null) return;
                synchronized (lk) {
                    long now2 = System.currentTimeMillis();
                    Long block2 = inFlightUntil.get(uuid);
                    if (block2 != null && now2 < block2) return;

                    int totalViolations = tracker.getTotalViolations();
                    int typeViolations = tracker.getTypeViolations(type);

                    PunishmentLadder.LadderResult result = ladder.decide(totalViolations, typeViolations, confidenceLevel);
                    if (result.decision == PunishmentDecision.NONE) return;

                    if (result.decision == PunishmentDecision.BAN
                            && !tracker.wasPunishedRecently(type.getFamily(), 24L * 3600_000L)) {
                        result = new PunishmentLadder.LadderResult(
                                PunishmentDecision.TEMPBAN,
                                DatabaseManager.PunishmentType.TEMPBAN,
                                24L * 3600_000L,
                                result.reasonKey);
                        plugin.getLogger().info("BAN downgraded to TEMPBAN 24h for " + player.getName()
                                + " (no prior tempban for " + type.getFamily() + ")");
                    }

                    inFlightUntil.put(uuid, now2 + IN_FLIGHT_GUARD_MS);
                    tracker.recordPunishment(type.getFamily());
                    int flags60s = tracker.getFlagsWithin(60_000L);
                    int families60s = tracker.getDistinctFamiliesWithin(60_000L).size();
                    int mitigated60s = tracker.getMitigatedWithin(60_000L);
                    String verification = String.format("&a&l[✓ Confirmed]&r &7%dx flag%s / %d famil%s / %d mitigated",
                            flags60s, flags60s == 1 ? "" : "s",
                            families60s, families60s == 1 ? "y" : "ies",
                            mitigated60s);
                    String reason = executor.formatReason(player, result.reasonKey, type, confidenceLevel);
                    history.record(uuid, player.getName(), result.dbType, reason, result.duration);
                    executor.execute(player, result, type, confidenceLevel, verification);
                    tracker.reset();
                }
            }, 2L);
        }
    }

    public void cleanup(UUID uuid) {
        history.cleanup(uuid);
        executor.cleanup(uuid);
        playerLocks.remove(uuid);
        inFlightUntil.remove(uuid);
        pendingDecisionToken.remove(uuid);
    }

    public Map<UUID, Long> getLastWarningTimeMap() {
        return executor.getLastWarningTimeMap();
    }

    public PunishmentExecutor getExecutor() {
        return executor;
    }

    public Map<UUID, Long> getLastSetbackTimeMap() {
        return executor.getLastSetbackTimeMap();
    }
}
