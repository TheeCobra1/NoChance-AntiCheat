package NC.noChance.core;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.logging.Level;

public class ACConfig {
    private FileConfiguration config;
    private final Plugin plugin;

    public ACConfig(Plugin plugin) {
        this.plugin = plugin;
        plugin.saveDefaultConfig();
        this.config = plugin.getConfig();
        if (config.getBoolean("auto_update_config", true)) {
            mergeMissingDefaults();
        }
        validateConfig();
    }

    private void mergeMissingDefaults() {
        InputStream defStream = plugin.getResource("config.yml");
        if (defStream == null) return;

        YamlConfiguration defaults = YamlConfiguration.loadConfiguration(
                new InputStreamReader(defStream, StandardCharsets.UTF_8));

        int added = 0;
        for (String key : defaults.getKeys(true)) {
            if (defaults.isConfigurationSection(key)) continue;
            if (!config.contains(key, true)) {
                config.set(key, defaults.get(key));
                try {
                    List<String> comments = defaults.getComments(key);
                    if (!comments.isEmpty()) {
                        config.setComments(key, comments);
                    }
                } catch (NoSuchMethodError e) {
                    plugin.getLogger().log(Level.WARNING, "setComments not supported on this Bukkit version; config comments will not be preserved", e);
                }
                added++;
            }
        }

        if (added > 0) {
            plugin.saveConfig();
            plugin.getLogger().info("Config auto-update: added " + added + " new key(s) from plugin defaults.");
        }
    }

    public boolean isCheckEnabled(String checkName) {
        return config.getBoolean("checks." + checkName + ".enabled", true);
    }

    public int getViolationThreshold(String checkName) {
        return config.getInt("checks." + checkName + ".threshold", 10);
    }

    public double getSeverityMultiplier(String checkName) {
        return config.getDouble("checks." + checkName + ".severity_multiplier", 1.0);
    }

    public int getTimeWindow() {
        return config.getInt("general.time_window_seconds", 45);
    }

    public int getGracePeriod() {
        return config.getInt("general.grace_period_seconds", 5);
    }

    public int getTeleportGracePeriod() {
        return config.getInt("general.teleport_grace_period_seconds", 3);
    }

    public int getMinSamples() {
        return config.getInt("general.min_samples", 4);
    }

    public double getLowConfidenceThreshold() {
        return config.getDouble("thresholds.low_confidence", 0.55);
    }

    public double getMediumConfidenceThreshold() {
        return config.getDouble("thresholds.medium_confidence", 0.70);
    }

    public double getHighConfidenceThreshold() {
        return config.getDouble("thresholds.high_confidence", 0.85);
    }

    public double getExtremeConfidenceThreshold() {
        return config.getDouble("thresholds.extreme_confidence", 0.98);
    }

    public double getCusumThreshold() {
        return config.getDouble("statistical.cusum_threshold", 0.6);
    }

    public double getCusumDrift() {
        return config.getDouble("statistical.cusum_drift", 0.05);
    }

    public int getWelfordWarmupSamples() {
        return config.getInt("statistical.welford_warmup_samples", 5);
    }

    public double getMaxSpeed() {
        return config.getDouble("checks.speed.max_speed", 0.32);
    }

    public double getSprintMultiplier() {
        return config.getDouble("checks.speed.sprint_multiplier", 1.30);
    }

    public double getSpeedPotionMultiplier(int level) {
        return 1.0 + (0.2 * level);
    }

    public double getIceMultiplier() {
        return config.getDouble("checks.speed.ice_multiplier", 2.4);
    }

    public double getMaxFlyVelocityY() {
        return config.getDouble("checks.fly.max_velocity_y", 0.55);
    }

    public double getMaxElytraSpeed() {
        return config.getDouble("checks.elytrafly.max_speed", 3.1);
    }

    public long getElytraBoostGraceMs() {
        return config.getLong("checks.elytrafly.firework_boost_duration_ms", 5000L);
    }

