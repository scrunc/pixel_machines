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

    /**
     * One guarantee: "a {@code rarity} or better at least every {@code every} pulls". A machine may carry
     * several — a soft floor every ten pulls and a headline tier every hundred, say — and each keeps its
     * own counter per player, reset whenever a pull lands on that tier or above.
     */
    public record Pity(String rarity, int every) { }

    /**
     * What a PLAYER is allowed to see. A machine may be a plain vending machine — items in, item out,
     * no percentages, no tiers, no countdown — or it may show its whole hand. None of this changes a
     * roll: the rarities still drive weights, colours and the theatre, they are simply not narrated.
     * Admins always see everything, so a machine can be tuned without turning its shutters back on.
     * <pre>
     * show:
     *   rarity: false      # no rarity names in the menus or the messages — just the item
     *   odds: false        # no Odds screen, no percentages
     *   pity: true         # the guarantee countdowns (menu line, chat hint, luck board)
     *   contents: true     # the Collection screen: what this machine can give
     * </pre>
     */
    public record Show(boolean rarity, boolean odds, boolean pity, boolean contents) {
        public static Show parse(ConfigurationSection sec) {
            if (sec == null) return new Show(true, true, true, true);
            return new Show(sec.getBoolean("rarity", true), sec.getBoolean("odds", true),
                    sec.getBoolean("pity", true), sec.getBoolean("contents", true));
        }
    }

    /**
     * The lore on a prize in the player's contents menu. Lines are MiniMessage with placeholders, and
     * <b>a line that comes out empty is dropped</b> — which is how {@code show:} does its work here: with
     * {@code rarity: false} the rarity placeholders resolve to nothing, so the rarity line simply is not
     * there, and the same for {@code odds}/{@code pity}. Write the lore you want; the switches prune it.
     * <pre>
     * lore:
     *   owned:   ["&lt;{color}&gt;{rarity_label}", "&lt;gray&gt;Pulled &lt;white&gt;{count}x"]
     *   unowned: ["&lt;{color}&gt;{rarity_label}", "&lt;dark_gray&gt;Not pulled yet"]
     *   extra:   ["&lt;gray&gt;Chance: &lt;white&gt;{chance}%", "&lt;dark_gray&gt;{pity}"]   # appended to both
     * </pre>
     * Placeholders: {@code {name} {rarity} {rarity_label} {color} {count} {chance} {pity} {owned} {total}
     * {pulls} {price}}.
     */
    public record Lore(List<String> owned, List<String> unowned, List<String> extra) {
        static final List<String> DEF_OWNED = List.of("<{color}>{rarity_label}", "<gray>Pulled <white>{count}×");
        static final List<String> DEF_UNOWNED = List.of("<{color}>{rarity_label}", "<dark_gray>Not pulled yet");
        static final List<String> DEF_EXTRA = List.of("<dark_gray>{chance}", "<dark_gray>{pity}");

        static Lore parse(ConfigurationSection sec) {
            if (sec == null) return new Lore(DEF_OWNED, DEF_UNOWNED, DEF_EXTRA);
            return new Lore(lines(sec, "owned", DEF_OWNED), lines(sec, "unowned", DEF_UNOWNED), lines(sec, "extra", DEF_EXTRA));
        }

        private static List<String> lines(ConfigurationSection sec, String key, List<String> dflt) {
            if (!sec.contains(key)) return dflt;
            if (sec.isString(key)) return List.of(sec.getString(key, ""));
            return sec.getStringList(key);
        }
    }

    /** How a machine performs a pull: a capsule that parks in a tray, or reels that spin and pay out at once. */
    public enum Style { CAPSULE, REELS, CLAW }

    private final String series, title;
    private final Style style;
    private final double priceMoney;
    private final int priceCoins; private final String coinTier;
    private final Material priceItem; private final int priceAmount;
    private final List<Pity> pity;
    private final Show show;
    private final Sfx sfx;
    private final Lore lore;
    private final int multi;
    private final int openAfter;
    private final String broadcastMin; private final double broadcastRadius;
    private final List<String> hidden;
    private final ClawSpec claw;      // gacha.claw — set when style: claw
    private final String seedCrate;   // gacha.seed_crate — an ExcellentCrates crate id whose rewards fill the series once
    private final Map<String, Rarity> rarities;

    private GachaSpec(String series, String title, Style style, double priceMoney, int priceCoins, String coinTier, Material priceItem, int priceAmount, List<Pity> pity, Show show, Sfx sfx, Lore lore,
                      int multi, int openAfter, String broadcastMin, double broadcastRadius, List<String> hidden, Map<String, Rarity> rarities, String seedCrate, ClawSpec claw) {
        this.seedCrate = seedCrate; this.claw = claw;
        this.series = series; this.title = title; this.style = style; this.priceMoney = priceMoney; this.priceCoins = priceCoins; this.coinTier = coinTier; this.priceItem = priceItem; this.priceAmount = priceAmount;
        this.pity = pity; this.show = show; this.sfx = sfx; this.lore = lore; this.multi = multi; this.openAfter = openAfter;
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
        // pity: one rule (a map), a ladder (a list of them), or `false` — the guarantees switched off
        // entirely. Both shapes below read empty out of a boolean, so `pity: false` needs no special case.
        List<Pity> pity = new ArrayList<>();
        ConfigurationSection pitySec = sec.getConfigurationSection("pity");
        if (pitySec != null && pitySec.isString("rarity")) pity.add(new Pity(pitySec.getString("rarity"), pitySec.getInt("every", 0)));
        for (Map<?, ?> m : sec.getMapList("pity")) {
            Object r = m.get("rarity"); Object n = m.get("every");
            if (r == null || !(n instanceof Number num)) continue;
            pity.add(new Pity(String.valueOf(r), num.intValue()));
        }
        pity.removeIf(r -> r.every() <= 0 || r.rarity() == null || r.rarity().isBlank());
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
        return new GachaSpec(series, title, style, money, coins, coinTier, item, amount, pity, Show.parse(sec.getConfigurationSection("show")), Sfx.parse(sec.getConfigurationSection("sfx")), Lore.parse(sec.getConfigurationSection("lore")), Math.max(0, sec.getInt("multi", 10)), Math.max(3, sec.getInt("open_after", 20)),
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
    /** What a prize's lore says in the player's menu. */
    public Lore lore() { return lore; }

    /** What this machine SOUNDS like — every event it rebinds. See {@link Sfx}. */
    public Sfx sfx() { return sfx; }

    /** What this machine tells a player about itself. */
    public Show show() { return show; }
    /** Every guarantee this machine carries, in file order. */
    public List<Pity> pity() { return pity; }
    /** The gentlest guarantee — what a one-line hint or an odds screen leads with. */
    public Pity softestPity() {
        Pity best = null;
        for (Pity r : pity) if (best == null || r.every() < best.every()) best = r;
        return best;
    }
    public String pityRarity() { Pity r = softestPity(); return r == null ? null : r.rarity(); }
    public int pityEvery() { Pity r = softestPity(); return r == null ? 0 : r.every(); }
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
