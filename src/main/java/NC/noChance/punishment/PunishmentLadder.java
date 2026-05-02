package NC.noChance.punishment;

import NC.noChance.core.ACConfig;
import NC.noChance.core.ViolationType;
import NC.noChance.database.DatabaseManager;

import java.util.concurrent.TimeUnit;

public class PunishmentLadder {
    private final ACConfig config;

    public PunishmentLadder(ACConfig config) {
        this.config = config;
    }

    public LadderResult decide(int totalViolations, int typeViolations, String confidenceLevel) {
        if (confidenceLevel.equals("EXTREME")) {
            if (totalViolations >= 8) {
                return new LadderResult(PunishmentDecision.BAN, DatabaseManager.PunishmentType.BAN, 0L, "punishment.ban_extreme");
            } else if (totalViolations >= 5) {
                return new LadderResult(PunishmentDecision.TEMPBAN, DatabaseManager.PunishmentType.TEMPBAN,
                        TimeUnit.HOURS.toMillis(24), "punishment.tempban_extreme");
            } else if (config.shouldKickOnExtreme()) {
                return new LadderResult(PunishmentDecision.KICK, DatabaseManager.PunishmentType.KICK, 0L, "punishment.kick_extreme");
            }
        }

        if (confidenceLevel.equals("HIGH")) {
            if (totalViolations >= 10) {
                return new LadderResult(PunishmentDecision.TEMPBAN, DatabaseManager.PunishmentType.TEMPBAN,
                        TimeUnit.HOURS.toMillis(12), "punishment.tempban_high");
            } else if (totalViolations >= 3 && config.shouldKickOnHigh()) {
                return new LadderResult(PunishmentDecision.KICK, DatabaseManager.PunishmentType.KICK, 0L, "punishment.kick_high");
            } else if (config.shouldWarnOnHigh()) {
                return new LadderResult(PunishmentDecision.WARN, DatabaseManager.PunishmentType.WARN, 0L, "punishment.warn_high");
            }
        }

        if (confidenceLevel.equals("MEDIUM")) {
            if (totalViolations >= 8) {
                return new LadderResult(PunishmentDecision.KICK, DatabaseManager.PunishmentType.KICK, 0L, "punishment.kick_medium");
            } else if (totalViolations >= 3 && config.shouldWarnOnMedium()) {
                return new LadderResult(PunishmentDecision.WARN, DatabaseManager.PunishmentType.WARN, 0L, "punishment.warn_medium");
            }
        }

        if (confidenceLevel.equals("LOW")) {
            if (totalViolations >= 12) {
                return new LadderResult(PunishmentDecision.KICK, DatabaseManager.PunishmentType.KICK, 0L, "punishment.kick_low");
            }
        }

        if (typeViolations >= 5) {
            return new LadderResult(PunishmentDecision.KICK, DatabaseManager.PunishmentType.KICK, 0L, "punishment.kick_repeated");
        }

        return new LadderResult(PunishmentDecision.NONE, null, 0L, null);
    }

    public static final class LadderResult {
        public final PunishmentDecision decision;
        public final DatabaseManager.PunishmentType dbType;
        public final long duration;
        public final String reasonKey;

        public LadderResult(PunishmentDecision decision, DatabaseManager.PunishmentType dbType,
                            long duration, String reasonKey) {
            this.decision = decision;
            this.dbType = dbType;
            this.duration = duration;
            this.reasonKey = reasonKey;
        }
    }
}
