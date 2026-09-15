# SmartSpawner — reference notes (for the Dimensional Grinder design)

Source: https://github.com/NighterDevelopment/SmartSpawner (read 2026-06-02). Gradle multi-module: `api` (public API + events) + `core` (impl). Paper/Folia.

## What it is
GUI-based **virtual spawner** management. A Smart Spawner generates a mob's drops + XP **without spawning any mobs** (pure performance play), accumulates them in an internal storage, and the player opens a chest GUI to collect / sell. Spawners **stack** in one block (up to 10 000) and drops scale with stack size.

## Spawner kinds
- **Smart spawner** — typed to a mob (zombie, blaze, …); generates that mob's configured loot + XP. No entities.
- **Item spawner** — generates a configured material directly (e.g. a "diamond spawner"); same GUI.
- **Vanilla spawner** — real Minecraft spawner (actually spawns mobs, no GUI, no stacking) — the opt-out.

## Generation loop (the core mechanic)
- `spawner_properties.default`: `min_mobs:1 max_mobs:4`, `range:16`, `delay:25s`, `max_storage_pages:1` (45 slots/page), `max_stored_exp:1000`, `max_stack_size:10000`.
- Every `delay`, **only if a player is within `range`**, it triggers: for each loot entry, roll `chance`, pick `amount` (min-max), then **multiply by Random(min_mobs..max_mobs) × stackSize**. XP accumulates up to `max_stored_exp`.
- So generation is **proximity-gated + timer-based** (not offline by default), and scales with stack size.
- Loot tables: `spawners_settings.yml` (per mob) / `item_spawners_settings.yml` (per material): `experience`, `head_texture`, `loot: { ITEM: {amount: min-max, chance: %, durability, potion_type(tipped arrows only)} }`. No potions/enchanted-book loot (only tipped arrows).

## GUIs (chest menus, layout-skinnable via `gui_layouts/`)
- **Main**: drop+XP summary, quick controls, metrics.
- **Storage**: paged item view, take items, **sell-all**, sort/filter.
- **Stacker**: precise stack add/remove.
- Bedrock: optional Floodgate FormUI.

## Stacking & breaking
- Stack by right-click (one), shift-right-click (bulk), or stacker GUI. Drops scale proportionally.
- **Mineable**: break with allowed tools (+ optional Silk Touch level) to collect the spawner item; `drop_chance` per mob; `sell_and_xp_break` auto-cashes-out on full removal.

## Economy / selling (the part we'd care about)
- `sell_integration`: currency = **VAULT** or **ExcellentEconomy**.
- Price source modes: `SHOP_ONLY | SHOP_PRIORITY | CUSTOM_ONLY | CUSTOM_PRIORITY`.
- Shop hooks for prices: **EconomyShopGUI(+Premium), ShopGUI+, zShop**. Custom prices via `item_prices.yml` (+ `default_price`).
- Sell-all from the storage GUI; XP claim to player.

## Hooks / compatibility
- **Economy**: Vault, ExcellentEconomy. **Shops**: EconomyShopGUI(+), ShopGUI+, zShop.
- **Protection**: WorldGuard, GriefPrevention, Lands, Towny, Residence, SimpleClaimSystem, HuskClaims, MinePlots (place/break/access gated).
- **Worlds**: Multiverse, Multiworld. **Skyblock**: SuperiorSkyblock2, Iridium. **RPG**: AuraSkills. **Mobs**: MythicMobs. Bedrock: Floodgate.

## Extras
- **Hopper** (`hopper.enabled`, default off): auto-transfer items from storage to a hopper below (`stack_per_transfer` ≤5, `check_delay`).
- **Hologram** above the block, **particles** (stack/activate/generate), action **logging** (file/JSON/Discord), bStats.

## Data / storage
- `database.mode`: **YAML | SQLITE | MYSQL** (+ `sync_across_servers` cross-server view on MySQL). `SpawnerManager` + `SpawnerFileHandler` + `DatabaseManager`/`SpawnerDatabaseHandler`; YAML→DB and SQLite→MySQL migrators included. Periodic save.

## Public API (`api` module)
- `SmartSpawnerAPI`: `getSpawnerByLocation(Location)`, `getSpawnerById(String)`, `getAllSpawners()`, `getSpawnerModifier(id)`.
- `SpawnerDataDTO` (read-only: id, location, entityType, material, stackSize, max stack, storage pages, min/max mobs, max exp, delay, isItemSpawner). `SpawnerDataModifier` (chainable setters + `applyChanges()`; stackSize read-only).
- Events: SpawnerPlace/Break/PlayerBreak/Remove/Explode/Stack/Sell/ExpClaim/TakeAll/DropAll/EggChange/OpenGUI.

## Commands / perms
- `/ss` `/spawner` `/smartspawner`: `give spawner|vanilla_spawner|item_spawner`, `list`, `prices`, `reload`, `hologram`, `near [r]`, `set <stack_size|range|delay>`, `clear holograms|ghost_spawners`.
- Perms: base `smartspawner.command.use` + per-command; features `stack`(true) `break`(true) `sellall`(true) `changetype`(op) `break.bypassdropchance`(op).

## Takeaways for the Dimensional Grinder
- Reusable ideas: **virtual generation (no entities)**, **internal paged storage**, **sell-all via economy/shop hook**, **proximity-gated timer**, **per-type loot YAML**, **stacking → drop scaling**, **clean data layer (YAML/SQLite/MySQL)**, **events API**.
- The grinder concept = one machine that holds *many* spawner types ("a dimension of spawners"), buys/stores them, runs the generation loop, and pools all loot into one storage to collect/sell — i.e. a SmartSpawner-style subsystem embedded in a MachineConstruct machine, but consolidated + upgrade-tiered.
