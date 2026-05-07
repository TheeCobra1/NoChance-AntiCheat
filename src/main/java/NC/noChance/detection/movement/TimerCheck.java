package NC.noChance.detection.movement;

import NC.noChance.core.*;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class TimerCheck {
    private final ACConfig config;
    private final Map<UUID, PlayerData> playerDataMap;
    private final LayerFiltering filtering;
    private final Map<UUID, TimerData> timerData;

    public TimerCheck(ACConfig config, Map<UUID, PlayerData> playerDataMap, LayerFiltering filtering) {
        this.config = config;
        this.playerDataMap = playerDataMap;
        this.filtering = filtering;
        this.timerData = new ConcurrentHashMap<>();
    }

    public CheckResult check(Player player) {
        if (!config.isCheckEnabled("timer")) {
            return CheckResult.passed();
        }

        PlayerData data = playerDataMap.get(player.getUniqueId());
        if (data == null) return CheckResult.passed();

        if (data.isInGracePeriod(config.getGracePeriod())) {
            return CheckResult.passed();
        }

        if (player.getGameMode() == org.bukkit.GameMode.SPECTATOR) {
            return CheckResult.passed();
        }

        if (player.getVehicle() != null) {
            return CheckResult.passed();
        }

        if (player.isGliding() || player.isRiptiding()) {
            return CheckResult.passed();
        }

        if (data.isInTeleportGracePeriod(config.getTeleportGracePeriod())) {
            return CheckResult.passed();
        }

        long timeSinceVelocity = System.currentTimeMillis() - data.getLastVelocityTime();
        if (timeSinceVelocity < 1000) {
            return CheckResult.passed();
        }

        org.bukkit.Location lastLoc = data.getLastLocation();
        if (lastLoc == null) return CheckResult.passed();

        long lastMoveTime = data.getLastMoveTime();
        if (lastMoveTime == 0) return CheckResult.passed();

        UUID uuid = player.getUniqueId();
        TimerData timer = timerData.computeIfAbsent(uuid, k -> new TimerData());

        long now = System.currentTimeMillis();
        long timeDelta = now - lastMoveTime;

        if (timeDelta > 1000 || timeDelta < 5) {
            return CheckResult.passed();
        }

        org.bukkit.Location currentLoc = player.getLocation();
        if (currentLoc == null || lastLoc.getWorld() != currentLoc.getWorld()) {
            return CheckResult.passed();
        }
        double distance = lastLoc.distance(currentLoc);

        if (distance < 0.01) {
            return CheckResult.passed();
        }

        double movementsPerSecond = 1000.0 / timeDelta;
        timer.recordMovement(movementsPerSecond, distance, timeDelta);
        timer.recordPacketTimestamp(now);

        CheckResult bunchResult = checkPacketBunching(player, timer, now);
        if (bunchResult.isFailed()) return bunchResult;

        CheckResult crossResult = checkCrossWindow(player, timer, now);
        if (crossResult.isFailed()) return crossResult;

        if (timer.getSampleCount() < 20) {
            return CheckResult.passed();
        }

        double avgMovementsPerSec = timer.getAverageMovementsPerSecond();
        double balance = timer.getBalance();

        int ping = filtering.getPing(player);
        double pingBuffer = ping > 200 ? 3.0 : (ping > 100 ? 1.5 : 0.0);
        double balanceBuffer = ping > 200 ? 30.0 : (ping > 100 ? 15.0 : 0.0);

        double adjustedTimerThreshold = VersionAdapter.adjustTimerThreshold(player, 21.5 + pingBuffer);

        if (avgMovementsPerSec > adjustedTimerThreshold && balance > (50.0 + balanceBuffer)) {
            double severity = Math.min(1.0, (avgMovementsPerSec - 20.0) / 15.0);

            CheckResult prelimResult = CheckResult.failed(
                ViolationType.TIMER,
                severity,
                String.format("Fast timer: %.1f moves/s, Balance: +%.0f", avgMovementsPerSec, balance)
            );

            if (!filtering.passesLayer2HeuristicFiltering(player, ViolationType.TIMER, prelimResult)) {
                timer.compensate();
                return CheckResult.passed();
            }

            return prelimResult;
        }

        if (avgMovementsPerSec > (20.3 + pingBuffer * 0.5) && balance > (50.0 + balanceBuffer) && timer.getSampleCount() >= 60) {
            timer.incrementSubtleCount();

            if (timer.getSubtleCount() >= 4) {
                double severity = Math.min(0.92, 0.65 + (avgMovementsPerSec - 20.0) * 0.12);

                CheckResult prelimResult = CheckResult.failed(
                    ViolationType.TIMER,
                    severity,
                    String.format("Slow timer: %.2f moves/s, Balance: +%.0f", avgMovementsPerSec, balance)
                );

                if (!filtering.passesLayer2HeuristicFiltering(player, ViolationType.TIMER, prelimResult)) {
                    timer.resetSubtleCount();
                    return CheckResult.passed();
                }

                timer.resetSubtleCount();
                return prelimResult;
            }
        } else if (avgMovementsPerSec > (21.0 + pingBuffer) && balance > (30.0 + balanceBuffer) && timer.getSampleCount() >= 22) {
            timer.incrementSubtleCount();

            if (timer.getSubtleCount() >= 3) {
                double severity = Math.min(1.0, 0.70 + (avgMovementsPerSec - 20.0) * 0.1);

                CheckResult prelimResult = CheckResult.failed(
                    ViolationType.TIMER,
                    severity,
                    String.format("Subtle timer: %.1f moves/s, Balance: +%.0f", avgMovementsPerSec, balance)
                );

                if (!filtering.passesLayer2HeuristicFiltering(player, ViolationType.TIMER, prelimResult)) {
                    timer.resetSubtleCount();
                    return CheckResult.passed();
                }

                timer.resetSubtleCount();
                return prelimResult;
            }
        } else {
            timer.decaySubtleCount();
        }

        if (timer.getSampleCount() >= VARIANCE_MIN_SAMPLES) {
            double intervalVariance = timer.getIntervalVariance();
            if (intervalVariance < TIMER_VARIANCE_THRESHOLD && avgMovementsPerSec > (VARIANCE_FAST_THRESHOLD + pingBuffer * 0.5)) {
                double severity = Math.min(VARIANCE_MAX_SEVERITY, 0.35 + (TIMER_VARIANCE_THRESHOLD - intervalVariance) / TIMER_VARIANCE_THRESHOLD * 0.20);

                CheckResult prelimResult = CheckResult.failed(
                    ViolationType.TIMER,
                    severity,
                    String.format("Low-variance timer: %.1f moves/s, variance=%.2f ms^2", avgMovementsPerSec, intervalVariance)
                );

                if (!filtering.passesLayer2HeuristicFiltering(player, ViolationType.TIMER, prelimResult)) {
                    timer.resetWelford();
                    return CheckResult.passed();
                }

                return prelimResult;
            }
        }

        return CheckResult.passed();
    }

    public void cleanup(UUID uuid) {
        timerData.remove(uuid);
    }

    private CheckResult checkCrossWindow(Player player, TimerData timer, long now) {
        if (timer.getSampleCount() < 30) return CheckResult.passed();

        double avg1s = timer.getWindowAverage(now, 1000L);
        double avg3s = timer.getWindowAverage(now, 3000L);
        double avg5s = timer.getWindowAverage(now, 5000L);

        if (avg1s <= 0 || avg3s <= 0 || avg5s <= 0) return CheckResult.passed();

        double maxRatio = 0.0;
        maxRatio = Math.max(maxRatio, avg1s / avg3s);
        maxRatio = Math.max(maxRatio, avg3s / avg1s);
        maxRatio = Math.max(maxRatio, avg1s / avg5s);
        maxRatio = Math.max(maxRatio, avg5s / avg1s);
        maxRatio = Math.max(maxRatio, avg3s / avg5s);
        maxRatio = Math.max(maxRatio, avg5s / avg3s);

        if (maxRatio > 1.05) {
            timer.incrementCrossViolation();
            if (timer.getCrossViolation() >= 3) {
                double severity = Math.min(0.88, 0.55 + (maxRatio - 1.05) * 2.0);
                CheckResult prelim = CheckResult.failed(
                    ViolationType.TIMER,
                    severity,
                    String.format("Cross-window timer: 1s=%.2f 3s=%.2f 5s=%.2f ratio=%.3f",
                        avg1s, avg3s, avg5s, maxRatio)
                );
                if (!filtering.passesLayer2HeuristicFiltering(player, ViolationType.TIMER, prelim)) {
                    timer.resetCrossViolation();
                    return CheckResult.passed();
                }
                timer.resetCrossViolation();
                return prelim;
            }
        } else {
            timer.decayCrossViolation();
        }

        return CheckResult.passed();
    }

    private CheckResult checkPacketBunching(Player player, TimerData timer, long now) {
        if (now - timer.getLastBunchReset() > 5000L) {
            timer.resetBunching();
            timer.setLastBunchReset(now);
        }

        int clustered = timer.countWithin(now, 3L);
        if (clustered >= 3) {
            timer.incrementBunching();
        }

        if (timer.getBunching() >= 5) {
            double severity = Math.min(0.85, 0.60 + (timer.getBunching() - 5) * 0.05);
            CheckResult prelim = CheckResult.failed(
                ViolationType.TIMER,
                severity,
                String.format("Packet bunching: %d clusters within 3ms", timer.getBunching())
            );
            if (!filtering.passesLayer2HeuristicFiltering(player, ViolationType.TIMER, prelim)) {
                timer.resetBunching();
                return CheckResult.passed();
            }
            timer.resetBunching();
            return prelim;
        }

        return CheckResult.passed();
    }

    private static final double TIMER_VARIANCE_THRESHOLD = 15.0;
    private static final int VARIANCE_MIN_SAMPLES = 30;
    private static final double VARIANCE_FAST_THRESHOLD = 19.5;
    private static final double VARIANCE_MAX_SEVERITY = 0.55;

    private static class TimerData {
        private final List<Double> movementsPerSecond = new ArrayList<>();
        private final List<Long> deltas = new ArrayList<>();
        private double balance = 0.0;
        private int subtleCount = 0;
        private static final double EXPECTED_MOVEMENTS_PER_SEC = 20.0;
        private static final int MAX_SAMPLES = 30;

        private long welfordN = 0;
        private double welfordMean = 0.0;
        private double welfordM2 = 0.0;

        private static final int PACKET_RING_SIZE = 32;
        private final long[] packetRing = new long[PACKET_RING_SIZE];
        private int packetRingIdx = 0;
        private int packetRingFill = 0;
        private int bunching = 0;
        private long lastBunchReset = System.currentTimeMillis();
        private int crossViolation = 0;

        void recordMovement(double movementsPerSec, double distance, long timeDelta) {
            movementsPerSecond.add(movementsPerSec);
            deltas.add(timeDelta);

            if (movementsPerSecond.size() > MAX_SAMPLES) {
                movementsPerSecond.remove(0);
                deltas.remove(0);
            }

            welfordN++;
            double delta = timeDelta - welfordMean;
            welfordMean += delta / welfordN;
            double delta2 = timeDelta - welfordMean;
            welfordM2 += delta * delta2;

            double difference = movementsPerSec - EXPECTED_MOVEMENTS_PER_SEC;
            balance += difference;

            if (balance > 80.0) balance = 80.0;
            if (balance < -50.0) balance = -50.0;
        }

        double getIntervalVariance() {
            if (welfordN < 2) return Double.MAX_VALUE;
            return welfordM2 / welfordN;
        }

        void resetWelford() {
            welfordN = 0;
            welfordMean = 0.0;
            welfordM2 = 0.0;
        }

        double getAverageMovementsPerSecond() {
            if (movementsPerSecond.isEmpty()) return 20.0;
            return movementsPerSecond.stream().mapToDouble(Double::doubleValue).average().orElse(20.0);
        }

        double getBalance() {
            return balance;
        }

        int getSampleCount() {
            return movementsPerSecond.size();
        }

        void compensate() {
            balance *= 0.6;
            int clearCount = Math.min(10, movementsPerSecond.size());
            for (int i = 0; i < clearCount; i++) {
                if (!movementsPerSecond.isEmpty()) movementsPerSecond.remove(movementsPerSecond.size() - 1);
                if (!deltas.isEmpty()) deltas.remove(deltas.size() - 1);
            }
        }

        void incrementSubtleCount() {
            subtleCount++;
        }

        void decaySubtleCount() {
            if (subtleCount > 0) subtleCount--;
        }

        void resetSubtleCount() {
            subtleCount = 0;
        }

        int getSubtleCount() {
            return subtleCount;
        }

        void recordPacketTimestamp(long now) {
            packetRing[packetRingIdx] = now;
            packetRingIdx = (packetRingIdx + 1) % PACKET_RING_SIZE;
            if (packetRingFill < PACKET_RING_SIZE) packetRingFill++;
        }

        int countWithin(long now, long withinMs) {
            int count = 0;
            for (int i = 0; i < packetRingFill; i++) {
                if (now - packetRing[i] <= withinMs) count++;
            }
            return count;
        }

        double getWindowAverage(long now, long windowMs) {
            int n = movementsPerSecond.size();
            if (n == 0) return 0.0;
            int included = 0;
            double sum = 0.0;
            long elapsed = 0L;
            for (int i = n - 1; i >= 0; i--) {
                long d = deltas.get(i);
                if (elapsed + d > windowMs && included > 0) break;
                sum += movementsPerSecond.get(i);
                elapsed += d;
                included++;
                if (elapsed >= windowMs) break;
            }
            return included == 0 ? 0.0 : sum / included;
        }

        void incrementBunching() { bunching++; }
        int getBunching() { return bunching; }
        void resetBunching() { bunching = 0; }
        long getLastBunchReset() { return lastBunchReset; }
        void setLastBunchReset(long t) { lastBunchReset = t; }

        void incrementCrossViolation() { crossViolation++; }
        void decayCrossViolation() { if (crossViolation > 0) crossViolation--; }
        int getCrossViolation() { return crossViolation; }
        void resetCrossViolation() { crossViolation = 0; }
    }
}
