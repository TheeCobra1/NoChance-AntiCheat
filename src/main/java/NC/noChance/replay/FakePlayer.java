package NC.noChance.replay;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import com.github.retrooper.packetevents.protocol.player.Equipment;
import com.github.retrooper.packetevents.protocol.player.EquipmentSlot;
import com.github.retrooper.packetevents.protocol.player.GameMode;
import com.github.retrooper.packetevents.protocol.player.TextureProperty;
import com.github.retrooper.packetevents.protocol.player.UserProfile;
import com.github.retrooper.packetevents.protocol.world.states.WrappedBlockState;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.util.Vector3i;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import com.github.retrooper.packetevents.wrapper.play.server.*;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.retrooper.packetevents.util.SpigotConversionUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.io.InputStreamReader;
import java.io.BufferedReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

public class FakePlayer {
    private static final AtomicInteger NEXT_ID = new AtomicInteger(
            ThreadLocalRandom.current().nextInt(100000, 900000));
    private static final Map<UUID, TextureProperty> SKIN_CACHE = new ConcurrentHashMap<>();

    private final Player viewer;
    private final World world;
    private final int entityId;
    private final UUID entityUuid;
    private final UserProfile profile;
    private final String teamName;
    private final NamedTextColor teamColor;

    private volatile double x, y, z;
    private volatile float yaw, pitch;
    private volatile boolean spawned;
    private volatile boolean sneaking;
    private volatile boolean sprinting;
    private volatile boolean gliding;
    private volatile boolean swimming;
    private volatile boolean blocking;

    public FakePlayer(Player viewer, String name, World world) {
        this(viewer, name, world, NamedTextColor.GRAY, null);
    }

    public FakePlayer(Player viewer, String name, World world, NamedTextColor color) {
        this(viewer, name, world, color, null);
    }

    public FakePlayer(Player viewer, String name, World world, NamedTextColor color, UUID targetUuid) {
        this.viewer = viewer;
        this.world = world;
        this.entityId = NEXT_ID.getAndIncrement();
        this.entityUuid = UUID.randomUUID();
        String profileName = name.length() > 16 ? name.substring(0, 16) : name;
        this.profile = new UserProfile(entityUuid, profileName);
        this.teamName = "nc_" + Integer.toHexString(entityId);
        this.teamColor = color;
        this.spawned = false;
    }

    public void applySkin(UUID playerUuid) {
        TextureProperty cached = SKIN_CACHE.get(playerUuid);
        if (cached != null) {
            profile.setTextureProperties(List.of(cached));
            return;
        }

        try {
            org.bukkit.entity.Player online = org.bukkit.Bukkit.getPlayer(playerUuid);
            if (online != null) {
                com.github.retrooper.packetevents.protocol.player.User user =
                    PacketEvents.getAPI().getPlayerManager().getUser(online);
                if (user != null && user.getProfile() != null) {
                    List<TextureProperty> textures = user.getProfile().getTextureProperties();
                    if (textures != null && !textures.isEmpty()) {
                        SKIN_CACHE.put(playerUuid, textures.get(0));
                        profile.setTextureProperties(textures);
                        return;
                    }
                }
            }
        } catch (Exception e) {
            Logger.getLogger("NoChance").log(Level.WARNING, "Failed to apply skin from online player " + playerUuid, e);
        }

        CompletableFuture.runAsync(() -> {
            try {
                TextureProperty tex = fetchSkin(playerUuid);
                if (tex != null) {
                    SKIN_CACHE.put(playerUuid, tex);
                    profile.setTextureProperties(List.of(tex));
                }
            } catch (Exception e) {
                Logger.getLogger("NoChance").log(Level.WARNING, "Failed to fetch skin async for " + playerUuid, e);
            }
        });
    }

    public void applySkinSync(UUID playerUuid) {
        TextureProperty cached = SKIN_CACHE.get(playerUuid);
        if (cached != null) {
            profile.setTextureProperties(List.of(cached));
            return;
        }

        try {
            TextureProperty tex = fetchSkin(playerUuid);
            if (tex != null) {
                SKIN_CACHE.put(playerUuid, tex);
                profile.setTextureProperties(List.of(tex));
            }
        } catch (Exception e) {
            Logger.getLogger("NoChance").log(Level.WARNING, "Failed to apply skin sync for " + playerUuid, e);
        }
    }

    private TextureProperty fetchSkin(UUID uuid) {
        try {
            String uuidStr = uuid.toString().replace("-", "");
            URL url = new URL("https://sessionserver.mojang.com/session/minecraft/profile/" + uuidStr + "?unsigned=false");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(3000);
            conn.setReadTimeout(3000);
            conn.setRequestMethod("GET");

            if (conn.getResponseCode() != 200) return null;

            StringBuilder sb = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) sb.append(line);
            }

