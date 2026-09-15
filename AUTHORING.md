# Authoring MachineConstruct machines

A machine is one YAML file under `Foundry/machines/<id>.yml`. The engine reads it;
`/mc reload` re-reads YAML edits live (model/gui/recipe changes). Changing engine
Java needs a server restart.

```yaml
id: my_machine            # defaults to the filename
anchor: chest             # the real block placed (chest/barrel/…); holds the sealed store
capacity: 0               # generator storage cap (0 = none); drives the `level` driver
vars:      { ... }        # reusable values / blocks / whole assemblies   (see below)
templates: { ... }        # OPTIONAL parameterised parts (most files don't need these)
model:     { parts: ... } # the visual (packet displays) — the bulk of a file
gui:       { ... }        # the sealed chest menu (title, layout, icons, progress)
fuel:      { ... }        # material → seconds of burn (omit = runs free)
recipes:   [ ... ]        # input → output (+time); empty input = a timed generator
tiers:     { ... }        # upgrade ladder I→II→III (+ per-tier vars/models)
grinder:   { ... }        # turns the machine into a Dimensional Grinder (see ADR 0008)
```

## The model — parts, transforms, parenting

Every visual is a **part**. A part is either a **block/head/item/text** (a leaf) or a
**group** (has `parts:`), or both.

```yaml
model:
  parts:
    base: { block: smooth_stone, offset: [0,0,0], scale: [1,0.3,1] }
```

- **`offset: [x,y,z]`** — block-local. A block fills the box `[offset, offset+scale]`,
  **corner-anchored**. To centre a part of width `W` on the axis, use `offset = -W/2`.
- **`scale: [x,y,z]`** (or a single number) — size in blocks.
- **`rotation: [yaw, pitch, roll]`** — degrees, i.e. **[Y, X, Z]**. Static pose.
  By default it pivots around the part's **corner**; add **`center: true`** to pivot
  around its **middle**.
- **`block:`** a material · **`head:`** a player-head skin (base64 / texture URL / name)
  · **`item:`** an item · **`text:`** a floating label (+ `billboard: center`).
- **`<input>` / `<output>`** as an `item:` value = the machine's live input/output item.

### Parenting (parent → child)

A part with `parts:` is a **group**. Its children are positioned **relative** to it,
and **inherit its offset, rotation, and animation**.

```yaml
gears:
  parts:
    left:                                 # PARENT — where it sits + how it spins
      offset: [-0.9, 1.05, 0.5]
      animation: { always: { spin: { axis: x, speed: 55 } } }
      parts:
        gear: { ... }                     # CHILD — moves & spins WITH left
```

Rule of thumb: **child = moves/turns with its parent.** Make something a child when it
belongs to that assembly; keep it a sibling when it should be independent.

### Animation (under a part's `animation:`)

Triggers: `always` (recommended), `idle`, `working`, `blocked`, `no_fuel`.

```yaml
animation:
  always:
    spin:     { axis: y, speed: 60 }                       # continuous rotation
    bob:      { axis: y, amplitude: 0.05, period: 2.0 }    # up/down float
    pulse:    { axis: all, amplitude: 0.1, hz: 0.8 }       # breathe (scale)
    shake:    { amplitude: 0.05, hz: 13 }                  # jitter
    swing:    { axis: x, amplitude: 20, period: 2.0 }      # rock back & forth
    fill:     { axis: y, from: 0.0, to: 1.0, driver: level }   # grow with level/progress/tier
    particle: { type: flame, offset: [0,0.2,0], spread: [0.1,0.1,0.1], count: 4, speed: 0.03, rate: 0.05 }
    sound:    { type: block.campfire.crackle, volume: 0.3, pitch: 1.0, rate: 0.7 }
```

## The authoring layer — stop repeating yourself

Four tools, all resolved at load time (so the built model has no placeholders).

### 1. `vars:` + `${name}` / `<name>` — define once, reference anywhere
A var can hold a **value**, a **block**, or a **whole multi-part assembly**.

