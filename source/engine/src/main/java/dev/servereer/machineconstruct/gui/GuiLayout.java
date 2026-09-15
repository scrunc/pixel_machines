package dev.servereer.machineconstruct.gui;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A parsed machine-GUI layout (DESIGN.md §14): a fixed-size chest menu where
 * each slot has a {@link SlotRole}. Input/output slots map (in reading order) to
 * the machine's input/output item arrays; progress slots render a live status
 * bar; decor slots are locked icons. Immutable + shared across all instances of
 * a machine type — per-player state (open window, cursor) lives elsewhere.
 */
public final class GuiLayout {

    /** A status icon template; {@code <percent>} in the name is filled at render. */
    public record ProgressIcon(Material material, String name, List<String> lore, Integer model) {
        public ItemStack build(int percent) {
            ItemStack item = new ItemStack(material == null ? Material.GRAY_STAINED_GLASS_PANE : material);
            ItemMeta meta = item.getItemMeta();
            if (meta != null) {
                if (name != null) {
                    meta.displayName(MiniMessage.miniMessage()
                            .deserialize(name.replace("<percent>", Integer.toString(percent)))
                            .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false));
                }
                if (lore != null && !lore.isEmpty()) {
                    List<Component> lines = new ArrayList<>();
                    for (String l : lore) lines.add(MiniMessage.miniMessage()
                            .deserialize(l.replace("<percent>", Integer.toString(percent)))
                            .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false));
                    meta.lore(lines);
                }
                if (model != null) meta.setCustomModelData(model);
                item.setItemMeta(meta);
            }
            return item;
        }
    }

    private final Component title;
    private final int size;
    private final SlotRole[] roles;
    private final ItemStack[] decor;          // per-slot decoration (null = empty locked slot)
    private final int[] inputSlots;
    private final int[] outputSlots;
    private final int[] fuelSlots;
    private final int[] progressSlots;
    private final int[] burnSlots;
    private final int[] infoSlots;
    private final int[] upgradeSlots;
    private final int[] tierSlots;
    private final int[] sellSlots;
    private final ItemStack infoIcon;     // base icon for info slots; recipe lore added at render
    private final Map<String, ProgressIcon> progressIcons;   // idle/working/blocked/no_fuel/burn/burn_empty

    public GuiLayout(Component title, int size, SlotRole[] roles, ItemStack[] decor,
                     int[] inputSlots, int[] outputSlots, int[] fuelSlots,
                     int[] progressSlots, int[] burnSlots, int[] infoSlots,
                     int[] upgradeSlots, int[] tierSlots, int[] sellSlots, ItemStack infoIcon,
                     Map<String, ProgressIcon> progressIcons) {
        this.title = title;
        this.size = size;
        this.roles = roles;
        this.decor = decor;
        this.inputSlots = inputSlots;
        this.outputSlots = outputSlots;
        this.fuelSlots = fuelSlots;
        this.progressSlots = progressSlots;
        this.burnSlots = burnSlots;
        this.infoSlots = infoSlots;
        this.upgradeSlots = upgradeSlots;
        this.tierSlots = tierSlots;
        this.sellSlots = sellSlots;
        this.infoIcon = infoIcon;
        this.progressIcons = progressIcons;
    }

    public Component title() { return title; }
    public int size() { return size; }
    public SlotRole role(int slot) { return (slot >= 0 && slot < size) ? roles[slot] : SlotRole.DECOR; }
    public ItemStack decorAt(int slot) { return (slot >= 0 && slot < size) ? decor[slot] : null; }
    public int[] inputSlots() { return inputSlots; }
    public int[] outputSlots() { return outputSlots; }
    public int[] fuelSlots() { return fuelSlots; }
    public int[] progressSlots() { return progressSlots; }
    public int[] burnSlots() { return burnSlots; }
    public int[] infoSlots() { return infoSlots; }
    public int[] upgradeSlots() { return upgradeSlots; }
    public int[] tierSlots() { return tierSlots; }
    public int[] sellSlots() { return sellSlots; }
    public int upgradeCount() { return upgradeSlots.length; }
    public ItemStack infoIcon() { return infoIcon; }
    public int inputCount() { return inputSlots.length; }
    public int outputCount() { return outputSlots.length; }
    public int fuelCount() { return fuelSlots.length; }
    public ProgressIcon progressIcon(String state) { return progressIcons.get(state.toLowerCase()); }

    /** The input-array index a menu slot maps to, or -1. */
    public int inputIndexOf(int slot) { return indexOf(inputSlots, slot); }

    /** The output-array index a menu slot maps to, or -1. */
    public int outputIndexOf(int slot) { return indexOf(outputSlots, slot); }

    /** The fuel-array index a menu slot maps to, or -1. */
    public int fuelIndexOf(int slot) { return indexOf(fuelSlots, slot); }

    /** The upgrade-array index a menu slot maps to, or -1. */
    public int upgradeIndexOf(int slot) { return indexOf(upgradeSlots, slot); }

    private static int indexOf(int[] arr, int slot) {
        for (int i = 0; i < arr.length; i++) if (arr[i] == slot) return i;
        return -1;
    }
}
