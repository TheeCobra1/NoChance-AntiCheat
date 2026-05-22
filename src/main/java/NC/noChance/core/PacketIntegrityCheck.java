package NC.noChance.core;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientHeldItemChange;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerFlying;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerPosition;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerPositionAndRotation;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerRotation;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class PacketIntegrityCheck {
    private static final float MAX_ABS_COORD = 30_000_000f;
    private static final long DUPLICATE_SLOT_WINDOW_MS = 200L;
    private static final int DUPLICATE_SLOT_FLAG_COUNT = 4;

    private final Plugin plugin;
    private final Map<UUID, SlotState> slotStates = new ConcurrentHashMap<>();

    public PacketIntegrityCheck(Plugin plugin) {
        this.plugin = plugin;
    }

    public boolean inspect(PacketReceiveEvent event, Player player) {
        if (player == null) return false;
        try {
            Object type = event.getPacketType();
            if (type == PacketType.Play.Client.PLAYER_POSITION) {
                WrapperPlayClientPlayerPosition w = new WrapperPlayClientPlayerPosition(event);
                if (badCoords(w.getLocation().getX(), w.getLocation().getY(), w.getLocation().getZ())) {
                    return cancel(event, player, "NaN/Inf/oob position");
                }
            } else if (type == PacketType.Play.Client.PLAYER_POSITION_AND_ROTATION) {
                WrapperPlayClientPlayerPositionAndRotation w = new WrapperPlayClientPlayerPositionAndRotation(event);
                if (badCoords(w.getLocation().getX(), w.getLocation().getY(), w.getLocation().getZ())) {
                    return cancel(event, player, "NaN/Inf/oob position-rot");
                }
                if (badAngle(w.getLocation().getYaw()) || badAngle(w.getLocation().getPitch())) {
                    return cancel(event, player, "NaN/Inf rotation");
                }
            } else if (type == PacketType.Play.Client.PLAYER_ROTATION) {
                WrapperPlayClientPlayerRotation w = new WrapperPlayClientPlayerRotation(event);
                if (badAngle(w.getYaw()) || badAngle(w.getPitch())) {
                    return cancel(event, player, "NaN/Inf rotation");
                }
            } else if (type == PacketType.Play.Client.PLAYER_FLYING) {
                WrapperPlayClientPlayerFlying w = new WrapperPlayClientPlayerFlying(event);
                if (w.hasPositionChanged() && badCoords(w.getLocation().getX(), w.getLocation().getY(), w.getLocation().getZ())) {
                    return cancel(event, player, "NaN/Inf flying position");
                }
                if (w.hasRotationChanged() && (badAngle(w.getLocation().getYaw()) || badAngle(w.getLocation().getPitch()))) {
                    return cancel(event, player, "NaN/Inf flying rotation");
                }
            } else if (type == PacketType.Play.Client.HELD_ITEM_CHANGE) {
                WrapperPlayClientHeldItemChange w = new WrapperPlayClientHeldItemChange(event);
                int slot = w.getSlot();
                if (slot < 0 || slot > 8) {
                    return cancel(event, player, "invalid hotbar slot " + slot);
                }
                if (isDuplicateSlot(player, slot)) {
                    return cancel(event, player, "duplicate hotbar slot spam");
                }
            }
        } catch (Throwable t) {
            return cancel(event, player, "malformed packet: " + t.getClass().getSimpleName());
        }
        return false;
    }

    private boolean isDuplicateSlot(Player player, int slot) {
        UUID uuid = player.getUniqueId();
        long now = System.currentTimeMillis();
        SlotState s = slotStates.computeIfAbsent(uuid, k -> new SlotState());
        synchronized (s) {
            if (s.lastSlot == slot && now - s.lastTime < DUPLICATE_SLOT_WINDOW_MS) {
                s.streak++;
                s.lastTime = now;
                if (s.streak >= DUPLICATE_SLOT_FLAG_COUNT) {
                    s.streak = 0;
                    return true;
                }
                return false;
            }
            s.lastSlot = slot;
            s.lastTime = now;
            s.streak = 1;
            return false;
        }
    }

    private boolean badCoords(double x, double y, double z) {
        return Double.isNaN(x) || Double.isInfinite(x)
                || Double.isNaN(y) || Double.isInfinite(y)
                || Double.isNaN(z) || Double.isInfinite(z)
                || Math.abs(x) > MAX_ABS_COORD
                || Math.abs(y) > MAX_ABS_COORD
                || Math.abs(z) > MAX_ABS_COORD;
    }

    private boolean badAngle(float a) {
        return Float.isNaN(a) || Float.isInfinite(a);
    }

    private boolean cancel(PacketReceiveEvent event, Player player, String reason) {
        try { event.setCancelled(true); } catch (Throwable ignored) {}
        plugin.getLogger().warning("PacketIntegrity dropped packet from " + player.getName() + ": " + reason);
        return true;
    }

    public void cleanup(UUID uuid) {
        if (uuid != null) slotStates.remove(uuid);
    }

    private static class SlotState {
        int lastSlot = -1;
        long lastTime = 0L;
        int streak = 0;
    }
}
