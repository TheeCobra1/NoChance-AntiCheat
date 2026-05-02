package NC.noChance.web;

import NC.noChance.core.PlayerData;
import NC.noChance.core.ViolationType;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.util.Deque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.concurrent.atomic.DoubleAdder;
import java.util.concurrent.atomic.LongAdder;

public class WebTelemetry {
    private static final int MAX_EVENTS = 400;
    private static final AtomicLong FLAG_SEQ = new AtomicLong();

    private final ConcurrentLinkedDeque<JsonObject> events = new ConcurrentLinkedDeque<>();
    private final Map<ViolationType, CheckStats> byCheck = new ConcurrentHashMap<>();
    private final Map<String, LongAdder> byConfidence = new ConcurrentHashMap<>();
    private final AtomicLong totalFlags = new AtomicLong();
    private final AtomicLong totalPunishments = new AtomicLong();
    private final AtomicLong totalFp = new AtomicLong();
    private final AtomicLong totalTp = new AtomicLong();
    private final AtomicLong sessionCount = new AtomicLong();

    public void recordFlag(String playerName, UUID uuid, ViolationType type, double severity,
                           String confidence, double score, String method, double trustScore,
                           int ping, double tps) {
        recordFlag(playerName, uuid, type, severity, confidence, score, method, trustScore, ping, tps, null, null);
    }

    public void recordFlag(String playerName, UUID uuid, ViolationType type, double severity,
                           String confidence, double score, String method, double trustScore,
                           int ping, double tps, Player player, PlayerData pd) {
        totalFlags.incrementAndGet();
        byCheck.computeIfAbsent(type, k -> new CheckStats()).record(severity, score);
        byConfidence.computeIfAbsent(confidence == null ? "UNKNOWN" : confidence, k -> new LongAdder()).increment();

        if (confidence != null) {
            JsonObject e = new JsonObject();
            e.addProperty("id", "flag_" + System.currentTimeMillis() + "_" + Long.toHexString(FLAG_SEQ.incrementAndGet()));
            e.addProperty("kind", "flag");
            if (uuid != null) e.addProperty("player_uuid", uuid.toString());
            if (playerName != null) e.addProperty("player_name", playerName);
            e.addProperty("check", type == null ? "UNKNOWN" : type.name());
            e.addProperty("severity", round(severity));
            e.addProperty("confidence", confidence);
            e.addProperty("score", round(score));
            if (method != null) e.addProperty("method", method);
            e.addProperty("trust", round(trustScore));
            e.addProperty("ping", ping);
            e.addProperty("tps", round(tps));
            e.addProperty("ts", System.currentTimeMillis());
            if (player != null) {
                e.add("features", buildFeatures(player, pd, severity, score, trustScore, ping, tps));
                if (pd != null) {
                    e.add("summary", buildSummary(pd, trustScore, ping, tps));
                    e.add("replay_window", buildReplayWindow(pd));
                }
            }
            append(e);
        }
    }

