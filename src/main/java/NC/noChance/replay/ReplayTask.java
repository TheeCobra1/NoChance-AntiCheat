package NC.noChance.replay;

import NC.noChance.NoChance;
import NC.noChance.core.LangManager;
import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.*;
import java.util.logging.Level;

public class ReplayTask extends BukkitRunnable {
    private final Plugin plugin;
    private final Player viewer;
    private final ReplayData data;
    private final List<Snapshot> snapshots;
    private final List<BlockAction> blockActions;
    private final FakePlayer fakePlayer;
    private final Location originalLocation;
    private final GameMode originalGameMode;
    private final Map<Long, Long> breakStartTimes;
    private final Map<Long, int[]> changedBlocks;
    private final BlockStats blockStats;
    private final World cachedWorld;
    private final ReplayOverlay overlay;

    private volatile int index;
    private volatile int blockIndex;
    private volatile long startTime;
    private volatile long baseTimestamp;
    private volatile boolean paused;
    private volatile float speed;
    private final java.util.concurrent.atomic.AtomicBoolean finished = new java.util.concurrent.atomic.AtomicBoolean(false);
    private volatile boolean following;
    private volatile boolean showHud;
    private volatile Snapshot lastSnap;
    private volatile float lastHealth;
    private volatile int hudTick;

    public ReplayTask(Plugin plugin, Player viewer, ReplayData data) {
        this.plugin = plugin;
        this.viewer = viewer;
        this.data = data;
        this.snapshots = data.getSnapshots();
        this.blockActions = new ArrayList<>(data.getBlockActions());
        this.blockActions.sort(Comparator.comparingLong(a -> a.timestamp));
        this.breakStartTimes = new HashMap<>();
        this.changedBlocks = new HashMap<>();
        this.blockStats = new BlockStats();
        this.index = 0;
        this.blockIndex = 0;
        this.paused = false;
        this.speed = 1.0f;
        this.finished.set(false);
        this.following = false;
        this.showHud = true;
        this.lastHealth = 20;
        this.hudTick = 0;
        this.originalLocation = viewer.getLocation().clone();
        this.originalGameMode = viewer.getGameMode();

        World world = Bukkit.getWorld(data.getWorld());
        if (world == null) world = viewer.getWorld();
        this.cachedWorld = world;

        this.fakePlayer = new FakePlayer(viewer, data.getPlayerName(), cachedWorld, net.kyori.adventure.text.format.NamedTextColor.GRAY, data.getPlayerId());
        this.overlay = new ReplayOverlay();
    }

    private LangManager lang() {
        return ((NoChance) plugin).getLangManager();
    }

