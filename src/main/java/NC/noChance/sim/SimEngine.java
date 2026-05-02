package NC.noChance.sim;

import NC.noChance.core.CheckResult;
import NC.noChance.core.TPSCache;
import NC.noChance.core.ViaHelper;
import NC.noChance.core.ViolationType;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class SimEngine {

    private final Map<UUID, SimPlayer> players = new ConcurrentHashMap<>();
    private final Map<UUID, double[]> predicted = new ConcurrentHashMap<>();

    private static final double BASE_TOLERANCE = 0.03;
    private static final int MIN_TICKS = 20;
    private static final int FLAG_CONSECUTIVE = 15;
    private static final double FLAG_EWMA = 0.08;

    public CheckResult processMovement(Player player, Location from, Location to) {
        if (shouldSkip(player)) {
            return CheckResult.passed();
        }

        UUID id = player.getUniqueId();
        SimPlayer sp = players.computeIfAbsent(id, SimPlayer::new);

        if (!sp.isValid()) {
            sp.init(to.getX(), to.getY(), to.getZ());
            return CheckResult.passed();
        }

        double dx = to.getX() - from.getX();
        double dy = to.getY() - from.getY();
        double dz = to.getZ() - from.getZ();
        double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);

        if (dist > 5.0) {
            sp.softReset(to.getX(), to.getY(), to.getZ());
            predicted.remove(id);
            return CheckResult.passed();
        }

        double actualVelX = to.getX() - from.getX();
        double actualVelY = to.getY() - from.getY();
        double actualVelZ = to.getZ() - from.getZ();
        double actualHSpeed = Math.sqrt(actualVelX * actualVelX + actualVelZ * actualVelZ);

        sp.setSprinting(player.isSprinting());
        sp.setSneaking(player.isSneaking());
        sp.setSwimming(player.isSwimming());
        sp.setClimbing(player.isClimbing());

        World world = player.getWorld();
        boolean onGround = SimCollision.isOnGround(world, to.getX(), to.getY(), to.getZ());

        int belowX = (int) Math.floor(to.getX());
        int belowY = (int) Math.floor(to.getY()) - 1;
        int belowZ = (int) Math.floor(to.getZ());
        Material below = world.getBlockAt(belowX, belowY, belowZ).getType();
        double slip = SimCollision.getBlockSlipperiness(below);

        double maxHSpeed = SimPhysics.computeMaxHorizontalSpeed(sp, player, slip);
        double velYForPred = sp.isOnGround() && actualVelY > 0.2 ? actualVelY : sp.getVelY();
        double expectedVelY = SimPhysics.computeNextVerticalVelocity(velYForPred, sp, player);
        double friction = SimPhysics.computeFriction(slip, sp.isOnGround());

        if ((below == Material.SOUL_SAND || below == Material.SOUL_SOIL)) {
            double soulMult = SimPhysics.getSoulSpeedMultiplier(player);
            if (soulMult > 1.0) {
                maxHSpeed *= soulMult;
            }
        }

        String belowName = below.name();
        if (belowName.contains("ICE")) {
            if (below == Material.BLUE_ICE) {
                maxHSpeed *= 3.2;
            } else {
                maxHSpeed *= 2.6;
            }
        }

        if (player.isInWater()) {
            maxHSpeed *= SimPhysics.WATER_DRAG;
        }

        float walkSpeed = player.getWalkSpeed();
        if (walkSpeed != 0.2f) {
            maxHSpeed *= walkSpeed / 0.2f;
        }

        storePrediction(id, from, sp, maxHSpeed, expectedVelY, friction);

        double tolerance = BASE_TOLERANCE;
        tolerance += Math.min(0.3, Math.max(0, (player.getPing() - 30) * 0.002));
        double tps = TPSCache.getTPS();
        if (tps < 19.0) {
            tolerance += (20.0 - tps) * 0.03;
        }
        if (sp.getAirTicks() < 5) {
            tolerance += 0.05;
        }
        if (sp.getJumpTicks() > 0 && sp.getJumpTicks() < 12) {
            tolerance += 0.04;
        }

        tolerance += ViaHelper.getMovementTolerance(player);

        double hDev = Math.max(0, actualHSpeed - maxHSpeed - tolerance);
        double vDev = Math.abs(actualVelY - expectedVelY) - tolerance;
        if (vDev < 0) {
            vDev = 0;
        }
        double combined = Math.sqrt(hDev * hDev + vDev * vDev);

        sp.recordDeviation(combined, 0.01);
        sp.setTicksExisted(sp.getTicksExisted() + 1);

        sp.setVelocity(actualVelX * friction, actualVelY, actualVelZ * friction);
        sp.updateActual(to.getX(), to.getY(), to.getZ());

        boolean wasOnGround = sp.isOnGround();
        sp.setOnGround(onGround);

        if (onGround) {
            sp.setGroundTicks(sp.getGroundTicks() + 1);
            sp.setAirTicks(0);
        } else {
            sp.setAirTicks(sp.getAirTicks() + 1);
            sp.setGroundTicks(0);
        }

        if (wasOnGround && actualVelY > 0.2) {
            sp.setJumpTicks(1);
        } else if (sp.getJumpTicks() > 0 && !onGround) {
            sp.setJumpTicks(sp.getJumpTicks() + 1);
        } else if (onGround) {
            sp.setJumpTicks(0);
        }

        double height = SimCollision.getPlayerHeight(player.isSneaking(), player.isSwimming());
        if (!SimCollision.isPathClear(world, from.getX(), from.getY(), from.getZ(),
                to.getX(), to.getY(), to.getZ(), height)) {
            sp.recordDeviation(0.1, 0.01);
        }

        if (sp.getSamples() < MIN_TICKS) {
            return CheckResult.passed();
        }

        int consec = sp.getConsecutiveDev();
        double ewma = sp.getEwmaDev();

        if (consec >= FLAG_CONSECUTIVE && ewma > FLAG_EWMA) {
            ViolationType type = getViolationType(hDev, vDev);
            return CheckResult.failed(type, scaleSeverity(ewma, consec),
                    "ewma=" + String.format("%.4f", ewma) + " consec=" + consec);
        }

        if (ewma > 0.12 && consec >= 8) {
            ViolationType type = getViolationType(hDev, vDev);
            return CheckResult.failed(type, scaleSeverity(ewma, consec),
                    "ewma=" + String.format("%.4f", ewma) + " consec=" + consec);
        }

        if (ewma > 0.25) {
            ViolationType type = getViolationType(hDev, vDev);
            return CheckResult.failed(type, Math.min(0.95, 0.70 + ewma * 0.8),
                    "high-ewma=" + String.format("%.4f", ewma));
        }

        if (combined > 0.4 && consec >= 6) {
            ViolationType type = getViolationType(hDev, vDev);
            return CheckResult.failed(type, Math.min(0.95, 0.60 + combined * 0.5),
                    "spike=" + String.format("%.4f", combined) + " consec=" + consec);
        }

        return CheckResult.passed();
    }

    public SimPlayer getSimPlayer(UUID id) {
        return players.get(id);
    }

    public double[] getPredictedPosition(UUID id) {
        return predicted.get(id);
    }

    public void applyKnockback(UUID id, double vx, double vy, double vz) {
        SimPlayer sp = players.get(id);
        if (sp != null && sp.isValid()) {
            sp.setVelocity(vx, vy, vz);
            sp.resetDeviation();
            predicted.remove(id);
        }
    }

    public void resetPlayer(UUID id) {
        players.remove(id);
        predicted.remove(id);
    }

    public void cleanup() {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<UUID, SimPlayer>> it = players.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, SimPlayer> entry = it.next();
            if (now - entry.getValue().getLastUpdate() > 120_000) {
                predicted.remove(entry.getKey());
                it.remove();
            }
        }
    }

    private void storePrediction(UUID id, Location from, SimPlayer sp,
                                  double maxH, double expectedY, double friction) {
        double predVelX = sp.getVelX() * friction;
        double predVelZ = sp.getVelZ() * friction;

        double hMag = Math.sqrt(predVelX * predVelX + predVelZ * predVelZ);
        if (hMag > maxH && hMag > 0) {
            double scale = maxH / hMag;
            predVelX *= scale;
            predVelZ *= scale;
        }

        double predX = from.getX() + predVelX;
        double predY = from.getY() + expectedY;
        double predZ = from.getZ() + predVelZ;

        predicted.put(id, new double[]{predX, predY, predZ});
    }

    private boolean shouldSkip(Player player) {
        GameMode gm = player.getGameMode();
        if (gm == GameMode.CREATIVE || gm == GameMode.SPECTATOR) {
            return true;
        }
        if (player.isFlying() && player.getAllowFlight()) {
            return true;
        }
        if (player.getVehicle() != null) {
            return true;
        }
        if (player.isRiptiding()) {
            return true;
        }
        if (player.isGliding()) {
            return true;
        }
        if (!player.hasGravity()) {
            return true;
        }
        return false;
    }

    private ViolationType getViolationType(double hDev, double vDev) {
        return ViolationType.FLY;
    }

    private double scaleSeverity(double ewma, int consec) {
        double base = 0.55 + Math.min(0.30, ewma * 1.8) + Math.min(0.10, consec * 0.004);
        return Math.min(0.95, base);
    }
}