            String json = sb.toString();
            JsonElement root = JsonParser.parseString(json);
            if (!root.isJsonObject()) return null;
            JsonObject obj = root.getAsJsonObject();
            if (!obj.has("properties") || !obj.get("properties").isJsonArray()) return null;
            for (JsonElement el : obj.getAsJsonArray("properties")) {
                if (!el.isJsonObject()) continue;
                JsonObject prop = el.getAsJsonObject();
                String name = prop.has("name") ? prop.get("name").getAsString() : null;
                if (!"textures".equals(name)) continue;
                String value = prop.has("value") ? prop.get("value").getAsString() : null;
                String signature = prop.has("signature") ? prop.get("signature").getAsString() : null;
                if (value != null && signature != null) {
                    return new TextureProperty("textures", value, signature);
                }
            }
        } catch (Exception e) {
            Logger.getLogger("NoChance").log(Level.WARNING, "Failed to fetch skin from Mojang API for " + uuid, e);
        }
        return null;
    }

    public void spawn(double x, double y, double z, float yaw, float pitch) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.yaw = yaw;
        this.pitch = pitch;
        spawned = true;
    }

    public void sendSpawnPackets() {
        try {
            WrapperPlayServerPlayerInfoUpdate info = new WrapperPlayServerPlayerInfoUpdate(
                EnumSet.of(
                    WrapperPlayServerPlayerInfoUpdate.Action.ADD_PLAYER,
                    WrapperPlayServerPlayerInfoUpdate.Action.UPDATE_LISTED
                ),
                new WrapperPlayServerPlayerInfoUpdate.PlayerInfo(
                    profile, false, 0, GameMode.SURVIVAL, null, null
                )
            );
            send(info);
        } catch (Exception e) {
            Logger.getLogger("NoChance").log(Level.WARNING, "Failed to send spawn packets to " + viewer.getName(), e);
        }
    }

    public void sendSpawnEntity() {
        try {
            WrapperPlayServerSpawnEntity spawn = new WrapperPlayServerSpawnEntity(
                entityId, Optional.of(entityUuid), EntityTypes.PLAYER,
                new Vector3d(x, y, z), pitch, yaw, yaw, 0, Optional.empty()
            );
            send(spawn);
            sendHeadRotation();
            createTeam();
        } catch (Exception e) {
            Logger.getLogger("NoChance").log(Level.WARNING, "Failed to send spawn entity to " + viewer.getName(), e);
        }
    }

    public void move(double nx, double ny, double nz, float nyaw, float npitch, boolean onGround) {
        if (!spawned) return;
        this.x = nx;
        this.y = ny;
        this.z = nz;
        this.yaw = nyaw;
        this.pitch = npitch;

        send(new WrapperPlayServerEntityTeleport(
            entityId, new Vector3d(nx, ny, nz), nyaw, npitch, onGround
        ));
        sendHeadRotation();
    }

    public void swing() {
        if (!spawned) return;
        send(new WrapperPlayServerEntityAnimation(
            entityId, WrapperPlayServerEntityAnimation.EntityAnimationType.SWING_MAIN_ARM
        ));
    }

    public void hurt() {
    }

    public void sneak(boolean val) {
        if (!spawned || sneaking == val) return;
        sneaking = val;
        sendFlags();
    }

    public void setSprinting(boolean val) {
        if (!spawned || sprinting == val) return;
        sprinting = val;
        sendFlags();
    }

    public void setGliding(boolean val) {
        if (!spawned || gliding == val) return;
        gliding = val;
        sendFlags();
    }

    public void setSwimming(boolean val) {
        if (!spawned || swimming == val) return;
        swimming = val;
        sendFlags();
    }

    public void setBlocking(boolean val) {
        if (!spawned || blocking == val) return;
        blocking = val;
        sendHandState();
    }

    public void setEquipment(Material mainHand, Material offHand,
                             Material helmet, Material chest, Material legs, Material boots) {
        if (!spawned) return;
        try {
            List<Equipment> equip = new ArrayList<>();
            addEquip(equip, EquipmentSlot.MAIN_HAND, mainHand);
            addEquip(equip, EquipmentSlot.OFF_HAND, offHand);
            addEquip(equip, EquipmentSlot.HELMET, helmet);
            addEquip(equip, EquipmentSlot.CHEST_PLATE, chest);
            addEquip(equip, EquipmentSlot.LEGGINGS, legs);
            addEquip(equip, EquipmentSlot.BOOTS, boots);
            if (!equip.isEmpty()) {
                send(new WrapperPlayServerEntityEquipment(entityId, equip));
            }
        } catch (Exception e) {
            Logger.getLogger("NoChance").log(Level.WARNING, "Failed to send equipment to " + viewer.getName(), e);
        }
    }

    private void addEquip(List<Equipment> list, EquipmentSlot slot, Material mat) {
        if (mat == null || mat == Material.AIR) return;
        try {
            list.add(new Equipment(slot, SpigotConversionUtil.fromBukkitItemStack(new ItemStack(mat))));
        } catch (Exception e) {
            Logger.getLogger("NoChance").log(Level.WARNING, "Failed to convert equipment item for slot " + slot, e);
        }
    }

    public void sendBlockChange(int x, int y, int z, Material mat) {
        try {
            WrappedBlockState state = SpigotConversionUtil.fromBukkitBlockData(
                (mat == null || mat.isAir()) ? Material.AIR.createBlockData() : mat.createBlockData()
            );
            send(new WrapperPlayServerBlockChange(new Vector3i(x, y, z), state.getGlobalId()));
        } catch (Exception e) {
            Logger.getLogger("NoChance").log(Level.WARNING, "Failed to send block change to " + viewer.getName(), e);
        }
    }

    public void sendBlockRestore(int x, int y, int z) {
        try {
            WrappedBlockState state = SpigotConversionUtil.fromBukkitBlockData(
                world.getBlockAt(x, y, z).getBlockData()
            );
            send(new WrapperPlayServerBlockChange(new Vector3i(x, y, z), state.getGlobalId()));
        } catch (Exception e) {
            Logger.getLogger("NoChance").log(Level.WARNING, "Failed to send block restore to " + viewer.getName(), e);
        }
    }

    public void sendBreakAnim(int x, int y, int z, int stage) {
        try {
            send(new WrapperPlayServerBlockBreakAnimation(entityId, new Vector3i(x, y, z), (byte) stage));
        } catch (Exception e) {
            Logger.getLogger("NoChance").log(Level.WARNING, "Failed to send break animation to " + viewer.getName(), e);
        }
    }

    public void destroy() {
        if (!spawned) return;
        spawned = false;
        send(new WrapperPlayServerDestroyEntities(entityId));
        send(new WrapperPlayServerPlayerInfoRemove(entityUuid));
        destroyTeam();
    }

    public Location getLocation() {
        return new Location(world, x, y, z, yaw, pitch);
    }

    public boolean isSpawned() {
        return spawned;
    }

    public int getEntityId() {
        return entityId;
    }

    private void sendFlags() {
        byte flags = 0;
        if (sneaking) flags |= 0x02;
        if (sprinting) flags |= 0x08;
        if (swimming) flags |= 0x10;
        if (gliding) flags |= (byte) 0x80;

        List<EntityData<?>> meta = List.of(new EntityData<>(0, EntityDataTypes.BYTE, flags));
        send(new WrapperPlayServerEntityMetadata(entityId, meta));
    }

    private void sendHandState() {
        try {
            byte handFlags = blocking ? (byte) 0x01 : (byte) 0x00;
            List<EntityData<?>> meta = List.of(new EntityData<>(8, EntityDataTypes.BYTE, handFlags));
            send(new WrapperPlayServerEntityMetadata(entityId, meta));
        } catch (Exception e) {
            Logger.getLogger("NoChance").log(Level.WARNING, "Failed to send hand state to " + viewer.getName(), e);
        }
    }

    private void sendSkinParts() {
        try {
            List<EntityData<?>> meta = List.of(new EntityData<>(17, EntityDataTypes.BYTE, (byte) 0x7F));
            send(new WrapperPlayServerEntityMetadata(entityId, meta));
        } catch (Exception e) {
            Logger.getLogger("NoChance").log(Level.WARNING, "Failed to send skin parts to " + viewer.getName(), e);
        }
    }

    private void sendHeadRotation() {
        send(new WrapperPlayServerEntityHeadLook(entityId, yaw));
    }

    private void createTeam() {
        try {
            WrapperPlayServerTeams.ScoreBoardTeamInfo teamInfo = new WrapperPlayServerTeams.ScoreBoardTeamInfo(
                Component.empty(),
                Component.empty(),
                Component.empty(),
                WrapperPlayServerTeams.NameTagVisibility.NEVER,
                WrapperPlayServerTeams.CollisionRule.NEVER,
                teamColor,
                WrapperPlayServerTeams.OptionData.NONE
            );

            send(new WrapperPlayServerTeams(
                teamName,
                WrapperPlayServerTeams.TeamMode.CREATE,
                Optional.of(teamInfo),
                profile.getName()
            ));
        } catch (Exception e) {
            Logger.getLogger("NoChance").log(Level.WARNING, "Failed to create team for " + viewer.getName(), e);
        }
    }

    private void destroyTeam() {
        try {
            send(new WrapperPlayServerTeams(
                teamName,
                WrapperPlayServerTeams.TeamMode.REMOVE,
                Optional.empty()
            ));
        } catch (Exception e) {
            Logger.getLogger("NoChance").log(Level.WARNING, "Failed to destroy team for " + viewer.getName(), e);
        }
    }

    private void send(PacketWrapper<?> packet) {
        try {
            if (viewer.isOnline()) {
                PacketEvents.getAPI().getPlayerManager().sendPacket(viewer, packet);
            }
        } catch (Exception e) {
            Logger.getLogger("NoChance").log(Level.WARNING, "Failed to send packet to " + viewer.getName(), e);
        }
    }
}
