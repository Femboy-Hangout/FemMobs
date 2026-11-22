# FemMobs project handoff

## Goal

This project implements custom spawning for the Forsaken arena mobs described in `C:\Users\texme\Documents\github\FemItems\arena-mobs-handoff.md`. Mob loot is handled by the separate FemItems plugin. FemMobs applies scoreboard tags that FemItems can match; it does not implement drops.

## Project and runtime

- Kotlin JVM plugin, built against Paper API `26.3.build.+`.
- Java toolchain target: 25.
- Paper `paper-plugin.yml` plugin entry point, with `api-version: '26.3'` and `folia-supported: true`.
- Uses Folia region scheduling for spawning and global-region scheduling for wave timing. Mob deaths are observed via Bukkit's entity-death event.
- Paper plugin packaging is intended for PlugManX Paper-plugin loading; verify behavior with the server's installed PlugManX build.

## Files

- `build.gradle.kts`: Kotlin JVM and Paper API configuration.
- `src/main/resources/paper-plugin.yml`: Paper plugin declaration. The `/arenamobs` Brigadier command is registered through Paper's lifecycle API.
- `src/main/resources/config.yml`: arena world, delay, and the five wave rosters.
- `src/main/kotlin/dev/femmobs/MobProfile.kt`: editable profile parser and application of entity settings, attributes, gear, enchantments, tags, and potion effects.
- `src/main/kotlin/dev/femmobs/FemMobsPlugin.kt`: command handling, wave lifecycle, and Folia scheduling.
- `README.md`: player/admin usage and config reference.

## Usage

1. Set `arena.world` in `config.yml` to the exact loaded world name.
2. In IntelliJ, run the shared **Clean Build Plugin** configuration. On Windows, double-click `build-plugin.bat` or run it from a terminal; it runs `clean build` with the installed Java 25 JDK. The output JAR bundles Kotlin runtime.
3. Install `build/libs/FemMobs-1.0.0.jar` with PlugManX or by placing it in the server's `plugins` directory.
4. Use `/arenamobs help` for instructions. `/arenamobs gui` opens the wave list; clicking a wave spawns only that wave. `/arenamobs start` starts the automatic sequence. Commands require `femmobs.admin` (OP by default).
5. Ensure FemItems has matching `entity-tag` values: `forsaken.grunt`, `forsaken.archer`, `forsaken.ravager`, `forsaken.dreadbound`, and `forsaken.champion`.

Mob profiles and wave rosters are fully configurable in `config.yml`; see `README.md` for every supported profile field. Automatic waves advance after every spawned mob in the current wave dies, then wait `wave-delay-seconds`. Menu-selected waves spawn once and do not advance the campaign. `/arenamobs stop` removes mobs spawned during that run.

## Mob behavior

Profiles follow the supplied handoff's stats and equipment for Grunt, Archer, Ravager, and Dreadbound. Champion keeps the supplied 250 health, 12 attack damage, 0.28 speed, 0.5 knockback resistance, diamond gear, and Resistance I. Armor and weapon drops are disabled so custom equipment does not duplicate rewards. Mob drops remain available to the other plugin.

## Follow-up

Compile on Java 25 and resolve any API signature changes against the actual Paper/Folia 26.3 build in use. Then do an in-game smoke test on both Paper and Folia, verify PlugManX lifecycle behavior, check that FemItems sees each scoreboard tag, and playtest the wave difficulty. The original handoff says the proposed balance has not yet been player-tested.
