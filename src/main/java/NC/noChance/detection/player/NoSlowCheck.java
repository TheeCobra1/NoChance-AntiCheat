package NC.noChance.detection.player;

import NC.noChance.core.*;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.enchantments.Enchantment;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

public class NoSlowCheck {
    private final ACConfig config;
    private final Map<UUID, PlayerData> playerDataMap;
    private final LayerFiltering filtering;
    private final Map<UUID, SlowData> dataMap;

    private static final double WEB_MAX_H = 0.095;
    private static final double WEB_MAX_V_UP = 0.080;
    private static final double WEB_MAX_FALL = 0.075;

    private static final double SOUL_MAX_H = 0.12;
    private static final double HONEY_MAX_H = 0.09;
    private static final double HONEY_MAX_SLIDE = 0.12;
    private static final double SNOW_MAX_H = 0.10;
    private static final double SNOW_MAX_SINK = 0.08;
    private static final double BERRY_MAX_H = 0.13;

    private static final double EAT_MULTI = 0.2;
    private static final double SHIELD_MULTI = 0.2;

    private MovementGrace movementGrace;

    public NoSlowCheck(ACConfig config, Map<UUID, PlayerData> playerDataMap, LayerFiltering filtering) {
        this.config = config;
        this.playerDataMap = playerDataMap;
        this.filtering = filtering;
        this.dataMap = new ConcurrentHashMap<>();
    }

    public void setMovementGrace(MovementGrace grace) {
        this.movementGrace = grace;
    }

    public CheckResult check(Player player, Location from, Location to) {
        if (!config.isCheckEnabled("noslow")) {
            return CheckResult.passed();
        }

        PlayerData pData = playerDataMap.get(player.getUniqueId());
        if (pData == null) return CheckResult.passed();

        if (pData.isInGracePeriod(config.getGracePeriod())) {
            return CheckResult.passed();
        }

        if (player.isFlying() && player.getAllowFlight()) {
            return CheckResult.passed();
        }

        if (player.getGameMode() == org.bukkit.GameMode.CREATIVE ||
            player.getGameMode() == org.bukkit.GameMode.SPECTATOR) {
            return CheckResult.passed();
        }

        if (WaterHelper.isInWater(player) || player.isGliding() || player.isRiptiding()) {
            return CheckResult.passed();
        }

        if (player.getVehicle() != null) {
            return CheckResult.passed();
        }

        if (movementGrace != null && movementGrace.inMountGrace(player.getUniqueId(), System.currentTimeMillis())) {
            return CheckResult.passed();
        }

        if (pData.isInTeleportGracePeriod(2)) {
            return CheckResult.passed();
        }

        long timeSinceVelocity = System.currentTimeMillis() - pData.getLastVelocityTime();
        if (timeSinceVelocity < 800) {
            return CheckResult.passed();
        }

        UUID uuid = player.getUniqueId();
        SlowData sd = dataMap.computeIfAbsent(uuid, k -> new SlowData());

        int ping = filtering.getPing(player);
        sd.ping = ping;

        if (ping > 350) {
            return CheckResult.passed();
        }

        double dx = to.getX() - from.getX();
        double dy = to.getY() - from.getY();
        double dz = to.getZ() - from.getZ();
        double hDist = Math.sqrt(dx * dx + dz * dz);

        SlowCtx ctx = getContext(player, from, to);

        if (!ctx.slow) {
            if (sd.ticks > 0) {
                long nowExit = System.currentTimeMillis();
                if (sd.useStartTime > 0 && nowExit - sd.useStartTime < 50 && (ctx.type == SlowType.EAT || ctx.type == SlowType.NONE) && hDist > 0.15) {
                    CheckResult cancelRes = CheckResult.failed(ViolationType.NOSLOW_ITEM, 0.82,
                            String.format("[CancelEat] flip after %dms hDist:%.3f", nowExit - sd.useStartTime, hDist));
                    if (filtering.passesLayer2HeuristicFiltering(player, ViolationType.NOSLOW_ITEM, cancelRes)) {
                        sd.lastSlowExitTime = nowExit;
                        pData.touchSlowExit();
                        sd.useStartTime = 0;
                        sd.clear();
                        return cancelRes;
                    }
                }
                sd.lastSlowExitTime = nowExit;
                pData.touchSlowExit();
            }
            sd.useStartTime = 0;
            sd.clear();
            return CheckResult.passed();
        }

        if (sd.useStartTime == 0) {
            sd.useStartTime = System.currentTimeMillis();
        }

        if (System.currentTimeMillis() - sd.lastSlowExitTime < 800) {
            return CheckResult.passed();
        }

        sd.tick();

        switch (ctx.type) {
            case WEB:
                return checkWeb(player, sd, hDist, dy, ping);
            case SOUL:
                return checkSoul(player, sd, hDist, dy, ping);
            case HONEY:
                return checkHoney(player, sd, hDist, dy, ping, ctx.onSide);
            case SNOW:
                return checkSnow(player, sd, hDist, dy, ping);
            case BERRY:
                return checkBerry(player, sd, hDist, ping);
            case EAT:
            case SHIELD:
                return checkItem(player, sd, ctx, hDist, ping);
            default:
                return CheckResult.passed();
        }
    }

