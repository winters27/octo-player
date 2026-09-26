package app.winters.octo.widget

// What a widget button asks the playback service to do. Play and pause are
// separate so a widget still showing an old state can never do the opposite
// of what its button shows.
sealed interface WidgetCommand {
    data object Play : WidgetCommand
    data object Pause : WidgetCommand
    data object Next : WidgetCommand
    data object Previous : WidgetCommand
    data class Quick(val pick: QuickPick) : WidgetCommand
}

private const val QUICK = "quick:"

// How a command travels inside an intent.
fun encodeCommand(command: WidgetCommand): String = when (command) {
    WidgetCommand.Play -> "play"
    WidgetCommand.Pause -> "pause"
    WidgetCommand.Next -> "next"
    WidgetCommand.Previous -> "previous"
    is WidgetCommand.Quick -> QUICK + command.pick.name
}

fun decodeCommand(text: String?): WidgetCommand? = when {
    text == null -> null
    text == "play" -> WidgetCommand.Play
    text == "pause" -> WidgetCommand.Pause
    text == "next" -> WidgetCommand.Next
    text == "previous" -> WidgetCommand.Previous
    text.startsWith(QUICK) -> QuickPick.entries.firstOrNull { it.name == text.removePrefix(QUICK) }?.let(WidgetCommand::Quick)
    else -> null
}

// Each button needs its own pending intent, and the system tells them apart
// by this number, not by their extras.
fun requestCode(command: WidgetCommand): Int = when (command) {
    WidgetCommand.Play -> 1
    WidgetCommand.Pause -> 2
    WidgetCommand.Next -> 3
    WidgetCommand.Previous -> 4
    is WidgetCommand.Quick -> 10 + command.pick.ordinal
}
