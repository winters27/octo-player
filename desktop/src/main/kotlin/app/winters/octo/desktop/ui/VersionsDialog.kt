package app.winters.octo.desktop.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.winters.octo.design.Corner
import app.winters.octo.design.DesktopType
import app.winters.octo.design.Glyph
import app.winters.octo.design.IconSize
import app.winters.octo.design.OctoColors
import app.winters.octo.design.OctoDuration
import app.winters.octo.design.OctoIcons
import app.winters.octo.design.RowHeight
import app.winters.octo.design.Space
import app.winters.octo.design.Spinner
import app.winters.octo.design.TextAction
import app.winters.octo.design.Txt
import app.winters.octo.design.hoverLift
import app.winters.octo.design.motionScale
import app.winters.octo.design.octoTween
import app.winters.octo.desktop.AppState
import app.winters.octo.desktop.player.QueueEntry
import app.winters.octo.desktop.versions.streamVersionTaken
import app.winters.octo.subsonic.STREAM_VERSION_AUTO
import app.winters.octo.subsonic.StreamVersion
import app.winters.octo.subsonic.StreamVersionPlaying
import app.winters.octo.subsonic.SubsonicException
import app.winters.octo.versions.StreamVersionWords
import app.winters.octo.versions.StreamVersionsEvent
import app.winters.octo.versions.StreamVersionsSheetState
import app.winters.octo.versions.VersionsSong
import app.winters.octo.versions.on
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

// Opens "Choose the version" for the entry playing: the YouTube videos the
// server offers for a song found online, the one playing marked, a pick
// playing at once from its start. It closes once another entry plays.
fun showStreamVersions(app: AppState, entry: QueueEntry) {
    app.popups.showCentred(width = 480.dp, scrim = true, scrollsItself = true) { close -> StreamVersionsDialog(app, entry, close) }
}

// The dialog: the title with the song and Done; the version playing (accent
// fill and a check) and the others, which scroll between the title and the
// footer with a fade and "More below" when they do not all fit; the footer's
// note and, while a person's pick stands, the way back to Octo's pick. Its
// states are the shared ones (StreamVersionsSheetState), so it behaves and
// speaks as the phones do.
@Composable
private fun ColumnScope.StreamVersionsDialog(app: AppState, entry: QueueEntry, close: () -> Unit) {
    val now by app.player.state.collectAsState()
    val song = entry.song
    val opened = remember(entry.key) { VersionsSong(entry.key.toString(), song.id, song.title, song.artist.orEmpty()) }
    var state by remember { mutableStateOf<StreamVersionsSheetState>(StreamVersionsSheetState.Closed.on(StreamVersionsEvent.Open(opened))) }
    var asks by remember { mutableIntStateOf(0) }
    // Another entry plays: the state closes, and the dialog with it.
    LaunchedEffect(now.current?.key) {
        val next = state.on(StreamVersionsEvent.EntryChanged(now.current?.key?.toString()))
        if (next is StreamVersionsSheetState.Closed) {
            state = next
            close()
        }
    }
    LaunchedEffect(asks) {
        val client = app.connection?.client
        val event = if (client == null) {
            StreamVersionsEvent.ListFailed(null)
        } else {
            try {
                StreamVersionsEvent.Listed(client.streamVersions(song.id))
            } catch (e: CancellationException) {
                throw e
            } catch (e: SubsonicException.Unreachable) {
                StreamVersionsEvent.ListFailed(null)
            } catch (e: SubsonicException.NotSubsonic) {
                StreamVersionsEvent.ListFailed(null)
            } catch (e: SubsonicException) {
                StreamVersionsEvent.ListFailed(e.message ?: StreamVersionWords.SERVER_UNREACHABLE)
            } catch (e: Exception) {
                StreamVersionsEvent.ListFailed(null)
            }
        }
        state = state.on(event)
    }

    // A pick (`video`) or the way back (null) the state takes goes to the
    // server, on the window's own scope, so its answer is taken (what was
    // kept of the old file dropped, the song restarted) even when the dialog
    // closed meanwhile.
    fun choose(video: String?) {
        val client = app.connection?.client ?: return
        val playing = app.player.state.value.current
        val entryId = playing?.key?.toString()
        val event = if (video == null) {
            StreamVersionsEvent.Undo(entryId, playing?.song?.id)
        } else {
            StreamVersionsEvent.Pick(video, entryId, playing?.song?.id)
        }
        val asked = state.on(event)
        if (asked === state) return
        state = asked
        app.scope.launch {
            val answer = try {
                client.setStreamVersion(song.id, video ?: STREAM_VERSION_AUTO)
            } catch (e: CancellationException) {
                throw e
            } catch (e: SubsonicException.Unreachable) {
                state = state.on(StreamVersionsEvent.PickFailed(StreamVersionWords.PICK_UNREACHABLE))
                return@launch
            } catch (e: SubsonicException.NotSubsonic) {
                state = state.on(StreamVersionsEvent.PickFailed(StreamVersionWords.PICK_UNREACHABLE))
                return@launch
            } catch (e: SubsonicException) {
                state = state.on(StreamVersionsEvent.PickFailed(e.message ?: StreamVersionWords.PICK_UNREACHABLE))
                return@launch
            } catch (e: Exception) {
                state = state.on(StreamVersionsEvent.PickFailed(StreamVersionWords.PICK_UNREACHABLE))
                return@launch
            }
            state = state.on(StreamVersionsEvent.Picked(answer))
            app.streamVersionTaken(entry, answer)
        }
    }

    val view = viewOf(state)
    Header(StreamVersionWords.TITLE, StreamVersionWords.songLine(song.title, song.artist.orEmpty()), close)
    val scroll = rememberScrollState()
    Box(Modifier.weight(1f, fill = false)) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(scroll).padding(horizontal = Space.S, vertical = Space.Xs),
            verticalArrangement = Arrangement.spacedBy(Space.Xxs),
        ) {
            view.playing?.let { playing ->
                SectionName(StreamVersionWords.PLAYING_NOW)
                PlayingRow(playing, view.playingNote.orEmpty())
            }
            view.line?.let { Quiet(it, spinning = view.busy) }
            if (view.retry) {
                TextAction(StreamVersionWords.TRY_AGAIN, {
                    val next = state.on(StreamVersionsEvent.Retry)
                    if (next !== state) {
                        state = next
                        asks++
                    }
                }, Modifier.padding(start = Space.Xs))
            }
            if (view.listed) SectionName(StreamVersionWords.OTHER_VERSIONS)
            view.empty?.let { Quiet(it) }
            view.versions.forEach { version ->
                VersionRow(
                    version,
                    checking = view.checking == version.videoId,
                    refused = view.refusal.takeIf { view.refused == version.videoId },
                    enabled = view.checking == null,
                ) { choose(version.videoId) }
            }
        }
        MoreBelow(scroll, Modifier.align(Alignment.BottomCenter))
    }
    Footer(
        undo = view.undo,
        enabled = view.checking == null,
        undoing = view.checking == STREAM_VERSION_AUTO,
        refused = view.refusal.takeIf { view.refused == STREAM_VERSION_AUTO },
    ) { choose(null) }
}

