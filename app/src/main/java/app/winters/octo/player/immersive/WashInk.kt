package app.winters.octo.player.immersive

import androidx.compose.ui.graphics.Color

// The words' colour as a Compose Color. The colour maths it rests on is in
// shared core (WashColour.kt).

// The dark words used over a light background.
val DarkInk = Color(0xFF141416)

// The colour for the player's words over a background made from a cover
// whose main colour is this: dark over a light wash, white otherwise.
fun washContent(dominant: Int?, tuning: WashTuning): Color =
    if (dominant != null && washIsLight(prepareColor(dominant, tuning))) DarkInk else Color.White
