package NC.noChance.packets;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

public class PacketFingerprint {

    private static final int WINDOW_SIZE = 100;
    private static final long OBSERVATION_MS = 20_000L;
    private static final List<Signature> SIGNATURES = new ArrayList<>();
    private static final Logger LOG = Logger.getLogger("NoChance");

    private final Map<UUID, PlayerWindow> windows = new ConcurrentHashMap<>();
    private final Map<UUID, Long> joinTime = new ConcurrentHashMap<>();
    private final Set<UUID> observationLogged = ConcurrentHashMap.newKeySet();
    private final Map<UUID, SessionMatch> sessionMatches = new ConcurrentHashMap<>();
    private static final long SESSION_MATCH_TTL_MS = 300_000L;
    private static final double SESSION_MATCH_MIN_CONF = 0.85;

    static {
        SIGNATURES.add(new TightBurstSignature("wurst_aura",
                "Wurst", "perfect ANIMATION-INTERACT cadence",
                new String[]{"ANIMATION", "INTERACT_ENTITY"},
                30, 6, 0.82));

        SIGNATURES.add(new BurstSignature("generic_aura",
                "Generic Aura", "interact burst without rotation",
                "INTERACT_ENTITY",
                new String[]{"PLAYER_ROTATION", "PLAYER_POSITION_AND_ROTATION"},
                4, 200, 0.80));

        SIGNATURES.add(new FastTimingSignature("future_fly",
                "Future", "flying packets faster than tick rate",
                "PLAYER_FLYING", 25, 42.0, 0.12, 0.78));

        SIGNATURES.add(new RotSnapSignature("scaffold_snap",
                "Scaffold", "impossible placement cadence",
                "PLAYER_BLOCK_PLACEMENT", 6, 0.78));

        SIGNATURES.add(new FastTimingSignature("timer_fast",
                "Timer", "position packets faster than tick rate",
                "PLAYER_POSITION", 25, 42.0, 0.12, 0.84));

        SIGNATURES.add(new FastTimingSignature("autoclicker_rhythm",
                "AutoClicker", "animation packets with bot-like rhythm",
                "ANIMATION", 30, 42.0, 0.08, 0.78));

        SIGNATURES.add(new BlinkSignature("blink_burst",
                "Blink", "packet gap then burst",
                700, 12, 150, 0.80));

        SIGNATURES.add(new TightBurstSignature("wurst_speed_order",
                "Wurst", "position before position-look in same tick",
                new String[]{"PLAYER_POSITION", "PLAYER_POSITION_AND_ROTATION"},
                30, 5, 0.45));

        SIGNATURES.add(new FastTimingSignature("meteor_fly_zero",
                "Meteor Client", "fly packets at machine-like interval while airborne",
                "PLAYER_FLYING", 20, 50.0, 0.04, 0.55));

        SIGNATURES.add(new TightBurstSignature("meteor_autocrystal",
                "Meteor Client", "USE_ITEM then SWING_ARM within 1ms",
                new String[]{"USE_ITEM", "ANIMATION"},
                2, 6, 0.55));

        SIGNATURES.add(new BlinkSignature("future_blink_burst",
                "Future Client", "large queued packet burst after gap",
                600, 20, 200, 0.60));

        SIGNATURES.add(new RotSnapSignature("aristois_scaffold",
                "Aristois", "rapid scaffold placement packets",
                "PLAYER_BLOCK_PLACEMENT", 5, 0.50));

        SIGNATURES.add(new FastTimingSignature("aristois_speed",
                "Aristois", "sprint position packets with consistent low interval",
                "PLAYER_POSITION", 20, 48.0, 0.06, 0.50));

        SIGNATURES.add(new FastTimingSignature("vape_autoclicker",
                "Vape", "animation clicks with near-zero timing variance",
                "ANIMATION", 25, 50.0, 0.02, 0.65));

        SIGNATURES.add(new BurstSignature("inertia_speed_dupe",
                "Inertia", "duplicate position packets without rotation",
                "PLAYER_POSITION",
                new String[]{"PLAYER_ROTATION", "PLAYER_POSITION_AND_ROTATION"},
                5, 250, 0.50));

        SIGNATURES.add(new RotSnapSignature("slinky_scaffold",
                "Slinky", "block placement packets out of sequence",
                "PLAYER_BLOCK_PLACEMENT", 4, 0.30));

        SIGNATURES.add(new FastTimingSignature("sigma_fly",
                "Sigma", "spoofed vanilla brand with low-variance flying interval",
                "PLAYER_FLYING", 25, 48.0, 0.05, 0.35));

        SIGNATURES.add(new TightBurstSignature("liquid_clipper_aura",
                "Liquid Clipper", "forge toolkit interact-animation pair",
                new String[]{"INTERACT_ENTITY", "ANIMATION"},
                30, 6, 0.0));

        SIGNATURES.add(new BurstSignature("verus_speed",
                "Verus", "spoofed brand position burst without rotation",
                "PLAYER_POSITION",
                new String[]{"PLAYER_ROTATION", "PLAYER_POSITION_AND_ROTATION"},
                5, 220, 0.0));

        SIGNATURES.add(new RotSnapSignature("wonyong_scaffold",
                "Wonyong", "placement cadence anomaly",
                "PLAYER_BLOCK_PLACEMENT", 5, 0.0));

        SIGNATURES.add(new TightBurstSignature("augustus_aura",
                "Augustus", "forge aura interact-animation pairing",
                new String[]{"ANIMATION", "INTERACT_ENTITY"},
                35, 5, 0.30));

        SIGNATURES.add(new FastTimingSignature("tip_speed",
                "Tip", "forge speed position interval",
                "PLAYER_POSITION", 20, 47.0, 0.07, 0.30));

        SIGNATURES.add(new FastTimingSignature("cloudflare_autoclick",
                "Cloudflare", "animation autoclicker rhythm",
                "ANIMATION", 25, 50.0, 0.05, 0.20));

        SIGNATURES.add(new TightBurstSignature("mcc_aura",
                "MCC", "interact-animation cadence",
                new String[]{"INTERACT_ENTITY", "ANIMATION"},
                30, 6, 0.0));

        SIGNATURES.add(new BurstSignature("foxclient_speed",
                "FoxClient", "position burst without rotation",
                "PLAYER_POSITION",
                new String[]{"PLAYER_ROTATION", "PLAYER_POSITION_AND_ROTATION"},
                5, 230, 0.30));

        SIGNATURES.add(new TightBurstSignature("rusherhack_aura",
                "Rusherhack", "killaura swing-interact ordering",
                new String[]{"ANIMATION", "INTERACT_ENTITY"},
                30, 6, 0.40));

        SIGNATURES.add(new RotSnapSignature("akrien_scaffold",
                "Akrien", "scaffold placement cadence",
                "PLAYER_BLOCK_PLACEMENT", 5, 0.0));

        SIGNATURES.add(new FastTimingSignature("bleachhack_fly",
                "BleachHack", "fabric flying packet interval",
                "PLAYER_FLYING", 22, 49.0, 0.05, 0.30));

        SIGNATURES.add(new BlinkSignature("drip_blink",
                "Drip", "packet gap then position burst",
                650, 14, 180, 0.0));

        SIGNATURES.add(new FastTimingSignature("konas_speed",
                "Konas", "forge position interval",
                "PLAYER_POSITION", 20, 48.0, 0.07, 0.20));

        SIGNATURES.add(new TightBurstSignature("salhack_aura",
                "Salhack", "forge interact-swing pairing",
                new String[]{"INTERACT_ENTITY", "ANIMATION"},
                35, 5, 0.20));

        SIGNATURES.add(new TightBurstSignature("impact_aura",
                "Impact", "killaura swing-interact rhythm",
                new String[]{"ANIMATION", "INTERACT_ENTITY"},
                30, 6, 0.40));

        SIGNATURES.add(new RotSnapSignature("reach_client_reach",
                "Reach Client", "rapid interact placement cadence",
                "PLAYER_BLOCK_PLACEMENT", 5, 0.30));
    }

