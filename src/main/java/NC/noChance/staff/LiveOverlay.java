package NC.noChance.staff;

import NC.noChance.core.DetectionEngine;
import NC.noChance.core.PlayerData;
import NC.noChance.core.ThresholdAdapter;
import NC.noChance.core.ViaHelper;
import NC.noChance.core.ViolationType;
import NC.noChance.predict.PredictionEngine;
import NC.noChance.sim.SimBridge;
import org.bukkit.entity.Player;

import java.util.*;

public class LiveOverlay {
    private final Map<UUID, PlayerData> dataMap;
    private final DetectionEngine engine;
    private SimBridge simBridge;
    private PredictionEngine predictionEngine;
    private int tickCounter;

    private static final ViolationType[] MAIN_CHECKS = {
        ViolationType.SPEED, ViolationType.KILLAURA,
        ViolationType.FLY, ViolationType.REACH,
        ViolationType.SCAFFOLD, ViolationType.TIMER,
        ViolationType.VELOCITY, ViolationType.PHASE
    };

    private static final long SEVERITY_WINDOW = 30000;

    public LiveOverlay(Map<UUID, PlayerData> dataMap, DetectionEngine engine) {
        this.dataMap = dataMap;
        this.engine = engine;
        this.tickCounter = 0;
    }

    public void setSimBridge(SimBridge simBridge) {
        this.simBridge = simBridge;
    }

    public void setPredictionEngine(PredictionEngine predictionEngine) {
        this.predictionEngine = predictionEngine;
    }

    public String buildOverlay(Player staff, Player target) {
        tickCounter++;
        PlayerData pd = dataMap.get(target.getUniqueId());
        if (pd == null) return "\u00a77No data for \u00a7f" + target.getName();

        int view = (tickCounter / 60) % 3;

        switch (view) {
            case 0: return buildStatus(target, pd);
            case 1: return buildScores(target, pd);
            case 2: return buildChecks(target, pd);
            default: return buildStatus(target, pd);
        }
    }

