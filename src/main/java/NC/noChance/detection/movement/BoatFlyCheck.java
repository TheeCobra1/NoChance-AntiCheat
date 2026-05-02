package NC.noChance.detection.movement;

import NC.noChance.core.*;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.*;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class BoatFlyCheck {
    private final ACConfig config;
    private final Map<UUID, PlayerData> playerDataMap;
    private final LayerFiltering filtering;
    private final Map<UUID, VehicleData> vehicleDataMap;

    private static final double MAX_BOAT_AIR_Y = 0.12;
    private static final int MAX_AIR_TICKS = 12;

    private MovementGrace movementGrace;

    public BoatFlyCheck(ACConfig config, Map<UUID, PlayerData> playerDataMap, LayerFiltering filtering) {
        this.config = config;
        this.playerDataMap = playerDataMap;
        this.filtering = filtering;
        this.vehicleDataMap = new ConcurrentHashMap<>();
    }

    public void setMovementGrace(MovementGrace grace) {
        this.movementGrace = grace;
    }

    public CheckResult check(Player player) {
        if (!config.isCheckEnabled("boatfly")) {
            return CheckResult.passed();
        }

        Entity vehicle = player.getVehicle();
        if (vehicle == null) {
            return CheckResult.passed();
        }

        if (!(vehicle instanceof org.bukkit.entity.Vehicle)) {
            return CheckResult.passed();
        }

        PlayerData data = playerDataMap.get(player.getUniqueId());
        if (data == null) return CheckResult.passed();

        if (data.isInGracePeriod(config.getGracePeriod())) {
            return CheckResult.passed();
        }

        if (data.isInTeleportGracePeriod(5)) {
            return CheckResult.passed();
        }

        long now = System.currentTimeMillis();
        if (now - data.getLastVelocityTime() < 1500) {
            return CheckResult.passed();
        }

        UUID uuid = player.getUniqueId();
        VehicleData vData = vehicleDataMap.computeIfAbsent(uuid, k -> new VehicleData());

        Location vehicleLoc = vehicle.getLocation();
        Location lastLoc = vData.getLastLocation();

        if (lastLoc == null || vehicleLoc.getWorld() == null || lastLoc.getWorld() != vehicleLoc.getWorld()) {
            vData.setLastLocation(vehicleLoc);
            return CheckResult.passed();
        }

        double deltaY = vehicleLoc.getY() - lastLoc.getY();
        boolean inAir = !isVehicleGrounded(vehicle);
        boolean inWater = isVehicleInLiquid(vehicle);

        vData.recordMovement(deltaY, inAir);
        vData.setLastLocation(vehicleLoc);

        if (inWater) {
            vData.resetAirTicks();
            return CheckResult.passed();
        }

        if (inAir) {
            vData.incrementAirTicks();

            if (deltaY > MAX_BOAT_AIR_Y && vData.getAirTicks() > 5) {
                vData.incrementViolations();

                if (vData.getViolations() < 7) {
                    return CheckResult.passed();
                }

                double severity = Math.min(0.92, 0.70 + deltaY * 0.5 + vData.getViolations() * 0.04);

                CheckResult prelimResult = CheckResult.failed(
                    ViolationType.BOATFLY,
                    severity,
                    String.format("Vehicle rising: deltaY=%.3f airTicks=%d vl=%d",
                        deltaY, vData.getAirTicks(), vData.getViolations())
                );

                if (!filtering.passesLayer2HeuristicFiltering(player, ViolationType.BOATFLY, prelimResult)) {
                    vData.decay();
                    return CheckResult.passed();
                }

                return prelimResult;
            }

            if (vData.getAirTicks() > MAX_AIR_TICKS) {
                List<Double> recentY = vData.getRecentYMovements(20);

                if (recentY.size() >= 20) {
                    double avgFull = recentY.stream().mapToDouble(Double::doubleValue).average().orElse(0);
                    if (avgFull > 0) {
                        vData.incrementViolations();
                        if (vData.getViolations() >= 7) {
                            CheckResult avgRise = CheckResult.failed(
                                ViolationType.BOATFLY,
                                0.90,
                                String.format("Vehicle average rising: avgY=%.4f over 20 ticks vl=%d",
                                    avgFull, vData.getViolations())
                            );
                            if (filtering.passesLayer2HeuristicFiltering(player, ViolationType.BOATFLY, avgRise)) {
                                return avgRise;
                            }
                        }
                    }
                }

                if (recentY.size() >= 10) {
                    double avgY = recentY.stream().mapToDouble(Double::doubleValue).average().orElse(0);
                    long stableCount = recentY.stream().filter(y -> Math.abs(y) < 0.025).count();

                    if (stableCount >= 9 || avgY > -0.015) {
                        vData.incrementViolations();

                        if (vData.getViolations() < 7) {
                            return CheckResult.passed();
                        }

                        CheckResult prelimResult = CheckResult.failed(
                            ViolationType.BOATFLY,
                            0.88,
                            String.format("Vehicle hover: avgY=%.4f stable=%d airTicks=%d vl=%d",
                                avgY, stableCount, vData.getAirTicks(), vData.getViolations())
                        );

                        if (!filtering.passesLayer2HeuristicFiltering(player, ViolationType.BOATFLY, prelimResult)) {
                            vData.decay();
                            return CheckResult.passed();
                        }

                        return prelimResult;
                    }
                }
            }
        } else {
            vData.resetAirTicks();
            vData.decay();
        }

        return CheckResult.passed();
    }

    private boolean isVehicleGrounded(Entity vehicle) {
        Location loc = vehicle.getLocation();

        double[] offsets = {-0.9, -0.45, 0.0, 0.45, 0.9};
        for (double x : offsets) {
            for (double z : offsets) {
                Block block = loc.clone().add(x, -0.1, z).getBlock();
                if (block.getType().isSolid()) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean isVehicleInLiquid(Entity vehicle) {
        Location loc = vehicle.getLocation();
        Block at = loc.getBlock();
        Block below = loc.clone().subtract(0, 0.5, 0).getBlock();
        Block deeper = loc.clone().subtract(0, 1.0, 0).getBlock();
        Block deepest = loc.clone().subtract(0, 1.5, 0).getBlock();

        return isLiquid(at) || isLiquid(below) || isLiquid(deeper) || isLiquid(deepest);
    }

    private boolean isLiquid(Block block) {
        Material type = block.getType();
        return type == Material.WATER || type == Material.BUBBLE_COLUMN ||
               type == Material.LAVA || type.name().contains("WATER");
    }

    public void cleanup(UUID playerId) {
        vehicleDataMap.remove(playerId);
    }

    public void cleanupStale() {
        long now = System.currentTimeMillis();
        vehicleDataMap.entrySet().removeIf(entry -> {
            PlayerData data = playerDataMap.get(entry.getKey());
            return data == null || (now - data.getLastMoveTime()) > 300000;
        });
    }

    private static class VehicleData {
        private Location lastLocation;
        private final Deque<Double> yMovements = new ArrayDeque<>(20);
        private int airTicks;
        private int violations;
        private long lastViolation;

        void recordMovement(double deltaY, boolean inAir) {
            if (yMovements.size() >= 20) {
                yMovements.pollFirst();
            }
            yMovements.addLast(deltaY);
        }

        List<Double> getRecentYMovements(int count) {
            List<Double> result = new ArrayList<>();
            int idx = 0;
            int start = Math.max(0, yMovements.size() - count);
            for (Double y : yMovements) {
                if (idx >= start) {
                    result.add(y);
                }
                idx++;
            }
            return result;
        }

        Location getLastLocation() { return lastLocation; }
        void setLastLocation(Location loc) { lastLocation = loc; }

        int getAirTicks() { return airTicks; }
        void incrementAirTicks() { airTicks++; }
        void resetAirTicks() { airTicks = 0; }

        void incrementViolations() {
            long now = System.currentTimeMillis();
            if (now - lastViolation > 5000) violations = 0;
            violations++;
            lastViolation = now;
        }

        void decay() {
            if (System.currentTimeMillis() - lastViolation > 8000) {
                violations = Math.max(0, violations - 1);
            }
        }

        int getViolations() { return violations; }
    }
}
