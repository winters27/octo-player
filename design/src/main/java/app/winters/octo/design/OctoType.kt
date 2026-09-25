package app.winters.octo.design

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// The type scale. Colours are set where the text is used.
object OctoType {
    val display = TextStyle(fontSize = 32.sp, fontWeight = FontWeight.Black, letterSpacing = (-0.5).sp)
    val title = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.Black)
    val headline = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Bold)
    val section = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Bold)
    val body = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Medium)
    val bodySmall = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Normal)
    val caption = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Normal)
    val label = TextStyle(fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
}
