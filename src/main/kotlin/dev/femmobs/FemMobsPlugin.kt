package dev.femmobs

import com.mojang.brigadier.Command
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.context.CommandContext
import com.mojang.brigadier.suggestion.SuggestionProvider
import io.papermc.paper.command.brigadier.CommandSourceStack
import io.papermc.paper.command.brigadier.Commands
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents
import io.papermc.paper.threadedregions.scheduler.ScheduledTask
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.command.CommandSender
import org.bukkit.command.ConsoleCommandSender
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Entity
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.entity.Projectile
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDeathEvent
import org.bukkit.event.entity.EntityRemoveEvent
import org.bukkit.event.entity.EntityTransformEvent
import org.bukkit.event.entity.ItemMergeEvent
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.event.world.EntitiesLoadEvent
import org.bukkit.permissions.Permission
import org.bukkit.permissions.PermissionDefault
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.util.Vector
import java.util.UUID
import kotlin.math.roundToInt
import org.bukkit.attribute.Attribute
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.atomic.AtomicInteger
import java.util.logging.Level

class FemMobsPlugin : JavaPlugin(), Listener {
    private class Settings(
        val profiles: Map<String, MobProfile>,
        val arenas: Map<String, ArenaSpec>,
        val maxMobsPerWave: Int,
        val spawnDistance: Double,
        val events: Map<String, EventSpec>,
        val maxEventRadius: Int,
        val schedule: ScheduleSpec,
    )

    /** One active campaign or single menu wave in one arena. Several arenas may run at the same time. */
    private class ArenaRun(val arena: ArenaSpec, val manual: Boolean, val owner: UUID?) {
        val tag = RUN_TAG_PREFIX + UUID.randomUUID().toString().replace("-", "")
        val alive: MutableSet<UUID> = ConcurrentHashMap.newKeySet()
        val pendingSpawns = AtomicInteger(0)
        @Volatile var active = true
        @Volatile var wave = 0
        @Volatile var nextWaveTask: ScheduledTask? = null
    }

    private val runs = ConcurrentHashMap<String, ArenaRun>()
    @Volatile private lateinit var settings: Settings
    private var ownsAdminPermission = false
    private lateinit var traders: TraderFeature
    private lateinit var events: EventFeature
    private lateinit var bosses: BossFeature

    internal val eventSpecs: Map<String, EventSpec> get() = settings.events
    internal val maxEventRadius: Int get() = settings.maxEventRadius
    internal val schedule: ScheduleSpec get() = settings.schedule

    internal fun profileFor(key: String): MobProfile? = settings.profiles[key]

    /** The profile a FemMobs mob was spawned from, via its `femmobs.profile.<key>` tag. */
    internal fun profileOf(entity: Entity): MobProfile? = entity.scoreboardTags
        .firstOrNull { it.startsWith(BossFeature.PROFILE_TAG_PREFIX) }
        ?.let { settings.profiles[it.removePrefix(BossFeature.PROFILE_TAG_PREFIX)] }

    /** A boss summoned [minion]: it joins the boss's event or arena run so it's tracked and cleaned up with it. */
    internal fun adoptMinion(boss: Entity, minion: LivingEntity) {
        if (events.adopt(boss, minion)) return
        val run = runFor(boss) ?: return
        minion.addScoreboardTag(run.tag)
        minion.removeWhenFarAway = false
        run.alive.add(minion.uniqueId)
    }

    override fun onEnable() {
        saveDefaultConfig()
        migrateConfigIfNeeded()
        settings = loadSettings()
        traders = TraderFeature(this, dataFolder)
        traders.load()
        events = EventFeature(this)
        bosses = BossFeature(this)
        events.applySchedule()
        server.pluginManager.registerEvents(this, this)
        server.pluginManager.registerEvents(traders, this)
        val adminPermission = server.pluginManager.getPermission(PERMISSION)
        if (adminPermission == null) {
            server.pluginManager.addPermission(Permission(PERMISSION, PermissionDefault.FALSE))
            ownsAdminPermission = true
        } else {
            adminPermission.setDefault(PermissionDefault.FALSE)
        }
        lifecycleManager.registerEventHandler(LifecycleEvents.COMMANDS) { event ->
            event.registrar().register(buildCommand(), "FemMobs admin commands")
        }
        // After a PlugManX reload, wave mobs from the previous instance may still be loaded.
        Bukkit.getGlobalRegionScheduler().runDelayed(this, { sweepOrphansNearPlayers(null) }, 20L)
    }

    override fun onDisable() {
        if (::traders.isInitialized) traders.clearDrafts()
        val folia = isFolia()
        if (::events.isInitialized) events.shutdown(folia)
        if (::bosses.isInitialized) bosses.shutdown()
        runs.values.toList().forEach { run ->
            endRun(run)
            // On Paper the disabling thread owns every entity, so mobs can be removed now. Folia forbids
            // touching entities here; leftovers are removed when their chunk loads again or by the
            // sweep that runs on the next enable.
            if (!folia) run.alive.forEach { id -> Bukkit.getEntity(id)?.remove() }
            run.alive.clear()
        }
        runs.clear()
        if (ownsAdminPermission && server.pluginManager.getPermission(PERMISSION) != null) {
            server.pluginManager.removePermission(PERMISSION)
        }
    }

    // ---------------------------------------------------------------- commands

    private fun buildCommand() = Commands.literal("femmobs")
        .requires { source -> isAdmin(source.sender) }
        .then(Commands.literal("start")
            .executes { ctx -> start(ctx.sender, null) }
            .then(Commands.argument("arena", StringArgumentType.word()).suggests(arenaSuggestions)
                .executes { ctx -> start(ctx.sender, StringArgumentType.getString(ctx, "arena")) }))
        .then(Commands.literal("stop")
            .executes { ctx -> stop(ctx.sender, null) }
            .then(Commands.argument("arena", StringArgumentType.word()).suggests(arenaSuggestions)
                .executes { ctx -> stop(ctx.sender, StringArgumentType.getString(ctx, "arena")) }))
        .then(Commands.literal("spawn")
            .then(Commands.argument("mob", StringArgumentType.word()).suggests(mobSuggestions)
                .executes { ctx -> spawnCommand(ctx.sender, StringArgumentType.getString(ctx, "mob"), 1) }
                .then(Commands.argument("amount", IntegerArgumentType.integer(1, 64))
                    .executes { ctx ->
                        spawnCommand(ctx.sender, StringArgumentType.getString(ctx, "mob"), IntegerArgumentType.getInteger(ctx, "amount"))
                    })))
        .then(Commands.literal("status").executes { ctx -> status(ctx.sender) })
        .then(Commands.literal("cleanup").executes { ctx -> sweepOrphansNearPlayers(ctx.sender); Command.SINGLE_SUCCESS })
        .then(Commands.literal("reload").executes { ctx -> reload(ctx.sender) })
        .then(Commands.literal("gui").executes { ctx -> openGui(ctx.sender) })
        .then(traders.command())
        .then(events.command())
        .then(Commands.literal("help").executes { ctx -> sendHelp(ctx.sender) })
        .executes { ctx -> sendHelp(ctx.sender) }
        .build()

    private val CommandContext<CommandSourceStack>.sender: CommandSender get() = source.sender

    /** femmobs.admin defaults to false (grant it with LuckPerms); the server console is always allowed. */
    private fun isAdmin(sender: CommandSender) = sender is ConsoleCommandSender || sender.hasPermission(PERMISSION)

    private val arenaSuggestions = SuggestionProvider<CommandSourceStack> { _, builder ->
        settings.arenas.keys.filter { it.startsWith(builder.remainingLowerCase) }.forEach(builder::suggest)
        builder.buildFuture()
    }

    private val mobSuggestions = SuggestionProvider<CommandSourceStack> { _, builder ->
        settings.profiles.keys.filter { it.startsWith(builder.remainingLowerCase) }.forEach(builder::suggest)
        builder.buildFuture()
    }

    private fun findArena(key: String): ArenaSpec? =
        settings.arenas[key] ?: settings.arenas.values.firstOrNull { it.key.equals(key, ignoreCase = true) }

