package dev.servereer.machineconstruct.core;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.profile.PlayerProfile;
import org.bukkit.profile.PlayerTextures;

import java.net.URI;
import java.util.Base64;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Builds a player-head {@link ItemStack} from a skin reference — a base64
 * textures value, a texture URL, or a player name. This is how parts get a
 * distinctive look with no resource pack (DESIGN.md §9).
 */
public final class Heads {

    private static final Pattern URL_IN_JSON = Pattern.compile("\"url\"\\s*:\\s*\"([^\"]+)\"");

    private Heads() {}

    public static ItemStack create(String value) {
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        if (value == null || value.isEmpty()) return head;
        SkullMeta meta = (SkullMeta) head.getItemMeta();
        try {
            if (value.startsWith("http")) {
                applyUrl(meta, value);
            } else if (looksBase64(value)) {
                String json = new String(Base64.getDecoder().decode(value));
                Matcher m = URL_IN_JSON.matcher(json);
                if (m.find()) applyUrl(meta, m.group(1));
            } else {
                meta.setOwningPlayer(Bukkit.getOfflinePlayer(value));   // by name
            }
        } catch (Exception ignored) {
            // bad skin reference → leave a plain head rather than failing the model
        }
        head.setItemMeta(meta);
        return head;
    }

    private static void applyUrl(SkullMeta meta, String url) throws Exception {
        // A random id made every head unique: two coins from the same texture never stacked and never
        // compared equal. Derive the id from the url instead — same skin, same head.
        PlayerProfile profile = Bukkit.createPlayerProfile(UUID.nameUUIDFromBytes(("mc-head:" + url).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        PlayerTextures tex = profile.getTextures();
        tex.setSkin(URI.create(url).toURL());
        profile.setTextures(tex);
        meta.setOwnerProfile(profile);
    }

    private static boolean looksBase64(String s) {
        if (s.length() < 40) return false;
        return s.matches("[A-Za-z0-9+/=]+");
    }
}
