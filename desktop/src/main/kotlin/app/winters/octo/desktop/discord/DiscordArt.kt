package app.winters.octo.desktop.discord

import app.winters.octo.catalog.SongIdentity
import app.winters.octo.desktop.system.NowPlaying
import app.winters.octo.discovery.sameArtist
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.Collections
import java.util.concurrent.TimeUnit

// The cover and artist photo found for the Discord status, when found.
data class DiscordArtwork(val cover: String? = null, val artist: String? = null)

// Finds pictures for the Discord status on public catalogues, by name, so no
// address of the listener's server and nothing signed ever leaves this
// computer: the album cover from iTunes (Deezer when iTunes has none) and
// the artist's photo from Deezer. Only names Discord shows anyway are sent.
// Every answer, found or not, is kept for the session.
class DiscordArt(
    // The body of a GET, or null on any failure.
    private val fetch: (String) -> String? = ::httpGet,
    private val keep: Int = 256,
) {
    private val found: MutableMap<String, String?> = Collections.synchronizedMap(
        object : LinkedHashMap<String, String?>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String?>) = size > keep
        },
    )
    private val looking: MutableSet<String> = Collections.synchronizedSet(HashSet())

    fun known(now: NowPlaying): DiscordArtwork =
        DiscordArtwork(cover = found[coverKey(now)], artist = found[artistKey(now)])

    // Whether a picture the settings ask for has not been looked for yet.
    fun wants(now: NowPlaying, prefs: DiscordPrefs): Boolean =
        keysFor(now, prefs).any { it !in found && it !in looking }

    suspend fun look(now: NowPlaying, prefs: DiscordPrefs) = withContext(Dispatchers.IO) {
        for (key in keysFor(now, prefs)) {
            if (key in found || !looking.add(key)) continue
            try {
                found[key] = if (key == coverKey(now)) findCover(albumArtistOf(now), now.album) else findArtist(primaryOf(now))
            } finally {
                looking.remove(key)
            }
        }
    }

    private fun keysFor(now: NowPlaying, prefs: DiscordPrefs): List<String> = buildList {
        if (prefs.picture == DiscordPicture.Cover && now.album.isNotBlank()) add(coverKey(now))
        if (prefs.badge == DiscordBadge.Artist && primaryOf(now).isNotBlank()) add(artistKey(now))
    }

    private fun findCover(artist: String, album: String): String? =
        fetch(ITUNES + enc("$artist $album"))?.let { itunesCover(it, artist, album) }
            ?: fetch(DEEZER_ALBUM + enc("artist:\"$artist\" album:\"$album\""))?.let { deezerCover(it, artist, album) }

    private fun findArtist(artist: String): String? =
        fetch(DEEZER_ARTIST + enc(artist))?.let { deezerArtistPhoto(it, artist) }

    private fun albumArtistOf(now: NowPlaying) = now.albumArtist.ifBlank { now.artist }

    private fun primaryOf(now: NowPlaying) = SongIdentity.primaryArtist(now.artist)

    private fun coverKey(now: NowPlaying) = "cover:${SongIdentity.key(albumArtistOf(now))}|${SongIdentity.key(now.album)}"

    private fun artistKey(now: NowPlaying) = "artist:${SongIdentity.key(primaryOf(now))}"
}

private const val ITUNES = "https://itunes.apple.com/search?media=music&entity=album&limit=10&term="
private const val DEEZER_ALBUM = "https://api.deezer.com/search/album?limit=10&q="
private const val DEEZER_ARTIST = "https://api.deezer.com/search/artist?limit=5&q="

private val json = Json { ignoreUnknownKeys = true }

private fun enc(text: String) = URLEncoder.encode(text, Charsets.UTF_8)

// "Album - Single" and "Album - EP" on iTunes are the album.
private val ReleaseKind = Regex("""\s+-\s+(Single|EP)$""")

private fun albumKey(name: String) = SongIdentity.key(name.replace(ReleaseKind, ""))

private fun JsonObject.text(name: String): String? = this[name]?.jsonPrimitive?.content

private fun rows(body: String, name: String): List<JsonObject> =
    json.parseToJsonElement(body).jsonObject[name]?.jsonArray.orEmpty().map { it.jsonObject }

// The first iTunes album whose artist and name agree, at 1024 px.
fun itunesCover(body: String, artist: String, album: String): String? =
    runCatching {
        rows(body, "results").firstOrNull {
            sameArtist(it.text("artistName").orEmpty(), artist) && albumKey(it.text("collectionName").orEmpty()) == albumKey(album)
        }?.text("artworkUrl100")?.replace("100x100bb", "1024x1024bb")
    }.getOrNull()

// The first Deezer album whose artist and name agree, at 1000 px.
fun deezerCover(body: String, artist: String, album: String): String? =
    runCatching {
        rows(body, "data").firstOrNull {
            sameArtist(it["artist"]?.jsonObject?.text("name").orEmpty(), artist) && albumKey(it.text("title").orEmpty()) == albumKey(album)
        }?.text("cover_xl")
    }.getOrNull()

// The Deezer photo of the artist with this very name; Deezer's empty
// placeholder (no picture id in its address) is not a photo.
fun deezerArtistPhoto(body: String, artist: String): String? =
    runCatching {
        rows(body, "data").firstOrNull {
            SongIdentity.key(it.text("name").orEmpty()) == SongIdentity.key(artist)
        }?.text("picture_big")?.takeUnless { "/artist//" in it }
    }.getOrNull()

private val http by lazy {
    OkHttpClient.Builder().callTimeout(4, TimeUnit.SECONDS).build()
}

private fun httpGet(url: String): String? = runCatching {
    http.newCall(Request.Builder().url(url).header("User-Agent", "Octo").build()).execute().use { response ->
        if (response.isSuccessful) response.body.string() else null
    }
}.getOrNull()
