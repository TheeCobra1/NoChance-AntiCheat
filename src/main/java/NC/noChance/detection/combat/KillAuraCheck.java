package NC.noChance.detection.combat;

import NC.noChance.core.*;
import NC.noChance.core.PrecisionReach;
import NC.noChance.core.ViaHelper;
import NC.noChance.core.VersionAdapter;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.*;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

public class KillAuraCheck {
    private final ACConfig config;
    private final Map<UUID, PlayerData> playerDataMap;
    private final LayerFiltering filtering;
    private final Map<UUID, ConcurrentLinkedDeque<HitRecord>> recentHits;
    private final CombatTracker combatTracker;
    private final FalsePositiveFilter falsePositiveFilter;
    private final Map<UUID, KillAuraTracker> trackers;
    private final Map<UUID, Long> sweepMarks;
    private static final long SWEEP_EXEMPTION_WINDOW_MS = 100L;

    public KillAuraCheck(ACConfig config, Map<UUID, PlayerData> playerDataMap, LayerFiltering filtering, CombatTracker combatTracker) {
        this.config = config;
        this.playerDataMap = playerDataMap;
        this.filtering = filtering;
        this.recentHits = new ConcurrentHashMap<>();
        this.combatTracker = combatTracker;
        this.falsePositiveFilter = new FalsePositiveFilter();
        this.trackers = new ConcurrentHashMap<>();
        this.sweepMarks = new ConcurrentHashMap<>();
    }

    public void markSweepAttack(Player player) {
        sweepMarks.put(player.getUniqueId(), System.currentTimeMillis());
    }

    public boolean isInSweepWindow(UUID playerId) {
        Long ts = sweepMarks.get(playerId);
        if (ts == null) return false;
        if (System.currentTimeMillis() - ts > SWEEP_EXEMPTION_WINDOW_MS) {
            sweepMarks.remove(playerId, ts);
            return false;
        }
        return true;
    }

    public CheckResult check(Player player, Entity target) {
        if (target.isDead() || !target.isValid()) return CheckResult.passed();

        if (!config.isCheckEnabled("killaura")) {
            return CheckResult.passed();
        }

        if (player.isRiptiding()) {
            return CheckResult.passed();
        }

        PlayerData data = playerDataMap.get(player.getUniqueId());
        if (data == null) return CheckResult.passed();

        if (data.isInGracePeriod(config.getGracePeriod())) {
            return CheckResult.passed();
        }

        UUID uuid = player.getUniqueId();
        KillAuraTracker tracker = trackers.computeIfAbsent(uuid, k -> new KillAuraTracker());

        combatTracker.recordPlayerHit(player, target, 1.0);

        if (combatTracker.isInCombat(uuid)) {
            long minHitDelay = VersionAdapter.getHitDelay(player);
            CombatTracker.CombatContext ctx = combatTracker.getContext(uuid);
            if (ctx.getTimeSinceLastHit() < minHitDelay * 0.15) {
                tracker.recordCPSViolation();
            }
        }

        if (ViaHelper.isPre1_19(player)) {
            tracker.setLegacyCombatMode(true);
        }

        CombatTracker.CombatContext context = combatTracker.getContext(uuid);
        double recentCPS = context.getRecentCPS();

        Location currentLoc = player.getLocation();
        Location lastLoc = data.getLastLocation();
        double thisTickRotationDelta = 0.0;
        if (lastLoc != null) {
            float yawDiff = getYawDifference(currentLoc.getYaw(), lastLoc.getYaw());
            float pitchDiff = getPitchDifference(currentLoc.getPitch(), lastLoc.getPitch());
            double rotationSpeed = Math.sqrt(yawDiff * yawDiff + pitchDiff * pitchDiff);
            thisTickRotationDelta = rotationSpeed;
            falsePositiveFilter.recordRotation(player, rotationSpeed);
        }

        long attackNow = System.currentTimeMillis();
        long timeSinceLastRotation = tracker.getLastRotationTime() == 0 ? Long.MAX_VALUE
                : attackNow - tracker.getLastRotationTime();
        tracker.recordRotationDelta(thisTickRotationDelta, attackNow);
        tracker.recordAttackTime(attackNow);

        if (thisTickRotationDelta > 35.0 && timeSinceLastRotation < 60 && !player.isInsideVehicle()) {
            tracker.recordSnapAttackHit();
            tracker.recordSignal("ROTATION_SNAP");
            if (tracker.getSnapAttackHits() >= 3 && tracker.hasNonPatternSignalSince(8000)) {
                CheckResult snapResult = CheckResult.failed(
                        ViolationType.KILLAURA,
                        Math.min(0.94, 0.78 + tracker.getSnapAttackHits() * 0.03),
                        String.format("Rotation-snap-attack: rot=%.1f° in %dms (x%d)",
                                thisTickRotationDelta, timeSinceLastRotation, tracker.getSnapAttackHits())
                );
                if (filtering.passesLayer2HeuristicFiltering(player, ViolationType.KILLAURA, snapResult)) {
                    tracker.resetSnapAttackHits();
                    return snapResult;
                }
            }
        }

        double trustScore = falsePositiveFilter.getPlayerTrustScore(uuid);
        double maxCPS = VersionAdapter.getMaxCPS(player);

        double mobMultiplier = getMobCPSMultiplier(target);
        maxCPS *= mobMultiplier;

        if (trustScore > 0.75) {
            maxCPS += 1.5;
        } else if (trustScore < 0.4) {
            maxCPS -= 2.0;
        }

        int ping = filtering.getPing(player);
        if (ping > 200) {
            maxCPS += 2.0;
        } else if (ping > 150) {
            maxCPS += 1.0;
        }

        double slowdownGrace = getSlowdownCpsMultiplier(player);
        maxCPS *= slowdownGrace;

        double instantFlagCPS = config.getKillAuraInstantFlagCPSOver();
        double impossibleCPS = (VersionAdapter.isLegacyCombat(player) ? 26.0 : 28.0) * slowdownGrace;
        if (recentCPS > maxCPS + instantFlagCPS) {
            tracker.recordInstantCpsHit();
            if (tracker.getInstantCpsHits() >= 2) {
                CheckResult instantResult = CheckResult.failed(
                        ViolationType.KILLAURA,
                        0.96,
                        String.format("Instant CPS flag: %.1f (threshold: %.1f+%.1f)", recentCPS, maxCPS, instantFlagCPS)
                );
                if (filtering.passesLayer2HeuristicFiltering(player, ViolationType.KILLAURA, instantResult)) {
                    tracker.resetInstantCps();
                    return instantResult;
                }
            }
        } else {
            tracker.decayInstantCps();
        }
        if (recentCPS > impossibleCPS) {
            tracker.recordImpossibleCpsHit();
            if (tracker.getImpossibleCpsHits() >= 2) {
                CheckResult prelimResult = CheckResult.failed(
                        ViolationType.KILLAURA,
                        0.95,
                        String.format("Impossible CPS: %.1f (max: %.0f)", recentCPS, impossibleCPS)
                );
                if (filtering.passesLayer2HeuristicFiltering(player, ViolationType.KILLAURA, prelimResult)) {
                    tracker.resetImpossibleCps();
                    return prelimResult;
                }
            }
        } else {
            tracker.decayImpossibleCps();
        }

        if (recentCPS > maxCPS) {
            tracker.recordCPSViolation();

            int requiredViolations = 4;
            if (recentCPS > maxCPS + 2.0) {
                double severity = Math.min(0.98, 0.80 + (recentCPS - maxCPS) / 10.0);
                CheckResult prelimResult = CheckResult.failed(
                        ViolationType.KILLAURA,
                        severity,
                        String.format("High CPS: %.1f (max: %.1f)", recentCPS, maxCPS)
                );
                if (filtering.passesLayer2HeuristicFiltering(player, ViolationType.KILLAURA, prelimResult)) {
                    return prelimResult;
                }
            }

            if (tracker.getCPSViolations() < requiredViolations) {
                return CheckResult.passed();
            }

            double severity = Math.min(0.96, 0.72 + (recentCPS - maxCPS) / 15.0);
            CheckResult prelimResult = CheckResult.failed(
                    ViolationType.KILLAURA,
                    severity,
                    String.format("High CPS: %.1f (max: %.1f)", recentCPS, maxCPS)
            );
            if (!filtering.passesLayer2HeuristicFiltering(player, ViolationType.KILLAURA, prelimResult)) {
                tracker.decayCPS();
                return CheckResult.passed();
            }
            return prelimResult;
        } else {
            tracker.decayCPS();
        }

        CheckResult timingCheck = checkAttackTiming(player, target);
        if (timingCheck.isFailed()) return timingCheck;

        CheckResult angleCheck = checkHitAngle(player, target, data);
        if (angleCheck.isFailed()) return angleCheck;

        CheckResult distanceCheck = checkHitDistance(player, target);
        if (distanceCheck.isFailed()) return distanceCheck;

        CheckResult rotationCheck = checkRotationSpeed(player, target, data);
        if (rotationCheck.isFailed()) return rotationCheck;

        CheckResult multiCheck = checkMultiAura(player, target);
        if (multiCheck.isFailed()) return multiCheck;

        CheckResult patternCheck = checkPatternDetection(player, target);
        if (patternCheck.isFailed()) return patternCheck;

        CheckResult sprintCheck = checkSprintHit(player, target);
        if (sprintCheck.isFailed()) return sprintCheck;

        CheckResult snapCheck = checkAimSnap(player, target, data);
        if (snapCheck.isFailed()) return snapCheck;

        CheckResult cooldownCheck = checkAttackCooldown(player, target);
        if (cooldownCheck.isFailed()) return cooldownCheck;

        CheckResult aimLockCheck = checkAimLock(player, target, data);
        if (aimLockCheck.isFailed()) return aimLockCheck;

        CheckResult gazeCheck = checkGazeCone(player, target, data);
        if (gazeCheck.isFailed()) return gazeCheck;

        CheckResult reactionCheck = checkSuprahumanReaction(player, target);
        if (reactionCheck.isFailed()) return reactionCheck;

        int distinctSignals = tracker.distinctSignalsSince(5000);
        if (distinctSignals >= 4 && tracker.hasNonPatternSignalSince(5000)) {
            double sev = Math.min(0.94, 0.68 + (distinctSignals - 3) * 0.06);
            CheckResult correlationResult = CheckResult.failed(
                    ViolationType.KILLAURA,
                    sev,
                    String.format("Cross-metric correlation: %d distinct signals in 5s", distinctSignals)
            );
            if (filtering.passesLayer2HeuristicFiltering(player, ViolationType.KILLAURA, correlationResult)) {
                return correlationResult;
            }
        }

        return CheckResult.passed();
    }

