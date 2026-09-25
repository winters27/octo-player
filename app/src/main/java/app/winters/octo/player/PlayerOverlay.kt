package app.winters.octo.player

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateInt
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitVerticalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.verticalDrag
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import app.winters.octo.catalog.ArtworkRef
import app.winters.octo.design.AccentFill
import app.winters.octo.design.GlassSheet
import app.winters.octo.design.GlazeInset
import app.winters.octo.design.GlowIcon
import app.winters.octo.design.Glaze
import app.winters.octo.design.LineSlider
import app.winters.octo.design.OctoColors
import app.winters.octo.design.OctoIcons
import app.winters.octo.design.OctoType
import app.winters.octo.design.elevation3
import app.winters.octo.playback.AudioQuality
import app.winters.octo.playback.NowPlaying
import app.winters.octo.playback.SleepState
import app.winters.octo.ui.common.Artwork
import app.winters.octo.ui.common.LocalHaze
import app.winters.octo.ui.common.asClock
import coil3.compose.AsyncImage
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// The full player. It opens over everything, with the artwork flying in
// from the bar, and closes on back, on the chevron, or by pulling it down
// from the top.
@Composable
fun AnimatedVisibilityScope.PlayerOverlay(
    artModifier: Modifier,
    onClose: () -> Unit,
    onOpenArtist: (String) -> Unit,
    model: PlayerViewModel = hiltViewModel(),
) {
    val now by model.now.collectAsStateWithLifecycle()
    val colors by model.colors.collectAsStateWithLifecycle()
    val prefs by model.prefs.collectAsStateWithLifecycle()
    val base by animateColorAsState(colors.base, tween(600), label = "player base")
    val close by rememberUpdatedState(onClose)
    val scope = rememberCoroutineScope()
    // How far the player has been pulled down, in pixels.
    var pull by remember { mutableFloatStateOf(0f) }
    val backdrop = rememberHazeState()
    var showQueue by remember { mutableStateOf(false) }
    var showSleep by remember { mutableStateOf(false) }

    BackHandler(onBack = onClose)
    LightOnDarkBars()

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val cap = constraints.maxHeight * 0.45f
        val pulled = (pull / cap).coerceIn(0f, 1f)
        // Fully rounded a fifth of the way down.
        val corner = 28.dp * (pulled * 5f).coerceAtMost(1f)
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    translationY = pull
                    scaleX = 1f - 0.06f * pulled
                    scaleY = 1f - 0.06f * pulled
                    shape = RoundedCornerShape(corner)
                    clip = pull > 0f
                }
                .pointerInput(Unit) {
                    val zone = 140.dp.toPx()
                    val threshold = 96.dp.toPx()
                    val fling = 1_200.dp.toPx()
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        // Only a pull that starts near the top closes the player, so
                        // the slider and buttons below keep their own gestures.
                        if (down.position.y > zone) return@awaitEachGesture
                        val tracker = VelocityTracker()
                        tracker.addPosition(down.uptimeMillis, down.position)
                        val start = awaitVerticalTouchSlopOrCancellation(down.id) { change, over ->
                            change.consume()
                            pull = (over * 0.82f).coerceIn(0f, cap)
                        } ?: return@awaitEachGesture
                        verticalDrag(start.id) { change ->
                            tracker.addPosition(change.uptimeMillis, change.position)
                            // A little resistance, so the sheet feels weighted.
                            pull = (pull + change.positionChange().y * 0.82f).coerceIn(0f, cap)
                            change.consume()
                        }
                        if (pull > threshold || tracker.calculateVelocity().y > fling) {
                            close()
                        } else {
                            scope.launch { animate(pull, 0f, animationSpec = spring(0.8f, 400f)) { value, _ -> pull = value } }
                        }
                    }
                }
                .background(base),
        ) {
            // The background is what the player's glass frosts, the way the
            // bar frosts the page.
            Box(Modifier.fillMaxSize().hazeSource(backdrop)) {
                if (prefs.liveBackground && LiveBackgroundSupported) {
                    MeshBackground(colors, now.isPlaying)
                } else {
                    BlurredArtwork(now.artwork)
                }
            }
            CompositionLocalProvider(LocalHaze provides backdrop) {
                PlayerContent(now, model, artModifier, onClose, onOpenArtist, onOpenQueue = { showQueue = true }, onOpenSleep = { showSleep = true })
            }
        }
        GlassSheet(visible = showQueue, onDismiss = { showQueue = false }) {
            val upNext by model.upNext.collectAsStateWithLifecycle()
            QueueSheet(upNext, now.shuffle, model::moveInQueue, model::removeFromQueue, model::playAt)
        }
        GlassSheet(visible = showSleep, onDismiss = { showSleep = false }) {
            SleepSheet(model, onDone = { showSleep = false })
        }
    }
}

