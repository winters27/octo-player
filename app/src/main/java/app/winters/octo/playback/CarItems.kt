@file:OptIn(UnstableApi::class)

package app.winters.octo.playback

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.annotation.DrawableRes
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaConstants
import app.winters.octo.catalog.AlbumEntity
import app.winters.octo.catalog.ArtistEntity
import app.winters.octo.catalog.GenreSummary
import app.winters.octo.catalog.PlaylistSummary
import app.winters.octo.catalog.TrackEntity
import app.winters.octo.design.OctoIcons
import app.winters.octo.discovery.OnlineAlbum
import app.winters.octo.discovery.OnlineArtist
import app.winters.octo.discovery.Station
import app.winters.octo.discovery.albumShareLine
import app.winters.octo.discovery.isRadio
import app.winters.octo.liveradio.LIVE_RADIO_LINE
import app.winters.octo.liveradio.LiveShelfEntry
import app.winters.octo.liveradio.LiveStation
import app.winters.octo.liveradio.detailLine
import app.winters.octo.liveradio.radioId
import app.winters.octo.livelists.LiveList
import app.winters.octo.server.ResumeOffer
import app.winters.octo.subsonic.ChartChoice
import app.winters.octo.ui.home.songCountLine
import app.winters.octo.ui.livelists.LIVE_LIST
import app.winters.octo.ui.search.OUTSIDE_FAILED

// The words a car shows for its own rows.
const val CAR_SEE_ALL = "See all"
const val CAR_TRY_AGAIN = "Tap to try again"
const val CAR_LIKED = "Liked songs"
const val CAR_FAVORITE_ALBUMS = "Favorite albums"
const val CAR_FAVORITE_ARTISTS = "Favorite artists"
fun carResumeLine(device: String): String = "Pick up where you left off on $device"

// How a list's children show: as rows, as a grid of covers, or as rows with
// an icon each (the Library's own rows).
enum class CarStyle(val value: Int) {
    List(MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM),
    Grid(MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM),
    Category(MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_CATEGORY_LIST_ITEM),
}

// How things look in a car: the phone's words, covers a car can read (with
// the "Not in your library" mark on what the library lacks), lists as rows
// or grids, and the explicit and downloaded marks. Every text is cut to what
// a car shows (carText).
class CarLook(private val context: Context) {
    // One of the app's own drawables, at an address a car can read.
    fun icon(@DrawableRes id: Int): Uri = Uri.Builder()
        .scheme(ContentResolver.SCHEME_ANDROID_RESOURCE)
        .authority(context.packageName)
        .appendPath(context.resources.getResourceTypeName(id))
        .appendPath(context.resources.getResourceEntryName(id))
        .build()

    fun cover(ref: String?, outside: Boolean = false, round: Boolean = false): Uri? = when {
        ref != null -> ArtworkProvider.carUriFor(context, ref, outside, round)
        outside -> ArtworkProvider.carMarkOnlyUri(round)
        else -> null
    }

    // A place to open: a tab, a row of the Library, "See all". `children`
    // and `playable` set how what it holds shows.
    fun folder(
        node: CarNode,
        title: String,
        @DrawableRes icon: Int? = null,
        subtitle: String? = null,
        browsable: CarStyle? = null,
        playable: CarStyle? = null,
    ): MediaItem = item(
        node,
        MediaMetadata.Builder()
            .setTitle(carText(title))
            .setSubtitle(carText(subtitle))
            .setArtworkUri(icon?.let(::icon))
            .setIsBrowsable(true)
            .setIsPlayable(false)
            .setExtras(styles(browsable, playable)),
    )

    fun album(album: AlbumEntity): MediaItem = item(
        CarNode.Album(album.id),
        MediaMetadata.Builder()
            .setTitle(carText(album.title))
            .setArtist(carText(album.artist))
            .setSubtitle(carText(album.artist))
            .setArtworkUri(cover(album.artwork))
            .setIsBrowsable(true)
            .setIsPlayable(true)
            .setMediaType(MediaMetadata.MEDIA_TYPE_ALBUM),
    )

    // An album the server has beyond the library, with the mark; one the
    // library holds some of says how much. It sets no artist: a car's
    // second line shows the artist ahead of the subtitle when one is set.
    fun onlineAlbum(album: OnlineAlbum): MediaItem = item(
        CarNode.OnlineAlbum(album.id),
        MediaMetadata.Builder()
            .setTitle(carText(album.title))
            .setSubtitle(carText(albumShareLine(album.ownedCount, album.songCount) ?: album.artist))
            .setArtworkUri(cover(album.artwork, outside = true))
            .setIsBrowsable(true)
            .setIsPlayable(true)
            .setMediaType(MediaMetadata.MEDIA_TYPE_ALBUM),
    )