    private CheckResult checkWeb(Player player, SlowData sd, double hDist, double vDist, int ping) {
        double pingMult = ping > 150 ? 1.4 : (ping > 100 ? 1.2 : 1.0);
        double tpsMult = 20.0 / Math.max(getTPS(), 12.0);
        double combined = pingMult * tpsMult;

        double maxH = WEB_MAX_H * combined;
        double maxVUp = WEB_MAX_V_UP * combined;
        double maxFall = WEB_MAX_FALL * combined;

        sd.addMove(hDist, vDist);

        boolean hViolation = hDist > maxH;
        boolean vUpViolation = vDist > maxVUp;
        boolean fastFall = vDist < -maxFall && Math.abs(vDist) > 0.12;

        if (!hViolation && !vUpViolation && !fastFall) {
            sd.decayVL();
            return CheckResult.passed();
        }

        if (hViolation) {
            double ratio = hDist / maxH;
            sd.hVL++;

            if (sd.hVL >= 4 || ratio > 2.0) {
                double sev = Math.min(1.0, 0.5 + (ratio - 1.0) * 0.4);
                return flagOrPassWeb(player, sd, sev, String.format("[NoWeb] H:%.3f/%.3f r:%.1f", hDist, maxH, ratio));
            }
        }

        if (vUpViolation) {
            double ratio = vDist / maxVUp;
            sd.vVL++;

            if (sd.vVL >= 4 || ratio > 2.5) {
                double sev = Math.min(1.0, 0.5 + (ratio - 1.0) * 0.35);
                return flagOrPassWeb(player, sd, sev, String.format("[NoWeb] V:%.3f/%.3f r:%.1f", vDist, maxVUp, ratio));
            }
        }

        if (fastFall) {
            sd.fallVL++;
            if (sd.fallVL >= 5) {
                double sev = Math.min(1.0, 0.55 + Math.abs(vDist) * 0.8);
                return flagOrPassWeb(player, sd, sev, String.format("[NoWeb] Fall:%.3f", vDist));
            }
        }

        return CheckResult.passed();
    }