// The song's own artwork, blurred into a wash of its colours: the
// background when the live one is off or the phone cannot draw it.
@Composable
private fun BlurredArtwork(ref: String?) {
    val picture = remember(ref) { ArtworkRef.decode(ref) }
    if (picture != null) {
        AsyncImage(
            model = picture,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize().blur(80.dp).alpha(0.6f),
        )
    }
}

@Composable
private fun AnimatedVisibilityScope.PlayerContent(
    now: NowPlaying,
    model: PlayerViewModel,
    artModifier: Modifier,
    onClose: () -> Unit,
    onOpenArtist: (String) -> Unit,
    onOpenQueue: () -> Unit,
    onOpenSleep: () -> Unit,
) {
    val density = LocalDensity.current
    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 24.dp),
    ) {
        Box(
            Modifier.fillMaxWidth().height(56.dp),
            contentAlignment = Alignment.Center,
        ) {
            CloseButton(onClose, Modifier.align(Alignment.CenterStart))
            now.album?.let {
                Text(
                    it,
                    style = OctoType.caption,
                    color = OctoColors.TextMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 60.dp),
                )
            }
        }
        Spacer(Modifier.weight(1f))
        BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            PlayerArt(now.artwork, min(312.dp, maxWidth), now.isPlaying, artModifier)
        }
        Spacer(Modifier.weight(1f))

        // The controls rise into place a moment after the player opens.
        Column(
            Modifier.animateEnterExit(
                enter = slideInVertically(spring(0.68f, 400f, IntOffset.VisibilityThreshold)) {
                    with(density) { 28.dp.roundToPx() }
                } + fadeIn(tween(380, delayMillis = 80)),
                exit = ExitTransition.None,
            ),
        ) {
            TitleBlock(now, onOpenArtist)
            Spacer(Modifier.height(12.dp))
            Progress(now, model)
            Spacer(Modifier.height(12.dp))
            Transport(now, model)
            Spacer(Modifier.height(8.dp))
            Volume(model)
            Spacer(Modifier.height(12.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterHorizontally),
            ) {
                ActionButton(
                    icon = OctoIcons.Queue,
                    description = "Up next",
                    on = false,
                    onClick = onOpenQueue,
                )
                SleepCircle(model, onClick = onOpenSleep)
                ActionButton(
                    icon = OctoIcons.Shuffle,
                    description = "Shuffle",
                    on = now.shuffle,
                    onClick = model::toggleShuffle,
                )
                ActionButton(
                    icon = if (now.repeatMode == Player.REPEAT_MODE_ONE) OctoIcons.RepeatOne else OctoIcons.Repeat,
                    description = "Repeat",
                    on = now.repeatMode != Player.REPEAT_MODE_OFF,
                    state = when (now.repeatMode) {
                        Player.REPEAT_MODE_ALL -> "All songs"
                        Player.REPEAT_MODE_ONE -> "This song"
                        else -> "Off"
                    },
                    onClick = model::cycleRepeat,
                )
            }
            Spacer(Modifier.height(28.dp))
        }
    }
}

// How round the card's corners are, as a percentage of its side.
const val PlayerArtCorner = 3

// The artwork card. Its corners round from a circle, as it leaves the bar,
// to a soft square as it lands. It sits full size while music plays and
// settles back smaller when paused.
@Composable
private fun AnimatedVisibilityScope.PlayerArt(ref: String?, side: Dp, playing: Boolean, artModifier: Modifier) {
    val corner by transition.animateInt(label = "art corners") {
        if (it == EnterExitState.Visible) PlayerArtCorner else 50
    }
    val scale by animateFloatAsState(
        if (playing) 1f else 0.84f,
        spring(dampingRatio = 0.6f, stiffness = 300f),
        label = "art size",
    )
    val shape = RoundedCornerShape(percent = corner)
    Box(artModifier.size(side), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }
                .elevation3(shape),
        ) {
            Artwork(ref, side, shape = shape)
        }
    }
}

