package app.winters.octo.desktop.pages

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import app.winters.octo.design.Corner
import app.winters.octo.design.OctoIcons
import app.winters.octo.desktop.AppState
import app.winters.octo.desktop.library.Cover
import app.winters.octo.desktop.library.SongColumn
import app.winters.octo.desktop.library.discHeadings
import app.winters.octo.desktop.library.formatSummary
import app.winters.octo.desktop.library.sortAlbums
import app.winters.octo.desktop.nav.Page
import app.winters.octo.desktop.nav.Visit
import app.winters.octo.desktop.ui.AlbumMenu
import app.winters.octo.desktop.ui.LinkText
import app.winters.octo.desktop.ui.PageSide
import app.winters.octo.desktop.ui.SongTable
import app.winters.octo.desktop.ui.rememberListState
import app.winters.octo.desktop.ui.rememberLoad
import app.winters.octo.desktop.ui.show
import app.winters.octo.sort.SortList
import app.winters.octo.subsonic.Album
import app.winters.octo.subsonic.AlbumWithSongs
import app.winters.octo.subsonic.SubsonicException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

// An album and the artist's other albums, as the page shows them.
class AlbumView(val album: AlbumWithSongs, val others: List<Album>)

// An album: a compact header (cover, names, year, genre, songs, length and
// format), Play, Shuffle, the heart, Add to queue and More, then its songs
// in album order with a heading for each disc (named as the album names
// them), and the artist's other albums at the foot.
@Composable
fun AlbumPage(app: AppState, visit: Visit, id: String) {
    val connection = app.connection ?: return
    val loaded = rememberLoad(connection, id) {
        coroutineScope {
            val album = connection.client.album(id)
            val others = async {
                val artistId = album.artistId?.takeIf(String::isNotEmpty) ?: return@async emptyList()
                try {
                    sortAlbums(connection.client.artist(artistId).album.filter { it.id != id }, SortList.ArtistAlbums.default)
                } catch (e: SubsonicException) {
                    emptyList()
                }
            }
            AlbumView(album, others.await())
        }
    }
    val list = rememberListState(app.navigator, visit)
    loaded.show(Modifier.padding(horizontal = PageSide)) { view ->
        val album = view.album
        val songs = album.song
        val headings = remember(album) { discHeadings(songs, album.discTitles) }
        val format = remember(album) { formatSummary(songs) }
        // An album found online is not in the library: no heart, since on
        // Octo a star would fetch the whole album.
        val inLibrary = app.library?.index?.hasAlbum(album.id) ?: true
        val artistName = album.displayArtist ?: album.artist
        BoxWithConstraints {
            val columns = cardColumns(maxWidth)
            SongTable(
                app,
                songs,
                listOf(SongColumn.Number, SongColumn.Title, SongColumn.Artist, SongColumn.Plays, SongColumn.Favourite, SongColumn.Length),
                list,
                id = "album",
                covers = false,
                number = { index, song -> song.track?.toString() ?: "${index + 1}" },
                groupTitle = { index -> headings.getOrNull(index) },
                empty = {
                    NextStep(
                        "This album has no songs",
                        "Your server lists the album but has no songs for it. A rescan on the server may bring them back.",
                        *listOfNotNull(album.artistId?.takeIf(String::isNotEmpty)?.let { artist -> "Go to $artistName" to { app.navigator.go(Page.Artist(artist, album.artist)) } }).toTypedArray(),
                    )
                },
                footer = {
                    if (view.others.isNotEmpty()) {
                        val page = PageItems(this, HashMap())
                        page.item("more-title") {
                            GroupTitle("More by $artistName", action = if (view.others.size > columns) "See all" else null) {
                                album.artistId?.let { app.navigator.go(Page.Artist(it, album.artist)) }
                            }
                        }
                        page.cards("more", view.others.take(columns), columns) { AlbumCard(app, it) }
                    }
                },
            ) {
                item(key = "head") {
                    EntityHeader(
                        "Album",
                        album.name,
                        picture = { Cover(album.coverArt, it, shape = Corner.ArtLShape, online = !inLibrary) },
                        subtitle = { LinkText(artistName, album.artistId) { app.navigator.go(Page.Artist(it, album.artist)) } },
                        facts = albumFacts(app, album, songs.size, songs.sumOf { it.duration }, format),
                    ) {
                        PlayAndShuffle({ app.play(songs) }, { app.play(songs, shuffle = true) }, enabled = songs.isNotEmpty())
                        if (inLibrary) {
                            val starred = app.isAlbumStarred(album.id, album.starred ?: app.library?.index?.albums?.firstOrNull { it.id == album.id }?.starred)
                            HeaderIcon(if (starred) OctoIcons.Liked else OctoIcons.Like, if (starred) "Remove from favourites" else "Add to favourites", { app.setAlbumStarred(album.id, !starred) })
                        }
                        HeaderIcon(OctoIcons.AddToQueue, "Add to queue", { app.addToQueue(songs) }, enabled = songs.isNotEmpty())
                        MoreButton(app, "More for this album") { close -> AlbumMenu(app, album.asAlbum(), close, outside = !inLibrary) }
                    }
                }
            }
        }
    }
}

// The line under an album's name: year, genre (opening the genre), how
// many songs and how long, and the format.
private fun albumFacts(app: AppState, album: AlbumWithSongs, count: Int, seconds: Int, format: String?): List<Fact> {
    val genre = (album.genres.firstOrNull() ?: album.genre)?.trim()?.takeIf(String::isNotEmpty)
    return listOfNotNull(
        (album.year ?: album.releaseDate?.year)?.takeIf { it > 0 }?.let { Fact(it.toString()) },
        genre?.let { Fact(it) { app.navigator.go(Page.Genre(it)) } },
        Fact(songsLine(count, seconds)),
        format?.let(::Fact),
    )
}

// The album as a card or menu takes it.
fun AlbumWithSongs.asAlbum() = Album(
    id = id,
    name = name,
    artist = artist,
    artistId = artistId,
    displayArtist = displayArtist,
    coverArt = coverArt,
    songCount = songCount,
    duration = duration,
    year = year,
    genre = genre,
    starred = starred,
    isCompilation = isCompilation,
    discTitles = discTitles,
    releaseTypes = releaseTypes,
)
