package NC.noChance.detection.combat;

import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;

public class CombatGeometry {

    public static Vector directionFromYawPitch(float yaw, float pitch) {
        double yRad = Math.toRadians(yaw);
        double pRad = Math.toRadians(pitch);
        double xz = Math.cos(pRad);
        double x = -xz * Math.sin(yRad);
        double y = -Math.sin(pRad);
        double z = xz * Math.cos(yRad);
        return new Vector(x, y, z);
    }

    public static double angleBetweenDegrees(Vector a, Vector b) {
        double la = a.length();
        double lb = b.length();
        if (la < 1.0e-9 || lb < 1.0e-9) return 0.0;
        double dot = (a.getX() * b.getX() + a.getY() * b.getY() + a.getZ() * b.getZ()) / (la * lb);
        if (dot > 0.999999) dot = 0.999999;
        if (dot < -0.999999) dot = -0.999999;
        return Math.toDegrees(Math.acos(dot));
    }

    public static BoundingBox lerpBox(BoundingBox last, BoundingBox current, double factor) {
        double f = clamp(factor, 0.0, 1.0);
        double minX = last.getMinX() + (current.getMinX() - last.getMinX()) * f;
        double minY = last.getMinY() + (current.getMinY() - last.getMinY()) * f;
        double minZ = last.getMinZ() + (current.getMinZ() - last.getMinZ()) * f;
        double maxX = last.getMaxX() + (current.getMaxX() - last.getMaxX()) * f;
        double maxY = last.getMaxY() + (current.getMaxY() - last.getMaxY()) * f;
        double maxZ = last.getMaxZ() + (current.getMaxZ() - last.getMaxZ()) * f;
        return new BoundingBox(minX, minY, minZ, maxX, maxY, maxZ);
    }

    public static Vector closestPointOnBox(Vector point, BoundingBox box) {
        double cx = clamp(point.getX(), box.getMinX(), box.getMaxX());
        double cy = clamp(point.getY(), box.getMinY(), box.getMaxY());
        double cz = clamp(point.getZ(), box.getMinZ(), box.getMaxZ());
        return new Vector(cx, cy, cz);
    }

    public static double distanceRayToPoint(Vector origin, Vector dir, Vector point) {
        Vector d = dir.clone();
        double len = d.length();
        if (len < 1.0e-9) return point.clone().subtract(origin).length();
        d.multiply(1.0 / len);
        Vector op = point.clone().subtract(origin);
        double t = op.dot(d);
        if (t < 0) t = 0;
        Vector proj = origin.clone().add(d.multiply(t));
        return point.clone().subtract(proj).length();
    }

    public static double distanceRayToBox(Vector origin, Vector dir, BoundingBox box) {
        Vector closest = closestPointOnBox(origin, box);
        return distanceRayToPoint(origin, dir, closest);
    }

    public static double lerpFactorFromPing(int pingMs) {
        return Math.min(0.5, pingMs / 200.0);
    }

    private static double clamp(double v, double lo, double hi) {
        if (v < lo) return lo;
        if (v > hi) return hi;
        return v;
    }
}