    private fun findProfile(key: String): MobProfile? =
        settings.profiles[key] ?: settings.profiles.values.firstOrNull { it.key.equals(key, ignoreCase = true) }

    private fun arenaList(): String = settings.arenas.keys.joinToString(", ").ifEmpty { "(none configured)" }

    private fun start(sender: CommandSender, arenaKey: String?): Int {
        val arena = when {
            arenaKey != null -> findArena(arenaKey)
            settings.arenas.size == 1 -> settings.arenas.values.first()
            else -> null
        }
        if (arena == null) {
            sender.say(if (arenaKey == null) "Usage: /femmobs start <arena>. Arenas: ${arenaList()}"
                else "Unknown arena '$arenaKey'. Arenas: ${arenaList()}")
            return 0
        }
        startCampaign(sender, arena)
        return Command.SINGLE_SUCCESS
    }

    private fun stop(sender: CommandSender, arenaKey: String?): Int {
        if (arenaKey != null && findArena(arenaKey) == null) {
            sender.say("Unknown arena '$arenaKey'. Arenas: ${arenaList()}")
            return 0
        }
        Bukkit.getGlobalRegionScheduler().execute(this) {
            val targets = if (arenaKey == null) runs.values.toList() else listOfNotNull(runs[findArena(arenaKey)!!.key])
            if (targets.isEmpty()) { tell(sender, "Nothing is running."); return@execute }
            targets.forEach(::stopRun)
            tell(sender, "Stopped ${targets.joinToString(", ") { it.arena.name }}; their spawned wave mobs were removed.")
        }
        return Command.SINGLE_SUCCESS
    }

    private fun status(sender: CommandSender): Int {
        sender.sendMessage(Msg.line("Status", Msg.Tone.INFO))
        if (runs.isEmpty()) sender.sendMessage(Msg.detail("No arenas are running."))
        else runs.values.forEach { sender.sendMessage(Msg.detail("Arena ${it.arena.name}: ${describeRun(it)}")) }
        val runningEvents = events.running
        if (runningEvents.isEmpty()) sender.sendMessage(Msg.detail("No events are running."))
        else runningEvents.forEach { sender.sendMessage(Msg.detail("Event $it")) }
        return Command.SINGLE_SUCCESS
    }

    private fun describeRun(run: ArenaRun): String =
        if (run.manual) "single wave, ${run.alive.size} mobs alive"
        else "campaign wave ${run.wave.coerceAtLeast(1)}/${run.arena.waves.size}, ${run.alive.size} mobs alive"

    private fun reload(sender: CommandSender): Int {
        Bukkit.getGlobalRegionScheduler().execute(this) {
            val stopped = runs.values.toList()
            stopped.forEach(::stopRun)
            events.stopAll()
            try {
                reloadConfig()
                migrateConfigIfNeeded()
                settings = loadSettings()
                traders.load()
                events.applySchedule()
                val note = if (stopped.isEmpty()) "" else " Stopped ${stopped.size} running arena(s) first."
                tell(sender, "FemMobs reloaded: ${settings.profiles.size} mob profiles, ${settings.arenas.size} arenas, ${settings.events.size} events, ${traders.count} traders.$note")
            } catch (e: Exception) {
                logger.warning("Reload failed: ${e.message}")
                tell(sender, "Reload failed, keeping the previous config: ${e.message}")
            }
        }
        return Command.SINGLE_SUCCESS
    }

    private fun spawnCommand(sender: CommandSender, mobKey: String, amount: Int): Int {
        if (sender !is Player) { sender.say("Only a player can spawn mobs in front of themselves."); return 0 }
        val profile = findProfile(mobKey)
        if (profile == null) {
            sender.say("Unknown mob '$mobKey'. Mobs: ${settings.profiles.keys.joinToString(", ")}")
            return 0
        }
        spawnInFront(sender, profile, amount)
        return Command.SINGLE_SUCCESS
    }

    private fun openGui(sender: CommandSender): Int {
        if (sender !is Player) { sender.say("Only a player can open the menu."); return 0 }
        sender.scheduler.run(this, { openMainMenu(sender) }, null)
        return Command.SINGLE_SUCCESS
    }

    private fun sendHelp(sender: CommandSender): Int {
        val platform = if (isFolia()) "Folia" else "Paper"
        val bosses = settings.profiles.values.count { it.boss != null }
        sender.sendMessage(Msg.line("v${pluginMeta.version} on $platform", Msg.Tone.INFO))
        sender.sendMessage(Msg.detail("${settings.profiles.size} mobs ($bosses bosses) · ${settings.arenas.size} arenas · " +
            "${settings.events.size} events · ${traders.count} traders · schedule ${if (settings.schedule.enabled) "on" else "off"}"))
        listOf(
            "/femmobs gui" to "menu: arenas, events, mobs and traders",
            "/femmobs event start <event> <radius>" to "start an event around you",
            "/femmobs event stop [event]" to "end one or all events",
            "/femmobs event list" to "all events and what's running",
            "/femmobs event schedule [now]" to "automatic events (now = test one)",
            "/femmobs spawn <mob> [amount]" to "spawn a mob in front of you",
            "/femmobs start <arena>" to "start an arena campaign",
            "/femmobs stop [arena]" to "stop an arena and remove its mobs",
            "/femmobs trader" to "build and spawn custom traders",
            "/femmobs status" to "running arenas and events",
            "/femmobs cleanup" to "remove leftover mobs near players",
            "/femmobs reload" to "reload config.yml and traders.yml",
        ).forEach { (command, description) -> sender.sendMessage(Msg.command(command, description)) }
        return Command.SINGLE_SUCCESS
    }

    // ---------------------------------------------------------------- config

    private fun migrateConfigIfNeeded() {
        // contains(path, true) ignores the bundled defaults that reloadConfig() attaches.
        if (config.contains("arenas", true)) {
            var changed = false
            if (!config.contains("max-mobs-per-wave", true)) { config.set("max-mobs-per-wave", 100); changed = true }
            if (!config.contains("spawn-menu.distance", true)) { config.set("spawn-menu.distance", 3.0); changed = true }
            if (addEventsIfMissing()) changed = true
            if (addVersion5Content()) changed = true
            if (changed) saveConfigFile()
            return
        }

        val bundled = YamlConfiguration()
        getResource("config.yml")!!.bufferedReader().use { bundled.load(it) }
        if (!config.contains("mobs", true)) config.set("mobs", bundled.get("mobs"))
        migrateVersion1Waves(bundled)

        // Version 2 had a single `arena` section; version 3 supports any number under `arenas`.
        val legacy = config.getConfigurationSection("arena")
        val maxMobs = legacy?.getInt("max-mobs-per-wave", 100) ?: 100
        if (legacy != null) {
            val target = config.createSection("arenas.forsaken")
            target.set("name", "Forsaken Arena")
            target.set("description", "Five waves of the Forsaken.")
            target.set("icon", "NETHER_STAR")
            target.set("world", legacy.getString("world", "world"))
            target.set("wave-delay-seconds", legacy.getLong("wave-delay-seconds", 5))
            legacy.getConfigurationSection("waves")?.let { copySection(it, target.createSection("waves")) }
        } else {
            copySection(bundled.getConfigurationSection("arenas")!!, config.createSection("arenas"))
        }
        config.set("arena", null)
        config.set("max-mobs-per-wave", maxMobs)
        config.set("spawn-menu.distance", 3.0)
        addEventsIfMissing()
        addVersion5Content()
        saveConfigFile()
        logger.info("Updated FemMobs config to version 3 (multiple arenas). Your previous arena is now 'arenas.forsaken'.")
    }

    /** Version 4 added server events; older configs get the bundled example event. Returns true if it changed anything. */
    private fun addEventsIfMissing(): Boolean {
        if (config.contains("events", true)) return false
        val bundled = YamlConfiguration()
        getResource("config.yml")!!.bufferedReader().use { bundled.load(it) }
        if (!config.contains("max-event-radius", true)) config.set("max-event-radius", 200)
        copySection(bundled.getConfigurationSection("events")!!, config.createSection("events"))
        config.set("config-version", 4)
        logger.info("Added the example server event 'invasion' to config.yml (config version 4).")
        return true
    }

