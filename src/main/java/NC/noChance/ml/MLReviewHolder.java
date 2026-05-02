package NC.noChance.ml;

import org.bukkit.Bukkit;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

public class MLReviewHolder implements InventoryHolder {
    public enum View { LIST, DETAIL }

    private final View view;
    private final int page;
    private final long detailRowId;
    private Inventory inv;

    public MLReviewHolder(View view, int page, long detailRowId) {
        this.view = view;
        this.page = page;
        this.detailRowId = detailRowId;
    }

    public View view() { return view; }
    public int page() { return page; }
    public long detailRowId() { return detailRowId; }

    void bind(Inventory inv) { this.inv = inv; }

    @Override
    public Inventory getInventory() {
        if (inv == null) inv = Bukkit.createInventory(this, 54);
        return inv;
    }
}