    // An artist opens to a grid of their albums.
    fun artist(artist: ArtistEntity): MediaItem = item(
        CarNode.Artist(artist.id),
        MediaMetadata.Builder()
            .setTitle(carText(artist.name))
            .setArtworkUri(cover(artist.artwork))
            .setIsBrowsable(true)
            .setIsPlayable(false)
            .setMediaType(MediaMetadata.MEDIA_TYPE_ARTIST)
            .setExtras(styles(CarStyle.Grid, null)),
    )

    fun onlineArtist(artist: OnlineArtist): MediaItem = item(
        CarNode.OnlineArtist(artist.id),
        MediaMetadata.Builder()
            .setTitle(carText(artist.name))
            .setArtworkUri(cover(artist.artwork, outside = true, round = true))
            .setIsBrowsable(true)
            .setIsPlayable(false)
            .setMediaType(MediaMetadata.MEDIA_TYPE_ARTIST)
            .setExtras(styles(CarStyle.Grid, null)),
    )

    // A song, carrying the list it was found in. `outside` marks one found
    // beyond the library and not downloaded since; `downloaded` one with a
    // copy kept on the phone.
    fun song(track: TrackEntity, list: CarNode?, outside: Boolean, downloaded: Boolean): MediaItem = item(
        CarNode.Song(track.id, list),
        MediaMetadata.Builder()
            .setTitle(carText(track.title))
            .setArtist(carText(track.artist))
            .setSubtitle(carText(track.artist))
            .setAlbumTitle(carText(track.album))
            .setDurationMs(track.durationMs.takeIf { it > 0 })
            .setArtworkUri(cover(track.artwork, outside))
            .setIsBrowsable(false)
            .setIsPlayable(true)
            .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
            .setExtras(
                Bundle().apply {
                    if (track.explicit == true) putLong(MediaConstants.EXTRAS_KEY_IS_EXPLICIT, MediaConstants.EXTRAS_VALUE_ATTRIBUTE_PRESENT)
                    if (downloaded) putLong(MediaConstants.EXTRAS_KEY_DOWNLOAD_STATUS, MediaConstants.EXTRAS_VALUE_STATUS_DOWNLOADED)
                },
            ),
    )

    fun playlist(playlist: PlaylistSummary): MediaItem = list(
        CarNode.Playlist(playlist.id),
        playlist.name,
        if (playlist.family) "By Family · ${songCountLine(playlist.songCount)}" else songCountLine(playlist.songCount),
        cover(playlist.covers.firstOrNull()),
    )

    fun liked(count: Int): MediaItem = list(CarNode.Liked, CAR_LIKED, songCountLine(count), null)

    fun liveList(list: LiveList, coverRef: String?): MediaItem = list(CarNode.LiveList(list.id), list.name, LIVE_LIST, cover(coverRef))

    // One of Octo's stations, which keeps going, or a list Octo made, which
    // ends: both open to their songs and play from a tap.
    fun station(station: Station): MediaItem {
        val node = if (station.isRadio) CarNode.Station(station.id, station.name) else CarNode.OctoList(station.id, station.name)
        return item(
            node,
            MediaMetadata.Builder()
                .setTitle(carText(station.name))
                .setSubtitle(carText(station.songCount?.let(::songCountLine)))
                .setArtworkUri(cover(station.artwork))
                .setIsBrowsable(true)
                .setIsPlayable(true)
                .setMediaType(if (station.isRadio) MediaMetadata.MEDIA_TYPE_RADIO_STATION else MediaMetadata.MEDIA_TYPE_PLAYLIST),
        )
    }

    // A station on the Live radio shelf: it plays the shelf from itself.
    fun live(entry: LiveShelfEntry, sourceId: String?): MediaItem {
        val name = when (entry) {
            is LiveShelfEntry.Server -> entry.station.name
            is LiveShelfEntry.Kept -> entry.station.name
        }
        return liveItem(CarNode.Live(entry.key), name, LIVE_RADIO_LINE, stationArt(entry.radioId(), sourceId = sourceId)?.encode())
    }

    // A station near the listener: it plays the stations near them from itself.
    fun near(station: LiveStation, miles: Boolean): MediaItem =
        liveItem(CarNode.Near(station.uuid), station.name, detailLine(station, miles).ifBlank { LIVE_RADIO_LINE }, stationArt(station.radioId())?.encode())