    public void record(UUID player, String packetType, long timestamp) {
        windows.computeIfAbsent(player, k -> new PlayerWindow())
                .add(new PacketEntry(packetType, timestamp));
    }

    public FingerprintResult analyze(UUID player) {
        return analyze(player, 50);
    }

    public FingerprintResult analyze(UUID player, int ping) {
        PlayerWindow window = windows.get(player);
        if (window == null || window.size() < 20) {
            return FingerprintResult.CLEAN;
        }

        List<PacketEntry> entries = window.snapshot();
        FingerprintResult best = FingerprintResult.CLEAN;

        for (Signature sig : SIGNATURES) {
            FingerprintResult result = sig.check(entries, ping);
            if (result.matched && result.confidence > best.confidence) {
                best = result;
            }
        }

        long t = joinTime.getOrDefault(player, 0L);
        if (t == 0L || System.currentTimeMillis() - t < OBSERVATION_MS) {
            return FingerprintResult.CLEAN;
        }

        if (observationLogged.add(player)) {
            String name = player.toString();
            LOG.fine("fingerprint observation complete: " + name);
        }

        if (best.matched && best.confidence >= SESSION_MATCH_MIN_CONF) {
            sessionMatches.put(player, new SessionMatch(best.clientHint, best.confidence, System.currentTimeMillis()));
        }

        return best;
    }