    public double getKillAuraMaxReach() {
        return config.getDouble("checks.killaura.max_reach", 3.1);
    }

    public double getKillAuraMaxAngle() {
        return config.getDouble("checks.killaura.max_angle", 35.0);
    }

    public double getKillAuraMaxRotationSpeed() {
        return config.getDouble("checks.killaura.max_rotation_speed", 500.0);
    }

    public int getHighSkillMaxCPS() {
        return config.getInt("skill_profiles.high.max_cps", 20);
    }

    public int getMediumSkillMaxCPS() {
        return config.getInt("skill_profiles.medium.max_cps", 12);
    }

    public int getLowSkillMaxCPS() {
        return config.getInt("skill_profiles.low.max_cps", 8);
    }

    public double getStandardDeviationMultiplier() {
        return config.getDouble("statistical.std_dev_multiplier", 2.5);
    }

    public int getOutlierForgiveness() {
        return config.getInt("statistical.outlier_forgiveness", 2);
    }

    public int getLowSkillMinCPS() {
        return config.getInt("skill_profiles.low.min_cps", 4);
    }

    public int getMediumSkillMinCPS() {
        return config.getInt("skill_profiles.medium.min_cps", 8);
    }

    public int getHighSkillMinCPS() {
        return config.getInt("skill_profiles.high.min_cps", 12);
    }

    public double getLowSkillMinRotation() {
        return config.getDouble("skill_profiles.low.min_rotation_speed", 120);
    }

    public double getMediumSkillMinRotation() {
        return config.getDouble("skill_profiles.medium.min_rotation_speed", 200);
    }

    public double getHighSkillMinRotation() {
        return config.getDouble("skill_profiles.high.min_rotation_speed", 350);
    }

    public double getLowSkillMaxRotation() {
        return config.getDouble("skill_profiles.low.max_rotation_speed", 200);
    }

    public double getMediumSkillMaxRotation() {
        return config.getDouble("skill_profiles.medium.max_rotation_speed", 350);
    }

    public double getHighSkillMaxRotation() {
        return config.getDouble("skill_profiles.high.max_rotation_speed", 600);
    }

    public double getLowSkillMinAccuracy() {
        return config.getDouble("skill_profiles.low.min_accuracy", 0.30);
    }

    public double getMediumSkillMinAccuracy() {
        return config.getDouble("skill_profiles.medium.min_accuracy", 0.50);
    }

    public double getHighSkillMinAccuracy() {
        return config.getDouble("skill_profiles.high.min_accuracy", 0.70);
    }

    public double getLowSkillMaxAccuracy() {
        return config.getDouble("skill_profiles.low.max_accuracy", 0.50);
    }

    public double getMediumSkillMaxAccuracy() {
        return config.getDouble("skill_profiles.medium.max_accuracy", 0.70);
    }

    public double getHighSkillMaxAccuracy() {
        return config.getDouble("skill_profiles.high.max_accuracy", 0.85);
    }

    public int getAutoClickerMaxCPS() {
        return config.getInt("checks.autoclicker.max_cps", 16);
    }

    public double getReachMaxEntityReach() {
        return config.getDouble("checks.reach.max_entity_reach", 3.15);
    }

    public double getReachMaxBlockReach() {
        return config.getDouble("checks.reach.max_block_reach", 4.5);
    }

    public int getInventoryMaxClicksPerSecond() {
        return config.getInt("checks.inventory.max_clicks_per_second", 18);
    }

    public int getFastPlaceMaxBlocksPerTick() {
        return config.getInt("checks.fastplace.max_blocks_per_tick", 1);
    }

    public int getFastPlaceMinIntervalMs() {
        return config.getInt("checks.fastplace.min_interval_ms", 50);
    }

    public int getFastPlaceMaxBlocksPerSecond() {
        return config.getInt("checks.fastplace.max_blocks_per_second", 12);
    }