    public List<String> buildDetailed(Player staff, Player target) {
        PlayerData pd = dataMap.get(target.getUniqueId());
        List<String> lines = new ArrayList<>();

        if (pd == null) {
            lines.add("\u00a7c\u00a7lNoChance \u00a78\u00bb \u00a77No data for \u00a7f" + target.getName());
            return lines;
        }

        UUID uid = target.getUniqueId();
        DetectionEngine.SuspicionLevel susp = engine.getSuspicionLevel(uid);

        lines.add("\u00a78\u00a7m                                                    ");
        lines.add("\u00a7c\u00a7lNoChance \u00a78\u00bb \u00a7fDetailed Report: \u00a7e" + target.getName());
        lines.add("\u00a78\u00a7m                                                    ");

        lines.add("");
        lines.add("\u00a76Check Severities:");
        for (ViolationType type : ViolationType.values()) {
            double sev = getLastSeverity(pd, type);
            if (sev > 0.0) {
                lines.add("  " + getSeverityColor(sev) + type.getDisplayName() + " \u00a78\u00bb \u00a7f" + String.format("%.2f", sev));
            }
        }

        lines.add("");
        lines.add("\u00a76Movement:");
        double speed = calcSpeed(pd);
        String speedColor = speed > 0.35 ? "\u00a7c" : speed > 0.25 ? "\u00a7e" : "\u00a7a";
        lines.add("  \u00a77Speed: " + speedColor + String.format("%.2f", speed) + " b/t");
        lines.add("  \u00a77Air Ticks: \u00a7f" + pd.getAirTicks());
        lines.add("  \u00a77Fall Dist: \u00a7f" + String.format("%.1f", pd.getTotalFallDistance()));

        lines.add("");
        lines.add("\u00a76Rotation:");
        lines.add("  \u00a77Speed: \u00a7f" + String.format("%.1f", pd.getAverageRotationSpeed()) + "\u00b0/s");
        String rotPattern = pd.getAverageRotationSpeed() > 500 ? "\u00a7cSuspicious" :
                           pd.getAverageRotationSpeed() > 300 ? "\u00a7eFast" : "\u00a7aNormal";
        lines.add("  \u00a77Pattern: " + rotPattern);

        lines.add("");
        lines.add("\u00a76Click Analysis:");
        lines.add("  \u00a77CPS: \u00a7f" + String.format("%.1f", pd.getAverageCPS()));
        lines.add("  \u00a77Accuracy: \u00a7f" + String.format("%.0f%%", pd.getAverageAccuracy() * 100));
        Deque<Long> intervals = pd.getAttackIntervals();
        if (intervals.size() >= 2) {
            double avgInterval = calcAvgInterval(intervals);
            double cv = calcCV(intervals);
            lines.add("  \u00a77Avg Interval: \u00a7f" + String.format("%.0fms", avgInterval));
            lines.add("  \u00a77CV: " + (cv < 0.08 ? "\u00a7c" : cv < 0.20 ? "\u00a7e" : "\u00a7a") + String.format("%.3f", cv));
        }

        lines.add("");
        lines.add("\u00a76Detection Status:");
        lines.add("  \u00a77Suspicion: " + getSuspicionDisplay(susp));
        lines.add("  " + formatConfidence(1.0 - pd.getViolationRatio()));
        lines.add("  \u00a77VL Total: \u00a7f" + pd.getTotalViolations());
        lines.add("  \u00a77Check Freq: \u00a7f1.0x");
        String skillName = pd.getSkillLevel().name();
        lines.add("  \u00a77Skill: \u00a7f" + skillName.substring(0, 1) + skillName.substring(1).toLowerCase());

        lines.add("");
        lines.add("\u00a76Client Info:");
        int version = pd.getClientVersion();
        if (version > 0) {
            lines.add("  \u00a77Version: \u00a7f" + ViaHelper.getVersionName(version) + " \u00a78(" + version + ")");
        } else {
            lines.add("  \u00a77Version: \u00a77Server native");
        }
        String connType = NC.noChance.core.BedrockHelper.getConnectionType(target);
        lines.add("  \u00a77Connection: \u00a7f" + connType);
        lines.add("  \u00a77Bedrock: " + (pd.isBedrockPlayer() ? "\u00a7eYes" : "\u00a77No"));
        lines.add("  \u00a77Ping: \u00a7f" + pd.getPing() + "ms \u00a78(\u00a77avg " + String.format("%.0f", pd.getAveragePing()) + "ms\u00a78)");

        if (predictionEngine != null) {
            PredictionEngine.PredScore ps = predictionEngine.getLatestScore(uid);
            if (ps != null && ps.combined > 0) {
                lines.add("");
                lines.add("\u00a76Prediction:");
                lines.add("  \u00a77Move: " + getSeverityColor(ps.moveSeverity) + String.format("%.2f", ps.moveSeverity) +
                    " \u00a78| \u00a77EWMA: \u00a7f" + String.format("%.3f", ps.moveEwma) +
                    (ps.moveFlag ? " \u00a7c[FLAGGED]" : ""));
                lines.add("  \u00a77Combat: " + getSeverityColor(ps.combatSeverity) + String.format("%.2f", ps.combatSeverity) +
                    (ps.combatFlag ? " \u00a7c[FLAGGED]" : ""));
                lines.add("  \u00a77Combined: \u00a7f" + String.format("%.2f", ps.combined) +
                    " \u00a78| \u00a77XCorr: \u00a7f" + String.format("%.2f", ps.crossCorrelation));
            }
        }

        if (simBridge != null) {
            List<Double> devHist = simBridge.getDeviationHistory(uid);
            if (!devHist.isEmpty()) {
                double avgDev = devHist.stream().mapToDouble(Double::doubleValue).average().orElse(0);
                double maxDev = devHist.stream().mapToDouble(Double::doubleValue).max().orElse(0);
                lines.add("");
                lines.add("\u00a76Sim Deviation:");
                lines.add("  \u00a77Avg: \u00a7f" + String.format("%.4f", avgDev) + " \u00a78| \u00a77Max: \u00a7f" + String.format("%.4f", maxDev) + " \u00a78| \u00a77Samples: \u00a7f" + devHist.size());
            }
        }

        lines.add("\u00a78\u00a7m                                                    ");

        return lines;
    }

    public String getSeverityColor(double severity) {
        if (severity >= 0.85) return "\u00a74";
        if (severity >= 0.60) return "\u00a7c";
        if (severity >= 0.35) return "\u00a7e";
        return "\u00a7a";
    }

    public String formatConfidence(double confidence) {
        String color;
        if (confidence >= 0.80) color = "\u00a7a";
        else if (confidence >= 0.55) color = "\u00a7e";
        else if (confidence >= 0.30) color = "\u00a7c";
        else color = "\u00a74";
        return "\u00a77Trust: " + color + String.format("%.2f", confidence);
    }

    private String buildStatus(Player target, PlayerData pd) {
        StringBuilder sb = new StringBuilder();
        sb.append("\u00a7f").append(target.getName());
        sb.append(" \u00a78| \u00a77\u2764 ").append(String.format("%.1f", target.getHealth()));
        sb.append(" \u00a78| \u00a77Ping \u00a7f").append(pd.getPing()).append("ms");
        sb.append(" \u00a78| \u00a77CPS \u00a7f").append(String.format("%.1f", pd.getAverageCPS()));
        sb.append(" \u00a78| \u00a77Rot \u00a7f").append(String.format("%.0f", pd.getAverageRotationSpeed())).append("\u00b0/s");
        if (target.isSprinting()) sb.append(" \u00a78| \u00a7a[Sprint]");
        if (target.isSneaking()) sb.append(" \u00a78| \u00a7e[Sneak]");
        return sb.toString();
    }

