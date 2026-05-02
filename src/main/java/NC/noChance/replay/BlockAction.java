package NC.noChance.replay;

import org.bukkit.Material;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;

public class BlockAction {
    public final long timestamp;
    public final int x;
    public final int y;
    public final int z;
    public final Material block;
    public final Type type;
    public final float progress;
    public final Material tool;
    public final int breakTimeMs;
    public final int expectedTimeMs;

    public BlockAction(long timestamp, int x, int y, int z, Material block, Type type,
                       float progress, Material tool, int breakTimeMs, int expectedTimeMs) {
        this.timestamp = timestamp;
        this.x = x;
        this.y = y;
        this.z = z;
        this.block = block;
        this.type = type;
        this.progress = progress;
        this.tool = tool;
        this.breakTimeMs = breakTimeMs;
        this.expectedTimeMs = expectedTimeMs;
    }

    public BlockAction(long timestamp, int x, int y, int z, Material block, Type type, float progress) {
        this(timestamp, x, y, z, block, type, progress, Material.AIR, 0, 0);
    }

    public void write(DataOutputStream out) throws IOException {
        out.writeLong(timestamp);
        out.writeInt(x);
        out.writeInt(y);
        out.writeInt(z);
        out.writeUTF(block != null ? block.name() : "AIR");
        out.writeByte(type.ordinal());
        out.writeFloat(progress);
        out.writeUTF(tool != null ? tool.name() : "AIR");
        out.writeInt(breakTimeMs);
        out.writeInt(expectedTimeMs);
    }

    public static BlockAction read(DataInputStream in) throws IOException {
        long timestamp = in.readLong();
        int x = in.readInt();
        int y = in.readInt();
        int z = in.readInt();
        Material block = parseMat(in.readUTF());
        int typeOrd = in.readByte() & 0xFF;
        Type type = typeOrd < Type.values().length ? Type.values()[typeOrd] : Type.BREAK;
        float progress = in.readFloat();
        Material tool = parseMat(in.readUTF());
        int breakTimeMs = in.readInt();
        int expectedTimeMs = in.readInt();
        return new BlockAction(timestamp, x, y, z, block, type, progress, tool, breakTimeMs, expectedTimeMs);
    }

    private static Material parseMat(String name) {
        try {
            return Material.valueOf(name);
        } catch (IllegalArgumentException e) {
            Logger.getLogger("NoChance").log(Level.WARNING, "Unknown material in replay block action: " + name, e);
            return Material.AIR;
        }
    }

    public enum Type {
        START_BREAK,
        BREAKING,
        BREAK,
        PLACE,
        CANCEL
    }
}
