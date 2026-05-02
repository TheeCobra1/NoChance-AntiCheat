package NC.noChance.utils;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.Scanner;
import java.util.function.Consumer;
import java.util.logging.Level;

public class UpdateChecker {
    private final Plugin plugin;
    private final int resourceId;
    private volatile String latestVersion;
    private volatile boolean updateAvailable = false;
    private volatile boolean checkComplete = false;

    public UpdateChecker(Plugin plugin, int resourceId) {
        this.plugin = plugin;
        this.resourceId = resourceId;
    }

    public void checkForUpdates(Consumer<String> consumer) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try (InputStream inputStream = new URL("https://api.spigotmc.org/legacy/update.php?resource=" + resourceId)
                    .openStream(); Scanner scanner = new Scanner(inputStream)) {
                if (scanner.hasNext()) {
                    consumer.accept(scanner.next());
                }
            } catch (IOException e) {
                plugin.getLogger().warning("Failed to check for updates: " + e.getMessage());
                this.checkComplete = true;
            }
        });
    }

    public void performCheck() {
        checkForUpdates(version -> {
            String currentVersion = plugin.getDescription().getVersion();
            this.latestVersion = version;

            int comparison = compareVersions(currentVersion, version);

            if (comparison < 0) {
                this.updateAvailable = true;
                plugin.getLogger().info(" ");
                plugin.getLogger().info("Update available! Current: " + currentVersion + " | Latest: " + version);
                plugin.getLogger().info("Download: https://www.spigotmc.org/resources/129357/");
                plugin.getLogger().info(" ");
            } else {
                this.updateAvailable = false;
                plugin.getLogger().info("You are running the latest version!");
            }

            this.checkComplete = true;
        });
    }

    private int compareVersions(String current, String latest) {
        try {
            String[] currentParts = current.split("\\.");
            String[] latestParts = latest.split("\\.");

            int maxLength = Math.max(currentParts.length, latestParts.length);

            for (int i = 0; i < maxLength; i++) {
                int currentPart = i < currentParts.length ? parseVersionPart(currentParts[i]) : 0;
                int latestPart = i < latestParts.length ? parseVersionPart(latestParts[i]) : 0;

                if (currentPart < latestPart) {
                    return -1;
                } else if (currentPart > latestPart) {
                    return 1;
                }
            }
            return 0;
        } catch (NumberFormatException e) {
            plugin.getLogger().log(Level.WARNING, "Failed to compare version numbers: " + current + " vs " + latest, e);
            return current.compareTo(latest);
        }
    }

    private int parseVersionPart(String part) {
        try {
            return Integer.parseInt(part.replaceAll("[^0-9]", ""));
        } catch (NumberFormatException e) {
            plugin.getLogger().log(Level.WARNING, "Failed to parse version part: " + part, e);
            return 0;
        }
    }

    public boolean isUpdateAvailable() {
        return updateAvailable && checkComplete;
    }

    public String getLatestVersion() {
        return latestVersion != null ? latestVersion : "Unknown";
    }

    public String getCurrentVersion() {
        return plugin.getDescription().getVersion();
    }

    public boolean isCheckComplete() {
        return checkComplete;
    }
}
