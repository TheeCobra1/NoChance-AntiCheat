package NC.noChance.core;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class SessionTracker {
    private final ConcurrentHashMap<UUID, Baseline> baselines = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, SessionData> sessions = new ConcurrentHashMap<>();

    private static final double Z_THRESHOLD = 3.0;
    private static final double BLEND_NEW = 0.3;
    private static final double BLEND_OLD = 0.7;
    private static final int MIN_SAMPLES = 60;
    private static final int MIN_SESSIONS = 3;

    public void loadBaseline(UUID id, double avgCps, double cpsVar, double avgRot, double rotVar,
                             double avgMove, double moveVar, double avgAcc, long playtime, int sessions) {
        baselines.put(id, new Baseline(avgCps, cpsVar, avgRot, rotVar, avgMove, moveVar, avgAcc, playtime, sessions));
    }

    public void updateSession(UUID id, double cps, double rotSpeed, double moveSpeed, double accuracy) {
        sessions.computeIfAbsent(id, k -> new SessionData()).record(cps, rotSpeed, moveSpeed, accuracy);
    }

    public ProfileShift checkForShift(UUID id) {
        Baseline bl = baselines.get(id);
        SessionData sd = sessions.get(id);

        if (bl == null || sd == null || sd.samples < MIN_SAMPLES || bl.sessionCount < MIN_SESSIONS) {
            return ProfileShift.none();
        }

        if (bl.avgCps >= 1.0 && bl.cpsVar >= 0.05) {
            double cpsZ = zScore(sd.cpsSum / sd.samples, bl.avgCps, bl.cpsVar);
            if (cpsZ > Z_THRESHOLD && sd.cpsSum / sd.samples > bl.avgCps * 1.5) {
                return ProfileShift.detected(
                    normalize(cpsZ), "cps", bl.avgCps, sd.cpsSum / sd.samples
                );
            }
        }

        if (bl.avgRot >= 20.0 && bl.rotVar >= 5.0) {
            double rotZ = zScore(sd.rotSum / sd.samples, bl.avgRot, bl.rotVar);
            if (rotZ > Z_THRESHOLD && sd.rotSum / sd.samples > bl.avgRot * 1.4) {
                return ProfileShift.detected(
                    normalize(rotZ), "rotation", bl.avgRot, sd.rotSum / sd.samples
                );
            }
        }

        double sessionMoveVar = sd.moveVariance();
        if (bl.moveVar > 0.2 && sessionMoveVar < 0.05 && sd.samples >= MIN_SAMPLES) {
            double dropRatio = 1.0 - (sessionMoveVar / bl.moveVar);
            if (dropRatio > 0.75) {
                return ProfileShift.detected(
                    Math.min(1.0, dropRatio), "moveVariance", bl.moveVar, sessionMoveVar
                );
            }
        }

        double sessionAcc = sd.accSum / sd.samples;
        double accZ = zScore(sessionAcc, bl.avgAcc, estimateAccVar(bl.avgAcc));
        if (accZ > Z_THRESHOLD && sessionAcc > bl.avgAcc) {
            return ProfileShift.detected(
                normalize(accZ), "accuracy", bl.avgAcc, sessionAcc
            );
        }

        return ProfileShift.none();
    }

    public BaselineData getBaselineForSave(UUID id) {
        Baseline bl = baselines.get(id);
        SessionData sd = sessions.get(id);

        if (bl == null && sd == null) return null;

        if (bl == null) {
            return new BaselineData(
                sd.cpsSum / Math.max(1, sd.samples),
                sd.cpsVariance(),
                sd.rotSum / Math.max(1, sd.samples),
                sd.rotVariance(),
                sd.moveSum / Math.max(1, sd.samples),
                sd.moveVariance(),
                sd.accSum / Math.max(1, sd.samples),
                sd.durationMinutes(),
                1
            );
        }

        if (sd == null || sd.samples < MIN_SAMPLES) {
            return new BaselineData(
                bl.avgCps, bl.cpsVar, bl.avgRot, bl.rotVar,
                bl.avgMove, bl.moveVar, bl.avgAcc, bl.playtime, bl.sessionCount
            );
        }

        double sCps = sd.cpsSum / sd.samples;
        double sRot = sd.rotSum / sd.samples;
        double sMove = sd.moveSum / sd.samples;
        double sAcc = sd.accSum / sd.samples;

        return new BaselineData(
            blend(bl.avgCps, sCps),
            blend(bl.cpsVar, sd.cpsVariance()),
            blend(bl.avgRot, sRot),
            blend(bl.rotVar, sd.rotVariance()),
            blend(bl.avgMove, sMove),
            blend(bl.moveVar, sd.moveVariance()),
            blend(bl.avgAcc, sAcc),
            bl.playtime + sd.durationMinutes(),
            bl.sessionCount + 1
        );
    }

    public void cleanup(UUID id) {
        baselines.remove(id);
        sessions.remove(id);
    }

    private double zScore(double value, double mean, double variance) {
        double stdDev = Math.sqrt(Math.max(variance, 0.001));
        return Math.abs(value - mean) / stdDev;
    }

    private double normalize(double z) {
        return Math.min(1.0, (z - Z_THRESHOLD) / 7.0);
    }

    private double blend(double old, double fresh) {
        return BLEND_OLD * old + BLEND_NEW * fresh;
    }

    private double estimateAccVar(double avgAcc) {
        if (avgAcc < 0.05) return 1.0;
        double p = Math.max(0.05, Math.min(0.95, avgAcc));
        return p * (1.0 - p);
    }

    private static class Baseline {
        final double avgCps, cpsVar;
        final double avgRot, rotVar;
        final double avgMove, moveVar;
        final double avgAcc;
        final long playtime;
        final int sessionCount;

        Baseline(double avgCps, double cpsVar, double avgRot, double rotVar,
                 double avgMove, double moveVar, double avgAcc, long playtime, int sessionCount) {
            this.avgCps = avgCps;
            this.cpsVar = cpsVar;
            this.avgRot = avgRot;
            this.rotVar = rotVar;
            this.avgMove = avgMove;
            this.moveVar = moveVar;
            this.avgAcc = avgAcc;
            this.playtime = playtime;
            this.sessionCount = sessionCount;
        }
    }

    private static class SessionData {
        double cpsSum, cpsSqSum;
        double rotSum, rotSqSum;
        double moveSum, moveSqSum;
        double accSum;
        int samples;
        final long startTime = System.currentTimeMillis();

        void record(double cps, double rot, double move, double acc) {
            cpsSum += cps;
            cpsSqSum += cps * cps;
            rotSum += rot;
            rotSqSum += rot * rot;
            moveSum += move;
            moveSqSum += move * move;
            accSum += acc;
            samples++;
        }

        double cpsVariance() {
            if (samples < 2) return 0;
            double mean = cpsSum / samples;
            return Math.max(0.0, (cpsSqSum / samples) - (mean * mean));
        }

        double rotVariance() {
            if (samples < 2) return 0;
            double mean = rotSum / samples;
            return Math.max(0.0, (rotSqSum / samples) - (mean * mean));
        }

        double moveVariance() {
            if (samples < 2) return 0;
            double mean = moveSum / samples;
            return Math.max(0.0, (moveSqSum / samples) - (mean * mean));
        }

        long durationMinutes() {
            return (System.currentTimeMillis() - startTime) / 60000;
        }
    }

    public static class ProfileShift {
        public final boolean detected;
        public final double severity;
        public final String metric;
        public final double baseline;
        public final double current;

        private ProfileShift(boolean detected, double severity, String metric, double baseline, double current) {
            this.detected = detected;
            this.severity = severity;
            this.metric = metric;
            this.baseline = baseline;
            this.current = current;
        }

        static ProfileShift none() {
            return new ProfileShift(false, 0.0, "", 0.0, 0.0);
        }

        static ProfileShift detected(double severity, String metric, double baseline, double current) {
            return new ProfileShift(true, Math.min(1.0, Math.max(0.0, severity)), metric, baseline, current);
        }
    }

    public static class BaselineData {
        public final double avgCps, cpsVar;
        public final double avgRot, rotVar;
        public final double avgMove, moveVar;
        public final double avgAcc;
        public final long playtime;
        public final int sessionCount;

        public BaselineData(double avgCps, double cpsVar, double avgRot, double rotVar,
                            double avgMove, double moveVar, double avgAcc, long playtime, int sessionCount) {
            this.avgCps = avgCps;
            this.cpsVar = cpsVar;
            this.avgRot = avgRot;
            this.rotVar = rotVar;
            this.avgMove = avgMove;
            this.moveVar = moveVar;
            this.avgAcc = avgAcc;
            this.playtime = playtime;
            this.sessionCount = sessionCount;
        }
    }
}
