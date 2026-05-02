package NC.noChance.packets;

import NC.noChance.core.ACConfig;
import NC.noChance.core.ViolationType;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerPosition;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerPositionAndRotation;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

public class MovementPacketAnalyzer {
    private final ACConfig config;
    private static final double GRAVITY = 0.08;
    private static final double MAX_LEGIT_SPEED = 0.6;
    private static final int PACKET_RATE_THRESHOLD = 25;

    public MovementPacketAnalyzer(ACConfig config) {
        this.config = config;
    }

    public void analyzeMovementPacket(PacketReceiveEvent event, Player player, PacketAnalyzer.PacketData data) {
        data.recordPacket(PacketAnalyzer.PacketCategory.MOVEMENT);

        double x = 0, y = 0, z = 0;
        boolean hasPosition = false;

        try {
            if (event.getPacketType() == PacketType.Play.Client.PLAYER_POSITION) {
                WrapperPlayClientPlayerPosition wrapper = new WrapperPlayClientPlayerPosition(event);
                x = wrapper.getLocation().getX();
                y = wrapper.getLocation().getY();
                z = wrapper.getLocation().getZ();
                hasPosition = true;
            } else if (event.getPacketType() == PacketType.Play.Client.PLAYER_POSITION_AND_ROTATION) {
                WrapperPlayClientPlayerPositionAndRotation wrapper = new WrapperPlayClientPlayerPositionAndRotation(event);
                x = wrapper.getLocation().getX();
                y = wrapper.getLocation().getY();
                z = wrapper.getLocation().getZ();
                hasPosition = true;
            }
        } catch (Exception e) {
            Logger.getLogger("NoChance").log(Level.WARNING, "Failed to parse movement packet", e);
            return;
        }

        if (hasPosition) {
            data.setMetadata("lastPacketX", x);
            data.setMetadata("lastPacketY", y);
            data.setMetadata("lastPacketZ", z);
            data.setMetadata("lastPacketTime", System.currentTimeMillis());

            Object prevX = data.getMetadata("prevPacketX");
            Object prevY = data.getMetadata("prevPacketY");
            Object prevZ = data.getMetadata("prevPacketZ");

            if (prevX != null && prevY != null && prevZ != null) {
                double dx = x - (double) prevX;
                double dy = y - (double) prevY;
                double dz = z - (double) prevZ;

                double horizontalDist = Math.sqrt(dx * dx + dz * dz);
                data.setMetadata("lastHorizontalSpeed", horizontalDist);
                data.setMetadata("lastVerticalSpeed", dy);
            }

            data.setMetadata("prevPacketX", x);
            data.setMetadata("prevPacketY", y);
            data.setMetadata("prevPacketZ", z);
        }
    }

    public PacketAnalyzer.PacketViolationResult checkMovementViolation(Player player, PacketAnalyzer.PacketData data, ViolationType type) {
        Deque<Long> packetTimes = data.getMovementPacketTimes();

        if (packetTimes.size() < 10) {
            return new PacketAnalyzer.PacketViolationResult(false, 0.0, "Insufficient data");
        }

        if (type == ViolationType.SPEED) {
            return checkSpeedPackets(player, data);
        } else if (type == ViolationType.FLY) {
            return checkFlyPackets(player, data);
        } else if (type == ViolationType.NOCLIP) {
            return checkNoClipPackets(player, data);
        }

        return new PacketAnalyzer.PacketViolationResult(false, 0.0, "Unknown type");
    }

