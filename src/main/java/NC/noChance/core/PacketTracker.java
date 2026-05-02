package NC.noChance.core;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.potion.PotionEffect;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class PacketTracker {
    private final Map<UUID, SequenceData> sequences;

    public PacketTracker() {
        this.sequences = new ConcurrentHashMap<>();
    }

    public enum PacketAction {
        START_DIG,
        ABORT_DIG,
        FINISH_DIG,
        BLOCK_PLACE,
        SWING_ARM,
        USE_ITEM
    }

    private static class SequenceData {
        private final Deque<PacketEvent> events;
        private volatile Location currentDigLocation;
        private volatile long currentDigStartTime;
        private volatile int invalidSequences;
        private volatile long lastDecayTime;

        public SequenceData() {
            this.events = new java.util.concurrent.ConcurrentLinkedDeque<>();
            this.currentDigLocation = null;
            this.currentDigStartTime = 0;
            this.invalidSequences = 0;
            this.lastDecayTime = System.currentTimeMillis();
        }
    }

    private static class PacketEvent {
        public final PacketAction action;
        public final Location location;
        public final long timestamp;

        public PacketEvent(PacketAction action, Location location) {
            this.action = action;
            this.location = location;
            this.timestamp = System.currentTimeMillis();
        }
    }

    public void recordPacket(UUID playerId, PacketAction action, Location location) {
        SequenceData data = sequences.computeIfAbsent(playerId, k -> new SequenceData());

        long now = System.currentTimeMillis();
        data.events.addLast(new PacketEvent(action, location));

        if (data.events.size() > 50) {
            data.events.pollFirst();
        }

        if (now - data.lastDecayTime > 10000) {
            data.invalidSequences = Math.max(0, data.invalidSequences - 1);
            data.lastDecayTime = now;
        }

        if (action == PacketAction.START_DIG) {
            data.currentDigLocation = location;
            data.currentDigStartTime = now;
        } else if (action == PacketAction.FINISH_DIG) {
            if (data.currentDigLocation == null || location == null) {
                data.invalidSequences++;
            } else if (location.getWorld() != null && data.currentDigLocation.getWorld() != null
                       && location.getWorld().equals(data.currentDigLocation.getWorld())
                       && data.currentDigLocation.distance(location) > 0.5) {
                data.invalidSequences++;
            }
            data.currentDigLocation = null;
            data.currentDigStartTime = 0;
        } else if (action == PacketAction.ABORT_DIG) {
            data.currentDigLocation = null;
            data.currentDigStartTime = 0;
        }
    }

    public SequenceViolation checkSequence(UUID playerId, Player player) {
        SequenceData data = sequences.get(playerId);
        if (data == null || data.events.size() < 5) {
            return new SequenceViolation(false, 0.0, "Insufficient data");
        }

        ItemStack tool = player.getInventory().getItemInMainHand();
        String toolType = tool != null && tool.getType() != Material.AIR ? tool.getType().name() : "HAND";

        int efficiencyLevel = 0;
        if (tool != null && tool.getType() != Material.AIR) {
            Enchantment effEnch = Enchantment.getByName("EFFICIENCY");
            if (effEnch == null) effEnch = Enchantment.getByName("DIG_SPEED");
            if (effEnch != null) {
                efficiencyLevel = tool.getEnchantmentLevel(effEnch);
            }
        }

        boolean isShovel = toolType.contains("SHOVEL") || toolType.contains("SPADE");
        boolean isAxe = toolType.contains("AXE") && !toolType.contains("PICKAXE");
        boolean isGoldenTool = toolType.contains("GOLDEN");
        boolean hasHighEfficiency = efficiencyLevel >= 4;

        PotionEffectType hasteType = PotionEffectType.getByName("HASTE");
        if (hasteType == null) hasteType = PotionEffectType.getByName("FAST_DIGGING");
        int hasteLevel = 0;
        if (hasteType != null) {
            PotionEffect haste = player.getPotionEffect(hasteType);
            if (haste != null) {
                hasteLevel = haste.getAmplifier() + 1;
            }
        }

        List<PacketEvent> recentEvents = new ArrayList<>();
        long cutoff = System.currentTimeMillis() - 2000;
        for (PacketEvent event : data.events) {
            if (event.timestamp > cutoff) {
                recentEvents.add(event);
            }
        }

        long finishWithoutStart = recentEvents.stream()
                .filter(e -> e.action == PacketAction.FINISH_DIG)
                .count();

        long startEvents = recentEvents.stream()
                .filter(e -> e.action == PacketAction.START_DIG)
                .count();

        int allowedFinishOverage = 2;
        if (hasHighEfficiency && hasteLevel >= 2) {
            allowedFinishOverage = 8;
        } else if ((isShovel || isAxe || isGoldenTool) && hasHighEfficiency) {
            allowedFinishOverage = 7;
        } else if (hasHighEfficiency || hasteLevel >= 2) {
            allowedFinishOverage = 5;
        } else if (efficiencyLevel >= 2 || hasteLevel >= 1) {
            allowedFinishOverage = 4;
        }

        if (finishWithoutStart > startEvents + allowedFinishOverage) {
            return new SequenceViolation(true, 0.85,
                String.format("Invalid packet sequence: %d finish without start", finishWithoutStart - startEvents));
        }

        if (data.invalidSequences >= 5) {
            return new SequenceViolation(true, 0.78,
                String.format("Multiple invalid sequences: %d", data.invalidSequences));
        }

        int rapidThreshold = 50;
        int requiredRapid = 3;

        if (hasHighEfficiency && hasteLevel >= 2) {
            rapidThreshold = 20;
            requiredRapid = 18;
        } else if (isShovel && efficiencyLevel >= 5) {
            rapidThreshold = 20;
            requiredRapid = 16;
        } else if ((isAxe || isGoldenTool) && efficiencyLevel >= 4) {
            rapidThreshold = 25;
            requiredRapid = 14;
        } else if (hasHighEfficiency || hasteLevel >= 2) {
            rapidThreshold = 30;
            requiredRapid = 12;
        } else if (efficiencyLevel >= 2 || hasteLevel >= 1) {
            rapidThreshold = 35;
            requiredRapid = 10;
        }

        long rapidFinishes = 0;
        PacketEvent lastFinish = null;
        for (PacketEvent event : recentEvents) {
            if (event.action == PacketAction.FINISH_DIG) {
                if (lastFinish != null && event.timestamp - lastFinish.timestamp < rapidThreshold) {
                    rapidFinishes++;
                }
                lastFinish = event;
            }
        }

        if (rapidFinishes >= requiredRapid) {
            return new SequenceViolation(true, 0.82,
                String.format("Rapid packet sequence: %d finishes <%dms apart", rapidFinishes, rapidThreshold));
        }

        return new SequenceViolation(false, 0.0, "Clean sequence");
    }

    public void cleanup(UUID playerId) {
        sequences.remove(playerId);
    }

    public static class SequenceViolation {
        public final boolean violated;
        public final double severity;
        public final String reason;

        public SequenceViolation(boolean violated, double severity, String reason) {
            this.violated = violated;
            this.severity = severity;
            this.reason = reason;
        }
    }
}
