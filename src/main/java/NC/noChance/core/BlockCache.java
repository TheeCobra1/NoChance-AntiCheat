package NC.noChance.core;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class BlockCache {
    private static final Map<Long, CachedBlock> cache = new ConcurrentHashMap<>();
    private static final long CACHE_TTL = 500;
    private static final int MAX_CACHE_SIZE = 5000;
    private static volatile long lastCleanup = 0;

    public static Material getType(Location loc) {
        if (loc == null || loc.getWorld() == null) return Material.AIR;
        return getType(loc.getWorld(), loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
    }

    public static Material getType(World world, int x, int y, int z) {
        if (world == null) return Material.AIR;

        long key = packCoords(world.getUID().hashCode(), x, y, z);
        long now = System.currentTimeMillis();

        CachedBlock cached = cache.get(key);
        if (cached != null && now - cached.time < CACHE_TTL) {
            return cached.type;
        }

        Block block = world.getBlockAt(x, y, z);
        Material type = block.getType();

        if (cache.size() < MAX_CACHE_SIZE) {
            cache.put(key, new CachedBlock(type, now));
        }

        if (now - lastCleanup > 5000) {
            cleanup(now);
            lastCleanup = now;
        }

        return type;
    }

    public static Block getBlock(Location loc) {
        if (loc == null || loc.getWorld() == null) return null;
        return loc.getWorld().getBlockAt(loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
    }

    public static boolean isSolid(Location loc) {
        return getType(loc).isSolid();
    }

    public static boolean isSolid(World world, int x, int y, int z) {
        return getType(world, x, y, z).isSolid();
    }

    public static boolean isType(Location loc, Material... types) {
        Material at = getType(loc);
        for (Material type : types) {
            if (at == type) return true;
        }
        return false;
    }

    public static boolean nameContains(Location loc, String... patterns) {
        String name = getType(loc).name();
        for (String pattern : patterns) {
            if (name.contains(pattern)) return true;
        }
        return false;
    }

    public static boolean nameContains(Material type, String... patterns) {
        String name = type.name();
        for (String pattern : patterns) {
            if (name.contains(pattern)) return true;
        }
        return false;
    }

    public static void invalidate(Location loc) {
        if (loc == null || loc.getWorld() == null) return;
        long key = packCoords(loc.getWorld().getUID().hashCode(), loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
        cache.remove(key);
    }

    public static void clear() {
        cache.clear();
    }

    private static void cleanup(long now) {
        cache.entrySet().removeIf(entry -> now - entry.getValue().time > CACHE_TTL * 2);
    }

    private static long packCoords(int worldHash, int x, int y, int z) {
        long hash = (long) worldHash;
        hash = hash * 6364136223846793005L + x;
        hash = hash * 6364136223846793005L + y;
        hash = hash * 6364136223846793005L + z;
        return hash;
    }

    private static class CachedBlock {
        final Material type;
        final long time;

        CachedBlock(Material type, long time) {
            this.type = type;
            this.time = time;
        }
    }
}