    private JsonObject buildFeatures(Player player, PlayerData pd, double severity, double score,
                                     double trust, int ping, double tps) {
        JsonObject f = new JsonObject();
        Location loc = player.getLocation();
        Vector v = player.getVelocity();
        f.addProperty("vl", pd == null ? 0 : pd.getTotalViolations());
        f.addProperty("x", round(loc.getX()));
        f.addProperty("y", round(loc.getY()));
        f.addProperty("z", round(loc.getZ()));
        f.addProperty("yaw", round(loc.getYaw()));
        f.addProperty("pitch", round(loc.getPitch()));
        f.addProperty("vx", round(v.getX()));
        f.addProperty("vy", round(v.getY()));
        f.addProperty("vz", round(v.getZ()));
        f.addProperty("speed", round(Math.sqrt(v.getX() * v.getX() + v.getZ() * v.getZ())));
        f.addProperty("cps", pd == null ? 0 : recentCps(pd));
        f.addProperty("ping", ping);
        f.addProperty("tps", round(tps));
        f.addProperty("severity", round(severity));
        f.addProperty("confidence", round(score));
        f.addProperty("on_ground", bool(player.isOnGround()));
        f.addProperty("sprinting", bool(player.isSprinting()));
        f.addProperty("sneaking", bool(player.isSneaking()));
        f.addProperty("swimming", bool(player.isSwimming()));
        f.addProperty("gliding", bool(player.isGliding()));
        f.addProperty("in_vehicle", bool(player.isInsideVehicle()));
        f.addProperty("in_water", bool(player.isInWater()));
        f.addProperty("in_lava", bool(safeInLava(player)));
        f.addProperty("on_ladder", bool(onLadder(player)));
        f.addProperty("ticks_since_damage", pd == null ? -1 : Math.min(1200, (int) ((System.currentTimeMillis() - pd.getLastDamageTime()) / 50)));
        f.addProperty("ticks_since_move", pd == null ? -1 : Math.min(1200, (int) ((System.currentTimeMillis() - pd.getLastMoveTime()) / 50)));
        f.addProperty("ticks_since_ground", pd == null ? -1 : Math.min(1200, (int) ((System.currentTimeMillis() - pd.getLastGroundTime()) / 50)));
        f.addProperty("ticks_since_teleport", pd == null ? -1 : Math.min(1200, (int) ((System.currentTimeMillis() - pd.getLastTeleportTime()) / 50)));
        f.addProperty("air_ticks", pd == null ? 0 : pd.getAirTicks());
        f.addProperty("ground_ticks", pd == null ? 0 : pd.getGroundTicks());
        try {
            Block below = loc.clone().subtract(0, 1, 0).getBlock();
            Block inside = loc.getBlock();
            f.addProperty("block_below_hardness", below == null ? 0 : below.getType().getHardness());
            f.addProperty("block_inside_id", inside == null || inside.getType() == Material.AIR ? 0 : inside.getType().ordinal());
            f.addProperty("light_level", inside == null ? 0 : inside.getLightLevel());
        } catch (Exception e) {
            Logger.getLogger("NoChance").log(Level.WARNING, "Failed to collect block telemetry for " + player.getName(), e);
        }
        try {
            f.addProperty("potion_speed", effectAmp(player, PotionEffectType.SPEED));
            f.addProperty("potion_slow", effectAmp(player, PotionEffectType.SLOWNESS));
            f.addProperty("potion_jump", effectAmp(player, PotionEffectType.JUMP_BOOST));
        } catch (Exception e) {
            Logger.getLogger("NoChance").log(Level.WARNING, "Failed to collect potion telemetry for " + player.getName(), e);
        }
        if (pd != null) {
            Deque<PlayerData.RotationData> rots = pd.getRotationHistory();
            if (rots != null && rots.size() >= 2) {
                PlayerData.RotationData last = null;
                double totalYawDelta = 0, totalPitchDelta = 0;
                int n = 0;
                for (PlayerData.RotationData r : rots) {
                    if (last != null) {
                        totalYawDelta += Math.abs(wrap(r.yaw - last.yaw));
                        totalPitchDelta += Math.abs(r.pitch - last.pitch);
                        n++;
                    }
                    last = r;
                }
                if (n > 0) {
                    f.addProperty("yaw_delta", round(totalYawDelta / n));
                    f.addProperty("pitch_delta", round(totalPitchDelta / n));
                    f.addProperty("turn_rate", round(totalYawDelta / Math.max(1, n)));
                }
            }
        }
        return f;
    }

    private JsonObject buildSummary(PlayerData pd, double trust, int ping, double tps) {
        JsonObject s = new JsonObject();
        s.addProperty("avg_cps", recentCps(pd));
        s.addProperty("trust", round(trust));
        s.addProperty("avg_ping", ping);
        s.addProperty("avg_tps", round(tps));
        s.addProperty("flag_rate", pd.getTotalChecks() == 0 ? 0 : round((double) pd.getTotalViolations() / pd.getTotalChecks()));
        return s;
    }

