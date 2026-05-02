package NC.noChance.core;

import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.logging.Logger;

public class ConfigValidator {

    public enum Type { BOOL, INT, DOUBLE, STRING, LIST }

    public record Rule(String key, Type type, Double min, Double max, List<String> allowed, boolean fatal) {
        public static Rule bool(String key) { return new Rule(key, Type.BOOL, null, null, null, false); }
        public static Rule fatalBool(String key) { return new Rule(key, Type.BOOL, null, null, null, true); }
        public static Rule intRange(String key, double min, double max) { return new Rule(key, Type.INT, min, max, null, false); }
        public static Rule fatalIntRange(String key, double min, double max) { return new Rule(key, Type.INT, min, max, null, true); }
        public static Rule dblRange(String key, double min, double max) { return new Rule(key, Type.DOUBLE, min, max, null, false); }
        public static Rule fatalDblRange(String key, double min, double max) { return new Rule(key, Type.DOUBLE, min, max, null, true); }
        public static Rule str(String key) { return new Rule(key, Type.STRING, null, null, null, false); }
        public static Rule strAllowed(String key, List<String> allowed, boolean fatal) { return new Rule(key, Type.STRING, null, null, allowed, fatal); }
        public static Rule list(String key) { return new Rule(key, Type.LIST, null, null, null, false); }
    }

    private static final List<String> CHECK_NAMES = Arrays.asList(
            "fly", "speed", "noclip", "jesus", "fastbreak", "fastplace", "nuker",
            "killaura", "nofall", "autoclicker", "reach", "inventory", "scaffold",
            "timer", "velocity", "criticals", "phase", "step", "blink", "noslow",
            "groundspoof", "elytrafly", "strider", "boatfly"
    );

    private static final List<String> KNOWN_TOP_LEVEL_KEYS = Arrays.asList(
            "auto_update_config", "language", "force_server_language", "general",
            "thresholds", "advanced_filtering", "actions", "statistical", "checks",
            "database", "discord", "web", "ai", "replay", "bedrock",
            "ping_compensation", "skill_profiles"
    );

    public static class ValidationResult {
        private final List<String> fatalErrors;
        private final List<String> warnings;

        ValidationResult(List<String> fatalErrors, List<String> warnings) {
            this.fatalErrors = fatalErrors;
            this.warnings = warnings;
        }

        public boolean isValid() {
            return fatalErrors.isEmpty();
        }

        public boolean isFatal() {
            return !fatalErrors.isEmpty();
        }

        public List<String> getErrors() {
            return fatalErrors;
        }

        public List<String> getWarnings() {
            return warnings;
        }
    }

    private final FileConfiguration config;
    private final Logger logger;
    private final List<Rule> rules;

    public ConfigValidator(FileConfiguration config, Logger logger) {
        this.config = config;
        this.logger = logger;
        this.rules = buildRules();
    }

    public ValidationResult validate() {
        List<String> fatals = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        validateTopLevelUnknownKeys(warnings);

        for (Rule r : rules) {
            applyRule(r, fatals, warnings);
        }

        validateCrossKey(fatals, warnings);
        validateMysqlConditionals(fatals, warnings);

        for (String w : warnings) {
            logger.warning("[ConfigValidator] " + w);
        }
        for (String e : fatals) {
            logger.severe("[ConfigValidator] " + e);
        }

        return new ValidationResult(fatals, warnings);
    }

    public List<Rule> getRules() {
        return rules;
    }