```yaml
vars:
  metal:  cut_copper
  canister:                          # a whole assembly
    parts:
      glass: { block: glass, offset: [-0.45,0.3,-0.45], scale: [0.9,1.95,0.9] }
      band:  { block: "${metal}", offset: [-0.5,0.85,-0.5], scale: [1,0.12,1] }
model:
  parts:
    rim:      { block: "${metal}", ... }   # ${name}: value, inline or whole
    canister: "<canister>"                 # <name>: drop a whole part/assembly here
```
- `"${name}"` — substitutes the value; also works **inline** in text (`"Tier ${n}"`).
- `"<name>"` — same lookup, whole-value (a block, a list, or a whole assembly).
- Reserved live tokens `<input>` `<output>` `<percent>` are **not** vars.
- Var values may reference other vars (`tname: "${rule}❖ ${t_name}"`) — the table is
  resolved against itself (a few passes) before anything else reads it. Substitution is
  ONE pass everywhere else, so `${usd}{price}` with `usd: "$"` yields the literal `${price}`
  that the menu renderer later fills — that is the way to put a `$` in front of a placeholder.

### 2. `grid:` — stamp one part at many spots
```yaml
pillars: { block: "${dark}", scale: [0.5,1.7,0.5], grid: { x: [-1,1.5], y: 0.3, z: [-1,1.5] } }  # 2×2 = 4
pipes:   { block: "${metal}", scale: [...], grid: [ [-0.75,1,0.45], [1.1,1,0.45] ] }             # explicit list
```
`grid:` is either an **x/y/z product** (each axis a list or scalar) or a **list of offsets**.

### 3. `octagon: true` — auto 45° twin
Adds a second copy rotated 45° about Y, so an octagonal disc is authored once:
```yaml
ring: { block: dark_prismarine, offset: [-0.975,0,-0.975], scale: [1.95,0.16,1.95], octagon: true }
```

### 4. `tiers.vars:` — per-tier block swaps with no model duplication
The base model re-renders per tier with the tier's vars merged over the base:
```yaml
tiers:
  max: 3
  vars:
    2: { metal: gold_block, stone: polished_blackstone }
    3: { metal: netherite_block, stone: crying_obsidian }
```

### (Optional) `templates:` + `use:` / `with:`
A parameterised part. Most files don't need this — a `<name>` assembly + `grid:` usually
covers it. Use it only for "same shape, deep param differs":
```yaml
templates:
  gear: { animation: { always: { spin: { axis: x, speed: "${spin}" } } }, parts: { ... } }
model:
  parts:
    g1: { use: gear, offset: [...], with: { spin: 55 } }   # with: fills ${spin}
```
`use:` stamps the template; plain fields on the use-node override it; `with:` fills
`${blanks}` that aren't normal fields.

### 5. `skin:` — re-skin a code-rendered menu (grinder, chunk collector)
The grinder and chunk-collector menus are rendered in Java (no `gui:` layout), so their
look and wording come from a `skin:` section instead. **Every key is optional** — a missing
key falls back to the built-in text, so a machine with no `skin:` renders exactly as before,
and functions never change (a moved button carries its click with it).
```yaml
skin:
  prefix: "${node}${foam}Grinder ${sep}"          # chat brand (MiniMessage)
  buy_command: ""                                 # grinder only: blank = its own catalog view; default "spawnershop"
  titles:  { main: "…", pool: "…", log: "…" }     # window title per view
  fillers: { main: black_stained_glass_pane }     # border pane per view
  accents:                                        # panes painted over the filler, under the buttons
    main: [ { material: cyan_stained_glass_pane, slots: [0, 8, 45, 53] } ]
  slots:                                          # move any button; lists for grids/tabs
    main: { collect: 47, pool: 48, spawners: [20, 21, 22, 23, 24, 29, 30, 31, 32, 33] }
    content: { start: 9, end: 36 }                # the paginated area (exclusive end)
  buttons:                                        # material / name / lore / model per item
    collect: { material: hopper, name: "${foam}Haul In", lore: [ "${slate}…", "{items} items" ] }
  messages: { collected: "${glow}Hauled {items} items." }   # chat lines, incl. the engine's
```
Names, lore and messages take `{placeholders}` (`{items}`, `{xp}`, `{tier}`, `{mob}`, …);
the Java default for each key uses the same placeholders, so read `GrinderMenus` /
`ChunkCollectorMenus` (and `smsg(…)` calls in `MachineManager`) for the key names and
what each receives. Model-palette vars (`${glow}`, `${t_name}`) work here too.
Worked example: `templates/abyssal-market/configs/Foundry/machines/portable_grinder.yml`.
A machine file can also carry `enabled: false` at top level to park it without deleting it.

