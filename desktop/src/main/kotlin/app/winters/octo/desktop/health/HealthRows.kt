package app.winters.octo.desktop.health

import app.winters.octo.desktop.library.SongColumn
import app.winters.octo.health.HealthCheck
import app.winters.octo.health.HealthReport
import app.winters.octo.health.SubsonicHealth
import app.winters.octo.health.heading
import app.winters.octo.health.summary
import app.winters.octo.subsonic.Song

// One check's songs as the table shows them: the songs, a heading and a
// line above the first row of each set of copies or split album, and what
// the number column says.
data class HealthRows(
    val songs: List<Song>,
    val titles: Map<Int, String> = emptyMap(),
    val details: Map<Int, String> = emptyMap(),
    val numbers: List<String> = songs.indices.map { "${it + 1}" },
)

fun healthRows(report: HealthReport<Song>, check: HealthCheck?): HealthRows = when (check) {
    null -> HealthRows(emptyList())
    // Each set under the song's name, its copies numbered from 1, the one
    // to keep first.
    HealthCheck.Duplicates -> {
        val songs = ArrayList<Song>()
        val titles = HashMap<Int, String>()
        val details = HashMap<Int, String>()
        val numbers = ArrayList<String>()
        report.duplicates.forEach { group ->
            titles[songs.size] = group.heading(SubsonicHealth)
            details[songs.size] = group.summary(SubsonicHealth)
            group.copies.forEachIndexed { i, copy ->
                songs += copy
                numbers += "${i + 1}"
            }
        }
        HealthRows(songs, titles, details, numbers)
    }
    // Each album under its name and what its parts disagree on, the songs
    // by their track numbers.
    HealthCheck.SplitAlbums -> {
        val songs = ArrayList<Song>()
        val titles = HashMap<Int, String>()
        val details = HashMap<Int, String>()
        val numbers = ArrayList<String>()
        report.splitAlbums.forEach { album ->
            titles[songs.size] = album.heading()
            details[songs.size] = album.summary()
            album.parts.forEach { part ->
                part.songs.forEach { song ->
                    songs += song
                    numbers += song.track?.takeIf { it > 0 }?.toString().orEmpty()
                }
            }
        }
        HealthRows(songs, titles, details, numbers)
    }
    else -> HealthRows(report.songs(check))
}

// The columns that show what a check is about: the format and size for
// copies, the album and year for split albums, the tag itself for a
// missing one.
fun healthColumns(check: HealthCheck?): List<SongColumn> {
    val base = listOf(SongColumn.Number, SongColumn.Title, SongColumn.Artist, SongColumn.Album)
    return base + when (check) {
        HealthCheck.Duplicates, HealthCheck.NoLength -> listOf(SongColumn.Format, SongColumn.Size, SongColumn.Length)
        HealthCheck.SplitAlbums, HealthCheck.NoYear -> listOf(SongColumn.Year, SongColumn.Length)
        HealthCheck.NoGenre -> listOf(SongColumn.Genre, SongColumn.Length)
        else -> listOf(SongColumn.Year, SongColumn.Length)
    }
}
