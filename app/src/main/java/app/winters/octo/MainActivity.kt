package app.winters.octo

import app.winters.octo.subsonic.parseFamilyLink
import app.winters.octo.family.ClipReads
import app.winters.octo.family.FamilyOpen
import app.winters.octo.family.FamilyNotifier
import app.winters.octo.family.FamilyHub
import app.winters.octo.theme.LAUNCH_PAGE
import app.winters.octo.theme.ThemeApplier
import app.winters.octo.theme.ThemeRoot
import app.winters.octo.theme.ThemeStore
import app.winters.octo.theme.activeTheme
import app.winters.octo.theme.barStyleFor
import app.winters.octo.theme.base
import app.winters.octo.ui.imports.ImportInbox
import app.winters.octo.ui.imports.fromHistory
import app.winters.octo.ui.imports.sharedImport
import android.app.SearchManager
import android.content.Intent
import android.content.res.Configuration
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import app.winters.octo.catalog.UpgradeGuard
import app.winters.octo.catalog.UpgradeState
import app.winters.octo.listening.PlayTotals
import app.winters.octo.ui.common.UpgradeSpaceScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import app.winters.octo.ui.common.Feedback
import app.winters.octo.update.AppUpdates
import app.winters.octo.device.DeviceLibrary
import app.winters.octo.playback.PlaybackConnection
import app.winters.octo.server.QueueSync
import app.winters.octo.system.SystemEntries
import app.winters.octo.system.publishShortcuts
import app.winters.octo.ui.common.ProvideAdoptedFinds
import app.winters.octo.ui.common.ProvideLearnedLengths
import app.winters.octo.ui.common.ProvideOfflineMarks
import app.winters.octo.ui.nav.MainShell
import app.winters.octo.widget.EXTRA_OPEN_PLAYER
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var library: DeviceLibrary
    @Inject lateinit var playback: PlaybackConnection
    @Inject lateinit var queueSync: QueueSync
    @Inject lateinit var feedback: Feedback
    // Launcher shortcuts and "Open with Octo".
    @Inject lateinit var systemEntries: SystemEntries
    @Inject lateinit var updates: AppUpdates
    // Family links and family notices.
    @Inject lateinit var family: FamilyHub
    // Lists shared to Octo, for Import.
    @Inject lateinit var imports: ImportInbox
    // The theme showing, kept where the first frame can read it.
    @Inject lateinit var themes: ThemeStore
    // Whether the library can open, and the space an upgrade still needs.
    @Inject lateinit var upgradeGuard: UpgradeGuard
    // Play counts that could not be brought up to date try again on return.
    @Inject lateinit var totals: PlayTotals
    // Songs found online whose version changed on the server, read on return.
    @Inject lateinit var versionFeed: app.winters.octo.playback.StreamVersionFeed

    // Counts up each time a home screen widget asks for the full player.
    private var openPlayer by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as OctoApp
        val firstScreen = !app.screenCreated
        app.screenCreated = true
        val settings = themes.settings.value
        // The window behind the first frame is the page of the theme showing,
        // so nothing flashes before the first frame is drawn.
        val deviceDark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val theme = activeTheme(settings, deviceDark)
        val page = theme.base.page
        window.setBackgroundDrawable(ColorDrawable(page))
        // The bars match that page from the first frame too; the root keeps
        // them in step from then on.
        val (status, navigation) = barStyleFor(theme.light, theme.light)
        enableEdgeToEdge(status, navigation)
        ThemeApplier.applySplash(this, settings)
        lifecycleScope.launch { themes.settings.collect { ThemeApplier.applySplash(this@MainActivity, it) } }
        // Android 12 shows its own launch screen; when the theme's page is not
        // Octo's it fades out over the page instead of cutting to it.
        if (Build.VERSION.SDK_INT in Build.VERSION_CODES.S until Build.VERSION_CODES.TIRAMISU && page != LAUNCH_PAGE) {
            splashScreen.setOnExitAnimationListener { view ->
                view.animate().alpha(0f).setDuration(250).withEndAction { view.remove() }.start()
            }
        }
        // The app opens on the library; servers are an optional add-on.
        setContent {
            ThemeRoot(themes, launchFade = firstScreen || savedInstanceState == null) {
                when (val state = upgradeGuard.state.collectAsState().value) {
                    is UpgradeState.NeedsSpace -> {
                        var checking by remember { mutableStateOf(false) }
                        UpgradeSpaceScreen(state.bytes, checking) {
                            checking = true
                            lifecycleScope.launch {
                                val clear = withContext(Dispatchers.IO) { upgradeGuard.retry() }
                                checking = false
                                if (clear) {
                                    (application as OctoApp).startAll()
                                    recreate()
                                }
                            }
                        }
                    }
                    UpgradeState.Clear -> {
                        ProvideOfflineMarks { ProvideAdoptedFinds { ProvideLearnedLengths { MainShell(library, playback, feedback, openPlayer) } } }
                    }
                    UpgradeState.Checking -> Unit
                }
            }
        }
        publishShortcuts(this)
        // Opened by a voice request or a widget; not again when the screen turns.
        if (savedInstanceState == null) {
            playIfAsked(intent)
            openPlayerIfAsked(intent)
            familyIfAsked(intent)
            importIfShared(intent)
            systemEntries.handle(this, intent) { openPlayer++ }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        playIfAsked(intent)
        openPlayerIfAsked(intent)
        familyIfAsked(intent)
        importIfShared(intent)
        systemEntries.handle(this, intent) { openPlayer++ }
    }

    // A list shared to Octo opens Import, which sends it.
    private fun importIfShared(intent: Intent) {
        if (intent.fromHistory()) return
        intent.sharedImport()?.let(imports::offer)
    }

    // Coming to the front: a family link on the clipboard is offered once.
    // Android lets only the app in front read the clipboard, and says so on
    // every read, so only a new text clip is read (ClipReads).
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) return
        val clipboard = getSystemService(android.content.ClipboardManager::class.java) ?: return
        val about = runCatching { clipboard.primaryClipDescription }.getOrNull() ?: return
        if (!clipReads.due(about.timestamp, about.hasMimeType("text/*"))) return
        val clip = runCatching { clipboard.primaryClip?.getItemAt(0)?.text?.toString() }.getOrNull()
        family.offerClipboard(clip)
    }

    private val clipReads by lazy { ClipReads(getSharedPreferences("clipboard", MODE_PRIVATE)) }

    // A family link (an invite, or a sign-in from another device, from a QR
    // code the camera read or a link tapped) opens the sign-in form filled
    // in; a family notice opens Family.
    private fun familyIfAsked(intent: Intent) {
        if (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return
        when {
            intent.action == FamilyNotifier.OPEN_FAMILY -> family.open(FamilyOpen.Family)
            intent.action == Intent.ACTION_VIEW ->
                parseFamilyLink(intent.dataString.orEmpty())?.let { family.open(FamilyOpen.Join(it)) }
        }
    }

    // A widget's artwork or title opens the player. Coming back from recents
    // hands over the old intent again, which is not a new tap.
    private fun openPlayerIfAsked(intent: Intent) {
        if (!intent.getBooleanExtra(EXTRA_OPEN_PLAYER, false)) return
        if (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return
        openPlayer++
    }

    // "Play Drake on Octo": the assistant sends what was said.
    private fun playIfAsked(intent: Intent) {
        if (intent.action != MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH) return
        playback.playFromSearch(intent.getStringExtra(SearchManager.QUERY).orEmpty(), intent.extras)
    }

    // While casting, the volume buttons turn the TV or speaker up and down.
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (isVolumeKey(keyCode) && playback.stepDeviceVolume(up = keyCode == KeyEvent.KEYCODE_VOLUME_UP)) return true
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (isVolumeKey(keyCode) && playback.now.value.casting) return true
        return super.onKeyUp(keyCode, event)
    }

    private fun isVolumeKey(keyCode: Int) = keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN

    override fun onStart() {
        super.onStart()
        playback.connect()
        // A queue left on another device may be waiting on the server.
        queueSync.check()
        totals.foreground()
        versionFeed.foreground()
    }

    // Letting go lets the service stop itself when nothing is playing.
    override fun onStop() {
        // Leaving Octo is when "When I leave Octo" puts a ready update in,
        // unless music is playing.
        if (!isChangingConfigurations) updates.leaving(playing = playback.now.value.isPlaying)
        playback.release()
        super.onStop()
    }
}