// The dialog as drawn from its state: its line (looking, could not reach,
// the server's own words, or the notice above the list) with a spinner or
// Try again, the version playing and who picked it, the others (or the
// line saying there are none), the row being checked and the one refused
// with the server's words, and whether the way back to Octo's pick shows.
internal data class VersionsView(
    val line: String? = null,
    val busy: Boolean = false,
    val retry: Boolean = false,
    val listed: Boolean = false,
    val playing: StreamVersionPlaying? = null,
    val playingNote: String? = null,
    val versions: List<StreamVersion> = emptyList(),
    val empty: String? = null,
    val checking: String? = null,
    val refused: String? = null,
    val refusal: String? = null,
    val undo: Boolean = false,
)

internal fun viewOf(state: StreamVersionsSheetState): VersionsView = when (state) {
    StreamVersionsSheetState.Closed -> VersionsView()
    is StreamVersionsSheetState.Looking -> VersionsView(line = StreamVersionWords.stateLine(state), busy = true)
    is StreamVersionsSheetState.CouldNotReach -> VersionsView(line = StreamVersionWords.stateLine(state), retry = true)
    is StreamVersionsSheetState.Unavailable -> VersionsView(line = StreamVersionWords.stateLine(state))
    is StreamVersionsSheetState.Shown -> VersionsView(
        line = state.notice,
        listed = state.picksOpen,
        playing = state.playing,
        playingNote = state.playing?.let { StreamVersionWords.playingNote(it, state.answer.heardVideoId) },
        versions = state.rows,
        empty = state.emptyText,
        checking = state.checking,
        refused = state.refused,
        refusal = state.refusal,
        undo = state.canUndo,
    )
}

// The title, the song under it in muted words, and Done.
@Composable
private fun Header(title: String, song: String, done: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = Space.L, end = Space.S, top = Space.M, bottom = Space.S),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(Space.M),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.Xxs)) {
            Txt(title, DesktopType.emphasis, OctoColors.TextPrimary, Modifier.semantics { heading() }, maxLines = Int.MAX_VALUE)
            Txt(song, DesktopType.body, OctoColors.TextMuted, maxLines = 2)
        }
        TextAction(StreamVersionWords.DONE, done)
    }
}

@Composable
private fun SectionName(text: String) {
    Txt(text, DesktopType.label, OctoColors.TextSecondary, Modifier.padding(start = Space.M, top = Space.S, bottom = Space.Xxs).semantics { heading() })
}

