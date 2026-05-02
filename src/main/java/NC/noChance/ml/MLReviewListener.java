package NC.noChance.ml;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.List;

public class MLReviewListener implements Listener {
    private final MLDataReviewGUI gui;

    public MLReviewListener(MLDataReviewGUI gui) {
        this.gui = gui;
    }

    private MLReviewHolder holderOf(Inventory inv) {
        if (inv == null) return null;
        return inv.getHolder() instanceof MLReviewHolder h ? h : null;
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        MLReviewHolder holder = holderOf(event.getInventory());
        if (holder == null) return;

        event.setCancelled(true);

        if (!player.hasPermission("nochance.review") && !player.hasPermission("nochance.admin")) {
            player.closeInventory();
            return;
        }

        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || clicked.getType().isAir() || !clicked.hasItemMeta()) return;
        ItemMeta meta = clicked.getItemMeta();
        if (meta == null) return;

        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        String action = pdc.get(gui.actionKey(), PersistentDataType.STRING);
        if (action != null) {
            handleNav(player, holder, action);
            return;
        }

        Long rowId = pdc.get(gui.rowIdKey(), PersistentDataType.LONG);
        if (rowId == null) return;

        if (event.isRightClick()) {
            MLDataCollector.Row row = findRow(player, rowId);
            if (row != null) gui.openDetail(player, row);
            return;
        }

        if (event.isLeftClick()) {
            gui.cycleOutcome(player, rowId);
            gui.open(player, holder.page());
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onDrag(InventoryDragEvent event) {
        if (holderOf(event.getInventory()) != null) {
            event.setCancelled(true);
        }
    }

    private void handleNav(Player p, MLReviewHolder h, String action) {
        switch (action) {
            case "close" -> p.closeInventory();
            case "next" -> gui.open(p, h.page() + 1);
            case "prev" -> gui.open(p, Math.max(0, h.page() - 1));
            case "back" -> gui.open(p, 0);
            default -> {}
        }
    }

    private MLDataCollector.Row findRow(Player p, long rowId) {
        List<MLDataCollector.Row> rows = gui.getCached(p.getUniqueId());
        for (MLDataCollector.Row r : rows) if (r.id == rowId) return r;
        return null;
    }
}
