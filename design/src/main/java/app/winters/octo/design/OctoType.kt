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

    // A form's hierarchy (the sign-in page's): a section's title, a group's
    // title over its card, a row's name, a choice's value, the line under a
    // row's name, what is typed into an entry, a note or error under a card,
    // and the Optional tag.
    val formSection = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Bold)
    val formGroup = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
    val formName = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Medium)
    val formValue = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Normal)
    val formDescription = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Normal)
    val formEntry = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Normal)
    val formNote = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Normal)
    val formTag = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
}
