package dev.femmobs

import io.papermc.paper.threadedregions.scheduler.ScheduledTask
import net.kyori.adventure.bossbar.BossBar
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver
import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.Particle
import org.bukkit.Registry
import org.bukkit.Sound
import org.bukkit.attribute.Attribute
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.entity.Entity
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Mob
import org.bukkit.entity.Player
import org.bukkit.entity.SmallFireball
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import org.bukkit.util.Vector
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ThreadLocalRandom
import java.util.logging.Level
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

enum class AbilityType { SLAM, FIREBALL, AURA, SUMMON, TELEPORT, LIGHTNING }

/** One boss ability. Unused options are ignored by the types that don't need them. */
class AbilitySpec(
    val type: AbilityType,
    val cooldownTicks: Int,
    /** Only used while the boss's health is at or below this fraction (1.0 = always). */
    val belowHealth: Double,
    /** How far away a target player may be. */
    val range: Double,
    /** Area size for slam / aura, spread for summon. */
    val radius: Double,
    val damage: Double,
    val power: Double,
    val fireSeconds: Int,
    val effect: PotionEffectType?,
    val level: Int,
    val durationTicks: Int,
    val summon: String?,
    val count: Int,
    val maxSummons: Int,
    val minRange: Double,
    /** Optional action-bar text shown to nearby players when the ability fires. */
    val message: String?,
)

class BossSpec(
    val healthBarColor: BossBar.Color?,
    val healthBarRange: Double,
    val deathMessage: String?,
    val particle: Particle?,
    val particleCount: Int,
    val abilities: List<AbilitySpec>,
)

fun loadBossSpec(path: String, section: ConfigurationSection): BossSpec {
    val particle = section.getString("particle")?.let { name ->
        runCatching { Particle.valueOf(name.uppercase()) }.getOrElse { throw IllegalArgumentException("Unknown particle '$name' in $path.particle") }
    }
    val barColor = section.getString("health-bar")?.takeUnless { it.equals("none", true) }?.let { name ->
        runCatching { BossBar.Color.valueOf(name.uppercase()) }.getOrElse { throw IllegalArgumentException("Unknown health-bar color '$name' in $path") }
    }
    val abilities = section.getMapList("abilities").mapIndexed { index, row ->
        val at = "$path.abilities[$index]"
        fun num(key: String, default: Double) = row[key]?.toString()?.toDoubleOrNull() ?: default
        fun int(key: String, default: Int) = row[key]?.toString()?.toIntOrNull() ?: default
        val type = row["type"]?.toString()?.let { name ->
            runCatching { AbilityType.valueOf(name.uppercase()) }.getOrElse {
                throw IllegalArgumentException("Unknown ability type '$name' at $at (slam, fireball, aura, summon, teleport, lightning)")
            }
        } ?: throw IllegalArgumentException("Missing ability type at $at")
        val effect = row["effect"]?.toString()?.let { name ->
            Registry.POTION_EFFECT_TYPE.get(namespaced(if (':' in name) name else "minecraft:$name"))
                ?: throw IllegalArgumentException("Unknown effect '$name' at $at")
        }
        if (type == AbilityType.SUMMON) require(row["summon"] != null) { "summon ability needs a 'summon' mob profile at $at" }
        if (type == AbilityType.AURA) require(effect != null || int("fire-seconds", 0) > 0) { "aura ability needs an effect or fire-seconds at $at" }
        AbilitySpec(
            type = type,
            cooldownTicks = (num("cooldown", 10.0) * 20).roundToInt().coerceAtLeast(10),
            belowHealth = (num("below-health", 100.0) / 100.0).coerceIn(0.0, 1.0),
            range = num("range", 16.0).coerceIn(1.0, 48.0),
            radius = num("radius", 5.0).coerceIn(1.0, 24.0),
            damage = num("damage", 0.0).coerceAtLeast(0.0),
            power = num("power", 1.0).coerceIn(0.0, 4.0),
            fireSeconds = int("fire-seconds", 0).coerceAtLeast(0),
            effect = effect,
            level = int("level", 1).coerceIn(1, 10),
            durationTicks = (num("duration", 3.0) * 20).roundToInt().coerceAtLeast(1),
            summon = row["summon"]?.toString(),
            count = int("count", 1).coerceIn(1, 10),
            maxSummons = int("max-alive", 4).coerceIn(1, 30),
            minRange = num("min-range", 0.0).coerceAtLeast(0.0),
            message = row["message"]?.toString(),
        )
    }
    return BossSpec(
        healthBarColor = barColor,
        healthBarRange = section.getDouble("health-bar-range", 40.0).coerceIn(8.0, 128.0),
        deathMessage = section.getString("death-message"),
        particle = particle,
        particleCount = section.getInt("particle-count", 4).coerceIn(0, 40),
        abilities = abilities,
    )
}

