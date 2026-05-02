package NC.noChance.ml;

import NC.noChance.NoChance;
import NC.noChance.core.LifecycleRegistry;
import NC.noChance.core.TPSCache;
import NC.noChance.database.DatabaseManager;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.concurrent.*;
import java.util.logging.Level;

public class MLDataCollector {
    private static final int QUEUE_CAP = 50_000;
    private static final long FLUSH_PERIOD_SEC = 5L;
    private static final long DROP_LOG_INTERVAL_MS = 60_000L;
    private static final int MAX_BATCH = 500;

    private final NoChance plugin;
    private final DatabaseManager database;
    private final BlockingQueue<Row> queue;
    private final ScheduledExecutorService scheduler;
    private volatile long lastDropLog = 0L;
    private volatile long droppedSinceLog = 0L;

    public MLDataCollector(NoChance plugin, DatabaseManager database) {
        this.plugin = plugin;
        this.database = database;
        this.queue = new ArrayBlockingQueue<>(QUEUE_CAP);
        ScheduledExecutorService raw = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "NoChance-ML");
            t.setDaemon(true);
            return t;
        });
        LifecycleRegistry reg = plugin.getLifecycleRegistry();
        this.scheduler = reg != null ? reg.registerScheduledExecutor("MLCollector", raw) : raw;
        this.scheduler.scheduleAtFixedRate(this::flush, FLUSH_PERIOD_SEC, FLUSH_PERIOD_SEC, TimeUnit.SECONDS);
    }

    public void record(UUID player, String checkName, Map<String, Object> features,
                       Map<String, Object> thresholds, MLVerdict verdict) {
        if (player == null || checkName == null || verdict == null) return;
        long ts = System.currentTimeMillis();
        Player bp = Bukkit.getPlayer(player);
        int ping = bp != null ? bp.getPing() : 0;
        String world = bp != null && bp.getWorld() != null ? bp.getWorld().getName() : "";
        double tps = TPSCache.getTPS();
        String clientVersion = "";
        String featJson = toJson(features);
        String thrJson = toJson(thresholds);
        Row row = new Row(0L, ts, player.toString(), checkName, featJson, thrJson,
                verdict.name(), clientVersion, ping, tps, world, null, 0L);
        if (!queue.offer(row)) {
            queue.poll();
            droppedSinceLog++;
            queue.offer(row);
            long now = System.currentTimeMillis();
            if (now - lastDropLog >= DROP_LOG_INTERVAL_MS) {
                lastDropLog = now;
                plugin.getLogger().warning("ML buffer full, dropped " + droppedSinceLog + " row(s) in last minute");
                droppedSinceLog = 0L;
            }
        }
    }

    private void flush() {
        if (queue.isEmpty()) return;
        if (database == null || !database.isAvailable()) return;
        List<Row> batch = new ArrayList<>(Math.min(MAX_BATCH, queue.size()));
        queue.drainTo(batch, MAX_BATCH);
        if (batch.isEmpty()) return;
        try {
            database.insertMLBatch(batch);
        } catch (Throwable t) {
            plugin.getLogger().log(Level.WARNING, "ML batch insert failed", t);
        }
    }

    public int getQueueSize() {
        return queue.size();
    }

    public static String toJson(Map<String, Object> map) {
        if (map == null || map.isEmpty()) return "{}";
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> e : map.entrySet()) {
            if (!first) sb.append(',');
            first = false;
            sb.append('"').append(esc(e.getKey())).append('"').append(':');
            writeValue(sb, e.getValue());
        }
        sb.append('}');
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static void writeValue(StringBuilder sb, Object v) {
        if (v == null) { sb.append("null"); return; }
        if (v instanceof Number || v instanceof Boolean) { sb.append(v); return; }
        if (v instanceof Map) { sb.append(toJson((Map<String, Object>) v)); return; }
        if (v instanceof Collection) {
            sb.append('[');
            boolean first = true;
            for (Object o : (Collection<?>) v) {
                if (!first) sb.append(',');
                first = false;
                writeValue(sb, o);
            }
            sb.append(']');
            return;
        }
        sb.append('"').append(esc(v.toString())).append('"');
    }

    private static String esc(String s) {
        StringBuilder out = new StringBuilder(s.length() + 2);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': out.append("\\\""); break;
                case '\\': out.append("\\\\"); break;
                case '\n': out.append("\\n"); break;
                case '\r': out.append("\\r"); break;
                case '\t': out.append("\\t"); break;
                default:
                    if (c < 0x20) out.append(String.format("\\u%04x", (int) c));
                    else out.append(c);
            }
        }
        return out.toString();
    }

    public static final class Row {
        public final long id;
        public final long ts;
        public final String playerUuid;
        public final String checkName;
        public final String features;
        public final String thresholds;
        public final String verdict;
        public final String clientVersion;
        public final int ping;
        public final double tps;
        public final String world;
        public final String reviewOutcome;
        public final long reviewTs;

        public Row(long id, long ts, String playerUuid, String checkName, String features, String thresholds,
                   String verdict, String clientVersion, int ping, double tps, String world,
                   String reviewOutcome, long reviewTs) {
            this.id = id;
            this.ts = ts;
            this.playerUuid = playerUuid;
            this.checkName = checkName;
            this.features = features;
            this.thresholds = thresholds;
            this.verdict = verdict;
            this.clientVersion = clientVersion;
            this.ping = ping;
            this.tps = tps;
            this.world = world;
            this.reviewOutcome = reviewOutcome;
            this.reviewTs = reviewTs;
        }
    }
}
