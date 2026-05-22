package NC.noChance.database;

import NC.noChance.NoChance;
import NC.noChance.core.ACConfig;
import NC.noChance.core.LifecycleRegistry;
import NC.noChance.core.ViolationType;
import NC.noChance.ml.MLDataCollector;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.logging.Level;

public class DatabaseManager {
    private static final int QUEUE_LIMIT = 10_000;
    private static final long SUMMARY_INTERVAL_MS = 5 * 60 * 1000L;

    private final Plugin plugin;
    private final ACConfig config;
    private HikariDataSource dataSource;
    private final DatabaseType type;
    private final String tablePrefix;
    private final ExecutorService dbExecutor;
    private volatile boolean degraded = true;
    private String lastHost;
    private int lastPort;
    private String lastDatabase;
    private String lastUsername;
    private String lastPassword;

    private final Deque<Runnable> writeQueue = new ConcurrentLinkedDeque<>();
    private final AtomicLong droppedSinceLastLog = new AtomicLong(0);
    private volatile long lastSummaryLog = 0L;
    private volatile long lastDropLog = 0L;
    private volatile long lastQueueWarn = 0L;
    private static final long QUEUE_WARN_INTERVAL_MS = 30_000L;

    public DatabaseManager(Plugin plugin, ACConfig config, DatabaseType type, String host, int port,
                          String database, String username, String password, String tablePrefix) {
        this.plugin = plugin;
        this.config = config;
        this.type = type;
        this.tablePrefix = tablePrefix;
        this.lastHost = host;
        this.lastPort = port;
        this.lastDatabase = database;
        this.lastUsername = username;
        this.lastPassword = password;
        ExecutorService rawDb = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "NoChance-DB");
            t.setDaemon(true);
            return t;
        });
        this.dbExecutor = (plugin instanceof NoChance)
                ? ((NoChance) plugin).getLifecycleRegistry().register("Database", rawDb)
                : rawDb;
        init();
        scheduleReconnect();
    }

    public final void init() {
        try {
            initializeDataSource(lastHost, lastPort, lastDatabase, lastUsername, lastPassword);
            try (Connection probe = dataSource.getConnection();
                 Statement st = probe.createStatement()) {
                st.execute("SELECT 1");
            }
            createTables();
            degraded = false;
            plugin.getLogger().info("Database initialized (" + type.name() + ").");
        } catch (Throwable e) {
            degraded = true;
            plugin.getLogger().warning("Database init failed, entering degraded mode: " + e.getMessage());
            try { if (dataSource != null && !dataSource.isClosed()) dataSource.close(); } catch (Exception ignored) {}
        }
    }

    public boolean isAvailable() {
        return !degraded;
    }

    public boolean isDegraded() {
        return degraded;
    }

    public int getQueuedWrites() {
        return writeQueue.size();
    }

    public HikariDataSource getDataSource() {
        return dataSource;
    }

    private void scheduleReconnect() {
        if (!(plugin instanceof NoChance)) return;
        LifecycleRegistry reg = ((NoChance) plugin).getLifecycleRegistry();
        reg.registerBukkitTask(Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::tickMaintenance, 20L * 60, 20L * 60));
    }

    private void tickMaintenance() {
        long now = System.currentTimeMillis();
        if (degraded) {
            attemptReconnect();
            if (now - lastSummaryLog >= SUMMARY_INTERVAL_MS) {
                lastSummaryLog = now;
                plugin.getLogger().warning("DB degraded, " + writeQueue.size() + " writes queued (cap " + QUEUE_LIMIT + ")");
            }
        }
        if (now - lastDropLog >= SUMMARY_INTERVAL_MS) {
            long dropped = droppedSinceLastLog.getAndSet(0);
            lastDropLog = now;
            if (dropped > 0) {
                plugin.getLogger().warning("DB write queue dropped " + dropped + " entries in last 5 minutes");
            }
        }
    }

    public void attemptReconnect() {
        try {
            if (dataSource != null && !dataSource.isClosed()) {
                dataSource.close();
            }
            initializeDataSource(lastHost, lastPort, lastDatabase, lastUsername, lastPassword);
            try (Connection probe = dataSource.getConnection();
                 Statement st = probe.createStatement()) {
                st.execute("SELECT 1");
            }
            createTables();
            degraded = false;
            int drained = drainQueue();
            plugin.getLogger().info("DB reconnected, replayed " + drained + " queued writes");
        } catch (Throwable e) {
            degraded = true;
            plugin.getLogger().warning("DB reconnect failed: " + e.getMessage());
        }
    }

    private int drainQueue() {
        int count = 0;
        Runnable r;
        while ((r = writeQueue.pollFirst()) != null) {
            try {
                dbExecutor.execute(r);
                count++;
                Thread.sleep(5);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            } catch (Throwable t) {
                plugin.getLogger().log(Level.WARNING, "Queued write failed during drain", t);
            }
        }
        return count;
    }

    private void submit(Runnable task) {
        if (!degraded && dataSource != null && !dataSource.isClosed()) {
            try {
                dbExecutor.execute(task);
                return;
            } catch (RejectedExecutionException ex) {
                enqueue(task);
                return;
            }
        }
        enqueue(task);
    }

    private void enqueue(Runnable task) {
        if (writeQueue.size() >= QUEUE_LIMIT) {
            long now = System.currentTimeMillis();
            if (now - lastQueueWarn >= QUEUE_WARN_INTERVAL_MS) {
                lastQueueWarn = now;
                plugin.getLogger().warning("DB write queue at limit (" + writeQueue.size() + "/" + QUEUE_LIMIT + "), dropping oldest entries");
            }
            while (writeQueue.size() >= QUEUE_LIMIT) {
                if (writeQueue.pollFirst() != null) {
                    droppedSinceLastLog.incrementAndGet();
                } else {
                    break;
                }
            }
        }
        writeQueue.offerLast(task);
    }

    private void initializeDataSource(String host, int port, String database, String username, String password) {
        HikariConfig hikariConfig = new HikariConfig();

        if (type == DatabaseType.SQLITE) {
            hikariConfig.setJdbcUrl("jdbc:sqlite:" + plugin.getDataFolder() + "/data.db");
            hikariConfig.setDriverClassName("org.sqlite.JDBC");
        } else {
            hikariConfig.setJdbcUrl("jdbc:mysql://" + host + ":" + port + "/" + database +
                "?useSSL=false&autoReconnect=true&cachePrepStmts=true&prepStmtCacheSize=250&prepStmtCacheSqlLimit=2048&useServerPrepStmts=true");
            hikariConfig.setUsername(username);
            hikariConfig.setPassword(password);
            hikariConfig.setDriverClassName("com.mysql.cj.jdbc.Driver");
        }

        hikariConfig.setMaximumPoolSize(type == DatabaseType.SQLITE ? 4 : Math.min(50, 200));
        hikariConfig.setConnectionTimeout(config.getDatabaseConnectionTimeout());
        hikariConfig.setIdleTimeout(config.getDatabaseIdleTimeout());
        hikariConfig.setMaxLifetime(config.getDatabaseMaxLifetime());
        hikariConfig.setInitializationFailTimeout(-1);

        this.dataSource = new HikariDataSource(hikariConfig);
    }

    private void createTables() {
        String autoIncrement = type == DatabaseType.SQLITE ? "AUTOINCREMENT" : "AUTO_INCREMENT";

        executeUpdate("CREATE TABLE IF NOT EXISTS " + tablePrefix + "violations (" +
                "id INTEGER PRIMARY KEY " + autoIncrement + ", " +
                "uuid VARCHAR(36) NOT NULL, " +
                "player_name VARCHAR(16) NOT NULL, " +
                "violation_type VARCHAR(32) NOT NULL, " +
                "severity DOUBLE NOT NULL, " +
                "details TEXT, " +
                "confidence_level VARCHAR(16), " +
                "detection_method VARCHAR(32), " +
                "timestamp BIGINT NOT NULL" +
                ")");

        executeUpdate("CREATE TABLE IF NOT EXISTS " + tablePrefix + "punishments (" +
                "id INTEGER PRIMARY KEY " + autoIncrement + ", " +
                "uuid VARCHAR(36) NOT NULL, " +
                "player_name VARCHAR(16) NOT NULL, " +
                "punishment_type VARCHAR(16) NOT NULL, " +
                "reason TEXT, " +
                "duration BIGINT, " +
                "issued_by VARCHAR(32), " +
                "timestamp BIGINT NOT NULL, " +
                "active BOOLEAN DEFAULT 1" +
                ")");

        executeUpdate("CREATE TABLE IF NOT EXISTS " + tablePrefix + "player_data (" +
                "uuid VARCHAR(36) PRIMARY KEY, " +
                "player_name VARCHAR(16) NOT NULL, " +
                "total_violations INTEGER DEFAULT 0, " +
                "total_checks INTEGER DEFAULT 0, " +
                "trust_score DOUBLE DEFAULT 1.0, " +
                "skill_level VARCHAR(16), " +
                "first_seen BIGINT NOT NULL, " +
                "last_seen BIGINT NOT NULL, " +
                "banned BOOLEAN DEFAULT 0" +
                ")");

        executeUpdate("CREATE TABLE IF NOT EXISTS " + tablePrefix + "session_baselines (" +
                "uuid VARCHAR(36) PRIMARY KEY, " +
                "avg_cps DOUBLE DEFAULT 0, " +
                "cps_variance DOUBLE DEFAULT 0, " +
                "avg_rotation DOUBLE DEFAULT 0, " +
                "rotation_variance DOUBLE DEFAULT 0, " +
                "avg_movement DOUBLE DEFAULT 0, " +
                "movement_variance DOUBLE DEFAULT 0, " +
                "avg_accuracy DOUBLE DEFAULT 0, " +
                "total_playtime BIGINT DEFAULT 0, " +
                "session_count INT DEFAULT 0, " +
                "last_updated BIGINT DEFAULT 0" +
                ")");

        executeUpdate("CREATE TABLE IF NOT EXISTS " + tablePrefix + "ml_training_data (" +
                "id " + (type == DatabaseType.SQLITE ? "INTEGER" : "BIGINT") + " PRIMARY KEY " + autoIncrement + ", " +
                "ts " + (type == DatabaseType.SQLITE ? "INTEGER" : "BIGINT") + " NOT NULL, " +
                "player_uuid VARCHAR(36) NOT NULL, " +
                "check_name VARCHAR(32) NOT NULL, " +
                "features TEXT NOT NULL, " +
                "thresholds TEXT NOT NULL, " +
                "verdict VARCHAR(16) NOT NULL, " +
                "client_version VARCHAR(64), " +
                "ping INTEGER, " +
                "tps DOUBLE, " +
                "world VARCHAR(64), " +
                "staff_review_outcome VARCHAR(16), " +
                "review_ts " + (type == DatabaseType.SQLITE ? "INTEGER" : "BIGINT") +
                ")");

        createIndexes();
    }

    private void createIndexes() {
        executeUpdate("CREATE INDEX IF NOT EXISTS idx_violations_uuid ON " + tablePrefix + "violations(uuid)");
        executeUpdate("CREATE INDEX IF NOT EXISTS idx_violations_type ON " + tablePrefix + "violations(violation_type)");
        executeUpdate("CREATE INDEX IF NOT EXISTS idx_violations_timestamp ON " + tablePrefix + "violations(timestamp)");
        executeUpdate("CREATE INDEX IF NOT EXISTS idx_punishments_uuid ON " + tablePrefix + "punishments(uuid)");
        executeUpdate("CREATE INDEX IF NOT EXISTS idx_punishments_active ON " + tablePrefix + "punishments(active)");
        executeUpdate("CREATE INDEX IF NOT EXISTS idx_ml_ts ON " + tablePrefix + "ml_training_data(ts)");
        executeUpdate("CREATE INDEX IF NOT EXISTS idx_ml_review ON " + tablePrefix + "ml_training_data(staff_review_outcome)");
        executeUpdate("CREATE INDEX IF NOT EXISTS idx_ml_verdict ON " + tablePrefix + "ml_training_data(verdict)");
    }

    public CompletableFuture<Void> logViolation(UUID uuid, String playerName, ViolationType vType,
                                                 double severity, String details, String confidenceLevel,
                                                 String detectionMethod) {
        long ts = System.currentTimeMillis();
        submit(() -> {
            String sql = "INSERT INTO " + tablePrefix + "violations " +
                        "(uuid, player_name, violation_type, severity, details, confidence_level, detection_method, timestamp) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, uuid.toString());
                stmt.setString(2, playerName);
                stmt.setString(3, vType.name());
                stmt.setDouble(4, severity);
                stmt.setString(5, details);
                stmt.setString(6, confidenceLevel);
                stmt.setString(7, detectionMethod);
                stmt.setLong(8, ts);
                stmt.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().severe("Failed to log violation: " + e.getMessage());
            }
        });
        return CompletableFuture.completedFuture(null);
    }

    public CompletableFuture<Void> logPunishment(UUID uuid, String playerName, PunishmentType pType,
                                                  String reason, long duration, String issuedBy) {
        long ts = System.currentTimeMillis();
        submit(() -> {
            String sql = "INSERT INTO " + tablePrefix + "punishments " +
                        "(uuid, player_name, punishment_type, reason, duration, issued_by, timestamp, active) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, uuid.toString());
                stmt.setString(2, playerName);
                stmt.setString(3, pType.name());
                stmt.setString(4, reason);
                stmt.setLong(5, duration);
                stmt.setString(6, issuedBy);
                stmt.setLong(7, ts);
                stmt.setBoolean(8, true);
                stmt.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().severe("Failed to log punishment: " + e.getMessage());
            }
        });
        return CompletableFuture.completedFuture(null);
    }

    public CompletableFuture<Integer> getViolationCount(UUID uuid, ViolationType vType, long timeWindow) {
        if (degraded) return CompletableFuture.completedFuture(0);
        return CompletableFuture.supplyAsync(() -> {
            String sql = "SELECT COUNT(*) FROM " + tablePrefix + "violations " +
                        "WHERE uuid = ? AND violation_type = ? AND timestamp > ?";
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, uuid.toString());
                stmt.setString(2, vType.name());
                stmt.setLong(3, System.currentTimeMillis() - timeWindow);
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) return rs.getInt(1);
                }
            } catch (SQLException e) {
                plugin.getLogger().severe("Failed to get violation count: " + e.getMessage());
            }
            return 0;
        }, dbExecutor);
    }

    public CompletableFuture<List<ViolationRecord>> getViolations(UUID uuid, long timeWindow) {
        if (degraded) return CompletableFuture.completedFuture(Collections.emptyList());
        return CompletableFuture.supplyAsync(() -> {
            List<ViolationRecord> violations = new ArrayList<>();
            String sql = "SELECT * FROM " + tablePrefix + "violations " +
                        "WHERE uuid = ? AND timestamp > ? ORDER BY timestamp DESC LIMIT 100";
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, uuid.toString());
                stmt.setLong(2, System.currentTimeMillis() - timeWindow);
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        try {
                            violations.add(new ViolationRecord(
                                rs.getString("player_name"),
                                ViolationType.valueOf(rs.getString("violation_type")),
                                rs.getDouble("severity"),
                                rs.getString("details"),
                                rs.getString("confidence_level"),
                                rs.getString("detection_method"),
                                rs.getLong("timestamp")
                            ));
                        } catch (IllegalArgumentException e) {
                            plugin.getLogger().log(Level.WARNING, "Unknown violation_type in database row, skipping: " + rs.getString("violation_type"), e);
                        }
                    }
                }
            } catch (SQLException e) {
                plugin.getLogger().severe("Failed to get violations: " + e.getMessage());
            }
            return violations;
        }, dbExecutor);
    }

    public CompletableFuture<List<ViolationRecord>> getViolationsByName(String playerName, long timeWindow) {
        if (degraded) return CompletableFuture.completedFuture(Collections.emptyList());
        return CompletableFuture.supplyAsync(() -> {
            List<ViolationRecord> violations = new ArrayList<>();
            String sql = "SELECT * FROM " + tablePrefix + "violations " +
                        "WHERE LOWER(player_name) = LOWER(?) AND timestamp > ? ORDER BY timestamp DESC LIMIT 100";
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, playerName);
                stmt.setLong(2, System.currentTimeMillis() - timeWindow);
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        try {
                            violations.add(new ViolationRecord(
                                rs.getString("player_name"),
                                ViolationType.valueOf(rs.getString("violation_type")),
                                rs.getDouble("severity"),
                                rs.getString("details"),
                                rs.getString("confidence_level"),
                                rs.getString("detection_method"),
                                rs.getLong("timestamp")
                            ));
                        } catch (IllegalArgumentException e) {
                            plugin.getLogger().log(Level.WARNING, "Unknown violation_type in database row, skipping: " + rs.getString("violation_type"), e);
                        }
                    }
                }
            } catch (SQLException e) {
                plugin.getLogger().severe("Failed to get violations by name: " + e.getMessage());
            }
            return violations;
        }, dbExecutor);
    }

    public CompletableFuture<ActivePunishment> getActivePunishment(UUID uuid) {
        if (degraded) return CompletableFuture.completedFuture(null);
        return CompletableFuture.supplyAsync(() -> {
            String sql = "SELECT * FROM " + tablePrefix + "punishments " +
                        "WHERE uuid = ? AND active = 1 ORDER BY timestamp DESC LIMIT 1";
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, uuid.toString());
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        long timestamp = rs.getLong("timestamp");
                        long duration = rs.getLong("duration");
                        if (duration > 0 && System.currentTimeMillis() > timestamp + duration) {
                            submit(() -> deactivatePunishment(uuid));
                            return null;
                        }
                        return new ActivePunishment(
                            PunishmentType.valueOf(rs.getString("punishment_type")),
                            rs.getString("reason"),
                            timestamp,
                            duration
                        );
                    }
                }
            } catch (SQLException e) {
                plugin.getLogger().severe("Failed to get active punishment: " + e.getMessage());
            }
            return null;
        }, dbExecutor);
    }

    private void deactivatePunishment(UUID uuid) {
        if (dataSource == null || dataSource.isClosed()) return;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                "UPDATE " + tablePrefix + "punishments SET active = 0 WHERE uuid = ? AND active = 1")) {
            stmt.setString(1, uuid.toString());
            stmt.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to deactivate punishment: " + e.getMessage());
        }
    }

    public CompletableFuture<Void> updatePlayerData(UUID uuid, String playerName, int totalViolations,
                                                     int totalChecks, double trustScore, String skillLevel, boolean banned) {
        long now = System.currentTimeMillis();
        submit(() -> {
            String sql;
            if (type == DatabaseType.MYSQL) {
                sql = "INSERT INTO " + tablePrefix + "player_data " +
                     "(uuid, player_name, total_violations, total_checks, trust_score, skill_level, first_seen, last_seen, banned) " +
                     "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) " +
                     "ON DUPLICATE KEY UPDATE " +
                     "player_name = ?, total_violations = ?, total_checks = ?, trust_score = ?, skill_level = ?, last_seen = ?, banned = ?";
            } else {
                sql = "INSERT INTO " + tablePrefix + "player_data " +
                     "(uuid, player_name, total_violations, total_checks, trust_score, skill_level, first_seen, last_seen, banned) " +
                     "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) " +
                     "ON CONFLICT(uuid) DO UPDATE SET " +
                     "player_name = ?, total_violations = ?, total_checks = ?, trust_score = ?, skill_level = ?, last_seen = ?, banned = ?";
            }
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, uuid.toString());
                stmt.setString(2, playerName);
                stmt.setInt(3, totalViolations);
                stmt.setInt(4, totalChecks);
                stmt.setDouble(5, trustScore);
                stmt.setString(6, skillLevel);
                stmt.setLong(7, now);
                stmt.setLong(8, now);
                stmt.setBoolean(9, banned);
                stmt.setString(10, playerName);
                stmt.setInt(11, totalViolations);
                stmt.setInt(12, totalChecks);
                stmt.setDouble(13, trustScore);
                stmt.setString(14, skillLevel);
                stmt.setLong(15, now);
                stmt.setBoolean(16, banned);
                stmt.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().severe("Failed to update player data: " + e.getMessage());
            }
        });
        return CompletableFuture.completedFuture(null);
    }

    public CompletableFuture<PlayerHistory> getPlayerHistory(UUID uuid, long timeWindow) {
        if (degraded) return CompletableFuture.completedFuture(new PlayerHistory(0, 0));
        return CompletableFuture.supplyAsync(() -> {
            String violationSql = "SELECT COUNT(*) FROM " + tablePrefix + "violations WHERE uuid = ? AND timestamp > ?";
            String kickSql = "SELECT MAX(timestamp) FROM " + tablePrefix + "punishments " +
                            "WHERE uuid = ? AND punishment_type = 'KICK' AND timestamp > ?";
            int violationCount = 0;
            long lastKickTime = 0;
            try (Connection conn = dataSource.getConnection()) {
                try (PreparedStatement stmt = conn.prepareStatement(violationSql)) {
                    stmt.setString(1, uuid.toString());
                    stmt.setLong(2, System.currentTimeMillis() - timeWindow);
                    try (ResultSet rs = stmt.executeQuery()) {
                        if (rs.next()) violationCount = rs.getInt(1);
                    }
                }
                try (PreparedStatement stmt = conn.prepareStatement(kickSql)) {
                    stmt.setString(1, uuid.toString());
                    stmt.setLong(2, System.currentTimeMillis() - (24 * 60 * 60 * 1000L));
                    try (ResultSet rs = stmt.executeQuery()) {
                        if (rs.next()) lastKickTime = rs.getLong(1);
                    }
                }
            } catch (SQLException e) {
                plugin.getLogger().severe("Failed to get player history: " + e.getMessage());
            }
            return new PlayerHistory(violationCount, lastKickTime);
        }, dbExecutor);
    }

    public CompletableFuture<Map<String, Double>> loadBaseline(UUID uuid) {
        if (degraded) return CompletableFuture.completedFuture(null);
        return CompletableFuture.supplyAsync(() -> {
            String sql = "SELECT * FROM " + tablePrefix + "session_baselines WHERE uuid = ?";
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, uuid.toString());
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        Map<String, Double> baseline = new HashMap<>();
                        baseline.put("avg_cps", rs.getDouble("avg_cps"));
                        baseline.put("cps_variance", rs.getDouble("cps_variance"));
                        baseline.put("avg_rotation", rs.getDouble("avg_rotation"));
                        baseline.put("rotation_variance", rs.getDouble("rotation_variance"));
                        baseline.put("avg_movement", rs.getDouble("avg_movement"));
                        baseline.put("movement_variance", rs.getDouble("movement_variance"));
                        baseline.put("avg_accuracy", rs.getDouble("avg_accuracy"));
                        baseline.put("total_playtime", (double) rs.getLong("total_playtime"));
                        baseline.put("session_count", (double) rs.getInt("session_count"));
                        baseline.put("last_updated", (double) rs.getLong("last_updated"));
                        return baseline;
                    }
                }
            } catch (SQLException e) {
                plugin.getLogger().severe("Failed to load baseline: " + e.getMessage());
            }
            return null;
        }, dbExecutor);
    }

    public CompletableFuture<Void> saveBaseline(UUID uuid, double avgCps, double cpsVar, double avgRot,
                                                  double rotVar, double avgMove, double moveVar,
                                                  double avgAcc, long playtime, int sessions) {
        long now = System.currentTimeMillis();
        submit(() -> {
            String sql;
            if (type == DatabaseType.MYSQL) {
                sql = "INSERT INTO " + tablePrefix + "session_baselines " +
                     "(uuid, avg_cps, cps_variance, avg_rotation, rotation_variance, avg_movement, movement_variance, avg_accuracy, total_playtime, session_count, last_updated) " +
                     "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) " +
                     "ON DUPLICATE KEY UPDATE " +
                     "avg_cps = ?, cps_variance = ?, avg_rotation = ?, rotation_variance = ?, avg_movement = ?, movement_variance = ?, avg_accuracy = ?, total_playtime = ?, session_count = ?, last_updated = ?";
            } else {
                sql = "INSERT OR REPLACE INTO " + tablePrefix + "session_baselines " +
                     "(uuid, avg_cps, cps_variance, avg_rotation, rotation_variance, avg_movement, movement_variance, avg_accuracy, total_playtime, session_count, last_updated) " +
                     "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
            }
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, uuid.toString());
                stmt.setDouble(2, avgCps);
                stmt.setDouble(3, cpsVar);
                stmt.setDouble(4, avgRot);
                stmt.setDouble(5, rotVar);
                stmt.setDouble(6, avgMove);
                stmt.setDouble(7, moveVar);
                stmt.setDouble(8, avgAcc);
                stmt.setLong(9, playtime);
                stmt.setInt(10, sessions);
                stmt.setLong(11, now);
                if (type == DatabaseType.MYSQL) {
                    stmt.setDouble(12, avgCps);
                    stmt.setDouble(13, cpsVar);
                    stmt.setDouble(14, avgRot);
                    stmt.setDouble(15, rotVar);
                    stmt.setDouble(16, avgMove);
                    stmt.setDouble(17, moveVar);
                    stmt.setDouble(18, avgAcc);
                    stmt.setLong(19, playtime);
                    stmt.setInt(20, sessions);
                    stmt.setLong(21, now);
                }
                stmt.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().severe("Failed to save baseline: " + e.getMessage());
            }
        });
        return CompletableFuture.completedFuture(null);
    }

    public void insertMLBatch(List<MLDataCollector.Row> rows) {
        if (rows == null || rows.isEmpty()) return;
        submit(() -> {
            String sql = "INSERT INTO " + tablePrefix + "ml_training_data " +
                    "(ts, player_uuid, check_name, features, thresholds, verdict, client_version, ping, tps, world, staff_review_outcome, review_ts) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(sql)) {
                conn.setAutoCommit(false);
                for (MLDataCollector.Row r : rows) {
                    stmt.setLong(1, r.ts);
                    stmt.setString(2, r.playerUuid);
                    stmt.setString(3, r.checkName);
                    stmt.setString(4, r.features);
                    stmt.setString(5, r.thresholds);
                    stmt.setString(6, r.verdict);
                    stmt.setString(7, r.clientVersion);
                    stmt.setInt(8, r.ping);
                    stmt.setDouble(9, r.tps);
                    stmt.setString(10, r.world);
                    if (r.reviewOutcome == null) stmt.setNull(11, Types.VARCHAR);
                    else stmt.setString(11, r.reviewOutcome);
                    if (r.reviewTs <= 0) stmt.setNull(12, Types.BIGINT);
                    else stmt.setLong(12, r.reviewTs);
                    stmt.addBatch();
                }
                stmt.executeBatch();
                conn.commit();
            } catch (SQLException e) {
                plugin.getLogger().severe("Failed to insert ML batch: " + e.getMessage());
            }
        });
    }

    public CompletableFuture<Void> updateMLOutcome(long id, String outcome) {
        long now = System.currentTimeMillis();
        submit(() -> {
            String sql = "UPDATE " + tablePrefix + "ml_training_data SET staff_review_outcome = ?, review_ts = ? WHERE id = ?";
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(sql)) {
                if (outcome == null) stmt.setNull(1, Types.VARCHAR);
                else stmt.setString(1, outcome);
                stmt.setLong(2, now);
                stmt.setLong(3, id);
                stmt.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().severe("Failed to update ML outcome: " + e.getMessage());
            }
        });
        return CompletableFuture.completedFuture(null);
    }

    public CompletableFuture<List<MLDataCollector.Row>> fetchUnreviewedML(int limit, int offset) {
        if (degraded) return CompletableFuture.completedFuture(Collections.emptyList());
        return CompletableFuture.supplyAsync(() -> {
            List<MLDataCollector.Row> out = new ArrayList<>();
            String sql = "SELECT id, ts, player_uuid, check_name, features, thresholds, verdict, client_version, ping, tps, world, staff_review_outcome, review_ts " +
                    "FROM " + tablePrefix + "ml_training_data " +
                    "WHERE staff_review_outcome IS NULL AND verdict IN ('HARD_FLAG','SOFT_FLAG') " +
                    "ORDER BY ts DESC LIMIT ? OFFSET ?";
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setInt(1, limit);
                stmt.setInt(2, offset);
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        out.add(rowOf(rs));
                    }
                }
            } catch (SQLException e) {
                plugin.getLogger().severe("Failed to fetch unreviewed ML rows: " + e.getMessage());
            }
            return out;
        }, dbExecutor);
    }

    public CompletableFuture<Integer> streamMLForExport(Consumer<MLDataCollector.Row> sink) {
        if (degraded) return CompletableFuture.completedFuture(0);
        return CompletableFuture.supplyAsync(() -> {
            int count = 0;
            String sql = "SELECT id, ts, player_uuid, check_name, features, thresholds, verdict, client_version, ping, tps, world, staff_review_outcome, review_ts " +
                    "FROM " + tablePrefix + "ml_training_data ORDER BY id ASC";
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setFetchSize(500);
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        sink.accept(rowOf(rs));
                        count++;
                    }
                }
            } catch (SQLException e) {
                plugin.getLogger().severe("Failed to stream ML rows: " + e.getMessage());
            }
            return count;
        }, dbExecutor);
    }

    private MLDataCollector.Row rowOf(ResultSet rs) throws SQLException {
        return new MLDataCollector.Row(
                rs.getLong("id"),
                rs.getLong("ts"),
                rs.getString("player_uuid"),
                rs.getString("check_name"),
                rs.getString("features"),
                rs.getString("thresholds"),
                rs.getString("verdict"),
                rs.getString("client_version"),
                rs.getInt("ping"),
                rs.getDouble("tps"),
                rs.getString("world"),
                rs.getString("staff_review_outcome"),
                rs.getLong("review_ts")
        );
    }

    private void executeUpdate(String sql, String... params) {
        if (dataSource == null || dataSource.isClosed()) return;
        int placeholders = 0;
        for (int i = 0; i < sql.length(); i++) {
            if (sql.charAt(i) == '?') placeholders++;
        }
        if (placeholders != params.length) {
            plugin.getLogger().severe("executeUpdate param count mismatch: sql=" + placeholders + " params=" + params.length);
            return;
        }
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                stmt.setString(i + 1, params[i]);
            }
            stmt.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to execute update: " + e.getMessage());
        }
    }

    public void close() {
        if (dbExecutor != null && !dbExecutor.isShutdown()) {
            dbExecutor.shutdown();
            try {
                if (!dbExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                    dbExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                dbExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        if (dataSource != null && !dataSource.isClosed()) {
            try {
                dataSource.close();
            } catch (Exception e) {
                plugin.getLogger().warning("Failed to close data source: " + e.getMessage());
            }
        }
    }

    public enum DatabaseType {
        MYSQL, SQLITE
    }

    public enum PunishmentType {
        WARN, KICK, TEMPBAN, BAN, SETBACK, CUSTOM_CMD
    }

    public static final class ViolationRecord {
        public final String playerName;
        public final ViolationType type;
        public final double severity;
        public final String details;
        public final String confidenceLevel;
        public final String detectionMethod;
        public final long timestamp;

        ViolationRecord(String playerName, ViolationType type, double severity, String details,
                        String confidenceLevel, String detectionMethod, long timestamp) {
            this.playerName = playerName;
            this.type = type;
            this.severity = severity;
            this.details = details;
            this.confidenceLevel = confidenceLevel;
            this.detectionMethod = detectionMethod;
            this.timestamp = timestamp;
        }
    }

    public static final class ActivePunishment {
        public final PunishmentType type;
        public final String reason;
        public final long timestamp;
        public final long duration;

        ActivePunishment(PunishmentType type, String reason, long timestamp, long duration) {
            this.type = type;
            this.reason = reason;
            this.timestamp = timestamp;
            this.duration = duration;
        }

        public boolean isExpired() {
            return duration > 0 && System.currentTimeMillis() > timestamp + duration;
        }

        public long getTimeRemaining() {
            if (duration == 0) return -1;
            return Math.max(0, (timestamp + duration) - System.currentTimeMillis());
        }
    }

    public static final class PlayerHistory {
        public final int violationCount;
        public final long lastKickTime;

        PlayerHistory(int violationCount, long lastKickTime) {
            this.violationCount = violationCount;
            this.lastKickTime = lastKickTime;
        }

        public boolean wasRecentlyKicked(long threshold) {
            return lastKickTime > 0 && (System.currentTimeMillis() - lastKickTime) < threshold;
        }
    }
}