    private void applyRule(Rule r, List<String> fatals, List<String> warnings) {
        if (!config.contains(r.key())) return;
        Object raw = config.get(r.key());
        String actual = String.valueOf(raw);

        switch (r.type()) {
            case BOOL -> {
                if (!(raw instanceof Boolean)) {
                    record(r, fatals, warnings, "boolean (true/false)", actual);
                }
            }
            case INT -> {
                if (!(raw instanceof Number)) {
                    record(r, fatals, warnings, "integer" + rangeText(r), actual);
                    return;
                }
                int v = ((Number) raw).intValue();
                if (r.min() != null && v < r.min()) {
                    record(r, fatals, warnings, "integer >= " + r.min().intValue(), actual);
                } else if (r.max() != null && v > r.max()) {
                    record(r, fatals, warnings, "integer <= " + r.max().intValue(), actual);
                }
            }
            case DOUBLE -> {
                if (!(raw instanceof Number)) {
                    record(r, fatals, warnings, "number" + rangeText(r), actual);
                    return;
                }
                double v = ((Number) raw).doubleValue();
                if (r.min() != null && v < r.min()) {
                    record(r, fatals, warnings, "number >= " + r.min(), actual);
                } else if (r.max() != null && v > r.max()) {
                    record(r, fatals, warnings, "number <= " + r.max(), actual);
                }
            }
            case STRING -> {
                if (!(raw instanceof String s)) {
                    record(r, fatals, warnings, "string", actual);
                    return;
                }
                if (r.allowed() != null && !r.allowed().contains(s.toUpperCase()) && !r.allowed().contains(s)) {
                    record(r, fatals, warnings, "one of " + r.allowed(), actual);
                }
            }
            case LIST -> {
                if (!(raw instanceof List)) {
                    record(r, fatals, warnings, "list", actual);
                }
            }
        }
    }

    private String rangeText(Rule r) {
        if (r.min() == null && r.max() == null) return "";
        if (r.min() != null && r.max() != null) return " in [" + r.min() + ", " + r.max() + "]";
        if (r.min() != null) return " >= " + r.min();
        return " <= " + r.max();
    }

    private void record(Rule r, List<String> fatals, List<String> warnings, String expected, String actual) {
        String msg = "'" + r.key() + "' expected " + expected + ", got: " + actual;
        if (r.fatal()) {
            fatals.add(msg);
        } else {
            warnings.add(msg);
        }
    }

    private void validateTopLevelUnknownKeys(List<String> warnings) {
        for (String key : config.getKeys(false)) {
            if (!KNOWN_TOP_LEVEL_KEYS.contains(key)) {
                warnings.add("Unknown top-level key '" + key + "' ignored");
            }
        }
    }

    private void validateCrossKey(List<String> fatals, List<String> warnings) {
        if (config.contains("thresholds.low_confidence") && config.contains("thresholds.medium_confidence")) {
            double low = config.getDouble("thresholds.low_confidence");
            double med = config.getDouble("thresholds.medium_confidence");
            if (med <= low) warnings.add("'thresholds.medium_confidence' (" + med + ") should be > 'thresholds.low_confidence' (" + low + ")");
        }
        if (config.contains("thresholds.medium_confidence") && config.contains("thresholds.high_confidence")) {
            double med = config.getDouble("thresholds.medium_confidence");
            double high = config.getDouble("thresholds.high_confidence");
            if (high <= med) warnings.add("'thresholds.high_confidence' (" + high + ") should be > 'thresholds.medium_confidence' (" + med + ")");
        }
        if (config.contains("thresholds.high_confidence") && config.contains("thresholds.extreme_confidence")) {
            double high = config.getDouble("thresholds.high_confidence");
            double extreme = config.getDouble("thresholds.extreme_confidence");
            if (extreme <= high) warnings.add("'thresholds.extreme_confidence' (" + extreme + ") should be > 'thresholds.high_confidence' (" + high + ")");
        }

        if (config.contains("checks.reach.max_entity_reach") && config.contains("checks.reach.max_block_reach")) {
            double entity = config.getDouble("checks.reach.max_entity_reach");
            double block = config.getDouble("checks.reach.max_block_reach");
            if (block < entity) warnings.add("'checks.reach.max_block_reach' (" + block + ") is less than 'max_entity_reach' (" + entity + ")");
        }

        if (config.contains("replay.before_seconds") && config.contains("replay.buffer_seconds")) {
            int before = config.getInt("replay.before_seconds");
            int buffer = config.getInt("replay.buffer_seconds");
            if (before > buffer) warnings.add("'replay.before_seconds' (" + before + ") exceeds 'replay.buffer_seconds' (" + buffer + ")");
        }

        for (String tier : Arrays.asList("low", "medium", "high")) {
            String base = "skill_profiles." + tier;
            if (config.contains(base + ".min_cps") && config.contains(base + ".max_cps")) {
                int mn = config.getInt(base + ".min_cps");
                int mx = config.getInt(base + ".max_cps");
                if (mn >= mx) warnings.add("'" + base + ".min_cps' (" + mn + ") must be < max_cps (" + mx + ")");
            }
            if (config.contains(base + ".min_rotation_speed") && config.contains(base + ".max_rotation_speed")) {
                double mn = config.getDouble(base + ".min_rotation_speed");
                double mx = config.getDouble(base + ".max_rotation_speed");
                if (mn >= mx) warnings.add("'" + base + ".min_rotation_speed' (" + mn + ") must be < max_rotation_speed (" + mx + ")");
            }
            if (config.contains(base + ".min_accuracy") && config.contains(base + ".max_accuracy")) {
                double mn = config.getDouble(base + ".min_accuracy");
                double mx = config.getDouble(base + ".max_accuracy");
                if (mn >= mx) warnings.add("'" + base + ".min_accuracy' (" + mn + ") must be < max_accuracy (" + mx + ")");
            }
        }
    }