    public double getFastPlaceMaxScaffoldAngle() {
        return config.getDouble("checks.fastplace.max_scaffold_angle", 35.0);
    }

    public double getFastPlaceMaxScaffoldDistance() {
        return config.getDouble("checks.fastplace.max_scaffold_distance", 5.0);
    }

    public int getFastPlaceScaffoldMinBlocks() {
        return config.getInt("checks.fastplace.scaffold_min_blocks", 3);
    }

    public String getDatabaseType() {
        return config.getString("database.type", "SQLITE");
    }

    public String getDatabaseHost() {
        return config.getString("database.host", "localhost");
    }

    public int getDatabasePort() {
        return config.getInt("database.port", 3306);
    }

    public String getDatabaseName() {
        return config.getString("database.database", "nochance");
    }

    public String getDatabaseUsername() {
        return config.getString("database.username", "root");
    }

    public String getDatabasePassword() {
        return config.getString("database.password", "password");
    }

    public String getDatabaseTablePrefix() {
        return config.getString("database.table_prefix", "nc_");
    }

    public boolean isDiscordEnabled() {
        return config.getBoolean("discord.enabled", false);
    }

    public String getDiscordWebhook() {
        return config.getString("discord.webhook_url", "");
    }

    public boolean isOpExempt() {
        return config.getBoolean("general.op_exempt", true);
    }

    public boolean isOpDetectOnly() {
        return config.getBoolean("general.op_detect_only", false);
    }

    public boolean isHorizontalFlyEnabled() {
        return config.getBoolean("checks.fly.horizontal_enabled", false);
    }

    public boolean isSetbackEnabled() {
        return config.getBoolean("actions.setback.enabled", false);
    }

    public java.util.List<String> getSetbackTypes() {
        return config.getStringList("actions.setback.types");
    }

    public boolean isCustomCommandsEnabled() {
        return config.getBoolean("actions.custom_commands.enabled", false);
    }

    public java.util.List<String> getCustomCommands(String confidenceLevel) {
        String key = "actions.custom_commands.on_" + confidenceLevel.toLowerCase();
        return config.getStringList(key);
    }

    public boolean isExternalPunishEnabled() {
        return config.getBoolean("actions.external.enabled", false);
    }

    public String getExternalCommand(String action) {
        return config.getString("actions.external." + action.toLowerCase(), "");
    }

    public String getExternalMessage(String action) {
        return config.getString("actions.external.messages." + action.toLowerCase(), "");
    }

    public String getLanguage() {
        return config.getString("language", "en");
    }

    public boolean isForceServerLanguage() {
        return config.getBoolean("force_server_language", false);
    }

    public boolean shouldNotifyPlayerOnFlag() {
        return config.getBoolean("general.notify_player_on_flag", false);
    }

    public boolean shouldKickOnExtreme() {
        return config.getBoolean("actions.kick_on_extreme_confidence", true);
    }

    public boolean shouldKickOnHigh() {
        return config.getBoolean("actions.kick_on_high_confidence", true);
    }

    public boolean shouldWarnOnHigh() {
        return config.getBoolean("actions.warn_on_high_confidence", true);
    }

    public boolean shouldWarnOnMedium() {
        return config.getBoolean("actions.warn_on_medium_confidence", true);
    }

    public double getKillAuraMaxCPS() {
        return config.getDouble("checks.killaura.max_cps", 15.0);
    }

    public double getKillAuraCPSTrustedBonus() {
        return config.getDouble("checks.killaura.cps_trusted_bonus", 1.0);
    }

    public double getKillAuraCPSUntrustedPenalty() {
        return config.getDouble("checks.killaura.cps_untrusted_penalty", 3.0);
    }

    public double getKillAuraInstantFlagCPSOver() {
        return config.getDouble("checks.killaura.instant_flag_cps_over", 4.0);
    }

