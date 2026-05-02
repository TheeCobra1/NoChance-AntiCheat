package NC.noChance.core;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class MovementGrace {
    public enum Cause {
        ENDERPEARL, CHORUS_FRUIT, PORTAL, COMMAND, PLUGIN, RESPAWN, VEHICLE_DISMOUNT, OTHER
    }

    private static final long TELEPORT_GRACE_MS = 1500L;
    private static final long VELOCITY_GRACE_MS = 1000L;
    private static final long MOUNT_GRACE_MS = 800L;

    private static final int IDX_TELEPORT = 0;
    private static final int IDX_VELOCITY = 1;
    private static final int IDX_MOUNT = 2;

    private final Map<UUID, long[]> stamps = new ConcurrentHashMap<>();
    private final Map<UUID, Cause> lastCause = new ConcurrentHashMap<>();

    public void onTeleport(UUID id, long ts, Cause cause) {
        if (id == null) return;
        long[] arr = stamps.computeIfAbsent(id, k -> new long[3]);
        arr[IDX_TELEPORT] = ts;
        if (cause != null) lastCause.put(id, cause);
    }

    public void onVelocity(UUID id, long ts) {
        if (id == null) return;
        long[] arr = stamps.computeIfAbsent(id, k -> new long[3]);
        arr[IDX_VELOCITY] = ts;
    }

    public void onMountChange(UUID id, long ts) {
        if (id == null) return;
        long[] arr = stamps.computeIfAbsent(id, k -> new long[3]);
        arr[IDX_MOUNT] = ts;
    }

    public boolean inTeleportGrace(UUID id, long now) {
        long[] arr = stamps.get(id);
        if (arr == null) return false;
        long ts = arr[IDX_TELEPORT];
        return ts > 0 && (now - ts) < TELEPORT_GRACE_MS;
    }

    public boolean inVelocityGrace(UUID id, long now) {
        long[] arr = stamps.get(id);
        if (arr == null) return false;
        long ts = arr[IDX_VELOCITY];
        return ts > 0 && (now - ts) < VELOCITY_GRACE_MS;
    }

    public boolean inMountGrace(UUID id, long now) {
        long[] arr = stamps.get(id);
        if (arr == null) return false;
        long ts = arr[IDX_MOUNT];
        return ts > 0 && (now - ts) < MOUNT_GRACE_MS;
    }

    public Cause lastCause(UUID id) {
        return lastCause.get(id);
    }

    public void cleanup(UUID id) {
        stamps.remove(id);
        lastCause.remove(id);
    }

    public Map<UUID, long[]> getStampMap() {
        return stamps;
    }
}
