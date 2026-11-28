package dev.femmobs

import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.suggestion.SuggestionProvider
import io.papermc.paper.command.brigadier.CommandSourceStack
import io.papermc.paper.command.brigadier.Commands
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.JoinConfiguration
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.event.HoverEvent
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Bukkit
import org.bukkit.NamespacedKey
import org.bukkit.command.CommandSender
import org.bukkit.entity.AbstractVillager
import org.bukkit.entity.Player
import org.bukkit.entity.Villager
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.entity.VillagerAcquireTradeEvent
import org.bukkit.event.entity.VillagerCareerChangeEvent
import org.bukkit.inventory.ItemStack
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Level

/**
 * Custom villager / wandering trader builder. Traders are built step by step in chat with clickable
 * buttons, saved to traders.yml, and spawned in front of the player.
 */
class TraderFeature(private val plugin: FemMobsPlugin, dataFolder: File) : Listener {
    private enum class Step(val title: String) {
        TYPE("Type"), PROFESSION("Profession"), LOOK("Biome look"), LEVEL("Level"),
        BEHAVIOR("Name & behavior"), TRADES("Trades"), REVIEW("Review"),
    }

    private class Draft(val spec: TraderSpec, val isNew: Boolean) {
        @Volatile var step = Step.TYPE
        /** Index of the trade being edited on the Trades step, or null for the trade list. */
        @Volatile var editing: Int? = null
    }

    private val store = TraderStore(File(dataFolder, "traders.yml"))
    private val lock = Any()
    @Volatile private var traders: Map<String, TraderSpec> = emptyMap()
    private val drafts = ConcurrentHashMap<UUID, Draft>()

    fun load() {
        traders = store.load()
    }

    val count: Int get() = traders.size

    val saved: Collection<TraderSpec> get() = traders.values

    /** The trader's name without colors, or its key. */
    fun displayName(spec: TraderSpec): String = spec.name?.let { MiniMessage.miniMessage().stripTags(it) } ?: spec.key

    /** "5x Emerald + 1x Book → 1x Enchanted Book" in plain text, for menus. */
    fun tradeText(trade: TradeSpec): String {
        fun item(stack: ItemStack?) = stack?.let { "${it.amount}x " + PlainTextComponentSerializer.plainText().serialize(it.effectiveName()) } ?: "?"
        return item(trade.cost1) + (trade.cost2?.let { " + " + item(it) } ?: "") + " → " + item(trade.result)
    }

    // ---------------------------------------------------------------- commands

    private val traderSuggestions = SuggestionProvider<CommandSourceStack> { _, builder ->
        traders.keys.filter { it.startsWith(builder.remainingLowerCase) }.forEach(builder::suggest)
        builder.buildFuture()
    }

    fun command(): LiteralArgumentBuilder<CommandSourceStack> = Commands.literal("trader")
        .executes { ctx -> overview(ctx.source.sender); 1 }
        .then(Commands.literal("create").then(Commands.argument("name", StringArgumentType.word())
            .executes { ctx -> asPlayer(ctx.source.sender) { create(it, StringArgumentType.getString(ctx, "name")) } }))
        .then(Commands.literal("edit").then(Commands.argument("name", StringArgumentType.word()).suggests(traderSuggestions)
            .executes { ctx -> asPlayer(ctx.source.sender) { edit(it, StringArgumentType.getString(ctx, "name")) } }))
        .then(Commands.literal("spawn").then(Commands.argument("name", StringArgumentType.word()).suggests(traderSuggestions)
            .executes { ctx -> asPlayer(ctx.source.sender) { spawnSaved(it, StringArgumentType.getString(ctx, "name")) } }))
        .then(Commands.literal("apply").then(Commands.argument("name", StringArgumentType.word()).suggests(traderSuggestions)
            .executes { ctx -> asPlayer(ctx.source.sender) { applyToTarget(it, StringArgumentType.getString(ctx, "name")) } }))
        .then(Commands.literal("delete").then(Commands.argument("name", StringArgumentType.word()).suggests(traderSuggestions)
            .executes { ctx -> delete(ctx.source.sender, StringArgumentType.getString(ctx, "name")); 1 }))
        .then(Commands.literal("list").executes { ctx -> list(ctx.source.sender); 1 })
        // Builder actions; the chat buttons run these.
        .then(Commands.literal("b").then(Commands.argument("action", StringArgumentType.greedyString())
            .executes { ctx -> asPlayer(ctx.source.sender) { builderAction(it, StringArgumentType.getString(ctx, "action")) } }))