    private void validateMysqlConditionals(List<String> fatals, List<String> warnings) {
        if (!config.contains("database.type")) return;
        Object t = config.get("database.type");
        if (!(t instanceof String s)) return;
        if (!s.equalsIgnoreCase("MYSQL")) return;

        if (!config.contains("database.host") || config.getString("database.host", "").isEmpty()) {
            fatals.add("'database.host' must be set when database.type is MYSQL");
        }
        if (!config.contains("database.port")) {
            fatals.add("'database.port' must be set when database.type is MYSQL");
        }
        if (!config.contains("database.database") || config.getString("database.database", "").isEmpty()) {
            warnings.add("'database.database' (schema) is empty for MYSQL");
        }
        if (!config.contains("database.username") || config.getString("database.username", "").isEmpty()) {
            warnings.add("'database.username' is empty for MYSQL");
        }
    }

    private List<Rule> buildRules() {
        List<Rule> r = new ArrayList<>();

        r.add(Rule.bool("auto_update_config"));
        r.add(Rule.str("language"));
        r.add(Rule.bool("force_server_language"));

        r.add(Rule.fatalIntRange("general.time_window_seconds", 1, 86400));
        r.add(Rule.intRange("general.grace_period_seconds", 0, 600));
        r.add(Rule.intRange("general.teleport_grace_period_seconds", 0, 60));
        r.add(Rule.fatalIntRange("general.min_samples", 1, 1000));
        r.add(Rule.bool("general.op_exempt"));
        r.add(Rule.bool("general.op_detect_only"));
        r.add(Rule.bool("general.notify_player_on_flag"));

        r.add(Rule.fatalDblRange("thresholds.low_confidence", 0.0, 1.0));
        r.add(Rule.fatalDblRange("thresholds.medium_confidence", 0.0, 1.0));
        r.add(Rule.fatalDblRange("thresholds.high_confidence", 0.0, 1.0));
        r.add(Rule.fatalDblRange("thresholds.extreme_confidence", 0.0, 1.0));

        r.add(Rule.bool("advanced_filtering.enabled"));
        r.add(Rule.dblRange("advanced_filtering.bayesian_prior", 0.0, 1.0));
        r.add(Rule.dblRange("advanced_filtering.z_score_threshold", 0.01, 10.0));
        r.add(Rule.dblRange("advanced_filtering.min_trust_score", 0.0, 1.0));
        r.add(Rule.intRange("advanced_filtering.established_baseline_checks", 1, 10000));
        r.add(Rule.intRange("advanced_filtering.established_baseline_time_seconds", 1, 86400));

        r.add(Rule.bool("actions.kick_on_extreme_confidence"));
        r.add(Rule.bool("actions.kick_on_high_confidence"));
        r.add(Rule.bool("actions.warn_on_high_confidence"));
        r.add(Rule.bool("actions.warn_on_medium_confidence"));
        r.add(Rule.bool("actions.log_low_confidence"));
        r.add(Rule.bool("actions.setback.enabled"));
        r.add(Rule.list("actions.setback.types"));
        r.add(Rule.bool("actions.custom_commands.enabled"));
        r.add(Rule.list("actions.custom_commands.on_low"));
        r.add(Rule.list("actions.custom_commands.on_medium"));
        r.add(Rule.list("actions.custom_commands.on_high"));
        r.add(Rule.list("actions.custom_commands.on_extreme"));

        r.add(Rule.dblRange("statistical.std_dev_multiplier", 0.01, 100.0));
        r.add(Rule.intRange("statistical.outlier_forgiveness", 0, 1000));
        r.add(Rule.dblRange("statistical.cusum_drift", 0.0, 100.0));
        r.add(Rule.dblRange("statistical.cusum_threshold", 0.01, 100.0));
        r.add(Rule.intRange("statistical.welford_warmup_samples", 1, 10000));

        for (String c : CHECK_NAMES) {
            String b = "checks." + c;
            r.add(Rule.fatalBool(b + ".enabled"));
            r.add(Rule.fatalIntRange(b + ".threshold", 1, 100000));
            r.add(Rule.dblRange(b + ".severity_multiplier", 0.01, 100.0));
        }

        r.add(Rule.dblRange("checks.fly.max_velocity_y", 0.01, 100.0));
        r.add(Rule.bool("checks.fly.horizontal_enabled"));

        r.add(Rule.dblRange("checks.speed.max_speed", 0.01, 100.0));
        r.add(Rule.dblRange("checks.speed.sprint_multiplier", 0.01, 100.0));
        r.add(Rule.dblRange("checks.speed.ice_multiplier", 0.01, 100.0));

        r.add(Rule.dblRange("checks.killaura.max_reach", 0.01, 100.0));
        r.add(Rule.dblRange("checks.killaura.max_angle", 0.0, 360.0));
        r.add(Rule.dblRange("checks.killaura.max_rotation_speed", 0.01, 100000.0));
        r.add(Rule.fatalDblRange("checks.killaura.max_cps", 0.01, 1000.0));
        r.add(Rule.dblRange("checks.killaura.cps_trusted_bonus", 0.0, 100.0));
        r.add(Rule.dblRange("checks.killaura.cps_untrusted_penalty", 0.0, 100.0));
        r.add(Rule.dblRange("checks.killaura.instant_flag_cps_over", 0.0, 1000.0));
        r.add(Rule.dblRange("checks.killaura.angle_trusted_bonus", 0.0, 360.0));
        r.add(Rule.dblRange("checks.killaura.angle_untrusted_penalty", 0.0, 360.0));
        r.add(Rule.dblRange("checks.killaura.instant_flag_angle_over", 0.0, 360.0));
        r.add(Rule.dblRange("checks.killaura.rotation_trusted_bonus", 0.0, 100000.0));
        r.add(Rule.dblRange("checks.killaura.rotation_untrusted_penalty", 0.0, 100000.0));
        r.add(Rule.dblRange("checks.killaura.instant_flag_rotation_over", 0.0, 100000.0));
        r.add(Rule.dblRange("checks.killaura.rotation_variance_threshold", 0.0, 360.0));
        r.add(Rule.dblRange("checks.killaura.rotation_variance_strict", 0.0, 360.0));
        r.add(Rule.fatalIntRange("checks.killaura.required_violations", 1, 1000));
        r.add(Rule.dblRange("checks.killaura.packet_variance_threshold", 0.0, 1000.0));
        r.add(Rule.dblRange("checks.killaura.packet_variance_strict", 0.0, 1000.0));
        r.add(Rule.intRange("checks.killaura.packet_attack_rate_limit", 1, 1000));

        r.add(Rule.fatalIntRange("checks.autoclicker.max_cps", 1, 1000));

        r.add(Rule.dblRange("checks.reach.max_entity_reach", 0.01, 100.0));
        r.add(Rule.dblRange("checks.reach.max_block_reach", 0.01, 100.0));

        r.add(Rule.fatalIntRange("checks.inventory.max_clicks_per_second", 1, 1000));

        r.add(Rule.intRange("checks.fastplace.max_blocks_per_tick", 1, 1000));
        r.add(Rule.intRange("checks.fastplace.min_interval_ms", 1, 100000));
        r.add(Rule.intRange("checks.fastplace.max_blocks_per_second", 1, 1000));
        r.add(Rule.dblRange("checks.fastplace.max_scaffold_angle", 0.0, 360.0));
        r.add(Rule.dblRange("checks.fastplace.max_scaffold_distance", 0.01, 100.0));
        r.add(Rule.intRange("checks.fastplace.scaffold_min_blocks", 1, 1000));

        r.add(Rule.intRange("checks.fastbreak.base_tolerance_ms", 0, 100000));
        r.add(Rule.dblRange("checks.fastbreak.percentage_tolerance", 0.0, 100.0));
        r.add(Rule.intRange("checks.fastbreak.shovel_instant_tolerance_ms", 0, 100000));
        r.add(Rule.dblRange("checks.fastbreak.shovel_instant_percentage", 0.0, 100.0));
        r.add(Rule.intRange("checks.fastbreak.hand_instant_tolerance_ms", 0, 100000));
        r.add(Rule.dblRange("checks.fastbreak.hand_instant_percentage", 0.0, 100.0));
        r.add(Rule.intRange("checks.fastbreak.trust_bonus_instant_ms", 0, 100000));
        r.add(Rule.intRange("checks.fastbreak.trust_bonus_normal_ms", 0, 100000));

        r.add(Rule.dblRange("checks.elytrafly.max_speed", 0.01, 1000.0));
        r.add(Rule.dblRange("checks.elytrafly.min_glide_descent", -100.0, 100.0));
        r.add(Rule.intRange("checks.elytrafly.firework_boost_duration_ms", 1, 600000));

        r.add(Rule.intRange("checks.boatfly.max_air_ticks", 1, 100000));

        r.add(Rule.strAllowed("database.type", Arrays.asList("SQLITE", "MYSQL"), true));
        r.add(Rule.str("database.host"));
        r.add(Rule.intRange("database.port", 1, 65535));
        r.add(Rule.str("database.database"));
        r.add(Rule.str("database.username"));
        r.add(Rule.str("database.password"));
        r.add(Rule.str("database.table_prefix"));

        r.add(Rule.bool("discord.enabled"));
        r.add(Rule.str("discord.webhook_url"));

        r.add(Rule.bool("web.enabled"));
        r.add(Rule.str("web.endpoint"));
        r.add(Rule.bool("web.telemetry"));
        r.add(Rule.bool("web.share_learnings"));

        r.add(Rule.bool("ai.enabled"));
        r.add(Rule.dblRange("ai.max_adjustment", 0.0, 1.0));
        r.add(Rule.dblRange("ai.min_agreement", 0.0, 1.0));
        r.add(Rule.dblRange("ai.verdict_ttl_seconds", 0.01, 86400.0));
        r.add(Rule.bool("ai.send_features"));

        r.add(Rule.bool("replay.enabled"));
        r.add(Rule.intRange("replay.buffer_seconds", 1, 3600));
        r.add(Rule.intRange("replay.before_seconds", 0, 3600));
        r.add(Rule.intRange("replay.after_seconds", 0, 3600));
        r.add(Rule.intRange("replay.retention_days", 1, 3650));
        r.add(Rule.bool("replay.save_on_high"));
        r.add(Rule.bool("replay.save_on_extreme"));

        r.add(Rule.bool("bedrock.exempt"));
        r.add(Rule.bool("bedrock.relaxed_checks"));
        r.add(Rule.dblRange("bedrock.tolerance_multiplier", 0.01, 100.0));

        r.add(Rule.bool("ping_compensation.enabled"));
        r.add(Rule.intRange("ping_compensation.high_ping_threshold", 0, 100000));
        r.add(Rule.dblRange("ping_compensation.max_multiplier", 1.0, 100.0));

        for (String tier : Arrays.asList("low", "medium", "high")) {
            String b = "skill_profiles." + tier;
            r.add(Rule.intRange(b + ".min_cps", 0, 1000));
            r.add(Rule.intRange(b + ".max_cps", 1, 1000));
            r.add(Rule.dblRange(b + ".min_rotation_speed", 0.0, 100000.0));
            r.add(Rule.dblRange(b + ".max_rotation_speed", 0.01, 100000.0));
            r.add(Rule.dblRange(b + ".min_accuracy", 0.0, 1.0));
            r.add(Rule.dblRange(b + ".max_accuracy", 0.0, 1.0));
        }

        return r;
    }
}
