package app.winters.octo.player

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.winters.octo.catalog.matchKey
import app.winters.octo.design.GlassInput
import app.winters.octo.design.GlassPopup
import app.winters.octo.design.GlazeButton
import app.winters.octo.design.GlazeSelected
import app.winters.octo.design.OctoColors
import app.winters.octo.design.OctoIcons
import app.winters.octo.design.OctoType
import app.winters.octo.lyrics.CandidateOrigin
import app.winters.octo.lyrics.CandidateSearch
import app.winters.octo.lyrics.LyricsCandidate
import app.winters.octo.lyrics.LyricsChoices
import app.winters.octo.lyrics.LyricsRepository
import app.winters.octo.lyrics.LyricsSong
import app.winters.octo.lyrics.LyricsSource
import app.winters.octo.lyrics.LyricsTiming
import app.winters.octo.lyrics.TIMING_LIMIT_MS
import app.winters.octo.lyrics.candidateList
import app.winters.octo.lyrics.pickOf
import app.winters.octo.lyrics.signedTiming
import app.winters.octo.lyrics.timingLabel
import app.winters.octo.playback.NowPlaying
import app.winters.octo.ui.common.Choice
import app.winters.octo.ui.common.GlassMenuOptions
import app.winters.octo.ui.common.GlassMenuTitle
import app.winters.octo.ui.common.LocalHaze
import app.winters.octo.ui.common.asClock
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

// The lyrics menu's work: the song's timing, hiding its lyrics or showing
// them again, and the lyrics to choose from, found afresh each time the
// chooser opens, with a search by hand.
@HiltViewModel
class LyricsMenuViewModel @Inject constructor(
    private val repository: LyricsRepository,
    private val choices: LyricsChoices,
    private val timing: LyricsTiming,
) : ViewModel() {
    fun offsetFor(trackId: String): Flow<Long> = timing.offsetFor(trackId)

    fun step(trackId: String, steps: Int) {
        viewModelScope.launch { timing.step(trackId, steps) }
    }

    fun resetTiming(trackId: String) {
        viewModelScope.launch { timing.reset(trackId) }
    }

    fun hide(trackId: String) {
        viewModelScope.launch { choices.hide(trackId) }
    }

    fun show(trackId: String) {
        viewModelScope.launch { choices.show(trackId) }
    }

    private val foundNow = MutableStateFlow<FoundLyrics>(FoundLyrics.Looking(""))
    val found: StateFlow<FoundLyrics> = foundNow

    private val searchedNow = MutableStateFlow<SearchedLyrics>(SearchedLyrics.Nothing)
    val searched: StateFlow<SearchedLyrics> = searchedNow

    private var looking: Job? = null
    private var searching: Job? = null

    fun look(song: LyricsSong) {
        looking?.cancel()
        searching?.cancel()
        searchedNow.value = SearchedLyrics.Nothing
        foundNow.value = FoundLyrics.Looking(song.id)
        looking = viewModelScope.launch {
            foundNow.value = try {
                FoundLyrics.Ready(song.id, repository.candidatesFor(song))
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                FoundLyrics.Ready(song.id, CandidateSearch(emptyList(), emptySet(), onlineAllowed = false))
            }
        }
    }

    fun search(query: String) {
        val words = query.trim()
        searching?.cancel()
        if (words.isEmpty()) {
            searchedNow.value = SearchedLyrics.Nothing
            return
        }
        searchedNow.value = SearchedLyrics.Searching
        searching = viewModelScope.launch {
            searchedNow.value = try {
                SearchedLyrics.Ready(repository.searchOnline(words))
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                SearchedLyrics.Failed
            }
        }
    }

    // Uses the candidate for the song from now on. Its timing starts as the
    // new lyrics are written: an offset set for other lyrics would be wrong
    // for these.
    fun choose(trackId: String, candidate: LyricsCandidate) {
        viewModelScope.launch {
            timing.reset(trackId)
            repository.choose(trackId, candidate)
        }
    }
}

// What the chooser found for a song.
sealed interface FoundLyrics {
    val trackId: String

    data class Looking(override val trackId: String) : FoundLyrics
    data class Ready(override val trackId: String, val search: CandidateSearch) : FoundLyrics
}

// What a search by hand found.
sealed interface SearchedLyrics {
    data object Nothing : SearchedLyrics
    data object Searching : SearchedLyrics
    data class Ready(val found: List<LyricsCandidate>) : SearchedLyrics
    data object Failed : SearchedLyrics
}

