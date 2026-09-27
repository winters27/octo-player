package app.winters.octo.player

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.LongState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.ResolvedTextDirection
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.winters.octo.design.GlazeButton
import app.winters.octo.design.OctoColors
import app.winters.octo.design.OctoType
import app.winters.octo.lyrics.LyricLine
import app.winters.octo.lyrics.LyricWord
import app.winters.octo.lyrics.Lyrics
import app.winters.octo.lyrics.LyricsLook
import app.winters.octo.lyrics.LyricsLookSettings
import app.winters.octo.lyrics.LyricsStyle
import app.winters.octo.lyrics.LyricsTiming
import app.winters.octo.lyrics.TIMING_LIMIT_MS
import app.winters.octo.lyrics.endOf
import app.winters.octo.lyrics.heardAt
import app.winters.octo.lyrics.lineAt
import app.winters.octo.lyrics.lyricsClock
import app.winters.octo.lyrics.shownLines
import app.winters.octo.lyrics.timingLabel
import app.winters.octo.playback.NowPlaying
import app.winters.octo.playback.PlaybackConnection
import app.winters.octo.ui.common.FloatingSheet
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

// Sizes: the line being sung is bigger than the rest.
private const val SUNG_LINE_SP = 28f
private const val OTHER_LINE_SP = 20f

// How bright the words are: sung, still to sing, and lines not being sung.
private const val SUNG_ALPHA = 0.85f
private const val UNSUNG_ALPHA = 0.5f
private const val RESTING_ALPHA = 0.35f

// Backing vocals sit quieter than the lead.
private const val BACKING_SUNG_ALPHA = 0.55f
private const val BACKING_ALPHA = 0.3f
private const val BACKING_RESTING_ALPHA = 0.2f

// How long a scroll by hand keeps the lyrics from following the song.
private const val FOLLOW_PAUSE_MS = 3_000L

// A rounded bold face would be closer to the look; the default family in
// bold stands in until one is bundled.
private val LyricStyle = TextStyle(fontWeight = FontWeight.Bold, lineHeight = 1.2.em, letterSpacing = (-0.2).sp)

// How lines move as the song reaches them.
private fun <T> lineSpring() = spring<T>(dampingRatio = 0.7f, stiffness = 80f)

// Each song's lyrics timing, whether the screen stays on for lyrics, how
// they look, and the player's clock for the flowing style.
@HiltViewModel
class LyricsTimingViewModel @Inject constructor(
    private val timing: LyricsTiming,
    lookSettings: LyricsLookSettings,
    player: PlayerSettings,
    private val playback: PlaybackConnection,
) : ViewModel() {
    val keepScreenOn: StateFlow<Boolean> = timing.keepScreenOn.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    // Null until read, so the chosen style shows from the first frame.
    val look: StateFlow<LyricsLook?> = lookSettings.look.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val reduceMotion: StateFlow<Boolean> = player.prefs
        .map { it.reduceMotion }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val timeEvents: Flow<Int> = playback.timeEvents

    fun speed(): Float = playback.speed()

    fun offsetFor(trackId: String): Flow<Long> = timing.offsetFor(trackId)

    fun step(trackId: String, steps: Int) {
        viewModelScope.launch { timing.step(trackId, steps) }
    }

    fun reset(trackId: String) {
        viewModelScope.launch { timing.reset(trackId) }
    }
}

