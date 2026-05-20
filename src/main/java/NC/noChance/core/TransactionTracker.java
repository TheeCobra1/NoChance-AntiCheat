package NC.noChance.core;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPing;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public class TransactionTracker {
    private static final long MIN_INTERVAL_NANOS = 100_000_000L;
    private static final int MAX_PENDING = 32;
    private static final long STALE_THRESHOLD_NANOS = 5_000_000_000L;

    private final Plugin plugin;
    private final Map<UUID, State> states = new ConcurrentHashMap<>();
    private final Random rnd = new Random();
    private volatile BukkitTask scheduledTask;
    private volatile boolean running;

    public TransactionTracker(Plugin plugin) {
        this.plugin = plugin;
    }

    public void start() {
        if (running) return;
        running = true;
        try {
            scheduledTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 2L, 2L);
        } catch (Throwable t) {
            running = false;
        }
    }

    public void shutdown() {
        running = false;
        BukkitTask t = scheduledTask;
        if (t != null) {
            try { t.cancel(); } catch (Throwable ignored) {}
            scheduledTask = null;
        }
        states.clear();
    }

    public void onPlayerQuit(UUID uuid) {
        if (uuid != null) states.remove(uuid);
    }

    public long getRoundTripNanos(Player player) {
        if (player == null) return -1L;
        State s = states.get(player.getUniqueId());
        if (s == null || s.lastConfirmedRttNanos <= 0) return -1L;
        return s.lastConfirmedRttNanos;
    }

    public long getRoundTripMillis(Player player) {
        long n = getRoundTripNanos(player);
        return n < 0 ? -1L : n / 1_000_000L;
    }

    public long getLastConfirmedNanos(Player player) {
        if (player == null) return 0L;
        State s = states.get(player.getUniqueId());
        return s == null ? 0L : s.lastConfirmedNanos;
    }

    public int getPendingCount(Player player) {
        if (player == null) return 0;
        State s = states.get(player.getUniqueId());
        return s == null ? 0 : s.pending.size();
    }

    public long getConfirmedCount(Player player) {
        if (player == null) return 0L;
        State s = states.get(player.getUniqueId());
        return s == null ? 0L : s.confirmCount.get();
    }

    public boolean hasFreshData(Player player) {
        if (player == null) return false;
        State s = states.get(player.getUniqueId());
        if (s == null || s.lastConfirmedNanos == 0L) return false;
        return System.nanoTime() - s.lastConfirmedNanos < STALE_THRESHOLD_NANOS;
    }

    public void onPong(Player player, int id) {
        if (player == null) return;
        State s = states.get(player.getUniqueId());
        if (s == null) return;
        Long sent = s.pending.remove(id);
        if (sent == null) return;
        long now = System.nanoTime();
        long rtt = now - sent;
        if (rtt < 0 || rtt > STALE_THRESHOLD_NANOS) return;
        s.lastConfirmedNanos = now;
        s.lastConfirmedRttNanos = rtt;
        s.confirmCount.incrementAndGet();
    }

    private void tick() {
        if (!running) return;
        long expireBefore = System.nanoTime() - STALE_THRESHOLD_NANOS;
        for (Player p : Bukkit.getOnlinePlayers()) {
            try { sendTransaction(p); } catch (Throwable ignored) {}
        }
        for (State s : states.values()) {
            s.pending.entrySet().removeIf(e -> e.getValue() < expireBefore);
        }
    }

    private void sendTransaction(Player player) {
        if (player == null || !player.isOnline()) return;
        UUID uuid = player.getUniqueId();
        State s = states.computeIfAbsent(uuid, k -> new State());
        long now = System.nanoTime();
        if (now - s.lastSentNanos < MIN_INTERVAL_NANOS) return;
        if (s.pending.size() >= MAX_PENDING) return;

        User user;
        try {
            user = PacketEvents.getAPI().getPlayerManager().getUser(player);
        } catch (Throwable t) {
            return;
        }
        if (user == null) return;

        int id;
        int attempts = 0;
        do {
            id = rnd.nextInt(Integer.MAX_VALUE - 1) + 1;
            if (++attempts > 8) return;
        } while (s.pending.containsKey(id));

        try {
            user.sendPacket(new WrapperPlayServerPing(id));
        } catch (Throwable t) {
            return;
        }
        s.pending.put(id, now);
        s.lastSentNanos = now;
        s.sendCount.incrementAndGet();
    }

    private static class State {
        final Map<Integer, Long> pending = new ConcurrentHashMap<>();
        volatile long lastConfirmedNanos = 0L;
        volatile long lastConfirmedRttNanos = 0L;
        volatile long lastSentNanos = 0L;
        final AtomicLong sendCount = new AtomicLong();
        final AtomicLong confirmCount = new AtomicLong();
    }
}
