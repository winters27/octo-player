package app.winters.octo.desktop.ui

import app.winters.octo.desktop.FakeServer
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface

// Made-up libraries for the polish shots: a hundred songs full of awkward
// cases (long titles, every kind of script, many artists, discs, missing
// tags and covers, the same song twice), and plain filler to any size, with
// the awkward ones mixed in. `serve` answers a FakeServer from one of them,
// paging the whole-library reads as a real server does.
internal class FakeSong(
    val id: String,
    val title: String,
    val artist: String? = null,
    val artistId: String? = null,
    val album: String? = null,
    val albumId: String? = null,
    val track: Int? = null,
    val disc: Int? = null,
    val year: Int? = null,
    val duration: Int = 200,
    val cover: String? = null,
    val suffix: String = "flac",
    val genre: String? = null,
    val displayArtist: String? = null,
    val plays: Int = 0,
    val starred: Boolean = false,
    val added: String = "2026-09-01T10:00:00Z",
) {
    fun json(): String {
        val fields = buildList {
            add("\"id\":${q(id)}")
            add("\"title\":${q(title)}")
            artist?.let { add("\"artist\":${q(it)}") }
            artistId?.let { add("\"artistId\":${q(it)}") }
            displayArtist?.let { add("\"displayArtist\":${q(it)}") }
            album?.let { add("\"album\":${q(it)}") }
            albumId?.let { add("\"albumId\":${q(it)}") }
            track?.let { add("\"track\":$it") }
            disc?.let { add("\"discNumber\":$it") }
            year?.let { add("\"year\":$it") }
            add("\"duration\":$duration")
            cover?.let { add("\"coverArt\":${q(it)}") }
            add("\"suffix\":${q(suffix)}")
            add("\"bitDepth\":${if (suffix == "flac") 24 else 16},\"samplingRate\":${if (suffix == "flac") 96000 else 44100}")
            add("\"size\":${duration * 110_000L}")
            genre?.let { add("\"genre\":${q(it)}") }
            if (plays > 0) add("\"playCount\":$plays")
            if (starred) add("\"starred\":\"2026-09-01T00:00:00Z\"")
            add("\"created\":${q(added)}")
            add("\"path\":${q("${artist ?: "Unknown"}/${album ?: "Unknown"}/$title.$suffix")}")
        }
        return "{" + fields.joinToString(",") + "}"
    }
}

internal class FakeLibrary(val songs: List<FakeSong>, val playlists: List<FakePlaylist>) {
    val albums: List<FakeAlbum> = songs.filter { it.albumId != null }.groupBy { it.albumId!! }.map { (id, list) ->
        val first = list.first()
        FakeAlbum(id, first.album.orEmpty(), first.artist, first.artistId, first.year, first.cover, list)
    }
    val artists: List<Pair<String, String>> = songs.filter { it.artistId != null && it.artist != null }.distinctBy { it.artistId }.map { it.artistId!! to it.artist!! }
    private val byId = songs.associateBy { it.id }
    fun song(id: String) = byId[id]
}

internal class FakeAlbum(val id: String, val name: String, val artist: String?, val artistId: String?, val year: Int?, val cover: String?, val songs: List<FakeSong>) {
    fun json(): String {
        val fields = buildList {
            add("\"id\":${q(id)}")
            add("\"name\":${q(name)}")
            artist?.let { add("\"artist\":${q(it)}") }
            artistId?.let { add("\"artistId\":${q(it)}") }
            year?.let { add("\"year\":$it") }
            add("\"songCount\":${songs.size},\"duration\":${songs.sumOf { it.duration }}")
            cover?.let { add("\"coverArt\":${q(it)}") }
            add("\"created\":\"2026-09-01T10:00:00Z\"")
        }
        return "{" + fields.joinToString(",") + "}"
    }
}

internal class FakePlaylist(val id: String, val name: String, val songIds: List<String>, val comment: String? = null)

