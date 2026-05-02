package NC.noChance.replay;

import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.Player;

public class ReplayOverlay {

    private static final double GRAVITY = 0.08;
    private static final double DRAG_Y = 0.98;
    private static final double DRAG_H_GROUND = 0.91;
    private static final double DRAG_H_AIR = 0.98;
    private static final double BASE_SPEED = 0.1;
    private static final double SPRINT_SPEED = 0.13;
    private static final double JUMP_VEL = 0.42;
    private static final double DIVERGE_THRESHOLD = 0.5;
    private static final double REACH_NORMAL = 3.0;
    private static final double REACH_WARN = 3.5;
    private static final int LINE_POINTS = 8;
    private static final double HITBOX_W = 0.6;
    private static final double HITBOX_H = 1.8;

    private static final Particle.DustOptions DUST_RED = new Particle.DustOptions(Color.RED, 0.8f);
    private static final Particle.DustOptions DUST_GREEN = new Particle.DustOptions(Color.GREEN, 0.8f);
    private static final Particle.DustOptions DUST_YELLOW = new Particle.DustOptions(Color.YELLOW, 0.8f);
    private static final Particle.DustOptions DUST_GRAY = new Particle.DustOptions(Color.GRAY, 0.6f);
    private static final Particle.DustOptions DUST_BOX = new Particle.DustOptions(Color.fromRGB(100, 200, 255), 0.5f);

    private Player viewer;
    private World world;
    private FakePlayer ghost;
    private double predX, predY, predZ;
    private double predVelX, predVelY, predVelZ;
    private double cumulativeDev;
    private boolean initialized;

    public void init(Player viewer, World world, String name) {
        if (ghost != null) {
            ghost.destroy();
        }
        this.viewer = viewer;
        this.world = world;
        this.ghost = new FakePlayer(viewer, "~" + trimName(name), world, NamedTextColor.DARK_GRAY);
        this.cumulativeDev = 0;
        this.initialized = false;
    }

    public void processSnapshot(Snapshot snap, Snapshot prev) {
        if (ghost == null || viewer == null) return;

        if (!initialized) {
            predX = snap.x;
            predY = snap.y;
            predZ = snap.z;
            predVelX = snap.velX;
            predVelY = snap.velY;
            predVelZ = snap.velZ;
            ghost.spawn(predX, predY, predZ, snap.yaw, snap.pitch);
            ghost.sendSpawnPackets();
            ghost.sendSpawnEntity();
            initialized = true;
            return;
        }

        if (prev == null) return;

        long dt = snap.timestamp - prev.timestamp;
        int ticks = Math.max(1, (int) Math.round(dt / 50.0));

        for (int i = 0; i < ticks; i++) {
            stepPhysics(prev.onGround, prev.sprinting, prev.jumping, prev.swimming, prev.gliding);
        }

        ghost.move(predX, predY, predZ, snap.yaw, snap.pitch, snap.onGround);
        ghost.sneak(snap.sneaking);
        ghost.setSprinting(snap.sprinting);
        ghost.setGliding(snap.gliding);
        ghost.setSwimming(snap.swimming);

        double dx = snap.x - predX;
        double dy = snap.y - predY;
        double dz = snap.z - predZ;
        double dev = Math.sqrt(dx * dx + dy * dy + dz * dz);
        cumulativeDev += dev;

        if (dev > DIVERGE_THRESHOLD) {
            drawDivergeLine(snap.x, snap.y, snap.z, predX, predY, predZ);
        }

        if (snap.action == Snapshot.Action.ATTACK) {
            drawReachLine(viewer, snap);
            if (snap.damage > 0) {
                double[] target = computeTarget(snap);
                drawHitbox(viewer, target[0], target[1], target[2]);
            }
        }
    }

    public void drawReachLine(Player viewer, Snapshot snap) {
        double[] dir = lookDir(snap.yaw, snap.pitch);
        double eyeX = snap.x;
        double eyeY = snap.y + 1.62;
        double eyeZ = snap.z;

        double reach = REACH_NORMAL;
        Particle.DustOptions color;

        if (snap.damage > 0) {
            double[] target = computeTarget(snap);
            double tdx = target[0] - eyeX;
            double tdy = target[1] - eyeY;
            double tdz = target[2] - eyeZ;
            reach = Math.sqrt(tdx * tdx + tdy * tdy + tdz * tdz);

            if (reach <= REACH_NORMAL) {
                color = DUST_GREEN;
            } else if (reach <= REACH_WARN) {
                color = DUST_YELLOW;
            } else {
                color = DUST_RED;
            }
        } else {
            color = DUST_GRAY;
        }

        for (int i = 0; i <= LINE_POINTS; i++) {
            double t = (double) i / LINE_POINTS;
            double px = eyeX + dir[0] * reach * t;
            double py = eyeY + dir[1] * reach * t;
            double pz = eyeZ + dir[2] * reach * t;
            Location loc = new Location(world, px, py, pz);

            if (snap.damage > 0) {
                viewer.spawnParticle(Particle.FLAME, loc, 1, 0, 0, 0, 0);
            }
            viewer.spawnParticle(Particle.DUST, loc, 1, 0, 0, 0, color);
        }
    }