    /**
     * Runs [action] on the player's own thread, which owns their inventory and surroundings: right away when
     * the command is already there (always on Paper), otherwise via the player's scheduler (Folia).
     * Any error is reported in chat and logged instead of disappearing.
     */
    private fun asPlayer(sender: CommandSender, action: (Player) -> Unit): Int {
        if (sender !is Player) { sender.say("Only a player can do that."); return 0 }
        val guarded = {
            try {
                action(sender)
            } catch (error: Throwable) {
                plugin.logger.log(Level.WARNING, "Trader command failed for ${sender.name}", error)
                sender.sendMessage(Component.text("Trader command failed: $error (details in the console).", Msg.ERROR_COLOR))
            }
        }
        if (Bukkit.isOwnedByCurrentRegion(sender)) guarded()
        else if (sender.scheduler.run(plugin, { guarded() }, null) == null) plugin.logger.warning("Could not schedule trader command for ${sender.name}.")
        return 1
    }

    private fun overview(sender: CommandSender) {
        val draft = (sender as? Player)?.let { drafts[it.uniqueId] }
        if (draft != null) { render(sender, draft); return }
        sender.sendMessage(Msg.line("Traders (${traders.size} saved)", Msg.Tone.INFO))
        listOf(
            "/femmobs trader create <name>" to "build a new trader step by step",
            "/femmobs trader edit <name>" to "change a saved trader",
            "/femmobs trader spawn <name>" to "spawn a saved trader in front of you",
            "/femmobs trader apply <name>" to "give the villager you look at its trades",
            "/femmobs trader list" to "list saved traders",
            "/femmobs trader delete <name>" to "delete a saved trader",
        ).forEach { (command, description) -> sender.sendMessage(Msg.command(command, description)) }
    }

    private fun list(sender: CommandSender) {
        if (traders.isEmpty()) { sender.say("No traders saved yet. Make one with /femmobs trader create <name>."); return }
        sender.sendMessage(Msg.line("Saved traders (${traders.size})", Msg.Tone.INFO))
        traders.values.forEach { trader ->
            sender.sendMessage(join(
                Component.text("  ▸", Msg.PINK_COLOR),
                Component.text(trader.key, NamedTextColor.WHITE),
                Component.text("— ${describeKind(trader)} · ${trader.trades.size} trade(s)", Msg.MUTED_COLOR),
                button("Spawn", "/femmobs trader spawn ${trader.key}", "Spawn in front of you"),
                button("Edit", "/femmobs trader edit ${trader.key}", "Open in the builder"),
            ))
        }
    }

    private fun create(player: Player, rawName: String) {
        plugin.logger.info("[trader] ${player.name} is creating trader '$rawName'.")
        val key = rawName.lowercase()
        if (!KEY_PATTERN.matches(key)) { player.say("Trader names may only use a-z, 0-9, _ and - (max 32 characters)."); return }
        if (key in traders) { player.say("A trader named '$key' already exists. Use /femmobs trader edit $key."); return }
        val draft = Draft(TraderSpec(key), isNew = true)
        drafts[player.uniqueId] = draft
        render(player, draft)
    }

    internal fun edit(player: Player, rawName: String) {
        val saved = traders[rawName.lowercase()] ?: return player.say("No trader named '$rawName'. See /femmobs trader list.")
        val draft = Draft(saved.deepCopy(), isNew = false)
        drafts[player.uniqueId] = draft
        render(player, draft)
    }

