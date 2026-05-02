package NC.noChance.replay;

import NC.noChance.NoChance;
import NC.noChance.core.LangManager;
import NC.noChance.core.ViolationType;
import org.bukkit.Bukkit;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class ReplayMgr {
    private final Plugin plugin;
    private final ReplayIO io;
    private final Map<UUID, ReplayBuffer> buffers;
    private final Map<UUID, ReplayTask> viewers;
    private final Map<UUID, PendingReplay> pending;
    private final int bufferSize;
    private final int beforeTicks;
    private final int afterTicks;
    private final long retentionMs;
    private final boolean enabled;
    private BukkitTask captureTask;
    private BukkitTask cleanupTask;

    public ReplayMgr(Plugin plugin, int bufferSeconds, int retentionDays, boolean enabled) {
        this(plugin, bufferSeconds, retentionDays, enabled, 10, 15);
    }

    public ReplayMgr(Plugin plugin, int bufferSeconds, int retentionDays, boolean enabled, int beforeSeconds, int afterSeconds) {
        this.plugin = plugin;
        this.io = new ReplayIO(plugin.getDataFolder().toPath());
        this.buffers = new ConcurrentHashMap<>();
        this.viewers = new ConcurrentHashMap<>();
        this.pending = new ConcurrentHashMap<>();
        this.bufferSize = bufferSeconds * 20;
        this.beforeTicks = beforeSeconds * 20;
        this.afterTicks = afterSeconds * 20;
        this.retentionMs = retentionDays * 24L * 60L * 60L * 1000L;
        this.enabled = enabled;

        if (enabled) {
            startCapture();
            startCleanup();
        }
    }

    private LangManager lang() {
        return ((NoChance) plugin).getLangManager();
    }

    private void startCapture() {
        captureTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            for (Player player : Bukkit.getOnlinePlayers()) {
                ReplayBuffer buffer = buffers.computeIfAbsent(player.getUniqueId(),
                        k -> new ReplayBuffer(bufferSize));
                buffer.capture(player);

                PendingReplay pr = pending.get(player.getUniqueId());
                if (pr != null && pr.isRecording()) {
                    List<Snapshot> copy = buffer.copy();
                    if (!copy.isEmpty()) {
                        pr.addSnapshot(copy.get(copy.size() - 1));
                    }
                }
            }
        }, 1L, 1L);
    }

    private void startCleanup() {
        cleanupTask = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, () -> {
            io.cleanup(retentionMs);
        }, 20L * 60 * 60, 20L * 60 * 60);
    }

    public void onPlayerJoin(Player player) {
        if (!enabled) return;
        buffers.put(player.getUniqueId(), new ReplayBuffer(bufferSize));
    }

    public void onPlayerQuit(Player player) {
        UUID pid = player.getUniqueId();
        ReplayBuffer buffer = buffers.remove(pid);

        PendingReplay pr = pending.remove(pid);
        if (pr != null) {
            pr.stopRecording();
            List<BlockAction> blocks;
            if (buffer != null) {
                buffer.drain();
                blocks = buffer.copyBlocks();
                buffer.clear();
            } else {
                blocks = new ArrayList<>();
            }
            pr.addBlocks(blocks);
            List<Snapshot> snaps = pr.getSnapshots();

            if (snaps.size() >= 20) {
                List<BlockAction> allBlocks = pr.getBlocks();
                Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                    ReplayData data = new ReplayData(pid, pr.playerName, pr.world, pr.type,
                            pr.confidence, System.currentTimeMillis(), snaps, allBlocks);
                    try {
                        io.save(data);
                    } catch (IOException e) {
                        plugin.getLogger().warning("Failed to save replay for " + pr.playerName + ": " + e.getMessage());
                    }
                });
            }
        }

        ReplayTask task = viewers.remove(pid);
        if (task != null) {
            task.stop();
        }
    }

    public void recordAction(Player player, Snapshot.Action action) {
        if (!enabled) return;
        ReplayBuffer buffer = buffers.get(player.getUniqueId());
        if (buffer != null) {
            buffer.setAction(action);
        }
    }

    public void recordAction(Player player, Snapshot.Action action, int targetId) {
        if (!enabled) return;
        ReplayBuffer buffer = buffers.get(player.getUniqueId());
        if (buffer != null) {
            buffer.setAction(action, targetId);
        }
    }

    public void recordBlock(Player player, Block block, BlockAction.Type type, float progress) {
        if (!enabled) return;
        ReplayBuffer buffer = buffers.get(player.getUniqueId());
        if (buffer != null) {
            buffer.recordBlock(block, type, progress);
        }
    }

    public void recordBlock(Player player, Block block, BlockAction.Type type, float progress, org.bukkit.Material tool, int expectedMs) {
        if (!enabled) return;
        ReplayBuffer buffer = buffers.get(player.getUniqueId());
        if (buffer != null) {
            buffer.recordBlock(block, type, progress, tool, expectedMs);
        }
    }

    public void saveReplay(Player player, ViolationType type, String confidence) {
        if (!enabled) return;

        UUID pid = player.getUniqueId();
        if (pending.containsKey(pid)) return;

        ReplayBuffer buffer = buffers.get(pid);
        if (buffer == null || buffer.size() < 20) return;

        List<Snapshot> fullBuffer = buffer.copy();
        int takeFrom = Math.max(0, fullBuffer.size() - beforeTicks);
        List<Snapshot> before = new ArrayList<>(fullBuffer.subList(takeFrom, fullBuffer.size()));

        List<BlockAction> blocks = buffer.copyBlocks();
        long cutoffTime = before.isEmpty() ? 0 : before.get(0).timestamp;
        blocks.removeIf(b -> b.timestamp < cutoffTime);

        String worldName = player.getWorld() != null ? player.getWorld().getName() : "world";
        PendingReplay pr = new PendingReplay(player.getName(), worldName, type, confidence, before, blocks);
        pending.put(pid, pr);

        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            PendingReplay finished = pending.remove(pid);
            if (finished == null) return;

            finished.stopRecording();
            List<Snapshot> allSnaps = finished.getSnapshots();
            List<BlockAction> allBlocks = finished.getBlocks();

            ReplayBuffer buf = buffers.get(pid);
            if (buf != null) {
                allBlocks.addAll(buf.copyBlocks());
            }

            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                ReplayData data = new ReplayData(pid, finished.playerName, finished.world,
                        finished.type, finished.confidence, System.currentTimeMillis(), allSnaps, allBlocks);
                try {
                    io.save(data);
                } catch (IOException e) {
                    plugin.getLogger().warning("Failed to save replay for " + finished.playerName + ": " + e.getMessage());
                }
            });
        }, afterTicks);
    }

    public void playReplay(Player viewer, String filename) {
        ReplayTask existing = viewers.get(viewer.getUniqueId());
        if (existing != null && !existing.isFinished()) {
            viewer.sendMessage(lang().get(viewer, "replay.mgr.already_watching"));
            return;
        }

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                ReplayData data = io.load(filename);
                Bukkit.getScheduler().runTask(plugin, () -> {
                    ReplayTask task = new ReplayTask(plugin, viewer, data);
                    viewers.put(viewer.getUniqueId(), task);
                    task.start();
                });
            } catch (IOException e) {
                Bukkit.getScheduler().runTask(plugin, () -> {
                    viewer.sendMessage(lang().get(viewer, "replay.mgr.load_failed", "error", e.getMessage()));
                });
            }
        });
    }

    public void stopReplay(Player viewer) {
        ReplayTask task = viewers.remove(viewer.getUniqueId());
        if (task != null) {
            ReplayData rd = task.getData();
            task.stop();
            if (rd != null) {
                viewer.sendMessage(lang().get(viewer, "replay.mgr.ended",
                        "player", rd.getPlayerName(),
                        "type", rd.getViolationType().name()));
            }
        } else {
            viewer.sendMessage(lang().get(viewer, "replay.mgr.not_watching"));
        }
    }

    public void pauseReplay(Player viewer) {
        ReplayTask task = viewers.get(viewer.getUniqueId());
        if (task != null && !task.isFinished()) {
            task.pause();
        } else {
            viewer.sendMessage(lang().get(viewer, "replay.mgr.not_watching"));
        }
    }

    public void setReplaySpeed(Player viewer, float speed) {
        ReplayTask task = viewers.get(viewer.getUniqueId());
        if (task != null && !task.isFinished()) {
            task.setSpeed(speed);
        } else {
            viewer.sendMessage(lang().get(viewer, "replay.mgr.not_watching"));
        }
    }

    public void toggleFollow(Player viewer) {
        ReplayTask task = viewers.get(viewer.getUniqueId());
        if (task != null && !task.isFinished()) {
            task.toggleFollow();
        } else {
            viewer.sendMessage(lang().get(viewer, "replay.mgr.not_watching"));
        }
    }

    public void toggleHud(Player viewer) {
        ReplayTask task = viewers.get(viewer.getUniqueId());
        if (task != null && !task.isFinished()) {
            task.toggleHud();
        } else {
            viewer.sendMessage(lang().get(viewer, "replay.mgr.not_watching"));
        }
    }

    public void recordDamage(Player player, double damage) {
        if (!enabled) return;
        ReplayBuffer buffer = buffers.get(player.getUniqueId());
        if (buffer != null) {
            buffer.setDamage(damage);
        }
    }

    public List<String> listReplays(String playerName) {
        return io.listReplays(playerName);
    }

    public void shutdown() {
        if (captureTask != null) captureTask.cancel();
        if (cleanupTask != null) cleanupTask.cancel();
        for (ReplayTask task : viewers.values()) {
            task.stop();
        }
        buffers.clear();
        viewers.clear();
        pending.clear();
    }

    public boolean isEnabled() {
        return enabled;
    }

    public ReplayIO getIO() {
        return io;
    }

    private static class PendingReplay {
        final String playerName;
        final String world;
        final ViolationType type;
        final String confidence;
        final List<Snapshot> snapshots;
        final List<BlockAction> blocks;
        private volatile boolean recording = true;

        PendingReplay(String playerName, String world, ViolationType type, String confidence,
                      List<Snapshot> before, List<BlockAction> blocks) {
            this.playerName = playerName;
            this.world = world;
            this.type = type;
            this.confidence = confidence;
            this.snapshots = new ArrayList<>(before);
            this.blocks = new ArrayList<>(blocks);
        }

        synchronized void addSnapshot(Snapshot s) {
            if (recording && s != null) {
                snapshots.add(s);
            }
        }

        synchronized void addBlocks(List<BlockAction> b) {
            if (b != null) blocks.addAll(b);
        }

        synchronized void stopRecording() {
            recording = false;
        }

        boolean isRecording() {
            return recording;
        }

        synchronized List<Snapshot> getSnapshots() {
            return new ArrayList<>(snapshots);
        }

        synchronized List<BlockAction> getBlocks() {
            return new ArrayList<>(blocks);
        }
    }
}