    public double getKillAuraAngleTrustedBonus() {
        return config.getDouble("checks.killaura.angle_trusted_bonus", 5.0);
    }

    public double getKillAuraAngleUntrustedPenalty() {
        return config.getDouble("checks.killaura.angle_untrusted_penalty", 8.0);
    }

    public double getKillAuraInstantFlagAngleOver() {
        return config.getDouble("checks.killaura.instant_flag_angle_over", 12.0);
    }

    public double getKillAuraRotationTrustedBonus() {
        return config.getDouble("checks.killaura.rotation_trusted_bonus", 40.0);
    }

    public double getKillAuraRotationUntrustedPenalty() {
        return config.getDouble("checks.killaura.rotation_untrusted_penalty", 60.0);
    }

    public double getKillAuraInstantFlagRotationOver() {
        return config.getDouble("checks.killaura.instant_flag_rotation_over", 100.0);
    }

    public double getKillAuraRotationVarianceThreshold() {
        return config.getDouble("checks.killaura.rotation_variance_threshold", 30.0);
    }

    public double getKillAuraRotationVarianceStrict() {
        return config.getDouble("checks.killaura.rotation_variance_strict", 15.0);
    }

    public int getKillAuraRequiredViolations() {
        return config.getInt("checks.killaura.required_violations", 3);
    }

    public double getKillAuraPacketVarianceThreshold() {
        return config.getDouble("checks.killaura.packet_variance_threshold", 20.0);
    }

    public double getKillAuraPacketVarianceStrict() {
        return config.getDouble("checks.killaura.packet_variance_strict", 8.0);
    }

    public int getKillAuraPacketAttackRateLimit() {
        return config.getInt("checks.killaura.packet_attack_rate_limit", 18);
    }

    public int getFastBreakBaseToleranceMs() {
        return config.getInt("checks.fastbreak.base_tolerance_ms", 35);
    }

    public double getFastBreakPercentageTolerance() {
        return config.getDouble("checks.fastbreak.percentage_tolerance", 0.08);
    }

    public int getFastBreakShovelInstantToleranceMs() {
        return config.getInt("checks.fastbreak.shovel_instant_tolerance_ms", 30);
    }

    public double getFastBreakShovelInstantPercentage() {
        return config.getDouble("checks.fastbreak.shovel_instant_percentage", 0.15);
    }

    public int getFastBreakHandInstantToleranceMs() {
        return config.getInt("checks.fastbreak.hand_instant_tolerance_ms", 20);
    }

    public double getFastBreakHandInstantPercentage() {
        return config.getDouble("checks.fastbreak.hand_instant_percentage", 0.12);
    }

    public int getFastBreakTrustBonusInstantMs() {
        return config.getInt("checks.fastbreak.trust_bonus_instant_ms", 0);
    }

    public int getFastBreakTrustBonusNormalMs() {
        return config.getInt("checks.fastbreak.trust_bonus_normal_ms", 0);
    }

    public long getDatabaseConnectionTimeout() {
        return 30000;
    }

    public long getDatabaseIdleTimeout() {
        return 600000;
    }

    public long getDatabaseMaxLifetime() {
        return 1800000;
    }