    public String getRecentClient(UUID player) {
        SessionMatch m = sessionMatches.get(player);
        if (m == null) return null;
        if (System.currentTimeMillis() - m.timestamp > SESSION_MATCH_TTL_MS) {
            sessionMatches.remove(player);
            return null;
        }
        return m.clientHint;
    }

    public void noteJoin(UUID player) {
        joinTime.put(player, System.currentTimeMillis());
        observationLogged.remove(player);
    }

    public void cleanup(UUID player) {
        windows.remove(player);
        joinTime.remove(player);
        observationLogged.remove(player);
        sessionMatches.remove(player);
    }

    private static class SessionMatch {
        final String clientHint;
        final double confidence;
        final long timestamp;

        SessionMatch(String clientHint, double confidence, long timestamp) {
            this.clientHint = clientHint;
            this.confidence = confidence;
            this.timestamp = timestamp;
        }
    }

    public static class FingerprintResult {
        public static final FingerprintResult CLEAN = new FingerprintResult(false, 0.0, "", "");

        public final boolean matched;
        public final double confidence;
        public final String clientHint;
        public final String pattern;

        public FingerprintResult(boolean matched, double confidence, String clientHint, String pattern) {
            this.matched = matched;
            this.confidence = confidence;
            this.clientHint = clientHint;
            this.pattern = pattern;
        }
    }

    static class PacketEntry {
        final String type;
        final long time;

        PacketEntry(String type, long time) {
            this.type = type;
            this.time = time;
        }
    }

    static class PlayerWindow {
        private final ArrayDeque<PacketEntry> entries = new ArrayDeque<>(WINDOW_SIZE + 1);

        synchronized void add(PacketEntry entry) {
            if (entries.size() >= WINDOW_SIZE) {
                entries.pollFirst();
            }
            entries.addLast(entry);
        }

        synchronized int size() {
            return entries.size();
        }

        synchronized List<PacketEntry> snapshot() {
            return new ArrayList<>(entries);
        }
    }

    static abstract class Signature {
        final String id;
        final String client;
        final String desc;

        Signature(String id, String client, String desc) {
            this.id = id;
            this.client = client;
            this.desc = desc;
        }

        abstract FingerprintResult check(List<PacketEntry> entries, int ping);

        FingerprintResult hit(double confidence) {
            return new FingerprintResult(true, Math.min(1.0, confidence), client, id);
        }

        static double pingTolerance(int ping) {
            if (ping <= 80) return 0.0;
            if (ping >= 300) return 12.0;
            return (ping - 80) * 0.06;
        }
    }

    static class TightBurstSignature extends Signature {
        final String[] seq;
        final long maxPairGapMs;
        final int minPairs;
        final double baseConf;

        TightBurstSignature(String id, String client, String desc, String[] seq,
                             long maxPairGapMs, int minPairs, double baseConf) {
            super(id, client, desc);
            this.seq = seq;
            this.maxPairGapMs = maxPairGapMs;
            this.minPairs = minPairs;
            this.baseConf = baseConf;
        }

