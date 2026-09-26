package app.winters.octo

import android.app.SearchManager
import android.content.Intent
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import app.winters.octo.design.OctoTheme
import app.winters.octo.device.DeviceLibrary
import app.winters.octo.playback.PlaybackConnection
import app.winters.octo.server.QueueSync
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

    // Counts up each time a home screen widget asks for the full player.
    private var openPlayer by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // The app opens on the library; servers are an optional add-on.
        setContent { OctoTheme { ProvideOfflineMarks { MainShell(library, playback, openPlayer) } } }
        // Opened by a voice request or a widget; not again when the screen turns.
        if (savedInstanceState == null) {
            playIfAsked(intent)
            openPlayerIfAsked(intent)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        playIfAsked(intent)
        openPlayerIfAsked(intent)
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
