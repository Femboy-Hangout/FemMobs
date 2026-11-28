package dev.femmobs

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.event.HoverEvent
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder
import org.bukkit.command.CommandSender

/**
 * Chat styling shared with FemItems: a `[FemMobs]` prefix in the same pink-to-lavender gradient, green for
 * success, red for errors, gray for details, and clickable `▸ /command — description` help lines.
 */
object Msg {
    const val PINK = "#ff7eb6"
    const val LAVENDER = "#c084fc"
    const val SUCCESS = "#7ee8a2"
    const val ERROR = "#ff6b81"
    const val MUTED = "#a1a1aa"

    val PINK_COLOR: TextColor = TextColor.fromHexString(PINK)!!
    val LAVENDER_COLOR: TextColor = TextColor.fromHexString(LAVENDER)!!
    val SUCCESS_COLOR: TextColor = TextColor.fromHexString(SUCCESS)!!
    val ERROR_COLOR: TextColor = TextColor.fromHexString(ERROR)!!
    val MUTED_COLOR: TextColor = TextColor.fromHexString(MUTED)!!

    private val mm = MiniMessage.miniMessage()
    private const val PREFIX = "<dark_gray>[</dark_gray><gradient:$PINK:$LAVENDER>FemMobs</gradient><dark_gray>]</dark_gray> "

    enum class Tone(val color: String) { INFO(LAVENDER), SUCCESS(Msg.SUCCESS), ERROR(Msg.ERROR), MUTED(Msg.MUTED) }

    /** A prefixed line. [text] is plain text (never parsed), so player and config names are safe in it. */
    fun line(text: String, tone: Tone = Tone.INFO): Component =
        mm.deserialize("$PREFIX<color:${tone.color}><text></color>", Placeholder.unparsed("text", text))

    /** A prefixed line whose tone is guessed from its wording: problems in red, finished actions in green. */
    fun auto(text: String): Component = line(text, toneOf(text))

    private val errorWords = listOf(
        "unknown", "only a player", "only players", "no room", "failed", "can't", "cannot", "couldn't", "isn't",
        "aren't", "must ", "usage:", "not loaded", "already", "look at", "hold the item", "lack permission",
        "no trader", "stop it first", "stop ",
    )
    private val mutedWords = listOf("no arenas are running", "no events are running", "nothing is running", "no traders saved")
    private val successWords = listOf(
        "started", "starting", "stopped", "spawned", "spawning", "saved", "deleted", "replaced", "reloaded",
        "cleanup removed", "discarded",
    )

    private fun toneOf(text: String): Tone {
        val lower = text.lowercase()
        return when {
            mutedWords.any { it in lower } -> Tone.MUTED
            successWords.any { lower.startsWith(it) || lower.startsWith("femmobs $it") } -> Tone.SUCCESS
            errorWords.any { it in lower } -> Tone.ERROR
            else -> Tone.INFO
        }
    }

    /** `  ✦ text` detail line under a header (no prefix), in the FemItems layout. */
    fun detail(text: String): Component =
        mm.deserialize("<color:$PINK>  ✦</color> <color:$MUTED><text></color>", Placeholder.unparsed("text", text))

    /** A clickable `  ▸ /command — description` line that fills the command into chat. */
    fun command(command: String, description: String, fill: String = command.substringBefore(" <").substringBefore(" [")): Component =
        mm.deserialize(
            "<color:$PINK>  ▸</color> <color:$LAVENDER><command></color> <color:$MUTED>— <description></color>",
            Placeholder.unparsed("command", command),
            Placeholder.unparsed("description", description),
        )
            .clickEvent(ClickEvent.suggestCommand("$fill "))
            .hoverEvent(HoverEvent.showText(Component.text("Click to fill in $fill")))

    /** A clickable list entry: `  ▸ name — details`, running or filling [command] on click. */
    fun entry(
        name: String, details: String, command: String, run: Boolean = false, hover: String = "Click: $command",
        tag: String? = null, tagColor: TextColor? = null,
    ): Component =
        mm.deserialize(
            "<color:$PINK>  ▸</color> <tag><white><name></white> <color:$MUTED>— <details></color>",
            Placeholder.unparsed("name", name),
            Placeholder.unparsed("details", details),
            Placeholder.component("tag", if (tag == null) Component.empty()
                else Component.text("[$tag] ", tagColor ?: TextColor.fromHexString(LAVENDER)!!)),
        )
            .clickEvent(if (run) ClickEvent.runCommand(command) else ClickEvent.suggestCommand(command))
            .hoverEvent(HoverEvent.showText(Component.text(hover)))
}

/** Sends a styled `[FemMobs]` line; the tone (success, error, info) is picked from the wording. */
fun CommandSender.say(text: String) = sendMessage(Msg.auto(text))
