package NC.noChance.packets;

import NC.noChance.core.ACConfig;
import NC.noChance.core.ViolationType;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerBlockPlacement;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerDigging;
import org.bukkit.entity.Player;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

public class BlockPacketAnalyzer {
    private static final int MAX_BLOCKS_PER_SECOND = 20;
    private static final double MAX_BLOCK_REACH = 5.0;

    public BlockPacketAnalyzer(ACConfig config) {
    }

    public void analyzeBlockPacket(PacketReceiveEvent event, Player player, PacketAnalyzer.PacketData data) {
        data.recordPacket(PacketAnalyzer.PacketCategory.BLOCK);

        try {
            if (event.getPacketType() == PacketType.Play.Client.PLAYER_BLOCK_PLACEMENT) {
                try {
                    WrapperPlayClientPlayerBlockPlacement wrapper = new WrapperPlayClientPlayerBlockPlacement(event);
                    int bx = wrapper.getBlockPosition().getX();
                    int by = wrapper.getBlockPosition().getY();
                    int bz = wrapper.getBlockPosition().getZ();
                    data.setMetadata("lastPlaceX", bx);
                    data.setMetadata("lastPlaceY", by);
                    data.setMetadata("lastPlaceZ", bz);
                    data.setMetadata("lastPlaceTime", System.currentTimeMillis());
                    Double pd = packetDistanceTo(data, bx, by, bz);
                    if (pd != null) data.setMetadata("lastPlaceDistance", pd);
                } catch (Exception e) {
                    Logger.getLogger("NoChance").log(Level.WARNING, "Failed to analyze block placement packet", e);
                }
            }

            if (event.getPacketType() == PacketType.Play.Client.PLAYER_DIGGING) {
                try {
                    WrapperPlayClientPlayerDigging wrapper = new WrapperPlayClientPlayerDigging(event);
                    int bx = wrapper.getBlockPosition().getX();
                    int by = wrapper.getBlockPosition().getY();
                    int bz = wrapper.getBlockPosition().getZ();
                    data.setMetadata("lastDigX", bx);
                    data.setMetadata("lastDigY", by);
                    data.setMetadata("lastDigZ", bz);
                    data.setMetadata("lastDigTime", System.currentTimeMillis());
                    Double pd = packetDistanceTo(data, bx, by, bz);
                    if (pd != null) data.setMetadata("lastDigDistance", pd);
                } catch (Exception e) {
                    Logger.getLogger("NoChance").log(Level.WARNING, "Failed to analyze block dig packet", e);
                }
            }
        } catch (Exception e) {
            Logger.getLogger("NoChance").log(Level.WARNING, "Failed to analyze block packet", e);
        }
    }

