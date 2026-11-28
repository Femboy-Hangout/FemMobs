package dev.femmobs

import io.papermc.paper.registry.RegistryAccess
import io.papermc.paper.registry.RegistryKey
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextColor
import org.bukkit.Color
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Registry
import org.bukkit.attribute.Attribute
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.enchantments.Enchantment
import org.bukkit.entity.Ageable
import org.bukkit.entity.EntityType
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Mob
import org.bukkit.entity.Slime
import org.bukkit.inventory.meta.ArmorMeta
import org.bukkit.inventory.meta.LeatherArmorMeta
import org.bukkit.inventory.meta.trim.ArmorTrim
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import java.util.logging.Logger

data class EquipmentSpec(
    val slot: EquipmentSlot,
    val material: Material,
    val amount: Int,
    val enchantments: Map<Enchantment, Int>,
    val dropChance: Float,
    /** Armor trim (both set or both null), e.g. material gold + pattern rib. */
    val trim: ArmorTrim? = null,
    /** Dye color for leather armor. */
    val color: Color? = null,
)

data class EffectSpec(
    val type: PotionEffectType,
    val amplifier: Int,
    val durationSeconds: Int,
    val ambient: Boolean,
    val particles: Boolean,
    val icon: Boolean,
)

data class MobProfile(
    val key: String,
    val entityType: EntityType,
    val name: String,
    val icon: Material,
    val nameColor: TextColor,
    val nameVisible: Boolean,
    val persistent: Boolean,
    val invulnerable: Boolean,
    val glowing: Boolean,
    val silent: Boolean,
    val hasAi: Boolean,
    val canPickupItems: Boolean,
    val baby: Boolean,
    val fireTicks: Int,
    val tags: List<String>,
    val health: Double?,
    val attributes: Map<Attribute, Double>,
    val equipment: List<EquipmentSpec>,
    val effects: List<EffectSpec>,
    /** Slime / magma cube size (1 = small). */
    val slimeSize: Int? = null,
    /** Colored glowing outline (needs `glowing: true`); white when null. */
    val glowColor: NamedTextColor? = null,
    /** Boss behavior: abilities, particles, health bar, death message. */
    val boss: BossSpec? = null,
) {
    fun applyTo(entity: LivingEntity, logger: Logger) {
        entity.customName(Component.text(name, nameColor))
        entity.isCustomNameVisible = nameVisible
        entity.isPersistent = persistent
        // isPersistent only controls saving on chunk unload; this stops natural despawning.
        // Mob.setPersistenceRequired is newer (Paper 26.3 build 258+) and missing on older servers.
        entity.removeWhenFarAway = !persistent
        entity.isInvulnerable = invulnerable
        entity.isGlowing = glowing
        entity.isSilent = silent
        entity.fireTicks = fireTicks.coerceAtLeast(0)
        (entity as? Mob)?.let {
            it.setAI(hasAi)
            it.setCanPickupItems(canPickupItems)
        }
        (entity as? Ageable)?.let { if (baby) it.setBaby() else it.setAdult() }
        slimeSize?.let { size -> (entity as? Slime)?.size = size }
        tags.forEach(entity::addScoreboardTag)

        attributes.forEach { (attribute, value) ->
            val instance = entity.getAttribute(attribute)
            if (instance == null) {
                logger.warning("Mob '$key' has no ${attribute.key} attribute on ${entity.type}.")
            } else {
                instance.baseValue = value
            }
        }
        entity.health = (health ?: entity.getAttribute(Attribute.MAX_HEALTH)?.value ?: 20.0)
            .coerceIn(0.0, entity.getAttribute(Attribute.MAX_HEALTH)?.value ?: 20.0)

        equipment.forEach { itemSpec ->
            val item = ItemStack(itemSpec.material, itemSpec.amount)
            itemSpec.enchantments.forEach { (enchantment, level) -> item.addUnsafeEnchantment(enchantment, level) }
            if (itemSpec.trim != null || itemSpec.color != null) {
                item.editMeta { meta ->
                    if (itemSpec.trim != null) (meta as? ArmorMeta)?.trim = itemSpec.trim
                    if (itemSpec.color != null) (meta as? LeatherArmorMeta)?.setColor(itemSpec.color)
                }
            }
            entity.equipment?.setItem(itemSpec.slot, item, true)
            entity.equipment?.setDropChance(itemSpec.slot, itemSpec.dropChance)
        }

        effects.forEach { effect ->
            val durationTicks = if (effect.durationSeconds < 0) PotionEffect.INFINITE_DURATION else effect.durationSeconds * 20
            entity.addPotionEffect(PotionEffect(effect.type, durationTicks, effect.amplifier, effect.ambient, effect.particles, effect.icon))
        }
    }
}

