package app.winters.octo.ui.family

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
        setContent {
            OctoTheme {
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF2A2140), OctoColors.Background)))) {
                    GlassSheet(visible = true, onDismiss = ::finish) {
                        val actions = PhoneSheetActions(done = ::finish)
                        if (state == "invite") InviteSheetContent(invite, "https://music.example.com", owner = true, newLink = {}, actions = actions)
                        else DeviceSheetContent(sheet, "https://music.example.com", "winters", "Jordan", owner = true, actions = actions)
                    }
                }
            }
        }
    }
}