    fun chart(choice: ChartChoice): MediaItem = list(CarNode.ChartList(choice.id, choice.name), choice.name, choice.label.ifBlank { null }, null)

    fun genre(genre: GenreSummary): MediaItem = item(
        CarNode.Genre(genre.name),
        MediaMetadata.Builder()
            .setTitle(carText(genre.name))
            .setSubtitle(songCountLine(genre.songCount))
            .setArtworkUri(cover(genre.artwork))
            .setIsBrowsable(true)
            .setIsPlayable(false)
            .setExtras(styles(CarStyle.Grid, CarStyle.List)),
    )

    // "See all" at the end of a shelf or a search's kind: a tile among
    // covers, a row among rows.
    fun seeAll(node: CarNode, amongRows: Boolean): MediaItem = item(
        node,
        MediaMetadata.Builder()
            .setTitle(CAR_SEE_ALL)
            .setArtworkUri(icon(OctoIcons.Chevron))
            .setIsBrowsable(true)
            .setIsPlayable(false)
            .setExtras(single(if (amongRows) CarStyle.List else CarStyle.Grid)),
    )

    // A part of a long list: where it runs, and what it starts with.
    fun part(node: CarNode.Part, range: IntRange, firstTitle: String?): MediaItem =
        folder(node, partTitle(range), subtitle = firstTitle)

    // One of the phone's headings of a long list sorted by name.
    fun heading(node: CarNode.Heading, browsable: CarStyle?): MediaItem = folder(node, node.heading, browsable = browsable)

    // The other device's queue, to carry on from in the car. Like an album
    // beyond the library, it sets no artist, so the subtitle shows.
    fun resume(offer: ResumeOffer): MediaItem = item(
        CarNode.Resume,
        MediaMetadata.Builder()
            .setTitle(carText(offer.title))
            .setSubtitle(carText(carResumeLine(offer.device)))
            .setArtworkUri(icon(OctoIcons.History))
            .setIsBrowsable(false)
            .setIsPlayable(true),
    )

    // A search that could not look outside the library, to try again.
    fun retry(token: String): MediaItem = folder(CarNode.Retry(token), OUTSIDE_FAILED, subtitle = CAR_TRY_AGAIN)

    // The same item under a heading: items with one heading show together.
    fun grouped(item: MediaItem, title: String): MediaItem = withExtras(item) {
        putString(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_GROUP_TITLE, carText(title))
    }

    // The same item shown as a tile among rows, or a row among tiles.
    fun shownAs(item: MediaItem, style: CarStyle): MediaItem = withExtras(item) {
        putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_SINGLE_ITEM, style.value)
    }

    private fun liveItem(node: CarNode, name: String, line: String, artRef: String?): MediaItem = item(
        node,
        MediaMetadata.Builder()
            .setTitle(carText(name))
            .setSubtitle(carText(line))
            .setArtworkUri(cover(artRef))
            .setIsBrowsable(false)
            .setIsPlayable(true)
            .setMediaType(MediaMetadata.MEDIA_TYPE_RADIO_STATION)
            .setExtras(single(CarStyle.Grid)),
    )

    private fun list(node: CarNode, title: String, subtitle: String?, cover: Uri?): MediaItem = item(
        node,
        MediaMetadata.Builder()
            .setTitle(carText(title))
            .setSubtitle(carText(subtitle))
            .setArtworkUri(cover)
            .setIsBrowsable(true)
            .setIsPlayable(true)
            .setMediaType(MediaMetadata.MEDIA_TYPE_PLAYLIST),
    )

    private fun item(node: CarNode, metadata: MediaMetadata.Builder): MediaItem =
        MediaItem.Builder().setMediaId(carId(node)).setMediaMetadata(metadata.build()).build()

    private fun styles(browsable: CarStyle?, playable: CarStyle?): Bundle? {
        if (browsable == null && playable == null) return null
        return Bundle().apply {
            browsable?.let { putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE, it.value) }
            playable?.let { putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE, it.value) }
        }
    }

    private fun single(style: CarStyle): Bundle = Bundle().apply { putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_SINGLE_ITEM, style.value) }

    private fun withExtras(item: MediaItem, change: Bundle.() -> Unit): MediaItem {
        val extras = Bundle(item.mediaMetadata.extras ?: Bundle.EMPTY).apply(change)
        return item.buildUpon().setMediaMetadata(item.mediaMetadata.buildUpon().setExtras(extras).build()).build()
    }
}