    private fun delete(sender: CommandSender, rawName: String) {
        val key = rawName.lowercase()
        synchronized(lock) {
            if (key !in traders) { sender.say("No trader named '$key'."); return }
            traders = LinkedHashMap(traders).also { it.remove(key) }
            store.save(traders.values)
        }
        sender.say("Deleted trader '$key'. Traders already spawned keep their trades.")
    }

    internal fun spawnSaved(player: Player, rawName: String) {
        val spec = traders[rawName.lowercase()] ?: return player.say("No trader named '$rawName'. See /femmobs trader list.")
        spawn(player, spec)
    }

    private fun spawn(player: Player, spec: TraderSpec) {
        val label = spec.name?.let { MiniMessage.miniMessage().stripTags(it) } ?: spec.key
        plugin.spawnInFront(player, label, 1) { location ->
            @Suppress("UNCHECKED_CAST")
            val type = spec.entityClass as Class<AbstractVillager>
            location.world.spawn(location, type, false) { spec.applyTo(it) }
        }
    }

    private fun applyToTarget(player: Player, rawName: String) {
        val spec = traders[rawName.lowercase()] ?: return player.say("No trader named '$rawName'. See /femmobs trader list.")
        val target = player.getTargetEntity(8) as? AbstractVillager
            ?: return player.say("Look at a villager or wandering trader within 8 blocks first.")
        if (target is Villager && (target.profession.key.key == "none" || target.profession.key.key == "nitwit")) {
            return player.say("Unemployed villagers and nitwits can't trade. Spawn one with /femmobs trader spawn ${spec.key} instead.")
        }
        target.scheduler.run(plugin, {
            if (target is Villager && target.villagerExperience == 0) target.villagerExperience = 1 // keeps its job
            spec.applyTrades(target)
            plugin.tell(player, "Replaced that trader's trades with '${spec.key}' (${spec.trades.count { it.complete }} trades). Its look and level were kept.")
        }, null)
    }

    // ---------------------------------------------------------------- builder actions

    private fun builderAction(player: Player, input: String) {
        val draft = drafts[player.uniqueId]
            ?: return player.say("You aren't building a trader. Start with /femmobs trader create <name>.")
        val spec = draft.spec
        val args = input.trim().split(" ")
        val error: String? = when (args[0]) {
            "step" -> runCatching { Step.valueOf(args[1]) }.getOrNull()?.let { draft.step = it; draft.editing = null; null } ?: "Unknown step."
            "next", "back" -> {
                val steps = steps(spec)
                val index = steps.indexOf(draft.step) + if (args[0] == "next") 1 else -1
                draft.step = steps[index.coerceIn(0, steps.size - 1)]
                draft.editing = null
                null
            }
            "type" -> { spec.kind = if (args.getOrNull(1) == "wandering") TraderKind.WANDERING_TRADER else TraderKind.VILLAGER; null }
            "profession" -> NamespacedKey.fromString(args.getOrElse(1) { "" })?.takeIf { it in TraderSpec.professions() }
                ?.let { spec.profession = it; null } ?: "Unknown profession."
            "look" -> NamespacedKey.fromString(args.getOrElse(1) { "" })?.takeIf { it in TraderSpec.biomes() }
                ?.let { spec.biome = it; null } ?: "Unknown biome look."
            "level" -> args.getOrNull(1)?.toIntOrNull()?.takeIf { it in 1..5 }?.let { spec.level = it; null } ?: "Level must be 1 to 5."
            "name" -> {
                val text = input.trim().removePrefix("name").trim()
                if (text.isEmpty()) "Type the name after the command, e.g. /femmobs trader b name <gold>Bob"
                else { spec.name = text; null }
            }
            "name-clear" -> { spec.name = null; null }
            "toggle" -> when (args.getOrNull(1)) {
                "stay" -> { spec.stayInPlace = !spec.stayInPlace; null }
                "invulnerable" -> { spec.invulnerable = !spec.invulnerable; null }
                "silent" -> { spec.silent = !spec.silent; null }
                "despawn" -> { spec.neverDespawn = !spec.neverDespawn; null }
                else -> "Unknown setting."
            }
            "trade-add" -> {
                spec.trades += TradeSpec()
                draft.step = Step.TRADES
                draft.editing = spec.trades.lastIndex
                null
            }
            "trade" -> tradeAction(player, draft, args)
            "preview" -> { spawn(player, spec); null }
            "save" -> save(player, draft)
            "discard" -> {
                drafts.remove(player.uniqueId)
                player.sendMessage(Component.text("Discarded your changes to '${spec.key}'.", Msg.MUTED_COLOR))
                return
            }
            else -> "Unknown builder action."
        }
        if (error != null) player.sendMessage(Component.text(error, Msg.ERROR_COLOR))
        if (drafts[player.uniqueId] === draft) render(player, draft)
    }

