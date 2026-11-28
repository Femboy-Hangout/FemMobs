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
import org.bukkit.inventory.ItemFlag
import org.bukkit.inventory.ItemStack

/**
 * A chest menu in the FemItems style (dark purple "FemMobs • ..." title, light purple names, gray hints) whose
 * slots carry their own click handlers. A new holder is created every time a menu opens; nothing is cached.
 */
class Menu(rows: Int, title: String) : InventoryHolder {
    private val menu: Inventory = Bukkit.createInventory(this, rows * 9, Component.text(title, NamedTextColor.DARK_PURPLE))
    private val actions = mutableMapOf<Int, (Player, ClickType) -> Unit>()

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
        const val BACK_SLOT = 45
        const val PREVIOUS_SLOT = 48
        const val NEXT_SLOT = 50

        /** A labeled button. Default name color is light purple, like FemItems' category names. */
        fun icon(material: Material, title: String, color: TextColor = NamedTextColor.LIGHT_PURPLE, lore: List<Component> = emptyList()): ItemStack =
            ItemStack(material).also { item ->
                item.editMeta { meta ->
                    meta.displayName(Component.text(title, color).decoration(TextDecoration.ITALIC, false))
                    if (lore.isNotEmpty()) meta.lore(lore.map { it.decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE) })
                    meta.addItemFlags(*ItemFlag.entries.toTypedArray())
                }
            }

        /** A gray detail line. */
        fun line(text: String, color: TextColor = NamedTextColor.GRAY): Component = Component.text(text, color)

        /** "Label: value" with a gray label and white value. */
        fun field(label: String, value: String): Component =
            Component.text("$label: ", NamedTextColor.GRAY).append(Component.text(value, NamedTextColor.WHITE))

        /** The click hint at the bottom of a button, e.g. "Click: spawn 1 · Shift-click: spawn 5". */
        fun hint(text: String): Component = Component.text(text, NamedTextColor.GRAY)

        /** A colored tag line such as "★ Boss battle". */
        fun tag(text: String, color: TextColor): Component = Component.text(text, color).decorate(TextDecoration.BOLD)

        fun back(label: String): ItemStack = icon(Material.ARROW, label, NamedTextColor.YELLOW)
        fun previous(): ItemStack = icon(Material.SPECTRAL_ARROW, "Previous page", NamedTextColor.YELLOW)
        fun next(): ItemStack = icon(Material.SPECTRAL_ARROW, "Next page", NamedTextColor.YELLOW)
    }
}
