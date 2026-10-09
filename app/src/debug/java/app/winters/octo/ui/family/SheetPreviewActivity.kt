package app.winters.octo.ui.family

import androidx.compose.foundation.layout.padding
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import app.winters.octo.design.GlassSheet
import app.winters.octo.design.OctoColors
import app.winters.octo.design.OctoTheme
import app.winters.octo.design.OctoType
import app.winters.octo.server.PasswordChange
import app.winters.octo.subsonic.FamilyLinkChoices
import app.winters.octo.subsonic.FamilyLogin
import app.winters.octo.subsonic.FamilySignInPending
import app.winters.octo.subsonic.FamilySignInStart
import java.time.Instant

// Debug builds only: one of the family sheets with sample data, picked by
// the "state" extra (handover, asking, sent, stale, unavailable, loading,
// error, invite, reset, login, password), for looking at on a phone
// without a family server:
//   adb shell am start -n app.winters.octo.debug/app.winters.octo.ui.family.SheetPreviewActivity --es state handover
// "w" and "h" (in dp) draw it on a smaller screen inside this one, and
// "font" at a larger text size: --ei w 360 --ei h 640 --ef font 1.5
class SheetPreviewActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val state = intent.getStringExtra("state") ?: "handover"
        val key = HandOverBox.newKey()
        val start = FamilySignInStart(
            token = "tok_7Hq2",
            expires = Instant.now().plusSeconds(102).toString(),
            links = FamilyLinkChoices("https://music.example.com", "http://192.168.1.20:4533"),
        )
        val sheet = when (state) {
            "sent" -> HandOverSheet(id = 1, start = start, key = key, done = "Sent to Studio PC. It's signing in now.")
            "stale" -> HandOverSheet(id = 1, start = start.copy(expires = Instant.now().minusSeconds(5).toString()), key = key, stale = true)
            "unavailable" -> HandOverSheet(id = 1, start = start.copy(links = FamilyLinkChoices(null, "http://192.168.1.20:4533"), anywhereAvailable = false), key = key)
            "loading" -> HandOverSheet(id = 1, key = key, loading = true)
            "error" -> HandOverSheet(id = 1, key = key, error = HANDOVER_FAILED)
            else -> HandOverSheet(id = 1, start = start, key = key)
        }
        val invite = InviteSheet(
            "Sam",
            "sam",
            "https://music.example.com/family/join#invite=tok_sam_7Hq2",
            links = FamilyLinkChoices("https://music.example.com/family/join#invite=tok_sam_7Hq2", "http://192.168.1.20:4533/family/join#invite=tok_sam_7Hq2"),
            reset = state == "reset",
        )
        val login = FamilyLogin("alex", FamilyLinkChoices("https://music.example.com", "http://192.168.1.20:4533"))
        val w = intent.getIntExtra("w", 0)
        val h = intent.getIntExtra("h", 0)
        val font = intent.getFloatExtra("font", 0f)
        setContent {
            val density = LocalDensity.current
            val screen = if (w > 0 && h > 0) Modifier.size(w.dp, h.dp).border(1.dp, Color.White.copy(alpha = 0.4f)) else Modifier.fillMaxSize()
            CompositionLocalProvider(LocalDensity provides if (font > 0f) Density(density.density, font) else density) {
                OctoTheme {
                    Box(screen.background(Brush.verticalGradient(listOf(Color(0xFF2A2140), OctoColors.Background)))) {
                        val actions = PhoneSheetActions(done = ::finish)
                        if (state == "login") {
                            Column(Modifier.statusBarsPadding().verticalScroll(rememberScrollState())) {
                                Text(YOUR_LOGIN, style = OctoType.title, color = OctoColors.TextPrimary, modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp))
                                LoginCard(login, "https://music.example.com", changePassword = {}, showQr = {}, actions = actions)
                            }
                        } else {
                            GlassSheet(visible = true, onDismiss = ::finish, tall = true, scrolls = false) {
                                when (state) {
                                    "invite", "reset" -> InviteSheetContent(invite, "https://music.example.com", owner = true, newLink = {}, actions = actions)
                                    "asking" -> AskingContent(FamilySignInPending("r_1", "Studio PC", "Windows"), sending = false, allow = ::finish, deny = ::finish)
                                    "password" -> PasswordSheetContent({ _, _ -> PasswordChange.Changed }, done = { finish() }, close = ::finish)
                                    else -> HandOverSheetContent(sheet, awayAllowed = true, server = "https://music.example.com", avatarName = "Alex", owner = true, actions = actions)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
