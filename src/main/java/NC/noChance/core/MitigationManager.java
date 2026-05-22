package NC.noChance.core;

import NC.noChance.punishment.PunishmentExecutor;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class MitigationManager {
    public enum Result { NONE, CANCELLED, SETBACK }

    private static final long PER_PLAYER_THROTTLE_MS = 50L;
    private final ACConfig config;
    private PunishmentExecutor executor;
    private final Map<UUID, Long> lastMitigation = new ConcurrentHashMap<>();

    public MitigationManager(ACConfig config) {
        this.config = config;
    }

    public void setExecutor(PunishmentExecutor executor) {
        this.executor = executor;
    }

    public Result mitigate(Player player, ViolationType type, CheckResult result, Cancellable event) {
        if (player == null || type == null || result == null) return Result.NONE;
        if (!result.isFailed()) return Result.NONE;
        if (!config.isMitigationEnabled()) return Result.NONE;

        double conf = result.getSeverity();
        double low = config.getMitigationMinConfidence();
        double high = config.getMitigationMaxConfidence();
        if (conf < low || conf > high) return Result.NONE;

        UUID uuid = player.getUniqueId();
        long now = System.currentTimeMillis();
        Long prev = lastMitigation.get(uuid);
        if (prev != null && now - prev < PER_PLAYER_THROTTLE_MS) return Result.NONE;
        lastMitigation.put(uuid, now);

        switch (type) {
            case REACH:
            case KILLAURA:
            case KILLAURA_MULTI:
            case KILLAURA_ANGLE:
            case KILLAURA_ROTATION:
            case KILLAURA_PATTERN:
            case AIMASSIST:
            case AIMASSIST_SILENT:
            case CRITICALS:
            case VELOCITY:
                if (event != null && !event.isCancelled()) {
                    event.setCancelled(true);
                    return Result.CANCELLED;
                }
                return Result.NONE;
            case SCAFFOLD:
            case SCAFFOLD_BRIDGE:
            case SCAFFOLD_TOWER:
            case FASTPLACE:
            case FASTBREAK:
            case NUKER:
            case AUTOCLICKER:
                if (event != null && !event.isCancelled()) {
                    event.setCancelled(true);
                    return Result.CANCELLED;
                }
                return Result.NONE;
            case FLY:
            case FLY_HOVER:
            case FLY_VERTICAL:
            case FLY_GLIDE:
            case SPEED:
            case SPEED_GROUND:
            case SPEED_AIR:
            case SPEED_STRAFE:
            case PHASE:
            case NOCLIP:
            case JESUS:
            case STEP:
            case SPIDER:
            case STRAFE:
                if (executor != null && executor.trySetback(player, type)) {
                    return Result.SETBACK;
                }
                return Result.NONE;
            default:
                return Result.NONE;
        }
    }

    public void cleanup(UUID uuid) {
        if (uuid != null) lastMitigation.remove(uuid);
    }
}