// A string as a JSON string, with quotes and backslashes escaped.
private fun q(text: String): String = buildString {
    append('"')
    text.forEach { c ->
        when {
            c == '"' -> append("\\\"")
            c == '\\' -> append("\\\\")
            c < ' ' -> append("\\u%04x".format(c.code))
            else -> append(c)
        }
    }
    append('"')
}

internal object PolishData {
    // Ids the shots reach for by name.
    const val LONG_ALBUM = "al-long"
    const val DISCS_ALBUM = "al-discs"
    const val PLAIN_ALBUM = "al-plain"
    const val BUSY_ARTIST = "ar-many"
    const val LONG_SONG = "long-1"
    const val CJK_SONG = "cjk-1"
    const val MISSING_FILE = "plain-3"
    const val BIG_PLAYLIST = "pl-big"
    const val EMPTY_PLAYLIST = "pl-empty"
    const val SMALL_PLAYLIST = "pl-small"

    // The hand-made hundred.
    fun awkward(): List<FakeSong> {
        val list = mutableListOf<FakeSong>()
        // A plain album, to compare against.
        val plain = listOf("Airbag", "Paranoid Android", "Subterranean Homesick Alien", "Exit Music (For a Film)", "Let Down", "Karma Police", "Fitter Happier", "Electioneering", "Climbing Up the Walls", "No Surprises", "Lucky", "The Tourist")
        plain.forEachIndexed { i, t -> list += FakeSong("plain-${i + 1}", t, "Radiohead", "ar-radiohead", "OK Computer", PLAIN_ALBUM, i + 1, 1, 1997, 200 + i * 13, "c-plain", genre = "Alternative", plays = i * 3, starred = i == 5) }
        // Very long titles, on an album with a very long name, by a very long artist credit.
        val longAlbum = "The Idler Wheel Is Wiser Than the Driver of the Screw and Whipping Cords Will Serve You More Than Ropes Will Ever Do"
        val longTitles = listOf(
            "Come On! Feel the Illinoise! (Part I: The World's Columbian Exposition, Part II: Carl Sandburg Visits Me in a Dream)",
            "The Predatory Wasp of the Palisades Is Out to Get Us! (Remastered 2025 Deluxe Edition Bonus Track)",
            "They Are Night Zombies!! They Are Neighbors!! They Have Come Back from the Dead!! Ahhhh!",
            "To the Workers of the Rock River Valley Region, I Have an Idea Concerning Your Predicament",
            "A Conjunction of Drones Simulating the Way in Which Sufjan Stevens Has an Existential Crisis in the Great Godfrey Maze",
            "Every Day",
        )
        longTitles.forEachIndexed { i, t ->
            list += FakeSong(
                "long-${i + 1}", t, "Sufjan Stevens", "ar-sufjan", longAlbum, LONG_ALBUM, i + 1, 1, 2005, 300 + i * 40, "c-long", genre = "Indie Folk, Chamber Pop, Singer-Songwriter",
                displayArtist = if (i == 0) "Sufjan Stevens, The Illinoisemakers, Shara Worden, Rosie Thomas and the Chicago Children's Choir" else null, plays = 40 - i,
            )
        }
        // Every kind of script: Japanese, Chinese, Korean, Arabic and Hebrew
        // (right to left), emoji with joiners, combining accents.
        val scripts = listOf(
            Triple("宇多田ヒカル", "初恋", listOf("初恋", "誓い", "あなた", "Play A Love Song", "嫉妬されるべき人生")),
            Triple("周杰倫", "范特西", listOf("愛在西元前", "爸我回來了", "簡單愛", "忍者")),
            Triple("아이유", "Palette", listOf("팔레트 (Feat. G-DRAGON)", "이런 엔딩", "밤편지")),
            Triple("فيروز", "كيفك إنت", listOf("كيفك إنت", "مش قصة هاي", "بكتب اسمك يا حبيبي")),
            Triple("עומר אדם", "שמש", listOf("שני משוגעים", "מה זה משנה", "תל אביב")),
            Triple("Emoji Band 🎸", "Family 👨‍👩‍👧‍👦 Album 🏳️‍🌈", listOf("👨‍👩‍👧‍👦 We Are Family 🏳️‍🌈", "🔥🔥🔥", "❤️‍🔥 Burning Heart")),
            Triple("Zoë Keating", "Café Élan", listOf("Café", "Zoë", "Ångström Façade Naïveté", "Z̷a̸l̶g̵o̷ T̶e̵x̸t̷")),
        )
        scripts.forEachIndexed { s, (artist, album, titles) ->
            titles.forEachIndexed { i, t ->
                val id = if (s == 0 && i == 0) CJK_SONG else "script-$s-${i + 1}"
                list += FakeSong(id, t, artist, "ar-script-$s", album, "al-script-$s", i + 1, 1, 2010 + s, 180 + i * 17, "c-script-$s", genre = "World", plays = i)
            }
        }
        // Many artists on one song, and an album on three discs.
        val discs = listOf(
            listOf("Get Lucky", "Lose Yourself to Dance", "Give Life Back to Music", "The Game of Love"),
            listOf("Giorgio by Moroder", "Within", "Instant Crush", "Touch"),
            listOf("Doin' It Right", "Contact", "Horizon", "Beyond"),
        )
        discs.forEachIndexed { d, titles ->
            titles.forEachIndexed { i, t ->
                list += FakeSong(
                    "disc-${d + 1}-${i + 1}", t, "Daft Punk", BUSY_ARTIST, "Random Access Memories (10th Anniversary Edition)", DISCS_ALBUM, i + 1, d + 1, 2013, 240 + i * 30, "c-discs",
                    genre = "Electronic", displayArtist = if (i == 0) "Daft Punk feat. Pharrell Williams & Nile Rodgers" else null, plays = 10 + i,
                )
            }
        }
        // Missing tags: no artist, no album, no year, no cover, no track.
        list += FakeSong("bare-1", "Track 01")
        list += FakeSong("bare-2", "Untitled", artist = "Unknown Artist")
        list += FakeSong("bare-3", "voice memo 2026-03-14 08.12.44", album = "Recordings", albumId = "al-rec", duration = 12)
        list += FakeSong("bare-4", "A song with a cover the server cannot draw", "Nobody", "ar-nobody", "Broken Cover", "al-broken", 1, 1, 2020, cover = "missing-cover")
        // The same song twice, from two albums in two formats.
        list += FakeSong("dup-1", "Holocene", "Bon Iver", "ar-boniver", "Bon Iver, Bon Iver", "al-boniver", 3, 1, 2011, 337, "c-boniver", genre = "Indie")
        list += FakeSong("dup-2", "Holocene", "Bon Iver", "ar-boniver", "Holocene (Single)", "al-holocene", 1, 1, 2011, 336, "c-holocene", suffix = "mp3", genre = "Indie")
        list += FakeSong("dup-3", "Holocene", "Bon Iver", "ar-boniver", "Bon Iver, Bon Iver", "al-boniver", 3, 1, 2011, 337, "c-boniver", genre = "Indie")
        // Filler to a hundred.
        list += filler(100 - list.size, "f")
        return list
    }

