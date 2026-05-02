package NC.noChance.detection.movement;

import NC.noChance.core.*;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class SpiderCheck {
    private final ACConfig config;
    private final Map<UUID, PlayerData> playerDataMap;
    private final LayerFiltering filtering;
    private final Map<UUID, Integer> consecutive;
    private final Map<UUID, long[]> lastWallPos;
    private MovementGrace movementGrace;

    public SpiderCheck(ACConfig config, Map<UUID, PlayerData> playerDataMap, LayerFiltering filtering) {
        this.config = config;
        this.playerDataMap = playerDataMap;
        this.filtering = filtering;
        this.consecutive = new ConcurrentHashMap<>();
        this.lastWallPos = new ConcurrentHashMap<>();
    }

    public void setMovementGrace(MovementGrace grace) {
        this.movementGrace = grace;
    }

    public CheckResult check(Player player, Location from, Location to) {
        if (!config.isCheckEnabled("spider")) return CheckResult.passed();
        if (from == null || to == null) return CheckResult.passed();

        UUID id = player.getUniqueId();
        PlayerData data = playerDataMap.get(id);
        if (data == null) return CheckResult.passed();

        if (player.isGliding() || player.isRiptiding() || player.isSwimming()) {
            consecutive.put(id, 0);
            return CheckResult.passed();
        }
        if (player.getVehicle() != null || player.isFlying() || player.getAllowFlight()) {
            consecutive.put(id, 0);
            return CheckResult.passed();
        }
        if (data.isInGracePeriod(config.getGracePeriod())) {
            consecutive.put(id, 0);
            return CheckResult.passed();
        }
        long now = System.currentTimeMillis();
        if (movementGrace != null && (movementGrace.inTeleportGrace(id, now)
                || movementGrace.inVelocityGrace(id, now)
                || movementGrace.inMountGrace(id, now))) {
            consecutive.put(id, 0);
            return CheckResult.passed();
        }

        double dy = to.getY() - from.getY();
        if (dy <= 0.005) {
            consecutive.put(id, 0);
            return CheckResult.passed();
        }

        if (player.isClimbing()) {
            consecutive.put(id, 0);
            return CheckResult.passed();
        }

        long sinceVel = now - data.getLastVelocityTime();
        if (sinceVel < 1500) {
            consecutive.put(id, 0);
            return CheckResult.passed();
        }

        long[] wallPos = findAdjacentSolid(to);
        if (wallPos == null) {
            consecutive.put(id, 0);
            lastWallPos.remove(id);
            return CheckResult.passed();
        }

        long[] prev = lastWallPos.get(id);
        if (prev != null && (prev[0] != wallPos[0] || prev[1] != wallPos[1] || prev[2] != wallPos[2])) {
            consecutive.put(id, 0);
        }
        lastWallPos.put(id, wallPos);

        int count = consecutive.getOrDefault(id, 0) + 1;
        consecutive.put(id, count);

        int threshold = config.getSpiderThreshold();
        if (count >= threshold) {
            CheckResult r = CheckResult.failed(ViolationType.SPIDER, 0.78,
                    "Spider: " + count + " ticks vertical climb against wall");
            if (filtering.passesLayer2HeuristicFiltering(player, ViolationType.SPIDER, r)) {
                return r;
            }
        }
        return CheckResult.passed();
    }

    private long[] findAdjacentSolid(Location loc) {
        World w = loc.getWorld();
        if (w == null) return null;
        int x = loc.getBlockX();
        int y = loc.getBlockY();
        int z = loc.getBlockZ();
        int[][] off = {
            {1, 0}, {-1, 0}, {0, 1}, {0, -1},
            {1, 1}, {1, -1}, {-1, 1}, {-1, -1}
        };
        for (int[] o : off) {
            int wx = x + o[0];
            int wz = z + o[1];
            Material m = BlockCache.getType(w, wx, y, wz);
            if (m != null && m.isSolid() && m != Material.LADDER && m != Material.VINE
                    && m != Material.SCAFFOLDING && m != Material.TWISTING_VINES
                    && m != Material.WEEPING_VINES) {
                return new long[] { wx, y, wz };
            }
        }
        return null;
    }

    public void cleanup(UUID playerId) {
        consecutive.remove(playerId);
        lastWallPos.remove(playerId);
    }

    public void cleanupStale() {
    }
}