    private void validateConfig() {
        validatePositive("general.time_window_seconds", getTimeWindow());
        validatePositive("general.grace_period_seconds", getGracePeriod());
        validatePositive("general.min_samples", getMinSamples());

        validateRange("thresholds.low_confidence", getLowConfidenceThreshold(), 0.0, 1.0);
        validateRange("thresholds.medium_confidence", getMediumConfidenceThreshold(), 0.0, 1.0);
        validateRange("thresholds.high_confidence", getHighConfidenceThreshold(), 0.0, 1.0);
        validateRange("thresholds.extreme_confidence", getExtremeConfidenceThreshold(), 0.0, 1.0);

        validatePositive("checks.speed.max_speed", getMaxSpeed());
        validatePositive("checks.speed.sprint_multiplier", getSprintMultiplier());
        validatePositive("checks.speed.ice_multiplier", getIceMultiplier());
        validatePositive("checks.fly.max_velocity_y", getMaxFlyVelocityY());

        validatePositive("checks.killaura.max_reach", getKillAuraMaxReach());
        validateRange("checks.killaura.max_angle", getKillAuraMaxAngle(), 0.0, 360.0);
        validatePositive("checks.killaura.max_rotation_speed", getKillAuraMaxRotationSpeed());
        validatePositive("checks.killaura.max_cps", getKillAuraMaxCPS());

        validatePositive("checks.autoclicker.max_cps", getAutoClickerMaxCPS());
        validatePositive("checks.reach.max_entity_reach", getReachMaxEntityReach());
        validatePositive("checks.reach.max_block_reach", getReachMaxBlockReach());
        validatePositive("checks.inventory.max_clicks_per_second", getInventoryMaxClicksPerSecond());
        validatePositive("checks.fastplace.max_blocks_per_tick", getFastPlaceMaxBlocksPerTick());
        validatePositive("checks.fastplace.min_interval_ms", getFastPlaceMinIntervalMs());
        validatePositive("checks.fastplace.max_blocks_per_second", getFastPlaceMaxBlocksPerSecond());
        validatePositive("checks.fastplace.max_scaffold_angle", getFastPlaceMaxScaffoldAngle());
        validatePositive("checks.fastplace.max_scaffold_distance", getFastPlaceMaxScaffoldDistance());
        validatePositive("checks.fastplace.scaffold_min_blocks", getFastPlaceScaffoldMinBlocks());

        validatePositive("statistical.std_dev_multiplier", getStandardDeviationMultiplier());
        validatePositive("statistical.outlier_forgiveness", getOutlierForgiveness());

        validateOrdered("thresholds",
                "low_confidence", getLowConfidenceThreshold(),
                "medium_confidence", getMediumConfidenceThreshold(),
                "high_confidence", getHighConfidenceThreshold(),
                "extreme_confidence", getExtremeConfidenceThreshold());

        validatePositive("checks.autotool.threshold", getAutoToolThreshold());
        validatePositive("checks.automine.threshold", getAutoMineThreshold());
        validatePositive("checks.autofish.threshold", getAutoFishThreshold());
        validatePositive("checks.spider.threshold", getSpiderThreshold());
        validatePositive("checks.xray.min_stone", getXRayMinStone());
        validateRange("checks.xray.ratio_threshold", getXRayRatioThreshold(), 0.0, 1.0);

        String dbType = getDatabaseType().toUpperCase();
        if (!dbType.equals("SQLITE") && !dbType.equals("MYSQL")) {
            plugin.getLogger().warning("Invalid database type '" + dbType + "', must be SQLITE or MYSQL. Using SQLITE as default.");
        }
    }

    private void validatePositive(String path, double value) {
        if (value <= 0) {
            plugin.getLogger().severe("Config value '" + path + "' must be positive, got: " + value);
            throw new IllegalArgumentException("Invalid config: " + path + " must be positive");
        }
    }

    private void validatePositive(String path, int value) {
        if (value <= 0) {
            plugin.getLogger().severe("Config value '" + path + "' must be positive, got: " + value);
            throw new IllegalArgumentException("Invalid config: " + path + " must be positive");
        }
    }

    private void validateRange(String path, double value, double min, double max) {
        if (value < min || value > max) {
            plugin.getLogger().severe("Config value '" + path + "' must be between " + min + " and " + max + ", got: " + value);
            throw new IllegalArgumentException("Invalid config: " + path + " out of range");
        }
    }

