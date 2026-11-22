package dev.femmobs

import com.mojang.brigadier.Command
import io.papermc.paper.command.brigadier.Commands
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.command.CommandSender
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDeathEvent
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemStack
import org.bukkit.permissions.Permission
import org.bukkit.permissions.PermissionDefault
import org.bukkit.plugin.java.JavaPlugin
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class FemMobsPlugin : JavaPlugin(), Listener {
    private class WaveMenuHolder(val page: Int) : InventoryHolder {
        lateinit var menu: Inventory
        val slotToWave = mutableMapOf<Int, Int>()
        override fun getInventory(): Inventory = menu
    }

    private val alive = ConcurrentHashMap.newKeySet<UUID>()
    @Volatile private var runTag = "femmobs_run"
    @Volatile private var running = false
    @Volatile private var manualWave = false
    @Volatile private var wave = 0
    @Volatile private var pendingSpawns = AtomicInteger(0)
    @Volatile private var runGeneration = 0L
    private var waves: List<WaveSpec> = emptyList()
    private var worldName = "world"
    private var delaySeconds = 5L

    override fun onEnable() {
        instance = this
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
                    .then(Commands.literal("gui").executes { context -> runCommand(context.source.sender, "gui") })
                    .then(Commands.literal("help").executes { context -> runCommand(context.source.sender, "help") })
                    .executes { context -> runCommand(context.source.sender, "help") }
                    .build(),
            )
        }
    }

    private fun runCommand(sender: CommandSender, action: String): Int {
        if (!sender.hasPermission("femmobs.admin")) {
            sender.sendMessage("You lack permission. Required: femmobs.admin")
            return 0
        }
        when (action) {
            "start" -> start(sender)
            "stop" -> stop(sender)
            "status" -> sender.sendMessage(if (running) {
                "${if (manualWave) "Manual wave" else "Campaign wave ${wave.coerceAtLeast(1)}/${waves.size}"}: ${alive.size} mobs alive."
            } else "Arena waves are stopped.")
            "reload" -> {
                Bukkit.getGlobalRegionScheduler().execute(this) {
                    if (running) stopNow(sender)
                    reloadConfig()
                    loadSettings()
                    tell(sender, "FemMobs config reloaded. Loaded ${profilesCount()} mob profiles and ${waves.size} waves.")
                }
            }
            "gui" -> {
                if (sender !is Player) sender.sendMessage("Only a player can open the wave menu.")
                else openWaveMenu(sender, 0)
            }
            "help" -> sendHelp(sender)
        }
        return Command.SINGLE_SUCCESS
    }

    private fun profilesCount(): Int = config.getConfigurationSection("mobs")?.getKeys(false)?.size ?: 0

    private fun loadSettings() {
        worldName = config.getString("arena.world", "world")!!
        delaySeconds = config.getLong("arena.wave-delay-seconds", 5).coerceAtLeast(0)
        val mobSection = config.getConfigurationSection("mobs")
            ?: throw IllegalArgumentException("Missing 'mobs' section in config.yml")
        val profiles = mobSection.getKeys(false).associateWith { key ->
            val section = mobSection.getConfigurationSection(key)
                ?: throw IllegalArgumentException("mobs.$key must be a configuration section")
            loadMobProfile(key, section)
        }
        val waveSection = config.getConfigurationSection("arena.waves")
            ?: throw IllegalArgumentException("Missing 'arena.waves' section in config.yml")
        waves = waveSection.getKeys(false).map { waveKey ->
            val section = waveSection.getConfigurationSection(waveKey)
                ?: throw IllegalArgumentException("arena.waves.$waveKey must be a configuration section")
            val spawns = section.getMapList("spawns").mapIndexed { index, row ->
                val profileKey = row["profile"]?.toString()
                    ?: throw IllegalArgumentException("Missing profile at arena.waves.$waveKey.spawns[$index]")
                val profile = profiles[profileKey]
                    ?: throw IllegalArgumentException("Unknown profile '$profileKey' at arena.waves.$waveKey.spawns[$index]")
                SpawnSpec(profile, row.number("x", waveKey, index), row.number("y", waveKey, index), row.number("z", waveKey, index))
            }
            WaveSpec(section.getString("name", waveKey)!!, spawns)
        }
    }

    private fun Map<*, *>.number(key: String, waveKey: String, index: Int): Double =
        this[key]?.toString()?.toDoubleOrNull()
            ?: throw IllegalArgumentException("Invalid/missing coordinate '$key' at arena.waves.$waveKey.spawns[$index]")

    private fun start(sender: CommandSender) {
        Bukkit.getGlobalRegionScheduler().execute(this) {
            if (running) { tell(sender, "An arena run is already active."); return@execute }
            val world = Bukkit.getWorld(worldName)
            if (world == null) { tell(sender, "Arena world '$worldName' is not loaded."); return@execute }
            beginRun(manual = false)
            tell(sender, "Starting the ${waves.size}-wave arena campaign.")
            spawnNextCampaignWave(world)
        }
    }

    private fun spawnNextCampaignWave(world: World) {
        if (!running || manualWave) return
        if (wave >= waves.size) {
            running = false
            broadcast(world, "Arena campaign complete!")
            return
        }
        val current = wave++
        val selected = waves[current]
        spawnBatch(world, selected.spawns) {
            broadcast(world, "Wave ${current + 1}/${waves.size} (${selected.name}) spawned.")
            if (alive.isEmpty()) finishWave(world)
        }
    }

    private fun beginRun(manual: Boolean) {
        alive.clear()
        wave = 0
        running = true
        manualWave = manual
        runGeneration++
        runTag = "femmobs_${UUID.randomUUID().toString().replace("-", "")}"
    }

    private fun spawnBatch(world: World, spawns: List<SpawnSpec>, onSpawned: () -> Unit) {
        val generation = runGeneration
        val remaining = AtomicInteger(spawns.size)
        pendingSpawns = remaining
        if (spawns.isEmpty()) { onSpawned(); return }
        spawns.forEach { spawn ->
            val location = Location(world, spawn.x, spawn.y, spawn.z)
            Bukkit.getRegionScheduler().execute(this, location) {
                if (!running || generation != runGeneration) {
                    if (remaining.decrementAndGet() == 0 && generation == runGeneration && running) onSpawned()
                    return@execute
                }
                val entity = world.spawnEntity(location, spawn.profile.entityType) as LivingEntity
                spawn.profile.applyTo(entity)
                entity.addScoreboardTag(runTag)
                alive.add(entity.uniqueId)
                if (remaining.decrementAndGet() == 0) onSpawned()
            }
        }
    }

    private fun spawnSelectedWave(player: Player, waveIndex: Int) {
        player.closeInventory()
        Bukkit.getGlobalRegionScheduler().execute(this) {
            if (running) { tell(player, "Stop the current run before spawning a wave from the menu."); return@execute }
            val world = Bukkit.getWorld(worldName)
            if (world == null) { tell(player, "Arena world '$worldName' is not loaded."); return@execute }
            val selected = waves.getOrNull(waveIndex) ?: return@execute
            beginRun(manual = true)
            tell(player, "Spawning Wave ${waveIndex + 1}: ${selected.name} (${selected.spawns.size} mobs).")
            spawnBatch(world, selected.spawns) {
                if (alive.isEmpty()) running = false
            }
        }
    }

    @EventHandler
    fun onDeath(event: EntityDeathEvent) {
        val entity = event.entity
        if (!entity.scoreboardTags.contains(runTag)) return
        alive.remove(entity.uniqueId)
        if (alive.isNotEmpty() || !running || pendingSpawns.get() != 0) return
        if (manualWave) running = false
        else entity.scheduler.run(this, { finishWave(entity.world) }, null)
    }

    private fun finishWave(world: World) {
        if (!running || alive.isNotEmpty()) return
        if (manualWave) { running = false; return }
        val generation = runGeneration
        Bukkit.getGlobalRegionScheduler().runDelayed(this, {
            if (running && !manualWave && generation == runGeneration && alive.isEmpty()) spawnNextCampaignWave(world)
        }, delaySeconds * 20)
    }

    private fun stop(sender: CommandSender) {
        Bukkit.getGlobalRegionScheduler().execute(this) { stopNow(sender) }
    }

    private fun stopNow(sender: CommandSender) {
        running = false
        runGeneration++
        alive.toList().forEach { id ->
            Bukkit.getEntity(id)?.let { entity -> entity.scheduler.run(this, { entity.remove() }, null) }
        }
        alive.clear()
        tell(sender, "Arena run stopped; spawned arena mobs removed.")
    }

    private fun openWaveMenu(player: Player, page: Int) {
        if (waves.isEmpty()) { player.sendMessage("No waves are configured."); return }
        val pages = (waves.size + 44) / 45
        val safePage = page.coerceIn(0, pages - 1)
        val holder = WaveMenuHolder(safePage)
        val inventory = Bukkit.createInventory(holder, 54, Component.text("Arena Waves (${safePage + 1}/$pages)"))
        holder.menu = inventory
        for (slot in 0 until 54) inventory.setItem(slot, menuItem(Material.GRAY_STAINED_GLASS_PANE, " "))

        val firstWave = safePage * 45
        val lastWave = minOf(firstWave + 45, waves.size)
        for (waveIndex in firstWave until lastWave) {
            val slot = waveIndex - firstWave
            val selected = waves[waveIndex]
            val item = ItemStack(Material.NETHER_STAR)
            val meta = item.itemMeta
            meta.displayName(Component.text("Wave ${waveIndex + 1}: ${selected.name}", NamedTextColor.GOLD))
            val lore = mutableListOf(
                Component.text("${selected.spawns.size} mob(s)", NamedTextColor.YELLOW),
                Component.text("Click to spawn this wave", NamedTextColor.GREEN),
                Component.empty(),
            )
            selected.spawns.forEach { spawn ->
                lore += Component.text("${spawn.profile.name} (${spawn.profile.key})", NamedTextColor.WHITE)
                lore += Component.text("  ${spawn.x}, ${spawn.y}, ${spawn.z}", NamedTextColor.GRAY)
            }
            meta.lore(lore)
            item.itemMeta = meta
            inventory.setItem(slot, item)
            holder.slotToWave[slot] = waveIndex
        }
        if (safePage > 0) inventory.setItem(45, menuItem(Material.ARROW, "Previous page"))
        inventory.setItem(49, menuItem(Material.BOOK, "Click a wave to spawn its listed mobs"))
        if (safePage < pages - 1) inventory.setItem(53, menuItem(Material.ARROW, "Next page"))
        player.openInventory(inventory)
    }

    @EventHandler
    fun onWaveMenuClick(event: InventoryClickEvent) {
        val holder = event.view.topInventory.holder as? WaveMenuHolder ?: return
        event.isCancelled = true
        if (event.clickedInventory != event.view.topInventory || event.rawSlot !in 0 until event.view.topInventory.size) return
        val player = event.whoClicked as? Player ?: return
        val waveIndex = holder.slotToWave[event.rawSlot]
        if (waveIndex != null) {
            spawnSelectedWave(player, waveIndex)
        } else if (event.rawSlot == 45 && holder.page > 0) {
            openWaveMenu(player, holder.page - 1)
        } else if (event.rawSlot == 53) {
            openWaveMenu(player, holder.page + 1)
        }
    }

    private fun menuItem(material: Material, title: String): ItemStack = ItemStack(material).also { item ->
        item.itemMeta = item.itemMeta.also { it.displayName(Component.text(title, NamedTextColor.YELLOW)) }
    }

    private fun sendHelp(sender: CommandSender) {
        sender.sendMessage("FemMobs commands:")
        sender.sendMessage("/arenamobs gui — open the wave list; click a wave to spawn it once.")
        sender.sendMessage("/arenamobs start — start the automatic campaign, advancing after each wave is cleared.")
        sender.sendMessage("/arenamobs stop — stop and remove mobs spawned by the current run.")
        sender.sendMessage("/arenamobs status — show the current run and remaining mob count.")
        sender.sendMessage("/arenamobs reload — reload config.yml (stops the current run first).")
        sender.sendMessage("Edit mob profiles and wave rosters in plugins/FemMobs/config.yml; then run /arenamobs reload.")
        sender.sendMessage("Mob equipment drop chances are in each equipment slot; loot is handled by your other plugin.")
    }

    private fun broadcast(world: World, message: String) {
        Bukkit.getGlobalRegionScheduler().execute(this) {
            Bukkit.getOnlinePlayers().filter { it.world.uid == world.uid }.forEach { player ->
                player.scheduler.run(this, { player.sendMessage(message) }, null)
            }
        }
    }

    private fun tell(sender: CommandSender, message: String) {
        if (sender is Player) sender.scheduler.run(this, { sender.sendMessage(message) }, null)
        else sender.sendMessage(message)
    }

    companion object {
        lateinit var instance: FemMobsPlugin
            private set
    }
}