    private CheckResult checkSoul(Player player, SlowData sd, double hDist, double vDist, int ping) {
        double pingMult = ping > 150 ? 1.35 : (ping > 100 ? 1.2 : 1.0);
        double tpsMult = 20.0 / Math.max(getTPS(), 12.0);
        double combined = pingMult * tpsMult;

        double maxH = SOUL_MAX_H * combined;

        if (player.isSprinting()) {
            maxH *= 1.15;
        }

        org.bukkit.potion.PotionEffectType jumpType = org.bukkit.potion.PotionEffectType.getByName("JUMP_BOOST");
        if (jumpType == null) jumpType = org.bukkit.potion.PotionEffectType.getByName("JUMP");
        if (jumpType != null && player.hasPotionEffect(jumpType)) {
            int level = player.getPotionEffect(jumpType).getAmplifier() + 1;
            maxH *= (1.0 + level * 0.1);
        }

        if (player.isSneaking()) {
            ItemStack leggings = player.getInventory().getLeggings();
            if (leggings != null) {
                int swiftSneak = leggings.getEnchantmentLevel(Enchantment.SWIFT_SNEAK);
                if (swiftSneak > 0) {
                    maxH *= (1.0 + (swiftSneak * 0.15));
                }
            }
        }

        PotionEffectType speedType = PotionEffectType.getByName("SPEED");
        if (speedType == null) speedType = PotionEffectType.getByName("INCREASE_MOVEMENT_SPEED");
        if (speedType != null) {
            PotionEffect speed = player.getPotionEffect(speedType);
            if (speed != null) {
                maxH *= (1.0 + ((speed.getAmplifier() + 1) * 0.15));
            }
        }

        sd.addMove(hDist, vDist);

        if (hDist <= maxH) {
            sd.decayVL();
            return CheckResult.passed();
        }

        double ratio = hDist / maxH;
        if (ratio < 1.35) {
            return CheckResult.passed();
        }

        sd.hVL++;
        int threshold = ping > 150 ? 3 : 2;

        if (sd.hVL >= threshold || ratio > 2.2) {
            double sev = Math.min(1.0, 0.5 + (ratio - 1.0) * 0.4);
            return flagOrPass(player, sd, sev, String.format("[NoSoul] H:%.3f/%.3f r:%.1f", hDist, maxH, ratio));
        }

        return CheckResult.passed();
    }

    private CheckResult checkHoney(Player player, SlowData sd, double hDist, double vDist, int ping, boolean onSide) {
        double pingMult = ping > 150 ? 1.35 : (ping > 100 ? 1.2 : 1.0);
        double tpsMult = 20.0 / Math.max(getTPS(), 12.0);
        double combined = pingMult * tpsMult;

        sd.addMove(hDist, vDist);

        if (onSide) {
            double maxSlide = HONEY_MAX_SLIDE * combined;

            if (vDist < -maxSlide) {
                double ratio = Math.abs(vDist) / maxSlide;
                sd.fallVL++;

                if (sd.fallVL >= 2 || ratio > 2.0) {
                    double sev = Math.min(1.0, 0.5 + (ratio - 1.0) * 0.35);
                    return flagOrPassHoney(player, sd, sev, String.format("[NoHoney] Slide:%.3f/%.3f", vDist, -maxSlide));
                }
            } else {
                sd.decayVL();
            }
            return CheckResult.passed();
        }

        double maxH = HONEY_MAX_H * combined;

        if (player.isSprinting()) {
            maxH *= 1.1;
        }

        if (hDist <= maxH) {
            sd.decayVL();
            return CheckResult.passed();
        }

        double ratio = hDist / maxH;
        if (ratio < 1.4) {
            return CheckResult.passed();
        }

        sd.hVL++;
        int threshold = ping > 150 ? 3 : 2;

        if (sd.hVL >= threshold || ratio > 2.3) {
            double sev = Math.min(1.0, 0.5 + (ratio - 1.0) * 0.4);
            return flagOrPassHoney(player, sd, sev, String.format("[NoHoney] H:%.3f/%.3f r:%.1f", hDist, maxH, ratio));
        }

        return CheckResult.passed();
    }

