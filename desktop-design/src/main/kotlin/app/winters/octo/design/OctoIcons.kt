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
    val Add by sym("sym_add")
    val Filter by sym("sym_filter_list")
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

    // An open eye and a crossed one: show or hide a password. The same
    // rounded filled symbols, written the web's way like Home.
    val Reveal by lazy { drawn("visibility", REVEAL_PATH).toImageVector(shiftY = 960f) }
    val Conceal by lazy { drawn("visibility_off", CONCEAL_PATH).toImageVector(shiftY = 960f) }

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

private const val REVEAL_PATH =
    "M607.5-372.5Q660-425 660-500t-52.5-127.5Q555-680 480-680t-127.5 52.5Q300-575 300-500t52.5 127.5Q405-320 480-320t127.5-52.5Z" +
        "m-204-51Q372-455 372-500t31.5-76.5Q435-608 480-608t76.5 31.5Q588-545 588-500t-31.5 76.5Q525-392 480-392t-76.5-31.5Z" +
        "M235.5-272Q125-344 61-462q-5-9-7.5-18.5T51-500q0-10 2.5-19.5T61-538q64-118 174.5-190T480-800q134 0 244.5 72T899-538" +
        "q5 9 7.5 18.5T909-500q0 10-2.5 19.5T899-462q-64 118-174.5 190T480-200q-134 0-244.5-72Z"

private const val CONCEAL_PATH =
    "M764-84 624-222q-35 11-71 16.5t-73 5.5q-134 0-245-72T61-462q-5-9-7.5-18.5T51-500q0-10 2.5-19.5T61-538q22-39 47-76t58-66" +
        "l-83-84q-11-11-11-27.5T84-820q11-11 28-11t28 11l680 680q11 11 11.5 27.5T820-84q-11 11-28 11t-28-11Z" +
        "M480-320q11 0 21-1t20-4L305-541q-3 10-4 20t-1 21q0 75 52.5 127.5T480-320Z" +
        "m0-480q134 0 245.5 72.5T900-537q5 8 7.5 17.5T910-500q0 10-2 19.5t-7 17.5q-19 37-42.5 70T806-331q-14 14-33 13t-33-15" +
        "l-80-80q-7-7-9-16.5t1-19.5q4-13 6-25t2-26q0-75-52.5-127.5T480-680q-14 0-26 2t-25 6q-10 3-20 1t-17-9l-33-33" +
        "q-19-19-12.5-44t31.5-32q25-5 50.5-8t51.5-3Z" +
        "m79 226q11 13 18.5 28.5T587-513q1 8-6 11t-13-3l-82-82q-6-6-2.5-13t11.5-7q19 2 35 10.5t29 22.5Z"

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
    "Playback" to { OctoIcons.Playback }, "Sound" to { OctoIcons.Sound }, "Reveal" to { OctoIcons.Reveal },
    "Conceal" to { OctoIcons.Conceal }, "Add" to { OctoIcons.Add }, "Filter" to { OctoIcons.Filter },
)
