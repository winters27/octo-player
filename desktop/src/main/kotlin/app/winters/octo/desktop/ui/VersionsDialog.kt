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
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.FirstBaseline
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
import app.winters.octo.design.OctoType
import app.winters.octo.design.RowHeight
import app.winters.octo.design.Space
import app.winters.octo.design.Spinner
import app.winters.octo.design.TextAction
import app.winters.octo.design.Txt
import app.winters.octo.design.WholeTxt
import app.winters.octo.design.hoverLift
import app.winters.octo.design.motionScale
import app.winters.octo.design.octoTween
import app.winters.octo.design.scrollFades
import app.winters.octo.desktop.AppState
import app.winters.octo.desktop.pages.FormType
import app.winters.octo.desktop.pages.RowAction
import app.winters.octo.desktop.pages.capsMiddle
import app.winters.octo.desktop.player.QueueEntry
import app.winters.octo.desktop.versions.streamVersionTaken
import app.winters.octo.subsonic.PLAYS_FROM_STREAM
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
// Its top holds while it loads and picks, and Done takes the keyboard
// first, so no row looks chosen beside the one playing.
fun showStreamVersions(app: AppState, entry: QueueEntry) {
    val done = FocusRequester()
    app.popups.showCentred(width = 480.dp, scrim = true, scrollsItself = true, first = done, holdTop = true) { close ->
        StreamVersionsDialog(app, entry, close, done)
    }
}

// The dialog: the title with the song and Done; the version playing (accent
// fill and a check) and the others, which scroll between the title and the
// footer with a fade and "More below" when they do not all fit; the footer's
// note and, while a person's pick stands, the way back to Octo's pick. Its
// states are the shared ones (StreamVersionsSheetState), so it behaves and
// speaks as the phones do.
@Composable
private fun ColumnScope.StreamVersionsDialog(app: AppState, entry: QueueEntry, close: () -> Unit, doneFocus: FocusRequester) {
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

    VersionsContent(
        viewOf(state),
        StreamVersionWords.songLine(song.title, song.artist.orEmpty()),
        done = close,
        retry = {
            val next = state.on(StreamVersionsEvent.Retry)
            if (next !== state) {
                state = next
                asks++
            }
        },
        pick = { choose(it) },
        undo = { choose(null) },
        doneFocus = doneFocus,
    )
}

// The dialog as its view draws it, with what each control does.
@Composable
internal fun ColumnScope.VersionsContent(
    view: VersionsView,
    songLine: String,
    done: () -> Unit,
    retry: () -> Unit,
    pick: (String) -> Unit,
    undo: () -> Unit,
    doneFocus: FocusRequester? = null,
) {
    Header(StreamVersionWords.TITLE, songLine, done, doneFocus)
    val scroll = rememberScrollState()
    Box(Modifier.weight(1f, fill = false)) {
        Column(
            Modifier
                .fillMaxWidth()
                .scrollFades(scroll, 24.dp, eased = true)
                .verticalScroll(scroll)
                .padding(horizontal = Space.Xs, vertical = Space.Xs),
            verticalArrangement = Arrangement.spacedBy(Space.Xxs),
        ) {
            view.playing?.let { playing ->
                SectionName(StreamVersionWords.PLAYING_NOW)
                PlayingRow(playing, view.playingNote.orEmpty())
            }
            // The notice over the list goes under its name; any other line
            // (looking, could not reach, the server's words) stands alone.
            if (!view.listed) view.line?.let { Quiet(it, spinning = view.busy, error = view.error) }
            if (view.retry) TextAction(StreamVersionWords.TRY_AGAIN, retry, Modifier.padding(start = Space.Xs))
            if (view.listed) {
                SectionName(StreamVersionWords.OTHER_VERSIONS)
                view.line?.let { Quiet(it) }
            }
            view.empty?.let { Quiet(it) }
            view.versions.forEach { version ->
                VersionRow(
                    version,
                    checking = view.checking == version.videoId,
                    refused = view.refusal.takeIf { view.refused == version.videoId },
                    enabled = view.checking == null,
                ) { pick(version.videoId) }
            }
        }
        MoreBelow(scroll, Modifier.align(Alignment.BottomCenter))
    }
    if (view.footer) {
        Footer(
            undo = view.undo,
            enabled = view.checking == null,
            undoing = view.checking == STREAM_VERSION_AUTO,
            refused = view.refusal.takeIf { view.refused == STREAM_VERSION_AUTO },
            onUndo = undo,
        )
    }
}

