package NC.noChance.commands;

import NC.noChance.NoChance;
import NC.noChance.alerts.StaffAlertManager;
import NC.noChance.core.*;
import NC.noChance.database.DatabaseManager;
import NC.noChance.gui.ACMenuGUI;
import NC.noChance.ml.MLDataExport;
import NC.noChance.ml.MLDataReviewGUI;
import NC.noChance.replay.ReplayIO;
import NC.noChance.replay.ReplayMgr;
import NC.noChance.staff.StaffTools;
import NC.noChance.web.WebAuth;
import NC.noChance.web.WebBridge;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ThreadPoolExecutor;

public class NoChanceCommand implements CommandExecutor, TabCompleter {
    private final Plugin plugin;
    private final ACConfig config;
    private final Map<UUID, PlayerData> playerDataMap;
    private final ViolationScoring violationScoring;
    private final ACMenuGUI menuGUI;
    private final StaffAlertManager alertManager;
    private final DatabaseManager database;
    private ReplayMgr replayMgr;
    private StaffTools staffTools;
    private ReplayIO replayIO;
    private WebBridge webBridge;
    private MLDataReviewGUI mlReviewGUI;

    public NoChanceCommand(Plugin plugin, ACConfig config, Map<UUID, PlayerData> playerDataMap,
                           ViolationScoring violationScoring, ACMenuGUI menuGUI,
                           StaffAlertManager alertManager, DatabaseManager database) {
        this.plugin = plugin;
        this.config = config;
        this.playerDataMap = playerDataMap;
        this.violationScoring = violationScoring;
        this.menuGUI = menuGUI;
        this.alertManager = alertManager;
        this.database = database;
    }

    public void setReplayMgr(ReplayMgr replayMgr) {
        this.replayMgr = replayMgr;
    }

    public void setStaffTools(StaffTools staffTools) {
        this.staffTools = staffTools;
    }

    public void setReplayIO(ReplayIO replayIO) {
        this.replayIO = replayIO;
    }

    public void setWebBridge(WebBridge webBridge) {
        this.webBridge = webBridge;
    }

    public void setMLReviewGUI(MLDataReviewGUI gui) {
        this.mlReviewGUI = gui;
    }

    private LangManager lang() {
        return ((NoChance) plugin).getLangManager();
    }

    private String t(CommandSender s, String key, Object... ph) {
        return lang().get(s, key, ph);
    }

    private void send(CommandSender s, String key, Object... ph) {
        s.sendMessage(t(s, key, ph));
    }

    private void divider(CommandSender s) {
        send(s, "common.divider");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length > 0 ? args[0].toLowerCase() : "";
        boolean reviewOnly = sub.equals("review") && sender.hasPermission("nochance.review");
        boolean exportOnly = sub.equals("export-ml") && sender.hasPermission("nochance.admin.export");
        if (!sender.hasPermission("nochance.admin") && !reviewOnly && !exportOnly) {
            send(sender, "common.no_permission");
            return true;
        }

        if (args.length == 0) {
            sendHelpMessage(sender);
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "menu":
            case "gui":
                if (!(sender instanceof Player)) {
                    send(sender, "common.player_only");
                    return true;
                }
                menuGUI.openMainMenu((Player) sender);
                break;

            case "reload":
                handleReload(sender);
                break;

            case "info":
                if (args.length < 2) {
                    send(sender, "command.usage_info");
                    return true;
                }
                handleInfo(sender, args[1]);
                break;

            case "violations":
                if (args.length < 2) {
                    send(sender, "command.usage_violations");
                    return true;
                }
                handleViolations(sender, args[1]);
                break;

            case "reset":
                if (args.length < 2) {
                    send(sender, "command.usage_reset");
                    return true;
                }
                handleReset(sender, args[1]);
                break;

            case "toggle":
                if (args.length < 2) {
                    send(sender, "command.usage_toggle");
                    return true;
                }
                handleToggle(sender, args[1]);
                break;

            case "stats":
                handleStats(sender);
                break;

            case "alerts":
                if (!(sender instanceof Player)) {
                    send(sender, "common.player_only");
                    return true;
                }
                handleAlerts((Player) sender);
                break;

            case "replay":
                if (!(sender instanceof Player)) {
                    send(sender, "common.player_only");
                    return true;
                }
                handleReplay((Player) sender, args);
                break;

            case "spectate":
                if (!(sender instanceof Player)) {
                    send(sender, "common.player_only");
                    return true;
                }
                if (!sender.hasPermission("nochance.staff.spectate")) {
                    send(sender, "common.no_permission");
                    return true;
                }
                handleSpectate((Player) sender, args);
                break;

            case "freeze":
                if (!(sender instanceof Player)) {
                    send(sender, "common.player_only");
                    return true;
                }
                if (!sender.hasPermission("nochance.staff.freeze")) {
                    send(sender, "common.no_permission");
                    return true;
                }
                handleFreeze((Player) sender, args);
                break;

            case "verbose":
                if (!(sender instanceof Player)) {
                    send(sender, "common.player_only");
                    return true;
                }
                if (!sender.hasPermission("nochance.staff.verbose")) {
                    send(sender, "common.no_permission");
                    return true;
                }
                handleVerbose((Player) sender);
                break;

            case "profile":
                if (args.length < 2) {
                    send(sender, "command.usage_profile");
                    return true;
                }
                handleProfile(sender, args[1]);
                break;

            case "status":
                handleStatus(sender);
                break;

            case "variant":
                if (args.length < 2) {
                    send(sender, "command.usage_variant");
                    return true;
                }
                handleVariant(sender, args[1]);
                break;

            case "web":
                handleWeb(sender, args);
                break;

            case "diag":
                if (!sender.hasPermission("nochance.diag")) {
                    send(sender, "common.no_permission");
                    return true;
                }
                handleDiag(sender);
                break;

            case "review":
                if (!(sender instanceof Player)) {
                    send(sender, "common.player_only");
                    return true;
                }
                if (!sender.hasPermission("nochance.review")) {
                    send(sender, "common.no_permission");
                    return true;
                }
                handleReview((Player) sender);
                break;

            case "export-ml":
                if (!sender.hasPermission("nochance.admin.export")) {
                    send(sender, "common.no_permission");
                    return true;
                }
                handleExportML(sender);
                break;

            default:
                sendHelpMessage(sender);
                break;
        }

        return true;
    }

