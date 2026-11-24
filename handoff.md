# FemMobs project handoff

## Goal

This project implements custom mob spawning, originally for the Forsaken arena mobs described in `C:\Users\texme\Documents\github\FemItems\arena-mobs-handoff.md`. It is not arena-specific: any number of arenas (named spawn areas with waves) can be configured, and any mob profile can be spawned on its own in front of an admin. Mob loot is handled by the separate FemItems plugin. FemMobs applies scoreboard tags that FemItems can match; it does not implement drops.

## Project and runtime

- Kotlin JVM plugin, built against Paper API `26.3.build.230-stable` (pinned to match the server).
- Java toolchain target: 25.
- Paper `paper-plugin.yml` plugin entry point, with `api-version: '26.3'` and `folia-supported: true`.
- Uses Folia region scheduling for spawning and global-region scheduling for wave timing. Mob deaths are observed via Bukkit's entity-death event.
- Paper plugin packaging is intended for PlugManX Paper-plugin loading; verify behavior with the server's installed PlugManX build.

## Files

- `build.gradle.kts`: Kotlin JVM and Paper API configuration.
- `src/main/resources/paper-plugin.yml`: Paper plugin declaration. The `/femmobs` Brigadier command is registered through Paper's lifecycle API.
- `src/main/resources/config.yml` (version 3): global spawn cap and spawn-menu distance, mob profiles, and `arenas.<key>` each with world, delay, and waves. The bundled `forsaken` arena has the five Forsaken waves. Version 1 and 2 configs are migrated automatically.
- `src/main/kotlin/dev/femmobs/MobProfile.kt`: editable profile parser and application of entity settings, attributes, gear, enchantments, tags, and potion effects; also the wave and arena data classes.
- `src/main/kotlin/dev/femmobs/Trader.kt`: trader/trade data, applying them to villagers and wandering traders, and `traders.yml` storage (items as base64 of Paper's `serializeAsBytes`).
- `src/main/kotlin/dev/femmobs/TraderFeature.kt`: `/femmobs trader` commands, the clickable step-by-step chat builder (per-player in-memory drafts), and events that stop tagged traders from gaining trades or changing jobs.
- `src/main/kotlin/dev/femmobs/Menu.kt`: chest menu holder whose slots carry their own click handlers.
- `src/main/kotlin/dev/femmobs/FemMobsPlugin.kt`: `/femmobs` commands, menus (main, arenas, waves, and spawn mobs), per-arena runs, despawn/removal tracking, orphan cleanup, config migration, and Folia scheduling.
- `README.md`: player/admin usage and config reference.

## Usage

1. Set `arena.world` in `config.yml` to the exact loaded world name.
2. In IntelliJ, run the shared **Clean Build Plugin** configuration. On Windows, double-click `build-plugin.bat` or run it from a terminal; it runs `clean build` with the installed Java 25 JDK. The output JAR bundles Kotlin runtime.
3. Install `build/libs/FemMobs-1.2.0.jar` in the server's `plugins` directory (remove older FemMobs jars) and fully restart. PlugManX unload/load/reload kept running the old classes of this Paper plugin in testing, so don't rely on it for updates.
4. Grant `femmobs.admin` explicitly in LuckPerms (for example, `/lp group admins permission set femmobs.admin true`). All `/femmobs` commands require this node; its default is false.
5. Use `/femmobs help` for instructions. `/femmobs gui` opens the main menu: **Arenas** (pick an arena, then spawn one wave or start/stop its campaign) and **Spawn mobs** (spawn any profile in front of you). `/femmobs start <arena>` starts a campaign; `/femmobs spawn <mob> [amount]` spawns a profile in front of you. `/femmobs trader create <name>` builds a custom villager/wandering trader in chat (items taken from your hand), saved to `traders.yml`; `/femmobs trader spawn <name>` spawns it in front of you.
6. Ensure FemItems has matching `entity-tag` values: `forsaken.grunt`, `forsaken.archer`, `forsaken.ravager`, `forsaken.dreadbound`, and `forsaken.champion`.

Mob profiles and arenas are fully configurable in `config.yml`; see `README.md` for every supported field. Arenas run independently and can run at the same time. Automatic waves advance once every mob in the current wave is gone (death or any non-unload removal), then wait the wave/arena delay. Menu-selected waves spawn once and do not advance the campaign. `/femmobs stop [arena]` removes mobs spawned by that run. Progress messages go only to the player who started the run (console log if started from console), never server-wide.

Each spawn group can spawn multiple copies, rotate them, or distribute them within a horizontal radius. The configured per-wave mob cap limits queued Folia tasks. Active tracking stores only UUIDs and releases them on death, removal, stop, reload, and disable. Wave mobs always have persistence required, so they cannot despawn. Orphaned wave mobs (tag `femmobs_<id>` with no matching run) are removed on chunk entity load and by a sweep near players after enable (`/femmobs cleanup` runs it manually). Free-spawned mobs get `femmobs.spawned` and are never auto-removed. Plugin code avoids static plugin references and cached inventories.

## Mob behavior

Profiles follow the supplied handoff's stats and equipment for Grunt, Archer, Ravager, and Dreadbound. Champion keeps the supplied 250 health, 12 attack damage, 0.28 speed, 0.5 knockback resistance, diamond gear, and Resistance I. Armor and weapon drops are disabled so custom equipment does not duplicate rewards. Mob drops remain available to the other plugin.

## Follow-up

The Paper API dependency is pinned to `26.3.build.230-stable`, the build the server runs. Build 258 added APIs (e.g. `Mob.setPersistenceRequired`) that crash with `NoSuchMethodError` on 230, so bump the pin only after the server is updated. Builds cleanly (no warnings) against it. Still to do: an in-game smoke test on both Paper and Folia, verify PlugManX lifecycle behavior, check that FemItems sees each scoreboard tag, and playtest the wave difficulty. The original handoff says the proposed balance has not yet been player-tested.