    // Plain songs by plain artists, a dozen to an album.
    fun filler(count: Int, prefix: String): List<FakeSong> {
        val words = listOf("Blue", "Night", "River", "Glass", "Summer", "Echo", "Paper", "Light", "Stone", "Silver", "Ghost", "Garden", "Morning", "Static", "Velvet", "Wire", "Harbour", "Neon", "Quiet", "Fire")
        return (0 until count).map { n ->
            val albumN = n / 12
            val artistN = albumN / 4
            val title = "${words[n % words.size]} ${words[(n / 7 + 3) % words.size]}" + if (n % 9 == 0) " (Extended Mix)" else ""
            FakeSong(
                "$prefix-$n", title, "Artist ${artistN + 1}", "ar-$prefix-$artistN", "${words[albumN % words.size]} Album ${albumN + 1}", "al-$prefix-$albumN",
                n % 12 + 1, 1, 1970 + albumN % 55, 150 + (n * 37) % 240, "c-$prefix-${albumN % 40}", genre = listOf("Rock", "Pop", "Jazz", "Electronic", "Folk")[albumN % 5],
                plays = n % 17, added = "2026-0${1 + albumN % 9}-1${n % 9}T10:00:00Z",
            )
        }
    }

    // A library of `size` songs: the awkward hundred first where it has
    // room for them, then filler.
    fun library(size: Int): FakeLibrary {
        val songs = when {
            size <= 0 -> emptyList()
            size == 1 -> listOf(awkward().first { it.id == LONG_SONG })
            size <= 100 -> awkward().take(size)
            else -> awkward() + filler(size - 100, "g")
        }
        val ids = songs.map { it.id }
        val playlists = if (songs.isEmpty()) emptyList() else listOf(
            FakePlaylist(SMALL_PLAYLIST, "Late night", ids.take(12), "For the drive home after midnight"),
            FakePlaylist(BIG_PLAYLIST, "Everything I have ever loved, in the order I found it, from the first cassette onwards", List(2_500) { ids[it % ids.size] }),
            FakePlaylist(EMPTY_PLAYLIST, "Empty for now", emptyList()),
            FakePlaylist("pl-cjk", "夜のドライブ 🌙", ids.filter { it.startsWith("script") }.take(8)),
        )
        return FakeLibrary(songs, playlists)
    }

