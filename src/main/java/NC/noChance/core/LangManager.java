package NC.noChance.core;

import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;

public class LangManager {
    private static final String[] SUPPORTED = {"en", "es", "pt_BR", "zh_CN", "ru"};
    private static final String FALLBACK = "en";

    private final Plugin plugin;
    private String defaultLang;
    private boolean forceServerLanguage;
    private final Map<String, YamlConfiguration> langs = new HashMap<>();

    public LangManager(Plugin plugin, String defaultLang) {
        this(plugin, defaultLang, false);
    }

    public LangManager(Plugin plugin, String defaultLang, boolean forceServerLanguage) {
        this.plugin = plugin;
        this.defaultLang = normalize(defaultLang);
        this.forceServerLanguage = forceServerLanguage;
        loadAll();
    }

    private void loadAll() {
        File langDir = new File(plugin.getDataFolder(), "lang");
        if (!langDir.exists()) langDir.mkdirs();

        for (String code : SUPPORTED) {
            File file = new File(langDir, code + ".yml");
            if (!file.exists()) {
                try {
                    plugin.saveResource("lang/" + code + ".yml", false);
                } catch (IllegalArgumentException e) {
                    plugin.getLogger().log(Level.WARNING, "Language resource not found in jar: lang/" + code + ".yml", e);
                }
            }
            YamlConfiguration cfg = YamlConfiguration.loadConfiguration(file);
            InputStream jarStream = plugin.getResource("lang/" + code + ".yml");
            if (jarStream != null) {
                YamlConfiguration defaults = YamlConfiguration.loadConfiguration(
                    new InputStreamReader(jarStream, StandardCharsets.UTF_8));
                cfg.setDefaults(defaults);
            }
            langs.put(code, cfg);
        }
    }

    public void reload(String newDefault) {
        reload(newDefault, this.forceServerLanguage);
    }

    public void reload(String newDefault, boolean forceServerLanguage) {
        this.defaultLang = normalize(newDefault);
        this.forceServerLanguage = forceServerLanguage;
        langs.clear();
        loadAll();
    }

    public String get(Player player, String key, Object... placeholders) {
        return format(raw(resolve(player), key), placeholders);
    }

    public String get(CommandSender sender, String key, Object... placeholders) {
        if (sender instanceof Player) return get((Player) sender, key, placeholders);
        return format(raw(defaultLang, key), placeholders);
    }

    public String get(String key, Object... placeholders) {
        return format(raw(defaultLang, key), placeholders);
    }

    private String resolve(Player p) {
        if (forceServerLanguage) return defaultLang;
        String locale = p.getLocale();
        if (locale == null || locale.isEmpty()) return defaultLang;
        String low = locale.toLowerCase();
        if (low.startsWith("pt_br") || low.startsWith("pt-br")) return "pt_BR";
        if (low.startsWith("zh")) return "zh_CN";
        if (low.startsWith("es")) return "es";
        if (low.startsWith("ru")) return "ru";
        if (low.startsWith("en")) return "en";
        return defaultLang;
    }

    private String normalize(String code) {
        if (code == null) return FALLBACK;
        for (String s : SUPPORTED) if (s.equalsIgnoreCase(code)) return s;
        return FALLBACK;
    }

    private String raw(String code, String key) {
        YamlConfiguration cfg = langs.get(code);
        String val = cfg != null ? cfg.getString(key) : null;
        if (val == null && !FALLBACK.equals(code)) {
            YamlConfiguration en = langs.get(FALLBACK);
            if (en != null) val = en.getString(key);
        }
        if (val == null) return "[missing:" + key + "]";
        return ChatColor.translateAlternateColorCodes('&', val);
    }

    private String format(String text, Object[] placeholders) {
        if (placeholders == null || placeholders.length == 0) return text;
        for (int i = 0; i + 1 < placeholders.length; i += 2) {
            String token = "{" + placeholders[i] + "}";
            text = text.replace(token, String.valueOf(placeholders[i + 1]));
        }
        return text;
    }
}
