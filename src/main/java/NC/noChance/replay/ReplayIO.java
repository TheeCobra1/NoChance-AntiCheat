package NC.noChance.replay;

import NC.noChance.core.ViolationType;
import org.bukkit.Material;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

public class ReplayIO {
    private static final int VERSION = 3;
    private static final byte[] MAGIC = {'N', 'C', 'R', 'P'};

    private final Path replayDir;

    public ReplayIO(Path dataFolder) {
        this.replayDir = dataFolder.resolve("replays");
        try {
            Files.createDirectories(replayDir);
        } catch (IOException e) {
            throw new RuntimeException("Failed to create replay directory: " + replayDir, e);
        }
    }

    public void save(ReplayData data) throws IOException {
        String id = Long.toString(data.getRecordTime() % 1000000, 36);
        String vt = shortViolation(data.getViolationType());
        String name = data.getPlayerName().length() > 6 ?
                data.getPlayerName().substring(0, 6) : data.getPlayerName();
        String filename = String.format("%s_%s_%s.ncrp", name, id, vt);
        Path file = replayDir.resolve(filename);

        try (DataOutputStream out = new DataOutputStream(
                new GZIPOutputStream(new FileOutputStream(file.toFile())))) {
            out.write(MAGIC);
            out.writeInt(VERSION);

            out.writeLong(data.getPlayerId().getMostSignificantBits());
            out.writeLong(data.getPlayerId().getLeastSignificantBits());
            out.writeUTF(data.getPlayerName());
            out.writeUTF(data.getWorld());
            out.writeUTF(data.getViolationType().name());
            out.writeUTF(data.getConfidence());
            out.writeLong(data.getRecordTime());

            out.writeInt(data.getSnapshots().size());
            for (Snapshot snap : data.getSnapshots()) {
                snap.write(out);
            }

            out.writeInt(data.getBlockActions().size());
            for (BlockAction ba : data.getBlockActions()) {
                ba.write(out);
            }
        }
    }

    private String shortViolation(ViolationType type) {
        switch (type) {
            case FLY: return "fly";
            case SPEED: return "spd";
            case NOCLIP: return "nclp";
            case JESUS: return "jes";
            case FASTBREAK: return "fbrk";
            case FASTPLACE: return "fplc";
            case NUKER: return "nuk";
            case KILLAURA: return "ka";
            case NOFALL: return "nf";
            case AUTOCLICKER: return "ac";
            case REACH: return "rch";
            case INVENTORY: return "inv";
            case SCAFFOLD: return "scf";
            case TIMER: return "tmr";
            case VELOCITY: return "vel";
            case CRITICALS: return "crt";
            case PHASE: return "phs";
            case STEP: return "stp";
            case BLINK: return "blk";
            case NOSLOW: return "nsl";

            case GROUNDSPOOF: return "gsp";
            case ELYTRAFLY: return "ely";
            case STRIDER: return "str";
            case BOATFLY: return "bfl";
            case SPIDER: return "spi";
            default: return type.name().substring(0, Math.min(3, type.name().length())).toLowerCase();
        }
    }

