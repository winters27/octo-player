package app.winters.octo.design

import androidx.compose.ui.graphics.vector.ImageVector
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

// The icons, by the same names the phone app uses, from the same Material
// Symbols Rounded (filled) vector files. A few the desktop needs that the
// phone draws another way (home, settings, the window buttons, back and
// forward) are drawn here in the same 960 square and weight.
object OctoIcons {
    private fun sym(file: String) = lazy { loadIcon(file).toImageVector() }

    val Previous by sym("sym_fast_rewind")
    val Next by sym("sym_fast_forward")
    val Play by sym("sym_play_arrow")
    val Pause by sym("sym_pause")
    val Shuffle by sym("sym_shuffle")
    val Repeat by sym("sym_repeat")
    val RepeatOne by sym("sym_repeat_one")
    val SleepTimer by sym("sym_bedtime")
    val Queue by sym("sym_queue_music")
    val VolumeDown by sym("sym_volume_down")
    val VolumeUp by sym("sym_volume_up")
    val Lossless by sym("sym_graphic_eq")
    val Like by sym("sym_favorite")
    val Liked by sym("sym_favorite_filled")
    val More by sym("sym_more_horiz")
    val PlayNext by sym("sym_queue_play_next")
    val AddToQueue by sym("sym_add_to_queue")
    val AddToPlaylist by sym("sym_playlist_add")
    val Rename by sym("sym_edit")
    val Delete by sym("sym_delete")
    val Album by sym("sym_album")
    val Artist by sym("sym_person")
    val Songs by sym("sym_music_note")
    val Genres by sym("sym_genres")
    val Playlists by sym("sym_queue_music")
    val Chevron by sym("sym_chevron_right")
    val Cloud by sym("sym_cloud")
    val Check by sym("sym_check")
    val Download by sym("sym_download")
    val Downloading by sym("sym_downloading")
    val Downloaded by sym("sym_download_done")

    // A plus: has the server add a song found online to the library.
    val AddToLibrary by sym("sym_add")

    // The same plus drawn thinner, on things not in the library.
    val NotInLibrary by sym("sym_add_light")
    val Radio by sym("sym_radio")
    val Share by sym("sym_share")
    val Sound by sym("sym_tune")
    val Star by sym("sym_star")
    val StarFilled by sym("sym_star_filled")
    val Lyrics by sym("sym_lyrics")
    val Folder by sym("sym_folder")
    val Sort by sym("sym_sort")
    val Ascending by sym("sym_arrow_upward")
    val Descending by sym("sym_arrow_downward")
    val Info by sym("sym_info")
    val Select by sym("sym_check_circle")
    val Close by sym("sym_close")
    val History by sym("sym_history")
    val Pin by sym("sym_keep")
    val RemoveFromPlaylist by sym("sym_playlist_remove")
    val Playback by sym("sym_play_circle")
    val Appearance by sym("sym_palette")
    val Library by sym("sym_library_music")
    val Search by sym("sym_search")
    val Speaker by sym("sym_speaker")
    val Headphones by sym("sym_headphones")

    // Back and forward through the pages, and opening the full player: the
    // chevron turned.
    val Back by lazy { loadIcon("sym_chevron_right").toImageVector(mirrored = true) }
    val Forward by lazy { loadIcon("sym_chevron_right").toImageVector() }
    val Expand by lazy { loadIcon("sym_chevron_right").toImageVector(rotation = -90f) }
    val Collapse by lazy { loadIcon("sym_chevron_right").toImageVector(rotation = 90f) }

    // Home: the rounded filled house of the same symbol set. Its path is
    // written the web's way, above the viewport, so it is moved down.
    val Home by lazy { drawn("home", HOME_PATH).toImageVector(shiftY = 960f) }

    // Settings: a cog with a round hole, drawn to the set's proportions.
    val Settings by lazy { IconSource("settings", 24f, 24f, 960f, 960f, listOf(IconPath(cogPath(), evenOdd = true))).toImageVector() }

    // The window's own buttons, for the title bar drawn by the app.
    val Minimize by lazy { drawn("minimize", "M220,450L740,450Q760,450 760,470L760,490Q760,510 740,510L220,510Q200,510 200,490L200,470Q200,450 220,450Z").toImageVector() }
    val Maximize by lazy { drawn("maximize", MAXIMIZE_PATH, evenOdd = true).toImageVector() }
    val Restore by lazy { drawn("restore", RESTORE_PATH, evenOdd = true).toImageVector() }

    private fun drawn(name: String, data: String, evenOdd: Boolean = false) =
        IconSource(name, 24f, 24f, 960f, 960f, listOf(IconPath(data, evenOdd)))
}

