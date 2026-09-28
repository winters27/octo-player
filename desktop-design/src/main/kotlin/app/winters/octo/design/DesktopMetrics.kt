package app.winters.octo.design

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// The desktop's sizes, in one place, so every page and piece of the frame
// takes them from here rather than choosing its own. Colours and motion
// stay in the shared tokens; these are what a mouse and a big screen need.

// Gaps, from the tightest to the widest.
object Space {
    val None = 0.dp
    val Xxs = 2.dp
    val Xs = 4.dp
    val S = 6.dp
    val M = 8.dp
    val L = 12.dp
    val Xl = 16.dp
    val Xxl = 20.dp
    val Page = 24.dp
    val Section = 32.dp
    val Wide = 40.dp
}

// How tall controls are: small buttons, fields, the default toolbar
// control, and the largest.
object ControlHeight {
    val Xs = 24.dp
    val S = 28.dp
    val M = 32.dp
    val L = 36.dp
}

// How tall a row in a list is, by density. Roomy adds the cover.
object RowHeight {
    val Compact = 32.dp
    val Regular = 40.dp
    val Roomy = 52.dp
    val Nav = 30.dp
}

// Icon sizes: beside words, in a table, in a toolbar, in the transport.
object IconSize {
    val Inline = 14.dp
    val Table = 16.dp
    val Toolbar = 18.dp
    val Transport = 20.dp
}

// One corner per role.
object Corner {
    val Row = 6.dp
    val Control = 8.dp
    val Panel = 10.dp
    val ArtS = 4.dp
    val ArtM = 6.dp
    val ArtL = 10.dp

    val RowShape = RoundedCornerShape(Row)
    val ControlShape = RoundedCornerShape(Control)
    val PanelShape = RoundedCornerShape(Panel)
    val ArtSShape = RoundedCornerShape(ArtS)
    val ArtMShape = RoundedCornerShape(ArtM)
    val ArtLShape = RoundedCornerShape(ArtL)
}

// The frame around the page: title bar, sidebar, side panel and player.
object FrameSize {
    val TitleBar = 40.dp
    val Hairline = 1.dp
    val Sidebar = 224.dp
    val SidebarMin = 184.dp
    val SidebarMax = 320.dp
    val SidebarRail = 64.dp
    val Panel = 328.dp
    val PanelMin = 280.dp
    val PanelMax = 480.dp
    val Player = 76.dp
    val PlayerCover = 56.dp
    val PlayButton = 36.dp
    val PlayButtonLarge = 44.dp
    // A menu opened from a button.
    val Menu = 280.dp
    // The volume slider's length.
    val Volume = 96.dp
    val PlaylistCover = 22.dp
    val SearchWidth = 360.dp
    // A song list's filter field.
    val FilterWidth = 260.dp
    // The transport's column, at most; the side zones share the rest evenly.
    val TransportMax = 640.dp
}

// Type on the desktop: smaller and denser than the phone's, with page
// titles that orient rather than announce.
object DesktopType {
    val label = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.6.sp)
    val meta = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Normal)
    val table = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Normal)
    val tableTitle = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium)
    val body = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Normal)
    val emphasis = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    val section = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Bold)
    val pageTitle = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.2).sp)
}

// The window's colours: the playing song's cover, blurred far past
// recognition, across the top of the window.
object Ambience {
    val Height = 520.dp
    val Blur = 110.dp
}
