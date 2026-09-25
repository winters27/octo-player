package app.winters.octo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.winters.octo.data.SessionRepository
import app.winters.octo.data.SessionState
import app.winters.octo.design.OctoColors
import app.winters.octo.design.OctoTheme
import app.winters.octo.ui.nav.MainShell
import app.winters.octo.ui.signin.SignInScreen
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var sessions: SessionRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            OctoTheme {
                val state by sessions.state.collectAsStateWithLifecycle()
                // Sign-in and the app are two different places; switching
                // between them is a plain fade, keyed by which one it is.
                AnimatedContent(
                    targetState = state,
                    transitionSpec = { fadeIn(tween(300)) togetherWith fadeOut(tween(300)) },
                    contentKey = { it::class },
                    label = "session",
                ) { current ->
                    when (current) {
                        SessionState.Loading -> Box(Modifier.fillMaxSize().background(OctoColors.Background))
                        SessionState.SignedOut -> SignInScreen()
                        is SessionState.SignedIn -> MainShell(current.session)
                    }
                }
            }
        }
    }
}
