package NC.noChance.detection.movement;

import NC.noChance.core.ACConfig;
import NC.noChance.core.CheckResult;
import NC.noChance.core.LayerFiltering;
import NC.noChance.core.PlayerData;
import NC.noChance.core.ViolationType;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class AntiVoidCheck {
    private static final double VOID_DEPTH_OFFSET = 32.0;
    private static final long VOID_ESCAPE_WINDOW_MS = 1500L;
    private static final int VOID_HOVER_TICKS = 6;

    private final ACConfig config;
    private final Map<UUID, PlayerData> playerDataMap;
    private final LayerFiltering filtering;
    private final Map<UUID, VoidState> states = new ConcurrentHashMap<>();

    public AntiVoidCheck(ACConfig config, Map<UUID, PlayerData> playerDataMap, LayerFiltering filtering) {
        this.config = config;
        this.playerDataMap = playerDataMap;
        this.filtering = filtering;
    }

    public CheckResult check(Player player, Location from, Location to) {
        if (player == null || from == null || to == null) return CheckResult.passed();
        if (!config.isCheckEnabled("antivoid")) return CheckResult.passed();
        if (player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR) return CheckResult.passed();
        if (player.isFlying() && player.getAllowFlight()) return CheckResult.passed();
        if (player.getVehicle() != null) return CheckResult.passed();
        if (player.isGliding() || player.isRiptiding()) return CheckResult.passed();

        PlayerData data = playerDataMap.get(player.getUniqueId());
        if (data == null) return CheckResult.passed();
        if (data.isBedrockPlayer() && config.isBedrockExempt()) return CheckResult.passed();
        if (data.isInGracePeriod(config.getGracePeriod())) return CheckResult.passed();
        if (data.isInTeleportGracePeriod(3)) return CheckResult.passed();

        World world = to.getWorld();
        if (world == null) return CheckResult.passed();
        double voidFloor = world.getMinHeight() - VOID_DEPTH_OFFSET;

        UUID uuid = player.getUniqueId();
        VoidState s = states.computeIfAbsent(uuid, k -> new VoidState());
        long now = System.currentTimeMillis();
        double fromY = from.getY();
        double toY = to.getY();

        if (toY < voidFloor) {
            if (s.voidEntryTime == 0L) {
                s.voidEntryTime = now;
                s.voidTicks = 1;
                s.lastSafeY = (fromY > voidFloor) ? fromY : s.lastSafeY;
            } else {
                s.voidTicks++;
            }

            if (s.voidTicks >= VOID_HOVER_TICKS) {
                s.voidTicks = 0;
                s.voidEntryTime = 0L;
                CheckResult prelim = CheckResult.failed(
                        ViolationType.FLY,
                        0.88,
                        String.format("ANTIVOID_HOVER y=%.2f floor=%.2f ticks=%d", toY, voidFloor, VOID_HOVER_TICKS)
                );
                if (!filtering.passesLayer2HeuristicFiltering(player, ViolationType.FLY, prelim)) {
                    return CheckResult.passed();
                }
                return prelim;
            }
            return CheckResult.passed();
        }

        if (s.voidEntryTime != 0L && toY > voidFloor + 1.0) {
            long elapsed = now - s.voidEntryTime;
            double yJump = toY - fromY;
            s.voidEntryTime = 0L;
            s.voidTicks = 0;

            if (elapsed <= VOID_ESCAPE_WINDOW_MS && yJump > 3.0) {
                double severity = Math.min(0.96, 0.82 + Math.min(0.12, yJump * 0.005));
                CheckResult prelim = CheckResult.failed(
                        ViolationType.FLY,
                        severity,
                        String.format("ANTIVOID_TELEPORT jump=%.2f elapsed=%dms", yJump, elapsed)
                );
                if (!filtering.passesLayer2HeuristicFiltering(player, ViolationType.FLY, prelim)) {
                    return CheckResult.passed();
                }
                return prelim;
            }
        }

        if (fromY < voidFloor && toY >= voidFloor) {
            s.voidTicks = 0;
            s.voidEntryTime = 0L;
        }

        return CheckResult.passed();
    }

    public void cleanup(UUID uuid) {
        if (uuid != null) states.remove(uuid);
    }

    private static class VoidState {
        long voidEntryTime = 0L;
        int voidTicks = 0;
        double lastSafeY = 0.0;
    }
}
