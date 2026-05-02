package NC.noChance.core;

public enum ViolationType {
    FLY(3, 1.8, "Fly"),
    SPEED(4, 2.2, "Speed"),
    NOCLIP(2, 2.6, "NoClip"),
    JESUS(3, 1.6, "Jesus"),
    FASTBREAK(4, 1.9, "FastBreak"),
    FASTPLACE(2, 1.3, "FastPlace"),
    NUKER(3, 2.7, "Nuker"),
    KILLAURA(5, 2.8, "Kill Aura"),
    KILLAURA_MULTI(5, 3.0, "Kill Aura (Multi)"),
    KILLAURA_ANGLE(5, 2.6, "Kill Aura (Angle)"),
    KILLAURA_ROTATION(5, 2.7, "Kill Aura (Rotation)"),
    KILLAURA_PATTERN(5, 2.5, "Kill Aura (Pattern)"),
    NOFALL(4, 2.1, "NoFall"),
    AUTOCLICKER(4, 2.5, "AutoClicker"),
    REACH(4, 2.7, "Reach"),
    INVENTORY(3, 1.9, "Inventory"),
    SCAFFOLD(4, 2.4, "Scaffold"),
    TIMER(5, 3.2, "Timer"),
    VELOCITY(4, 2.8, "Velocity"),
    CRITICALS(4, 2.6, "Criticals"),
    PHASE(5, 3.1, "Phase"),
    STEP(3, 2.3, "Step"),
    BLINK(5, 3.0, "Blink"),
    NOSLOW(3, 2.7, "NoSlow"),

    GROUNDSPOOF(4, 2.9, "Ground Spoof"),
    ELYTRAFLY(5, 3.0, "Elytra Fly"),
    STRIDER(3, 2.4, "Strider"),
    BOATFLY(4, 2.8, "Boat Fly"),
    STRAFE(4, 2.5, "Strafe"),
    SPIDER(4, 2.8, "Spider"),
    AIMASSIST(5, 2.9, "Aim Assist"),
    AIMASSIST_SILENT(5, 3.1, "Aim Assist (Silent)"),
    GHOSTHAND(4, 2.8, "Ghost Hand"),
    INVALIDINTERACT(3, 2.5, "Invalid Interact"),
    PROTOCOL(5, 3.2, "Protocol"),
    SPEED_GROUND(4, 2.2, "Speed (Ground)"),
    SPEED_AIR(4, 2.4, "Speed (Air)"),
    SPEED_STRAFE(4, 2.3, "Speed (Strafe)"),
    FLY_HOVER(3, 1.8, "Fly (Hover)"),
    FLY_VERTICAL(3, 2.0, "Fly (Vertical)"),
    FLY_GLIDE(3, 1.9, "Fly (Glide)"),
    NOSLOW_ITEM(3, 2.7, "NoSlow (Item)"),
    NOSLOW_WEB(3, 2.6, "NoSlow (Web)"),
    NOSLOW_HONEY(3, 2.5, "NoSlow (Honey)"),
    SCAFFOLD_BRIDGE(4, 2.4, "Scaffold (Bridge)"),
    SCAFFOLD_TOWER(4, 2.6, "Scaffold (Tower)");

    private final int complexity;
    private final double weight;
    private final String displayName;

    ViolationType(int complexity, double weight, String displayName) {
        this.complexity = complexity;
        this.weight = weight;
        this.displayName = displayName;
    }

    public int getComplexity() {
        return complexity;
    }

    public double getWeight() {
        return weight;
    }

    public String getDisplayName() {
        return displayName;
    }
}
