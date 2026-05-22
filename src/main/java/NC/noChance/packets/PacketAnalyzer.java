package NC.noChance.packets;

import NC.noChance.core.*;
import NC.noChance.detection.player.BlinkCheck;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.client.*;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

public class PacketAnalyzer {
    private final Plugin plugin;
    private final Map<UUID, PacketData> packetDataMap;
    private final MovementPacketAnalyzer movementAnalyzer;
    private final CombatPacketAnalyzer combatAnalyzer;
    private final BlockPacketAnalyzer blockAnalyzer;
    private final ACConfig config;
    private final BlinkCheck blinkCheck;
    private PacketFingerprint packetFingerprint;
    private TransactionTracker transactionTracker;
    private PacketIntegrityCheck integrityCheck;
    private PacketListenerAbstract registeredListener;

    public PacketAnalyzer(Plugin plugin, ACConfig config, BlinkCheck blinkCheck) {
        this.plugin = plugin;
        this.config = config;
        this.blinkCheck = blinkCheck;
        this.packetDataMap = new ConcurrentHashMap<>();
        this.movementAnalyzer = new MovementPacketAnalyzer(config);
        this.combatAnalyzer = new CombatPacketAnalyzer(config, plugin);
        this.blockAnalyzer = new BlockPacketAnalyzer(config);

        registerPacketListeners();
    }

    public void setPacketFingerprint(PacketFingerprint packetFingerprint) {
        this.packetFingerprint = packetFingerprint;
    }

    public void setTransactionTracker(TransactionTracker transactionTracker) {
        this.transactionTracker = transactionTracker;
    }

    public void setIntegrityCheck(PacketIntegrityCheck integrityCheck) {
        this.integrityCheck = integrityCheck;
    }

    public void shutdown() {
        if (registeredListener != null) {
            try {
                PacketEvents.getAPI().getEventManager().unregisterListener(registeredListener);
            } catch (Throwable ignored) {
            }
            registeredListener = null;
        }
        packetDataMap.clear();
    }

    private void registerPacketListeners() {
        registeredListener = new PacketListenerAbstract() {
            @Override
            public void onPacketReceive(PacketReceiveEvent event) {
                Player player = null;
                try {
                    Object playerObj = event.getPlayer();
                    if (playerObj == null || !(playerObj instanceof Player)) {
                        return;
                    }

                    player = (Player) playerObj;

                    if (integrityCheck != null && integrityCheck.inspect(event, player)) {
                        return;
                    }

                    PacketData data = packetDataMap.computeIfAbsent(player.getUniqueId(), k -> new PacketData());

                    long timestamp = System.currentTimeMillis();
                    String packetType = event.getPacketType().getName();

                    if (blinkCheck != null) {
                        blinkCheck.onPacketReceived(player, packetType, timestamp);
                    }

                    if (packetFingerprint != null) {
                        packetFingerprint.record(player.getUniqueId(), packetType, timestamp);
                    }

                    if (transactionTracker != null && event.getPacketType() == PacketType.Play.Client.PONG) {
                        try {
                            WrapperPlayClientPong pong = new WrapperPlayClientPong(event);
                            transactionTracker.onPong(player, pong.getId());
                        } catch (Throwable ignored) {
                        }
                    }

                    if (event.getPacketType() == PacketType.Play.Client.PLAYER_POSITION ||
                        event.getPacketType() == PacketType.Play.Client.PLAYER_POSITION_AND_ROTATION ||
                        event.getPacketType() == PacketType.Play.Client.PLAYER_ROTATION ||
                        event.getPacketType() == PacketType.Play.Client.PLAYER_FLYING) {
                        movementAnalyzer.analyzeMovementPacket(event, player, data);
                    } else if (event.getPacketType() == PacketType.Play.Client.INTERACT_ENTITY ||
                               event.getPacketType() == PacketType.Play.Client.ANIMATION) {
                        combatAnalyzer.analyzeCombatPacket(event, player, data);
                    } else if (event.getPacketType() == PacketType.Play.Client.PLAYER_BLOCK_PLACEMENT ||
                               event.getPacketType() == PacketType.Play.Client.PLAYER_DIGGING) {
                        blockAnalyzer.analyzeBlockPacket(event, player, data);
                    }
                } catch (IndexOutOfBoundsException e) {
                    if (player != null) {
                        PacketData pd = packetDataMap.get(player.getUniqueId());
                        if (pd != null) pd.recordMalformedPacket();
                    }
                } catch (Exception e) {
                    if (e.getMessage() == null || !e.getMessage().contains("readerIndex")) {
                        plugin.getLogger().warning("PacketAnalyzer error: " + e.getMessage());
                    }
                }
            }
        };
        PacketEvents.getAPI().getEventManager().registerListener(registeredListener);
    }

