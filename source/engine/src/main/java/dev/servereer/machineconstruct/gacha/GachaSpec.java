package dev.servereer.machineconstruct.gacha;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The {@code gacha:} block of a capsule machine (ADR 0045): which SERIES it dispenses, what a pull
 * costs, the pity rule, and the RARITIES — each a weight plus the look of its capsule (head texture,
 * colour, glow, sound, particle). The loot itself lives in the series file
 * ({@code plugins/MachineConstruct/gacha/<series>.yml}, see {@link GachaSeries}) so admins can feed
 * items in-game and edit them in Dev's Diary without touching the template.
 * <pre>
 * gacha:
 *   series: trench_relics
 *   title: "Trench Relics"
 *   style: capsule                                # capsule (a capsule parks in the tray) | reels (a slot machine, pays out at once)
 *   price: { coins: 1 }                           # the series' COIN (a held item set in-game, else the engine's default token);
 *                                                 # and/or money: 250 / item: prismarine_shard, amount: 8
 *   pity: { rarity: rare, every: 12 }
 *   multi: 10
 *   open_after: 20                                # seconds before a waiting capsule opens itself
 *   broadcast: { min_rarity: rare, radius: 24 }
 *   hidden: [capsule, half_l, half_r, prize]      # parts blank until a cue shows them
 *   rarities:
 *     common: { weight: 70, capsule: "eyJ…", color: "#9fb4c7" }
 *     rare:   { weight: 7,  capsule: "eyJ…", color: "#b07cff", glow: true, sound: block.amethyst_block.chime, pitch: 1.2, fx: end_rod }
 * </pre>
 */
public final class GachaSpec {

    /** One rarity tier: its share of pulls and the capsule look. */
    public record Rarity(String name, double weight, String capsule, String color, boolean glow, String sound, double pitch, String fx, String label, String symbol) {
        /** What a slot reel shows for this tier — its own {@code symbol:}, else the capsule head. */
        public String reelSymbol() { return symbol == null || symbol.isBlank() ? capsule : symbol; }
    }

    /** How a machine performs a pull: a capsule that parks in a tray, or reels that spin and pay out at once. */
    public enum Style { CAPSULE, REELS, CLAW }

    private final String series, title;
    private final Style style;
    private final double priceMoney;
    private final int priceCoins; private final String coinTier;
    private final Material priceItem; private final int priceAmount;
    private final String pityRarity; private final int pityEvery;
    private final int multi;
    private final int openAfter;
    private final String broadcastMin; private final double broadcastRadius;
    private final List<String> hidden;
    private final ClawSpec claw;      // gacha.claw — set when style: claw
    private final String seedCrate;   // gacha.seed_crate — an ExcellentCrates crate id whose rewards fill the series once
    private final Map<String, Rarity> rarities;

    private GachaSpec(String series, String title, Style style, double priceMoney, int priceCoins, String coinTier, Material priceItem, int priceAmount, String pityRarity, int pityEvery,
                      int multi, int openAfter, String broadcastMin, double broadcastRadius, List<String> hidden, Map<String, Rarity> rarities, String seedCrate, ClawSpec claw) {
        this.seedCrate = seedCrate; this.claw = claw;
        this.series = series; this.title = title; this.style = style; this.priceMoney = priceMoney; this.priceCoins = priceCoins; this.coinTier = coinTier; this.priceItem = priceItem; this.priceAmount = priceAmount;
        this.pityRarity = pityRarity; this.pityEvery = pityEvery; this.multi = multi; this.openAfter = openAfter;
        this.broadcastMin = broadcastMin; this.broadcastRadius = broadcastRadius;
        this.hidden = Collections.unmodifiableList(hidden); this.rarities = Collections.unmodifiableMap(rarities);
    }