data class SpawnSpec(
    val profile: MobProfile,
    val x: Double,
    val y: Double,
    val z: Double,
    val amount: Int,
    val yaw: Float,
    val pitch: Float,
    val spread: Double,
)
data class WaveSpec(
    val name: String,
    val description: String,
    val delayAfterSeconds: Long?,
    val spawns: List<SpawnSpec>,
)

data class ArenaSpec(
    val key: String,
    val name: String,
    val description: String,
    val icon: Material,
    val worldName: String,
    val waveDelaySeconds: Long,
    val waves: List<WaveSpec>,
)

fun loadMobProfile(key: String, section: ConfigurationSection): MobProfile {
    val entityType = enumValue<EntityType>(section.getString("entity-type"), "mobs.$key.entity-type")
    require(entityType.isSpawnable && entityType.entityClass?.let { LivingEntity::class.java.isAssignableFrom(it) } == true) {
        "mobs.$key.entity-type '$entityType' must be a spawnable living entity"
    }
    val attrs = linkedMapOf<Attribute, Double>()
    section.getConfigurationSection("attributes")?.getKeys(false)?.forEach { attributeName ->
        val normalized = attributeName.replace('-', '_').uppercase()
        val attribute = Registry.ATTRIBUTE.get(NamespacedKey.minecraft(normalized.lowercase()))
            ?: throw IllegalArgumentException("Unknown attribute '$attributeName' in mobs.$key.attributes")
        attrs[attribute] = section.getDouble("attributes.$attributeName")
    }

    val equipmentSlots = linkedMapOf(
        "main-hand" to EquipmentSlot.HAND,
        "off-hand" to EquipmentSlot.OFF_HAND,
        "helmet" to EquipmentSlot.HEAD,
        "chestplate" to EquipmentSlot.CHEST,
        "leggings" to EquipmentSlot.LEGS,
        "boots" to EquipmentSlot.FEET,
    )
    val equipment = equipmentSlots.mapNotNull { (slotName, slot) ->
        val path = "equipment.$slotName"
        val materialName = section.getString("$path.material")?.uppercase() ?: return@mapNotNull null
        val material = runCatching { Material.valueOf(materialName) }.getOrElse {
            throw IllegalArgumentException("Unknown material '$materialName' in mobs.$key.$path", it)
        }
        val enchants = linkedMapOf<Enchantment, Int>()
        section.getConfigurationSection("$path.enchantments")?.getKeys(false)?.forEach { enchantName ->
            val enchantmentKey = NamespacedKey.minecraft(enchantName.lowercase())
            val enchantment = RegistryAccess.registryAccess().getRegistry(RegistryKey.ENCHANTMENT).get(enchantmentKey)
                ?: throw IllegalArgumentException("Unknown enchantment '$enchantName' in mobs.$key.$path")
            enchants[enchantment] = section.getInt("$path.enchantments.$enchantName")
        }
        val trim = section.getConfigurationSection("$path.trim")?.let { trimSection ->
            val materialKey = trimSection.getString("material") ?: throw IllegalArgumentException("Missing trim material in mobs.$key.$path.trim")
            val patternKey = trimSection.getString("pattern") ?: throw IllegalArgumentException("Missing trim pattern in mobs.$key.$path.trim")
            val trimMaterial = RegistryAccess.registryAccess().getRegistry(RegistryKey.TRIM_MATERIAL).get(namespaced(materialKey))
                ?: throw IllegalArgumentException("Unknown trim material '$materialKey' in mobs.$key.$path.trim")
            val trimPattern = RegistryAccess.registryAccess().getRegistry(RegistryKey.TRIM_PATTERN).get(namespaced(patternKey))
                ?: throw IllegalArgumentException("Unknown trim pattern '$patternKey' in mobs.$key.$path.trim")
            ArmorTrim(trimMaterial, trimPattern)
        }
        val dye = section.getString("$path.color")?.let { hex ->
            val rgb = hex.removePrefix("#").toIntOrNull(16)?.takeIf { hex.removePrefix("#").length == 6 }
                ?: throw IllegalArgumentException("Invalid color '$hex' in mobs.$key.$path.color (use #RRGGBB)")
            Color.fromRGB(rgb)
        }
        EquipmentSpec(slot, material, section.getInt("$path.amount", 1).coerceAtLeast(1), enchants,
            section.getDouble("$path.drop-chance", 0.0).toFloat().coerceIn(0f, 1f), trim, dye)
    }

    val effects = section.getMapList("effects").mapIndexed { index, effectRow ->
        val typeName = effectRow["type"]?.toString() ?: throw IllegalArgumentException("Missing type in mobs.$key.effects[$index]")
        val effectKey = NamespacedKey.fromString(typeName.lowercase()) ?: NamespacedKey.minecraft(typeName.lowercase())
        val type = Registry.POTION_EFFECT_TYPE.get(effectKey)
            ?: throw IllegalArgumentException("Unknown potion effect '$typeName' in mobs.$key.effects[$index]")
        EffectSpec(
            type = type,
            amplifier = effectRow["amplifier"]?.toString()?.toIntOrNull() ?: 0,
            durationSeconds = effectRow["duration-seconds"]?.toString()?.toIntOrNull() ?: -1,
            ambient = effectRow["ambient"]?.toString()?.toBooleanStrictOrNull() ?: false,
            particles = effectRow["particles"]?.toString()?.toBooleanStrictOrNull() ?: false,
            icon = effectRow["icon"]?.toString()?.toBooleanStrictOrNull() ?: false,
        )
    }

    val colorValue = section.getString("name-color", "white")!!
    val color: TextColor = if (colorValue.startsWith('#')) {
        TextColor.fromHexString(colorValue)
            ?: throw IllegalArgumentException("Invalid hex name color '$colorValue' in mobs.$key.name-color")
    } else {
        NamedTextColor.NAMES.value(colorValue.lowercase())
            ?: throw IllegalArgumentException("Unknown name color '$colorValue' in mobs.$key.name-color")
    }

    val icon = section.getString("icon")?.let { iconName ->
        Material.matchMaterial(iconName)?.takeIf { it.isItem && !it.isAir }
            ?: throw IllegalArgumentException("Unknown or non-item icon '$iconName' in mobs.$key.icon")
    } ?: Material.matchMaterial("${entityType.name}_SPAWN_EGG") ?: Material.SPAWNER

    return MobProfile(
        key = key,
        entityType = entityType,
        name = section.getString("name", key)!!,
        icon = icon,
        nameColor = color,
        nameVisible = section.getBoolean("name-visible", true),
        persistent = section.getBoolean("persistent", true),
        invulnerable = section.getBoolean("invulnerable", false),
        glowing = section.getBoolean("glowing", false),
        silent = section.getBoolean("silent", false),
        hasAi = section.getBoolean("ai", true),
        canPickupItems = section.getBoolean("can-pickup-items", false),
        baby = section.getBoolean("baby", false),
        fireTicks = section.getInt("fire-ticks", 0),
        tags = section.getStringList("scoreboard-tags"),
        health = if (section.contains("health")) section.getDouble("health") else null,
        attributes = attrs,
        equipment = equipment,
        effects = effects,
        slimeSize = if (section.contains("slime-size")) section.getInt("slime-size").coerceIn(1, 16) else null,
        glowColor = section.getString("glow-color")?.let { name ->
            NamedTextColor.NAMES.value(name.lowercase())
                ?: throw IllegalArgumentException("Unknown glow-color '$name' in mobs.$key (use a named color like gold or dark_purple)")
        },
        boss = section.getConfigurationSection("boss")?.let { loadBossSpec("mobs.$key.boss", it) },
    )
}

internal fun namespaced(value: String): NamespacedKey =
    NamespacedKey.fromString(value.lowercase()) ?: throw IllegalArgumentException("Invalid key '$value'")

private inline fun <reified T : Enum<T>> enumValue(value: String?, path: String): T {
    val enumName = value ?: throw IllegalArgumentException("Missing required setting '$path'")
    return runCatching { enumValueOf<T>(enumName.uppercase()) }.getOrElse {
        throw IllegalArgumentException("Unknown value '$enumName' for '$path'", it)
    }
}