    private fun tradeAction(player: Player, draft: Draft, args: List<String>): String? {
        val trades = draft.spec.trades
        val index = args.getOrNull(1)?.toIntOrNull()?.minus(1)
        if (index == null || index !in trades.indices) return "That trade no longer exists."
        val trade = trades[index]
        draft.step = Step.TRADES
        fun held(): ItemStack? = player.inventory.itemInMainHand.takeUnless { it.type.isAir }?.clone()
        return when (args.getOrNull(2)) {
            "edit" -> { draft.editing = index; null }
            "done" -> { draft.editing = null; null }
            "remove" -> { trades.removeAt(index); draft.editing = null; null }
            "up" -> { if (index > 0) trades.add(index - 1, trades.removeAt(index)); null }
            "down" -> { if (index < trades.lastIndex) trades.add(index + 1, trades.removeAt(index)); null }
            "cost1" -> held()?.let { trade.cost1 = it; null } ?: HOLD_ITEM
            "cost2" -> held()?.let { trade.cost2 = it; null } ?: HOLD_ITEM
            "cost2-clear" -> { trade.cost2 = null; null }
            "result" -> held()?.let { trade.result = it; null } ?: HOLD_ITEM
            "unlimited" -> { trade.maxUses = TradeSpec.UNLIMITED; null }
            "uses" -> args.getOrNull(3)?.toIntOrNull()?.takeIf { it > 0 }?.let { trade.maxUses = it; null } ?: "Uses must be a number above 0."
            "xp" -> args.getOrNull(3)?.toIntOrNull()?.takeIf { it >= 0 }?.let { trade.villagerXp = it; null } ?: "XP must be 0 or more."
            "fixed" -> { trade.fixedPrice = !trade.fixedPrice; null }
            else -> "Unknown trade action."
        }
    }

    private fun save(player: Player, draft: Draft): String? {
        val spec = draft.spec
        val incomplete = spec.trades.withIndex().filter { !it.value.complete }.map { it.index + 1 }
        if (incomplete.isNotEmpty()) {
            draft.step = Step.TRADES
            return "Trade(s) ${incomplete.joinToString(", ")} need at least Cost 1 and a Result before saving."
        }
        if (spec.trades.isEmpty()) { draft.step = Step.TRADES; return "Add at least one trade before saving." }
        synchronized(lock) {
            if (draft.isNew && spec.key in traders) return "A trader named '${spec.key}' was created meanwhile; pick another name."
            traders = LinkedHashMap(traders).also { it[spec.key] = spec.deepCopy() }
            store.save(traders.values)
        }
        drafts.remove(player.uniqueId)
        player.sendMessage(join(
            Component.text("Saved trader '${spec.key}'.", Msg.SUCCESS_COLOR),
            button("Spawn now", "/femmobs trader spawn ${spec.key}", "Spawn it in front of you"),
            button("Edit again", "/femmobs trader edit ${spec.key}", "Reopen the builder"),
        ))
        return null
    }

    // ---------------------------------------------------------------- chat rendering

    private fun steps(spec: TraderSpec): List<Step> =
        if (spec.kind == TraderKind.VILLAGER) Step.entries
        else listOf(Step.TYPE, Step.BEHAVIOR, Step.TRADES, Step.REVIEW)