    private CheckResult checkSnow(Player player, SlowData sd, double hDist, double vDist, int ping) {
        double pingMult = ping > 150 ? 1.35 : (ping > 100 ? 1.2 : 1.0);
        double tpsMult = 20.0 / Math.max(getTPS(), 12.0);
        double combined = pingMult * tpsMult;

        double maxH = SNOW_MAX_H * combined;
        double maxSink = SNOW_MAX_SINK * combined;

        sd.addMove(hDist, vDist);

        boolean hViolation = hDist > maxH;
        boolean noSink = vDist > maxSink && sd.ticks > 5;

        if (!hViolation && !noSink) {
            sd.decayVL();
            return CheckResult.passed();
        }

        if (hViolation) {
            double ratio = hDist / maxH;
            if (ratio > 1.4) {
                sd.hVL++;
                int threshold = ping > 150 ? 3 : 2;

                if (sd.hVL >= threshold || ratio > 2.2) {
                    double sev = Math.min(1.0, 0.5 + (ratio - 1.0) * 0.4);
                    return flagOrPass(player, sd, sev, String.format("[NoSnow] H:%.3f/%.3f r:%.1f", hDist, maxH, ratio));
                }
            }
        }

        if (noSink && sd.getAvgV() > maxSink) {
            sd.vVL++;

            if (sd.vVL >= 4) {
                double sev = Math.min(1.0, 0.55 + sd.getAvgV() * 2);
                return flagOrPass(player, sd, sev, String.format("[NoSnow] NoSink V:%.3f", sd.getAvgV()));
            }
        }

        return CheckResult.passed();
    }

    private CheckResult checkBerry(Player player, SlowData sd, double hDist, int ping) {
        double pingMult = ping > 150 ? 1.35 : (ping > 100 ? 1.2 : 1.0);
        double tpsMult = 20.0 / Math.max(getTPS(), 12.0);
        double combined = pingMult * tpsMult;

        double maxH = BERRY_MAX_H * combined;

        if (player.isSprinting()) {
            maxH *= 1.1;
        }

        sd.addMove(hDist, 0);

        if (hDist <= maxH) {
            sd.decayVL();
            return CheckResult.passed();
        }

        double ratio = hDist / maxH;
        if (ratio < 1.35) {
            return CheckResult.passed();
        }

        sd.hVL++;
        int threshold = ping > 150 ? 3 : 2;

        if (sd.hVL >= threshold || ratio > 2.2) {
            double sev = Math.min(1.0, 0.5 + (ratio - 1.0) * 0.4);
            return flagOrPass(player, sd, sev, String.format("[NoBerry] H:%.3f/%.3f r:%.1f", hDist, maxH, ratio));
        }

        return CheckResult.passed();
    }

    private CheckResult checkItem(Player player, SlowData sd, SlowCtx ctx, double hDist, int ping) {
        if (sd.ticks < 5) {
            sd.addMove(hDist, 0);
            return CheckResult.passed();
        }

        double base = 4.317 / 20.0;

        if (player.isSprinting()) {
            base = 5.612 / 20.0;
        }

        ItemStack mainHand = player.getInventory().getItemInMainHand();
        ItemStack offHand = player.getInventory().getItemInOffHand();
        double useMulti;
        if (ctx.type == SlowType.SHIELD) {
            useMulti = player.isBlocking() ? SHIELD_MULTI : 1.0;
        } else if (isMaterial(mainHand, Material.CROSSBOW) || isMaterial(offHand, Material.CROSSBOW)) {
            useMulti = 1.0;
        } else if (isMaterial(mainHand, Material.BOW) || isMaterial(offHand, Material.BOW) ||
                   isMaterial(mainHand, Material.TRIDENT) || isMaterial(offHand, Material.TRIDENT)) {
            useMulti = 0.2;
        } else {
            useMulti = EAT_MULTI;
        }
        base *= useMulti;
        if (useMulti >= 1.0) {
            sd.addMove(hDist, 0);
            sd.decayVL();
            return CheckResult.passed();
        }

        PotionEffectType speedType = PotionEffectType.getByName("SPEED");
        if (speedType == null) speedType = PotionEffectType.getByName("INCREASE_MOVEMENT_SPEED");
        if (speedType != null) {
            PotionEffect speed = player.getPotionEffect(speedType);
            if (speed != null) {
                base *= (1.0 + ((speed.getAmplifier() + 1) * 0.2));
            }
        }

        double pingMult = ping > 150 ? 1.35 : (ping > 100 ? 1.2 : 1.05);
        double tpsMult = 20.0 / Math.max(getTPS(), 12.0);
        double combined = pingMult * tpsMult;

        double maxH = base * combined * 1.2;

        sd.addMove(hDist, 0);

        if (hDist <= maxH) {
            sd.decayVL();
            return CheckResult.passed();
        }

        double ratio = hDist / maxH;
        if (ratio < 1.6) {
            return CheckResult.passed();
        }

        sd.hVL++;
        int threshold = ping > 150 ? 4 : 3;

        if (sd.hVL >= threshold || ratio > 2.5) {
            double sev = Math.min(1.0, 0.5 + (ratio - 1.0) * 0.35);
            String tag = ctx.type == SlowType.EAT ? "NoEat" : "NoShield";
            return flagOrPassItem(player, sd, sev, String.format("[%s] H:%.3f/%.3f r:%.1f", tag, hDist, maxH, ratio));
        }

        return CheckResult.passed();
    }

