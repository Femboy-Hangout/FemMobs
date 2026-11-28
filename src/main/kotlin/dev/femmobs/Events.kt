package dev.femmobs

import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.suggestion.SuggestionProvider
import io.papermc.paper.command.brigadier.CommandSourceStack
import io.papermc.paper.command.brigadier.Commands
import io.papermc.paper.threadedregions.scheduler.ScheduledTask
import net.kyori.adventure.bossbar.BossBar
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver
import org.bukkit.Bukkit
import org.bukkit.HeightMap
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Tag
import org.bukkit.World
import org.bukkit.command.CommandSender
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Entity
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.event.entity.EntityDeathEvent
import org.bukkit.event.entity.EntityTransformEvent
import org.bukkit.event.entity.ItemMergeEvent
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.atomic.AtomicInteger
import java.util.logging.Level
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

enum class EventMode { FIXED, KEEP_POPULATION }

/** A roster entry: either a relative [weight] for random picks, or an exact [amount]. */
class RosterEntry(val profile: MobProfile, val weight: Int, val amount: Int?)

/** Messages are MiniMessage text; see [EventSpec] placeholders in the config comments. */
class EventMessages(
    val start: String,
    val warning: String,
    val endTime: String,
    val endCleared: String,
    val endStopped: String,
    val leaderboard: String,
    val leaderboardEntry: String,
    val bossBar: String,
    val dropCleanup: String,
)

/** A server event template. Where it happens is chosen when it starts: around the player, with a radius. */
class EventSpec(
    val key: String,
    val name: String,
    val icon: Material,
    val mode: EventMode,
    /** Fixed: total mobs spawned. Keep population: how many stay alive at once. With exact amounts: their sum. */
    val mobs: Int,
    val respawnSeconds: Long,
    val durationSeconds: Long,
    val roster: List<RosterEntry>,
    val boss: MobProfile?,
    val warningsSeconds: List<Long>,
    val bossBarColor: BossBar.Color,
    val leaderboardSize: Int,
    val locationRounding: Int,
    /** Event mobs get the glowing outline so players can find them through walls and terrain. */
    val glowing: Boolean,
    /** Seconds after the event ends before regular mobs' unclaimed drops are removed (0 = never). */
    val dropCleanupSeconds: Int,
    val messages: EventMessages,
) {
    /** True when the roster lists exact amounts instead of weights. */
    val exact: Boolean get() = roster.first().amount != null

    fun rosterText(): String =
        if (exact) roster.joinToString(", ") { "${it.amount}x ${it.profile.name}" }
        else roster.joinToString(", ") { "${it.profile.name} (${it.weight * 100 / roster.sumOf { r -> r.weight }}%)" }

    fun pickProfile(): MobProfile {
        var roll = ThreadLocalRandom.current().nextInt(roster.sumOf { it.weight })
        for (entry in roster) {
            roll -= entry.weight
            if (roll < 0) return entry.profile
        }
        return roster.last().profile
    }
}

private val DEFAULT_MESSAGES = EventMessages(
    start = "<gold>⚔ <bold><event></bold></gold> <yellow>has begun around <white><location></white>! <time> to fight them off.",
    warning = "<gold>⚔ <event>:</gold> <yellow><time> left, <white><mobs></white> mobs remain!",
    endTime = "<gray>⚔ <event> is over. The remaining mobs have retreated.",
    endCleared = "<green>⚔ <event>: every mob was defeated!",
    endStopped = "<gray>⚔ <event> was stopped.",
    leaderboard = "<gold>🏆 Top hunters:</gold> <entries>",
    leaderboardEntry = "<yellow><player></yellow> <white><kills></white>",
    bossBar = "<gold>⚔ <event></gold> <gray>·</gray> <white><time> left</white> <gray>·</gray> <red><mobs> mobs</red>",
    dropCleanup = "<gray>⚔ Leftover drops from <event> vanish in <seconds> seconds.",
)

