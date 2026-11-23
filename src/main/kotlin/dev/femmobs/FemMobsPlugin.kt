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
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Entity
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDeathEvent
import org.bukkit.event.entity.EntityRemoveEvent
import org.bukkit.event.entity.EntityTransformEvent
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.event.world.EntitiesLoadEvent
import org.bukkit.permissions.Permission
import org.bukkit.permissions.PermissionDefault
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.util.Vector
import java.util.UUID
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

    override fun onEnable() {
        saveDefaultConfig()
        migrateConfigIfNeeded()
        settings = loadSettings()
        server.pluginManager.registerEvents(this, this)
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
        val folia = isFolia()
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
        .requires { source -> source.sender.hasPermission(PERMISSION) }
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
        .then(Commands.literal("help").executes { ctx -> sendHelp(ctx.sender) })
        .executes { ctx -> sendHelp(ctx.sender) }
        .build()

    private val CommandContext<CommandSourceStack>.sender: CommandSender get() = source.sender

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
            sender.sendMessage(if (arenaKey == null) "Usage: /femmobs start <arena>. Arenas: ${arenaList()}"
                else "Unknown arena '$arenaKey'. Arenas: ${arenaList()}")
            return 0
        }
        startCampaign(sender, arena)
        return Command.SINGLE_SUCCESS
    }

    private fun stop(sender: CommandSender, arenaKey: String?): Int {
        if (arenaKey != null && findArena(arenaKey) == null) {
            sender.sendMessage("Unknown arena '$arenaKey'. Arenas: ${arenaList()}")
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
        if (runs.isEmpty()) sender.sendMessage("No arenas are running.")
        else runs.values.forEach { sender.sendMessage("${it.arena.name}: ${describeRun(it)}") }
        return Command.SINGLE_SUCCESS
    }

    private fun describeRun(run: ArenaRun): String =
        if (run.manual) "single wave, ${run.alive.size} mobs alive"
        else "campaign wave ${run.wave.coerceAtLeast(1)}/${run.arena.waves.size}, ${run.alive.size} mobs alive"

    private fun reload(sender: CommandSender): Int {
        Bukkit.getGlobalRegionScheduler().execute(this) {
            val stopped = runs.values.toList()
            stopped.forEach(::stopRun)
            try {
                reloadConfig()
                migrateConfigIfNeeded()
                settings = loadSettings()
                val note = if (stopped.isEmpty()) "" else " Stopped ${stopped.size} running arena(s) first."
                tell(sender, "FemMobs reloaded: ${settings.profiles.size} mob profiles, ${settings.arenas.size} arenas.$note")
            } catch (e: Exception) {
                logger.warning("Reload failed: ${e.message}")
                tell(sender, "Reload failed, keeping the previous config: ${e.message}")
            }
        }
        return Command.SINGLE_SUCCESS
    }

    private fun spawnCommand(sender: CommandSender, mobKey: String, amount: Int): Int {
        if (sender !is Player) { sender.sendMessage("Only a player can spawn mobs in front of themselves."); return 0 }
        val profile = findProfile(mobKey)
        if (profile == null) {
            sender.sendMessage("Unknown mob '$mobKey'. Mobs: ${settings.profiles.keys.joinToString(", ")}")
            return 0
        }
        spawnInFront(sender, profile, amount)
        return Command.SINGLE_SUCCESS
    }

    private fun openGui(sender: CommandSender): Int {
        if (sender !is Player) { sender.sendMessage("Only a player can open the menu."); return 0 }
        sender.scheduler.run(this, { openMainMenu(sender) }, null)
        return Command.SINGLE_SUCCESS
    }

    private fun sendHelp(sender: CommandSender): Int {
        listOf(
            "FemMobs commands:",
            "/femmobs gui - open the menu: browse arenas and their waves, or spawn any mob profile in front of you.",
            "/femmobs start <arena> - start an arena's automatic campaign; the next wave starts once the current one is cleared.",
            "/femmobs stop [arena] - stop one arena (or all) and remove the wave mobs it spawned.",
            "/femmobs spawn <mob> [amount] - spawn a mob profile a few blocks in front of you.",
            "/femmobs status - show running arenas and how many mobs remain.",
            "/femmobs cleanup - remove leftover wave mobs (from a restart or reload) near online players.",
            "/femmobs reload - reload config.yml (stops running arenas first).",
            "Edit mob profiles and arenas in plugins/FemMobs/config.yml, then run /femmobs reload.",
        ).forEach(sender::sendMessage)
        return Command.SINGLE_SUCCESS
    }

    // ---------------------------------------------------------------- config

    private fun migrateConfigIfNeeded() {
        // contains(path, true) ignores the bundled defaults that reloadConfig() attaches.
        if (config.contains("arenas", true)) {
            var changed = false
            if (!config.contains("max-mobs-per-wave", true)) { config.set("max-mobs-per-wave", 100); changed = true }
            if (!config.contains("spawn-menu.distance", true)) { config.set("spawn-menu.distance", 3.0); changed = true }
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
        config.set("config-version", 3)
        saveConfigFile()
        logger.info("Updated FemMobs config to version 3 (multiple arenas). Your previous arena is now 'arenas.forsaken'.")
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
        return Settings(profiles, arenas, maxMobsPerWave, spawnDistance)
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
        entity.scoreboardTags.any { it.startsWith(RUN_TAG_PREFIX) } && runFor(entity) == null

    private fun release(entity: Entity) {
        val run = runFor(entity) ?: return
        if (!run.alive.remove(entity.uniqueId)) return
        if (run.alive.isNotEmpty() || !run.active || run.pendingSpawns.get() != 0) return
        val world = entity.world
        Bukkit.getGlobalRegionScheduler().execute(this) { finishWave(run, world) }
    }

    @EventHandler
    fun onDeath(event: EntityDeathEvent) = release(event.entity)

    /** Despawns, void falls, /kill and other removals also count as the mob being gone; chunk unloads do not. */
    @EventHandler
    fun onRemove(event: EntityRemoveEvent) {
        if (event.cause != EntityRemoveEvent.Cause.UNLOAD) release(event.entity)
    }

    /** A wave mob that converts (husk to zombie, zombie to drowned, ...) keeps counting for its wave. */
    @EventHandler(ignoreCancelled = true)
    fun onTransform(event: EntityTransformEvent) {
        val run = runFor(event.entity) ?: return
        if (event.entity.uniqueId !in run.alive) return
        event.transformedEntities.forEach { converted ->
            converted.addScoreboardTag(run.tag)
            run.alive.add(converted.uniqueId)
        }
    }

    @EventHandler
    fun onEntitiesLoad(event: EntitiesLoadEvent) {
        event.entities.filter(::isOrphan).forEach { entity -> entity.scheduler.run(this, { entity.remove() }, null) }
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

    private fun spawnProfile(profile: MobProfile, location: Location, extra: (LivingEntity) -> Unit = {}): LivingEntity {
        @Suppress("UNCHECKED_CAST")
        val type = profile.entityType.entityClass as Class<LivingEntity>
        return location.world.spawn(location, type) { entity ->
            profile.applyTo(entity, logger)
            extra(entity)
        }
    }

    /** Spawns a profile a few blocks in front of the player, facing them. Not tied to any arena run. */
    private fun spawnInFront(player: Player, profile: MobProfile, amount: Int) {
        val count = amount.coerceAtMost(settings.maxMobsPerWave)
        val distance = settings.spawnDistance
        player.scheduler.run(this, {
            val origin = player.location
            val forward = origin.direction.setY(0.0).let { if (it.lengthSquared() < 1.0E-6) Vector(0.0, 0.0, 1.0) else it.normalize() }
            val target = origin.clone().add(forward.multiply(distance))
            target.yaw = origin.yaw + 180f
            target.pitch = 0f
            Bukkit.getRegionScheduler().execute(this, target) {
                val spot = standingSpot(target)
                if (spot == null) { tell(player, "No room to spawn ${profile.name} in front of you."); return@execute }
                try {
                    repeat(count) { index ->
                        val location = if (index == 0) spot else scatter(spot) ?: spot
                        spawnProfile(profile, location) { it.addScoreboardTag(FREE_SPAWN_TAG) }
                    }
                    tell(player, "Spawned ${count}x ${profile.name}.")
                } catch (error: Throwable) {
                    logger.log(Level.WARNING, "Failed to spawn '${profile.key}' for ${player.name}", error)
                    tell(player, "Failed to spawn ${profile.name}: ${error}. See the console.")
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

    private fun openMainMenu(player: Player) {
        val menu = Menu(3, "FemMobs")
        menu.set(11, Menu.icon(Material.COMPASS, "Arenas", NamedTextColor.GOLD, listOf(
            Menu.line("${settings.arenas.size} configured, ${runs.size} running", NamedTextColor.YELLOW),
            Menu.line("Browse arenas, spawn single waves,"),
            Menu.line("or start and stop campaigns."),
        ))) { p, _ -> openArenaList(p, 0) }
        menu.set(15, Menu.icon(Material.SPAWNER, "Spawn mobs", NamedTextColor.GOLD, listOf(
            Menu.line("${settings.profiles.size} mob profiles", NamedTextColor.YELLOW),
            Menu.line("Spawn any profile a few blocks"),
            Menu.line("in front of you."),
        ))) { p, _ -> openMobMenu(p, 0) }
        menu.open(player)
    }

    /** Fills a 6-row menu with one page of [items] and adds previous/next/back controls on the bottom row. */
    private fun <T> pagedMenu(
        player: Player, title: String, items: List<T>, page: Int,
        reopen: (Player, Int) -> Unit, back: ((Player) -> Unit)?,
        render: (Menu, Int, T) -> Unit,
    ): Menu {
        val pages = ((items.size + Menu.PAGE_SIZE - 1) / Menu.PAGE_SIZE).coerceAtLeast(1)
        val safePage = page.coerceIn(0, pages - 1)
        val menu = Menu(6, "$title (${safePage + 1}/$pages)")
        val first = safePage * Menu.PAGE_SIZE
        items.subList(first, minOf(first + Menu.PAGE_SIZE, items.size)).forEachIndexed { slot, item -> render(menu, slot, item) }
        if (safePage > 0) menu.set(45, Menu.icon(Material.ARROW, "Previous page")) { p, _ -> reopen(p, safePage - 1) }
        if (safePage < pages - 1) menu.set(53, Menu.icon(Material.ARROW, "Next page")) { p, _ -> reopen(p, safePage + 1) }
        if (back != null) menu.set(48, Menu.icon(Material.OAK_DOOR, "Back")) { p, _ -> back(p) }
        return menu.also { it.open(player) }
    }

    private fun openArenaList(player: Player, page: Int) {
        pagedMenu(player, "Arenas", settings.arenas.values.toList(), page, ::openArenaList, ::openMainMenu) { menu, slot, arena ->
            val run = runs[arena.key]
            val lore = mutableListOf<Component>()
            if (arena.description.isNotBlank()) lore += Menu.line(arena.description)
            lore += Menu.line("World: ${arena.worldName}")
            lore += Menu.line("${arena.waves.size} wave(s)", NamedTextColor.YELLOW)
            lore += if (run == null) Menu.line("Idle", NamedTextColor.DARK_GRAY) else Menu.line("Running: ${describeRun(run)}", NamedTextColor.RED)
            lore += Component.empty()
            lore += Menu.line("Click to view waves", NamedTextColor.GREEN)
            menu.set(slot, Menu.icon(arena.icon, arena.name, NamedTextColor.GOLD, lore)) { p, _ -> openWaveMenu(p, arena.key, 0) }
        }
    }

    private fun openWaveMenu(player: Player, arenaKey: String, page: Int) {
        val arena = settings.arenas[arenaKey] ?: return openArenaList(player, 0)
        val menu = pagedMenu(player, arena.name, arena.waves.withIndex().toList(), page,
            { p, newPage -> openWaveMenu(p, arenaKey, newPage) }, { p -> openArenaList(p, 0) }) { menu, slot, (waveIndex, wave) ->
            val lore = mutableListOf(
                Menu.line("${wave.spawns.sumOf { it.amount }} mob(s)", NamedTextColor.YELLOW),
                Menu.line("Click to spawn only this wave", NamedTextColor.GREEN),
                Component.empty(),
            )
            if (wave.description.isNotBlank()) lore += Menu.line(wave.description)
            wave.spawns.forEach { spawn ->
                lore += Menu.line("${spawn.profile.name} x${spawn.amount} (${spawn.profile.key})", NamedTextColor.WHITE)
                lore += Menu.line("  ${spawn.x}, ${spawn.y}, ${spawn.z}; yaw ${spawn.yaw}, pitch ${spawn.pitch}")
                if (spawn.spread > 0.0) lore += Menu.line("  spread radius ${spawn.spread}")
            }
            lore += Menu.line("Next campaign delay: ${wave.delayAfterSeconds ?: arena.waveDelaySeconds}s", NamedTextColor.DARK_GRAY)
            menu.set(slot, Menu.icon(Material.NETHER_STAR, "Wave ${waveIndex + 1}: ${wave.name}", NamedTextColor.GOLD, lore)) { p, _ ->
                p.closeInventory()
                spawnSelectedWave(p, arena, waveIndex)
            }
        }
        val run = runs[arena.key]
        menu.set(49, Menu.icon(Material.BOOK, arena.name, NamedTextColor.YELLOW, listOf(
            if (run == null) Menu.line("Idle") else Menu.line("Running: ${describeRun(run)}", NamedTextColor.RED),
            Menu.line("World: ${arena.worldName}"),
        )))
        menu.set(47, Menu.icon(Material.LIME_CONCRETE, "Start campaign", NamedTextColor.GREEN, listOf(
            Menu.line("Runs all ${arena.waves.size} waves in order."),
        ))) { p, _ -> p.closeInventory(); startCampaign(p, arena) }
        menu.set(51, Menu.icon(Material.BARRIER, "Stop arena", NamedTextColor.RED, listOf(
            Menu.line("Stops this arena and removes its wave mobs."),
        ))) { p, _ -> p.closeInventory(); stop(p, arena.key) }
    }

    private fun openMobMenu(player: Player, page: Int) {
        pagedMenu(player, "Spawn mobs", settings.profiles.values.toList(), page, ::openMobMenu, ::openMainMenu) { menu, slot, profile ->
            val lore = mutableListOf(
                Menu.line("Profile: ${profile.key}"),
                Menu.line("Entity: ${profile.entityType.name.lowercase()}"),
            )
            profile.health?.let { lore += Menu.line("Health: $it") }
            if (profile.tags.isNotEmpty()) lore += Menu.line("Tags: ${profile.tags.joinToString(", ")}", NamedTextColor.DARK_GRAY)
            lore += Component.empty()
            lore += Menu.line("Left-click: spawn 1 in front of you", NamedTextColor.GREEN)
            lore += Menu.line("Shift-click: spawn 5", NamedTextColor.GREEN)
            menu.set(slot, Menu.icon(profile.icon, profile.name, profile.nameColor, lore)) { p, click -> spawnInFront(p, profile, if (click.isShiftClick) 5 else 1) }
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

    private fun tell(sender: CommandSender, message: String) {
        if (sender is Player) sender.scheduler.run(this, { sender.sendMessage(message) }, null)
        else sender.sendMessage(message)
    }

    private fun isFolia(): Boolean =
        runCatching { Class.forName("io.papermc.paper.threadedregions.RegionizedServer") }.isSuccess

    private companion object {
        const val PERMISSION = "femmobs.admin"
        /** Prefix of the per-run scoreboard tag on wave mobs; unchanged from earlier versions so old leftovers are found. */
        const val RUN_TAG_PREFIX = "femmobs_"
        /** Marks mobs spawned from the mob menu or /femmobs spawn. They are not tracked or cleaned up. */
        const val FREE_SPAWN_TAG = "femmobs.spawned"
        const val SWEEP_RADIUS = 64.0
    }
}