    private void sendHelpMessage(CommandSender sender) {
        send(sender, "command.help_header");
        divider(sender);
        send(sender, "command.help.menu");
        send(sender, "command.help.reload");
        send(sender, "command.help.info");
        send(sender, "command.help.violations");
        send(sender, "command.help.reset");
        send(sender, "command.help.toggle");
        send(sender, "command.help.stats");
        send(sender, "command.help.alerts");
        send(sender, "command.help.replay");
        send(sender, "command.help.spectate");
        send(sender, "command.help.freeze");
        send(sender, "command.help.verbose");
        send(sender, "command.help.profile");
        send(sender, "command.help.status");
        send(sender, "command.help.variant");
        send(sender, "command.help.web");
        sender.sendMessage("§7/nc diag §8- §aView live plugin diagnostics");
        sender.sendMessage("§7/nc review §8- §aOpen ML flag review GUI");
        sender.sendMessage("§7/nc export-ml §8- §aExport ML training data to CSV");
        divider(sender);
    }

    private void handleReview(Player player) {
        if (mlReviewGUI == null) {
            player.sendMessage("§cML review GUI not available (database offline?)");
            return;
        }
        mlReviewGUI.open(player, 0);
    }

    private void handleExportML(CommandSender sender) {
        NoChance nc = (NoChance) plugin;
        DatabaseManager db = nc.getDatabase();
        if (db == null) {
            send(sender, "command.violations.db_offline");
            return;
        }
        new MLDataExport(plugin, db).run(sender);
    }

    private void handleWeb(CommandSender sender, String[] args) {
        if (!sender.hasPermission("nochance.web") && !sender.hasPermission("nochance.admin")) {
            send(sender, "common.no_permission");
            return;
        }
        if (webBridge == null) {
            send(sender, "command.web.not_init");
            return;
        }

        String sub = args.length >= 2 ? args[1].toLowerCase() : "pair";

        if (sub.equals("status")) {
            send(sender, "command.web.status_header");
            divider(sender);
            if (webBridge.isLinked()) {
                send(sender, "command.web.state_linked");
                send(sender, "command.web.server_id", "id", webBridge.getServerId());
            } else if (webBridge.getPendingCode() != null) {
                send(sender, "command.web.state_pending");
                send(sender, "command.web.pending_code", "code", webBridge.getPendingCode());
            } else {
                send(sender, "command.web.state_unlinked");
                send(sender, "command.web.run_hint");
            }
            divider(sender);
            return;
        }

        if (sub.equals("unlink")) {
            if (!webBridge.isLinked()) {
                send(sender, "command.web.not_linked");
                return;
            }
            webBridge.unlink();
            send(sender, "command.web.unlinked");
            return;
        }

        if (sub.equals("cancel")) {
            webBridge.cancelPair();
            send(sender, "command.web.cancelled");
            return;
        }

        if (!(sender instanceof Player)) {
            send(sender, "command.web.op_only");
            return;
        }
        Player p = (Player) sender;
        if (!p.isOp()) {
            send(sender, "command.web.op_required");
            return;
        }
        if (webBridge.isLinked()) {
            send(sender, "command.web.already_linked");
            return;
        }

        WebAuth.Pending pending = webBridge.beginPair(p);
        if (pending == null) {
            send(sender, "command.web.disabled");
            return;
        }

        send(p, "command.web.pair_header");
        divider(p);
        send(p, "command.web.pair_code", "code", pending.code);
        send(p, "command.web.pair_enter");
        send(p, "command.web.pair_url", "url", config.getWebEndpoint());
        send(p, "command.web.pair_expiry");
        divider(p);
    }