// The lyrics, in place of the artwork. Synced lyrics follow the song in
// the chosen style: flowing, or the classic view with the line being sung
// in the middle; a tap on a line plays from there. Plain lyrics are text to
// scroll. Where they came from shows quietly underneath, beside a way to
// fix synced lyrics that run early or late for this one song. The flowing
// lyrics reach `edgeBleed` past the sides, to the screen's own margins.
@Composable
fun LyricsPane(
    state: LyricsState,
    now: NowPlaying,
    positionMs: () -> Long,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
    edgeBleed: Dp = 0.dp,
    timing: LyricsTimingViewModel = hiltViewModel(),
) {
    // The screen stays on while the words are there to read, unless that
    // is switched off in Settings.
    val keepOn by timing.keepScreenOn.collectAsStateWithLifecycle()
    if (keepOn && state is LyricsState.Found) KeepScreenOn()

    Crossfade(targetState = state, animationSpec = tween(300), modifier = modifier, label = "lyrics") { shown ->
        when (shown) {
            is LyricsState.Found -> {
                val lyrics = shown.lyrics
                val offset by remember(shown.trackId) { timing.offsetFor(shown.trackId) }.collectAsStateWithLifecycle(0L)
                val look by timing.look.collectAsStateWithLifecycle()
                val calm by timing.reduceMotion.collectAsStateWithLifecycle()
                var adjusting by remember { mutableStateOf(false) }
                val flowing = lyrics.synced && !lyrics.instrumental && look?.style == LyricsStyle.Flowing
                Column(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(1f).fillMaxWidth().bleed(if (flowing) edgeBleed else 0.dp).fadedEdges()) {
                        val chosen = look
                        when {
                            lyrics.instrumental -> Quiet("Instrumental")
                            // The style is not known yet: nothing, for a moment.
                            lyrics.synced && chosen == null -> Unit
                            lyrics.synced && chosen != null && flowing -> FlowingLyrics(
                                lyrics,
                                now,
                                positionMs,
                                timing::speed,
                                timing.timeEvents,
                                offset,
                                chosen,
                                calm,
                                onSeek,
                            )
                            lyrics.synced -> SyncedLyrics(lyrics, now, positionMs, onSeek, offset)
                            else -> PlainLyrics(lyrics)
                        }
                    }
                    Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            lyrics.source.label,
                            style = OctoType.caption,
                            color = OctoColors.TextMuted,
                            modifier = Modifier.weight(1f),
                        )
                        // A quiet way to move the words when they run early or late.
                        if (lyrics.synced && !lyrics.instrumental) {
                            Text(
                                if (offset == 0L) "Timing" else timingLabel(offset),
                                style = OctoType.caption,
                                color = OctoColors.TextMuted,
                                modifier = Modifier
                                    .clickable(role = Role.Button, onClickLabel = "Change the lyrics timing") { adjusting = true }
                                    .padding(horizontal = 8.dp, vertical = 6.dp),
                            )
                        }
                    }
                }
                FloatingSheet(visible = adjusting, onDismiss = { adjusting = false }) {
                    TimingSheet(
                        offset,
                        onStep = { steps -> timing.step(shown.trackId, steps) },
                        onReset = { timing.reset(shown.trackId) },
                    )
                }
            }
            LyricsState.None -> Quiet("No lyrics for this song")
            LyricsState.Loading, LyricsState.Hidden -> Box(Modifier.fillMaxSize())
        }
    }
}

// Holds the screen on for as long as this is shown.
@Composable
private fun KeepScreenOn() {
    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
}

// Moves one song's lyrics earlier or later, a quarter second at a time.
@Composable
private fun TimingSheet(offsetMs: Long, onStep: (Int) -> Unit, onReset: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 24.dp)) {
        Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Lyrics timing", style = OctoType.section, color = OctoColors.TextPrimary, modifier = Modifier.weight(1f))
            if (offsetMs != 0L) GlazeButton("Reset", onClick = onReset)
        }
        Text(
            timingLabel(offsetMs),
            style = OctoType.headline.copy(fontFeatureSettings = "tnum"),
            color = OctoColors.TextPrimary,
            modifier = Modifier.padding(top = 4.dp).semantics { liveRegion = LiveRegionMode.Polite },
        )
        Text(
            "For this song only. If the words light up late, move them earlier; if early, move them later.",
            style = OctoType.caption,
            color = OctoColors.TextMuted,
            modifier = Modifier.padding(top = 4.dp),
        )
        Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            GlazeButton("Earlier", onClick = { onStep(-1) }, modifier = Modifier.weight(1f), enabled = offsetMs > -TIMING_LIMIT_MS)
            GlazeButton("Later", onClick = { onStep(1) }, modifier = Modifier.weight(1f), enabled = offsetMs < TIMING_LIMIT_MS)
        }
    }
}