    private String buildScores(Player target, PlayerData pd) {
        UUID uid = target.getUniqueId();
        DetectionEngine.SuspicionLevel susp = engine.getSuspicionLevel(uid);
        double trust = 1.0 - pd.getViolationRatio();
        int vl = pd.getTotalViolations();

        double entropy = calcEntropy(pd);
        ThresholdAdapter ta = engine.getThresholdAdapter();
        double tpsMult = ta.getCurrentTPSMultiplier();
        double avgTps = ta.getAverageTPS();

        StringBuilder sb = new StringBuilder();
        sb.append("\u00a7f").append(target.getName());
        sb.append(" \u00a78| \u00a77Trust ").append(getTrustColor(trust)).append(String.format("%.2f", trust));
        sb.append(" \u00a78| \u00a77Suspicion ").append(getSuspicionDisplay(susp));
        sb.append(" \u00a78| \u00a77VL \u00a7f").append(vl);
        sb.append(" \u00a78| \u00a77Entropy \u00a7f").append(String.format("%.2f", entropy));
        sb.append(" \u00a78| \u00a77TPS \u00a7f").append(String.format("%.1f", avgTps));
        if (tpsMult > 1.0) {
            sb.append("\u00a7e(x").append(String.format("%.1f", tpsMult)).append(")");
        }
        return sb.toString();
    }

    private String buildChecks(Player target, PlayerData pd) {
        StringBuilder sb = new StringBuilder();
        sb.append("\u00a7f").append(target.getName());

        for (ViolationType type : MAIN_CHECKS) {
            double sev = getLastSeverity(pd, type);
            String label = shortName(type);
            sb.append(" \u00a78| ").append(getSeverityColor(sev)).append(label);
            sb.append(" \u00a7f").append(String.format("%.2f", sev));
        }

        return sb.toString();
    }

    private double getLastSeverity(PlayerData pd, ViolationType type) {
        List<PlayerData.ViolationRecord> records = pd.getViolations(type, SEVERITY_WINDOW);
        if (records.isEmpty()) return 0.0;
        return records.get(records.size() - 1).severity;
    }

    private double calcSpeed(PlayerData pd) {
        Deque<PlayerData.LocationData> locs = pd.getLocationHistory();
        if (locs.size() < 2) return 0.0;
        PlayerData.LocationData[] arr = locs.toArray(new PlayerData.LocationData[0]);
        PlayerData.LocationData prev = arr[arr.length - 2];
        PlayerData.LocationData curr = arr[arr.length - 1];
        double dx = curr.location.getX() - prev.location.getX();
        double dz = curr.location.getZ() - prev.location.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }

    private double calcEntropy(PlayerData pd) {
        Deque<Long> clicks = pd.getClickHistory();
        if (clicks.size() < 10) return 0.5;
        double sum = 0;
        double sumSq = 0;
        int count = 0;
        Long prev = null;
        for (Long c : clicks) {
            if (prev != null) {
                long interval = c - prev;
                sum += interval;
                sumSq += interval * interval;
                count++;
            }
            prev = c;
        }
        if (count == 0) return 0.5;
        double mean = sum / count;
        double var = Math.max(0.0, (sumSq / count) - (mean * mean));
        double cv = mean > 0.01 ? Math.sqrt(var) / mean : 0.0;
        return Math.min(1.0, Math.max(0.0, cv));
    }

    private double calcAvgInterval(Deque<Long> intervals) {
        double sum = 0;
        for (long v : intervals) sum += v;
        return intervals.isEmpty() ? 0 : sum / intervals.size();
    }

    private double calcCV(Deque<Long> intervals) {
        if (intervals.size() < 2) return 0.5;
        double sum = 0;
        double sumSq = 0;
        for (long v : intervals) {
            sum += v;
            sumSq += (double) v * v;
        }
        double mean = sum / intervals.size();
        double var = Math.max(0.0, (sumSq / intervals.size()) - (mean * mean));
        return mean > 0.01 ? Math.sqrt(var) / mean : 0.0;
    }

    private String getTrustColor(double trust) {
        if (trust >= 0.80) return "\u00a7a";
        if (trust >= 0.55) return "\u00a7e";
        if (trust >= 0.30) return "\u00a7c";
        return "\u00a74";
    }

    private String getSuspicionDisplay(DetectionEngine.SuspicionLevel level) {
        switch (level) {
            case CLEAN: return "\u00a7a[CLEAN]";
            case LOW: return "\u00a7a[LOW]";
            case MEDIUM: return "\u00a7e[MEDIUM]";
            case HIGH: return "\u00a7c[HIGH]";
            case CONFIRMED: return "\u00a74[CONFIRMED]";
            default: return "\u00a77[UNKNOWN]";
        }
    }

    private String shortName(ViolationType type) {
        switch (type) {
            case SPEED: return "Spd";
            case KILLAURA: return "Aura";
            case FLY: return "Fly";
            case REACH: return "Rch";
            case SCAFFOLD: return "Scaf";
            case TIMER: return "Tmr";
            case VELOCITY: return "Vel";
            case PHASE: return "Phs";
            default: return type.getDisplayName().substring(0, Math.min(4, type.getDisplayName().length()));
        }
    }
}
