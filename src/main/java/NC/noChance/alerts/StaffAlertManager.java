package NC.noChance.alerts;

import NC.noChance.NoChance;
import NC.noChance.core.ACConfig;
import NC.noChance.core.LangManager;
import NC.noChance.core.PlayerData;
import NC.noChance.core.ViolationType;
import NC.noChance.packets.PacketFingerprint;
import NC.noChance.web.WebBridge;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.chat.hover.content.Text;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

public class StaffAlertManager {
    private final Plugin plugin;
    private final ACConfig config;
    private final Map<UUID, AlertThrottle> throttles;
    private final Set<UUID> alertsEnabled;
    private final Map<UUID, AlertBatch> pendingBatches;
    private Map<UUID, PlayerData> playerDataMap;
    private String discordWebhook;
    private LangManager lang;
    private WebBridge webBridge;
    private PacketFingerprint packetFingerprint;

    private static final long BATCH_WINDOW_MS = 2500;
    private static final int MAX_BATCH_SIZE = 5;
    private static final long DISCORD_RATE_LIMIT_COOLDOWN_MS = 5000;
    private volatile long lastRateLimitTime = 0L;

    public StaffAlertManager(Plugin plugin, ACConfig config) {
        this.plugin = plugin;
        this.config = config;
        this.throttles = new ConcurrentHashMap<>();
        this.alertsEnabled = ConcurrentHashMap.newKeySet();
        this.pendingBatches = new ConcurrentHashMap<>();
        this.discordWebhook = null;

        startBatchProcessor();
    }

    private void startBatchProcessor() {
        org.bukkit.scheduler.BukkitTask task = Bukkit.getScheduler().runTaskTimer(plugin, this::processBatches, 40L, 40L);
        if (plugin instanceof NoChance) {
            ((NoChance) plugin).getLifecycleRegistry().registerBukkitTask(task);
        }
    }

    private void processBatches() {
        long now = System.currentTimeMillis();
        List<Map.Entry<UUID, AlertBatch>> snapshot = new ArrayList<>(pendingBatches.entrySet());

        for (Map.Entry<UUID, AlertBatch> entry : snapshot) {
            AlertBatch batch = entry.getValue();

            if (now - batch.firstAlert > BATCH_WINDOW_MS || batch.alerts.size() >= MAX_BATCH_SIZE) {
                if (pendingBatches.remove(entry.getKey(), batch)) {
                    sendBatchedAlert(batch);
                }
            }
        }
    }

    public void setPlayerDataMap(Map<UUID, PlayerData> map) {
        this.playerDataMap = map;
    }

    public void setLangManager(LangManager lang) {
        this.lang = lang;
    }

    public void setDiscordWebhook(String webhook) {
        this.discordWebhook = webhook;
    }

    public void setWebBridge(WebBridge bridge) {
        this.webBridge = bridge;
    }

    public void setPacketFingerprint(PacketFingerprint fp) {
        this.packetFingerprint = fp;
    }

    public void sendAlert(Player player, ViolationType type, double severity, String details,
                         String confidenceLevel, double score, String detectionMethod, String variant) {
        details = sanitizeDetails(details);
        UUID uuid = player.getUniqueId();
        AlertThrottle throttle = throttles.computeIfAbsent(uuid, k -> new AlertThrottle());

        throttle.recordViolation(type);

        PlayerData pd = playerDataMap != null ? playerDataMap.get(uuid) : null;

        if (webBridge != null) {
            double trust = pd == null ? 0.5 : 1.0 - pd.getViolationRatio();
            double tps = NC.noChance.core.TPSCache.getTPS();
            webBridge.telemetry().recordFlag(player.getName(), uuid, type, severity, confidenceLevel, score,
                    detectionMethod, trust, getPing(player), tps, player, pd);
            if ("HIGH".equals(confidenceLevel) || "EXTREME".equals(confidenceLevel)) {
                webBridge.scheduleFastFlush();
            }
        }

        if (!throttle.shouldAlert(type)) {
            return;
        }

        AlertEntry entry = new AlertEntry(player.getName(), uuid, type, severity, details,
                confidenceLevel, score, detectionMethod, variant, player.getLocation().clone(),
                getPing(player), pd != null && pd.isBedrockPlayer());

        if (confidenceLevel.equals("EXTREME") || confidenceLevel.equals("HIGH")) {
            sendImmediateAlert(entry);
        } else {
            addToBatch(entry);
        }

        if (discordWebhook != null && shouldSendToDiscord(confidenceLevel, severity)) {
            int count = throttle.getViolationCount(type);
            sendDiscordAlert(player.getName(), type, severity, details, confidenceLevel, score, detectionMethod, count, variant);
        }
    }