    // Answers every call the app makes from `library`.
    fun serve(server: FakeServer, library: FakeLibrary, tone: ByteArray) {
        server.answer("ping", type = "octo")
        server.answer("getOpenSubsonicExtensions", """"openSubsonicExtensions":[{"name":"songLyrics","versions":[1]}]""", type = "octo")
        server.answerBy("getAlbumList2") { request ->
            val size = request.url.queryParameter("size")?.toIntOrNull() ?: 10
            val offset = request.url.queryParameter("offset")?.toIntOrNull() ?: 0
            server.ok(""""albumList2":{"album":[${library.albums.drop(offset).take(size).joinToString(",") { it.json() }}]}""")
        }
        server.answer("getArtists", """"artists":{"index":[{"name":"A","artist":[${library.artists.joinToString(",") { (id, name) -> """{"id":${q(id)},"name":${q(name)},"albumCount":${library.albums.count { it.artistId == id }}}""" }}]}]}""")
        server.answerBy("search3") { request ->
            val query = request.url.queryParameter("query").orEmpty().trim('"').lowercase()
            val count = request.url.queryParameter("songCount")?.toIntOrNull() ?: 20
            val offset = request.url.queryParameter("songOffset")?.toIntOrNull() ?: 0
            if (query.isEmpty()) {
                server.ok(""""searchResult3":{"song":[${library.songs.drop(offset).take(count).joinToString(",") { it.json() }}]}""")
            } else {
                val songs = library.songs.filter { query in it.title.lowercase() || query in it.artist.orEmpty().lowercase() }.take(count)
                val albums = library.albums.filter { query in it.name.lowercase() }.take(request.url.queryParameter("albumCount")?.toIntOrNull() ?: 10)
                val artists = library.artists.filter { query in it.second.lowercase() }.take(request.url.queryParameter("artistCount")?.toIntOrNull() ?: 10)
                server.ok(""""searchResult3":{"song":[${songs.joinToString(",") { it.json() }}],"album":[${albums.joinToString(",") { it.json() }}],"artist":[${artists.joinToString(",") { (id, name) -> """{"id":${q(id)},"name":${q(name)}}""" }}]}""")
            }
        }
        server.answer("getStarred2", """"starred2":{"song":[${library.songs.filter { it.starred }.joinToString(",") { it.json() }}]}""")
        server.answer("getPlaylists", """"playlists":{"playlist":[${library.playlists.joinToString(",") { """{"id":${q(it.id)},"name":${q(it.name)},"songCount":${it.songIds.size},"owner":"winters","coverArt":"c-${it.id}"}""" }}]}""")
        server.answerBy("getPlaylist") { request ->
            val playlist = library.playlists.firstOrNull { it.id == request.url.queryParameter("id") } ?: return@answerBy server.failed(70, "Playlist not found")
            val entries = playlist.songIds.mapNotNull(library::song).joinToString(",") { it.json() }
            server.ok(""""playlist":{"id":${q(playlist.id)},"name":${q(playlist.name)},"owner":"winters","public":false,"coverArt":${q("c-" + playlist.id)},${playlist.comment?.let { "\"comment\":${q(it)}," } ?: ""}"songCount":${playlist.songIds.size},"entry":[$entries]}""")
        }
        server.answerBy("getAlbum") { request ->
            val album = library.albums.firstOrNull { it.id == request.url.queryParameter("id") } ?: return@answerBy server.failed(70, "Album not found")
            val titles = if (album.id == DISCS_ALBUM) ""","discTitles":[{"disc":1,"title":"Memories"},{"disc":2,"title":"Random Access"},{"disc":3,"title":"The 10th Anniversary Bonus Disc With a Very Long Name Indeed"}]""" else ""
            server.ok(""""album":${album.json().dropLast(1)},"genres":[{"name":"Electronic"}],"releaseTypes":["Album"]$titles,"song":[${album.songs.joinToString(",") { it.json() }}]}""")
        }
        server.answerBy("getArtist") { request ->
            val id = request.url.queryParameter("id")
            val albums = library.albums.filter { it.artistId == id }
            val name = library.artists.firstOrNull { it.first == id }?.second ?: "Unknown"
            server.ok(""""artist":{"id":${q(id.orEmpty())},"name":${q(name)},"albumCount":${albums.size},"album":[${albums.joinToString(",") { it.json() }}]}""")
        }
        server.answer("getArtistInfo2", """"artistInfo2":{"biography":"A band with a long history, told here in a few sentences so the page has a paragraph to lay out. It runs to several lines on a narrow window and one or two on a wide one.","similarArtist":[]}""")
        server.answerBy("getTopSongs") { request ->
            val name = request.url.queryParameter("artist")
            server.ok(""""topSongs":{"song":[${library.songs.filter { it.artist == name }.take(10).joinToString(",") { it.json() }}]}""")
        }
        server.answer(
            "getLyricsBySongId",
            """"lyricsList":{"structuredLyrics":[{"lang":"en","synced":true,"line":[{"start":0,"value":"A first line of the song"},{"start":4000,"value":"And a second, much longer line that has to wrap across the width of the panel when it is narrow"},{"start":8000,"value":"初恋の歌を歌う"},{"start":12000,"value":"كيفك إنت"}]}]}""",
        )
        server.fail("getPlayQueue", 70, "No queue")
        server.fail("getMusicDirectory", 70, "Not found")
        server.answer("getInternetRadioStations", """"internetRadioStations":{"internetRadioStation":[]}""")
        server.fileBy("getCoverArt") { request ->
            val id = request.url.queryParameter("id").orEmpty()
            // A cover the server has lost: not a picture at all.
            if (id.startsWith("missing")) "not a picture".toByteArray() else cover(id)
        }
        server.file("stream", tone)
    }

    // A cover of soft shapes, in colours and places of its own for each id.
    fun cover(id: String): ByteArray {
        val palette = listOf(0xFF1B2A4A, 0xFFE0703A, 0xFF3AA6A0, 0xFFF2D06B, 0xFF6B3A7A, 0xFFB8C4C9, 0xFF2F5D3A, 0xFFC0463F).map { it.toInt() }
        val seed = id.hashCode() and 0x7fffffff
        fun colour(n: Int) = palette[(seed / (n + 1) + n) % palette.size]
        val surface = Surface.makeRasterN32Premul(300, 300)
        val canvas = surface.canvas
        canvas.clear(colour(0))
        val paint = Paint()
        paint.color = colour(1)
        canvas.drawCircle(60f + seed % 90, 100f, 90f, paint)
        paint.color = colour(2)
        canvas.drawCircle(220f, 140f + seed % 80, 110f, paint)
        paint.color = colour(3)
        canvas.drawRect(Rect.makeXYWH(40f, 210f, 120f + seed % 60f, 60f), paint)
        return surface.makeImageSnapshot().encodeToData(EncodedImageFormat.PNG)!!.bytes
    }
}