fun loadEvent(key: String, section: ConfigurationSection, profiles: Map<String, MobProfile>, maxMobs: Int): EventSpec {
    val path = "events.$key"
    fun profile(name: String, at: String) = profiles[name] ?: throw IllegalArgumentException("Unknown profile '$name' at $at")
    val mode = when (val raw = section.getString("mode", "fixed")!!.lowercase().replace('_', '-')) {
        "fixed" -> EventMode.FIXED
        "keep-population" -> EventMode.KEEP_POPULATION
        else -> throw IllegalArgumentException("$path.mode must be 'fixed' or 'keep-population', not '$raw'")
    }
    val duration = section.getLong("duration-seconds", 600)
    require(duration in 10..86_400) { "$path.duration-seconds must be between 10 and 86400" }
    val roster = section.getMapList("roster").mapIndexed { index, row ->
        val at = "$path.roster[$index]"
        val name = row["profile"]?.toString() ?: throw IllegalArgumentException("Missing profile at $at")
        require(!(row.containsKey("weight") && row.containsKey("amount"))) { "Use either weight or amount, not both, at $at" }
        val amount = if (row.containsKey("amount")) {
            row["amount"]?.toString()?.toIntOrNull()?.takeIf { it > 0 } ?: throw IllegalArgumentException("amount must be a number above 0 at $at")
        } else null
        val weight = row["weight"]?.toString()?.toIntOrNull() ?: 1
        require(weight > 0) { "weight must be above 0 at $at" }
        RosterEntry(profile(name, at), weight, amount)
    }
    require(roster.isNotEmpty()) { "$path.roster needs at least one profile" }
    require(roster.all { it.amount != null } || roster.none { it.amount != null }) {
        "$path.roster must use amount on every entry (exact numbers) or on none (random by weight)"
    }
    // With exact amounts the total is their sum and `mobs` is ignored.
    val mobs = if (roster.first().amount != null) roster.sumOf { it.amount!! } else section.getInt("mobs", 20)
    require(mobs in 1..maxMobs) { "$path spawns $mobs mobs; it must be between 1 and max-mobs-per-wave ($maxMobs)" }
    val icon = section.getString("icon")?.let { name ->
        Material.matchMaterial(name)?.takeIf { it.isItem && !it.isAir } ?: throw IllegalArgumentException("Unknown or non-item icon '$name' in $path.icon")
    } ?: Material.IRON_SWORD
    val color = section.getString("boss-bar-color", "red")!!.uppercase().let { name ->
        runCatching { BossBar.Color.valueOf(name) }.getOrElse { throw IllegalArgumentException("Unknown boss-bar-color '$name' in $path") }
    }
    val msg = section.getConfigurationSection("messages")
    fun message(key: String, default: String) = msg?.getString(key) ?: default
    return EventSpec(
        key = key,
        name = section.getString("name", key)!!,
        icon = icon,
        mode = mode,
        mobs = mobs,
        respawnSeconds = section.getLong("respawn-seconds", 5).coerceIn(1, 3600),
        durationSeconds = duration,
        roster = roster,
        // No boss: leave the line out, or write `boss: none`.
        boss = section.getString("boss")?.takeIf { it.isNotBlank() && !it.equals("none", ignoreCase = true) }?.let { profile(it, "$path.boss") },
        warningsSeconds = section.getLongList("warnings-seconds").filter { it in 1 until duration }.sortedDescending(),
        bossBarColor = color,
        leaderboardSize = section.getInt("leaderboard-size", 3).coerceIn(0, 10),
        locationRounding = section.getInt("location-rounding", 50).coerceIn(1, 1000),
        glowing = section.getBoolean("glowing", true),
        dropCleanupSeconds = section.getInt("drop-cleanup-seconds", 60).coerceIn(0, 3600),
        messages = EventMessages(
            start = message("start", DEFAULT_MESSAGES.start),
            warning = message("warning", DEFAULT_MESSAGES.warning),
            endTime = message("end-time", DEFAULT_MESSAGES.endTime),
            endCleared = message("end-cleared", DEFAULT_MESSAGES.endCleared),
            endStopped = message("end-stopped", DEFAULT_MESSAGES.endStopped),
            leaderboard = message("leaderboard", DEFAULT_MESSAGES.leaderboard),
            leaderboardEntry = message("leaderboard-entry", DEFAULT_MESSAGES.leaderboardEntry),
            bossBar = message("boss-bar", DEFAULT_MESSAGES.bossBar),
            dropCleanup = message("drop-cleanup", DEFAULT_MESSAGES.dropCleanup),
        ),
    )
}

/** Automatic events: a weighted random event at a random spot a set distance from a center (usually 0 0). */
class ScheduleSpec(
    val enabled: Boolean,
    val everyMinutes: Long,
    val randomMinutes: Long,
    val minPlayers: Int,
    val world: String,
    val centerX: Double,
    val centerZ: Double,
    val minDistance: Int,
    val maxDistance: Int,
    val radius: Int,
    /** Event key to relative chance. */
    val weights: Map<String, Int>,
)

fun loadSchedule(section: ConfigurationSection?, events: Map<String, EventSpec>, maxRadius: Int): ScheduleSpec {
    val path = "event-schedule"
    val weights = LinkedHashMap<String, Int>()
    section?.getConfigurationSection("events")?.getKeys(false)?.forEach { key ->
        require(key.lowercase() in events) { "$path.events.$key isn't an event under 'events'" }
        val weight = section.getInt("events.$key")
        require(weight >= 0) { "$path.events.$key must be 0 or more" }
        if (weight > 0) weights[key.lowercase()] = weight
    }
    val minDistance = section?.getInt("min-distance", 200) ?: 200
    val maxDistance = section?.getInt("max-distance", 500) ?: 500
    require(minDistance in 0..30_000_000 && maxDistance >= minDistance) { "$path: max-distance must be at least min-distance" }
    val radius = section?.getInt("radius", 30) ?: 30
    require(radius in EventFeature.MIN_RADIUS..maxRadius) { "$path.radius must be between ${EventFeature.MIN_RADIUS} and max-event-radius ($maxRadius)" }
    val enabled = section?.getBoolean("enabled", false) ?: false
    require(!enabled || weights.isNotEmpty()) { "$path.events needs at least one event with a chance above 0" }
    return ScheduleSpec(
        enabled = enabled,
        everyMinutes = (section?.getLong("every-minutes", 180) ?: 180).coerceIn(5, 10_080),
        randomMinutes = (section?.getLong("random-minutes", 30) ?: 30).coerceIn(0, 1_440),
        minPlayers = (section?.getInt("min-players", 3) ?: 3).coerceAtLeast(0),
        world = section?.getString("world", "world") ?: "world",
        centerX = section?.getDouble("center-x", 0.0) ?: 0.0,
        centerZ = section?.getDouble("center-z", 0.0) ?: 0.0,
        minDistance = minDistance,
        maxDistance = maxDistance,
        radius = radius,
        weights = weights,
    )
}

