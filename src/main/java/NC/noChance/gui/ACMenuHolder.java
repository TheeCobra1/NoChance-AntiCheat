package NC.noChance.gui;

import org.bukkit.Bukkit;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

public class ACMenuHolder implements InventoryHolder {
    private final MenuType type;
    private final String param;
    private Inventory inv;

    public ACMenuHolder(MenuType type) {
        this(type, null);
    }

    public ACMenuHolder(MenuType type, String param) {
        this.type = type;
        this.param = param;
    }

    public MenuType type() {
        return type;
    }

    public String param() {
        return param;
    }

    void bind(Inventory inv) {
        this.inv = inv;
    }

    @Override
    public Inventory getInventory() {
        if (inv == null) {
            inv = Bukkit.createInventory(this, 54);
        }
        return inv;
    }
}
