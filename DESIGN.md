# MachineConstruct — Design

A packet-based factory system for Paper 1.21.11, shipped as **two plugins**:

- **MachineConstruct** (the engine) — *makes the system*. The packet display
  layer, immediate-mode renderer, tracking, model/animation runtime, machine +
  recipe engine, storage, GUI framework, placer/chest lifecycle, and a public
  **API**. Ships with **no concrete machines** — it's the platform.
- **Foundry** (the content pack) — *makes the machines and items*. Depends only
  on the engine. Defines the actual machines, custom items, recipes, models, and
  placer head-skins (the Crusher, Smeltery, etc.), via the engine's API + YAML
  content packs. Visuals are vanilla blocks + head skins out of the box, and can
  reference custom-model items if a server ships a pack. (Name is a working
  default — rename freely.)

The player experience: hold a **machine placer** (a player-head item — its look
is just its skin, no resource pack); placing it drops a real **chest** that
becomes the machine's heart — its interface, persistence anchor, and lifecycle
key. Around that chest a **display-entity structure** (sent over packets, never
real entities) renders and animates while the machine runs a data-driven
production chain. Break the chest and the whole structure tears down.

**The plugin ships no textures — but supports them.** Visuals are assembled from
**vanilla blocks** (`block_display`) and **items** (`item_display`), then *shaped*
into machine forms by the transform system (translate / scale / rotate). An item
part's look has three tiers, all chosen in the model YAML:

1. plain vanilla item/block — works everywhere, zero setup;
2. **player head** carrying its own skin (base64/url) — "custom" look, still no pack;
3. an item tagged with **`custom_model_data`** (or the 1.21.4+ **`item_model`**
   component) — renders a custom model **if the server has a matching resource
   pack**, else falls back to the base item.

So texture support is real but **optional and server-side**: the engine just
sets the component on the display item; it never ships or requires a pack. Works
on a bare server; scales up if you bring one.

**Status:** planning (pre-P0). This document is the spine; code follows it.
**On-server references (studied, not depended on):** ZNPCsPlus (entity
tracking), DecentHolograms (packet displays).
**External reference:** TheCymaera/minecraft-spider (876★) +
minecraft-hologram — for the *immediate-mode rendering* + *hierarchical
transform* pattern (§6). Those use **real** display entities; we keep the
pattern, swap the backend to packets per §2.

---

## 0. Table of contents

1. Locked decisions
1B. Two-plugin architecture (engine + content) & the API
2. Why packet-based (the core bet)
3. The chest-anchor model (the spine)
4. Lifecycle (placer → chest → structure → teardown)
5. The machine placer item
6. Rendering: immediate-mode + packet backend
7. Packet entity layer
8. Tracking & viewers
9. Model system (the customization headline)
10. Animation
11. Interaction & the chest interface
12. Machines & state
13. Recipes & processing
14. GUI
15. Storage (pluggable: SQLite / JSON / H2)
16. Protection & anti-grief
17. Edge cases & failure modes
18. Commands & permissions
19. Config layout & schemas
20. Package structure
21. Build
22. Performance budget
23. Phased roadmap
24. Future (P7+)
25. Risks & open questions

---

## 1. Locked decisions

| Decision | Choice | Rationale |
|---|---|---|
| Language | **Java 21** | 1:1 with ZNPCsPlus/DecentHolograms reference code; packetevents is Java-first. |
| Build | **Gradle 9, Kotlin DSL, gradlew** | Mirrors `plugin_customized/panel-bridge`. |
| packetevents | **shaded + relocated** | Self-contained: zero runtime plugin dependencies. Works on any Paper server with no prerequisites (DecentHolograms does the same). Relocation avoids clashing with a server-installed copy. |
| Dependencies | **None (hard)** | Standalone jar. GriefPrevention/WorldGuard/PlaceholderAPI are *optional* reflective hooks, gracefully skipped if absent. No item-plugin coupling. |
| v1 scope | **Animated chest-anchored machines + recipes + GUI** (P0–P6) | Shippable slice. Inter-machine transport & energy = P7+. |
| Persistence | **Pluggable `StorageProvider`: SQLite (default) / flat-JSON / H2** | Config-selectable; abstraction now, swap later. |
| Name | **MachineConstruct** | Package `dev.servereer.machineconstruct`. |
| Anchor | **Real chest block at machine center** | Free interaction, chunk-load detection, persistence key, teardown trigger. |
| Placement | **Custom player-head "placer" item** | Identifiable, themeable, obtainable via `/mc give` / shop / crate. |
| Visibility | **Global** (all players see placed machines) | Factories are world objects; per-player rendering reserved for previews. |
| Rendering | **Immediate-mode reconciler over packet entities** | TheCymaera's pattern; minimal-diff updates; clean animation. |
| Animation | **Function/driver-based per part, with parenting** | Each part's transform = f(clock, progress, state, tier, fuel), composed down a parent/child transform tree (pivots, local/world axis, inherit flags). Procedural + keyframe; preset catalog (spin/bob/pulse/progress-fill/shake/glow/swap/flash/…); blending + per-instance phase. (§10) |
| Shape | **Two plugins: engine + content pack** | Reusable platform vs swappable content; the engine ships no machines. |
| Visuals | **Ships no textures; texture-optional** | Built from `block_display` (any blockstate) + `item_display` (vanilla items, **player heads** with their own skin, **or** items carrying `custom_model_data`/`item_model`) + `text_display`, all *shaped* by transforms. The plugin ships **no** resource pack — but if the server has one, model defs can reference custom-model items and they render; if not, they fall back to the base item. Graceful either way. |

---

## 1B. Two-plugin architecture (engine + content) & the API

