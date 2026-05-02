package NC.noChance.replay;

import org.bukkit.Material;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;

public class Snapshot {
    public final long timestamp;
    public final double x;
    public final double y;
    public final double z;
    public final float yaw;
    public final float pitch;
    public final double velX;
    public final double velY;
    public final double velZ;
    public final boolean sneaking;
    public final boolean sprinting;
    public final boolean blocking;
    public final boolean onGround;
    public final boolean swimming;
    public final boolean gliding;
    public final boolean jumping;
    public final Material mainHand;
    public final Material offHand;
    public final Material helmet;
    public final Material chest;
    public final Material legs;
    public final Material boots;
    public final Action action;
    public final int targetId;
    public final int slot;
    public final float health;
    public final int food;
    public final float saturation;
    public final float fallDist;
    public final int fireTicks;
    public final int potionCount;
    public final double damage;

    public Snapshot(long timestamp, double x, double y, double z, float yaw, float pitch,
                    double velX, double velY, double velZ,
                    boolean sneaking, boolean sprinting, boolean blocking, boolean onGround,
                    boolean swimming, boolean gliding, boolean jumping,
                    Material mainHand, Material offHand,
                    Material helmet, Material chest, Material legs, Material boots,
                    Action action, int targetId, int slot,
                    float health, int food, float saturation,
                    float fallDist, int fireTicks, int potionCount, double damage) {
        this.timestamp = timestamp;
        this.x = x;
        this.y = y;
        this.z = z;
        this.yaw = yaw;
        this.pitch = pitch;
        this.velX = velX;
        this.velY = velY;
        this.velZ = velZ;
        this.sneaking = sneaking;
        this.sprinting = sprinting;
        this.blocking = blocking;
        this.onGround = onGround;
        this.swimming = swimming;
        this.gliding = gliding;
        this.jumping = jumping;
        this.mainHand = mainHand;
        this.offHand = offHand;
        this.helmet = helmet;
        this.chest = chest;
        this.legs = legs;
        this.boots = boots;
        this.action = action;
        this.targetId = targetId;
        this.slot = slot;
        this.health = health;
        this.food = food;
        this.saturation = saturation;
        this.fallDist = fallDist;
        this.fireTicks = fireTicks;
        this.potionCount = potionCount;
        this.damage = damage;
    }

    public void write(DataOutputStream out) throws IOException {
        out.writeLong(timestamp);
        out.writeDouble(x);
        out.writeDouble(y);
        out.writeDouble(z);
        out.writeFloat(yaw);
        out.writeFloat(pitch);
        out.writeDouble(velX);
        out.writeDouble(velY);
        out.writeDouble(velZ);
        int flags = 0;
        if (sneaking) flags |= 1;
        if (sprinting) flags |= 2;
        if (blocking) flags |= 4;
        if (onGround) flags |= 8;
        if (swimming) flags |= 16;
        if (gliding) flags |= 32;
        if (jumping) flags |= 64;
        out.writeByte(flags);
        out.writeUTF(mainHand != null ? mainHand.name() : "AIR");
        out.writeUTF(offHand != null ? offHand.name() : "AIR");
        out.writeUTF(helmet != null ? helmet.name() : "AIR");
        out.writeUTF(chest != null ? chest.name() : "AIR");
        out.writeUTF(legs != null ? legs.name() : "AIR");
        out.writeUTF(boots != null ? boots.name() : "AIR");
        out.writeByte(action.ordinal());
        out.writeInt(targetId);
        out.writeInt(slot);
        out.writeFloat(health);
        out.writeInt(food);
        out.writeFloat(saturation);
        out.writeFloat(fallDist);
        out.writeInt(fireTicks);
        out.writeInt(potionCount);
        out.writeDouble(damage);
    }

    public static Snapshot read(DataInputStream in) throws IOException {
        long timestamp = in.readLong();
        double x = in.readDouble();
        double y = in.readDouble();
        double z = in.readDouble();
        float yaw = in.readFloat();
        float pitch = in.readFloat();
        double velX = in.readDouble();
        double velY = in.readDouble();
        double velZ = in.readDouble();
        int flags = in.readByte();
        boolean sneaking = (flags & 1) != 0;
        boolean sprinting = (flags & 2) != 0;
        boolean blocking = (flags & 4) != 0;
        boolean onGround = (flags & 8) != 0;
        boolean swimming = (flags & 16) != 0;
        boolean gliding = (flags & 32) != 0;
        boolean jumping = (flags & 64) != 0;
        Material mainHand = parseMat(in.readUTF());
        Material offHand = parseMat(in.readUTF());
        Material helmet = parseMat(in.readUTF());
        Material chest = parseMat(in.readUTF());
        Material legs = parseMat(in.readUTF());
        Material boots = parseMat(in.readUTF());
        int actionOrd = in.readByte() & 0xFF;
        Action action = actionOrd < Action.values().length ? Action.values()[actionOrd] : Action.NONE;
        int targetId = in.readInt();
        int slot = in.readInt();
        float health = in.readFloat();
        int food = in.readInt();
        float saturation = in.readFloat();
        float fallDist = in.readFloat();
        int fireTicks = in.readInt();
        int potionCount = in.readInt();
        double damage = in.readDouble();

        return new Snapshot(timestamp, x, y, z, yaw, pitch, velX, velY, velZ,
                sneaking, sprinting, blocking, onGround, swimming, gliding, jumping,
                mainHand, offHand, helmet, chest, legs, boots,
                action, targetId, slot, health, food, saturation,
                fallDist, fireTicks, potionCount, damage);
    }

    private static Material parseMat(String name) {
        try {
            return Material.valueOf(name);
        } catch (IllegalArgumentException e) {
            Logger.getLogger("NoChance").log(Level.WARNING, "Unknown material in snapshot: " + name, e);
            return Material.AIR;
        }
    }

    public enum Action {
        NONE,
        SWING,
        ATTACK,
        BREAK_BLOCK,
        PLACE_BLOCK,
        USE_ITEM,
        DROP_ITEM,
        PICKUP_ITEM,
        BOW_SHOOT,
        CONSUME
    }
}