    private void validateOrdered(String section, String lowKey, double low, String midKey, double mid, String highKey, double high, String extremeKey, double extreme) {
        if (!(low < mid && mid < high && high < extreme)) {
            plugin.getLogger().warning("Config '" + section + "' thresholds out of order: "
                    + lowKey + "=" + low + ", " + midKey + "=" + mid + ", " + highKey + "=" + high + ", " + extremeKey + "=" + extreme
                    + " — clamping to default ladder.");
            config.set(section + "." + lowKey, 0.55);
            config.set(section + "." + midKey, 0.70);
            config.set(section + "." + highKey, 0.85);
            config.set(section + "." + extremeKey, 0.92);
        }
    }

    public void reload() {
        plugin.reloadConfig();
        this.config = plugin.getConfig();
        if (config.getBoolean("auto_update_config", true)) {
            mergeMissingDefaults();
        }
        validateConfig();
        plugin.getLogger().info("Configuration reloaded successfully!");
    }

    public boolean isReplayEnabled() {
        return config.getBoolean("replay.enabled", true);
    }

    public int getReplayBufferSeconds() {
        return config.getInt("replay.buffer_seconds", 30);
    }

    public int getReplayBeforeSeconds() {
        return config.getInt("replay.before_seconds", 10);
    }

    public int getReplayAfterSeconds() {
        return config.getInt("replay.after_seconds", 15);
    }

    public int getReplayRetentionDays() {
        return config.getInt("replay.retention_days", 7);
    }

    public boolean shouldSaveReplayOnHigh() {
        return config.getBoolean("replay.save_on_high", true);
    }

    public boolean shouldSaveReplayOnExtreme() {
        return config.getBoolean("replay.save_on_extreme", true);
    }

    public boolean isBedrockExempt() {
        return config.getBoolean("bedrock.exempt", true);
    }

    public boolean isBedrockRelaxed() {
        return config.getBoolean("bedrock.relaxed_checks", true);
    }

    public double getBedrockToleranceMultiplier() {
        return Math.min(1.3, config.getDouble("bedrock.tolerance_multiplier", 1.3));
    }

    public boolean isPingCompensationEnabled() {
        return config.getBoolean("ping_compensation.enabled", true);
    }

    public int getPingHighThreshold() {
        return config.getInt("ping_compensation.high_ping_threshold", 120);
    }

    public double getPingMaxMultiplier() {
        return config.getDouble("ping_compensation.max_multiplier", 1.4);
    }

    public boolean isWebEnabled() {
        return config.getBoolean("web.enabled", true);
    }

    public String getWebEndpoint() {
        return config.getString("web.endpoint", "https://nochance-ac.com");
    }

    public boolean isWebTelemetryEnabled() {
        return config.getBoolean("web.telemetry", true);
    }

    public boolean isWebShareLearnings() {
        return config.getBoolean("web.share_learnings", true);
    }

    public boolean isAIEnabled() {
        return config.getBoolean("ai.enabled", true);
    }

    public double getAIMaxAdjustment() {
        return config.getDouble("ai.max_adjustment", 0.40);
    }

    public double getAIMinAgreement() {
        return config.getDouble("ai.min_agreement", 0.60);
    }

    public double getAIVerdictTTL() {
        return config.getDouble("ai.verdict_ttl_seconds", 600);
    }

    public boolean isAISendFeatures() {
        return config.getBoolean("ai.send_features", true);
    }

    public int getAutoToolThreshold() {
        return config.getInt("checks.autotool.threshold", 3);
    }

    public int getAutoMineThreshold() {
        return config.getInt("checks.automine.threshold", 8);
    }

    public int getAutoFishThreshold() {
        return config.getInt("checks.autofish.threshold", 3);
    }

    public int getSpiderThreshold() {
        return config.getInt("checks.spider.threshold", 5);
    }

    public int getXRayMinStone() {
        return config.getInt("checks.xray.min_stone", 200);
    }

    public double getXRayRatioThreshold() {
        return config.getDouble("checks.xray.ratio_threshold", 0.05);
    }
}
