package NC.noChance.core;

import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;

public class PrecisionReach {

    private static final double E8_PRECISION = 0.00000001;
    private static final double MAX_ENTITY_REACH = 3.0;
    private static final double MAX_BLOCK_REACH = 4.5;
    private static final double PING_COMPENSATION_BASE = 0.001;
    private static final double PING_COMPENSATION_MAX = 0.55;
    private static final int PING_THRESHOLD_LOW = 50;
    private static final int PING_THRESHOLD_HIGH = 200;

    public static class ReachResult {
        public final double distance;
        public final double maxReach;
        public final boolean valid;
        public final double confidence;
        public final String reason;
        public final Vector closestPoint;

        public ReachResult(double distance, double maxReach, boolean valid, double confidence, String reason, Vector closest) {
            this.distance = distance;
            this.maxReach = maxReach;
            this.valid = valid;
            this.confidence = confidence;
            this.reason = reason;
            this.closestPoint = closest;
        }

        public static ReachResult valid(double distance, double maxReach, Vector closest) {
            return new ReachResult(distance, maxReach, true, 1.0, "Valid reach", closest);
        }

        public static ReachResult invalid(double distance, double maxReach, double confidence, String reason, Vector closest) {
            return new ReachResult(distance, maxReach, false, confidence, reason, closest);
        }
    }

    public static ReachResult checkEntityReach(Player player, Entity target, int ping) {
        Location eyeLocation = player.getEyeLocation();
        Vector eyePos = eyeLocation.toVector();
        Vector lookDirection = eyeLocation.getDirection().normalize();

        BoundingBox targetBox = target.getBoundingBox();
        BoundingBox expandedBox = expandBoxForLatency(targetBox, ping);

        Vector closestPoint = getClosestPointOnBox(eyePos, targetBox);
        Vector closestExpanded = getClosestPointOnBox(eyePos, expandedBox);

        double distance = calculatePreciseDistance(eyePos, closestPoint);
        double distanceExpanded = calculatePreciseDistance(eyePos, closestExpanded);

        double maxReach = MAX_ENTITY_REACH;
        double pingCompensation = calculatePingCompensation(ping);
        maxReach += pingCompensation;

        double rayDistance = getRayBoxIntersection(eyePos, lookDirection, targetBox);
        if (rayDistance > 0 && rayDistance < distance) {
            distance = rayDistance;
        }

        double rayDistanceExpanded = getRayBoxIntersection(eyePos, lookDirection, expandedBox);
        if (rayDistanceExpanded > 0 && rayDistanceExpanded < distanceExpanded) {
            distanceExpanded = rayDistanceExpanded;
        }

        double effectiveDistance = distanceExpanded;
        double threshold = maxReach + 0.005;

        if (effectiveDistance <= threshold) {
            return ReachResult.valid(effectiveDistance, maxReach, closestPoint);
        }

        double excess = effectiveDistance - maxReach;
        double confidence;
        if (excess > 0.5) {
            confidence = Math.min(0.98, 0.85 + (excess - 0.5) * 0.2);
        } else if (excess > 0.15) {
            confidence = Math.min(0.85, 0.70 + (excess - 0.15) * 0.4);
        } else {
            confidence = Math.min(0.70, 0.50 + excess * 1.3);
        }

        String reason = String.format("Reach: %.4f (max: %.2f, excess: %.4f, ping: %d)",
                effectiveDistance, maxReach, excess, ping);

        return ReachResult.invalid(effectiveDistance, maxReach, confidence, reason, closestPoint);
    }

    private static double calculatePingCompensation(int ping) {
        if (ping <= PING_THRESHOLD_LOW) {
            return ping * PING_COMPENSATION_BASE;
        } else if (ping <= PING_THRESHOLD_HIGH) {
            double base = PING_THRESHOLD_LOW * PING_COMPENSATION_BASE;
            double extra = (ping - PING_THRESHOLD_LOW) * PING_COMPENSATION_BASE * 2.0;
            return Math.min(base + extra, PING_COMPENSATION_MAX);
        } else {
            double base = PING_THRESHOLD_LOW * PING_COMPENSATION_BASE;
            double mid = (PING_THRESHOLD_HIGH - PING_THRESHOLD_LOW) * PING_COMPENSATION_BASE * 2.0;
            double extra = (ping - PING_THRESHOLD_HIGH) * PING_COMPENSATION_BASE * 0.5;
            return Math.min(base + mid + extra, PING_COMPENSATION_MAX);
        }
    }

    private static BoundingBox expandBoxForLatency(BoundingBox box, int ping) {
        double expansion = Math.min(0.5, ping * 0.0012);
        return box.clone().expand(expansion);
    }