@Composable
private fun TitleBlock(now: NowPlaying, onOpenArtist: (String) -> Unit) {
    AnimatedContent(
        targetState = now,
        contentKey = { it.trackId },
        transitionSpec = { fadeIn(tween(250)) togetherWith fadeOut(tween(150)) },
        label = "song title",
    ) { song ->
        Column(Modifier.fillMaxWidth()) {
            Text(
                song.title.orEmpty(),
                style = OctoType.title.copy(fontWeight = FontWeight.Bold),
                color = OctoColors.TextPrimary,
                maxLines = 1,
                modifier = Modifier.basicMarquee(),
            )
            Text(
                song.artist.orEmpty(),
                style = OctoType.body,
                color = OctoColors.Accent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.clickable(
                    enabled = song.artistId != null,
                    interactionSource = null,
                    indication = null,
                    role = Role.Button,
                ) { song.artistId?.let(onOpenArtist) },
            )
        }
    }
}

@Composable
private fun Progress(now: NowPlaying, model: PlayerViewModel) {
    val position = rememberPositionMs(now, model::positionMs)
    val duration = now.durationMs.coerceAtLeast(0)
    // The line moves every frame, but the times only change once a second,
    // so only they recompose, and only then.
    val playedSeconds by remember(duration) {
        derivedStateOf { (position.longValue.coerceIn(0, duration) / 1000).toInt() }
    }
    val times = OctoType.caption.copy(fontFeatureSettings = "tnum")
    LineSlider(
        fraction = { now.fractionAt(position.longValue) },
        onSeek = { fraction ->
            val target = (fraction * now.durationMs).toLong()
            position.longValue = target
            model.seekTo(target)
        },
    )
    Box(Modifier.fillMaxWidth().offset(y = (-8).dp)) {
        Text(playedSeconds.asClock(), style = times, color = OctoColors.TextMuted, modifier = Modifier.align(Alignment.CenterStart))
        now.quality?.let { QualityBadge(it, Modifier.align(Alignment.Center)) }
        Text(
            "-" + ((duration / 1000).toInt() - playedSeconds).coerceAtLeast(0).asClock(),
            style = times,
            color = OctoColors.TextMuted,
            modifier = Modifier.align(Alignment.CenterEnd),
        )
    }
}

// What kind of file is playing, as a small glazed label: a waveform mark
// and "LOSSLESS" or "HI-RES" for lossless files, or just the format, like
// "OPUS". A tap shows everything, like "FLAC · 24-bit · 48 kHz", for a few
// seconds.
@Composable
private fun QualityBadge(quality: AudioQuality, modifier: Modifier) {
    var open by remember(quality) { mutableStateOf(false) }
    LaunchedEffect(open) {
        if (open) {
            delay(4_000)
            open = false
        }
    }
    Glaze(
        modifier
            .height(24.dp)
            .clickable(interactionSource = null, indication = null, role = Role.Button) { open = !open }
            .clearAndSetSemantics { contentDescription = "${quality.label}, ${quality.full}" },
        backdrop = LocalHaze.current,
    ) {
        Row(
            Modifier.animateContentSize(spring(0.8f, 400f)).padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (quality.lossless) {
                Icon(
                    painterResource(OctoIcons.Lossless),
                    contentDescription = null,
                    tint = OctoColors.TextPrimary,
                    modifier = Modifier.padding(end = 5.dp).size(13.dp),
                )
            }
            Text(
                if (open) quality.full else quality.label.uppercase(),
                style = if (open) BadgeDetail else BadgeLabel,
                color = OctoColors.TextPrimary,
                maxLines = 1,
            )
        }
    }
}

private val BadgeLabel = OctoType.caption.copy(fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp)
private val BadgeDetail = OctoType.caption.copy(fontSize = 11.sp)

@Composable
private fun Transport(now: NowPlaying, model: PlayerViewModel) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TransportButton(OctoIcons.Previous, "Previous", 44.dp, model::previous)
        TransportButton(
            if (now.isPlaying) OctoIcons.Pause else OctoIcons.Play,
            if (now.isPlaying) "Pause" else "Play",
            56.dp,
            model::togglePlayPause,
        )
        TransportButton(OctoIcons.Next, "Next", 44.dp, model::next)
    }
}