    public ReplayData load(String filename) throws IOException {
        if (filename == null || filename.isEmpty()) {
            throw new IOException("Invalid replay filename");
        }
        if (filename.contains("..") || filename.contains("/") || filename.contains("\\")
                || filename.contains(":") || filename.contains("~")) {
            throw new IOException("Invalid replay filename");
        }
        for (int i = 0; i < filename.length(); i++) {
            if (Character.isISOControl(filename.charAt(i))) {
                throw new IOException("Invalid replay filename");
            }
        }
        Path file = replayDir.resolve(filename);
        if (!Files.exists(file)) {
            throw new FileNotFoundException("Replay not found: " + filename);
        }

        try (DataInputStream in = new DataInputStream(
                new GZIPInputStream(new FileInputStream(file.toFile())))) {
            byte[] magic = new byte[4];
            in.readFully(magic);
            for (int i = 0; i < 4; i++) {
                if (magic[i] != MAGIC[i]) {
                    throw new IOException("Invalid replay file");
                }
            }

            int version = in.readInt();
            if (version > VERSION) {
                throw new IOException("Unsupported replay version: " + version);
            }

            long mostSig = in.readLong();
            long leastSig = in.readLong();
            UUID playerId = new UUID(mostSig, leastSig);
            String playerName = in.readUTF();
            String world = in.readUTF();
            String vtName = in.readUTF();
            ViolationType violationType;
            try {
                violationType = ViolationType.valueOf(vtName);
            } catch (IllegalArgumentException e) {
                throw new IOException("Unknown violation type: " + vtName);
            }
            String confidence = in.readUTF();
            long recordTime = in.readLong();

            int snapCount = in.readInt();
            List<Snapshot> snapshots = new ArrayList<>(snapCount);
            for (int i = 0; i < snapCount; i++) {
                if (version >= 3) {
                    snapshots.add(Snapshot.read(in));
                } else {
                    snapshots.add(readLegacySnapshot(in));
                }
            }

            List<BlockAction> blockActions = new ArrayList<>();
            if (version >= 2) {
                int blockCount = in.readInt();
                for (int i = 0; i < blockCount; i++) {
                    if (version >= 3) {
                        blockActions.add(BlockAction.read(in));
                    } else {
                        blockActions.add(readLegacyBlockAction(in));
                    }
                }
            }

            return new ReplayData(playerId, playerName, world, violationType, confidence, recordTime, snapshots, blockActions);
        }
    }

    private Snapshot readLegacySnapshot(DataInputStream in) throws IOException {
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
        String matName = in.readUTF();
        Material mainHand;
        try {
            mainHand = Material.valueOf(matName);
        } catch (IllegalArgumentException e) {
            Logger.getLogger("NoChance").log(Level.WARNING, "Unknown material in legacy snapshot: " + matName, e);
            mainHand = Material.AIR;
        }
        Snapshot.Action action = Snapshot.Action.values()[Math.max(0, Math.min(in.readByte() & 0xFF, Snapshot.Action.values().length - 1))];
        int targetId = in.readInt();
        int slot = in.readInt();
        float health = in.readFloat();

        return new Snapshot(timestamp, x, y, z, yaw, pitch, velX, velY, velZ,
                sneaking, sprinting, blocking, onGround, swimming, gliding, false,
                mainHand, Material.AIR, Material.AIR, Material.AIR, Material.AIR, Material.AIR,
                action, targetId, slot, health, 20, 5.0f, 0, 0, 0, 0);
    }

    private BlockAction readLegacyBlockAction(DataInputStream in) throws IOException {
        long timestamp = in.readLong();
        int x = in.readInt();
        int y = in.readInt();
        int z = in.readInt();
        String matName = in.readUTF();
        Material block;
        try {
            block = Material.valueOf(matName);
        } catch (IllegalArgumentException e) {
            Logger.getLogger("NoChance").log(Level.WARNING, "Unknown material in legacy block action: " + matName, e);
            block = Material.AIR;
        }
        BlockAction.Type type = BlockAction.Type.values()[in.readByte()];
        float progress = in.readFloat();
        return new BlockAction(timestamp, x, y, z, block, type, progress);
    }

    public List<String> listReplays(String playerName) {
        List<String> result = new ArrayList<>();
        File[] files = replayDir.toFile().listFiles((dir, name) ->
                name.endsWith(".ncrp") && (playerName == null || name.toLowerCase().startsWith(playerName.toLowerCase())));

        if (files != null) {
            for (File f : files) {
                result.add(f.getName());
            }
        }
        result.sort((a, b) -> b.compareTo(a));
        return result;
    }

    public void cleanup(long maxAgeMs) {
        long cutoff = System.currentTimeMillis() - maxAgeMs;
        File[] files = replayDir.toFile().listFiles((dir, name) -> name.endsWith(".ncrp"));

        if (files != null) {
            for (File f : files) {
                if (f.lastModified() < cutoff) {
                    f.delete();
                }
            }
        }
    }

    public Path getReplayDir() {
        return replayDir;
    }
}
