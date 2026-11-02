package app.winters.octo.playback

// The queue and the account as a play that waits on the server found them.
data class PlayMark(val replacements: Long, val account: String?)

// Whether a play that waited on the server is still wanted: nothing replaced
// the queue meanwhile (`replacements` counts that), and the same account is
// in use. A later choice always wins over an earlier one still on its way.
class PlayGuard(private val replacements: () -> Long, private val account: () -> String?) {
    fun mark(): PlayMark = PlayMark(replacements(), account())

    fun stillWanted(mark: PlayMark): Boolean = replacements() == mark.replacements && account() == mark.account
}