// The phone's media volume, quiet on the left and loud on the right. It
// follows the volume buttons too.
@Composable
private fun Volume(model: PlayerViewModel) {
    val volume by model.volume.collectAsStateWithLifecycle()
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Icon(painterResource(OctoIcons.VolumeDown), contentDescription = null, tint = OctoColors.TextMuted, modifier = Modifier.size(20.dp))
        LineSlider(
            fraction = { volume },
            onSeek = model::setVolume,
            live = true,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 10.dp)
                .semantics { contentDescription = "Volume" },
            color = OctoColors.TextPrimary.copy(alpha = 0.85f),
        )
        Icon(painterResource(OctoIcons.VolumeUp), contentDescription = null, tint = OctoColors.TextMuted, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun TransportButton(@DrawableRes icon: Int, description: String, iconSize: Dp, onClick: () -> Unit) {
    Box(
        Modifier
            .size(72.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Button,
                onClick = onClick,
            )
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(icon), contentDescription = null, tint = OctoColors.TextPrimary, modifier = Modifier.size(iconSize))
    }
}

// Closes the player: a small glaze that frosts the background, like the bar.
@Composable
private fun CloseButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Glaze(
        modifier
            .size(44.dp)
            .clickable(interactionSource = null, indication = null, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = "Close player" },
        backdrop = LocalHaze.current,
    ) {
        Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = null, tint = OctoColors.TextPrimary, modifier = Modifier.size(26.dp))
    }
}

// One of the buttons under the controls: a plain icon, bright white and
// glowing while its setting is on and dimmed while off, the way the
// progress line glows where it has played.
@Composable
private fun ActionButton(
    @DrawableRes icon: Int,
    description: String,
    on: Boolean,
    onClick: () -> Unit,
    state: String? = null,
) {
    Box(
        Modifier
            .size(44.dp)
            .clickable(interactionSource = null, indication = null, role = Role.Button, onClick = onClick)
            .semantics {
                contentDescription = description
                stateDescription = state ?: if (on) "On" else "Off"
            },
        contentAlignment = Alignment.Center,
    ) {
        GlowIcon(
            painterResource(icon),
            tint = if (on) Color.White else Color.White.copy(alpha = 0.45f),
            lit = on,
            modifier = Modifier.size(22.dp),
        )
    }
}

@Composable
private fun SleepCircle(model: PlayerViewModel, onClick: () -> Unit) {
    val sleep by model.sleep.collectAsStateWithLifecycle()
    val minutes = (sleep as? SleepState.Counting)?.let { ((it.remainingMs + 59_999) / 60_000).toInt() }
    Box {
        ActionButton(
            icon = OctoIcons.SleepTimer,
            description = "Sleep timer",
            on = sleep != SleepState.Off,
            state = when {
                minutes != null -> "$minutes min left"
                sleep == SleepState.EndOfSong -> "At the end of this song"
                else -> "Off"
            },
            onClick = onClick,
        )
        if (minutes != null) {
            GlazeInset(
                fill = AccentFill,
                shape = CircleShape,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = 6.dp, y = (-4).dp)
                    .height(18.dp)
                    .clearAndSetSemantics { },
            ) {
                Text(
                    "$minutes",
                    style = OctoType.caption.copy(fontSize = 10.sp, fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum"),
                    color = Color.White,
                    modifier = Modifier.padding(horizontal = 6.dp),
                )
            }
        }
    }
}

// Light status and navigation icons while the player is open, whatever the
// rest of the app uses, put back as they were when it closes.
@Composable
private fun LightOnDarkBars() {
    val view = LocalView.current
    DisposableEffect(view) {
        val window = view.context.findActivity()?.window ?: return@DisposableEffect onDispose { }
        val bars = WindowCompat.getInsetsController(window, view)
        val status = bars.isAppearanceLightStatusBars
        val navigation = bars.isAppearanceLightNavigationBars
        bars.isAppearanceLightStatusBars = false
        bars.isAppearanceLightNavigationBars = false
        onDispose {
            bars.isAppearanceLightStatusBars = status
            bars.isAppearanceLightNavigationBars = navigation
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