    private PacketAnalyzer.PacketViolationResult checkSpeedPackets(Player player, PacketAnalyzer.PacketData data) {
        Object speedObj = data.getMetadata("lastHorizontalSpeed");
        if (speedObj == null) {
            return new PacketAnalyzer.PacketViolationResult(false, 0.0, "No speed data");
        }

        double speed = (double) speedObj;
        double maxSpeed = config.getMaxSpeed() * config.getSprintMultiplier();

        if (player.hasPotionEffect(org.bukkit.potion.PotionEffectType.SPEED)) {
            int level = player.getPotionEffect(org.bukkit.potion.PotionEffectType.SPEED).getAmplifier() + 1;
            maxSpeed *= config.getSpeedPotionMultiplier(level);
        }

        if (player.isGliding() || player.isRiptiding() || player.getVehicle() != null) {
            return new PacketAnalyzer.PacketViolationResult(false, 0.0, "Legitimate movement");
        }

        if (player.hasPotionEffect(org.bukkit.potion.PotionEffectType.DOLPHINS_GRACE)) {
            maxSpeed *= 1.5;
        }

        Block below = player.getLocation().subtract(0, 0.5, 0).getBlock();
        Material belowType = below.getType();
        if (belowType == Material.ICE || belowType == Material.PACKED_ICE || belowType == Material.BLUE_ICE) {
            maxSpeed *= 2.5;
        }

        ItemStack boots = player.getInventory().getBoots();
        if (boots != null && boots.containsEnchantment(Enchantment.SOUL_SPEED)) {
            maxSpeed *= 1.6;
        }

        maxSpeed *= 1.3;

        if (speed > maxSpeed * 1.5) {
            double severity = Math.min(1.0, (speed - maxSpeed * 1.3) / maxSpeed);
            return new PacketAnalyzer.PacketViolationResult(true, severity, "Packet speed: " + String.format("%.3f", speed));
        }

        Deque<Long> times = data.getMovementPacketTimes();
        long now = System.currentTimeMillis();
        int count = 0;
        for (Long t : times) {
            if (now - t < 1000) count++;
        }

        if (count > PACKET_RATE_THRESHOLD * 1.2) {
            return new PacketAnalyzer.PacketViolationResult(true, 0.75, "Packet flood: " + count + " packets/sec");
        }

        return new PacketAnalyzer.PacketViolationResult(false, 0.0, "Clean");
    }

    private PacketAnalyzer.PacketViolationResult checkFlyPackets(Player player, PacketAnalyzer.PacketData data) {
        Object verticalSpeedObj = data.getMetadata("lastVerticalSpeed");
        if (verticalSpeedObj == null) {
            return new PacketAnalyzer.PacketViolationResult(false, 0.0, "No vertical data");
        }

        double vSpeed = (double) verticalSpeedObj;

        if (player.isFlying() || player.isGliding() || player.getAllowFlight()) {
            return new PacketAnalyzer.PacketViolationResult(false, 0.0, "Allowed flight");
        }

        if (Math.abs(vSpeed) > config.getMaxFlyVelocityY() && !player.isOnGround()) {
            if (vSpeed > 0) {
                double severity = Math.min(1.0, vSpeed / config.getMaxFlyVelocityY());
                return new PacketAnalyzer.PacketViolationResult(true, severity, "Upward velocity: " + String.format("%.3f", vSpeed));
            }
        }

        Object prevVSpeed = data.getMetadata("prevVerticalSpeed");
        if (prevVSpeed != null) {
            double prev = (double) prevVSpeed;
            Block belowBlock = player.getLocation().subtract(0, 0.5, 0).getBlock();
            String belowName = belowBlock.getType().name();
            if (prev < -0.8 && vSpeed > 0.5 && !player.isOnGround() && !belowName.contains("SLIME")) {
                return new PacketAnalyzer.PacketViolationResult(true, 0.85, "Impossible velocity change");
            }
        }

        data.setMetadata("prevVerticalSpeed", vSpeed);

        return new PacketAnalyzer.PacketViolationResult(false, 0.0, "Clean");
    }

    private PacketAnalyzer.PacketViolationResult checkNoClipPackets(Player player, PacketAnalyzer.PacketData data) {
        if (player.isGliding() || player.isRiptiding()) {
            return new PacketAnalyzer.PacketViolationResult(false, 0.0, "Gliding");
        }

        Object xObj = data.getMetadata("lastPacketX");
        Object yObj = data.getMetadata("lastPacketY");
        Object zObj = data.getMetadata("lastPacketZ");

        if (xObj == null || yObj == null || zObj == null) {
            return new PacketAnalyzer.PacketViolationResult(false, 0.0, "No position data");
        }

        try {
            double x = (double) xObj;
            double y = (double) yObj;
            double z = (double) zObj;

            if (player.getWorld() == null) {
                return new PacketAnalyzer.PacketViolationResult(false, 0.0, "No world");
            }

            Location packetLoc = new Location(player.getWorld(), x, y, z);
            Location actualLoc = player.getLocation();

            if (actualLoc.getWorld() == null || !actualLoc.getWorld().equals(packetLoc.getWorld())) {
                return new PacketAnalyzer.PacketViolationResult(false, 0.0, "World mismatch");
            }

            double distance = packetLoc.distance(actualLoc);

            if (distance > 5.0) {
                double severity = Math.min(1.0, distance / 10.0);
                return new PacketAnalyzer.PacketViolationResult(true, severity, "Position desync: " + String.format("%.2f", distance));
            }
        } catch (Exception e) {
            Logger.getLogger("NoChance").log(Level.WARNING, "Failed to validate position desync", e);
        }

        return new PacketAnalyzer.PacketViolationResult(false, 0.0, "Clean");
    }
}