/**
 * Runs server events: mobs spawn on the ground in a circle around where an admin stood, the server is told
 * roughly where, a boss bar shows progress to players nearby, and leftover mobs are removed when time runs out.
 */
class EventFeature(private val plugin: FemMobsPlugin) {
    private enum class EndReason { TIME, CLEARED, STOPPED, FAILED }

    private inner class ActiveEvent(
        val spec: EventSpec, val world: World,
        val centerX: Double, val centerY: Double, val centerZ: Double,
        val radius: Int, val locationText: String, val starter: UUID?,
    ) {
        /** The event's `boss:` mob(s); their drops are never cleaned up. */
        val bossIds: MutableSet<UUID> = ConcurrentHashMap.newKeySet()
        /** Item entities dropped by this event's regular mobs, removed a while after the event ends. */
        val droppedItems: MutableSet<UUID> = ConcurrentHashMap.newKeySet()
        val tag = EVENT_TAG_PREFIX + UUID.randomUUID().toString().replace("-", "")
        val alive: MutableSet<UUID> = ConcurrentHashMap.newKeySet()
        val pending = AtomicInteger(0)
        val spawned = AtomicInteger(0)
        /** Profile key of each living mob, and spawns in flight per profile (for exact amounts). */
        val profileOf = ConcurrentHashMap<UUID, String>()
        val pendingByProfile = ConcurrentHashMap<String, AtomicInteger>()

        fun countOf(profile: MobProfile): Int =
            profileOf.values.count { it == profile.key } + (pendingByProfile[profile.key]?.get() ?: 0)
        val kills = ConcurrentHashMap<UUID, AtomicInteger>()
        val killerNames = ConcurrentHashMap<UUID, String>()
        val viewers: MutableSet<UUID> = ConcurrentHashMap.newKeySet()
        val warned: MutableSet<Long> = ConcurrentHashMap.newKeySet()
        val endsAt = System.currentTimeMillis() + spec.durationSeconds * 1000
        val bar: BossBar = BossBar.bossBar(Component.text(spec.name), 1f, spec.bossBarColor, BossBar.Overlay.PROGRESS)
        @Volatile var active = true
        @Volatile var task: ScheduledTask? = null
        @Volatile var secondsSinceRespawn = 0L

        // Rounded up, so a fresh 15-minute event says "15 minutes", not "14 min 59 s".
        val secondsLeft: Long get() = ((endsAt - System.currentTimeMillis() + 999) / 1000).coerceAtLeast(0)

        /** Mobs still to deal with: alive plus any still spawning. */
        val remainingMobs: Int get() = alive.size + pending.get()

        fun resolver(vararg extra: TagResolver) = TagResolver.resolver(
            Placeholder.unparsed("event", spec.name),
            Placeholder.unparsed("location", locationText),
            Placeholder.unparsed("time", humanTime(secondsLeft)),
            Placeholder.unparsed("mobs", remainingMobs.toString()),
            Placeholder.unparsed("radius", radius.toString()),
            *extra,
        )
    }

    private val active = ConcurrentHashMap<String, ActiveEvent>()
    private val mini = MiniMessage.miniMessage()
    private val scheduleFile = File(plugin.dataFolder, "schedule.yml")
    @Volatile private var scheduleTask: ScheduledTask? = null
    /** When the next automatic event is due (epoch millis); kept in schedule.yml across restarts. */
    @Volatile private var nextScheduledAt = 0L

    val running: Collection<String> get() = active.values.map { "${it.spec.name}: ${humanTime(it.secondsLeft)} left, ${it.remainingMobs} mobs" }

    fun isRunning(key: String) = active.containsKey(key)

    // ---------------------------------------------------------------- commands

    private val eventSuggestions = SuggestionProvider<CommandSourceStack> { _, builder ->
        plugin.eventSpecs.keys.filter { it.startsWith(builder.remainingLowerCase) }.forEach(builder::suggest)
        builder.buildFuture()
    }

    fun command(): LiteralArgumentBuilder<CommandSourceStack> = Commands.literal("event")
        .executes { ctx -> list(ctx.source.sender); 1 }
        .then(Commands.literal("list").executes { ctx -> list(ctx.source.sender); 1 })
        .then(Commands.literal("start").then(Commands.argument("event", StringArgumentType.word()).suggests(eventSuggestions)
            .then(Commands.argument("radius", IntegerArgumentType.integer(MIN_RADIUS, 1000)).executes { ctx ->
                val sender = ctx.source.sender
                if (sender !is Player) { sender.sendMessage("Only a player can start an event: it happens around you."); return@executes 0 }
                start(sender, StringArgumentType.getString(ctx, "event"), IntegerArgumentType.getInteger(ctx, "radius"))
                1
            })))
        .then(Commands.literal("schedule")
            .executes { ctx -> scheduleInfo(ctx.source.sender); 1 }
            .then(Commands.literal("now").executes { ctx ->
                ctx.source.sender.sendMessage("Starting a scheduled event now (ignores enabled and min-players)...")
                launchScheduled(ctx.source.sender, 0)
                1
            }))
        .then(Commands.literal("stop")
            .executes { ctx -> stop(ctx.source.sender, null); 1 }
            .then(Commands.argument("event", StringArgumentType.word()).suggests(eventSuggestions)
                .executes { ctx -> stop(ctx.source.sender, StringArgumentType.getString(ctx, "event")); 1 }))

