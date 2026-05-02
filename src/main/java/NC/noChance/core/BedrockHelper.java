package NC.noChance.core;

import org.bukkit.entity.Player;

import java.lang.reflect.Method;
import java.util.UUID;

public class BedrockHelper {
    private static boolean floodgateAvailable = false;
    private static boolean geyserAvailable = false;
    private static Method floodgateIsBedrockPlayer;
    private static Method geyserIsBedrockPlayer;
    private static Object floodgateApi;
    private static Object geyserApi;

    static {
        try {
            Class<?> floodgateApiClass = Class.forName("org.geysermc.floodgate.api.FloodgateApi");
            Method getInstance = floodgateApiClass.getMethod("getInstance");
            floodgateApi = getInstance.invoke(null);
            floodgateIsBedrockPlayer = floodgateApiClass.getMethod("isFloodgatePlayer", UUID.class);
            floodgateAvailable = true;
        } catch (Exception ignored) {
        }

        if (!floodgateAvailable) {
            try {
                Class<?> geyserApiClass = Class.forName("org.geysermc.geyser.api.GeyserApi");
                Method provider = geyserApiClass.getMethod("api");
                geyserApi = provider.invoke(null);
                geyserIsBedrockPlayer = geyserApiClass.getMethod("isBedrockPlayer", UUID.class);
                geyserAvailable = true;
            } catch (Exception ignored) {
            }
        }
    }

    public static boolean isAvailable() {
        return floodgateAvailable || geyserAvailable;
    }

    public static boolean isBedrockPlayer(Player player) {
        if (player == null) return false;
        return isBedrockPlayer(player.getUniqueId());
    }

    public static boolean isBedrockPlayer(UUID uuid) {
        if (uuid == null) return false;

        if (floodgateAvailable && floodgateApi != null) {
            try {
                Object result = floodgateIsBedrockPlayer.invoke(floodgateApi, uuid);
                if (result instanceof Boolean) {
                    return (Boolean) result;
                }
            } catch (Exception e) {
                java.util.logging.Logger.getLogger("NoChance").log(java.util.logging.Level.WARNING, "Failed to query Floodgate isBedrockPlayer", e);
            }
        }

        if (geyserAvailable && geyserApi != null) {
            try {
                Object result = geyserIsBedrockPlayer.invoke(geyserApi, uuid);
                if (result instanceof Boolean) {
                    return (Boolean) result;
                }
            } catch (Exception e) {
                java.util.logging.Logger.getLogger("NoChance").log(java.util.logging.Level.WARNING, "Failed to query Geyser isBedrockPlayer", e);
            }
        }

        String uuidStr = uuid.toString();
        if (uuidStr.startsWith("00000000-0000-0000-0009")) {
            return true;
        }

        return false;
    }

    public static String getConnectionType(Player player) {
        if (isBedrockPlayer(player)) {
            return "Bedrock";
        }
        return "Java";
    }
}
