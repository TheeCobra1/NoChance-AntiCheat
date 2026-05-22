package NC.noChance.listeners;

import NC.noChance.NoChance;
import NC.noChance.alerts.StaffAlertManager;
import NC.noChance.core.*;
import NC.noChance.packets.PacketAnalyzer;
import NC.noChance.packets.PacketFingerprint;
import NC.noChance.predict.PredictionEngine;
import NC.noChance.sim.SimBridge;
import NC.noChance.sim.SimEngine;
import NC.noChance.replay.BlockAction;
import NC.noChance.replay.ReplayMgr;
import NC.noChance.replay.Snapshot;
import NC.noChance.staff.StaffTools;
import NC.noChance.detection.player.ProtocolCheck;
import NC.noChance.ml.MLDataCollector;
import NC.noChance.ml.MLVerdict;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDamageEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.*;
import org.bukkit.event.vehicle.VehicleEnterEvent;
import org.bukkit.event.vehicle.VehicleExitEvent;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class PlayerListener implements Listener {
    private final NoChance plugin;
    private final Map<UUID, PlayerData> playerDataMap;
    private final ViolationScoring violationScoring;
    private final MultiLayerValidator multiLayerValidator;
    private final StaffAlertManager alertManager;
    private final ACConfig config;
    private final CheckRegistry checks;
    private StaffTools staffTools;
    private PacketAnalyzer packetAnalyzer;
    private PredictionEngine predictionEngine;
    private SimEngine simEngine;
    private SimBridge simBridge;
    private SessionTracker sessionTracker;
    private PacketFingerprint packetFingerprint;
    private TransactionTracker transactionTracker;
    private MovementGrace movementGrace;

    private final Map<UUID, Long> lastShiftAlert = new ConcurrentHashMap<>();
    private static final long SHIFT_ALERT_COOLDOWN_MS = 300_000L;
    private boolean isRegistered = false;

    public PlayerListener(NoChance plugin, Map<UUID, PlayerData> playerDataMap, ViolationScoring violationScoring,
                          MultiLayerValidator multiLayerValidator, StaffAlertManager alertManager, ACConfig config,
                          CheckRegistry checkRegistry) {
        this.plugin = plugin;
        this.playerDataMap = playerDataMap;
        this.violationScoring = violationScoring;
        this.multiLayerValidator = multiLayerValidator;
        this.alertManager = alertManager;
        this.config = config;
        this.checks = checkRegistry;
    }

    public void setPacketAnalyzer(PacketAnalyzer packetAnalyzer) {
        this.packetAnalyzer = packetAnalyzer;
    }

    public void setStaffTools(StaffTools staffTools) {
        this.staffTools = staffTools;
    }

    public void setPredictionEngine(PredictionEngine predictionEngine) {
        this.predictionEngine = predictionEngine;
    }

    public void setSimEngine(SimEngine simEngine) {
        this.simEngine = simEngine;
    }

    public void setSimBridge(SimBridge simBridge) {
        this.simBridge = simBridge;
    }

    public void setSessionTracker(SessionTracker sessionTracker) {
        this.sessionTracker = sessionTracker;
    }

    public void setPacketFingerprint(PacketFingerprint packetFingerprint) {
        this.packetFingerprint = packetFingerprint;
    }

    public void setTransactionTracker(TransactionTracker transactionTracker) {
        this.transactionTracker = transactionTracker;
    }

    public void setMovementGrace(MovementGrace movementGrace) {
        this.movementGrace = movementGrace;
    }

    public Map<UUID, Long> getLastShiftAlertMap() {
        return lastShiftAlert;
    }

    public boolean registerOnce(org.bukkit.plugin.Plugin pl) {
        if (isRegistered) return false;
        org.bukkit.Bukkit.getPluginManager().registerEvents(this, pl);
        isRegistered = true;
        return true;
    }

    private LangManager lang() {
        return plugin.getLangManager();
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        PlayerData data = new PlayerData(player.getUniqueId());
        playerDataMap.put(player.getUniqueId(), data);

        data.setBedrockPlayer(BedrockHelper.isBedrockPlayer(player));

        if (ViaHelper.isAvailable()) {
            data.setClientVersion(ViaHelper.getProtocolVersion(player));
            data.setViaTolerance(ViaHelper.getMovementTolerance(player));
        }

        if (data.isBedrockPlayer() && config.isBedrockExempt()) {
            for (Player staff : Bukkit.getOnlinePlayers()) {
                if (staff.hasPermission("nochance.alerts") && alertManager.hasAlertsEnabled(staff.getUniqueId())) {
                    staff.sendMessage(lang().get(staff, "listener.bedrock_exempt", "player", player.getName()));
                }
            }
        }

        BukkitTask pingTask = new BukkitRunnable() {
            @Override
            public void run() {
                if (!player.isOnline()) {
                    cancel();
                    return;
                }
                PlayerData pd = playerDataMap.get(player.getUniqueId());
                if (pd != null) {
                    pd.updatePing(player.getPing());
                }
            }
        }.runTaskTimer(plugin, 20L, 40L);
        plugin.getLifecycleRegistry().registerBukkitTask(pingTask);

        long timeWindow = config.getTimeWindow() * 1000L;
        plugin.getDatabase().getPlayerHistory(player.getUniqueId(), timeWindow).thenAccept(history -> {
            if (history == null) return;
            PlayerData current = playerDataMap.get(player.getUniqueId());
            if (current == null) return;
            current.setHistoricalData(history.violationCount, history.lastKickTime);

            if (history.wasRecentlyKicked(5 * 60 * 1000L)) {
                long kickAge = (System.currentTimeMillis() - history.lastKickTime) / 1000;
                int histCount = history.violationCount;
                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    if (!player.isOnline()) return;
                    for (Player staff : Bukkit.getOnlinePlayers()) {
                        if (staff.hasPermission("nochance.alerts")) {
                            staff.sendMessage(lang().get(staff, "listener.strict_detection",
                                    "player", player.getName(),
                                    "seconds", kickAge,
                                    "count", histCount));
                        }
                    }
                }, 20L);
            }
        }).exceptionally(ex -> {
            plugin.getLogger().warning("Failed to load player history: " + ex.getMessage());
            return null;
        });

        if (sessionTracker != null) {
            plugin.getDatabase().loadBaseline(player.getUniqueId()).thenAccept(baseline -> {
                if (baseline != null && sessionTracker != null) {
                    sessionTracker.loadBaseline(player.getUniqueId(),
                        baseline.getOrDefault("avg_cps", 0.0),
                        baseline.getOrDefault("cps_variance", 0.0),
                        baseline.getOrDefault("avg_rotation", 0.0),
                        baseline.getOrDefault("rotation_variance", 0.0),
                        baseline.getOrDefault("avg_movement", 0.0),
                        baseline.getOrDefault("movement_variance", 0.0),
                        baseline.getOrDefault("avg_accuracy", 0.0),
                        baseline.getOrDefault("total_playtime", 0.0).longValue(),
                        baseline.getOrDefault("session_count", 0.0).intValue()
                    );
                }
            }).exceptionally(ex -> {
                plugin.getLogger().warning("Failed to load baseline: " + ex.getMessage());
                return null;
            });
        }

        if (player.hasPermission("nochance.alerts")) {
            alertManager.enableAlerts(player.getUniqueId());
        }

        if (player.hasPermission("nochance.admin") || player.isOp()) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (plugin.getUpdateChecker() != null &&
                    plugin.getUpdateChecker().isCheckComplete() &&
                    plugin.getUpdateChecker().isUpdateAvailable()) {
                    player.sendMessage(lang().get(player, "listener.update_available"));
                    player.sendMessage(lang().get(player, "listener.update_versions",
                            "current", plugin.getUpdateChecker().getCurrentVersion(),
                            "latest", plugin.getUpdateChecker().getLatestVersion()));
                    player.sendMessage(lang().get(player, "listener.update_download"));
                }
            }, 60L);
        }

        ReplayMgr replay = plugin.getReplayMgr();
        if (replay != null) {
            replay.onPlayerJoin(player);
        }

        if (packetFingerprint != null) {
            packetFingerprint.noteJoin(player.getUniqueId());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        PlayerData data = playerDataMap.get(player.getUniqueId());

        if (data != null && data.isFrozen()) {
            for (Player staff : Bukkit.getOnlinePlayers()) {
                if (staff.hasPermission("nochance.alerts")) {
                    staff.sendMessage(lang().get(staff, "listener.frozen_disconnect", "player", player.getName()));
                }
            }
        }

        ReplayMgr replay = plugin.getReplayMgr();
        if (replay != null) {
            replay.onPlayerQuit(player);
        }

        if (staffTools != null) {
            staffTools.cleanup(player.getUniqueId());
        }

        if (sessionTracker != null) {
            SessionTracker.BaselineData bl = sessionTracker.getBaselineForSave(player.getUniqueId());
            if (bl != null) {
                plugin.getDatabase().saveBaseline(player.getUniqueId(),
                    bl.avgCps, bl.cpsVar, bl.avgRot, bl.rotVar,
                    bl.avgMove, bl.moveVar, bl.avgAcc,
                    bl.playtime, bl.sessionCount)
                .exceptionally(ex -> {
                    plugin.getLogger().warning("Failed to save baseline: " + ex.getMessage());
                    return null;
                });
            }
            sessionTracker.cleanup(player.getUniqueId());
        }

        if (packetFingerprint != null) {
            packetFingerprint.cleanup(player.getUniqueId());
        }

        if (transactionTracker != null) {
            transactionTracker.onPlayerQuit(player.getUniqueId());
        }

        Improbable impCleanup = plugin.getImprobable();
        if (impCleanup != null) {
            impCleanup.cleanup(player.getUniqueId());
        }

        if (simBridge != null) {
            simBridge.cleanup(player.getUniqueId());
        }

        VersionAdapter.evict(player.getUniqueId());

        cleanupPlayer(player.getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerKick(PlayerKickEvent event) {
        if (event.isCancelled()) return;

        Player player = event.getPlayer();

        ReplayMgr replay = plugin.getReplayMgr();
        if (replay != null) {
            replay.onPlayerQuit(player);
        }

        if (staffTools != null) {
            staffTools.cleanup(player.getUniqueId());
        }

        if (sessionTracker != null) {
            SessionTracker.BaselineData bl = sessionTracker.getBaselineForSave(player.getUniqueId());
            if (bl != null) {
                plugin.getDatabase().saveBaseline(player.getUniqueId(),
                    bl.avgCps, bl.cpsVar, bl.avgRot, bl.rotVar,
                    bl.avgMove, bl.moveVar, bl.avgAcc,
                    bl.playtime, bl.sessionCount)
                .exceptionally(ex -> {
                    plugin.getLogger().warning("Failed to save baseline: " + ex.getMessage());
                    return null;
                });
            }
            sessionTracker.cleanup(player.getUniqueId());
        }

        if (packetFingerprint != null) {
            packetFingerprint.cleanup(player.getUniqueId());
        }

        if (transactionTracker != null) {
            transactionTracker.onPlayerQuit(player.getUniqueId());
        }

        Improbable impCleanup = plugin.getImprobable();
        if (impCleanup != null) {
            impCleanup.cleanup(player.getUniqueId());
        }

        if (simBridge != null) {
            simBridge.cleanup(player.getUniqueId());
        }

        VersionAdapter.evict(player.getUniqueId());

        cleanupPlayer(player.getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerTeleport(PlayerTeleportEvent event) {
        Player player = event.getPlayer();
        PlayerData data = playerDataMap.get(player.getUniqueId());
        if (data == null) return;

        long now = System.currentTimeMillis();
        if (event.getTo() == null) return;
        data.updateLocationHistory(event.getTo());
        data.clearRotationHistory();
        data.resetFallDistance();
        data.setWasOnGround(true);
        data.setLastVelocityTime(now);
        data.setLastTeleportTime(now);
        if (event.getCause() == PlayerTeleportEvent.TeleportCause.ENDER_PEARL) {
            data.setLastEnderPearlTime(now);
        } else if (event.getCause() == PlayerTeleportEvent.TeleportCause.CHORUS_FRUIT) {
            data.setLastChorusFruitTime(now);
        }
        if (movementGrace != null) {
            MovementGrace.Cause mc;
            switch (event.getCause()) {
                case ENDER_PEARL: mc = MovementGrace.Cause.ENDERPEARL; break;
                case CHORUS_FRUIT: mc = MovementGrace.Cause.CHORUS_FRUIT; break;
                case END_PORTAL:
                case NETHER_PORTAL:
                case END_GATEWAY: mc = MovementGrace.Cause.PORTAL; break;
                case COMMAND: mc = MovementGrace.Cause.COMMAND; break;
                case PLUGIN: mc = MovementGrace.Cause.PLUGIN; break;
                default: mc = MovementGrace.Cause.OTHER; break;
            }
            movementGrace.onTeleport(player.getUniqueId(), now, mc);
        }
        data.setAirTicks(0);
        data.setGroundTicks(0);

        checks.resetPlayerState(player.getUniqueId());
        if (predictionEngine != null) {
            predictionEngine.getMovementPredictor().reset(player.getUniqueId());
        }
        if (simEngine != null) {
            simEngine.resetPlayer(player.getUniqueId());
        }
        if (simBridge != null) {
            simBridge.markTeleport(player.getUniqueId());
        }

        PlayerTeleportEvent.TeleportCause cause = event.getCause();
        if (cause == PlayerTeleportEvent.TeleportCause.PLUGIN ||
            cause == PlayerTeleportEvent.TeleportCause.COMMAND ||
            cause == PlayerTeleportEvent.TeleportCause.ENDER_PEARL ||
            cause == PlayerTeleportEvent.TeleportCause.END_PORTAL ||
            cause == PlayerTeleportEvent.TeleportCause.NETHER_PORTAL ||
            cause == PlayerTeleportEvent.TeleportCause.SPECTATE) {
            checks.getBlinkCheck().handleTeleport(player.getUniqueId());
            checks.getPhaseCheck().handleTeleport(player.getUniqueId());
        }
    }

    private org.bukkit.Material getToolMaterial(Player player) {
        org.bukkit.inventory.ItemStack hand = player.getInventory().getItemInMainHand();
        return hand != null ? hand.getType() : org.bukkit.Material.AIR;
    }

    private int getExpectedBreakTime(org.bukkit.block.Block block, Player player) {
        return checks.getFastBreakCheck().getExpectedBreakTime(player, block);
    }

    private boolean isContainer(Material type) {
        String name = type.name();
        return name.contains("CHEST") || name.contains("BARREL") || name.contains("FURNACE")
            || name.contains("HOPPER") || name.contains("DROPPER") || name.contains("DISPENSER")
            || name.contains("SHULKER") || name.contains("BREWING") || name.contains("ANVIL")
            || type == Material.CRAFTING_TABLE || type == Material.ENCHANTING_TABLE
            || type == Material.SMITHING_TABLE || type == Material.LOOM
            || type == Material.GRINDSTONE || type == Material.STONECUTTER
            || type == Material.CARTOGRAPHY_TABLE;
    }

    public void cleanupPlayer(UUID playerId) {
        if (playerDataMap.remove(playerId) == null) return;
        multiLayerValidator.cleanupPlayer(playerId);
        checks.cleanupPlayer(playerId);
        alertManager.cleanup(playerId);
        plugin.getPunishmentManager().cleanup(playerId);
        if (packetAnalyzer != null) {
            packetAnalyzer.cleanup(playerId);
        }
        if (predictionEngine != null) {
            predictionEngine.resetPlayer(playerId);
        }
        if (simEngine != null) {
            simEngine.resetPlayer(playerId);
        }
        if (plugin.getAsyncProcessor() != null) {
            plugin.getAsyncProcessor().cleanup(playerId);
        }
        if (plugin.getDetectionEngine() != null) {
            plugin.getDetectionEngine().cleanup(playerId);
        }
        if (movementGrace != null) {
            movementGrace.cleanup(playerId);
        }
        lastShiftAlert.remove(playerId);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerPortal(PlayerPortalEvent event) {
        Player player = event.getPlayer();
        if (movementGrace != null) {
            movementGrace.onTeleport(player.getUniqueId(), System.currentTimeMillis(), MovementGrace.Cause.PORTAL);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        PlayerData data = playerDataMap.get(player.getUniqueId());
        if (data == null) return;

        long now = System.currentTimeMillis();
        checks.getProtocolCheck().checkState(player,
            new ProtocolCheck.StateEvent(ProtocolCheck.EventType.RESPAWN, now));

        data.setLastTeleportTime(now);
        if (movementGrace != null) {
            movementGrace.onTeleport(player.getUniqueId(), now, MovementGrace.Cause.RESPAWN);
        }

        data.resetTransientState();
        checks.resetPlayerState(player.getUniqueId());
        if (predictionEngine != null) {
            predictionEngine.resetPlayer(player.getUniqueId());
        }
        if (simEngine != null) {
            simEngine.resetPlayer(player.getUniqueId());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent event) {
        Player player = event.getPlayer();
        PlayerData data = playerDataMap.get(player.getUniqueId());
        if (data == null) return;

        data.resetTransientState();
        checks.resetPlayerState(player.getUniqueId());
        if (predictionEngine != null) {
            predictionEngine.resetPlayer(player.getUniqueId());
        }
        if (simEngine != null) {
            simEngine.resetPlayer(player.getUniqueId());
        }
        if (simBridge != null) {
            simBridge.markDimensionChange(player.getUniqueId());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onGameModeChange(PlayerGameModeChangeEvent event) {
        Player player = event.getPlayer();
        PlayerData data = playerDataMap.get(player.getUniqueId());
        if (data == null) return;

        org.bukkit.GameMode current = player.getGameMode();
        if ((current == org.bukkit.GameMode.CREATIVE || current == org.bukkit.GameMode.SPECTATOR)
                && (event.getNewGameMode() == org.bukkit.GameMode.SURVIVAL
                    || event.getNewGameMode() == org.bukkit.GameMode.ADVENTURE)) {
            data.flyDisableGraceTicks = 30;
        }

        data.resetTransientState();
        checks.resetPlayerState(player.getUniqueId());
        if (predictionEngine != null) {
            predictionEngine.resetPlayer(player.getUniqueId());
        }
        if (simEngine != null) {
            simEngine.resetPlayer(player.getUniqueId());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerToggleFlight(PlayerToggleFlightEvent event) {
        if (event.isFlying()) return;
        Player player = event.getPlayer();
        PlayerData data = playerDataMap.get(player.getUniqueId());
        if (data == null) return;
        data.flyDisableGraceTicks = 30;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onVehicleEnter(VehicleEnterEvent event) {
        if (!(event.getEntered() instanceof Player)) return;
        Player player = (Player) event.getEntered();
        PlayerData data = playerDataMap.get(player.getUniqueId());
        if (data == null) return;

        data.resetTransientState();
        if (movementGrace != null) {
            movementGrace.onMountChange(player.getUniqueId(), System.currentTimeMillis());
        }
        if (simBridge != null) {
            simBridge.markVehicleEnter(player.getUniqueId());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onVehicleExit(VehicleExitEvent event) {
        if (!(event.getExited() instanceof Player)) return;
        Player player = (Player) event.getExited();
        PlayerData data = playerDataMap.get(player.getUniqueId());
        if (data == null) return;

        long now = System.currentTimeMillis();
        data.resetTransientState();
        checks.resetPlayerState(player.getUniqueId());
        checks.getBlinkCheck().handleTeleport(player.getUniqueId());
        checks.getPhaseCheck().handleTeleport(player.getUniqueId());
        if (movementGrace != null) {
            movementGrace.onMountChange(player.getUniqueId(), now);
            movementGrace.onTeleport(player.getUniqueId(), now, MovementGrace.Cause.VEHICLE_DISMOUNT);
        }
        if (predictionEngine != null) {
            predictionEngine.resetPlayer(player.getUniqueId());
        }
        if (simEngine != null) {
            simEngine.resetPlayer(player.getUniqueId());
        }
        if (simBridge != null) {
            simBridge.markVehicleExit(player.getUniqueId());
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        if (player.hasPermission("nochance.bypass") && !config.isOpDetectOnly()) return;

        if (staffTools != null && staffTools.isSpectating(player.getUniqueId())) return;

        PlayerData data = playerDataMap.get(player.getUniqueId());
        if (data == null) return;

        if (data.isFrozen() || (staffTools != null && staffTools.isFrozen(player.getUniqueId()))) {
            event.setTo(event.getFrom());
            return;
        }

        Location to = event.getTo();
        if (to == null) return;

        long moveNow = System.currentTimeMillis();
        long lastMove = data.getLastMoveTime();
        if (lastMove > 0) {
            data.recordPacketDelta(moveNow - lastMove);
        }
        data.setLastMoveTime(moveNow);

        if (data.isBedrockPlayer() && config.isBedrockExempt()) {
            data.updateLocationHistory(to);
            return;
        }

        if (event.getFrom().getX() == to.getX() &&
                event.getFrom().getY() == to.getY() &&
                event.getFrom().getZ() == to.getZ()) {
            return;
        }

        data.updateLocationHistory(to);
        data.updateRotationHistory(to.getYaw(), to.getPitch());

        if (predictionEngine != null && !data.isInGracePeriod(config.getGracePeriod())) {
            predictionEngine.processMovement(player, event.getFrom(), to);
        }

        if (simBridge != null && !data.isInGracePeriod(config.getGracePeriod())) {
            SimBridge.SimResult simResult = simBridge.process(player, event.getFrom(), to);

            double[] predicted = simBridge.getPredictedPosition(player.getUniqueId());
            if (predicted != null) {
                data.setLastPredictedPos(predicted);
            }

            if (simResult.shouldFlag) {
                String detail = simResult.detail +
                    " hd=" + String.format("%.4f", simResult.hDeviation) +
                    " vd=" + String.format("%.4f", simResult.vDeviation) +
                    " ewma=" + String.format("%.4f", simResult.ewmaDeviation) +
                    " consec=" + simResult.consecutiveFlags;
                CheckResult cr = CheckResult.failed(simResult.suggestedType, simResult.severity, detail);
                handleMultiLayerValidation(player, cr, simResult.suggestedType);
            }
        } else if (simEngine != null && !data.isInGracePeriod(config.getGracePeriod())) {
            CheckResult simResult = simEngine.processMovement(player, event.getFrom(), to);
            if (simResult.isFailed()) {
                handleMultiLayerValidation(player, simResult, simResult.getViolationType());
            }
        }

        if (sessionTracker != null) {
            double moveSpeed = Math.sqrt(
                Math.pow(to.getX() - event.getFrom().getX(), 2) +
                Math.pow(to.getZ() - event.getFrom().getZ(), 2)
            );
            sessionTracker.updateSession(player.getUniqueId(),
                data.getAverageCPS(), data.getAverageRotationSpeed(),
                moveSpeed, data.getAverageAccuracy());

            if (data.getTotalChecks() % 200 == 0) {
                SessionTracker.ProfileShift shift = sessionTracker.checkForShift(player.getUniqueId());
                if (shift.detected && shift.severity > 0.85) {
                    long now2 = System.currentTimeMillis();
                    Long last = lastShiftAlert.get(player.getUniqueId());
                    if (last == null || now2 - last >= SHIFT_ALERT_COOLDOWN_MS) {
                        lastShiftAlert.put(player.getUniqueId(), now2);
                        for (org.bukkit.entity.Player staff : Bukkit.getOnlinePlayers()) {
                            if (staff.hasPermission("nochance.alerts") && alertManager.hasAlertsEnabled(staff.getUniqueId())) {
                                staff.sendMessage(lang().get(staff, "listener.profile_shift",
                                        "player", player.getName(),
                                        "metric", shift.metric,
                                        "baseline", String.format("%.2f", shift.baseline),
                                        "current", String.format("%.2f", shift.current),
                                        "severity", String.format("%.2f", shift.severity)));
                            }
                        }
                    }
                }
            }
        }

        CheckResult flyResult = checks.getFlyCheck().check(player, event.getFrom(), event.getTo());
        handleMultiLayerValidation(player, flyResult, ViolationType.FLY);

        CheckResult speedResult = checks.getSpeedCheck().check(player, event.getFrom(), event.getTo());
        handleMultiLayerValidation(player, speedResult, ViolationType.SPEED);

        CheckResult noClipResult = checks.getNoClipCheck().check(player, event.getFrom(), event.getTo());
        handleMultiLayerValidation(player, noClipResult, ViolationType.NOCLIP);

        CheckResult jesusResult = checks.getJesusCheck().check(player, event.getFrom(), event.getTo());
        handleMultiLayerValidation(player, jesusResult, ViolationType.JESUS);

        CheckResult timerResult = checks.getTimerCheck().check(player);
        handleMultiLayerValidation(player, timerResult, ViolationType.TIMER);

        CheckResult phaseResult = checks.getPhaseCheck().check(player, event.getFrom(), event.getTo());
        handleMultiLayerValidation(player, phaseResult, ViolationType.PHASE);

        CheckResult stepResult = checks.getStepCheck().check(player, event.getFrom(), event.getTo());
        handleMultiLayerValidation(player, stepResult, ViolationType.STEP);

        CheckResult blinkResult = checks.getBlinkCheck().checkForBlink(player);
        handleMultiLayerValidation(player, blinkResult, ViolationType.BLINK);

        org.bukkit.inventory.ItemStack hand = player.getInventory().getItemInMainHand();
        boolean eating = player.isHandRaised() && hand != null && hand.getType().isEdible();
        boolean bow = player.isHandRaised() && hand != null && (hand.getType() == Material.BOW || hand.getType() == Material.CROSSBOW);
        boolean blocking = player.isBlocking();
        boolean riding = player.getVehicle() != null;
        checks.getProtocolCheck().updateState(player, eating, blocking, bow,
            player.isSprinting(), player.isSneaking(), riding, false, false);

        CheckResult protocolMove = checks.getProtocolCheck().checkState(player,
            new ProtocolCheck.StateEvent(ProtocolCheck.EventType.MOVE, System.currentTimeMillis()));
        handleMultiLayerValidation(player, protocolMove, ViolationType.PROTOCOL);

        CheckResult noSlowResult = checks.getNoSlowCheck().check(player, event.getFrom(), event.getTo());
        handleMultiLayerValidation(player, noSlowResult, ViolationType.NOSLOW);

        CheckResult groundSpoofResult = checks.getGroundSpoofCheck().check(player, event.getFrom(), event.getTo(), player.isOnGround());
        handleMultiLayerValidation(player, groundSpoofResult, ViolationType.GROUNDSPOOF);

        if (player.isGliding()) {
            if (simBridge != null && !data.isGliding()) {
                simBridge.markElytraTransition(player.getUniqueId());
            }
            data.setGliding(true);
            CheckResult elytraResult = checks.getElytraFlyCheck().check(player, event.getFrom(), event.getTo());
            handleMultiLayerValidation(player, elytraResult, ViolationType.ELYTRAFLY);
        } else {
            if (data.isGliding() && simBridge != null) {
                simBridge.markElytraTransition(player.getUniqueId());
            }
            data.setGliding(false);
        }

        CheckResult striderResult = checks.getStriderCheck().check(player, event.getFrom(), event.getTo());
        handleMultiLayerValidation(player, striderResult, ViolationType.STRIDER);

        CheckResult spiderResult = checks.getSpiderCheck().check(player, event.getFrom(), event.getTo());
        handleMultiLayerValidation(player, spiderResult, ViolationType.SPIDER);

        CheckResult disablerResult = checks.getDisablerCheck().check(player);
        handleMultiLayerValidation(player, disablerResult, ViolationType.PROTOCOL);

        if (player.getVehicle() != null) {
            CheckResult boatResult = checks.getBoatFlyCheck().check(player);
            handleMultiLayerValidation(player, boatResult, ViolationType.BOATFLY);
        }

        CheckResult noFallMoveResult = checks.getNoFallCheck().checkMovement(player, event.getFrom(), event.getTo());
        handleMultiLayerValidation(player, noFallMoveResult, ViolationType.NOFALL);

        checks.getNoFallCheck().updateFallDistance(player);

        if (packetFingerprint != null) {
            PacketFingerprint.FingerprintResult fpResult = packetFingerprint.analyze(player.getUniqueId(), player.getPing());
            if (fpResult.matched && fpResult.confidence > 0.78) {
                CheckResult fpCheck = CheckResult.failed(ViolationType.PROTOCOL, fpResult.confidence,
                    "[Fingerprint] " + fpResult.clientHint + " (" + fpResult.pattern + ")");
                handleMultiLayerValidation(player, fpCheck, ViolationType.PROTOCOL);
            }
        }

        long nowSafe = System.currentTimeMillis();
        if (player.isOnGround()
                && (nowSafe - data.getLastFlagTime()) > 250
                && (nowSafe - data.getLastTeleportTime()) > 1000
                && (nowSafe - data.getLastDamageTime()) > 500
                && !player.isGliding() && !player.isRiptiding()
                && player.getVehicle() == null) {
            data.setLastSafeLocation(event.getFrom().clone());
        }

        if (player.isRiptiding()) data.touchRiptide();
        if (data.riptideActiveTicks > 0) data.riptideActiveTicks--;
        if (data.flyDisableGraceTicks > 0) data.flyDisableGraceTicks--;

        if (checks.getKeepSprintCheck() != null) {
            CheckResult ksResult = checks.getKeepSprintCheck().checkPostHit(player);
            if (ksResult.isFailed()) {
                handleMultiLayerValidation(player, ksResult, ViolationType.VELOCITY);
            }
        }

        if (checks.getAntiVoidCheck() != null) {
            CheckResult avResult = checks.getAntiVoidCheck().check(player, event.getFrom(), to);
            if (avResult.isFailed()) {
                handleMultiLayerValidation(player, avResult, ViolationType.FLY);
            }
        }

        if (checks.getCriticalsCheck() != null) {
            checks.getCriticalsCheck().recordMovement(player, event.getFrom(), to);
        }

        Improbable imp = plugin.getImprobable();
        if (imp != null) {
            CheckResult impResult = imp.evaluate(player);
            if (impResult.isFailed()) {
                handleMultiLayerValidation(player, impResult, ViolationType.PROTOCOL);
            }
        }
        org.bukkit.Location lc = event.getTo();
        if (lc != null) {
            org.bukkit.World w = lc.getWorld();
            if (w != null) {
                org.bukkit.Material below = NC.noChance.core.BlockCache.getType(w, lc.getBlockX(), lc.getBlockY() - 1, lc.getBlockZ());
                if (below == org.bukkit.Material.SLIME_BLOCK) data.touchSlimeContact();
                else if (below == org.bukkit.Material.HONEY_BLOCK) data.touchHoneyContact();
                org.bukkit.Material at = NC.noChance.core.BlockCache.getType(lc);
                if (at == org.bukkit.Material.BUBBLE_COLUMN) data.touchBubbleColumn();
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onBlockDamage(BlockDamageEvent event) {
        Player player = event.getPlayer();
        checks.getFastBreakCheck().onStartDigging(player, event.getBlock());

        ReplayMgr replay = plugin.getReplayMgr();
        if (replay != null) {
            org.bukkit.Material tool = getToolMaterial(player);
            int expected = getExpectedBreakTime(event.getBlock(), player);
            replay.recordBlock(player, event.getBlock(), BlockAction.Type.START_BREAK, 0f, tool, expected);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onBlockBreak(BlockBreakEvent event) {
        if (event.isCancelled()) return;

        Player player = event.getPlayer();
        PlayerData data = playerDataMap.get(player.getUniqueId());
        if (data != null && data.isFrozen()) {
            event.setCancelled(true);
            return;
        }

        ReplayMgr replay = plugin.getReplayMgr();
        if (replay != null) {
            org.bukkit.Material tool = getToolMaterial(player);
            int expected = getExpectedBreakTime(event.getBlock(), player);
            replay.recordBlock(player, event.getBlock(), BlockAction.Type.BREAK, 1f, tool, expected);
        }

        if (player.hasPermission("nochance.bypass")) return;

        if (data != null && data.isBedrockPlayer() && config.isBedrockExempt()) return;

        CheckResult protocolBreak = checks.getProtocolCheck().checkState(player,
            new ProtocolCheck.StateEvent(ProtocolCheck.EventType.BREAK, System.currentTimeMillis()));
        handleMultiLayerValidation(player, protocolBreak, ViolationType.PROTOCOL);

        BlockCache.invalidate(event.getBlock().getLocation());

        CheckResult fastBreakResult = checks.getFastBreakCheck().check(player, event.getBlock());
        handleMultiLayerValidation(player, fastBreakResult, ViolationType.FASTBREAK);

        CheckResult nukerResult = checks.getNukerCheck().check(player, event.getBlock().getLocation());
        handleMultiLayerValidation(player, nukerResult, ViolationType.NUKER);

        CheckResult autoToolResult = checks.getAutoToolCheck().onBlockBreak(player);
        handleMultiLayerValidation(player, autoToolResult, ViolationType.FASTBREAK);

        CheckResult autoMineResult = checks.getAutoMineCheck().onBlockBreak(player,
            event.getBlock().getLocation(), event.getBlock().getType());
        handleMultiLayerValidation(player, autoMineResult, ViolationType.NUKER);

        CheckResult xRayResult = checks.getXRayCheck().onBlockBreak(player, event.getBlock().getType());
        handleMultiLayerValidation(player, xRayResult, ViolationType.NUKER);

        if (data != null) {
            data.setBlockBreakStartTime(0);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onBlockPlace(BlockPlaceEvent event) {
        if (event.isCancelled()) return;

        Player player = event.getPlayer();
        PlayerData data = playerDataMap.get(player.getUniqueId());
        if (data != null && data.isFrozen()) {
            event.setCancelled(true);
            return;
        }

        BlockCache.invalidate(event.getBlock().getLocation());

        ReplayMgr replay = plugin.getReplayMgr();
        if (replay != null) {
            replay.recordBlock(player, event.getBlock(), BlockAction.Type.PLACE, 1f);
            replay.recordAction(player, Snapshot.Action.PLACE_BLOCK);
        }

        if (player.hasPermission("nochance.bypass")) return;

        if (data != null && data.isBedrockPlayer() && config.isBedrockExempt()) return;

        CheckResult protocolPlace = checks.getProtocolCheck().checkState(player,
            new ProtocolCheck.StateEvent(ProtocolCheck.EventType.PLACE, System.currentTimeMillis()));
        handleMultiLayerValidation(player, protocolPlace, ViolationType.PROTOCOL);

        CheckResult fastPlaceResult = checks.getFastPlaceCheck().check(player, event.getBlock().getLocation());
        handleMultiLayerValidation(player, fastPlaceResult, ViolationType.FASTPLACE);

        CheckResult reachResult = checks.getReachCheck().checkBlockReach(player, event.getBlock());
        handleMultiLayerValidation(player, reachResult, ViolationType.REACH);

        CheckResult scaffoldResult = checks.getScaffoldCheck().check(player, event.getBlock());
        handleMultiLayerValidation(player, scaffoldResult, ViolationType.SCAFFOLD);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
        if (event.isCancelled()) return;
        if (!(event.getDamager() instanceof Player)) return;

        Player player = (Player) event.getDamager();
        if (player.hasPermission("nochance.bypass")) return;

        PlayerData data = playerDataMap.get(player.getUniqueId());
        if (data == null) return;

        if (data.isBedrockPlayer() && config.isBedrockExempt()) return;

        data.updateRotationHistory(player.getLocation().getYaw(), player.getLocation().getPitch());
        data.recordAttack(event.getEntity().getUniqueId());

        if (event.getCause() == EntityDamageEvent.DamageCause.ENTITY_SWEEP_ATTACK) {
            checks.getKillAuraCheck().markSweepAttack(player);
        }

        CheckResult protocolAttack = checks.getProtocolCheck().checkState(player,
            new ProtocolCheck.StateEvent(ProtocolCheck.EventType.ATTACK, System.currentTimeMillis()));
        handleMultiLayerValidation(player, protocolAttack, ViolationType.PROTOCOL);

        if (predictionEngine != null && !data.isInGracePeriod(config.getGracePeriod())) {
            predictionEngine.trackTarget(player, event.getEntity());
            predictionEngine.processHit(player, event.getEntity());
        }

        CheckResult killAuraResult = checks.getKillAuraCheck().check(player, event.getEntity());
        ViolationType type = killAuraResult.getViolationType();
        if (type == null) type = ViolationType.KILLAURA;
        handleMultiLayerValidation(player, killAuraResult, type);

        CheckResult reachResult = checks.getReachCheck().checkEntityReach(player, event.getEntity());
        handleMultiLayerValidation(player, reachResult, ViolationType.REACH);

        CheckResult autoClickerResult = checks.getAutoClickerCheck().check(player);
        handleMultiLayerValidation(player, autoClickerResult, ViolationType.AUTOCLICKER);

        if (checks.getKeepSprintCheck() != null) {
            checks.getKeepSprintCheck().recordAttack(player);
        }

        CheckResult aimAssistResult = checks.getAimAssistCheck().check(player, event.getEntity());
        handleMultiLayerValidation(player, aimAssistResult, ViolationType.AIMASSIST);

        boolean isCritical = !player.isOnGround() &&
                            player.getFallDistance() > 0 &&
                            !player.isSprinting() &&
                            !WaterHelper.isInWater(player) &&
                            !player.isClimbing();
        CheckResult criticalsResult = checks.getCriticalsCheck().check(player, event.getEntity(), isCritical);
        handleMultiLayerValidation(player, criticalsResult, ViolationType.CRITICALS);

        ReplayMgr replay = plugin.getReplayMgr();
        if (replay != null) {
            replay.recordAction(player, Snapshot.Action.ATTACK, event.getEntity().getEntityId());
            replay.recordDamage(player, event.getFinalDamage());
        }

        data.updateAccuracy(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onEntityDamage(EntityDamageEvent event) {
        if (event.isCancelled()) return;
        if (!(event.getEntity() instanceof Player)) return;

        Player player = (Player) event.getEntity();
        if (player.hasPermission("nochance.bypass")) return;

        PlayerData data = playerDataMap.get(player.getUniqueId());
        if (data != null) {
            if (event.getCause() == EntityDamageEvent.DamageCause.ENTITY_ATTACK ||
                event.getCause() == EntityDamageEvent.DamageCause.ENTITY_EXPLOSION ||
                event.getCause() == EntityDamageEvent.DamageCause.BLOCK_EXPLOSION) {

                data.setLastVelocityTime(System.currentTimeMillis());
                data.setLastKnockbackVelocity(0.4);

                Vector expectedVelocity = new Vector(0, 0.4, 0);
                checks.getVelocityCheck().recordVelocity(player, expectedVelocity);

                if (event instanceof EntityDamageByEntityEvent) {
                    EntityDamageByEntityEvent edbe = (EntityDamageByEntityEvent) event;
                    checks.getKillAuraCheck().recordDamaged(player, edbe.getDamager(), event.getFinalDamage());
                }
            }
        }

        if (event.getCause() == EntityDamageEvent.DamageCause.FALL) {
            CheckResult noFallResult = checks.getNoFallCheck().checkOnLanding(player, event);
            handleMultiLayerValidation(player, noFallResult, ViolationType.NOFALL);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerAnimation(PlayerAnimationEvent event) {
        Player player = event.getPlayer();
        PlayerData data = playerDataMap.get(player.getUniqueId());
        if (data == null) return;

        if (data.getBlockBreakStartTime() > 0 &&
            System.currentTimeMillis() - data.getBlockBreakStartTime() < 2000) {
            return;
        }

        data.updateClickHistory();
        data.updateAccuracy(false);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        if (player.hasPermission("nochance.bypass")) return;

        PlayerData data = playerDataMap.get(player.getUniqueId());
        if (data == null) return;
        if (data.isBedrockPlayer() && config.isBedrockExempt()) return;

        if (player.isGliding() && (event.getAction() == Action.RIGHT_CLICK_AIR || event.getAction() == Action.RIGHT_CLICK_BLOCK)) {
            org.bukkit.inventory.ItemStack used = event.getItem();
            if (used != null && used.getType() == Material.FIREWORK_ROCKET) {
                data.setLastVelocityTime(System.currentTimeMillis());
            }
        }

        Block block = event.getClickedBlock();
        if (block == null) return;

        Action action = event.getAction();

        if (action == Action.RIGHT_CLICK_BLOCK) {
            ReplayMgr replay = plugin.getReplayMgr();
            if (replay != null) {
                replay.recordAction(player, Snapshot.Action.USE_ITEM);
            }

            CheckResult protocolUse = checks.getProtocolCheck().checkState(player,
                new ProtocolCheck.StateEvent(ProtocolCheck.EventType.USE, System.currentTimeMillis()));
            handleMultiLayerValidation(player, protocolUse, ViolationType.PROTOCOL);

            if (isContainer(block.getType())) {
                CheckResult containerResult = checks.getInteractCheck().checkContainerAccess(player, block);
                handleMultiLayerValidation(player, containerResult, ViolationType.INVALIDINTERACT);
            }
        }

        if (action == Action.LEFT_CLICK_BLOCK || action == Action.RIGHT_CLICK_BLOCK) {
            CheckResult interactResult = checks.getInteractCheck().checkBlockInteraction(
                player, block, event.getBlockFace());
            handleMultiLayerValidation(player, interactResult, ViolationType.INVALIDINTERACT);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerInteractEntity(PlayerInteractEntityEvent event) {
        Player player = event.getPlayer();
        if (player.hasPermission("nochance.bypass")) return;

        PlayerData data = playerDataMap.get(player.getUniqueId());
        if (data == null) return;
        if (data.isBedrockPlayer() && config.isBedrockExempt()) return;

        Entity target = event.getRightClicked();
        CheckResult entityResult = checks.getInteractCheck().checkEntityInteraction(player, target);
        handleMultiLayerValidation(player, entityResult, ViolationType.INVALIDINTERACT);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInventoryClick(InventoryClickEvent event) {
        if (event.isCancelled()) return;
        if (!(event.getWhoClicked() instanceof Player)) return;

        Player player = (Player) event.getWhoClicked();
        if (player.hasPermission("nochance.bypass")) return;

        PlayerData invData = playerDataMap.get(player.getUniqueId());
        if (invData != null && invData.isBedrockPlayer() && config.isBedrockExempt()) return;

        checks.getProtocolCheck().checkState(player,
            new ProtocolCheck.StateEvent(ProtocolCheck.EventType.SLOT_CHANGE, System.currentTimeMillis()));

        CheckResult inventoryResult = checks.getInventoryCheck().check(player, event);
        handleMultiLayerValidation(player, inventoryResult, ViolationType.INVENTORY);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerVelocity(PlayerVelocityEvent event) {
        Player player = event.getPlayer();
        PlayerData data = playerDataMap.get(player.getUniqueId());

        if (data != null && !event.isCancelled()) {
            Vector velocity = event.getVelocity();
            double magnitude = Math.sqrt(velocity.getX() * velocity.getX() +
                                        velocity.getY() * velocity.getY() +
                                        velocity.getZ() * velocity.getZ());

            long velNow = System.currentTimeMillis();
            data.setLastVelocityTime(velNow);
            data.setLastKnockbackVelocity(magnitude);
            if (movementGrace != null) {
                movementGrace.onVelocity(player.getUniqueId(), velNow);
            }

            checks.getVelocityCheck().recordVelocity(player, velocity);

            CheckResult velocityResult = checks.getVelocityCheck().check(player, velocity);
            handleMultiLayerValidation(player, velocityResult, ViolationType.VELOCITY);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerRiptide(PlayerRiptideEvent event) {
        PlayerData data = playerDataMap.get(event.getPlayer().getUniqueId());
        if (data != null) {
            data.riptideActiveTicks = 60;
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerToggleSneak(PlayerToggleSneakEvent event) {
        Player player = event.getPlayer();
        if (player.hasPermission("nochance.bypass")) return;

        PlayerData data = playerDataMap.get(player.getUniqueId());
        if (data == null) return;
        if (data.isBedrockPlayer() && config.isBedrockExempt()) return;

        CheckResult protocolSneak = checks.getProtocolCheck().checkState(player,
            new ProtocolCheck.StateEvent(ProtocolCheck.EventType.SNEAK, System.currentTimeMillis()));
        handleMultiLayerValidation(player, protocolSneak, ViolationType.PROTOCOL);

        if (event.isSneaking() && checks.getScaffoldCheck() != null) {
            checks.getScaffoldCheck().recordSneakOn(player.getUniqueId());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerToggleSprint(PlayerToggleSprintEvent event) {
        Player player = event.getPlayer();
        if (player.hasPermission("nochance.bypass")) return;

        PlayerData data = playerDataMap.get(player.getUniqueId());
        if (data == null) return;
        if (data.isBedrockPlayer() && config.isBedrockExempt()) return;

        CheckResult protocolSprint = checks.getProtocolCheck().checkState(player,
            new ProtocolCheck.StateEvent(ProtocolCheck.EventType.SPRINT, System.currentTimeMillis()));
        handleMultiLayerValidation(player, protocolSprint, ViolationType.PROTOCOL);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerDropItem(PlayerDropItemEvent event) {
        Player player = event.getPlayer();
        ReplayMgr replay = plugin.getReplayMgr();
        if (replay != null) {
            replay.recordAction(player, Snapshot.Action.DROP_ITEM);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerPickupItem(org.bukkit.event.entity.EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player)) return;
        Player player = (Player) event.getEntity();
        ReplayMgr replay = plugin.getReplayMgr();
        if (replay != null) {
            replay.recordAction(player, Snapshot.Action.PICKUP_ITEM);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerItemConsume(PlayerItemConsumeEvent event) {
        Player player = event.getPlayer();
        ReplayMgr replay = plugin.getReplayMgr();
        if (replay != null) {
            replay.recordAction(player, Snapshot.Action.CONSUME);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerItemHeld(PlayerItemHeldEvent event) {
        Player player = event.getPlayer();
        if (player.hasPermission("nochance.bypass")) return;
        checks.getAutoToolCheck().onSlotChange(player.getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerFish(PlayerFishEvent event) {
        Player player = event.getPlayer();
        if (player.hasPermission("nochance.bypass")) return;
        PlayerFishEvent.State state = event.getState();
        if (state == PlayerFishEvent.State.CAUGHT_ENTITY || state == PlayerFishEvent.State.IN_GROUND) {
            Entity caught = event.getCaught();
            if (caught != null && caught.getUniqueId().equals(player.getUniqueId())) {
                PlayerData data = playerDataMap.get(player.getUniqueId());
                if (data != null) {
                    data.setLastVelocityTime(System.currentTimeMillis());
                }
            }
        }
        CheckResult fishResult = checks.getAutoFishCheck().onFish(player, state);
        handleMultiLayerValidation(player, fishResult, ViolationType.AUTOCLICKER);
    }

    private void handleMultiLayerValidation(Player player, CheckResult checkResult, ViolationType type) {
        DiagMetrics dm = plugin.getDiagMetrics();
        if (dm != null && type != null) {
            dm.record(type.name());
        }
        if (!checkResult.isFailed()) {
            return;
        }

        ViolationType actualType = checkResult.getViolationType();
        if (actualType != null) {
            type = actualType;
        }

        Improbable imp = plugin.getImprobable();
        if (imp != null && type != null) {
            String reason = checkResult.getReason();
            if (reason == null || !reason.startsWith("IMPROBABLE")) {
                imp.feed(player, type, Math.min(0.6, checkResult.getSeverity() * 0.4));
            }
        }

        if (predictionEngine != null) {
            predictionEngine.recordViolation(player.getUniqueId(), type, checkResult.getSeverity());
        }

        if (staffTools != null) {
            staffTools.sendVerbose(player, type, checkResult);
        }

        MultiLayerValidator.ValidationResult validationResult = multiLayerValidator.validate(player, checkResult, type);

        MLDataCollector mlc = plugin.getMLCollector();
        if (mlc != null) {
            mlc.record(player.getUniqueId(), type.name(),
                    Map.of("severity", checkResult.getSeverity(), "details", checkResult.getDetails() == null ? "" : checkResult.getDetails()),
                    Map.of(),
                    toMLVerdict(validationResult));
        }

        if (validationResult.shouldFlag()) {
            PlayerData pd = playerDataMap.get(player.getUniqueId());
            if (pd != null) {
                pd.setLastFlagTime(System.currentTimeMillis());
            }
            violationScoring.handleViolationWithAdvancedMetrics(
                player,
                validationResult.getOriginalResult(),
                validationResult.getScore(),
                validationResult.getDetectionMethod(),
                validationResult.getConfidence(),
                validationResult.getVariant(),
                validationResult.getPunishmentMultiplier()
            );
        }
    }

    private MLVerdict toMLVerdict(MultiLayerValidator.ValidationResult v) {
        if (v == null || !v.shouldFlag()) return MLVerdict.PASSED;
        MultiLayerValidator.ConfidenceLevel c = v.getConfidence();
        if (c == MultiLayerValidator.ConfidenceLevel.HIGH || c == MultiLayerValidator.ConfidenceLevel.EXTREME) {
            return MLVerdict.HARD_FLAG;
        }
        return MLVerdict.SOFT_FLAG;
    }

}