    private CheckResult flagOrPass(Player player, SlowData sd, double severity, String details) {
        CheckResult res = CheckResult.failed(ViolationType.NOSLOW, severity, details + " vl:" + sd.hVL);

        if (!filtering.passesLayer2HeuristicFiltering(player, ViolationType.NOSLOW, res)) {
            sd.decayVL();
            return CheckResult.passed();
        }

        sd.resetVL();
        return res;
    }

    private CheckResult flagOrPassWeb(Player player, SlowData sd, double severity, String details) {
        CheckResult res = CheckResult.failed(ViolationType.NOSLOW_WEB, severity, details + " vl:" + sd.hVL);

        if (!filtering.passesLayer2HeuristicFiltering(player, ViolationType.NOSLOW_WEB, res)) {
            sd.decayVL();
            return CheckResult.passed();
        }

        sd.resetVL();
        return res;
    }

    private CheckResult flagOrPassHoney(Player player, SlowData sd, double severity, String details) {
        CheckResult res = CheckResult.failed(ViolationType.NOSLOW_HONEY, severity, details + " vl:" + sd.hVL);

        if (!filtering.passesLayer2HeuristicFiltering(player, ViolationType.NOSLOW_HONEY, res)) {
            sd.decayVL();
            return CheckResult.passed();
        }

        sd.resetVL();
        return res;
    }

    private CheckResult flagOrPassItem(Player player, SlowData sd, double severity, String details) {
        CheckResult res = CheckResult.failed(ViolationType.NOSLOW_ITEM, severity, details + " vl:" + sd.hVL);

        if (!filtering.passesLayer2HeuristicFiltering(player, ViolationType.NOSLOW_ITEM, res)) {
            sd.decayVL();
            return CheckResult.passed();
        }

        sd.resetVL();
        return res;
    }

