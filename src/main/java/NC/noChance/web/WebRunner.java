package NC.noChance.web;

import NC.noChance.NoChance;
import NC.noChance.core.ACConfig;
import NC.noChance.core.PlayerData;
import NC.noChance.core.ViolationType;
import NC.noChance.database.DatabaseManager;
import NC.noChance.staff.StaffTools;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.bukkit.BanList;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.util.Date;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

public class WebRunner {
    private final NoChance plugin;
    private final ACConfig config;
    private final WebBridge bridge;

    public WebRunner(NoChance plugin, ACConfig config, WebBridge bridge) {
        this.plugin = plugin;
        this.config = config;
        this.bridge = bridge;
    }

    public void dispatch(JsonObject cmd) {
        String id = getStr(cmd, "id");
        String type = getStr(cmd, "type");
        JsonObject payload = cmd.has("payload") && cmd.get("payload").isJsonObject()
                ? cmd.getAsJsonObject("payload") : new JsonObject();
        Bukkit.getScheduler().runTask(plugin, () -> run(id, type, payload));
    }

    private void run(String id, String type, JsonObject p) {
        JsonObject ack = new JsonObject();
        ack.addProperty("id", id);
        ack.addProperty("type", type);
        ack.addProperty("ts", System.currentTimeMillis());
        try {
            String msg;
            switch (type == null ? "" : type.toLowerCase()) {
                case "kick": msg = kick(p); break;
                case "ban": msg = ban(p, true); break;
                case "tempban": msg = ban(p, false); break;
                case "warn": msg = warn(p); break;
                case "unban": msg = unban(p); break;
                case "freeze": msg = freeze(p, true); break;
                case "unfreeze": msg = freeze(p, false); break;
                case "toggle_check": msg = toggleCheck(p); break;
                case "set_check_enabled": msg = setCheckEnabled(p); break;
                case "reload": msg = reload(); break;
                case "set_config": msg = setConfig(p); break;
                case "reset_player": msg = resetPlayer(p); break;
                case "broadcast": msg = broadcast(p); break;
                case "fp_flag": msg = markFalsePositive(p); break;
                case "tp_flag": msg = markTruePositive(p); break;
                case "ai_verdict": msg = applyAIVerdict(p); break;
                case "unlink": msg = unlink(); break;
                default:
                    ack.addProperty("ok", false);
                    ack.addProperty("error", "unknown command: " + type);
                    bridge.pushAck(ack);
                    return;
            }
            ack.addProperty("ok", true);
            if (msg != null) ack.addProperty("message", msg);
        } catch (Exception e) {
            ack.addProperty("ok", false);
            ack.addProperty("error", e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        bridge.pushAck(ack);
    }

    private String kick(JsonObject p) {
        Player t = resolvePlayer(p);
        if (t == null) throw new IllegalArgumentException("player not online");
        String reason = getStr(p, "reason", "Kicked by staff");
        t.kickPlayer(color(reason));
        bridge.telemetry().recordPunishment(t.getName(), t.getUniqueId(), "KICK", reason, null, null, 0L);
        bridge.scheduleFastFlush();
        return "kicked " + t.getName();
    }

    private String ban(JsonObject p, boolean permanent) {
        UUID uuid = resolveUuid(p);
        String name = resolveName(p);
        long durationMs = permanent ? 0L : Math.max(60_000L,
                p.has("duration_ms") ? p.get("duration_ms").getAsLong() : 3_600_000L);
        String reason = getStr(p, "reason", permanent ? "Banned by staff" : "Temporarily banned");
        String issuedBy = getStr(p, "issued_by", "Web");

        DatabaseManager db = plugin.getDatabase();
        if (db != null) {
            db.logPunishment(uuid, name == null ? uuid.toString() : name,
                    permanent ? DatabaseManager.PunishmentType.BAN : DatabaseManager.PunishmentType.TEMPBAN,
                    reason, durationMs, issuedBy);
        }

        Date expires = permanent ? null : new Date(System.currentTimeMillis() + durationMs);
        if (name != null) {
            Bukkit.getBanList(BanList.Type.NAME).addBan(name, reason, expires, issuedBy);
        }
        Player online = Bukkit.getPlayer(uuid);
        if (online != null) online.kickPlayer(color(reason));

        bridge.telemetry().recordPunishment(name == null ? uuid.toString() : name, uuid,
                permanent ? "BAN" : "TEMPBAN", reason, null, null, durationMs);
        bridge.scheduleFastFlush();
        return (permanent ? "banned " : "tempbanned ") + (name == null ? uuid.toString() : name);
    }

    private String unban(JsonObject p) {
        UUID uuid = resolveUuid(p);
        String name = resolveName(p);
        if (name != null) {
            Bukkit.getBanList(BanList.Type.NAME).pardon(name);
        }
        bridge.telemetry().recordPunishment(name == null ? uuid.toString() : name, uuid,
                "UNBAN", null, null, null, 0L);
        bridge.scheduleFastFlush();
        return "unbanned " + (name == null ? "player" : name);
    }

    private String warn(JsonObject p) {
        Player t = resolvePlayer(p);
        if (t == null) throw new IllegalArgumentException("player not online");
        String reason = getStr(p, "reason", "Warning");
        t.sendMessage(color("&c&lNoChance &8» &7" + reason));
        bridge.telemetry().recordPunishment(t.getName(), t.getUniqueId(), "WARN", reason, null, null, 0L);
        bridge.scheduleFastFlush();
        return "warned " + t.getName();
    }

    private String freeze(JsonObject p, boolean on) {
        StaffTools st = plugin.getStaffTools();
        if (st == null) throw new IllegalStateException("staff tools unavailable");
        Player t = resolvePlayer(p);
        if (t == null) throw new IllegalArgumentException("player not online");
        Player actor = t;
        if (p.has("actor_uuid")) {
            try {
                Player a = Bukkit.getPlayer(UUID.fromString(p.get("actor_uuid").getAsString()));
                if (a != null) actor = a;
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "Failed to resolve actor_uuid in freeze command", e);
            }
        }
        boolean isFrozen = st.isFrozen(t.getUniqueId());
        boolean target = on;
        if (!p.has("explicit")) target = !isFrozen;
        if (target != isFrozen) {
            st.freezePlayer(actor, t);
        }
        return (target ? "froze " : "unfroze ") + t.getName();
    }

    private String toggleCheck(JsonObject p) {
        String name = requireStr(p, "check");
        String path = "checks." + name.toLowerCase() + ".enabled";
        boolean current = config.isCheckEnabled(name.toLowerCase());
        plugin.getConfig().set(path, !current);
        plugin.saveConfig();
        config.reload();
        bridge.flushSettings();
        return "check " + name + " " + (!current ? "enabled" : "disabled");
    }

    private String setCheckEnabled(JsonObject p) {
        String name = requireStr(p, "check");
        boolean enabled = p.has("enabled") && p.get("enabled").getAsBoolean();
        plugin.getConfig().set("checks." + name.toLowerCase() + ".enabled", enabled);
        plugin.saveConfig();
        config.reload();
        bridge.flushSettings();
        return "check " + name + " set to " + enabled;
    }

    private String setConfig(JsonObject p) {
        if (!p.has("entries") || !p.get("entries").isJsonArray()) {
            throw new IllegalArgumentException("entries missing");
        }
        JsonArray entries = p.getAsJsonArray("entries");
        int applied = 0;
        for (JsonElement el : entries) {
            if (!el.isJsonObject()) continue;
            JsonObject e = el.getAsJsonObject();
            String path = getStr(e, "path");
            if (path == null || !isPathAllowed(path)) continue;
            Object value = toPrimitive(e.get("value"));
            if (!isValueSane(path, value)) continue;
            plugin.getConfig().set(path, value);
            applied++;
        }
        if (applied > 0) {
            plugin.saveConfig();
            config.reload();
            bridge.flushSettings();
        }
        return "applied " + applied + " setting(s)";
    }

    private String reload() {
        config.reload();
        if (plugin.getLangManager() != null) plugin.getLangManager().reload(config.getLanguage(), config.isForceServerLanguage());
        bridge.flushSettings();
        return "config reloaded";
    }

    private String resetPlayer(JsonObject p) {
        UUID uuid = resolveUuid(p);
        Map<UUID, PlayerData> map = plugin.getPlayerDataMap();
        if (map != null) map.put(uuid, new PlayerData(uuid));
        return "reset " + uuid;
    }

    private String broadcast(JsonObject p) {
        String raw = getStr(p, "message", "");
        if (raw == null || raw.trim().isEmpty()) throw new IllegalArgumentException("empty message");
        if (raw.length() > 200) raw = raw.substring(0, 200);
        Bukkit.broadcastMessage(color(raw));
        return "broadcasted";
    }

    private String markFalsePositive(JsonObject p) {
        UUID uuid = resolveUuid(p);
        String check = getStr(p, "check", "UNKNOWN");
        String flagId = getStr(p, "flag_id", null);
        try {
            ViolationType type = ViolationType.valueOf(check.toUpperCase());
            bridge.telemetry().recordFalsePositive(uuid, type, flagId);
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Failed to record false positive for check=" + check, e);
        }
        bridge.scheduleFastFlush();
        plugin.getLogger().info("Web marked FP uuid=" + uuid + " check=" + check);
        return "fp recorded";
    }

    private String markTruePositive(JsonObject p) {
        UUID uuid = resolveUuid(p);
        String check = getStr(p, "check", "UNKNOWN");
        String flagId = getStr(p, "flag_id", null);
        try {
            ViolationType type = ViolationType.valueOf(check.toUpperCase());
            bridge.telemetry().recordTruePositive(uuid, type, flagId);
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Failed to record true positive for check=" + check, e);
        }
        bridge.scheduleFastFlush();
        plugin.getLogger().info("Web confirmed TP uuid=" + uuid + " check=" + check);
        return "tp recorded";
    }

    private String applyAIVerdict(JsonObject p) {
        if (!config.isAIEnabled()) return "ai disabled";
        String check = getStr(p, "check", "UNKNOWN");
        String uuidStr = getStr(p, "uuid", null);
        String verdict = getStr(p, "verdict", "neutral");
        double adjust = p.has("adjust") ? p.get("adjust").getAsDouble() : 0.0;
        double agreement = p.has("agreement") ? p.get("agreement").getAsDouble() : 0.0;
        if (agreement < config.getAIMinAgreement()) return "below min agreement";
        double cap = config.getAIMaxAdjustment();
        adjust = Math.max(-cap, Math.min(cap, adjust));
        UUID uuid = null;
        try { if (uuidStr != null) uuid = UUID.fromString(uuidStr); } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Invalid UUID in AI verdict: " + uuidStr, e);
        }
        try {
            ViolationType type = ViolationType.valueOf(check.toUpperCase());
            if (uuid != null) plugin.getDetectionEngine().applyAIVerdict(uuid, type, verdict, adjust, agreement);
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Failed to apply AI verdict for check=" + check, e);
        }
        return "ai verdict " + verdict + " adj=" + String.format("%.2f", adjust);
    }

