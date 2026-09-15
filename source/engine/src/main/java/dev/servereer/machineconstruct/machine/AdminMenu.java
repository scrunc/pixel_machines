package dev.servereer.machineconstruct.machine;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Admin machine browser + restore GUI (/mc admin). Lists every backed-up machine (id, type, owner,
 * location, size, snapshot count, quarantine flag); click one to see its snapshot timeline and restore
 * any version, or teleport to it. Read-only except the restore/teleport buttons.
 */
public final class AdminMenu implements Listener {

    private static final int SIZE = 54;
    private static final int[] GRID = { 10,11,12,13,14,15,16, 19,20,21,22,23,24,25, 28,29,30,31,32,33,34, 37,38,39,40,41,42,43 };

    private final MachineManager mgr;
    public AdminMenu(MachineManager mgr) { this.mgr = mgr; }

    private enum View { LIST, DETAIL }

    public final class Holder implements InventoryHolder {
        View view = View.LIST;
        int page;
        String machineId;                                  // DETAIL: which machine
        Inventory inv;
        final Map<Integer, String> slotMachine = new HashMap<>();   // LIST: slot → machine id
        final Map<Integer, Integer> slotVersion = new HashMap<>();  // DETAIL: slot → version index
        @Override public Inventory getInventory() { return inv; }
    }

    public void open(Player p) {
        Holder h = new Holder();
        h.inv = Bukkit.createInventory(h, SIZE, ChatColor.DARK_AQUA + "⚙ Machine Admin");
        render(h);
        p.openInventory(h.inv);
    }

    private void render(Holder h) {
        Inventory inv = h.inv;
        inv.clear();
        h.slotMachine.clear();
        h.slotVersion.clear();
        ItemStack edge = item(Material.GRAY_STAINED_GLASS_PANE, " ", null);
        for (int i = 0; i < 9; i++) inv.setItem(i, edge);
        for (int i = 45; i < 54; i++) inv.setItem(i, edge);
        if (h.view == View.LIST) renderList(inv, h); else renderDetail(inv, h);
        inv.setItem(49, item(Material.BARRIER, "&cClose", null));
    }

    private void renderList(Inventory inv, Holder h) {
        List<Map.Entry<String, MachineBackupStore.Entry>> all = mgr.backupEntries();
        int per = GRID.length;
        int pages = Math.max(1, (int) Math.ceil(all.size() / (double) per));
        if (h.page >= pages) h.page = pages - 1;
        if (h.page < 0) h.page = 0;
        inv.setItem(4, item(Material.COMPARATOR, "&b⚙ Machine Admin",
                List.of("&7" + all.size() + " machine(s) backed up.", "&7Click one to view snapshots & restore.", "&8Page " + (h.page + 1) + "/" + pages)));
        int start = h.page * per;
        for (int i = 0; i < per; i++) {
            int idx = start + i;
            if (idx >= all.size()) break;
            var en = all.get(idx);
            MachineBackupStore.Entry e = en.getValue();
            MachineBackupStore.Version v = e.latest();
            boolean quar = quarantined(en.getKey());
            List<String> lore = new ArrayList<>();
            lore.add("&8id &7#§f" + shortId(en.getKey()));
            lore.add("&7owner: &f" + ownerName(e.owner));
            lore.add("&7at: &f" + e.world + " " + e.x + " " + e.y + " " + e.z);
            lore.add("&7snapshots: &f" + e.versions.size() + " &8· newest " + (v == null ? "—" : MachineManager.agoText(v.ts) + " ago · " + v.size() + "B"));
            if (quar) lore.add("&c[QUARANTINED — data preserved]");
            lore.add("&8▶ click to manage");
            inv.setItem(GRID[i], colored(item(icon(e.type), "&e" + e.type + " &7#" + shortId(en.getKey()), lore), quar));
            h.slotMachine.put(GRID[i], en.getKey());
        }
        if (all.isEmpty()) inv.setItem(22, item(Material.GLOWSTONE_DUST, "&7No machine backups yet",
                List.of("&7Snapshots appear as machines load & change.")));
        if (h.page > 0) inv.setItem(45, item(Material.ARROW, "&e◀ Previous", null));
        if (h.page < pages - 1) inv.setItem(53, item(Material.ARROW, "&eNext ▶", null));
    }