### 6. `facing: player` + `panel:` — signs, boards, anything with text stuck to it
- **`facing: player`** (top level) turns the whole model at placement so its authored front
  (−Z) points at the placing player, snapped to 45° (diagonals included). The yaw is stored in the machine's
  identity tag and survives restarts. `facing-yaw-offset` in the engine config.yml nudges every
  such machine if a server finds them turned.
- **`panel: { refresh: 30s }`** makes a display-only machine (no GUI; a right-click just
  refreshes it) whose text parts carrying `%placeholders%` are re-resolved through
  PlaceholderAPI on that interval — global context, no player — and pushed to viewers in range.
  Text without `%` is untouched; PAPI absent = the raw text stays.
- **Text on a face, not following the camera:** leave `billboard` at its default (fixed) and
  turn the part with `rotation: [180, 0, 0]` so it reads from the −Z front. A text display grows
  UP from its position (bottom-centre) and `scale` is also the text size, so position the box
  centre where the bottom of the text should sit. Multi-line text = a `|-` block scalar in a var.
- **Themes are whole block palettes.** `panel.themes: { name: { <var overrides…>, theme: "#hex" } }`
  — each entry overrides model vars exactly like `tiers.vars` and is pre-rendered as its own model
  (`<id>_theme_<name>`); `panel.theme` names the default. The `theme` key inside is the text
  colour that `{theme}` expands to. Admins switch a placed panel's theme in the editor (the machine
  re-renders); a typed hex only overrides the text colour.
- **Admin editing** (`machineconstruct.admin`): right-clicking a panel opens `PanelEditor` — every
  text part (by its YAML key) → lines; retype / add / delete a line in chat, reset a part or the
  panel, cycle or type the theme. Overrides persist with the machine (`panel:` in its store
  blob), so the file stays the default and a reset returns to it.
- **`button:` on any block part** makes it a physical button: it outline-glows (per viewer) for
  whoever aims at it within 5.5 blocks, sinks `depth` into its face when clicked (right or left
  click, with a click sound) and runs `action` — `state:next` · `state:prev` · `state:set:<name>` ·
  `refresh` · `command:<as the player>` · `console:<cmd>` · `message:<MiniMessage>`. `lit:` +
  `lit_when:` swap its block while that panel state is active. Its caption sinks with it: the
  part named by `label:` (default: `lbl_X` for a part named `btn_X`). `push:` picks WHICH WAY it
  sinks — `in` (default, into a front-facing panel) · `out` · `down` · `up` · `left` · `right`, or a
  model-space vector `[0, -1, 0]`. A button lying face-up on a console shelf wants `push: down`;
  without it the press reads as the button sliding sideways. Head themes work too — the
  ray-cast reads the part's anchoring (block = corner box, head/item = centred box). See `core/ButtonSpec`.
- **`panel.states`** — named var sets (`weekly: { period: topweek, label: "this week", btn: "WEEK" }`)
  substituted into every panel text as `{period}` / `{label}` / `{state}` / …; `default_state`
  picks the starting one. The current state is per placed panel (persisted, in panels.yml,
  switched by buttons, the Studio, or `/mc panels apply`).
- **`panel.paged: true` + `panel.pages`** — an INFO panel: instead of states it carries PAGES,
  each a map of *section* parts (`sec_1` … `sec_6`, any part named `sec_*`) → lines. Buttons run
  `page:next` / `page:prev` (clamped — no wrap) / `page:first` / `page:last` / `page:set:<n>`; the
  caption sinks with them like any button. `{page}` / `{pages}` resolve in every text (the `pager`
  part shows "2 / 3"). A section a page doesn't mention is blank on that page; non-section parts
  (title, pager) are the same on every page. `panel.pages` are the template's defaults; a placed
  panel can own its pages (in-game: the 📖 Page item — left/right turn, shift-left adds, shift-right
  deletes; sections are edited on the page showing — or Dev's Diary, or `panels.yml` `pages:` +
  `page:`). Sections are positioned per placed panel with the usual `layouts` (dx/dy/scale/align/
  width), so one slim board can be a notice, a rules list, two columns of lore…
