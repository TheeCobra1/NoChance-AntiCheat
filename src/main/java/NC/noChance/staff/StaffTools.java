package NC.noChance.staff;

import NC.noChance.NoChance;
import NC.noChance.core.CheckResult;
import NC.noChance.core.LangManager;
import NC.noChance.core.PlayerData;
import NC.noChance.core.ViolationType;
import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class StaffTools {
    private final Plugin plugin;
    private final Map<UUID, PlayerData> playerDataMap;
    private final Set<UUID> verboseEnabled;
    private final Map<UUID, SpectateData> spectating;
    private final Set<UUID> frozenPlayers;
    private BukkitTask freezeTask;
    private BukkitTask spectateTask;
    private LiveOverlay liveOverlay;

    public StaffTools(Plugin plugin, Map<UUID, PlayerData> playerDataMap) {
        this.plugin = plugin;
        this.playerDataMap = playerDataMap;
        this.verboseEnabled = ConcurrentHashMap.newKeySet();
        this.spectating = new ConcurrentHashMap<>();
        this.frozenPlayers = ConcurrentHashMap.newKeySet();
        startTasks();
    }

    private LangManager lang() {
        return ((NoChance) plugin).getLangManager();
    }

    private String msg(Player p, String key, Object... ph) {
        return lang().get(p, key, ph);
    }

    private void startTasks() {
        freezeTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            for (UUID uuid : frozenPlayers) {
                Player player = Bukkit.getPlayer(uuid);
                if (player == null || !player.isOnline()) {
                    frozenPlayers.remove(uuid);
                    continue;
                }
                PlayerData data = playerDataMap.get(uuid);
                if (data != null && data.isFrozen()) {
                    player.spigot().sendMessage(ChatMessageType.ACTION_BAR,
                            new TextComponent(msg(player, "staff.freeze.actionbar")));
                }
            }
        }, 20L, 20L);

        spectateTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            Iterator<Map.Entry<UUID, SpectateData>> it = spectating.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<UUID, SpectateData> entry = it.next();
                Player staff = Bukkit.getPlayer(entry.getKey());
                SpectateData data = entry.getValue();
                Player target = Bukkit.getPlayer(data.targetId);

                if (staff == null || !staff.isOnline() || target == null || !target.isOnline()) {
                    it.remove();
                    if (staff != null && staff.isOnline()) {
                        staff.setGameMode(data.originalGameMode);
                        staff.teleport(data.originalLocation);
                        for (Player online : Bukkit.getOnlinePlayers()) {
                            online.showPlayer(plugin, staff);
                        }
                        staff.sendMessage(msg(staff, "staff.spectate.stopped"));
                    }
                    continue;
                }

                if (data.follow) {
                    Location loc = target.getLocation().clone();
                    loc.add(target.getLocation().getDirection().multiply(-3));
                    loc.add(0, 2, 0);
                    org.bukkit.util.Vector dir = target.getLocation().toVector().subtract(loc.toVector());
                    if (dir.lengthSquared() > 0.001) {
                        loc.setDirection(dir);
                    }
                    staff.teleport(loc);
                }

                String info;
                if (liveOverlay != null) {
                    info = liveOverlay.buildOverlay(staff, target);
                } else {
                    PlayerData pd = playerDataMap.get(target.getUniqueId());
                    info = msg(staff, "staff.spectate.actionbar",
                        "player", target.getName(),
                        "ping", pd != null ? pd.getPing() : 0,
                        "cps", String.format("%.1f", pd != null ? pd.getAverageCPS() : 0));
                }
                staff.spigot().sendMessage(ChatMessageType.ACTION_BAR, new TextComponent(info));

                if (verboseEnabled.contains(entry.getKey()) && liveOverlay != null) {
                    data.detailTick++;
                    if (data.detailTick >= 50) {
                        data.detailTick = 0;
                        List<String> details = liveOverlay.buildDetailed(staff, target);
                        for (String line : details) {
                            staff.sendMessage(line);
                        }
                    }
                }
            }
        }, 1L, 2L);
    }

    public void toggleVerbose(Player staff) {
        UUID uuid = staff.getUniqueId();
        if (verboseEnabled.contains(uuid)) {
            verboseEnabled.remove(uuid);
            staff.sendMessage(msg(staff, "staff.verbose.off_lbl"));
        } else {
            verboseEnabled.add(uuid);
            staff.sendMessage(msg(staff, "staff.verbose.on_lbl"));
        }
    }

    public boolean hasVerbose(UUID uuid) {
        return verboseEnabled.contains(uuid);
    }

    public void sendVerbose(Player target, ViolationType type, CheckResult result) {
        String msg = String.format("§8[§7V§8] §f%s §c%s §8| §7sev: §f%.2f §8| §7%s",
                target.getName(),
                type.getDisplayName(),
                result.getSeverity(),
                result.getDetails());

        for (UUID staffId : verboseEnabled) {
            Player staff = Bukkit.getPlayer(staffId);
            if (staff != null && staff.isOnline()) {
                staff.sendMessage(msg);
            }
        }
    }

    public void startSpectate(Player staff, Player target) {
        if (staff.equals(target)) {
            staff.sendMessage(msg(staff, "staff.spectate.self"));
            return;
        }

        SpectateData existing = spectating.get(staff.getUniqueId());
        if (existing != null) {
            stopSpectate(staff);
        }

        SpectateData data = new SpectateData();
        data.targetId = target.getUniqueId();
        data.originalLocation = staff.getLocation().clone();
        data.originalGameMode = staff.getGameMode();
        data.follow = true;

        spectating.put(staff.getUniqueId(), data);

        staff.setGameMode(GameMode.SPECTATOR);
        staff.teleport(target.getLocation());

        for (Player online : Bukkit.getOnlinePlayers()) {
            if (!online.hasPermission("nochance.admin")) {
                online.hidePlayer(plugin, staff);
            }
        }

        staff.sendMessage(msg(staff, "staff.spectate.started", "player", target.getName()));
        staff.sendMessage(msg(staff, "staff.spectate.hint"));
    }

    public void stopSpectate(Player staff) {
        if (staff == null) return;

        SpectateData data = spectating.remove(staff.getUniqueId());
        if (data == null) {
            staff.sendMessage(msg(staff, "staff.spectate.not_spectating"));
            return;
        }

        staff.setGameMode(data.originalGameMode);
        staff.teleport(data.originalLocation);

        for (Player online : Bukkit.getOnlinePlayers()) {
            online.showPlayer(plugin, staff);
        }

        staff.sendMessage(msg(staff, "staff.spectate.stopped"));
    }

    public void toggleSpectateFollow(Player staff) {
        SpectateData data = spectating.get(staff.getUniqueId());
        if (data == null) {
            staff.sendMessage(msg(staff, "staff.spectate.not_spectating"));
            return;
        }

        data.follow = !data.follow;
        String state = msg(staff, data.follow ? "staff.spectate.on_lbl" : "staff.spectate.off_lbl");
        staff.sendMessage(msg(staff, "staff.spectate.follow_toggled", "state", state));
    }

    public boolean isSpectating(UUID uuid) {
        return spectating.containsKey(uuid);
    }

    public void freezePlayer(Player staff, Player target) {
        if (target.hasPermission("nochance.bypass")) {
            staff.sendMessage(msg(staff, "staff.freeze.bypass"));
            return;
        }

        UUID uuid = target.getUniqueId();
        PlayerData data = playerDataMap.get(uuid);

        if (data != null && data.isFrozen()) {
            long frozenDuration = System.currentTimeMillis() - data.getFrozenTime();
            long frozenSecs = frozenDuration / 1000;
            data.setFrozen(false);
            frozenPlayers.remove(uuid);
            staff.sendMessage(msg(staff, "staff.freeze.unfrozen_staff",
                "player", target.getName(), "seconds", frozenSecs));
            target.sendMessage(msg(target, "staff.freeze.unfrozen_target"));
            return;
        }

        if (data != null) {
            data.setFrozen(true);
        }
        frozenPlayers.add(uuid);

        staff.sendMessage(msg(staff, "staff.freeze.frozen_staff", "player", target.getName()));
        target.sendMessage(msg(target, "staff.freeze.frozen_target"));
        target.sendMessage(msg(target, "staff.freeze.frozen_warn"));
        target.sendTitle(msg(target, "staff.freeze.title_main"),
            msg(target, "staff.freeze.title_sub"), 10, 70, 20);
    }

    public boolean isFrozen(UUID uuid) {
        return frozenPlayers.contains(uuid);
    }

    public void cleanup(UUID playerId) {
        verboseEnabled.remove(playerId);
        spectating.remove(playerId);
        frozenPlayers.remove(playerId);
    }

    public void shutdown() {
        if (freezeTask != null) freezeTask.cancel();
        if (spectateTask != null) spectateTask.cancel();
        verboseEnabled.clear();
        spectating.clear();
        frozenPlayers.clear();
    }

    public void setLiveOverlay(LiveOverlay overlay) {
        this.liveOverlay = overlay;
    }

    public LiveOverlay getLiveOverlay() {
        return liveOverlay;
    }

    public Set<UUID> getFrozenPlayers() {
        return Collections.unmodifiableSet(frozenPlayers);
    }

    private static class SpectateData {
        UUID targetId;
        Location originalLocation;
        GameMode originalGameMode;
        boolean follow;
        int detailTick;
    }
}