    private void renderDetail(Inventory inv, Holder h) {
        MachineBackupStore.Entry e = mgr.backupEntry(h.machineId);
        if (e == null) { h.view = View.LIST; renderList(inv, h); return; }
        boolean quar = quarantined(h.machineId);
        int total = e.versions.size();
        int per = GRID.length;
        int pages = Math.max(1, (int) Math.ceil(total / (double) per));
        if (h.page >= pages) h.page = pages - 1;
        if (h.page < 0) h.page = 0;
        inv.setItem(4, item(icon(e.type), "&e" + e.type + " &7#" + shortId(h.machineId), List.of(
                "&7owner: &f" + ownerName(e.owner),
                "&7at: &f" + e.world + " " + e.x + " " + e.y + " " + e.z,
                "&7" + total + " snapshot(s)" + (total > 0 ? " &8· spanning " + MachineManager.agoText(e.versions.get(0).ts) : "") + (quar ? " &c[QUARANTINED]" : ""),
                "&8page " + (h.page + 1) + "/" + pages + " · newest → oldest",
                "&8click a snapshot below to restore it")));
        // versions newest → oldest, sliced to the current page
        int newestIdx = total - 1 - h.page * per;
        int i = 0;
        for (int off = 0; off < per && (newestIdx - off) >= 0; off++, i++) {
            int k = newestIdx - off;
            MachineBackupStore.Version v = e.versions.get(k);
            boolean newest = (k == total - 1);
            inv.setItem(GRID[i], item(newest ? Material.LIME_DYE : Material.PAPER,
                    "&fSnapshot #" + (k + 1) + (newest ? " &a(newest)" : ""),
                    List.of("&7" + MachineManager.agoText(v.ts) + " ago", "&7" + v.size() + " bytes", "&8▶ click to restore this version")));
            h.slotVersion.put(GRID[i], k);
        }
        inv.setItem(45, item(Material.ARROW, "&e◀ Back to list", null));
        if (h.page > 0) inv.setItem(46, item(Material.ARROW, "&e◀ Newer", List.of("&7Page " + h.page + "/" + pages)));
        inv.setItem(47, item(Material.ENDER_PEARL, "&dTeleport to machine", List.of("&7" + e.world + " " + e.x + " " + e.y + " " + e.z, "&8▶ click")));
        inv.setItem(51, item(Material.LIME_CONCRETE, "&a⟲ Restore newest", List.of("&7Roll back to the most recent snapshot.", "&8▶ click")));
        if (h.page < pages - 1) inv.setItem(52, item(Material.ARROW, "&eOlder ▶", List.of("&7Page " + (h.page + 2) + "/" + pages)));
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getView().getTopInventory().getHolder() instanceof Holder h)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (e.getClickedInventory() != e.getView().getTopInventory()) return;
        int slot = e.getRawSlot();
        if (slot == 49) { p.closeInventory(); return; }
        if (h.view == View.LIST) {
            if (slot == 45) { if (h.page > 0) { h.page--; render(h); } return; }
            if (slot == 53) { h.page++; render(h); return; }
            String id = h.slotMachine.get(slot);
            if (id != null) { h.view = View.DETAIL; h.machineId = id; h.page = 0; render(h); }
            return;
        }
        // DETAIL
        if (slot == 45) { h.view = View.LIST; h.page = 0; render(h); return; }
        if (slot == 46) { if (h.page > 0) { h.page--; render(h); } return; }
        if (slot == 52) { h.page++; render(h); return; }
        if (slot == 47) { teleport(p, h.machineId); return; }
        if (slot == 51) { restore(p, h, lastIndex(h.machineId)); return; }
        Integer vi = h.slotVersion.get(slot);
        if (vi != null) restore(p, h, vi);
    }

    private void restore(Player p, Holder h, int versionIndex) {
        if (versionIndex < 0) return;
        p.sendMessage(ChatColor.DARK_AQUA + "[MC] " + ChatColor.RESET + mgr.restoreVersion(h.machineId, versionIndex));   // already §-coded
        render(h);
    }

    private void teleport(Player p, String id) {
        MachineBackupStore.Entry e = mgr.backupEntry(id);
        if (e == null || e.world == null) return;
        World w = Bukkit.getWorld(e.world);
        if (w == null) { p.sendMessage(ChatColor.RED + "That world isn't loaded."); return; }
        p.closeInventory();
        p.teleport(new Location(w, e.x + 0.5, e.y + 1, e.z + 0.5));
        p.sendMessage(ChatColor.LIGHT_PURPLE + "Teleported to " + e.type + " #" + shortId(id) + ".");
    }

    private int lastIndex(String id) {
        MachineBackupStore.Entry e = mgr.backupEntry(id);
        return e == null ? -1 : e.versions.size() - 1;
    }

    // ---- helpers ----
    private boolean quarantined(String id) {
        try { return mgr.isQuarantined(java.util.UUID.fromString(id)); } catch (Exception ex) { return false; }
    }
    private static String shortId(String id) { return id == null ? "?" : id.substring(0, Math.min(8, id.length())); }
    private static String ownerName(String uuid) {
        if (uuid == null) return "server";
        try { String n = Bukkit.getOfflinePlayer(java.util.UUID.fromString(uuid)).getName(); return n == null ? uuid.substring(0, 8) : n; }
        catch (Exception e) { return uuid.length() >= 8 ? uuid.substring(0, 8) : uuid; }
    }
    private static Material icon(String type) {
        if (type == null) return Material.CHEST;
        String t = type.toLowerCase();
        if (t.contains("grinder")) return Material.SPAWNER;
        if (t.contains("quarry")) return Material.NETHERITE_PICKAXE;
        if (t.contains("collector")) return Material.HOPPER;
        if (t.contains("trading")) return Material.EMERALD;
        if (t.contains("factory")) return Material.CRAFTING_TABLE;
        if (t.contains("jukebox")) return Material.JUKEBOX;
        return Material.CHEST;
    }
    private ItemStack colored(ItemStack it, boolean quar) {
        if (!quar) return it;
        ItemMeta m = it.getItemMeta();
        if (m != null) { m.addEnchant(org.bukkit.enchantments.Enchantment.UNBREAKING, 1, true);
            m.addItemFlags(org.bukkit.inventory.ItemFlag.HIDE_ENCHANTS); it.setItemMeta(m); }
        return it;
    }
    private static ItemStack item(Material mat, String name, List<String> lore) {
        ItemStack it = new ItemStack(mat == null ? Material.CHEST : mat);
        ItemMeta m = it.getItemMeta();
        if (m != null) {
            m.setDisplayName(ChatColor.translateAlternateColorCodes('&', name));
            if (lore != null) { List<String> l = new ArrayList<>(); for (String s : lore) l.add(ChatColor.translateAlternateColorCodes('&', s)); m.setLore(l); }
            it.setItemMeta(m);
        }
        return it;
    }
}
