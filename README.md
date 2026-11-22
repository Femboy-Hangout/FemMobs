# FemMobs

FemMobs spawns configurable arena waves on Paper and Folia 26.3. It is a Kotlin plugin for Java 25. It does not define loot: another plugin handles mob drops. FemMobs adds the configured scoreboard tags so that plugin can identify each mob.

## Install

1. Copy `build/libs/FemMobs-1.0.0.jar` into the server's `plugins` folder (or load it with your PlugManX version).
2. Start the server once. FemMobs creates `plugins/FemMobs/config.yml`.
3. Edit the arena world, mob profiles, equipment, attributes, effects, and wave coordinates in that config.
4. Run `/arenamobs reload` to apply config changes. Reload stops and removes mobs from a currently running FemMobs run.

The server must be Paper or Folia 26.3 and run Java 25. The plugin uses Paper's `paper-plugin.yml` format and Folia region schedulers.

## Commands

All commands require `femmobs.admin`, which defaults to operators.

| Command | What it does |
| --- | --- |
| `/arenamobs help` | Shows the in-game command guide. Running `/arenamobs` also shows it. |
| `/arenamobs gui` | Opens the wave menu. Each wave shows its name and every configured mob profile and coordinate. Click a wave to spawn that wave once. |
| `/arenamobs start` | Starts the automatic campaign at wave 1. The next wave starts after all mobs in the current wave die and the configured delay passes. |
| `/arenamobs status` | Shows whether a run is active and how many tagged wave mobs remain. |
| `/arenamobs stop` | Stops the current campaign or menu-spawned wave and removes its still-living mobs. |
| `/arenamobs reload` | Stops any active run, then reloads `config.yml`. |

Only one campaign or menu-spawned wave can run at once. Menu spawning is a one-wave action; it does not continue to the next wave automatically. To run the whole sequence, use `/arenamobs start`.

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

Each key under `arena.waves` is a wave ID. `name` is shown in the GUI. Every entry in `spawns` references a profile and gives its world coordinates. Repeat an entry to spawn multiple copies. Coordinates may include decimals; the bundled examples use `.5` to put mobs in the center of a block.

```yaml
arena:
  world: world
  wave-delay-seconds: 5
  waves:
    wave1:
      name: "Opening Patrol"
      spawns:
        - { profile: grunt, x: 106.5, y: 75, z: -2787.5 }
        - { profile: archer, x: 109.5, y: 75, z: -2791.5 }
```

The profile name must match a key under `mobs`. Wave order follows the order of entries in the YAML file. The arena world must already be loaded. If you change names, tags, or mob profiles, update the matching loot plugin configuration too.

## Build

- IntelliJ IDEA: run the shared **Clean Build Plugin** Gradle configuration.
- Windows: run `build-plugin.bat`, which invokes the Gradle wrapper with Java 25 and runs `clean build`.
- The output JAR bundles Kotlin's runtime, so the server does not require a separate Kotlin plugin.