@Composable
private fun Quiet(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, style = OctoType.body, color = OctoColors.TextMuted, textAlign = TextAlign.Center)
    }
}

// Reaches `horizontal` past each side of the space it is given.
private fun Modifier.bleed(horizontal: Dp): Modifier = if (horizontal <= 0.dp) {
    this
} else {
    layout { measurable, constraints ->
        val extra = horizontal.roundToPx() * 2
        val wider = if (constraints.hasBoundedWidth) {
            constraints.copy(minWidth = constraints.minWidth + extra, maxWidth = constraints.maxWidth + extra)
        } else {
            constraints
        }
        val placeable = measurable.measure(wider)
        val width = if (constraints.hasBoundedWidth) placeable.width - extra else placeable.width
        layout(width, placeable.height) { placeable.place(-(placeable.width - width) / 2, 0) }
    }
}

// Lines fade out at the top and bottom edges instead of being cut off.
private fun Modifier.fadedEdges() = graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        val edge = (32.dp.toPx() / size.height).coerceAtMost(0.45f)
        drawRect(
            Brush.verticalGradient(
                0f to Color.Transparent,
                edge to Color.Black,
                (1f - edge) to Color.Black,
                1f to Color.Transparent,
            ),
            blendMode = BlendMode.DstIn,
        )
    }

