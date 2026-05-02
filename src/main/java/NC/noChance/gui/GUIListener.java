package NC.noChance.gui;

import NC.noChance.NoChance;
import NC.noChance.core.ACConfig;
import NC.noChance.core.LangManager;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryInteractEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import static NC.noChance.gui.ACMenuGUI.*;

public class GUIListener implements Listener {
    private final ACMenuGUI menuGUI;
    private final Plugin plugin;
    private final ACConfig config;

    public GUIListener(ACMenuGUI menuGUI) {
        this.menuGUI = menuGUI;
        this.plugin = menuGUI.getPlugin();
        this.config = menuGUI.getConfig();
    }

    private LangManager lang() {
        return ((NoChance) plugin).getLangManager();
    }

    private String msg(Player p, String key, Object... placeholders) {
        return lang().get(p, key, placeholders);
    }

    private ACMenuHolder holderOf(Inventory inv) {
        if (inv == null) return null;
        return inv.getHolder() instanceof ACMenuHolder h ? h : null;
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        ACMenuHolder holder = holderOf(event.getInventory());
        if (holder == null) return;

        event.setCancelled(true);
        event.setResult(org.bukkit.event.Event.Result.DENY);

        if (!player.hasPermission("nochance.admin")) {
            player.closeInventory();
            return;
        }

        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || clicked.getType().isAir() || !clicked.hasItemMeta()) return;

        ItemMeta meta = clicked.getItemMeta();
        if (meta == null) return;

        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        String action = pdc.get(menuGUI.actionKey(), PersistentDataType.STRING);
        if (action == null) return;