    private void handleReload(CommandSender sender) {
        config.reload();
        if (config.isDiscordEnabled() && !config.getDiscordWebhook().isEmpty()) {
            alertManager.setDiscordWebhook(config.getDiscordWebhook());
        } else {
            alertManager.setDiscordWebhook(null);
        }
        lang().reload(config.getLanguage(), config.isForceServerLanguage());
        if (webBridge != null) webBridge.flushSettings();
        send(sender, "command.reload_success");
    }

    private void handleInfo(CommandSender sender, String playerName) {
        Player target = Bukkit.getPlayer(playerName);
        if (target == null) {
            send(sender, "common.player_not_found");
            return;
        }

        PlayerData data = playerDataMap.get(target.getUniqueId());
        if (data == null) {
            send(sender, "common.no_data");
            return;
        }

        long timeWindow = config.getTimeWindow() * 1000L;
        double score = violationScoring.calculateViolationScore(target.getUniqueId(), timeWindow);
        ViolationScoring.ConfidenceLevel confidence = violationScoring.getConfidenceLevel(score);

        send(sender, "command.info.header", "player", target.getName());
        divider(sender);
        send(sender, "command.info.skill", "value", formatEnum(data.getSkillLevel().name()));
        send(sender, "command.info.cps", "value", String.format("%.1f", data.getAverageCPS()));
        send(sender, "command.info.rotation", "value", String.format("%.1f°/s", data.getAverageRotationSpeed()));
        send(sender, "command.info.accuracy", "value", String.format("%.1f%%", data.getAverageAccuracy() * 100));
        send(sender, "command.info.score", "value", String.format("%.2f", score));
        send(sender, "command.info.confidence", "value", formatEnum(confidence.name()));

        if (database != null) {
            database.getActivePunishment(target.getUniqueId()).thenAccept(punishment -> {
                if (punishment != null && !punishment.isExpired()) {
                    long remaining = punishment.getTimeRemaining();
                    String dur = remaining < 0 ? t(sender, "command.info.duration_permanent") : (remaining / 1000) + "s";
                    Bukkit.getScheduler().runTask(plugin, () ->
                        send(sender, "command.info.active_punishment",
                            "type", formatEnum(punishment.type.name()),
                            "duration", dur));
                }
            });

            for (ViolationType vt : ViolationType.values()) {
                database.getViolationCount(target.getUniqueId(), vt, timeWindow).thenAccept(count -> {
                    if (count > 0) {
                        Bukkit.getScheduler().runTask(plugin, () ->
                            send(sender, "command.info.violation_line",
                                "type", vt.getDisplayName(),
                                "count", count));
                    }
                });
            }
        }

        divider(sender);
    }