    private JsonArray buildReplayWindow(PlayerData pd) {
        JsonArray arr = new JsonArray();
        Deque<PlayerData.LocationData> positions = pd.getLocationHistory();
        if (positions == null) return arr;
        int limit = 24;
        for (PlayerData.LocationData p : positions) {
            if (limit-- <= 0) break;
            if (p == null || p.location == null) continue;
            Location pl = p.location;
            JsonObject e = new JsonObject();
            e.addProperty("x", round(pl.getX()));
            e.addProperty("y", round(pl.getY()));
            e.addProperty("z", round(pl.getZ()));
            e.addProperty("yaw", round(pl.getYaw()));
            e.addProperty("pitch", round(pl.getPitch()));
            e.addProperty("ts", p.timestamp);
            arr.add(e);
        }
        return arr;
    }

    private static double recentCps(PlayerData pd) {
        Deque<Long> clicks = pd.getClickHistory();
        if (clicks == null || clicks.isEmpty()) return 0;
        long now = System.currentTimeMillis();
        long cutoff = now - 1000;
        int n = 0;
        for (Long c : clicks) if (c != null && c >= cutoff) n++;
        return n;
    }

    private static int effectAmp(Player p, PotionEffectType t) {
        if (p == null || t == null) return 0;
        PotionEffect pe = p.getPotionEffect(t);
        return pe == null ? 0 : pe.getAmplifier() + 1;
    }

    private static boolean safeInLava(Player p) {
        try { return p.getLocation().getBlock().getType() == Material.LAVA; }
        catch (Exception e) {
            Logger.getLogger("NoChance").log(Level.WARNING, "Failed to check lava status for " + p.getName(), e);
            return false;
        }
    }

    private static boolean onLadder(Player p) {
        try {
            Material m = p.getLocation().getBlock().getType();
            return m == Material.LADDER || m == Material.VINE;
        } catch (Exception e) {
            Logger.getLogger("NoChance").log(Level.WARNING, "Failed to check ladder status for " + p.getName(), e);
            return false;
        }
    }

    private static double wrap(double d) {
        while (d > 180) d -= 360;
        while (d < -180) d += 360;
        return d;
    }

    private static int bool(boolean b) { return b ? 1 : 0; }

    public void recordPunishment(String playerName, UUID uuid, String action, String reason,
                                 String check, String confidence, long durationMs) {
        totalPunishments.incrementAndGet();
        JsonObject e = new JsonObject();
        e.addProperty("kind", "punishment");
        if (uuid != null) e.addProperty("player_uuid", uuid.toString());
        if (playerName != null) e.addProperty("player_name", playerName);
        e.addProperty("action", action);
        if (reason != null) e.addProperty("reason", reason);
        if (check != null) e.addProperty("check", check);
        if (confidence != null) e.addProperty("confidence", confidence);
        e.addProperty("duration_ms", durationMs);
        e.addProperty("ts", System.currentTimeMillis());
        append(e);
    }

    public void recordFalsePositive(UUID uuid, ViolationType type) {
        recordFalsePositive(uuid, type, null);
    }

    public void recordFalsePositive(UUID uuid, ViolationType type, String flagId) {
        totalFp.incrementAndGet();
        if (type != null) byCheck.computeIfAbsent(type, k -> new CheckStats()).fp.increment();
        JsonObject e = new JsonObject();
        e.addProperty("kind", "fp");
        if (flagId != null) e.addProperty("flag_id", flagId);
        if (uuid != null) e.addProperty("player_uuid", uuid.toString());
        e.addProperty("check", type == null ? "UNKNOWN" : type.name());
        e.addProperty("ts", System.currentTimeMillis());
        append(e);
    }

    public void recordTruePositive(UUID uuid, ViolationType type) {
        recordTruePositive(uuid, type, null);
    }