    public static GachaSpec parse(ConfigurationSection sec) {
        if (sec == null) return null;
        String series = sec.getString("series", "default").trim();
        String title = sec.getString("title", series);
        ConfigurationSection price = sec.getConfigurationSection("price");
        double money = price == null ? 0 : price.getDouble("money", 0);
        int coins = price == null ? 0 : price.getInt("coins", 0);
        if (price == null) coins = 1;   // no price block at all → one coin (the default token) per pull
        String coinTier = price == null ? "common" : price.getString("coin", "common").trim().toLowerCase();
        Material item = null; int amount = 0;
        if (price != null && price.isString("item")) {
            item = Material.matchMaterial(price.getString("item", "").trim().toUpperCase().replace("MINECRAFT:", ""));
            amount = Math.max(1, price.getInt("amount", 1));
        }
        ConfigurationSection pity = sec.getConfigurationSection("pity");
        String pityR = pity == null ? null : pity.getString("rarity");
        int pityN = pity == null ? 0 : pity.getInt("every", 0);
        ConfigurationSection bc = sec.getConfigurationSection("broadcast");
        Map<String, Rarity> rarities = new LinkedHashMap<>();
        ConfigurationSection rs = sec.getConfigurationSection("rarities");
        if (rs != null) for (String k : rs.getKeys(false)) {
            ConfigurationSection r = rs.getConfigurationSection(k);
            if (r == null) continue;
            rarities.put(k, new Rarity(k, r.getDouble("weight", 1), r.getString("capsule", ""), r.getString("color", "#9fb4c7"),
                    r.getBoolean("glow", false), r.getString("sound"), r.getDouble("pitch", 1.0), r.getString("fx"), r.getString("label", k), r.getString("symbol")));
        }
        String styleName = sec.getString("style", "capsule");
        Style style = "reels".equalsIgnoreCase(styleName) ? Style.REELS
                : "claw".equalsIgnoreCase(styleName) ? Style.CLAW : Style.CAPSULE;
        return new GachaSpec(series, title, style, money, coins, coinTier, item, amount, pityR, pityN, Math.max(0, sec.getInt("multi", 10)), Math.max(3, sec.getInt("open_after", 20)),
                bc == null ? null : bc.getString("min_rarity"), bc == null ? 0 : bc.getDouble("radius", 24), sec.getStringList("hidden"), rarities, sec.getString("seed_crate"), ClawSpec.parse(sec.getConfigurationSection("claw")));
    }

    public String series() { return series; }
    public String title() { return title; }
    public Style style() { return style; }
    public boolean isReels() { return style == Style.REELS; }
    public boolean isClaw() { return style == Style.CLAW && claw != null; }
    /** The crane's geometry and odds, or null when this isn't a claw machine. */
    public ClawSpec claw() { return claw; }
    public double priceMoney() { return priceMoney; }
    public int priceCoins() { return priceCoins; }
    /** Which coin-ladder tier the machine takes when its series has no coin of its own. */
    public String coinTier() { return coinTier; }
    public Material priceItem() { return priceItem; }
    public int priceAmount() { return priceAmount; }
    public String pityRarity() { return pityRarity; }
    public int pityEvery() { return pityEvery; }
    public int multi() { return multi; }
    public int openAfter() { return openAfter; }
    public String broadcastMin() { return broadcastMin; }
    public double broadcastRadius() { return broadcastRadius; }
    public List<String> hidden() { return hidden; }
    public String seedCrate() { return seedCrate; }
    public Map<String, Rarity> rarities() { return rarities; }

    /** Rarity names worst → best (file order). */
    public List<String> rarityOrder() { return new ArrayList<>(rarities.keySet()); }
    public int rank(String rarity) { return rarityOrder().indexOf(rarity); }
    public Rarity rarity(String name) { Rarity r = rarities.get(name); return r != null ? r : rarities.values().stream().findFirst().orElse(null); }

    /** Share of pulls (0..100) for a rarity. */
    public double percent(String name) {
        double total = 0; for (Rarity r : rarities.values()) total += r.weight();
        Rarity r = rarities.get(name);
        return total <= 0 || r == null ? 0 : r.weight() * 100.0 / total;
    }
}