    private String unlink() {
        bridge.unlink();
        return "server unlinked";
    }

    private Player resolvePlayer(JsonObject p) {
        if (p.has("uuid")) {
            try { return Bukkit.getPlayer(UUID.fromString(p.get("uuid").getAsString())); }
            catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "Failed to resolve player UUID: " + p.get("uuid").getAsString(), e);
            }
        }
        if (p.has("name")) return Bukkit.getPlayerExact(p.get("name").getAsString());
        return null;
    }

    private UUID resolveUuid(JsonObject p) {
        if (p.has("uuid")) {
            try {
                return UUID.fromString(p.get("uuid").getAsString());
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("invalid uuid format");
            }
        }
        if (p.has("name")) return Bukkit.getOfflinePlayer(p.get("name").getAsString()).getUniqueId();
        throw new IllegalArgumentException("uuid or name required");
    }

    private String resolveName(JsonObject p) {
        if (p.has("name")) return p.get("name").getAsString();
        if (p.has("uuid")) {
            try {
                OfflinePlayer op = Bukkit.getOfflinePlayer(UUID.fromString(p.get("uuid").getAsString()));
                String n = op.getName();
                return n;
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
        return null;
    }

    private static final java.util.Set<String> ALLOWED_GENERAL = java.util.Set.of(
            "general.time_window_seconds",
            "general.grace_period_seconds",
            "general.teleport_grace_period_seconds",
            "general.min_samples",
            "general.op_exempt",
            "general.notify_player_on_flag"
    );

    private boolean isValueSane(String path, Object value) {
        if (path == null) return false;
        if (value == null) return true;
        if (value instanceof Number) {
            double v = ((Number) value).doubleValue();
            if (Double.isNaN(v) || Double.isInfinite(v)) return false;
            if (path.contains("severity_multiplier") || path.contains("threshold") || path.contains("multiplier")) {
                return v >= 0.0 && v <= 5.0;
            }
            if (path.contains("min_samples") || path.contains("min_violations") || path.contains("required")) {
                return v >= 1 && v <= 200;
            }
            if (path.contains("seconds") || path.contains("ms") || path.contains("interval")) {
                return v >= 0 && v <= 86400000;
            }
        }
        return true;
    }

    private boolean isPathAllowed(String path) {
        if (path == null) return false;
        if (path.startsWith("web.") || path.startsWith("database.") || path.startsWith("discord.")) return false;
        if (path.startsWith("display.")) return true;
        if (path.startsWith("alerts.")) return true;
        if (path.startsWith("advanced_filtering.")) return true;
        if (path.startsWith("bedrock.")) return true;
        if (path.startsWith("ping_compensation.")) return true;
        if (path.startsWith("replay.")) return true;
        if (path.startsWith("skill_profiles.")) return true;
        if (path.startsWith("statistical.")) return true;
        if (path.startsWith("ai.")) return true;
        if (path.equals("language")) return true;
        return ALLOWED_GENERAL.contains(path);
    }

    private Object toPrimitive(JsonElement el) {
        if (el == null || el.isJsonNull()) return null;
        if (el.isJsonPrimitive()) {
            var prim = el.getAsJsonPrimitive();
            if (prim.isBoolean()) return prim.getAsBoolean();
            if (prim.isNumber()) {
                double d = prim.getAsDouble();
                if (d == Math.floor(d) && !Double.isInfinite(d) && Math.abs(d) < Integer.MAX_VALUE) {
                    return (int) d;
                }
                return d;
            }
            return prim.getAsString();
        }
        if (el.isJsonArray()) {
            JsonArray arr = el.getAsJsonArray();
            java.util.List<Object> out = new java.util.ArrayList<>(arr.size());
            for (JsonElement child : arr) {
                Object v = toPrimitive(child);
                if (v != null) out.add(v);
            }
            return out;
        }
        return el.toString();
    }

    private String requireStr(JsonObject o, String k) {
        String v = getStr(o, k, null);
        if (v == null) throw new IllegalArgumentException(k + " required");
        return v;
    }

    private String getStr(JsonObject o, String k) { return getStr(o, k, null); }

    private String getStr(JsonObject o, String k, String d) {
        if (o == null || !o.has(k) || o.get(k).isJsonNull()) return d;
        return o.get(k).getAsString();
    }

    private String color(String s) {
        if (s == null) return "";
        return s.replace('&', '\u00a7');
    }
}
