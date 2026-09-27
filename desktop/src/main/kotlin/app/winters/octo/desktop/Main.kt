package app.winters.octo.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import app.winters.octo.catalog.SongIdentity
import app.winters.octo.lyrics.engine.linePositionSpring
import java.util.Locale

// Two answers worked out by the shared core, to show it runs here: whether
// two spellings name the same song, and where a lyric line's spring is a
// quarter of a second after it was sent to 100.
fun sharedCoreReadings(): List<String> {
    val match = SongIdentity.same("${'$'}UICIDE", "${'$'}uicideboy${'$'}", "Suicide", "Suicideboys")
    val spring = linePositionSpring().apply {
        setTarget(100.0)
        update(0.25)
    }
    return listOf(
        "SongIdentity: \"\$UICIDE\" and \"Suicide\" are ${match.verdict} (${match.reason})",
        "Lyric line spring at 0.25 s: " + String.format(Locale.ROOT, "%.2f", spring.value()),
    )
}

fun main() = application {
    Window(onCloseRequest = ::exitApplication, title = "Octo", state = rememberWindowState()) {
        Column(
            modifier = Modifier.fillMaxSize().background(Color(0xFF101014)).padding(32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            BasicText("Octo", style = TextStyle(color = Color.White, fontSize = 40.sp))
            sharedCoreReadings().forEach { line ->
                BasicText(line, style = TextStyle(color = Color(0xFFB8B8C4), fontSize = 16.sp))
            }
        }
    }
}