    public static ReachResult checkBlockReach(Player player, Location blockLocation, int ping) {
        Location eyeLocation = player.getEyeLocation();
        Vector eyePos = eyeLocation.toVector();
        Vector lookDirection = eyeLocation.getDirection().normalize();

        BoundingBox blockBox = new BoundingBox(
                blockLocation.getX(),
                blockLocation.getY(),
                blockLocation.getZ(),
                blockLocation.getX() + 1,
                blockLocation.getY() + 1,
                blockLocation.getZ() + 1
        );

        Vector closestPoint = getClosestPointOnBox(eyePos, blockBox);
        double distance = calculatePreciseDistance(eyePos, closestPoint);

        double rayDistance = getRayBoxIntersection(eyePos, lookDirection, blockBox);
        if (rayDistance > 0 && rayDistance < distance) {
            distance = rayDistance;
        }

        double maxReach = MAX_BLOCK_REACH;
        double pingCompensation = calculatePingCompensation(ping) * 1.2;
        maxReach += pingCompensation;

        double threshold = maxReach + 0.05;

        if (distance <= threshold) {
            return ReachResult.valid(distance, maxReach, closestPoint);
        }

        double excess = distance - maxReach;
        double confidence;
        if (excess > 1.0) {
            confidence = Math.min(0.98, 0.88 + (excess - 1.0) * 0.1);
        } else if (excess > 0.3) {
            confidence = Math.min(0.88, 0.70 + (excess - 0.3) * 0.25);
        } else {
            confidence = Math.min(0.70, 0.45 + excess * 0.8);
        }

        String reason = String.format("Block reach: %.4f (max: %.2f, excess: %.4f)",
                distance, maxReach, excess);

        return ReachResult.invalid(distance, maxReach, confidence, reason, closestPoint);
    }

    private static double calculatePreciseDistance(Vector from, Vector to) {
        double dx = to.getX() - from.getX();
        double dy = to.getY() - from.getY();
        double dz = to.getZ() - from.getZ();

        double distanceSquared = (dx * dx) + (dy * dy) + (dz * dz);

        return Math.sqrt(distanceSquared);
    }

    private static Vector getClosestPointOnBox(Vector point, BoundingBox box) {
        double closestX = clamp(point.getX(), box.getMinX(), box.getMaxX());
        double closestY = clamp(point.getY(), box.getMinY(), box.getMaxY());
        double closestZ = clamp(point.getZ(), box.getMinZ(), box.getMaxZ());

        return new Vector(closestX, closestY, closestZ);
    }

    private static double getRayBoxIntersection(Vector rayOrigin, Vector rayDirection, BoundingBox box) {
        double dirX = rayDirection.getX();
        double dirY = rayDirection.getY();
        double dirZ = rayDirection.getZ();

        double invDirX = Math.abs(dirX) < E8_PRECISION ? 1e16 * Math.signum(dirX == 0 ? 1 : dirX) : 1.0 / dirX;
        double invDirY = Math.abs(dirY) < E8_PRECISION ? 1e16 * Math.signum(dirY == 0 ? 1 : dirY) : 1.0 / dirY;
        double invDirZ = Math.abs(dirZ) < E8_PRECISION ? 1e16 * Math.signum(dirZ == 0 ? 1 : dirZ) : 1.0 / dirZ;

        double tMin = (box.getMinX() - rayOrigin.getX()) * invDirX;
        double tMax = (box.getMaxX() - rayOrigin.getX()) * invDirX;

        if (tMin > tMax) {
            double temp = tMin;
            tMin = tMax;
            tMax = temp;
        }

        double tyMin = (box.getMinY() - rayOrigin.getY()) * invDirY;
        double tyMax = (box.getMaxY() - rayOrigin.getY()) * invDirY;

        if (tyMin > tyMax) {
            double temp = tyMin;
            tyMin = tyMax;
            tyMax = temp;
        }

        if ((tMin > tyMax) || (tyMin > tMax)) {
            return -1;
        }

        if (tyMin > tMin) {
            tMin = tyMin;
        }

        if (tyMax < tMax) {
            tMax = tyMax;
        }

        double tzMin = (box.getMinZ() - rayOrigin.getZ()) * invDirZ;
        double tzMax = (box.getMaxZ() - rayOrigin.getZ()) * invDirZ;

        if (tzMin > tzMax) {
            double temp = tzMin;
            tzMin = tzMax;
            tzMax = temp;
        }

        if ((tMin > tzMax) || (tzMin > tMax)) {
            return -1;
        }

        if (tzMin > tMin) {
            tMin = tzMin;
        }

        if (tMin < 0) {
            return -1;
        }

        return tMin;
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    public static double getHorizontalDistance(Location from, Location to) {
        double dx = to.getX() - from.getX();
        double dz = to.getZ() - from.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }

    public static double getVerticalDistance(Location from, Location to) {
        return Math.abs(to.getY() - from.getY());
    }

    public static Vector interpolatePosition(Vector from, Vector to, double progress) {
        double x = from.getX() + (to.getX() - from.getX()) * progress;
        double y = from.getY() + (to.getY() - from.getY()) * progress;
        double z = from.getZ() + (to.getZ() - from.getZ()) * progress;
        return new Vector(x, y, z);
    }
}
