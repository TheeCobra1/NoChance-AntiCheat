package NC.noChance.punishment;

import NC.noChance.NoChance;
import NC.noChance.alerts.StaffAlertManager;
import NC.noChance.core.ACConfig;
import NC.noChance.core.LangManager;
import NC.noChance.core.PlayerData;
import NC.noChance.core.ViolationType;
import org.bukkit.BanList;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public class PunishmentExecutor {
    private final Plugin plugin;
    private final ACConfig config;
    private final Map<UUID, Long> lastWarningTime;
    private final Map<UUID, Long> lastSetbackTime;
    private Map<UUID, PlayerData> playerDataMap;
    private StaffAlertManager alertManager;
    private static final long WARNING_THROTTLE_MS = 30000;
    private static final long SETBACK_THROTTLE_MS = 350;
    private static final Set<ViolationType> SETBACK_DEFAULT_TYPES = Set.of(
            ViolationType.FLY, ViolationType.FLY_VERTICAL, ViolationType.FLY_HOVER,
            ViolationType.FLY_GLIDE, ViolationType.SPEED, ViolationType.SPEED_GROUND,
            ViolationType.SPEED_AIR, ViolationType.SPEED_STRAFE, ViolationType.STEP,
            ViolationType.NOCLIP, ViolationType.JESUS, ViolationType.PHASE,
            ViolationType.SPIDER, ViolationType.GROUNDSPOOF, ViolationType.NOSLOW,
            ViolationType.STRIDER, ViolationType.BOATFLY, ViolationType.STRAFE);

    public PunishmentExecutor(Plugin plugin, ACConfig config) {
        this.plugin = plugin;
        this.config = config;
        this.lastWarningTime = new ConcurrentHashMap<>();
        this.lastSetbackTime = new ConcurrentHashMap<>();
    }

    public void setPlayerDataMap(Map<UUID, PlayerData> playerDataMap) {
        this.playerDataMap = playerDataMap;
    }

    public void setAlertManager(StaffAlertManager alertManager) {
        this.alertManager = alertManager;
    }

    public Map<UUID, Long> getLastWarningTimeMap() {
        return lastWarningTime;
    }

    public Map<UUID, Long> getLastSetbackTimeMap() {
        return lastSetbackTime;
    }

    public void cleanup(UUID uuid) {
        lastWarningTime.remove(uuid);
        lastSetbackTime.remove(uuid);
    }

    public boolean trySetback(Player player, ViolationType type) {
        if (!config.isSetbackEnabled() || playerDataMap == null) return false;

        if (player.getFallDistance() > 3f) return false;

        List<String> types = config.getSetbackTypes();
        boolean allowed;
        if (types == null || types.isEmpty()) {
            allowed = SETBACK_DEFAULT_TYPES.contains(type);
        } else if (types.size() == 1 && "ALL".equalsIgnoreCase(types.get(0))) {
            allowed = true;
        } else {
            allowed = types.contains(type.name());
        }
        if (!allowed) return false;

        UUID uuid = player.getUniqueId();
        long now = System.currentTimeMillis();
        Long prior = lastSetbackTime.putIfAbsent(uuid, now);
        if (prior != null) {
            if (now - prior < SETBACK_THROTTLE_MS) return false;
            if (!lastSetbackTime.replace(uuid, prior, now)) return false;
        }

        PlayerData data = playerDataMap.get(uuid);
        if (data == null) return false;

        Location target = data.getLastSafeLocation();
        if (target == null || target.getWorld() == null) return false;

        Bukkit.getScheduler().runTask(plugin, () -> {
            player.setVelocity(new org.bukkit.util.Vector(0, 0, 0));
            player.teleport(target);
            data.setLastTeleportTime(System.currentTimeMillis());
        });
        return true;
    }

    public boolean runCustomCommands(Player player, ViolationType type, double severity,
                                     String details, String confidenceLevel) {
        if (!config.isCustomCommandsEnabled()) return false;

        List<String> commands = config.getCustomCommands(confidenceLevel);
        if (commands == null || commands.isEmpty()) return false;

        String name = player.getName();
        String sev = String.format("%.2f", severity);
        String safeDetails = details == null ? "" : details;

        Bukkit.getScheduler().runTask(plugin, () -> {
            for (String raw : commands) {
                if (raw == null || raw.isEmpty()) continue;
                String cmd = raw
                        .replace("%player%", name)
                        .replace("%type%", type.name())
                        .replace("%severity%", sev)
                        .replace("%confidence%", confidenceLevel)
                        .replace("%details%", safeDetails);
                if (cmd.startsWith("/")) cmd = cmd.substring(1);
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
            }
        });
        return true;
    }

    public void execute(Player player, PunishmentLadder.LadderResult result, ViolationType type,
                        String confidenceLevel) {
        String reasonText = msg(player, result.reasonKey);
        String reason = msg(player, "punishment.kick_format",
                "reason", reasonText,
                "type", type.name(),
                "confidence", confidenceLevel);

        UUID playerUuid = player.getUniqueId();
        String playerName = player.getName();
        Bukkit.getScheduler().runTask(plugin, () -> {
            Player live = Bukkit.getPlayer(playerUuid);
            boolean onlineNow = live != null && live.isOnline();
            switch (result.decision) {
                case WARN:
                    long now = System.currentTimeMillis();
                    Long lastWarn = lastWarningTime.get(playerUuid);

                    if (onlineNow && config.shouldNotifyPlayerOnFlag()) {
                        if (lastWarn == null || now - lastWarn >= WARNING_THROTTLE_MS) {
                            live.sendMessage(msg(live, "punishment.warn_title"));
                            live.sendMessage(msg(live, "punishment.warn_flagged", "type", type.name()));
                            live.sendMessage(msg(live, "punishment.warn_stop"));
                            lastWarningTime.put(playerUuid, now);
                        }
                    }

                    broadcastStaff("punishment.broadcast_warn", playerName, type.name());
                    notifyDiscord(playerName, type, "WARN", null, reasonText, confidenceLevel);
                    break;

                case KICK:
                    if (!dispatchExternal("kick", player, type, confidenceLevel, reasonText, null, 0L)) {
                        if (onlineNow) live.kickPlayer(reason);
                    }
                    broadcastStaff("punishment.broadcast_kick", playerName, type.name());
                    notifyDiscord(playerName, type, "KICK", null, reasonText, confidenceLevel);
                    break;

                case TEMPBAN:
                    String duration = formatDuration(result.duration);
                    if (!dispatchExternal("tempban", player, type, confidenceLevel, reasonText, duration, result.duration)) {
                        Date expirationDate = new Date(System.currentTimeMillis() + result.duration);
                        String tempbanSuffix = msg(player, "punishment.kick_tempban_suffix", "duration", duration);
                        Bukkit.getBanList(BanList.Type.NAME).addBan(
                                playerName,
                                reason + tempbanSuffix,
                                expirationDate,
                                "NoChance"
                        );
                        if (onlineNow) live.kickPlayer(reason + tempbanSuffix);
                    }
                    broadcastStaffTempban(playerName, type.name(), duration);
                    notifyDiscord(playerName, type, "TEMPBAN", duration, reasonText, confidenceLevel);
                    break;

                case BAN:
                    if (!dispatchExternal("ban", player, type, confidenceLevel, reasonText, null, 0L)) {
                        Bukkit.getBanList(BanList.Type.NAME).addBan(
                                playerName,
                                reason,
                                (Date) null,
                                "NoChance"
                        );
                        String kickBan = reason + msg(player, "punishment.kick_ban_suffix");
                        if (onlineNow) live.kickPlayer(kickBan);
                    }
                    broadcastStaff("punishment.broadcast_ban", playerName, type.name());
                    notifyDiscord(playerName, type, "BAN", "Permanent", reasonText, confidenceLevel);
                    break;

                default:
                    break;
            }
        });
    }

    private boolean dispatchExternal(String action, Player player, ViolationType type,
                                     String confidenceLevel, String reasonText,
                                     String duration, long durationMs) {
        if (!config.isExternalPunishEnabled()) return false;
        String template = config.getExternalCommand(action);
        if (template == null || template.isEmpty()) return false;

        String preset = config.getExternalMessage(action);
        String msgText = (preset == null || preset.isEmpty()) ? reasonText : preset;

        String cmd = applyPlaceholders(template, player.getName(), type, confidenceLevel,
                msgText, duration, durationMs);
        if (cmd.startsWith("/")) cmd = cmd.substring(1);
        boolean dispatched;
        try {
            dispatched = Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
        } catch (Throwable t) {
            plugin.getLogger().warning("External " + action + " command threw: " + t.getMessage());
            return false;
        }
        if (!dispatched) {
            plugin.getLogger().warning("External " + action + " command rejected: /" + cmd);
        }
        return dispatched;
    }

    private String applyPlaceholders(String raw, String name, ViolationType type,
                                     String confidenceLevel, String reasonText,
                                     String duration, long durationMs) {
        long durSec = durationMs / 1000L;
        String dur = duration == null ? "" : duration;
        String text = reasonText == null ? "" : reasonText;
        return raw
                .replace("%message%", text)
                .replace("%reason%", text)
                .replace("%player%", name)
                .replace("%type%", type.name())
                .replace("%confidence%", confidenceLevel)
                .replace("%duration%", dur)
                .replace("%duration_seconds%", String.valueOf(durSec));
    }

    public void broadcastDetectOnly(String playerName, String typeName) {
        broadcastStaff("punishment.broadcast_detect_only", playerName, typeName);
    }

    public String formatReason(Player player, String reasonKey, ViolationType type, String confidenceLevel) {
        String reasonText = msg(player, reasonKey);
        return msg(player, "punishment.kick_format",
                "reason", reasonText,
                "type", type.name(),
                "confidence", confidenceLevel);
    }

    private LangManager lang() {
        return ((NoChance) plugin).getLangManager();
    }

    private String msg(Player p, String key, Object... ph) {
        return lang().get(p, key, ph);
    }

    private void broadcastStaff(String key, String playerName, String typeName) {
        for (Player staff : Bukkit.getOnlinePlayers()) {
            if (staff.hasPermission("nochance.alerts")) {
                staff.sendMessage(msg(staff, key, "player", playerName, "type", typeName));
            }
        }
    }

    private void broadcastStaffTempban(String playerName, String typeName, String duration) {
        for (Player staff : Bukkit.getOnlinePlayers()) {
            if (staff.hasPermission("nochance.alerts")) {
                staff.sendMessage(msg(staff, "punishment.broadcast_tempban",
                        "player", playerName, "type", typeName, "duration", duration));
            }
        }
    }

    private void notifyDiscord(String playerName, ViolationType type, String actionType,
                               String duration, String reason, String confidenceLevel) {
        if (alertManager == null) return;
        alertManager.sendPunishmentWebhook(playerName, type, actionType, duration, reason, confidenceLevel);
    }

    private String formatDuration(long millis) {
        long days = TimeUnit.MILLISECONDS.toDays(millis);
        long hours = TimeUnit.MILLISECONDS.toHours(millis) % 24;
        long minutes = TimeUnit.MILLISECONDS.toMinutes(millis) % 60;

        if (days > 0) {
            return days + "d " + hours + "h";
        } else if (hours > 0) {
            return hours + "h " + minutes + "m";
        } else {
            return minutes + "m";
        }
    }
}