    /**
     * Versions 5 and 6 added bosses and the event schedule. Adds bundled mob profiles and events whose names
     * don't exist yet, the disabled `event-schedule` section and chances for new scheduled events, and the
     * health-bar `boss` section to the Dreadbound and Champion if they have none. Nothing else that already
     * exists is changed. Runs once per version.
     */
    private fun addVersion5Content(): Boolean {
        if (config.getInt("config-version", 0) >= CONFIG_VERSION) return false
        val bundled = YamlConfiguration()
        getResource("config.yml")!!.bufferedReader().use { bundled.load(it) }
        val added = mutableListOf<String>()
        for (sectionName in listOf("mobs", "events")) {
            val from = bundled.getConfigurationSection(sectionName) ?: continue
            val to = config.getConfigurationSection(sectionName) ?: config.createSection(sectionName)
            from.getKeys(false).filter { !to.contains(it, true) }.forEach { key ->
                copySection(from.getConfigurationSection(key)!!, to.createSection(key))
                added += "$sectionName.$key"
            }
        }
        if (!config.contains("event-schedule", true)) {
            copySection(bundled.getConfigurationSection("event-schedule")!!, config.createSection("event-schedule"))
            added += "event-schedule (disabled)"
        } else {
            val weights = config.getConfigurationSection("event-schedule.events") ?: config.createSection("event-schedule.events")
            bundled.getConfigurationSection("event-schedule.events")?.getKeys(false)
                ?.filter { it in NEW_SCHEDULED_EVENTS && !weights.contains(it, true) }
                ?.forEach { key -> weights.set(key, bundled.getInt("event-schedule.events.$key")); added += "event-schedule.events.$key" }
        }
        if (config.getInt("config-version", 0) < 7) upgradeToVersion7(bundled, added)
        if (config.getInt("config-version", 0) < 8) softenAbilities(added)
        if (config.getInt("config-version", 0) < 9) upgradeToVersion9(bundled, added)
        for (key in listOf("dreadbound", "champion")) {
            val profile = config.getConfigurationSection("mobs.$key") ?: continue
            val bossSection = bundled.getConfigurationSection("mobs.$key.boss") ?: continue
            if (!profile.contains("boss", true)) {
                copySection(bossSection, profile.createSection("boss"))
                added += "mobs.$key.boss (health bar)"
            }
        }
        config.set("config-version", CONFIG_VERSION)
        logger.info("Updated config to version $CONFIG_VERSION. Added: ${added.joinToString(", ").ifEmpty { "nothing new" }}.")
        return true
    }

    /**
     * Version 7: XP for the bundled mobs, participation rewards for the bundled events, bigger boss escorts,
     * and an hourly schedule. Only fills in what's missing, or replaces values still at their old defaults.
     */
    private fun upgradeToVersion7(bundled: YamlConfiguration, added: MutableList<String>) {
        bundled.getConfigurationSection("mobs")?.getKeys(false)?.forEach { key ->
            val profile = config.getConfigurationSection("mobs.$key") ?: return@forEach
            if (!profile.contains("xp", true) && bundled.contains("mobs.$key.xp")) {
                profile.set("xp", bundled.getInt("mobs.$key.xp")); added += "mobs.$key.xp"
            }
        }
        bundled.getConfigurationSection("events")?.getKeys(false)?.forEach { key ->
            val event = config.getConfigurationSection("events.$key") ?: return@forEach
            val bundledRewards = bundled.getConfigurationSection("events.$key.rewards")
            if (!event.contains("rewards", true) && bundledRewards != null) {
                copySection(bundledRewards, event.createSection("rewards")); added += "events.$key.rewards"
            }
            OLD_BOSS_ESCORTS[key]?.let { oldRoster ->
                val current = event.getMapList("roster").map { row -> row.entries.associate { it.key.toString() to it.value.toString() } }
                if (current == oldRoster) {
                    event.set("roster", bundled.getMapList("events.$key.roster")); added += "events.$key.roster (bigger escort)"
                }
            }
        }
        if (config.getLong("event-schedule.every-minutes", -1) == 180L && config.getLong("event-schedule.random-minutes", -1) == 30L) {
            config.set("event-schedule.every-minutes", 60)
            config.set("event-schedule.random-minutes", 10)
            added += "event-schedule every 60 minutes"
        }
    }

    /**
     * Version 9: difficulty tiers. The Dreadbound becomes an elite mob leading the hard "Dreadbound Assault"
     * instead of a mini-boss, the Champion becomes a full boss, the Forsaken Invasion loses its Dreadbound, and
     * boss schedule chances even out. Each part only applies where the old defaults are still in place.
     */
    private fun upgradeToVersion9(bundled: YamlConfiguration, added: MutableList<String>) {
        val dreadboundBoss = config.getConfigurationSection("mobs.dreadbound.boss")
        if (dreadboundBoss != null && dreadboundBoss.getMapList("abilities").isEmpty()) {
            config.set("mobs.dreadbound.boss", null); added += "mobs.dreadbound (elite mob, no boss bar)"
        }
        val champion = config.getConfigurationSection("mobs.champion")
        if (champion != null && config.getMapList("mobs.champion.boss.abilities").isEmpty()) {
            config.set("mobs.champion", null)
            copySection(bundled.getConfigurationSection("mobs.champion")!!, config.createSection("mobs.champion"))
            added += "mobs.champion (reworked as a boss)"
        }
        if (config.getString("events.invasion.boss") == "dreadbound") {
            config.set("events.invasion.boss", "none"); added += "events.invasion without the Dreadbound"
        }
        fun replaceEventIf(key: String, oldRoster: List<Map<String, String>>) {
            val current = config.getMapList("events.$key.roster").map { row -> row.entries.associate { it.key.toString() to it.value.toString() } }
            if (config.contains("events.$key", true) && current == oldRoster) {
                config.set("events.$key", null)
                copySection(bundled.getConfigurationSection("events.$key")!!, config.createSection("events.$key"))
                added += "events.$key (reworked)"
            }
        }
        replaceEventIf("dreadbound_battle", listOf(mapOf("profile" to "grunt", "amount" to "8")))
        replaceEventIf("champion_battle", listOf(mapOf("profile" to "ravager", "amount" to "6")))
        bundled.getConfigurationSection("events")?.getKeys(false)?.forEach { key ->
            if (config.contains("events.$key", true) && !config.contains("events.$key.difficulty", true)) {
                bundled.getString("events.$key.difficulty")?.let { config.set("events.$key.difficulty", it); added += "events.$key.difficulty" }
            }
        }
        listOf("dreadbound_battle" to (6 to 7), "champion_battle" to (4 to 3), "cosmic_wraith_battle" to (2 to 3),
            "reaper_battle" to (2 to 3), "frost_battle" to (2 to 3), "storm_battle" to (2 to 3), "venom_battle" to (2 to 3),
        ).forEach { (key, change) ->
            val path = "event-schedule.events.$key"
            if (config.contains(path, true) && config.getInt(path) == change.first) {
                config.set(path, change.second); added += "$path ${change.first} -> ${change.second}"
            }
        }
    }

    /** Version 8: softer Reaper and Frost Jarl effects, only where the abilities are still at the old values. */
    private fun softenAbilities(added: MutableList<String>) {
        SOFTENED_ABILITIES.groupBy { it[0] }.forEach { (mob, changes) ->
            val path = "mobs.$mob.boss.abilities"
            val abilities = config.getMapList(path).map { LinkedHashMap(it.mapKeys { (key, _) -> key.toString() }) }
            if (abilities.isEmpty()) return@forEach
            var changed = false
            abilities.forEach { ability ->
                val change = changes.firstOrNull { (_, type, effect, oldLevel, oldDuration) ->
                    ability["type"]?.toString() == type && ability["effect"]?.toString() == effect &&
                        (ability["level"]?.toString() ?: "1") == oldLevel && ability["duration"]?.toString()?.toDoubleOrNull() == oldDuration.toDouble()
                } ?: return@forEach
                ability["level"] = change[5].toInt()
                ability["duration"] = change[6].toInt()
                changed = true
                added += "mobs.$mob ${change[2]} softened"
            }
            if (changed) config.set(path, abilities)
        }
    }