    private void handleViolations(CommandSender sender, String playerName) {
        Player target = Bukkit.getPlayer(playerName);
        long timeWindow = Math.max(config.getTimeWindow() * 1000L, 86_400_000L);

        if (target != null) {
            PlayerData data = playerDataMap.get(target.getUniqueId());

            send(sender, "command.violations.header", "player", target.getName());
            divider(sender);

            boolean hasViolations = false;
            if (data != null) {
                for (ViolationType type : ViolationType.values()) {
                    List<PlayerData.ViolationRecord> violations = data.getViolations(type, timeWindow);
                    if (!violations.isEmpty()) {
                        hasViolations = true;
                        double avgSeverity = violations.stream()
                                .mapToDouble(v -> v.severity)
                                .average()
                                .orElse(0.0);
                        send(sender, "command.violations.summary_line",
                            "type", type.getDisplayName(),
                            "count", violations.size(),
                            "severity", String.format("%.2f", avgSeverity));
                    }
                }
            }

            if (!hasViolations) {
                send(sender, "command.violations.none");
            }

            if (database != null) {
                database.getViolations(target.getUniqueId(), timeWindow).thenAccept(dbViolations ->
                    Bukkit.getScheduler().runTask(plugin, () -> renderDbViolations(sender, dbViolations)));
            } else {
                divider(sender);
            }
            return;
        }

        if (database == null) {
            send(sender, "command.violations.db_offline");
            return;
        }

        send(sender, "command.violations.lookup_offline", "player", playerName);
        database.getViolationsByName(playerName, timeWindow).thenAccept(dbViolations ->
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (dbViolations.isEmpty()) {
                    send(sender, "command.violations.no_history", "player", playerName);
                    return;
                }
                send(sender, "command.violations.history_header", "player", dbViolations.get(0).playerName);
                divider(sender);
                renderDbViolations(sender, dbViolations);
            }));
    }

    private void renderDbViolations(CommandSender sender, List<DatabaseManager.ViolationRecord> list) {
        if (list.isEmpty()) {
            divider(sender);
            return;
        }
        send(sender, "command.violations.db_count_line", "count", list.size());
        Map<ViolationType, Integer> counts = new EnumMap<>(ViolationType.class);
        Map<ViolationType, Double> severitySum = new EnumMap<>(ViolationType.class);
        for (DatabaseManager.ViolationRecord vr : list) {
            counts.merge(vr.type, 1, Integer::sum);
            severitySum.merge(vr.type, vr.severity, Double::sum);
        }
        for (Map.Entry<ViolationType, Integer> e : counts.entrySet()) {
            double avg = severitySum.get(e.getKey()) / e.getValue();
            send(sender, "command.violations.db_summary_line",
                "type", e.getKey().getDisplayName(),
                "count", e.getValue(),
                "severity", String.format("%.2f", avg));
        }
        send(sender, "command.violations.recent_header");
        int shown = 0;
        for (DatabaseManager.ViolationRecord vr : list) {
            if (shown++ >= 5) {
                send(sender, "command.violations.more", "count", list.size() - 5);
                break;
            }
            long ago = (System.currentTimeMillis() - vr.timestamp) / 1000;
            send(sender, "command.violations.recent_line",
                "type", vr.type.getDisplayName(),
                "severity", String.format("%.2f", vr.severity),
                "ago", formatAgo(ago));
        }
        divider(sender);
    }

    private String formatAgo(long seconds) {
        if (seconds < 60) return seconds + "s";
        if (seconds < 3600) return (seconds / 60) + "m";
        if (seconds < 86400) return (seconds / 3600) + "h";
        return (seconds / 86400) + "d";
    }

    private void handleReset(CommandSender sender, String playerName) {
        Player target = Bukkit.getPlayer(playerName);
        if (target == null) {
            send(sender, "common.player_not_found");
            return;
        }

        UUID uid = target.getUniqueId();
        playerDataMap.compute(uid, (k, v) -> new PlayerData(uid));
        send(sender, "command.reset_done", "player", target.getName());
    }

    private void handleToggle(CommandSender sender, String checkName) {
        String configPath = "checks." + checkName.toLowerCase() + ".enabled";
        boolean currentValue = config.isCheckEnabled(checkName.toLowerCase());
        plugin.getConfig().set(configPath, !currentValue);
        plugin.saveConfig();
        config.reload();

        String state = t(sender, !currentValue ? "command.toggle_state_on" : "command.toggle_state_off");
        send(sender, "command.toggle_result", "check", checkName, "state", state);
    }

    private void handleStats(CommandSender sender) {
        int totalPlayers = playerDataMap.size();
        int playersWithViolations = 0;
        int totalViolations = 0;

        long timeWindow = config.getTimeWindow() * 1000L;

        for (PlayerData data : playerDataMap.values()) {
            boolean hasViolations = false;
            for (ViolationType type : ViolationType.values()) {
                List<PlayerData.ViolationRecord> violations = data.getViolations(type, timeWindow);
                if (!violations.isEmpty()) {
                    hasViolations = true;
                    totalViolations += violations.size();
                }
            }
            if (hasViolations) {
                playersWithViolations++;
            }
        }

        send(sender, "command.stats.header");
        divider(sender);
        send(sender, "command.stats.total", "value", totalPlayers);
        send(sender, "command.stats.flagged", "value", playersWithViolations);
        send(sender, "command.stats.recent", "value", totalViolations);
        divider(sender);
    }

    private void handleAlerts(Player player) {
        alertManager.toggleAlerts(player.getUniqueId());
        boolean enabled = alertManager.hasAlertsEnabled(player.getUniqueId());
        String state = t(player, enabled ? "command.toggle_state_on" : "command.toggle_state_off");
        send(player, "command.alerts_toggled", "state", state);
    }

    private void handleReplay(Player player, String[] args) {
        if (replayMgr == null || !replayMgr.isEnabled()) {
            send(player, "command.replay.disabled");
            return;
        }

        if (args.length < 2) {
            send(player, "command.replay.help_header");
            send(player, "command.replay.help_list");
            send(player, "command.replay.help_play");
            send(player, "command.replay.help_stop");
            send(player, "command.replay.help_pause");
            send(player, "command.replay.help_speed");
            send(player, "command.replay.help_follow");
            send(player, "command.replay.help_hud");
            if (replayIO != null) {
                Path dir = replayIO.getReplayDir();
                send(player, "command.replay.dir", "path", dir.toString());
            }
            return;
        }

        switch (args[1].toLowerCase()) {
            case "list":
                String filterPlayer = args.length > 2 ? args[2] : null;
                List<String> replays = replayMgr.listReplays(filterPlayer);
                if (replays.isEmpty()) {
                    send(player, "command.replay.none");
                } else {
                    send(player, "command.replay.list_header");
                    int count = 0;
                    for (String r : replays) {
                        if (count++ >= 10) {
                            send(player, "command.violations.more", "count", replays.size() - 10);
                            break;
                        }
                        send(player, "command.replay.list_line", "file", r);
                    }
                }
                break;

            case "play":
                if (args.length < 3) {
                    send(player, "command.usage_replay_play");
                    return;
                }
                replayMgr.playReplay(player, args[2]);
                break;

            case "stop":
                replayMgr.stopReplay(player);
                break;

            case "pause":
                replayMgr.pauseReplay(player);
                break;

            case "speed":
                if (args.length < 3) {
                    send(player, "command.usage_replay_speed");
                    return;
                }
                try {
                    float speed = Float.parseFloat(args[2]);
                    replayMgr.setReplaySpeed(player, speed);
                } catch (NumberFormatException e) {
                    send(player, "command.replay.bad_speed");
                }
                break;

            case "follow":
                replayMgr.toggleFollow(player);
                break;

            case "hud":
                replayMgr.toggleHud(player);
                break;

            default:
                send(player, "command.replay.unknown");
                break;
        }
    }

    private void handleSpectate(Player player, String[] args) {
        if (staffTools == null) {
            send(player, "command.spectate.no_tools");
            return;
        }

        if (args.length < 2) {
            send(player, "command.usage_spectate");
            return;
        }

        String sub = args[1].toLowerCase();
        if (sub.equals("stop")) {
            staffTools.stopSpectate(player);
            return;
        }

        if (sub.equals("follow")) {
            staffTools.toggleSpectateFollow(player);
            return;
        }

        Player target = Bukkit.getPlayer(args[1]);
        if (target == null) {
            send(player, "common.player_not_found");
            return;
        }

        staffTools.startSpectate(player, target);
    }

    private void handleFreeze(Player player, String[] args) {
        if (staffTools == null) {
            send(player, "command.freeze.no_tools");
            return;
        }

        if (args.length < 2 || args[1].equalsIgnoreCase("list")) {
            Set<UUID> frozen = staffTools.getFrozenPlayers();
            if (frozen.isEmpty()) {
                send(player, "command.freeze.none_frozen");
            } else {
                send(player, "command.freeze.list_header", "count", frozen.size());
                for (UUID uid : frozen) {
                    Player fp = Bukkit.getPlayer(uid);
                    String name = fp != null ? fp.getName() : uid.toString().substring(0, 8);
                    send(player, "command.freeze.list_line", "name", name);
                }
            }
            if (args.length < 2) return;
            if (args[1].equalsIgnoreCase("list")) return;
        }

        Player target = Bukkit.getPlayer(args[1]);
        if (target == null) {
            send(player, "common.player_not_found");
            return;
        }

        staffTools.freezePlayer(player, target);
    }

    private void handleVerbose(Player player) {
        if (staffTools == null) {
            send(player, "command.verbose.no_tools");
            return;
        }

        boolean wasBefore = staffTools.hasVerbose(player.getUniqueId());
        staffTools.toggleVerbose(player);
        if (wasBefore) {
            send(player, "command.verbose.was_on");
        }
    }

    private void handleProfile(CommandSender sender, String playerName) {
        Player target = Bukkit.getPlayer(playerName);
        if (target == null) {
            send(sender, "common.player_not_found");
            return;
        }

        PlayerData data = playerDataMap.get(target.getUniqueId());
        if (data == null) {
            send(sender, "command.profile.no_data");
            return;
        }

        String clientType = t(sender, data.isBedrockPlayer() ? "command.profile.client_bedrock" : "command.profile.client_java");
        String frozenStatus = t(sender, data.isFrozen() ? "command.profile.status_frozen" : "command.profile.status_normal");

        send(sender, "command.profile.header", "player", target.getName());
        divider(sender);
        send(sender, "command.profile.client", "value", clientType);
        send(sender, "command.profile.ping",
            "ping", data.getPing(),
            "avg", String.format("%.0f", data.getAveragePing()));
        send(sender, "command.profile.status", "value", frozenStatus);
        send(sender, "command.profile.skill", "value", formatEnum(data.getSkillLevel().name()));
        send(sender, "command.profile.cps", "value", String.format("%.1f", data.getAverageCPS()));
        send(sender, "command.profile.rotation", "value", String.format("%.0f°/s", data.getAverageRotationSpeed()));
        send(sender, "command.profile.accuracy", "value", String.format("%.1f%%", data.getAverageAccuracy() * 100));
        send(sender, "command.profile.vl_ratio", "value", String.format("%.2f%%", data.getViolationRatio() * 100));
        String highPing = t(sender, data.isHighPing() ? "command.profile.yes_lbl" : "command.profile.no_lbl");
        send(sender, "command.profile.high_ping", "value", highPing);
        send(sender, "command.profile.ping_mult", "value", String.format("%.2fx", data.getPingMultiplier()));
        divider(sender);
    }

    private void handleStatus(CommandSender sender) {
        NoChance nc = (NoChance) plugin;
        send(sender, "command.status.header");
        divider(sender);
        send(sender, "command.status.version", "value", plugin.getDescription().getVersion());
        send(sender, "command.status.tracked", "value", playerDataMap.size());

        CheckRegistry cr = nc.getCheckRegistry();
        send(sender, "command.status.checks_loaded", "value", (cr != null ? cr.getCheckCount() : 0));

        send(sender, "command.status.alert_mgr", "value", state(sender, nc.getAlertManager() != null));
        send(sender, "command.status.staff_tools", "value", state(sender, nc.getStaffTools() != null));
        send(sender, "command.status.prediction", "value", state(sender, nc.getPredictionEngine() != null));
        send(sender, "command.status.simulation", "value", state(sender, nc.getSimEngine() != null));
        send(sender, "command.status.sim_bridge", "value", state(sender, nc.getSimBridge() != null));
        send(sender, "command.status.session", "value", state(sender, nc.getSessionTracker() != null));
        send(sender, "command.status.protocol", "value", state(sender, nc.getPacketFingerprint() != null));
        NC.noChance.staff.LiveOverlay overlay = staffTools != null ? staffTools.getLiveOverlay() : nc.getLiveOverlay();
        send(sender, "command.status.overlay", "value", state(sender, overlay != null));

        ReplayMgr rm = nc.getReplayMgr();
        String replayState = t(sender, rm != null && rm.isEnabled() ? "command.status.enabled" : "command.status.disabled");
        send(sender, "command.status.replay", "value", replayState);

        String dbState = t(sender, nc.getDatabase() != null ? "command.status.connected" : "command.status.off_lbl");
        send(sender, "command.status.database", "value", dbState);
        divider(sender);
    }

    private String state(CommandSender s, boolean on) {
        return t(s, on ? "command.status.active" : "command.status.off_lbl");
    }

    private void handleVariant(CommandSender sender, String checkName) {
        NoChance nc = (NoChance) plugin;
        DetectionEngine de = nc.getDetectionEngine();
        if (de == null) {
            send(sender, "command.variant.no_engine");
            return;
        }

        ViolationType type;
        try {
            type = ViolationType.valueOf(checkName.toUpperCase());
        } catch (IllegalArgumentException e) {
            send(sender, "command.variant.unknown", "check", checkName);
            return;
        }

        VariantCheck vc = de.getVariantCheck();
        VariantCheck.VariantAnalysis analysis = vc.analyzeVariants(type);
        Map<String, String> summary = vc.getMetricsSummary(type);

        send(sender, "command.variant.header", "check", type.getDisplayName());
        divider(sender);
        String sig = t(sender, analysis.statistically_significant ? "command.variant.significant" : "command.variant.not_significant");
        send(sender, "command.variant.best",
            "variant", analysis.bestVariant,
            "improvement", String.format("%.3f", analysis.improvement),
            "significance", sig);

        for (String variant : new String[]{"A", "B", "C"}) {
            String samples = summary.getOrDefault(variant + "_samples", "0");
            String accuracy = summary.getOrDefault(variant + "_accuracy", "0.00%");
            String fpRate = summary.getOrDefault(variant + "_fp_rate", "0.00%");
            String tpRate = summary.getOrDefault(variant + "_tp_rate", "0.00%");
            send(sender, "command.variant.row",
                "variant", variant,
                "samples", samples,
                "accuracy", accuracy,
                "fp", fpRate,
                "tp", tpRate);
        }
        divider(sender);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        boolean admin = sender.hasPermission("nochance.admin");
        boolean review = sender.hasPermission("nochance.review");
        boolean exportP = sender.hasPermission("nochance.admin.export");
        if (!admin && !review && !exportP) {
            return new ArrayList<>();
        }

        if (args.length == 1) {
            List<String> base = new ArrayList<>();
            if (admin) {
                base.addAll(Arrays.asList("menu", "gui", "reload", "info", "violations", "reset", "toggle",
                        "stats", "alerts", "replay", "spectate", "freeze", "verbose", "profile", "status", "variant", "web", "diag"));
            }
            if (review) base.add("review");
            if (exportP) base.add("export-ml");
            return base;
        }

        if (args.length == 2) {
            if (args[0].equalsIgnoreCase("info") || args[0].equalsIgnoreCase("violations") ||
                    args[0].equalsIgnoreCase("reset") || args[0].equalsIgnoreCase("freeze") ||
                    args[0].equalsIgnoreCase("profile")) {
                List<String> players = new ArrayList<>();
                for (Player p : Bukkit.getOnlinePlayers()) {
                    players.add(p.getName());
                }
                return players;
            }

            if (args[0].equalsIgnoreCase("spectate")) {
                List<String> options = new ArrayList<>(Arrays.asList("stop", "follow"));
                for (Player p : Bukkit.getOnlinePlayers()) {
                    options.add(p.getName());
                }
                return options;
            }

            if (args[0].equalsIgnoreCase("toggle")) {
                return Arrays.asList("fly", "speed", "noclip", "jesus", "fastbreak",
                        "fastplace", "nuker", "killaura", "nofall", "autoclicker",
                        "reach", "inventory", "scaffold", "timer", "velocity",
                        "criticals", "phase", "step", "blink", "noslow",
                        "groundspoof", "elytrafly", "strider", "boatfly");
            }

            if (args[0].equalsIgnoreCase("replay")) {
                return Arrays.asList("list", "play", "stop", "pause", "speed", "follow", "hud");
            }

            if (args[0].equalsIgnoreCase("variant")) {
                return Arrays.asList("reach", "fly", "speed", "killaura", "autoclicker", "fastbreak", "scaffold", "velocity");
            }

            if (args[0].equalsIgnoreCase("web")) {
                return Arrays.asList("pair", "status", "unlink", "cancel");
            }
        }

        if (args.length == 3) {
            if (args[0].equalsIgnoreCase("replay")) {
                if (args[1].equalsIgnoreCase("list")) {
                    List<String> players = new ArrayList<>();
                    for (Player p : Bukkit.getOnlinePlayers()) {
                        players.add(p.getName());
                    }
                    return players;
                }
                if (args[1].equalsIgnoreCase("play") && replayMgr != null) {
                    return replayMgr.listReplays(null);
                }
                if (args[1].equalsIgnoreCase("speed")) {
                    return Arrays.asList("0.5", "1", "1.5", "2");
                }
            }
        }

        return new ArrayList<>();
    }

    private void handleDiag(CommandSender sender) {
        NoChance nc = (NoChance) plugin;

        sender.sendMessage("§8§m----§r §bNoChance Diagnostics §8§m----");

        double tps;
        try {
            tps = TPSCache.getTPS();
        } catch (Throwable t) {
            tps = 20.0;
        }
        String tpsColor = tps >= 19.0 ? "§a" : (tps >= 16.0 ? "§e" : "§c");
        sender.sendMessage("§7TPS: " + tpsColor + String.format("%.2f", tps));

        int online = Bukkit.getOnlinePlayers().size();
        int tracked = playerDataMap.size();
        sender.sendMessage("§7Players: §a" + online + " online §7/ §a" + tracked + " tracked");

        Runtime rt = Runtime.getRuntime();
        long usedMb = (rt.totalMemory() - rt.freeMemory()) / 1048576L;
        long totalMb = rt.totalMemory() / 1048576L;
        long maxMb = rt.maxMemory() / 1048576L;
        sender.sendMessage("§7Memory: §a" + usedMb + "MB §7used / §a" + totalMb + "MB §7alloc / §a" + maxMb + "MB §7max");

        long uptimeMs = System.currentTimeMillis() - nc.getEnableTime();
        long uptimeSec = uptimeMs / 1000L;
        String uptime;
        if (uptimeSec < 60) {
            uptime = uptimeSec + "s";
        } else if (uptimeSec < 3600) {
            uptime = (uptimeSec / 60) + "m " + (uptimeSec % 60) + "s";
        } else {
            uptime = (uptimeSec / 3600) + "h " + ((uptimeSec % 3600) / 60) + "m";
        }
        sender.sendMessage("§7Uptime: §a" + uptime);

        LifecycleRegistry reg = nc.getLifecycleRegistry();
        if (reg != null) {
            sender.sendMessage("§7Thread pools:");
            List<LifecycleRegistry.NamedExecutor> nes = reg.getNamedExecutors();
            if (nes.isEmpty()) {
                sender.sendMessage("  §8(none)");
            } else {
                for (LifecycleRegistry.NamedExecutor ne : nes) {
                    if (ne.executor instanceof ThreadPoolExecutor tpe) {
                        sender.sendMessage("  §7" + ne.name + ": §a" + tpe.getActiveCount() + " active §7/ §a" + tpe.getQueue().size() + " queued");
                    } else {
                        sender.sendMessage("  §7" + ne.name + ": §8(unavailable)");
                    }
                }
            }
        } else {
            sender.sendMessage("§7Thread pools: §coff");
        }

        DatabaseManager db = nc.getDatabase();
        com.zaxxer.hikari.HikariPoolMXBean mx = null;
        if (db != null) {
            try {
                com.zaxxer.hikari.HikariDataSource hds = db.getDataSource();
                if (hds != null) mx = hds.getHikariPoolMXBean();
            } catch (Throwable t) {
                mx = null;
            }
        }
        if (mx != null) {
            sender.sendMessage("§7DB pool: §a" + mx.getActiveConnections() + " active §7/ §a" + mx.getIdleConnections() + " idle §7/ §a" + mx.getTotalConnections() + " total §7/ §a" + mx.getThreadsAwaitingConnection() + " waiting");
        } else {
            sender.sendMessage("§7DB pool: §7n/a");
        }

        DiagMetrics dm = nc.getDiagMetrics();
        if (dm != null) {
            Map<String, Long> rates = dm.snapshotLast60s();
            if (rates.isEmpty()) {
                sender.sendMessage("§7Check rate: §8(no checks in last 60s)");
            } else {
                sender.sendMessage("§7Check rate (last 60s):");
                for (Map.Entry<String, Long> e : rates.entrySet()) {
                    double perSec = e.getValue() / 60.0;
                    sender.sendMessage("  §7" + e.getKey() + ": §a" + String.format("%.1f", perSec) + "/s");
                }
            }
        } else {
            sender.sendMessage("§7Check rate: §7n/a");
        }

        DetectionEngine de = nc.getDetectionEngine();
        int detSize = de != null ? de.getStateSize() : 0;
        int playerStateTotal = 0;
        for (PlayerData pd : playerDataMap.values()) {
            playerStateTotal += pd.getStateSize();
        }
        sender.sendMessage("§7State entries: §a" + (detSize + playerStateTotal) + " §7(engine=" + detSize + ", players=" + playerStateTotal + ")");

        StateSweeper sw = nc.getStateSweeper();
        if (sw != null && sw.getLastSweepTime() > 0) {
            long ago = (System.currentTimeMillis() - sw.getLastSweepTime()) / 1000L;
            sender.sendMessage("§7Sweeper: §a" + sw.getLastSweepRemoved() + " removed §7/ §a" + sw.getLastSweepRemainingEntries() + " kept §7(" + formatAgo(ago) + " ago, " + sw.getRegisteredCount() + " maps)");
        } else {
            sender.sendMessage("§7Sweeper: §7n/a");
        }

        sender.sendMessage("§8§m--------------------------");
    }

    private String formatEnum(String raw) {
        if (raw == null) return "";
        String lower = raw.toLowerCase().replace('_', ' ');
        StringBuilder sb = new StringBuilder();
        boolean cap = true;
        for (char c : lower.toCharArray()) {
            if (c == ' ') {
                sb.append(' ');
                cap = true;
            } else {
                sb.append(cap ? Character.toUpperCase(c) : c);
                cap = false;
            }
        }
        return sb.toString();
    }
}
