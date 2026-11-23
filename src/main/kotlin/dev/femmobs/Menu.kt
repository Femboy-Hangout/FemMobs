package dev.femmobs

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.inventory.ClickType
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemStack

/**
 * A chest menu whose slots carry their own click handlers. A new holder is created every time a
 * menu opens, so nothing is cached between openings.
 */
class Menu(rows: Int, title: String) : InventoryHolder {
    private val menu: Inventory = Bukkit.createInventory(this, rows * 9, Component.text(title))
    private val actions = mutableMapOf<Int, (Player, ClickType) -> Unit>()

    init {
        for (slot in 0 until menu.size) menu.setItem(slot, icon(Material.GRAY_STAINED_GLASS_PANE, " "))
    }

    override fun getInventory(): Inventory = menu

    val size: Int get() = menu.size

    fun set(slot: Int, item: ItemStack, action: ((Player, ClickType) -> Unit)? = null) {
        menu.setItem(slot, item)
        if (action == null) actions.remove(slot) else actions[slot] = action
    }

    fun click(player: Player, slot: Int, click: ClickType) {
        actions[slot]?.invoke(player, click)
    }

    fun open(player: Player) {
        player.openInventory(menu)
    }

    companion object {
        /** Slots available for content in a 6-row menu; the bottom row is reserved for navigation. */
        const val PAGE_SIZE = 45

        fun icon(material: Material, title: String, color: TextColor = NamedTextColor.YELLOW, lore: List<Component> = emptyList()): ItemStack =
            ItemStack(material).also { item ->
                item.itemMeta = item.itemMeta.also { meta ->
                    meta.displayName(Component.text(title, color).decoration(TextDecoration.ITALIC, false))
                    if (lore.isNotEmpty()) meta.lore(lore.map { it.decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE) })
                }
            }

        fun line(text: String, color: NamedTextColor = NamedTextColor.GRAY): Component = Component.text(text, color)
    }
}
