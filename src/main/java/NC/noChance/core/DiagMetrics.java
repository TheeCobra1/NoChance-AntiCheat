package NC.noChance.core;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public class DiagMetrics {
    private static final int WINDOW_SECONDS = 60;

    private final ConcurrentHashMap<String, Bucket[]> buckets = new ConcurrentHashMap<>();

    public void record(String checkName) {
        if (checkName == null) return;
        Bucket[] arr = buckets.computeIfAbsent(checkName, k -> {
            Bucket[] b = new Bucket[WINDOW_SECONDS];
            for (int i = 0; i < WINDOW_SECONDS; i++) b[i] = new Bucket();
            return b;
        });
        long sec = System.currentTimeMillis() / 1000L;
        int idx = (int) (sec % WINDOW_SECONDS);
        Bucket b = arr[idx];
        long stamp = b.stamp.get();
        if (stamp != sec) {
            if (b.stamp.compareAndSet(stamp, sec)) {
                b.count.set(0);
            }
        }
        b.count.incrementAndGet();
    }

    public Map<String, Long> snapshotLast60s() {
        long now = System.currentTimeMillis() / 1000L;
        long cutoff = now - WINDOW_SECONDS + 1;
        Map<String, Long> out = new LinkedHashMap<>();
        for (Map.Entry<String, Bucket[]> e : buckets.entrySet()) {
            long total = 0;
            for (Bucket b : e.getValue()) {
                long stamp = b.stamp.get();
                if (stamp >= cutoff && stamp <= now) {
                    total += b.count.get();
                }
            }
            if (total > 0) out.put(e.getKey(), total);
        }
        return out;
    }

    public int getTrackedCheckCount() {
        return buckets.size();
    }

    private static final class Bucket {
        final AtomicLong stamp = new AtomicLong(-1L);
        final AtomicLong count = new AtomicLong(0L);
    }
}