    private SlowCtx getContext(Player player, Location from, Location to) {
        SlowCtx ctx = new SlowCtx();

        Block feet = player.getLocation().getBlock();
        Block body = player.getLocation().clone().add(0, 1, 0).getBlock();
        Block below = player.getLocation().clone().subtract(0, 0.3, 0).getBlock();
        Block toFeet = to.getBlock();
        Block toBody = to.clone().add(0, 1, 0).getBlock();
        Block toBelow = to.clone().subtract(0, 0.3, 0).getBlock();

        if (feet.getType() == Material.COBWEB || body.getType() == Material.COBWEB ||
            toFeet.getType() == Material.COBWEB || toBody.getType() == Material.COBWEB) {
            ctx.slow = true;
            ctx.type = SlowType.WEB;
            return ctx;
        }

        Material b = below.getType();
        Material tb = toBelow.getType();

        if (b == Material.SOUL_SAND || b == Material.SOUL_SOIL ||
            tb == Material.SOUL_SAND || tb == Material.SOUL_SOIL) {
            ItemStack boots = player.getInventory().getBoots();
            boolean hasSoulSpeed = false;
            if (boots != null) {
                try {
                    Enchantment soulSpeedEnch = Enchantment.getByKey(org.bukkit.NamespacedKey.minecraft("soul_speed"));
                    if (soulSpeedEnch != null && boots.getEnchantmentLevel(soulSpeedEnch) > 0) {
                        hasSoulSpeed = true;
                    }
                } catch (Exception e) {
                    try {
                        Enchantment soulSpeedEnch = Enchantment.getByName("SOUL_SPEED");
                        if (soulSpeedEnch != null && boots.getEnchantmentLevel(soulSpeedEnch) > 0) {
                            hasSoulSpeed = true;
                        }
                    } catch (Exception e2) {
                        Logger.getLogger("NoChance").log(Level.WARNING, "Failed to check SOUL_SPEED enchantment", e2);
                    }
                }
            }
            if (!hasSoulSpeed) {
                ctx.slow = true;
                ctx.type = SlowType.SOUL;
                return ctx;
            }
        }

        if (isNearHoney(player, feet, body, below, toFeet, toBody, toBelow)) {
            ctx.slow = true;
            ctx.type = SlowType.HONEY;
            ctx.onSide = isOnHoneySide(player);
            return ctx;
        }

        if (feet.getType() == Material.POWDER_SNOW || toFeet.getType() == Material.POWDER_SNOW ||
            body.getType() == Material.POWDER_SNOW || toBody.getType() == Material.POWDER_SNOW) {
            ItemStack boots = player.getInventory().getBoots();
            if (boots == null || boots.getType() != Material.LEATHER_BOOTS) {
                ctx.slow = true;
                ctx.type = SlowType.SNOW;
                return ctx;
            }
        }

        if (feet.getType() == Material.SWEET_BERRY_BUSH || toFeet.getType() == Material.SWEET_BERRY_BUSH ||
            body.getType() == Material.SWEET_BERRY_BUSH || toBody.getType() == Material.SWEET_BERRY_BUSH) {
            ctx.slow = true;
            ctx.type = SlowType.BERRY;
            return ctx;
        }

        ItemStack main = player.getInventory().getItemInMainHand();
        ItemStack off = player.getInventory().getItemInOffHand();
        boolean using = player.isBlocking() || player.isHandRaised();

        if (using) {
            if (isShield(main) || isShield(off)) {
                ctx.slow = true;
                ctx.type = SlowType.SHIELD;
                return ctx;
            } else if (isUsable(main) || isUsable(off)) {
                ctx.slow = true;
                ctx.type = SlowType.EAT;
                return ctx;
            }
        }

        return ctx;
    }

    private boolean isNearHoney(Player player, Block feet, Block body, Block below, Block toFeet, Block toBody, Block toBelow) {
        if (below.getType() == Material.HONEY_BLOCK || toBelow.getType() == Material.HONEY_BLOCK) {
            return true;
        }
        if (feet.getType() == Material.HONEY_BLOCK || toFeet.getType() == Material.HONEY_BLOCK) {
            return true;
        }

        Location loc = player.getLocation();
        double px = loc.getX();
        double pz = loc.getZ();
        int bx = loc.getBlockX();
        int by = loc.getBlockY();
        int bz = loc.getBlockZ();

        double fx = px - bx;
        double fz = pz - bz;

        if (fx < 0.3) {
            Block side = loc.getWorld().getBlockAt(bx - 1, by, bz);
            Block sideUp = loc.getWorld().getBlockAt(bx - 1, by + 1, bz);
            if (side.getType() == Material.HONEY_BLOCK || sideUp.getType() == Material.HONEY_BLOCK) return true;
        }
        if (fx > 0.7) {
            Block side = loc.getWorld().getBlockAt(bx + 1, by, bz);
            Block sideUp = loc.getWorld().getBlockAt(bx + 1, by + 1, bz);
            if (side.getType() == Material.HONEY_BLOCK || sideUp.getType() == Material.HONEY_BLOCK) return true;
        }
        if (fz < 0.3) {
            Block side = loc.getWorld().getBlockAt(bx, by, bz - 1);
            Block sideUp = loc.getWorld().getBlockAt(bx, by + 1, bz - 1);
            if (side.getType() == Material.HONEY_BLOCK || sideUp.getType() == Material.HONEY_BLOCK) return true;
        }
        if (fz > 0.7) {
            Block side = loc.getWorld().getBlockAt(bx, by, bz + 1);
            Block sideUp = loc.getWorld().getBlockAt(bx, by + 1, bz + 1);
            if (side.getType() == Material.HONEY_BLOCK || sideUp.getType() == Material.HONEY_BLOCK) return true;
        }

        return false;
    }

