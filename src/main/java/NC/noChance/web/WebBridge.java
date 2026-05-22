package NC.noChance.web;

import NC.noChance.NoChance;
import NC.noChance.core.ACConfig;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public class WebBridge {
    private static final int POLL_SECONDS = 2;
    private static final int FLUSH_SECONDS = 10;
    private static final int FAST_FLUSH_MIN_MS = 1500;
    private static final int ACK_BATCH = 32;

    private final NoChance plugin;
    private final ACConfig config;
    private final WebAuth auth;
    private final WebTelemetry telemetry = new WebTelemetry();
    private final WebRunner runner;
    private final Map<String, Boolean> seenCommands = java.util.Collections.synchronizedMap(new java.util.LinkedHashMap<>(256, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Boolean> e) { return size() > 256; }
    });
    private final ConcurrentLinkedQueue<JsonObject> ackQueue = new ConcurrentLinkedQueue<>();

    private final AtomicReference<ScheduledExecutorService> schedulerRef = new AtomicReference<>(null);
    private final List<ScheduledFuture<?>> pendingFutures = new ArrayList<>();
    private String endpoint;
    private String serverId;
    private String serverKey;
    private String pendingCode;
    private String pendingNonce;
    private UUID pendingIssuer;
    private volatile boolean linked;
    private volatile boolean running;
    private volatile long lastFastFlush;
    private volatile boolean fastFlushScheduled;
    private volatile int authFailureCount;
    private volatile long justLinkedAt;
    private static final int AUTH_FAILURE_UNLINK_THRESHOLD = 5;
    private static final long POST_LINK_GRACE_MS = 30_000;

    public WebBridge(NoChance plugin, ACConfig config) {
        this.plugin = plugin;
        this.config = config;
        this.auth = new WebAuth();
        this.runner = new WebRunner(plugin, config, this);
    }

    public void start() {
        if (!config.isWebEnabled()) {
            plugin.getLogger().info("Web dashboard integration disabled.");
            return;
        }
        this.endpoint = trimSlash(config.getWebEndpoint());
        if (endpoint == null || endpoint.isEmpty()) {
            plugin.getLogger().warning("Web dashboard enabled but endpoint is empty.");
            return;
        }
        loadIdentity();
        linked = hasValidIdentity();
        running = true;

        ScheduledExecutorService sched = plugin.getLifecycleRegistry().register("Web",
                Executors.newScheduledThreadPool(2, r -> {
                    Thread t = new Thread(r, "NC-Web");
                    t.setDaemon(true);
                    return t;
                }));
        schedulerRef.set(sched);
        sched.scheduleWithFixedDelay(this::tickPoll, 2, POLL_SECONDS, TimeUnit.SECONDS);
        sched.scheduleWithFixedDelay(this::flushTelemetry, FLUSH_SECONDS, FLUSH_SECONDS, TimeUnit.SECONDS);

        if (linked) {
            plugin.getLogger().info("Web dashboard linked as server " + serverId);
            justLinkedAt = System.currentTimeMillis();
            synchronized (pendingFutures) {
                pendingFutures.add(sched.schedule(this::flushSettings, 1, TimeUnit.SECONDS));
                pendingFutures.add(sched.schedule(this::flushTelemetry, 3, TimeUnit.SECONDS));
            }
        } else {
            plugin.getLogger().info("Web dashboard ready — run /nc web to pair.");
        }
    }

    public void stop() {
        running = false;
        flushTelemetry();
        ScheduledExecutorService sched = schedulerRef.getAndSet(null);
        if (sched != null) {
            synchronized (pendingFutures) {
                for (ScheduledFuture<?> f : pendingFutures) {
                    f.cancel(false);
                }
                pendingFutures.clear();
            }
            sched.shutdown();
            try {
                if (!sched.awaitTermination(3, TimeUnit.SECONDS)) sched.shutdownNow();
            } catch (InterruptedException e) {
                sched.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }

    public WebAuth.Pending beginPair(Player issuer) {
        if (!config.isWebEnabled() || endpoint == null) return null;
        String previousCode = this.pendingCode;
        WebAuth.Pending p = auth.issue(issuer.getUniqueId(), issuer.getName());
        this.pendingCode = p.code;
        this.pendingNonce = randomHex(32);
        this.pendingIssuer = issuer.getUniqueId();

        JsonObject body = new JsonObject();
        body.addProperty("code", p.code);
        body.addProperty("plugin_nonce", pendingNonce);
        if (previousCode != null) body.addProperty("cancel_code", previousCode);
        body.addProperty("server_name", plugin.getServer().getName());
        body.addProperty("server_motd", stripColors(plugin.getServer().getMotd()));
        body.addProperty("server_port", plugin.getServer().getPort());
        body.addProperty("plugin_version", plugin.getDescription().getVersion());
        body.addProperty("mc_version", plugin.getServer().getBukkitVersion());
        body.addProperty("issuer_uuid", issuer.getUniqueId().toString());
        body.addProperty("issuer_name", issuer.getName());
        body.addProperty("online_players", plugin.getServer().getOnlinePlayers().size());
        body.addProperty("max_players", plugin.getServer().getMaxPlayers());
        UUID issuerCopy = issuer.getUniqueId();
        scheduleTask(() -> submitPairInit(body, issuerCopy, 0));
        return p;
    }

    private void submitPairInit(JsonObject body, UUID issuer, int attempt) {
        JsonObject resp = postJson("/api/mc/pair/init", body, null);
        if (resp != null) return;
        if (attempt >= 3) {
            Bukkit.getScheduler().runTask(plugin, () -> {
                Player p = Bukkit.getPlayer(issuer);
                if (p != null && p.isOnline()) {
                    p.sendMessage(plugin.getLangManager().get(p, "web.endpoint_unreachable"));
                }
            });
            pendingCode = null;
            pendingNonce = null;
            pendingIssuer = null;
            return;
        }
        long delay = (long) (Math.pow(2, attempt) * 500);
        ScheduledExecutorService s = schedulerRef.get();
        if (s != null && !s.isShutdown()) {
            synchronized (pendingFutures) {
                pendingFutures.add(s.schedule(() -> {
                    if (!running) return;
                    submitPairInit(body, issuer, attempt + 1);
                }, delay, TimeUnit.MILLISECONDS));
            }
        }
    }

    private String randomHex(int bytes) {
        byte[] buf = new byte[bytes];
        new java.security.SecureRandom().nextBytes(buf);
        StringBuilder sb = new StringBuilder(bytes * 2);
        for (byte b : buf) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    public void cancelPair() {
        if (pendingCode != null) auth.invalidate(pendingCode);
        pendingCode = null;
        pendingNonce = null;
        pendingIssuer = null;
    }

    public boolean isLinked() { return linked; }
    public String getServerId() { return serverId; }
    public String getPendingCode() { return pendingCode; }
    public WebTelemetry telemetry() { return telemetry; }

    public void unlink() {
        linked = false;
        serverId = null;
        serverKey = null;
        deleteIdentity();
    }

    public void pushAck(JsonObject ack) {
        if (ack != null) ackQueue.offer(ack);
    }

    private void tickPoll() {
        if (!running) return;
        try {
            if (!linked) {
                pollPairClaim();
            } else {
                pollCommands();
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Web poll error: " + e.getMessage());
        }
    }

    private void pollPairClaim() {
        if (pendingCode == null || pendingNonce == null) return;
        if (auth.peek(pendingCode) == null) {
            pendingCode = null;
            pendingNonce = null;
            pendingIssuer = null;
            return;
        }
        JsonObject body = new JsonObject();
        body.addProperty("code", pendingCode);
        body.addProperty("plugin_nonce", pendingNonce);
        JsonObject resp = postJson("/api/mc/pair/check", body, null);
        if (resp == null) return;
        if (!resp.has("claimed") || !resp.get("claimed").getAsBoolean()) return;

        this.serverId = resp.get("server_id").getAsString();
        this.serverKey = resp.get("server_key").getAsString();
        String adminLabel = resp.has("admin_username") ? resp.get("admin_username").getAsString() : "unknown";
        saveIdentity();
        linked = true;
        auth.invalidate(pendingCode);
        String finalCode = pendingCode;
        UUID issuerCopy = pendingIssuer;
        pendingCode = null;
        pendingNonce = null;
        pendingIssuer = null;

        justLinkedAt = System.currentTimeMillis();
        ScheduledExecutorService s2 = schedulerRef.get();
        if (s2 != null && !s2.isShutdown()) {
            synchronized (pendingFutures) {
                pendingFutures.add(s2.schedule(this::flushSettingsInline, 1500, TimeUnit.MILLISECONDS));
                pendingFutures.add(s2.schedule(this::flushTelemetry, 2500, TimeUnit.MILLISECONDS));
            }
        }

        Bukkit.getScheduler().runTask(plugin, () -> {
            if (issuerCopy == null) return;
            Player p = Bukkit.getPlayer(issuerCopy);
            if (p != null && p.isOnline()) {
                p.sendMessage(plugin.getLangManager().get(p, "web.linked", "admin", adminLabel));
                p.sendMessage(plugin.getLangManager().get(p, "web.linked_server_id", "id", serverId));
            } else {
                plugin.getLogger().info("Server paired (issuer offline). code=" + finalCode);
            }
        });
    }

    private void pollCommands() {
        JsonObject body = new JsonObject();
        body.addProperty("server_id", serverId);
        body.addProperty("online_players", plugin.getServer().getOnlinePlayers().size());
        body.addProperty("tps", NC.noChance.core.TPSCache.getTPS());
        body.add("acks", drainAcks());

        JsonObject resp = postJson("/api/mc/pull", body, serverKey);
        if (resp == null) return;
        if (!resp.has("commands")) return;
        JsonArray arr = resp.getAsJsonArray("commands");
        for (JsonElement el : arr) {
            if (!el.isJsonObject()) continue;
            JsonObject c = el.getAsJsonObject();
            if (!c.has("id")) continue;
            String id = c.get("id").getAsString();
            if (seenCommands.putIfAbsent(id, Boolean.TRUE) != null) continue;
            runner.dispatch(c);
        }
    }

    private JsonArray drainAcks() {
        JsonArray out = new JsonArray();
        for (int i = 0; i < ACK_BATCH; i++) {
            JsonObject a = ackQueue.poll();
            if (a == null) break;
            out.add(a);
        }
        return out;
    }

    private void flushTelemetry() {
        if (!running || !linked) return;
        JsonArray events = telemetry.drainEvents(200);
        JsonObject stats = telemetry.snapshotStats();

        JsonObject body = new JsonObject();
        body.addProperty("server_id", serverId);
        body.addProperty("plugin_version", plugin.getDescription().getVersion());
        body.addProperty("ts", System.currentTimeMillis());
        body.add("events", events);
        body.add("stats", stats);
        body.add("snapshot", buildSnapshot());
        body.add("settings", buildSettings());
        JsonObject resp = postJson("/api/mc/push", body, serverKey);
        if (resp == null && events.size() > 0) {
            telemetry.requeue(events);
        }
    }

    public void flushSettings() {
        if (!running || !linked) return;
        scheduleTask(this::flushSettingsInline);
    }

    private void flushSettingsInline() {
        if (!running || !linked) return;
        JsonObject body = new JsonObject();
        body.addProperty("server_id", serverId);
        body.addProperty("ts", System.currentTimeMillis());
        body.add("settings", buildSettings());
        body.add("snapshot", buildSnapshot());
        postJson("/api/mc/push", body, serverKey);
    }

    public void scheduleFastFlush() {
        if (!running || !linked) return;
        if (fastFlushScheduled) return;
        long now = System.currentTimeMillis();
        long sinceLast = now - lastFastFlush;
        long delay = sinceLast >= FAST_FLUSH_MIN_MS ? 0 : FAST_FLUSH_MIN_MS - sinceLast;
        fastFlushScheduled = true;
        ScheduledExecutorService s = schedulerRef.get();
        if (s != null && !s.isShutdown()) {
            synchronized (pendingFutures) {
                pendingFutures.add(s.schedule(() -> {
                    fastFlushScheduled = false;
                    lastFastFlush = System.currentTimeMillis();
                    flushTelemetry();
                }, delay, TimeUnit.MILLISECONDS));
            }
        }
    }

    private JsonObject buildSettings() {
        JsonObject o = new JsonObject();
        JsonObject checks = new JsonObject();
        String[] names = { "fly", "speed", "noclip", "jesus", "fastbreak", "fastplace", "nuker",
                "killaura", "nofall", "autoclicker", "reach", "inventory", "scaffold", "timer",
                "velocity", "criticals", "phase", "step", "blink", "noslow", "groundspoof",
                "elytrafly", "strider", "boatfly" };
        for (String n : names) {
            JsonObject c = new JsonObject();
            c.addProperty("enabled", config.isCheckEnabled(n));
            c.addProperty("threshold", config.getViolationThreshold(n));
            c.addProperty("severity_multiplier", config.getSeverityMultiplier(n));
            checks.add(n, c);
        }
        o.add("checks", checks);

        JsonObject thresholds = new JsonObject();
        thresholds.addProperty("low_confidence", config.getLowConfidenceThreshold());
        thresholds.addProperty("medium_confidence", config.getMediumConfidenceThreshold());
        thresholds.addProperty("high_confidence", config.getHighConfidenceThreshold());
        thresholds.addProperty("extreme_confidence", config.getExtremeConfidenceThreshold());
        o.add("thresholds", thresholds);

        JsonObject general = new JsonObject();
        general.addProperty("op_exempt", config.isOpExempt());
        general.addProperty("notify_player_on_flag", config.shouldNotifyPlayerOnFlag());
        general.addProperty("time_window_seconds", config.getTimeWindow());
        general.addProperty("grace_period_seconds", config.getGracePeriod());
        general.addProperty("min_samples", config.getMinSamples());
        o.add("general", general);

        JsonObject actions = new JsonObject();
        actions.addProperty("kick_on_extreme_confidence", config.shouldKickOnExtreme());
        actions.addProperty("kick_on_high_confidence", config.shouldKickOnHigh());
        actions.addProperty("warn_on_high_confidence", config.shouldWarnOnHigh());
        actions.addProperty("warn_on_medium_confidence", config.shouldWarnOnMedium());
        o.add("actions", actions);

        JsonObject ai = new JsonObject();
        ai.addProperty("enabled", config.isAIEnabled());
        ai.addProperty("send_features", config.isAISendFeatures());
        ai.addProperty("max_adjustment", config.getAIMaxAdjustment());
        ai.addProperty("min_agreement", config.getAIMinAgreement());
        ai.addProperty("verdict_ttl_seconds", config.getAIVerdictTTL());
        o.add("ai", ai);

        org.bukkit.configuration.file.FileConfiguration raw = plugin.getConfig();
        JsonObject filter = new JsonObject();
        filter.addProperty("enabled", raw.getBoolean("advanced_filtering.enabled", true));
        filter.addProperty("bayesian_prior", raw.getDouble("advanced_filtering.bayesian_prior", 0.12));
        filter.addProperty("z_score_threshold", raw.getDouble("advanced_filtering.z_score_threshold", 1.8));
        filter.addProperty("min_trust_score", raw.getDouble("advanced_filtering.min_trust_score", 0.5));
        o.add("advanced_filtering", filter);

        JsonObject replay = new JsonObject();
        replay.addProperty("enabled", raw.getBoolean("replay.enabled", true));
        replay.addProperty("buffer_seconds", raw.getInt("replay.buffer_seconds", 30));
        replay.addProperty("before_seconds", raw.getInt("replay.before_seconds", 10));
        replay.addProperty("after_seconds", raw.getInt("replay.after_seconds", 15));
        replay.addProperty("retention_days", raw.getInt("replay.retention_days", 7));
        replay.addProperty("save_on_high", raw.getBoolean("replay.save_on_high", true));
        replay.addProperty("save_on_extreme", raw.getBoolean("replay.save_on_extreme", true));
        o.add("replay", replay);

        JsonObject pingc = new JsonObject();
        pingc.addProperty("enabled", raw.getBoolean("ping_compensation.enabled", true));
        pingc.addProperty("high_ping_threshold", raw.getInt("ping_compensation.high_ping_threshold", 120));
        pingc.addProperty("max_multiplier", raw.getDouble("ping_compensation.max_multiplier", 1.4));
        o.add("ping_compensation", pingc);

        JsonObject bedrock = new JsonObject();
        bedrock.addProperty("exempt", raw.getBoolean("bedrock.exempt", false));
        bedrock.addProperty("relaxed_checks", raw.getBoolean("bedrock.relaxed_checks", true));
        bedrock.addProperty("tolerance_multiplier", raw.getDouble("bedrock.tolerance_multiplier", 1.3));
        o.add("bedrock", bedrock);

        o.addProperty("language", config.getLanguage());
        o.addProperty("synced_at", System.currentTimeMillis());
        return o;
    }

    private JsonObject buildSnapshot() {
        JsonObject snap = new JsonObject();
        snap.addProperty("online_players", plugin.getServer().getOnlinePlayers().size());
        snap.addProperty("max_players", plugin.getServer().getMaxPlayers());
        snap.addProperty("tps", NC.noChance.core.TPSCache.getTPS());
        snap.addProperty("plugin_version", plugin.getDescription().getVersion());
        NC.noChance.staff.StaffTools st = plugin.getStaffTools();
        JsonArray players = new JsonArray();
        for (Player p : plugin.getServer().getOnlinePlayers()) {
            JsonObject po = new JsonObject();
            po.addProperty("uuid", p.getUniqueId().toString());
            po.addProperty("name", p.getName());
            po.addProperty("ping", Math.max(0, p.getPing()));
            po.addProperty("op", p.isOp());
            po.addProperty("world", p.getWorld() == null ? "" : p.getWorld().getName());
            po.addProperty("gamemode", p.getGameMode().name());
            po.addProperty("frozen", st != null && st.isFrozen(p.getUniqueId()));
            players.add(po);
        }
        snap.add("players", players);
        return snap;
    }

    private boolean hasValidIdentity() {
        return serverId != null && !serverId.isEmpty() && serverKey != null && !serverKey.isEmpty();
    }

    private File identityFile() {
        if (!plugin.getDataFolder().exists()) plugin.getDataFolder().mkdirs();
        return new File(plugin.getDataFolder(), "web-identity.yml");
    }

    private void loadIdentity() {
        File f = identityFile();
        if (!f.exists()) return;
        YamlConfiguration yc = YamlConfiguration.loadConfiguration(f);
        this.serverId = yc.getString("server_id", "");
        this.serverKey = yc.getString("server_key", "");
    }

    private void saveIdentity() {
        YamlConfiguration yc = new YamlConfiguration();
        yc.set("server_id", serverId);
        yc.set("server_key", serverKey);
        yc.set("linked_at", System.currentTimeMillis());
        try {
            yc.save(identityFile());
        } catch (IOException e) {
            plugin.getLogger().warning("Failed to save web identity: " + e.getMessage());
        }
    }

    private void deleteIdentity() {
        File f = identityFile();
        if (f.exists()) f.delete();
    }

    private void scheduleTask(Runnable r) {
        ScheduledExecutorService s = schedulerRef.get();
        if (s != null && !s.isShutdown()) s.submit(r);
        else r.run();
    }

    private JsonObject postJson(String path, JsonObject body, String bearer) {
        if (endpoint == null) return null;
        HttpURLConnection conn = null;
        try {
            URL url = new URI(endpoint + path).toURL();
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("User-Agent", "NoChance/" + plugin.getDescription().getVersion() + " (Spigot)");
            if (bearer != null) {
                String safe = bearer.replaceAll("[\\r\\n\\u0000-\\u001F\\u007F]", "");
                if (!safe.isEmpty()) conn.setRequestProperty("Authorization", "Bearer " + safe);
            }
            conn.setDoOutput(true);
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(8000);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.toString().getBytes(StandardCharsets.UTF_8));
            }

            int rc = conn.getResponseCode();
            if (rc >= 200 && rc < 300) {
                authFailureCount = 0;
                try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = br.readLine()) != null) sb.append(line);
                    String text = sb.toString();
                    if (text.isEmpty()) return new JsonObject();
                    JsonElement el = JsonParser.parseString(text);
                    return el.isJsonObject() ? el.getAsJsonObject() : null;
                }
            }
            if ((rc == 401 || rc == 403) && linked && bearer != null) {
                long sinceLink = System.currentTimeMillis() - justLinkedAt;
                if (sinceLink < POST_LINK_GRACE_MS) {
                    plugin.getLogger().info("Dashboard auth 401 during post-link grace, will retry (" + sinceLink + "ms).");
                } else {
                    authFailureCount++;
                    plugin.getLogger().warning("Web dashboard rejected credentials (" + authFailureCount + "/" + AUTH_FAILURE_UNLINK_THRESHOLD + ").");
                    if (authFailureCount >= AUTH_FAILURE_UNLINK_THRESHOLD) {
                        plugin.getLogger().severe("Web dashboard credentials rejected repeatedly — unlinking. Run /nc web to re-pair.");
                        Bukkit.getScheduler().runTask(plugin, () -> {
                            for (Player op : Bukkit.getOnlinePlayers()) {
                                if (op.isOp()) op.sendMessage("§c[NoChance] §7Dashboard unlinked — auth rejected. Run §f/nc web§7 to re-pair.");
                            }
                        });
                        unlink();
                    }
                }
            }
            return null;
        } catch (Exception e) {
            plugin.getLogger().log(java.util.logging.Level.WARNING, "Web request failed for path: " + path, e);
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private String trimSlash(String s) {
        if (s == null) return null;
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    private String stripColors(String s) {
        if (s == null) return "";
        return s.replaceAll("§[0-9a-fklmnor]", "");
    }
}
