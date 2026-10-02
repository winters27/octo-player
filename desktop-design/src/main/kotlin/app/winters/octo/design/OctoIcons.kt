package app.winters.octo.design

import androidx.compose.ui.graphics.vector.ImageVector

// The icons, by the same names the phone app uses, from the same Phosphor
// vector files (tools/icons/make_icons.py). Back, forward and the player's
// open and close are the chevron turned.
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
    val Playlists by sym("sym_playlist")
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
    // The pin filled: something kept pinned, or kept on top.
    val Pinned by sym("sym_keep_filled")
    val RemoveFromPlaylist by sym("sym_playlist_remove")
    val Playback by sym("sym_play_circle")
    val Appearance by sym("sym_palette")
    val Library by sym("sym_library_music")
    val Search by sym("sym_search")
    val Add by sym("sym_add")
    val Filter by sym("sym_filter_list")
    val Speaker by sym("sym_speaker")
    val Headphones by sym("sym_headphones")
    // The sections of Settings and Sound.
    val Servers by sym("sym_servers")
    val System by sym("sym_system")
    val Keyboard by sym("sym_keyboard")
    val Equalizer by sym("sym_equalizer")
    val Loudness by sym("sym_loudness")
    val Balance by sym("sym_balance")
    val Crossfade by sym("sym_crossfade")

    // Back and forward through the pages, and opening the full player: the
    // chevron turned.
    val Back by lazy { loadIcon("sym_chevron_right").toImageVector(mirrored = true) }
    val Forward by lazy { loadIcon("sym_chevron_right").toImageVector() }
    val Expand by lazy { loadIcon("sym_chevron_right").toImageVector(rotation = -90f) }
    val Collapse by lazy { loadIcon("sym_chevron_right").toImageVector(rotation = 90f) }

    // Home, settings, a password shown or hidden, and the window's own
    // buttons for the title bar drawn by the app.
    val Home by sym("sym_home")
    val Settings by sym("sym_settings")
    val Reveal by sym("sym_visibility")
    val Conceal by sym("sym_visibility_off")
    val Minimize by sym("sym_minimize")
    val Maximize by sym("sym_maximize")
    val Restore by sym("sym_restore")
}

// Every icon by name, so a test can load them all.
val allIcons: List<Pair<String, () -> ImageVector>> = listOf(
    "Previous" to { OctoIcons.Previous }, "Next" to { OctoIcons.Next }, "Play" to { OctoIcons.Play },
    "Pause" to { OctoIcons.Pause }, "Shuffle" to { OctoIcons.Shuffle }, "Repeat" to { OctoIcons.Repeat },
    "RepeatOne" to { OctoIcons.RepeatOne }, "SleepTimer" to { OctoIcons.SleepTimer }, "Queue" to { OctoIcons.Queue },
    "VolumeDown" to { OctoIcons.VolumeDown }, "VolumeUp" to { OctoIcons.VolumeUp },
    "Lossless" to { OctoIcons.Lossless }, "Like" to { OctoIcons.Like }, "Liked" to { OctoIcons.Liked },
    "More" to { OctoIcons.More }, "PlayNext" to { OctoIcons.PlayNext }, "AddToQueue" to { OctoIcons.AddToQueue },
    "AddToPlaylist" to { OctoIcons.AddToPlaylist }, "Rename" to { OctoIcons.Rename },
    "Delete" to { OctoIcons.Delete }, "Album" to { OctoIcons.Album }, "Artist" to { OctoIcons.Artist },
    "Songs" to { OctoIcons.Songs }, "Genres" to { OctoIcons.Genres }, "Playlists" to { OctoIcons.Playlists },
    "Chevron" to { OctoIcons.Chevron }, "Cloud" to { OctoIcons.Cloud }, "Check" to { OctoIcons.Check },
    "Download" to { OctoIcons.Download }, "Downloading" to { OctoIcons.Downloading },
    "Downloaded" to { OctoIcons.Downloaded }, "AddToLibrary" to { OctoIcons.AddToLibrary },
    "NotInLibrary" to { OctoIcons.NotInLibrary }, "Radio" to { OctoIcons.Radio }, "Share" to { OctoIcons.Share },
    "Sound" to { OctoIcons.Sound }, "Star" to { OctoIcons.Star }, "StarFilled" to { OctoIcons.StarFilled },
    "Lyrics" to { OctoIcons.Lyrics }, "Folder" to { OctoIcons.Folder }, "Sort" to { OctoIcons.Sort },
    "Ascending" to { OctoIcons.Ascending }, "Descending" to { OctoIcons.Descending }, "Info" to { OctoIcons.Info },
    "Select" to { OctoIcons.Select }, "Close" to { OctoIcons.Close }, "History" to { OctoIcons.History },
    "Pin" to { OctoIcons.Pin }, "Pinned" to { OctoIcons.Pinned },
    "RemoveFromPlaylist" to { OctoIcons.RemoveFromPlaylist }, "Playback" to { OctoIcons.Playback },
    "Appearance" to { OctoIcons.Appearance }, "Library" to { OctoIcons.Library }, "Search" to { OctoIcons.Search },
    "Add" to { OctoIcons.Add }, "Filter" to { OctoIcons.Filter }, "Speaker" to { OctoIcons.Speaker },
    "Headphones" to { OctoIcons.Headphones }, "Servers" to { OctoIcons.Servers }, "System" to { OctoIcons.System },
    "Keyboard" to { OctoIcons.Keyboard }, "Equalizer" to { OctoIcons.Equalizer }, "Loudness" to { OctoIcons.Loudness },
    "Balance" to { OctoIcons.Balance }, "Crossfade" to { OctoIcons.Crossfade }, "Back" to { OctoIcons.Back }, "Forward" to { OctoIcons.Forward },
    "Expand" to { OctoIcons.Expand }, "Collapse" to { OctoIcons.Collapse }, "Home" to { OctoIcons.Home },
    "Settings" to { OctoIcons.Settings }, "Reveal" to { OctoIcons.Reveal }, "Conceal" to { OctoIcons.Conceal },
    "Minimize" to { OctoIcons.Minimize }, "Maximize" to { OctoIcons.Maximize }, "Restore" to { OctoIcons.Restore },
)