private val MenuWidth = 280.dp

// What the lyrics menu shows: its options, or the timing control in their place.
private enum class MenuPage { Options, Timing }

// The lyrics menu, a glass card beside the small player's "..." button:
// where the lyrics came from, then other lyrics to choose, the timing, and
// hiding them for this song, or showing them again.
@Composable
fun LyricsMenu(
    visible: Boolean,
    anchor: IntRect?,
    state: LyricsState,
    trackId: String?,
    onDismiss: () -> Unit,
    onChooseOther: () -> Unit,
    model: LyricsMenuViewModel = hiltViewModel(),
) {
    var page by remember { mutableStateOf(MenuPage.Options) }
    LaunchedEffect(visible) {
        if (visible) page = MenuPage.Options
    }
    GlassPopup(visible = visible, anchor = anchor, onDismiss = onDismiss, backdrop = LocalHaze.current, title = "Lyrics") {
        Column(Modifier.width(MenuWidth).animateContentSize(spring(0.9f, 500f)).padding(6.dp)) {
            if (trackId == null) return@Column
            when (page) {
                MenuPage.Options -> MenuOptions(
                    state,
                    trackId,
                    model,
                    onTiming = { page = MenuPage.Timing },
                    onChooseOther = {
                        onDismiss()
                        onChooseOther()
                    },
                    onDismiss = onDismiss,
                )
                MenuPage.Timing -> TimingControl(trackId, model)
            }
        }
    }
}

// Where the lyrics came from, in plain words, at the top of the menu.
private fun sourceLine(state: LyricsState): String = when (state) {
    is LyricsState.Found -> state.lyrics.source.label
    LyricsState.HiddenForSong -> "Lyrics are hidden for this song"
    LyricsState.None -> "No lyrics found for this song"
    LyricsState.Loading, LyricsState.Hidden -> "Looking for lyrics"
}

@Composable
private fun MenuOptions(
    state: LyricsState,
    trackId: String,
    model: LyricsMenuViewModel,
    onTiming: () -> Unit,
    onChooseOther: () -> Unit,
    onDismiss: () -> Unit,
) {
    val offset by remember(trackId) { model.offsetFor(trackId) }.collectAsStateWithLifecycle(0L)
    val lyrics = (state as? LyricsState.Found)?.lyrics
    val entries = buildList<Pair<Choice, () -> Unit>> {
        if (state == LyricsState.HiddenForSong) {
            add(
                Choice("Show lyrics again") to {
                    model.show(trackId)
                    onDismiss()
                },
            )
            add(Choice("Choose other lyrics") to onChooseOther)
        } else {
            add(Choice(if (lyrics == null) "Find lyrics" else "Choose other lyrics", "Every copy that can be found for this song") to onChooseOther)
            if (lyrics != null && lyrics.synced && !lyrics.instrumental) {
                add(Choice("Adjust timing", if (offset == 0L) "Move the words earlier or later" else signedTiming(offset)) to onTiming)
            }
            if (lyrics != null) {
                add(
                    Choice("Hide lyrics for this song") to {
                        model.hide(trackId)
                        onDismiss()
                    },
                )
            }
        }
    }
    GlassMenuTitle(sourceLine(state))
    GlassMenuOptions(entries.map { it.first }, selected = -1, onPick = { entries[it].second() })
}

// Moves the song's lyrics earlier or later a quarter second a tap, while
// they keep playing behind the menu.
@Composable
private fun TimingControl(trackId: String, model: LyricsMenuViewModel) {
    val offset by remember(trackId) { model.offsetFor(trackId) }.collectAsStateWithLifecycle(0L)
    GlassMenuTitle("Lyrics timing")
    Text(
        signedTiming(offset),
        style = OctoType.headline.copy(fontFeatureSettings = "tnum"),
        color = OctoColors.TextPrimary,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .semantics {
                liveRegion = LiveRegionMode.Polite
                contentDescription = timingLabel(offset)
            },
    )
    Text(
        "For this song only. If the words light up late, tap Earlier. If early, tap Later.",
        style = OctoType.caption,
        color = OctoColors.TextMuted,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, bottom = 12.dp),
    )
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        GlazeButton("Earlier", onClick = { model.step(trackId, -1) }, modifier = Modifier.weight(1f), enabled = offset > -TIMING_LIMIT_MS)
        GlazeButton("Later", onClick = { model.step(trackId, 1) }, modifier = Modifier.weight(1f), enabled = offset < TIMING_LIMIT_MS)
    }
    Spacer(Modifier.height(8.dp))
    if (offset != 0L) {
        GlassMenuOptions(listOf(Choice("Reset", "Back to the timing the lyrics came with")), selected = -1, onPick = { model.resetTiming(trackId) })
    }
}

