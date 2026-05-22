package NC.noChance;

import NC.noChance.alerts.StaffAlertManager;
import NC.noChance.commands.NoChanceCommand;
import NC.noChance.core.*;
import NC.noChance.database.DatabaseManager;
import NC.noChance.gui.ACMenuGUI;
import NC.noChance.gui.GUIListener;
import NC.noChance.listeners.PlayerListener;
import NC.noChance.ml.MLDataCollector;
import NC.noChance.ml.MLDataReviewGUI;
import NC.noChance.ml.MLReviewListener;
import NC.noChance.packets.PacketAnalyzer;
import NC.noChance.packets.PacketFingerprint;
import NC.noChance.predict.PhysicsValidator;
import NC.noChance.predict.PredictionEngine;
import NC.noChance.sim.SimBridge;
import NC.noChance.sim.SimEngine;
import NC.noChance.punishment.PunishmentManager;
import NC.noChance.replay.ReplayMgr;
import NC.noChance.staff.LiveOverlay;
import NC.noChance.staff.StaffTools;
import NC.noChance.utils.UpdateChecker;
import NC.noChance.web.WebBridge;
import com.github.retrooper.packetevents.PacketEvents;
import io.github.retrooper.packetevents.factory.spigot.SpigotPacketEventsBuilder;
import org.bstats.bukkit.Metrics;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public final class NoChance extends JavaPlugin {
    private Map<UUID, PlayerData> playerDataMap;
    private ACConfig config;
    private DatabaseManager database;
    private PunishmentManager punishmentManager;
    private StaffAlertManager alertManager;
    private AsyncCheckProcessor asyncProcessor;
    private ViolationScoring violationScoring;
    private LayerFiltering layerFiltering;
    private MultiLayerValidator multiLayerValidator;
    private PacketAnalyzer packetAnalyzer;
    private TransactionTracker transactionTracker;
    private PacketIntegrityCheck packetIntegrityCheck;
    private Improbable improbable;
    private EntityPositionTracker entityPositionTracker;
    private MitigationManager mitigationManager;
    private DetectionEngine detectionEngine;
    private UpdateChecker updateChecker;
    private CheckRegistry checkRegistry;
    private ReplayMgr replayMgr;
    private StaffTools staffTools;
    private PredictionEngine predictionEngine;
    private SimEngine simEngine;
    private SimBridge simBridge;
    private SessionTracker sessionTracker;
    private PacketFingerprint packetFingerprint;
    private LiveOverlay liveOverlay;
    private LangManager langManager;
    private WebBridge webBridge;
    private LifecycleRegistry lifecycleRegistry;
    private StateSweeper stateSweeper;
    private DiagMetrics diagMetrics;
    private MovementGrace movementGrace;
    private PhysicsValidator physicsValidator;
    private MLDataCollector mlCollector;
    private MLDataReviewGUI mlReviewGUI;
    private long enableTime;

    @Override
    public void onLoad() {
        PacketEvents.setAPI(SpigotPacketEventsBuilder.build(this));
        PacketEvents.getAPI().getSettings()
                .reEncodeByDefault(false)
                .checkForUpdates(false)
                .bStats(false)
                .debug(false);
        PacketEvents.getAPI().load();
    }

    @Override
    public void onEnable() {
        enableTime = System.currentTimeMillis();
        lifecycleRegistry = new LifecycleRegistry(getLogger());
        if (PacketEvents.getAPI() == null) {
            getLogger().severe("PacketEvents API is null; disabling plugin.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        try {
            PacketEvents.getAPI().init();
        } catch (Exception e) {
            getLogger().severe("PacketEvents failed to initialize: " + e.getMessage());
            getLogger().severe("This version may not support your Minecraft version. Check for updates at: https://github.com/retrooper/packetevents/releases");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        new Metrics(this, 27528);

        playerDataMap = new ConcurrentHashMap<>();
        diagMetrics = new DiagMetrics();

        saveDefaultConfig();
        ConfigValidator validator = new ConfigValidator(getConfig(), getLogger());
        ConfigValidator.ValidationResult result = validator.validate();
        if (result.isFatal()) {
            getLogger().severe("Configuration validation failed (" + result.getErrors().size() + " critical issue(s)). Plugin will not enable.");
            for (String error : result.getErrors()) {
                getLogger().severe("  " + error);
            }
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        if (!result.getWarnings().isEmpty()) {
            getLogger().warning("Configuration has " + result.getWarnings().size() + " non-fatal issue(s); proceeding with defaults where applicable.");
        }

        new ConfigMigrator(this).sync();
        config = new ACConfig(this);
        langManager = new LangManager(this, config.getLanguage(), config.isForceServerLanguage());

        DatabaseManager.DatabaseType dbType = config.getDatabaseType().equalsIgnoreCase("MYSQL") ?
            DatabaseManager.DatabaseType.MYSQL : DatabaseManager.DatabaseType.SQLITE;

        try {
            database = new DatabaseManager(this, config, dbType,
                config.getDatabaseHost(), config.getDatabasePort(),
                config.getDatabaseName(), config.getDatabaseUsername(),
                config.getDatabasePassword(), config.getDatabaseTablePrefix());
        } catch (Throwable t) {
            getLogger().warning("DatabaseManager construction failed, continuing without persistence: " + t.getMessage());
            database = null;
        }

        asyncProcessor = new AsyncCheckProcessor(this);
        alertManager = new StaffAlertManager(this, config);
        punishmentManager = new PunishmentManager(this, config, database);
        punishmentManager.setAlertManager(alertManager);
        punishmentManager.setPlayerDataMap(playerDataMap);

        mitigationManager = new MitigationManager(config);
        mitigationManager.setExecutor(punishmentManager.getExecutor());

        if (config.isDiscordEnabled() && !config.getDiscordWebhook().isEmpty()) {
            alertManager.setDiscordWebhook(config.getDiscordWebhook());
            getLogger().info("Discord webhook integration enabled!");
        }

        layerFiltering = new LayerFiltering(config);
        detectionEngine = new DetectionEngine(config);
        TPSCache.init(this);
        violationScoring = new ViolationScoring(config, playerDataMap, database, punishmentManager, alertManager, detectionEngine);
        multiLayerValidator = new MultiLayerValidator(config, playerDataMap, layerFiltering, detectionEngine);

        checkRegistry = new CheckRegistry(config, playerDataMap, layerFiltering);

        movementGrace = new MovementGrace();
        checkRegistry.setMovementGrace(movementGrace);

        if (getConfig().getBoolean("checks.physics_validator.enabled", false)) {
            physicsValidator = new PhysicsValidator(movementGrace);
            checkRegistry.getFlyCheck().setPhysicsValidator(physicsValidator);
            checkRegistry.getSpeedCheck().setPhysicsValidator(physicsValidator);
        }

        packetFingerprint = new PacketFingerprint();

        packetAnalyzer = new PacketAnalyzer(this, config, checkRegistry.getBlinkCheck());
        packetAnalyzer.setPacketFingerprint(packetFingerprint);
        packetIntegrityCheck = new PacketIntegrityCheck(this);
        packetAnalyzer.setIntegrityCheck(packetIntegrityCheck);
        improbable = new Improbable();
        multiLayerValidator.setPacketAnalyzer(packetAnalyzer);

        transactionTracker = new TransactionTracker(this);
        packetAnalyzer.setTransactionTracker(transactionTracker);
        if (layerFiltering != null) {
            layerFiltering.setTransactionTracker(transactionTracker);
        }
        if (checkRegistry != null && checkRegistry.getBlinkCheck() != null) {
            checkRegistry.getBlinkCheck().setTransactionTracker(transactionTracker);
        }
        transactionTracker.start();

        entityPositionTracker = new EntityPositionTracker(this);
        if (checkRegistry != null && checkRegistry.getReachCheck() != null) {
            checkRegistry.getReachCheck().setPositionTracker(entityPositionTracker);
        }
        entityPositionTracker.start();

        predictionEngine = new PredictionEngine();
        multiLayerValidator.setPredictionEngine(predictionEngine);

        simEngine = new SimEngine();
        simBridge = new SimBridge(simEngine);
        sessionTracker = new SessionTracker();
        liveOverlay = new LiveOverlay(playerDataMap, detectionEngine);
        liveOverlay.setSimBridge(simBridge);
        liveOverlay.setPredictionEngine(predictionEngine);

        staffTools = new StaffTools(this, playerDataMap);
        staffTools.setLiveOverlay(liveOverlay);
        alertManager.setPlayerDataMap(playerDataMap);
        alertManager.setLangManager(langManager);
        alertManager.setPacketFingerprint(packetFingerprint);

        PlayerListener playerListener = new PlayerListener(
                this, playerDataMap, violationScoring, multiLayerValidator, alertManager, config,
                checkRegistry
        );
        playerListener.setStaffTools(staffTools);
        playerListener.setPacketAnalyzer(packetAnalyzer);
        playerListener.setPredictionEngine(predictionEngine);
        playerListener.setSimEngine(simEngine);
        playerListener.setSimBridge(simBridge);
        playerListener.setSessionTracker(sessionTracker);
        playerListener.setPacketFingerprint(packetFingerprint);
        playerListener.setTransactionTracker(transactionTracker);
        playerListener.setMovementGrace(movementGrace);
        multiLayerValidator.setSimEngine(simEngine);
        playerListener.registerOnce(this);
        lifecycleRegistry.registerListener(playerListener);

        ACMenuGUI menuGUI = new ACMenuGUI(this, config);
        GUIListener guiListener = new GUIListener(menuGUI);
        getServer().getPluginManager().registerEvents(guiListener, this);
        lifecycleRegistry.registerListener(guiListener);

        if (database != null) {
            mlCollector = new MLDataCollector(this, database);
            checkRegistry.setMLCollector(mlCollector);
            mlReviewGUI = new MLDataReviewGUI(this, database);
            MLReviewListener mlListener = new MLReviewListener(mlReviewGUI);
            getServer().getPluginManager().registerEvents(mlListener, this);
            lifecycleRegistry.registerListener(mlListener);
        }

        replayMgr = new ReplayMgr(this, config.getReplayBufferSeconds(), config.getReplayRetentionDays(), config.isReplayEnabled(), config.getReplayBeforeSeconds(), config.getReplayAfterSeconds());
        violationScoring.setReplayMgr(replayMgr);

        webBridge = new WebBridge(this, config);
        webBridge.start();
        alertManager.setWebBridge(webBridge);

        NoChanceCommand commandExecutor = new NoChanceCommand(this, config, playerDataMap, violationScoring, menuGUI, alertManager, database);
        commandExecutor.setReplayMgr(replayMgr);
        commandExecutor.setStaffTools(staffTools);
        commandExecutor.setWebBridge(webBridge);
        commandExecutor.setMLReviewGUI(mlReviewGUI);
        if (replayMgr != null) {
            commandExecutor.setReplayIO(replayMgr.getIO());
        }
        getCommand("nochance").setExecutor(commandExecutor);
        getCommand("nochance").setTabCompleter(commandExecutor);

        lifecycleRegistry.registerBukkitTask(Bukkit.getScheduler().runTaskTimerAsynchronously(this, () -> {
            long timeWindow = config.getTimeWindow() * 1000L;
            List<PlayerData> dataSnapshot = new ArrayList<>(playerDataMap.values());
            if (predictionEngine != null) {
                predictionEngine.cleanup();
            }
            if (simEngine != null) {
                simEngine.cleanup();
            }
            for (PlayerData data : dataSnapshot) {
                data.cleanOldViolations(timeWindow);
                data.calibrateSkillLevel();
                Player onlinePlayer = Bukkit.getPlayer(data.getPlayerId());
                String playerName = onlinePlayer != null ? onlinePlayer.getName() : "Unknown";
                if (database != null) {
                    database.updatePlayerData(
                        data.getPlayerId(),
                        playerName,
                        data.getViolationRatio() > 0 ? (int)(data.getViolationRatio() * 100) : 0,
                        data.getViolationRatio() > 0 ? 100 : 0,
                        1.0 - data.getViolationRatio(),
                        data.getSkillLevel().name(),
                        false
                    );
                }
            }
            multiLayerValidator.cleanupOldData();
            detectionEngine.cleanupOldData();
        }, 20L * 60, 20L * 60));

        lifecycleRegistry.registerBukkitTask(Bukkit.getScheduler().runTaskTimerAsynchronously(this, () -> {
            checkRegistry.cleanupStale();
            VersionAdapter.cleanup();
            if (simBridge != null) simBridge.cleanupStale();
        }, 20L * 30, 20L * 30));

        lifecycleRegistry.registerBukkitTask(Bukkit.getScheduler().runTaskTimer(this, () -> {
            if (checkRegistry != null) {
                checkRegistry.getKillAuraCheck().tickCombat();
            }
        }, 1L, 1L));

        final PlayerListener playerListenerRef = playerListener;
        lifecycleRegistry.registerBukkitTask(Bukkit.getScheduler().runTaskTimer(this, () -> {
            Set<UUID> online = new java.util.HashSet<>();
            for (Player p : Bukkit.getOnlinePlayers()) online.add(p.getUniqueId());
            List<UUID> stale = new ArrayList<>();
            for (UUID uuid : playerDataMap.keySet()) {
                if (!online.contains(uuid)) stale.add(uuid);
            }
            for (UUID uuid : stale) playerListenerRef.cleanupPlayer(uuid);
        }, 6000L, 6000L));

        stateSweeper = new StateSweeper(this);
        stateSweeper.register("punishment.lastWarningTime", punishmentManager.getLastWarningTimeMap(), v -> (Long) v);
        stateSweeper.register("punishment.lastSetbackTime", punishmentManager.getLastSetbackTimeMap(), v -> (Long) v);
        stateSweeper.register("listener.lastShiftAlert", playerListener.getLastShiftAlertMap(), v -> (Long) v);
        stateSweeper.register("movementGrace.stamps", movementGrace.getStampMap(), v -> {
            long[] arr = (long[]) v;
            long max = 0L;
            for (long t : arr) if (t > max) max = t;
            return max;
        });
        stateSweeper.register("detection.aiVerdicts", detectionEngine.getAiVerdictsMap(), v -> {
            Map<?, ?> inner = (Map<?, ?>) v;
            long max = 0L;
            for (Object o : inner.values()) {
                if (o instanceof DetectionEngine.AIVerdict) {
                    long t = ((DetectionEngine.AIVerdict) o).ts;
                    if (t > max) max = t;
                }
            }
            return max;
        });
        stateSweeper.start();
        if (stateSweeper.getTask() != null) {
            lifecycleRegistry.registerBukkitTask(stateSweeper.getTask());
        }

        updateChecker = new UpdateChecker(this, 129357);
        updateChecker.performCheck();

        getLogger().info("NoChance Anti-Cheat has been enabled!");
        getLogger().info("Version: " + getDescription().getVersion());
        getLogger().info("Database: " + dbType.name());
        getLogger().info("Loaded " + checkRegistry.getCheckCount() + " detection modules with 4-layer validation cascade");
        getLogger().info("Packet detection: ENABLED");
        getLogger().info("Punishment system: ACTIVE (warn/kick/tempban/ban ladder)");
        getLogger().info("Async processing: ENABLED");
        getLogger().info("Replay system: " + (config.isReplayEnabled() ? "ENABLED (" + config.getReplayBufferSeconds() + "s buffer)" : "DISABLED"));
        getLogger().info("Bedrock support: " + (BedrockHelper.isAvailable() ? "ENABLED (Floodgate/Geyser detected)" : "AVAILABLE (no Floodgate/Geyser)"));
        getLogger().info("Multi-version: " + (ViaHelper.isAvailable() ? "ENABLED (ViaVersion/ProtocolSupport detected)" : "AVAILABLE (native version only)"));
        getLogger().info("Ping compensation: " + (config.isPingCompensationEnabled() ? "ENABLED" : "DISABLED"));
        getLogger().info("Packet fingerprinting: ENABLED (9 cheat client signatures)");
        getLogger().info("Session tracking: ENABLED (cross-session toggle detection)");
        getLogger().info("Simulation engine: ENABLED (tick-level physics validation)");
    }

    @Override
    public void onDisable() {
        if (database != null) {
            List<CompletableFuture<Void>> updates = new ArrayList<>();
            for (Map.Entry<UUID, PlayerData> entry : playerDataMap.entrySet()) {
                UUID uuid = entry.getKey();
                PlayerData data = entry.getValue();
                Player onlinePlayer = Bukkit.getPlayer(uuid);
                String playerName = onlinePlayer != null ? onlinePlayer.getName() : "Unknown";
                CompletableFuture<Void> future = database.updatePlayerData(
                    uuid,
                    playerName,
                    data.getViolationRatio() > 0 ? (int)(data.getViolationRatio() * 100) : 0,
                    data.getViolationRatio() > 0 ? 100 : 0,
                    1.0 - data.getViolationRatio(),
                    data.getSkillLevel().name(),
                    false
                );
                updates.add(future);
            }
            try {
                CompletableFuture.allOf(updates.toArray(new CompletableFuture[0])).get(10, TimeUnit.SECONDS);
            } catch (Exception e) {
                getLogger().warning("Failed to wait for database updates on shutdown: " + e.getMessage());
            }
        }

        if (lifecycleRegistry != null) {
            lifecycleRegistry.shutdownAll();
        }
        Bukkit.getScheduler().cancelTasks(this);

        if (webBridge != null) {
            webBridge.stop();
        }

        if (staffTools != null) {
            staffTools.shutdown();
        }

        if (replayMgr != null) {
            replayMgr.shutdown();
        }

        if (database != null) {
            database.close();
        }

        if (asyncProcessor != null) {
            asyncProcessor.shutdown();
        }

        BlockCache.clear();
        NC.noChance.replay.FakePlayer.clearSkinCache();

        if (transactionTracker != null) {
            transactionTracker.shutdown();
        }

        if (entityPositionTracker != null) {
            entityPositionTracker.shutdown();
        }

        if (playerDataMap != null) {
            playerDataMap.clear();
        }

        try {
            if (PacketEvents.getAPI() != null) {
                try {
                    PacketEvents.getAPI().getEventManager().unregisterAllListeners();
                } catch (Throwable ignored) {
                }
                PacketEvents.getAPI().terminate();
            }
        } catch (Exception e) {
            getLogger().log(java.util.logging.Level.WARNING, "Failed to terminate PacketEvents", e);
        }

        getLogger().info("NoChance Anti-Cheat has been disabled!");
    }

    public DatabaseManager getDatabase() {
        return database;
    }

    public PunishmentManager getPunishmentManager() {
        return punishmentManager;
    }

    public StaffAlertManager getAlertManager() {
        return alertManager;
    }

    public AsyncCheckProcessor getAsyncProcessor() {
        return asyncProcessor;
    }

    public UpdateChecker getUpdateChecker() {
        return updateChecker;
    }

    public CheckRegistry getCheckRegistry() {
        return checkRegistry;
    }

    public ReplayMgr getReplayMgr() {
        return replayMgr;
    }

    public StaffTools getStaffTools() {
        return staffTools;
    }

    public PredictionEngine getPredictionEngine() {
        return predictionEngine;
    }

    public SimEngine getSimEngine() {
        return simEngine;
    }

    public DetectionEngine getDetectionEngine() {
        return detectionEngine;
    }

    public SimBridge getSimBridge() {
        return simBridge;
    }

    public SessionTracker getSessionTracker() {
        return sessionTracker;
    }

    public PacketFingerprint getPacketFingerprint() {
        return packetFingerprint;
    }

    public TransactionTracker getTransactionTracker() {
        return transactionTracker;
    }

    public Improbable getImprobable() {
        return improbable;
    }

    public MitigationManager getMitigationManager() {
        return mitigationManager;
    }

    public LiveOverlay getLiveOverlay() {
        return liveOverlay;
    }

    public LangManager getLangManager() {
        return langManager;
    }

    public Map<UUID, PlayerData> getPlayerDataMap() {
        return playerDataMap;
    }

    public WebBridge getWebBridge() {
        return webBridge;
    }

    public LifecycleRegistry getLifecycleRegistry() {
        return lifecycleRegistry;
    }

    public StateSweeper getStateSweeper() {
        return stateSweeper;
    }

    public DiagMetrics getDiagMetrics() {
        return diagMetrics;
    }

    public MovementGrace getMovementGrace() {
        return movementGrace;
    }

    public MLDataCollector getMLCollector() {
        return mlCollector;
    }

    public MLDataReviewGUI getMLReviewGUI() {
        return mlReviewGUI;
    }

    public long getEnableTime() {
        return enableTime;
    }
}