/**
 * Runs boss behavior. Each boss gets a task on its own entity scheduler (so it follows the boss across
 * Folia regions) that shows particles, updates its health bar, and uses its abilities on nearby players.
 */
class BossFeature(private val plugin: FemMobsPlugin) {
    private inner class BossState(val entity: LivingEntity, val profile: MobProfile, val spec: BossSpec) {
        val cooldowns = IntArray(spec.abilities.size) { index -> spec.abilities[index].cooldownTicks / 2 }
        val summons: MutableSet<UUID> = ConcurrentHashMap.newKeySet()
        val viewers: MutableSet<UUID> = ConcurrentHashMap.newKeySet()
        val bar: BossBar? = spec.healthBarColor?.let { BossBar.bossBar(Component.text(profile.name), 1f, it, BossBar.Overlay.NOTCHED_10) }
        @Volatile var task: ScheduledTask? = null
    }

    private val bosses = ConcurrentHashMap<UUID, BossState>()
    private val mini = MiniMessage.miniMessage()

    /** Applies the colored glow and starts boss behavior for a freshly spawned or reloaded profile mob. */
    fun attach(entity: LivingEntity, profile: MobProfile) {
        profile.glowColor?.let { color -> glowTeam(entity, color) }
        val spec = profile.boss ?: return
        if (bosses.containsKey(entity.uniqueId)) return
        val state = BossState(entity, profile, spec)
        bosses[entity.uniqueId] = state
        state.task = entity.scheduler.runAtFixedRate(plugin, { tick(state) }, { cleanup(state, removeSummons = false) }, 1L, TICK_INTERVAL)
    }

    fun isBoss(entity: Entity) = bosses.containsKey(entity.uniqueId)

    /** The boss died: announce it, remove its summons, hide its bar. */
    fun onDeath(entity: LivingEntity, killer: Player?) {
        val state = bosses[entity.uniqueId] ?: return
        state.spec.deathMessage?.let { message ->
            Bukkit.getServer().sendMessage(mini.deserialize(message, TagResolver.resolver(
                Placeholder.unparsed("mob", state.profile.name),
                Placeholder.unparsed("killer", killer?.name ?: "an unknown hero"),
            )))
        }
        cleanup(state, removeSummons = true)
    }

    /** Removed for another reason (despawn, plugin stop, /kill): no announcement, summons go too. */
    fun onGone(entity: Entity) {
        bosses[entity.uniqueId]?.let { cleanup(it, removeSummons = true) }
    }

    fun shutdown() = bosses.values.toList().forEach { cleanup(it, removeSummons = false) }

    private fun cleanup(state: BossState, removeSummons: Boolean) {
        if (bosses.remove(state.entity.uniqueId, state).not()) return
        state.task?.cancel()
        state.bar?.let { bar -> state.viewers.forEach { id -> Bukkit.getPlayer(id)?.hideBossBar(bar) } }
        state.viewers.clear()
        if (removeSummons) state.summons.forEach { id ->
            Bukkit.getEntity(id)?.let { summon -> summon.scheduler.run(plugin, { summon.remove() }, null) }
        }
    }

    // ---------------------------------------------------------------- ticking

    private fun tick(state: BossState) {
        val boss = state.entity
        if (!boss.isValid || boss.isDead) return
        val max = boss.getAttribute(Attribute.MAX_HEALTH)?.value ?: 20.0
        val fraction = (boss.health / max).coerceIn(0.0, 1.0)
        state.spec.particle?.let { particle ->
            if (state.spec.particleCount > 0) {
                boss.world.spawnParticle(particle, boss.location.add(0.0, boss.height / 2, 0.0), state.spec.particleCount,
                    boss.width / 2, boss.height / 3, boss.width / 2, 0.01)
            }
        }
        state.bar?.let { bar -> updateBar(state, bar, fraction, max) }
        state.summons.removeIf { id -> Bukkit.getEntity(id)?.isValid != true }

        // Count down every cooldown, then use at most one ready ability this tick.
        for (i in state.cooldowns.indices) if (state.cooldowns[i] > 0) state.cooldowns[i] -= TICK_INTERVAL.toInt()
        for ((i, ability) in state.spec.abilities.withIndex()) {
            if (state.cooldowns[i] > 0 || fraction > ability.belowHealth) continue
            val target = target(boss, ability.range) ?: continue
            val used = runCatching { use(state, ability, target) }.getOrElse { error ->
                plugin.logger.log(Level.WARNING, "Boss ability ${ability.type} of '${state.profile.key}' failed", error)
                true
            }
            if (used) {
                state.cooldowns[i] = ability.cooldownTicks
                ability.message?.let { text -> nearbyPlayers(boss, 24.0).forEach { it.sendActionBar(mini.deserialize(text, Placeholder.unparsed("mob", state.profile.name))) } }
                break
            }
        }
    }

