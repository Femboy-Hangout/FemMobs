package dev.femmobs

import io.papermc.paper.registry.RegistryAccess
import io.papermc.paper.registry.RegistryKey
import net.kyori.adventure.text.minimessage.MiniMessage
import org.bukkit.NamespacedKey
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.AbstractVillager
import org.bukkit.entity.Villager
import org.bukkit.entity.WanderingTrader
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.MerchantRecipe
import java.io.File
import java.util.Base64

enum class TraderKind(val label: String) { VILLAGER("Villager"), WANDERING_TRADER("Wandering trader") }

/** One trade. Items are copied exactly from the player's hand, so custom names, enchantments and plugin items survive. */
class TradeSpec(
    var cost1: ItemStack? = null,
    var cost2: ItemStack? = null,
    var result: ItemStack? = null,
    /** [UNLIMITED] or a positive number of uses. */
    var maxUses: Int = UNLIMITED,
    /** XP the villager gains per trade. Traders never level up or gain trades from it. */
    var villagerXp: Int = 0,
    /** Ignore reputation, discounts and demand: the price set is always the price paid. */
    var fixedPrice: Boolean = true,
) {
    val complete: Boolean get() = cost1 != null && result != null

    fun deepCopy() = TradeSpec(cost1?.clone(), cost2?.clone(), result?.clone(), maxUses, villagerXp, fixedPrice)

    fun toRecipe(): MerchantRecipe {
        val uses = if (maxUses == UNLIMITED) Int.MAX_VALUE else maxUses
        val recipe = MerchantRecipe(
            result!!.clone(), 0, uses, villagerXp > 0, villagerXp,
            if (fixedPrice) 0f else 0.05f, 0, 0, fixedPrice,
        )
        recipe.ingredients = listOfNotNull(cost1!!.clone(), cost2?.clone())
        return recipe
    }

    companion object { const val UNLIMITED = -1 }
}

class TraderSpec(
    val key: String,
    var kind: TraderKind = TraderKind.VILLAGER,
    var profession: NamespacedKey = NamespacedKey.minecraft("librarian"),
    var biome: NamespacedKey = NamespacedKey.minecraft("plains"),
    var level: Int = 5,
    /** MiniMessage text, e.g. "<gold>Bob the Banker"; null for no name. */
    var name: String? = null,
    var stayInPlace: Boolean = true,
    var invulnerable: Boolean = true,
    var silent: Boolean = false,
    var neverDespawn: Boolean = true,
    val trades: MutableList<TradeSpec> = mutableListOf(),
) {
    fun deepCopy(newKey: String = key) = TraderSpec(
        newKey, kind, profession, biome, level, name, stayInPlace, invulnerable, silent, neverDespawn,
        trades.mapTo(mutableListOf()) { it.deepCopy() },
    )

    val entityClass: Class<out AbstractVillager>
        get() = if (kind == TraderKind.VILLAGER) Villager::class.java else WanderingTrader::class.java

    /** Applies everything to a freshly spawned trader. */
    fun applyTo(trader: AbstractVillager) {
        name?.let {
            trader.customName(MiniMessage.miniMessage().deserialize(it))
            trader.isCustomNameVisible = true
        }
        trader.setAI(!stayInPlace)
        trader.isInvulnerable = invulnerable
        trader.isSilent = silent
        if (neverDespawn) {
            trader.isPersistent = true
            trader.removeWhenFarAway = false
            // A despawn delay of 0 means a wandering trader never leaves.
            (trader as? WanderingTrader)?.despawnDelay = 0
        }
        if (trader is Villager) {
            // Profession must be set before the trades: changing profession clears a villager's offers.
            professionRegistry().get(profession)?.let(trader::setProfession)
            typeRegistry().get(biome)?.let(trader::setVillagerType)
            trader.villagerLevel = level
            // A villager with 0 XP and no job site loses its job; give it the XP its level needs.
            trader.villagerExperience = LEVEL_XP[level - 1].coerceAtLeast(1)
        }
        applyTrades(trader)
    }

    /** Replaces the trader's offers with these trades and marks it as a FemMobs trader. */
    fun applyTrades(trader: AbstractVillager) {
        trader.recipes = trades.filter { it.complete }.map { it.toRecipe() }
        trader.addScoreboardTag(TRADER_TAG)
    }

    companion object {
        /** Traders with this tag never change job or gain random trades. */
        const val TRADER_TAG = "femmobs.trader"
        val LEVEL_NAMES = listOf("Novice", "Apprentice", "Journeyman", "Expert", "Master")
        /** Minimum villager XP for each level (vanilla thresholds). */
        val LEVEL_XP = listOf(0, 10, 70, 150, 250)

        fun professionRegistry() = RegistryAccess.registryAccess().getRegistry(RegistryKey.VILLAGER_PROFESSION)
        fun typeRegistry() = RegistryAccess.registryAccess().getRegistry(RegistryKey.VILLAGER_TYPE)

        /** Professions a trader can have; unemployed villagers and nitwits can't trade. */
        fun professions(): List<NamespacedKey> = professionRegistry().keyStream()
            .filter { it.key != "none" && it.key != "nitwit" }
            .sorted(compareBy { it.key }).toList()

        fun biomes(): List<NamespacedKey> = typeRegistry().keyStream().sorted(compareBy { it.key }).toList()
    }
}

