package dev.servereer.machineconstruct.gacha;

import dev.servereer.machineconstruct.core.DisplayContent;
import dev.servereer.machineconstruct.core.ItemContent;
import dev.servereer.machineconstruct.core.PacketDisplay;
import dev.servereer.machineconstruct.grinder.econ.EconomyBridge;
import dev.servereer.machineconstruct.gui.MenuSkin;
import dev.servereer.machineconstruct.machine.CuePlayer;
import dev.servereer.machineconstruct.machine.Machine;
import dev.servereer.machineconstruct.machine.MachineType;
import dev.servereer.machineconstruct.model.anim.Cue;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Runs capsule machines (ADR 0045): charges the pull, rolls rarity (with pity) then an entry, plays
 * the {@code pull} cue with the rolled capsule, parks the capsule in the flap for its puller, and on
 * open (right-click, or after {@code open_after} seconds) plays {@code open}, delivers the item,
 * records the collection and broadcasts rare drops. One series file per {@code gacha.series}.
 */
public final class GachaManager {

    /** One pull's outcome. */
    public record Result(GachaSeries.Entry entry, GachaSpec.Rarity rarity, boolean duplicate) { }

    /** A capsule sitting in a machine's flap, waiting for its puller. */
    public static final class Waiting {
        public final UUID player; public final String playerName; public final List<Result> results; public final long since;
        public boolean opening;
        Waiting(Player p, List<Result> results) { this.player = p.getUniqueId(); this.playerName = p.getName(); this.results = results; this.since = System.currentTimeMillis(); }
    }

    /** Hooks back into the manager / menus. */
    public interface Host {
        MachineType typeOf(Machine m);
        void showResults(Player p, Machine m, List<Result> results);
        void persist(Machine m);
        void rerender(Machine m);
    }
    public Host host() { return host; }

    private final Plugin plugin;
    private final CuePlayer cues;
    private final Supplier<EconomyBridge> economy;
    private final Host host;
    private final File dir;
    private final Map<String, GachaSeries> series = new HashMap<>();
    private final Map<Machine, Waiting> waiting = new HashMap<>();
    private final Map<Machine, Long> lastBroadcast = new HashMap<>();
    private final Map<Machine, List<PacketDisplay>> boards = new HashMap<>();   // the per-viewer "your luck" text parts
    private final Coins coins;
    /** The music library + audio core, for `sfx: { track: … }` — null until the engine wires them. */
    private dev.servereer.machineconstruct.music.TrackLibrary tracks;
    private dev.servereer.machineconstruct.audio.MusicAudio audio;
    public Coins coins() { return coins; }

    /** Let machines play real audio files as sound effects, not only vanilla keys. */
    public void useAudio(dev.servereer.machineconstruct.music.TrackLibrary tracks, dev.servereer.machineconstruct.audio.MusicAudio audio) {
        this.tracks = tracks; this.audio = audio;
    }

    /**
     * Make the noise a machine binds to {@code event}. {@code dflt} is what the engine would do if the file
     * says nothing — so every call site reads as "this is the sound, unless the machine disagrees".
     */
    public void sfx(Machine m, GachaSpec spec, String event, Location at, Sfx.Event dflt, Map<String, String> vars) {
        if (spec == null || at == null) return;
        Sfx.Event e = spec.sfx() == null ? dflt : spec.sfx().event(event, dflt);
        Sfx.play(plugin, at, e, vars, (track, layer) -> playTrack(track, at, layer.distance(), layer.volume()));
    }

    /** Convenience for the common case: one vanilla key at a machine's centre. */
    public void sfx(Machine m, GachaSpec spec, String event, String sound, double volume, double pitch) {
        sfx(m, spec, event, m.anchor().clone().add(0.5, 1.0, 0.5), Sfx.one(sound, volume, String.valueOf(pitch)), null);
    }