    /** Converts the original list-based `arena.waves` format (version 1) into wave sections. */
    private fun migrateVersion1Waves(bundled: YamlConfiguration) {
        if (config.getConfigurationSection("arena") == null && config.get("arena") == null) return
        if (config.getConfigurationSection("arena.waves") != null) return
        val waveRows = when (val savedWaves = config.get("arena.waves")) {
            is Map<*, *> -> savedWaves.entries.mapNotNull { entry ->
                val key = entry.key?.toString() ?: return@mapNotNull null
                val row = entry.value as? Map<*, *> ?: return@mapNotNull null
                key to row
            }
            is List<*> -> savedWaves.mapIndexedNotNull { index, rawWave ->
                val entries = rawWave as? List<*> ?: return@mapIndexedNotNull null
                val spawns = entries.mapNotNull { rawEntry ->
                    val row = rawEntry as? Map<*, *> ?: return@mapNotNull null
                    val profile = row["mob"]?.toString()?.lowercase() ?: return@mapNotNull null
                    mapOf(
                        "profile" to profile,
                        "x" to (row["x"] ?: return@mapNotNull null),
                        "y" to (row["y"] ?: return@mapNotNull null),
                        "z" to (row["z"] ?: return@mapNotNull null),
                        "amount" to 1, "yaw" to 0, "pitch" to 0, "spread" to 0,
                    )
                }
                "wave${index + 1}" to mapOf("name" to "Wave ${index + 1}", "spawns" to spawns)
            }
            else -> emptyList()
        }
        config.set("arena.waves", null)
        if (waveRows.isEmpty()) {
            copySection(bundled.getConfigurationSection("arenas.forsaken.waves")!!, config.createSection("arena.waves"))
            return
        }
        val waveSection = config.createSection("arena.waves")
        waveRows.forEach { (key, row) ->
            val section = waveSection.createSection(key)
            section.set("name", row["name"]?.toString() ?: key)
            row["description"]?.let { section.set("description", it.toString()) }
            row["delay-after-seconds"]?.let { section.set("delay-after-seconds", it) }
            section.set("spawns", row["spawns"] as? List<*> ?: emptyList<Any>())
        }
    }

    private fun copySection(from: ConfigurationSection, to: ConfigurationSection) {
        from.getKeys(false).forEach { key ->
            when (val value = from.get(key)) {
                is ConfigurationSection -> copySection(value, to.createSection(key))
                else -> to.set(key, value)
            }
        }
    }

    private fun saveConfigFile() = config.save(dataFolder.toPath().resolve("config.yml").toFile())

    private fun loadSettings(): Settings {
        val maxMobsPerWave = config.getInt("max-mobs-per-wave", 100)
        require(maxMobsPerWave in 1..500) { "max-mobs-per-wave must be between 1 and 500" }
        val spawnDistance = config.getDouble("spawn-menu.distance", 3.0)
        require(spawnDistance in 1.0..16.0) { "spawn-menu.distance must be between 1 and 16" }

        val mobSection = config.getConfigurationSection("mobs")
            ?: throw IllegalArgumentException("Missing 'mobs' section in config.yml")
        val profiles = mobSection.getKeys(false).associateWith { key ->
            val section = mobSection.getConfigurationSection(key)
                ?: throw IllegalArgumentException("mobs.$key must be a configuration section")
            loadMobProfile(key, section)
        }

        val arenaSection = config.getConfigurationSection("arenas")
            ?: throw IllegalArgumentException("Missing 'arenas' section in config.yml")
        val arenas = LinkedHashMap<String, ArenaSpec>()
        arenaSection.getKeys(false).forEach { key ->
            val section = arenaSection.getConfigurationSection(key)
                ?: throw IllegalArgumentException("arenas.$key must be a configuration section")
            arenas[key] = loadArena(key, section, profiles, maxMobsPerWave)
        }
        val maxEventRadius = config.getInt("max-event-radius", 200)
        require(maxEventRadius in EventFeature.MIN_RADIUS..1000) { "max-event-radius must be between ${EventFeature.MIN_RADIUS} and 1000" }
        val eventSection = config.getConfigurationSection("events")
        val events = LinkedHashMap<String, EventSpec>()
        eventSection?.getKeys(false)?.forEach { key ->
            val section = eventSection.getConfigurationSection(key)
                ?: throw IllegalArgumentException("events.$key must be a configuration section")
            events[key.lowercase()] = loadEvent(key.lowercase(), section, profiles, maxMobsPerWave)
        }
        profiles.values.forEach { profile ->
            profile.boss?.abilities?.mapNotNull { it.summon }?.forEach { summon ->
                require(summon in profiles) { "mobs.${profile.key}.boss summons unknown mob profile '$summon'" }
            }
        }
        val schedule = loadSchedule(config.getConfigurationSection("event-schedule"), events, maxEventRadius)
        return Settings(profiles, arenas, maxMobsPerWave, spawnDistance, events, maxEventRadius, schedule)
    }

    private fun loadArena(arenaKey: String, section: ConfigurationSection, profiles: Map<String, MobProfile>, maxMobsPerWave: Int): ArenaSpec {
        val path = "arenas.$arenaKey"
        val icon = section.getString("icon")?.let { name ->
            Material.matchMaterial(name)?.takeIf { it.isItem && !it.isAir }
                ?: throw IllegalArgumentException("Unknown or non-item icon '$name' in $path.icon")
        } ?: Material.NETHER_STAR
        val waveSection = section.getConfigurationSection("waves")
        val waves = waveSection?.getKeys(false)?.mapNotNull { waveKey ->
            val row = waveSection.getConfigurationSection(waveKey)?.getValues(false) ?: return@mapNotNull null
            val wavePath = "$path.waves.$waveKey"
            val rawSpawns = row["spawns"] as? List<*> ?: emptyList<Any>()
            val spawns = rawSpawns.mapIndexed { index, rawSpawn ->
                val at = "$wavePath.spawns[$index]"
                val spawnRow = (rawSpawn as? Map<*, *>) ?: throw IllegalArgumentException("Invalid spawn at $at")
                val profileKey = spawnRow["profile"]?.toString() ?: throw IllegalArgumentException("Missing profile at $at")
                val profile = profiles[profileKey] ?: throw IllegalArgumentException("Unknown profile '$profileKey' at $at")
                val amount = if (spawnRow.containsKey("amount")) {
                    spawnRow["amount"]?.toString()?.toIntOrNull() ?: throw IllegalArgumentException("Invalid amount at $at")
                } else 1
                require(amount > 0) { "amount must be at least 1 at $at" }
                val spread = spawnRow.optionalNumber("spread", 0.0, at)
                require(spread >= 0.0) { "spread cannot be negative at $at" }
                SpawnSpec(
                    profile = profile,
                    x = spawnRow.number("x", at),
                    y = spawnRow.number("y", at),
                    z = spawnRow.number("z", at),
                    amount = amount,
                    yaw = spawnRow.optionalNumber("yaw", 0.0, at).toFloat(),
                    pitch = spawnRow.optionalNumber("pitch", 0.0, at).toFloat(),
                    spread = spread,
                )
            }
            val mobCount = spawns.sumOf { it.amount.toLong() }
            require(mobCount <= maxMobsPerWave) { "$wavePath contains $mobCount mobs, exceeding max-mobs-per-wave ($maxMobsPerWave)" }
            WaveSpec(
                name = row["name"]?.toString() ?: waveKey,
                description = row["description"]?.toString() ?: "",
                delayAfterSeconds = row["delay-after-seconds"]?.toString()?.toLongOrNull()?.coerceAtLeast(0),
                spawns = spawns,
            )
        } ?: emptyList()
        return ArenaSpec(
            key = arenaKey,
            name = section.getString("name", arenaKey)!!,
            description = section.getString("description", "")!!,
            icon = icon,
            worldName = section.getString("world", "world")!!,
            waveDelaySeconds = section.getLong("wave-delay-seconds", 5).coerceAtLeast(0),
            waves = waves,
        )
    }