    private fun render(player: Player, draft: Draft) {
        val spec = draft.spec
        val steps = steps(spec)
        if (draft.step !in steps) draft.step = Step.TYPE
        val lines = mutableListOf<Component>()
        lines += Component.empty()
        lines += Component.text("━━━ Trader '${spec.key}' ━━━ ", Msg.PINK_COLOR).decorate(TextDecoration.BOLD)
            .append(Component.text("step ${steps.indexOf(draft.step) + 1}/${steps.size}", Msg.MUTED_COLOR).decoration(TextDecoration.BOLD, false))
        // Clickable step bar: jump straight to any part.
        lines += Component.join(JoinConfiguration.separator(Component.text(" › ", NamedTextColor.DARK_GRAY)), steps.map { step ->
            val current = step == draft.step
            Component.text(step.title, if (current) Msg.PINK_COLOR else Msg.MUTED_COLOR)
                .decoration(TextDecoration.UNDERLINED, current)
                .clickEvent(ClickEvent.runCommand("$B step ${step.name}"))
                .hoverEvent(HoverEvent.showText(Component.text("Go to ${step.title}")))
        })
        lines += Component.empty()
        lines += when (draft.step) {
            Step.TYPE -> listOf(
                Component.text("What kind of trader?", NamedTextColor.WHITE),
                join(
                    option("Villager", spec.kind == TraderKind.VILLAGER, "$B type villager", "Has a profession, biome look and level badge"),
                    option("Wandering trader", spec.kind == TraderKind.WANDERING_TRADER, "$B type wandering", "The blue-robed travelling trader"),
                ),
            )
            Step.PROFESSION -> listOf(
                Component.text("Profession (sets its outfit):", NamedTextColor.WHITE),
                join(TraderSpec.professions().map { key -> option(pretty(key), spec.profession == key, "$B profession $key", key.toString()) }),
            )
            Step.LOOK -> listOf(
                Component.text("Biome look (sets its clothing style):", NamedTextColor.WHITE),
                join(TraderSpec.biomes().map { key -> option(pretty(key), spec.biome == key, "$B look $key", key.toString()) }),
            )
            Step.LEVEL -> listOf(
                Component.text("Level (badge shown in the trade screen; it never levels up or gains trades):", NamedTextColor.WHITE),
                join(TraderSpec.LEVEL_NAMES.mapIndexed { i, name -> option("${i + 1} $name", spec.level == i + 1, "$B level ${i + 1}", name) }),
            )
            Step.BEHAVIOR -> behaviorLines(spec)
            Step.TRADES -> draft.editing?.takeIf { it in spec.trades.indices }?.let { tradeEditorLines(spec, it) } ?: tradeListLines(spec)
            Step.REVIEW -> reviewLines(spec)
        }
        lines += Component.empty()
        val index = steps.indexOf(draft.step)
        val nav = mutableListOf<Component>()
        if (index > 0) nav += button("◀ Back", "$B back", "Previous step", NamedTextColor.WHITE)
        if (index < steps.lastIndex) nav += button("Next ▶", "$B next", "Next step", NamedTextColor.WHITE)
        nav += button("Save", "$B save", "Save this trader to traders.yml", Msg.SUCCESS_COLOR)
        nav += button("Discard", "$B discard", "Throw away these changes", Msg.ERROR_COLOR)
        lines += join(nav)
        lines.forEach(player::sendMessage)
        plugin.logger.info("[trader] Showed ${player.name} the ${draft.step.title} step of '${spec.key}' (${lines.size} lines).")
    }