    /**
     * A track from the music library, played once at a point through PixelAudio. Decoding is ffmpeg work, so
     * the first play of a clip loads on a worker thread and starts a tick later; after that the library's
     * cache makes it immediate.
     */
    private void playTrack(String id, Location at, float distance, float volume) {
        if (tracks == null || audio == null || !audio.available() || at.getWorld() == null) return;
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            dev.servereer.machineconstruct.audio.MusicAudio.Clip clip = tracks.loadTrack(id);
            if (clip == null) return;
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                double[] pos = {0};
                audio.startLocational(at.getWorld(), at.getX(), at.getY(), at.getZ(), distance, () -> {
                    short[] out = new short[dev.servereer.machineconstruct.audio.MusicAudio.FRAME_SAMPLES];
                    if (!clip.fill(out, pos[0], 1.0)) return null;          // null ends the stream
                    pos[0] += out.length;
                    if (volume < 0.999f) for (int i = 0; i < out.length; i++) out[i] = (short) (out[i] * volume);
                    return out;
                }, null);
            });
        });
    }
    private final Random rng = new Random();

    public GachaManager(Plugin plugin, CuePlayer cues, Supplier<EconomyBridge> economy, Host host) {
        this.plugin = plugin; this.cues = cues; this.economy = economy; this.host = host;
        this.dir = new File(plugin.getDataFolder(), "gacha");
        this.coins = new Coins(plugin);
        plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 40L, 20L);
        plugin.getServer().getScheduler().runTaskTimer(plugin, this::tickBoards, 60L, 30L);
    }

    /**
     * The series a machine dispenses, loaded (and seeded) on first use. Plain get/put, NOT
     * computeIfAbsent: seeding puts the series into the map and calls back into this method
     * (importCrate → series), and a mapping function that touches its own map makes
     * HashMap.computeIfAbsent throw ConcurrentModificationException — which used to escape through
     * onRender → renderMachine and leave the machine invisible until a /mc reload.
     */
    public GachaSeries series(GachaSpec spec) {
        GachaSeries have = series.get(spec.series());
        if (have != null) return have;
        GachaSeries s = new GachaSeries(dir, spec.series(), spec.title());
        s.load();
        series.put(spec.series(), s);
        String seed = spec.seedCrate();
        if (seed != null && !seed.isBlank() && !s.seeded().contains("crate:" + seed)) {   // fill from the crate the machine names — once per crate, even if the series already has pieces
            s.seeded().add("crate:" + seed);
            try {
                plugin.getLogger().info("[MachineConstruct] capsule series '" + s.key() + "' — seeding from ExcellentCrates crate '" + seed + "': "
                        + importCrate(spec, seed).replaceAll("§.", ""));
            } catch (Throwable ex) {   // a bad crate file must never stop the machine from rendering
                plugin.getLogger().warning("[MachineConstruct] seeding '" + s.key() + "' from crate '" + seed + "' failed: " + ex);
            }
            s.save();
        }
        return s;
    }
    public GachaSeries seriesByKey(String key) { return series.get(key); }
    public java.util.Collection<GachaSeries> allSeries() { return series.values(); }

    /** Take one play's price — the claw charges up front and may then pay nothing. */
    boolean chargeFor(Player p, MachineType t) { return charge(p, t.gacha(), t.skin(), 1); }

    /** One roll of the series (rarity with pity, then a weighted entry), or null when nothing is loaded. */
    GachaManager.Result rollFor(GachaSpec spec, GachaSeries s, GachaSeries.Record rec) { return roll(spec, s, rec); }

    /** Hand over a single already-rolled prize: collection, duplicates, commands, broadcast — the usual path. */
    void deliverOne(Machine m, MachineType t, Player p, Result r) {
        Waiting w = new Waiting(p, new ArrayList<>(List.of(r)));
        deliver(m, t, w);
    }

    /** Re-read every series file (after Dev's Diary / hand edits). */
    public int reload() { for (GachaSeries s : series.values()) s.load(); return series.size(); }

    public Waiting waiting(Machine m) { return waiting.get(m); }

    private final Map<UUID, Long> lastHint = new HashMap<>();

    /** A right-click with nothing to open: tell the player how the machine works (throttled). */
    public void hint(Player p, Machine m, MachineType t) {
        long now = System.currentTimeMillis();
        Long last = lastHint.get(p.getUniqueId());
        if (last != null && now - last < 3000) return;
        lastHint.put(p.getUniqueId(), now);
        GachaSpec spec = t.gacha();
        StringBuilder odds = new StringBuilder();
        for (String rn : spec.rarityOrder()) {
            GachaSpec.Rarity r = spec.rarity(rn);
            if (odds.length() > 0) odds.append(" <dark_gray>· ");
            odds.append("<").append(r.color()).append(">").append(r.label()).append(" ").append(String.format(java.util.Locale.ROOT, "%.0f", spec.percent(rn))).append("%");
        }
        GachaSeries.Record rec = series(spec).record(p.getUniqueId());
        String pity = spec.pity().isEmpty() || !spec.show().pity() ? "" : " <dark_gray>· <gray>guaranteed: " + pityLine(spec, rec);
        p.sendMessage(msg(t.skin(), "hint", "<gray>Turn the dial — <gold>{price}</gold> a capsule. {odds}{pity}",
                MenuSkin.vars("price", priceText(spec, 1), "odds", odds.toString(), "pity", pity, "series", spec.title())));
    }

    /** Admin: switch the machine to the next theme (block palette), persist and re-render. */
    public String cycleTheme(Machine m, MachineType t) {
        List<String> names = new ArrayList<>(t.panelThemeModels().keySet());
        if (names.isEmpty()) return null;
        String cur = m.panel() != null && m.panel().theme() != null ? m.panel().theme() : t.panelTheme();
        String next = names.get((Math.max(0, names.indexOf(cur)) + 1) % names.size());
        if (m.panel() == null) m.setPanel(new dev.servereer.machineconstruct.machine.PanelData());
        m.panel().setTheme(next);
        host.persist(m); host.rerender(m);
        return next;
    }
    public boolean busy(Machine m) { return cues.playing(m) || waiting.containsKey(m); }

    /** After render: blank the cue-driven parts (capsule, halves, prize) and pin them so nothing else touches them. */
    public void onRender(Machine m, MachineType t) {
        GachaSpec spec = t.gacha();
        if (spec == null) return;
        series(spec);   // load (or seed) the series as soon as a machine of it stands in the world
        for (String sel : spec.hidden()) for (PacketDisplay d : CuePlayer.select(m, sel)) { d.forceContent(CuePlayer.blank(d)); d.pin(); }
        List<PacketDisplay> board = CuePlayer.select(m, "pity|pity_*");
        if (board.isEmpty()) boards.remove(m);
        else {
            boards.put(m, board);
            GachaSeries s = series(spec);
            for (PacketDisplay d : board)
                if (d.baseContent() instanceof dev.servereer.machineconstruct.core.TextContent tc)
                    d.forceContent(tc.withText(fill(tc.template(), spec, s, new GachaSeries.Record(), "")));
        }
        Waiting w = waiting.remove(m);
        if (w != null) deliver(m, t, w);   // a re-render (theme / reload) eats the parked capsule — hand its items over rather than lose them
    }

    public void forget(Machine m) { waiting.remove(m); boards.remove(m); cues.forget(m); }

    // --- pull ----------------------------------------------------------------------

    /** Pull {@code count} capsules for {@code p} on {@code m}. Returns false (with a message) when it can't. */
    public boolean pull(Player p, Machine m, MachineType t, int count) {
        GachaSpec spec = t.gacha();
        if (spec == null) return false;
        MenuSkin sk = t.skin();
        Waiting w = waiting.get(m);
        if (w != null) {
            if (w.player.equals(p.getUniqueId())) p.sendMessage(msg(sk, "open_first", "<yellow>Open your capsule first — right-click the flap."));
            else p.sendMessage(msg(sk, "someone_waiting", "<yellow>{player}'s capsule is still in the flap.", MenuSkin.vars("player", w.playerName)));
            return false;
        }
        if (cues.playing(m)) { p.sendMessage(msg(sk, "busy", "<yellow>The machine is still turning…")); return false; }
        GachaSeries s = series(spec);
        if (s.loot().isEmpty()) { p.sendMessage(msg(sk, "empty", "<red>This machine has nothing loaded yet.")); return false; }
        count = Math.max(1, count);
        Location centre = m.anchor().clone().add(0.5, 1.0, 0.5);
        if (!charge(p, spec, sk, count)) {
            sfx(m, spec, "denied", centre, Sfx.one("block.note_block.bass", 0.7, "0.6"), null);
            return false;
        }
        sfx(m, spec, "insert", centre, Sfx.one("block.amethyst_block.chime", 0.6, "1.4~1.7"), null);
        List<Result> results = new ArrayList<>();
        GachaSeries.Record rec = s.record(p.getUniqueId());
        for (int i = 0; i < count; i++) {
            Result r = roll(spec, s, rec);
            if (r == null) break;
            results.add(r);
        }
        if (results.isEmpty()) { refund(p, spec, count); p.sendMessage(msg(sk, "empty", "<red>This machine has nothing loaded yet.")); return false; }
        s.save();
        refreshBoard(m);   // the counters just moved — let the board say so while the cue plays
        Waiting nw = new Waiting(p, results);
        waiting.put(m, nw);
        Result first = results.get(0);
        Cue pullCue = t.cues().get("pull");
        Map<String, String> vars = vars(p, spec, first);
        vars.put("count", String.valueOf(results.size()));
        if (spec.isReels()) {
            // a slot machine pays out at the end of its own spin — nothing to park, nothing to click
            cues.play(m, pullCue, vars, p, () -> open(m, t, nw, p));
        } else {
            cues.play(m, pullCue, vars, p, () -> {
                sfx(m, spec, "land", centre, Sfx.one("block.copper_bulb.turn_on", 0.7, "1.2"), vars);
                if (waiting.get(m) == nw) p.sendMessage(msg(sk, "landed", spec.show().rarity()
                        ? "<aqua>A <white>{rarity_label}</white> capsule landed — right-click the flap to open it."
                        : "<aqua>A capsule landed — right-click the flap to open it.", vars));
            });
        }
        return true;
    }

    /** Right-click on the machine while a capsule waits: the puller opens it. Returns true if handled. */
    public boolean tryOpen(Player p, Machine m, MachineType t) {
        if (t.gacha() != null && t.gacha().isReels()) return cues.playing(m);   // reels open themselves; a click mid-spin does nothing
        Waiting w = waiting.get(m);
        if (w == null || w.opening) return w != null;   // someone else's capsule sits there: nothing opens, no GUI either

        if (!w.player.equals(p.getUniqueId()) && !p.hasPermission("machineconstruct.admin")) {
            p.sendMessage(msg(t.skin(), "not_yours", "<yellow>That capsule is {player}'s.", MenuSkin.vars("player", w.playerName)));
            return true;
        }
        if (cues.playing(m)) return true;   // still sliding
        open(m, t, w, p);
        return true;
    }

    private void open(Machine m, MachineType t, Waiting w, Player opener) {
        w.opening = true;
        GachaSpec spec = t.gacha();
        Result first = w.results.get(0);
        Map<String, String> vars = vars(opener, spec, first);
        Map<String, DisplayContent> objects = new HashMap<>();
        objects.put("prize", new ItemContent(first.entry().shown(), ItemContent.context("fixed")));
        sfx(m, spec, "open", m.anchor().clone().add(0.5, 1.0, 0.5),
                Sfx.one("block.barrel.open", 0.7, "1.3"), vars);
        Cue reveal = t.cues().get("reveal");
        cues.play(m, reveal != null ? reveal : t.cues().get("open"), vars, objects, opener, () -> deliver(m, t, w));
    }

    private void deliver(Machine m, MachineType t, Waiting w) {
        if (waiting.get(m) == w) waiting.remove(m);
        GachaSpec spec = t.gacha();
        GachaSeries s = series(spec);
        MenuSkin sk = t.skin();
        Player p = plugin.getServer().getPlayer(w.player);
        GachaSeries.Record rec = s.record(w.player);
        Location drop = m.anchor().clone().add(0.5, 0.6, 0.5);
        Result best = null;
        for (Result r : w.results) {
            GachaSeries.Entry e = r.entry();
            boolean dup = e.once && rec.owned.contains(e.id);
            if (dup && s.duplicateMoney() > 0 && economy.get().available() && p != null) {
                economy.get().deposit(p, s.duplicateMoney());
                p.sendMessage(msg(sk, "duplicate", "<gray>Already in your collection — <gold>${money}</gold> instead.", MenuSkin.vars("money", fmt(s.duplicateMoney()), "name", e.name)));
            } else if (e.isCommand()) {
                for (String c : e.commands) {
                    String cmd = c.replace("{player}", w.playerName).replace("%player_name%", w.playerName).replace("%player%", w.playerName);
                    if (cmd.startsWith("/")) cmd = cmd.substring(1);
                    try { plugin.getServer().dispatchCommand(plugin.getServer().getConsoleSender(), cmd); }
                    catch (Throwable ex) { plugin.getLogger().warning("[MachineConstruct] capsule reward command failed: " + cmd + " — " + ex); }
                }
            } else give(p, e.item(), drop);
            if (!rec.owned.contains(e.id)) rec.owned.add(e.id);
            rec.counts.merge(e.id, 1, Integer::sum);
            if (best == null || spec.rank(r.rarity().name()) > spec.rank(best.rarity().name())) best = r;
        }
        s.save();
        if (p != null) {
            if (w.results.size() == 1) {
                Result r = w.results.get(0);
                p.sendMessage(msg(sk, "got", spec.show().rarity()
                        ? "<aqua>✦ <{color}>{rarity_label}</{color}> — <white>{name}</white>"
                        : "<aqua>✦ <white>{name}</white>", vars(p, spec, r)));
            } else host.showResults(p, m, w.results);
        }
        if (best != null) {
            Map<String, String> bv = vars(p, spec, best);
            sfx(m, spec, "reveal", m.anchor().clone().add(0.5, 1.0, 0.5),
                    Sfx.one("entity.experience_orb.pickup", 0.8, "{pitch}"), bv);
            if (spec.broadcastMin() != null && spec.rank(best.rarity().name()) >= spec.rank(spec.broadcastMin())) {
                sfx(m, spec, "jackpot", m.anchor().clone().add(0.5, 1.0, 0.5),
                        Sfx.one("ui.toast.challenge_complete", 0.9, "1"), bv);
                broadcast(m, t, w, best);
            }
        }
    }

    private void give(Player p, ItemStack item, Location drop) {
        if (item == null) return;
        if (p != null && p.isOnline()) {
            Map<Integer, ItemStack> left = p.getInventory().addItem(item);
            for (ItemStack rest : left.values()) p.getWorld().dropItemNaturally(p.getLocation(), rest);
        } else drop.getWorld().dropItemNaturally(drop, item);
    }

    private void broadcast(Machine m, MachineType t, Waiting w, Result best) {
        long now = System.currentTimeMillis();
        Long last = lastBroadcast.get(m);
        if (last != null && now - last < 2000) return;
        lastBroadcast.put(m, now);
        GachaSpec spec = t.gacha();
        Component line = msg(t.skin(), "broadcast", "<gray>{player} pulled <{color}>✦ {name}</{color}> <gray>from <white>{series}</white>!",
                vars(null, spec, best, w.playerName));
        Location a = m.anchor();
        double r2 = spec.broadcastRadius() * spec.broadcastRadius();
        for (Player o : plugin.getServer().getOnlinePlayers())
            if (o.getWorld() == a.getWorld() && o.getLocation().distanceSquared(a) <= r2 && !o.getUniqueId().equals(w.player)) o.sendMessage(line);
    }

    /**
     * The "your luck" board: a text part named {@code pity} on a gacha machine reads every nearby player
     * THEIR own guarantees — how many pulls until each tier is owed them. Per-viewer packets, so two
     * players stood at the same machine see different numbers, and nobody has to open a menu to find
     * out that a Mythic is four pulls away.
     */
    private void tickBoards() { for (Machine m : boards.keySet().toArray(new Machine[0])) refreshBoard(m); }

    /** Re-read the board on one machine for everyone stood in front of it. */
    void refreshBoard(Machine m) {
        MachineType t = host.typeOf(m);
        GachaSpec spec = t == null ? null : t.gacha();
        if (spec == null) { boards.remove(m); return; }
        List<PacketDisplay> parts = boards.get(m);
        if (parts == null || parts.isEmpty()) { boards.remove(m); return; }
        GachaSeries s = series(spec);
        for (PacketDisplay d : parts) {
            if (!(d.baseContent() instanceof dev.servereer.machineconstruct.core.TextContent tc)) continue;
            cues.tracker().forEachViewer(d, v -> d.sendTextFor(v, tc.withText(fill(tc.template(), spec, s, s.record(v.getUniqueId()), v.getName()))));
        }
    }

    /** The board's own placeholders. A fresh Record reads as "nobody has played yet", which is what a passer-by should see. */
    private String fill(String template, GachaSpec spec, GachaSeries s, GachaSeries.Record rec, String who) {
        int own = 0;
        for (GachaSeries.Entry e : s.loot()) if (rec.owned.contains(e.id)) own++;
        ClawSpec c = spec.claw();
        String grabs = c == null || c.pityGrabs() <= 0 ? "-" : String.valueOf(Math.max(1, c.pityGrabs() - rec.sinceGrab));
        return template
                .replace("{pity}", !spec.show().pity() ? "" : spec.pity().isEmpty() ? "<dark_gray>no guarantees here" : pityLine(spec, rec))
                .replace("{grabs}", grabs)
                .replace("{player}", who)
                .replace("{pulls}", String.valueOf(rec.pulls))
                .replace("{owned}", String.valueOf(own))
                .replace("{total}", String.valueOf(s.loot().size()));
    }

    /** Auto-open parked capsules after {@code open_after} seconds. */
    private void tick() {
        if (waiting.isEmpty()) return;
        long now = System.currentTimeMillis();
        for (Map.Entry<Machine, Waiting> e : new ArrayList<>(waiting.entrySet())) {
            Waiting w = e.getValue();
            MachineType t = host.typeOf(e.getKey());
            if (t == null || t.gacha() == null) { waiting.remove(e.getKey()); continue; }
            if (!w.opening && !cues.playing(e.getKey()) && now - w.since > t.gacha().openAfter() * 1000L) {
                Player p = plugin.getServer().getPlayer(w.player);
                open(e.getKey(), t, w, p);
            }
        }
    }

    // --- roll ----------------------------------------------------------------------

    private Result roll(GachaSpec spec, GachaSeries s, GachaSeries.Record rec) {
        List<String> order = spec.rarityOrder();
        if (order.isEmpty()) return null;
        double total = 0; for (GachaSpec.Rarity r : spec.rarities().values()) total += Math.max(0, r.weight());
        String pick = order.get(0);
        double x = rng.nextDouble() * total;
        for (GachaSpec.Rarity r : spec.rarities().values()) { x -= Math.max(0, r.weight()); if (x <= 0) { pick = r.name(); break; } }
        // Guarantees: a machine may carry several ("rare every 10, mythic every 100"). Each keeps its own
        // counter; when one comes due it forces the roll up, and the steepest tier due wins.
        for (GachaSpec.Pity rule : spec.pity()) {
            if (!spec.rarities().containsKey(rule.rarity())) continue;
            if (counter(rec, spec, rule) + 1 < rule.every()) continue;
            if (spec.rank(pick) < spec.rank(rule.rarity())) pick = rule.rarity();
        }
        GachaSeries.Entry e = s.roll(pick);
        if (e == null) {   // nothing loaded at that rarity: step down, then up
            int i = spec.rank(pick);
            for (int d = i - 1; d >= 0 && e == null; d--) e = s.roll(order.get(d));
            for (int u = i + 1; u < order.size() && e == null; u++) e = s.roll(order.get(u));
            if (e == null) return null;
        }
        rec.pulls++;
        // a pull resets every guarantee at or below the tier it landed on, and ticks the rest up
        for (GachaSpec.Pity rule : spec.pity()) {
            if (spec.rank(e.rarity) >= spec.rank(rule.rarity())) rec.since.put(rule.rarity(), 0);
            else rec.since.put(rule.rarity(), counter(rec, spec, rule) + 1);
        }
        GachaSpec.Pity soft = spec.softestPity();
        rec.sinceRare = soft == null ? 0 : counter(rec, spec, soft);   // kept so older builds still read the file
        return new Result(e, spec.rarity(e.rarity), e.once && rec.owned.contains(e.id));
    }

    // --- coins ---------------------------------------------------------------------

    private static final String DEFAULT_COIN_TEXTURE = "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvYjBhN2I5NGM0ZTU4MWI2OTkxNTlkNDg4NDZlYzA5MTM5MjUwNjIzN2M4OWE5N2M5MzI0OGEwZDhhYmM5MTZkNSJ9fX0=";
    private org.bukkit.NamespacedKey coinKey;

    /** The engine's default token — a gold-coin head tagged so nothing else matches it. */
    public ItemStack defaultCoin() {
        if (coinKey == null) coinKey = new org.bukkit.NamespacedKey(plugin, "coin");
        ItemStack it = dev.servereer.machineconstruct.core.Heads.create(DEFAULT_COIN_TEXTURE);
        org.bukkit.inventory.meta.ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.displayName(MenuSkin.mini("<!italic><gold>Capsule Token"));
            meta.lore(MenuSkin.miniList(List.of("<!italic><gray>Feeds a capsule machine.", "<!italic><dark_gray>Turn the dial to spend it.")));
            meta.getPersistentDataContainer().set(coinKey, org.bukkit.persistence.PersistentDataType.STRING, "default");
            it.setItemMeta(meta);
        }
        return it;
    }

    /** The coin a machine takes: its series' own (set from a held item), else the ladder tier the machine names. */
    public ItemStack coinOf(GachaSpec spec) { ItemStack c = series(spec).coin(); return c != null ? c : coins.item(spec.coinTier()); }
    /** A series' own coin, else the ladder tier of the first machine dispensing it, else the common coin. */
    public ItemStack coinOf(GachaSeries s) {
        ItemStack c = s.coin(); if (c != null) return c;
        for (Machine m : new ArrayList<>(machinesOf(s))) { MachineType t = host.typeOf(m); if (t != null && t.gacha() != null) return coins.item(t.gacha().coinTier()); }
        return coins.item("common");
    }
    private java.util.List<Machine> machinesOf(GachaSeries s) {
        java.util.List<Machine> out = new ArrayList<>();
        for (Machine m : waiting.keySet()) { MachineType t = host.typeOf(m); if (t != null && t.gacha() != null && t.gacha().series().equals(s.key())) out.add(m); }
        return out;
    }
    /** A coin by ladder tier key, or a series' coin by series key. */
    public ItemStack coinByKey(String key) {
        if (coins.tier(key) != null) return coins.item(key);
        GachaSeries s = seriesByKey(key);
        return s == null ? null : coinOf(s);
    }

    /** Is {@code it} the same coin as {@code coin}? Ladder coins by their tag (heads made at different times differ in profile id), series coins by similarity. */
    private boolean sameCoin(ItemStack it, ItemStack coin) {
        if (it == null || it.getType() != coin.getType()) return false;
        String tier = coins.tierOf(coin);
        if (tier != null) return tier.equals(coins.tierOf(it));
        return it.isSimilar(coin);
    }
    private int countSimilar(Player p, ItemStack coin) {
        int n = 0;
        for (ItemStack it : p.getInventory().getContents()) if (sameCoin(it, coin)) n += it.getAmount();
        return n;
    }
    private void removeSimilar(Player p, ItemStack coin, int amount) {
        ItemStack[] inv = p.getInventory().getContents();
        for (int i = 0; i < inv.length && amount > 0; i++) {
            ItemStack it = inv[i];
            if (!sameCoin(it, coin)) continue;
            int take = Math.min(amount, it.getAmount());
            it.setAmount(it.getAmount() - take); amount -= take;
            if (it.getAmount() <= 0) p.getInventory().setItem(i, null);
        }
    }

    /** Hand a player coins of a series (or the common coin when {@code s} is null). */
    public void giveCoins(Player p, int amount, GachaSeries s) { giveCoinItem(p, amount, s == null ? coins.item("common") : coinOf(s)); }

    public void giveCoinItem(Player p, int amount, ItemStack coin) {
        int left = Math.max(1, amount);
        while (left > 0) {
            ItemStack stack = coin.clone(); int n = Math.min(left, Math.max(1, coin.getMaxStackSize())); stack.setAmount(n); left -= n;
            for (ItemStack rest : p.getInventory().addItem(stack).values()) p.getWorld().dropItemNaturally(p.getLocation(), rest);
        }
    }

    /** How many pulls this player has gone without that tier — migrating the old single counter once. */
    public static int counter(GachaSeries.Record rec, GachaSpec spec, GachaSpec.Pity rule) {
        Integer v = rec.since.get(rule.rarity());
        if (v != null) return v;
        GachaSpec.Pity soft = spec.softestPity();
        int seed = soft != null && soft.rarity().equals(rule.rarity()) ? rec.sinceRare : 0;
        rec.since.put(rule.rarity(), seed);
        return seed;
    }

    /** "Rare in 3 · Mythic in 64" — what every guarantee owes this player right now. */
    public String pityLine(GachaSpec spec, GachaSeries.Record rec) {
        StringBuilder sb = new StringBuilder();
        for (GachaSpec.Pity rule : spec.pity()) {
            GachaSpec.Rarity ra = spec.rarity(rule.rarity());
            if (ra == null) continue;
            if (sb.length() > 0) sb.append(" <dark_gray>· ");
            sb.append("<").append(ra.color()).append(">").append(ra.label()).append(" <white>in ")
              .append(Math.max(1, rule.every() - counter(rec, spec, rule)));
        }
        return sb.toString();
    }

    // --- price ---------------------------------------------------------------------

    private boolean charge(Player p, GachaSpec spec, MenuSkin sk, int count) {
        double money = spec.priceMoney() * count;
        if (money > 0) {
            EconomyBridge eco = economy.get();
            if (!eco.available()) { p.sendMessage(msg(sk, "no_economy", "<red>No economy plugin — the machine can't take coins.")); return false; }
            if (!eco.has(p, money)) { p.sendMessage(msg(sk, "cant_afford", "<red>You need <gold>${price}</gold> for that.", MenuSkin.vars("price", fmt(money)))); return false; }
        }
        ItemStack coin = spec.priceCoins() > 0 ? coinOf(spec) : null;
        if (coin != null) {
            int need = spec.priceCoins() * count;
            if (countSimilar(p, coin) < need) {
                p.sendMessage(msg(sk, "cant_afford_coin", "<red>You need <white>{amount}× {coin}</white> for that.", MenuSkin.vars("amount", String.valueOf(need), "coin", GachaSeries.displayName(coin))));
                return false;
            }
        }
        if (spec.priceItem() != null) {
            int need = spec.priceAmount() * count;
            if (!p.getInventory().containsAtLeast(new ItemStack(spec.priceItem()), need)) {
                p.sendMessage(msg(sk, "cant_afford_item", "<red>You need <white>{amount}× {item}</white> for that.", MenuSkin.vars("amount", String.valueOf(need), "item", pretty(spec.priceItem()))));
                return false;
            }
        }
        if (money > 0 && !economy.get().withdraw(p, money)) return false;
        if (coin != null) removeSimilar(p, coin, spec.priceCoins() * count);
        if (spec.priceItem() != null) p.getInventory().removeItem(new ItemStack(spec.priceItem(), spec.priceAmount() * count));
        return true;
    }

    private void refund(Player p, GachaSpec spec, int count) {
        if (spec.priceMoney() > 0 && economy.get().available()) economy.get().deposit(p, spec.priceMoney() * count);
        if (spec.priceCoins() > 0) giveCoins(p, spec.priceCoins() * count, series(spec));
        if (spec.priceItem() != null) p.getInventory().addItem(new ItemStack(spec.priceItem(), spec.priceAmount() * count));
    }

    /** "1× Capsule Token" / "$250" — what a pull costs, for menus and the hint. */
    public String priceText(GachaSpec spec, int count) {
        List<String> parts = new ArrayList<>();
        if (spec.priceCoins() > 0) parts.add((spec.priceCoins() * count) + "× " + GachaSeries.displayName(coinOf(spec)));
        if (spec.priceMoney() > 0) parts.add("$" + fmt(spec.priceMoney() * count));
        if (spec.priceItem() != null) parts.add((spec.priceAmount() * count) + "× " + pretty(spec.priceItem()));
        return parts.isEmpty() ? "free" : String.join(" + ", parts);
    }

    // --- import from ExcellentCrates -----------------------------------------------

    /**
     * Copy an ExcellentCrates crate's rewards into a series: ITEM rewards become item entries (their
     * SNBT re-built through the item factory), COMMAND rewards become command entries; the crate's
     * rarity name and weight carry over (an unknown rarity name lands on the lowest tier). Existing
     * entries with the same name are replaced. Returns a summary line.
     */
    public String importCrate(GachaSpec spec, String crateId) {
        File f = new File(new File(plugin.getDataFolder().getParentFile(), "ExcellentCrates"), "crates/" + crateId + ".yml");
        if (!f.exists()) return "§cNo crate file §f" + f.getName() + "§c in plugins/ExcellentCrates/crates/.";
        org.bukkit.configuration.file.YamlConfiguration y = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(f);
        org.bukkit.configuration.ConfigurationSection list = y.getConfigurationSection("Rewards.List");
        if (list == null) return "§cThat crate has no Rewards.List.";
        GachaSeries s = series(spec);
        int items = 0, cmds = 0, skipped = 0;
        for (String rid : list.getKeys(false)) {
            org.bukkit.configuration.ConfigurationSection r = list.getConfigurationSection(rid);
            if (r == null) continue;
            String type = r.getString("Type", "ITEM").toUpperCase();
            double weight = r.getDouble("Weight", 1);
            String rarity = r.getString("Rarity", "common").toLowerCase();
            if (!spec.rarities().containsKey(rarity)) rarity = spec.rarityOrder().isEmpty() ? "common" : spec.rarityOrder().get(0);
            GachaSeries.Entry old = s.byName(rid);
            if (old != null) s.removeNoSave(old.id);
            if (type.equals("COMMAND")) {
                List<String> cs = new ArrayList<>();
                for (String c : r.getStringList("Commands")) cs.add(c.replace("%player_name%", "{player}").replace("%player%", "{player}"));
                if (cs.isEmpty()) { skipped++; continue; }
                ItemStack icon = SnbtItems.parse(r.getString("PreviewData.Data.Value"));
                if (icon == null) icon = new ItemStack(cs.get(0).contains("eco give") || cs.get(0).contains("money") ? Material.SUNFLOWER : cs.get(0).contains("key") ? Material.TRIPWIRE_HOOK : Material.PAPER);
                GachaSeries.Entry e = s.addCommand(rid, rarity, cs, icon);
                e.weight = weight; cmds++;
            } else {
                org.bukkit.configuration.ConfigurationSection data = r.getConfigurationSection("ItemsData");
                boolean any = false;
                if (data != null) for (String k : data.getKeys(false)) {
                    ItemStack it = SnbtItems.parse(data.getString(k + ".Data.Value"));
                    if (it == null) continue;
                    GachaSeries.Entry e = s.add(it, rarity, data.getKeys(false).size() > 1 ? rid + "_" + k : rid);
                    e.weight = weight; any = true; items++;
                }
                if (!any) skipped++;
            }
        }
        s.save();
        return "§aImported §f" + items + "§a item + §f" + cmds + "§a command reward(s) from §f" + crateId + "§a into §f" + s.key() + (skipped > 0 ? " §7(" + skipped + " skipped)" : "") + "§a.";
    }

    // --- helpers -------------------------------------------------------------------

    private Map<String, String> vars(Player p, GachaSpec spec, Result r) { return vars(p, spec, r, p == null ? "" : p.getName()); }

    private Map<String, String> vars(Player p, GachaSpec spec, Result r, String playerName) {
        GachaSpec.Rarity ra = r.rarity();
        Map<String, String> v = new LinkedHashMap<>();
        String capsule = r.entry().capsule != null && !r.entry().capsule.isBlank() ? r.entry().capsule : ra.capsule();
        v.put("capsule", capsule); v.put("capsule_half", capsule);
        v.put("color", ra.color()); v.put("glow_color", ra.glow() ? ra.color() : "off");
        v.put("pitch", String.valueOf(ra.pitch())); v.put("sound", ra.sound() == null ? "" : ra.sound()); v.put("fx", ra.fx() == null ? "" : ra.fx());
        v.put("rarity", ra.name()); v.put("rarity_label", ra.label());
        v.put("name", r.entry().name); v.put("player", playerName); v.put("series", spec.title());
        // Slot reels: what the three drums land on. Three of a kind is the jackpot picture, so only a
        // genuinely rare tier gets it — otherwise every single pull looks like a win and the drums stop
        // meaning anything. The tier's own odds decide the picture:
        //   ≤6% of pulls  → three of a kind
        //   ≤20%          → a near miss: two of the tier's symbol and one stranger
        //   commoner      → three different symbols, the tier's among them
        String sym = ra.reelSymbol();
        v.put("sym", sym);
        List<String> others = new ArrayList<>();
        for (String rn : spec.rarityOrder()) {
            String o = spec.rarity(rn).reelSymbol();
            if (o != null && !o.isBlank() && !o.equals(sym) && !others.contains(o)) others.add(o);
        }
        java.util.Collections.shuffle(others, rng);
        double pct = spec.percent(ra.name());
        String a = sym, b = sym, c = sym;
        if (others.size() >= 2 && pct > 20) {          // common: no two alike
            b = others.get(0); c = others.get(1);
            if (rng.nextBoolean()) { String t = a; a = b; b = t; }   // the tier's symbol is not always first
        } else if (!others.isEmpty() && pct > 6) {     // uncommon: a near miss on the last drum
            c = others.get(0);
            if (rng.nextInt(3) == 0) { String t = b; b = c; c = t; }  // sometimes it is the middle one that breaks it
        }
        v.put("sym1", a); v.put("sym2", b); v.put("sym3", c);
        return v;
    }

    static Component msg(MenuSkin sk, String key, String def) { return msg(sk, key, def, null); }
    static Component msg(MenuSkin sk, String key, String def, Map<String, String> vars) {
        String over = sk == null ? null : sk.msg(key, null, vars);
        String body = over != null ? over : MenuSkin.fill(def, vars);
        return MenuSkin.mini((sk == null ? "<gold>Capsules <dark_gray>» " : sk.prefix("<gold>Capsules <dark_gray>» ")) + body);
    }

    public static String fmt(double v) { return v == Math.floor(v) ? String.valueOf((long) v) : String.format(java.util.Locale.ROOT, "%.2f", v); }
    public static String pretty(Material m) { String n = m.name().toLowerCase().replace('_', ' '); return Character.toUpperCase(n.charAt(0)) + n.substring(1); }
}