// The dialog as drawn from its state: its line (looking, could not reach,
// the server's own words, or the notice above the list) with a spinner or
// Try again, the version playing and who picked it, the others (or the
// line saying there are none), the row being checked and the one refused
// with the server's words, and whether the way back to Octo's pick shows.
internal data class VersionsView(
    val line: String? = null,
    val busy: Boolean = false,
    // The line says something failed (could not reach, or refused).
    val error: Boolean = false,
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
    // The footer shows: the song streams, so there is a version to choose.
    val footer: Boolean = false,
)

internal fun viewOf(state: StreamVersionsSheetState): VersionsView = when (state) {
    StreamVersionsSheetState.Closed -> VersionsView()
    is StreamVersionsSheetState.Looking -> VersionsView(line = StreamVersionWords.stateLine(state), busy = true)
    is StreamVersionsSheetState.CouldNotReach -> VersionsView(line = StreamVersionWords.stateLine(state), error = true, retry = true)
    is StreamVersionsSheetState.Unavailable -> VersionsView(line = StreamVersionWords.stateLine(state), error = true)
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
        footer = state.answer.playsFrom == PLAYS_FROM_STREAM,
    )
}

// The title, whole and as loud as a form sheet's, with Done on the middle
// of its capitals, and the song under it in muted words.
@Composable
private fun Header(title: String, song: String, done: () -> Unit, doneFocus: FocusRequester?) {
    val titleCaps = capsMiddle(DesktopType.pageTitle)
    val doneCaps = capsMiddle(OctoType.label)
    Column(
        Modifier.fillMaxWidth().padding(start = Space.L, end = Space.S, top = Space.M, bottom = Space.S),
        verticalArrangement = Arrangement.spacedBy(Space.Xxs),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.M)) {
            WholeTxt(
                title,
                DesktopType.pageTitle,
                OctoColors.TextPrimary,
                Modifier.weight(1f).alignBy { it[FirstBaseline] - titleCaps }.semantics { heading() },
            )
            TextAction(
                StreamVersionWords.DONE,
                done,
                Modifier.alignBy { it[FirstBaseline] - doneCaps }.then(if (doneFocus != null) Modifier.focusRequester(doneFocus) else Modifier),
            )
        }
        Txt(song, DesktopType.body, OctoColors.TextMuted, maxLines = 2)
    }
}

// A group's name, set as the form groups' titles are.
@Composable
private fun SectionName(text: String) {
    Txt(text, FormType.group, OctoColors.TextPrimary, Modifier.padding(start = Space.M, top = Space.L, bottom = Space.Xs).semantics { heading() }, maxLines = 2)
}

// A line in place of rows: a spinner and words while it waits, else a note
// with its mark (an error's in the error words), the mark on the middle of
// the first line's capitals.
@Composable
private fun Quiet(text: String, spinning: Boolean = false, error: Boolean = false) {
    if (spinning) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = Space.M, vertical = Space.S),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.S),
        ) {
            Spinner()
            Txt(text, FormType.note, OctoColors.TextSecondary, maxLines = 4)
        }
        return
    }
    val caps = capsMiddle(FormType.note)
    val ink = if (error) OctoColors.ErrorWords else OctoColors.TextSecondary
    Row(
        Modifier.fillMaxWidth().padding(horizontal = Space.M, vertical = Space.S),
        horizontalArrangement = Arrangement.spacedBy(Space.M),
    ) {
        Glyph(
            if (error) OctoIcons.Error else OctoIcons.Info,
            Modifier.alignBy { it.measuredHeight / 2 + caps },
            size = IconSize.Table,
            tint = if (error) ink else OctoColors.Accent,
        )
        Txt(text, FormType.note, ink, Modifier.weight(1f).alignBy(FirstBaseline), maxLines = Int.MAX_VALUE)
    }
}