/** Saves traders to traders.yml. Items are stored as Paper's binary item format (base64), which keeps every detail. */
class TraderStore(private val file: File) {
    fun load(): LinkedHashMap<String, TraderSpec> {
        val result = LinkedHashMap<String, TraderSpec>()
        if (!file.exists()) return result
        val yaml = YamlConfiguration.loadConfiguration(file)
        val section = yaml.getConfigurationSection("traders") ?: return result
        section.getKeys(false).forEach { key ->
            val row = section.getConfigurationSection(key) ?: return@forEach
            result[key] = read(key, row)
        }
        return result
    }

    @Synchronized
    fun save(traders: Collection<TraderSpec>) {
        val yaml = YamlConfiguration()
        yaml.options().setHeader(listOf(
            "FemMobs traders. Build and edit them in-game with /femmobs trader create|edit <name>.",
            "Items are stored in Paper's binary item format so custom items survive exactly; edit trades in-game.",
        ))
        val root = yaml.createSection("traders")
        traders.forEach { trader ->
            val row = root.createSection(trader.key)
            row.set("type", trader.kind.name.lowercase())
            row.set("profession", trader.profession.toString())
            row.set("biome", trader.biome.toString())
            row.set("level", trader.level)
            row.set("name", trader.name)
            row.set("stay-in-place", trader.stayInPlace)
            row.set("invulnerable", trader.invulnerable)
            row.set("silent", trader.silent)
            row.set("never-despawn", trader.neverDespawn)
            row.set("trades", trader.trades.map { trade ->
                linkedMapOf<String, Any?>(
                    "cost1" to trade.cost1?.let(::encode),
                    "cost2" to trade.cost2?.let(::encode),
                    "result" to trade.result?.let(::encode),
                    "max-uses" to trade.maxUses,
                    "villager-xp" to trade.villagerXp,
                    "fixed-price" to trade.fixedPrice,
                ).filterValues { it != null }
            })
        }
        file.parentFile.mkdirs()
        yaml.save(file)
    }

    private fun read(key: String, row: ConfigurationSection): TraderSpec {
        val kind = runCatching { TraderKind.valueOf(row.getString("type", "villager")!!.uppercase()) }.getOrDefault(TraderKind.VILLAGER)
        return TraderSpec(
            key = key,
            kind = kind,
            profession = NamespacedKey.fromString(row.getString("profession", "minecraft:librarian")!!) ?: NamespacedKey.minecraft("librarian"),
            biome = NamespacedKey.fromString(row.getString("biome", "minecraft:plains")!!) ?: NamespacedKey.minecraft("plains"),
            level = row.getInt("level", 5).coerceIn(1, 5),
            name = row.getString("name"),
            stayInPlace = row.getBoolean("stay-in-place", true),
            invulnerable = row.getBoolean("invulnerable", true),
            silent = row.getBoolean("silent", false),
            neverDespawn = row.getBoolean("never-despawn", true),
            trades = row.getMapList("trades").mapTo(mutableListOf()) { trade ->
                TradeSpec(
                    cost1 = (trade["cost1"] as? String)?.let(::decode),
                    cost2 = (trade["cost2"] as? String)?.let(::decode),
                    result = (trade["result"] as? String)?.let(::decode),
                    maxUses = trade["max-uses"]?.toString()?.toIntOrNull() ?: TradeSpec.UNLIMITED,
                    villagerXp = trade["villager-xp"]?.toString()?.toIntOrNull() ?: 0,
                    fixedPrice = trade["fixed-price"]?.toString()?.toBooleanStrictOrNull() ?: true,
                )
            },
        )
    }

    private fun encode(item: ItemStack): String = Base64.getEncoder().encodeToString(item.serializeAsBytes())
    private fun decode(data: String): ItemStack = ItemStack.deserializeBytes(Base64.getDecoder().decode(data))
}
