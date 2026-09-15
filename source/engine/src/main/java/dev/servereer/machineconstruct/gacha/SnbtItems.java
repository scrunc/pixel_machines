package dev.servereer.machineconstruct.gacha;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns an item written as SNBT ({@code {count:4,id:"minecraft:diamond",components:{…}}} — what
 * ExcellentCrates stores) into an ItemStack by re-shaping it as the {@code /give} form
 * {@code minecraft:diamond[key=value,…]} and letting the item factory parse the components. Only
 * the top level is split by hand (respecting quotes / braces); component values go through
 * untouched. Falls back to a plain stack of the material when the components won't parse.
 */
public final class SnbtItems {

    private SnbtItems() { }

    public static ItemStack parse(String snbt) {
        if (snbt == null) return null;
        String s = snbt.trim();
        if (!s.startsWith("{") || !s.endsWith("}")) return null;
        String id = null, count = "1", components = null;
        for (String entry : splitTop(s.substring(1, s.length() - 1))) {
            int c = keyColon(entry);
            if (c < 0) continue;
            String k = unq(entry.substring(0, c).trim()), v = entry.substring(c + 1).trim();
            switch (k) {
                case "id" -> id = unq(v);
                case "count", "Count" -> count = v.replaceAll("[^0-9]", "");
                case "components" -> components = v;
                default -> { }
            }
        }
        if (id == null) return null;
        int amount = 1;
        try { amount = Math.max(1, Integer.parseInt(count.isEmpty() ? "1" : count)); } catch (NumberFormatException ignored) { }
        ItemStack it = null;
        if (components != null && components.startsWith("{") && components.endsWith("}")) {
            List<String> parts = new ArrayList<>();
            for (String entry : splitTop(components.substring(1, components.length() - 1))) {
                int c = keyColon(entry);
                if (c < 0) continue;
                parts.add(unq(entry.substring(0, c).trim()) + "=" + entry.substring(c + 1).trim());
            }
            try { it = Bukkit.getItemFactory().createItemStack(id + "[" + String.join(",", parts) + "]"); } catch (Throwable ignored) { it = null; }
        }
        if (it == null) {
            Material mat = Material.matchMaterial(id.toUpperCase().replace("MINECRAFT:", ""));
            if (mat == null) return null;
            it = new ItemStack(mat);
        }
        it.setAmount(Math.min(amount, Math.max(1, it.getMaxStackSize() * 1)));
        if (amount > it.getMaxStackSize()) it.setAmount(amount);   // let over-stacks through; the give path splits them
        return it;
    }

    /** Split {@code a:1,b:"x,y",c:{d:2}} at top-level commas. */
    static List<String> splitTop(String s) {
        List<String> out = new ArrayList<>();
        int depth = 0; boolean q = false; char qc = 0; StringBuilder cur = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (q) { cur.append(ch); if (ch == '\\' && i + 1 < s.length()) { cur.append(s.charAt(++i)); } else if (ch == qc) q = false; continue; }
            if (ch == '"' || ch == '\'') { q = true; qc = ch; cur.append(ch); continue; }
            if (ch == '{' || ch == '[') depth++;
            if (ch == '}' || ch == ']') depth--;
            if (ch == ',' && depth == 0) { if (cur.toString().trim().length() > 0) out.add(cur.toString().trim()); cur.setLength(0); continue; }
            cur.append(ch);
        }
        if (cur.toString().trim().length() > 0) out.add(cur.toString().trim());
        return out;
    }

    /** Index of the key/value colon (the first one outside quotes). */
    static int keyColon(String s) {
        boolean q = false; char qc = 0;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (q) { if (ch == '\\') i++; else if (ch == qc) q = false; continue; }
            if (ch == '"' || ch == '\'') { q = true; qc = ch; continue; }
            if (ch == ':') return i;
        }
        return -1;
    }

    static String unq(String s) {
        String t = s.trim();
        if (t.length() >= 2 && (t.charAt(0) == '"' || t.charAt(0) == '\'') && t.charAt(t.length() - 1) == t.charAt(0)) return t.substring(1, t.length() - 1);
        return t;
    }
}