    public void drawHitbox(Player viewer, double tx, double ty, double tz) {
        double hw = HITBOX_W / 2.0;
        int segments = 4;

        double[][] corners = {
            {tx - hw, ty, tz - hw},
            {tx + hw, ty, tz - hw},
            {tx + hw, ty, tz + hw},
            {tx - hw, ty, tz + hw},
            {tx - hw, ty + HITBOX_H, tz - hw},
            {tx + hw, ty + HITBOX_H, tz - hw},
            {tx + hw, ty + HITBOX_H, tz + hw},
            {tx - hw, ty + HITBOX_H, tz + hw}
        };

        int[][] edges = {
            {0, 1}, {1, 2}, {2, 3}, {3, 0},
            {4, 5}, {5, 6}, {6, 7}, {7, 4},
            {0, 4}, {1, 5}, {2, 6}, {3, 7}
        };

        for (int[] edge : edges) {
            double[] a = corners[edge[0]];
            double[] b = corners[edge[1]];
            for (int i = 0; i <= segments; i++) {
                double t = (double) i / segments;
                double px = a[0] + (b[0] - a[0]) * t;
                double py = a[1] + (b[1] - a[1]) * t;
                double pz = a[2] + (b[2] - a[2]) * t;
                viewer.spawnParticle(Particle.DUST, new Location(world, px, py, pz), 1, 0, 0, 0, DUST_BOX);
            }
        }
    }

    public double getDeviation() {
        return cumulativeDev;
    }

    public void destroy() {
        if (ghost != null) {
            ghost.destroy();
            ghost = null;
        }
        initialized = false;
    }

    private void stepPhysics(boolean onGround, boolean sprinting, boolean jumping, boolean swimming, boolean gliding) {
        if (gliding) {
            predVelY *= 0.99;
            predVelX *= 0.99;
            predVelZ *= 0.99;
            predVelY -= 0.05;
        } else if (swimming) {
            predVelY -= GRAVITY;
            predVelY *= 0.8;
            predVelX *= 0.8;
            predVelZ *= 0.8;
        } else {
            if (onGround && jumping) {
                predVelY = JUMP_VEL;
            }

            predVelY -= GRAVITY;
            predVelY *= DRAG_Y;

            double dragH = onGround ? DRAG_H_GROUND : DRAG_H_AIR;
            double accel = sprinting ? SPRINT_SPEED : BASE_SPEED;

            double hSpeed = Math.sqrt(predVelX * predVelX + predVelZ * predVelZ);
            if (hSpeed > 0.001) {
                predVelX = predVelX * dragH;
                predVelZ = predVelZ * dragH;
            } else {
                predVelX *= dragH;
                predVelZ *= dragH;
            }
        }

        if (onGround && predVelY < 0) {
            predVelY = -0.0784;
        }

        predX += predVelX;
        predY += predVelY;
        predZ += predVelZ;
    }

    private void drawDivergeLine(double ax, double ay, double az, double bx, double by, double bz) {
        int points = 6;
        for (int i = 0; i <= points; i++) {
            double t = (double) i / points;
            double px = ax + (bx - ax) * t;
            double py = (ay + 1.0) + ((by + 1.0) - (ay + 1.0)) * t;
            double pz = az + (bz - az) * t;
            viewer.spawnParticle(Particle.DUST, new Location(world, px, py, pz), 1, 0, 0, 0, DUST_RED);
        }
    }

    private double[] lookDir(float yaw, float pitch) {
        double radYaw = Math.toRadians(yaw);
        double radPitch = Math.toRadians(pitch);
        double cosP = Math.cos(radPitch);
        return new double[]{
            -Math.sin(radYaw) * cosP,
            -Math.sin(radPitch),
            Math.cos(radYaw) * cosP
        };
    }

    private double[] computeTarget(Snapshot snap) {
        double[] dir = lookDir(snap.yaw, snap.pitch);
        double eyeX = snap.x;
        double eyeY = snap.y + 1.62;
        double eyeZ = snap.z;
        return new double[]{
            eyeX + dir[0] * REACH_NORMAL,
            eyeY + dir[1] * REACH_NORMAL,
            eyeZ + dir[2] * REACH_NORMAL
        };
    }

    private String trimName(String name) {
        if (name.length() > 14) return name.substring(0, 14);
        return name;
    }
}
