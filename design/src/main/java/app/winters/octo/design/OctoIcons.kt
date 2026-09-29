package app.winters.octo.design

// The app's icons, from Phosphor Icons (MIT, design/licenses/Phosphor-MIT.txt):
// the Regular weight, and Fill for the transport and for things that are
// on (a liked song, a rating, the selected tab). Kept as vectors in this
// module and made by tools/icons/make_icons.py from tools/icons/icons.json.
// Draw them with painterResource and tint in place.
object OctoIcons {
    val Previous = R.drawable.sym_fast_rewind
    val Next = R.drawable.sym_fast_forward
    val Play = R.drawable.sym_play_arrow
    val Pause = R.drawable.sym_pause
    val Shuffle = R.drawable.sym_shuffle
    val Repeat = R.drawable.sym_repeat
    val RepeatOne = R.drawable.sym_repeat_one
    val SleepTimer = R.drawable.sym_bedtime
    val Queue = R.drawable.sym_queue_music
    val VolumeDown = R.drawable.sym_volume_down
    val VolumeUp = R.drawable.sym_volume_up
    val Lossless = R.drawable.sym_graphic_eq
    val Like = R.drawable.sym_favorite
    val Liked = R.drawable.sym_favorite_filled
    val More = R.drawable.sym_more_horiz
    val PlayNext = R.drawable.sym_queue_play_next
    val AddToQueue = R.drawable.sym_add_to_queue
    val AddToPlaylist = R.drawable.sym_playlist_add
    val Rename = R.drawable.sym_edit
    val Delete = R.drawable.sym_delete
    val Album = R.drawable.sym_album
    val Artist = R.drawable.sym_person
    val Songs = R.drawable.sym_music_note
    val Genres = R.drawable.sym_genres
    val Playlists = R.drawable.sym_playlist
    val Chevron = R.drawable.sym_chevron_right

    // The chevron pointing back (inside a menu) and down (closing the
    // player, opening a choice).
    val ChevronBack = R.drawable.sym_chevron_left
    val Collapse = R.drawable.sym_chevron_down

    // Back out of a page.
    val Back = R.drawable.sym_arrow_back

    val Cloud = R.drawable.sym_cloud
    val Check = R.drawable.sym_check
    val Download = R.drawable.sym_download
    val Downloading = R.drawable.sym_downloading
    val Downloaded = R.drawable.sym_download_done

    // A plus: adds a song found online to the library. The download arrow
    // stays for saving a library song to the phone.
    val AddToLibrary = R.drawable.sym_add

    // The same plus drawn thinner (the Light weight), small on the artwork
    // of a song, album or artist that is not in the library.
    val NotInLibrary = R.drawable.sym_add_light
    val Radio = R.drawable.sym_radio
    // A link passed on to someone else.
    val Share = R.drawable.sym_share
    // Three sliders: the sound settings.
    val Sound = R.drawable.sym_tune
    // A rating.
    val Star = R.drawable.sym_star
    val StarFilled = R.drawable.sym_star_filled

    // Lyrics.
    val Lyrics = R.drawable.sym_lyrics

    // A folder, for browsing music the way it is filed.
    val Folder = R.drawable.sym_folder

    // How a list is ordered, and which way it runs.
    val Sort = R.drawable.sym_sort
    // Rules: filters, and the mark of a live list.
    val Filter = R.drawable.sym_filter_list
    val Ascending = R.drawable.sym_arrow_upward
    val Descending = R.drawable.sym_arrow_downward

    // Everything known about a song.
    val Info = R.drawable.sym_info

    // Picking songs in a list, and a picked one.
    val Select = R.drawable.sym_check_circle

    // Closes a bar or a panel.
    val Close = R.drawable.sym_close
    val History = R.drawable.sym_history

    // Keeps an album, artist or playlist at the front of Home.
    val Pin = R.drawable.sym_keep

    // Takes a song off a playlist.
    val RemoveFromPlaylist = R.drawable.sym_playlist_remove

    // A song's own file, passed on to another app.
    val ShareFile = R.drawable.sym_audio_file

    // The phone's ringtone and other sounds.
    val Ringtone = R.drawable.sym_ring_volume

    // The settings categories: how songs play, how the app looks, the
    // library, listening stats, and saving settings to a file.
    val Playback = R.drawable.sym_play_circle
    val Appearance = R.drawable.sym_palette
    val Library = R.drawable.sym_library_music
    val Scrobbling = R.drawable.sym_insights
    val Backup = R.drawable.sym_settings_backup_restore

    // Looking something up by name.
    val Search = R.drawable.sym_search

    // The phone itself, as a place music is kept.
    val Phone = R.drawable.sym_smartphone

    // The tabs, each with the filled form it takes while selected. Search
    // and Library share their icons with the rest of the app.
    val Home = R.drawable.sym_home
    val HomeSelected = R.drawable.sym_home_filled
    val SearchSelected = R.drawable.sym_search_filled
    val LibrarySelected = R.drawable.sym_library_filled
    val Settings = R.drawable.sym_settings
    val SettingsSelected = R.drawable.sym_settings_filled

    // Playing on another device: the button, and the button while it is.
    val Cast = R.drawable.sym_cast
    val CastConnected = R.drawable.sym_cast_connected

    // The kinds of device music can play on.
    val Tv = R.drawable.sym_tv
    val Speaker = R.drawable.sym_speaker
    val SpeakerGroup = R.drawable.sym_speaker_group
    val Headphones = R.drawable.sym_headphones
}