    private boolean isMob(Entity entity) {
        return entity instanceof Creature || entity instanceof Monster || entity instanceof Animals ||
               entity instanceof WaterMob || entity instanceof Ambient || entity instanceof Flying;
    }

    private boolean isFarmingScenario(Entity target) {
        if (!isMob(target)) return false;

        if (target instanceof Animals || target instanceof WaterMob || target instanceof Ambient) {
            return true;
        }

        if (target instanceof LivingEntity) {
            LivingEntity living = (LivingEntity) target;
            if (living.getVelocity().length() < 0.15) {
                return true;
            }
        }

        return false;
    }

    private double getMobAngleMultiplier(Entity entity) {
        if (entity instanceof Player) return 1.0;

        if (entity instanceof EnderDragon || entity instanceof Wither) return 2.5;
        if (entity instanceof Enderman) return 2.0;
        if (entity instanceof Phantom || entity instanceof Ghast || entity instanceof Vex) return 2.2;
        if (entity instanceof Spider || entity instanceof CaveSpider) return 1.8;
        if (entity instanceof Creeper || entity instanceof Skeleton || entity instanceof Zombie) return 1.6;
        if (entity instanceof Slime || entity instanceof MagmaCube) return 1.9;
        if (entity instanceof Silverfish || entity instanceof Bee) return 2.1;
        if (entity instanceof Animals || entity instanceof WaterMob) return 1.7;

        return 1.5;
    }

    private double getMobCPSMultiplier(Entity entity) {
        if (entity instanceof Player) return 1.0;

        if (isFarmingScenario(entity)) return 1.4;

        return 1.25;
    }

    private double getSlowdownCpsMultiplier(Player player) {
        double mult = 1.0;
        PotionEffectType slownessType = PotionEffectType.getByName("SLOWNESS");
        if (slownessType == null) slownessType = PotionEffectType.getByName("SLOW");
        if (slownessType != null && player.hasPotionEffect(slownessType)) {
            mult *= 1.30;
        }
        PotionEffectType fatigueType = PotionEffectType.getByName("MINING_FATIGUE");
        if (fatigueType == null) fatigueType = PotionEffectType.getByName("SLOW_DIGGING");
        if (fatigueType != null && player.hasPotionEffect(fatigueType)) {
            PotionEffect effect = player.getPotionEffect(fatigueType);
            int amp = effect != null ? effect.getAmplifier() : 0;
            mult *= 1.0 + 0.30 * (amp + 1);
        }
        return mult;
    }

    private Location getEntityHitboxCenter(Entity entity) {
        Location loc = entity.getLocation().clone();

        if (entity instanceof Player) {
            return loc.add(0, entity.getHeight() * 0.5, 0);
        }

        if (entity instanceof EnderDragon) {
            return loc.add(0, 2.0, 0);
        }
        if (entity instanceof Enderman || entity instanceof Wither) {
            return loc.add(0, entity.getHeight() * 0.6, 0);
        }
        if (entity instanceof Slime || entity instanceof MagmaCube) {
            Slime slime = (Slime) entity;
            return loc.add(0, slime.getSize() * 0.25, 0);
        }
        if (entity instanceof Phantom || entity instanceof Ghast) {
            return loc.add(0, entity.getHeight() * 0.4, 0);
        }
        if (entity instanceof Spider || entity instanceof CaveSpider) {
            return loc.add(0, 0.4, 0);
        }
        if (entity instanceof Silverfish || entity instanceof Bee) {
            return loc.add(0, 0.15, 0);
        }
        if (entity instanceof Chicken || entity instanceof Rabbit) {
            return loc.add(0, 0.3, 0);
        }
        if (entity instanceof Pig || entity instanceof Sheep || entity instanceof Wolf) {
            return loc.add(0, 0.45, 0);
        }
        if (entity instanceof Cow || entity instanceof MushroomCow) {
            return loc.add(0, 0.7, 0);
        }
        if (entity instanceof Horse || entity instanceof Llama) {
            return loc.add(0, 0.8, 0);
        }

        return loc.add(0, entity.getHeight() * 0.5, 0);
    }

    private boolean isTeleportingMob(Entity entity) {
        return entity instanceof Enderman || entity instanceof Shulker || entity instanceof Vex;
    }

    private boolean isFlyingMob(Entity entity) {
        return entity instanceof Phantom || entity instanceof Ghast || entity instanceof Vex ||
               entity instanceof Bee || entity instanceof Parrot || entity instanceof Bat;
    }

    private boolean isErraticMob(Entity entity) {
        return entity instanceof Spider || entity instanceof CaveSpider || entity instanceof Silverfish ||
               entity instanceof Enderman || entity instanceof Creeper;
    }

    private float normalizeYaw(float yaw) {
        while (yaw > 180.0f) yaw -= 360.0f;
        while (yaw < -180.0f) yaw += 360.0f;
        return yaw;
    }

    private float getYawDifference(float yaw1, float yaw2) {
        float diff = normalizeYaw(yaw1 - yaw2);
        return Math.abs(diff);
    }

    private float getPitchDifference(float pitch1, float pitch2) {
        float p1 = Math.max(-90.0f, Math.min(90.0f, pitch1));
        float p2 = Math.max(-90.0f, Math.min(90.0f, pitch2));
        return Math.abs(p1 - p2);
    }

    private CheckResult checkAttackTiming(Player player, Entity target) {
        UUID playerId = player.getUniqueId();
        ConcurrentLinkedDeque<HitRecord> hits = recentHits.computeIfAbsent(playerId, k -> new ConcurrentLinkedDeque<>());
        if (hits.size() < 5) {
            return CheckResult.passed();
        }

        List<Long> intervals = new ArrayList<>();
        HitRecord prev = null;
        for (HitRecord hit : hits) {
            if (prev != null) {
                long interval = hit.timestamp - prev.timestamp;
                if (interval > 0 && interval < 500) {
                    intervals.add(interval);
                }
            }
            prev = hit;
        }

        if (intervals.size() < 4) {
            return CheckResult.passed();
        }

        double avg = intervals.stream().mapToLong(Long::longValue).average().orElse(100);
        double varianceSum = 0;
        for (Long interval : intervals) {
            varianceSum += Math.pow(interval - avg, 2);
        }
        double stdDev = Math.sqrt(varianceSum / intervals.size());

        int timingPing = filtering.getPing(player);
        double stdDevFloor = 3.0 + (timingPing * 0.015);
        if (stdDev < stdDevFloor && avg < 100 && intervals.size() >= 12) {
            double severity = Math.min(0.94, 0.80 + (stdDevFloor - stdDev) / 20.0);
            CheckResult prelimResult = CheckResult.failed(
                ViolationType.KILLAURA,
                severity,
                String.format("Perfect timing: %.1fms avg, %.1fms stddev", avg, stdDev)
            );

            if (filtering.passesLayer2HeuristicFiltering(player, ViolationType.KILLAURA, prelimResult)) {
                return prelimResult;
            }
        }

        return CheckResult.passed();
    }

    private CheckResult checkSprintHit(Player player, Entity target) {
        UUID uuid = player.getUniqueId();
        KillAuraTracker tracker = trackers.computeIfAbsent(uuid, k -> new KillAuraTracker());

        if (!player.isSprinting()) {
            return CheckResult.passed();
        }

        if (isMob(target)) {
            return CheckResult.passed();
        }

        Location playerLoc = player.getLocation();
        Location targetLoc = target.getLocation();

        Vector playerDir = playerLoc.getDirection().normalize();
        Vector toTarget = targetLoc.toVector().subtract(playerLoc.toVector()).normalize();

        double dotProduct = playerDir.dot(toTarget);

        double threshold = -0.5;

        if (dotProduct < threshold) {
            tracker.recordAngleViolation();

            int requiredViolations = 3;

            if (tracker.getAngleViolations() < requiredViolations) {
                return CheckResult.passed();
            }

            double severity = Math.min(0.92, 0.78 + Math.abs(dotProduct) * 0.3);

            CheckResult prelimResult = CheckResult.failed(
                ViolationType.KILLAURA,
                severity,
                String.format("Sprint-hit backwards (direction: %.2f, violations: %d)", dotProduct, tracker.getAngleViolations())
            );

            if (!filtering.passesLayer2HeuristicFiltering(player, ViolationType.KILLAURA, prelimResult)) {
                return CheckResult.passed();
            }

            return prelimResult;
        }

        return CheckResult.passed();
    }

