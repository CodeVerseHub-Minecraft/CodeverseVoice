package net.codeverse.voice.paper.gui;

import net.codeverse.voice.config.PluginConfig;
import net.codeverse.voice.paper.util.Sounds;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Base class for the plugin's inventory menus.
 *
 * Menus identify themselves through the inventory holder rather than by title
 * or contents. A title can be spoofed by any other plugin opening a similarly
 * named inventory, and acting on that would let an unprivileged player trigger
 * a moderation action by opening a lookalike menu. Holder identity cannot be
 * forged from outside this plugin.
 *
 * Every slot carries its own handler, so adding an action never means editing a
 * switch over slot numbers that silently drifts out of step with the layout.
 */
public abstract class Menu implements InventoryHolder {

    private final PluginConfig config;
    private final Map<Integer, Action> actions = new HashMap<>();
    private Inventory inventory;

    protected Menu(PluginConfig config) {
        this.config = config;
    }

    /** Title shown at the top of the menu. */
    protected abstract Component title();

    /** Number of rows, capped to the configured maximum. */
    protected int rows() {
        return config.gui.rows;
    }

    /** Populates the menu. Called on open and on every refresh. */
    protected abstract void build();

    @Override
    public Inventory getInventory() {
        if (inventory == null) {
            inventory = Bukkit.createInventory(this, Math.max(9, rows() * 9), title());
        }
        return inventory;
    }

    public void open(Player viewer) {
        actions.clear();
        getInventory().clear();
        build();
        if (config.gui.fillEmptySlots) {
            fillEmpty();
        }
        viewer.openInventory(getInventory());
    }

    /** Rebuilds in place, so an action can update the menu without reopening it. */
    public void refresh() {
        actions.clear();
        getInventory().clear();
        build();
        if (config.gui.fillEmptySlots) {
            fillEmpty();
        }
    }

    protected void set(int slot, ItemStack item, Action action) {
        if (slot < 0 || slot >= getInventory().getSize()) {
            return;
        }
        getInventory().setItem(slot, item);
        if (action != null) {
            actions.put(slot, action);
        }
    }

    protected void set(int slot, ItemStack item) {
        set(slot, item, null);
    }

    /** Invoked by the listener. Returns true when the slot had a handler. */
    public boolean handleClick(Player viewer, int slot, ClickType click) {
        Action action = actions.get(slot);
        if (action == null) {
            return false;
        }
        if (config.gui.playClickSound) {
            playClick(viewer);
        }
        action.run(viewer, click);
        return true;
    }

    private void playClick(Player viewer) {
        // A mistyped sound name should not break the menu, and the config
        // validator cannot check one without a running server.
        Sounds.resolve(config.gui.clickSound)
                .ifPresent(sound -> viewer.playSound(viewer.getLocation(), sound, 0.5f, 1.4f));
    }

    private void fillEmpty() {
        Material filler = material(config.gui.fillMaterial, Material.GRAY_STAINED_GLASS_PANE);
        ItemStack pane = new ItemStack(filler);
        ItemMeta meta = pane.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.empty());
            pane.setItemMeta(meta);
        }
        Inventory target = getInventory();
        for (int slot = 0; slot < target.getSize(); slot++) {
            if (target.getItem(slot) == null) {
                target.setItem(slot, pane);
            }
        }
    }

    /** Builds a display item. Lore entries are already rendered components. */
    protected static ItemStack icon(Material material, Component name, List<Component> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(name.decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false));
            if (lore != null && !lore.isEmpty()) {
                List<Component> cleaned = new ArrayList<>(lore.size());
                for (Component line : lore) {
                    cleaned.add(line.decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false));
                }
                meta.lore(cleaned);
            }
            item.setItemMeta(meta);
        }
        return item;
    }

    /** Resolves a configured material name, falling back rather than failing. */
    protected static Material material(String name, Material fallback) {
        if (name == null || name.isBlank()) {
            return fallback;
        }
        Material resolved = Material.matchMaterial(name.toUpperCase(Locale.ROOT));
        return resolved == null ? fallback : resolved;
    }

    protected PluginConfig config() {
        return config;
    }

    /** A click handler bound to one slot. */
    @FunctionalInterface
    public interface Action {
        void run(Player viewer, ClickType click);
    }
}
