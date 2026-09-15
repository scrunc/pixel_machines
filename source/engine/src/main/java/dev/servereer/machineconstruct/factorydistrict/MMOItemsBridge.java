package dev.servereer.machineconstruct.factorydistrict;

import org.bukkit.Bukkit;
import org.bukkit.inventory.ItemStack;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Soft MMOItems integration by reflection (no compile/runtime dependency). Resolves an
 * {@code ItemStack} for a {@code <TYPE> <ID>} reward via {@code MMOItems.plugin.getItem(type, id)}.
 * Returns null if MMOItems is absent or the item id is unknown.
 */
final class MMOItemsBridge {

    private MMOItemsBridge() {}

    private static boolean resolved;
    private static Object pluginInstance;   // net.Indyuce.mmoitems.MMOItems.plugin
    private static Method getItemSS;        // getItem(String type, String id) → ItemStack
    private static Method getType;          // getType(String) → Type  (fallback path)
    private static Method getItemTS;        // getItem(Type, String) → ItemStack (fallback path)

    private static synchronized void resolve() {
        if (resolved) return;
        resolved = true;
        try {
            if (!Bukkit.getPluginManager().isPluginEnabled("MMOItems")) return;
            Class<?> mmo = Class.forName("net.Indyuce.mmoitems.MMOItems");
            Field pluginField = mmo.getField("plugin");
            pluginInstance = pluginField.get(null);
            // Preferred: getItem(String, String)
            try { getItemSS = pluginInstance.getClass().getMethod("getItem", String.class, String.class); }
            catch (NoSuchMethodException ignored) { /* try the Type-based path below */ }
            // Fallback: getType(String) → Type, then getItem(Type, String)
            try {
                Class<?> typeClass = Class.forName("net.Indyuce.mmoitems.api.Type");
                getType = pluginInstance.getClass().getMethod("getType", String.class);
                getItemTS = pluginInstance.getClass().getMethod("getItem", typeClass, String.class);
            } catch (Throwable ignored) { /* fallback unavailable */ }
            // Official identifiers: MMOItems.getType(ItemStack) (static) + MMOItems.getID(ItemStack) (static)
            try {
                Class<?> typeClass = Class.forName("net.Indyuce.mmoitems.api.Type");
                idGetType = mmo.getMethod("getType", org.bukkit.inventory.ItemStack.class);   // static → Type
                idGetId = mmo.getMethod("getID", org.bukkit.inventory.ItemStack.class);        // static → String
                typeGetId = typeClass.getMethod("getId");                                       // Type.getId() → String
            } catch (Throwable ignored) { idGetType = null; idGetId = null; typeGetId = null; }
        } catch (Throwable t) {
            pluginInstance = null;   // MMOItems missing or API shape changed
        }
    }

    private static Method idGetType, idGetId, typeGetId;   // official ItemStack → (Type,id) identifiers

    private static boolean nbtResolved;
    private static Method nbtGet;       // NBTItem.get(ItemStack) → NBTItem
    private static Method nbtGetString; // NBTItem.getString(String) → String

    private static synchronized void resolveNbt() {
        if (nbtResolved) return;
        nbtResolved = true;
        try {
            Class<?> nbt = Class.forName("io.lumine.mythic.lib.api.item.NBTItem");
            nbtGet = nbt.getMethod("get", ItemStack.class);
            nbtGetString = nbt.getMethod("getString", String.class);
        } catch (Throwable t) { nbtGet = null; nbtGetString = null; }
    }

    /** The MMOItems identity {type, id} of an item, or null if it isn't an MMOItem. */
    static String[] identify(ItemStack it) {
        if (it == null || it.getType().isAir()) return null;
        resolve();
        // Preferred: official MMOItems.getType(ItemStack) + getID(ItemStack)
        if (idGetType != null && idGetId != null && typeGetId != null) {
            try {
                Object t = idGetType.invoke(null, it);          // static
                String id = (String) idGetId.invoke(null, it);  // static
                if (t != null && id != null && !id.isEmpty()) {
                    String type = (String) typeGetId.invoke(t);
                    if (type != null && !type.isEmpty()) return new String[]{ type, id };
                }
            } catch (Throwable ignored) { /* fall through to NBT */ }
        }
        // Fallback: read the MMOItems NBT tags directly
        resolveNbt();
        if (nbtGet == null || nbtGetString == null) return null;
        try {
            Object nbt = nbtGet.invoke(null, it);
            String type = (String) nbtGetString.invoke(nbt, "MMOITEMS_ITEM_TYPE");
            String id = (String) nbtGetString.invoke(nbt, "MMOITEMS_ITEM_ID");
            if (type == null || type.isEmpty() || id == null || id.isEmpty()) return null;
            return new String[]{ type, id };
        } catch (Throwable t) { return null; }
    }

    /** True if {@code it} is the MMOItem {@code type}/{@code id} — by identity, else by similarity. */
    static boolean matches(ItemStack it, String type, String id) {
        String[] ident = identify(it);
        if (ident != null) return ident[0].equalsIgnoreCase(type) && ident[1].equalsIgnoreCase(id);
        ItemStack canon = get(type, id);                 // fallback: compare to the canonical item
        return canon != null && canon.isSimilar(it);
    }

    /** The MMOItems item for {@code type}/{@code id}, or null if unavailable. */
    static ItemStack get(String type, String id) {
        resolve();
        if (pluginInstance == null) return null;
        try {
            if (getItemSS != null) {
                Object r = getItemSS.invoke(pluginInstance, type, id);
                if (r instanceof ItemStack is) return is;
            }
            if (getType != null && getItemTS != null) {
                Object t = getType.invoke(pluginInstance, type);
                if (t != null) {
                    Object r = getItemTS.invoke(pluginInstance, t, id);
                    if (r instanceof ItemStack is) return is;
                }
            }
        } catch (Throwable ignored) { /* unknown id / API drift → null */ }
        return null;
    }
}