    private fun behaviorLines(spec: TraderSpec): List<Component> {
        val name = spec.name?.let { MiniMessage.miniMessage().deserialize(it) } ?: Component.text("none", NamedTextColor.DARK_GRAY)
        fun toggle(label: String, on: Boolean, flag: String, hover: String) = join(
            Component.text("$label: ", NamedTextColor.WHITE),
            Component.text(if (on) "ON" else "OFF", if (on) Msg.SUCCESS_COLOR else Msg.ERROR_COLOR),
            button("Toggle", "$B toggle $flag", hover),
        )
        return listOf(
            join(
                Component.text("Name: ", NamedTextColor.WHITE).append(name),
                button("Set name", "$B name ", "Type a name after the command. Colors work: <gold>Bob, <#ff8800>Bob", suggest = true),
                button("Clear", "$B name-clear", "No name above its head"),
            ),
            toggle("Stay in place", spec.stayInPlace, "stay", "Never walks off, turns or sleeps. Trading still works."),
            toggle("Can't be hurt", spec.invulnerable, "invulnerable", "Mobs and survival players can't damage it."),
            toggle("Silent", spec.silent, "silent", "No villager sounds."),
            toggle("Never despawns", spec.neverDespawn, "despawn", "Stays forever. Wandering traders normally leave after a while."),
        )
    }

    private fun tradeListLines(spec: TraderSpec): List<Component> {
        val lines = mutableListOf<Component>()
        if (spec.trades.isEmpty()) lines += Component.text("No trades yet.", Msg.MUTED_COLOR)
        spec.trades.forEachIndexed { i, trade ->
            val n = i + 1
            lines += join(
                Component.text("#$n", Msg.PINK_COLOR),
                tradeSummary(trade),
                button("Edit", "$B trade $n edit", "Change this trade"),
                button("↑", "$B trade $n up", "Move up"),
                button("↓", "$B trade $n down", "Move down"),
                button("✖", "$B trade $n remove", "Remove this trade", Msg.ERROR_COLOR),
            )
        }
        lines += button("+ Add trade", "$B trade-add", "Add a new trade", Msg.SUCCESS_COLOR)
        return lines
    }

    private fun tradeEditorLines(spec: TraderSpec, index: Int): List<Component> {
        val trade = spec.trades[index]
        val n = index + 1
        val hand = "Hold the item in your main hand. The stack size you hold is the amount used."
        return listOf(
            Component.text("Editing trade #$n. Hold an item and click [Use hand].", NamedTextColor.WHITE),
            join(label("Cost 1"), item(trade.cost1, "not set"), button("Use hand", "$B trade $n cost1", hand)),
            join(label("Cost 2"), item(trade.cost2, "none"), button("Use hand", "$B trade $n cost2", hand),
                button("Clear", "$B trade $n cost2-clear", "No second cost")),
            join(label("Result"), item(trade.result, "not set"), button("Use hand", "$B trade $n result", hand)),
            join(label("Uses"), Component.text(usesText(trade), NamedTextColor.AQUA),
                button("Unlimited", "$B trade $n unlimited", "Never runs out (recommended: summoned traders can't restock)"),
                button("Set number", "$B trade $n uses ", "Type how many times it works", suggest = true)),
            join(label("Villager XP"), Component.text(trade.villagerXp.toString(), NamedTextColor.AQUA),
                button("Set", "$B trade $n xp ", "XP the villager gains per trade (cosmetic; it won't gain trades)", suggest = true)),
            join(label("Fixed price"), Component.text(if (trade.fixedPrice) "ON" else "OFF", if (trade.fixedPrice) Msg.SUCCESS_COLOR else Msg.ERROR_COLOR),
                button("Toggle", "$B trade $n fixed", "ON: reputation, discounts and demand never change the price")),
            join(button("✔ Done", "$B trade $n done", "Back to the trade list", Msg.SUCCESS_COLOR),
                button("✖ Remove trade", "$B trade $n remove", "Delete this trade", Msg.ERROR_COLOR)),
        )
    }

