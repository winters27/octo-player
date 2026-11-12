@file:OptIn(UnstableApi::class)

package app.winters.octo.playback

import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi

// What a car's choice (or a spoken request) comes to: the items Media3 is
// to set, where to start and how far in; the words it is refused with; or
// nothing, a newer choice having come while it waited.
sealed interface CarStarted {
    data class Items(val items: List<MediaItem>, val start: Int, val positionMs: Long) : CarStarted

    data class Refused(val words: String) : CarStarted

    data object Superseded : CarStarted
}

// A car's choice as the queue plays a list, for Media3 to set: its start
// read with no write, the whole list when it is small, else the song it
// starts with, laid out in queue order. The queue's rows are written
// meanwhile, and the queue is taken up as the one in use when Media3 sets
// these items; a big list's rest is written behind. Refused with the
// play's own words when nothing in the list plays; dropped when a newer
// choice came while it waited. A choice keeps the player's shuffle, read
// at the tap: with it on, the chosen song plays first and every other
// shuffled; a spoken request (`keepsShuffle` false) plays as it says.
internal suspend fun carStart(window: QueueWindow, guard: PlayGuard, play: CarPlay, mark: PlayMark, keepsShuffle: Boolean = true): CarStarted {
    val shuffle = play.shuffle || (keepsShuffle && window.shuffleOn)
    val request = PlayRequest(play.spec, play.start, shuffle, pageOwner = play.pageOwner, shownOverWindow = play.shownOverWindow, word = play.source.encoded())
    return window.playForMedia3(request, guard, mark, play.emptyWords)
}