```
MachineConstruct  (engine plugin — "the system")
  • zero external deps (shaded packetevents/sqlite/h2)
  • all runtime: render, track, animate, process, store, GUI, lifecycle
  • public API:  dev.servereer.machineconstruct.api.*
  • content-pack loader: scans registered packs' YAML
  • ships NO machines (maybe one trivial example, disabled by default)
        ▲
        │ depend: [MachineConstruct]   (our own engine — not an external dep)
        │ uses the API + ships a content pack
        │
Foundry  (content plugin — "the machines & items")
  • machine type defs, custom item defs, recipes, models, placer head-skins
  • visuals = vanilla blocks + head skins (no pack); optional custom-model items if a pack exists
  • registers them via the API and/or ships a YAML pack the engine scans
  • pure content: little to no runtime logic of its own
```

**Why split.** The engine is a reusable factory platform; content is swappable.
Anyone (us, or third parties) can write another content plugin against the same
API without forking the engine. The engine's correctness/perf work is isolated
from balance/theme iteration on machines.

**The dependency is intra-project only** — Foundry depends on *our* engine, not
on anything external. The engine itself still depends on nothing.

**Public API surface** (`dev.servereer.machineconstruct.api`), the engine's
"makes the system" contract:

- `MachineConstructAPI` (service, via Bukkit ServicesManager):
  `registerMachineType(...)`, `registerItem(...)`, `registerModel(...)`,
  `registerContentPack(folder)`, `giveePlacer(player, typeId, amount)`,
  `getMachineAt(location)`, `mintItem(itemId, amount)`.
- Builders: `MachineType.Builder`, `Recipe.Builder`, `ModelDef.Builder`,
  `ItemDef.Builder` — for programmatic content (Java) where YAML is too static.
- **Custom item registry**: items the system mints, identified by our PDC id
  (`mc:item`). Content defines them; recipes reference them by id; the closed
  item economy (§13) is engine-managed.
- **Bukkit events** for any plugin to hook: `MachinePlaceEvent`,
  `MachineBreakEvent`, `MachineProcessStartEvent`, `MachineProcessCompleteEvent`,
  `MachineInteractEvent` (all cancellable where it makes sense).
- **Content packs**: a folder convention
  `packs/<pack>/{models,machines,recipes,items,gui}/`. The engine scans its own
  `plugins/MachineConstruct/packs/` *and* any folder a content plugin registers
  via `registerContentPack(getDataFolder())`. So Foundry ships its defs as
  resources, points the engine at them on enable, and is otherwise thin.

Foundry's `onEnable` is essentially: get the API service → `registerContentPack`
→ optionally register a few programmatic extras → done.

---

## 2. Why packet-based (the core bet)

| Real display entities | Packet display entities (this plugin) |
|---|---|
| Tick, persist to region files, count to entity caps | Virtual — no tick, no save bloat, no entity caps |
| Griefable / dupeable / outlive the plugin | Fully controlled; vanish if plugin disabled |
| Server-side animation = teleport spam | Client-side interpolation = ~1 packet per keyframe |
| One global copy | Per-player visibility available for free |

Cost: **we** own tracking, lifecycle resend, interpolation, interaction. The
chest anchor (§3) pays down most of that cost by giving us a real, persistent,
clickable, chunk-tracked center for free.

---

## 3. The chest-anchor model (the spine)

Every machine is anchored to **one real chest block** at its center. The
packet-rendered display structure dresses around/over it. The chest is doing
four jobs at once:

| Job | Without chest (pure packet) | With chest |
|---|---|---|
| **Interaction** | Must overlay `interaction` entities + intercept InteractEntity packets | Vanilla right-click the chest → open GUI. Free. |
| **Chunk-load detection** | Must spatial-index virtual machines + watch ChunkLoad | The chest is a real block; it loads with the chunk. We read a PDC tag off it. Free. |
| **Persistence key** | Invent a key, risk desync from world | Block location is the natural primary key; chest existence == machine existence. |
| **Teardown trigger** | Need a custom "remove" flow | BlockBreak on the chest → tear down. Intuitive. |

**Chest specifics & caveats:**
- **No double-chest.** Two adjacent chests pair into a 54-slot double — that
  would break the one-chest-one-machine invariant. Mitigation: on place, set the
  chest's `type` blockdata to `SINGLE` and **cancel pairing** (deny placement if
  it would pair, or force-single via NMS/blockdata). Alternative considered:
  **barrel** (no pairing, same affordance) — kept as a config option
  `anchor-block: chest|barrel`, default chest per the brief.
- **PDC tag** on the chest's `TileState` (`mc:machine` = machineTypeId + uuid)
  is the source of truth that "this block is a machine." Survives world save
  natively; our store holds the richer state.
- **Vanilla chest inventory:** in v1 the real chest contents are **locked/empty**
  — the GUI (§14) drives a separate machine inventory. Letting hoppers feed the
  real chest as an input buffer is a clean P7 automation hook (noted, not v1).
- The chest may be **visually hidden** under the display model (a block_display
  covering it) so players see the machine, not a chest — but the clickable real
  block remains at center.

---

## 4. Lifecycle (placer → chest → structure → teardown)

State machine for a machine's existence:

```
UNPLACED      placer head item in inventory
   │  player right-clicks a block face with placer
   ▼
PLACING       validate (perm, space, not pairing, region allows build)
   │  place anchor chest, write PDC tag, consume 1 placer
   ▼
REGISTERED    Machine record created (owner, type, IDLE), persisted
   │  chunk loaded + viewer within render-distance
   ▼
RENDERED      display structure spawned via packets (immediate-mode)
   │  valid inputs present
   ▼
WORKING ⇄ BLOCKED / NO_FUEL          recipe processes; "working" animation
   │  owner/trusted breaks the chest
   ▼
TEARDOWN      despawn structure packets, drop placer (+ contents per config),
              delete record + PDC
```

