package NC.noChance.core;

import NC.noChance.NoChance;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.lang.reflect.Method;
import java.util.logging.Level;
import java.util.logging.Logger;

public class TPSCache {
    private static volatile double cachedTPS = 20.0;
    private static volatile long lastUpdate = 0;
    private static final long UPDATE_INTERVAL = 1000;
    private static Method tpsMethod;
    private static boolean usePaperApi = true;

    public static void init(Plugin plugin) {
        try {
            tpsMethod = Bukkit.class.getMethod("getTPS");
            usePaperApi = true;
        } catch (NoSuchMethodException e) {
            usePaperApi = false;
        }
        BukkitTask task = Bukkit.getScheduler().runTaskTimer(plugin, TPSCache::updateTPS, 20L, 20L);
        if (plugin instanceof NoChance) {
            ((NoChance) plugin).getLifecycleRegistry().registerBukkitTask(task);
        }
    }

    private static void updateTPS() {
        try {
            if (usePaperApi && tpsMethod != null) {
                double[] tps = (double[]) tpsMethod.invoke(null);
                cachedTPS = Math.min(20.0, tps[0]);
            } else {
                Object server = Bukkit.getServer().getClass().getMethod("getServer").invoke(Bukkit.getServer());
                double[] tps = (double[]) server.getClass().getField("recentTps").get(server);
                cachedTPS = Math.min(20.0, tps[0]);
            }
            lastUpdate = System.currentTimeMillis();
        } catch (Exception e) {
            Logger.getLogger("NoChance").log(Level.WARNING, "Failed to update TPS cache", e);
            cachedTPS = 20.0;
        }
    }

    public static double getTPS() {
        if (System.currentTimeMillis() - lastUpdate > UPDATE_INTERVAL * 3) {
            updateTPS();
        }
        return cachedTPS;
    }

    public static boolean isLagging() {
        return cachedTPS < 18.0;
    }

    public static boolean isSeverelyLagging() {
        return cachedTPS < 15.0;
    }

    public static double getMultiplier() {
        if (cachedTPS >= 19.5) return 1.0;
        return 1.0 + (20.0 - cachedTPS) * 0.08;
    }
}