// The version playing: accent fill and a check, never a button. Under it,
// who picked it, and that it plays from the listener's next start when the
// play they hear is another (`note`, StreamVersionWords.playingNote).
@Composable
private fun PlayingRow(playing: StreamVersionPlaying, note: String) {
    val caps = capsMiddle(DesktopType.tableTitle)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = RowHeight.Roomy)
            .background(OctoColors.AccentSelected, Corner.RowShape)
            .semantics(mergeDescendants = true) { selected = true }
            .padding(horizontal = Space.M, vertical = Space.S),
        horizontalArrangement = Arrangement.spacedBy(Space.M),
    ) {
        Column(Modifier.weight(1f).alignBy(FirstBaseline), verticalArrangement = Arrangement.spacedBy(Space.Xxs)) {
            Txt(playing.title ?: playing.videoId, DesktopType.tableTitle, OctoColors.SelectedWords, maxLines = 2)
            Txt(StreamVersionWords.rowDetail(playing.channel, playing.duration, playing.kind), DesktopType.meta, OctoColors.SelectedWords)
            Txt(note, DesktopType.label, OctoColors.SelectedWords, maxLines = 2)
        }
        Box(Modifier.size(MarkSlot).alignBy { it.measuredHeight / 2 + caps }, contentAlignment = Alignment.Center) {
            Glyph(OctoIcons.Check, size = IconSize.Table, tint = OctoColors.SelectedWords)
        }
    }
}

// Another version: its title (two lines at most) and "channel · length ·
// kind", or "Checking" in that line's place while it is checked, with a
// spinner in the slot the playing row's check takes, so nothing moves;
// refused, the server's words under it as an error. While another pick is
// checked it waits, dimmed.
@Composable
private fun VersionRow(version: StreamVersion, checking: Boolean, refused: String?, enabled: Boolean, onClick: () -> Unit) {
    val caps = capsMiddle(DesktopType.tableTitle)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = RowHeight.Roomy)
            .alpha(if (enabled || checking) 1f else WaitingAlpha)
            .hoverLift(Corner.RowShape, clickable = enabled)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = Space.M, vertical = Space.S),
        horizontalArrangement = Arrangement.spacedBy(Space.M),
    ) {
        Column(Modifier.weight(1f).alignBy(FirstBaseline), verticalArrangement = Arrangement.spacedBy(Space.Xxs)) {
            Txt(version.title, DesktopType.tableTitle, OctoColors.TextPrimary, maxLines = 2)
            if (checking) {
                Txt(StreamVersionWords.CHECKING, DesktopType.meta, OctoColors.TextSecondary)
            } else {
                Txt(StreamVersionWords.rowDetail(version.channel, version.duration, version.kind), DesktopType.meta, OctoColors.TextSecondary)
            }
            if (refused != null) Txt(refused, DesktopType.meta, OctoColors.ErrorWords, maxLines = 3)
        }
        Box(Modifier.size(MarkSlot).alignBy { it.measuredHeight / 2 + caps }, contentAlignment = Alignment.Center) {
            if (checking) Spinner()
        }
    }
}

// The sticky footer, a layer of its own under a line: whose the pick is for
// (everyone on the server), and the way back to Octo's pick while a
// person's pick stands, its pill's edge on the note's edge.
@Composable
private fun Footer(undo: Boolean, enabled: Boolean, undoing: Boolean, refused: String?, onUndo: () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(OctoColors.TextPrimary.copy(alpha = 0.12f)))
        Column(
            Modifier
                .fillMaxWidth()
                .background(OctoColors.TextPrimary.copy(alpha = 0.04f))
                .padding(start = Space.L, end = Space.L, top = Space.M, bottom = Space.M),
            verticalArrangement = Arrangement.spacedBy(Space.S),
        ) {
            Txt(StreamVersionWords.NOTE, DesktopType.meta, OctoColors.TextSecondary, maxLines = 3)
            if (undo) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.S)) {
                    RowAction(StreamVersionWords.GO_BACK, onUndo, enabled = enabled)
                    if (undoing) {
                        Spinner()
                        Txt(StreamVersionWords.CHECKING, DesktopType.meta, OctoColors.TextSecondary)
                    }
                }
            }
            if (refused != null) Txt(refused, DesktopType.meta, OctoColors.ErrorWords, maxLines = 3)
        }
    }
}

// The slot at a row's end the playing row's check and a checked row's
// spinner share, so their right edges and middles meet.
private val MarkSlot = 18.dp

// How far a row waiting for another pick's answer dims.
private const val WaitingAlpha = 0.5f

// A "More below" button over the end of rows that scroll (which fade at
// their edges), scrolling to their end; gone once the end shows.
@Composable
private fun MoreBelow(scroll: ScrollState, modifier: Modifier) {
    val scope = rememberCoroutineScope()
    val motion = motionScale()
    AnimatedVisibility(scroll.canScrollForward, modifier, enter = fadeIn(octoTween(motion, OctoDuration.Card)), exit = fadeOut(octoTween(motion, OctoDuration.Card))) {
        Box(
            Modifier.fillMaxWidth(),
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
