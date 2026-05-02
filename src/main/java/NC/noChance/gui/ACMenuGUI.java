package NC.noChance.gui;

import NC.noChance.NoChance;
import NC.noChance.core.ACConfig;
import NC.noChance.core.LangManager;
import NC.noChance.core.ViolationType;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class ACMenuGUI {
    private static final ItemStack BORDER;
    static {
        BORDER = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta m = BORDER.getItemMeta();
        if (m != null) {
            m.setDisplayName("§8");
            BORDER.setItemMeta(m);
        }
    }
    private static final long OPEN_THROTTLE_MS = 50L;
    private final Map<UUID, Long> lastOpen = new ConcurrentHashMap<>();
    public static final String ACT_BACK = "back";
    public static final String ACT_CLOSE = "close";
    public static final String ACT_SAVE = "save";
    public static final String ACT_OPEN_MOVEMENT = "open_movement";
    public static final String ACT_OPEN_BLOCK = "open_block";
    public static final String ACT_OPEN_COMBAT = "open_combat";
    public static final String ACT_OPEN_OTHER = "open_other";
    public static final String ACT_OPEN_THRESHOLDS = "open_thresholds";
    public static final String ACT_OPEN_STATISTICAL = "open_statistical";
    public static final String ACT_OPEN_SKILL_PROFILES = "open_skill_profiles";
    public static final String ACT_OPEN_GENERAL = "open_general";
    public static final String ACT_OPEN_KILLAURA = "open_killaura";
    public static final String ACT_OPEN_FASTBREAK = "open_fastbreak";
    public static final String ACT_OPEN_CHECK = "open_check";
    public static final String ACT_OPEN_SKILL = "open_skill";
    public static final String ACT_TOGGLE = "toggle";
    public static final String ACT_THRESHOLD = "threshold";
    public static final String ACT_SEVERITY = "severity";
    public static final String ACT_LOW_CONF = "low_conf";
    public static final String ACT_MED_CONF = "med_conf";
    public static final String ACT_HIGH_CONF = "high_conf";
    public static final String ACT_STD_DEV = "std_dev";
    public static final String ACT_OUTLIER = "outlier";
    public static final String ACT_TIME_WINDOW = "time_window";
    public static final String ACT_GRACE = "grace";
    public static final String ACT_MIN_SAMPLES = "min_samples";
    public static final String ACT_MIN_CPS = "min_cps";
    public static final String ACT_MAX_CPS = "max_cps";
    public static final String ACT_MIN_ROT = "min_rot";
    public static final String ACT_MAX_ROT = "max_rot";
    public static final String ACT_MIN_ACC = "min_acc";
    public static final String ACT_MAX_ACC = "max_acc";
    public static final String ACT_KA_MAX_CPS = "ka_max_cps";
    public static final String ACT_KA_CPS_TRUST = "ka_cps_trust";
    public static final String ACT_KA_MAX_ANGLE = "ka_max_angle";
    public static final String ACT_KA_ANGLE_TRUST = "ka_angle_trust";
    public static final String ACT_KA_MAX_ROT = "ka_max_rot";
    public static final String ACT_KA_REQ_VL = "ka_req_vl";
    public static final String ACT_KA_PKT_VAR = "ka_pkt_var";
    public static final String ACT_KA_PKT_RATE = "ka_pkt_rate";
    public static final String ACT_FB_BASE_TOL = "fb_base_tol";
    public static final String ACT_FB_PCT_TOL = "fb_pct_tol";
    public static final String ACT_FB_SHOVEL_TOL = "fb_shovel_tol";
    public static final String ACT_FB_SHOVEL_PCT = "fb_shovel_pct";
    public static final String ACT_FB_HAND_TOL = "fb_hand_tol";
    public static final String ACT_FB_TRUST_INST = "fb_trust_inst";
    public static final String ACT_FB_TRUST_NORM = "fb_trust_norm";

    private final Plugin plugin;
    private final ACConfig config;
    private final NamespacedKey actionKey;
    private final NamespacedKey paramKey;

    public ACMenuGUI(Plugin plugin, ACConfig config) {
        this.plugin = plugin;
        this.config = config;
        this.actionKey = new NamespacedKey(plugin, "gui_action");
        this.paramKey = new NamespacedKey(plugin, "gui_param");
    }

    public NamespacedKey actionKey() {
        return actionKey;
    }

    public NamespacedKey paramKey() {
        return paramKey;
    }

    private LangManager lang() {
        return ((NoChance) plugin).getLangManager();
    }

    private String t(Player p, String key, Object... args) {
        return lang().get(p, key, args);
    }

    private Inventory makeInv(Player p, MenuType type, String titleKey) {
        return makeInv(p, type, null, titleKey);
    }

    private Inventory makeInv(Player p, MenuType type, String param, String titleKey, Object... titleArgs) {
        ACMenuHolder holder = new ACMenuHolder(type, param);
        Inventory inv = Bukkit.createInventory(holder, 54, t(p, titleKey, titleArgs));
        holder.bind(inv);
        return inv;
    }

    private boolean throttled(Player p) {
        long now = System.currentTimeMillis();
        Long last = lastOpen.put(p.getUniqueId(), now);
        return last != null && now - last < OPEN_THROTTLE_MS;
    }

    public void openMainMenu(Player p) {
        if (throttled(p)) return;
        Inventory inv = makeInv(p, MenuType.MAIN, "gui.title.main");
        fillBorders(inv);

        inv.setItem(10, item(Material.FEATHER, ACT_OPEN_MOVEMENT, null,
                t(p, "gui.menu.main.movement.name"),
                lore(p, "gui.menu.main.movement.lore", 3)));

        inv.setItem(12, item(Material.DIAMOND_PICKAXE, ACT_OPEN_BLOCK, null,
                t(p, "gui.menu.main.block.name"),
                lore(p, "gui.menu.main.block.lore", 2)));

        inv.setItem(14, item(Material.DIAMOND_SWORD, ACT_OPEN_COMBAT, null,
                t(p, "gui.menu.main.combat.name"),
                lore(p, "gui.menu.main.combat.lore", 2)));

        inv.setItem(16, item(Material.GOLDEN_BOOTS, ACT_OPEN_OTHER, null,
                t(p, "gui.menu.main.other.name"),
                lore(p, "gui.menu.main.other.lore", 4)));

        inv.setItem(28, item(Material.COMPARATOR, ACT_OPEN_THRESHOLDS, null,
                t(p, "gui.menu.main.thresholds.name"),
                lore(p, "gui.menu.main.thresholds.lore", 2)));

        inv.setItem(30, item(Material.REDSTONE, ACT_OPEN_STATISTICAL, null,
                t(p, "gui.menu.main.statistical.name"),
                lore(p, "gui.menu.main.statistical.lore", 2)));

        inv.setItem(32, item(Material.PLAYER_HEAD, ACT_OPEN_SKILL_PROFILES, null,
                t(p, "gui.menu.main.skill_profiles.name"),
                lore(p, "gui.menu.main.skill_profiles.lore", 2)));

        inv.setItem(34, item(Material.PAPER, ACT_OPEN_GENERAL, null,
                t(p, "gui.menu.main.general.name"),
                lore(p, "gui.menu.main.general.lore", 2)));

        inv.setItem(49, item(Material.BARRIER, ACT_CLOSE, null,
                t(p, "gui.menu.main.close.name"),
                lore(p, "gui.menu.main.close.lore", 1)));

        p.openInventory(inv);
    }

    public void openMovementChecksMenu(Player p) {
        Inventory inv = makeInv(p, MenuType.MOVEMENT, "gui.title.movement");
        fillBorders(inv);

        addCheckItem(p, inv, 10, ViolationType.FLY, Material.FEATHER, "fly");
        addCheckItem(p, inv, 11, ViolationType.SPEED, Material.SUGAR, "speed");
        addCheckItem(p, inv, 12, ViolationType.NOCLIP, Material.BARRIER, "noclip");
        addCheckItem(p, inv, 13, ViolationType.JESUS, Material.WATER_BUCKET, "jesus");
        addCheckItem(p, inv, 14, ViolationType.BLINK, Material.ENDER_PEARL, "blink");
        addCheckItem(p, inv, 15, ViolationType.ELYTRAFLY, Material.ELYTRA, "elytrafly");
        addCheckItem(p, inv, 16, ViolationType.NOSLOW, Material.COBWEB, "noslow");

        inv.setItem(49, backItem(p, "gui.lore.back_main"));
        p.openInventory(inv);
    }

    public void openBlockChecksMenu(Player p) {
        Inventory inv = makeInv(p, MenuType.BLOCK, "gui.title.block");
        fillBorders(inv);

        addCheckItem(p, inv, 10, ViolationType.FASTBREAK, Material.DIAMOND_PICKAXE, "fastbreak");
        addCheckItem(p, inv, 12, ViolationType.FASTPLACE, Material.SCAFFOLDING, "fastplace");
        addCheckItem(p, inv, 14, ViolationType.NUKER, Material.TNT, "nuker");
        addCheckItem(p, inv, 16, ViolationType.SCAFFOLD, Material.OAK_PLANKS, "scaffold");

        inv.setItem(28, item(Material.REDSTONE_TORCH, ACT_OPEN_FASTBREAK, null,
                t(p, "gui.menu.block.fastbreak_settings.name"),
                lore(p, "gui.menu.block.fastbreak_settings.lore", 3)));

        inv.setItem(49, backItem(p, "gui.lore.back_main"));
        p.openInventory(inv);
    }

    public void openCombatChecksMenu(Player p) {
        Inventory inv = makeInv(p, MenuType.COMBAT, "gui.title.combat");
        fillBorders(inv);

        addCheckItem(p, inv, 10, ViolationType.KILLAURA, Material.DIAMOND_SWORD, "killaura");
        addCheckItem(p, inv, 12, ViolationType.AUTOCLICKER, Material.LEVER, "autoclicker");
        addCheckItem(p, inv, 14, ViolationType.REACH, Material.STICK, "reach");
        addCheckItem(p, inv, 16, ViolationType.INVENTORY, Material.CHEST, "inventory");

        inv.setItem(28, item(Material.REDSTONE_TORCH, ACT_OPEN_KILLAURA, null,
                t(p, "gui.menu.combat.killaura_settings.name"),
                lore(p, "gui.menu.combat.killaura_settings.lore", 3)));

        inv.setItem(49, backItem(p, "gui.lore.back_main"));
        p.openInventory(inv);
    }

    public void openDamageChecksMenu(Player p) {
        Inventory inv = makeInv(p, MenuType.OTHER, "gui.title.other");
        fillBorders(inv);

        addCheckItem(p, inv, 10, ViolationType.NOFALL, Material.GOLDEN_BOOTS, "nofall");
        addCheckItem(p, inv, 11, ViolationType.TIMER, Material.CLOCK, "timer");
        addCheckItem(p, inv, 12, ViolationType.VELOCITY, Material.SLIME_BALL, "velocity");
        addCheckItem(p, inv, 13, ViolationType.CRITICALS, Material.DIAMOND_AXE, "criticals");
        addCheckItem(p, inv, 14, ViolationType.PHASE, Material.END_PORTAL_FRAME, "phase");
        addCheckItem(p, inv, 15, ViolationType.STEP, Material.STONE_STAIRS, "step");
        addCheckItem(p, inv, 16, ViolationType.GROUNDSPOOF, Material.GRASS_BLOCK, "groundspoof");
        addCheckItem(p, inv, 28, ViolationType.BOATFLY, Material.OAK_BOAT, "boatfly");
        addCheckItem(p, inv, 29, ViolationType.STRIDER, Material.WARPED_FUNGUS_ON_A_STICK, "strider");

        inv.setItem(49, backItem(p, "gui.lore.back_main"));
        p.openInventory(inv);
    }

    public void openCheckConfigMenu(Player p, String checkName) {
        Inventory inv = makeInv(p, MenuType.CHECK_CONFIG, checkName,
                "gui.title.check_config", "name", checkName.toUpperCase());
        fillBorders(inv);

        boolean enabled = config.isCheckEnabled(checkName);
        inv.setItem(10, item(enabled ? Material.LIME_DYE : Material.GRAY_DYE, ACT_TOGGLE, checkName,
                t(p, enabled ? "gui.menu.check.enabled" : "gui.menu.check.disabled"),
                lore(p, "gui.menu.check.toggle_lore", 1)));

        int threshold = config.getViolationThreshold(checkName);
        inv.setItem(13, thresholdItem(p, threshold));

        double severity = config.getSeverityMultiplier(checkName);
        inv.setItem(16, severityItem(p, severity));

        inv.setItem(31, saveItem(p));
        inv.setItem(49, backItem(p, "gui.lore.back_checks"));
        p.openInventory(inv);
    }

    public void openThresholdMenu(Player p) {
        Inventory inv = makeInv(p, MenuType.THRESHOLDS, "gui.title.thresholds");
        fillBorders(inv);

        double lowConf = config.getLowConfidenceThreshold();
        double medConf = config.getMediumConfidenceThreshold();
        double highConf = config.getHighConfidenceThreshold();

        inv.setItem(11, progressItem(p, ACT_LOW_CONF, null,
                t(p, "gui.menu.thresholds.low.name"), lowConf, Material.YELLOW_STAINED_GLASS_PANE,
                t(p, "gui.common.current_pct", "value", String.format("%.0f%%", lowConf * 100)),
                "",
                t(p, "gui.common.click_dec", "amount", "5%"),
                t(p, "gui.common.click_inc", "amount", "5%")));

        inv.setItem(13, progressItem(p, ACT_MED_CONF, null,
                t(p, "gui.menu.thresholds.medium.name"), medConf, Material.ORANGE_STAINED_GLASS_PANE,
                t(p, "gui.common.current_pct", "value", String.format("%.0f%%", medConf * 100)),
                "",
                t(p, "gui.common.click_dec", "amount", "5%"),
                t(p, "gui.common.click_inc", "amount", "5%")));

        inv.setItem(15, progressItem(p, ACT_HIGH_CONF, null,
                t(p, "gui.menu.thresholds.high.name"), highConf, Material.RED_STAINED_GLASS_PANE,
                t(p, "gui.common.current_pct", "value", String.format("%.0f%%", highConf * 100)),
                "",
                t(p, "gui.common.click_dec", "amount", "5%"),
                t(p, "gui.common.click_inc", "amount", "5%")));

        inv.setItem(31, saveItem(p));
        inv.setItem(49, backItem(p, "gui.lore.back_main"));
        p.openInventory(inv);
    }

    public void openStatisticalMenu(Player p) {
        Inventory inv = makeInv(p, MenuType.STATISTICAL, "gui.title.statistical");
        fillBorders(inv);

        double stdDev = config.getStandardDeviationMultiplier();
        int outlier = config.getOutlierForgiveness();

        inv.setItem(12, progressItem(p, ACT_STD_DEV, null,
                t(p, "gui.menu.stat.std_dev.name"), stdDev / 5.0, Material.CYAN_STAINED_GLASS_PANE,
                t(p, "gui.common.current", "value", String.format("%.1f", stdDev)),
                "",
                t(p, "gui.common.click_dec", "amount", "0.5"),
                t(p, "gui.common.click_inc", "amount", "0.5")));

        inv.setItem(14, progressItem(p, ACT_OUTLIER, null,
                t(p, "gui.menu.stat.outlier.name"), outlier / 10.0, Material.MAGENTA_STAINED_GLASS_PANE,
                t(p, "gui.common.current", "value", String.valueOf(outlier)),
                "",
                t(p, "gui.common.click_dec", "amount", "1"),
                t(p, "gui.common.click_inc", "amount", "1")));

        inv.setItem(31, saveItem(p));
        inv.setItem(49, backItem(p, "gui.lore.back_main"));
        p.openInventory(inv);
    }

    public void openSkillProfilesMenu(Player p) {
        Inventory inv = makeInv(p, MenuType.SKILL_PROFILES, "gui.title.skill_profiles");
        fillBorders(inv);

        inv.setItem(11, skillProfileItem(p, "low", Material.WOODEN_SWORD,
                config.getLowSkillMinCPS(), config.getLowSkillMaxCPS(),
                config.getLowSkillMinRotation(), config.getLowSkillMaxRotation(),
                config.getLowSkillMinAccuracy(), config.getLowSkillMaxAccuracy()));

        inv.setItem(13, skillProfileItem(p, "medium", Material.IRON_SWORD,
                config.getMediumSkillMinCPS(), config.getMediumSkillMaxCPS(),
                config.getMediumSkillMinRotation(), config.getMediumSkillMaxRotation(),
                config.getMediumSkillMinAccuracy(), config.getMediumSkillMaxAccuracy()));

        inv.setItem(15, skillProfileItem(p, "high", Material.DIAMOND_SWORD,
                config.getHighSkillMinCPS(), config.getHighSkillMaxCPS(),
                config.getHighSkillMinRotation(), config.getHighSkillMaxRotation(),
                config.getHighSkillMinAccuracy(), config.getHighSkillMaxAccuracy()));

        inv.setItem(31, item(Material.BOOK, null, null,
                t(p, "gui.menu.skill_profiles.info.name"),
                lore(p, "gui.menu.skill_profiles.info.lore", 5)));

        inv.setItem(49, backItem(p, "gui.lore.back_main"));
        p.openInventory(inv);
    }

    private ItemStack skillProfileItem(Player p, String level, Material mat,
                                       int minCps, int maxCps,
                                       double minRot, double maxRot,
                                       double minAcc, double maxAcc) {
        List<String> lore = new ArrayList<>();
        lore.add(t(p, "gui.menu.skill_profiles.entry.min_cps", "value", String.valueOf(minCps)));
        lore.add(t(p, "gui.menu.skill_profiles.entry.max_cps", "value", String.valueOf(maxCps)));
        lore.add(t(p, "gui.menu.skill_profiles.entry.min_rot", "value", String.format("%.0f", minRot)));
        lore.add(t(p, "gui.menu.skill_profiles.entry.max_rot", "value", String.format("%.0f", maxRot)));
        lore.add(t(p, "gui.menu.skill_profiles.entry.min_acc", "value", String.format("%.0f", minAcc * 100)));
        lore.add(t(p, "gui.menu.skill_profiles.entry.max_acc", "value", String.format("%.0f", maxAcc * 100)));
        lore.add("");
        lore.add(t(p, "gui.common.click_configure"));
        return item(mat, ACT_OPEN_SKILL, level,
                t(p, "gui.menu.skill_profiles." + level + ".name"),
                lore.toArray(new String[0]));
    }

    public void openSkillConfigMenu(Player p, String level) {
        Inventory inv = makeInv(p, MenuType.SKILL_CONFIG, level,
                "gui.title.skill_config", "name", level.toUpperCase());
        fillBorders(inv);

        int minCPS, maxCPS;
        double minRotation, maxRotation, minAccuracy, maxAccuracy;

        if (level.equals("low")) {
            minCPS = config.getLowSkillMinCPS();
            maxCPS = config.getLowSkillMaxCPS();
            minRotation = config.getLowSkillMinRotation();
            maxRotation = config.getLowSkillMaxRotation();
            minAccuracy = config.getLowSkillMinAccuracy();
            maxAccuracy = config.getLowSkillMaxAccuracy();
        } else if (level.equals("medium")) {
            minCPS = config.getMediumSkillMinCPS();
            maxCPS = config.getMediumSkillMaxCPS();
            minRotation = config.getMediumSkillMinRotation();
            maxRotation = config.getMediumSkillMaxRotation();
            minAccuracy = config.getMediumSkillMinAccuracy();
            maxAccuracy = config.getMediumSkillMaxAccuracy();
        } else {
            minCPS = config.getHighSkillMinCPS();
            maxCPS = config.getHighSkillMaxCPS();
            minRotation = config.getHighSkillMinRotation();
            maxRotation = config.getHighSkillMaxRotation();
            minAccuracy = config.getHighSkillMinAccuracy();
            maxAccuracy = config.getHighSkillMaxAccuracy();
        }

        inv.setItem(10, item(Material.CLOCK, ACT_MIN_CPS, level,
                t(p, "gui.menu.skill.min_cps"),
                t(p, "gui.common.current", "value", String.valueOf(minCPS)),
                "",
                t(p, "gui.common.click_dec", "amount", "1"),
                t(p, "gui.common.click_inc", "amount", "1")));

        inv.setItem(11, item(Material.CLOCK, ACT_MAX_CPS, level,
                t(p, "gui.menu.skill.max_cps"),
                t(p, "gui.common.current", "value", String.valueOf(maxCPS)),
                "",
                t(p, "gui.common.click_dec", "amount", "1"),
                t(p, "gui.common.click_inc", "amount", "1")));

        inv.setItem(13, item(Material.COMPASS, ACT_MIN_ROT, level,
                t(p, "gui.menu.skill.min_rot"),
                t(p, "gui.common.current", "value", String.format("%.0f°/s", minRotation)),
                "",
                t(p, "gui.common.click_dec", "amount", "10"),
                t(p, "gui.common.click_inc", "amount", "10")));

        inv.setItem(14, item(Material.COMPASS, ACT_MAX_ROT, level,
                t(p, "gui.menu.skill.max_rot"),
                t(p, "gui.common.current", "value", String.format("%.0f°/s", maxRotation)),
                "",
                t(p, "gui.common.click_dec", "amount", "10"),
                t(p, "gui.common.click_inc", "amount", "10")));

        inv.setItem(16, item(Material.BOW, ACT_MIN_ACC, level,
                t(p, "gui.menu.skill.min_acc"),
                t(p, "gui.common.current", "value", String.format("%.0f%%", minAccuracy * 100)),
                "",
                t(p, "gui.common.click_dec", "amount", "5%"),
                t(p, "gui.common.click_inc", "amount", "5%")));

        inv.setItem(17, item(Material.BOW, ACT_MAX_ACC, level,
                t(p, "gui.menu.skill.max_acc"),
                t(p, "gui.common.current", "value", String.format("%.0f%%", maxAccuracy * 100)),
                "",
                t(p, "gui.common.click_dec", "amount", "5%"),
                t(p, "gui.common.click_inc", "amount", "5%")));

        inv.setItem(31, saveItem(p));
        inv.setItem(49, backItem(p, "gui.lore.back_profiles"));
        p.openInventory(inv);
    }

    public void openGeneralSettingsMenu(Player p) {
        Inventory inv = makeInv(p, MenuType.GENERAL, "gui.title.general");
        fillBorders(inv);

        int timeWindow = config.getTimeWindow();
        int gracePeriod = config.getGracePeriod();
        int minSamples = config.getMinSamples();

        inv.setItem(11, item(Material.CLOCK, ACT_TIME_WINDOW, null,
                t(p, "gui.menu.general.time_window.name"),
                t(p, "gui.common.current", "value", timeWindow + "s"),
                "",
                t(p, "gui.menu.general.time_window.desc.0"),
                t(p, "gui.menu.general.time_window.desc.1"),
                "",
                t(p, "gui.common.click_dec", "amount", "5s"),
                t(p, "gui.common.click_inc", "amount", "5s")));

        inv.setItem(13, item(Material.FEATHER, ACT_GRACE, null,
                t(p, "gui.menu.general.grace.name"),
                t(p, "gui.common.current", "value", gracePeriod + "s"),
                "",
                t(p, "gui.menu.general.grace.desc.0"),
                t(p, "gui.menu.general.grace.desc.1"),
                "",
                t(p, "gui.common.click_dec", "amount", "1s"),
                t(p, "gui.common.click_inc", "amount", "1s")));

        inv.setItem(15, item(Material.REDSTONE, ACT_MIN_SAMPLES, null,
                t(p, "gui.menu.general.min_samples.name"),
                t(p, "gui.common.current", "value", String.valueOf(minSamples)),
                "",
                t(p, "gui.menu.general.min_samples.desc.0"),
                t(p, "gui.menu.general.min_samples.desc.1"),
                "",
                t(p, "gui.common.click_dec", "amount", "1"),
                t(p, "gui.common.click_inc", "amount", "1")));

        inv.setItem(31, saveItem(p));
        inv.setItem(49, backItem(p, "gui.lore.back_main"));
        p.openInventory(inv);
    }

    public void openKillAuraSettingsMenu(Player p) {
        Inventory inv = makeInv(p, MenuType.KILLAURA, "gui.title.killaura");
        fillBorders(inv);

        inv.setItem(10, item(Material.REDSTONE, ACT_KA_MAX_CPS, null,
                t(p, "gui.menu.killaura.max_cps.name"),
                t(p, "gui.common.current", "value", String.valueOf(config.getKillAuraMaxCPS())),
                "",
                t(p, "gui.menu.killaura.max_cps.desc"),
                "",
                t(p, "gui.common.click_dec", "amount", "1"),
                t(p, "gui.common.click_inc", "amount", "1")));

        inv.setItem(11, item(Material.LIME_DYE, ACT_KA_CPS_TRUST, null,
                t(p, "gui.menu.killaura.cps_trust.name"),
                t(p, "gui.common.current", "value", "+" + config.getKillAuraCPSTrustedBonus()),
                "",
                t(p, "gui.menu.killaura.cps_trust.desc"),
                "",
                t(p, "gui.common.click_dec", "amount", "0.5"),
                t(p, "gui.common.click_inc", "amount", "0.5")));

        inv.setItem(13, item(Material.COMPASS, ACT_KA_MAX_ANGLE, null,
                t(p, "gui.menu.killaura.max_angle.name"),
                t(p, "gui.common.current", "value", config.getKillAuraMaxAngle() + "°"),
                "",
                t(p, "gui.menu.killaura.max_angle.desc"),
                "",
                t(p, "gui.common.click_dec", "amount", "2°"),
                t(p, "gui.common.click_inc", "amount", "2°")));

        inv.setItem(14, item(Material.LIME_DYE, ACT_KA_ANGLE_TRUST, null,
                t(p, "gui.menu.killaura.angle_trust.name"),
                t(p, "gui.common.current", "value", "+" + config.getKillAuraAngleTrustedBonus() + "°"),
                "",
                t(p, "gui.menu.killaura.angle_trust.desc"),
                "",
                t(p, "gui.common.click_dec", "amount", "2°"),
                t(p, "gui.common.click_inc", "amount", "2°")));

        inv.setItem(16, item(Material.ENDER_EYE, ACT_KA_MAX_ROT, null,
                t(p, "gui.menu.killaura.max_rot.name"),
                t(p, "gui.common.current", "value", config.getKillAuraMaxRotationSpeed() + "°/s"),
                "",
                t(p, "gui.menu.killaura.max_rot.desc"),
                "",
                t(p, "gui.common.click_dec", "amount", "20"),
                t(p, "gui.common.click_inc", "amount", "20")));

        inv.setItem(28, item(Material.PAPER, ACT_KA_REQ_VL, null,
                t(p, "gui.menu.killaura.req_vl.name"),
                t(p, "gui.common.current", "value", String.valueOf(config.getKillAuraRequiredViolations())),
                "",
                t(p, "gui.menu.killaura.req_vl.desc"),
                "",
                t(p, "gui.common.click_dec", "amount", "1"),
                t(p, "gui.common.click_inc", "amount", "1")));

        inv.setItem(30, item(Material.HOPPER, ACT_KA_PKT_VAR, null,
                t(p, "gui.menu.killaura.pkt_var.name"),
                t(p, "gui.common.current", "value", String.valueOf(config.getKillAuraPacketVarianceThreshold())),
                "",
                t(p, "gui.menu.killaura.pkt_var.desc"),
                "",
                t(p, "gui.common.click_dec", "amount", "5"),
                t(p, "gui.common.click_inc", "amount", "5")));

        inv.setItem(32, item(Material.CLOCK, ACT_KA_PKT_RATE, null,
                t(p, "gui.menu.killaura.pkt_rate.name"),
                t(p, "gui.common.current", "value", config.getKillAuraPacketAttackRateLimit() + "/s"),
                "",
                t(p, "gui.menu.killaura.pkt_rate.desc"),
                "",
                t(p, "gui.common.click_dec", "amount", "2"),
                t(p, "gui.common.click_inc", "amount", "2")));

        inv.setItem(49, backItem(p, "gui.lore.back_combat"));
        p.openInventory(inv);
    }

    public void openFastBreakSettingsMenu(Player p) {
        Inventory inv = makeInv(p, MenuType.FASTBREAK, "gui.title.fastbreak");
        fillBorders(inv);

        inv.setItem(10, item(Material.CLOCK, ACT_FB_BASE_TOL, null,
                t(p, "gui.menu.fastbreak.base_tol.name"),
                t(p, "gui.common.current", "value", config.getFastBreakBaseToleranceMs() + "ms"),
                "",
                t(p, "gui.menu.fastbreak.base_tol.desc"),
                "",
                t(p, "gui.common.click_dec", "amount", "5ms"),
                t(p, "gui.common.click_inc", "amount", "5ms")));

        double percentTolerance = config.getFastBreakPercentageTolerance() * 100;
        inv.setItem(12, item(Material.REDSTONE, ACT_FB_PCT_TOL, null,
                t(p, "gui.menu.fastbreak.pct_tol.name"),
                t(p, "gui.common.current", "value", percentTolerance + "%"),
                "",
                t(p, "gui.menu.fastbreak.pct_tol.desc"),
                "",
                t(p, "gui.common.click_dec", "amount", "2%"),
                t(p, "gui.common.click_inc", "amount", "2%")));

        inv.setItem(14, item(Material.IRON_SHOVEL, ACT_FB_SHOVEL_TOL, null,
                t(p, "gui.menu.fastbreak.shovel_tol.name"),
                t(p, "gui.common.current", "value", config.getFastBreakShovelInstantToleranceMs() + "ms"),
                "",
                t(p, "gui.menu.fastbreak.shovel_tol.desc"),
                "",
                t(p, "gui.common.click_dec", "amount", "5ms"),
                t(p, "gui.common.click_inc", "amount", "5ms")));

        double shovelPercent = config.getFastBreakShovelInstantPercentage() * 100;
        inv.setItem(15, item(Material.DIAMOND_SHOVEL, ACT_FB_SHOVEL_PCT, null,
                t(p, "gui.menu.fastbreak.shovel_pct.name"),
                t(p, "gui.common.current", "value", shovelPercent + "%"),
                "",
                t(p, "gui.menu.fastbreak.shovel_pct.desc"),
                "",
                t(p, "gui.common.click_dec", "amount", "3%"),
                t(p, "gui.common.click_inc", "amount", "3%")));

        inv.setItem(28, item(Material.PAPER, ACT_FB_HAND_TOL, null,
                t(p, "gui.menu.fastbreak.hand_tol.name"),
                t(p, "gui.common.current", "value", config.getFastBreakHandInstantToleranceMs() + "ms"),
                "",
                t(p, "gui.menu.fastbreak.hand_tol.desc"),
                "",
                t(p, "gui.common.click_dec", "amount", "3ms"),
                t(p, "gui.common.click_inc", "amount", "3ms")));

        inv.setItem(30, item(Material.LIME_DYE, ACT_FB_TRUST_INST, null,
                t(p, "gui.menu.fastbreak.trust_inst.name"),
                t(p, "gui.common.current", "value", "+" + config.getFastBreakTrustBonusInstantMs() + "ms"),
                "",
                t(p, "gui.menu.fastbreak.trust_inst.desc"),
                "",
                t(p, "gui.common.click_dec", "amount", "3ms"),
                t(p, "gui.common.click_inc", "amount", "3ms")));

        inv.setItem(32, item(Material.LIME_DYE, ACT_FB_TRUST_NORM, null,
                t(p, "gui.menu.fastbreak.trust_norm.name"),
                t(p, "gui.common.current", "value", "+" + config.getFastBreakTrustBonusNormalMs() + "ms"),
                "",
                t(p, "gui.menu.fastbreak.trust_norm.desc"),
                "",
                t(p, "gui.common.click_dec", "amount", "5ms"),
                t(p, "gui.common.click_inc", "amount", "5ms")));

        inv.setItem(49, backItem(p, "gui.lore.back_block"));
        p.openInventory(inv);
    }

    private void addCheckItem(Player p, Inventory inv, int slot, ViolationType type, Material material, String checkName) {
        boolean enabled = config.isCheckEnabled(checkName);
        int threshold = config.getViolationThreshold(checkName);
        double severity = config.getSeverityMultiplier(checkName);

        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            inv.setItem(slot, stack);
            return;
        }
        meta.setDisplayName((enabled ? "§a" : "§c") + "§l" + type.getDisplayName());

        List<String> lines = new ArrayList<>();
        lines.add("§8§m                              ");
        lines.add(t(p, "gui.menu.check.status", "value",
                t(p, enabled ? "gui.menu.check.status_on" : "gui.menu.check.status_off")));
        lines.add(t(p, "gui.menu.check.threshold", "value", String.valueOf(threshold)));
        lines.add(t(p, "gui.menu.check.severity", "value", String.format("%.1fx", severity)));
        lines.add(t(p, "gui.menu.check.complexity", "value", type.getComplexity() + "/5"));
        lines.add("§8§m                              ");
        lines.add(t(p, "gui.common.click_configure"));

        meta.setLore(lines);
        tag(meta, ACT_OPEN_CHECK, checkName);
        stack.setItemMeta(meta);
        inv.setItem(slot, stack);
    }

    private ItemStack thresholdItem(Player p, int threshold) {
        double percentage = Math.min(1.0, threshold / 20.0);
        return progressItem(p, ACT_THRESHOLD, null,
                t(p, "gui.menu.check.vl_threshold"),
                percentage, Material.RED_STAINED_GLASS_PANE,
                t(p, "gui.common.current", "value", String.valueOf(threshold)),
                "",
                t(p, "gui.common.click_dec", "amount", "1"),
                t(p, "gui.common.click_inc", "amount", "1"));
    }

    private ItemStack severityItem(Player p, double severity) {
        double percentage = Math.min(1.0, severity / 3.0);
        return progressItem(p, ACT_SEVERITY, null,
                t(p, "gui.menu.check.sev_mult"),
                percentage, Material.ORANGE_STAINED_GLASS_PANE,
                t(p, "gui.common.current", "value", String.format("%.1fx", severity)),
                "",
                t(p, "gui.common.click_dec", "amount", "0.1x"),
                t(p, "gui.common.click_inc", "amount", "0.1x"));
    }

    private ItemStack progressItem(Player p, String action, String param, String name, double percentage, Material mat, String... extra) {
        ItemStack stack = new ItemStack(mat);
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) return stack;
        meta.setDisplayName(name);

        List<String> lines = new ArrayList<>();
        lines.add("§8§m                              ");
        lines.add(progressBar(percentage));
        lines.add(t(p, "gui.common.percentage", "value", String.format("%.0f%%", percentage * 100)));
        for (String line : extra) lines.add(line);
        lines.add("§8§m                              ");

        meta.setLore(lines);
        tag(meta, action, param);
        stack.setItemMeta(meta);
        return stack;
    }

    private String progressBar(double percentage) {
        int filled = (int) (percentage * 20);
        StringBuilder bar = new StringBuilder("§7[");
        for (int i = 0; i < 20; i++) {
            bar.append(i < filled ? "§a█" : "§8█");
        }
        bar.append("§7]");
        return bar.toString();
    }

    private ItemStack item(Material mat, String action, String param, String name, String... lore) {
        ItemStack stack = new ItemStack(mat);
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) return stack;
        meta.setDisplayName(name);

        List<String> lines = new ArrayList<>();
        lines.add("§8§m                              ");
        for (String line : lore) lines.add(line);
        lines.add("§8§m                              ");
        meta.setLore(lines);

        if (action != null) tag(meta, action, param);
        stack.setItemMeta(meta);
        return stack;
    }

    private ItemStack backItem(Player p, String loreKey) {
        return item(Material.ARROW, ACT_BACK, null,
                t(p, "gui.common.back"),
                t(p, loreKey));
    }

    private ItemStack saveItem(Player p) {
        return item(Material.WRITABLE_BOOK, ACT_SAVE, null,
                t(p, "gui.common.save"),
                t(p, "gui.common.save_lore"));
    }

    private void tag(ItemMeta meta, String action, String param) {
        meta.getPersistentDataContainer().set(actionKey, PersistentDataType.STRING, action);
        if (param != null) {
            meta.getPersistentDataContainer().set(paramKey, PersistentDataType.STRING, param);
        }
    }

    private String[] lore(Player p, String baseKey, int count) {
        String[] out = new String[count];
        for (int i = 0; i < count; i++) {
            out[i] = t(p, baseKey + "." + i);
        }
        return out;
    }

    private void fillBorders(Inventory inv) {
        for (int i = 0; i < 9; i++) {
            if (inv.getItem(i) == null) inv.setItem(i, BORDER.clone());
        }
        for (int i = 45; i < 54; i++) {
            if (inv.getItem(i) == null) inv.setItem(i, BORDER.clone());
        }
        for (int i = 9; i < 45; i += 9) {
            if (inv.getItem(i) == null) inv.setItem(i, BORDER.clone());
            if (inv.getItem(i + 8) == null) inv.setItem(i + 8, BORDER.clone());
        }
    }

    public Plugin getPlugin() {
        return plugin;
    }

    public ACConfig getConfig() {
        return config;
    }
}
