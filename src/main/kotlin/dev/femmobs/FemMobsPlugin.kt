package dev.femmobs

import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.entity.LivingEntity
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDeathEvent
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.permissions.Permission
import org.bukkit.permissions.PermissionDefault
import com.mojang.brigadier.Command
import io.papermc.paper.command.brigadier.Commands
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.UUID

class FemMobsPlugin : JavaPlugin(), Listener {
    private data class Spawn(val kind: MobKind, val x: Int, val y: Int, val z: Int)
    private val alive = ConcurrentHashMap.newKeySet<UUID>()
    @Volatile private var runTag = "femmobs_run"
    @Volatile private var running = false
    @Volatile private var wave = 0
    @Volatile private var pendingSpawns = AtomicInteger(0)
    @Volatile private var runGeneration = 0L
    private var waves: List<List<Spawn>> = emptyList()
    private var worldName = "world"
    private var delaySeconds = 5L

    override fun onEnable() {
        saveDefaultConfig()
        loadSettings()
        server.pluginManager.registerEvents(this, this)
        if (server.pluginManager.getPermission("femmobs.admin") == null) {
            server.pluginManager.addPermission(Permission("femmobs.admin", PermissionDefault.OP))
        }
        lifecycleManager.registerEventHandler(LifecycleEvents.COMMANDS) { event ->
            event.registrar().register(
                Commands.literal("arenamobs")
                    .then(Commands.literal("start").executes { context -> runCommand(context.source.sender, "start") })
                    .then(Commands.literal("stop").executes { context -> runCommand(context.source.sender, "stop") })
                    .then(Commands.literal("status").executes { context -> runCommand(context.source.sender, "status") })
                    .then(Commands.literal("reload").executes { context -> runCommand(context.source.sender, "reload") })
                    .build()
            )
        }
    }

    private fun runCommand(sender: org.bukkit.command.CommandSender, action: String): Int {
        if (!sender.hasPermission("femmobs.admin")) { sender.sendMessage("You lack permission."); return 0 }
        when (action) {
            "start" -> start(sender)
            "stop" -> stop(sender)
            "status" -> sender.sendMessage(if (running) "Wave ${wave.coerceAtLeast(1)}/${waves.size}; ${alive.size} mobs alive." else "Arena waves are stopped.")
            "reload" -> { reloadConfig(); loadSettings(); sender.sendMessage("FemMobs config reloaded.") }
        }
        return Command.SINGLE_SUCCESS
    }

    private fun loadSettings() {
        worldName = config.getString("arena.world", "world")!!
        delaySeconds = config.getLong("arena.wave-delay-seconds", 5).coerceAtLeast(0)
        waves = (config.getList("arena.waves") ?: emptyList<Any>()).map { rawWave ->
            (rawWave as List<*>).map { value ->
                val row = value as Map<*, *>
                Spawn(MobKind.valueOf(row["mob"].toString().uppercase()), row["x"].toString().toInt(), row["y"].toString().toInt(), row["z"].toString().toInt())
            }
        }
    }

    private fun start(sender: org.bukkit.command.CommandSender) {
        if (running) { sender.sendMessage("An arena run is already active."); return }
        val world = Bukkit.getWorld(worldName)
        if (world == null) { sender.sendMessage("Arena world '$worldName' is not loaded."); return }
        alive.clear(); wave = 0; running = true
        runGeneration++
        runTag = "femmobs_${UUID.randomUUID().toString().replace("-", "")}"
        sender.sendMessage("Starting Forsaken arena waves.")
        spawnWave(world)
    }

    private fun spawnWave(world: World) {
        if (!running) return
        val generation = runGeneration
        if (wave >= waves.size) { running = false; broadcast(world, "Arena complete!"); return }
        val current = wave++
        val spawns = waves[current]
        val remaining = AtomicInteger(spawns.size)
        pendingSpawns = remaining
        if (spawns.isEmpty()) { advance(world); return }
        spawns.forEach { spawn ->
            val location = Location(world, spawn.x + .5, spawn.y.toDouble(), spawn.z + .5)
            Bukkit.getRegionScheduler().execute(this, location) {
                if (!running || generation != runGeneration) { remaining.decrementAndGet(); if (remaining.get() == 0 && generation == runGeneration) checkWaveComplete(world); return@execute }
                val entity = world.spawnEntity(location, spawn.kind.entityType) as LivingEntity
                entity.addScoreboardTag(runTag)
                spawn.kind.applyTo(entity)
                alive.add(entity.uniqueId)
                if (remaining.decrementAndGet() == 0) {
                    broadcast(world, "Arena wave ${current + 1}/${waves.size} spawned.")
                    checkWaveComplete(world)
                }
            }
        }
    }

    @EventHandler
    fun onDeath(event: EntityDeathEvent) {
        val entity = event.entity
        if (!entity.scoreboardTags.contains(runTag)) return
        alive.remove(entity.uniqueId)
        if (alive.isEmpty() && running && pendingSpawns.get() == 0) entity.scheduler.run(this, { advance(entity.world) }, null)
    }

    private fun checkWaveComplete(world: World) { if (alive.isEmpty() && running) advance(world) }
    private fun advance(world: World) {
        val generation = runGeneration
        Bukkit.getGlobalRegionScheduler().runDelayed(this, { if (running && generation == runGeneration && alive.isEmpty()) spawnWave(world) }, delaySeconds * 20)
    }
    private fun stop(sender: org.bukkit.command.CommandSender) {
        running = false
        runGeneration++
        alive.toList().forEach { id ->
            Bukkit.getEntity(id)?.let { entity -> entity.scheduler.run(this, { entity.remove() }, null) }
        }
        alive.clear()
        sender.sendMessage("Arena run stopped; spawned arena mobs removed.")
    }
    private fun broadcast(world: World, message: String) { Bukkit.getOnlinePlayers().forEach { player -> player.sendMessage(message) } }
}