    private void addToBatch(AlertEntry entry) {
        AlertBatch batch = pendingBatches.computeIfAbsent(entry.playerId,
                k -> new AlertBatch(entry.playerId));
        batch.add(entry);

        if (batch.alerts.size() >= MAX_BATCH_SIZE) {
            if (pendingBatches.remove(entry.playerId, batch)) {
                sendBatchedAlert(batch);
            }
        }
    }

    private void sendImmediateAlert(AlertEntry entry) {
        String hoverInfo = buildHoverInfo(entry);
        TextComponent alert = buildClickableAlert(entry, 1, hoverInfo);

        List<Player> online = List.copyOf(Bukkit.getOnlinePlayers());
        for (Player staff : online) {
            if (staff.hasPermission("nochance.alerts") && alertsEnabled.contains(staff.getUniqueId())) {
                staff.spigot().sendMessage(alert);
            }
        }
    }

    private void sendBatchedAlert(AlertBatch batch) {
        if (batch.alerts.isEmpty()) return;

        TextComponent alert = buildBatchedAlert(batch);

        List<Player> online = List.copyOf(Bukkit.getOnlinePlayers());
        for (Player staff : online) {
            if (staff.hasPermission("nochance.alerts") && alertsEnabled.contains(staff.getUniqueId())) {
                staff.spigot().sendMessage(alert);
            }
        }
    }

