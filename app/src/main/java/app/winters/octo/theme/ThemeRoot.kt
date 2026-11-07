package app.winters.octo.theme

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.graphics.drawable.ColorDrawable
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.winters.octo.design.LocalThemeWrapper
import app.winters.octo.design.OctoTheme
import app.winters.octo.design.ProvideOctoTheme
import app.winters.octo.ui.settings.AccentSeed
import app.winters.octo.ui.settings.AccentViewModel

// The launch color the manifest's window shows before anything is drawn.
const val LAUNCH_PAGE = 0xFF0C0C0D.toInt()

// The theme store, for the pages that pick a theme.
val LocalThemeStore = staticCompositionLocalOf<ThemeStore?> { null }

// The theme showing now, for the pages that name it.
val LocalActiveTheme = staticCompositionLocalOf { ThemeChoice.Octo }

// Whether the phone itself is in dark mode, for Match.
val LocalDeviceDark = staticCompositionLocalOf { true }

private val MaterialWrapper: @Composable (@Composable () -> Unit) -> Unit = { inner -> OctoTheme(inner) }

// Draws everything inside in the theme showing, with the accent chosen, and
// is the one writer of the window: its background and the bars. `launchFade`
// fades the launch color into a theme whose page differs, on the Android
// versions whose launch screen cannot do it.
@Composable
fun ThemeRoot(
    store: ThemeStore,
    launchFade: Boolean,
    accent: AccentViewModel = hiltViewModel(),
    content: @Composable () -> Unit,
) {
    val settings by store.settings.collectAsState()
    val deviceDark = (LocalConfiguration.current.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    val active = activeTheme(settings, deviceDark)

    val chosen by accent.choice.collectAsStateWithLifecycle()
    val tinted by accent.glassTint.collectAsStateWithLifecycle()
    val seeded by accent.seed.collectAsStateWithLifecycle()
    val seed = seeded.argb
    val loaded by accent.loaded.collectAsStateWithLifecycle()
    val seen = remember(store) { store.seen }
    val choice = chosen ?: seen.choice
    val glass = tinted ?: seen.glass
    val art = firstFrameArt(chosen, seeded, seen.art)

    val palette = remember(active, choice, art, glass) { resolvePalette(active, choice, art, glass) }

    // What the first frame of the next run draws with, once the player's
    // settings are read. With From the album art and no cover yet, the cover
    // seen last time stays.
    LaunchedEffect(loaded, choice, seed, glass) {
        if (!loaded) return@LaunchedEffect
        val kept = store.seen.art
        store.see(AccentSeen(choice, if (choice == AccentChoice.FromArt) seed ?: kept else null, glass))
    }

    val activity = LocalContext.current.findActivity()
    LaunchedEffect(palette.page) {
        activity?.window?.setBackgroundDrawable(ColorDrawable(palette.page))
    }

    val request = remember { mutableStateOf<BarWish?>(null) }
    val bars = barsFor(request.value, palette.light)
    SideEffect {
        val (status, navigation) = barStyleFor(bars.statusLight, bars.navigationLight)
        (activity as? ComponentActivity)?.enableEdgeToEdge(status, navigation)
    }

    val fade = remember { Animatable(if (launchFade && Build.VERSION.SDK_INT < Build.VERSION_CODES.S && palette.page != LAUNCH_PAGE) 1f else 0f) }
    LaunchedEffect(Unit) {
        if (fade.value > 0f) fade.animateTo(0f, tween(250, easing = LinearEasing))
    }

    CompositionLocalProvider(
        LocalThemeStore provides store,
        LocalActiveTheme provides active,
        LocalDeviceDark provides deviceDark,
        LocalBarRequest provides request,
        LocalThemeWrapper provides MaterialWrapper,
    ) {
        Box(Modifier.fillMaxSize()) {
            ProvideOctoTheme(palette) { content() }
            if (fade.value > 0f) {
                Box(Modifier.fillMaxSize().graphicsLayer { alpha = fade.value }.background(Color(LAUNCH_PAGE)))
            }
        }
    }
}

// The cover color the accent grows from: this run's once the accent chosen
// is read and its cover worked out, until then the one seen last time.
internal fun firstFrameArt(chosen: AccentChoice?, seeded: AccentSeed, seenArt: Int?): Int? =
    if (chosen != null && seeded.seedKnown) seeded.argb else seenArt

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
