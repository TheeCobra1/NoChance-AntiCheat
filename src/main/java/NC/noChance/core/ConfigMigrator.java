package NC.noChance.core;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.logging.Level;

public class ConfigMigrator {
    private final Plugin plugin;

    public ConfigMigrator(Plugin plugin) {
        this.plugin = plugin;
    }

    public void sync() {
        plugin.saveDefaultConfig();
        FileConfiguration userConfig = plugin.getConfig();

        if (!userConfig.getBoolean("auto_update_config", true)) {
            return;
        }

        InputStream defaultStream = plugin.getResource("config.yml");
        if (defaultStream == null) return;

        YamlConfiguration defaults;
        try (InputStreamReader reader = new InputStreamReader(defaultStream)) {
            defaults = YamlConfiguration.loadConfiguration(reader);
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Failed to load default config for migration", e);
            return;
        }

        int added = 0;
        for (String key : defaults.getKeys(true)) {
            if (defaults.isConfigurationSection(key)) continue;
            if (!userConfig.contains(key)) {
                userConfig.set(key, defaults.get(key));
                added++;
            }
        }

        if (added > 0) {
            plugin.saveConfig();
            plugin.getLogger().info("Config updated: " + added + " new setting" + (added > 1 ? "s" : "") + " added with defaults.");
        }
    }
}
