package app.winters.octo.ui.family

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.size
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.runtime.CompositionLocalProvider
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import app.winters.octo.design.GlassSheet
import app.winters.octo.design.OctoColors
import app.winters.octo.design.OctoTheme
import app.winters.octo.subsonic.FamilyDeviceAdded
import app.winters.octo.subsonic.FamilyDeviceKind
import app.winters.octo.subsonic.FamilyLinkChoices
import java.time.Instant

// Debug builds only: one of the family sheets with sample data, picked by
// the "state" extra (fresh, home, apps, invite, renewed, stale, unavailable,
// loading, error), for looking at on a phone without a family server:
//   adb shell am start -n app.winters.octo.debug/app.winters.octo.ui.family.SheetPreviewActivity --es state fresh
// "w" and "h" (in dp) draw it on a smaller screen inside this one, and
// "font" at a larger text size: --ei w 360 --ei h 640 --ef font 1.5
class SheetPreviewActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val state = intent.getStringExtra("state") ?: "fresh"
        val code = FamilyDeviceAdded(
            deviceId = "d_1",
            kind = FamilyDeviceKind.OctoApp,
            pairCode = "482913",
            expires = Instant.now().plusSeconds(if (state == "renewed") 45 else 582).toString(),
            server = "https://music.example.com",
            username = "winters",
            links = FamilyLinkChoices(
                "https://music.example.com/family/join#u=winters&c=482913",
                "http://192.168.1.20:4533/family/join#u=winters&c=482913",
            ),
        )
        val password = FamilyDeviceAdded(
            deviceId = "p_1",
            kind = FamilyDeviceKind.SubsonicApp,
            appPassword = "ABCDEFGHJKMNPQRS",
            server = "https://music.example.com",
            username = "winters",
        )
        val sheet = when (state) {
            "apps" -> DeviceSheet(id = 1, code = code, password = password, view = DeviceSheetView.OtherApps)
            "renewed" -> DeviceSheet(id = 1, code = code, renewed = 1)
            "stale" -> DeviceSheet(id = 1, code = code.copy(expires = Instant.now().minusSeconds(5).toString()), stale = true)
            "unavailable" -> DeviceSheet(id = 1, code = code.copy(links = FamilyLinkChoices(null, code.links!!.home), anywhereAvailable = false))
            "loading" -> DeviceSheet(id = 1, loading = true)
            "error" -> DeviceSheet(id = 1, error = CODE_FAILED)
            else -> DeviceSheet(id = 1, code = code)
        }
        val invite = InviteSheet(
            "Sam",
            "sam",
            "https://music.example.com/family/join#invite=tok_sam_7Hq2",
            links = FamilyLinkChoices("https://music.example.com/family/join#invite=tok_sam_7Hq2", "http://192.168.1.20:4533/family/join#invite=tok_sam_7Hq2"),
        )
        val w = intent.getIntExtra("w", 0)
        val h = intent.getIntExtra("h", 0)
        val font = intent.getFloatExtra("font", 0f)
        setContent {
            val density = LocalDensity.current
            val screen = if (w > 0 && h > 0) Modifier.size(w.dp, h.dp).border(1.dp, Color.White.copy(alpha = 0.4f)) else Modifier.fillMaxSize()
            CompositionLocalProvider(LocalDensity provides if (font > 0f) Density(density.density, font) else density) {
            OctoTheme {
                Box(screen.background(Brush.verticalGradient(listOf(Color(0xFF2A2140), OctoColors.Background)))) {
                    GlassSheet(visible = true, onDismiss = ::finish, tall = true, scrolls = false) {
                        val actions = PhoneSheetActions(done = ::finish)
                        if (state == "invite") InviteSheetContent(invite, "https://music.example.com", owner = true, newLink = {}, actions = actions)
                        else DeviceSheetContent(sheet, "https://music.example.com", "winters", "Jordan", owner = true, actions = actions)
                    }
                }
            }
            }
        }
    }
}
