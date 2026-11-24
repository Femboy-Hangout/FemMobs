# FemMobs

FemMobs spawns configurable custom mobs on Paper and Folia 26.3: as waves in any number of arenas, or one at a time in front of you. It is a Kotlin plugin for Java 25. It does not define loot: another plugin handles mob drops. FemMobs adds the configured scoreboard tags so that plugin can identify each mob.

## Install

1. Copy `build/libs/FemMobs-1.2.1.jar` into the server's `plugins` folder, removing any older FemMobs jar, and restart the server. PlugManX can't reliably reload Paper plugins (`paper-plugin.yml`): it may keep running the old code.
2. Start the server once. FemMobs creates `plugins/FemMobs/config.yml`.
3. Edit mob profiles, equipment, attributes, effects, arenas, and wave coordinates in that config.
4. Run `/femmobs reload` to apply config changes. Reload stops every running arena first. If the new config has an error, the old one stays active and the error is shown.

Older FemMobs configs are upgraded automatically. A version 2 config's single `arena` section becomes `arenas.forsaken` with its world, delay, and waves unchanged; `max-mobs-per-wave` moves to the top level. Version 1 list-based waves are converted first.

The server must be Paper or Folia 26.3 and run Java 25. The plugin uses Paper's `paper-plugin.yml` format and Folia region schedulers.

## Commands

Every `/femmobs` command, including `help` and the GUI, requires the LuckPerms permission `femmobs.admin`. It defaults to false, so grant it explicitly to a group or user.

```text
/lp group admins permission set femmobs.admin true
/lp user PlayerName permission set femmobs.admin true
```

To revoke access, set the node to `false` for that group or user. Players without the node cannot see or execute `/femmobs`.