        String param = pdc.get(menuGUI.paramKey(), PersistentDataType.STRING);
        dispatch(player, holder, action, param, event);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (holderOf(event.getInventory()) != null) {
            event.setCancelled(true);
            event.setResult(org.bukkit.event.Event.Result.DENY);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onInventoryInteract(InventoryInteractEvent event) {
        if (holderOf(event.getInventory()) != null) {
            event.setCancelled(true);
            event.setResult(org.bukkit.event.Event.Result.DENY);
        }
    }

    private void dispatch(Player p, ACMenuHolder holder, String action, String param, InventoryClickEvent event) {
        switch (action) {
            case ACT_CLOSE -> p.closeInventory();
            case ACT_BACK -> handleBack(p, holder);
            case ACT_SAVE -> handleSave(p, holder);
            case ACT_OPEN_MOVEMENT -> menuGUI.openMovementChecksMenu(p);
            case ACT_OPEN_BLOCK -> menuGUI.openBlockChecksMenu(p);
            case ACT_OPEN_COMBAT -> menuGUI.openCombatChecksMenu(p);
            case ACT_OPEN_OTHER -> menuGUI.openDamageChecksMenu(p);
            case ACT_OPEN_THRESHOLDS -> menuGUI.openThresholdMenu(p);
            case ACT_OPEN_STATISTICAL -> menuGUI.openStatisticalMenu(p);
            case ACT_OPEN_SKILL_PROFILES -> menuGUI.openSkillProfilesMenu(p);
            case ACT_OPEN_GENERAL -> menuGUI.openGeneralSettingsMenu(p);
            case ACT_OPEN_KILLAURA -> menuGUI.openKillAuraSettingsMenu(p);
            case ACT_OPEN_FASTBREAK -> menuGUI.openFastBreakSettingsMenu(p);
            case ACT_OPEN_CHECK -> {
                if (param != null) menuGUI.openCheckConfigMenu(p, param);
            }
            case ACT_OPEN_SKILL -> {
                if (param != null) menuGUI.openSkillConfigMenu(p, param);
            }
            case ACT_TOGGLE -> handleToggle(p, param);
            case ACT_THRESHOLD -> handleThresholdAdjust(p, holder, event);
            case ACT_SEVERITY -> handleSeverityAdjust(p, holder, event);
            case ACT_LOW_CONF -> handleLowConf(p, event);
            case ACT_MED_CONF -> handleMedConf(p, event);
            case ACT_HIGH_CONF -> handleHighConf(p, event);
            case ACT_STD_DEV -> handleStdDev(p, event);
            case ACT_OUTLIER -> handleOutlier(p, event);
            case ACT_TIME_WINDOW -> handleTimeWindow(p, event);
            case ACT_GRACE -> handleGrace(p, event);
            case ACT_MIN_SAMPLES -> handleMinSamples(p, event);
            case ACT_MIN_CPS -> handleMinCps(p, holder, event);
            case ACT_MAX_CPS -> handleMaxCps(p, holder, event);
            case ACT_MIN_ROT -> handleMinRot(p, holder, event);
            case ACT_MAX_ROT -> handleMaxRot(p, holder, event);
            case ACT_MIN_ACC -> handleMinAcc(p, holder, event);
            case ACT_MAX_ACC -> handleMaxAcc(p, holder, event);
            case ACT_KA_MAX_CPS -> handleKaMaxCps(p, event);
            case ACT_KA_CPS_TRUST -> handleKaCpsTrust(p, event);
            case ACT_KA_MAX_ANGLE -> handleKaMaxAngle(p, event);
            case ACT_KA_ANGLE_TRUST -> handleKaAngleTrust(p, event);
            case ACT_KA_MAX_ROT -> handleKaMaxRot(p, event);
            case ACT_KA_REQ_VL -> handleKaReqVl(p, event);
            case ACT_KA_PKT_VAR -> handleKaPktVar(p, event);
            case ACT_KA_PKT_RATE -> handleKaPktRate(p, event);
            case ACT_FB_BASE_TOL -> handleFbBaseTol(p, event);
            case ACT_FB_PCT_TOL -> handleFbPctTol(p, event);
            case ACT_FB_SHOVEL_TOL -> handleFbShovelTol(p, event);
            case ACT_FB_SHOVEL_PCT -> handleFbShovelPct(p, event);
            case ACT_FB_HAND_TOL -> handleFbHandTol(p, event);
            case ACT_FB_TRUST_INST -> handleFbTrustInst(p, event);
            case ACT_FB_TRUST_NORM -> handleFbTrustNorm(p, event);
            default -> {}
        }
    }

    private void handleBack(Player p, ACMenuHolder h) {
        switch (h.type()) {
            case MOVEMENT, BLOCK, COMBAT, OTHER, THRESHOLDS, STATISTICAL, SKILL_PROFILES, GENERAL -> menuGUI.openMainMenu(p);
            case CHECK_CONFIG -> backFromCheckConfig(p, h.param());
            case SKILL_CONFIG -> menuGUI.openSkillProfilesMenu(p);
            case KILLAURA -> menuGUI.openCombatChecksMenu(p);
            case FASTBREAK -> menuGUI.openBlockChecksMenu(p);
            default -> p.closeInventory();
        }
    }

    private void backFromCheckConfig(Player p, String checkName) {
        if (checkName == null) { menuGUI.openMainMenu(p); return; }
        switch (checkName) {
            case "fly", "speed", "noclip", "jesus", "blink", "elytrafly", "noslow" -> menuGUI.openMovementChecksMenu(p);
            case "fastbreak", "fastplace", "nuker", "scaffold" -> menuGUI.openBlockChecksMenu(p);
            case "killaura", "autoclicker", "reach", "inventory" -> menuGUI.openCombatChecksMenu(p);
            case "nofall", "timer", "velocity", "criticals", "phase", "step", "groundspoof", "boatfly", "strider" -> menuGUI.openDamageChecksMenu(p);
            default -> menuGUI.openMainMenu(p);
        }
    }

    private void handleSave(Player p, ACMenuHolder h) {
        plugin.saveConfig();
        config.reload();
        p.sendMessage(msg(p, "gui.feedback.saved"));
        p.playSound(p.getLocation(), org.bukkit.Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, 1.0f);
        reopen(p, h);
    }

    private void reopen(Player p, ACMenuHolder h) {
        switch (h.type()) {
            case CHECK_CONFIG -> { if (h.param() != null) menuGUI.openCheckConfigMenu(p, h.param()); }
            case SKILL_CONFIG -> { if (h.param() != null) menuGUI.openSkillConfigMenu(p, h.param()); }
            case THRESHOLDS -> menuGUI.openThresholdMenu(p);
            case STATISTICAL -> menuGUI.openStatisticalMenu(p);
            case GENERAL -> menuGUI.openGeneralSettingsMenu(p);
            case KILLAURA -> menuGUI.openKillAuraSettingsMenu(p);
            case FASTBREAK -> menuGUI.openFastBreakSettingsMenu(p);
            case MOVEMENT -> menuGUI.openMovementChecksMenu(p);
            case BLOCK -> menuGUI.openBlockChecksMenu(p);
            case COMBAT -> menuGUI.openCombatChecksMenu(p);
            case OTHER -> menuGUI.openDamageChecksMenu(p);
            case SKILL_PROFILES -> menuGUI.openSkillProfilesMenu(p);
            default -> menuGUI.openMainMenu(p);
        }
    }

    private void handleToggle(Player p, String checkName) {
        if (checkName == null) return;
        boolean current = config.isCheckEnabled(checkName);
        plugin.getConfig().set("checks." + checkName + ".enabled", !current);
        plugin.saveConfig();
        config.reload();
        p.sendMessage(msg(p, !current ? "gui.feedback.toggled_on" : "gui.feedback.toggled_off", "check", checkName));
        menuGUI.openCheckConfigMenu(p, checkName);
    }

    private void handleThresholdAdjust(Player p, ACMenuHolder h, InventoryClickEvent e) {
        if (h.param() == null) return;
        int current = config.getViolationThreshold(h.param());
        int newValue = e.isLeftClick() ? Math.max(1, current - 1) : current + 1;
        plugin.getConfig().set("checks." + h.param() + ".threshold", newValue);
        plugin.saveConfig();
        config.reload();
        p.sendMessage(msg(p, "gui.feedback.threshold_set", "value", newValue));
        menuGUI.openCheckConfigMenu(p, h.param());
    }

    private void handleSeverityAdjust(Player p, ACMenuHolder h, InventoryClickEvent e) {
        if (h.param() == null) return;
        double current = config.getSeverityMultiplier(h.param());
        double newValue = e.isLeftClick() ? Math.max(0.1, current - 0.1) : Math.min(5.0, current + 0.1);
        plugin.getConfig().set("checks." + h.param() + ".severity_multiplier", Math.round(newValue * 10.0) / 10.0);
        plugin.saveConfig();
        config.reload();
        p.sendMessage(msg(p, "gui.feedback.severity_set", "value", String.format("%.1fx", newValue)));
        menuGUI.openCheckConfigMenu(p, h.param());
    }

    private void handleLowConf(Player p, InventoryClickEvent e) {
        double current = config.getLowConfidenceThreshold();
        double medium = config.getMediumConfidenceThreshold();
        double newValue = Math.max(0.0, Math.min(1.0, current + (e.isLeftClick() ? -0.05 : 0.05)));
        if (newValue >= medium) { p.sendMessage(msg(p, "gui.feedback.err_low_lt_med")); return; }
        plugin.getConfig().set("thresholds.low_confidence", Math.round(newValue * 100.0) / 100.0);
        plugin.saveConfig();
        config.reload();
        p.sendMessage(msg(p, "gui.feedback.value_set", "label", msg(p, "gui.label.low"), "value", String.format("%.0f%%", newValue * 100)));
        menuGUI.openThresholdMenu(p);
    }

    private void handleMedConf(Player p, InventoryClickEvent e) {
        double low = config.getLowConfidenceThreshold();
        double high = config.getHighConfidenceThreshold();
        double current = config.getMediumConfidenceThreshold();
        double newValue = Math.max(0.0, Math.min(1.0, current + (e.isLeftClick() ? -0.05 : 0.05)));
        if (newValue <= low || newValue >= high) { p.sendMessage(msg(p, "gui.feedback.err_med_between")); return; }
        plugin.getConfig().set("thresholds.medium_confidence", Math.round(newValue * 100.0) / 100.0);
        plugin.saveConfig();
        config.reload();
        p.sendMessage(msg(p, "gui.feedback.value_set", "label", msg(p, "gui.label.medium"), "value", String.format("%.0f%%", newValue * 100)));
        menuGUI.openThresholdMenu(p);
    }

    private void handleHighConf(Player p, InventoryClickEvent e) {
        double medium = config.getMediumConfidenceThreshold();
        double current = config.getHighConfidenceThreshold();
        double newValue = Math.max(0.0, Math.min(1.0, current + (e.isLeftClick() ? -0.05 : 0.05)));
        if (newValue <= medium) { p.sendMessage(msg(p, "gui.feedback.err_high_gt_med")); return; }
        plugin.getConfig().set("thresholds.high_confidence", Math.round(newValue * 100.0) / 100.0);
        plugin.saveConfig();
        config.reload();
        p.sendMessage(msg(p, "gui.feedback.value_set", "label", msg(p, "gui.label.high"), "value", String.format("%.0f%%", newValue * 100)));
        menuGUI.openThresholdMenu(p);
    }

    private void handleStdDev(Player p, InventoryClickEvent e) {
        double current = config.getStandardDeviationMultiplier();
        double newValue = Math.max(1.0, Math.min(5.0, current + (e.isLeftClick() ? -0.5 : 0.5)));
        plugin.getConfig().set("statistical.std_dev_multiplier", Math.round(newValue * 10.0) / 10.0);
        plugin.saveConfig();
        config.reload();
        p.sendMessage(msg(p, "gui.feedback.value_set", "label", msg(p, "gui.label.std_dev"), "value", String.format("%.1f", newValue)));
        menuGUI.openStatisticalMenu(p);
    }

    private void handleOutlier(Player p, InventoryClickEvent e) {
        int current = config.getOutlierForgiveness();
        int newValue = Math.max(0, Math.min(10, current + (e.isLeftClick() ? -1 : 1)));
        plugin.getConfig().set("statistical.outlier_forgiveness", newValue);
        plugin.saveConfig();
        config.reload();
        p.sendMessage(msg(p, "gui.feedback.value_set", "label", msg(p, "gui.label.outlier"), "value", newValue));
        menuGUI.openStatisticalMenu(p);
    }

    private void handleTimeWindow(Player p, InventoryClickEvent e) {
        int current = config.getTimeWindow();
        int newValue = Math.max(10, Math.min(300, current + (e.isLeftClick() ? -5 : 5)));
        plugin.getConfig().set("general.time_window_seconds", newValue);
        plugin.saveConfig();
        config.reload();
        p.sendMessage(msg(p, "gui.feedback.value_set", "label", msg(p, "gui.label.time_window"), "value", newValue + "s"));
        menuGUI.openGeneralSettingsMenu(p);
    }

    private void handleGrace(Player p, InventoryClickEvent e) {
        int current = config.getGracePeriod();
        int newValue = Math.max(0, Math.min(60, current + (e.isLeftClick() ? -1 : 1)));
        plugin.getConfig().set("general.grace_period_seconds", newValue);
        plugin.saveConfig();
        config.reload();
        p.sendMessage(msg(p, "gui.feedback.value_set", "label", msg(p, "gui.label.grace"), "value", newValue + "s"));
        menuGUI.openGeneralSettingsMenu(p);
    }

    private void handleMinSamples(Player p, InventoryClickEvent e) {
        int current = config.getMinSamples();
        int newValue = Math.max(1, Math.min(50, current + (e.isLeftClick() ? -1 : 1)));
        plugin.getConfig().set("general.min_samples", newValue);
        plugin.saveConfig();
        config.reload();
        p.sendMessage(msg(p, "gui.feedback.value_set", "label", msg(p, "gui.label.min_samples"), "value", newValue));
        menuGUI.openGeneralSettingsMenu(p);
    }

    private int skillMinCps(String s) { return s.equals("low") ? config.getLowSkillMinCPS() : s.equals("medium") ? config.getMediumSkillMinCPS() : config.getHighSkillMinCPS(); }
    private int skillMaxCps(String s) { return s.equals("low") ? config.getLowSkillMaxCPS() : s.equals("medium") ? config.getMediumSkillMaxCPS() : config.getHighSkillMaxCPS(); }
    private double skillMinRot(String s) { return s.equals("low") ? config.getLowSkillMinRotation() : s.equals("medium") ? config.getMediumSkillMinRotation() : config.getHighSkillMinRotation(); }
    private double skillMaxRot(String s) { return s.equals("low") ? config.getLowSkillMaxRotation() : s.equals("medium") ? config.getMediumSkillMaxRotation() : config.getHighSkillMaxRotation(); }
    private double skillMinAcc(String s) { return s.equals("low") ? config.getLowSkillMinAccuracy() : s.equals("medium") ? config.getMediumSkillMinAccuracy() : config.getHighSkillMinAccuracy(); }
    private double skillMaxAcc(String s) { return s.equals("low") ? config.getLowSkillMaxAccuracy() : s.equals("medium") ? config.getMediumSkillMaxAccuracy() : config.getHighSkillMaxAccuracy(); }

    private void handleMinCps(Player p, ACMenuHolder h, InventoryClickEvent e) {
        String s = h.param(); if (s == null) return;
        int current = skillMinCps(s);
        int max = skillMaxCps(s);
        int newValue = Math.max(1, Math.min(30, current + (e.isLeftClick() ? -1 : 1)));
        if (newValue >= max) { p.sendMessage(msg(p, "gui.feedback.err_min_cps")); return; }
        plugin.getConfig().set("skill_profiles." + s + ".min_cps", newValue);
        plugin.saveConfig(); config.reload();
        p.sendMessage(msg(p, "gui.feedback.value_set", "label", msg(p, "gui.label.min_cps"), "value", newValue));
        menuGUI.openSkillConfigMenu(p, s);
    }

    private void handleMaxCps(Player p, ACMenuHolder h, InventoryClickEvent e) {
        String s = h.param(); if (s == null) return;
        int current = skillMaxCps(s);
        int min = skillMinCps(s);
        int newValue = Math.max(1, Math.min(30, current + (e.isLeftClick() ? -1 : 1)));
        if (newValue <= min) { p.sendMessage(msg(p, "gui.feedback.err_max_cps")); return; }
        plugin.getConfig().set("skill_profiles." + s + ".max_cps", newValue);
        plugin.saveConfig(); config.reload();
        p.sendMessage(msg(p, "gui.feedback.value_set", "label", msg(p, "gui.label.max_cps"), "value", newValue));
        menuGUI.openSkillConfigMenu(p, s);
    }

    private void handleMinRot(Player p, ACMenuHolder h, InventoryClickEvent e) {
        String s = h.param(); if (s == null) return;
        double current = skillMinRot(s);
        double max = skillMaxRot(s);
        double newValue = Math.max(0, Math.min(1000, current + (e.isLeftClick() ? -10 : 10)));
        if (newValue >= max) { p.sendMessage(msg(p, "gui.feedback.err_min_rot")); return; }
        plugin.getConfig().set("skill_profiles." + s + ".min_rotation_speed", newValue);
        plugin.saveConfig(); config.reload();
        p.sendMessage(msg(p, "gui.feedback.value_set", "label", msg(p, "gui.label.min_rot"), "value", String.format("%.0f°/s", newValue)));
        menuGUI.openSkillConfigMenu(p, s);
    }

    private void handleMaxRot(Player p, ACMenuHolder h, InventoryClickEvent e) {
        String s = h.param(); if (s == null) return;
        double current = skillMaxRot(s);
        double min = skillMinRot(s);
        double newValue = Math.max(0, Math.min(1000, current + (e.isLeftClick() ? -10 : 10)));
        if (newValue <= min) { p.sendMessage(msg(p, "gui.feedback.err_max_rot")); return; }
        plugin.getConfig().set("skill_profiles." + s + ".max_rotation_speed", newValue);
        plugin.saveConfig(); config.reload();
        p.sendMessage(msg(p, "gui.feedback.value_set", "label", msg(p, "gui.label.max_rot"), "value", String.format("%.0f°/s", newValue)));
        menuGUI.openSkillConfigMenu(p, s);
    }

    private void handleMinAcc(Player p, ACMenuHolder h, InventoryClickEvent e) {
        String s = h.param(); if (s == null) return;
        double current = skillMinAcc(s);
        double max = skillMaxAcc(s);
        double newValue = Math.max(0.0, Math.min(1.0, current + (e.isLeftClick() ? -0.05 : 0.05)));
        if (newValue >= max) { p.sendMessage(msg(p, "gui.feedback.err_min_acc")); return; }
        plugin.getConfig().set("skill_profiles." + s + ".min_accuracy", Math.round(newValue * 100.0) / 100.0);
        plugin.saveConfig(); config.reload();
        p.sendMessage(msg(p, "gui.feedback.value_set", "label", msg(p, "gui.label.min_acc"), "value", String.format("%.0f%%", newValue * 100)));
        menuGUI.openSkillConfigMenu(p, s);
    }

    private void handleMaxAcc(Player p, ACMenuHolder h, InventoryClickEvent e) {
        String s = h.param(); if (s == null) return;
        double current = skillMaxAcc(s);
        double min = skillMinAcc(s);
        double newValue = Math.max(0.0, Math.min(1.0, current + (e.isLeftClick() ? -0.05 : 0.05)));
        if (newValue <= min) { p.sendMessage(msg(p, "gui.feedback.err_max_acc")); return; }
        plugin.getConfig().set("skill_profiles." + s + ".max_accuracy", Math.round(newValue * 100.0) / 100.0);
        plugin.saveConfig(); config.reload();
        p.sendMessage(msg(p, "gui.feedback.value_set", "label", msg(p, "gui.label.max_acc"), "value", String.format("%.0f%%", newValue * 100)));
        menuGUI.openSkillConfigMenu(p, s);
    }

    private void handleKaMaxCps(Player p, InventoryClickEvent e) {
        double current = config.getKillAuraMaxCPS();
        double newValue = Math.max(10, Math.min(30, current + (e.isLeftClick() ? -1 : 1)));
        plugin.getConfig().set("checks.killaura.max_cps", newValue);
        plugin.saveConfig(); config.reload();
        p.sendMessage(msg(p, "gui.feedback.value_set", "label", msg(p, "gui.label.max_cps"), "value", newValue));
        menuGUI.openKillAuraSettingsMenu(p);
    }

    private void handleKaCpsTrust(Player p, InventoryClickEvent e) {
        double current = config.getKillAuraCPSTrustedBonus();
        double newValue = Math.max(0, Math.min(10, current + (e.isLeftClick() ? -0.5 : 0.5)));
        plugin.getConfig().set("checks.killaura.cps_trusted_bonus", Math.round(newValue * 10.0) / 10.0);
        plugin.saveConfig(); config.reload();
        p.sendMessage(msg(p, "gui.feedback.value_set", "label", msg(p, "gui.label.cps_trust"), "value", "+" + newValue));
        menuGUI.openKillAuraSettingsMenu(p);
    }

    private void handleKaMaxAngle(Player p, InventoryClickEvent e) {
        double current = config.getKillAuraMaxAngle();
        double newValue = Math.max(20, Math.min(90, current + (e.isLeftClick() ? -2 : 2)));
        plugin.getConfig().set("checks.killaura.max_angle", newValue);
        plugin.saveConfig(); config.reload();
        p.sendMessage(msg(p, "gui.feedback.value_set", "label", msg(p, "gui.label.max_angle"), "value", newValue + "°"));
        menuGUI.openKillAuraSettingsMenu(p);
    }

    private void handleKaAngleTrust(Player p, InventoryClickEvent e) {
        double current = config.getKillAuraAngleTrustedBonus();
        double newValue = Math.max(0, Math.min(30, current + (e.isLeftClick() ? -2 : 2)));
        plugin.getConfig().set("checks.killaura.angle_trusted_bonus", newValue);
        plugin.saveConfig(); config.reload();
        p.sendMessage(msg(p, "gui.feedback.value_set", "label", msg(p, "gui.label.angle_trust"), "value", "+" + newValue + "°"));
        menuGUI.openKillAuraSettingsMenu(p);
    }

    private void handleKaMaxRot(Player p, InventoryClickEvent e) {
        double current = config.getKillAuraMaxRotationSpeed();
        double newValue = Math.max(300, Math.min(1200, current + (e.isLeftClick() ? -20 : 20)));
        plugin.getConfig().set("checks.killaura.max_rotation_speed", newValue);
        plugin.saveConfig(); config.reload();
        p.sendMessage(msg(p, "gui.feedback.value_set", "label", msg(p, "gui.label.max_rot"), "value", newValue + "°/s"));
        menuGUI.openKillAuraSettingsMenu(p);
    }

    private void handleKaReqVl(Player p, InventoryClickEvent e) {
        int current = config.getKillAuraRequiredViolations();
        int newValue = Math.max(1, Math.min(5, current + (e.isLeftClick() ? -1 : 1)));
        plugin.getConfig().set("checks.killaura.required_violations", newValue);
        plugin.saveConfig(); config.reload();
        p.sendMessage(msg(p, "gui.feedback.value_set", "label", msg(p, "gui.label.req_vl"), "value", newValue));
        menuGUI.openKillAuraSettingsMenu(p);
    }

    private void handleKaPktVar(Player p, InventoryClickEvent e) {
        double current = config.getKillAuraPacketVarianceThreshold();
        double newValue = Math.max(10, Math.min(100, current + (e.isLeftClick() ? -5 : 5)));
        plugin.getConfig().set("checks.killaura.packet_variance_threshold", newValue);
        plugin.saveConfig(); config.reload();
        p.sendMessage(msg(p, "gui.feedback.value_set", "label", msg(p, "gui.label.pkt_var"), "value", newValue));
        menuGUI.openKillAuraSettingsMenu(p);
    }

    private void handleKaPktRate(Player p, InventoryClickEvent e) {
        int current = config.getKillAuraPacketAttackRateLimit();
        int newValue = Math.max(15, Math.min(40, current + (e.isLeftClick() ? -2 : 2)));
        plugin.getConfig().set("checks.killaura.packet_attack_rate_limit", newValue);
        plugin.saveConfig(); config.reload();
        p.sendMessage(msg(p, "gui.feedback.value_set", "label", msg(p, "gui.label.pkt_rate"), "value", newValue + "/s"));
        menuGUI.openKillAuraSettingsMenu(p);
    }

    private void handleFbBaseTol(Player p, InventoryClickEvent e) {
        int current = config.getFastBreakBaseToleranceMs();
        int newValue = Math.max(20, Math.min(150, current + (e.isLeftClick() ? -5 : 5)));
        plugin.getConfig().set("checks.fastbreak.base_tolerance_ms", newValue);
        plugin.saveConfig(); config.reload();
        p.sendMessage(msg(p, "gui.feedback.value_set", "label", msg(p, "gui.label.base_tol"), "value", newValue + "ms"));
        menuGUI.openFastBreakSettingsMenu(p);
    }

    private void handleFbPctTol(Player p, InventoryClickEvent e) {
        double current = config.getFastBreakPercentageTolerance();
        double newValue = Math.max(0.05, Math.min(0.50, current + (e.isLeftClick() ? -0.02 : 0.02)));
        plugin.getConfig().set("checks.fastbreak.percentage_tolerance", Math.round(newValue * 100.0) / 100.0);
        plugin.saveConfig(); config.reload();
        p.sendMessage(msg(p, "gui.feedback.value_set", "label", msg(p, "gui.label.pct_tol"), "value", String.format("%.0f%%", newValue * 100)));
        menuGUI.openFastBreakSettingsMenu(p);
    }

    private void handleFbShovelTol(Player p, InventoryClickEvent e) {
        int current = config.getFastBreakShovelInstantToleranceMs();
        int newValue = Math.max(20, Math.min(80, current + (e.isLeftClick() ? -5 : 5)));
        plugin.getConfig().set("checks.fastbreak.shovel_instant_tolerance_ms", newValue);
        plugin.saveConfig(); config.reload();
        p.sendMessage(msg(p, "gui.feedback.value_set", "label", msg(p, "gui.label.shovel_tol"), "value", newValue + "ms"));
        menuGUI.openFastBreakSettingsMenu(p);
    }

    private void handleFbShovelPct(Player p, InventoryClickEvent e) {
        double current = config.getFastBreakShovelInstantPercentage();
        double newValue = Math.max(0.10, Math.min(0.50, current + (e.isLeftClick() ? -0.03 : 0.03)));
        plugin.getConfig().set("checks.fastbreak.shovel_instant_percentage", Math.round(newValue * 100.0) / 100.0);
        plugin.saveConfig(); config.reload();
        p.sendMessage(msg(p, "gui.feedback.value_set", "label", msg(p, "gui.label.shovel_pct"), "value", String.format("%.0f%%", newValue * 100)));
        menuGUI.openFastBreakSettingsMenu(p);
    }

    private void handleFbHandTol(Player p, InventoryClickEvent e) {
        int current = config.getFastBreakHandInstantToleranceMs();
        int newValue = Math.max(15, Math.min(60, current + (e.isLeftClick() ? -3 : 3)));
        plugin.getConfig().set("checks.fastbreak.hand_instant_tolerance_ms", newValue);
        plugin.saveConfig(); config.reload();
        p.sendMessage(msg(p, "gui.feedback.value_set", "label", msg(p, "gui.label.hand_tol"), "value", newValue + "ms"));
        menuGUI.openFastBreakSettingsMenu(p);
    }

    private void handleFbTrustInst(Player p, InventoryClickEvent e) {
        int current = config.getFastBreakTrustBonusInstantMs();
        int newValue = Math.max(0, Math.min(50, current + (e.isLeftClick() ? -3 : 3)));
        plugin.getConfig().set("checks.fastbreak.trust_bonus_instant_ms", newValue);
        plugin.saveConfig(); config.reload();
        p.sendMessage(msg(p, "gui.feedback.value_set", "label", msg(p, "gui.label.trust_inst"), "value", "+" + newValue + "ms"));
        menuGUI.openFastBreakSettingsMenu(p);
    }

    private void handleFbTrustNorm(Player p, InventoryClickEvent e) {
        int current = config.getFastBreakTrustBonusNormalMs();
        int newValue = Math.max(0, Math.min(100, current + (e.isLeftClick() ? -5 : 5)));
        plugin.getConfig().set("checks.fastbreak.trust_bonus_normal_ms", newValue);
        plugin.saveConfig(); config.reload();
        p.sendMessage(msg(p, "gui.feedback.value_set", "label", msg(p, "gui.label.trust_norm"), "value", "+" + newValue + "ms"));
        menuGUI.openFastBreakSettingsMenu(p);
    }
}
