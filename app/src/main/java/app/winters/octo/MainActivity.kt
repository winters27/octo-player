package app.winters.octo

import android.app.SearchManager
import android.content.Intent
import android.os.Bundle
import android.provider.MediaStore
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import app.winters.octo.ui.common.Feedback
import app.winters.octo.design.OctoTheme
import app.winters.octo.device.DeviceLibrary
import app.winters.octo.playback.PlaybackConnection
import app.winters.octo.server.QueueSync
import app.winters.octo.system.SystemEntries
import app.winters.octo.system.publishShortcuts
import app.winters.octo.ui.common.ProvideOfflineMarks
import app.winters.octo.ui.nav.MainShell
import app.winters.octo.widget.EXTRA_OPEN_PLAYER
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var library: DeviceLibrary
    @Inject lateinit var playback: PlaybackConnection
    @Inject lateinit var queueSync: QueueSync
    @Inject lateinit var feedback: Feedback
    // Launcher shortcuts and "Open with Octo".
    @Inject lateinit var systemEntries: SystemEntries

    // Counts up each time a home screen widget asks for the full player.
    private var openPlayer by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // The app opens on the library; servers are an optional add-on.
        setContent { OctoTheme { ProvideOfflineMarks { MainShell(library, playback, feedback, openPlayer) } } }
        publishShortcuts(this)
        // Opened by a voice request or a widget; not again when the screen turns.
        if (savedInstanceState == null) {
            playIfAsked(intent)
            openPlayerIfAsked(intent)
            systemEntries.handle(this, intent) { openPlayer++ }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        playIfAsked(intent)
        openPlayerIfAsked(intent)
        systemEntries.handle(this, intent) { openPlayer++ }
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
        playback.playFromSearch(intent.getStringExtra(SearchManager.QUERY).orEmpty())
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
    }

    // Letting go lets the service stop itself when nothing is playing.
    override fun onStop() {
        playback.release()
        super.onStop()
    }
}
