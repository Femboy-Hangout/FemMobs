# FemMobs

FemMobs spawns configurable arena waves on Paper and Folia 26.3. It is a Kotlin plugin for Java 25. It does not define loot: another plugin handles mob drops. FemMobs adds the configured scoreboard tags so that plugin can identify each mob.

## Install

1. Copy `build/libs/FemMobs-1.0.1.jar` into the server's `plugins` folder (or load it with your PlugManX version).
2. Start the server once. FemMobs creates `plugins/FemMobs/config.yml`.
3. Edit the arena world, mob profiles, equipment, attributes, effects, and wave coordinates in that config.
4. Run `/femmobs reload` to apply config changes. Reload stops the current wave and resets its tracking.

Older FemMobs configs are upgraded automatically. Existing legacy wave mob types and coordinates are converted to the new profile-based wave format; default editable profiles are added with the same FemItems tags.

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
| `/femmobs gui` | Opens the wave menu. Each wave shows its name and every configured mob profile and coordinate. Click a wave to spawn that wave once. |
| `/femmobs start` | Starts the automatic campaign at wave 1. The next wave starts after all mobs in the current wave die and the configured delay passes. |
| `/femmobs status` | Shows whether a run is active and how many tagged wave mobs remain. |
| `/femmobs stop` | Stops the current campaign or menu-spawned wave and removes its still-living mobs. |
| `/femmobs reload` | Stops any active run, then reloads `config.yml`. |

Only one campaign or menu-spawned wave can run at once. Menu spawning is a one-wave action; it does not continue to the next wave automatically. To run the whole sequence, use `/femmobs start`.

## Configure mob profiles

Edit `plugins/FemMobs/config.yml`. The bundled file documents all settings and contains the supplied Forsaken roster. A profile contains the entity type, visible name and color, scoreboard tags, attributes, equipment, enchantments, item drop chances, and potion effects.

| Profile field | Meaning |
| --- | --- |
| `entity-type` | Bukkit entity type such as `ZOMBIE`, `SKELETON`, or `WITHER_SKELETON`. |
| `name`, `name-color`, `name-visible` | Custom name, named Adventure color or `#RRGGBB`, and visibility. |
| `persistent` | Keeps the mob from despawning naturally. |
| `scoreboard-tags` | Tags added to each spawned entity. Include the matching FemItems `entity-tag` here. |
| `attributes` | Any attribute supported by that entity, using names such as `max-health`, `attack-damage`, `movement-speed`, `knockback-resistance`, `armor`, and `armor-toughness`. |
| `health` | Current health when spawned. If omitted, the mob starts at its configured max health. |
| `equipment` | Optional `main-hand`, `off-hand`, `helmet`, `chestplate`, `leggings`, and `boots` items. Set `material`, optional `amount`, an `enchantments` map, and `drop-chance` (0 to 1) per slot. |
| `effects` | List of potion `type`, `amplifier`, `duration-seconds`, `ambient`, `particles`, and `icon`. A duration of `-1` is infinite. |
| `invulnerable`, `glowing`, `silent`, `ai`, `can-pickup-items`, `fire-ticks` | Additional entity flags and initial fire duration. `ai` and pickup settings apply to mob entities. |

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

## Configure waves

Each key under `arena.waves` is a wave ID. `name` and optional `description` appear in the GUI. `delay-after-seconds` optionally overrides the default pause before the next campaign wave. Each spawn group references a mob profile and sets its location, amount, and optional facing and spread:

- `profile`: key under `mobs`.
- `x`, `y`, `z`: world coordinates; decimals are supported.
- `amount`: number of copies to spawn at this group (default 1).
- `yaw`, `pitch`: initial facing rotation in degrees (default 0).
- `spread`: random horizontal radius around the configured point (default 0, exact position). Copies are distributed within a circle.

The sum of all `amount` values in one wave must not exceed `arena.max-mobs-per-wave` (default 100). This setting can be raised for intentionally larger waves, up to 500, to bound memory and scheduler work.

```yaml
arena:
  world: world
  wave-delay-seconds: 5
  max-mobs-per-wave: 100
  waves:
    wave1:
      name: "Opening Patrol"
      description: "Two guards and an archer."
      delay-after-seconds: 8
      spawns:
        - { profile: grunt, x: 106.5, y: 75, z: -2787.5, amount: 2, yaw: 90, pitch: 0, spread: 2.5 }
        - { profile: archer, x: 109.5, y: 75, z: -2791.5, amount: 1, yaw: 0, pitch: 0, spread: 0 }
```

The profile name must match a key under `mobs`. Wave order follows the order of entries in the YAML file. The arena world must already be loaded. If you change names, tags, or mob profiles, update the matching loot plugin configuration too.

## Scheduling and cleanup

Folia region tasks spawn each mob in the region that owns its location. The plugin stores only UUIDs for mobs in the active run, removes those UUIDs when the mob dies, and clears run tracking on stop, reload, or plugin disable. There is no static plugin reference or cached menu inventory. The per-wave spawn cap bounds the number of simultaneous spawn tasks created by a config entry.

## Build

- IntelliJ IDEA: run the shared **Clean Build Plugin** Gradle configuration.
- Windows: run `build-plugin.bat`, which invokes the Gradle wrapper with Java 25 and runs `clean build`.
- The output JAR bundles Kotlin's runtime, so the server does not require a separate Kotlin plugin.
