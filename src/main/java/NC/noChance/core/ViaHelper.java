package NC.noChance.core;

import org.bukkit.entity.Player;

import java.lang.reflect.Method;
import java.util.UUID;

public class ViaHelper {
    private static boolean viaAvailable = false;
    private static boolean protocolSupportAvailable = false;
    private static Method viaGetVersion;
    private static Method psGetVersion;
    private static Object viaApi;

    public static final int V1_8 = 47;
    public static final int V1_9 = 107;
    public static final int V1_12 = 335;
    public static final int V1_13 = 393;
    public static final int V1_14 = 477;
    public static final int V1_16 = 735;
    public static final int V1_17 = 755;
    public static final int V1_18 = 757;
    public static final int V1_19 = 759;
    public static final int V1_20 = 763;
    public static final int V1_21 = 767;

    static {
        try {
            Class<?> viaApiClass = Class.forName("com.viaversion.viaversion.api.Via");
            Method apiMethod = viaApiClass.getMethod("getAPI");
            viaApi = apiMethod.invoke(null);
            Class<?> playerVersionClass = Class.forName("com.viaversion.viaversion.api.ViaAPI");
            viaGetVersion = playerVersionClass.getMethod("getPlayerVersion", UUID.class);
            viaAvailable = true;
        } catch (Exception ignored) {
        }

        if (!viaAvailable) {
            try {
                Class<?> psClass = Class.forName("protocolsupport.api.ProtocolSupportAPI");
                psGetVersion = psClass.getMethod("getProtocolVersion", Player.class);
                protocolSupportAvailable = true;
            } catch (Exception ignored) {
            }
        }
    }

    public static boolean isAvailable() {
        return viaAvailable || protocolSupportAvailable;
    }

    public static int getProtocolVersion(Player player) {
        if (player == null) return -1;
        return getProtocolVersion(player.getUniqueId(), player);
    }

    public static int getProtocolVersion(UUID uuid, Player player) {
        if (viaAvailable && viaApi != null && uuid != null) {
            try {
                Object result = viaGetVersion.invoke(viaApi, uuid);
                if (result instanceof Integer) {
                    return (Integer) result;
                }
            } catch (Exception e) {
                java.util.logging.Logger.getLogger("NoChance").log(java.util.logging.Level.WARNING, "Failed to query ViaVersion player protocol", e);
            }
        }

        if (protocolSupportAvailable && player != null) {
            try {
                Object result = psGetVersion.invoke(null, player);
                if (result != null) {
                    Method getId = result.getClass().getMethod("getId");
                    Object idResult = getId.invoke(result);
                    if (idResult instanceof Integer) {
                        return (Integer) idResult;
                    }
                }
            } catch (Exception e) {
                java.util.logging.Logger.getLogger("NoChance").log(java.util.logging.Level.WARNING, "Failed to query ProtocolSupport player protocol", e);
            }
        }

        return -1;
    }

    public static boolean isLegacyClient(Player player) {
        int version = getProtocolVersion(player);
        if (version == -1) return false;
        return version < V1_9;
    }

    public static boolean isPre1_13(Player player) {
        int version = getProtocolVersion(player);
        if (version == -1) return false;
        return version < V1_13;
    }

    public static boolean isPre1_16(Player player) {
        int version = getProtocolVersion(player);
        if (version == -1) return false;
        return version < V1_16;
    }

    public static boolean isPre1_19(Player player) {
        int version = getProtocolVersion(player);
        if (version == -1) return false;
        return version < V1_19;
    }

    public static String getVersionName(int protocol) {
        if (protocol >= V1_21) return "1.21+";
        if (protocol >= V1_20) return "1.20.x";
        if (protocol >= V1_19) return "1.19.x";
        if (protocol >= V1_18) return "1.18.x";
        if (protocol >= V1_17) return "1.17.x";
        if (protocol >= V1_16) return "1.16.x";
        if (protocol >= V1_14) return "1.14-1.15";
        if (protocol >= V1_13) return "1.13.x";
        if (protocol >= V1_12) return "1.12.x";
        if (protocol >= V1_9) return "1.9-1.11";
        if (protocol >= V1_8) return "1.8.x";
        return "Unknown";
    }

    public static double getVersionTolerance(Player player) {
        int version = getProtocolVersion(player);
        if (version == -1) return 0.0;

        if (version < V1_9) return 0.15;
        if (version < V1_13) return 0.10;
        if (version < V1_16) return 0.06;
        if (version < V1_19) return 0.04;
        return 0.0;
    }

    public static double getCombatCooldownMultiplier(Player player) {
        int version = getProtocolVersion(player);
        if (version == -1) return 1.0;
        if (version < V1_9) return 0.0;
        return 1.0;
    }

    public static boolean hasAttackCooldown(Player player) {
        int version = getProtocolVersion(player);
        if (version == -1) return true;
        return version >= V1_9;
    }

    public static double getMovementTolerance(Player player) {
        int version = getProtocolVersion(player);
        if (version == -1) return 0.0;

        if (version < V1_9) return 0.12;
        if (version < V1_13) return 0.08;
        if (version < V1_16) return 0.05;
        return 0.0;
    }
}
