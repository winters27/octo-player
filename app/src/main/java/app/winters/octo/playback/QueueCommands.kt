package app.winters.octo.playback

import android.os.Bundle
import androidx.media3.session.SessionCommand

// The queue's commands from the app to the service: a list played or added
// by what it is, and Up next's edits by entry. The app never adds items to
// the player itself, so nothing shows under a request's id.
internal val PLAY_LIST = SessionCommand("app.winters.octo.PLAY_LIST", Bundle.EMPTY)
internal val ADD_NEXT = SessionCommand("app.winters.octo.ADD_NEXT", Bundle.EMPTY)
internal val ADD_LAST = SessionCommand("app.winters.octo.ADD_LAST", Bundle.EMPTY)
internal val PLAY_ENTRY = SessionCommand("app.winters.octo.PLAY_ENTRY", Bundle.EMPTY)
internal val MOVE_ENTRY = SessionCommand("app.winters.octo.MOVE_ENTRY", Bundle.EMPTY)
internal val REMOVE_ENTRIES = SessionCommand("app.winters.octo.REMOVE_ENTRIES", Bundle.EMPTY)

// Plays the entry on (ARG_ENTRY_KEY, its key as the player's item carries it)
// again from its start as a new play, after a version was picked for its
// song. Taken only from the app itself.
internal val RESTART_FRESH = SessionCommand("app.winters.octo.RESTART_FRESH", Bundle.EMPTY)

internal const val ARG_SPEC = "spec"
internal const val ARG_START = "start"
internal const val ARG_SHUFFLE = "shuffle"
internal const val ARG_SOURCE = "source"
internal const val ARG_WORD = "word"
internal const val ARG_PLAY = "play"
internal const val ARG_PAGE_OWNER = "pageOwner"
internal const val ARG_SHOWN_OVER = "shownOverWindow"
internal const val ARG_TOKEN = "token"
internal const val ARG_ENTRY = "entry"
internal const val ARG_BEFORE = "before"
internal const val ARG_ENTRIES = "entries"
internal const val ARG_ENTRY_KEY = "entryKey"
internal const val ARG_LIST = "list"
internal const val ARG_COUNT = "count"
internal const val ARG_STARTED = "started"
internal const val ARG_REFUSAL = "refusal"
internal const val ARG_CUT = "cut"

internal fun playListArgs(
    spec: ListSpec,
    start: ListStart?,
    shuffle: Boolean,
    source: String?,
    playWhenReady: Boolean,
    pageOwner: String?,
    shownOverWindow: Boolean,
    token: Long,
    word: String?,
) = Bundle().apply {
    putString(ARG_SPEC, spec.toJson())
    start?.let { putString(ARG_START, it.toJson()) }
    putBoolean(ARG_SHUFFLE, shuffle)
    source?.let { putString(ARG_SOURCE, it) }
    word?.let { putString(ARG_WORD, it) }
    putBoolean(ARG_PLAY, playWhenReady)
    pageOwner?.let { putString(ARG_PAGE_OWNER, it) }
    putBoolean(ARG_SHOWN_OVER, shownOverWindow)
    putLong(ARG_TOKEN, token)
}

// A play as the command carries it; null when it cannot be read.
internal fun playRequestOf(args: Bundle): PlayRequest? = runCatching {
    PlayRequest(
        spec = ListSpec.fromJson(args.getString(ARG_SPEC)!!),
        start = args.getString(ARG_START)?.let(ListStart::fromJson),
        shuffle = args.getBoolean(ARG_SHUFFLE),
        source = args.getString(ARG_SOURCE),
        playWhenReady = args.getBoolean(ARG_PLAY, true),
        pageOwner = args.getString(ARG_PAGE_OWNER),
        shownOverWindow = args.getBoolean(ARG_SHOWN_OVER),
        token = args.getLong(ARG_TOKEN).takeIf { it > 0 },
        word = args.getString(ARG_WORD),
    )
}.getOrNull()

internal fun addListArgs(spec: ListSpec, source: String?, shownOverWindow: Boolean) = Bundle().apply {
    putString(ARG_SPEC, spec.toJson())
    source?.let { putString(ARG_SOURCE, it) }
    putBoolean(ARG_SHOWN_OVER, shownOverWindow)
}

internal fun answerExtras(answer: PlayAnswer) = Bundle().apply {
    putBoolean(ARG_STARTED, answer.started)
    putBoolean(ARG_CUT, answer.cut)
    answer.refusal?.let { putString(ARG_REFUSAL, it) }
}

internal fun addedExtras(added: Added?) = Bundle().apply {
    putLong(ARG_LIST, added?.listId ?: 0)
    putInt(ARG_COUNT, added?.count ?: 0)
}