    private TextComponent buildBatchedAlert(AlertBatch batch) {
        Map<ViolationType, Integer> typeCounts = new LinkedHashMap<>();
        double maxScore = 0;
        String worstConfidence = "LOW";

        for (AlertEntry e : batch.alerts) {
            typeCounts.merge(e.type, 1, Integer::sum);
            if (e.score > maxScore) {
                maxScore = e.score;
                worstConfidence = e.confidenceLevel;
            }
        }

        AlertEntry latest = batch.alerts.get(batch.alerts.size() - 1);
        String name = latest.playerName;
        Location loc = latest.location;
        int ping = latest.ping;
        boolean bedrock = latest.bedrock;
        Player live = Bukkit.getPlayer(batch.playerId);
        if (live != null) {
            name = live.getName();
            loc = live.getLocation();
            ping = Math.max(0, live.getPing());
        }

        String confColor = getColorByConfidence(worstConfidence);
        StringBuilder typeStr = new StringBuilder();
        int i = 0;
        for (Map.Entry<ViolationType, Integer> e : typeCounts.entrySet()) {
            if (i > 0) typeStr.append("§8, ");
            typeStr.append(confColor).append(e.getKey().getDisplayName());
            if (e.getValue() > 1) {
                typeStr.append("§7x").append(e.getValue());
            }
            i++;
        }

        String tpCommand = String.format("/tp %s %d %d %d",
                name,
                loc.getBlockX(),
                loc.getBlockY(),
                loc.getBlockZ());

        String bedrockTag = bedrock ? " §d[BE]" : "";

        TextComponent prefix = new TextComponent(lang("prefix"));

        TextComponent typeComp = new TextComponent(typeStr.toString() + " ");
        typeComp.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new Text(buildBatchHover(batch))));

        String batchClientHint = null;
        boolean directMatch = false;
        for (AlertEntry e : batch.alerts) {
            String h = extractClientHint(e.details);
            if (h != null) {
                batchClientHint = h;
                directMatch = true;
                break;
            }
        }
        if (batchClientHint == null && packetFingerprint != null) {
            batchClientHint = packetFingerprint.getRecentClient(batch.playerId);
        }

        TextComponent playerComp = new TextComponent("§7" + name + bedrockTag + " ");
        playerComp.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, tpCommand));
        playerComp.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                new Text(lang("alert.click_teleport") + "\n" + lang("alert.ping", "ping", ping))));

        TextComponent scoreComp = new TextComponent(confColor + String.format("%.0f%%", maxScore * 100));
        if (batch.alerts.size() > 1) {
            scoreComp.addExtra(" §7(" + batch.alerts.size() + " flags)");
        }

        TextComponent actions = new TextComponent(" §8[§7⚡§8]");
        actions.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/nc freeze " + name));
        actions.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new Text(lang("alert.click_freeze"))));

        TextComponent spectate = new TextComponent(" §8[§7👁§8]");
        spectate.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/nc spectate " + name));
        spectate.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new Text(lang("alert.click_spectate"))));

        prefix.addExtra(typeComp);
        if (batchClientHint != null) {
            TextComponent clientComp;
            if (directMatch) {
                clientComp = new TextComponent("§8[§b" + batchClientHint + "§8] ");
                clientComp.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                        new Text(lang("alert.fingerprint_direct"))));
            } else {
                clientComp = new TextComponent("§8(seen " + batchClientHint + "§8) ");
                clientComp.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                        new Text(lang("alert.fingerprint_session", "client", batchClientHint))));
            }
            prefix.addExtra(clientComp);
        }
        prefix.addExtra(playerComp);
        prefix.addExtra(scoreComp);
        prefix.addExtra(actions);
        prefix.addExtra(spectate);

        return prefix;
    }

    private static String sanitizeDetails(String details) {
        if (details == null) return null;
        String s = details;
        if (s.toLowerCase().contains("chat:")) {
            s = s.replaceAll("(?i)chat:\\s*\"[^\"]{0,1000}\"", "chat:[redacted]");
            s = s.replaceAll("(?i)chat:\\s*\\S+", "chat:[redacted]");
        }
        s = s.replaceAll("\"[^\"]{100,}\"", "\"[redacted]\"");
        return s;
    }

    private static String extractClientHint(String details) {
        if (details == null) return null;
        int start = details.indexOf("[Fingerprint] ");
        if (start < 0) return null;
        int nameStart = start + "[Fingerprint] ".length();
        int parenStart = details.indexOf(" (", nameStart);
        if (parenStart < 0 || parenStart <= nameStart) return null;
        return details.substring(nameStart, parenStart).trim();
    }

    private String lang(String key, Object... placeholders) {
        if (lang == null) return "";
        return lang.get(key, placeholders);
    }

    private String buildBatchHover(AlertBatch batch) {
        AlertEntry latest = batch.alerts.get(batch.alerts.size() - 1);
        String name = latest.playerName;
        int ping = latest.ping;
        Player live = Bukkit.getPlayer(batch.playerId);
        if (live != null) {
            name = live.getName();
            ping = Math.max(0, live.getPing());
        }

        StringBuilder sb = new StringBuilder();
        sb.append("§7Player: §e").append(name).append("\n");
        sb.append("§7Flags: §f").append(batch.alerts.size()).append("\n");
        sb.append("§7Ping: §f").append(ping).append("ms\n");
        sb.append("§8───────────────\n");

        int shown = 0;
        for (AlertEntry e : batch.alerts) {
            if (shown >= 5) {
                sb.append("§8... and ").append(batch.alerts.size() - shown).append(" more\n");
                break;
            }
            sb.append("§7• §c").append(e.type.getDisplayName());
            sb.append(" §f").append(String.format("%.0f%%", e.score * 100)).append("\n");
            shown++;
        }

        return sb.toString();
    }

    private String buildHoverInfo(AlertEntry entry) {
        return String.format(
            "§7Player: §e%s\n" +
            "§7Type: §c%s\n" +
            "§7Severity: §f%.2f\n" +
            "§7Confidence: %s\n" +
            "§7Score: §f%.1f%%\n" +
            "§7Method: §e%s\n" +
            "§7Details: §7%s\n" +
            "§7Ping: §f%dms\n" +
            "§7TPS: §f%.1f",
            entry.playerName, entry.type.getDisplayName(), entry.severity, entry.confidenceLevel,
            entry.score * 100, entry.detectionMethod, entry.details,
            entry.ping, getTPS()
        );
    }

    private TextComponent buildClickableAlert(AlertEntry entry, int count, String hoverInfo) {
        String confColor = getColorByConfidence(entry.confidenceLevel);
        String variantTag = "";
        String countSuffix = count > 1 ? " §7x" + count : "";

        String tpCommand = String.format("/tp %s %d %d %d",
                entry.playerName,
                entry.location.getBlockX(),
                entry.location.getBlockY(),
                entry.location.getBlockZ());

        String bedrockTag = entry.bedrock ? " §d[BE]" : "";

        TextComponent prefix = new TextComponent(lang("prefix"));

        TextComponent typeComp = new TextComponent(confColor + entry.type.getDisplayName() + " ");
        typeComp.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new Text(hoverInfo)));

        String clientHint = extractClientHint(entry.details);
        TextComponent clientComp = null;
        if (clientHint != null) {
            clientComp = new TextComponent("§8[§b" + clientHint + "§8] ");
            clientComp.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                    new Text(lang("alert.fingerprint_direct"))));
        } else if (packetFingerprint != null) {
            String session = packetFingerprint.getRecentClient(entry.playerId);
            if (session != null) {
                clientComp = new TextComponent("§8(seen " + session + "§8) ");
                clientComp.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                        new Text(lang("alert.fingerprint_session", "client", session))));
            }
        }

        TextComponent playerComp = new TextComponent("§7" + entry.playerName + bedrockTag + " ");
        playerComp.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, tpCommand));
        playerComp.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                new Text(lang("alert.click_teleport") + "\n" + lang("alert.ping", "ping", entry.ping))));

        TextComponent scoreComp = new TextComponent(confColor + String.format("%.0f%%", entry.score * 100) + countSuffix);

        TextComponent actions = new TextComponent(" §8[§7⚡§8]");
        actions.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/nc freeze " + entry.playerName));
        actions.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new Text(lang("alert.click_freeze"))));

        TextComponent spectate = new TextComponent(" §8[§7👁§8]");
        spectate.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/nc spectate " + entry.playerName));
        spectate.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new Text(lang("alert.click_spectate"))));

        prefix.addExtra(variantTag);
        prefix.addExtra(typeComp);
        if (clientComp != null) {
            prefix.addExtra(clientComp);
        }
        prefix.addExtra(playerComp);
        prefix.addExtra(scoreComp);
        prefix.addExtra(actions);
        prefix.addExtra(spectate);

        return prefix;
    }

    private String getColorByConfidence(String confidence) {
        switch (confidence) {
            case "EXTREME": return "§4§l";
            case "HIGH": return "§c";
            case "MEDIUM": return "§6";
            case "LOW": return "§e";
            default: return "§7";
        }
    }

    private String getCategory(ViolationType type) {
        String name = type.getDisplayName();
        if (name.contains("FLY") || name.contains("SPEED") || name.contains("NOCLIP") || name.contains("JESUS") || name.contains("NOSLOW")) {
            return "Movement";
        } else if (name.contains("KILLAURA") || name.contains("REACH") || name.contains("AUTOCLICKER")) {
            return "Combat";
        } else if (name.contains("SCAFFOLD") || name.contains("FASTPLACE") || name.contains("NUKER") || name.contains("FASTBREAK")) {
            return "Block";
        } else if (name.contains("BLINK") || name.contains("TIMER")) {
            return "Packet";
        }
        return "Other";
    }

    private boolean shouldSendToDiscord(String confidenceLevel, double severity) {
        if (confidenceLevel.equals("EXTREME")) return true;
        if (confidenceLevel.equals("HIGH")) return true;
        if (confidenceLevel.equals("MEDIUM") && severity >= 0.8) return true;
        return false;
    }

    private void sendDiscordAlert(String playerName, ViolationType type, double severity, String details,
                                  String confidenceLevel, double score, String detectionMethod, int count, String variant) {
        CompletableFuture.runAsync(() -> {
            try {
                JsonObject embed = new JsonObject();

                String checkName = type.getDisplayName();
                String countText = count > 1 ? " x" + count : "";

                embed.addProperty("title", checkName + countText);
                embed.addProperty("color", getDiscordColor(confidenceLevel));

                embed.addProperty("description",
                    "**" + playerName + "** — " + confidenceLevel.toLowerCase()
                    + " (" + String.format("%.0f%%", score * 100) + ")");

                JsonArray fields = new JsonArray();
                fields.add(field("Severity", String.format("%.2f", severity), true));
                fields.add(field("Category", getCategory(type), true));
                fields.add(field("TPS", String.format("%.1f", getTPS()), true));

                if (detectionMethod != null && !detectionMethod.isEmpty()) {
                    fields.add(field("Method", detectionMethod, true));
                }
                if (variant != null && !variant.isEmpty() && !variant.equalsIgnoreCase("default")) {
                    fields.add(field("Variant", variant, true));
                }
                if (details != null && !details.isEmpty()) {
                    String body = details.length() > 900 ? details.substring(0, 900) + "…" : details;
                    fields.add(field("Details", "```" + body + "```", false));
                }

                embed.add("fields", fields);
                embed.add("thumbnail", thumbnail(playerName));

                SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'");
                sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
                embed.addProperty("timestamp", sdf.format(new Date()));

                JsonObject payload = new JsonObject();
                payload.addProperty("username", "NoChance");
                JsonArray embeds = new JsonArray();
                embeds.add(embed);
                payload.add("embeds", embeds);

                postWebhook(payload);
            } catch (Exception e) {
                plugin.getLogger().warning("Failed to send Discord alert: " + e.getMessage());
            }
        });
    }

    private JsonObject field(String name, String value, boolean inline) {
        JsonObject f = new JsonObject();
        f.addProperty("name", name);
        f.addProperty("value", value);
        f.addProperty("inline", inline);
        return f;
    }

    private JsonObject thumbnail(String playerName) {
        JsonObject t = new JsonObject();
        t.addProperty("url", "https://mc-heads.net/avatar/" + playerName + "/64");
        return t;
    }

    public void sendPunishmentWebhook(String playerName, ViolationType type, String actionType,
                                      String duration, String reason, String confidenceLevel) {
        if (webBridge != null) {
            long durMs = parseDurationMs(duration);
            UUID pid = resolveUuidByName(playerName);
            webBridge.telemetry().recordPunishment(playerName, pid, actionType, reason,
                    type == null ? null : type.name(), confidenceLevel, durMs);
            webBridge.scheduleFastFlush();
        }

        if (discordWebhook == null || discordWebhook.isEmpty()) return;

        CompletableFuture.runAsync(() -> {
            try {
                JsonObject embed = new JsonObject();
                String action = actionType == null ? "Action" : actionType;
                embed.addProperty("title", action.charAt(0) + action.substring(1).toLowerCase() + " — " + playerName);
                embed.addProperty("color", getPunishmentColor(actionType));

                StringBuilder desc = new StringBuilder();
                desc.append("**").append(playerName).append("**");
                if (type != null) {
                    desc.append(" for ").append(type.getDisplayName());
                }
                if (confidenceLevel != null && !confidenceLevel.isEmpty()) {
                    desc.append(" (").append(confidenceLevel.toLowerCase()).append(")");
                }
                embed.addProperty("description", desc.toString());

                JsonArray fields = new JsonArray();
                if (duration != null && !duration.isEmpty()) {
                    fields.add(field("Duration", duration, true));
                }

                String cleanReason = stripMinecraftColors(reason);
                if (cleanReason != null && !cleanReason.isEmpty()) {
                    String body = cleanReason.length() > 900 ? cleanReason.substring(0, 900) + "…" : cleanReason;
                    fields.add(field("Reason", "```" + body + "```", false));
                }
                embed.add("fields", fields);

                embed.add("thumbnail", thumbnail(playerName));

                SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'");
                sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
                embed.addProperty("timestamp", sdf.format(new Date()));

                JsonObject payload = new JsonObject();
                payload.addProperty("username", "NoChance");
                JsonArray embeds = new JsonArray();
                embeds.add(embed);
                payload.add("embeds", embeds);

                postWebhook(payload);
            } catch (Exception e) {
                plugin.getLogger().warning("Failed to send punishment webhook: " + e.getMessage());
            }
        });
    }

    private String stripMinecraftColors(String s) {
        if (s == null) return null;
        return s.replaceAll("§[0-9a-fklmnor]", "").trim();
    }

    private long parseDurationMs(String d) {
        if (d == null || d.isEmpty() || d.equalsIgnoreCase("Permanent")) return 0L;
        try {
            long total = 0L;
            StringBuilder num = new StringBuilder();
            for (char c : d.toCharArray()) {
                if (Character.isDigit(c)) { num.append(c); continue; }
                if (num.length() == 0) continue;
                long v = Long.parseLong(num.toString());
                num.setLength(0);
                switch (Character.toLowerCase(c)) {
                    case 'd': total += v * 86_400_000L; break;
                    case 'h': total += v * 3_600_000L; break;
                    case 'm': total += v * 60_000L; break;
                    case 's': total += v * 1000L; break;
                }
            }
            return total;
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Failed to parse duration string: " + d, e);
            return 0L;
        }
    }

    private UUID resolveUuidByName(String name) {
        if (name == null) return null;
        Player p = Bukkit.getPlayerExact(name);
        if (p != null) return p.getUniqueId();
        try { return Bukkit.getOfflinePlayer(name).getUniqueId(); }
        catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Failed to resolve UUID for player name: " + name, e);
            return null;
        }
    }

    private void postWebhook(JsonObject payload) throws Exception {
        String hook = discordWebhook;
        if (hook == null || hook.isEmpty()) return;
        if (System.currentTimeMillis() - lastRateLimitTime < DISCORD_RATE_LIMIT_COOLDOWN_MS) return;

        URL url = new java.net.URI(hook).toURL();
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        try {
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("User-Agent", "NoChance/" + plugin.getDescription().getVersion() + " (Spigot)");
            conn.setRequestProperty("Accept", "application/json");
            conn.setDoOutput(true);
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);

            try (OutputStream os = conn.getOutputStream()) {
                byte[] input = payload.toString().getBytes(StandardCharsets.UTF_8);
                os.write(input, 0, input.length);
            }

            int responseCode = conn.getResponseCode();
            if (responseCode == 429) {
                lastRateLimitTime = System.currentTimeMillis();
                plugin.getLogger().warning("Discord webhook rate limited (429), suppressing for 5s");
            } else if (responseCode != 204 && responseCode != 200) {
                plugin.getLogger().warning("Discord webhook failed: HTTP " + responseCode);
            }
        } finally {
            conn.disconnect();
        }
    }

    private int getPunishmentColor(String actionType) {
        if (actionType == null) return 0x808080;
        switch (actionType.toUpperCase()) {
            case "BAN": return 0x8B0000;
            case "TEMPBAN": return 0xFF4500;
            case "KICK": return 0xFF8C00;
            case "WARN": return 0xFFD700;
            default: return 0x808080;
        }
    }

    private int getDiscordColor(String confidence) {
        switch (confidence) {
            case "EXTREME": return 0xFF0000;
            case "HIGH": return 0xFF6600;
            case "MEDIUM": return 0xFFAA00;
            case "LOW": return 0xFFFF00;
            default: return 0x808080;
        }
    }

    public void toggleAlerts(UUID uuid) {
        if (alertsEnabled.contains(uuid)) {
            alertsEnabled.remove(uuid);
        } else {
            alertsEnabled.add(uuid);
        }
    }

    public boolean hasAlertsEnabled(UUID uuid) {
        return alertsEnabled.contains(uuid);
    }

    public void enableAlerts(UUID uuid) {
        alertsEnabled.add(uuid);
    }

    public void cleanup(UUID uuid) {
        throttles.remove(uuid);
        alertsEnabled.remove(uuid);
        pendingBatches.remove(uuid);
    }

    private int getPing(Player player) {
        return Math.max(0, player.getPing());
    }

    private double getTPS() {
        return NC.noChance.core.TPSCache.getTPS();
    }

    private static class AlertThrottle {
        private final Map<ViolationType, Long> lastAlertTime = new ConcurrentHashMap<>();
        private final Map<ViolationType, Long> lastViolationTime = new ConcurrentHashMap<>();
        private final Map<ViolationType, Integer> violationCount = new ConcurrentHashMap<>();
        private static final long THROTTLE_MS = 800;
        private static final long RESET_MS = 5000;

        void recordViolation(ViolationType type) {
            long now = System.currentTimeMillis();
            Long lastViolation = lastViolationTime.get(type);

            if (lastViolation != null && now - lastViolation < RESET_MS) {
                violationCount.merge(type, 1, Integer::sum);
            } else {
                violationCount.put(type, 1);
            }
            lastViolationTime.put(type, now);
        }

        int getViolationCount(ViolationType type) {
            return violationCount.getOrDefault(type, 1);
        }

        boolean shouldAlert(ViolationType type) {
            long now = System.currentTimeMillis();
            Long last = lastAlertTime.get(type);

            if (last == null || now - last >= THROTTLE_MS) {
                lastAlertTime.put(type, now);
                return true;
            }
            return false;
        }
    }

    private static class AlertEntry {
        final String playerName;
        final UUID playerId;
        final ViolationType type;
        final double severity;
        final String details;
        final String confidenceLevel;
        final double score;
        final String detectionMethod;
        final Location location;
        final int ping;
        final boolean bedrock;

        AlertEntry(String playerName, UUID playerId, ViolationType type, double severity,
                   String details, String confidenceLevel, double score, String detectionMethod,
                   String variant, Location location, int ping, boolean bedrock) {
            this.playerName = playerName;
            this.playerId = playerId;
            this.type = type;
            this.severity = severity;
            this.details = details;
            this.confidenceLevel = confidenceLevel;
            this.score = score;
            this.detectionMethod = detectionMethod;
            this.location = location;
            this.ping = ping;
            this.bedrock = bedrock;
        }
    }

    private static class AlertBatch {
        final UUID playerId;
        final long firstAlert;
        final List<AlertEntry> alerts;

        AlertBatch(UUID playerId) {
            this.playerId = playerId;
            this.firstAlert = System.currentTimeMillis();
            this.alerts = new java.util.concurrent.CopyOnWriteArrayList<>();
        }

        void add(AlertEntry entry) {
            alerts.add(entry);
        }
    }
}