| Command | What it does |
| --- | --- |
| `/femmobs help` | Shows the in-game command guide. Running `/femmobs` also shows it. |
| `/femmobs gui` | Opens the menu. **Arenas** lists every arena; click one to see its waves, click a wave to spawn just that wave, or use **Start campaign** / **Stop arena** on the bottom row. **Spawn mobs** lists every mob profile; left-click spawns one a few blocks in front of you, shift-click spawns five. |
| `/femmobs start <arena>` | Starts that arena's automatic campaign at wave 1. The next wave starts after all mobs in the current wave are gone and the delay passes. The arena name can be left out when only one arena exists. |
| `/femmobs stop [arena]` | Stops one arena, or every arena without an argument, and removes the wave mobs it spawned. |
| `/femmobs spawn <mob> [amount]` | Spawns a mob profile (1 to 64 copies) a few blocks in front of you, facing you. |
| `/femmobs trader ...` | Builds, saves, and spawns custom villager and wandering traders. See [Custom traders](#custom-traders). |
| `/femmobs status` | Lists running arenas and how many of their mobs remain. |
| `/femmobs cleanup` | Removes leftover wave mobs from earlier runs (after a restart or plugin reload) within 64 blocks of any online player. |
| `/femmobs reload` | Stops all running arenas, then reloads `config.yml` and `traders.yml`. |

Different arenas can run at the same time; each arena runs one campaign or single wave at a time. A wave spawned from the menu does not continue to the next wave. Wave progress messages go only to the player who started the run (or to the console log), never to the whole server.

Mobs spawned from **Spawn mobs** or `/femmobs spawn` are ordinary mobs with the profile applied and the extra tag `femmobs.spawned`. They are not part of any arena run, so `stop` and `cleanup` leave them alone.

## Configure mob profiles

Edit `plugins/FemMobs/config.yml`. The bundled file documents all settings and contains the supplied Forsaken roster. A profile contains the entity type, visible name and color, scoreboard tags, attributes, equipment, enchantments, item drop chances, and potion effects.

| Profile field | Meaning |
| --- | --- |
| `entity-type` | Bukkit entity type such as `ZOMBIE`, `SKELETON`, or `WITHER_SKELETON`. Must be a spawnable living entity. |
| `name`, `name-color`, `name-visible` | Custom name, named Adventure color or `#RRGGBB`, and visibility. |
| `icon` | Optional item shown in the spawn menu. Defaults to the entity's spawn egg. |
| `persistent` | `true` keeps the mob from despawning naturally and saves it with the chunk. Arena wave mobs never despawn regardless. |
| `scoreboard-tags` | Tags added to each spawned entity. Include the matching FemItems `entity-tag` here. |
| `attributes` | Any attribute supported by that entity, using names such as `max-health`, `attack-damage`, `movement-speed`, `knockback-resistance`, `armor`, and `armor-toughness`. |
| `health` | Current health when spawned. If omitted, the mob starts at its configured max health. |
| `equipment` | Optional `main-hand`, `off-hand`, `helmet`, `chestplate`, `leggings`, and `boots` items. Set `material`, optional `amount`, an `enchantments` map, and `drop-chance` (0 to 1) per slot. |
| `effects` | List of potion `type`, `amplifier`, `duration-seconds`, `ambient`, `particles`, and `icon`. A duration of `-1` is infinite. |
| `invulnerable`, `glowing`, `silent`, `ai`, `can-pickup-items`, `fire-ticks` | Additional entity flags and initial fire duration. `ai` and pickup settings apply to mob entities. |
| `baby` | `true` spawns the baby form for mobs that have one (zombies, husks, piglins, animals). Default `false`. |

Mobs spawn without vanilla's random spawn data: no random babies, chicken jockeys, or random armor and weapons. Only the profile's settings and equipment apply.

Attribute and enchantment names use Bukkit names, with hyphens or underscores accepted for attributes and lowercase names for enchantments. Enchantment levels can exceed normal survival limits. Item `drop-chance` affects only vanilla equipment drops; FemItems or your loot plugin can still award configured rewards on death.

Example profile:

```yaml
mobs:
  arena_guard:
    entity-type: PIGLIN_BRUTE
    name: "Arena Guard"
    name-color: "#ff8800"
    name-visible: true
    persistent: true
    scoreboard-tags:
      - arena.guard
    attributes:
      max-health: 80
      attack-damage: 9
      movement-speed: 0.28
      knockback-resistance: 0.2
      armor: 8
      armor-toughness: 2
    health: 80
    invulnerable: false
    glowing: false
    silent: false
    ai: true
    can-pickup-items: false
    fire-ticks: 0
    equipment:
      main-hand:
        material: GOLDEN_AXE
        amount: 1
        enchantments:
          sharpness: 3
        drop-chance: 0.0
      helmet:
        material: GOLDEN_HELMET
        enchantments: { protection: 2 }
        drop-chance: 0.0
    effects: []
```

## Configure arenas and waves

Each key under `arenas` is an arena ID used by `/femmobs start <arena>`. An "arena" can be any named place with its own waves. Arena fields:

- `name`, `description`, `icon`: shown in the arena list (`description` and `icon` are optional; icon defaults to `NETHER_STAR`).
- `world`: exact name of the loaded world that holds this arena's coordinates.
- `wave-delay-seconds`: default pause after a wave is cleared before the next campaign wave.
- `waves`: the arena's waves, run in the order they appear in the file.

Each key under an arena's `waves` is a wave ID. `name` and optional `description` appear in the GUI. `delay-after-seconds` optionally overrides the arena's pause. Each spawn group references a mob profile and sets its location, amount, and optional facing and spread:

- `profile`: key under `mobs`.
- `x`, `y`, `z`: world coordinates; decimals are supported.
- `amount`: number of copies to spawn at this group (default 1).
- `yaw`, `pitch`: initial facing rotation in degrees (default 0).
- `spread`: random horizontal radius around the configured point (default 0, exact position). Copies are distributed within a circle.

The sum of all `amount` values in one wave must not exceed the top-level `max-mobs-per-wave` (default 100, maximum 500). `spawn-menu.distance` (default 3, range 1 to 16) sets how many blocks in front of you free spawns appear.

```yaml
max-mobs-per-wave: 100
spawn-menu:
  distance: 3.0
arenas:
  forsaken:
    name: "Forsaken Arena"
    description: "Five waves of the Forsaken."
    icon: NETHER_STAR
    world: world
    wave-delay-seconds: 5
    waves:
      wave1:
        name: "Opening Patrol"
        description: "Two guards and an archer."
        delay-after-seconds: 8
        spawns:
          - { profile: grunt, x: 106.5, y: 75, z: -2787.5, amount: 2, yaw: 90, pitch: 0, spread: 2.5 }
          - { profile: archer, x: 109.5, y: 75, z: -2791.5, amount: 1, yaw: 0, pitch: 0, spread: 0 }
  crypt:
    name: "Old Crypt"
    world: world_nether
    wave-delay-seconds: 10
    waves:
      wave1:
        name: "Restless Dead"
        spawns:
          - { profile: grunt, x: 20.5, y: 64, z: 40.5, amount: 4, spread: 3 }
```

Profile names must match keys under `mobs`. If you change names, tags, or mob profiles, update the matching loot plugin configuration too.

## Custom traders

Build villagers and wandering traders with your own trades, save them, and spawn them in front of you. Everything is done in chat with clickable buttons; nothing has to be typed into a config file.

| Command | What it does |
| --- | --- |
| `/femmobs trader create <name>` | Starts building a new trader. Names use `a-z`, `0-9`, `_` and `-`. |
| `/femmobs trader edit <name>` | Reopens a saved trader in the builder. |
| `/femmobs trader spawn <name>` | Spawns a saved trader a few blocks in front of you, facing you. |
| `/femmobs trader apply <name>` | Replaces the trades of the villager or wandering trader you're looking at (within 8 blocks). Its look and level are kept. |
| `/femmobs trader list` | Lists saved traders with Spawn and Edit buttons. |
| `/femmobs trader delete <name>` | Deletes a saved trader. Already spawned traders keep their trades. |
| `/femmobs trader` | Shows your current draft again, or this command list. |

The builder shows one part at a time. Click a part in the step bar to jump to it, or use **Back** / **Next**:

1. **Type**: villager or wandering trader.
2. **Profession**: armorer to weaponsmith (villagers only; unemployed and nitwit can't trade).
3. **Biome look**: desert, jungle, plains, savanna, snow, swamp, taiga (villagers only).
4. **Level**: Novice to Master badge (villagers only).
5. **Name & behavior**: optional name (MiniMessage colors work, e.g. `<gold>Bob`), and toggles for *Stay in place* (no AI; trading still works), *Can't be hurt*, *Silent*, and *Never despawns*.
6. **Trades**: add, edit, reorder, or remove trades. For each trade, hold an item and click **Use hand** to set **Cost 1**, optional **Cost 2**, and **Result**. The item is copied exactly, including amount, name, lore, enchantments, and custom plugin items. Each trade also has **Uses** (unlimited by default, because summoned traders can't restock), **Villager XP**, and **Fixed price** (on by default: reputation, discounts, and demand never change the price).
7. **Review**: summary, **Save**, and **Spawn preview** (spawns the draft without saving).

Drafts live in memory until you save or discard them. Saved traders are written to `plugins/FemMobs/traders.yml`; items are stored in Paper's binary item format so they come back exactly, which is why trades are edited in-game rather than in the file.

Spawned and applied traders get the tag `femmobs.trader`. Tagged villagers never gain random trades on level-up and never lose or change their job, and villagers are given the XP their level needs so they keep their profession without a job site. Wandering traders with *Never despawns* have no despawn timer.

## Scheduling and cleanup

Folia region tasks spawn each mob in the region that owns its location. Each running arena stores only the UUIDs of its living mobs and a unique scoreboard tag (`femmobs_<id>`) placed on them. A mob stops counting toward its wave when it dies or is removed any other way (void, `/kill`, despawn), so a wave cannot get stuck waiting for a mob that no longer exists. Unloading a chunk does not count: the wave waits for those mobs to come back. If a husk or zombie converts in water, the converted mob takes its place in the wave.

Wave mobs left behind when a run no longer exists (server restart, crash, or plugin reload) are removed automatically when their chunk loads, and by a sweep near online players one second after the plugin enables. `/femmobs cleanup` runs that sweep manually. On Paper, disabling the plugin removes running wave mobs right away; Folia doesn't allow that during shutdown, so on Folia they are removed by the load-time cleanup instead.

There is no static plugin reference or cached menu inventory. The per-wave spawn cap bounds the number of simultaneous spawn tasks created by a config entry.

## Build

- IntelliJ IDEA: run the shared **Clean Build Plugin** Gradle configuration.
- Windows: run `build-plugin.bat`, which invokes the Gradle wrapper with Java 25 and runs `clean build`.
- The output JAR bundles Kotlin's runtime, so the server does not require a separate Kotlin plugin.