    private fun Map<*, *>.number(key: String, at: String): Double =
        this[key]?.toString()?.toDoubleOrNull() ?: throw IllegalArgumentException("Invalid/missing coordinate '$key' at $at")

    private fun Map<*, *>.optionalNumber(key: String, default: Double, at: String): Double =
        this[key]?.toString()?.toDoubleOrNull()
            ?: if (containsKey(key)) throw IllegalArgumentException("Invalid '$key' at $at") else default

    // ---------------------------------------------------------------- arena runs

    private fun startCampaign(sender: CommandSender, arena: ArenaSpec) {
        Bukkit.getGlobalRegionScheduler().execute(this) {
            if (runs.containsKey(arena.key)) { tell(sender, "${arena.name} is already running."); return@execute }
            if (arena.waves.isEmpty()) { tell(sender, "${arena.name} has no waves configured."); return@execute }
            val world = Bukkit.getWorld(arena.worldName)
            if (world == null) { tell(sender, "World '${arena.worldName}' for ${arena.name} is not loaded."); return@execute }
            val run = ArenaRun(arena, manual = false, owner = (sender as? Player)?.uniqueId)
            runs[arena.key] = run
            tell(sender, "Starting ${arena.name}: ${arena.waves.size} waves.")
            spawnNextCampaignWave(run, world)
        }
    }

    private fun spawnSelectedWave(player: Player, arena: ArenaSpec, waveIndex: Int) {
        Bukkit.getGlobalRegionScheduler().execute(this) {
            if (runs.containsKey(arena.key)) { tell(player, "Stop ${arena.name} before spawning a single wave there."); return@execute }
            val world = Bukkit.getWorld(arena.worldName)
            if (world == null) { tell(player, "World '${arena.worldName}' for ${arena.name} is not loaded."); return@execute }
            val selected = arena.waves.getOrNull(waveIndex) ?: return@execute
            val run = ArenaRun(arena, manual = true, owner = player.uniqueId)
            runs[arena.key] = run
            tell(player, "Spawning ${arena.name} wave ${waveIndex + 1}: ${selected.name} (${selected.spawns.sumOf { it.amount }} mobs).")
            spawnBatch(run, world, selected.spawns) { if (run.alive.isEmpty()) endRun(run) }
        }
    }

    private fun spawnNextCampaignWave(run: ArenaRun, world: World) {
        if (!run.active || run.manual) return
        val waves = run.arena.waves
        if (run.wave >= waves.size) {
            notifyOwner(run, "${run.arena.name} complete!")
            endRun(run)
            return
        }
        val current = run.wave++
        val selected = waves[current]
        spawnBatch(run, world, selected.spawns) {
            notifyOwner(run, "${run.arena.name}: wave ${current + 1}/${waves.size} (${selected.name}) spawned.")
            if (run.alive.isEmpty()) finishWave(run, world)
        }
    }

    /** Spawns every mob of a wave on its owning region; [onSpawned] runs on the global region afterwards. */
    private fun spawnBatch(run: ArenaRun, world: World, spawns: List<SpawnSpec>, onSpawned: () -> Unit) {
        val total = spawns.sumOf { it.amount }
        run.pendingSpawns.set(total)
        val done = { if (run.active) Bukkit.getGlobalRegionScheduler().execute(this) { if (run.active) onSpawned() } }
        if (total == 0) { done(); return }
        spawns.forEach { spawn ->
            repeat(spawn.amount) {
                val location = spawnLocation(world, spawn)
                Bukkit.getRegionScheduler().execute(this, location) {
                    if (run.active) runCatching {
                        spawnProfile(spawn.profile, location) { entity ->
                            entity.addScoreboardTag(run.tag)
                            // Wave mobs must never despawn, or the wave would wait for them forever.
                            entity.removeWhenFarAway = false
                            run.alive.add(entity.uniqueId)
                        }
                    }.onFailure { error ->
                        logger.log(Level.WARNING, "Failed to spawn '${spawn.profile.key}' for ${run.arena.name}", error)
                        notifyOwner(run, "Failed to spawn ${spawn.profile.name}: ${error}. See the console.")
                    }
                    // Always count the attempt, or a failed spawn would leave the wave waiting forever.
                    if (run.pendingSpawns.decrementAndGet() == 0) done()
                }
            }
        }
    }

    private fun spawnLocation(world: World, spawn: SpawnSpec): Location {
        val angle = ThreadLocalRandom.current().nextDouble(0.0, Math.PI * 2.0)
        val radius = kotlin.math.sqrt(ThreadLocalRandom.current().nextDouble()) * spawn.spread
        return Location(world, spawn.x + kotlin.math.cos(angle) * radius, spawn.y, spawn.z + kotlin.math.sin(angle) * radius, spawn.yaw, spawn.pitch)
    }

    private fun finishWave(run: ArenaRun, world: World) {
        if (!run.active || run.alive.isNotEmpty()) return
        if (run.manual) { endRun(run); return }
        val delay = run.arena.waves.getOrNull(run.wave - 1)?.delayAfterSeconds ?: run.arena.waveDelaySeconds
        run.nextWaveTask?.cancel()
        run.nextWaveTask = Bukkit.getGlobalRegionScheduler().runDelayed(this, {
            run.nextWaveTask = null
            if (run.active && run.alive.isEmpty()) spawnNextCampaignWave(run, world)
        }, (delay * 20).coerceAtLeast(1))
    }

    /** Marks a run finished without touching its mobs. */
    private fun endRun(run: ArenaRun) {
        run.active = false
        run.nextWaveTask?.cancel()
        run.nextWaveTask = null
        runs.remove(run.arena.key, run)
    }

    /** Ends a run and removes the mobs it spawned. */
    private fun stopRun(run: ArenaRun) {
        endRun(run)
        run.alive.toList().forEach { id ->
            Bukkit.getEntity(id)?.let { entity -> entity.scheduler.run(this, { entity.remove() }, null) }
        }
        run.alive.clear()
    }

    private fun runFor(entity: Entity): ArenaRun? {
        val tag = entity.scoreboardTags.firstOrNull { it.startsWith(RUN_TAG_PREFIX) } ?: return null
        return runs.values.firstOrNull { it.tag == tag }
    }

    /** A wave mob from a run that no longer exists (server restart, plugin reload, crash). */
    private fun isOrphan(entity: Entity): Boolean =
        entity.scoreboardTags.any { it.startsWith(RUN_TAG_PREFIX) } && runFor(entity) == null && !events.ownsEntity(entity)

    private fun release(entity: Entity) {
        val run = runFor(entity) ?: return
        if (!run.alive.remove(entity.uniqueId)) return
        if (run.alive.isNotEmpty() || !run.active || run.pendingSpawns.get() != 0) return
        val world = entity.world
        Bukkit.getGlobalRegionScheduler().execute(this) { finishWave(run, world) }
    }

    @EventHandler
    fun onDeath(event: EntityDeathEvent) {
        profileOf(event.entity)?.xp?.let { event.droppedExp = it }
        release(event.entity)
        events.onDeath(event.entity, event.entity.killer)
        bosses.onDeath(event.entity, event.entity.killer)
        bosses.releaseGlow(event.entity)
    }

    /** After every other plugin (FemItems loot) has added its drops. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onDeathDrops(event: EntityDeathEvent) = events.onDeathDrops(event)

    @EventHandler(ignoreCancelled = true)
    fun onItemMerge(event: ItemMergeEvent) = events.onItemMerge(event)

    /** Tracks who takes part in events (melee or projectiles). */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onDamage(event: EntityDamageByEntityEvent) {
        val player = event.damager as? Player ?: (event.damager as? Projectile)?.shooter as? Player ?: return
        events.onDamage(event.entity, player)
    }

    /** Despawns, void falls, /kill and other removals also count as the mob being gone; chunk unloads do not. */
    @EventHandler
    fun onRemove(event: EntityRemoveEvent) {
        if (event.cause == EntityRemoveEvent.Cause.UNLOAD) return
        release(event.entity)
        events.onGone(event.entity)
        bosses.onGone(event.entity)
        bosses.releaseGlow(event.entity)
    }