    private fun reviewLines(spec: TraderSpec): List<Component> {
        val name = spec.name?.let { MiniMessage.miniMessage().deserialize(it) } ?: Component.text("none", NamedTextColor.DARK_GRAY)
        val flags = listOfNotNull(
            "stays in place".takeIf { spec.stayInPlace }, "can't be hurt".takeIf { spec.invulnerable },
            "silent".takeIf { spec.silent }, "never despawns".takeIf { spec.neverDespawn },
        ).joinToString(", ").ifEmpty { "none" }
        val lines = mutableListOf(
            join(label("Trader"), Component.text(describeKind(spec), NamedTextColor.AQUA)),
            join(label("Name"), name),
            join(label("Behavior"), Component.text(flags, NamedTextColor.AQUA)),
            label("Trades (${spec.trades.size})"),
        )
        spec.trades.forEachIndexed { i, trade -> lines += join(Component.text("  #${i + 1}", Msg.PINK_COLOR), tradeSummary(trade)) }
        lines += Component.empty()
        lines += join(
            button("Save", "$B save", "Save to traders.yml", Msg.SUCCESS_COLOR),
            button("Spawn preview", "$B preview", "Spawn this draft in front of you without saving"),
        )
        return lines
    }

    internal fun describeKind(spec: TraderSpec): String =
        if (spec.kind == TraderKind.WANDERING_TRADER) "Wandering trader"
        else "${TraderSpec.LEVEL_NAMES[spec.level - 1]} ${pretty(spec.biome)} ${pretty(spec.profession)}"

    private fun tradeSummary(trade: TradeSpec): Component {
        val parts = mutableListOf(item(trade.cost1, "?"))
        trade.cost2?.let { parts += Component.text("+", Msg.MUTED_COLOR); parts += item(it, "") }
        parts += Component.text("→", Msg.MUTED_COLOR)
        parts += item(trade.result, "?")
        parts += Component.text("(${usesText(trade)}${if (trade.fixedPrice) "" else ", price can change"})", NamedTextColor.DARK_GRAY)
        if (!trade.complete) parts += Component.text("incomplete", Msg.ERROR_COLOR)
        return join(parts)
    }

    private fun usesText(trade: TradeSpec) = if (trade.maxUses == TradeSpec.UNLIMITED) "unlimited uses" else "${trade.maxUses} uses"

    private fun item(stack: ItemStack?, empty: String): Component =
        if (stack == null) Component.text(empty, if (empty == "?" || empty == "not set") Msg.ERROR_COLOR else NamedTextColor.DARK_GRAY)
        else Component.text("${stack.amount}x ", NamedTextColor.WHITE).append(stack.displayName()) // displayName() hovers the full item

    private fun label(text: String) = Component.text("$text:", NamedTextColor.WHITE)

    private fun option(label: String, selected: Boolean, command: String, hover: String) =
        button(if (selected) "✔ $label" else label, command, hover, if (selected) Msg.SUCCESS_COLOR else Msg.MUTED_COLOR)

    private fun button(label: String, command: String, hover: String, color: TextColor = Msg.LAVENDER_COLOR, suggest: Boolean = false): Component =
        Component.text("[$label]", color)
            .clickEvent(if (suggest) ClickEvent.suggestCommand(command) else ClickEvent.runCommand(command))
            .hoverEvent(HoverEvent.showText(Component.text(hover)))

    private fun join(vararg parts: Component) = join(parts.toList())
    private fun join(parts: List<Component>) = Component.join(JoinConfiguration.separator(Component.space()), parts)

    private fun pretty(key: NamespacedKey) = key.key.split('_').joinToString(" ") { it.replaceFirstChar(Char::uppercase) }

    // ---------------------------------------------------------------- protection

    /** Custom traders keep exactly the trades they were given. */
    @EventHandler(ignoreCancelled = true)
    fun onAcquireTrade(event: VillagerAcquireTradeEvent) {
        if (event.entity.scoreboardTags.contains(TraderSpec.TRADER_TAG)) event.isCancelled = true
    }

    /** Custom traders never lose or change their job (which would wipe their trades). */
    @EventHandler(ignoreCancelled = true)
    fun onCareerChange(event: VillagerCareerChangeEvent) {
        if (event.entity.scoreboardTags.contains(TraderSpec.TRADER_TAG)) event.isCancelled = true
    }

    fun clearDrafts() = drafts.clear()

    private companion object {
        const val B = "/femmobs trader b"
        const val HOLD_ITEM = "Hold the item in your main hand first."
        val KEY_PATTERN = Regex("[a-z0-9_-]{1,32}")
    }
}