    private CheckResult checkHitAngle(Player player, Entity target, PlayerData data) {
        UUID uuid = player.getUniqueId();
        KillAuraTracker tracker = trackers.computeIfAbsent(uuid, k -> new KillAuraTracker());

        Location eyeLoc = player.getEyeLocation();
        Vector playerView = eyeLoc.getDirection().normalize();

        Location targetLoc = getEntityHitboxCenter(target);
        Vector toTarget = targetLoc.toVector().subtract(eyeLoc.toVector()).normalize();

        double dotProduct = playerView.dot(toTarget);
        dotProduct = Math.max(-1.0, Math.min(1.0, dotProduct));

        double hitAngle = Math.toDegrees(Math.acos(dotProduct));

        Vector horizontalView = playerView.clone().setY(0).normalize();
        Vector horizontalTarget = toTarget.clone().setY(0).normalize();
        double horizontalDot = horizontalView.dot(horizontalTarget);
        horizontalDot = Math.max(-1.0, Math.min(1.0, horizontalDot));
        double yawAngle = Math.toDegrees(Math.acos(horizontalDot));

        double pitchDiff = Math.abs(Math.toDegrees(Math.asin(playerView.getY())) - Math.toDegrees(Math.asin(toTarget.getY())));

        double maxAngle = 25.0;
        PlayerData.SkillLevel skill = data.getSkillLevel();
        double trustScore = falsePositiveFilter.getPlayerTrustScore(uuid);

        double mobMultiplier = getMobAngleMultiplier(target);
        maxAngle *= mobMultiplier;

        if (skill == PlayerData.SkillLevel.HIGH) {
            maxAngle += 5;
        }

        if (trustScore > 0.75) {
            maxAngle += 4;
        } else if (trustScore < 0.4) {
            maxAngle -= 3;
        }

        int ping = filtering.getPing(player);
        if (ping > 300) {
            maxAngle += 12;
        } else if (ping > 200) {
            maxAngle += 7;
        } else if (ping > 150) {
            maxAngle += 3;
        }

        if (player.getVelocity().length() > 0.3) {
            maxAngle += 2;
        }

        if (target instanceof LivingEntity) {
            LivingEntity living = (LivingEntity) target;
            if (living.getVelocity().length() > 0.4) {
                maxAngle += 3;
            }
        }

        if (isTeleportingMob(target)) {
            maxAngle += 10;
        }

        if (isFlyingMob(target)) {
            maxAngle += 8;
        }

        if (isErraticMob(target)) {
            maxAngle += 5;
        }

        if (isFarmingScenario(target)) {
            maxAngle += 4;
        }

        double instantFlagAngle = config.getKillAuraInstantFlagAngleOver();
        double angleCap = isTeleportingMob(target) ? 65.0 : 50.0;
        maxAngle = Math.min(maxAngle, angleCap);

        if (hitAngle > maxAngle + instantFlagAngle) {
            CheckResult instantAngle = CheckResult.failed(
                    ViolationType.KILLAURA_ANGLE,
                    0.96,
                    String.format("Instant angle flag: %.1f° (max+threshold: %.1f+%.1f)", hitAngle, maxAngle, instantFlagAngle)
            );
            if (filtering.passesLayer2HeuristicFiltering(player, ViolationType.KILLAURA, instantAngle)) {
                return instantAngle;
            }
        }

        if (hitAngle > 90.0) {
            CheckResult prelimResult = CheckResult.failed(
                    ViolationType.KILLAURA_ANGLE,
                    0.98,
                    String.format("Impossible angle: %.1f°", hitAngle)
            );
            if (filtering.passesLayer2HeuristicFiltering(player, ViolationType.KILLAURA, prelimResult)) {
                return prelimResult;
            }
        }

        if (hitAngle > maxAngle) {
            boolean bothOff = yawAngle > maxAngle * 0.7 && pitchDiff > maxAngle * 0.6;
            boolean yawExtreme = yawAngle > maxAngle * 1.0;
            boolean pitchExtreme = pitchDiff > maxAngle * 1.0;
            if (bothOff || yawExtreme || pitchExtreme) {
                tracker.recordAngleViolation();

                int requiredViolations = 4;
                if (hitAngle > maxAngle + 10.0) {
                    double severity = Math.min(0.98, 0.82 + ((hitAngle - maxAngle) / 40.0));
                    CheckResult prelimResult = CheckResult.failed(
                            ViolationType.KILLAURA_ANGLE,
                            severity,
                            String.format("Hit angle: %.1f° (max: %.1f°)", hitAngle, maxAngle)
                    );
                    if (filtering.passesLayer2HeuristicFiltering(player, ViolationType.KILLAURA, prelimResult)) {
                        return prelimResult;
                    }
                }

                if (tracker.getAngleViolations() < requiredViolations) {
                    falsePositiveFilter.recordLegitAction(player, ViolationType.KILLAURA);
                    return CheckResult.passed();
                }

                double severity = Math.min(0.95, 0.70 + ((hitAngle - maxAngle) / 40.0) + (tracker.getAngleViolations() * 0.05));

                CheckResult prelimResult = CheckResult.failed(
                        ViolationType.KILLAURA_ANGLE,
                        severity,
                        String.format("Hit angle: %.1f° yaw:%.1f° pitch:%.1f° (max: %.1f°, vl: %d, trust: %.2f)",
                            hitAngle, yawAngle, pitchDiff, maxAngle, tracker.getAngleViolations(), trustScore)
                );

                if (!filtering.passesLayer2HeuristicFiltering(player, ViolationType.KILLAURA, prelimResult)) {
                    tracker.decayAngle();
                    falsePositiveFilter.recordLegitAction(player, ViolationType.KILLAURA);
                    return CheckResult.passed();
                }

                return prelimResult;
            } else {
                tracker.decayAngle();
            }
        } else {
            tracker.decayAngle();
        }

        falsePositiveFilter.recordLegitAction(player, ViolationType.KILLAURA);
        return CheckResult.passed();
    }

    private CheckResult checkHitDistance(Player player, Entity target) {
        int ping = filtering.getPing(player);

        PrecisionReach.ReachResult reachResult = PrecisionReach.checkEntityReach(player, target, ping);

        if (reachResult.valid) {
            return CheckResult.passed();
        }

        double distance = reachResult.distance;
        double maxReach = VersionAdapter.adjustReachThreshold(player, reachResult.maxReach);

        EnhancementTracker.CombatEnhancements enhancements = EnhancementTracker.calculateCombatEnhancements(player);
        maxReach += enhancements.reachBonus;

        if (target instanceof org.bukkit.entity.LivingEntity) {
            double targetSpeed = ((org.bukkit.entity.LivingEntity) target).getVelocity().length();
            if (targetSpeed > 0.1) {
                maxReach += Math.min(0.6, targetSpeed * 0.4);
            }
        }

        org.bukkit.util.Vector pVel = player.getVelocity();
        double playerHSpeed = Math.sqrt(pVel.getX() * pVel.getX() + pVel.getZ() * pVel.getZ());
        if (playerHSpeed > 0.1) {
            maxReach += Math.min(0.5, playerHSpeed * 0.6);
        }
        if (player.isSprinting()) {
            maxReach += 0.3;
        }

        if (ping > 100) {
            maxReach += 0.3;
        }
        if (ping > 200) {
            maxReach += 0.2;
        }

        UUID uuid = player.getUniqueId();
        KillAuraTracker tracker = trackers.computeIfAbsent(uuid, k -> new KillAuraTracker());

        if (distance > maxReach + 0.1) {
            tracker.recordReachViolation();

            double excess = distance - maxReach;
            double severity = Math.min(0.98, 0.70 + (excess * 0.15));

            if (excess > 0.5) {
                severity = Math.min(0.98, severity + 0.1);
            }

            if (tracker.getReachViolations() >= 3) {
                severity = Math.min(0.98, severity + tracker.getReachViolations() * 0.02);
            }

            CheckResult prelimResult = CheckResult.failed(
                    ViolationType.REACH,
                    severity,
                    String.format("Reach: %.3f blocks (max: %.2f, excess: %.3f) | %s",
                            distance, maxReach, excess, enhancements.reason)
            );

            if (!filtering.passesLayer2HeuristicFiltering(player, ViolationType.REACH, prelimResult)) {
                return CheckResult.passed();
            }

            return prelimResult;
        } else {
            tracker.decayReach();
        }

        return CheckResult.passed();
    }