    /** A wave mob that converts (husk to zombie, zombie to drowned, ...) keeps counting for its wave. */
    @EventHandler(ignoreCancelled = true)
    fun onTransform(event: EntityTransformEvent) {
        events.onTransform(event)
        val run = runFor(event.entity) ?: return
        if (event.entity.uniqueId !in run.alive) return
        event.transformedEntities.forEach { converted ->
            converted.addScoreboardTag(run.tag)
            run.alive.add(converted.uniqueId)
        }
    }

    @EventHandler
    fun onEntitiesLoad(event: EntitiesLoadEvent) {
        event.entities.forEach { entity ->
            if (isOrphan(entity)) entity.scheduler.run(this, { entity.remove() }, null)
            // Bosses lose their behavior task when their chunk unloads; restore it when it loads again.
            else if (entity is LivingEntity) profileOf(entity)?.takeIf { it.boss != null || it.glowColor != null }?.let { profile ->
                entity.scheduler.run(this, { bosses.attach(entity, profile) }, null)
            }
        }
    }

    /** Removes orphaned wave mobs near every online player, each on the thread that owns that player. */
    private fun sweepOrphansNearPlayers(sender: CommandSender?) {
        val found = AtomicInteger(0)
        val players = Bukkit.getOnlinePlayers().toList()
        val remaining = AtomicInteger(players.size)
        if (players.isEmpty()) { sender?.let { tell(it, "No players online; leftovers are removed when their chunks load.") }; return }
        players.forEach { player ->
            val scheduled = player.scheduler.run(this, {
                try {
                    player.getNearbyEntities(SWEEP_RADIUS, SWEEP_RADIUS, SWEEP_RADIUS).filter(::isOrphan).forEach { entity ->
                        found.incrementAndGet()
                        entity.scheduler.run(this, { entity.remove() }, null)
                    }
                } catch (e: IllegalStateException) {
                    logger.fine("Skipped part of the cleanup sweep near ${player.name}: ${e.message}")
                }
                if (remaining.decrementAndGet() == 0 && sender != null) tell(sender, "Cleanup removed ${found.get()} leftover wave mob(s) near online players.")
            }, { if (remaining.decrementAndGet() == 0 && sender != null) tell(sender, "Cleanup removed ${found.get()} leftover wave mob(s).") })
            if (scheduled == null && remaining.decrementAndGet() == 0 && sender != null) tell(sender, "Cleanup removed ${found.get()} leftover wave mob(s).")
        }
    }

    // ---------------------------------------------------------------- free spawning

    internal fun spawnProfile(profile: MobProfile, location: Location, extra: (LivingEntity) -> Unit = {}): LivingEntity {
        @Suppress("UNCHECKED_CAST")
        val type = profile.entityType.entityClass as Class<LivingEntity>
        // randomizeData = false skips vanilla spawn randomization (random babies, chicken jockeys,
        // random armor and weapons), so the profile alone decides how the mob looks.
        val entity = location.world.spawn(location, type, false) { entity ->
            profile.applyTo(entity, logger)
            entity.addScoreboardTag(BossFeature.PROFILE_TAG_PREFIX + profile.key)
            extra(entity)
        }
        bosses.attach(entity, profile)
        return entity
    }

    /** Spawns a profile a few blocks in front of the player, facing them. Not tied to any arena run. */
    private fun spawnInFront(player: Player, profile: MobProfile, amount: Int) {
        val count = amount.coerceAtMost(settings.maxMobsPerWave)
        spawnInFront(player, profile.name, count) { location ->
            spawnProfile(profile, location) { it.addScoreboardTag(FREE_SPAWN_TAG) }
        }
    }

    /**
     * Finds room a few blocks in front of the player and calls [spawn] [count] times on the region that
     * owns that spot. Each location faces the player. Errors are reported to the player and logged.
     */
    internal fun spawnInFront(player: Player, label: String, count: Int, spawn: (Location) -> Unit) {
        val distance = settings.spawnDistance
        player.scheduler.run(this, {
            val origin = player.location
            val forward = origin.direction.setY(0.0).let { if (it.lengthSquared() < 1.0E-6) Vector(0.0, 0.0, 1.0) else it.normalize() }
            val target = origin.clone().add(forward.multiply(distance))
            target.yaw = origin.yaw + 180f
            target.pitch = 0f
            Bukkit.getRegionScheduler().execute(this, target) {
                val spot = standingSpot(target)
                if (spot == null) { tell(player, "No room to spawn $label in front of you."); return@execute }
                try {
                    repeat(count) { index -> spawn(if (index == 0) spot else scatter(spot) ?: spot) }
                    tell(player, "Spawned ${count}x $label.")
                } catch (error: Throwable) {
                    logger.log(Level.WARNING, "Failed to spawn '$label' for ${player.name}", error)
                    tell(player, "Failed to spawn $label: ${error}. See the console.")
                }
            }
        }, null)
    }

    /** The first spot at or up to two blocks above [target] with room for a two-block-tall mob. */
    private fun standingSpot(target: Location): Location? = (0..2).asSequence()
        .map { dy -> target.clone().add(0.0, dy.toDouble(), 0.0) }
        .firstOrNull { it.block.isPassable && it.clone().add(0.0, 1.0, 0.0).block.isPassable }

    private fun scatter(spot: Location): Location? {
        val random = ThreadLocalRandom.current()
        val candidate = spot.clone().add(random.nextDouble(-1.5, 1.5), 0.0, random.nextDouble(-1.5, 1.5))
        if (!Bukkit.isOwnedByCurrentRegion(candidate)) return null
        return candidate.takeIf { it.block.isPassable && it.clone().add(0.0, 1.0, 0.0).block.isPassable }
    }

    // ---------------------------------------------------------------- menus

    internal fun openMainMenu(player: Player) {
        val menu = Menu(3, "FemMobs")
        val bossBattles = settings.events.values.count { it.difficulty == Difficulty.BOSS }
        menu.set(10, Menu.icon(Material.IRON_SWORD, "Events", lore = listOf(
            Menu.field("Configured", "${settings.events.size} ($bossBattles boss battles)"),
            Menu.field("Running", "${events.running.size}"),
            Component.empty(),
            Menu.line("Start an invasion or boss battle"),
            Menu.line("around you, announced to everyone."),
            Component.empty(),
            Menu.hint("Click: browse events"),
        ))) { p, _ -> openEventList(p, 0) }
        menu.set(12, Menu.icon(Material.SPAWNER, "Spawn mobs", lore = listOf(
            Menu.field("Mob profiles", "${settings.profiles.size} (${settings.profiles.values.count { it.boss?.abilities?.isNotEmpty() == true }} with boss abilities)"),
            Component.empty(),
            Menu.line("Spawn any mob or boss a few"),
            Menu.line("blocks in front of you."),
            Component.empty(),
            Menu.hint("Click: browse mobs"),
        ))) { p, _ -> openMobMenu(p, 0) }
        menu.set(14, Menu.icon(Material.EMERALD, "Traders", lore = listOf(
            Menu.field("Saved", "${traders.count}"),
            Component.empty(),
            Menu.line("Spawn your custom villagers and"),
            Menu.line("wandering traders."),
            Component.empty(),
            Menu.hint("Click: browse traders"),
        ))) { p, _ -> openTraderMenu(p, 0) }
        menu.set(16, Menu.icon(Material.COMPASS, "Arenas", lore = listOf(
            Menu.field("Configured", "${settings.arenas.size}"),
            Menu.field("Running", "${runs.size}"),
            Component.empty(),
            Menu.line("Wave campaigns at fixed spots."),
            Component.empty(),
            Menu.hint("Click: browse arenas"),
        ))) { p, _ -> openArenaList(p, 0) }
        menu.set(22, Menu.icon(Material.BOOK, "FemMobs v${pluginMeta.version}", NamedTextColor.YELLOW, listOf(
            Menu.field("Automatic events", if (settings.schedule.enabled) "on, every ${settings.schedule.everyMinutes} min" else "off"),
            Menu.field("Commands", "/femmobs help"),
        )))
        menu.open(player)
    }

