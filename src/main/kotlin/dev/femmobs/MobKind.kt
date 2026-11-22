package dev.femmobs

import org.bukkit.Material
import org.bukkit.attribute.Attribute
import org.bukkit.enchantments.Enchantment
import org.bukkit.entity.EntityType
import org.bukkit.entity.LivingEntity
import org.bukkit.inventory.ItemStack
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor

enum class MobKind(
    val entityType: EntityType,
    val tag: String,
    val displayName: String,
    val color: String,
    val maxHealth: Double,
    val damage: Double?,
    val speed: Double,
    val weapon: Material?,
    val weaponEnchant: Enchantment?,
    val weaponLevel: Int,
    val armor: Material?,
    val champion: Boolean = false,
    val strength: Boolean = false,
) {
    GRUNT(EntityType.ZOMBIE, "forsaken.grunt", "Forsaken Grunt", "dark_gray", 40.0, 5.5, .25, Material.IRON_SWORD, Enchantment.SHARPNESS, 2, Material.IRON_HELMET),
    ARCHER(EntityType.SKELETON, "forsaken.archer", "Forsaken Archer", "green", 30.0, null, .24, Material.BOW, Enchantment.POWER, 2, Material.CHAINMAIL_HELMET),
    RAVAGER(EntityType.VINDICATOR, "forsaken.ravager", "Forsaken Ravager", "dark_red", 65.0, 10.0, .26, Material.DIAMOND_AXE, Enchantment.SHARPNESS, 2, Material.IRON_HELMET),
    DREADBOUND(EntityType.HUSK, "forsaken.dreadbound", "The Dreadbound", "dark_purple", 110.0, 8.0, .27, Material.DIAMOND_SWORD, Enchantment.SHARPNESS, 3, Material.DIAMOND_HELMET, strength = true),
    CHAMPION(EntityType.WITHER_SKELETON, "forsaken.champion", "The Forsaken Champion", "gold", 250.0, 12.0, .28, Material.DIAMOND_SWORD, null, 0, Material.DIAMOND_HELMET, champion = true)
}

fun MobKind.applyTo(entity: LivingEntity) {
    val nameColor = when (color) {
        "dark_gray" -> NamedTextColor.DARK_GRAY
        "green" -> NamedTextColor.GREEN
        "dark_red" -> NamedTextColor.DARK_RED
        "dark_purple" -> NamedTextColor.DARK_PURPLE
        else -> NamedTextColor.GOLD
    }
    entity.customName(Component.text(displayName, nameColor))
    entity.isCustomNameVisible = true
    entity.isPersistent = true
    entity.addScoreboardTag(tag)
    entity.getAttribute(Attribute.MAX_HEALTH)?.baseValue = maxHealth
    entity.health = maxHealth
    damage?.let { entity.getAttribute(Attribute.ATTACK_DAMAGE)?.baseValue = it }
    entity.getAttribute(Attribute.MOVEMENT_SPEED)?.baseValue = speed
    if (champion) entity.getAttribute(Attribute.KNOCKBACK_RESISTANCE)?.baseValue = .5
    if (weapon != null) {
        val item = ItemStack(weapon)
        weaponEnchant?.let { item.addUnsafeEnchantment(it, weaponLevel) }
        if (weapon == Material.BOW) item.addUnsafeEnchantment(Enchantment.PUNCH, 1)
        entity.equipment?.setItemInMainHand(item, true)
    }
    armor?.let { helmet ->
        val material = helmet.name.removeSuffix("_HELMET")
        val pieces = listOf("HELMET", "CHESTPLATE", "LEGGINGS", "BOOTS")
        val ench = Enchantment.PROTECTION
        pieces.forEach { part ->
            val piece = ItemStack(Material.valueOf("${material}_${part}"))
            if (!champion) piece.addUnsafeEnchantment(ench, 2)
            when (part) {
                "HELMET" -> entity.equipment?.setHelmet(piece)
                "CHESTPLATE" -> entity.equipment?.setChestplate(piece)
                "LEGGINGS" -> entity.equipment?.setLeggings(piece)
                "BOOTS" -> entity.equipment?.setBoots(piece)
            }
        }
    }
    entity.equipment?.let { equipment ->
        equipment.itemInMainHandDropChance = 0f
        equipment.helmetDropChance = 0f
        equipment.chestplateDropChance = 0f
        equipment.leggingsDropChance = 0f
        equipment.bootsDropChance = 0f
    }
    if (champion) entity.addPotionEffect(PotionEffect(PotionEffectType.RESISTANCE, PotionEffect.INFINITE_DURATION, 0, false, false))
    if (strength) entity.addPotionEffect(PotionEffect(PotionEffectType.STRENGTH, PotionEffect.INFINITE_DURATION, 0, false, false))
}
