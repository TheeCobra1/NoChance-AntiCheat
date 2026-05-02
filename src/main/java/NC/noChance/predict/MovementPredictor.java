package NC.noChance.predict;

import NC.noChance.core.TPSCache;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class MovementPredictor {

    private static final double GRAVITY = 0.08;
    private static final double DRAG_V = 0.98;
    private static final double DRAG_H = 0.91;
    private static final double JUMP_VEL = 0.42;
    private static final double EWMA_ALPHA = 0.28;
    private static final int MIN_SAMPLES = 20;
    private static final int MIN_AIR_TICKS = 3;

    private final Map<UUID, PredState> states = new ConcurrentHashMap<>();

    public PredResult process(Player player, Location from, Location to) {
        UUID id = player.getUniqueId();

        if (shouldSkip(player)) {
            reset(id);
            return PredResult.clean();
        }

        PredState state = states.get(id);
        double ax = to.getX();
        double ay = to.getY();
        double az = to.getZ();

        if (state == null) {
            state = new PredState(ax, ay, az);
            state.prevX = from.getX();
            state.prevY = from.getY();
            state.prevZ = from.getZ();
            states.put(id, state);
            return PredResult.clean();
        }

        state.lastUpdate = System.currentTimeMillis();

        double moveDist = Math.sqrt(
            Math.pow(ax - state.prevX, 2) + Math.pow(ay - state.prevY, 2) + Math.pow(az - state.prevZ, 2));
        if (moveDist > 5.0) {
            state.softReset(ax, ay, az);
            return PredResult.clean();
        }

        double deviation = 0;
        if (state.ready) {
            double pdx = ax - state.predX;
            double pdy = ay - state.predY;
            double pdz = az - state.predZ;
            deviation = Math.sqrt(pdx * pdx + pdy * pdy + pdz * pdz);
        }

        double vx = ax - state.prevX;
        double vy = ay - state.prevY;
        double vz = az - state.prevZ;

        boolean ground = player.isOnGround();
        double hFriction = getHFriction(player);

        double afterVX = vx * hFriction;
        double afterVZ = vz * hFriction;

        if (player.isInWater()) {
            afterVX *= 0.8;
            afterVZ *= 0.8;
        }

        double nextVY = (vy - GRAVITY) * DRAG_V;

        if (player.isInWater()) {
            nextVY = vy * 0.8 - GRAVITY * 0.4;
        }

        PotionEffectType levType = PotionEffectType.getByName("LEVITATION");
        if (levType != null && player.hasPotionEffect(levType)) {
            PotionEffect lev = player.getPotionEffect(levType);
            if (lev != null) {
                nextVY += 0.05 * (lev.getAmplifier() + 1);
            }
        }

        PotionEffectType sfType = PotionEffectType.getByName("SLOW_FALLING");
        if (sfType != null && player.hasPotionEffect(sfType)) {
            nextVY = Math.max(-0.01, nextVY);
        }

        if (ground && nextVY < 0) {
            nextVY = 0;
        }

        if (state.prevGround && vy > 0.3) {
            double jv = JUMP_VEL;
            PotionEffectType jt = PotionEffectType.getByName("JUMP_BOOST");
            if (jt == null) jt = PotionEffectType.getByName("JUMP");
            if (jt != null && player.hasPotionEffect(jt)) {
                PotionEffect je = player.getPotionEffect(jt);
                if (je != null) {
                    jv += (je.getAmplifier() + 1) * 0.1;
                }
            }
            Material belowMat = player.getLocation().clone().subtract(0, 0.2, 0).getBlock().getType();
            if (belowMat == Material.SLIME_BLOCK) {
                jv *= 2.5;
            }
            nextVY = jv;
        } else if (!ground && state.prevVy < 0) {
            Material belowMat = player.getLocation().clone().subtract(0, 0.2, 0).getBlock().getType();
            if (belowMat == Material.SLIME_BLOCK) {
                nextVY = -state.prevVy * 0.96;
            }
        }

        state.predX = ax + afterVX;
        state.predY = ay + nextVY;
        state.predZ = az + afterVZ;
        state.ready = true;

        state.prevX = ax;
        state.prevY = ay;
        state.prevZ = az;
        state.prevVy = vy;
        state.prevGround = ground;

        if (!ground) {
            state.airTicks++;
        } else {
            state.airTicks = 0;
        }

        double totalTol = calculateTolerance(player, ground);

        double adjusted = Math.max(0, deviation - totalTol);

        if (state.airTicks < MIN_AIR_TICKS && !ground) {
            adjusted = 0;
        }

        state.ewma = EWMA_ALPHA * adjusted + (1.0 - EWMA_ALPHA) * state.ewma;
        state.samples++;

        if (adjusted > 0.15) {
            state.consec++;
        } else {
            state.consec = Math.max(0, state.consec - 2);
        }

        boolean flag = false;
        double severity = 0;
        String reason = "";

        if (state.samples >= MIN_SAMPLES) {
            if (state.consec >= 10 && state.ewma > 0.20) {
                severity = Math.min(1.0, 0.45 + state.ewma);
                reason = "Sustained physics deviation ewma=" + String.format("%.3f", state.ewma);
                flag = true;
            } else if (state.ewma > 0.42 && state.consec >= 7) {
                severity = Math.min(1.0, 0.4 + state.ewma * 0.6);
                reason = "High physics deviation ewma=" + String.format("%.3f", state.ewma);
                flag = true;
            } else if (adjusted > 1.8 && state.consec >= 5) {
                severity = Math.min(1.0, 0.5 + (adjusted - 2.0) * 0.2);
                reason = "Extreme position mismatch dev=" + String.format("%.3f", adjusted);
                flag = true;
            }
        }

        return new PredResult(deviation, adjusted, state.ewma, severity, state.consec, flag, reason);
    }

    private boolean shouldSkip(Player player) {
        if (player.getVehicle() != null) return true;
        if (player.isRiptiding()) return true;
        if (player.isGliding()) return true;
        if (player.isFlying() && player.getAllowFlight()) return true;
        GameMode gm = player.getGameMode();
        if (gm == GameMode.CREATIVE || gm == GameMode.SPECTATOR) return true;
        if (!player.hasGravity()) return true;
        return false;
    }

    private double calculateTolerance(Player player, boolean ground) {
        double inputTol = ground ? 0.15 : 0.12;
        int ping = player.getPing();
        double pingTol = Math.max(0, (ping - 30) * 0.003);
        if (ping > 200) {
            pingTol += (ping - 200) * 0.002;
        }

        double speedMult = getSpeedMultiplier(player);
        inputTol *= speedMult;

        double envTol = getEnvTolerance(player);
        double totalTol = inputTol + pingTol + envTol;

        double tps = TPSCache.getTPS();
        if (tps < 19.0) {
            totalTol += (20.0 - tps) * 0.06;
        }
        if (tps < 16.0) {
            totalTol += 0.3;
        }

        long now = System.currentTimeMillis();
        Location loc = player.getLocation();

        boolean nearPiston = false;
        for (int dx = -1; dx <= 1 && !nearPiston; dx++) {
            for (int dz = -1; dz <= 1 && !nearPiston; dz++) {
                Block b = loc.clone().add(dx, 0, dz).getBlock();
                String name = b.getType().name();
                if (name.contains("PISTON")) {
                    totalTol += 0.4;
                    nearPiston = true;
                }
            }
        }

        return totalTol;
    }

    private double getHFriction(Player player) {
        if (!player.isOnGround()) return DRAG_H;
        Location loc = player.getLocation();
        Block below = loc.clone().subtract(0, 0.2, 0).getBlock();
        Material type = below.getType();
        double slip = getSlipperiness(type);
        return slip * DRAG_H;
    }

    private double getSlipperiness(Material type) {
        if (type == Material.ICE || type == Material.PACKED_ICE || type == Material.FROSTED_ICE) return 0.98;
        if (type == Material.BLUE_ICE) return 0.989;
        if (type == Material.SLIME_BLOCK) return 0.8;
        return 0.6;
    }

    private double getSpeedMultiplier(Player player) {
        double mult = 1.0;

        if (player.isSprinting()) mult *= 1.3;

        PotionEffectType speedType = PotionEffectType.getByName("SPEED");
        if (speedType == null) speedType = PotionEffectType.getByName("INCREASE_MOVEMENT_SPEED");
        if (speedType != null && player.hasPotionEffect(speedType)) {
            PotionEffect eff = player.getPotionEffect(speedType);
            if (eff != null) {
                mult *= 1.0 + ((eff.getAmplifier() + 1) * 0.2);
            }
        }

        PotionEffectType slowType = PotionEffectType.getByName("SLOWNESS");
        if (slowType == null) slowType = PotionEffectType.getByName("SLOW");
        if (slowType != null && player.hasPotionEffect(slowType)) {
            PotionEffect eff = player.getPotionEffect(slowType);
            if (eff != null) {
                mult *= Math.max(0.1, 1.0 - ((eff.getAmplifier() + 1) * 0.15));
            }
        }

        PotionEffectType dolphinType = PotionEffectType.getByName("DOLPHINS_GRACE");
        if (dolphinType != null && player.hasPotionEffect(dolphinType) && player.isSwimming()) {
            mult *= 1.5;
        }

        Location loc = player.getLocation();
        Material below = loc.clone().subtract(0, 0.3, 0).getBlock().getType();
        if (below == Material.ICE || below == Material.PACKED_ICE || below == Material.FROSTED_ICE) {
            mult *= 2.6;
        } else if (below == Material.BLUE_ICE) {
            mult *= 3.2;
        } else if (below == Material.SOUL_SAND || below == Material.SOUL_SOIL) {
            org.bukkit.inventory.ItemStack boots = player.getInventory().getBoots();
            int soulSpeed = 0;
            if (boots != null) {
                soulSpeed = boots.getEnchantmentLevel(org.bukkit.enchantments.Enchantment.SOUL_SPEED);
            }
            if (soulSpeed > 0) {
                mult *= 1.3 + (soulSpeed * 0.105);
            } else {
                mult *= 0.4;
            }
        } else if (below == Material.HONEY_BLOCK) {
            mult *= 0.4;
        }

        if (player.getWalkSpeed() != 0.2f) {
            mult *= (player.getWalkSpeed() / 0.2f);
        }

        return mult;
    }

    private double getEnvTolerance(Player player) {
        double tol = 0;
        if (player.isGliding()) tol += 0.7;
        if (player.isSwimming()) tol += 0.35;
        if (player.isClimbing()) tol += 0.35;
        if (player.isInWater()) tol += 0.25;

        Location loc = player.getLocation();
        Material at = loc.getBlock().getType();
        if (at == Material.COBWEB) tol += 0.4;
        if (at == Material.BUBBLE_COLUMN) tol += 0.6;
        if (at == Material.POWDER_SNOW) tol += 0.35;
        if (at == Material.SCAFFOLDING) tol += 0.4;
        if (at.name().contains("WATER") || at.name().contains("LAVA")) tol += 0.25;

        Material below = loc.clone().subtract(0, 0.5, 0).getBlock().getType();
        if (below == Material.SLIME_BLOCK) tol += 0.5;
        if (below == Material.HONEY_BLOCK) tol += 0.35;
        if (below.name().contains("BED")) tol += 0.4;
        if (below.name().contains("STAIRS") || below.name().contains("SLAB")) tol += 0.15;

        PotionEffectType levType = PotionEffectType.getByName("LEVITATION");
        if (levType != null && player.hasPotionEffect(levType)) {
            PotionEffect lev = player.getPotionEffect(levType);
            tol += 0.3 + (lev != null ? (lev.getAmplifier() + 1) * 0.1 : 0);
        }
        PotionEffectType sfType = PotionEffectType.getByName("SLOW_FALLING");
        if (sfType != null && player.hasPotionEffect(sfType)) tol += 0.35;

        return tol;
    }

    public void reset(UUID id) {
        states.remove(id);
    }

    public void cleanup() {
        long now = System.currentTimeMillis();
        states.entrySet().removeIf(e -> now - e.getValue().lastUpdate > 120000);
    }

    public static class PredState {
        double predX, predY, predZ;
        double prevX, prevY, prevZ;
        double prevVy;
        boolean prevGround;
        boolean ready;
        double ewma;
        int samples;
        int consec;
        int airTicks;
        long lastUpdate;

        PredState(double x, double y, double z) {
            this.predX = x;
            this.predY = y;
            this.predZ = z;
            this.lastUpdate = System.currentTimeMillis();
        }

        void softReset(double x, double y, double z) {
            this.predX = x;
            this.predY = y;
            this.predZ = z;
            this.prevX = x;
            this.prevY = y;
            this.prevZ = z;
            this.prevVy = 0;
            this.ready = false;
            this.ewma = 0;
            this.consec = 0;
            this.airTicks = 0;
            this.samples = 0;
            this.lastUpdate = System.currentTimeMillis();
        }
    }

    public static class PredResult {
        public final double rawDev;
        public final double adjDev;
        public final double ewma;
        public final double severity;
        public final int consec;
        public final boolean shouldFlag;
        public final String reason;

        PredResult(double rawDev, double adjDev, double ewma, double severity,
                   int consec, boolean shouldFlag, String reason) {
            this.rawDev = rawDev;
            this.adjDev = adjDev;
            this.ewma = ewma;
            this.severity = severity;
            this.consec = consec;
            this.shouldFlag = shouldFlag;
            this.reason = reason;
        }

        static PredResult clean() {
            return new PredResult(0, 0, 0, 0, 0, false, "");
        }
    }
}