    private fun eventLore(spec: EventSpec): MutableList<Component> {
        val lore = mutableListOf<Component>()
        lore += Menu.tag(spec.difficulty.label + if (spec.difficulty == Difficulty.BOSS) " battle" else " invasion", spec.difficulty.color)
        lore += Component.empty()
        spec.boss?.let { boss ->
            val health = boss.attributes.entries.firstOrNull { it.key == Attribute.MAX_HEALTH }?.value ?: boss.health
            lore += Menu.field(if (spec.difficulty == Difficulty.BOSS) "Boss" else "Led by", boss.name + (health?.let { " (${it.roundToInt()} ❤)" } ?: ""))
        }
        lore += Menu.field("Mobs", spec.rosterText())
        lore += Menu.field("Lasts", "${EventFeature.humanTime(spec.durationSeconds)} · " +
            if (spec.mode == EventMode.FIXED) "ends when all are killed" else "keeps ${spec.mobs} alive")
        spec.rewards?.let { lore += Menu.field("Rewards", "yes, with ${it.minPlayers}+ players") }
        settings.schedule.weights[spec.key]?.let { weight ->
            lore += Menu.field("Schedule chance", EventFeature.percent(weight, settings.schedule.weights.values.sum()))
        }
        lore += Component.empty()
        lore += events.statusOf(spec.key)?.let { Menu.line("● Running · $it", NamedTextColor.RED) } ?: Menu.line("○ Idle", NamedTextColor.DARK_GRAY)
        return lore
    }

    private fun nameColor(spec: EventSpec) = if (spec.difficulty == Difficulty.BOSS) NamedTextColor.RED else NamedTextColor.LIGHT_PURPLE

    private fun openEventList(player: Player, page: Int) {
        // Easy to hard, then boss battles.
        val ordered = settings.events.values.sortedBy { it.difficulty.ordinal }
        pagedMenu(player, "Events", ordered, page, ::openEventList, ::openMainMenu, "Back to menu") { menu, slot, spec ->
            val lore = eventLore(spec)
            lore += Component.empty()
            lore += Menu.hint("Click: choose a radius and start")
            menu.set(slot, Menu.icon(spec.icon, spec.name, nameColor(spec), lore)) { p, _ -> openEventRadius(p, spec.key) }
        }
    }

    /** Pick a radius; the event starts centered on the player. */
    private fun openEventRadius(player: Player, eventKey: String) {
        val spec = settings.events[eventKey] ?: return openEventList(player, 0)
        val menu = Menu(3, "FemMobs • ${spec.name}")
        menu.set(4, Menu.icon(spec.icon, spec.name, nameColor(spec), eventLore(spec)))
        val radii = listOf(10, 15, 25, 50, 75, 100, 150).filter { it <= settings.maxEventRadius }
        radii.forEachIndexed { index, radius ->
            menu.set(10 + index, Menu.icon(Material.LIME_DYE, "Start · $radius blocks", NamedTextColor.GREEN, listOf(
                Menu.line("Mobs spawn up to $radius blocks"),
                Menu.line("around you (${radius * 2} blocks across)."),
                Component.empty(),
                Menu.hint("Click: start it here"),
            ))) { p, _ -> p.closeInventory(); events.start(p, spec.key, radius) }
        }
        menu.set(18, Menu.back("Back to events")) { p, _ -> openEventList(p, 0) }
        if (events.isRunning(spec.key)) {
            menu.set(22, Menu.icon(Material.BARRIER, "Stop event", NamedTextColor.RED, listOf(
                Menu.line("Ends it now and removes its mobs."),
                Component.empty(),
                Menu.hint("Click: stop"),
            ))) { p, _ -> p.closeInventory(); events.stop(p, spec.key) }
        }
        menu.set(26, Menu.icon(Material.PAPER, "Other radius", NamedTextColor.YELLOW, listOf(
            Menu.line("/femmobs event start ${spec.key} <radius>"),
            Menu.line("Any radius from 5 to ${settings.maxEventRadius} blocks."),
        )))
        menu.open(player)
    }

    /** Fills a 6-row menu with one page of [items] and adds back/previous/next on the bottom row, like FemItems. */
    private fun <T> pagedMenu(
        player: Player, title: String, items: List<T>, page: Int,
        reopen: (Player, Int) -> Unit, back: ((Player) -> Unit)?, backLabel: String,
        render: (Menu, Int, T) -> Unit,
    ): Menu {
        val pages = ((items.size + Menu.PAGE_SIZE - 1) / Menu.PAGE_SIZE).coerceAtLeast(1)
        val safePage = page.coerceIn(0, pages - 1)
        val menu = Menu(6, "FemMobs • $title" + if (pages > 1) " (${safePage + 1}/$pages)" else "")
        val first = safePage * Menu.PAGE_SIZE
        items.subList(first, minOf(first + Menu.PAGE_SIZE, items.size)).forEachIndexed { slot, item -> render(menu, slot, item) }
        if (back != null) menu.set(Menu.BACK_SLOT, Menu.back(backLabel)) { p, _ -> back(p) }
        if (safePage > 0) menu.set(Menu.PREVIOUS_SLOT, Menu.previous()) { p, _ -> reopen(p, safePage - 1) }
        if (safePage < pages - 1) menu.set(Menu.NEXT_SLOT, Menu.next()) { p, _ -> reopen(p, safePage + 1) }
        return menu.also { it.open(player) }
    }

    private fun openArenaList(player: Player, page: Int) {
        pagedMenu(player, "Arenas", settings.arenas.values.toList(), page, ::openArenaList, ::openMainMenu, "Back to menu") { menu, slot, arena ->
            val run = runs[arena.key]
            val lore = mutableListOf<Component>()
            if (arena.description.isNotBlank()) { lore += Menu.line(arena.description); lore += Component.empty() }
            lore += Menu.field("World", arena.worldName)
            lore += Menu.field("Waves", "${arena.waves.size}")
            lore += Component.empty()
            lore += if (run == null) Menu.line("○ Idle", NamedTextColor.DARK_GRAY) else Menu.line("● Running · ${describeRun(run)}", NamedTextColor.RED)
            lore += Component.empty()
            lore += Menu.hint("Click: view waves")
            menu.set(slot, Menu.icon(arena.icon, arena.name, lore = lore)) { p, _ -> openWaveMenu(p, arena.key, 0) }
        }
    }

    private fun openWaveMenu(player: Player, arenaKey: String, page: Int) {
        val arena = settings.arenas[arenaKey] ?: return openArenaList(player, 0)
        val menu = pagedMenu(player, arena.name, arena.waves.withIndex().toList(), page,
            { p, newPage -> openWaveMenu(p, arenaKey, newPage) }, { p -> openArenaList(p, 0) }, "Back to arenas") { menu, slot, (waveIndex, wave) ->
            val lore = mutableListOf<Component>()
            if (wave.description.isNotBlank()) { lore += Menu.line(wave.description); lore += Component.empty() }
            lore += Menu.field("Mobs", "${wave.spawns.sumOf { it.amount }}")
            wave.spawns.groupBy { it.profile }.forEach { (profile, spawns) ->
                lore += Menu.line("  • ${spawns.sumOf { it.amount }}x ${profile.name}")
            }
            lore += Menu.field("Next wave after", "${wave.delayAfterSeconds ?: arena.waveDelaySeconds}s")
            lore += Component.empty()
            lore += Menu.hint("Click: spawn only this wave")
            menu.set(slot, Menu.icon(Material.NETHER_STAR, "Wave ${waveIndex + 1}: ${wave.name}", lore = lore)) { p, _ ->
                p.closeInventory()
                spawnSelectedWave(p, arena, waveIndex)
            }
        }
        val run = runs[arena.key]
        menu.set(49, Menu.icon(Material.BOOK, arena.name, NamedTextColor.YELLOW, listOf(
            Menu.field("World", arena.worldName),
            if (run == null) Menu.line("○ Idle", NamedTextColor.DARK_GRAY) else Menu.line("● Running · ${describeRun(run)}", NamedTextColor.RED),
        )))
        menu.set(52, Menu.icon(Material.LIME_DYE, "Start campaign", NamedTextColor.GREEN, listOf(
            Menu.line("Runs all ${arena.waves.size} waves in order."),
            Component.empty(),
            Menu.hint("Click: start"),
        ))) { p, _ -> p.closeInventory(); startCampaign(p, arena) }
        menu.set(53, Menu.icon(Material.BARRIER, "Stop arena", NamedTextColor.RED, listOf(
            Menu.line("Stops it and removes its wave mobs."),
            Component.empty(),
            Menu.hint("Click: stop"),
        ))) { p, _ -> p.closeInventory(); stop(p, arena.key) }
    }

