package NC.noChance.sim;

import java.util.UUID;

public class SimPlayer {

    private volatile double velX, velY, velZ;

    private volatile boolean onGround;
    private volatile boolean sprinting;
    private volatile boolean sneaking;
    private volatile boolean swimming;
    private volatile boolean climbing;

    private volatile int airTicks;
    private volatile int groundTicks;
    private volatile int jumpTicks;
    private volatile int ticksExisted;

    private volatile double ewmaDev;
    private volatile int consecutiveDev;
    private volatile int samples;

    private volatile long lastUpdate;
    private volatile boolean valid;

    public SimPlayer(UUID ignoredId) {
        this.valid = false;
    }

    public void init(double x, double y, double z) {
        this.velX = 0;
        this.velY = 0;
        this.velZ = 0;
        this.onGround = false;
        this.sprinting = false;
        this.sneaking = false;
        this.swimming = false;
        this.climbing = false;
        this.airTicks = 0;
        this.groundTicks = 0;
        this.jumpTicks = 0;
        this.ticksExisted = 0;
        this.ewmaDev = 0;
        this.consecutiveDev = 0;
        this.samples = 0;
        this.lastUpdate = System.currentTimeMillis();
        this.valid = true;
    }

    public void updateActual(double x, double y, double z) {
        this.lastUpdate = System.currentTimeMillis();
    }

    public void setVelocity(double vx, double vy, double vz) {
        this.velX = vx;
        this.velY = vy;
        this.velZ = vz;
    }

    public synchronized void recordDeviation(double dev, double threshold) {
        this.ewmaDev = 0.15 * dev + 0.85 * this.ewmaDev;
        this.samples++;

        if (dev > threshold) {
            this.consecutiveDev++;
        } else {
            this.consecutiveDev = 0;
        }
    }

    public synchronized void resetDeviation() {
        this.ewmaDev = 0;
        this.consecutiveDev = 0;
    }

    public void softReset(double x, double y, double z) {
        this.velX = 0;
        this.velY = 0;
        this.velZ = 0;
        this.airTicks = 0;
        this.groundTicks = 0;
        this.jumpTicks = 0;
        this.ewmaDev = 0;
        this.consecutiveDev = 0;
        this.samples = 0;
        this.lastUpdate = System.currentTimeMillis();
    }

    public double getVelX() { return velX; }
    public double getVelY() { return velY; }
    public double getVelZ() { return velZ; }

    public boolean isOnGround() { return onGround; }
    public void setOnGround(boolean onGround) { this.onGround = onGround; }

    public boolean isSprinting() { return sprinting; }
    public void setSprinting(boolean sprinting) { this.sprinting = sprinting; }

    public boolean isSneaking() { return sneaking; }
    public void setSneaking(boolean sneaking) { this.sneaking = sneaking; }

    public boolean isSwimming() { return swimming; }
    public void setSwimming(boolean swimming) { this.swimming = swimming; }

    public boolean isClimbing() { return climbing; }
    public void setClimbing(boolean climbing) { this.climbing = climbing; }

    public int getAirTicks() { return airTicks; }
    public void setAirTicks(int airTicks) { this.airTicks = airTicks; }

    public int getGroundTicks() { return groundTicks; }
    public void setGroundTicks(int groundTicks) { this.groundTicks = groundTicks; }

    public int getJumpTicks() { return jumpTicks; }
    public void setJumpTicks(int jumpTicks) { this.jumpTicks = jumpTicks; }

    public int getTicksExisted() { return ticksExisted; }
    public void setTicksExisted(int ticksExisted) { this.ticksExisted = ticksExisted; }

    public double getEwmaDev() { return ewmaDev; }

    public int getConsecutiveDev() { return consecutiveDev; }

    public int getSamples() { return samples; }

    public long getLastUpdate() { return lastUpdate; }

    public boolean isValid() { return valid; }
}