@Composable
private fun PlainLyrics(lyrics: Lyrics) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(vertical = 32.dp),
    ) {
        lyrics.lines.forEach { line ->
            if (line.text.isEmpty()) {
                Spacer(Modifier.height(18.dp))
            } else {
                Text(
                    line.text,
                    style = LyricStyle.copy(fontSize = OTHER_LINE_SP.sp),
                    color = Color.White.copy(alpha = SUNG_ALPHA),
                    modifier = Modifier.padding(vertical = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun SyncedLyrics(lyrics: Lyrics, now: NowPlaying, positionMs: () -> Long, onSeek: (Long) -> Unit, offsetMs: Long) {
    val lines = remember(lyrics) { lyrics.shownLines() }
    // Everything here runs on the lyrics' own clock, which the song's
    // timing offset moves.
    val offset by rememberUpdatedState(offsetMs)
    val clock = remember(positionMs) { { lyricsClock(positionMs(), offset) } }
    val position = rememberPositionMs(now, clock)
    val current by remember(lines) { derivedStateOf { lines.lineAt(position.longValue) } }
    val list = rememberLazyListState()
    var following by remember { mutableStateOf(true) }

    // While paused the position only moves by seeking, which can happen from
    // the progress line too, so it is checked now and then.
    LaunchedEffect(now.isPlaying) {
        while (!now.isPlaying) {
            delay(300)
            position.longValue = clock()
        }
    }

    // A drag stops the lyrics following the song until a few seconds after
    // the finger lifts.
    LaunchedEffect(list) {
        var resume: Job? = null
        list.interactionSource.interactions.collect { interaction ->
            when (interaction) {
                is DragInteraction.Start -> {
                    resume?.cancel()
                    following = false
                }
                is DragInteraction.Stop, is DragInteraction.Cancel -> {
                    resume?.cancel()
                    resume = launch {
                        delay(FOLLOW_PAUSE_MS)
                        following = true
                    }
                }
            }
        }
    }

    // Keeps the line being sung in the middle, gliding there on a spring.
    // The first time, it is simply put there.
    LaunchedEffect(list, lines) {
        var first = true
        snapshotFlow { current.takeIf { following } }
            .filterNotNull()
            .collectLatest { index ->
                list.centreOn(index.coerceAtLeast(0), animated = !first)
                first = false
            }
    }

    // A tap plays from where the line is heard, not where it is written.
    val seek: (Long) -> Unit = { at ->
        position.longValue = at
        following = true
        onSeek(heardAt(at, offset))
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val half = maxHeight / 2
        LazyColumn(
            state = list,
            contentPadding = PaddingValues(top = half, bottom = half),
            modifier = Modifier.fillMaxSize(),
        ) {
            itemsIndexed(lines) { index, line ->
                val active by remember(index) { derivedStateOf { current == index } }
                LyricRow(line, lines.endOf(index), active, position, seek)
            }
        }
    }
}

// Scrolls so the line's middle sits in the middle of the view.
private suspend fun LazyListState.centreOn(index: Int, animated: Boolean) {
    var item = layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }
    if (item == null) {
        scrollToItem(index)
        item = layoutInfo.visibleItemsInfo.firstOrNull { it.index == index } ?: return
    }
    val middle = (layoutInfo.viewportStartOffset + layoutInfo.viewportEndOffset) / 2f
    val by = item.offset + item.size / 2f - middle
    if (animated) animateScrollBy(by, lineSpring()) else scrollBy(by)
}

@Composable
private fun LyricRow(line: LyricLine, end: Long?, active: Boolean, position: LongState, onSeek: (Long) -> Unit) {
    val emphasis by animateFloatAsState(if (active) 1f else 0f, lineSpring(), label = "line")
    if (line.isGap) {
        GapDots(line, end, active, emphasis, position, onSeek)
        return
    }
    val shown = emphasis.coerceIn(0f, 1f)
    val size = lerp(OTHER_LINE_SP, SUNG_LINE_SP, emphasis)
    val align = if (line.duet) TextAlign.End else TextAlign.Start
    Column(
        Modifier.fillMaxWidth().padding(vertical = 10.dp),
        horizontalAlignment = if (line.duet) Alignment.End else Alignment.Start,
    ) {
        if (line.background) {
            TimedText(
                line.text, line.words, LyricStyle.copy(fontSize = (size * 0.8f).sp, textAlign = align), shown, end, position,
                sung = BACKING_SUNG_ALPHA, unsung = BACKING_ALPHA, resting = BACKING_RESTING_ALPHA,
                onTap = { word -> onSeek(word?.startMs ?: line.startMs) },
            )
        } else {
            TimedText(
                line.text, line.words, LyricStyle.copy(fontSize = size.sp, textAlign = align), shown, end, position,
                sung = SUNG_ALPHA, unsung = UNSUNG_ALPHA, resting = RESTING_ALPHA,
                onTap = { word -> onSeek(word?.startMs ?: line.startMs) },
            )
        }
        if (line.backingText.isNotBlank()) {
            TimedText(
                line.backingText, line.backing, LyricStyle.copy(fontSize = (size * 0.7f).sp, textAlign = align), shown, end, position,
                sung = BACKING_SUNG_ALPHA, unsung = BACKING_ALPHA, resting = BACKING_RESTING_ALPHA,
                onTap = { word -> onSeek(word?.startMs ?: line.startMs) },
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        line.translation?.let {
            Text(
                it,
                style = OctoType.body.copy(textAlign = align),
                color = Color.White.copy(alpha = lerp(0.3f, 0.6f, shown)),
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

// A line of lyrics drawn by hand, so the part already sung can be brighter
// than the rest without composing again on every frame. The word being
// sung brightens from its start to its end as it is sung.
@Composable
private fun TimedText(
    text: String,
    words: List<LyricWord>,
    style: TextStyle,
    emphasis: Float,
    lineEnd: Long?,
    position: LongState,
    sung: Float,
    unsung: Float,
    resting: Float,
    onTap: (LyricWord?) -> Unit,
    modifier: Modifier = Modifier,
) {
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val tap by rememberUpdatedState(onTap)
    Text(
        text,
        style = style,
        color = Color.White,
        onTextLayout = { layout = it },
        modifier = modifier
            .pointerInput(words) {
                detectTapGestures { at ->
                    val char = layout?.getOffsetForPosition(at)
                    tap(char?.let { c -> words.firstOrNull { c >= it.from && c < it.to } })
                }
            }
            .drawWithContent {
                val laid = layout ?: return@drawWithContent
                val base = lerp(resting, if (words.isEmpty()) sung else unsung, emphasis)
                if (words.isEmpty() || emphasis <= 0.01f) {
                    drawText(laid, color = Color.White, alpha = base)
                    return@drawWithContent
                }
                val area = sungArea(laid, words, position.longValue, lineEnd)
                clipPath(area, ClipOp.Difference) { drawText(laid, color = Color.White, alpha = base) }
                clipPath(area) { drawText(laid, color = Color.White, alpha = lerp(resting, sung, emphasis)) }
            },
    )
}

// The part of the text sung by now: every word that has ended, and the
// share of the word being sung that has gone by.
private fun sungArea(layout: TextLayoutResult, words: List<LyricWord>, at: Long, lineEnd: Long?): Path {
    val length = layout.layoutInput.text.length
    var sungTo = 0
    var partial: Pair<LyricWord, Float>? = null
    for ((index, word) in words.withIndex()) {
        if (at < word.startMs) break
        val end = word.endMs ?: words.getOrNull(index + 1)?.startMs ?: lineEnd ?: (word.startMs + 1_000)
        if (at >= end) {
            sungTo = word.to.coerceAtMost(length)
        } else {
            partial = word to ((at - word.startMs).toFloat() / (end - word.startMs).coerceAtLeast(1)).coerceIn(0f, 1f)
            break
        }
    }
    val area = if (sungTo > 0) layout.getPathForRange(0, sungTo) else Path()
    partial?.let { (word, share) ->
        val from = word.from.coerceIn(0, length)
        val to = word.to.coerceIn(from, length)
        if (to == from) return@let
        val box = layout.getPathForRange(from, to).getBounds()
        val width = box.width * share
        val rightToLeft = layout.getBidiRunDirection(from) == ResolvedTextDirection.Rtl
        area.addRect(
            if (rightToLeft) {
                Rect(box.right - width, box.top, box.right, box.bottom)
            } else {
                Rect(box.left, box.top, box.left + width, box.bottom)
            },
        )
    }
    return area
}

// A wait where nobody sings: three dots that bounce while it lasts and
// fill in as it runs out.
@Composable
private fun GapDots(line: LyricLine, end: Long?, active: Boolean, emphasis: Float, position: LongState, onSeek: (Long) -> Unit) {
    val shown = emphasis.coerceIn(0f, 1f)
    Row(
        Modifier
            .padding(vertical = 10.dp)
            .height(lerp(12f, 28f, shown).dp)
            .clickable(interactionSource = null, indication = null, role = Role.Button) { onSeek(line.startMs) },
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val bounce = if (active) bounceOf() else null
        repeat(3) { dot ->
            Box(
                Modifier
                    .size(8.dp)
                    .graphicsLayer {
                        val phase = bounce?.let { (it() - dot * 0.15f) * 2f * PI.toFloat() } ?: 0f
                        translationY = -abs(sin(phase)) * 4.dp.toPx() * shown
                        val scale = lerp(0.75f, 1f, shown)
                        scaleX = scale
                        scaleY = scale
                        val gone = end?.let { stop ->
                            (position.longValue - line.startMs).toFloat() / (stop - line.startMs).coerceAtLeast(1)
                        } ?: 0f
                        val lit = gone >= (dot + 1) / 3f
                        alpha = lerp(0.25f, if (lit) SUNG_ALPHA else UNSUNG_ALPHA, shown)
                    }
                    .background(Color.White, CircleShape),
            )
        }
    }
}

// A value going from 0 to 1 over and over, read while drawing.
@Composable
private fun bounceOf(): () -> Float {
    val beat = rememberInfiniteTransition(label = "gap").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1_400, easing = LinearEasing)),
        label = "gap beat",
    )
    return { beat.value }
}
