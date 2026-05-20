package NC.noChance.detection.player;

import NC.noChance.core.*;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class BlinkCheck {
    private final ACConfig config;
    private final Map<UUID, PlayerData> playerDataMap;
    private final LayerFiltering filtering;
    private final Map<UUID, BlinkTracker> blinkTrackers;
    private final FalsePositiveFilter falsePositiveFilter;

    private static final long MAX_PACKET_DELAY = 650;
    private static final long PACKET_WINDOW = 1000;
    private static final double PACKET_BURST_THRESHOLD = 23.0;
    private static final long CHUNK_LOAD_DELAY_THRESHOLD = 1200;
    private static final int MIN_PACKETS_FOR_ANALYSIS = 15;

    private MovementGrace movementGrace;
    private TransactionTracker transactionTracker;

    public BlinkCheck(ACConfig config, Map<UUID, PlayerData> playerDataMap, LayerFiltering filtering) {
        this.config = config;
        this.playerDataMap = playerDataMap;
        this.filtering = filtering;
        this.blinkTrackers = new ConcurrentHashMap<>();
        this.falsePositiveFilter = new FalsePositiveFilter();
    }

    public void setMovementGrace(MovementGrace grace) {
        this.movementGrace = grace;
    }

    public void setTransactionTracker(TransactionTracker transactionTracker) {
        this.transactionTracker = transactionTracker;
    }

    public void onPacketReceived(Player player, String packetType, long timestamp) {
        if (!config.isCheckEnabled("blink")) {
            return;
        }

        if (!isMovementPacket(packetType)) {
            return;
        }

        UUID playerId = player.getUniqueId();
        PlayerData data = playerDataMap.get(playerId);
        if (data == null) return;

        if (data.isInGracePeriod(config.getGracePeriod())) {
            return;
        }

        BlinkTracker tracker = blinkTrackers.computeIfAbsent(playerId, k -> new BlinkTracker());
        tracker.addPacket(timestamp, packetType);
    }

    private boolean isMovementPacket(String packetType) {
        if (packetType == null) return false;
        String lower = packetType.toLowerCase();
        if (lower.contains("flying") && !lower.contains("position") && !lower.contains("rotation")) {
            return false;
        }
        return lower.contains("position") ||
               lower.contains("rotation") ||
               lower.contains("move") ||
               lower.contains("look");
    }

    public CheckResult checkForBlink(Player player) {
        if (!config.isCheckEnabled("blink")) {
            return CheckResult.passed();
        }

        UUID playerId = player.getUniqueId();
        PlayerData data = playerDataMap.get(playerId);
        if (data == null) return CheckResult.passed();

        if (data.isInGracePeriod(config.getGracePeriod())) {
            return CheckResult.passed();
        }

        if (player.isFlying() || player.isGliding() || player.isRiptiding()) {
            return CheckResult.passed();
        }

        if (player.getFallDistance() > 2.0) {
            return CheckResult.passed();
        }

        if (player.getVehicle() != null) {
            return CheckResult.passed();
        }

        if (player.isDead()) {
            BlinkTracker tracker2 = blinkTrackers.get(playerId);
            if (tracker2 != null) tracker2.resetViolations();
            return CheckResult.passed();
        }

        long timeSinceDamage = System.currentTimeMillis() - data.getLastDamageTime();
        if (timeSinceDamage < 1000) {
            return CheckResult.passed();
        }

        long timeSinceVelocity = System.currentTimeMillis() - data.getLastVelocityTime();
        if (timeSinceVelocity < 1000) {
            return CheckResult.passed();
        }

        BlinkTracker tracker = blinkTrackers.get(playerId);
        if (tracker == null) return CheckResult.passed();

        long now = System.currentTimeMillis();
        tracker.cleanOldPackets(now - 5000);

        if (tracker.getPacketHistory().size() < MIN_PACKETS_FOR_ANALYSIS) {
            return CheckResult.passed();
        }

        double tps = getCurrentTPS();
        if (tps < 18.0) {
            return CheckResult.passed();
        }

        if (isPlayerLoadingChunks(player, tracker)) {
            tracker.resetViolations();
            return CheckResult.passed();
        }

        String currentWorld = player.getWorld().getName();
        tracker.checkWorldChange(currentWorld);
        if (tracker.isInWorldChangeGrace()) {
            tracker.resetViolations();
            return CheckResult.passed();
        }

        if (data.isInTeleportGracePeriod(3)) {
            tracker.resetViolations();
            return CheckResult.passed();
        }

        if (isPlayerMining(player)) {
            tracker.resetViolations();
            return CheckResult.passed();
        }

        if (transactionTracker != null) {
            int pending = transactionTracker.getPendingCount(player);
            long lastConfirmedNanos = transactionTracker.getLastConfirmedNanos(player);
            long confirmedCount = transactionTracker.getConfirmedCount(player);
            if (confirmedCount >= 30 && pending >= 15 && lastConfirmedNanos > 0) {
                long sinceLastNanos = System.nanoTime() - lastConfirmedNanos;
                if (sinceLastNanos > 1_500_000_000L) {
                    int recentMoves = tracker.getPacketHistory().size();
                    if (recentMoves >= 8) {
                        double severity = Math.min(0.97, 0.82 + (pending - 15) * 0.01 + (sinceLastNanos / 1_000_000_000.0 - 1.5) * 0.05);
                        CheckResult stallResult = CheckResult.failed(
                                ViolationType.BLINK,
                                severity,
                                String.format("BLINK_TXN_STALL pending=%d sinceLast=%.2fs moves=%d", pending, sinceLastNanos / 1_000_000_000.0, recentMoves)
                        );
                        if (filtering.passesLayer2HeuristicFiltering(player, ViolationType.BLINK, stallResult)) {
                            return stallResult;
                        }
                    }
                }
            }
        }

        PacketBurstAnalysis analysis = analyzePacketBursts(tracker, now);

        if (analysis.hasSuspiciousBurst) {
            double trustScore = falsePositiveFilter.getPlayerTrustScore(playerId);
            int ping = filtering.getPing(player);

            double maxDelay = MAX_PACKET_DELAY;

            if (ping > 100) {
                maxDelay += ping * 1.2;
            }

            if (ping > 200) {
                maxDelay += ping * 0.8;
            }

            if (trustScore > 0.75) {
                maxDelay *= 1.35;
            } else if (trustScore < 0.4) {
                maxDelay *= 0.85;
            }

            if (tps < 19.5) {
                maxDelay *= 1.15;
            }

            if (analysis.maxDelay > maxDelay) {
                double severity = Math.min(0.85, 0.45 + (analysis.maxDelay - maxDelay) / (maxDelay * 2));

                CheckResult prelimResult = CheckResult.failed(
                    ViolationType.BLINK,
                    severity,
                    String.format("Packet delay: %dms (max: %.0fms), burst: %.0f packets/sec, ping: %dms",
                        analysis.maxDelay, maxDelay, analysis.burstRate, ping)
                );

                if (!filtering.passesLayer2HeuristicFiltering(player, ViolationType.BLINK, prelimResult)) {
                    tracker.resetViolations();
                    falsePositiveFilter.recordLegitAction(player, ViolationType.BLINK);
                    return CheckResult.passed();
                }

                tracker.recordBurstViolation();

                if (tracker.getBurstViolations() < 3) {
                    return CheckResult.passed();
                }

                return prelimResult;
            }
        }

        if (analysis.hasPacketHolding) {
            long holdingDuration = analysis.holdingEndTime - analysis.holdingStartTime;

            if (player.getVelocity().length() > 0.5 || player.isFlying() || player.isGliding()) {
                return CheckResult.passed();
            }

            if (holdingDuration > CHUNK_LOAD_DELAY_THRESHOLD && analysis.heldPacketCount > 10) {
                double severity = Math.min(0.85, 0.55 + holdingDuration / 5000.0);

                CheckResult prelimResult = CheckResult.failed(
                    ViolationType.BLINK,
                    severity,
                    String.format("Packet holding: %dms gap, %d packets", holdingDuration, analysis.heldPacketCount)
                );

                if (!filtering.passesLayer2HeuristicFiltering(player, ViolationType.BLINK, prelimResult)) {
                    tracker.resetViolations();
                    return CheckResult.passed();
                }

                tracker.recordHoldingViolation();

                if (tracker.getHoldingViolations() < 4) {
                    return CheckResult.passed();
                }

                return prelimResult;
            }
        }

        if (tracker.getConsistentDelayCount() > 25) {
            double avgDelay = tracker.getAverageDelay();
            double variance = tracker.getDelayVariance();

            if (variance < 8.0 && avgDelay > 80) {
                double severity = 0.75;

                CheckResult prelimResult = CheckResult.failed(
                    ViolationType.BLINK,
                    severity,
                    String.format("Artificial delay: %.1fms avg, %.2fms var", avgDelay, variance)
                );

                if (!filtering.passesLayer2HeuristicFiltering(player, ViolationType.BLINK, prelimResult)) {
                    tracker.resetViolations();
                    return CheckResult.passed();
                }

                tracker.recordArtificialViolation();

                if (tracker.getArtificialViolations() < 5) {
                    return CheckResult.passed();
                }

                return prelimResult;
            }
        }

        falsePositiveFilter.recordLegitAction(player, ViolationType.BLINK);
        return CheckResult.passed();
    }

    private PacketBurstAnalysis analyzePacketBursts(BlinkTracker tracker, long now) {
        PacketBurstAnalysis analysis = new PacketBurstAnalysis();
        BoundedDeque<PacketRecord> history = tracker.getPacketHistory();

        if (history.size() < 2) {
            return analysis;
        }

        java.util.List<PacketRecord> packetList = history.toList();

        if (packetList.size() < 2) {
            return analysis;
        }

        PacketRecord prev = null;
        long maxGap = 0;
        long gapStart = 0;

        for (PacketRecord packet : packetList) {
            if (prev != null) {
                long gap = packet.timestamp - prev.timestamp;

                if (gap > maxGap) {
                    maxGap = gap;
                    gapStart = prev.timestamp;
                }

                analysis.maxDelay = Math.max(analysis.maxDelay, gap);
            }
            prev = packet;
        }

        int left = 0;
        for (int right = 0; right < packetList.size(); right++) {
            while (packetList.get(right).timestamp - packetList.get(left).timestamp > PACKET_WINDOW) {
                left++;
            }
            int packetsInWindow = right - left + 1;
            if (packetsInWindow > analysis.burstPacketCount) {
                analysis.burstPacketCount = packetsInWindow;
                analysis.burstStartTime = packetList.get(left).timestamp;
                analysis.burstRate = packetsInWindow;
            }
        }

        if (analysis.burstRate > PACKET_BURST_THRESHOLD) {
            analysis.hasSuspiciousBurst = true;
        }

        if (maxGap > MAX_PACKET_DELAY) {
            int packetsAfterGap = 0;
            for (PacketRecord packet : packetList) {
                if (packet.timestamp > gapStart + maxGap &&
                    packet.timestamp < gapStart + maxGap + 800) {
                    packetsAfterGap++;
                }
            }

            if (packetsAfterGap > 8) {
                analysis.hasPacketHolding = true;
                analysis.holdingStartTime = gapStart;
                analysis.holdingEndTime = gapStart + maxGap;
                analysis.heldPacketCount = packetsAfterGap;
            }
        }

        return analysis;
    }

    private double getCurrentTPS() {
        return NC.noChance.core.TPSCache.getTPS();
    }

    private boolean isPlayerLoadingChunks(Player player, BlinkTracker tracker) {
        Location loc = player.getLocation();
        if (loc.getWorld() == null) return false;

        int chunkX = loc.getBlockX() >> 4;
        int chunkZ = loc.getBlockZ() >> 4;

        boolean hasUnloadedChunks = false;
        for (int x = -2; x <= 2; x++) {
            for (int z = -2; z <= 2; z++) {
                if (!loc.getWorld().isChunkLoaded(chunkX + x, chunkZ + z)) {
                    hasUnloadedChunks = true;
                    break;
                }
            }
            if (hasUnloadedChunks) break;
        }

        if (hasUnloadedChunks) {
            long firstSeen = tracker.getChunkLoadingTime();
            long now = System.currentTimeMillis();
            if (firstSeen == 0) {
                tracker.setChunkLoadingTime(now);
                return false;
            }
            return now - firstSeen >= 100;
        }

        if (System.currentTimeMillis() - tracker.getChunkLoadingTime() < 2000) {
            return true;
        }

        return false;
    }

    private boolean isPlayerMining(Player player) {
        PlayerData data = playerDataMap.get(player.getUniqueId());
        if (data == null) return false;

        long timeSinceBreak = System.currentTimeMillis() - data.getLastBlockBreakTime();
        if (timeSinceBreak < 800) {
            return true;
        }

        return false;
    }

    public void handleTeleport(UUID playerId) {
        BlinkTracker tracker = blinkTrackers.get(playerId);
        if (tracker == null) return;
        tracker.getPacketHistory().clear();
        tracker.resetViolations();
    }

    public void cleanup(UUID playerId) {
        blinkTrackers.remove(playerId);
        falsePositiveFilter.cleanup(playerId);
    }

    public void cleanupStale() {
        long now = System.currentTimeMillis();
        blinkTrackers.entrySet().removeIf(entry -> {
            BlinkTracker tracker = entry.getValue();
            return tracker.getLastPacketTime() < now - 60000;
        });
    }

    private static class BlinkTracker {
        private final BoundedDeque<PacketRecord> packetHistory;
        private int burstViolations;
        private int holdingViolations;
        private int artificialViolations;
        private long lastBurstViolation;
        private long lastHoldingViolation;
        private long lastArtificialViolation;
        private double averageDelay;
        private double delayVariance;
        private int consistentDelayCount;
        private long lastPacketTime;
        private long chunkLoadingTime;
        private long worldChangeTime;
        private String lastWorld;
        private long lastConsistentCheck;

        public BlinkTracker() {
            this.packetHistory = new BoundedDeque<>(200);
            this.burstViolations = 0;
            this.holdingViolations = 0;
            this.artificialViolations = 0;
            this.lastBurstViolation = 0;
            this.lastHoldingViolation = 0;
            this.lastArtificialViolation = 0;
            this.averageDelay = 0;
            this.delayVariance = 0;
            this.consistentDelayCount = 0;
            this.lastPacketTime = 0;
            this.chunkLoadingTime = 0;
            this.worldChangeTime = 0;
            this.lastWorld = null;
            this.lastConsistentCheck = 0;
        }

        public void addPacket(long timestamp, String type) {
            packetHistory.add(new PacketRecord(timestamp, type));
            updateAverageDelay();
            lastPacketTime = timestamp;
        }

        private void updateAverageDelay() {
            if (packetHistory.size() < 10) return;

            java.util.List<PacketRecord> packets = packetHistory.toList();
            if (packets.size() < 10) return;

            double sum = 0;
            int count = 0;
            java.util.List<Long> delays = new java.util.ArrayList<>();
            PacketRecord prev = null;

            for (PacketRecord packet : packets) {
                if (prev != null) {
                    long delay = packet.timestamp - prev.timestamp;
                    sum += delay;
                    delays.add(delay);
                    count++;
                }
                prev = packet;
            }

            if (count > 0) {
                double newAvg = sum / count;

                double varianceSum = 0;
                for (long delay : delays) {
                    varianceSum += Math.pow(delay - newAvg, 2);
                }
                delayVariance = varianceSum / count;

                long now = System.currentTimeMillis();
                if (now - lastConsistentCheck > 5000) {
                    consistentDelayCount = 0;
                }
                lastConsistentCheck = now;

                if (Math.abs(newAvg - averageDelay) < 5.0 && delayVariance < 3.0) {
                    consistentDelayCount++;
                } else {
                    consistentDelayCount = 0;
                }
                averageDelay = newAvg;
            }
        }

        public void cleanOldPackets(long cutoff) {
            while (!packetHistory.isEmpty() && packetHistory.peek().timestamp < cutoff) {
                packetHistory.poll();
            }
        }

        public void recordBurstViolation() {
            long now = System.currentTimeMillis();
            if (now - lastBurstViolation < 5000) {
                burstViolations++;
            } else {
                burstViolations = 1;
            }
            lastBurstViolation = now;
        }

        public void recordHoldingViolation() {
            long now = System.currentTimeMillis();
            if (now - lastHoldingViolation < 5000) {
                holdingViolations++;
            } else {
                holdingViolations = 1;
            }
            lastHoldingViolation = now;
        }

        public void recordArtificialViolation() {
            long now = System.currentTimeMillis();
            if (now - lastArtificialViolation < 5000) {
                artificialViolations++;
            } else {
                artificialViolations = 1;
            }
            lastArtificialViolation = now;
        }

        public void resetViolations() {
            burstViolations = 0;
            holdingViolations = 0;
            artificialViolations = 0;
            consistentDelayCount = 0;
        }

        public BoundedDeque<PacketRecord> getPacketHistory() {
            return packetHistory;
        }

        public int getBurstViolations() {
            return burstViolations;
        }

        public int getHoldingViolations() {
            return holdingViolations;
        }

        public int getArtificialViolations() {
            return artificialViolations;
        }

        public double getAverageDelay() {
            return averageDelay;
        }

        public double getDelayVariance() {
            return delayVariance;
        }

        public int getConsistentDelayCount() {
            return consistentDelayCount;
        }

        public long getLastPacketTime() {
            return lastPacketTime;
        }

        public void setChunkLoadingTime(long time) {
            this.chunkLoadingTime = time;
        }

        public long getChunkLoadingTime() {
            return chunkLoadingTime;
        }

        public void checkWorldChange(String currentWorld) {
            if (lastWorld != null && !lastWorld.equals(currentWorld)) {
                worldChangeTime = System.currentTimeMillis();
            }
            lastWorld = currentWorld;
        }

        public boolean isInWorldChangeGrace() {
            return worldChangeTime > 0 && (System.currentTimeMillis() - worldChangeTime) < 5000;
        }
    }

    private static class PacketRecord {
        final long timestamp;
        final String type;

        PacketRecord(long timestamp, String type) {
            this.timestamp = timestamp;
            this.type = type;
        }
    }

    private static class PacketBurstAnalysis {
        boolean hasSuspiciousBurst = false;
        boolean hasPacketHolding = false;
        double burstRate = 0;
        int burstPacketCount = 0;
        long burstStartTime = 0;
        long maxDelay = 0;
        long holdingStartTime = 0;
        long holdingEndTime = 0;
        int heldPacketCount = 0;
    }
}