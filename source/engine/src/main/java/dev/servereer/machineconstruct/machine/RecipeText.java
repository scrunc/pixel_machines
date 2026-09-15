package dev.servereer.machineconstruct.machine;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;

/**
 * Human-readable recipe descriptions (DESIGN.md §14, recipe discovery). Shared by
 * the in-GUI info panel and the {@code /mc recipes} command so both read
 * identically: {@code 9x Iron Ingot → 1x Iron Block (3s)} plus a fuel line.
 */
public final class RecipeText {

    private RecipeText() {}

    /** One coloured line per recipe, then a fuel summary; non-italic for use as lore. */
    public static List<Component> lines(MachineType type) {
        List<Component> out = new ArrayList<>();
        for (Recipe r : type.recipes()) out.add(recipeLine(r));
        if (out.isEmpty()) out.add(plain("No recipes.", NamedTextColor.GRAY));
        if (type.requiresFuel()) out.add(fuelLine(type));
        return out;
    }

    private static Component recipeLine(Recipe r) {
        return Component.text("• ", NamedTextColor.DARK_GRAY)
                .append(plain(itemList(r.inputs()), NamedTextColor.GRAY))
                .append(plain(" → ", NamedTextColor.YELLOW))
                .append(plain(itemList(r.outputs()), NamedTextColor.GREEN))
                .append(plain("  (" + secs(r.timeTicks()) + ")", NamedTextColor.DARK_GRAY))
                .decoration(TextDecoration.ITALIC, false);
    }

    private static Component fuelLine(MachineType type) {
        StringJoiner sj = new StringJoiner(", ");
        for (Material m : type.fuelMaterials()) sj.add(pretty(m.name()));
        return plain("Fuel: ", NamedTextColor.GOLD)
                .append(plain(sj.toString(), NamedTextColor.YELLOW));
    }

    private static String itemList(List<ItemStack> items) {
        StringJoiner sj = new StringJoiner(", ");
        for (ItemStack s : items) sj.add(s.getAmount() + "x " + name(s));
        return sj.toString();
    }

    private static String name(ItemStack s) {
        if (s.hasItemMeta() && s.getItemMeta().hasDisplayName()) {
            return PlainTextComponentSerializer.plainText().serialize(s.getItemMeta().displayName());
        }
        return pretty(s.getType().name());
    }

    private static String secs(int ticks) {
        double s = ticks / 20.0;
        return (s == Math.floor(s)) ? ((int) s + "s") : (String.format("%.1fs", s));
    }

    private static String pretty(String enumName) {
        String[] parts = enumName.toLowerCase().split("_");
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p.isEmpty()) continue;
            sb.append(Character.toUpperCase(p.charAt(0))).append(p.substring(1)).append(' ');
        }
        return sb.toString().trim();
    }

    private static Component plain(String text, NamedTextColor color) {
        return Component.text(text, color).decoration(TextDecoration.ITALIC, false);
    }
}
