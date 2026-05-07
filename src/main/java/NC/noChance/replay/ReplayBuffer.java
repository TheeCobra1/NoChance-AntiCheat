package NC.noChance.replay;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.util.Vector;

import java.util.*;

public class ReplayBuffer {
    private static final int PER_PLAYER_BLOCK_CAP = 250;
    private final int capacity;
    private final Deque<Snapshot> buffer;
    private final List<BlockAction> blockActions;
    private final Map<Long, Long> breakStartTimes;
    private Snapshot.Action pendingAction = Snapshot.Action.NONE;
    private int pendingTargetId = -1;
    private double pendingDamage = 0;
    private boolean wasOnGround = true;

    public ReplayBuffer(int capacity) {
        this.capacity = capacity;
        this.buffer = new ArrayDeque<>(capacity);
        this.blockActions = new ArrayList<>();
        this.breakStartTimes = new HashMap<>();
    }

    public synchronized void capture(Player player) {
        Location loc = player.getLocation();
        Vector vel = player.getVelocity();
        PlayerInventory inv = player.getInventory();

        ItemStack main = inv.getItemInMainHand();
        ItemStack off = inv.getItemInOffHand();
        ItemStack[] armor = inv.getArmorContents();

        Material mainMat = main != null ? main.getType() : Material.AIR;
        Material offMat = off != null ? off.getType() : Material.AIR;
        Material helmet = armor[3] != null ? armor[3].getType() : Material.AIR;
        Material chest = armor[2] != null ? armor[2].getType() : Material.AIR;
        Material legs = armor[1] != null ? armor[1].getType() : Material.AIR;
        Material boots = armor[0] != null ? armor[0].getType() : Material.AIR;

        boolean onGround = player.isOnGround();
        boolean jumping = !onGround && wasOnGround && vel.getY() > 0.1;
        wasOnGround = onGround;

        int potionCount = player.getActivePotionEffects().size();

        Snapshot snapshot = new Snapshot(
                System.currentTimeMillis(),
                loc.getX(),
                loc.getY(),
                loc.getZ(),
                loc.getYaw(),
                loc.getPitch(),
                vel.getX(),
                vel.getY(),
                vel.getZ(),
                player.isSneaking(),
                player.isSprinting(),
                player.isBlocking(),
                onGround,
                player.isSwimming(),
                player.isGliding(),
                jumping,
                mainMat,
                offMat,
                helmet,
                chest,
                legs,
                boots,
                pendingAction,
                pendingTargetId,
                inv.getHeldItemSlot(),
                (float) player.getHealth(),
                player.getFoodLevel(),
                player.getSaturation(),
                player.getFallDistance(),
                player.getFireTicks(),
                potionCount,
                pendingDamage
        );

        if (buffer.size() >= capacity) {
            buffer.pollFirst();
        }
        buffer.addLast(snapshot);

        pendingAction = Snapshot.Action.NONE;
        pendingTargetId = -1;
        pendingDamage = 0;
    }

    public synchronized void recordBlock(Block block, BlockAction.Type type, float progress, Material tool, int expectedMs) {
        if (block == null) return;
        long cutoff = System.currentTimeMillis() - 30000;
        blockActions.removeIf(a -> a.timestamp < cutoff);
        while (blockActions.size() > PER_PLAYER_BLOCK_CAP) blockActions.remove(0);
        breakStartTimes.values().removeIf(t -> t < cutoff);

        long now = System.currentTimeMillis();
        long key = packCoord(block.getX(), block.getY(), block.getZ());
        int breakTimeMs = 0;

        if (type == BlockAction.Type.START_BREAK) {
            breakStartTimes.put(key, now);
        } else if (type == BlockAction.Type.BREAK) {
            Long startTime = breakStartTimes.remove(key);
            if (startTime != null) {
                breakTimeMs = (int) (now - startTime);
            }
        } else if (type == BlockAction.Type.CANCEL) {
            breakStartTimes.remove(key);
        }

        blockActions.add(new BlockAction(
                now,
                block.getX(),
                block.getY(),
                block.getZ(),
                block.getType(),
                type,
                progress,
                tool,
                breakTimeMs,
                expectedMs
        ));
    }

    public synchronized void recordBlock(Block block, BlockAction.Type type, float progress) {
        recordBlock(block, type, progress, Material.AIR, 0);
    }

    private long packCoord(int x, int y, int z) {
        return ((long)(x & 0x3FFFFFF) << 38) | ((long)(z & 0x3FFFFFF) << 12) | (y & 0xFFF);
    }

    public synchronized void setAction(Snapshot.Action action, int targetId) {
        this.pendingAction = action;
        this.pendingTargetId = targetId;
    }

    public synchronized void setAction(Snapshot.Action action) {
        setAction(action, -1);
    }

    public synchronized void setDamage(double damage) {
        this.pendingDamage = damage;
    }

    public synchronized List<Snapshot> drain() {
        List<Snapshot> snapshots = new ArrayList<>(buffer);
        buffer.clear();
        return snapshots;
    }

    public synchronized List<Snapshot> copy() {
        return new ArrayList<>(buffer);
    }

    public synchronized List<BlockAction> copyBlocks() {
        return new ArrayList<>(blockActions);
    }

    public synchronized void clear() {
        buffer.clear();
        blockActions.clear();
        breakStartTimes.clear();
    }

    public synchronized int size() {
        return buffer.size();
    }
}