private val RowShape = RoundedCornerShape(14.dp)
private val LineColour = OctoColors.TextPrimary.copy(alpha = 0.08f)

// The lyrics to choose from for the song playing, as the lines of a glass
// sheet: a search by hand at the top, for a song whose title or artist is
// filed wrong, then the lyrics showing now in the darker pill, then every
// other copy found. Each says where it is from, what the online library
// calls it, and its first lines. Picking one uses it for this song from
// now on and closes the sheet.
@Composable
fun ColumnScope.LyricsChooser(now: NowPlaying, state: LyricsState, onDone: () -> Unit, model: LyricsMenuViewModel = hiltViewModel()) {
    val trackId = now.trackId ?: return
    LaunchedEffect(trackId) {
        model.look(LyricsSong(trackId, now.title.orEmpty(), now.artist.orEmpty(), now.album.orEmpty(), now.durationMs))
    }
    val found by model.found.collectAsStateWithLifecycle()
    val searched by model.searched.collectAsStateWithLifecycle()
    var query by rememberSaveable(trackId) { mutableStateOf("") }
    val focus = LocalFocusManager.current
    val showing = (state as? LyricsState.Found)?.lyrics
    val pick: (String, LyricsCandidate, Boolean) -> Unit = { song, candidate, isShowing ->
        if (!isShowing) model.choose(song, candidate)
        onDone()
    }
    val side = Modifier.padding(horizontal = 16.dp)

    Text("Choose lyrics", style = OctoType.section, color = OctoColors.TextPrimary, modifier = side.padding(horizontal = 8.dp))
    Text(
        listOf(now.title, now.artist).filterNot { it.isNullOrBlank() }.joinToString(" by "),
        style = OctoType.caption,
        color = OctoColors.TextMuted,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = side.padding(start = 8.dp, end = 8.dp, bottom = 12.dp),
    )

    val ready = found as? FoundLyrics.Ready
    val onlineAllowed = ready?.search?.onlineAllowed ?: true
    if (onlineAllowed) {
        GlassInput(
            value = query,
            onValueChange = { query = it },
            placeholder = "Search LRCLIB by title or artist",
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = {
                focus.clearFocus()
                model.search(query)
            }),
            modifier = side,
            trailing = {
                Box(
                    Modifier
                        .size(40.dp)
                        .clickable(interactionSource = null, indication = null, role = Role.Button) {
                            focus.clearFocus()
                            model.search(query)
                        }
                        .semantics { contentDescription = "Search" },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(painterResource(OctoIcons.Search), contentDescription = null, tint = OctoColors.TextSecondary, modifier = Modifier.size(20.dp))
                }
            },
        )
        when (val result = searched) {
            SearchedLyrics.Nothing -> Unit
            SearchedLyrics.Searching -> QuietLine("Searching LRCLIB", side)
            SearchedLyrics.Failed -> QuietLine("Could not reach LRCLIB", side)
            is SearchedLyrics.Ready -> {
                SectionTitle("Search results", side)
                if (result.found.isEmpty()) QuietLine("Nothing found for that", side)
                result.found.forEachIndexed { index, candidate ->
                    val isShowing = showing != null && candidate.pick == pickOf(showing)
                    if (index > 0) Hairline(side)
                    CandidateRow(candidate, isShowing, now, byHand = true, modifier = side) { pick(trackId, candidate, isShowing) }
                }
            }
        }
    } else {
        QuietLine("Online lyrics are off in Settings, so only your server and this phone are searched.", side)
    }

    SectionTitle("For this song", side)
    when (val result = found) {
        is FoundLyrics.Looking -> QuietLine("Looking for lyrics", side)
        is FoundLyrics.Ready -> {
            if (result.trackId != trackId) return
            val current = showing?.let(::pickOf)
            val list = remember(result, current, showing) { candidateList(result.search.found, current, showing) }
            if (list.items.isEmpty()) QuietLine("No lyrics found for this song", side)
            list.items.forEachIndexed { index, candidate ->
                val isShowing = index == list.showing
                // No line touches the darker pill.
                if (index > 0) Hairline(side, drawn = index - 1 != list.showing)
                CandidateRow(candidate, isShowing, now, byHand = false, modifier = side) { pick(trackId, candidate, isShowing) }
            }
            missedLine(result.search.missed)?.let { QuietLine(it, side) }
        }
    }
    // Room for the keyboard, so the search stays in view above it.
    Spacer(Modifier.imePadding().height(8.dp))
}