        @Override
        FingerprintResult check(List<PacketEntry> entries, int ping) {
            long adjustedGap = maxPairGapMs + (long) pingTolerance(ping);
            List<Long> pairStarts = new ArrayList<>();
            long lastPairEnd = -1;
            for (int i = 0; i < entries.size() - 1; i++) {
                if (!entries.get(i).type.equals(seq[0])) continue;
                if (!entries.get(i + 1).type.equals(seq[1])) continue;
                long pairTime = entries.get(i + 1).time - entries.get(i).time;
                if (pairTime > adjustedGap || pairTime < 0) continue;
                if (lastPairEnd < 0 || entries.get(i).time - lastPairEnd < 150) {
                    pairStarts.add(entries.get(i).time);
                    lastPairEnd = entries.get(i + 1).time;
                }
            }
            if (pairStarts.size() < minPairs) return FingerprintResult.CLEAN;

            List<Long> gaps = new ArrayList<>();
            for (int i = 1; i < pairStarts.size(); i++) {
                gaps.add(pairStarts.get(i) - pairStarts.get(i - 1));
            }
            double mean = gaps.stream().mapToLong(Long::longValue).average().orElse(0);
            if (mean <= 0) return FingerprintResult.CLEAN;

            double variance = 0;
            for (Long g : gaps) {
                double d = g - mean;
                variance += d * d;
            }
            variance /= gaps.size();
            double stdDev = Math.sqrt(variance);
            double cv = stdDev / mean;

            if (cv > 0.18) return FingerprintResult.CLEAN;

            double scale = Math.min(1.0, (double) pairStarts.size() / (minPairs * 2));
            double rhythmBoost = (0.18 - cv) / 0.18;
            return hit(baseConf + (1.0 - baseConf) * (scale * 0.25 + rhythmBoost * 0.2));
        }
    }

    static class BurstSignature extends Signature {
        final String burstType;
        final String[] absentTypes;
        final int minBurst;
        final long windowMs;
        final double baseConf;

        BurstSignature(String id, String client, String desc, String burstType, String[] absentTypes,
                        int minBurst, long windowMs, double baseConf) {
            super(id, client, desc);
            this.burstType = burstType;
            this.absentTypes = absentTypes;
            this.minBurst = minBurst;
            this.windowMs = windowMs;
            this.baseConf = baseConf;
        }

        @Override
        FingerprintResult check(List<PacketEntry> entries, int ping) {
            long adjustedWindow = windowMs + (long) (pingTolerance(ping) * 2);
            int burstCount = 0;
            boolean hasAbsent = false;
            long burstStart = -1;

            for (PacketEntry e : entries) {
                if (e.type.equals(burstType)) {
                    if (burstStart == -1 || e.time - burstStart > adjustedWindow) {
                        if (burstCount >= minBurst && !hasAbsent) {
                            return hit(baseConf);
                        }
                        burstCount = 1;
                        burstStart = e.time;
                        hasAbsent = false;
                    } else {
                        burstCount++;
                    }
                } else if (isAbsent(e.type)) {
                    if (burstStart != -1 && e.time - burstStart <= adjustedWindow) {
                        hasAbsent = true;
                    }
                }
            }

            if (burstCount >= minBurst && !hasAbsent) {
                return hit(baseConf);
            }
            return FingerprintResult.CLEAN;
        }

        private boolean isAbsent(String type) {
            for (String t : absentTypes) {
                if (type.equals(t)) return true;
            }
            return false;
        }
    }

    static class FastTimingSignature extends Signature {
        final String packetType;
        final int minSamples;
        final double maxMeanMs;
        final double maxCv;
        final double baseConf;

        FastTimingSignature(String id, String client, String desc, String packetType,
                            int minSamples, double maxMeanMs, double maxCv, double baseConf) {
            super(id, client, desc);
            this.packetType = packetType;
            this.minSamples = minSamples;
            this.maxMeanMs = maxMeanMs;
            this.maxCv = maxCv;
            this.baseConf = baseConf;
        }

