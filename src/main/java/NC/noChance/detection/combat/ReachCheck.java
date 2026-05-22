package NC.noChance.detection.combat;

import NC.noChance.core.*;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class ReachCheck {
    private final ACConfig config;
    private final Map<UUID, PlayerData> playerDataMap;
    private final LayerFiltering filtering;
    private final CombatTracker combatTracker;

    private static final Set<EntityType> LARGE_ENTITIES = Set.of(
        EntityType.WARDEN, EntityType.RAVAGER, EntityType.IRON_GOLEM,
        EntityType.ELDER_GUARDIAN, EntityType.GHAST, EntityType.ENDER_DRAGON,
        EntityType.WITHER
    );

    private static final Set<EntityType> MEDIUM_ENTITIES = Set.of(
        EntityType.HORSE, EntityType.DONKEY, EntityType.MULE,
        EntityType.CAMEL, EntityType.POLAR_BEAR, EntityType.HOGLIN,
        EntityType.ZOGLIN, EntityType.SPIDER
    );

    private static final Map<UUID, Long> PUNCH_HITS = new ConcurrentHashMap<>();
    private static final long PUNCH_GRACE_MS = 400L;

    private final Map<UUID, BoundingBox> lastTargetBox = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastTargetBoxTime = new ConcurrentHashMap<>();

    private final Map<UUID, ReachHits> reachHits = new ConcurrentHashMap<>();

    private static class ReachHits {
        int count;
        long lastTime;
        double maxOver;
    }

    private KillAuraCheck killAuraCheck;
    private EntityPositionTracker positionTracker;

    public ReachCheck(ACConfig config, Map<UUID, PlayerData> playerDataMap, LayerFiltering filtering, CombatTracker combatTracker) {
        this.config = config;
        this.playerDataMap = playerDataMap;
        this.filtering = filtering;
        this.combatTracker = combatTracker;
    }

    public void setKillAuraCheck(KillAuraCheck killAuraCheck) {
        this.killAuraCheck = killAuraCheck;
    }

    public void setPositionTracker(EntityPositionTracker positionTracker) {
        this.positionTracker = positionTracker;
    }

    public CheckResult checkEntityReach(Player player, Entity target) {
        if (!config.isCheckEnabled("reach")) {
            return CheckResult.passed();
        }

        if (target == null || !target.isValid()) {
            return CheckResult.passed();
        }

        if (player.isRiptiding()) {
            return CheckResult.passed();
        }

        if (killAuraCheck != null && killAuraCheck.isInSweepWindow(player.getUniqueId())) {
            return CheckResult.passed();
        }

        PlayerData data = playerDataMap.get(player.getUniqueId());
        if (data == null) return CheckResult.passed();

        if (data.isInGracePeriod(config.getGracePeriod())) {
            return CheckResult.passed();
        }

        if (player.getVehicle() != null) {
            return CheckResult.passed();
        }

        long now = System.currentTimeMillis();
        if (data.getLastVelocityTime() > 0 && (now - data.getLastVelocityTime()) < 1000) {
            return CheckResult.passed();
        }

        if (data.getLastDamageTime() > 0 && (now - data.getLastDamageTime()) < 700) {
            return CheckResult.passed();
        }

        combatTracker.recordPlayerHit(player, target, 1.0);

        int ping = filtering.getPing(player);
        Location eyeLoc = player.getEyeLocation();
        Vector eyePos = eyeLoc.toVector();

        BoundingBox historicalBox = null;
        if (positionTracker != null) {
            historicalBox = positionTracker.getClosestBoxTo(target, eyePos, ping);
        }
        PrecisionReach.ReachResult result = PrecisionReach.checkEntityReach(player, target, historicalBox, ping);
        Vector lookDir = eyeLoc.getDirection().normalize();

        UUID targetId = target.getUniqueId();
        BoundingBox currentBox = target.getBoundingBox();
        BoundingBox prevBox = lastTargetBox.get(targetId);
        if (prevBox == null) prevBox = currentBox;

        double lerpFactor = CombatGeometry.lerpFactorFromPing(ping);
        BoundingBox lerpedBox = CombatGeometry.lerpBox(prevBox, currentBox, lerpFactor);

        Vector closestOnLerped = CombatGeometry.closestPointOnBox(eyePos, lerpedBox);
        double centerDistance = closestOnLerped.distance(eyePos);
        double coneDistance = CombatGeometry.distanceRayToBox(eyePos, lookDir, lerpedBox);

        lastTargetBox.put(targetId, currentBox);
        lastTargetBoxTime.put(targetId, now);

        if (result.valid && centerDistance <= result.maxReach + 0.005) {
            return CheckResult.passed();
        }

        double distance = Math.min(result.distance, centerDistance);
        double maxReach = VersionAdapter.adjustReachThreshold(player, result.maxReach);
        double baseReach = maxReach;

        maxReach += getEntitySizeBonus(target);

        EnvironmentHelper.EnvironmentalFactors envFactors = EnvironmentHelper.calculate(player);
        maxReach += Math.min(0.25, envFactors.reachBonus);

        double motionBonus = 0.0;
        if (target instanceof org.bukkit.entity.LivingEntity) {
            double targetSpeed = ((org.bukkit.entity.LivingEntity) target).getVelocity().length();
            if (targetSpeed > 0.1) {
                motionBonus = Math.min(0.35, targetSpeed * 0.5);
            }
        }
        Vector pVel = player.getVelocity();
        double playerHSpeed = Math.sqrt(pVel.getX() * pVel.getX() + pVel.getZ() * pVel.getZ());
        if (playerHSpeed > 0.1) {
            motionBonus = Math.max(motionBonus, Math.min(0.35, playerHSpeed * 0.5));
        }
        maxReach += motionBonus;

        if (ping > 250) {
            maxReach += 0.8;
        } else if (ping > 150) {
            maxReach += 0.5;
        }

        Long punchAt = PUNCH_HITS.get(target.getUniqueId());
        if (punchAt != null) {
            if (now - punchAt <= PUNCH_GRACE_MS) {
                maxReach += 0.5;
            } else {
                PUNCH_HITS.remove(target.getUniqueId());
            }
        }

        double maxBonus = (ping > 200 ? 0.85 : (ping > 100 ? 0.65 : 0.45));
        maxReach = Math.min(maxReach, baseReach + maxBonus);

        double hDist = PrecisionReach.getHorizontalDistance(eyeLoc, target.getLocation());
        double vDist = PrecisionReach.getVerticalDistance(eyeLoc, target.getLocation());

        if (distance > maxReach) {
            double over = distance - maxReach;
            UUID pid = player.getUniqueId();
            ReachHits hits = reachHits.computeIfAbsent(pid, k -> new ReachHits());
            if (now - hits.lastTime > 4000) {
                hits.count = 1;
                hits.maxOver = over;
            } else {
                hits.count++;
                if (over > hits.maxOver) hits.maxOver = over;
            }
            hits.lastTime = now;

            boolean egregious = over >= 0.6;
            boolean clearOver = over >= 0.30;
            if (!egregious && (!clearOver || hits.count < 2)) {
                return CheckResult.passed();
            }

            String reachReason = result.reason;
            String closestStr = String.format(" closest:(%.2f,%.2f,%.2f)",
                    closestOnLerped.getX(), closestOnLerped.getY(), closestOnLerped.getZ());

            double severity = Math.min(1.0, (egregious ? 0.55 : 0.40) + over * 0.20 + Math.max(0, hits.count - 2) * 0.05);

            CheckResult prelimResult = CheckResult.failed(
                    ViolationType.REACH,
                    severity,
                    String.format("Entity reach: %.2f (max: %.1f, over:%.2f, x%d, lerp: %.2f, ray: %.2f) H:%.2f V:%.2f | %s%s",
                            distance, maxReach, over, hits.count, lerpFactor, coneDistance, hDist, vDist, reachReason, closestStr)
            );

            if (!filtering.passesLayer2HeuristicFiltering(player, ViolationType.REACH, prelimResult)) {
                return CheckResult.passed();
            }

            if (egregious || hits.count >= 3) {
                hits.count = 0;
            }
            return prelimResult;
        } else {
            ReachHits hits = reachHits.get(player.getUniqueId());
            if (hits != null && now - hits.lastTime > 6000) {
                hits.count = Math.max(0, hits.count - 1);
            }
        }

        return CheckResult.passed();
    }

    public CheckResult checkBlockReach(Player player, Block block) {
        if (!config.isCheckEnabled("reach")) {
            return CheckResult.passed();
        }

        PlayerData data = playerDataMap.get(player.getUniqueId());
        if (data == null) return CheckResult.passed();

        if (data.isInGracePeriod(config.getGracePeriod())) {
            return CheckResult.passed();
        }

        int ping = filtering.getPing(player);
        PrecisionReach.ReachResult result = PrecisionReach.checkBlockReach(player, block.getLocation(), ping);

        if (player.getGameMode() == GameMode.CREATIVE) {
            if (result.distance <= 6.0) {
                return CheckResult.passed();
            }
        }

        if (result.valid) {
            return CheckResult.passed();
        }

        double distance = result.distance;
        double configBlockReach = config.getReachMaxBlockReach();
        double maxReach = VersionAdapter.adjustReachThreshold(player, Math.max(result.maxReach, configBlockReach));

        if (player.getGameMode() == GameMode.CREATIVE) {
            maxReach = 6.0;
        }

        EnvironmentHelper.EnvironmentalFactors blockEnv = EnvironmentHelper.calculate(player);
        maxReach += Math.min(0.25, blockEnv.reachBonus);

        org.bukkit.util.Vector pVelB = player.getVelocity();
        double playerHSpeedB = Math.sqrt(pVelB.getX() * pVelB.getX() + pVelB.getZ() * pVelB.getZ());
        if (playerHSpeedB > 0.1) {
            maxReach += Math.min(0.3, playerHSpeedB * 0.4);
        }

        double confidence = result.confidence;

        if (distance > maxReach) {
            CheckResult prelimResult = CheckResult.failed(
                    ViolationType.REACH,
                    Math.min(1.0, (distance - maxReach) / Math.max(0.01, maxReach)),
                    String.format("Block reach: %.2f blocks (max: %.1f) | Confidence: %.2f",
                            distance, maxReach, confidence)
            );

            if (!filtering.passesLayer2HeuristicFiltering(player, ViolationType.REACH, prelimResult)) {
                return CheckResult.passed();
            }

            return prelimResult;
        }

        return CheckResult.passed();
    }

    private double getEntitySizeBonus(Entity entity) {
        EntityType type = entity.getType();
        if (LARGE_ENTITIES.contains(type)) return 0.5;
        if (MEDIUM_ENTITIES.contains(type)) return 0.2;
        return 0.0;
    }

    public void cleanup(UUID playerId) {
        combatTracker.cleanup(playerId);
        PUNCH_HITS.remove(playerId);
        lastTargetBox.remove(playerId);
        lastTargetBoxTime.remove(playerId);
        reachHits.remove(playerId);
        long now = System.currentTimeMillis();
        PUNCH_HITS.values().removeIf(ts -> now - ts > 5000L);
        lastTargetBoxTime.entrySet().removeIf(e -> {
            if (now - e.getValue() > 10000L) {
                lastTargetBox.remove(e.getKey());
                return true;
            }
            return false;
        });
    }
}
