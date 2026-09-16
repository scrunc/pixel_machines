# MachineConstruct + Foundry

Packet-based, fully authorable machines for Paper/Leaf servers. Every machine is a structure of
**display entities sent as packets** — never real entities, never blocks in the world — wrapped
around one anchor block that holds its data. Server owners author the model, the animation, the
menus, the recipes and every line of text in YAML; the engine ships no machines of its own.

<img width="564" alt="preview" src="https://github.com/user-attachments/assets/2c97606f-dd83-49d3-812d-6a014ccd3d1b" />
<img width="308" alt="preview" src="https://github.com/user-attachments/assets/05c935ec-0437-47d9-97b6-a8be31977340" />
<img width="478" alt="preview" src="https://github.com/user-attachments/assets/6810f3b5-64db-45dd-8d63-2952757cdcff" />
<img width="567" alt="preview" src="https://github.com/user-attachments/assets/08095166-f0df-453f-b365-add4a8e9910d" />
<img width="467" alt="preview" src="https://github.com/user-attachments/assets/747e6951-8b06-4424-a668-b0a3119a076d" />

---

## About

Two jars, one idea.

**MachineConstruct** (the engine) is a platform: a packet display layer, a viewer tracker, an anchor
lifecycle, an animation system, a model/GUI loader and a public API. It has no opinion about what a
machine *is*. **Foundry** (the content pack) is everything concrete — machines, items, recipes,
models — expressed as YAML the engine reads.

### Why packets

A machine that looks like fifty moving parts would be fifty entities on the server: ticked, saved to
the region file, dragged through chunk loads, duplicated by every dupe bug and lost to every entity
wipe. MachineConstruct sends the parts as **display-entity packets** instead:

- nothing exists server-side except one anchor block and a data blob in its PDC,
- a viewer tracker spawns and despawns parts per player by distance, so a machine costs nothing for
  players who cannot see it,
- an entity wipe, a chunk reload or a crash cannot damage a machine — it is re-sent from its data,
- animation is a transform recomputed per frame and pushed as metadata, not entity movement.

The anchor is a real block (chest, barrel or an invisible barrier) and does four jobs at once: the
interface you right-click, the persistence key, the chunk-load detector and the teardown trigger.
Break it and the whole structure disappears and hands back the placer item.

### What you author, and what you never write

Everything below is YAML. There is no code path that hard-codes a machine.

| Layer | What it gives you |
| --- | --- |
| `model:` | parts (block / head / item / text), parenting, offsets, scale, rotation |
| `animation:` | `spin` `bob` `pulse` `shake` `swing` `fill`, plus particles and sound, per state |
| `cues:` | one-shot timed scripts — move, hinge about a pivot, spin a drum, show/hide, glow, sound |
| `vars:` / `grid:` / `octagon:` / `templates:` | say a thing once, stamp it many times |
| `tiers:` | an upgrade ladder that re-skins the same model per tier |
| `gui:` / `skin:` | the sealed menu — layout, icons, every message |
| `recipes:` / `fuel:` | input → output with time, or a timed generator |
| `panel:` | a board with live `%placeholder%` text, states and physical buttons |
| `gacha:` | a coin-operated machine: rarities, pity, collections, capsules or reels |

### Things that make it feel like a real machine

- **Physical buttons.** A part with `button:` is ray-cast against its true oriented box, outlines in
  its own colour for the player looking at it, sinks when pressed and runs an action. Its caption
  sinks with it.
- **Cues.** A timeline over named parts: a lever that hinges on its mount, a capsule that rattles
  down a chute, a drum that coasts and then decelerates through two readable turns before landing.
- **Live text.** Panels re-resolve PlaceholderAPI text on a timer, per placed panel, with per-panel
  overrides, themes (whole block palettes), paging and layout.
- **Coins.** A tiered coin ladder of tagged player-head tokens, or any held item promoted to a
  currency, with commands to hand them out from votes, quests or crates.
- **Loot fed in-game.** An admin holds an item and adds it; the exact stack — custom NBT and all —
  is stored and later handed back as a prize.

## Built-in behaviours

The engine knows a handful of machine *kinds*; a YAML file opts into one.

- **Grinder** — installs typed spawners and accrues their loot over time, with auto-sell.
- **Quarry** — mines a configured strata table; tiers change speed and depth.
- **Chunk collector** — a vacuum hopper with per-chest routing and filters.
- **Trading hall / factory district** — virtual shopfronts and worker economies.
- **Jukebox** — a track library and playlists, audio over Simple Voice Chat.
- **Panel** — leaderboards, info boards, paged section text, buttons, themes.
- **Gacha** — capsule dispensers and slot machines: rarity roll with pity, collectible pieces,
  duplicate payouts, command rewards, broadcasts.

## Quick start

```bash
cd source
./gradlew build            # → engine/build/libs/MachineConstruct-<ver>.jar
                           #   content/build/libs/Foundry-<ver>.jar
```

Drop both jars in `plugins/`, start the server, and a `plugins/Foundry/machines/` folder appears.
Add a `.yml` there, run `/mc reload`, and `/mc give <machine>` hands you its placer item. YAML edits
are live; only engine Java changes need a restart.

Requires Paper (or a fork — developed against Leaf 1.21.x / 26.x) and PacketEvents. Everything else
— Vault, PlaceholderAPI, GriefPrevention — is optional and probed at runtime.

**Anything that makes a noise needs [PixelAudio](https://github.com/scrunc/pixelaudio)**, plus
[Simple Voice Chat](https://modrinth.com/plugin/simple-voice-chat) under it. This engine carries no
audio core of its own: jukeboxes, the radio, and machine sound effects that play real audio files all
go through PixelAudio's shared services, and its `plugins/PixelAudio/bin/ffmpeg` is what decodes a
track. Without it MachineConstruct loads and runs normally — tracks still download and save, machines
still work — everything simply plays silently, and the boot log says why. (Players also need the SVC
client mod to hear anything at all; that is the usual reason a correct server is still quiet.)

## Repository layout

```
source/engine/     MachineConstruct — the platform + public API, no concrete machines
source/content/    Foundry — machines, items, recipes as YAML + the glue that registers them
AUTHORING.md       how to write a machine: every key, with worked examples
DESIGN.md          the architecture spine and the decisions behind it
research/          notes on the plugins this replaces
```

Start with **[AUTHORING.md](AUTHORING.md)** — it is the manual, written for someone who has never
opened the Java.

## Status

In production use on a private network. The engine is stable; new machine kinds arrive as the server
needs them. Machines authored for it are data, so they survive engine upgrades.