    private boolean isOnHoneySide(Player player) {
        Location loc = player.getLocation();
        Block below = loc.clone().subtract(0, 0.1, 0).getBlock();

        if (below.getType() == Material.HONEY_BLOCK) {
            return false;
        }

        double px = loc.getX();
        double pz = loc.getZ();
        int bx = loc.getBlockX();
        int by = loc.getBlockY();
        int bz = loc.getBlockZ();

        double fx = px - bx;
        double fz = pz - bz;

        if (fx < 0.3 || fx > 0.7 || fz < 0.3 || fz > 0.7) {
            return true;
        }

        return false;
    }

    private boolean isShield(ItemStack i) {
        return i != null && i.getType() == Material.SHIELD;
    }

    private boolean isMaterial(ItemStack i, Material m) {
        return i != null && i.getType() == m;
    }

    private boolean isUsable(ItemStack i) {
        if (i == null || i.getType() == Material.AIR) return false;
        Material t = i.getType();
        return t.isEdible() || t == Material.POTION || t == Material.MILK_BUCKET ||
               t == Material.HONEY_BOTTLE || t == Material.BOW || t == Material.CROSSBOW ||
               t == Material.TRIDENT;
    }

    private double getTPS() {
        return NC.noChance.core.TPSCache.getTPS();
    }

    public void cleanup(UUID id) {
        dataMap.remove(id);
    }

    public void cleanupStale() {
        long now = System.currentTimeMillis();
        dataMap.entrySet().removeIf(e -> now - e.getValue().lastUpdate > 60000);
    }

    private enum SlowType {
        NONE, WEB, SOUL, HONEY, SNOW, BERRY, SHIELD, EAT
    }

    private static class SlowCtx {
        boolean slow = false;
        boolean onSide = false;
        SlowType type = SlowType.NONE;
    }

    private static class SlowData {
        int hVL = 0;
        int vVL = 0;
        int fallVL = 0;
        long lastVL = 0;
        long lastUpdate = System.currentTimeMillis();
        long lastSlowExitTime = 0;
        long useStartTime = 0;
        int ping = 50;
        int ticks = 0;
        double[] hHist = new double[8];
        double[] vHist = new double[8];
        int idx = 0;
        int count = 0;

        void addMove(double h, double v) {
            hHist[idx] = h;
            vHist[idx] = v;
            idx = (idx + 1) % hHist.length;
            if (count < hHist.length) count++;
            lastUpdate = System.currentTimeMillis();
        }

        double getAvgH() {
            if (count == 0) return 0;
            double sum = 0;
            for (int i = 0; i < count; i++) sum += hHist[i];
            return sum / count;
        }

        double getAvgV() {
            if (count == 0) return 0;
            double sum = 0;
            for (int i = 0; i < count; i++) sum += vHist[i];
            return sum / count;
        }

        void tick() {
            ticks++;
        }

        void decayVL() {
            long now = System.currentTimeMillis();
            if (now - lastVL > 1500) {
                if (hVL > 0) hVL--;
                if (vVL > 0) vVL--;
                if (fallVL > 0) fallVL--;
                lastVL = now;
            }
        }

        void resetVL() {
            hVL = 0;
            vVL = 0;
            fallVL = 0;
        }

        void clear() {
            ticks = 0;
            idx = 0;
            count = 0;
            hVL = 0;
            vVL = 0;
            fallVL = 0;
            Arrays.fill(hHist, 0);
            Arrays.fill(vHist, 0);
        }
    }
}