    private CheckResult checkRotationSpeed(Player player, Entity target, PlayerData data) {
        UUID uuid = player.getUniqueId();
        KillAuraTracker tracker = trackers.computeIfAbsent(uuid, k -> new KillAuraTracker());

        if (player.isGliding() || player.isRiptiding() || player.isSwimming()) {
            return CheckResult.passed();
        }
        if (player.getGameMode() == org.bukkit.GameMode.SPECTATOR) {
            return CheckResult.passed();
        }

        long teleportAgeMs = System.currentTimeMillis() - data.getLastTeleportTime();
        if (data.getLastTeleportTime() > 0 && teleportAgeMs < (config.getTeleportGracePeriod() * 1000L)) {
            return CheckResult.passed();
        }

        Deque<PlayerData.RotationData> rotations = data.getRotationHistory();
        if (rotations.size() < 3) {
            return CheckResult.passed();
        }

        List<PlayerData.RotationData> rotList = new ArrayList<>(rotations);
        PlayerData.RotationData current = rotList.get(rotList.size() - 1);
        PlayerData.RotationData previous = rotList.get(rotList.size() - 2);

        float deltaYaw = getYawDifference(current.yaw, previous.yaw);
        float deltaPitch = getPitchDifference(current.pitch, previous.pitch);
        double deltaTime = (current.timestamp - previous.timestamp) / 1000.0;

        if (deltaTime == 0 || deltaTime > 1.0) {
            return CheckResult.passed();
        }

        double rotationSpeed = Math.sqrt(deltaYaw * deltaYaw + deltaPitch * deltaPitch) / deltaTime;

        double maxRotationSpeed = config.getKillAuraMaxRotationSpeed();
        double trustScore = falsePositiveFilter.getPlayerTrustScore(uuid);

        double mobAngleMultiplier = getMobAngleMultiplier(target);
        if (mobAngleMultiplier > 1.0) {
            maxRotationSpeed *= 1.0 + ((mobAngleMultiplier - 1.0) * 0.5);
        }

        if (trustScore > 0.75) {
            maxRotationSpeed += config.getKillAuraRotationTrustedBonus();
        } else if (trustScore < 0.4) {
            maxRotationSpeed -= config.getKillAuraRotationUntrustedPenalty();
        }

        int ping = filtering.getPing(player);
        if (ping > 200) {
            maxRotationSpeed += 120;
        } else if (ping > 100) {
            maxRotationSpeed += 60;
        }

        Vector rotVel = player.getVelocity();
        double rotHSpeed = Math.sqrt(rotVel.getX() * rotVel.getX() + rotVel.getZ() * rotVel.getZ());
        if (rotHSpeed > 0.15) {
            maxRotationSpeed += 200;
        }
        if (player.isSprinting()) {
            maxRotationSpeed += 150;
        }
        if (target instanceof LivingEntity) {
            double tSpeed = ((LivingEntity) target).getVelocity().length();
            if (tSpeed > 0.15) {
                maxRotationSpeed += 150;
            }
        }

        if (isTeleportingMob(target) || isFlyingMob(target)) {
            maxRotationSpeed += 150;
        }

        if (isErraticMob(target)) {
            maxRotationSpeed += 100;
        }

        if (rotList.size() >= 6) {
            double cpsForPattern = combatTracker.getContext(uuid).getRecentCPS();
            double maxCpsForPattern = VersionAdapter.getMaxCPS(player);
            double mobCpsGate = maxCpsForPattern + 4.0;
            boolean cpsTrigger = isMob(target) ? cpsForPattern > mobCpsGate : cpsForPattern > maxCpsForPattern;
            if (cpsTrigger) {
                CheckResult gcdCheck = checkRotationGCD(rotList, player);
                if (gcdCheck.isFailed()) return gcdCheck;

                CheckResult accelCheck = checkRotationAcceleration(rotList, player);
                if (accelCheck.isFailed()) return accelCheck;
            }
        }

        if (rotationSpeed > maxRotationSpeed) {
            List<Double> recentSpeeds = new ArrayList<>();
            for (int i = Math.max(0, rotList.size() - 5); i < rotList.size() - 1; i++) {
                PlayerData.RotationData curr = rotList.get(i + 1);
                PlayerData.RotationData prev = rotList.get(i);
                double dt = (curr.timestamp - prev.timestamp) / 1000.0;
                if (dt > 0 && dt < 1.0) {
                    float dy = getYawDifference(curr.yaw, prev.yaw);
                    float dp = getPitchDifference(curr.pitch, prev.pitch);
                    recentSpeeds.add(Math.sqrt(dy * dy + dp * dp) / dt);
                }
            }

            if (recentSpeeds.size() >= 3) {
                double avg = recentSpeeds.stream().mapToDouble(Double::doubleValue).average().orElse(0);
                double variance = recentSpeeds.stream()
                    .mapToDouble(s -> Math.pow(s - avg, 2))
                    .average()
                    .orElse(0);

                int rotPing = filtering.getPing(player);
                double varianceFloor = config.getKillAuraRotationVarianceThreshold() * Math.min(1.6, 1.0 + (rotPing * 0.0015));
                double cpsForVar = combatTracker.getContext(uuid).getRecentCPS();
                double maxCpsForVar = VersionAdapter.getMaxCPS(player);
                boolean cpsAnomalous = cpsForVar > maxCpsForVar;
                boolean targetIsPlayer = !isMob(target);
                if (variance < varianceFloor && avg > maxRotationSpeed * 0.70 && cpsAnomalous && targetIsPlayer) {
                    tracker.recordRotationViolation();

                    int requiredViolations = variance < config.getKillAuraRotationVarianceStrict() ? 4 : config.getKillAuraRequiredViolations();

                    if (tracker.getRotationViolations() < requiredViolations) {
                        return CheckResult.passed();
                    }

                    CheckResult prelimResult = CheckResult.failed(
                        ViolationType.KILLAURA_ROTATION,
                        0.74,
                        String.format("Consistent rotation: %.1f°/s (var: %.1f, violations: %d)", avg, variance, tracker.getRotationViolations())
                    );
                    if (!filtering.passesLayer2HeuristicFiltering(player, ViolationType.KILLAURA, prelimResult)) {
                        tracker.decayRotation();
                        return CheckResult.passed();
                    }
                    return prelimResult;
                }

            }

            double cpsForGate = combatTracker.getContext(uuid).getRecentCPS();
            double maxCpsForGate = VersionAdapter.getMaxCPS(player);
            if (rotationSpeed < maxRotationSpeed * 3.0 && cpsForGate <= maxCpsForGate) {
                return CheckResult.passed();
            }

            tracker.recordRotationViolation();

            int requiredViolations = config.getKillAuraRequiredViolations();
            if (rotationSpeed > maxRotationSpeed + config.getKillAuraInstantFlagRotationOver()) {
                requiredViolations = Math.max(1, requiredViolations - 1);
            }

            if (tracker.getRotationViolations() < requiredViolations) {
                falsePositiveFilter.recordLegitAction(player, ViolationType.KILLAURA);
                return CheckResult.passed();
            }

            double severity = Math.min(0.80, 0.60 + (rotationSpeed - maxRotationSpeed) / 1200.0 + (tracker.getRotationViolations() * 0.02));

            CheckResult prelimResult = CheckResult.failed(
                    ViolationType.KILLAURA_ROTATION,
                    severity,
                    String.format("Rotation speed: %.1f°/s (max: %.1f°/s, violations: %d, trust: %.2f)",
                        rotationSpeed, maxRotationSpeed, tracker.getRotationViolations(), trustScore)
            );

            if (!filtering.passesLayer2HeuristicFiltering(player, ViolationType.KILLAURA, prelimResult)) {
                tracker.decayRotation();
                falsePositiveFilter.recordLegitAction(player, ViolationType.KILLAURA);
                return CheckResult.passed();
            }

            return prelimResult;
        } else {
            tracker.decayRotation();
        }

        falsePositiveFilter.recordLegitAction(player, ViolationType.KILLAURA);
        return CheckResult.passed();
    }

    private CheckResult checkRotationGCD(List<PlayerData.RotationData> rotations, Player player) {
        List<Double> yawChanges = new ArrayList<>();
        List<Double> pitchChanges = new ArrayList<>();

        for (int i = 1; i < rotations.size(); i++) {
            double yawDelta = getYawDifference(rotations.get(i).yaw, rotations.get(i - 1).yaw);
            double pitchDelta = getPitchDifference(rotations.get(i).pitch, rotations.get(i - 1).pitch);

            if (yawDelta > 0.01) yawChanges.add(yawDelta);
            if (pitchDelta > 0.01) pitchChanges.add(pitchDelta);
        }

        if (yawChanges.size() < 10 || pitchChanges.size() < 10) {
            return CheckResult.passed();
        }

        double yawMeanRaw = yawChanges.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double pitchMeanRaw = pitchChanges.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        if (yawMeanRaw < 2.5 && pitchMeanRaw < 2.5) {
            return CheckResult.passed();
        }

        double yawGCD = calculateGCDIterative(yawChanges);
        double pitchGCD = calculateGCDIterative(pitchChanges);

        double estimatedSensitivity = reconstructSensitivity(yawGCD);
        boolean validSensitivity = isValidMinecraftSensitivity(estimatedSensitivity);

        if (!validSensitivity && yawGCD > 0.03 && yawGCD < 0.8) {
            long divisibleYaw = yawChanges.stream().filter(v -> Math.abs(v % yawGCD) < 0.008).count();
            long divisiblePitch = pitchChanges.stream().filter(v -> Math.abs(v % pitchGCD) < 0.008).count();

            double yawRatio = (double) divisibleYaw / yawChanges.size();
            double pitchRatio = (double) divisiblePitch / pitchChanges.size();

            if (yawRatio > 0.92 && pitchRatio > 0.92 && yawChanges.size() >= 40) {
                UUID uuid = player.getUniqueId();
                KillAuraTracker tracker = trackers.computeIfAbsent(uuid, k -> new KillAuraTracker());
                tracker.recordRotationViolation();
                if (tracker.getRotationViolations() < 4) {
                    return CheckResult.passed();
                }
                if (!tracker.hasNonPatternSignalSince(8000)) {
                    return CheckResult.passed();
                }
                CheckResult prelimResult = CheckResult.failed(
                    ViolationType.KILLAURA_ROTATION,
                    0.72,
                    String.format("Invalid sensitivity grid (yaw: %.4f, pitch: %.4f, sens: %.3f, ratio: %.2f/%.2f)",
                        yawGCD, pitchGCD, estimatedSensitivity, yawRatio, pitchRatio)
                );

                if (!filtering.passesLayer2HeuristicFiltering(player, ViolationType.KILLAURA, prelimResult)) {
                    return CheckResult.passed();
                }

                return prelimResult;
            }
        }

        CheckResult smoothAimCheck = checkSmoothAim(yawChanges, pitchChanges, player);
        if (smoothAimCheck.isFailed()) return smoothAimCheck;

        if (yawGCD > 0.04 && yawGCD < 0.6 && pitchGCD > 0.04 && pitchGCD < 0.6) {
            long divisibleYaw = yawChanges.stream().filter(v -> Math.abs(v % yawGCD) < 0.01).count();
            long divisiblePitch = pitchChanges.stream().filter(v -> Math.abs(v % pitchGCD) < 0.01).count();

            double yawRatio = (double) divisibleYaw / yawChanges.size();
            double pitchRatio = (double) divisiblePitch / pitchChanges.size();

            if (yawRatio > 0.96 && pitchRatio > 0.96 && yawChanges.size() >= 40) {
                UUID uuid = player.getUniqueId();
                KillAuraTracker tracker = trackers.computeIfAbsent(uuid, k -> new KillAuraTracker());
                tracker.recordRotationViolation();
                if (tracker.getRotationViolations() < 4) {
                    return CheckResult.passed();
                }
                if (!tracker.hasNonPatternSignalSince(8000)) {
                    return CheckResult.passed();
                }
                CheckResult prelimResult = CheckResult.failed(
                    ViolationType.KILLAURA_ROTATION,
                    0.70,
                    String.format("Aim assist GCD (yaw: %.4f, pitch: %.4f, ratio: %.2f/%.2f, samples: %d)",
                        yawGCD, pitchGCD, yawRatio, pitchRatio, yawChanges.size())
                );

                if (!filtering.passesLayer2HeuristicFiltering(player, ViolationType.KILLAURA, prelimResult)) {
                    return CheckResult.passed();
                }

                return prelimResult;
            }
        }

        return CheckResult.passed();
    }

