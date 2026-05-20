package NC.noChance.packets;

import NC.noChance.core.ACConfig;
import NC.noChance.core.ViolationType;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientInteractEntity;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Vector;
import java.util.*;
import java.util.logging.Level;

public class CombatPacketAnalyzer {
    private final ACConfig config;
    private final Plugin plugin;
    private static final long AUTOCLICKER_VARIANCE_THRESHOLD = 5;

    public CombatPacketAnalyzer(ACConfig config, Plugin plugin) {
        this.config = config;
        this.plugin = plugin;
    }

    public void analyzeCombatPacket(PacketReceiveEvent event, Player player, PacketAnalyzer.PacketData data) {
        if (event.getPacketType() == PacketType.Play.Client.INTERACT_ENTITY) {
            data.recordPacket(PacketAnalyzer.PacketCategory.COMBAT);
            try {
                WrapperPlayClientInteractEntity wrapper = new WrapperPlayClientInteractEntity(event);

                data.setMetadata("lastAttackTime", System.currentTimeMillis());
                data.setMetadata("lastAttackedEntity", wrapper.getEntityId());

                if (wrapper.getAction() == WrapperPlayClientInteractEntity.InteractAction.ATTACK) {
                    int entityId = wrapper.getEntityId();
                    data.setMetadata("lastAttackDistance", null);
                    data.setMetadata("lastAttackAngle", null);

                    long attackNow = System.currentTimeMillis();
                    Object lastSwingObj = data.getMetadata("lastSwingTime");
                    long lastSwing = lastSwingObj instanceof Long ? (Long) lastSwingObj : 0L;
                    long sinceSwing = lastSwing > 0 ? attackNow - lastSwing : Long.MAX_VALUE;
                    boolean swingMissing = sinceSwing < 0 || sinceSwing > 300L;
                    Object noSwingObj = data.getMetadata("noSwingStreak");
                    int noSwingStreak = noSwingObj instanceof Integer ? (Integer) noSwingObj : 0;
                    if (swingMissing) {
                        noSwingStreak++;
                    } else {
                        noSwingStreak = 0;
                    }
                    data.setMetadata("noSwingStreak", noSwingStreak);
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        if (player == null || !player.isOnline() || player.getWorld() == null) return;

                        Entity target = findEntityById(player, entityId);
                        if (target == null || target.getWorld() == null) return;
                        if (!target.getWorld().equals(player.getWorld())) return;

                        Location playerLoc = player.getLocation();
                        Location targetLoc = target.getLocation();

                        double distance = playerLoc.distance(targetLoc);
                        data.setMetadata("lastAttackDistance", distance);

                        if (distance <= 6.0) {
                            try {
                                Location eye = player.getEyeLocation();
                                Vector toTarget = target.getBoundingBox().getCenter().subtract(eye.toVector());
                                double rayLen = toTarget.length();
                                if (rayLen > 0.5 && rayLen <= 6.0) {
                                    org.bukkit.util.RayTraceResult rt = player.getWorld().rayTraceBlocks(
                                            eye, toTarget.clone().normalize(), rayLen,
                                            org.bukkit.FluidCollisionMode.NEVER, true);
                                    if (rt != null && rt.getHitBlock() != null) {
                                        org.bukkit.Material hitMat = rt.getHitBlock().getType();
                                        if (hitMat.isOccluding()) {
                                            Object streakObj = data.getMetadata("wallHitStreak");
                                            int streak = streakObj instanceof Integer ? (Integer) streakObj : 0;
                                            data.setMetadata("wallHitStreak", streak + 1);
                                            data.setMetadata("lastWallHitMaterial", hitMat.name());
                                        } else {
                                            data.setMetadata("wallHitStreak", 0);
                                        }
                                    } else {
                                        data.setMetadata("wallHitStreak", 0);
                                    }
                                }
                            } catch (Throwable ignored) {
                            }
                        }

                        double dx = targetLoc.getX() - playerLoc.getX();
                        double dy = targetLoc.getY() - playerLoc.getY();
                        double dz = targetLoc.getZ() - playerLoc.getZ();
                        double len = Math.sqrt(dx * dx + dy * dy + dz * dz);

                        if (len > 0.001) {
                            dx /= len; dy /= len; dz /= len;
                            Vector lookDir = playerLoc.getDirection();
                            double dot = dx * lookDir.getX() + dy * lookDir.getY() + dz * lookDir.getZ();
                            dot = Math.max(-1.0, Math.min(1.0, dot));
                            double angle = Math.toDegrees(Math.acos(dot));
                            data.setMetadata("lastAttackAngle", angle);
                        }
                    });
                }
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "Failed to analyze combat packet", e);
                return;
            }
        }

        if (event.getPacketType() == PacketType.Play.Client.ANIMATION) {
            long now = System.currentTimeMillis();
            data.setMetadata("lastSwingTime", now);
            Object lastAttack = data.getMetadata("lastAttackTime");
            if (lastAttack != null && now - (long) lastAttack < 500) {
                data.recordPacket(PacketAnalyzer.PacketCategory.COMBAT);
            }
        }
    }

    private Entity findEntityById(Player player, int entityId) {
        for (Entity nearby : player.getNearbyEntities(8, 8, 8)) {
            if (nearby.getEntityId() == entityId) {
                return nearby;
            }
        }
        return null;
    }

    public PacketAnalyzer.PacketViolationResult checkCombatViolation(Player player, PacketAnalyzer.PacketData data, ViolationType type) {
        Deque<Long> packetTimes = data.getCombatPacketTimes();

        if (packetTimes.size() < 5) {
            return new PacketAnalyzer.PacketViolationResult(false, 0.0, "Insufficient data");
        }

        switch (type) {
            case KILLAURA:
            case KILLAURA_ANGLE:
            case KILLAURA_ROTATION:
            case KILLAURA_PATTERN:
                return checkKillAuraPackets(player, data);

            case AUTOCLICKER:
                return checkAutoClickerPackets(player, data);

            case REACH:
                return checkReachPackets(player, data);

            default:
                return new PacketAnalyzer.PacketViolationResult(false, 0.0, "Unknown type");
        }
    }

    private PacketAnalyzer.PacketViolationResult checkKillAuraPackets(Player player, PacketAnalyzer.PacketData data) {
        Object noSwingObj = data.getMetadata("noSwingStreak");
        if (noSwingObj instanceof Integer) {
            int streak = (Integer) noSwingObj;
            if (streak >= 5) {
                double severity = Math.min(0.97, 0.82 + (streak - 5) * 0.02);
                return new PacketAnalyzer.PacketViolationResult(true, severity, "No-swing aura: " + streak + " attacks without swing");
            }
        }

        Object wallObj = data.getMetadata("wallHitStreak");
        if (wallObj instanceof Integer) {
            int streak = (Integer) wallObj;
            if (streak >= 3) {
                Object matObj = data.getMetadata("lastWallHitMaterial");
                String matName = matObj instanceof String ? (String) matObj : "?";
                double severity = Math.min(0.97, 0.85 + (streak - 3) * 0.03);
                return new PacketAnalyzer.PacketViolationResult(true, severity, "Wall-hit aura: " + streak + " through " + matName);
            }
        }

        Object angleObj = data.getMetadata("lastAttackAngle");
        if (angleObj != null) {
            double angle = (double) angleObj;
            if (angle > config.getKillAuraMaxAngle()) {
                double severity = Math.min(1.0, angle / config.getKillAuraMaxAngle());
                return new PacketAnalyzer.PacketViolationResult(true, severity, "Packet attack angle: " + String.format("%.1f", angle));
            }
        }

        Object distObj = data.getMetadata("lastAttackDistance");
        if (distObj != null) {
            double distance = (double) distObj;
            if (distance > config.getKillAuraMaxReach()) {
                double severity = Math.min(1.0, (distance - config.getKillAuraMaxReach()) / config.getKillAuraMaxReach());
                return new PacketAnalyzer.PacketViolationResult(true, severity, "Packet reach: " + String.format("%.2f", distance));
            }
        }

        Deque<Long> times = data.getCombatPacketTimes();
        long now = System.currentTimeMillis();

        int recentCount = 0;
        long[] recentIntervals = new long[32];
        int intervalIdx = 0;
        Long prev = null;

        for (Long time : times) {
            if (now - time > 2000) continue;
            if (now - time <= 1000) recentCount++;
            if (prev != null && intervalIdx < recentIntervals.length) {
                recentIntervals[intervalIdx++] = time - prev;
            }
            prev = time;
        }

        if (intervalIdx >= 7) {
            double variance = calculateVarianceArray(recentIntervals, intervalIdx);
            if (variance < config.getKillAuraPacketVarianceThreshold()) {
                double severity = 0.90;
                if (variance < config.getKillAuraPacketVarianceStrict()) {
                    severity = 0.94;
                }
                return new PacketAnalyzer.PacketViolationResult(true, severity, "Perfect timing variance: " + String.format("%.2f", variance));
            }
        }

        if (recentCount > config.getKillAuraPacketAttackRateLimit()) {
            double severity = Math.min(0.93, 0.73 + (recentCount - config.getKillAuraPacketAttackRateLimit()) / 32.0);
            return new PacketAnalyzer.PacketViolationResult(true, severity, "Packet attack rate: " + recentCount + "/s");
        }

        return new PacketAnalyzer.PacketViolationResult(false, 0.0, "Clean");
    }

    private PacketAnalyzer.PacketViolationResult checkAutoClickerPackets(Player player, PacketAnalyzer.PacketData data) {
        Deque<Long> times = data.getCombatPacketTimes();
        long now = System.currentTimeMillis();

        int recentClicks = 0;
        long[] intervals = new long[32];
        int intervalIdx = 0;
        Long prev = null;

        for (Long time : times) {
            if (now - time > 1000) continue;
            recentClicks++;
            if (prev != null && intervalIdx < intervals.length) {
                intervals[intervalIdx++] = time - prev;
            }
            prev = time;
        }

        if (recentClicks > config.getAutoClickerMaxCPS() * 1.15) {
            double severity = Math.min(1.0, (double) recentClicks / (config.getAutoClickerMaxCPS() * 1.1));
            return new PacketAnalyzer.PacketViolationResult(true, severity, "Packet CPS: " + recentClicks);
        }

        if (intervalIdx >= 10) {
            double variance = calculateVarianceArray(intervals, intervalIdx);
            if (variance < AUTOCLICKER_VARIANCE_THRESHOLD) {
                return new PacketAnalyzer.PacketViolationResult(true, 0.95, "Perfect click intervals: " + String.format("%.2f", variance));
            }
        }

        return new PacketAnalyzer.PacketViolationResult(false, 0.0, "Clean");
    }

    private PacketAnalyzer.PacketViolationResult checkReachPackets(Player player, PacketAnalyzer.PacketData data) {
        Object distObj = data.getMetadata("lastAttackDistance");
        if (distObj == null) {
            return new PacketAnalyzer.PacketViolationResult(false, 0.0, "No distance data");
        }

        double distance = (double) distObj;
        double maxReach = config.getReachMaxEntityReach();

        if (distance > maxReach) {
            double severity = Math.min(1.0, (distance - maxReach) / maxReach);
            return new PacketAnalyzer.PacketViolationResult(true, severity, "Packet reach: " + String.format("%.2f", distance));
        }

        return new PacketAnalyzer.PacketViolationResult(false, 0.0, "Clean");
    }

    private double calculateVarianceArray(long[] values, int count) {
        if (count == 0) return 0.0;

        long sum = 0;
        for (int i = 0; i < count; i++) {
            sum += values[i];
        }
        double mean = (double) sum / count;

        double variance = 0.0;
        for (int i = 0; i < count; i++) {
            double diff = values[i] - mean;
            variance += diff * diff;
        }

        return variance / count;
    }
}
