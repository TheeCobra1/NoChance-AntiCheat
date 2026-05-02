package NC.noChance.ml;

import NC.noChance.database.DatabaseManager;
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
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public class MLDataReviewGUI {
    public static final int PAGE_SIZE = 45;

    private final Plugin plugin;
    private final DatabaseManager database;
    private final NamespacedKey rowIdKey;
    private final NamespacedKey actionKey;
    private final ConcurrentMap<UUID, List<MLDataCollector.Row>> cache = new ConcurrentHashMap<>();

    public MLDataReviewGUI(Plugin plugin, DatabaseManager database) {
        this.plugin = plugin;
        this.database = database;
        this.rowIdKey = new NamespacedKey(plugin, "ml_row_id");
        this.actionKey = new NamespacedKey(plugin, "ml_action");
    }

    public NamespacedKey rowIdKey() { return rowIdKey; }
    public NamespacedKey actionKey() { return actionKey; }

    public void open(Player player, int page) {
        if (database == null || !database.isAvailable()) {
            player.sendMessage("§cDatabase unavailable.");
            return;
        }
        int offset = page * PAGE_SIZE;
        database.fetchUnreviewedML(PAGE_SIZE, offset).thenAccept(rows -> {
            cache.put(player.getUniqueId(), rows);
            Bukkit.getScheduler().runTask(plugin, () -> render(player, page, rows));
        });
    }

    private void render(Player player, int page, List<MLDataCollector.Row> rows) {
        MLReviewHolder holder = new MLReviewHolder(MLReviewHolder.View.LIST, page, -1L);
        Inventory inv = Bukkit.createInventory(holder, 54, "§8Review Flags §7- p" + (page + 1));
        holder.bind(inv);

        for (int i = 0; i < rows.size() && i < PAGE_SIZE; i++) {
            inv.setItem(i, flagItem(rows.get(i)));
        }

        inv.setItem(45, navItem(Material.ARROW, "§ePrev page", "prev"));
        inv.setItem(49, navItem(Material.BARRIER, "§cClose", "close"));
        inv.setItem(53, navItem(Material.ARROW, "§eNext page", "next"));

        player.openInventory(inv);
    }

    public void openDetail(Player player, MLDataCollector.Row row) {
        MLReviewHolder holder = new MLReviewHolder(MLReviewHolder.View.DETAIL, 0, row.id);
        Inventory inv = Bukkit.createInventory(holder, 54,
                "§8Detail §7- " + row.checkName + " #" + row.id);
        holder.bind(inv);

        ItemStack header = new ItemStack(Material.PAPER);
        ItemMeta hm = header.getItemMeta();
        if (hm != null) {
            hm.setDisplayName("§b" + row.checkName + " §7- §f#" + row.id);
            List<String> lore = new ArrayList<>();
            lore.add("§7Player: §f" + shortUuid(row.playerUuid));
            lore.add("§7Verdict: §f" + row.verdict);
            lore.add("§7Time: §f" + ago(row.ts));
            lore.add("§7Ping: §f" + row.ping + "ms");
            lore.add("§7TPS: §f" + String.format("%.2f", row.tps));
            lore.add("§7World: §f" + (row.world == null ? "" : row.world));
            lore.add("§7Client: §f" + (row.clientVersion == null || row.clientVersion.isEmpty() ? "unknown" : row.clientVersion));
            hm.setLore(lore);
            header.setItemMeta(hm);
        }
        inv.setItem(4, header);

        inv.setItem(20, jsonItem(Material.BOOK, "§aFeatures", row.features));
        inv.setItem(24, jsonItem(Material.WRITABLE_BOOK, "§dThresholds", row.thresholds));

        inv.setItem(49, navItem(Material.ARROW, "§eBack", "back"));
        player.openInventory(inv);
    }

    public List<MLDataCollector.Row> getCached(UUID player) {
        return cache.getOrDefault(player, new ArrayList<>());
    }

    public void cycleOutcome(Player player, long rowId) {
        List<MLDataCollector.Row> rows = cache.get(player.getUniqueId());
        if (rows == null) return;
        for (int i = 0; i < rows.size(); i++) {
            MLDataCollector.Row r = rows.get(i);
            if (r.id != rowId) continue;
            String next = nextOutcome(r.reviewOutcome);
            MLDataCollector.Row updated = new MLDataCollector.Row(r.id, r.ts, r.playerUuid, r.checkName,
                    r.features, r.thresholds, r.verdict, r.clientVersion, r.ping, r.tps, r.world,
                    next, System.currentTimeMillis());
            rows.set(i, updated);
            database.updateMLOutcome(rowId, next);
            player.sendMessage("§7Row §f#" + rowId + " §7- §a" + (next == null ? "cleared" : next));
            return;
        }
    }

    private static String nextOutcome(String current) {
        if (current == null) return "TRUE_POSITIVE";
        switch (current) {
            case "TRUE_POSITIVE": return "FALSE_POSITIVE";
            case "FALSE_POSITIVE": return "UNSURE";
            case "UNSURE": return null;
            default: return "TRUE_POSITIVE";
        }
    }

    private ItemStack flagItem(MLDataCollector.Row row) {
        Material mat = "HARD_FLAG".equals(row.verdict) ? Material.RED_DYE : Material.YELLOW_DYE;
        ItemStack stack = new ItemStack(mat);
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) return stack;
        meta.setDisplayName("§c" + row.checkName + " §7#" + row.id);
        List<String> lore = new ArrayList<>();
        lore.add("§7Player: §f" + shortUuid(row.playerUuid));
        lore.add("§7Verdict: §f" + row.verdict);
        lore.add("§7" + ago(row.ts) + " ago");
        lore.add("§7Ping: §f" + row.ping + "ms");
        lore.add("§7Outcome: §f" + (row.reviewOutcome == null ? "unset" : row.reviewOutcome));
        lore.add("");
        lore.add("§eLeft: cycle outcome");
        lore.add("§eRight: details");
        meta.setLore(lore);
        meta.getPersistentDataContainer().set(rowIdKey, PersistentDataType.LONG, row.id);
        stack.setItemMeta(meta);
        return stack;
    }

    private ItemStack jsonItem(Material mat, String name, String json) {
        ItemStack stack = new ItemStack(mat);
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) return stack;
        meta.setDisplayName(name);
        meta.setLore(splitJson(json));
        stack.setItemMeta(meta);
        return stack;
    }

    private List<String> splitJson(String json) {
        List<String> out = new ArrayList<>();
        if (json == null) { out.add("§7(empty)"); return out; }
        int chunk = 48;
        for (int i = 0; i < json.length(); i += chunk) {
            out.add("§f" + json.substring(i, Math.min(i + chunk, json.length())));
            if (out.size() >= 12) { out.add("§8..."); break; }
        }
        if (out.isEmpty()) out.add("§7(empty)");
        return out;
    }

    private ItemStack navItem(Material mat, String name, String action) {
        ItemStack stack = new ItemStack(mat);
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) return stack;
        meta.setDisplayName(name);
        meta.getPersistentDataContainer().set(actionKey, PersistentDataType.STRING, action);
        stack.setItemMeta(meta);
        return stack;
    }

    private static String shortUuid(String uuid) {
        if (uuid == null) return "";
        Player p = Bukkit.getPlayer(UUID.fromString(uuid));
        if (p != null) return p.getName();
        return uuid.substring(0, Math.min(8, uuid.length()));
    }

    private static String ago(long ts) {
        long sec = (System.currentTimeMillis() - ts) / 1000L;
        if (sec < 60) return sec + "s";
        if (sec < 3600) return (sec / 60) + "m";
        if (sec < 86400) return (sec / 3600) + "h";
        return (sec / 86400) + "d";
    }
}