    private fun list(sender: CommandSender) {
        val specs = plugin.eventSpecs
        if (specs.isEmpty()) { sender.sendMessage("No events are configured. Add one under 'events' in config.yml."); return }
        sender.sendMessage(Component.text("Events (start with /femmobs event start <event> <radius>):", NamedTextColor.GOLD))
        specs.values.forEach { spec ->
            val run = active[spec.key]
            val state = if (run == null) "idle" else "RUNNING, ${humanTime(run.secondsLeft)} left, ${run.remainingMobs} mobs"
            sender.sendMessage(Component.text(" ${spec.key}", NamedTextColor.YELLOW)
                .append(Component.text(" - ${spec.name}, ${describe(spec)} ($state)", NamedTextColor.GRAY)))
        }
    }

    fun describe(spec: EventSpec): String = when (spec.mode) {
        EventMode.FIXED -> "${spec.mobs} mobs"
        EventMode.KEEP_POPULATION -> "keeps ${spec.mobs} mobs alive"
    } + (if (spec.exact) " (exact)" else "") + (spec.boss?.let { " + ${it.name}" } ?: "") + ", ${humanTime(spec.durationSeconds)}"

    /** Starts [eventKey] centered on the player. Safe to call from any thread. */
    fun start(player: Player, eventKey: String, radius: Int) {
        val spec = plugin.eventSpecs[eventKey.lowercase()] ?: plugin.eventSpecs.values.firstOrNull { it.key.equals(eventKey, true) }
        if (spec == null) { plugin.tell(player, "Unknown event '$eventKey'. Events: ${plugin.eventSpecs.keys.joinToString(", ")}"); return }
        val maxRadius = plugin.maxEventRadius
        if (radius !in MIN_RADIUS..maxRadius) { plugin.tell(player, "Radius must be between $MIN_RADIUS and $maxRadius blocks."); return }
        player.scheduler.run(plugin, {
            val center = player.location
            val biome = runCatching { center.block.biome.key.key.split('_').joinToString(" ") { it.replaceFirstChar(Char::uppercase) } }.getOrDefault("")
            val step = spec.locationRounding
            val roundedX = (center.x / step).roundToInt() * step
            val roundedZ = (center.z / step).roundToInt() * step
            val locationText = "X $roundedX, Z $roundedZ" + if (biome.isNotEmpty()) " ($biome)" else ""
            Bukkit.getGlobalRegionScheduler().execute(plugin) {
                if (launch(spec, center, radius, locationText, player.uniqueId)) {
                    plugin.tell(player, "Started ${spec.name} with a radius of $radius blocks around you.")
                } else {
                    plugin.tell(player, "${spec.name} is already running. Stop it first with /femmobs event stop ${spec.key}.")
                }
            }
        }, null)
    }

