package NC.noChance.sim;

import NC.noChance.core.CheckResult;
import NC.noChance.core.ViolationType;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class SimBridge {

    private final SimEngine engine;
    private final Map<UUID, Deque<Double>> devHistory = new ConcurrentHashMap<>();
    private final Map<UUID, double[]> lastPredicted = new ConcurrentHashMap<>();
    private final Map<UUID, Long> teleportGrace = new ConcurrentHashMap<>();
    private final Map<UUID, Long> vehicleGrace = new ConcurrentHashMap<>();
    private final Map<UUID, Long> elytraGrace = new ConcurrentHashMap<>();
    private final Map<UUID, String> lastWorld = new ConcurrentHashMap<>();

    private static final int MAX_HISTORY = 20;
    private static final long GRACE_MS = 1500;

    public SimBridge(SimEngine engine) {
        this.engine = engine;
    }

    public SimResult process(Player player, Location from, Location to) {
        UUID id = player.getUniqueId();

        if (isInGrace(id)) {
            return SimResult.clean();
        }

        String world = player.getWorld().getName();
        String prev = lastWorld.put(id, world);
        if (prev != null && !prev.equals(world)) {
            markTeleport(id);
            engine.resetPlayer(id);
            return SimResult.clean();
        }

        CheckResult cr = engine.processMovement(player, from, to);
        SimPlayer sp = engine.getSimPlayer(id);
        double[] pred = engine.getPredictedPosition(id);

        if (sp == null || pred == null) {
            return SimResult.clean();
        }

        double actualX = to.getX();
        double actualY = to.getY();
        double actualZ = to.getZ();

        double dxH = actualX - pred[0];
        double dzH = actualZ - pred[2];
        double hDev = Math.sqrt(dxH * dxH + dzH * dzH);
        double vDev = Math.abs(actualY - pred[1]);
        double combined = Math.sqrt(hDev * hDev + vDev * vDev);

        lastPredicted.put(id, pred.clone());
        recordDev(id, combined);

        double ewma = sp.getEwmaDev();
        int consec = sp.getConsecutiveDev();

        boolean shouldFlag = cr.isFailed();
        ViolationType suggested = cr.getViolationType();
        String detail = cr.getDetails();

        if (suggested == null && shouldFlag) {
            suggested = ViolationType.FLY;
        }

        double scaledSeverity = cr.getSeverity();
        if (shouldFlag && scaledSeverity < 0.55) {
            scaledSeverity = Math.min(0.95, 0.55 + Math.min(0.30, ewma * 1.8) + Math.min(0.10, consec * 0.004));
        }

        if (!shouldFlag) {
            detail = "hd=" + fmt(hDev) + " vd=" + fmt(vDev) + " ewma=" + fmt(ewma);
        }

        return new SimResult(
                hDev, vDev, combined,
                pred[0], pred[1], pred[2],
                actualX, actualY, actualZ,
                ewma, consec,
                shouldFlag, suggested, detail,
                scaledSeverity
        );
    }

    public double[] getPredictedPosition(UUID id) {
        return lastPredicted.get(id);
    }

    public List<Double> getDeviationHistory(UUID id) {
        Deque<Double> deque = devHistory.get(id);
        if (deque == null) {
            return Collections.emptyList();
        }
        synchronized (deque) {
            return new ArrayList<>(deque);
        }
    }

    public void markTeleport(UUID id) {
        teleportGrace.put(id, System.currentTimeMillis());
        engine.resetPlayer(id);
        clearDev(id);
    }

    public void markVehicleEnter(UUID id) {
        vehicleGrace.put(id, System.currentTimeMillis());
        engine.resetPlayer(id);
        clearDev(id);
    }

    public void markVehicleExit(UUID id) {
        vehicleGrace.put(id, System.currentTimeMillis());
        engine.resetPlayer(id);
        clearDev(id);
    }

    public void markElytraTransition(UUID id) {
        elytraGrace.put(id, System.currentTimeMillis());
        engine.resetPlayer(id);
        clearDev(id);
    }

    public void markDimensionChange(UUID id) {
        lastWorld.remove(id);
        engine.resetPlayer(id);
        clearDev(id);
    }

    public void cleanup(UUID id) {
        devHistory.remove(id);
        lastPredicted.remove(id);
        teleportGrace.remove(id);
        vehicleGrace.remove(id);
        elytraGrace.remove(id);
        lastWorld.remove(id);
        engine.resetPlayer(id);
    }

    public void cleanupStale() {
        engine.cleanup();
        long now = System.currentTimeMillis();
        long expiry = 120_000;
        teleportGrace.entrySet().removeIf(e -> now - e.getValue() > expiry);
        vehicleGrace.entrySet().removeIf(e -> now - e.getValue() > expiry);
        elytraGrace.entrySet().removeIf(e -> now - e.getValue() > expiry);
    }

    private boolean isInGrace(UUID id) {
        long now = System.currentTimeMillis();
        Long tp = teleportGrace.get(id);
        if (tp != null && now - tp < GRACE_MS) return true;
        Long ve = vehicleGrace.get(id);
        if (ve != null && now - ve < GRACE_MS) return true;
        Long el = elytraGrace.get(id);
        if (el != null && now - el < GRACE_MS) return true;
        return false;
    }

    private void recordDev(UUID id, double val) {
        Deque<Double> deque = devHistory.computeIfAbsent(id, k -> new ArrayDeque<>());
        synchronized (deque) {
            deque.addLast(val);
            while (deque.size() > MAX_HISTORY) {
                deque.removeFirst();
            }
        }
    }

    private void clearDev(UUID id) {
        Deque<Double> deque = devHistory.get(id);
        if (deque != null) {
            synchronized (deque) {
                deque.clear();
            }
        }
        lastPredicted.remove(id);
    }

    private static String fmt(double v) {
        return String.format("%.4f", v);
    }

    public static class SimResult {

        public final double hDeviation;
        public final double vDeviation;
        public final double combined;
        public final double predictedX;
        public final double predictedY;
        public final double predictedZ;
        public final double actualX;
        public final double actualY;
        public final double actualZ;
        public final double ewmaDeviation;
        public final int consecutiveFlags;
        public final boolean shouldFlag;
        public final ViolationType suggestedType;
        public final String detail;
        public final double severity;

        SimResult(double hDev, double vDev, double combined,
                  double predX, double predY, double predZ,
                  double actX, double actY, double actZ,
                  double ewma, int consec,
                  boolean shouldFlag, ViolationType type, String detail,
                  double severity) {
            this.hDeviation = hDev;
            this.vDeviation = vDev;
            this.combined = combined;
            this.predictedX = predX;
            this.predictedY = predY;
            this.predictedZ = predZ;
            this.actualX = actX;
            this.actualY = actY;
            this.actualZ = actZ;
            this.ewmaDeviation = ewma;
            this.consecutiveFlags = consec;
            this.shouldFlag = shouldFlag;
            this.suggestedType = type;
            this.detail = detail;
            this.severity = severity;
        }

        static SimResult clean() {
            return new SimResult(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, false, null, "", 0);
        }
    }
}