    private fun updateBar(state: BossState, bar: BossBar, fraction: Double, max: Double) {
        bar.name(Component.text(state.profile.name, state.profile.nameColor)
            .append(Component.text("  ❤ ${state.entity.health.roundToInt()}/${max.roundToInt()}", NamedTextColor.RED)))
        bar.progress(fraction.toFloat())
        val near = nearbyPlayers(state.entity, state.spec.healthBarRange, includeSpectators = true).map { it.uniqueId }.toSet()
        near.forEach { id -> if (state.viewers.add(id)) Bukkit.getPlayer(id)?.showBossBar(bar) }
        state.viewers.filter { it !in near }.forEach { id ->
            state.viewers.remove(id)
            Bukkit.getPlayer(id)?.hideBossBar(bar)
        }
    }

    // ---------------------------------------------------------------- abilities

    private fun use(state: BossState, ability: AbilitySpec, target: Player): Boolean {
        val boss = state.entity
        val world = boss.world
        return when (ability.type) {
            AbilityType.SLAM -> {
                val hit = nearbyPlayers(boss, ability.radius)
                if (hit.isEmpty()) return false
                world.spawnParticle(Particle.EXPLOSION, boss.location, 3, 1.0, 0.2, 1.0, 0.0)
                world.spawnParticle(Particle.LAVA, boss.location, 20, ability.radius / 2, 0.2, ability.radius / 2, 0.0)
                world.playSound(boss.location, Sound.ENTITY_GENERIC_EXPLODE, 1f, 0.7f)
                hit.forEach { player ->
                    if (ability.damage > 0) player.damage(ability.damage, boss)
                    val away = player.location.toVector().subtract(boss.location.toVector()).setY(0)
                    val push = (if (away.lengthSquared() < 0.01) Vector(0.0, 0.0, 0.0) else away.normalize().multiply(ability.power * 0.8))
                    player.velocity = push.setY(0.35 + ability.power * 0.35)
                    if (ability.fireSeconds > 0) player.fireTicks = maxOf(player.fireTicks, ability.fireSeconds * 20)
                }
                true
            }
            AbilityType.FIREBALL -> {
                if (boss.location.distance(target.location) < ability.minRange) return false
                val shots = ability.count.coerceIn(1, 5)
                repeat(shots) { shot ->
                    val aim = target.eyeLocation.toVector().subtract(boss.eyeLocation.toVector()).normalize()
                    if (shots > 1) aim.rotateAroundY(Math.toRadians((shot - (shots - 1) / 2.0) * 8))
                    boss.launchProjectile(SmallFireball::class.java, aim.multiply(1.2))
                }
                world.playSound(boss.location, Sound.ENTITY_BLAZE_SHOOT, 1f, 0.8f)
                true
            }
            AbilityType.AURA -> {
                val hit = nearbyPlayers(boss, ability.radius)
                if (hit.isEmpty()) return false
                world.spawnParticle(ability.effect?.let { Particle.WITCH } ?: Particle.FLAME, boss.location.add(0.0, 1.0, 0.0), 40, ability.radius / 2, 0.6, ability.radius / 2, 0.02)
                world.playSound(boss.location, Sound.ENTITY_EVOKER_CAST_SPELL, 1f, 0.6f)
                hit.forEach { player ->
                    ability.effect?.let { player.addPotionEffect(PotionEffect(it, ability.durationTicks, ability.level - 1)) }
                    if (ability.fireSeconds > 0) player.fireTicks = maxOf(player.fireTicks, ability.fireSeconds * 20)
                    if (ability.damage > 0) player.damage(ability.damage, boss)
                }
                true
            }
            AbilityType.SUMMON -> {
                val profile = plugin.profileFor(ability.summon!!) ?: return false
                if (state.summons.size >= ability.maxSummons) return false
                val toSpawn = minOf(ability.count, ability.maxSummons - state.summons.size)
                world.playSound(boss.location, Sound.ENTITY_EVOKER_PREPARE_SUMMON, 1f, 0.8f)
                repeat(toSpawn) {
                    val spot = summonSpot(boss.location, ability.radius) ?: return@repeat
                    world.spawnParticle(Particle.LARGE_SMOKE, spot, 15, 0.3, 0.5, 0.3, 0.01)
                    val minion = plugin.spawnProfile(profile, spot) { it.addScoreboardTag(MINION_TAG) }
                    state.summons.add(minion.uniqueId)
                    (minion as? Mob)?.target = target
                    plugin.adoptMinion(boss, minion)
                }
                true
            }
            AbilityType.TELEPORT -> {
                val behind = target.location.clone().subtract(target.location.direction.setY(0).normalize().multiply(2.0))
                if (!Bukkit.isOwnedByCurrentRegion(behind) || !standable(behind)) return false
                behind.yaw = target.location.yaw
                world.spawnParticle(Particle.PORTAL, boss.location.add(0.0, 1.0, 0.0), 40, 0.4, 0.8, 0.4, 0.3)
                world.playSound(boss.location, Sound.ENTITY_ENDERMAN_TELEPORT, 1f, 0.7f)
                boss.teleportAsync(behind).thenAccept {
                    boss.scheduler.run(plugin, {
                        boss.world.spawnParticle(Particle.REVERSE_PORTAL, boss.location.add(0.0, 1.0, 0.0), 40, 0.4, 0.8, 0.4, 0.05)
                        (boss as? Mob)?.target = target
                    }, null)
                }
                true
            }
            AbilityType.LIGHTNING -> {
                world.strikeLightningEffect(target.location)
                if (ability.damage > 0) target.damage(ability.damage, boss)
                if (ability.fireSeconds > 0) target.fireTicks = maxOf(target.fireTicks, ability.fireSeconds * 20)
                true
            }
        }
    }

