package NC.noChance.detection.combat;

import NC.noChance.core.*;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class AutoClickerCheck {
    private final ACConfig config;
    private final Map<UUID, PlayerData> playerDataMap;
    private final LayerFiltering filtering;
    private final TimingStats timingAnalyzer;
    private final Map<UUID, ClickProfile> clickProfiles;

    private static final int MIN_SAMPLES = 20;
    private static final double BUTTERFLY_MAX_CPS = 25.0;
    private static final double DRAG_MAX_CPS = 35.0;
    private static final double JITTER_MIN_CPS = 8.0;
    private static final double JITTER_MAX_CPS = 18.0;
    private static final double ENTROPY_BOT_THRESHOLD = 1.2;
    private static final double ENTROPY_LOW_THRESHOLD = 1.8;
    private static final double CV_BOT_THRESHOLD = 0.055;
    private static final double CV_MACRO_THRESHOLD = 0.08;
    private static final int INTERVAL_WINDOW = 40;
    private static final long BUCKET_SIZE_MS = 10;
    private static final double SHANNON_ENTROPY_THRESHOLD = 2.8;
    private static final int RING_CAPACITY = 128;
    private static final int CORR_WINDOW = 64;
    private static final int CORR_MIN_SAMPLES = 32;
    private static final int CORR_LAG_MIN = 1;
    private static final int CORR_LAG_MAX = 16;
    private static final double CORR_THRESHOLD = 0.65;
    private static final double KURTOSIS_THRESHOLD = 1.0;
    private static final double CV_HUMANIZER_LOW = 0.05;
    private static final double CV_HUMANIZER_HIGH = 0.20;

    public AutoClickerCheck(ACConfig config, Map<UUID, PlayerData> playerDataMap, LayerFiltering filtering) {
        this.config = config;
        this.playerDataMap = playerDataMap;
        this.filtering = filtering;
        this.timingAnalyzer = new TimingStats();
        this.clickProfiles = new ConcurrentHashMap<>();
    }

    public CheckResult check(Player player) {
        if (!config.isCheckEnabled("autoclicker")) {
            return CheckResult.passed();
        }

        PlayerData data = playerDataMap.get(player.getUniqueId());
        if (data == null) return CheckResult.passed();

        if (data.isInGracePeriod(config.getGracePeriod())) {
            return CheckResult.passed();
        }

        if (player.isDead() || !player.isOnline()) {
            return CheckResult.passed();
        }

        if (player.isRiptiding()) {
            return CheckResult.passed();
        }

        Deque<Long> clickHistory = data.getClickHistory();
        if (clickHistory.size() < MIN_SAMPLES) {
            return CheckResult.passed();
        }

        UUID playerId = player.getUniqueId();
        ClickProfile profile = clickProfiles.computeIfAbsent(playerId, k -> new ClickProfile());

        List<Long> clicks = new ArrayList<>(clickHistory);
        List<Long> intervals = new ArrayList<>();
        for (int i = 1; i < clicks.size(); i++) {
            long delta = clicks.get(i) - clicks.get(i - 1);
            if (delta > 0 && delta < 2000) {
                intervals.add(delta);
            }
        }

        if (intervals.size() < 10) {
            return CheckResult.passed();
        }

        if (intervals.isEmpty()) {
            return CheckResult.passed();
        }

        double cps = computeCPS(intervals);
        profile.recordCPS(cps);

        double mean = intervals.stream().mapToLong(Long::longValue).average().orElse(100);
        double variance = intervals.stream().mapToDouble(v -> Math.pow(v - mean, 2)).average().orElse(0);
        double stdDev = Math.sqrt(variance);
        double cv = mean > 0.01 ? stdDev / mean : 0.0;

        List<Long> recentIntervals = intervals.subList(Math.max(0, intervals.size() - 20), intervals.size());
        double entropy = TimingStats.calculateEntropy(recentIntervals);

        profile.pushIntervals(intervals);
        double[] ringSnapshot = profile.snapshotIntervals(CORR_WINDOW);
        if (ringSnapshot.length >= CORR_MIN_SAMPLES) {
            double[] autocorr = computeAutocorrelation(ringSnapshot);
            double maxLagCorr = 0.0;
            int maxLag = -1;
            for (int lag = CORR_LAG_MIN; lag <= Math.min(CORR_LAG_MAX, autocorr.length - 1); lag++) {
                if (autocorr[lag] > maxLagCorr) {
                    maxLagCorr = autocorr[lag];
                    maxLag = lag;
                }
            }
            if (maxLagCorr > CORR_THRESHOLD) {
                profile.recordPeriodicHit();
                if (profile.getPeriodicHits() >= 2) {
                    CheckResult periodicResult = CheckResult.failed(
                            ViolationType.AUTOCLICKER,
                            Math.min(0.97, 0.78 + (maxLagCorr - CORR_THRESHOLD) * 0.5),
                            String.format("AC_PERIODIC: r=%.3f at lag=%d, CPS=%.1f (x%d)",
                                maxLagCorr, maxLag, cps, profile.getPeriodicHits())
                    );
                    if (filtering.passesLayer2HeuristicFiltering(player, ViolationType.AUTOCLICKER, periodicResult)) {
                        profile.resetPeriodicHits();
                        return periodicResult;
                    }
                }
            } else {
                profile.decayPeriodicHits();
            }

            double kurt = computeKurtosis(ringSnapshot);
            double ringMean = 0.0;
            for (double v : ringSnapshot) ringMean += v;
            ringMean /= ringSnapshot.length;
            double ringVar = 0.0;
            for (double v : ringSnapshot) ringVar += (v - ringMean) * (v - ringMean);
            ringVar /= ringSnapshot.length;
            double ringStd = Math.sqrt(ringVar);
            double ringCv = ringMean > 0.01 ? ringStd / ringMean : 0.0;
            if (kurt < KURTOSIS_THRESHOLD && ringCv >= CV_HUMANIZER_LOW && ringCv <= CV_HUMANIZER_HIGH) {
                CheckResult fakeHumanResult = CheckResult.failed(
                        ViolationType.AUTOCLICKER,
                        Math.min(0.95, 0.80 + (KURTOSIS_THRESHOLD - kurt) * 0.10),
                        String.format("AC_FAKE_HUMANIZER: kurtosis=%.2f cv=%.3f CPS=%.1f", kurt, ringCv, cps)
                );
                if (filtering.passesLayer2HeuristicFiltering(player, ViolationType.AUTOCLICKER, fakeHumanResult)) {
                    return fakeHumanResult;
                }
            }
        }

        ClickType clickType = classifyClickType(cps, cv, entropy, recentIntervals);
        profile.recordType(clickType);

        double slowdownGrace = getSlowdownCpsMultiplier(player);

        if (clickType == ClickType.BUTTERFLY) {
            return checkButterfly(player, cps, cv, entropy, recentIntervals, profile, slowdownGrace);
        }

        if (clickType == ClickType.DRAG) {
            return checkDrag(player, cps, cv, entropy, recentIntervals, profile, slowdownGrace);
        }

        if (clickType == ClickType.JITTER) {
            return checkJitter(player, cps, cv, entropy, recentIntervals, profile);
        }

        int adjustedMaxCPS = (int) Math.round(VersionAdapter.adjustCPSThreshold(player, config.getAutoClickerMaxCPS()) * slowdownGrace);
        if (cps > adjustedMaxCPS && cv < CV_MACRO_THRESHOLD) {
            CheckResult cpsResult = CheckResult.failed(
                    ViolationType.AUTOCLICKER,
                    Math.min(1.0, 0.78 + (cps - adjustedMaxCPS) * 0.02),
                    String.format("Over version-adjusted CPS: %.1f (max: %d)", cps, adjustedMaxCPS)
            );
            if (filtering.passesLayer2HeuristicFiltering(player, ViolationType.AUTOCLICKER, cpsResult)) {
                return cpsResult;
            }
        }

        if (TimingStats.hasConsistentPattern(recentIntervals, 8)) {
            TimingStats.TimingPattern consistentPattern = timingAnalyzer.analyzeTimings(clickHistory);
            double patternSeverity = Math.min(0.95, 0.75 + consistentPattern.coefficientOfVariation * 2.0);
            if (consistentPattern.zScore > 2.0) {
                patternSeverity = Math.min(0.98, patternSeverity + consistentPattern.zScore * 0.02);
            }
            CheckResult patResult = CheckResult.failed(
                    ViolationType.AUTOCLICKER,
                    patternSeverity,
                    String.format("Consistent pattern: mean=%.1f stdDev=%.2f cv=%.3f z=%.2f var=%.1f",
                        consistentPattern.mean, consistentPattern.stdDev,
                        consistentPattern.coefficientOfVariation, consistentPattern.zScore, consistentPattern.variance)
            );
            if (filtering.passesLayer2HeuristicFiltering(player, ViolationType.AUTOCLICKER, patResult)) {
                return patResult;
            }
        }

        TimingStats.TimingPattern pattern = timingAnalyzer.analyzeTimings(clickHistory);

        if (pattern.suspicious) {
            CheckResult prelimResult = CheckResult.failed(
                    ViolationType.AUTOCLICKER,
                    pattern.severity,
                    pattern.reason
            );

            if (!filtering.passesLayer2HeuristicFiltering(player, ViolationType.AUTOCLICKER, prelimResult)) {
                return CheckResult.passed();
            }

            return prelimResult;
        }

        if (pattern.macroDetected && !isLegitHighCPS(clickType, cps)) {
            CheckResult prelimResult = CheckResult.failed(
                    ViolationType.AUTOCLICKER,
                    0.90,
                    "Macro pattern: " + pattern.reason
            );

            if (!filtering.passesLayer2HeuristicFiltering(player, ViolationType.AUTOCLICKER, prelimResult)) {
                return CheckResult.passed();
            }

            return prelimResult;
        }

        if (cps > DRAG_MAX_CPS * slowdownGrace && cv < CV_MACRO_THRESHOLD) {
            CheckResult prelimResult = CheckResult.failed(
                    ViolationType.AUTOCLICKER,
                    Math.min(1.0, cps / 50.0),
                    String.format("Extreme CPS: %.1f, CV:%.3f", cps, cv)
            );

            if (!filtering.passesLayer2HeuristicFiltering(player, ViolationType.AUTOCLICKER, prelimResult)) {
                return CheckResult.passed();
            }

            return prelimResult;
        }

        boolean botNow = cv < CV_BOT_THRESHOLD && cps > 8 && entropy < ENTROPY_BOT_THRESHOLD;
        boolean botSoft = cv < CV_BOT_THRESHOLD * 1.4 && cps > 8 && entropy < ENTROPY_BOT_THRESHOLD * 1.2;
        if (botSoft) {
            int ticks = profile.bumpBotPattern();
            if (botNow || ticks >= 5) {
                double severity = Math.min(0.95, 0.78 + (8.0 - Math.max(0, entropy)) * 0.04 + ticks * 0.01);

                CheckResult prelimResult = CheckResult.failed(
                        ViolationType.AUTOCLICKER,
                        severity,
                        String.format("Bot-like: CPS:%.1f, CV:%.3f, Entropy:%.2f, sustained:%d", cps, cv, entropy, ticks)
                );

                if (!filtering.passesLayer2HeuristicFiltering(player, ViolationType.AUTOCLICKER, prelimResult)) {
                    return CheckResult.passed();
                }

                return prelimResult;
            }
        } else {
            profile.decayBotPattern();
        }

        List<Long> windowedIntervals = intervals.subList(Math.max(0, intervals.size() - INTERVAL_WINDOW), intervals.size());
        double bucketedEntropy = computeBucketedEntropy(windowedIntervals);
        if (bucketedEntropy < SHANNON_ENTROPY_THRESHOLD && cv < CV_MACRO_THRESHOLD && cps > 8) {
            CheckResult shannonResult = CheckResult.failed(
                    ViolationType.AUTOCLICKER,
                    Math.min(0.95, 0.76 + (SHANNON_ENTROPY_THRESHOLD - bucketedEntropy) * 0.06),
                    String.format("Low bucketed entropy: H=%.2f (threshold:%.1f), CV:%.3f, CPS:%.1f",
                            bucketedEntropy, SHANNON_ENTROPY_THRESHOLD, cv, cps)
            );
            if (filtering.passesLayer2HeuristicFiltering(player, ViolationType.AUTOCLICKER, shannonResult)) {
                return shannonResult;
            }
        }

        if (profile.hasCPSSpike()) {
            CheckResult prelimResult = CheckResult.failed(
                    ViolationType.AUTOCLICKER,
                    0.82,
                    String.format("CPS spike: current=%.1f avg=%.1f", cps, profile.getAverageCPS())
            );
            if (filtering.passesLayer2HeuristicFiltering(player, ViolationType.AUTOCLICKER, prelimResult)) {
                return prelimResult;
            }
        }

        if (profile.isAttackOnly() && cps > 14) {
            CheckResult prelimResult = CheckResult.failed(
                    ViolationType.AUTOCLICKER,
                    0.78,
                    String.format("Attack-only clicks: CPS:%.1f, no other types", cps)
            );
            if (filtering.passesLayer2HeuristicFiltering(player, ViolationType.AUTOCLICKER, prelimResult)) {
                return prelimResult;
            }
        }

        return CheckResult.passed();
    }

    private CheckResult checkButterfly(Player player, double cps, double cv, double entropy,
                                       List<Long> intervals, ClickProfile profile, double slowdownGrace) {
        double butterflyCap = BUTTERFLY_MAX_CPS * slowdownGrace;
        if (cps > butterflyCap) {
            if (cv < CV_MACRO_THRESHOLD && entropy < ENTROPY_LOW_THRESHOLD) {
                CheckResult prelimResult = CheckResult.failed(
                        ViolationType.AUTOCLICKER,
                        Math.min(0.95, 0.75 + (cps - butterflyCap) * 0.02),
                        String.format("Butterfly too fast: %.1f CPS, CV:%.3f, Entropy:%.2f", cps, cv, entropy)
                );

                if (!filtering.passesLayer2HeuristicFiltering(player, ViolationType.AUTOCLICKER, prelimResult)) {
                    return CheckResult.passed();
                }

                return prelimResult;
            }
        }

        if (!hasButterflyPattern(intervals)) {
            if (cps > 14 * slowdownGrace && cv < CV_MACRO_THRESHOLD) {
                CheckResult prelimResult = CheckResult.failed(
                        ViolationType.AUTOCLICKER,
                        0.85,
                        String.format("Fake butterfly: %.1f CPS, no alternation, CV:%.3f", cps, cv)
                );

                if (!filtering.passesLayer2HeuristicFiltering(player, ViolationType.AUTOCLICKER, prelimResult)) {
                    return CheckResult.passed();
                }

                return prelimResult;
            }
        }

        return CheckResult.passed();
    }

    private CheckResult checkDrag(Player player, double cps, double cv, double entropy,
                                  List<Long> intervals, ClickProfile profile, double slowdownGrace) {
        double dragCap = DRAG_MAX_CPS * slowdownGrace;
        if (cps > dragCap && cv < CV_MACRO_THRESHOLD) {
            CheckResult prelimResult = CheckResult.failed(
                    ViolationType.AUTOCLICKER,
                    Math.min(0.95, 0.70 + (cps - dragCap) * 0.03),
                    String.format("Drag too consistent: %.1f CPS, CV:%.3f", cps, cv)
            );

            if (!filtering.passesLayer2HeuristicFiltering(player, ViolationType.AUTOCLICKER, prelimResult)) {
                return CheckResult.passed();
            }

            return prelimResult;
        }

        if (cps > 25 * slowdownGrace && entropy < ENTROPY_BOT_THRESHOLD) {
            int vl = profile.bumpDragEntropy();
            if (vl >= 7) {
                CheckResult prelimResult = CheckResult.failed(
                        ViolationType.AUTOCLICKER,
                        0.88,
                        String.format("Drag low entropy: %.1f CPS, Entropy:%.2f (vl:%d)", cps, entropy, vl)
                );

                if (!filtering.passesLayer2HeuristicFiltering(player, ViolationType.AUTOCLICKER, prelimResult)) {
                    return CheckResult.passed();
                }

                return prelimResult;
            }
        } else {
            profile.resetDragEntropy();
        }

        if (profile.getConsistentHighCPSCount() > 25 && cps > 30 * slowdownGrace) {
            CheckResult prelimResult = CheckResult.failed(
                    ViolationType.AUTOCLICKER,
                    0.90,
                    String.format("Sustained drag: %.1f CPS for %d checks", cps, profile.getConsistentHighCPSCount())
            );

            if (!filtering.passesLayer2HeuristicFiltering(player, ViolationType.AUTOCLICKER, prelimResult)) {
                return CheckResult.passed();
            }

            return prelimResult;
        }

        return CheckResult.passed();
    }

    private CheckResult checkJitter(Player player, double cps, double cv, double entropy,
                                    List<Long> intervals, ClickProfile profile) {
        if (cv < CV_BOT_THRESHOLD && entropy < ENTROPY_BOT_THRESHOLD) {
            CheckResult prelimResult = CheckResult.failed(
                    ViolationType.AUTOCLICKER,
                    0.88,
                    String.format("Jitter too consistent: %.1f CPS, CV:%.3f, Entropy:%.2f", cps, cv, entropy)
            );

            if (!filtering.passesLayer2HeuristicFiltering(player, ViolationType.AUTOCLICKER, prelimResult)) {
                return CheckResult.passed();
            }

            return prelimResult;
        }

        return CheckResult.passed();
    }

    private ClickType classifyClickType(double cps, double cv, double entropy, List<Long> intervals) {
        if (cps >= 20 && hasBurstPattern(intervals)) {
            return ClickType.DRAG;
        }

        if (cps >= 14 && cps <= BUTTERFLY_MAX_CPS && hasButterflyPattern(intervals)) {
            return ClickType.BUTTERFLY;
        }

        if (cps >= JITTER_MIN_CPS && cps <= JITTER_MAX_CPS && cv > 0.15) {
            return ClickType.JITTER;
        }

        if (cps > BUTTERFLY_MAX_CPS) {
            return ClickType.DRAG;
        }

        return ClickType.NORMAL;
    }

    private boolean hasButterflyPattern(List<Long> intervals) {
        if (intervals.size() < 6) return false;

        int alternatingCount = 0;
        for (int i = 1; i < intervals.size(); i++) {
            long prev = intervals.get(i - 1);
            long curr = intervals.get(i);
            double ratio = Math.min(prev, curr) / (double) Math.max(prev, curr);
            if (ratio < 0.62) {
                alternatingCount++;
            }
        }

        return alternatingCount >= intervals.size() * 0.50;
    }

    private boolean hasBurstPattern(List<Long> intervals) {
        if (intervals.size() < 8) return false;

        int burstCount = 0;
        int gapCount = 0;
        for (long interval : intervals) {
            if (interval < 35) {
                burstCount++;
            } else if (interval > 80) {
                gapCount++;
            }
        }

        return burstCount >= intervals.size() * 0.4 && gapCount >= 2;
    }

    private boolean isLegitHighCPS(ClickType type, double cps) {
        if (type == ClickType.BUTTERFLY && cps <= BUTTERFLY_MAX_CPS) return true;
        if (type == ClickType.DRAG && cps <= DRAG_MAX_CPS) return true;
        if (type == ClickType.JITTER && cps <= JITTER_MAX_CPS) return true;
        return false;
    }

    private double computeBucketedEntropy(List<Long> intervals) {
        if (intervals.isEmpty()) return 0.0;
        Map<Long, Integer> freq = new HashMap<>();
        for (long interval : intervals) {
            long bucket = interval / BUCKET_SIZE_MS;
            freq.put(bucket, freq.getOrDefault(bucket, 0) + 1);
        }
        double n = intervals.size();
        double entropy = 0.0;
        for (int count : freq.values()) {
            double p = count / n;
            entropy -= p * (Math.log(p) / Math.log(2));
        }
        return entropy;
    }

    private double getSlowdownCpsMultiplier(Player player) {
        double mult = 1.0;
        PotionEffectType slownessType = PotionEffectType.getByName("SLOWNESS");
        if (slownessType == null) slownessType = PotionEffectType.getByName("SLOW");
        if (slownessType != null && player.hasPotionEffect(slownessType)) {
            mult *= 1.30;
        }
        PotionEffectType fatigueType = PotionEffectType.getByName("MINING_FATIGUE");
        if (fatigueType == null) fatigueType = PotionEffectType.getByName("SLOW_DIGGING");
        if (fatigueType != null && player.hasPotionEffect(fatigueType)) {
            PotionEffect effect = player.getPotionEffect(fatigueType);
            int amp = effect != null ? effect.getAmplifier() : 0;
            mult *= 1.0 + 0.30 * (amp + 1);
        }
        return mult;
    }

    public static double[] computeAutocorrelation(double[] intervals) {
        int n = intervals.length;
        double[] result = new double[Math.min(CORR_LAG_MAX + 1, n)];
        if (n < 2) return result;
        double mean = 0.0;
        for (double v : intervals) mean += v;
        mean /= n;
        double denom = 0.0;
        for (double v : intervals) denom += (v - mean) * (v - mean);
        if (denom <= 1e-9) return result;
        for (int k = 0; k < result.length; k++) {
            double num = 0.0;
            for (int i = k; i < n; i++) {
                num += (intervals[i] - mean) * (intervals[i - k] - mean);
            }
            result[k] = num / denom;
        }
        return result;
    }

    public static double computeKurtosis(double[] intervals) {
        int n = intervals.length;
        if (n < 4) return 3.0;
        double mean = 0.0;
        for (double v : intervals) mean += v;
        mean /= n;
        double m2 = 0.0;
        double m4 = 0.0;
        for (double v : intervals) {
            double d = v - mean;
            double d2 = d * d;
            m2 += d2;
            m4 += d2 * d2;
        }
        m2 /= n;
        m4 /= n;
        if (m2 <= 1e-9) return 3.0;
        return m4 / (m2 * m2);
    }

    private double computeCPS(List<Long> intervals) {
        if (intervals.isEmpty()) return 0;
        double totalMs = intervals.stream().mapToLong(Long::longValue).sum();
        if (totalMs <= 0) return 0;
        return (intervals.size() / totalMs) * 1000.0;
    }

    public void cleanup(UUID playerId) {
        clickProfiles.remove(playerId);
    }

    private enum ClickType {
        NORMAL, JITTER, BUTTERFLY, DRAG
    }

    private static class ClickProfile {
        private final List<Double> recentCPS = new ArrayList<>();
        private final Map<ClickType, Integer> typeCounts = new EnumMap<>(ClickType.class);
        private final Deque<Long> intervalRing = new ArrayDeque<>();
        private long lastSeenInterval = -1L;
        private int consistentHighCPSCount = 0;
        private int dragEntropyViolations = 0;
        private int botPatternTicks = 0;
        private long lastBotTick = 0;

        void pushIntervals(List<Long> intervals) {
            if (intervals.isEmpty()) return;
            int start = 0;
            if (lastSeenInterval >= 0) {
                for (int i = intervals.size() - 1; i >= 0; i--) {
                    if (intervals.get(i) == lastSeenInterval) {
                        start = i + 1;
                        break;
                    }
                }
            }
            for (int i = start; i < intervals.size(); i++) {
                intervalRing.addLast(intervals.get(i));
                while (intervalRing.size() > RING_CAPACITY) intervalRing.pollFirst();
            }
            lastSeenInterval = intervals.get(intervals.size() - 1);
        }

        double[] snapshotIntervals(int window) {
            int size = Math.min(window, intervalRing.size());
            double[] out = new double[size];
            if (size == 0) return out;
            int skip = intervalRing.size() - size;
            int idx = 0;
            int seen = 0;
            for (Long v : intervalRing) {
                if (seen++ < skip) continue;
                out[idx++] = v;
            }
            return out;
        }

        int bumpBotPattern() {
            long now = System.currentTimeMillis();
            if (now - lastBotTick > 30000) botPatternTicks = 0;
            botPatternTicks++;
            lastBotTick = now;
            return botPatternTicks;
        }

        void decayBotPattern() {
            if (botPatternTicks > 0 && System.currentTimeMillis() - lastBotTick > 8000) {
                botPatternTicks--;
            }
        }

        int getBotPatternTicks() { return botPatternTicks; }

        int bumpDragEntropy() {
            dragEntropyViolations++;
            return dragEntropyViolations;
        }

        void resetDragEntropy() {
            dragEntropyViolations = 0;
        }

        void recordCPS(double cps) {
            recentCPS.add(cps);
            if (recentCPS.size() > 50) {
                recentCPS.remove(0);
            }
            if (cps > 25) {
                consistentHighCPSCount++;
            } else {
                consistentHighCPSCount = Math.max(0, consistentHighCPSCount - 2);
            }
        }

        void recordType(ClickType type) {
            typeCounts.put(type, typeCounts.getOrDefault(type, 0) + 1);
        }

        private int periodicHits = 0;
        private long lastPeriodicHit = 0;

        void recordPeriodicHit() {
            long now = System.currentTimeMillis();
            if (now - lastPeriodicHit > 5000) periodicHits = 1;
            else periodicHits++;
            lastPeriodicHit = now;
        }
        int getPeriodicHits() { return periodicHits; }
        void resetPeriodicHits() { periodicHits = 0; }
        void decayPeriodicHits() {
            if (System.currentTimeMillis() - lastPeriodicHit > 5000) periodicHits = 0;
        }

        int getConsistentHighCPSCount() {
            return consistentHighCPSCount;
        }

        boolean hasCPSSpike() {
            if (recentCPS.size() < 10) return false;
            double avg = getAverageCPS();
            double latest = recentCPS.get(recentCPS.size() - 1);
            if (latest <= avg * 2.5 || latest <= 18.0) return false;
            int spikeCount = 0;
            for (int i = Math.max(0, recentCPS.size() - 5); i < recentCPS.size(); i++) {
                if (recentCPS.get(i) > avg * 2.0 && recentCPS.get(i) > 15.0) spikeCount++;
            }
            return spikeCount >= 2;
        }

        double getAverageCPS() {
            if (recentCPS.isEmpty()) return 0;
            return recentCPS.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        }

        boolean isAttackOnly() {
            if (typeCounts.isEmpty()) return false;
            int total = typeCounts.values().stream().mapToInt(Integer::intValue).sum();
            int normalCount = typeCounts.getOrDefault(ClickType.NORMAL, 0);
            int butterflyCount = typeCounts.getOrDefault(ClickType.BUTTERFLY, 0);
            int jitterCount = typeCounts.getOrDefault(ClickType.JITTER, 0);
            int legitHighCPS = butterflyCount + jitterCount;
            return total > 30 && normalCount == 0 && legitHighCPS < total / 4;
        }
    }
}
