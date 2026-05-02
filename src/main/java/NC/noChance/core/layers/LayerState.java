package NC.noChance.core.layers;

import NC.noChance.core.ViolationType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class LayerState {
    private final Map<ViolationType, List<SeverityRecord>> severityHistory;
    private final Map<ViolationType, Long> layerBPassTime;
    private final Map<ViolationType, Long> layerCPassTime;

    public LayerState() {
        this.severityHistory = new ConcurrentHashMap<>();
        this.layerBPassTime = new ConcurrentHashMap<>();
        this.layerCPassTime = new ConcurrentHashMap<>();
    }

    public synchronized void recordLayerA(ViolationType type, double severity) {
        severityHistory.computeIfAbsent(type, k -> Collections.synchronizedList(new ArrayList<>()))
                .add(new SeverityRecord(severity, System.currentTimeMillis()));
    }

    public void recordLayerB(ViolationType type, double avgSeverity) {
        layerBPassTime.put(type, System.currentTimeMillis());
    }

    public void recordLayerC(ViolationType type) {
        layerCPassTime.put(type, System.currentTimeMillis());
    }

    public synchronized List<Double> getRecentSeverities(ViolationType type, long timeWindow) {
        long cutoff = System.currentTimeMillis() - timeWindow;
        List<SeverityRecord> records = severityHistory.getOrDefault(type, Collections.emptyList());
        List<Double> recent = new ArrayList<>();
        for (SeverityRecord record : records) {
            if (record.timestamp > cutoff) {
                recent.add(record.severity);
            }
        }
        return recent;
    }

    public synchronized void resetLayer(ViolationType type) {
        severityHistory.remove(type);
        layerBPassTime.remove(type);
        layerCPassTime.remove(type);
    }

    public synchronized void cleanupOld(long now, long maxAge) {
        for (List<SeverityRecord> records : severityHistory.values()) {
            records.removeIf(r -> now - r.timestamp > maxAge);
        }
    }

    private static class SeverityRecord {
        final double severity;
        final long timestamp;

        SeverityRecord(double severity, long timestamp) {
            this.severity = severity;
            this.timestamp = timestamp;
        }
    }
}
