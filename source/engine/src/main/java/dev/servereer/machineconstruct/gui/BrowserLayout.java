package dev.servereer.machineconstruct.gui;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

/**
 * A parsed recipe-browser layout (DESIGN.md §14) — the data behind the recipe
 * sub-menu so it's authored, not hardcoded. Defines the window size, how many
 * recipes per page, where each recipe's parts sit within its 9-wide row, and the
 * nav buttons (slot + icon). The title and page button support {@code <machine>},
 * {@code <page>}, {@code <pages>} placeholders (filled at render).
 */
public final class BrowserLayout {

    private final String titleTemplate;
    private final int rows, perPage;
    private final int[] inputCols, outputCols;
    private final int arrowCol;
    private final ItemStack arrowIcon, filler, backIcon, prevIcon, nextIcon;
    private final int backSlot, prevSlot, nextSlot, pageSlot;
    private final Material pageMat;
    private final String pageNameTemplate;

    public BrowserLayout(String titleTemplate, int rows, int perPage,
                         int[] inputCols, int arrowCol, int[] outputCols,
                         ItemStack arrowIcon, ItemStack filler,
                         int backSlot, ItemStack backIcon, int prevSlot, ItemStack prevIcon,
                         int nextSlot, ItemStack nextIcon, int pageSlot, Material pageMat, String pageNameTemplate) {
        this.titleTemplate = titleTemplate;
        this.rows = rows;
        this.perPage = perPage;
        this.inputCols = inputCols;
        this.arrowCol = arrowCol;
        this.outputCols = outputCols;
        this.arrowIcon = arrowIcon;
        this.filler = filler;
        this.backSlot = backSlot;
        this.backIcon = backIcon;
        this.prevSlot = prevSlot;
        this.prevIcon = prevIcon;
        this.nextSlot = nextSlot;
        this.nextIcon = nextIcon;
        this.pageSlot = pageSlot;
        this.pageMat = pageMat;
        this.pageNameTemplate = pageNameTemplate;
    }

    public int rows() { return rows; }
    public int perPage() { return perPage; }
    public int size() { return rows * 9; }
    public int[] inputCols() { return inputCols; }
    public int[] outputCols() { return outputCols; }
    public int arrowCol() { return arrowCol; }
    public ItemStack arrowIcon() { return arrowIcon; }
    public ItemStack filler() { return filler; }
    public int backSlot() { return backSlot; }
    public int prevSlot() { return prevSlot; }
    public int nextSlot() { return nextSlot; }
    public int pageSlot() { return pageSlot; }
    public ItemStack backIcon() { return backIcon; }
    public ItemStack prevIcon() { return prevIcon; }
    public ItemStack nextIcon() { return nextIcon; }

    public Component title(String machine, int page, int pages) {
        return mm(fill(titleTemplate, machine, page, pages));
    }

    public ItemStack pageButton(int page, int pages) {
        ItemStack item = new ItemStack(pageMat);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) { meta.displayName(mm(fill(pageNameTemplate, null, page, pages))); item.setItemMeta(meta); }
        return item;
    }

    private static String fill(String s, String machine, int page, int pages) {
        if (s == null) return "";
        if (machine != null) s = s.replace("<machine>", machine);
        return s.replace("<page>", Integer.toString(page + 1)).replace("<pages>", Integer.toString(pages));
    }

    private static Component mm(String s) {
        return MiniMessage.miniMessage().deserialize(s).decoration(TextDecoration.ITALIC, false);
    }
}