private const val HOME_PATH =
    "M160-200v-360q0-19 8.5-36t23.5-28l240-180q21-16 48-16t48 16l240 180q15 11 23.5 28t8.5 36v360q0 33-23.5 56.5T720-120H600" +
        "q-17 0-28.5-11.5T560-160v-200q0-17-11.5-28.5T520-400h-80q-17 0-28.5 11.5T400-360v200q0 17-11.5 28.5T360-120H240" +
        "q-33 0-56.5-23.5T160-200Z"

// A square frame 480 wide with 44 thick sides.
private const val MAXIMIZE_PATH =
    "M240,240L720,240L720,720L240,720Z M284,284L284,676L676,676L676,284Z"

// A smaller frame in front, and the top and right edges of one behind it.
private const val RESTORE_PATH =
    "M220,340L620,340L620,740L220,740Z M264,384L264,696L576,696L576,384Z " +
        "M340,220L740,220L740,620L664,620L664,576L696,576L696,264L384,264L384,296L340,296Z"

// A cog of eight teeth around a hole, as one path: the teeth and the rim
// between them, then the hole, cut out by the even-odd rule.
internal fun cogPath(): String {
    val centre = 480.0
    val tip = 420.0
    val root = 330.0
    val hole = 125.0
    val teeth = 8
    val tipHalf = 0.17
    val rootHalf = 0.27
    val step = 2 * PI / teeth
    fun point(radius: Double, angle: Double) =
        String.format(Locale.ROOT, "%.1f,%.1f", centre + radius * cos(angle), centre + radius * sin(angle))
    val out = StringBuilder()
    for (i in 0 until teeth) {
        val a = i * step
        out.append(if (i == 0) "M" else "L").append(point(root, a - rootHalf))
        out.append("L").append(point(tip, a - tipHalf))
        out.append("L").append(point(tip, a + tipHalf))
        out.append("L").append(point(root, a + rootHalf))
        // Along the rim to the next tooth, in small steps so it reads round.
        val next = (i + 1) * step - rootHalf
        for (k in 1..3) out.append("L").append(point(root, a + rootHalf + (next - a - rootHalf) * k / 4))
    }
    out.append("Z M").append(point(hole, 0.0))
    for (k in 1..24) out.append("L").append(point(hole, 2 * PI * k / 24))
    out.append("Z")
    return out.toString()
}

// Every icon by name, so a test can load them all.
val allIcons: List<Pair<String, () -> ImageVector>> = listOf(
    "Previous" to { OctoIcons.Previous }, "Next" to { OctoIcons.Next }, "Play" to { OctoIcons.Play },
    "Pause" to { OctoIcons.Pause }, "Shuffle" to { OctoIcons.Shuffle }, "Repeat" to { OctoIcons.Repeat },
    "RepeatOne" to { OctoIcons.RepeatOne }, "Queue" to { OctoIcons.Queue }, "VolumeDown" to { OctoIcons.VolumeDown },
    "VolumeUp" to { OctoIcons.VolumeUp }, "Like" to { OctoIcons.Like }, "Liked" to { OctoIcons.Liked },
    "More" to { OctoIcons.More }, "PlayNext" to { OctoIcons.PlayNext }, "AddToQueue" to { OctoIcons.AddToQueue },
    "AddToPlaylist" to { OctoIcons.AddToPlaylist }, "Album" to { OctoIcons.Album }, "Artist" to { OctoIcons.Artist },
    "Songs" to { OctoIcons.Songs }, "Genres" to { OctoIcons.Genres }, "Playlists" to { OctoIcons.Playlists },
    "AddToLibrary" to { OctoIcons.AddToLibrary }, "Radio" to { OctoIcons.Radio }, "Lyrics" to { OctoIcons.Lyrics },
    "Folder" to { OctoIcons.Folder }, "Sort" to { OctoIcons.Sort }, "Ascending" to { OctoIcons.Ascending },
    "Descending" to { OctoIcons.Descending }, "Info" to { OctoIcons.Info }, "Close" to { OctoIcons.Close },
    "History" to { OctoIcons.History }, "Appearance" to { OctoIcons.Appearance }, "Library" to { OctoIcons.Library },
    "Search" to { OctoIcons.Search }, "Speaker" to { OctoIcons.Speaker }, "Back" to { OctoIcons.Back },
    "Forward" to { OctoIcons.Forward }, "Expand" to { OctoIcons.Expand }, "Collapse" to { OctoIcons.Collapse },
    "Home" to { OctoIcons.Home }, "Settings" to { OctoIcons.Settings }, "Minimize" to { OctoIcons.Minimize },
    "Maximize" to { OctoIcons.Maximize }, "Restore" to { OctoIcons.Restore }, "Check" to { OctoIcons.Check },
    "Playback" to { OctoIcons.Playback }, "Sound" to { OctoIcons.Sound },
)