        @Override
        FingerprintResult check(List<PacketEntry> entries, int ping) {
            List<Long> intervals = new ArrayList<>();
            long prev = -1;

            for (PacketEntry e : entries) {
                if (e.type.equals(packetType)) {
                    if (prev >= 0) {
                        intervals.add(e.time - prev);
                    }
                    prev = e.time;
                }
            }

            if (intervals.size() < minSamples) {
                return FingerprintResult.CLEAN;
            }

            List<Long> recent = intervals.subList(
                    Math.max(0, intervals.size() - minSamples), intervals.size());

            double mean = recent.stream().mapToLong(Long::longValue).average().orElse(50);
            double adjustedMean = maxMeanMs + pingTolerance(ping);
            if (mean >= adjustedMean || mean < 1.0) return FingerprintResult.CLEAN;

            double variance = 0.0;
            for (Long v : recent) {
                double d = v - mean;
                variance += d * d;
            }
            variance /= recent.size();
            double stdDev = Math.sqrt(variance);
            double cv = mean > 0 ? stdDev / mean : 0.0;

            if (cv > maxCv) {
                return FingerprintResult.CLEAN;
            }

            double speedRatio = adjustedMean / mean;
            double cvBoost = (maxCv - cv) / Math.max(maxCv, 0.001);
            return hit(baseConf + (1.0 - baseConf) * Math.min(1.0, (speedRatio - 1.0)) * 0.3
                    + (1.0 - baseConf) * cvBoost * 0.15);
        }
    }

    static class RotSnapSignature extends Signature {
        final String packetType;
        final int minSnaps;
        final double baseConf;

        RotSnapSignature(String id, String client, String desc, String packetType,
                          int minSnaps, double baseConf) {
            super(id, client, desc);
            this.packetType = packetType;
            this.minSnaps = minSnaps;
            this.baseConf = baseConf;
        }

        @Override
        FingerprintResult check(List<PacketEntry> entries, int ping) {
            long threshold = 50L + (long) pingTolerance(ping);
            int snaps = 0;
            long prevTime = -1;

            for (PacketEntry e : entries) {
                if (e.type.equals(packetType)) {
                    if (prevTime >= 0) {
                        long delta = e.time - prevTime;
                        if (delta < threshold) {
                            snaps++;
                        }
                    }
                    prevTime = e.time;
                }
            }

            if (snaps >= minSnaps) {
                double scale = Math.min(1.0, (double) snaps / (minSnaps * 2));
                return hit(baseConf + (1.0 - baseConf) * scale * 0.35);
            }
            return FingerprintResult.CLEAN;
        }
    }

    static class BlinkSignature extends Signature {
        final long gapMs;
        final int burstMin;
        final long burstWindowMs;
        final double baseConf;

        BlinkSignature(String id, String client, String desc, long gapMs,
                        int burstMin, long burstWindowMs, double baseConf) {
            super(id, client, desc);
            this.gapMs = gapMs;
            this.burstMin = burstMin;
            this.burstWindowMs = burstWindowMs;
            this.baseConf = baseConf;
        }

        @Override
        FingerprintResult check(List<PacketEntry> entries, int ping) {
            long adjustedGap = gapMs + (long) (pingTolerance(ping) * 6);
            for (int i = 1; i < entries.size(); i++) {
                long gap = entries.get(i).time - entries.get(i - 1).time;
                if (gap >= adjustedGap) {
                    int burst = 0;
                    long burstStart = entries.get(i).time;
                    double minInterval = Double.MAX_VALUE;
                    long lastBurstTime = burstStart;
                    for (int j = i; j < entries.size(); j++) {
                        if (entries.get(j).time - burstStart > burstWindowMs) break;
                        if (entries.get(j).type.contains("PLAYER_POSITION")) {
                            burst++;
                            long interval = entries.get(j).time - lastBurstTime;
                            if (interval > 0 && interval < minInterval) minInterval = interval;
                            lastBurstTime = entries.get(j).time;
                        }
                    }
                    if (burst >= burstMin && minInterval < 20) {
                        return hit(baseConf);
                    }
                }
            }
            return FingerprintResult.CLEAN;
        }
    }
}
