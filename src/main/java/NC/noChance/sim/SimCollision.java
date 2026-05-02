package NC.noChance.sim;

import org.bukkit.Material;
import org.bukkit.World;

public class SimCollision {

    public static final double HALF_WIDTH = 0.3;
    public static final double PLAYER_HEIGHT = 1.8;
    public static final double SNEAK_HEIGHT = 1.5;
    public static final double SWIM_HEIGHT = 0.6;

    public static boolean isOnGround(World world, double x, double y, double z) {
        int minBX = (int) Math.floor(x - HALF_WIDTH);
        int maxBX = (int) Math.floor(x + HALF_WIDTH);
        int minBZ = (int) Math.floor(z - HALF_WIDTH);
        int maxBZ = (int) Math.floor(z + HALF_WIDTH);

        double checkY = y - 0.001;
        int bY = (int) Math.floor(checkY);
        if (y == Math.floor(y)) {
            bY = (int) y - 1;
        }

        for (int bx = minBX; bx <= maxBX; bx++) {
            for (int bz = minBZ; bz <= maxBZ; bz++) {
                Material type = world.getBlockAt(bx, bY, bz).getType();
                if (isCollidable(type)) {
                    return true;
                }
            }
        }
        return false;
    }

    public static boolean hasCollision(World world, double minX, double minY, double minZ,
                                       double maxX, double maxY, double maxZ) {
        int bMinX = (int) Math.floor(minX);
        int bMaxX = (int) Math.floor(maxX);
        int bMinY = (int) Math.floor(minY);
        int bMaxY = (int) Math.floor(maxY);
        int bMinZ = (int) Math.floor(minZ);
        int bMaxZ = (int) Math.floor(maxZ);

        for (int bx = bMinX; bx <= bMaxX; bx++) {
            for (int by = bMinY; by <= bMaxY; by++) {
                for (int bz = bMinZ; bz <= bMaxZ; bz++) {
                    Material type = world.getBlockAt(bx, by, bz).getType();
                    if (isCollidable(type)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    public static boolean isPathClear(World world, double fromX, double fromY, double fromZ,
                                       double toX, double toY, double toZ, double height) {
        double minX = Math.min(fromX, toX) - HALF_WIDTH;
        double minY = Math.min(fromY, toY);
        double minZ = Math.min(fromZ, toZ) - HALF_WIDTH;
        double maxX = Math.max(fromX, toX) + HALF_WIDTH;
        double maxY = Math.max(fromY, toY) + height;
        double maxZ = Math.max(fromZ, toZ) + HALF_WIDTH;

        return !hasCollision(world, minX, minY, minZ, maxX, maxY, maxZ);
    }

    public static boolean isCollidable(Material type) {
        if (type.isAir()) {
            return false;
        }

        if (type == Material.WATER || type == Material.LAVA) {
            return false;
        }

        if (type == Material.FIRE || type == Material.SOUL_FIRE) {
            return false;
        }

        if (type == Material.REDSTONE_WIRE || type == Material.LIGHT || type == Material.STRUCTURE_VOID) {
            return false;
        }

        if (type == Material.SCAFFOLDING) {
            return false;
        }

        String name = type.name();

        if (name.contains("SIGN") || name.contains("BANNER") || name.contains("PRESSURE")
                || name.contains("BUTTON") || name.contains("TORCH") || name.contains("RAIL")) {
            return false;
        }

        if (name.equals("MOSS_CARPET")) {
            return false;
        }

        switch (type) {
            case SHORT_GRASS:
            case TALL_GRASS:
            case FERN:
            case LARGE_FERN:
            case DEAD_BUSH:
            case DANDELION:
            case POPPY:
            case BLUE_ORCHID:
            case ALLIUM:
            case AZURE_BLUET:
            case RED_TULIP:
            case ORANGE_TULIP:
            case WHITE_TULIP:
            case PINK_TULIP:
            case OXEYE_DAISY:
            case CORNFLOWER:
            case LILY_OF_THE_VALLEY:
            case WITHER_ROSE:
            case SUNFLOWER:
            case LILAC:
            case ROSE_BUSH:
            case PEONY:
            case TORCHFLOWER:
            case PINK_PETALS:
            case CRIMSON_FUNGUS:
            case WARPED_FUNGUS:
            case CRIMSON_ROOTS:
            case WARPED_ROOTS:
            case NETHER_SPROUTS:
            case SWEET_BERRY_BUSH:
            case SUGAR_CANE:
            case KELP:
            case KELP_PLANT:
            case SEAGRASS:
            case TALL_SEAGRASS:
            case VINE:
            case GLOW_LICHEN:
            case HANGING_ROOTS:
            case SPORE_BLOSSOM:
            case MOSS_CARPET:
            case SMALL_DRIPLEAF:
            case CAVE_VINES:
            case CAVE_VINES_PLANT:
            case WHEAT:
            case CARROTS:
            case POTATOES:
            case BEETROOTS:
            case MELON_STEM:
            case PUMPKIN_STEM:
            case ATTACHED_MELON_STEM:
            case ATTACHED_PUMPKIN_STEM:
            case NETHER_WART:
            case COCOA:
            case TRIPWIRE:
            case TRIPWIRE_HOOK:
            case STRING:
            case REDSTONE_TORCH:
            case REDSTONE_WALL_TORCH:
            case SOUL_TORCH:
            case SOUL_WALL_TORCH:
            case LEVER:
                return false;
            default:
                break;
        }

        if (name.contains("FENCE_GATE")) {
            return false;
        }

        return type.isSolid();
    }

    public static double getBlockSlipperiness(Material type) {
        switch (type) {
            case ICE:
            case PACKED_ICE:
            case FROSTED_ICE:
                return 0.98;
            case BLUE_ICE:
                return 0.989;
            case SLIME_BLOCK:
                return 0.8;
            default:
                return 0.6;
        }
    }

    public static double getPlayerHeight(boolean sneaking, boolean swimming) {
        if (swimming) {
            return SWIM_HEIGHT;
        }
        if (sneaking) {
            return SNEAK_HEIGHT;
        }
        return PLAYER_HEIGHT;
    }

}
