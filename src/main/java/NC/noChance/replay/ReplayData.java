package NC.noChance.replay;

import NC.noChance.core.ViolationType;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class ReplayData {
    private final UUID playerId;
    private final String playerName;
    private final String world;
    private final ViolationType violationType;
    private final String confidence;
    private final long recordTime;
    private final List<Snapshot> snapshots;
    private final List<BlockAction> blockActions;

    public ReplayData(UUID playerId, String playerName, String world, ViolationType violationType,
                      String confidence, long recordTime, List<Snapshot> snapshots, List<BlockAction> blockActions) {
        this.playerId = playerId;
        this.playerName = playerName;
        this.world = world;
        this.violationType = violationType;
        this.confidence = confidence;
        this.recordTime = recordTime;
        this.snapshots = snapshots;
        this.blockActions = blockActions != null ? blockActions : new ArrayList<>();
    }

    public UUID getPlayerId() {
        return playerId;
    }

    public String getPlayerName() {
        return playerName;
    }

    public String getWorld() {
        return world;
    }

    public ViolationType getViolationType() {
        return violationType;
    }

    public String getConfidence() {
        return confidence;
    }

    public long getRecordTime() {
        return recordTime;
    }

    public List<Snapshot> getSnapshots() {
        return snapshots;
    }

    public List<BlockAction> getBlockActions() {
        return blockActions;
    }

    public long getDuration() {
        if (snapshots.isEmpty()) return 0;
        return snapshots.get(snapshots.size() - 1).timestamp - snapshots.get(0).timestamp;
    }
}