**Lifecycle hooks:**
- `BlockPlaceEvent` / `PlayerInteractEvent` — detect placer, run PLACING.
- `BlockBreakEvent` on anchor — run TEARDOWN (guard owner/trust, §16).
- `ChunkLoadEvent` — scan for chests with our PDC → REGISTER (logic), defer
  RENDER to tracking (§8).
- `ChunkUnloadEvent` — save + drop from active set; despawn for viewers.
- **Orphan recovery:** on chunk load, if our store has a machine whose anchor
  block is no longer a tagged chest (explosion/edit bypassed us) → delete the
  record + despawn. Conversely a tagged chest with no record → rebuild a default
  record. The chest block is always the tiebreaker.

---

## 5. The machine placer item

- A **player-head** `ItemStack` whose look is simply its **skin** (a base64/URL
  head-profile value set per machine type — the head's own texture, **not** a
  resource pack), plus a branded display name + lore (machine name, tier, "Place
  to build") and a **PDC tag** `mc:placer = machineTypeId`.
- **Obtain:** `/mc give <player> <machineType> [amount]`, ExcellentCrates
  rewards, EconomyShopGUI listings, vote rewards — all just hand out the tagged
  head. No new economy code.
- **Placement detection:** on right-click block, read `mc:placer`; if present and
  player has `machineconstruct.place.<type>`, run PLACING and consume one.
- **Stacking:** placers of the same type + tier stack; lore stays identical so
  stacking isn't broken by per-item data.
- **Pickup on teardown:** breaking the anchor returns the exact placer head
  (so machines are relocatable), optionally + any non-output contents
  (`drop-contents-on-break: true|false`).

---

## 6. Rendering: immediate-mode + packet backend

Adopt TheCymaera/minecraft-spider's **immediate-mode** pattern, backed by our
packet layer (§7) instead of real entities.

- `RenderItem.submit(handle)` — describe what *should* exist this frame.
- `RenderGroup` — hierarchical composition: a group applies its own transform,
  then submits children with composed transforms (parent→child matrices). This
  is how a machine arm with sub-gears inherits the arm's rotation. Parenting is
  **server-side only** — the tree is flattened to per-leaf *world* transforms
  each frame (display entities can't inherit on the client); see §10.7.
- `RenderEntity` → **`PacketEntityTracker`** (our swap): keyed by a stable
  `handle` (machine-uuid → part-id). On submit: if a packet entity exists for
  the handle → send only changed metadata/teleport; else → send spawn. At
  frame end, handles not re-submitted → send despawn. Minimal-diff, declarative.
- We re-describe a machine's scene whenever its state or animation frame
  changes (not every tick unconditionally) — event/clock-driven, not polling.

**Why this matters:** animation, tracking, and state changes all collapse into
"re-describe the scene; the reconciler computes the packet delta." One mental
model for the whole render path.

---

## 7. Packet entity layer (`core/`)

Typed wrappers over packetevents. No Bukkit entity ever created.

- `PacketEntity` (abstract): `entityId` (`SpigotReflectionUtil.generateEntityId()`),
  `uuid`, `location`, dirty-meta set; `spawn/despawn/updateMeta/teleport(viewer)`.
- `BlockDisplayEntity` — block state, transform, brightness, glow color, view range.
- `ItemDisplayEntity` — itemstack, display-context (head/gui/fixed/ground…),
  transform. The itemstack may be a plain item, a **player head** (own skin, no
  pack), or an item carrying `minecraft:custom_model_data` / `minecraft:item_model`
  (renders a resource-pack model if present, else the base item). Engine sets the
  component; never ships a pack.
- `TextDisplayEntity` — Adventure component, billboard, background ARGB, see-through, alignment.
- `InteractionEntity` — width/height invisible hitbox (used only for clickable
  sub-parts away from the chest; the chest covers the primary click — §11).
- `DisplayMeta` — **all** version-specific metadata indices centralized here;
  a Paper bump touches one file.
- `Transform` helper — translation (Vector3f) + scale (Vector3f) + left/right
  rotation (Quaternionf), built from author-friendly euler degrees / axis-angle.

---

## 8. Tracking & viewers (`tracking/`)

The chest anchor simplifies the ZNPCsPlus pattern: **logic** presence follows
real chest blocks (chunk load); only **rendering** needs viewer diffing.

- `ActiveMachines`: machines in loaded chunks (registered on ChunkLoad).
- `Viewer`: a `Player` + its seen-set of rendered machine handles.
- `TrackingTask` (~every 10 ticks, main thread): for each player, machines whose
  anchor is within `render-distance` → diff seen-set → render (submit scene) /
  despawn. Event hooks (join/quit/respawn/world-change/teleport) trigger instant
  recompute for the affected player.
- `render-distance = min(configured, server view-distance)`.
- On plugin disable: despawn all for all viewers (client state never persists a
  relog anyway).

---

## 9. Model system (`model/`) — the customization headline

A **model** = a named hierarchical assembly of display parts + animation tracks,
authored in `models/*.yml`. Hierarchy via nested `parts` (RenderGroup §6).

**Part visual sources** (engine ships no pack; tiers 1–2 need none, tier 3 uses
the server's pack if present):
- `block_display` — any vanilla blockstate (`minecraft:copper_block`, slabs,
  stairs, etc.) shaped by transform.
- `item_display` — an item, specified one of three ways:
  - `item: <material>` — plain vanilla item;
  - `head: <base64|url|playerName>` — a player head with that skin profile
    (distinctive look, no pack);
  - `item: <material>` **+** `model: <id>` or `custom-model-data: <n>` — sets the
    `minecraft:item_model` / `custom_model_data` component so a resource pack can
    render a fully custom model; falls back to the base item if no pack.
- `text_display` — Adventure/MiniMessage text + placeholders.

So a "metal gear" can be a head-skin item, or a custom-model item if you ship a
pack; a "casing" is transformed `block_display`s. Same model def works with or
without a pack — only fidelity changes.

**Anchoring (block vs item/head/text).** A `block_display` fills the box
`offset .. offset+scale` measured from its **corner** (vanilla block-display
behavior). `item_display`/`text_display` render **centered** on their position,
so the engine (a) auto-offsets them by `+scale/2` to occupy the *same* box, and
(b) applies an intrinsic **fill** factor so they're the same *size* in it — a
player head renders as a ½-block skull, so it gets ×2. Net result: swapping
`block:` for `head:` on a part with identical `offset`/`scale` is a **no-op on
footprint** — same position, same size. `head:` also defaults to the `fixed`
display context (not worn `head`, which shrinks/repositions). `center:`/`pivot:`
apply to blocks only — items already rotate about their center. Implemented via
`DisplayContent.centerAnchored()` + `intrinsicFill()` → `ModelNode.localAt`.

```yaml
# models/crusher.yml
id: crusher
anchor: { hide-chest: true }       # cover the real chest with base block_display
root:
  offset: [0.5, 0.0, 0.5]          # center on the chest block
  parts:
    base:
      type: block_display
      block: "minecraft:smooth_stone[...]"   # full blockstate string
      scale:  [1.0, 0.5, 1.0]
      brightness: { block: 15, sky: 15 }
    drum:                          # a sub-group (parent) that rotates as a whole
      offset: [0.0, 0.55, 0.0]
      pivot:  [0.0, 0.0, 0.0]      # spin about the drum's own center
      animation:                   # group anim composes onto ALL children (§10.7)
        working: { rotate: { axis: y, speed: 220, blend: 8 } }   # ×tier, eased in
      parts:
        gear:                      # child: animates inside the drum's frame
          type: item_display
          item: "minecraft:iron_block"
          display: head
          scale: [0.8, 0.8, 0.8]
          animation:               # §10 function/driver-based, per part
            idle:    { rotate: { axis: y, speed: 30 } }
            working: { pulse: { from: 0.8, to: 0.95, period: 0.4 } }
            blocked: { shake: { amplitude: 0.04, hz: 12 } }
    piston:
      type: block_display
      block: "minecraft:iron_block"
      offset: [0.0, 0.2, 0.0]
      animation:
        progress: { translate: { axis: y, from: 0.0, to: 0.5, curve: ease-in-out } }
    label:
      type: text_display
      offset: [0.0, 1.4, 0.0]
      billboard: vertical
      background: "#00000000"
      inherit: { rotation: false }   # stay upright even if a parent spins (§10.7)
      animation:
        always: { text: { bind: progress, format: "<gold>Crusher <gray>· <white>{percent}%" } }
        complete: { flash: { color: "#6abe6a", duration: 8 } }   # one-shot pop
hitbox-extras: []                  # optional interaction entities for sub-parts
```

- Classes: `Model`, `ModelPart`, `ModelGroup` (immutable defs); each part/group
  carries its `AnimationSet` (trigger → layered {@code AnimationFunction}s, §10).
  `ModelInstance` (placed: origin + live render tree + `AnimationPlayer` that
  composes functions each frame against a `DriverContext`).
- Part text supports placeholders (`%mc_progress%`, `%mc_tier%`, PAPI) re-resolved
  on state change.
- **Authoring QoL** (stretch): `/mc model preview <id>` spawns an ephemeral,
  per-player instance for live tuning; `/mc model reload`.

---

## 10. Animation (`model/anim`) — function/driver-based per part

Each part has its own animation, expressed as **functions of drivers** — not
just a static keyframe timeline. A part's live transform is *computed every
frame* from the current machine state, then handed to the reconciler, which
sends the minimal packet delta. This is the procedural approach (cf.
TheCymaera/minecraft-spider) and is what makes machines feel alive and
*work-reactive*.

### 10.1 Two animation modes (a part may use either or both)

- **Procedural** — `transform = f(drivers)`, recomputed each frame. The default
  for machines: spin, bob, progress-fill, etc. Continuous, reactive, no timeline.
- **Keyframe** — an authored timeline (keyframe → display `interpolation_duration`,
  client tweens). For scripted one-shots: `startup`, `complete`, `shutdown`.

### 10.2 Drivers (what functions read)

| Driver | Range | Source |
|---|---|---|
| `clock` | seconds (monotonic) | shared animation clock |
| `progress` | 0.0–1.0 | current recipe progress |
| `state` | IDLE / WORKING / BLOCKED / NO_FUEL | machine state |
| `tier` | int ≥ 1 | machine tier (scales speed) |
| `fuel` | 0.0–1.0 | fuel/energy level |
| `random` | per-part stable seed | jitter / variation |

### 10.3 Triggers (when an animation layer applies)

`idle` (ambient, always), `working`, `blocked`, `no_fuel`, `progress`
(continuous, mapped from progress), one-shots `startup` / `complete` /
`shutdown`. Layers **compose**: `idle` runs always; state layers add to or
override specific channels (translation additive, rotation composed, scale
multiplied, discrete outputs replaced).

### 10.4 Function catalog (the "default animation for each part" set)

Authored per part under `animation: { <trigger>: { <function>: {...} } }`.

| Function | Channel | Params | Typical use |
|---|---|---|---|
| `rotate` | rotation | axis, speed (deg/s) **or** from/to, curve | gears, fans, drills |
| `translate` | translation | axis, from, to, curve | piston extend, slide |
| `bob` | translation | axis, amplitude, period | hover/float, idle life |
| `swing` | rotation | axis, range, period | pendulum, lever |
| `orbit` | translation | radius, period, plane | satellites, particles-as-blocks |
| `scale` | scale | axis/uniform, from, to, curve | grow/shrink |
| `pulse` | scale | from, to, period | breathing core/glow |
| `shake` | translation | amplitude, hz | BLOCKED jitter, impact |
| `glow` | brightness | from, to | heat up while working |
| `swap` | content (skin) | mode (cycle/random/hold), period, interval, frames[] | cycling/random/per-state skins ✅ implemented |
| `show` / `hide` | visibility | when (state expr) | reveal output, conditional parts |
| `flash` | scale+glow | on: complete, color, duration | craft-done pop (one-shot) |
| `text` | text content | bind: progress/state/count, format | label %, counters |

**`swap` (skin channel) — implemented.** Unlike the transform functions above
(which output a TRS delta blended into the part), `swap` selects *which*
`DisplayContent` the part shows this frame, from a `frames:` list. It re-sends
the content metadata each tick (cheap: same entity, content index only), so all
frames must share the base part's display type (head↔head, block↔block);
cross-type frames are warned and dropped (would need a despawn/respawn). Modes:
`cycle` (flipbook over `period`s — align to a spin's period for an "at end of
animation" switch), `random` (a random frame every `interval`s, desynced per
instance via the clock phase), `hold` (show `frames[0]` while the trigger is
active → per-state skins). When no swap is active the part reverts to its base
content. Code: `ContentSwap` + `ModelNode.contentAt` + `PacketDisplay.setContent`.

```yaml
housing:
  head: "illustro_"                       # base skin
  animation:
    idle:    { swap: { mode: cycle,  period: 3.0,  frames: [ {head: "illustro_"}, {head: "Notch"} ] } }
    working: { swap: { mode: random, interval: 0.25, frames: [ {head: "MHF_TNT"}, {head: "MHF_Steve"} ] } }
```

**`particle` / `sound` (FX channels) — implemented.** Side-effecting channels
(like `swap`) that emit at the part's live world position each tick. Rate-gated
statelessly off the clock (`rate` seconds between emissions; `0` = every tick) —
no per-instance state, since the node tree is shared. Emitted via Bukkit
`spawnParticle`/`playSound` (all players in range; viewer-scoped packets are a
later refinement). Code: `FxEmitter` + `ModelNode.collectFx` + `FxPlayer`.

```yaml
rotor:
  animation:
    idle:    { particle: { type: smoke, offset: [0,0.15,0], count: 2, rate: 0.2 } }
    working: { particle: { type: large_smoke, count: 4, rate: 0.08 },
               sound:    { type: block.stone.break, volume: 0.4, pitch: 0.7, rate: 0.5 } }
```

**Driver-mapped functions** (the work/progress feel): any numeric function may
bind its value to a driver instead of `clock`. e.g.

```yaml
piston:
  animation:
    progress: { translate: { axis: y, from: 0.0, to: 0.6, curve: ease-in-out } }  # extends as it crafts
bar:
  animation:
    progress: { scale: { axis: y, from: 0.05, to: 1.0 } }                          # fill bar
gear:
  animation:
    idle:    { rotate: { axis: y, speed: 30 } }                                    # ambient slow spin
    working: { rotate: { axis: y, speed: 220 } }                                   # faster while working (×tier)
core:
  animation:
    idle:    { bob: { axis: y, amplitude: 0.06, period: 2.0 } }
    working: { glow: { from: 4, to: 15 }, pulse: { from: 0.9, to: 1.05, period: 0.4 } }
    blocked: { shake: { amplitude: 0.04, hz: 12 } }
needle:
  animation:
    progress: { rotate: { axis: z, from: -90, to: 90 } }                           # dial/gauge
```

### 10.5 Curves / easing

`linear` (default), `ease-in`, `ease-out`, `ease-in-out`, `sine`, `bounce`,
`step`. Applied to keyframe interpolation **and** to driver→value mapping
(e.g. progress-fill that eases). `tier` scales `speed`/inverse-scales recipe
time, so higher tiers visibly spin/fill faster.

### 10.6 Runtime

- `AnimationFunction` interface: `apply(DriverContext ctx) -> PartialTransform`
  (+ optional discrete outputs: block/item swap, visibility, text, brightness).
  Named presets in the table parse to implementations; a future **expression**
  form (`rotation.y = clock*90`, `scale = lerp(0.9,1.05,progress)`) is the power-
  user escape hatch.
- A part composes its active layers each frame into one transform; the
  **shared clock** (one repeating task) ticks all instances; the reconciler
  diffs and sends packets **only to in-range viewers**, and **only when output
  changed** (idle machines with no motion and no viewers send nothing).
- Procedural channels that change every frame (spin) send a fresh transform with
  a short `interpolation_duration` matched to the clock period, so the client
  tweens smoothly between frames (no stutter, low packet rate).
- One-shot triggers (`startup`/`complete`/`shutdown`) run a keyframe track to
  completion, then control returns to the looped state layers.
- FX: any layer/keyframe may emit **particles** and **sounds**, synced to the
  animation, viewer-gated.

### 10.7 Parenting & transform hierarchy

The model is a **tree** of nodes (groups + parts). Each node has a *local*
transform and its own animation; a node's *world* transform is composed down the
chain. This is the parenting system — animate a parent and the whole subtree
follows; a child animates inside its parent's (possibly rotating) frame.

**Composition, per node, per frame:**

```
world(node) = world(parent) ∘ local(node) ∘ anim(node)
local(node) = T(offset) ∘ Tpivot⁻¹ ∘ R(rotation) ∘ S(scale) ∘ Tpivot
anim(node)  = the composed AnimationFunction output for this frame (§10.4)
```

- **Pivots.** Rotation/scale happen around a node's `pivot` (default = its
  origin), so a gear spins about its center while an arm rotates about a joint at
  one end: `pivot: [x, y, z]` (node-local). The engine bakes the
  `translate(-pivot) → rotate/scale → translate(+pivot)` sandwich so authors just
  name the pivot.
- **Local vs world axis.** `rotate.space: local` (default) rotates about the
  node's inherited frame (a tilted arm's child spins around the arm's axis);
  `space: world` ignores parent rotation (for gravity-aligned effects).
- **Inherit flags.** A child may opt out of parts of its parent's transform —
  e.g. a label that must stay upright/world-scaled even as its parent rotates:
  `inherit: { rotation: false, scale: false }`. (Billboard text usually sets
  `inherit.rotation: false`.)
- **Flatten to leaves.** Client display entities have **no** parenting — they
  can't inherit another entity's transform. So parenting is **server-side only**:
  each frame the reconciler walks the tree, composes each leaf's *world* transform,
  decomposes it into the display TRS the protocol supports (translation Vector3f +
  scale Vector3f + left/right rotation quaternions — enough for rotation plus
  non-uniform scale), and sends that per leaf. Groups are transform-only (no
  entity). This is exactly TheCymaera's "compute world transforms, render leaves."
- **Attach sockets** (stretch): named points on a node (`sockets: { spout: [...] }`)
  where sub-models, dropped-item displays, or interaction hitboxes mount and
  inherit the socket's world transform.

### 10.8 Blending, phase, looping

- **State blending.** Switching trigger layers (idle→working) **eases** scalar
  params over a `blend` window (e.g. spin speed ramps 30→220 deg/s over 8 ticks)
  instead of snapping. Per-layer `blend: <ticks>`.
- **Per-instance phase.** Each placed machine gets a stable random `phase` offset
  fed into `clock`, so a row of identical machines doesn't animate in lockstep
  (unless `sync: true` for deliberately synchronized banks).
- **Looping modes.** `loop` (wrap), `ping-pong` (reverse at ends), `once` (hold
  last). Applies to both clock-driven functions and keyframe tracks.
- **Time scale.** `tier` and an explicit `speed`/`timescale` multiply the clock
  for a node, so upgrades visibly run faster; `progress`-bound functions are
  unaffected (they track real progress).

---

## 11. Interaction & the chest interface (`io/interact`)

- **Primary interface = the real chest.** Right-clicking the anchor fires
  `PlayerInteractEvent` (or `InventoryOpenEvent`); we cancel the vanilla chest UI
  and open the `MachineMenu` (§14). No packet hitbox needed for the main click.
- **Sub-part clicks** (optional): models may declare `hitbox-extras`
  (interaction entities) for things like a clickable output spout; those route
  via packetevents `InteractEntity` → handle → action, as originally planned.
- Click variants: right (open GUI), shift-right (quick-action, e.g. eject
  output), left (info/inspect). Sneak modifies.

---

## 12. Machines & state (`machine/`)

- `MachineType` (def, `machines/*.yml`): id, display name, model id, anchor block
  (chest|barrel), placer head texture, recipe set, I/O slot layout, tier rules,
  upgrade slots, fuel/energy model, GUI layout id, permission node, sounds.
- `Machine` (instance): anchor `Location`, type, owner UUID + trusted set,
  `MachineState` (IDLE/WORKING/BLOCKED/NO_FUEL/DISABLED), machine inventory
  (input/output/fuel/upgrade slots), progress ticks, current recipe, tier,
  accumulated stats.
- `MachineRegistry`: type defs by id; `ActiveMachines`: live instances by anchor
  location (and a chunk index for tracking).

---

## 13. Recipes & processing (`process/`)

```yaml
# recipes/crusher.yml  (or inline under the machine type)
- id: ore_to_dust
  machine: crusher
  inputs:  [ { item: "minecraft:iron_ore", amount: 1 } ]
  fuel:    { item: "minecraft:coal", amount: 1, per: 8 }   # 1 coal per 8 ops
  time: 100                                                 # base ticks
  outputs:
    - { item: "minecraft:iron_dust_custom", amount: 2 }
    - { item: "minecraft:flint", amount: 1, chance: 0.10 } # byproduct
```

- `RecipeEngine` on a **processing tick** (configurable, e.g. every 5 ticks):
  for each loaded WORKING machine — match a recipe against inputs, reserve
  inputs at start, advance progress (`time / tier-speed-multiplier`), on
  completion push outputs (output-full → BLOCKED), consume fuel per cadence,
  loop. NO_FUEL when fuel empty and required.
- **Tiers/upgrades** modify: speed multiplier, parallel batch size, fuel
  efficiency, output bonus chance. Defined per type; applied as multipliers.
- Item identity is **vanilla-only + our own NBT**: matcher = material (+ optional
  exact-match flags: custom model data, our own PDC item id, display name). No
  dependency on external item plugins. A machine can mint its own custom outputs
  (tagged via our PDC) that other machines accept as inputs — a fully
  self-contained item economy.
- Processing logic is main-thread (touches inventories) but cheap; heavy scans
  amortized across ticks.

---

## 14. GUI (`io/gui`)

- Hand-rolled chest-inventory `MachineMenu` (no external menu lib in v1).
  Layout from `gui/*.yml` per machine type: input slots, output slots, fuel
  slot, upgrade slots, a **progress indicator** (animated arrow item / title
  percent), tier display, and info/help.
- **Dupe-safe**: clicks apply atomically to the machine inventory model, then
  mirror to open viewers; cancel raw vanilla slot moves; validate on close.
- **Hopper/automation** (P7): map real-chest hopper I/O to machine input/output
  buffers. v1 GUI-only.
- Uses Royal-palette MiniMessage titles/lore to match the server's look.

---

## 15. Storage (`store/`) — pluggable, all three

```
StorageProvider (interface)
 ├── SqliteStorage   (default; sqlite-jdbc, shaded)
 ├── JsonStorage     (flat per-region files, no DB dep)
 └── H2Storage       (server-provided H2 if present, else shaded)
```

- `storage.type: sqlite|json|h2` in config.
- **Primary key = anchor block location** (world, x, y, z). Region-bucketed
  (worldName, regionX>>5, regionZ>>5) for batched IO.
- Stored per machine: type id, owner + trusted, state, inventory (serialized
  ItemStacks, Base64/SNBT), progress, current recipe id, tier, stats.
- All writes async (dedicated executor); reads on chunk-load may prefetch the
  region. **Dirty-flag** per machine to skip untouched rows. Save triggers:
  interval, chunk unload, world save, plugin disable.
- `schema_version` column/field for migrations. Crash recovery: state is only as
  stale as the last interval flush; in-progress recipes resume from saved
  progress.

---

## 16. Protection & anti-grief (`io/protect`)

- Anchor break/interact gated by **owner + trusted** list; ops/`machineconstruct.admin` bypass.
  This is the plugin's *own* protection and works with **zero** external plugins.
- **Optional, reflective** GriefPrevention / WorldGuard hooks: if present, also
  defer to their build/break checks; if absent, silently skipped. No hard
  dependency — detected at runtime via `PluginManager`, called reflectively.
- Explosion protection: cancel anchor-chest destruction from TNT/creeper
  (config `protect-from-explosions: true`), else route to orphan recovery (§17).
- Piston-move of the anchor: cancel (machines don't move) or teardown-safe.

---

## 17. Edge cases & failure modes

| Case | Handling |
|---|---|
| Chest broken by explosion (bypasses our break flow) | Orphan recovery on next load: no tagged chest → delete record + despawn. |
| Piston tries to move anchor | Cancel move (registered as immovable). |
| Fluid / fire on display parts | Packet entities are immune; chest is real — protect or accept vanilla. |
| Chunk unload mid-process | Save progress; resume on reload. |
| Server crash mid-process | Resume from last interval flush; idempotent recipe step. |
| Duplicate placement on same block | Block occupied → placement denied. |
| Double-chest pairing | Forced single / pairing denied at place (§3). |
| Packet desync (client missed spawn) | Tracking re-submits scene on next viewer recompute; relog forces clean resend. |
| World deleted / machine in unloaded world | Records keyed by world name; skip on missing world; prune on demand. |
| `/reload` or plugin update | Despawn all, reload defs, re-render from store. |

---

## 18. Commands & permissions (`command/`)

- `/mc give <player> <type> [amount] [tier]` — `machineconstruct.admin`
- `/mc reload` — reload defs + re-render — `machineconstruct.admin`
- `/mc model preview <id>` / `/mc debug ...` — dev tools — `machineconstruct.admin`
- `/mc info` — look-at machine info — `machineconstruct.use`
- Permission nodes: `machineconstruct.place.<type>`, `.use`, `.admin`,
  `.bypass.protection`.

---

## 19. Config layout & schemas (`src/main/resources/`)

```
config.yml          # storage.type, render-distance, tick intervals, packet budget,
                    # anchor defaults, protection toggles, hook toggles
models/*.yml        # §9 model definitions (hierarchy + animations)
machines/*.yml      # §12 machine type definitions (model + recipes + io + placer)
recipes/*.yml       # §13 recipes (or inline under machines)
gui/*.yml           # §14 per-type menu layouts
messages.yml        # MiniMessage strings, Royal-palette branded
```

All hot-reloadable via `/mc reload` (re-reads defs, re-renders live instances).

---

## 20. Module & package structure

Gradle multi-project under `plugin_customized/machineconstruct/source/`, two
buildable plugin jars (mirrors zpeer's multi-module precedent):

```
source/
├── settings.gradle.kts          rootProject + :engine + :content
├── build.gradle.kts             shared config (Java 21, repos, Shadow)
├── engine/                      → MachineConstruct.jar  ("the system")
│   └── src/main/java/dev/servereer/machineconstruct/
│        ├── MachineConstruct.java     (entrypoint, wiring, API impl)
│        ├── api/        PUBLIC API: MachineConstructAPI, *.Builder, events, item registry
│        ├── core/       PacketEntity, Block/Item/Text/InteractionEntity, DisplayMeta, Transform
│        ├── render/     RenderItem, RenderGroup, RenderEntity, PacketEntityTracker (§6)
│        ├── tracking/   ActiveMachines, Viewer, TrackingTask, lifecycle listeners
│        ├── model/      Model, ModelPart/Group, loader; anim/ AnimationPlayer, clock, fx
│        ├── machine/    MachineType, Machine, MachineState, MachineRegistry, placement, anchor PDC
│        ├── process/    RecipeEngine, Recipe, tier/upgrade math, scheduler
│        ├── io/         interact/ (chest + InteractEntity), gui/ (MachineMenu), protect/ (optional hooks)
│        ├── store/      StorageProvider + Sqlite/Json/H2, region keying, async
│        ├── pack/       content-pack loader (scans registered pack folders)
│        ├── config/     loaders, schema validation, reload
│        └── command/    /mc (give, reload, model preview, info, debug)
└── content/                     → Foundry.jar  ("the machines & items")
    └── src/main/
         ├── java/dev/servereer/foundry/Foundry.java   (thin: register pack via API)
         └── resources/
              ├── plugin.yml      (depend: [MachineConstruct])
              └── packs/foundry/  models/  machines/  recipes/  items/  gui/   (the actual content)
```

The engine compiles standalone. `content/` has `compileOnly(project(":engine"))`
for the API and otherwise is mostly YAML + textures.

---

## 21. Build

Gradle 9 multi-project + Kotlin DSL + Java 21 toolchain + the **Shadow** plugin.
`./gradlew shadowJar` builds **both** jars.

**`:engine` → MachineConstruct.jar** — shaded + relocated **packetevents**,
**sqlite-jdbc**, **H2** under `dev.servereer.machineconstruct.libs.*`. No hard deps.

```yaml
# engine plugin.yml
name: MachineConstruct
main: dev.servereer.machineconstruct.MachineConstruct
api-version: '1.21'
softdepend: [GriefPrevention, WorldGuard, PlaceholderAPI]   # optional hooks only
authors: [servereer]
```

**`:content` → Foundry.jar** — depends on the engine; no shaded libs (it's data).

```yaml
# content plugin.yml
name: Foundry
main: dev.servereer.foundry.Foundry
api-version: '1.21'
depend: [MachineConstruct]
authors: [servereer]
```

`softdepend` on the engine only nudges load order for optional hooks; the engine
runs fully with none installed. Drop **both** jars into **umbralcraft**
`plugins/` (never zpeer-coupled basic-survival during dev). The engine drops
cleanly onto any Paper 1.21 server with no prerequisites; Foundry needs only the
engine.

---

## 22. Performance budget

- Tracking task: O(players × nearby-machines), ~every 10 ticks, main thread, cheap.
- Animation: packets only to in-range viewers, only on keyframe boundaries, only
  for WORKING (or transitioning) machines. **Hard cap** `animated-parts × viewers`
  per tick; overflow spills to next tick (visual hitch beats packet storm).
- Processing: every 5 ticks, only loaded WORKING machines.
- Storage: all writes async; dirty-flag avoids rewrites; region-batched.
- Target: hundreds of machines + dozens of viewers with no measurable MSPT hit;
  validate empirically at P4 (animation) before committing to P5 scale.

---

## 23. Phased roadmap (each phase boots + is verifiable on umbralcraft)

| Phase | Deliverable | Acceptance test |
|---|---|---|
| **P0** | Gradle scaffold, plugin.yml, empty plugin | Boots clean on umbralcraft; `/mc` responds |
| **P1** | Packet display layer + immediate-mode reconciler + tracking MVP | `/mc debug spawn` block_display survives relog/chunk reload, despawns out of range |
| **P2** | Placer head + chest anchor + PDC + place/break lifecycle | Place head → chest + tag appears; break chest → cleanup; survives chunk reload |
| **P3** | Model system from YAML (hierarchy + transforms), chest hidden under model | crusher.yml renders multi-part, centered, chest covered |
| **P4** | Animation via interpolation + shared clock + FX | gear/drum animate only while "working", only for in-range players; budget holds |
| **P5** | Machine state + pluggable storage + 1 recipe | Place Crusher, feed ore+fuel, get dust over time; survives restart on all 3 backends |
| **P6** | Chest-open → MachineMenu GUI (I/O + fuel + progress + tier) | Right-click chest opens GUI; no dupes; protection enforced |

**Engine vs content across phases:** P0–P6 build the **engine**. **Foundry**
(content) is scaffolded at P0 as an empty pack and grows the example **Crusher**
alongside the engine features that need exercising — its model at P3, recipe at
P5, GUI layout at P6. The Crusher is the engine's living acceptance test; it
lives entirely in Foundry, proving the API + pack loader work from the outside.

After P6 = **v1**: a complete, data-driven, animated, persistent factory machine
(defined in Foundry, run by the engine) placed by a head item and anchored to a
chest.

---

## 24. Future (P7+)

- **Conveyors / pipes**: item transport between machines (themselves display
  entities); real-chest hopper I/O as the simplest first automation.
- **Energy / power grid**: generators, storage, consumers, networks.
- **Multiblock machines**: structures spanning multiple anchors / a footprint.
- **Upgrade trees & tiers**: deeper progression, visual model swaps per tier.
- **In-game model editor**: author/preview models without YAML.
- **More archetypes**: smeltery, assembler, generator, storage core, farm.

---

## 25. Risks & open questions

- **Metadata index drift** across MC versions → centralized in `DisplayMeta`.
- **Packet volume** under many animated machines → budget guard (§22); measure at P4.
- **Double-chest / pairing** → forced single or barrel fallback (§3).
- **Hopper vs locked chest** → v1 locks the real chest; automation is P7.
- **Shaded jar size** → packetevents + sqlite-jdbc + H2 relocated adds a few MB;
  acceptable for a self-contained, dependency-free plugin.
- **Relocation correctness** → packetevents must be fully relocated so it never
  clashes with a server-installed copy; verify at P0 with a server that also runs
  packetevents (umbralcraft does).
- **Force-loaded factories** (run while owner offline) → v1 only while chunk
  loaded normally; force-load opt-in is P7.
- **Owner trust model** → integrate with GriefPrevention claims vs our own
  trusted-list; decide at P6.