    private double reconstructSensitivity(double gcd) {
        double f = gcd / 0.15;
        double f2 = f / 8.0;
        double sensitivity = (Math.cbrt(f2) - 0.2) / 0.6;
        return Math.max(0, Math.min(1, sensitivity));
    }

    private boolean isValidMinecraftSensitivity(double sensitivity) {
        if (sensitivity < 0.0 || sensitivity > 1.0) return false;

        double step = sensitivity * 200;
        double stepRemainder = Math.abs(step - Math.round(step));
        if (stepRemainder < 0.3) return true;

        double[] commonSensitivities = {0.5, 1.0, 0.0, 0.25, 0.75, 0.3, 0.4, 0.6, 0.7, 0.8, 0.2, 0.9, 0.15, 0.35, 0.45, 0.55, 0.65, 0.85};
        for (double common : commonSensitivities) {
            if (Math.abs(sensitivity - common) < 0.015) return true;
        }

        return false;
    }

    private CheckResult checkSmoothAim(List<Double> yawChanges, List<Double> pitchChanges, Player player) {
        if (yawChanges.size() < 12) return CheckResult.passed();

        double yawVariance = calculateRotationVariance(yawChanges);
        double pitchVariance = calculateRotationVariance(pitchChanges);
        double yawMean = yawChanges.stream().mapToDouble(Double::doubleValue).average().orElse(0);

        if (yawVariance < 0.00015 && pitchVariance < 0.00015 && yawMean > 2.5) {
            boolean allEqual = true;
            double first = yawChanges.get(0);
            for (Double yaw : yawChanges) {
                if (Math.abs(yaw - first) > 0.008) {
                    allEqual = false;
                    break;
                }
            }

            if (allEqual && yawChanges.size() >= 50) {
                UUID uuid = player.getUniqueId();
                KillAuraTracker tracker = trackers.computeIfAbsent(uuid, k -> new KillAuraTracker());
                tracker.recordRotationViolation();
                if (tracker.getRotationViolations() < 4) {
                    return CheckResult.passed();
                }
                if (!tracker.hasNonPatternSignalSince(8000)) {
                    return CheckResult.passed();
                }
                CheckResult prelimResult = CheckResult.failed(
                    ViolationType.KILLAURA_ROTATION,
                    0.68,
                    String.format("Smooth aim detected (constant delta: %.4f, var: %.6f)", first, yawVariance)
                );

                if (!filtering.passesLayer2HeuristicFiltering(player, ViolationType.KILLAURA, prelimResult)) {
                    return CheckResult.passed();
                }

                return prelimResult;
            }
        }

        return CheckResult.passed();
    }

    private double calculateRotationVariance(List<Double> values) {
        if (values.isEmpty()) return 0;
        double mean = values.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        return values.stream().mapToDouble(v -> Math.pow(v - mean, 2)).average().orElse(0);
    }

    private double calculateGCDIterative(List<Double> values) {
        if (values.size() < 2) return 0;

        List<Double> sorted = new ArrayList<>(values);
        Collections.sort(sorted);

        double gcd = sorted.get(0);
        for (int i = 1; i < Math.min(sorted.size(), 15); i++) {
            gcd = gcdEuclidean(gcd, sorted.get(i));
            if (gcd < 0.005) break;
        }

        return gcd;
    }

    private double gcdEuclidean(double a, double b) {
        a = Math.abs(a);
        b = Math.abs(b);

        if (a < b) {
            double temp = a;
            a = b;
            b = temp;
        }

        int iterations = 0;
        while (b > 0.001 && iterations < 50) {
            double temp = b;
            b = a - Math.floor(a / b) * b;
            a = temp;
            iterations++;
        }
        return a;
    }

    private CheckResult checkRotationAcceleration(List<PlayerData.RotationData> rotations, Player player) {
        List<Double> accelerations = new ArrayList<>();

        for (int i = 2; i < rotations.size(); i++) {
            PlayerData.RotationData curr = rotations.get(i);
            PlayerData.RotationData prev = rotations.get(i - 1);
            PlayerData.RotationData prevPrev = rotations.get(i - 2);

            double dt1 = (prev.timestamp - prevPrev.timestamp) / 1000.0;
            double dt2 = (curr.timestamp - prev.timestamp) / 1000.0;

            if (dt1 > 0 && dt1 < 1.0 && dt2 > 0 && dt2 < 1.0) {
                float yawDelta1 = getYawDifference(prev.yaw, prevPrev.yaw);
                float pitchDelta1 = getPitchDifference(prev.pitch, prevPrev.pitch);
                float yawDelta2 = getYawDifference(curr.yaw, prev.yaw);
                float pitchDelta2 = getPitchDifference(curr.pitch, prev.pitch);

                float delta1 = (float) Math.sqrt(yawDelta1 * yawDelta1 + pitchDelta1 * pitchDelta1);
                float delta2 = (float) Math.sqrt(yawDelta2 * yawDelta2 + pitchDelta2 * pitchDelta2);

                double speed1 = delta1 / dt1;
                double speed2 = delta2 / dt2;

                double accel = (speed2 - speed1) / ((dt1 + dt2) / 2.0);
                accelerations.add(Math.abs(accel));
            }
        }

        if (accelerations.size() >= 15) {
            double avgAccel = accelerations.stream().mapToDouble(Double::doubleValue).average().orElse(0);
            long lowAccelCount = accelerations.stream().filter(a -> a < 3.0).count();
            double lowAccelRatio = (double) lowAccelCount / accelerations.size();

            double sumSpeed = 0;
            int speedSamples = 0;
            for (int i = 2; i < rotations.size(); i++) {
                PlayerData.RotationData cur = rotations.get(i);
                PlayerData.RotationData prv = rotations.get(i - 1);
                double dt = (cur.timestamp - prv.timestamp) / 1000.0;
                if (dt > 0 && dt < 1.0) {
                    double dy = getYawDifference(cur.yaw, prv.yaw);
                    double dp = getPitchDifference(cur.pitch, prv.pitch);
                    sumSpeed += Math.sqrt(dy * dy + dp * dp) / dt;
                    speedSamples++;
                }
            }
            double avgRotSpeed = speedSamples > 0 ? sumSpeed / speedSamples : 0;
            if (avgRotSpeed < 200.0) {
                return CheckResult.passed();
            }

            if (lowAccelRatio > 0.97 && avgAccel < 2.0 && accelerations.size() >= 22) {
                UUID uuid = player.getUniqueId();
                KillAuraTracker tracker = trackers.computeIfAbsent(uuid, k -> new KillAuraTracker());
                tracker.recordRotationViolation();
                if (tracker.getRotationViolations() < 4) {
                    return CheckResult.passed();
                }
                if (!tracker.hasNonPatternSignalSince(8000)) {
                    return CheckResult.passed();
                }
                CheckResult prelimResult = CheckResult.failed(
                    ViolationType.KILLAURA_ROTATION,
                    0.66,
                    String.format("No rotation acceleration (avg: %.1f, low: %.2f%%, samples: %d)", avgAccel, lowAccelRatio * 100, accelerations.size())
                );

                if (!filtering.passesLayer2HeuristicFiltering(player, ViolationType.KILLAURA, prelimResult)) {
                    return CheckResult.passed();
                }

                return prelimResult;
            }
        }

        return CheckResult.passed();
    }