    public void recordTruePositive(UUID uuid, ViolationType type, String flagId) {
        totalTp.incrementAndGet();
        if (type != null) byCheck.computeIfAbsent(type, k -> new CheckStats()).tp.increment();
        JsonObject e = new JsonObject();
        e.addProperty("kind", "tp");
        if (flagId != null) e.addProperty("flag_id", flagId);
        if (uuid != null) e.addProperty("player_uuid", uuid.toString());
        e.addProperty("check", type == null ? "UNKNOWN" : type.name());
        e.addProperty("ts", System.currentTimeMillis());
        append(e);
    }

    public void recordSession(UUID uuid, String name, long durationMs, int violations) {
        sessionCount.incrementAndGet();
        JsonObject e = new JsonObject();
        e.addProperty("kind", "session");
        if (uuid != null) e.addProperty("player_uuid", uuid.toString());
        if (name != null) e.addProperty("player_name", name);
        e.addProperty("duration_ms", durationMs);
        e.addProperty("violations", violations);
        e.addProperty("ts", System.currentTimeMillis());
        append(e);
    }

    public JsonArray drainEvents(int max) {
        JsonArray arr = new JsonArray();
        int n = 0;
        while (n < max && !events.isEmpty()) {
            JsonObject e = events.pollFirst();
            if (e == null) break;
            arr.add(e);
            n++;
        }
        return arr;
    }

    public void requeue(JsonArray arr) {
        if (arr == null) return;
        int available = Math.max(0, MAX_EVENTS - events.size());
        int i = Math.min(arr.size(), available);
        for (int idx = i - 1; idx >= 0; idx--) {
            if (!arr.get(idx).isJsonObject()) continue;
            events.offerFirst(arr.get(idx).getAsJsonObject());
        }
    }

    public JsonObject snapshotStats() {
        JsonObject o = new JsonObject();
        o.addProperty("total_flags", totalFlags.get());
        o.addProperty("total_punishments", totalPunishments.get());
        o.addProperty("total_fp", totalFp.get());
        o.addProperty("total_tp", totalTp.get());
        o.addProperty("session_count", sessionCount.get());

        JsonObject checks = new JsonObject();
        for (Map.Entry<ViolationType, CheckStats> e : byCheck.entrySet()) {
            CheckStats s = e.getValue();
            long n = s.count.sum();
            JsonObject c = new JsonObject();
            c.addProperty("count", n);
            c.addProperty("fp", s.fp.sum());
            c.addProperty("tp", s.tp.sum());
            c.addProperty("avg_severity", n == 0 ? 0 : round(s.sevSum.sum() / n));
            c.addProperty("avg_score", n == 0 ? 0 : round(s.scoreSum.sum() / n));
            c.addProperty("max_severity", round(s.maxSev));
            checks.add(e.getKey().name(), c);
        }
        o.add("by_check", checks);

        JsonObject conf = new JsonObject();
        for (Map.Entry<String, LongAdder> e : byConfidence.entrySet()) {
            conf.addProperty(e.getKey(), e.getValue().sum());
        }
        o.add("by_confidence", conf);
        return o;
    }

    private void append(JsonObject e) {
        events.offerLast(e);
        while (events.size() > MAX_EVENTS) events.pollFirst();
    }

    private static double round(double d) {
        return Math.round(d * 1000.0) / 1000.0;
    }

    private static class CheckStats {
        final LongAdder count = new LongAdder();
        final LongAdder fp = new LongAdder();
        final LongAdder tp = new LongAdder();
        final DoubleAdder sevSum = new DoubleAdder();
        final DoubleAdder scoreSum = new DoubleAdder();
        volatile double maxSev = 0.0;

        synchronized void record(double severity, double score) {
            count.increment();
            sevSum.add(severity);
            scoreSum.add(score);
            if (severity > maxSev) maxSev = severity;
        }
    }
}
