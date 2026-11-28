# FemMobs

FemMobs spawns configurable custom mobs on Paper and Folia 26.3: as waves in any number of arenas, or one at a time in front of you. It is a Kotlin plugin for Java 25. It does not define loot: another plugin handles mob drops. FemMobs adds the configured scoreboard tags so that plugin can identify each mob.

## Install

1. Copy `build/libs/FemMobs-1.8.0.jar` into the server's `plugins` folder, removing any older FemMobs jar, and restart the server. PlugManX can't reliably reload Paper plugins (`paper-plugin.yml`): it may keep running the old code.
2. Start the server once. FemMobs creates `plugins/FemMobs/config.yml`.
3. Edit mob profiles, equipment, attributes, effects, arenas, and wave coordinates in that config.
4. Run `/femmobs reload` to apply config changes. Reload stops every running arena first. If the new config has an error, the old one stays active and the error is shown.

Older FemMobs configs are upgraded automatically. Version 3 configs get the example `invasion` event and `max-event-radius` added (version 4). A version 2 config's single `arena` section becomes `arenas.forsaken` with its world, delay, and waves unchanged; `max-mobs-per-wave` moves to the top level. Version 1 list-based waves are converted first.

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
| `/femmobs gui` | Opens the menu. **Events** lists server events; click one, pick a radius, and it starts around you. **Arenas** lists every arena; click one to see its waves, click a wave to spawn just that wave, or use **Start campaign** / **Stop arena** on the bottom row. **Spawn mobs** lists every mob profile; left-click spawns one a few blocks in front of you, shift-click spawns five. |
| `/femmobs start <arena>` | Starts that arena's automatic campaign at wave 1. The next wave starts after all mobs in the current wave are gone and the delay passes. The arena name can be left out when only one arena exists. |
| `/femmobs stop [arena]` | Stops one arena, or every arena without an argument, and removes the wave mobs it spawned. |
| `/femmobs spawn <mob> [amount]` | Spawns a mob profile (1 to 64 copies) a few blocks in front of you, facing you. |
| `/femmobs event start <event> <radius>` | Starts a server event centered on you. Mobs spawn on the ground up to `<radius>` blocks away in every direction. See [Server events](#server-events). |
| `/femmobs event stop [event]` | Ends one event, or every event without an argument, and removes its mobs. |
| `/femmobs event schedule [now]` | Shows the automatic event schedule (enabled or not, next start, chances), or with `now` starts a scheduled event immediately for testing. |
| `/femmobs event list` | Lists configured events and whether they're running. |
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
| `equipment` | Armor pieces can also have `trim: { material: gold, pattern: rib }` and leather a `color: '#RRGGBB'`. Blocks (e.g. `MAGMA_BLOCK`) can be worn as helmets. Optional `main-hand`, `off-hand`, `helmet`, `chestplate`, `leggings`, and `boots` items. Set `material`, optional `amount`, an `enchantments` map, and `drop-chance` (0 to 1) per slot. |
| `effects` | List of potion `type`, `amplifier`, `duration-seconds`, `ambient`, `particles`, and `icon`. A duration of `-1` is infinite. |
| `invulnerable`, `glowing`, `silent`, `ai`, `can-pickup-items`, `fire-ticks` | Additional entity flags and initial fire duration. `ai` and pickup settings apply to mob entities. |
| `xp` | Experience dropped on death, replacing the vanilla amount (bundled: grunt/archer 10, ravager 25, Dreadbound 80, Champion 150, bosses 250, minions 5). |
| `slime-size` | Size for slimes and magma cubes (1 = small). |
| `glow-color` | Colors the glowing outline (needs `glowing: true`), e.g. `gold`, `dark_purple`. Uses a scoreboard team; Folia has no scoreboard API, so there it stays white. |
| `boss` | Turns the mob into a boss: abilities, particles, its own health bar, and a death announcement. See [Bosses](#bosses). |
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

## Bosses

Any mob profile can get a `boss:` section. The bundled bosses, each with a 15-minute battle event:

| Boss | Look | Fights with |
| --- | --- | --- |
| **The Molten Warden** | 1.5x husk, gold-trimmed netherite, magma-block head, mace | Fire slam, fireball spreads; below 50% a fire aura and Molten Spawn magma cubes |
| **The Cosmic Wraith** | Wither skeleton, purple amethyst-trimmed leather, glass head | Teleports behind you, blindness, lightning; levitation below 60%, two invisible Cosmic Shades below 30% |
| **The Hollow Reaper** | 1.5x skeleton in black leather, netherite hoe as a scythe | Wither and darkness auras, a slowing slam; Lost Soul vexes below 70% |
| **The Frost Jarl** | 1.6x stray, diamond-trimmed iron, blue-ice head, diamond axe | Freezing slam, slowness and blizzard auras; Frost Wisp archers below 50% |
| **The Venom Stalker** | 1.3x bogged in emerald-trimmed dark green leather with a blade | Poison I aura, pounces behind you; below 50% vanishes (invisible) and speeds up; Venom Spiders (cave spiders) below 40% |
| **The Storm Ronin** | Fast zombie, copper bolt-trimmed chainmail, lightning-rod head, netherite sword | Lightning, dashes behind you, a knockback slam; Speed II below 50% |

The Dreadbound and the Champion have a `boss:` section too, for their health bar and kill announcement, but no abilities, so they fight as before. Both glow in their own color, leave particle trails, and show a health bar to players within 40 blocks (`health-bar-range`). When one dies, the server is told who landed the final blow (`death-message`, placeholders `<mob>` and `<killer>`).

```yaml
    boss:
      health-bar: RED            # boss bar color, or none
      particle: FLAME            # any Bukkit particle name
      particle-count: 4
      death-message: "<gold>🔥 <yellow><killer></yellow> struck the final blow on <bold><mob></bold>!"
      abilities:
        - { type: slam, cooldown: 9, radius: 5, damage: 7, power: 1.0, fire-seconds: 4, message: "<gold><mob> slams the ground!" }
        - { type: summon, cooldown: 25, below-health: 50, summon: molten_spawn, count: 2, max-alive: 4 }
```

| Ability | What it does | Main options |
| --- | --- | --- |
| `slam` | Damages and knocks back every player within `radius`, optionally setting them on fire. | `radius`, `damage`, `power`, `fire-seconds` |
| `fireball` | Shoots small fireballs at its target. | `count` (1 to 5, fanned out), `min-range` (only at targets at least this far) |
| `aura` | Gives every player within `radius` a potion effect and/or fire and damage. | `effect`, `level`, `duration`, `fire-seconds`, `damage` |
| `summon` | Spawns minions (any mob profile) around itself. Minions die with the boss and belong to the same event or arena run. | `summon`, `count`, `max-alive`, `radius` |
| `teleport` | Teleports behind its target. | `range` |
| `lightning` | Strikes its target with lightning. | `damage`, `fire-seconds` |
| `empower` | Gives the boss itself a potion effect (e.g. Speed) while it lasts. | `effect`, `level`, `duration` |

Every ability has `cooldown` (seconds), `range` (how far its target may be, default 16), `below-health` (only used at or below this % of health, default 100) and an optional `message` shown in the action bar to nearby players. `slam`, `aura` and `lightning` can also apply an `effect`, `fire-seconds` and `freeze-seconds` (powder-snow freezing) to the players they hit, and `particle` changes an ability's look. A boss uses at most one ability per half second, targeting the player it is fighting or the nearest survival/adventure player. Bosses work anywhere: events (`boss:` in an event), arena waves, and the spawn menu. They keep their behavior when their chunk unloads and reloads.

Loot comes from FemItems: each boss and minion has its own scoreboard tag (`forsaken.<profile>`, e.g. `forsaken.frost_jarl`, `forsaken.lost_soul`) for FemItems drop rules.

## Server events

An event spawns mobs around wherever you're standing, tells the whole server roughly where, and removes whatever is left when time runs out. Events are templates in `config.yml` under `events`; the place is chosen when you start one.

Start one with `/femmobs event start invasion 15` (mobs spawn up to 15 blocks from you in every direction, a circle 30 blocks across) or from **/femmobs gui â†’ Events**, which offers 10 to 150 block radii. The radius must be between 5 and `max-event-radius` (default 200). Each event can run once at a time; different events can run together.

While an event runs:

- **Spawning**: each mob goes to a random spot in the circle, on solid ground near your height (the surface, or open floor near you in caves and the Nether), never in water or lava, and at least 3 blocks from any player. Chunks load in the background first.
- **Announcements** go to everyone: a start message with the rounded location and biome (e.g. *around X 1200, Z -2800 (Plains)*), time warnings, and an end message.
- **Boss bar**: players within the circle (plus 16 blocks) see the event name, time left, and mobs remaining.
- **End**: when time runs out, or, in `fixed` mode, as soon as every mob is killed. Remaining mobs are removed and a one-line leaderboard of the top killers is announced (`ðŸ† Top hunters: Lucy 14 Â· Max 9 Â· Sam 6`).

Event fields:

| Field | Meaning |
| --- | --- |
| `name`, `icon` | Shown in announcements and the menu. |
| `mode` | `fixed` (default): spawn `mobs` once; the event ends early when all are killed. `keep-population`: keep about `mobs` alive, spawning replacements every `respawn-seconds` (up to 5 at a time). |
| `mobs` | Fixed: total mobs. Keep population: how many stay alive. At most `max-mobs-per-wave`. Ignored when the roster uses `amount`. |
| `duration-seconds` | How long the event lasts (10 to 86400). |
| `roster` | Either `{ profile, weight }` entries, where each mob is picked at random and weights are relative chances, or `{ profile, amount }` entries for exact numbers (the total is their sum; in keep-population mode each profile is kept at its amount). Use one style per event. |
| `boss` | Optional profile that spawns once at the start, on top of the roster. For no boss, leave it out or write `boss: none`. |
| `warnings-seconds` | Time-left announcements, e.g. `[300, 60]`. |
| `boss-bar-color` | `PINK`, `BLUE`, `RED`, `GREEN`, `YELLOW`, `PURPLE`, or `WHITE`. |
| `leaderboard-size` | How many top killers to list at the end (0 hides it). |
| `glowing` | Event mobs get a glowing outline visible through blocks so players can find them (default `true`). Arena and spawned mobs only glow if their profile sets `glowing: true`. |
| `rewards` | Participation rewards: console commands run when the event is won (cleared, or survived to the end in `keep-population` mode). `everyone` runs for each player who damaged the event's mobs, `top` once more for the player with the most kills. Only given when at least `min-players` (default 2) took part, so solo runs get just the mob drops and XP. `<player>` is replaced with the player's name, so loot can come from FemItems too (`femitems give <player> custom:...`). |
| `location-rounding` | Coordinates in messages are rounded to this many blocks (default 50). |
| `messages` | Optional MiniMessage overrides for `start`, `warning`, `end-time`, `end-cleared`, `end-stopped`, `leaderboard`, `leaderboard-entry`, and `boss-bar`. Placeholders: `<event>`, `<location>`, `<time>`, `<mobs>`, `<radius>`; the leaderboard adds `<entries>`, `<player>`, `<kills>`. The bundled config lists the defaults. |

Event mobs keep their profile tags (FemItems loot works) and also get `femmobs.event.<event>`, so FemItems can give event-only drops. They never despawn on their own while the event runs, and leftovers after a restart are removed by the same cleanup as arena mobs.

**Drop cleanup.** Items dropped by an event's regular mobs (including FemItems loot) are tracked. When the event ends the server is told they vanish in `drop-cleanup-seconds` (default 60, `0` keeps them), and whatever nobody picked up is then removed. Drops from bosses (the event's `boss` and any mob with a `boss:` section) are never removed, and tracked drops never merge with other items, so players' own items are never touched.

### Automatic events

`event-schedule` starts events by itself. It is **disabled** by default; set `enabled: true` and run `/femmobs reload`.

| Field | Meaning |
| --- | --- |
| `every-minutes`, `random-minutes` | Time between events, give or take a random amount (default 60 ± 10). The next start time survives restarts. |
| `min-players` | Skip (and look again 5 minutes later) when fewer players are online (default 3). Also skipped while any event is running. |
| `world`, `center-x`, `center-z`, `min-distance`, `max-distance` | Events start at a random spot this many blocks from the center (default 200 to 500 from 0 0 in `world`), on open ground (never water or lava). |
| `radius` | Event radius for scheduled events (default 30). |
| `events` | Relative chances per event key, e.g. `grunt_invasion: 35`, `cosmic_wraith_battle: 2`. |

Scheduled events announce their **exact** coordinates and biome so players can head straight there. `/femmobs event schedule` shows the state, the next start, and each event's chance; `/femmobs event schedule now` starts one immediately (ignoring `enabled` and `min-players`) for testing.

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