    private CheckResult checkMultiAura(Player player, Entity target) {
        UUID playerId = player.getUniqueId();
        KillAuraTracker tracker = trackers.computeIfAbsent(playerId, k -> new KillAuraTracker());
        long now = System.currentTimeMillis();

        boolean holdingSword = player.getInventory().getItemInMainHand().getType().name().endsWith("_SWORD");
        Long sweepAt = sweepMarks.get(playerId);
        if (sweepAt != null) {
            if (now - sweepAt > SWEEP_EXEMPTION_WINDOW_MS) {
                sweepMarks.remove(playerId);
                sweepAt = null;
            }
        }
        if (holdingSword && sweepAt != null) {
            return CheckResult.passed();
        }

        ConcurrentLinkedDeque<HitRecord> hits = recentHits.computeIfAbsent(playerId, k -> new ConcurrentLinkedDeque<>());
        hits.add(new HitRecord(target.getUniqueId(), now, player.getLocation(), target.getLocation(), isMob(target)));

        hits.removeIf(record -> now - record.timestamp > 1000);

        Set<UUID> uniqueTargets = new HashSet<>();
        int playerTargets = 0;
        int mobTargets = 0;

        for (HitRecord hit : hits) {
            uniqueTargets.add(hit.targetId);
            if (hit.isMob) {
                mobTargets++;
            } else {
                playerTargets++;
            }
        }

        int minTargets = 3;
        int minHits = 4;
        double maxMovement = 3.0;
        long timeWindow = 800;

        if (mobTargets > playerTargets) {
            minTargets = 5;
            minHits = 6;
            maxMovement = 5.0;
            timeWindow = 1200;
        }

        if (uniqueTargets.size() >= minTargets) {
            long recentTime = now - timeWindow;
            int recentHitCount = 0;
            Set<UUID> recentTargets = new HashSet<>();

            for (HitRecord hit : hits) {
                if (hit.timestamp >= recentTime) {
                    recentHitCount++;
                    recentTargets.add(hit.targetId);
                }
            }

            double playerMovement = 0.0;
            if (hits.size() >= 2) {
                HitRecord first = hits.getFirst();
                HitRecord last = hits.getLast();
                if (first.location != null && last.location != null &&
                    first.location.getWorld() == last.location.getWorld()) {
                    playerMovement = first.location.distance(last.location);
                }
            }

            if (recentTargets.size() >= minTargets && recentHitCount >= minHits && playerMovement < maxMovement) {
                double maxPairwiseDist = 0.0;
                List<Location> recentLocs = new ArrayList<>();
                for (HitRecord h : hits) {
                    if (h.timestamp >= recentTime && h.targetLocation != null) {
                        recentLocs.add(h.targetLocation);
                    }
                }
                for (int a = 0; a < recentLocs.size(); a++) {
                    for (int b = a + 1; b < recentLocs.size(); b++) {
                        Location la = recentLocs.get(a);
                        Location lb = recentLocs.get(b);
                        if (la.getWorld() == lb.getWorld()) {
                            double d = la.distance(lb);
                            if (d > maxPairwiseDist) maxPairwiseDist = d;
                        }
                    }
                }
                if (maxPairwiseDist < 2.5 && mobTargets == 0) {
                    tracker.decayMultiTarget();
                    return CheckResult.passed();
                }

                tracker.recordMultiTargetViolation();

                int requiredViolations = 3;

                if (mobTargets > playerTargets) {
                    requiredViolations = 3;
                }

                if (tracker.getMultiTargetViolations() < requiredViolations) {
                    return CheckResult.passed();
                }

                double severity = Math.min(0.96, 0.72 + (recentTargets.size() * 0.08) + (tracker.getMultiTargetViolations() * 0.05));

                if (mobTargets > 0) {
                    severity *= 0.7;
                }

                CheckResult prelimResult = CheckResult.failed(
                        ViolationType.KILLAURA_MULTI,
                        severity,
                        String.format("Hit %d entities in %dms with %.1f blocks movement (hits: %d, vl: %d)",
                            recentTargets.size(), timeWindow, playerMovement, recentHitCount, tracker.getMultiTargetViolations())
                );

                if (!filtering.passesLayer2HeuristicFiltering(player, ViolationType.KILLAURA, prelimResult)) {
                    tracker.decayMultiTarget();
                    return CheckResult.passed();
                }

                return prelimResult;
            } else {
                tracker.decayMultiTarget();
            }
        } else {
            tracker.decayMultiTarget();
        }

        return CheckResult.passed();
    }

    private CheckResult checkPatternDetection(Player player, Entity target) {
        UUID playerId = player.getUniqueId();
        PlayerData data = playerDataMap.get(playerId);

        ConcurrentLinkedDeque<HitRecord> hits = recentHits.get(playerId);
        if (hits == null || hits.size() < 7) {
            return CheckResult.passed();
        }

        List<Long> timestamps = new ArrayList<>();
        for (HitRecord hit : hits) {
            timestamps.add(hit.timestamp);
        }

        if (filtering.detectMachinePattern(timestamps)) {
            CheckResult prelimResult = CheckResult.failed(
                    ViolationType.KILLAURA_PATTERN,
                    0.9,
                    "Machine-like attack pattern detected (too consistent)"
            );

            if (!filtering.passesLayer2HeuristicFiltering(player, ViolationType.KILLAURA, prelimResult)) {
                return CheckResult.passed();
            }

            return prelimResult;
        }

        return CheckResult.passed();
    }

    private CheckResult checkAimSnap(Player player, Entity target, PlayerData data) {
        UUID uuid = player.getUniqueId();
        KillAuraTracker tracker = trackers.computeIfAbsent(uuid, k -> new KillAuraTracker());

        Deque<PlayerData.RotationData> rotations = data.getRotationHistory();
        if (rotations.size() < 4) {
            return CheckResult.passed();
        }

        List<PlayerData.RotationData> rotList = new ArrayList<>(rotations);
        int size = rotList.size();

        PlayerData.RotationData beforeHit = rotList.get(size - 2);
        PlayerData.RotationData atHit = rotList.get(size - 1);

        float yawSnap = getYawDifference(atHit.yaw, beforeHit.yaw);
        float pitchSnap = getPitchDifference(atHit.pitch, beforeHit.pitch);
        double snapMagnitude = Math.sqrt(yawSnap * yawSnap + pitchSnap * pitchSnap);

        long dt = atHit.timestamp - beforeHit.timestamp;
        if (dt <= 0 || dt > 200) {
            return CheckResult.passed();
        }

        Location eyeLoc = player.getEyeLocation();
        Location targetLoc = getEntityHitboxCenter(target);
        Vector toTarget = targetLoc.toVector().subtract(eyeLoc.toVector()).normalize();
        Vector playerView = eyeLoc.getDirection().normalize();

        double dotProduct = playerView.dot(toTarget);
        double aimAccuracy = Math.toDegrees(Math.acos(Math.max(-1.0, Math.min(1.0, dotProduct))));

        int snapPing = filtering.getPing(player);
        double snapThreshold = 65.0 + Math.max(0, snapPing - 100) * 0.08;
        snapThreshold *= getSlowdownCpsMultiplier(player);
        boolean airborneAttacker = !player.isOnGround() && player.getFallDistance() < 3.0f;

        if (snapMagnitude > snapThreshold && aimAccuracy < 5.0 && dt < 55 && !airborneAttacker) {
            tracker.recordSnapViolation();

            if (tracker.getSnapViolations() >= 3) {
                double severity = Math.min(0.96, 0.80 + (snapMagnitude / 200.0));

                CheckResult prelimResult = CheckResult.failed(
                    ViolationType.KILLAURA,
                    severity,
                    String.format("Aim snap: %.1f° in %dms, accuracy: %.1f°", snapMagnitude, dt, aimAccuracy)
                );

                if (filtering.passesLayer2HeuristicFiltering(player, ViolationType.KILLAURA, prelimResult)) {
                    return prelimResult;
                }
            }
        } else {
            tracker.decaySnap();
        }

        return CheckResult.passed();
    }

    private CheckResult checkAttackCooldown(Player player, Entity target) {
        if (!ViaHelper.hasAttackCooldown(player)) {
            return CheckResult.passed();
        }

        UUID uuid = player.getUniqueId();
        KillAuraTracker tracker = trackers.computeIfAbsent(uuid, k -> new KillAuraTracker());

        float cooldown = player.getAttackCooldown();

        float cooldownThreshold = (!player.isOnGround() && player.getFallDistance() > 0.0f) ? 0.15f : 0.2f;

        if (cooldown < cooldownThreshold) {
            tracker.recordCooldownViolation();

            if (tracker.getCooldownViolations() >= 4) {
                double severity = Math.min(0.92, 0.70 + (0.2 - cooldown) * 0.5);

                CheckResult prelimResult = CheckResult.failed(
                    ViolationType.KILLAURA,
                    severity,
                    String.format("Attack cooldown bypass: %.1f%% ready", cooldown * 100)
                );

                if (filtering.passesLayer2HeuristicFiltering(player, ViolationType.KILLAURA, prelimResult)) {
                    return prelimResult;
                }
            }
        } else if (cooldown < 0.35f) {
            tracker.recordCooldownViolation();
        } else {
            tracker.decayCooldown();
        }

        return CheckResult.passed();
    }

    private CheckResult checkAimLock(Player player, Entity target, PlayerData data) {
        if (!(target instanceof LivingEntity)) {
            return CheckResult.passed();
        }

        UUID uuid = player.getUniqueId();
        KillAuraTracker tracker = trackers.computeIfAbsent(uuid, k -> new KillAuraTracker());

        ConcurrentLinkedDeque<HitRecord> hits = recentHits.get(uuid);
        if (hits == null || hits.size() < 6) {
            return CheckResult.passed();
        }

        Location eyeLoc = player.getEyeLocation();
        Location targetLoc = target.getLocation();
        double targetHeight = target.getHeight();
        if (targetHeight < 0.1) targetHeight = 0.1;

        double hitRatio = Math.max(0, Math.min(1.0, (eyeLoc.getY() - targetLoc.getY()) / targetHeight));

        tracker.recordHitHeight(hitRatio);

        List<Double> heights = tracker.getRecentHitHeights();
        if (heights.size() >= 8) {
            double avg = heights.stream().mapToDouble(Double::doubleValue).average().orElse(0.5);
            double variance = heights.stream()
                .mapToDouble(h -> Math.pow(h - avg, 2))
                .average()
                .orElse(1.0);

            if (variance < 0.003 && heights.size() >= 15) {
                double severity = Math.min(0.92, 0.72 + (0.005 - variance) * 10);

                CheckResult prelimResult = CheckResult.failed(
                    ViolationType.KILLAURA,
                    severity,
                    String.format("Aim lock: hit height variance %.4f (avg: %.2f)", variance, avg)
                );

                if (filtering.passesLayer2HeuristicFiltering(player, ViolationType.KILLAURA, prelimResult)) {
                    tracker.clearHitHeights();
                    return prelimResult;
                }
            }
        }

        return CheckResult.passed();
    }

