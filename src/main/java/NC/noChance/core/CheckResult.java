package NC.noChance.core;

public class CheckResult {
    private final boolean failed;
    private final ViolationType violationType;
    private final double severity;
    private final String details;
    private final long timestamp;

    public CheckResult(boolean failed, ViolationType violationType, double severity, String details) {
        this.failed = failed;
        this.violationType = violationType;
        this.severity = severity;
        this.details = details;
        this.timestamp = System.currentTimeMillis();
    }

    public static CheckResult passed() {
        return new CheckResult(false, null, 0.0, "");
    }

    public static CheckResult failed(ViolationType type, double severity, String details) {
        return new CheckResult(true, type, severity, details);
    }

    public boolean isFailed() {
        return failed;
    }

    public ViolationType getViolationType() {
        return violationType;
    }

    public double getSeverity() {
        return severity;
    }

    public String getDetails() {
        return details;
    }

    public long getTimestamp() {
        return timestamp;
    }
}
