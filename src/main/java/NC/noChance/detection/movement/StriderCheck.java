package NC.noChance.detection.movement;

import NC.noChance.core.*;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffectType;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class StriderCheck {
    private final ACConfig config;
    private final Map<UUID, PlayerData> playerDataMap;
    private final LayerFiltering filtering;
    private final Map<UUID, LavaData> lavaDataMap;

    private MovementGrace movementGrace;

    public StriderCheck(ACConfig config, Map<UUID, PlayerData> playerDataMap, LayerFiltering filtering) {
        this.config = config;
        this.playerDataMap = playerDataMap;
        this.filtering = filtering;
        this.lavaDataMap = new ConcurrentHashMap<>();
    }

    public void setMovementGrace(MovementGrace grace) {
        this.movementGrace = grace;
    }

    public CheckResult check(Player player, Location from, Location to) {
        if (!config.isCheckEnabled("strider")) {
            return CheckResult.passed();
        }

        PlayerData data = playerDataMap.get(player.getUniqueId());
        if (data == null) return CheckResult.passed();

        if (data.isInGracePeriod(config.getGracePeriod())) {
            return CheckResult.passed();
        }

        if (data.isInTeleportGracePeriod(3)) {
            return CheckResult.passed();
        }

        if (player.getGameMode() == org.bukkit.GameMode.CREATIVE || player.getGameMode() == org.bukkit.GameMode.SPECTATOR) {
            return CheckResult.passed();
        }

        long now = System.currentTimeMillis();
        if (now - data.getLastVelocityTime() < 1000) {
            return CheckResult.passed();
        }

        if (player.getVehicle() != null) {
            return CheckResult.passed();
        }

        if (movementGrace != null && movementGrace.inMountGrace(player.getUniqueId(), System.currentTimeMillis())) {
            return CheckResult.passed();
        }

        boolean hasFireRes = false;
        PotionEffectType fireResType = PotionEffectType.getByName("FIRE_RESISTANCE");
        if (fireResType != null && player.hasPotionEffect(fireResType)) {
            hasFireRes = true;
        }

        if (player.isFlying() || player.isGliding()) {
            return CheckResult.passed();
        }

        Block atBlock = to.getBlock();
        Block belowBlock = to.clone().subtract(0, 0.5, 0).getBlock();

        boolean inLava = atBlock.getType() == Material.LAVA;
        boolean onLava = belowBlock.getType() == Material.LAVA;

        if (!inLava && !onLava) {
            return CheckResult.passed();
        }

        UUID uuid = player.getUniqueId();
        LavaData lavaData = lavaDataMap.computeIfAbsent(uuid, k -> new LavaData());

        double deltaY = to.getY() - from.getY();
        double horizontalSpeed = Math.sqrt(
            Math.pow(to.getX() - from.getX(), 2) +
            Math.pow(to.getZ() - from.getZ(), 2)
        );

        lavaData.recordMovement(deltaY, horizontalSpeed);

        if (onLava && !inLava) {
            boolean hasSolidBelow = hasSolidUnderLava(to, 10);

            if (!hasSolidBelow) {
                if (deltaY > -0.01 && horizontalSpeed > 0.1) {
                    lavaData.incrementViolations();

                    int minVl = 6;
                    if (lavaData.getViolations() < minVl && !lavaData.hasBurst()) {
                        return CheckResult.passed();
                    }

                    double severity = Math.min(0.92, 0.70 + lavaData.getViolations() * 0.05);
                    if (hasFireRes) severity *= 0.8;

                    CheckResult prelimResult = CheckResult.failed(
                        ViolationType.STRIDER,
                        severity,
                        String.format("Lava walk: deltaY=%.3f hSpeed=%.3f vl=%d",
                            deltaY, horizontalSpeed, lavaData.getViolations())
                    );

                    if (!filtering.passesLayer2HeuristicFiltering(player, ViolationType.STRIDER, prelimResult)) {
                        lavaData.decay();
                        return CheckResult.passed();
                    }

                    return prelimResult;
                }
            }
        }

        if (inLava && deltaY > 0.3) {
            List<Double> recentY = lavaData.getRecentYMovements(8);
            long upwardCount = recentY.stream().filter(y -> y > 0.2).count();

            if (upwardCount >= 4) {
                lavaData.incrementViolations();

                if (lavaData.getViolations() < 7) {
                    return CheckResult.passed();
                }

                CheckResult prelimResult = CheckResult.failed(
                    ViolationType.STRIDER,
                    0.85,
                    String.format("Lava fly: upwardMoves=%d deltaY=%.3f vl=%d",
                        upwardCount, deltaY, lavaData.getViolations())
                );

                if (!filtering.passesLayer2HeuristicFiltering(player, ViolationType.STRIDER, prelimResult)) {
                    lavaData.decay();
                    return CheckResult.passed();
                }

                return prelimResult;
            }
        }

        lavaData.decay();
        return CheckResult.passed();
    }

    private boolean hasSolidUnderLava(Location loc, int depth) {
        for (int y = 1; y <= depth; y++) {
            Block block = loc.clone().subtract(0, y, 0).getBlock();
            if (block.getType().isSolid()) {
                return true;
            }
            if (block.getType() != Material.LAVA) {
                break;
            }
        }
        return false;
    }

    public void cleanup(UUID playerId) {
        lavaDataMap.remove(playerId);
    }

    public void cleanupStale() {
        long now = System.currentTimeMillis();
        lavaDataMap.entrySet().removeIf(entry -> {
            PlayerData data = playerDataMap.get(entry.getKey());
            return data == null || (now - data.getLastMoveTime()) > 300000;
        });
    }

    private static class LavaData {
        private final Deque<Double> yMovements = new ArrayDeque<>(20);
        private final Deque<Long> burstTimes = new ArrayDeque<>(8);
        private int violations;
        private long lastViolation;

        void recordMovement(double deltaY, double hSpeed) {
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

        void incrementViolations() {
            long now = System.currentTimeMillis();
            if (now - lastViolation > 4000) violations = 0;
            violations++;
            lastViolation = now;
            if (burstTimes.size() >= 8) burstTimes.pollFirst();
            burstTimes.addLast(now);
        }

        boolean hasBurst() {
            long now = System.currentTimeMillis();
            int recent = 0;
            for (Long t : burstTimes) {
                if (now - t <= 100) recent++;
            }
            return recent >= 4;
        }

        void decay() {
            if (System.currentTimeMillis() - lastViolation > 6000) {
                violations = Math.max(0, violations - 1);
            }
        }

        int getViolations() { return violations; }
    }
}