    private fun openMobMenu(player: Player, page: Int) {
        val summoners = settings.profiles.values.flatMap { boss -> boss.boss?.abilities.orEmpty().mapNotNull { it.summon }.map { it to boss } }
            .groupBy({ it.first }, { it.second.name })
        pagedMenu(player, "Spawn mobs", settings.profiles.values.toList(), page, ::openMobMenu, ::openMainMenu, "Back to menu") { menu, slot, profile ->
            val lore = mutableListOf<Component>()
            val abilities = profile.boss?.abilities.orEmpty()
            when {
                abilities.isNotEmpty() -> lore += Menu.tag("★ Boss", NamedTextColor.GOLD)
                profile.boss != null -> lore += Menu.tag("★ Mini-boss", NamedTextColor.GOLD)
                profile.key in summoners -> lore += Menu.tag("Minion", NamedTextColor.DARK_PURPLE)
            }
            if (lore.isNotEmpty()) lore += Component.empty()
            val maxHealth = profile.attributes.entries.firstOrNull { it.key == Attribute.MAX_HEALTH }?.value ?: profile.health
            maxHealth?.let { lore += Menu.field("Health", "${it.roundToInt()} ❤") }
            profile.attributes.entries.firstOrNull { it.key == Attribute.ATTACK_DAMAGE }?.value?.let { lore += Menu.field("Damage", "$it + weapon") }
            profile.xp?.let { lore += Menu.field("XP", "$it") }
            if (abilities.isNotEmpty()) lore += Menu.field("Abilities", abilities.joinToString(", ") { ability ->
                ability.type.name.lowercase() + (ability.effect?.let { " (${it.key.key.replace('_', ' ')})" } ?: "")
            })
            summoners[profile.key]?.let { lore += Menu.field("Summoned by", it.distinct().joinToString(", ")) }
            lore += Component.empty()
            lore += Menu.line(profile.key + (profile.tags.firstOrNull()?.let { " · $it" } ?: ""), NamedTextColor.DARK_GRAY)
            lore += Menu.hint("Click: spawn 1 · Shift-click: spawn 5")
            menu.set(slot, Menu.icon(profile.icon, profile.name, profile.nameColor, lore)) { p, click -> spawnInFront(p, profile, if (click.isShiftClick) 5 else 1) }
        }
    }

    private fun openTraderMenu(player: Player, page: Int) {
        val menu = pagedMenu(player, "Traders", traders.saved.toList(), page, ::openTraderMenu, ::openMainMenu, "Back to menu") { menu, slot, trader ->
            val lore = mutableListOf(
                Menu.field("Type", traders.describeKind(trader)),
                Menu.field("Trades", "${trader.trades.size}"),
            )
            trader.trades.take(6).forEach { trade -> lore += Menu.line("  • ${traders.tradeText(trade)}") }
            if (trader.trades.size > 6) lore += Menu.line("  • ...and ${trader.trades.size - 6} more")
            lore += Component.empty()
            lore += Menu.line(trader.key, NamedTextColor.DARK_GRAY)
            lore += Menu.hint("Click: spawn in front of you · Shift-click: edit")
            val egg = if (trader.kind == TraderKind.VILLAGER) Material.VILLAGER_SPAWN_EGG else Material.WANDERING_TRADER_SPAWN_EGG
            menu.set(slot, Menu.icon(egg, traders.displayName(trader), lore = lore)) { p, click ->
                if (click.isShiftClick) { p.closeInventory(); traders.edit(p, trader.key) } else traders.spawnSaved(p, trader.key)
            }
        }
        menu.set(49, Menu.icon(Material.WRITABLE_BOOK, "New trader", NamedTextColor.GREEN, listOf(
            Menu.line("Build one step by step in chat:"),
            Menu.line("/femmobs trader create <name>"),
            Component.empty(),
            Menu.hint("Click: show the command in chat"),
        ))) { p, _ ->
            p.closeInventory()
            p.sendMessage(Msg.command("/femmobs trader create <name>", "click, then type a name", fill = "/femmobs trader create"))
        }
    }

    @EventHandler
    fun onMenuClick(event: InventoryClickEvent) {
        val menu = event.view.topInventory.holder as? Menu ?: return
        event.isCancelled = true
        if (event.clickedInventory != event.view.topInventory || event.rawSlot !in 0 until menu.size) return
        val player = event.whoClicked as? Player ?: return
        val slot = event.rawSlot
        val click = event.click
        // Opening another inventory inside a click event is unreliable; act on the next tick.
        player.scheduler.run(this, { menu.click(player, slot, click) }, null)
    }

    @EventHandler
    fun onMenuDrag(event: InventoryDragEvent) {
        if (event.view.topInventory.holder is Menu) event.isCancelled = true
    }

    // ---------------------------------------------------------------- messaging

    /** Sends run progress only to whoever started it (console log if it was started from the console). */
    private fun notifyOwner(run: ArenaRun, message: String) {
        val owner = run.owner
        if (owner == null) logger.info(message)
        else Bukkit.getPlayer(owner)?.let { tell(it, message) }
    }

    /** Sends a styled `[FemMobs]` line on the player's own thread (or straight to the console). */
    internal fun tell(sender: CommandSender, message: String) {
        if (sender is Player) sender.scheduler.run(this, { sender.say(message) }, null)
        else sender.say(message)
    }

    private fun isFolia(): Boolean =
        runCatching { Class.forName("io.papermc.paper.threadedregions.RegionizedServer") }.isSuccess

    private companion object {
        const val PERMISSION = "femmobs.admin"
        const val CONFIG_VERSION = 9
        /** Boss battle rosters from earlier versions; replaced on upgrade only if still unchanged. */
        val OLD_BOSS_ESCORTS = mapOf(
            "molten_warden_battle" to listOf(mapOf("profile" to "grunt", "amount" to "6")),
            "cosmic_wraith_battle" to listOf(mapOf("profile" to "archer", "amount" to "6")),
            "reaper_battle" to listOf(mapOf("profile" to "grunt", "amount" to "6")),
            "frost_battle" to listOf(mapOf("profile" to "archer", "amount" to "6")),
            "storm_battle" to listOf(mapOf("profile" to "ravager", "amount" to "4")),
        )
        /** Events whose schedule chances are added to existing configs on upgrade. */
        val NEW_SCHEDULED_EVENTS = setOf("molten_warden_battle", "cosmic_wraith_battle", "reaper_battle", "frost_battle", "storm_battle", "venom_battle")

        /**
         * Version 8 softened some boss abilities. Each entry: mob, ability type, effect, old level, old duration,
         * new level, new duration. Applied on upgrade only to abilities still at the old values.
         */
        val SOFTENED_ABILITIES = listOf(
            listOf("hollow_reaper", "aura", "wither", "2", "5", "1", "4"),
            listOf("hollow_reaper", "aura", "darkness", "1", "6", "1", "4"),
            listOf("frost_jarl", "aura", "slowness", "3", "3", "2", "3"),
            listOf("frost_jarl", "aura", "mining_fatigue", "2", "6", "1", "6"),
        )
        /** Prefix of the per-run scoreboard tag on wave mobs; unchanged from earlier versions so old leftovers are found. */
        const val RUN_TAG_PREFIX = "femmobs_"
        /** Marks mobs spawned from the mob menu or /femmobs spawn. They are not tracked or cleaned up. */
        const val FREE_SPAWN_TAG = "femmobs.spawned"
        const val SWEEP_RADIUS = 64.0
    }
}