    public void start() {
        if (snapshots.isEmpty()) {
            viewer.sendMessage(lang().get(viewer, "replay.task.no_data"));
            finished.set(true);
            return;
        }

        Snapshot first = snapshots.get(0);
        baseTimestamp = first.timestamp;
        lastHealth = first.health;

        double offsetX = -Math.sin(Math.toRadians(first.yaw)) * 8;
        double offsetZ = Math.cos(Math.toRadians(first.yaw)) * 8;
        Location start = new Location(cachedWorld, first.x + offsetX, first.y + 5, first.z + offsetZ, first.yaw, 15);
        viewer.setGameMode(GameMode.SPECTATOR);
        viewer.teleport(start);

        fakePlayer.applySkin(data.getPlayerId());
        fakePlayer.spawn(first.x, first.y, first.z, first.yaw, first.pitch);
        fakePlayer.sendSpawnPackets();
        overlay.init(viewer, cachedWorld, data.getPlayerName());

        viewer.sendMessage(lang().get(viewer, "replay.task.header",
                "player", data.getPlayerName(),
                "type", data.getViolationType().getDisplayName()));
        viewer.sendMessage(lang().get(viewer, "replay.task.duration",
                "duration", String.format("%.1f", data.getDuration() / 1000.0),
                "confidence", data.getConfidence()));
        viewer.sendMessage(lang().get(viewer, "replay.task.controls"));

        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!finished.get() && viewer.isOnline()) {
                fakePlayer.sendSpawnEntity();
                fakePlayer.setEquipment(
                    first.mainHand, first.offHand,
                    first.helmet, first.chest, first.legs, first.boots
                );
                startTime = System.currentTimeMillis();
                this.runTaskTimer(plugin, 0L, 1L);
            }
        }, 5L);
    }

    @Override
    public void run() {
        if (finished.get() || !viewer.isOnline()) {
            stop();
            return;
        }

        if (paused) return;

        long elapsed = (long) ((System.currentTimeMillis() - startTime) * speed);
        long targetTime = baseTimestamp + elapsed;

        while (index < snapshots.size()) {
            Snapshot snap = snapshots.get(index);
            if (snap.timestamp > targetTime) break;

            processSnapshot(snap);
            lastSnap = snap;
            index++;
        }

        processBlocks(targetTime);

        if (following && lastSnap != null && fakePlayer.isSpawned()) {
            Location fpLoc = fakePlayer.getLocation();
            double offX = -Math.sin(Math.toRadians(fpLoc.getYaw())) * 6;
            double offZ = Math.cos(Math.toRadians(fpLoc.getYaw())) * 6;
            Location target = new Location(cachedWorld, fpLoc.getX() + offX, fpLoc.getY() + 3, fpLoc.getZ() + offZ, fpLoc.getYaw(), 15);
            viewer.teleport(target);
        }

        hudTick++;
        if (showHud && lastSnap != null && hudTick % 4 == 0) {
            sendHud(lastSnap, targetTime - baseTimestamp);
        }

        if (index >= snapshots.size()) {
            viewer.sendMessage(lang().get(viewer, "replay.task.finished"));
            if (blockStats.totalBreaks > 0 || blockStats.totalPlaces > 0) {
                showStats();
            }
            stop();
        }
    }

    private void processSnapshot(Snapshot snap) {
        if (!fakePlayer.isSpawned()) return;
        fakePlayer.move(snap.x, snap.y, snap.z, snap.yaw, snap.pitch, snap.onGround);
        overlay.processSnapshot(snap, lastSnap);

        switch (snap.action) {
            case SWING:
            case ATTACK:
            case BREAK_BLOCK:
                fakePlayer.swing();
                break;
            case BOW_SHOOT:
                fakePlayer.swing();
                showBowParticle(snap);
                break;
            default:
                break;
        }

        if (snap.damage > 0) {
            showDamage(snap, snap.damage);
        }

        if (snap.health < lastHealth - 0.5) {
            double dmg = lastHealth - snap.health;
            showDamageTaken(snap, dmg);
            fakePlayer.hurt();
        }
        lastHealth = snap.health;

        fakePlayer.sneak(snap.sneaking);
        fakePlayer.setSprinting(snap.sprinting);
        fakePlayer.setGliding(snap.gliding);
        fakePlayer.setSwimming(snap.swimming);

        if (lastSnap == null || equipChanged(snap, lastSnap)) {
            fakePlayer.setEquipment(
                snap.mainHand, snap.offHand,
                snap.helmet, snap.chest, snap.legs, snap.boots
            );
        }
    }

    private void showDamage(Snapshot snap, double damage) {
        double rad = Math.toRadians(snap.yaw);
        double tx = snap.x - Math.sin(rad) * 3;
        double tz = snap.z + Math.cos(rad) * 3;
        Location target = new Location(cachedWorld, tx, snap.y + 1.5, tz);

        viewer.spawnParticle(Particle.DAMAGE_INDICATOR, target, (int) Math.ceil(damage), 0.3, 0.3, 0.3, 0);
        if (isNearViewer(target, 25)) {
            viewer.playSound(target, Sound.ENTITY_PLAYER_HURT, 0.3f, 1.0f);
        }
    }

    private void showDamageTaken(Snapshot snap, double damage) {
        Location loc = new Location(cachedWorld, snap.x, snap.y + 1.2, snap.z);
        viewer.spawnParticle(Particle.DAMAGE_INDICATOR, loc, (int) Math.ceil(damage * 2), 0.3, 0.5, 0.3, 0);
    }

    private void showBowParticle(Snapshot snap) {
        Location loc = new Location(cachedWorld, snap.x, snap.y + 1.5, snap.z);
        viewer.spawnParticle(Particle.CRIT, loc, 8, 0.2, 0.2, 0.2, 0.1);
    }

    private void sendHud(Snapshot snap, long elapsedMs) {
        StringBuilder sb = new StringBuilder();
        sb.append("§c").append(data.getPlayerName());
        sb.append(" §8| §7❤§f").append(String.format("%.1f", snap.health));
        sb.append(" §8| §7🍖§f").append(snap.food);

        if (snap.fallDist > 0.5) {
            sb.append(" §8| §7↓§e").append(String.format("%.1f", snap.fallDist));
        }

        if (snap.potionCount > 0) {
            sb.append(" §8| §d⚗").append(snap.potionCount);
        }

        if (snap.fireTicks > 0) {
            sb.append(" §8| §6🔥");
        }

        String state = "";
        if (snap.gliding) state = "§b[Glide]";
        else if (snap.swimming) state = "§3[Swim]";
        else if (snap.sneaking) state = "§7[Sneak]";
        else if (snap.sprinting) state = "§a[Sprint]";
        else if (snap.jumping) state = "§e[Jump]";
        else if (!snap.onGround) state = "§c[Air]";

        if (!state.isEmpty()) sb.append(" ").append(state);

        if (snap.mainHand != null && snap.mainHand != Material.AIR) {
            sb.append(" §8| §f").append(formatMat(snap.mainHand));
        }

        sb.append(" §8| §7").append(String.format("%.1fs", elapsedMs / 1000.0));
        sb.append("§8/§7").append(String.format("%.1fs", data.getDuration() / 1000.0));
        sb.append(" §8(§f").append(speed).append("x§8)");

        double dev = overlay.getDeviation();
        if (dev > 0.5) {
            sb.append(" §8| §cDev:§f").append(String.format("%.1f", dev));
        }

        viewer.spigot().sendMessage(ChatMessageType.ACTION_BAR, new TextComponent(sb.toString()));
    }

    private boolean equipChanged(Snapshot a, Snapshot b) {
        if (b == null) return true;
        return a.mainHand != b.mainHand || a.offHand != b.offHand ||
               a.helmet != b.helmet || a.chest != b.chest ||
               a.legs != b.legs || a.boots != b.boots;
    }

    private String formatMat(Material mat) {
        String name = mat.name().toLowerCase().replace("_", " ");
        if (name.length() > 12) {
            name = name.substring(0, 12);
        }
        return name;
    }

    private void processBlocks(long targetTime) {
        while (blockIndex < blockActions.size()) {
            BlockAction ba = blockActions.get(blockIndex);
            if (ba.timestamp > targetTime) break;

            long key = packCoord(ba.x, ba.y, ba.z);

            switch (ba.type) {
                case START_BREAK:
                    breakStartTimes.put(key, ba.timestamp);
                    fakePlayer.sendBreakAnim(ba.x, ba.y, ba.z, 0);
                    break;
                case BREAKING:
                    int stage = Math.min(9, (int) (ba.progress * 10));
                    fakePlayer.sendBreakAnim(ba.x, ba.y, ba.z, stage);
                    break;
                case BREAK:
                    Long started = breakStartTimes.remove(key);
                    int breakMs = ba.breakTimeMs > 0 ? ba.breakTimeMs : (started != null ? (int) (ba.timestamp - started) : 0);
                    boolean suspicious = ba.expectedTimeMs > 0 && breakMs < ba.expectedTimeMs * 0.7;

                    fakePlayer.sendBreakAnim(ba.x, ba.y, ba.z, -1);
                    changedBlocks.putIfAbsent(key, new int[]{ba.x, ba.y, ba.z});
                    fakePlayer.sendBlockChange(ba.x, ba.y, ba.z, Material.AIR);

                    if (isNearViewerXYZ(ba.x, ba.y, ba.z, 25)) {
                        Location loc = new Location(cachedWorld, ba.x + 0.5, ba.y + 0.5, ba.z + 0.5);
                        viewer.playSound(loc, getSoundForBlock(ba.block), 0.4f, 1.0f);
                    }

                    if (suspicious) {
                        Location loc = new Location(cachedWorld, ba.x + 0.5, ba.y + 0.5, ba.z + 0.5);
                        Particle.DustOptions red = new Particle.DustOptions(Color.RED, 1.5f);
                        viewer.spawnParticle(Particle.DUST, loc, 12, 0.4, 0.4, 0.4, red);
                    }

                    blockStats.recordBreak(ba.block, breakMs, ba.expectedTimeMs);
                    break;
                case PLACE:
                    changedBlocks.putIfAbsent(key, new int[]{ba.x, ba.y, ba.z});
                    fakePlayer.sendBlockChange(ba.x, ba.y, ba.z, ba.block);

                    if (isNearViewerXYZ(ba.x, ba.y, ba.z, 25)) {
                        Location loc = new Location(cachedWorld, ba.x + 0.5, ba.y + 0.5, ba.z + 0.5);
                        viewer.playSound(loc, getSoundForPlace(ba.block), 0.3f, 1.0f);
                    }

                    blockStats.recordPlace(ba.block);
                    break;
                case CANCEL:
                    breakStartTimes.remove(key);
                    fakePlayer.sendBreakAnim(ba.x, ba.y, ba.z, -1);
                    break;
            }
            blockIndex++;
        }
    }

    private Sound getSoundForBlock(Material mat) {
        if (mat == null) return Sound.BLOCK_STONE_BREAK;
        String name = mat.name();
        if (name.contains("GLASS")) return Sound.BLOCK_GLASS_BREAK;
        if (name.contains("WOOD") || name.contains("LOG") || name.contains("PLANKS")) return Sound.BLOCK_WOOD_BREAK;
        if (name.contains("GRASS") || name.contains("DIRT")) return Sound.BLOCK_GRASS_BREAK;
        if (name.contains("SAND") || name.contains("GRAVEL")) return Sound.BLOCK_SAND_BREAK;
        if (name.contains("WOOL")) return Sound.BLOCK_WOOL_BREAK;
        return Sound.BLOCK_STONE_BREAK;
    }

    private Sound getSoundForPlace(Material mat) {
        if (mat == null) return Sound.BLOCK_STONE_PLACE;
        String name = mat.name();
        if (name.contains("GLASS")) return Sound.BLOCK_GLASS_PLACE;
        if (name.contains("WOOD") || name.contains("LOG") || name.contains("PLANKS")) return Sound.BLOCK_WOOD_PLACE;
        if (name.contains("GRASS") || name.contains("DIRT")) return Sound.BLOCK_GRASS_PLACE;
        if (name.contains("SAND") || name.contains("GRAVEL")) return Sound.BLOCK_SAND_PLACE;
        if (name.contains("WOOL")) return Sound.BLOCK_WOOL_PLACE;
        return Sound.BLOCK_STONE_PLACE;
    }

    private boolean isNearViewer(Location loc, double maxDist) {
        Location viewerLoc = viewer.getLocation();
        if (!Objects.equals(viewerLoc.getWorld(), loc.getWorld())) return false;
        return viewerLoc.distanceSquared(loc) <= maxDist * maxDist;
    }

    private boolean isNearViewerXYZ(int x, int y, int z, double maxDist) {
        Location vl = viewer.getLocation();
        double dx = vl.getX() - (x + 0.5);
        double dy = vl.getY() - (y + 0.5);
        double dz = vl.getZ() - (z + 0.5);
        return (dx * dx + dy * dy + dz * dz) <= maxDist * maxDist;
    }

    private long packCoord(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
    }

    public void stop() {
        if (!finished.compareAndSet(false, true)) return;

        int eid = fakePlayer.getEntityId();
        restoreBlocks();
        overlay.destroy();
        fakePlayer.destroy();
        plugin.getLogger().fine("Destroyed replay NPC entity " + eid);

        if (viewer.isOnline()) {
            viewer.teleport(originalLocation);
            viewer.setGameMode(originalGameMode);
            viewer.sendMessage(lang().get(viewer, "replay.task.stopped"));
        }

        try {
            cancel();
        } catch (IllegalStateException e) {
            plugin.getLogger().log(Level.WARNING, "ReplayTask already cancelled", e);
        }
    }

    private void restoreBlocks() {
        for (int[] coords : changedBlocks.values()) {
            fakePlayer.sendBlockRestore(coords[0], coords[1], coords[2]);
        }
        changedBlocks.clear();
    }

    public void pause() {
        if (paused) {
            paused = false;
            startTime = System.currentTimeMillis() - (long) ((snapshots.get(Math.min(index, snapshots.size() - 1)).timestamp - baseTimestamp) / speed);
            viewer.sendMessage(lang().get(viewer, "replay.task.resumed"));
        } else {
            paused = true;
            viewer.sendMessage(lang().get(viewer, "replay.task.paused"));
        }
    }

    public void setSpeed(float speed) {
        this.speed = Math.max(0.25f, Math.min(4.0f, speed));
        if (index < snapshots.size()) {
            long currentProgress = snapshots.get(index).timestamp - baseTimestamp;
            startTime = System.currentTimeMillis() - (long) (currentProgress / this.speed);
        }
        viewer.sendMessage(lang().get(viewer, "replay.task.speed_set", "speed", this.speed));
    }

    public void toggleFollow() {
        following = !following;
        String state = lang().get(viewer, following ? "replay.task.state_on" : "replay.task.state_off");
        viewer.sendMessage(lang().get(viewer, "replay.task.follow_set", "state", state));
    }

    public void toggleHud() {
        showHud = !showHud;
        String state = lang().get(viewer, showHud ? "replay.task.state_on" : "replay.task.state_off");
        viewer.sendMessage(lang().get(viewer, "replay.task.hud_set", "state", state));
    }

    public boolean isFinished() {
        return finished.get();
    }

    public ReplayData getData() {
        return data;
    }

    public void showStats() {
        viewer.sendMessage(lang().get(viewer, "replay.task.stats_header"));
        viewer.sendMessage(lang().get(viewer, "replay.task.stats_breaks",
                "total", blockStats.totalBreaks,
                "suspicious", blockStats.suspiciousBreaks));
        viewer.sendMessage(lang().get(viewer, "replay.task.stats_places", "total", blockStats.totalPlaces));
        if (blockStats.totalBreaks > 0) {
            viewer.sendMessage(lang().get(viewer, "replay.task.stats_avg",
                    "ms", blockStats.totalBreakTime / blockStats.totalBreaks));
        }
        if (blockStats.fastestBreak < Integer.MAX_VALUE) {
            String fastColor = blockStats.fastestBreakSus ? "§c" : "§a";
            viewer.sendMessage(lang().get(viewer, "replay.task.stats_fastest",
                    "color", fastColor,
                    "ms", blockStats.fastestBreak,
                    "material", formatMat(blockStats.fastestMat)));
        }
    }

    private static class BlockStats {
        int totalBreaks = 0;
        int totalPlaces = 0;
        int suspiciousBreaks = 0;
        long totalBreakTime = 0;
        int fastestBreak = Integer.MAX_VALUE;
        Material fastestMat = Material.AIR;
        boolean fastestBreakSus = false;

        void recordBreak(Material mat, int breakMs, int expectedMs) {
            totalBreaks++;
            if (breakMs > 0) {
                totalBreakTime += breakMs;
                if (breakMs < fastestBreak) {
                    fastestBreak = breakMs;
                    fastestMat = mat;
                    fastestBreakSus = expectedMs > 0 && breakMs < expectedMs * 0.7;
                }
                if (expectedMs > 0 && breakMs < expectedMs * 0.7) {
                    suspiciousBreaks++;
                }
            }
        }

        void recordPlace(Material mat) {
            totalPlaces++;
        }
    }
}