- Sizes ship for the Abyssal theme, all from one generator:
  `templates/abyssal-market/tools/gen_panels.py` → leaderboards `panel_plaque`, `leaderboard_panel`,
  `panel_stele`, `panel_wide`, `panel_grand` — each also as `<id>_bare` (no plinth: the tablet
  alone on the ground, barrier anchor, sneak + left-click to pick up) — and SLIM paged info panels
  `info_panel` (2×1.4), `info_panel_wide` (3.4×1.4), `info_panel_tall` (1.6×2.6): 0.16 thick, flush
  with the back of their block (right-click a wall to hang one), BACK / NEXT buttons, six section
  slots. Edit the generator, not the files (Dev's Diary's Panels Studio is the same builder in C#).

### 7. `cues:` — one-shot scripted animations (ADR 0045)

`animation:` runs forever per state; a **cue** is a timeline played once on demand — a button's
`action: cue:<name>`, or a kind that fires them (the capsule machine's `pull` / `open`). Each step
has `at:` (seconds), a `part:` selector (`dial`, `a|b`, `pool_*`) and one or more of:

```yaml
cues:
  pull:
    - { at: 0.0, part: dial,     rotate: { axis: z, by: -120, over: 0.45, ease: out } }   # about its own centre (heads / items)
    - { at: 0.0, part: dial,     sound: { type: block.lever.click, pitch: 0.7 } }         # at the part (no part = at the anchor)
    - { at: 0.15, part: "pool_*", shake: { amplitude: 0.035, over: 0.7 } }
    - { at: 0.5, part: capsule,  show: true, content: "{capsule}",                        # show/hide; content = head texture | block | text | a {var}
                                  move: { from: [0.5, 1.3, 0.4], to: [0.86, 0.42, -0.33], over: 0.5, ease: in } }   # model space, anchor frame; `by:` = relative
    - { at: 1.45, part: capsule, glow: "{glow_color}" }                                   # outline colour for everyone ("off" clears)
    - { at: 1.5, particle: { type: "{fx}", count: 24, offset: [0.86, 0.45, -0.33], spread: [0.15, 0.15, 0.15], speed: 0.04 } }
```

Parts a cue touches are LOCKED for its duration (the animation loop can't fight it) and snap back to
rest when it ends unless the step says `keep: true`. `{vars}` come from whoever fires the cue.
`move`/`rotate` on one part in the same `at:` must sit in ONE step (two steps on the same part at
once overwrite each other). `rotate`/`spin` turn about the part's own centre — use them on heads /
items, not corner-anchored blocks.

### 8. `gacha:` — capsule machines and slot machines (ADR 0045 / 0046)

`capsule_trench.yml` is the reference: `gacha: { series, title, price: {money|item+amount}, pity:
{rarity, every}, multi, open_after, broadcast: {min_rarity, radius}, hidden: [parts blank until a cue
shows them], rarities: { name: { weight, label, capsule: <head>, color, glow, sound, pitch, fx } } }`,
a `button: { action: gacha:pull }` part, and the two cues `pull` / `open`. The engine fills
`{capsule} {capsule_half} {color} {glow_color} {pitch} {sound} {fx} {rarity} {rarity_label} {name}
{player} {series} {prize}`. **Prizes are real items** fed in-game (machine → Admin → hold the item →
Add; NBT/components kept bit-for-bit) into `plugins/MachineConstruct/gacha/<series>.yml`; name /
rarity / weight / `once` (collectible → duplicates pay `duplicate_money`) / per-piece capsule texture
are edited there, in the admin view, or in Dev's Diary → MachineConstruct → Capsules;
`/mc gacha reload` re-reads. Rarity is rolled first (pity forces the floor), the theatre plays after.
**Guarantees (`pity:`)** — one rule or a LADDER, on any coin machine:

```yaml
pity:                              # each rule keeps its own counter, per player per series
  - { rarity: rare,      every: 10 }
  - { rarity: epic,      every: 30 }
  - { rarity: legendary, every: 75 }
  - { rarity: mythic,    every: 150 }
```

A pull that comes up short is forced to the steepest tier that has come due; landing on a tier resets
every guarantee at or below it and ticks the rest up. The old single form (`pity: { rarity, every }`)
still works, and old files keep their counter (it migrates into the ladder on the next pull). The
odds screen lists what each guarantee owes that player right now.

**The luck board** — a text part named `pity` (or `pity_*`) on a gacha machine reads every player
stood in front of it THEIR own guarantees, without anyone opening a menu. It is per-viewer: two
players at the same cabinet see different numbers. Placeholders, resolved per viewer:

| | |
|---|---|
| `{pity}` | `Rare in 3 · Mythic in 64` — every guarantee, coloured by rarity |
| `{pulls}` | that player's total pulls on this series |
| `{owned}` / `{total}` | distinct prizes collected / in the series |
| `{grabs}` | claw only: plays left until the claw is made to hold |
| `{player}` | their name |

```yaml
vars:
  pity: |-
    ${slate}your luck ${slate}· {pulls} turns
    {pity}
model:
  parts:
    pity: { text: "${pity}", offset: [...], scale: [0.11, 0.11, 0.11], rotation: [180, 0, 0] }
```

It refreshes every 1.5 s, and immediately after a pull or a claw attempt, so the counter visibly
moves while the theatre is still playing. A machine with no `pity` part simply has no board.

**Coins**: the engine ships a ten-tier coin ladder (`plugins/MachineConstruct/coins.yml`: common …
leviathan, each a tagged coin head — rename / recolour / retexture there). `price: { coins: 1, coin:
rare }` charges that tier; `/mc coin give <player> <n> <tier>` pays them out (quests, votes, kits);
`/mc coin list`. Rewards may be COMMANDS too (`commands:` entries in the series file — money, keys,
ranks — with an `icon`), and `gacha.seed_crate: <id>` fills a NEW series from an ExcellentCrates crate
on first boot (`/mc gacha import <series> <crate>` does it any time: ITEM rewards re-built from their
SNBT, COMMAND rewards as command entries, rarity names + weights carried over). A series can still
override the coin with a held item. Series files may be AUTHORED with `item_string: "minecraft:diamond_sword[…] x3"`
(the /give form; the engine turns it into a real stack on load) — `templates/abyssal-market/tools/gen_gacha_templates.py`
writes `armory.yml` (weapons & tools, Armor Coin) and `bestiary.yml` (26 collectible sea heads, Pearl Coin) that
way. Reusable set-ups ("templates": pieces + tiers + price + coin) are a Dev's Diary thing — Capsules → 📦 Templates,
stored in the app (capsule-presets.json), never on the server. Named coins beyond the ladder (`armor`, `pearl`)
live in the same `coins.yml`; new engine defaults are merged into an existing file without touching edits.
`price: { coins: 1 }` charges the series' COIN — an
item admins set from their hand (`/mc coin set <series>` or the admin view's Coin button; `reset`
goes back to the engine's default *Capsule Token*, a tagged gold-coin head). `/mc coin give <player>
[n] [series]` / `/mc coin get` hand coins out (drop them from quests, kits, rewards…); `money:` and
`item:` still work alongside. **No player GUI**: the dial is the interface (right-click with nothing parked just prints the price
+ odds); admins right-click for the loading view (＋ Add held item, rarity cycle, collectible toggle,
🎨 theme cycle — `gacha.theme` / `gacha.themes` are the panels' theme system verbatim, 37 palettes).
The parked capsule belongs to its puller (right-click the machine to pop it; auto-opens after
`open_after` s); a re-render hands the items over instead of losing them.

**`style: reels`** turns the same kind into a SLOT MACHINE (ADR 0046, `slot_tidewheel.yml`): the roll
happens exactly as above, then three reels blur and land on it and the prize pays out at the end of
the spin — no parked capsule, no second click. The `pull` cue drives the reels and a `reveal` cue
(falls back to `open`) does the payout. Each rarity gets `symbol:` (the head the reels land on;
defaults to its `capsule:`), and the cue fills `{sym1} {sym2} {sym3}` — three of a kind for the tier
you won, two-and-a-neighbour on the bottom tier so a common spin still reads as a near miss.

```yaml
- { at: 0.2, part: reel_3, reel: { symbols: "<strip>", stop: "{sym3}", blur: "eyJ…", over: 3.1, tease: 0.55 } }
```

`reel:` drives a DRUM: the part turns about the machine's axis (`axis:`, `spins:`) for `over`
seconds, decelerating, and its symbol is swapped while the back face is toward the viewer, so a
strip appears to roll past; it lands face-on on `stop`. `tease` makes the final turn a separate,
much slower one — the reel all but stops on the neighbouring symbol, then rolls the last notch.
`blur` is the smear shown while it is too fast to read. A `rotate:` step takes `pivot: [x,y,z]`
(model space) to HINGE a part — a lever swinging on its mount — instead of spinning it on the spot. `symbols` is the strip (a list,
so keep it in `vars:` and reference it as `"<strip>"` — cues get the `${var}` / `<var>` pass too).
Slot themes add two vars the panels never needed (`glass` for the reel windows, `accent` for the win
line and chasing lamps); because a theme is just a var override this needs no engine support, and a
theme whose solid parts are head textures still wants a real translucent block for `glass`.

**`style: claw`** is a machine the player DRIVES (ADR 0047), and the only one allowed to pay nothing —
its prizes are expensive. Press START: it takes a coin, then the machine SEATS the player at the
joystick — it stands them at `claw.seat` facing the cabinet and takes their walk speed away, so WASD
is pure stick input (Paper's `PlayerInputEvent`, the same contract ArcadeCab uses) and Space or a
click drops the claw. The joystick part leans with the input. The claw holds about one play in five: `grab_chance` when it closes
within `radius` of a prize, `miss_chance` when it closes over nothing, and `pity_grabs` failures in a
row forces a hold. A grab then rolls the reward the usual way (rarity + pity + weighted entry), so
the crane decides *whether*, never *what*.

```yaml
claw:
  control: stick          # seat the player and read WASD (pad = the older walk-a-plate mode)
  speed: 0.55             # blocks a second while a direction is held
  seat: [0, 0, -1.17]     # where it stands the player, in model space
  travel: [0.62, 0.34]    # how far the claw may run from centre, x and z
  top: 2.08               # the gantry; floor: how deep it dives; cable: parked length
  chute: [-0.66, -0.20]   # where it lets go; tray: [x, y, z] where a won prize lands
  grab_chance: 0.20       # the prongs close on a prize
  miss_chance: 0.08       # ...or on nothing, if the aim was off
  slip_chance: 0.25       # and this many grabs lose it again on the way (so a play is worth grab x (1 - slip))
  pity_grabs: 6           # failures in a row before the claw is made to hold (a guaranteed play never slips)
  fail_modes: { miss: 40, slip_early: 35, slip_late: 25 }
  restock: 10s            # how long a lifted head leaves a gap in the pile before the machine refills it
```

A failure is *performed*, not scripted: the prongs close on nothing, or the prize is lifted a block
and tumbles back, or it is carried almost to the chute and dropped. **What the claw lifts is the head
that was sitting in the box**: when the prongs close over a `pile_*` part, that part goes blank and its
head rides the claw, so the player watches the thing they aimed at leave the heap. A win leaves the
gap open for `restock` (10s by default) and the machine quietly refills it; a slip puts it back the
moment it lands. A slot waiting to be restocked is not a target for the next play. If the prongs close
nowhere near a head, the claw carries a piece of the series instead, so a slip still shows something.
The closing angle is jittered, and the pile settles where the claw has been rummaging. Parts the engine drives by name: `rail*`, `carriage`,
`cable` (its Y scale is the cable), `claw_head`, `prong_*` (each hinges at its OWN top, on an axis
square to the line out from the claw's centre, so it splays straight outwards — put as many as you
like anywhere round the head; the engine reads their positions, not their numbering), `held` (the
carried prize — list it in `gacha.hidden`), `pile_*` (the prizes, and what `radius` is measured
against), and `stick` + `stick_ball` (the joystick, hinged at the shaft's foot). Give the cabinet a
START button (`action: gacha:pull`) and a DROP button (`action: claw:drop`). Geometry under `claw:` must match the model, which is why the machine is generated:
`templates/abyssal-market/tools/gen_claw.py`.

## Cheat-sheet

| want | write |
|---|---|
| a value once | `vars: { x: ... }` → `"${x}"` |
| a whole part/assembly once | `vars: { thing: {...} }` → `thing: "<thing>"` |
| same part at many spots | `grid: { x:[..], z:[..] }` or `grid: [ [..], [..] ]` |
| octagonal disc | `octagon: true` |
| move/turn a whole group | `offset:` / `rotation:` / `animation:` on the group |
| pose at a fixed angle | `rotation: [yaw, pitch, roll]` (+ `center: true` to pivot from middle) |
| keep turning | `animation: { always: { spin: { axis: x, speed: N } } }` |
| per-tier block swap | `tiers.vars: { 2: {...}, 3: {...} }` |
| rename/move/recolour a grinder or collector menu | `skin: { slots, titles, buttons, messages }` |
| park a shipped machine | `enabled: false` at the top of its file |
| turn the model toward the placer | `facing: player` |
| live `%placeholders%` on a board | `panel: { refresh: 30s }` + text parts, `rotation: [180,0,0]` |

Tip: edit the file, run `/mc reload`, look. Geometry is corner-anchored — `offset = -scale/2`
centres a part on the column.