    /** The boss's current player target if it's in range, otherwise the nearest fightable player. */
    private fun target(boss: LivingEntity, range: Double): Player? {
        val current = (boss as? Mob)?.target as? Player
        if (current != null && fightable(current) && current.world == boss.world && current.location.distanceSquared(boss.location) <= range * range) return current
        return nearbyPlayers(boss, range).minByOrNull { it.location.distanceSquared(boss.location) }
    }

    private fun nearbyPlayers(boss: LivingEntity, radius: Double, includeSpectators: Boolean = false): List<Player> =
        boss.location.getNearbyPlayers(radius).filter { includeSpectators || fightable(it) }

    private fun fightable(player: Player) = !player.isDead && (player.gameMode == GameMode.SURVIVAL || player.gameMode == GameMode.ADVENTURE)

    private fun summonSpot(center: Location, radius: Double): Location? {
        val random = ThreadLocalRandom.current()
        repeat(8) {
            val angle = random.nextDouble(0.0, Math.PI * 2)
            val distance = random.nextDouble(1.5, radius.coerceAtLeast(2.0))
            val spot = center.clone().add(cos(angle) * distance, 0.0, sin(angle) * distance)
            for (dy in listOf(0, 1, -1, 2)) {
                val candidate = spot.clone().add(0.0, dy.toDouble(), 0.0)
                if (Bukkit.isOwnedByCurrentRegion(candidate) && standable(candidate)) return candidate
            }
        }
        return null
    }

    private fun standable(feet: Location): Boolean {
        val below = feet.clone().add(0.0, -1.0, 0.0).block
        return below.type.isSolid && feet.block.isPassable && !feet.block.isLiquid && feet.clone().add(0.0, 1.0, 0.0).block.isPassable
    }

    // ---------------------------------------------------------------- glow colors

    /** Colored glow needs a scoreboard team. Folia has no scoreboard API, so there it stays white. */
    private fun glowTeam(entity: LivingEntity, color: NamedTextColor) {
        runCatching {
            val board = Bukkit.getScoreboardManager().mainScoreboard
            val name = "femmobs_glow_$color".take(64)
            val team = board.getTeam(name) ?: board.registerNewTeam(name).also { it.color(color) }
            team.addEntity(entity)
        }.onFailure { plugin.logger.fine("Colored glow unavailable: ${it.message}") }
    }

    /** Removes a dead or removed mob from its glow team so the scoreboard doesn't fill up with old entries. */
    fun releaseGlow(entity: Entity) {
        if (entity.scoreboardTags.none { it.startsWith(PROFILE_TAG_PREFIX) }) return
        runCatching { Bukkit.getScoreboardManager().mainScoreboard.getEntityTeam(entity)?.takeIf { it.name.startsWith("femmobs_glow_") }?.removeEntity(entity) }
    }

    companion object {
        const val TICK_INTERVAL = 10L
        const val MINION_TAG = "femmobs.minion"
        /** Every profile mob carries this plus its profile key, so bosses can be restored after a chunk reload. */
        const val PROFILE_TAG_PREFIX = "femmobs.profile."
    }
}