// Which sources could not be reached this time, in plain words.
private fun missedLine(missed: Set<LyricsSource>): String? {
    val names = buildList {
        if (LyricsSource.Server in missed) add("your server")
        if (LyricsSource.Online in missed) add("LRCLIB")
        if (LyricsSource.SongFile in missed || LyricsSource.LyricsFile in missed) add("the song's files")
    }
    return if (names.isEmpty()) null else "Could not reach ${names.joinToString(" or ")} this time."
}

// One set of lyrics to choose: where it is from, what it is, and its
// first two lines. The one showing now sits in the darker pill with a check.
@Composable
private fun CandidateRow(
    candidate: LyricsCandidate,
    showing: Boolean,
    now: NowPlaying,
    byHand: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val detail = candidateDetail(candidate, now, byHand)
    Box(
        modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clip(RowShape)
            .clickable(role = Role.Button, onClickLabel = if (showing) "Keep these lyrics" else "Use these lyrics", onClick = onClick)
            .semantics { if (showing) stateDescription = "Showing now" },
        contentAlignment = Alignment.CenterStart,
    ) {
        if (showing) GlazeSelected(Modifier.matchParentSize(), shape = RowShape)
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(originLabel(candidate.origin), style = OctoType.bodySmall, color = OctoColors.TextPrimary)
                if (detail.isNotEmpty()) {
                    Text(detail, style = OctoType.caption, color = OctoColors.TextMuted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                val preview = if (candidate.lyrics.instrumental) listOf("Instrumental") else candidate.preview
                if (preview.isNotEmpty()) {
                    Text(
                        preview.joinToString("\n"),
                        style = OctoType.caption,
                        color = OctoColors.TextSecondary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
            if (showing) {
                Icon(painterResource(OctoIcons.Check), contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
            }
        }
    }
}

private fun originLabel(origin: CandidateOrigin): String = when (origin) {
    CandidateOrigin.Server -> LyricsSource.Server.label
    CandidateOrigin.SongFile -> LyricsSource.SongFile.label
    CandidateOrigin.LyricsFile -> LyricsSource.LyricsFile.label
    CandidateOrigin.OnlineMatch, CandidateOrigin.OnlineSearch -> LyricsSource.Online.label
}

// What a candidate is: for an online copy, its title and artist when they
// differ from the song's (or it came from a search by hand), its album and
// length; then whether it is timed.
private fun candidateDetail(candidate: LyricsCandidate, now: NowPlaying, byHand: Boolean): String {
    val parts = mutableListOf<String>()
    val online = candidate.origin == CandidateOrigin.OnlineMatch || candidate.origin == CandidateOrigin.OnlineSearch
    if (online) {
        val otherName = byHand || matchKey(candidate.title) != matchKey(now.title.orEmpty())
        if (otherName && candidate.title.isNotBlank()) {
            parts += if (candidate.artist.isNotBlank()) "${candidate.title} by ${candidate.artist}" else candidate.title
        }
        if (candidate.album.isNotBlank()) parts += candidate.album
        if (candidate.durationMs >= 1_000) parts += (candidate.durationMs / 1_000).toInt().asClock()
    }
    when {
        candidate.lyrics.instrumental -> Unit
        candidate.lyrics.synced -> parts += "Timed"
        else -> parts += "Not timed"
    }
    return parts.joinToString(" · ")
}

@Composable
private fun SectionTitle(text: String, modifier: Modifier) {
    Text(
        text,
        style = OctoType.label,
        color = OctoColors.TextSecondary,
        modifier = modifier.padding(start = 14.dp, end = 14.dp, top = 16.dp, bottom = 6.dp),
    )
}

@Composable
private fun QuietLine(text: String, modifier: Modifier) {
    Text(
        text,
        style = OctoType.caption,
        color = OctoColors.TextMuted,
        modifier = modifier.padding(horizontal = 14.dp, vertical = 10.dp),
    )
}

// A 1dp line between rows, kept (undrawn) beside the darker pill so the
// rows never shift.
@Composable
private fun Hairline(modifier: Modifier, drawn: Boolean = true) {
    Box(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp)
            .height(1.dp)
            .background(if (drawn) LineColour else Color.Transparent),
    )
}