// A line in place of rows, with a spinner while it waits.
@Composable
private fun Quiet(text: String, spinning: Boolean = false) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = Space.M, vertical = Space.S),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.S),
    ) {
        if (spinning) Spinner()
        Txt(text, DesktopType.body, OctoColors.TextMuted, maxLines = 4)
    }
}

// The version playing: accent fill and a check, never a button. Under it,
// who picked it, and that it plays from the listener's next start when the
// play they hear is another (`note`, StreamVersionWords.playingNote).
@Composable
private fun PlayingRow(playing: StreamVersionPlaying, note: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = RowHeight.Roomy)
            .background(OctoColors.AccentSelected, Corner.RowShape)
            .semantics(mergeDescendants = true) { selected = true }
            .padding(horizontal = Space.M, vertical = Space.S),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.M),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.Xxs)) {
            Txt(playing.title ?: playing.videoId, DesktopType.tableTitle, OctoColors.TextPrimary, maxLines = 2)
            Txt(StreamVersionWords.rowDetail(playing.channel, playing.duration, playing.kind), DesktopType.meta, OctoColors.TextSecondary)
            Txt(note, DesktopType.meta, OctoColors.AccentWords, maxLines = 2)
        }
        Glyph(OctoIcons.Check, size = IconSize.Toolbar, tint = OctoColors.TextPrimary)
    }
}

// Another version: its title (two lines at most) and "channel · length ·
// kind"; while it is checked, a spinner; refused, the server's words under it.
@Composable
private fun VersionRow(version: StreamVersion, checking: Boolean, refused: String?, enabled: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = RowHeight.Roomy)
            .hoverLift(Corner.RowShape, clickable = enabled)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = Space.M, vertical = Space.S),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.M),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.Xxs)) {
            Txt(version.title, DesktopType.tableTitle, OctoColors.TextPrimary, maxLines = 2)
            Txt(StreamVersionWords.rowDetail(version.channel, version.duration, version.kind), DesktopType.meta, OctoColors.TextSecondary)
            if (refused != null) Txt(refused, DesktopType.meta, OctoColors.TextMuted, maxLines = 3)
        }
        if (checking) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.Xs)) {
                Spinner()
                Txt(StreamVersionWords.CHECKING, DesktopType.meta, OctoColors.TextMuted)
            }
        }
    }
}

// The sticky footer: whose the pick is for (everyone on the server), and
// the quiet way back to Octo's pick while a person's pick stands.
@Composable
private fun Footer(undo: Boolean, enabled: Boolean, undoing: Boolean, refused: String?, onUndo: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(start = Space.L, end = Space.L, top = Space.S, bottom = Space.M),
        verticalArrangement = Arrangement.spacedBy(Space.Xs),
    ) {
        Txt(StreamVersionWords.NOTE, DesktopType.meta, OctoColors.TextMuted, maxLines = 3)
        if (undo) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.S)) {
                TextAction(StreamVersionWords.GO_BACK, onUndo, enabled = enabled)
                if (undoing) {
                    Spinner()
                    Txt(StreamVersionWords.CHECKING, DesktopType.meta, OctoColors.TextMuted)
                }
            }
        }
        if (refused != null) Txt(refused, DesktopType.meta, OctoColors.TextMuted, maxLines = 3)
    }
}

// A soft fade at the bottom of rows that scroll, with a "More below" button
// that scrolls to their end; gone once the end shows.
@Composable
private fun MoreBelow(scroll: ScrollState, modifier: Modifier) {
    val scope = rememberCoroutineScope()
    val motion = motionScale()
    AnimatedVisibility(scroll.canScrollForward, modifier, enter = fadeIn(octoTween(motion, OctoDuration.Card)), exit = fadeOut(octoTween(motion, OctoDuration.Card))) {
        Box(
            Modifier.fillMaxWidth().height(48.dp).background(Brush.verticalGradient(listOf(Color.Transparent, OctoColors.Background.copy(alpha = 0.94f)))),
            contentAlignment = Alignment.BottomCenter,
        ) {
            Row(
                Modifier
                    .padding(bottom = Space.Xs)
                    .background(OctoColors.AccentSelected, PillShape)
                    .border(1.dp, OctoColors.TextPrimary.copy(alpha = 0.10f), PillShape)
                    .clickable(role = Role.Button, onClickLabel = StreamVersionWords.MORE_BELOW) { scope.launch { scroll.animateScrollTo(scroll.maxValue) } }
                    .padding(horizontal = Space.M, vertical = Space.Xxs),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Space.Xs),
            ) {
                Txt(StreamVersionWords.MORE_BELOW, DesktopType.label, OctoColors.TextPrimary)
                Glyph(OctoIcons.Collapse, size = IconSize.Inline)
            }
        }
    }
}

private val PillShape = RoundedCornerShape(50)