    /** Starts an event at [center]. Runs on the global region. Returns false if that event is already running. */
    private fun launch(spec: EventSpec, center: Location, radius: Int, locationText: String, starter: UUID?): Boolean {
        if (active.containsKey(spec.key)) return false
        val event = ActiveEvent(spec, center.world, center.x, center.y, center.z, radius, locationText, starter)
        active[spec.key] = event
        broadcast(mini.deserialize(spec.messages.start, event.resolver()))
        if (spec.exact) spec.roster.forEach { entry -> repeat(entry.amount!!) { queueSpawn(event, entry.profile) } }
        else repeat(spec.mobs) { queueSpawn(event, spec.pickProfile()) }
        spec.boss?.let { queueSpawn(event, it, boss = true) }
        event.task = Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin, { tick(event) }, 20L, 20L)
        return true
    }

    // ---------------------------------------------------------------- automatic schedule

    /** (Re)starts the schedule check after the config loads. */
    fun applySchedule() {
        scheduleTask?.cancel()
        val schedule = plugin.schedule
        val saved = runCatching { YamlConfiguration.loadConfiguration(scheduleFile).getLong("next-at", 0) }.getOrDefault(0)
        val now = System.currentTimeMillis()
        val longest = (schedule.everyMinutes + schedule.randomMinutes) * 60_000
        nextScheduledAt = if (saved in (now + 1)..(now + longest)) saved else nextTime(schedule)
        saveNextTime()
        scheduleTask = Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin, { checkSchedule() }, 20L * 30, 20L * 30)
    }

    private fun nextTime(schedule: ScheduleSpec): Long {
        val jitter = if (schedule.randomMinutes > 0) ThreadLocalRandom.current().nextLong(-schedule.randomMinutes, schedule.randomMinutes + 1) else 0
        return System.currentTimeMillis() + (schedule.everyMinutes + jitter).coerceAtLeast(5) * 60_000
    }

    private fun saveNextTime() {
        runCatching { YamlConfiguration().apply { set("next-at", nextScheduledAt) }.save(scheduleFile) }
    }

    private fun checkSchedule() {
        val schedule = plugin.schedule
        if (!schedule.enabled || System.currentTimeMillis() < nextScheduledAt) return
        if (Bukkit.getOnlinePlayers().size < schedule.minPlayers || active.isNotEmpty()) {
            // Not now: too few players, or something is already running. Look again in 5 minutes.
            nextScheduledAt = System.currentTimeMillis() + 5 * 60_000
            saveNextTime()
            return
        }
        nextScheduledAt = nextTime(schedule)
        saveNextTime()
        launchScheduled(null, 0)
    }

    private fun scheduleInfo(sender: CommandSender) {
        val schedule = plugin.schedule
        val total = schedule.weights.values.sum().coerceAtLeast(1)
        val minutes = ((nextScheduledAt - System.currentTimeMillis()) / 60_000).coerceAtLeast(0)
        listOf(
            "Automatic events: ${if (schedule.enabled) "ENABLED" else "disabled (set event-schedule.enabled: true)"}",
            "Every ${schedule.everyMinutes} min (±${schedule.randomMinutes}), next in about $minutes min, needs ${schedule.minPlayers}+ players online.",
            "Where: ${schedule.minDistance}-${schedule.maxDistance} blocks from X ${schedule.centerX.roundToInt()}, Z ${schedule.centerZ.roundToInt()} in '${schedule.world}', radius ${schedule.radius}.",
            "Chances: " + schedule.weights.entries.joinToString(", ") { (key, weight) -> "${plugin.eventSpecs[key]?.name ?: key} ${weight * 100 / total}%" },
            "Test it now with /femmobs event schedule now",
        ).forEach(sender::sendMessage)
    }

    /** Picks a weighted random event and open ground at a random spot, then starts it with exact coordinates. */
    private fun launchScheduled(requester: CommandSender?, attempt: Int) {
        val schedule = plugin.schedule
        val world = Bukkit.getWorld(schedule.world)
        val weights = schedule.weights.filterKeys { it in plugin.eventSpecs }
        if (world == null || weights.isEmpty()) {
            val reason = if (world == null) "world '${schedule.world}' isn't loaded" else "event-schedule.events has no events"
            plugin.logger.warning("Scheduled event skipped: $reason.")
            requester?.let { plugin.tell(it, "Can't start a scheduled event: $reason.") }
            return
        }
        var roll = ThreadLocalRandom.current().nextInt(weights.values.sum())
        val key = weights.entries.first { (_, weight) -> roll -= weight; roll < 0 }.key
        val spec = plugin.eventSpecs.getValue(key)
        val random = ThreadLocalRandom.current()
        val angle = random.nextDouble(0.0, Math.PI * 2)
        val distance = random.nextDouble(schedule.minDistance.toDouble(), schedule.maxDistance.toDouble() + 1)
        val x = floor(schedule.centerX + cos(angle) * distance).toInt()
        val z = floor(schedule.centerZ + sin(angle) * distance).toInt()
        world.getChunkAtAsync(x shr 4, z shr 4).whenComplete { _, error ->
            if (error != null) { retryScheduled(requester, attempt, "chunk load failed"); return@whenComplete }
            Bukkit.getRegionScheduler().execute(plugin, world, x shr 4, z shr 4) {
                val y = world.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES)
                if (!canStandOn(world, x, y, z)) { retryScheduled(requester, attempt, "no open ground (water or lava)"); return@execute }
                val center = Location(world, x + 0.5, y + 1.0, z + 0.5)
                val biome = runCatching { center.block.biome.key.key.split('_').joinToString(" ") { it.replaceFirstChar(Char::uppercase) } }.getOrDefault("")
                val locationText = "X $x, Z $z" + if (biome.isNotEmpty()) " ($biome)" else ""
                Bukkit.getGlobalRegionScheduler().execute(plugin) {
                    val started = launch(spec, center, schedule.radius, locationText, null)
                    plugin.logger.info(if (started) "Scheduled event ${spec.key} started at $locationText." else "Scheduled event ${spec.key} was already running.")
                    requester?.let { plugin.tell(it, if (started) "Started ${spec.name} at $locationText." else "${spec.name} is already running.") }
                }
            }
        }
    }

    private fun retryScheduled(requester: CommandSender?, attempt: Int, reason: String) {
        if (attempt < 12) { launchScheduled(requester, attempt + 1); return }
        plugin.logger.warning("Scheduled event skipped: couldn't find open ground after 12 tries ($reason).")
        requester?.let { plugin.tell(it, "Couldn't find open ground for a scheduled event after 12 tries.") }
    }

    fun stop(sender: CommandSender, eventKey: String?) {
        Bukkit.getGlobalRegionScheduler().execute(plugin) {
            val targets = if (eventKey == null) active.values.toList() else listOfNotNull(active[eventKey.lowercase()])
            if (targets.isEmpty()) { plugin.tell(sender, if (eventKey == null) "No events are running." else "'$eventKey' isn't running."); return@execute }
            targets.forEach { end(it, EndReason.STOPPED) }
            plugin.tell(sender, "Stopped ${targets.joinToString(", ") { it.spec.name }} and removed their mobs.")
        }
    }

    /** Ends every event, e.g. before a reload. */
    fun stopAll() = active.values.toList().forEach { end(it, EndReason.STOPPED) }

    /** Plugin disable: no announcements; on Paper the mobs can be removed right away (Folia relies on load cleanup). */
    fun shutdown(folia: Boolean) {
        scheduleTask?.cancel()
        active.values.toList().forEach { event ->
            event.active = false
            event.task?.cancel()
            hideBar(event)
            if (!folia) (event.alive + event.droppedItems).forEach { id -> Bukkit.getEntity(id)?.remove() }
        }
        active.clear()
    }

    // ---------------------------------------------------------------- spawning

    private fun queueSpawn(event: ActiveEvent, profile: MobProfile, boss: Boolean = false) {
        event.pending.incrementAndGet()
        event.pendingByProfile.computeIfAbsent(profile.key) { AtomicInteger() }.incrementAndGet()
        attemptSpawn(event, profile, 0, boss)
    }

    /** Picks a random point, loads its chunk in the background, then spawns on the ground there (retrying elsewhere). */
    private fun attemptSpawn(event: ActiveEvent, profile: MobProfile, attempt: Int, boss: Boolean) {
        if (!event.active) { finishAttempt(event, profile); return }
        val random = ThreadLocalRandom.current()
        val angle = random.nextDouble(0.0, Math.PI * 2)
        val minFraction = (MIN_DISTANCE_FROM_CENTER / event.radius).let { it * it }.coerceAtMost(0.5)
        val distance = sqrt(random.nextDouble(minFraction, 1.0)) * event.radius
        val x = event.centerX + cos(angle) * distance
        val z = event.centerZ + sin(angle) * distance
        val blockX = floor(x).toInt()
        val blockZ = floor(z).toInt()
        event.world.getChunkAtAsync(blockX shr 4, blockZ shr 4).whenComplete { _, error ->
            if (error != null) {
                plugin.logger.log(Level.WARNING, "Could not load a chunk for ${event.spec.name}", error)
                retryOrGiveUp(event, profile, attempt, boss)
                return@whenComplete
            }
            Bukkit.getRegionScheduler().execute(plugin, event.world, blockX shr 4, blockZ shr 4) {
                if (!event.active) { finishAttempt(event, profile); return@execute }
                val spot = runCatching { groundSpot(event, blockX, blockZ) }.getOrNull()
                if (spot == null) { retryOrGiveUp(event, profile, attempt, boss); return@execute }
                try {
                    val mob = plugin.spawnProfile(profile, spot) { entity ->
                        entity.addScoreboardTag(event.tag)
                        entity.addScoreboardTag("femmobs.event.${event.spec.key}")
                        entity.removeWhenFarAway = false
                        event.alive.add(entity.uniqueId)
                        event.profileOf[entity.uniqueId] = profile.key
                    }
                    if (event.spec.glowing) makeGlow(mob)
                    if (boss) event.bossIds.add(mob.uniqueId)
                    event.spawned.incrementAndGet()
                } catch (failure: Throwable) {
                    plugin.logger.log(Level.WARNING, "Failed to spawn '${profile.key}' for ${event.spec.name}", failure)
                }
                finishAttempt(event, profile)
            }
        }
    }

    /**
     * Two independent ways, applied after the mob is in the world: the glowing flag (set again one tick
     * later in case anything reset it during spawning) and an infinite, hidden Glowing effect.
     */
    private fun makeGlow(mob: LivingEntity) {
        mob.isGlowing = true
        mob.addPotionEffect(PotionEffect(PotionEffectType.GLOWING, PotionEffect.INFINITE_DURATION, 0, false, false, false))
        mob.scheduler.runDelayed(plugin, { if (mob.isValid) mob.isGlowing = true }, null, 1L)
    }

    private fun retryOrGiveUp(event: ActiveEvent, profile: MobProfile, attempt: Int, boss: Boolean) {
        if (event.active && attempt < MAX_SPAWN_ATTEMPTS) attemptSpawn(event, profile, attempt + 1, boss) else finishAttempt(event, profile)
    }

    private fun finishAttempt(event: ActiveEvent, profile: MobProfile) {
        event.pendingByProfile[profile.key]?.decrementAndGet()
        if (event.pending.decrementAndGet() == 0) Bukkit.getGlobalRegionScheduler().execute(plugin) { checkFinished(event) }
    }

    /**
     * Ground to stand on at this column: the surface when it is near the event's height, otherwise the closest
     * open floor around that height (so caves and the Nether's roof don't put mobs in the wrong place).
     */
    private fun groundSpot(event: ActiveEvent, x: Int, z: Int): Location? {
        val world = event.world
        val baseY = floor(event.centerY).toInt()
        val surface = world.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES)
        val candidates = if (abs(surface - baseY) <= SURFACE_TOLERANCE) listOf(surface)
            else (baseY + 12 downTo baseY - 24).toList()
        val groundY = candidates.firstOrNull { y -> y > world.minHeight && y < world.maxHeight - 2 && canStandOn(world, x, y, z) } ?: return null
        val spot = Location(world, x + 0.5, groundY + 1.0, z + 0.5, ThreadLocalRandom.current().nextFloat() * 360f, 0f)
        if (spot.getNearbyPlayers(MIN_DISTANCE_FROM_PLAYERS).isNotEmpty()) return null
        return spot
    }

    private fun canStandOn(world: World, x: Int, y: Int, z: Int): Boolean {
        val ground = world.getBlockAt(x, y, z)
        if (!ground.type.isSolid || ground.isLiquid || Tag.LEAVES.isTagged(ground.type)) return false
        return (1..2).all { dy ->
            val block = world.getBlockAt(x, y + dy, z)
            block.isPassable && !block.isLiquid && block.type != Material.FIRE
        }
    }

    // ---------------------------------------------------------------- lifecycle

    /** Runs every second on the global region. */
    private fun tick(event: ActiveEvent) {
        if (!event.active) return
        val left = event.secondsLeft
        if (left <= 0) { end(event, EndReason.TIME); return }
        event.spec.warningsSeconds.firstOrNull { it >= left && it !in event.warned }?.let { warning ->
            event.spec.warningsSeconds.filter { it >= left }.forEach(event.warned::add)
            broadcast(mini.deserialize(event.spec.messages.warning, event.resolver()))
        }
        if (event.spec.mode == EventMode.KEEP_POPULATION && ++event.secondsSinceRespawn >= event.spec.respawnSeconds) {
            event.secondsSinceRespawn = 0
            if (event.spec.exact) {
                // Replace each profile up to its exact amount (a dead grunt comes back as a grunt).
                var budget = MAX_RESPAWNS_PER_CYCLE
                event.spec.roster.forEach { entry ->
                    val missing = (entry.amount!! - event.countOf(entry.profile)).coerceAtMost(budget)
                    repeat(missing.coerceAtLeast(0)) { queueSpawn(event, entry.profile) }
                    budget -= missing.coerceAtLeast(0)
                }
            } else {
                val missing = event.spec.mobs - event.alive.size - event.pending.get()
                repeat(missing.coerceIn(0, MAX_RESPAWNS_PER_CYCLE)) { queueSpawn(event, event.spec.pickProfile()) }
            }
        }
        updateBar(event, left)
    }

    private fun updateBar(event: ActiveEvent, left: Long) {
        event.bar.name(mini.deserialize(event.spec.messages.bossBar, event.resolver(Placeholder.unparsed("time", clockTime(left)))))
        event.bar.progress((left.toFloat() / event.spec.durationSeconds).coerceIn(0f, 1f))
        val range = (event.radius + BAR_EXTRA_RANGE).toDouble()
        Bukkit.getOnlinePlayers().forEach { player ->
            // Location is read on the player's own thread.
            player.scheduler.run(plugin, {
                val loc = player.location
                val dx = loc.x - event.centerX
                val dz = loc.z - event.centerZ
                val inside = event.active && loc.world == event.world && dx * dx + dz * dz <= range * range
                if (inside && event.viewers.add(player.uniqueId)) player.showBossBar(event.bar)
                else if (!inside && event.viewers.remove(player.uniqueId)) player.hideBossBar(event.bar)
            }, null)
        }
    }

    private fun checkFinished(event: ActiveEvent) {
        if (!event.active || event.pending.get() != 0 || event.alive.isNotEmpty()) return
        if (event.spawned.get() == 0) { end(event, EndReason.FAILED); return }
        if (event.spec.mode == EventMode.FIXED) end(event, EndReason.CLEARED)
    }

    private fun end(event: ActiveEvent, reason: EndReason) {
        if (!event.active) return
        event.active = false
        event.task?.cancel()
        active.remove(event.spec.key, event)
        event.alive.toList().forEach { id -> Bukkit.getEntity(id)?.let { it.scheduler.run(plugin, { _ -> it.remove() }, null) } }
        event.alive.clear()
        hideBar(event)
        val messages = event.spec.messages
        when (reason) {
            EndReason.TIME -> broadcast(mini.deserialize(messages.endTime, event.resolver()))
            EndReason.CLEARED -> broadcast(mini.deserialize(messages.endCleared, event.resolver()))
            EndReason.STOPPED -> broadcast(mini.deserialize(messages.endStopped, event.resolver()))
            EndReason.FAILED -> {
                broadcast(mini.deserialize(messages.endStopped, event.resolver()))
                event.starter?.let(Bukkit::getPlayer)?.let { plugin.tell(it, "${event.spec.name} couldn't find any ground to spawn on within ${event.radius} blocks. Try somewhere more open or a bigger radius.") }
            }
        }
        if (reason != EndReason.FAILED) leaderboard(event)?.let(::broadcast)
        scheduleDropCleanup(event)
    }

    private fun scheduleDropCleanup(event: ActiveEvent) {
        val seconds = event.spec.dropCleanupSeconds
        val drops = event.droppedItems.toList()
        if (seconds <= 0 || drops.isEmpty()) return
        broadcast(mini.deserialize(event.spec.messages.dropCleanup, event.resolver(Placeholder.unparsed("seconds", seconds.toString()))))
        Bukkit.getGlobalRegionScheduler().runDelayed(plugin, {
            drops.forEach { id -> Bukkit.getEntity(id)?.let { item -> item.scheduler.run(plugin, { item.remove() }, null) } }
        }, seconds * 20L)
    }

    private fun leaderboard(event: ActiveEvent): Component? {
        if (event.spec.leaderboardSize <= 0 || event.kills.isEmpty()) return null
        val top = event.kills.entries.sortedByDescending { it.value.get() }.take(event.spec.leaderboardSize)
        val entries = Component.join(
            net.kyori.adventure.text.JoinConfiguration.separator(Component.text(" · ", NamedTextColor.DARK_GRAY)),
            top.map { (id, kills) ->
                mini.deserialize(event.spec.messages.leaderboardEntry, TagResolver.resolver(
                    Placeholder.unparsed("player", event.killerNames[id] ?: "?"),
                    Placeholder.unparsed("kills", kills.get().toString()),
                ))
            },
        )
        return mini.deserialize(event.spec.messages.leaderboard, event.resolver(Placeholder.component("entries", entries)))
    }

    private fun hideBar(event: ActiveEvent) {
        event.viewers.toList().forEach { id -> Bukkit.getPlayer(id)?.hideBossBar(event.bar) }
        event.viewers.clear()
    }

    private fun broadcast(message: Component) = Bukkit.getServer().sendMessage(message)

    // ---------------------------------------------------------------- tracking (called from the plugin's listeners)

    private fun eventFor(entity: Entity): ActiveEvent? {
        val tags = entity.scoreboardTags
        return active.values.firstOrNull { it.tag in tags }
    }

    /** True when the entity belongs to an event that is still running (so it isn't a leftover). */
    fun ownsEntity(entity: Entity) = eventFor(entity) != null

    fun onDeath(entity: Entity, killer: Player?) {
        val event = eventFor(entity) ?: return
        if (killer != null && entity.uniqueId in event.alive) {
            event.kills.computeIfAbsent(killer.uniqueId) { AtomicInteger() }.incrementAndGet()
            event.killerNames[killer.uniqueId] = killer.name
        }
        onGone(entity)
    }

    fun onGone(entity: Entity) {
        val event = eventFor(entity) ?: return
        if (!event.alive.remove(entity.uniqueId)) return
        event.profileOf.remove(entity.uniqueId)
        if (event.alive.isEmpty() && event.pending.get() == 0) Bukkit.getGlobalRegionScheduler().execute(plugin) { checkFinished(event) }
    }

    /**
     * Runs after other plugins (FemItems) added their loot. Drops of regular event mobs are re-dropped as
     * tracked items so they can be cleaned up after the event; bosses' drops are left alone.
     */
    fun onDeathDrops(death: EntityDeathEvent) {
        val entity = death.entity
        val event = eventFor(entity) ?: return
        if (event.spec.dropCleanupSeconds <= 0 || entity.uniqueId in event.bossIds || plugin.profileOf(entity)?.boss != null) return
        val drops = death.drops.toList()
        if (drops.isEmpty()) return
        death.drops.clear()
        drops.forEach { stack ->
            entity.world.dropItemNaturally(entity.location, stack) { item ->
                item.addScoreboardTag(DROP_TAG)
                event.droppedItems.add(item.uniqueId)
            }
        }
    }

    /** Tracked drops never merge with other items, so cleanup can't take a player's own items with them. */
    fun onItemMerge(merge: ItemMergeEvent) {
        if (DROP_TAG in merge.entity.scoreboardTags || DROP_TAG in merge.target.scoreboardTags) merge.isCancelled = true
    }

    /** A boss in an event summoned a minion: it belongs to the same event. Returns false if the boss isn't in one. */
    fun adopt(boss: Entity, minion: LivingEntity): Boolean {
        val event = eventFor(boss) ?: return false
        minion.addScoreboardTag(event.tag)
        minion.addScoreboardTag("femmobs.event.${event.spec.key}")
        minion.removeWhenFarAway = false
        if (event.spec.glowing) makeGlow(minion)
        event.alive.add(minion.uniqueId)
        return true
    }

    fun onTransform(transform: EntityTransformEvent) {
        val event = eventFor(transform.entity) ?: return
        if (transform.entity.uniqueId !in event.alive) return
        val profileKey = event.profileOf[transform.entity.uniqueId]
        transform.transformedEntities.forEach { converted ->
            converted.addScoreboardTag(event.tag)
            event.alive.add(converted.uniqueId)
            profileKey?.let { event.profileOf[converted.uniqueId] = it }
        }
    }

    companion object {
        /** Same prefix as arena runs, so the existing restart cleanup also removes leftover event mobs. */
        const val EVENT_TAG_PREFIX = "femmobs_"
        /** Marks items dropped by regular event mobs (removed after the event). */
        const val DROP_TAG = "femmobs.event_drop"
        const val MIN_RADIUS = 5
        private const val MIN_DISTANCE_FROM_CENTER = 3.0
        private const val MIN_DISTANCE_FROM_PLAYERS = 3.0
        private const val MAX_SPAWN_ATTEMPTS = 10
        private const val SURFACE_TOLERANCE = 24
        private const val MAX_RESPAWNS_PER_CYCLE = 5
        private const val BAR_EXTRA_RANGE = 16

        fun humanTime(seconds: Long): String {
            val minutes = seconds / 60
            val rest = seconds % 60
            return when {
                minutes == 0L -> "$rest second${if (rest == 1L) "" else "s"}"
                rest == 0L -> "$minutes minute${if (minutes == 1L) "" else "s"}"
                else -> "$minutes min $rest s"
            }
        }

        fun clockTime(seconds: Long) = "%d:%02d".format(seconds / 60, seconds % 60)
    }
}