    private CheckResult checkGazeCone(Player player, Entity target, PlayerData data) {
        UUID uuid = player.getUniqueId();
        KillAuraTracker tracker = trackers.computeIfAbsent(uuid, k -> new KillAuraTracker());

        if (filtering.getPing(player) > 200) {
            tracker.resetGazeStreak();
            return CheckResult.passed();
        }
        if (!(target instanceof LivingEntity)) {
            tracker.resetGazeStreak();
            return CheckResult.passed();
        }

        if (((LivingEntity) target).getVelocity().length() < 0.05) {
            tracker.resetGazeStreak();
            return CheckResult.passed();
        }

        Deque<TargetSample> samples = tracker.getTargetSamples(target.getUniqueId());

        long now = System.currentTimeMillis();
        Location eyeLoc = player.getEyeLocation();
        Vector targetEye = getEntityHitboxCenter(target).toVector();
        samples.addLast(new TargetSample(now, targetEye, eyeLoc.toVector(), eyeLoc.getYaw(), eyeLoc.getPitch()));
        while (samples.size() > 10) samples.pollFirst();

        if (samples.size() < 5) return CheckResult.passed();

        List<TargetSample> sampleList = new ArrayList<>(samples);

        int consecutive = 0;
        int bestStreak = 0;
        double sumAngle = 0;
        int counted = 0;
        for (TargetSample ts : sampleList) {
            Vector look = CombatGeometry.directionFromYawPitch(ts.yaw, ts.pitch);
            Vector toTarget = ts.targetEye.clone().subtract(ts.playerEye);
            double angle = CombatGeometry.angleBetweenDegrees(look, toTarget);
            sumAngle += angle;
            counted++;
            if (angle < 1.5) {
                consecutive++;
                if (consecutive > bestStreak) bestStreak = consecutive;
            } else {
                consecutive = 0;
            }
        }

        double avgAngle = counted > 0 ? sumAngle / counted : 99.0;
        List<float[]> gazeRef = data.getGazeHistorySnapshot();
        int gazeRefSize = gazeRef.size();

        if (bestStreak >= 5) {
            tracker.recordGazeStreak();
            if (tracker.getGazeViolations() < 2) {
                return CheckResult.passed();
            }
            CheckResult prelim = CheckResult.failed(
                ViolationType.KILLAURA,
                Math.min(0.94, 0.78 + (5 - avgAngle) * 0.02),
                String.format("KILLAURA_GAZE_LOCK: %d consecutive sub-1° (avg %.2f°, gaze:%d)", bestStreak, avgAngle, gazeRefSize)
            );
            if (!filtering.passesLayer2HeuristicFiltering(player, ViolationType.KILLAURA, prelim)) {
                return CheckResult.passed();
            }
            return prelim;
        } else {
            tracker.decayGazeStreak();
        }

        return CheckResult.passed();
    }

    private CheckResult checkSuprahumanReaction(Player player, Entity target) {
        UUID uuid = player.getUniqueId();
        KillAuraTracker tracker = trackers.computeIfAbsent(uuid, k -> new KillAuraTracker());
        UUID targetId = target.getUniqueId();

        Long enteredAt = tracker.getEnteredConeAt(targetId);
        if (enteredAt == null) {
            return CheckResult.passed();
        }

        long now = System.currentTimeMillis();
        long delta = now - enteredAt;
        if (tracker.hasFlaggedReaction(targetId)) {
            return CheckResult.passed();
        }

        if (delta >= 0 && delta < 50) {
            tracker.markReactionFlagged(targetId);
            tracker.recordSuprahumanReactionHit();
            if (tracker.getSuprahumanReactionHits() < 3) {
                return CheckResult.passed();
            }
            CheckResult prelim = CheckResult.failed(
                ViolationType.KILLAURA,
                0.92,
                String.format("KILLAURA_SUPRAHUMAN_REACTION: %dms reaction to cone-entry (x%d)",
                    delta, tracker.getSuprahumanReactionHits())
            );
            if (!filtering.passesLayer2HeuristicFiltering(player, ViolationType.KILLAURA, prelim)) {
                return CheckResult.passed();
            }
            tracker.resetSuprahumanReaction();
            return prelim;
        }

        return CheckResult.passed();
    }

    public void recordDamaged(Player victim, Entity attacker, double damage) {
        combatTracker.recordPlayerDamaged(victim, attacker, damage);
    }

    private int scanTickCounter = 0;

    public void tickCombat() {
        combatTracker.tick();
        if ((++scanTickCounter & 1) == 0) {
            scanGazeCones();
        }
    }

    private void scanGazeCones() {
        long now = System.currentTimeMillis();
        for (Player player : Bukkit.getOnlinePlayers()) {
            UUID uuid = player.getUniqueId();
            KillAuraTracker tracker = trackers.computeIfAbsent(uuid, k -> new KillAuraTracker());

            Location eyeLoc = player.getEyeLocation();
            Vector eyePos = eyeLoc.toVector();
            Vector lookDir = eyeLoc.getDirection().normalize();
            Set<UUID> seen = new HashSet<>();

            for (Entity nearby : player.getNearbyEntities(4.0, 4.0, 4.0)) {
                if (!(nearby instanceof LivingEntity)) continue;
                if (nearby.equals(player)) continue;

                Vector toEntity = getEntityHitboxCenter(nearby).toVector().subtract(eyePos);
                double dist = toEntity.length();
                if (dist > 4.0) continue;

                Vector toEntityNorm = toEntity.clone().normalize();
                double angle = CombatGeometry.angleBetweenDegrees(lookDir, toEntityNorm);
                boolean inCone = angle < 30.0 && dist <= 4.0;

                UUID id = nearby.getUniqueId();
                seen.add(id);
                tracker.markConeState(id, inCone, now);
            }

            tracker.retainConeEntries(seen);
            tracker.cleanupConeEntries(now, 5000);
            tracker.markConeScanInitialized();
        }
    }

    public void cleanup(UUID playerId) {
        recentHits.remove(playerId);
        trackers.remove(playerId);
        falsePositiveFilter.cleanup(playerId);
        combatTracker.cleanup(playerId);
    }

    public void cleanupStale() {
        long now = System.currentTimeMillis();
        recentHits.entrySet().removeIf(entry -> {
            ConcurrentLinkedDeque<HitRecord> hits = entry.getValue();
            hits.removeIf(record -> now - record.timestamp > 5000);
            return hits.isEmpty();
        });

        trackers.entrySet().removeIf(entry -> {
            KillAuraTracker tracker = entry.getValue();
            return now - Math.max(Math.max(tracker.lastCPSViolation, tracker.lastAngleViolation),
                                   Math.max(tracker.lastRotationViolation, tracker.lastMultiTargetViolation)) > 30000;
        });
    }

    private static class TargetSample {
        final long timestamp;
        final Vector targetEye;
        final Vector playerEye;
        final float yaw;
        final float pitch;

        TargetSample(long timestamp, Vector targetEye, Vector playerEye, float yaw, float pitch) {
            this.timestamp = timestamp;
            this.targetEye = targetEye;
            this.playerEye = playerEye;
            this.yaw = yaw;
            this.pitch = pitch;
        }
    }

    private static class HitRecord {
        final UUID targetId;
        final long timestamp;
        final Location location;
        final Location targetLocation;
        final boolean isMob;

        HitRecord(UUID targetId, long timestamp, Location location, Location targetLocation, boolean isMob) {
            this.targetId = targetId;
            this.timestamp = timestamp;
            this.location = location;
            this.targetLocation = targetLocation;
            this.isMob = isMob;
        }
    }

    private static class KillAuraTracker {
        private int cpsViolations = 0;
        private int angleViolations = 0;
        private int reachViolations = 0;
        private int rotationViolations = 0;
        private int multiTargetViolations = 0;
        private int snapViolations = 0;
        private int cooldownViolations = 0;
        private long lastCPSViolation = 0;
        private long lastAngleViolation = 0;
        private long lastReachViolation = 0;
        private long lastRotationViolation = 0;
        private long lastMultiTargetViolation = 0;
        private long lastSnapViolation = 0;
        private long lastCooldownViolation = 0;
        private final Deque<Double> hitHeights = new ArrayDeque<>(20);
        private boolean legacyCombatMode = false;

        private final Map<UUID, ArrayDeque<TargetSample>> targetSamples = new HashMap<>();
        private final Map<UUID, Long> targetEnteredConeAt = new HashMap<>();
        private final Set<UUID> wasInConeLastScan = new HashSet<>();
        private final Set<UUID> reactionFlagged = new HashSet<>();
        private int gazeViolations = 0;
        private long lastGazeViolation = 0;

        Deque<TargetSample> getTargetSamples(UUID targetId) {
            return targetSamples.computeIfAbsent(targetId, k -> new ArrayDeque<>(10));
        }

        private boolean coneScanInitialized = false;

        void markConeState(UUID targetId, boolean inCone, long ts) {
            boolean wasIn = wasInConeLastScan.contains(targetId);
            if (inCone) {
                if (!wasIn && coneScanInitialized) {
                    targetEnteredConeAt.put(targetId, ts);
                    reactionFlagged.remove(targetId);
                }
                wasInConeLastScan.add(targetId);
            } else {
                wasInConeLastScan.remove(targetId);
                targetEnteredConeAt.remove(targetId);
                reactionFlagged.remove(targetId);
            }
        }

