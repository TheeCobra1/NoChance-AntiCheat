package NC.noChance.core;

import NC.noChance.detection.block.*;
import NC.noChance.detection.combat.*;
import NC.noChance.detection.combat.AimAssistCheck;
import NC.noChance.detection.damage.*;
import NC.noChance.detection.movement.*;
import NC.noChance.detection.player.AutoFishCheck;
import NC.noChance.detection.player.BlinkCheck;
import NC.noChance.detection.player.DisablerCheck;
import NC.noChance.detection.player.InventoryCheck;
import NC.noChance.detection.player.NoSlowCheck;
import NC.noChance.detection.player.InteractCheck;
import NC.noChance.detection.player.ProtocolCheck;
import NC.noChance.ml.MLDataCollector;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class CheckRegistry {
    private final Map<String, Object> checks;
    private final ACConfig config;
    private final Map<UUID, PlayerData> playerDataMap;
    private final LayerFiltering filtering;
    private MLDataCollector mlCollector;

    private FlyCheck flyCheck;
    private SpeedCheck speedCheck;
    private NoClipCheck noClipCheck;
    private JesusCheck jesusCheck;
    private FastBreakCheck fastBreakCheck;
    private FastPlaceCheck fastPlaceCheck;
    private NukerCheck nukerCheck;
    private KillAuraCheck killAuraCheck;
    private NoFallCheck noFallCheck;
    private AutoClickerCheck autoClickerCheck;
    private ReachCheck reachCheck;
    private InventoryCheck inventoryCheck;
    private ScaffoldCheck scaffoldCheck;
    private TimerCheck timerCheck;
    private VelocityCheck velocityCheck;
    private CriticalsCheck criticalsCheck;
    private PhaseCheck phaseCheck;
    private StepCheck stepCheck;
    private BlinkCheck blinkCheck;
    private NoSlowCheck noSlowCheck;
    private GroundSpoofCheck groundSpoofCheck;
    private ElytraFlyCheck elytraFlyCheck;
    private StriderCheck striderCheck;
    private BoatFlyCheck boatFlyCheck;
    private AimAssistCheck aimAssistCheck;
    private InteractCheck interactCheck;
    private ProtocolCheck protocolCheck;
    private AutoToolCheck autoToolCheck;
    private AutoMineCheck autoMineCheck;
    private AutoFishCheck autoFishCheck;
    private SpiderCheck spiderCheck;
    private DisablerCheck disablerCheck;
    private XRayCheck xRayCheck;
    private CombatTracker sharedCombatTracker;

    public CheckRegistry(ACConfig config, Map<UUID, PlayerData> playerDataMap, LayerFiltering filtering) {
        this.config = config;
        this.playerDataMap = playerDataMap;
        this.filtering = filtering;
        this.checks = new ConcurrentHashMap<>();
        this.sharedCombatTracker = new CombatTracker();
        initializeChecks();
    }

    public CombatTracker getCombatTracker() { return sharedCombatTracker; }

    private void initializeChecks() {
        flyCheck = new FlyCheck(config, playerDataMap, filtering);
        speedCheck = new SpeedCheck(config, playerDataMap, filtering);
        noClipCheck = new NoClipCheck(config, playerDataMap, filtering);
        jesusCheck = new JesusCheck(config, playerDataMap, filtering);
        fastBreakCheck = new FastBreakCheck(config, playerDataMap, filtering);
        fastPlaceCheck = new FastPlaceCheck(config, playerDataMap, filtering);
        nukerCheck = new NukerCheck(config, playerDataMap, filtering);
        killAuraCheck = new KillAuraCheck(config, playerDataMap, filtering, sharedCombatTracker);
        noFallCheck = new NoFallCheck(config, playerDataMap, filtering);
        autoClickerCheck = new AutoClickerCheck(config, playerDataMap, filtering);
        reachCheck = new ReachCheck(config, playerDataMap, filtering, sharedCombatTracker);
        reachCheck.setKillAuraCheck(killAuraCheck);
        inventoryCheck = new InventoryCheck(config, playerDataMap, filtering);
        scaffoldCheck = new ScaffoldCheck(config, playerDataMap, filtering);
        timerCheck = new TimerCheck(config, playerDataMap, filtering);
        velocityCheck = new VelocityCheck(config, playerDataMap, filtering);
        criticalsCheck = new CriticalsCheck(config, playerDataMap, filtering);
        phaseCheck = new PhaseCheck(config, playerDataMap, filtering);
        stepCheck = new StepCheck(config, playerDataMap, filtering);
        blinkCheck = new BlinkCheck(config, playerDataMap, filtering);
        noSlowCheck = new NoSlowCheck(config, playerDataMap, filtering);
        groundSpoofCheck = new GroundSpoofCheck(config, playerDataMap, filtering);
        elytraFlyCheck = new ElytraFlyCheck(config, playerDataMap, filtering);
        striderCheck = new StriderCheck(config, playerDataMap, filtering);
        boatFlyCheck = new BoatFlyCheck(config, playerDataMap, filtering);
        aimAssistCheck = new AimAssistCheck(config, playerDataMap, filtering);
        interactCheck = new InteractCheck(config, playerDataMap, filtering);
        protocolCheck = new ProtocolCheck(config, playerDataMap, filtering);
        autoToolCheck = new AutoToolCheck(config, playerDataMap, filtering);
        autoMineCheck = new AutoMineCheck(config, playerDataMap, filtering);
        autoFishCheck = new AutoFishCheck(config, playerDataMap, filtering);
        spiderCheck = new SpiderCheck(config, playerDataMap, filtering);
        disablerCheck = new DisablerCheck(config, playerDataMap, filtering);
        xRayCheck = new XRayCheck(config, playerDataMap, filtering);

        registerCheck("fly", flyCheck);
        registerCheck("speed", speedCheck);
        registerCheck("noclip", noClipCheck);
        registerCheck("jesus", jesusCheck);
        registerCheck("fastbreak", fastBreakCheck);
        registerCheck("fastplace", fastPlaceCheck);
        registerCheck("nuker", nukerCheck);
        registerCheck("killaura", killAuraCheck);
        registerCheck("nofall", noFallCheck);
        registerCheck("autoclicker", autoClickerCheck);
        registerCheck("reach", reachCheck);
        registerCheck("inventory", inventoryCheck);
        registerCheck("scaffold", scaffoldCheck);
        registerCheck("timer", timerCheck);
        registerCheck("velocity", velocityCheck);
        registerCheck("criticals", criticalsCheck);
        registerCheck("phase", phaseCheck);
        registerCheck("step", stepCheck);
        registerCheck("blink", blinkCheck);
        registerCheck("noslow", noSlowCheck);
        registerCheck("groundspoof", groundSpoofCheck);
        registerCheck("elytrafly", elytraFlyCheck);
        registerCheck("strider", striderCheck);
        registerCheck("boatfly", boatFlyCheck);
        registerCheck("aimassist", aimAssistCheck);
        registerCheck("interact", interactCheck);
        registerCheck("protocol", protocolCheck);
        registerCheck("autotool", autoToolCheck);
        registerCheck("automine", autoMineCheck);
        registerCheck("autofish", autoFishCheck);
        registerCheck("spider", spiderCheck);
        registerCheck("disabler", disablerCheck);
        registerCheck("xray", xRayCheck);
    }

    public void setMLCollector(MLDataCollector collector) {
        this.mlCollector = collector;
    }

    public MLDataCollector getMLCollector() {
        return mlCollector;
    }

    public void setMovementGrace(MovementGrace grace) {
        flyCheck.setMovementGrace(grace);
        speedCheck.setMovementGrace(grace);
        stepCheck.setMovementGrace(grace);
        phaseCheck.setMovementGrace(grace);
        noClipCheck.setMovementGrace(grace);
        blinkCheck.setMovementGrace(grace);
        noSlowCheck.setMovementGrace(grace);
        groundSpoofCheck.setMovementGrace(grace);
        jesusCheck.setMovementGrace(grace);
        striderCheck.setMovementGrace(grace);
        noFallCheck.setMovementGrace(grace);
        elytraFlyCheck.setMovementGrace(grace);
        boatFlyCheck.setMovementGrace(grace);
        spiderCheck.setMovementGrace(grace);
    }

    private void registerCheck(String name, Object check) {
        checks.put(name.toLowerCase(), check);
    }

    @SuppressWarnings("unchecked")
    public <T> T getCheck(String name) {
        return (T) checks.get(name.toLowerCase());
    }

    public int getCheckCount() {
        return checks.size();
    }

    public void cleanupPlayer(UUID playerId) {
        flyCheck.cleanup(playerId);
        speedCheck.cleanup(playerId);
        noClipCheck.cleanup(playerId);
        jesusCheck.cleanup(playerId);
        fastBreakCheck.cleanup(playerId);
        fastPlaceCheck.cleanup(playerId);
        nukerCheck.cleanup(playerId);
        killAuraCheck.cleanup(playerId);
        noFallCheck.cleanup(playerId);
        autoClickerCheck.cleanup(playerId);
        reachCheck.cleanup(playerId);
        inventoryCheck.cleanup(playerId);
        scaffoldCheck.cleanup(playerId);
        timerCheck.cleanup(playerId);
        velocityCheck.cleanup(playerId);
        criticalsCheck.cleanup(playerId);
        phaseCheck.cleanup(playerId);
        stepCheck.cleanup(playerId);
        blinkCheck.cleanup(playerId);
        noSlowCheck.cleanup(playerId);
        groundSpoofCheck.cleanup(playerId);
        elytraFlyCheck.cleanup(playerId);
        striderCheck.cleanup(playerId);
        boatFlyCheck.cleanup(playerId);
        aimAssistCheck.cleanup(playerId);
        interactCheck.cleanup(playerId);
        protocolCheck.cleanup(playerId);
        autoToolCheck.cleanup(playerId);
        autoMineCheck.cleanup(playerId);
        autoFishCheck.cleanup(playerId);
        spiderCheck.cleanup(playerId);
        disablerCheck.cleanup(playerId);
        xRayCheck.cleanup(playerId);
        filtering.cleanup(playerId);
    }

    public void cleanupStale() {
        for (Object c : checks.values()) {
            try {
                java.lang.reflect.Method m = c.getClass().getMethod("cleanupStale");
                m.invoke(c);
            } catch (NoSuchMethodException ignored) {
            } catch (Exception e) {
            }
        }
    }

    public void unregisterAll() {
        checks.clear();
    }

    public void resetPlayerState(UUID playerId) {
        flyCheck.cleanup(playerId);
        speedCheck.cleanup(playerId);
        noClipCheck.cleanup(playerId);
        jesusCheck.cleanup(playerId);
        fastBreakCheck.cleanup(playerId);
        fastPlaceCheck.cleanup(playerId);
        nukerCheck.cleanup(playerId);
        killAuraCheck.cleanup(playerId);
        noFallCheck.cleanup(playerId);
        autoClickerCheck.cleanup(playerId);
        reachCheck.cleanup(playerId);
        inventoryCheck.cleanup(playerId);
        scaffoldCheck.cleanup(playerId);
        timerCheck.cleanup(playerId);
        velocityCheck.cleanup(playerId);
        criticalsCheck.cleanup(playerId);
        phaseCheck.cleanup(playerId);
        stepCheck.cleanup(playerId);
        blinkCheck.cleanup(playerId);
        noSlowCheck.cleanup(playerId);
        groundSpoofCheck.cleanup(playerId);
        elytraFlyCheck.cleanup(playerId);
        striderCheck.cleanup(playerId);
        boatFlyCheck.cleanup(playerId);
        aimAssistCheck.cleanup(playerId);
        interactCheck.cleanup(playerId);
        protocolCheck.cleanup(playerId);
        autoToolCheck.cleanup(playerId);
        autoMineCheck.cleanup(playerId);
        autoFishCheck.cleanup(playerId);
        spiderCheck.cleanup(playerId);
        disablerCheck.cleanup(playerId);
        xRayCheck.cleanup(playerId);
    }

    public FlyCheck getFlyCheck() { return flyCheck; }
    public SpeedCheck getSpeedCheck() { return speedCheck; }
    public NoClipCheck getNoClipCheck() { return noClipCheck; }
    public JesusCheck getJesusCheck() { return jesusCheck; }
    public FastBreakCheck getFastBreakCheck() { return fastBreakCheck; }
    public FastPlaceCheck getFastPlaceCheck() { return fastPlaceCheck; }
    public NukerCheck getNukerCheck() { return nukerCheck; }
    public KillAuraCheck getKillAuraCheck() { return killAuraCheck; }
    public NoFallCheck getNoFallCheck() { return noFallCheck; }
    public AutoClickerCheck getAutoClickerCheck() { return autoClickerCheck; }
    public ReachCheck getReachCheck() { return reachCheck; }
    public InventoryCheck getInventoryCheck() { return inventoryCheck; }
    public ScaffoldCheck getScaffoldCheck() { return scaffoldCheck; }
    public TimerCheck getTimerCheck() { return timerCheck; }
    public VelocityCheck getVelocityCheck() { return velocityCheck; }
    public CriticalsCheck getCriticalsCheck() { return criticalsCheck; }
    public PhaseCheck getPhaseCheck() { return phaseCheck; }
    public StepCheck getStepCheck() { return stepCheck; }
    public BlinkCheck getBlinkCheck() { return blinkCheck; }
    public NoSlowCheck getNoSlowCheck() { return noSlowCheck; }

    public GroundSpoofCheck getGroundSpoofCheck() { return groundSpoofCheck; }
    public ElytraFlyCheck getElytraFlyCheck() { return elytraFlyCheck; }
    public StriderCheck getStriderCheck() { return striderCheck; }
    public BoatFlyCheck getBoatFlyCheck() { return boatFlyCheck; }
    public AimAssistCheck getAimAssistCheck() { return aimAssistCheck; }
    public InteractCheck getInteractCheck() { return interactCheck; }
    public ProtocolCheck getProtocolCheck() { return protocolCheck; }
    public AutoToolCheck getAutoToolCheck() { return autoToolCheck; }
    public AutoMineCheck getAutoMineCheck() { return autoMineCheck; }
    public AutoFishCheck getAutoFishCheck() { return autoFishCheck; }
    public SpiderCheck getSpiderCheck() { return spiderCheck; }
    public DisablerCheck getDisablerCheck() { return disablerCheck; }
    public XRayCheck getXRayCheck() { return xRayCheck; }
}
