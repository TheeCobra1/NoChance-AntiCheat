package NC.noChance.predict;

import NC.noChance.core.BlockCache;
import NC.noChance.core.CheckResult;
import NC.noChance.core.MovementGrace;
import NC.noChance.core.PlayerData;
import NC.noChance.core.ViolationType;
import NC.noChance.sim.SimPhysics;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffectType;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class PhysicsValidator {

    private static final double FLY_EPSILON = 0.18;
    private static final double SPEED_EPSILON = 0.32;
    private static final int MIN_CONSEC = 3;
    private static final long GRACE_AFTER_RESET_MS = 800L;

    private final Map<UUID, State> states = new ConcurrentHashMap<>();
    private final MovementGrace grace;

    public PhysicsValidator(MovementGrace grace) {
        this.grace = grace;
    }

    public CheckResult validateFly(Player player, PlayerData data, Location from, Location to) {
        return validate(player, data, from, to, FLY_EPSILON, ViolationType.FLY, "FLY_PHYSICS_DIVERGENCE");
    }

    public CheckResult validateSpeed(Player player, PlayerData data, Location from, Location to) {
        return validate(player, data, from, to, SPEED_EPSILON, ViolationType.SPEED, "SPEED_PHYSICS_DIVERGENCE");
    }

    private CheckResult validate(Player player, PlayerData data, Location from, Location to,
                                 double baseEpsilon, ViolationType type, String reason) {
        if (player == null || from == null || to == null) return CheckResult.passed();
        if (shouldSkip(player, data, to)) {
            resetState(player.getUniqueId());
            return CheckResult.passed();
        }

        UUID id = player.getUniqueId();
        State st = states.computeIfAbsent(id, k -> new State());

        long now = System.currentTimeMillis();
        if (st.cooldownUntil > now) {
            st.lastX = to.getX();
            st.lastY = to.getY();
            st.lastZ = to.getZ();
            st.lastUpdate = now;
            return CheckResult.passed();
        }

        double ax = to.getX();
        double ay = to.getY();
        double az = to.getZ();

        if (!st.ready) {
            st.lastX = ax;
            st.lastY = ay;
            st.lastZ = az;
            st.vx = ax - from.getX();
            st.vy = ay - from.getY();
            st.vz = az - from.getZ();
            st.ready = true;
            st.lastUpdate = now;
            return CheckResult.passed();
        }

        double moveDist = dist(ax - st.lastX, ay - st.lastY, az - st.lastZ);
        if (moveDist > 4.0) {
            softReset(st, ax, ay, az, now);
            return CheckResult.passed();
        }

        double[] pred = predictNext(player, st, from);
        double divergence = dist(ax - pred[0], ay - pred[1], az - pred[2]);

        int ping = Math.max(0, player.getPing());
        double epsilon = baseEpsilon * Math.max(1.0, ping / 100.0);

        st.vx = ax - from.getX();
        st.vy = ay - from.getY();
        st.vz = az - from.getZ();
        st.lastX = ax;
        st.lastY = ay;
        st.lastZ = az;
        st.lastUpdate = now;

        if (divergence > epsilon) {
            st.consec++;
        } else {
            st.consec = Math.max(0, st.consec - 1);
        }

        if (st.consec >= MIN_CONSEC) {
            int triggered = st.consec;
            st.consec = 0;
            st.cooldownUntil = now + 1000L;
            double severity = Math.min(0.85, 0.55 + (divergence / Math.max(epsilon, 0.05)) * 0.05);
            return CheckResult.failed(type, severity,
                String.format("%s div=%.3f eps=%.3f consec=%d ping=%d",
                    reason, divergence, epsilon, triggered, ping));
        }

        return CheckResult.passed();
    }

    private double[] predictNext(Player player, State st, Location from) {
        World world = from.getWorld();
        boolean ground = player.isOnGround();
        Material below = Material.AIR;
        if (world != null) {
            below = BlockCache.getType(world, from.getBlockX(), from.getBlockY() - 1, from.getBlockZ());
        }
        double slip = slipperiness(below);
        double friction;
        try {
            friction = SimPhysics.computeFriction(slip, ground);
        } catch (Throwable t) {
            friction = ground ? slip * 0.91 : 0.91;
        }

        double afterVX = st.vx * friction;
        double afterVZ = st.vz * friction;

        double nextVY;
        if (ground && st.vy <= 0.001) {
            nextVY = 0.0;
        } else {
            nextVY = (st.vy - SimPhysics.GRAVITY) * SimPhysics.DRAG_Y;
        }

        PotionEffectType slowFall = PotionEffectType.getByName("SLOW_FALLING");
        if (slowFall != null && player.hasPotionEffect(slowFall)) {
            nextVY = Math.max(nextVY, -0.01);
        }

        double px = from.getX() + afterVX;
        double py = from.getY() + nextVY;
        double pz = from.getZ() + afterVZ;
        return new double[]{px, py, pz};
    }

    private double slipperiness(Material type) {
        if (type == Material.ICE || type == Material.PACKED_ICE || type == Material.FROSTED_ICE) return 0.98;
        if (type == Material.BLUE_ICE) return 0.989;
        if (type == Material.SLIME_BLOCK) return 0.8;
        return 0.6;
    }

    private boolean shouldSkip(Player player, PlayerData data, Location to) {
        GameMode gm = player.getGameMode();
        if (gm == GameMode.CREATIVE || gm == GameMode.SPECTATOR) return true;
        if (player.isFlying() && player.getAllowFlight()) return true;
        if (player.getVehicle() != null) return true;
        if (player.isRiptiding()) return true;
        if (player.isGliding()) return true;
        if (!player.hasGravity()) return true;
        if (player.isInWater() || player.isSwimming()) return true;
        if (player.isClimbing()) return true;

        PotionEffectType lev = PotionEffectType.getByName("LEVITATION");
        if (lev != null && player.hasPotionEffect(lev)) return true;

        long now = System.currentTimeMillis();
        if (grace != null) {
            UUID id = player.getUniqueId();
            if (grace.inTeleportGrace(id, now)) return true;
            if (grace.inVelocityGrace(id, now)) return true;
            if (grace.inMountGrace(id, now)) return true;
        }
        if (data != null) {
            if ((now - data.getLastTeleportTime()) < GRACE_AFTER_RESET_MS) return true;
            if ((now - data.getLastVelocityTime()) < GRACE_AFTER_RESET_MS) return true;
            if ((now - data.getLastDamageTime()) < 800L) return true;
            if ((now - data.getLastSlimeContactTime()) < 900L) return true;
            if ((now - data.getLastHoneyContactTime()) < 700L) return true;
            if ((now - data.getLastRiptideTime()) < 1200L) return true;
            if ((now - data.getLastWaterTime()) < 800L) return true;
            if (data.flyDisableGraceTicks > 0) return true;
        }

        World world = to.getWorld();
        if (world != null) {
            Material at = BlockCache.getType(to);
            if (at == Material.COBWEB || at == Material.HONEY_BLOCK || at == Material.SLIME_BLOCK
                    || at == Material.BUBBLE_COLUMN || at == Material.POWDER_SNOW
                    || at == Material.SCAFFOLDING || at == Material.SWEET_BERRY_BUSH) return true;
            if (BlockCache.nameContains(at, "WATER", "LAVA", "VINE", "LADDER")) return true;
            Material below = BlockCache.getType(world, to.getBlockX(), to.getBlockY() - 1, to.getBlockZ());
            if (below == Material.SLIME_BLOCK || below == Material.HONEY_BLOCK) return true;
            if (BlockCache.nameContains(below, "BED", "PISTON")) return true;
        }
        return false;
    }

    private void softReset(State st, double ax, double ay, double az, long now) {
        st.lastX = ax;
        st.lastY = ay;
        st.lastZ = az;
        st.vx = 0;
        st.vy = 0;
        st.vz = 0;
        st.consec = 0;
        st.lastUpdate = now;
        st.ready = false;
    }

    private double dist(double dx, double dy, double dz) {
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    public void resetState(UUID id) {
        states.remove(id);
    }

    public void cleanup() {
        long now = System.currentTimeMillis();
        states.entrySet().removeIf(e -> now - e.getValue().lastUpdate > 120_000L);
    }

    private static class State {
        double lastX, lastY, lastZ;
        double vx, vy, vz;
        int consec;
        long lastUpdate;
        long cooldownUntil;
        boolean ready;
    }
}