        void markConeScanInitialized() {
            coneScanInitialized = true;
        }

        void clearConeEntry(UUID targetId) {
            targetEnteredConeAt.remove(targetId);
            wasInConeLastScan.remove(targetId);
            reactionFlagged.remove(targetId);
        }

        Long getEnteredConeAt(UUID targetId) {
            return targetEnteredConeAt.get(targetId);
        }

        boolean hasFlaggedReaction(UUID targetId) {
            return reactionFlagged.contains(targetId);
        }

        void markReactionFlagged(UUID targetId) {
            reactionFlagged.add(targetId);
        }

        void retainConeEntries(Set<UUID> seen) {
            targetEnteredConeAt.keySet().removeIf(id -> !seen.contains(id));
            wasInConeLastScan.removeIf(id -> !seen.contains(id));
            reactionFlagged.removeIf(id -> !seen.contains(id));
        }

        void cleanupConeEntries(long now, long maxAge) {
            targetEnteredConeAt.entrySet().removeIf(e -> now - e.getValue() > maxAge);
        }

        void recordGazeStreak() {
            long now = System.currentTimeMillis();
            if (now - lastGazeViolation < 3000) {
                gazeViolations++;
            } else {
                gazeViolations = 1;
            }
            lastGazeViolation = now;
        }

        void decayGazeStreak() {
            long now = System.currentTimeMillis();
            if (now - lastGazeViolation > 4000) {
                gazeViolations = Math.max(0, gazeViolations - 1);
            }
        }

        void resetGazeStreak() {
            gazeViolations = 0;
        }

        int getGazeViolations() {
            return gazeViolations;
        }

        void setLegacyCombatMode(boolean legacy) {
            this.legacyCombatMode = legacy;
        }

        void recordCPSViolation() {
            recordSignal("CPS");
            long now = System.currentTimeMillis();
            if (now - lastCPSViolation < 3000) {
                cpsViolations++;
            } else {
                cpsViolations = 1;
            }
            lastCPSViolation = now;
        }

        void recordReachViolation() {
            recordSignal("REACH");
            long now = System.currentTimeMillis();
            if (now - lastReachViolation < 3000) {
                reachViolations++;
            } else {
                reachViolations = 1;
            }
            lastReachViolation = now;
        }

        int getReachViolations() {
            return reachViolations;
        }

        void decayReach() {
            long now = System.currentTimeMillis();
            if (now - lastReachViolation > 4000) {
                reachViolations = Math.max(0, reachViolations - 1);
            }
        }

        void recordAngleViolation() {
            recordSignal("ANGLE");
            long now = System.currentTimeMillis();
            if (now - lastAngleViolation < 2000) {
                angleViolations++;
            } else {
                angleViolations = 1;
            }
            lastAngleViolation = now;
        }

        void recordRotationViolation() {
            recordSignal("ROTATION");
            long now = System.currentTimeMillis();
            if (now - lastRotationViolation < 2000) {
                rotationViolations++;
            } else {
                rotationViolations = 1;
            }
            lastRotationViolation = now;
        }

        void recordMultiTargetViolation() {
            recordSignal("MULTI");
            long now = System.currentTimeMillis();
            if (now - lastMultiTargetViolation < 1500) {
                multiTargetViolations++;
            } else {
                multiTargetViolations = 1;
            }
            lastMultiTargetViolation = now;
        }

        void decayCPS() {
            long now = System.currentTimeMillis();
            if (now - lastCPSViolation > 10000) {
                cpsViolations = Math.max(0, cpsViolations - 1);
            }
        }

        private int instantCpsHits = 0;
        private long lastInstantCpsHit = 0;
        private int impossibleCpsHits = 0;
        private long lastImpossibleCpsHit = 0;
        private int suprahumanReactionHits = 0;
        private long lastSuprahumanReactionHit = 0;

        void recordInstantCpsHit() {
            recordSignal("INSTANT_CPS");
            long now = System.currentTimeMillis();
            if (now - lastInstantCpsHit > 4000) instantCpsHits = 1;
            else instantCpsHits++;
            lastInstantCpsHit = now;
        }
        int getInstantCpsHits() { return instantCpsHits; }
        void resetInstantCps() { instantCpsHits = 0; }
        void decayInstantCps() {
            if (System.currentTimeMillis() - lastInstantCpsHit > 4000) instantCpsHits = 0;
        }

        void recordImpossibleCpsHit() {
            long now = System.currentTimeMillis();
            if (now - lastImpossibleCpsHit > 4000) impossibleCpsHits = 1;
            else impossibleCpsHits++;
            lastImpossibleCpsHit = now;
        }
        int getImpossibleCpsHits() { return impossibleCpsHits; }
        void resetImpossibleCps() { impossibleCpsHits = 0; }
        void decayImpossibleCps() {
            if (System.currentTimeMillis() - lastImpossibleCpsHit > 4000) impossibleCpsHits = 0;
        }

        void recordSuprahumanReactionHit() {
            long now = System.currentTimeMillis();
            if (now - lastSuprahumanReactionHit > 15000) suprahumanReactionHits = 1;
            else suprahumanReactionHits++;
            lastSuprahumanReactionHit = now;
        }
        int getSuprahumanReactionHits() { return suprahumanReactionHits; }
        void resetSuprahumanReaction() { suprahumanReactionHits = 0; }

        private final java.util.LinkedHashMap<String, Long> signalLog = new java.util.LinkedHashMap<>();
        private long lastAttackTime = 0;
        private double lastRotationDelta = 0.0;
        private long lastRotationTime = 0;
        private int snapAttackHits = 0;
        private long lastSnapAttackHit = 0;

        synchronized void recordSignal(String signal) {
            long now = System.currentTimeMillis();
            signalLog.put(signal, now);
            if (signalLog.size() > 12) {
                java.util.Iterator<Long> it = signalLog.values().iterator();
                if (it.hasNext()) { it.next(); it.remove(); }
            }
        }

        synchronized int distinctSignalsSince(long windowMs) {
            long cutoff = System.currentTimeMillis() - windowMs;
            int count = 0;
            for (Long ts : signalLog.values()) {
                if (ts >= cutoff) count++;
            }
            return count;
        }

        synchronized boolean hasNonPatternSignalSince(long windowMs) {
            long cutoff = System.currentTimeMillis() - windowMs;
            for (java.util.Map.Entry<String, Long> e : signalLog.entrySet()) {
                if (e.getValue() < cutoff) continue;
                String s = e.getKey();
                if (s.equals("CPS") || s.equals("ANGLE") || s.equals("REACH")
                    || s.equals("MULTI") || s.equals("INSTANT_CPS") || s.equals("ROTATION_SNAP")) {
                    return true;
                }
            }
            return false;
        }

        void recordAttackTime(long now) { lastAttackTime = now; }
        long getLastAttackTime() { return lastAttackTime; }
        void recordRotationDelta(double delta, long now) {
            lastRotationDelta = delta;
            lastRotationTime = now;
        }
        double getLastRotationDelta() { return lastRotationDelta; }
        long getLastRotationTime() { return lastRotationTime; }

        void recordSnapAttackHit() {
            long now = System.currentTimeMillis();
            if (now - lastSnapAttackHit > 5000) snapAttackHits = 1;
            else snapAttackHits++;
            lastSnapAttackHit = now;
        }
        int getSnapAttackHits() { return snapAttackHits; }
        void resetSnapAttackHits() { snapAttackHits = 0; }

        void decayAngle() {
            long now = System.currentTimeMillis();
            if (now - lastAngleViolation > 4000) {
                angleViolations = Math.max(0, angleViolations - 1);
            }
        }

        void decayRotation() {
            long now = System.currentTimeMillis();
            if (now - lastRotationViolation > 4000) {
                rotationViolations = Math.max(0, rotationViolations - 1);
            }
        }

        void decayMultiTarget() {
            long now = System.currentTimeMillis();
            if (now - lastMultiTargetViolation > 3000) {
                multiTargetViolations = Math.max(0, multiTargetViolations - 1);
            }
        }

        int getCPSViolations() {
            return cpsViolations;
        }

        int getAngleViolations() {
            return angleViolations;
        }

        int getRotationViolations() {
            return rotationViolations;
        }

        int getMultiTargetViolations() {
            return multiTargetViolations;
        }

        void recordSnapViolation() {
            long now = System.currentTimeMillis();
            if (now - lastSnapViolation < 2000) {
                snapViolations++;
            } else {
                snapViolations = 1;
            }
            lastSnapViolation = now;
        }

        void decaySnap() {
            long now = System.currentTimeMillis();
            if (now - lastSnapViolation > 3000) {
                snapViolations = Math.max(0, snapViolations - 1);
            }
        }

        int getSnapViolations() {
            return snapViolations;
        }

        void recordCooldownViolation() {
            long now = System.currentTimeMillis();
            if (now - lastCooldownViolation < 1500) {
                cooldownViolations++;
            } else {
                cooldownViolations = 1;
            }
            lastCooldownViolation = now;
        }

        void decayCooldown() {
            long now = System.currentTimeMillis();
            if (now - lastCooldownViolation > 2000) {
                cooldownViolations = Math.max(0, cooldownViolations - 1);
            }
        }

        int getCooldownViolations() {
            return cooldownViolations;
        }

        void recordHitHeight(double ratio) {
            if (hitHeights.size() >= 20) {
                hitHeights.pollFirst();
            }
            hitHeights.addLast(ratio);
        }

        List<Double> getRecentHitHeights() {
            return new ArrayList<>(hitHeights);
        }

        void clearHitHeights() {
            hitHeights.clear();
        }
    }
}