    private static Double packetDistanceTo(PacketAnalyzer.PacketData data, int bx, int by, int bz) {
        Object px = data.getMetadata("lastPacketX");
        Object py = data.getMetadata("lastPacketY");
        Object pz = data.getMetadata("lastPacketZ");
        if (!(px instanceof Double) || !(py instanceof Double) || !(pz instanceof Double)) return null;
        double dx = (double) px - (bx + 0.5);
        double dy = (double) py - (by + 0.5);
        double dz = (double) pz - (bz + 0.5);
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    public PacketAnalyzer.PacketViolationResult checkBlockViolation(Player player, PacketAnalyzer.PacketData data, ViolationType type) {
        Deque<Long> packetTimes = data.getBlockPacketTimes();

        if (packetTimes.size() < 3) {
            return new PacketAnalyzer.PacketViolationResult(false, 0.0, "Insufficient data");
        }

        switch (type) {
            case SCAFFOLD:
            case FASTPLACE:
                return checkFastPlacePackets(player, data);

            case FASTBREAK:
            case NUKER:
                return checkFastBreakPackets(player, data);

            default:
                return new PacketAnalyzer.PacketViolationResult(false, 0.0, "Unknown type");
        }
    }

    private PacketAnalyzer.PacketViolationResult checkFastPlacePackets(Player player, PacketAnalyzer.PacketData data) {
        Deque<Long> times = data.getBlockPacketTimes();
        long now = System.currentTimeMillis();

        long recentBlocks = times.stream().filter(t -> now - t < 1000).count();

        if (recentBlocks > MAX_BLOCKS_PER_SECOND) {
            double severity = Math.min(1.0, (double) (recentBlocks - MAX_BLOCKS_PER_SECOND) / MAX_BLOCKS_PER_SECOND + 0.5);
            return new PacketAnalyzer.PacketViolationResult(true, severity, "Packet place rate: " + recentBlocks + "/sec");
        }

        Object distObj = data.getMetadata("lastPlaceDistance");
        if (distObj != null) {
            double distance = (double) distObj;
            if (distance > MAX_BLOCK_REACH) {
                double severity = Math.min(1.0, (distance - MAX_BLOCK_REACH) / MAX_BLOCK_REACH);
                return new PacketAnalyzer.PacketViolationResult(true, severity, "Packet place reach: " + String.format("%.2f", distance));
            }
        }

        List<Long> intervals = new ArrayList<>();
        Long prev = null;
        for (Long time : times) {
            if (now - time > 2000) continue;
            if (prev != null) {
                intervals.add(time - prev);
            }
            prev = time;
        }

        if (intervals.size() >= 5) {
            double variance = calculateVariance(intervals);
            if (variance < 10) {
                return new PacketAnalyzer.PacketViolationResult(true, 0.85, "Perfect place timing: " + String.format("%.2f", variance));
            }
        }

        return new PacketAnalyzer.PacketViolationResult(false, 0.0, "Clean");
    }

    private PacketAnalyzer.PacketViolationResult checkFastBreakPackets(Player player, PacketAnalyzer.PacketData data) {
        Deque<Long> times = data.getBlockPacketTimes();
        long now = System.currentTimeMillis();

        org.bukkit.inventory.ItemStack tool = player.getInventory().getItemInMainHand();
        String toolType = tool != null && tool.getType() != org.bukkit.Material.AIR ? tool.getType().name() : "HAND";

        int efficiencyLevel = 0;
        if (tool != null && tool.getType() != org.bukkit.Material.AIR) {
            org.bukkit.enchantments.Enchantment effEnch = org.bukkit.enchantments.Enchantment.getByName("EFFICIENCY");
            if (effEnch == null) effEnch = org.bukkit.enchantments.Enchantment.getByName("DIG_SPEED");
            if (effEnch != null) {
                efficiencyLevel = tool.getEnchantmentLevel(effEnch);
            }
        }

        boolean isShovel = toolType.contains("SHOVEL") || toolType.contains("SPADE");
        boolean isAxe = toolType.contains("AXE") && !toolType.contains("PICKAXE");
        boolean isPickaxe = toolType.contains("PICKAXE");
        boolean isGoldenTool = toolType.contains("GOLDEN");
        boolean hasHighEfficiency = efficiencyLevel >= 4;

        org.bukkit.potion.PotionEffectType hasteType = org.bukkit.potion.PotionEffectType.getByName("HASTE");
        if (hasteType == null) hasteType = org.bukkit.potion.PotionEffectType.getByName("FAST_DIGGING");
        int hasteLevel = 0;
        if (hasteType != null) {
            org.bukkit.potion.PotionEffect haste = player.getPotionEffect(hasteType);
            if (haste != null) {
                hasteLevel = haste.getAmplifier() + 1;
            }
        }

        long recentBlocks = times.stream().filter(t -> now - t < 1000).count();

        int maxBlocksPerSecond = MAX_BLOCKS_PER_SECOND;
        if ((isShovel || isAxe) && hasHighEfficiency) {
            maxBlocksPerSecond = 22;
        } else if (isGoldenTool && hasHighEfficiency) {
            maxBlocksPerSecond = 24;
        } else if (hasHighEfficiency && hasteLevel >= 2) {
            maxBlocksPerSecond = 30;
        } else if (hasHighEfficiency || hasteLevel >= 1) {
            maxBlocksPerSecond = 25;
        }

        if (recentBlocks > maxBlocksPerSecond) {
            double severity = Math.min(1.0, (double) (recentBlocks - maxBlocksPerSecond) / maxBlocksPerSecond + 0.5);
            return new PacketAnalyzer.PacketViolationResult(true, severity, "Packet break rate: " + recentBlocks + "/sec");
        }

        List<Long> intervals = new ArrayList<>();
        Long prev = null;
        for (Long time : times) {
            if (now - time > 1500) continue;
            if (prev != null) {
                intervals.add(time - prev);
            }
            prev = time;
        }

        if (intervals.size() >= 4) {
            final int veryFastThreshold;
            final int fastThreshold;
            final int requiredVeryFast;
            final int requiredFast;

            if (isShovel && efficiencyLevel >= 5) {
                veryFastThreshold = 20;
                fastThreshold = 40;
                requiredVeryFast = 10;
                requiredFast = 15;
            } else if (isAxe && efficiencyLevel >= 5) {
                veryFastThreshold = 30;
                fastThreshold = 50;
                requiredVeryFast = 8;
                requiredFast = 12;
            } else if (isGoldenTool && efficiencyLevel >= 4) {
                veryFastThreshold = 25;
                fastThreshold = 45;
                requiredVeryFast = 9;
                requiredFast = 13;
            } else if (hasHighEfficiency && hasteLevel >= 2) {
                veryFastThreshold = 30;
                fastThreshold = 55;
                requiredVeryFast = 7;
                requiredFast = 10;
            } else if (hasHighEfficiency || hasteLevel >= 1) {
                veryFastThreshold = 35;
                fastThreshold = 70;
                requiredVeryFast = 6;
                requiredFast = 8;
            } else {
                veryFastThreshold = 50;
                fastThreshold = 100;
                requiredVeryFast = 3;
                requiredFast = 4;
            }

            long veryFastBreaks = intervals.stream().filter(i -> i < veryFastThreshold).count();
            if (veryFastBreaks >= requiredVeryFast) {
                String details = String.format("Packet: %d breaks <%dms (tool:%s eff:%d haste:%d req:%d)",
                    veryFastBreaks, veryFastThreshold, toolType, efficiencyLevel, hasteLevel, requiredVeryFast);
                return new PacketAnalyzer.PacketViolationResult(true, 0.92, details);
            }

            long fastBreaks = intervals.stream().filter(i -> i < fastThreshold).count();
            if (fastBreaks >= requiredFast) {
                String details = String.format("Packet: %d breaks <%dms (tool:%s eff:%d haste:%d req:%d)",
                    fastBreaks, fastThreshold, toolType, efficiencyLevel, hasteLevel, requiredFast);
                return new PacketAnalyzer.PacketViolationResult(true, 0.82, details);
            }

            double variance = calculateVariance(intervals);
            double mean = intervals.stream().mapToLong(Long::longValue).average().orElse(0.0);

            final int varianceThreshold;
            final int meanThreshold;

            if (isShovel && efficiencyLevel >= 5) {
                varianceThreshold = 1;
                meanThreshold = 40;
            } else if (isAxe && efficiencyLevel >= 5) {
                varianceThreshold = 3;
                meanThreshold = 50;
            } else if ((isShovel || isAxe) && hasHighEfficiency) {
                varianceThreshold = 5;
                meanThreshold = 60;
            } else if (isGoldenTool) {
                varianceThreshold = 8;
                meanThreshold = 80;
            } else {
                varianceThreshold = 15;
                meanThreshold = 150;
            }

            if (variance < varianceThreshold && mean < meanThreshold) {
                return new PacketAnalyzer.PacketViolationResult(true, 0.88, "Packet: Perfect timing var=" + String.format("%.1f", variance) + " mean=" + String.format("%.1f", mean));
            }
        }

        Object distObj = data.getMetadata("lastDigDistance");
        if (distObj != null) {
            double distance = (double) distObj;
            if (distance > MAX_BLOCK_REACH) {
                double severity = Math.min(1.0, (distance - MAX_BLOCK_REACH) / MAX_BLOCK_REACH);
                return new PacketAnalyzer.PacketViolationResult(true, severity, "Packet break reach: " + String.format("%.2f", distance));
            }
        }

        return new PacketAnalyzer.PacketViolationResult(false, 0.0, "Clean");
    }

    private double calculateVariance(List<Long> values) {
        if (values.isEmpty()) return 0.0;

        double mean = values.stream().mapToLong(Long::longValue).average().orElse(0.0);
        double variance = 0.0;

        for (Long value : values) {
            variance += Math.pow(value - mean, 2);
        }

        return variance / values.size();
    }
}
