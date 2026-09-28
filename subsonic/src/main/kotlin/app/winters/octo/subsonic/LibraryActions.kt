package app.winters.octo.subsonic

import kotlinx.serialization.Serializable

// The OpenSubsonic extension an Octo server lists when it can act on the
// library's files for an app: for now, take a song out of the library. It
// is listed only while the server's library actions are switched on.
const val OCTO_LIBRARY_ACTIONS = "octoLibraryActions"

// The one action this version knows: move the song's file out of the
// library, into the server's trash.
const val LIBRARY_ACTION_REMOVE = "remove"

// What the server lets the signed-in user do to the library's files.
@Serializable
data class LibraryActions(
    // The server's library actions are on at all.
    val enabled: Boolean = false,
    // This user may use them.
    val allowed: Boolean = false,
    // The server only rehearses: it says what it would do and moves nothing.
    val dryRun: Boolean = true,
    val actions: List<String> = emptyList(),
    // How many days a removed file is kept in the server's trash; 0 keeps
    // it until someone clears it by hand.
    val keepDays: Int = 0,
) {
    // Whether a song can really be taken out of the library now.
    val canRemove: Boolean get() = enabled && allowed && !dryRun && LIBRARY_ACTION_REMOVE in actions
}

// How one action went, as the server tells it.
enum class LibraryActionState(val wire: String) {
    // Done: the file is out of the library.
    Applied("applied"),

    // Only rehearsed; nothing moved.
    Rehearsed("rehearsed"),

    // Not done, and never will be as asked: off, not allowed, or done already.
    Skipped("skipped"),

    // The server could not tell which file the song is, so it touched nothing.
    Unresolved("unresolved"),

    // It tried and could not.
    Failed("failed"),

    // A state this app does not know yet, from a newer server.
    Unknown(""),
    ;

    companion object {
        fun of(text: String?): LibraryActionState {
            val wire = text?.trim()?.lowercase().orEmpty()
            return entries.firstOrNull { it != Unknown && it.wire == wire } ?: Unknown
        }
    }
}

@Serializable
data class LibraryActionResult(
    val id: String = "",
    val action: String = "",
    // As the server says it; `outcome` is what it means.
    val state: String = "",
    // The server's own words about what happened.
    val detail: String? = null,
) {
    val outcome: LibraryActionState get() = LibraryActionState.of(state)
}
