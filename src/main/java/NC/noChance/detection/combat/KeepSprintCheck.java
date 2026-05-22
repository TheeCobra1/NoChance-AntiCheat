package NC.noChance.detection.combat;

import NC.noChance.core.ACConfig;
import NC.noChance.core.CheckResult;
import NC.noChance.core.LayerFiltering;
import NC.noChance.core.PlayerData;
import NC.noChance.core.ViolationType;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class KeepSprintCheck {
    private static final long EVAL_MIN_MS = 80L;
    private static final long EVAL_MAX_MS = 250L;
    private static final double SPRINT_RETAIN_RATIO = 0.85;
    private static final double MIN_PRE_HIT_SPEED = 0.18;
    private static final int FLAG_STREAK = 5;
    private static final int STREAK_CAP = 20;

    private final ACConfig config;
    private final Map<UUID, PlayerData> playerDataMap;
    private final LayerFiltering filtering;
    private final Map<UUID, Snapshot> pending = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> streaks = new ConcurrentHashMap<>();

    public KeepSprintCheck(ACConfig config, Map<UUID, PlayerData> playerDataMap, LayerFiltering filtering) {
        this.config = config;
        this.playerDataMap = playerDataMap;
        this.filtering = filtering;
    }

    public void recordAttack(Player attacker) {
        if (attacker == null || !attacker.isOnline()) return;
        if (!config.isCheckEnabled("velocity")) return;
        if (attacker.getVehicle() != null) return;
        if (attacker.isGliding() || attacker.isRiptiding() || attacker.isSwimming()) return;
        if (!attacker.isSprinting() || !attacker.isOnGround()) return;
        Vector v = attacker.getVelocity();
        double horiz = Math.sqrt(v.getX() * v.getX() + v.getZ() * v.getZ());
        if (horiz < MIN_PRE_HIT_SPEED) return;
        pending.put(attacker.getUniqueId(), new Snapshot(System.currentTimeMillis(), horiz));
    }

    public CheckResult checkPostHit(Player player) {
        if (player == null) return CheckResult.passed();
        UUID uuid = player.getUniqueId();
        Snapshot snap = pending.get(uuid);
        if (snap == null) return CheckResult.passed();
        long elapsed = System.currentTimeMillis() - snap.time;
        if (elapsed < EVAL_MIN_MS) return CheckResult.passed();
        if (elapsed > EVAL_MAX_MS) {
            pending.remove(uuid);
            return CheckResult.passed();
        }

        PlayerData data = playerDataMap.get(uuid);
        if (data != null && data.isInTeleportGracePeriod(3)) {
            pending.remove(uuid);
            return CheckResult.passed();
        }
        if (data != null && (System.currentTimeMillis() - data.getLastDamageTime()) < 500L) {
            pending.remove(uuid);
            return CheckResult.passed();
        }

        Vector v = player.getVelocity();
        double horiz = Math.sqrt(v.getX() * v.getX() + v.getZ() * v.getZ());
        double ratio = snap.preHitSpeed > 0 ? horiz / snap.preHitSpeed : 0;
        pending.remove(uuid);

        if (ratio >= SPRINT_RETAIN_RATIO && player.isSprinting() && player.isOnGround()) {
            int streak = streaks.merge(uuid, 1, (a, b) -> Math.min(STREAK_CAP, a + b));
            if (streak >= FLAG_STREAK) {
                double severity = Math.min(0.96, 0.78 + (streak - FLAG_STREAK) * 0.02);
                CheckResult prelim = CheckResult.failed(
                        ViolationType.VELOCITY,
                        severity,
                        String.format("KeepSprint: %d hits, post/pre ratio %.2f", streak, ratio)
                );
                if (!filtering.passesLayer2HeuristicFiltering(player, ViolationType.VELOCITY, prelim)) {
                    return CheckResult.passed();
                }
                return prelim;
            }
        } else {
            streaks.remove(uuid);
        }
        return CheckResult.passed();
    }

    public void cleanup(UUID uuid) {
        if (uuid == null) return;
        pending.remove(uuid);
        streaks.remove(uuid);
    }

    private static class Snapshot {
        final long time;
        final double preHitSpeed;

        Snapshot(long time, double preHitSpeed) {
            this.time = time;
            this.preHitSpeed = preHitSpeed;
        }
    }
}