    public PacketViolationResult analyzeForViolations(Player player, ViolationType type) {
        PacketData data = packetDataMap.get(player.getUniqueId());
        if (data == null) {
            return new PacketViolationResult(false, 0.0, "No packet data");
        }

        switch (type) {
            case FLY:
            case SPEED:
            case NOCLIP:
            case JESUS:
            case PHASE:
            case STEP:
            case NOSLOW:
            case GROUNDSPOOF:
            case ELYTRAFLY:
            case STRIDER:
            case BOATFLY:
            case STRAFE:
            case SPIDER:
                return movementAnalyzer.checkMovementViolation(player, data, type);

            case KILLAURA:
            case KILLAURA_MULTI:
            case KILLAURA_ANGLE:
            case KILLAURA_ROTATION:
            case KILLAURA_PATTERN:
            case AUTOCLICKER:
            case REACH:
            case CRITICALS:
            case VELOCITY:
                return combatAnalyzer.checkCombatViolation(player, data, type);

            case SCAFFOLD:
            case FASTPLACE:
            case FASTBREAK:
            case NUKER:
                return blockAnalyzer.checkBlockViolation(player, data, type);

            case NOFALL:
            case INVENTORY:
            case TIMER:
            case BLINK:
                return new PacketViolationResult(false, 0.0, "Handled by dedicated check");

            default:
                return new PacketViolationResult(false, 0.0, "Type not supported");
        }
    }

    public void cleanup(UUID playerId) {
        packetDataMap.remove(playerId);
        if (integrityCheck != null) integrityCheck.cleanup(playerId);
    }

    public static class PacketViolationResult {
        public final boolean violated;
        public final double severity;
        public final String reason;

        public PacketViolationResult(boolean violated, double severity, String reason) {
            this.violated = violated;
            this.severity = severity;
            this.reason = reason;
        }
    }

    public static class PacketData {
        private final Deque<Long> movementPacketTimes = new ConcurrentLinkedDeque<>();
        private final Deque<Long> combatPacketTimes = new ConcurrentLinkedDeque<>();
        private final Deque<Long> blockPacketTimes = new ConcurrentLinkedDeque<>();
        private final Map<String, Object> metadata = new ConcurrentHashMap<>();

        public void recordPacket(PacketCategory category) {
            long now = System.currentTimeMillis();

            switch (category) {
                case MOVEMENT:
                    movementPacketTimes.addLast(now);
                    if (movementPacketTimes.size() > 50) movementPacketTimes.removeFirst();
                    break;
                case COMBAT:
                    combatPacketTimes.addLast(now);
                    if (combatPacketTimes.size() > 50) combatPacketTimes.removeFirst();
                    break;
                case BLOCK:
                    blockPacketTimes.addLast(now);
                    if (blockPacketTimes.size() > 50) blockPacketTimes.removeFirst();
                    break;
            }
        }

        public Deque<Long> getMovementPacketTimes() { return movementPacketTimes; }
        public Deque<Long> getCombatPacketTimes() { return combatPacketTimes; }
        public Deque<Long> getBlockPacketTimes() { return blockPacketTimes; }

        public void setMetadata(String key, Object value) {
            if (value == null) {
                metadata.remove(key);
            } else {
                metadata.put(key, value);
            }
        }
        public void removeMetadata(String key) { metadata.remove(key); }
        public Object getMetadata(String key) { return metadata.get(key); }

        private volatile int malformedPacketCount = 0;
        private volatile long lastMalformedTime = 0;
        public void recordMalformedPacket() {
            malformedPacketCount++;
            lastMalformedTime = System.currentTimeMillis();
        }
        public int getMalformedPacketCount() { return malformedPacketCount; }
        public long getLastMalformedTime() { return lastMalformedTime; }
    }

    public enum PacketCategory {
        MOVEMENT,
        COMBAT,
        BLOCK
    }
}
