package app.winters.octo.desktop.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import app.winters.octo.desktop.AddQuestion
import app.winters.octo.design.LocalTyping
import app.winters.octo.design.TypingState
import app.winters.octo.desktop.AppState
import app.winters.octo.desktop.FakeServer
import app.winters.octo.desktop.SidePanel
import app.winters.octo.desktop.audio.EnginePlayer
import app.winters.octo.desktop.audio.LocalOrServer
import app.winters.octo.desktop.audio.NativeAudioEngine
import app.winters.octo.desktop.audio.ServerSongs
import app.winters.octo.desktop.audio.writeSine
import app.winters.octo.desktop.lyrics.openLyricsMenu
import app.winters.octo.desktop.removeQueued
import app.winters.octo.desktop.home.AlbumShelf
import app.winters.octo.desktop.listening.LoggedPlay
import app.winters.octo.desktop.listening.logged
import app.winters.octo.desktop.nav.Page
import app.winters.octo.desktop.nav.ScrollSpot
import app.winters.octo.desktop.secrets.SessionOnlySecrets
import app.winters.octo.desktop.server.Accounts
import app.winters.octo.desktop.server.CertificateQuestion
import app.winters.octo.desktop.server.SignInOutcome
import app.winters.octo.desktop.settings.DesktopOs
import app.winters.octo.desktop.settings.SettingsStore
import app.winters.octo.desktop.songJson
import app.winters.octo.lyrics.OnlineLyrics
import app.winters.octo.query.FilterPresets
import app.winters.octo.query.LibraryQuery
import app.winters.octo.query.QueryField
import app.winters.octo.query.QueryOp
import app.winters.octo.query.QueryRule
import app.winters.octo.ui.playlist.planAdd
import app.winters.octo.subsonic.Song
import java.io.File
import javax.swing.SwingUtilities
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.jetbrains.skia.EncodedImageFormat
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.Duration
import java.time.Instant

// Draws the whole window off screen against a pretend server and saves
// pictures of the main pages, for looking at the layout without a display.
// It only runs when asked: OCTO_SHOTS=1 ./gradlew :desktop:test. The
// player is the real audio engine on its silent device.
class ScreenShotsTest {
    @get:Rule val folder = TemporaryFolder()

    @Test
    fun drawTheMainPages() {
        assumeTrue(System.getenv("OCTO_SHOTS") == "1")
        val out = File("build/shots").apply { mkdirs() }
        FakeServer().use { server ->
            // A small library across albums, artists and genres, so the
            // album, artist, genre and folder pages show real structure.
            val made = listOf(
                listOf("Airbag", "a1", "Radiohead", "r1", "Alternative"),
                listOf("Paranoid Android", "a1", "Radiohead", "r1", "Alternative"),
                listOf("Karma Police", "a1", "Radiohead", "r1", "Alternative|Rock"),
                listOf("Everything In Its Right Place", "a2", "Radiohead", "r1", "Electronic|Alternative"),
                listOf("Idioteque", "a2", "Radiohead", "r1", "Electronic"),
                listOf("Roads", "a3", "Portishead", "r2", "Trip Hop"),
                listOf("Glory Box", "a3", "Portishead", "r2", "Trip Hop"),
                listOf("The Rip", "a4", "Portishead", "r2", "Trip Hop"),
                listOf("Teardrop", "a5", "Massive Attack", "r3", "Trip Hop|Electronic"),
                listOf("Angel", "a5", "Massive Attack", "r3", "Trip Hop"),
                listOf("Joga", "a6", "Björk", "r4", "Electronic"),
                listOf("Bachelorette", "a6", "Björk", "r4", "Electronic"),
                listOf("Lucky", "a7", "Radiohead", "r1", "Alternative"),
                listOf("Nude", "a8", "Radiohead", "r1", "Alternative|Electronic"),
            )
            val albumNames = mapOf("a1" to "OK Computer", "a2" to "Kid A", "a3" to "Dummy", "a4" to "Third", "a5" to "Mezzanine", "a6" to "Homogenic", "a7" to "Help!", "a8" to "In Rainbows")
            val songs = made.mapIndexed { index, (title, albumId, artist, artistId, genreList) ->
                val i = index + 1
                val genres = genreList.split("|")
                val album = albumNames.getValue(albumId)
                songJson("s$i", title, artist = artist, album = album, albumId = albumId, duration = 180 + i * 7).dropLast(1) +
                    ""","artistId":"$artistId","coverArt":"al-$albumId","parent":"d-$albumId","path":"$artist/$album/${"%02d".format(i)} $title.flac","suffix":"flac","contentType":"audio/flac","bitDepth":24,"samplingRate":96000,"size":${48_000_000 + i * 1_000_000},"playCount":${i * 3},"genre":"${genres.first()}","genres":[${genres.joinToString(",") { """{"name":"$it"}""" }}],"year":1997,"track":$i,"discNumber":1,"bpm":${70 + i},"created":"2026-08-0${1 + i % 9}T10:00:00Z","played":"2026-09-27T21:${10 + i}:00Z","replayGain":{"trackGain":-7.4,"trackPeak":0.998,"albumGain":-8.1,"albumPeak":1.0}}"""
            }.joinToString(",")
            val albums = listOf(
                "a1|OK Computer|Radiohead|r1|1997", "a2|Kid A|Radiohead|r1|2000", "a3|Dummy|Portishead|r2|1994",
                "a4|Third|Portishead|r2|2008", "a5|Mezzanine|Massive Attack|r3|1998", "a6|Homogenic|Björk|r4|1997",
                "a7|Help!|Various Artists|va|1995", "a8|In Rainbows|Radiohead|r1|2007", "a9|Amnesiac|Radiohead|r1|2001",
                "a10|Hail to the Thief|Radiohead|r1|2003", "a11|Protection|Massive Attack|r3|1994", "a12|Post|Björk|r4|1995",
            ).mapIndexed { index, line ->
                val (id, name, artist, artistId, year) = line.split("|")
                """{"id":"$id","name":"$name","artist":"$artist","artistId":"$artistId","year":$year,"songCount":10,"duration":2700,"coverArt":"al-$id","playCount":${(12 - index) * 4},"created":"2026-0${1 + index % 9}-1${index % 9}T10:00:00Z"}"""
            }.joinToString(",")
            // OK Computer as its page reads it: two discs, each named.
            val okTitles = listOf("Airbag", "Paranoid Android", "Subterranean Homesick Alien", "Exit Music (For a Film)", "Let Down", "Karma Police", "Fitter Happier", "Electioneering", "Climbing Up the Walls", "No Surprises", "Lucky", "The Tourist")
            val okSongs = okTitles.mapIndexed { index, title ->
                val disc = if (index < 6) 1 else 2
                val track = index % 6 + 1
                songJson("ok${index + 1}", title, artist = "Radiohead", album = "OK Computer", albumId = "a1", duration = 200 + index * 11).dropLast(1) +
                    ""","artistId":"r1","coverArt":"al-a1","parent":"d-a1","path":"Radiohead/OK Computer/$disc-${"%02d".format(track)} $title.flac","suffix":"flac","contentType":"audio/flac","bitDepth":24,"samplingRate":96000,"track":$track,"discNumber":$disc,"playCount":${(index * 7) % 23},"year":1997,"genre":"Alternative"}"""
            }.joinToString(",")
            val demoTitles = listOf("I Promise", "Man of War", "Lift", "Lull", "Meeting in the Aisle", "Melatonin", "A Reminder", "Polyethylene", "Pearly", "Palo Alto", "How I Made My Millions", "Airbag (Live)")
            val folderSongs = okSongs + "," + demoTitles.mapIndexed { index, title ->
                songJson("ok${index + 13}", title, artist = "Radiohead", album = "OK Computer", albumId = "a1", duration = 190 + index * 9).dropLast(1) +
                    ""","artistId":"r1","coverArt":"al-a1","parent":"d-a1","path":"Radiohead/OK Computer/3-${"%02d".format(index + 1)} $title.flac","suffix":"flac","contentType":"audio/flac","bitDepth":24,"samplingRate":96000,"track":${index + 1},"discNumber":3}"""
            }.joinToString(",")
            // Radiohead's releases, of every kind the artist page shelves.
            val releases = listOf(
                "a1|OK Computer|1997|Album", "a2|Kid A|2000|Album", "a8|In Rainbows|2007|Album", "a9|Amnesiac|2001|Album",
                "a10|Hail to the Thief|2003|Album", "a13|Creep|1992|Single", "a14|My Iron Lung|1994|EP",
                "a15|No Surprises|1998|Single", "a16|I Might Be Wrong|2001|Album,Live", "a17|The Best Of|2008|Album,Compilation",
            ).joinToString(",") { line ->
                val (id, name, year, types) = line.split("|")
                val typeList = types.split(",").joinToString(",") { "\"$it\"" }
                """{"id":"$id","name":"$name","artist":"Radiohead","artistId":"r1","year":$year,"songCount":10,"duration":2600,"coverArt":"al-$id","releaseTypes":[$typeList]}"""
            }
            val similar = listOf("r2" to "Portishead", "r3" to "Massive Attack", "r4" to "Björk", "r5" to "Muse", "r6" to "Blur", "r7" to "Sigur Rós", "r8" to "Air", "r9" to "Beck")
                .joinToString(",") { (id, name) -> """{"id":"$id","name":"$name","coverArt":"ar-$id","albumCount":3}""" }
            val biography = "Radiohead are an English rock band formed in Abingdon, Oxfordshire, in 1985. The band is <b>Thom Yorke</b>, the brothers Jonny and Colin Greenwood, Ed O&#39;Brien and Philip Selway. " +
                "Their third album, OK Computer, made them one of the best known bands of the late 1990s; Kid A and Amnesiac then turned toward electronic music, and In Rainbows was first sold for whatever listeners chose to pay. " +
                "They have sold more than 30 million albums. <a href='https://www.last.fm/music/Radiohead'>Read more on Last.fm</a>"
            // Songs of an album last played a year ago, for Home's "Not played in 6 months".
            val older = (1..3).joinToString(",") { i ->
                songJson("o$i", "Old song $i", artist = "Radiohead", album = "Album number 3", albumId = "a3", duration = 200).dropLast(1) + ""","playCount":6,"played":"2025-09-0${i}T20:00:00Z"}"""
            }
            server.answer("ping", type = "octo")
            server.answer("getOpenSubsonicExtensions", """"openSubsonicExtensions":[{"name":"songLyrics","versions":[1]},{"name":"octoAcquisitions","versions":[1]}]""", type = "octo")
            server.answer("getAlbumList2", """"albumList2":{"album":[$albums]}""")
            server.answer("getArtists", """"artists":{"index":[{"name":"R","artist":[{"id":"r1","name":"Radiohead","albumCount":9},{"id":"r2","name":"Portishead","albumCount":3,"coverArt":"ar-r2"},{"id":"r3","name":"Massive Attack","albumCount":2,"coverArt":"ar-r3"},{"id":"r4","name":"Björk","albumCount":2,"coverArt":"ar-r4"}]}]}""")
            server.answer("search3", """"searchResult3":{"song":[$songs,$older],"album":[{"id":"a1","name":"OK Computer","artist":"Radiohead"}],"artist":[{"id":"r1","name":"Radiohead"}]}""")
            server.answer("getStarred2", """"starred2":{"album":[{"id":"a5","name":"Album number 5","artist":"Radiohead","starred":"2026-09-01T00:00:00Z"},{"id":"a8","name":"Album number 8","artist":"Radiohead","starred":"2026-09-10T00:00:00Z"}]}""")
            server.answer("getPlaylists", """"playlists":{"playlist":[{"id":"p1","name":"Late night","songCount":12},{"id":"p2","name":"Running","songCount":40}]}""")
            server.answer("getAlbum", """"album":{"id":"a1","name":"OK Computer","artist":"Radiohead","artistId":"r1","year":1997,"songCount":12,"coverArt":"al-a1","genres":[{"name":"Alternative"}],"releaseTypes":["Album"],"discTitles":[{"disc":1,"title":"OK Computer"},{"disc":2,"title":"The other side"}],"song":[$okSongs]}""")
            server.answer("getPlaylist", """"playlist":{"id":"p1","name":"Late night","owner":"winters","comment":"For the drive home after midnight","public":false,"songCount":12,"entry":[$songs]}""")
            server.answer("getArtist", """"artist":{"id":"r1","name":"Radiohead","albumCount":10,"album":[$releases]}""")
            server.answer("getArtistInfo2", """"artistInfo2":{"biography":"$biography","similarArtist":[$similar]}""")
            server.answer("getTopSongs", """"topSongs":{"song":[$okSongs]}""")
            // The server's folders: Music, then Radiohead, then OK Computer.
            server.answerBy("getMusicDirectory") { request ->
                when (request.url.queryParameter("id")) {
                    "music" -> server.ok(""""directory":{"id":"music","name":"Music","child":[{"id":"d-r1","isDir":true,"title":"Radiohead"},{"id":"d-r2","isDir":true,"title":"Portishead"}]}""")
                    "d-r1" -> server.ok(""""directory":{"id":"d-r1","name":"Radiohead","parent":"music","child":[{"id":"d-a1","isDir":true,"title":"OK Computer","coverArt":"al-a1","songCount":12},{"id":"d-a2","isDir":true,"title":"Kid A","coverArt":"al-a2","songCount":10},{"id":"d-a8","isDir":true,"title":"In Rainbows","coverArt":"al-a8"}]}""")
                    "d-a1" -> server.ok(""""directory":{"id":"d-a1","name":"OK Computer","parent":"d-r1","child":[{"id":"d-a1-b","isDir":true,"title":"Demos","songCount":3},$folderSongs]}""")
                    else -> server.failed(70, "Directory not found")
                }
            }
            server.answer("getInternetRadioStations", """"internetRadioStations":{"internetRadioStation":[{"id":"st1","name":"Discover Weekly"},{"id":"st2","name":"Rock mix"}]}""")
            // The phone's queue, saved on the server, for Home's pick-up card.
            server.answer("getPlayQueue", """"playQueue":{"entry":[${songs}],"current":"s3","position":61000,"changed":"2026-09-28T10:00:00Z","changedBy":"Pixel 9"}""")
            server.answer("getLyricsBySongId", """"lyricsList":{"structuredLyrics":[{"lang":"en","synced":true,"line":[{"start":0,"value":"Karma police"},{"start":4000,"value":"Arrest this man"},{"start":8000,"value":"He talks in maths"}]}]}""")

            // A made-up cover, and a quiet tone for every song, so the engine
            // really plays (on its silent device) and the lyrics move.
            server.fileBy("getCoverArt") { madeUpCover(it.url.queryParameter("id").orEmpty()) }
            val tone = File(folder.root, "tone.wav").also { writeSine(it, seconds = 30) }
            server.file("stream", tone.readBytes())

            val settings = SettingsStore(File(folder.root, "settings.json"))
            // Never the real online lyrics library.
            settings.update { it.copy(lyrics = it.lyrics.copy(online = false)) }
            val http = OkHttpClient()
            val accounts = Accounts(settings, SessionOnlySecrets(), http)
            lateinit var app: AppState
            val player = EnginePlayer(NativeAudioEngine.open(silent = true), LocalOrServer(ServerSongs { app.connection?.client }))
            SwingUtilities.invokeAndWait {
                app = AppState(settings, accounts, http, CoroutineScope(SupervisorJob() + Dispatchers.Main), DesktopOs.Windows, player, OnlineLyrics(http, server.address.toHttpUrl()), listeningRoot = File(folder.root, "listening"))
            }
            val scene = ImageComposeScene(1440, 900, Density(1f)) {
                CompositionLocalProvider(LocalTyping provides TypingState()) { Shell(app, null) {} }
            }
            // Draws for a while and saves the picture; with no name, only draws.
            fun shot(name: String?, settleMs: Long = 1_500) {
                val begin = System.currentTimeMillis()
                val end = begin + settleMs
                // The scene's clock follows real time, so animations finish
                // however long a frame takes to draw off screen.
                var t = 0L
                while (System.currentTimeMillis() < end) {
                    SwingUtilities.invokeAndWait { scene.render(t) }
                    Thread.sleep(30)
                    t = (System.currentTimeMillis() - begin) * 1_000_000
                }
                val image = scene.render(t)
                if (name != null) File(out, "$name.png").writeBytes(image.encodeToData(EncodedImageFormat.PNG)!!.bytes)
            }
            // Turns the mouse wheel over the page, `clicks` times, then moves
            // the pointer off the page so no row is lit under it.
            fun wheel(clicks: Int) {
                repeat(clicks) {
                    SwingUtilities.invokeAndWait {
                        scene.sendPointerEvent(androidx.compose.ui.input.pointer.PointerEventType.Scroll, androidx.compose.ui.geometry.Offset(660f, 500f), scrollDelta = androidx.compose.ui.geometry.Offset(0f, 1f))
                    }
                }
                SwingUtilities.invokeAndWait { scene.sendPointerEvent(androidx.compose.ui.input.pointer.PointerEventType.Move, androidx.compose.ui.geometry.Offset(1270f, 600f)) }
            }
            // The sign-in, filled in; then asking about a certificate; then
            // with Advanced open.
            SwingUtilities.invokeAndWait {
                app.signInForm.typeAddress("192.168.1.20:4533")
                app.signInForm.username = "winters"
                app.signInForm.password = "pw"
            }
            shot("signin", 2_000)
            SwingUtilities.invokeAndWait {
                app.signInForm.typeAddress("music.example.com")
                app.signInForm.question = CertificateQuestion("music.example.com", "d693f076d6d65fcb023dd052e3637561a6772c9cb3c51f9799d3b94e3c655443")
            }
            shot("signin-certificate")
            SwingUtilities.invokeAndWait {
                app.popups.close()
                app.signInForm.result = null
                app.signInForm.advancedOpen = true
                app.signInForm.home = "192.168.1.20:4533"
                app.signInForm.addHeader()
                app.signInForm.setHeaderName(0, "X-Access-Token")
                app.signInForm.setHeaderValue(0, "secret")
            }
            shot("signin-advanced")
            val done = runBlocking { accounts.signIn(server.address, "winters", "pw") } as SignInOutcome.Done
            SwingUtilities.invokeAndWait { app.signedIn(done.connection) }
            shot("home", 3_000)
            // Further down Home: playlists and the rediscovery shelves, by
            // leaving it scrolled and coming back.
            val home = app.navigator.current
            SwingUtilities.invokeAndWait { app.navigator.go(Page.Songs) }
            shot(null, 300)
            SwingUtilities.invokeAndWait {
                app.navigator.keepScroll(home, ScrollSpot(7))
                app.navigator.back()
            }
            shot("home-more")
            SwingUtilities.invokeAndWait { app.navigator.go(Page.Shelf(AlbumShelf.NeverPlayed)) }
            shot("shelf")
            // Recently played: two plays logged here, the rest from the server.
            SwingUtilities.invokeAndWait {
                val log = app.plays.log()!!
                val library = app.library!!.index!!.songs
                log.add(LoggedPlay(System.currentTimeMillis() - 20 * 60_000, 180_000, library[0].logged()))
                log.add(LoggedPlay(System.currentTimeMillis() - 5 * 60_000, 180_000, library[3].logged()))
                app.navigator.go(Page.History)
            }
            shot("history")
            SwingUtilities.invokeAndWait {
                val list = app.library?.index?.songs.orEmpty()
                app.play(list, 0)
                app.toggleSidePanel(SidePanel.Queue)
                app.navigator.go(Page.Songs)
            }
            shot("songs")
            // Two filters on as pills, and the count of what is left.
            SwingUtilities.invokeAndWait {
                val rules = listOf(FilterPresets.Lossless, QueryRule(QueryField.Artist, QueryOp.Is, text = "Radiohead"))
                app.navigator.keepFilter(app.navigator.current, LibraryQuery(rules))
            }
            shot("filters")
            SwingUtilities.invokeAndWait {
                val visit = app.navigator.current
                showAddFilter(app, androidx.compose.ui.unit.IntRect(760, 142, 850, 170), app.navigator.filterOf(visit), { app.navigator.keepFilter(visit, it) }, app.library!!.index!!.songs)
            }
            shot("filters-menu")
            SwingUtilities.invokeAndWait { app.popups.close() }
            SwingUtilities.invokeAndWait {
                app.navigator.keepFilter(app.navigator.current, LibraryQuery(listOf(FilterPresets.NeverPlayed), text = "karma"))
            }
            shot("filters-empty")
            SwingUtilities.invokeAndWait { app.navigator.keepFilter(app.navigator.current, LibraryQuery()) }
            // A song that would not play: the notice line, with details, and its row marked.
            SwingUtilities.invokeAndWait {
                val failed = app.library!!.index!!.songs[1]
                app.failedSongs[failed.id] = "That song isn't on the server any more."
                app.notice = "Skipped ${failed.title}. That song isn't on the server any more."
                app.noticeDetail = "HTTP 404 from the server for the song's address"
            }
            shot("failure")
            SwingUtilities.invokeAndWait {
                app.notice = null
                app.noticeDetail = null
            }
            SwingUtilities.invokeAndWait { app.showSidePanel(SidePanel.Info) }
            shot("info")
            SwingUtilities.invokeAndWait { app.sleep.start(30) }
            shot("sleep")
            SwingUtilities.invokeAndWait { app.sleep.cancel() }
            SwingUtilities.invokeAndWait {
                app.toggleSidePanel(SidePanel.Lyrics)
                app.navigator.go(Page.Album("a1"))
            }
            shot("album")
            wheel(40)
            shot("album-foot")
            SwingUtilities.invokeAndWait { app.navigator.go(Page.Artist("r1", "Radiohead")) }
            shot("artist", 2_500)
            wheel(12)
            shot("artist-shelves")
            wheel(40)
            shot("artist-foot", 2_000)
            SwingUtilities.invokeAndWait { app.navigator.go(Page.Genres) }
            shot("genres", 2_000)
            SwingUtilities.invokeAndWait { app.navigator.go(Page.Genre("Trip Hop")) }
            shot("genre", 2_000)
            // Show in folder: the song's folder, the way up found from its
            // parents, and the list started at the song.
            SwingUtilities.invokeAndWait { app.navigator.go(Page.Folder("d-a1", "", focus = "ok21")) }
            shot("folder", 2_500)
            // A server that will not list its folders says so, with a way on.
            SwingUtilities.invokeAndWait { app.navigator.go(Page.Folders) }
            shot("folders-error")
            SwingUtilities.invokeAndWait {
                app.toggleSidePanel(SidePanel.Lyrics)
                app.navigator.go(Page.Albums)
            }
            shot("albums")
            SwingUtilities.invokeAndWait {
                app.updateFrame { it.copy(sidebarRail = true) }
                app.navigator.go(Page.RecentlyAdded)
            }
            shot("rail")
            SwingUtilities.invokeAndWait { app.updateFrame { it.copy(sidebarRail = false) } }
            SwingUtilities.invokeAndWait {
                app.navigator.go(Page.Search)
                app.search?.type("radiohead")
            }
            shot("search", 2_000)
            SwingUtilities.invokeAndWait {
                app.navigator.go(Page.Songs)
                app.openSearch()
                app.search?.type("radiohead")
            }
            shot("omnibox", 2_000)
            SwingUtilities.invokeAndWait { app.search?.type(">sle") }
            shot("commands")
            SwingUtilities.invokeAndWait {
                app.search?.type("")
                app.omnibox.open = false
            }
            SwingUtilities.invokeAndWait {
                app.navigator.go(Page.Songs)
                app.popups.showAt(androidx.compose.ui.unit.IntOffset(700, 300)) { close -> SongMenu(app, app.library!!.index!!.songs.take(1), close) }
            }
            shot("menu")
            SwingUtilities.invokeAndWait {
                app.popups.showAt(androidx.compose.ui.unit.IntOffset(700, 300)) { close -> ArtistMenu(app, "r1", "Radiohead", null, close) }
            }
            shot("menu-artist")
            // A playlist's page, then its menu, then the chooser asking about
            // songs already on a playlist, then the new playlist form.
            SwingUtilities.invokeAndWait {
                app.popups.close()
                app.navigator.go(Page.Playlist("p1"))
            }
            shot("playlist", 2_000)
            SwingUtilities.invokeAndWait {
                app.popups.showAt(androidx.compose.ui.unit.IntOffset(560, 250)) { close -> PlaylistMenu(app, app.playlists.first(), close) }
            }
            shot("playlist-menu")
            SwingUtilities.invokeAndWait {
                val picked = app.library!!.index!!.songs.take(5)
                val question = AddQuestion(
                    app.playlists.first(),
                    planAdd(picked.map { it.id }, picked.take(2).map { it.id }.toSet()),
                    picked,
                )
                app.popups.showAt(androidx.compose.ui.unit.IntOffset(700, 300)) { close -> AddAgainMenu(app, question, close) { } }
            }
            shot("duplicates")
            SwingUtilities.invokeAndWait {
                app.popups.close()
                newPlaylist(app)
            }
            shot("new-playlist")
            SwingUtilities.invokeAndWait {
                app.popups.close()
                app.navigator.go(Page.Settings)
            }
            shot("settings")
            SwingUtilities.invokeAndWait { app.navigator.go(Page.Sound) }
            shot("sound")
            SwingUtilities.invokeAndWait {
                app.navigator.go(Page.Songs)
                app.toggleSidePanel(SidePanel.Lyrics)
            }
            shot("lyrics", 2_500)
            SwingUtilities.invokeAndWait {
                openLyricsMenu(app, app.lyrics.state.value.song!!, androidx.compose.ui.unit.IntRect(1380, 60, 1412, 92))
            }
            shot("lyrics-menu")
            SwingUtilities.invokeAndWait {
                app.popups.close()
                app.fullPlayer = true
            }
            shot("player", 4_000)
            SwingUtilities.invokeAndWait { app.togglePlayerPanel(SidePanel.Queue) }
            shot("player-queue")
            // The queue in its parts: played, now playing, the listener's
            // own, then the rest of the album; then a song's menu there and
            // the queue's own menu.
            SwingUtilities.invokeAndWait {
                app.togglePlayerPanel(SidePanel.Queue)
                app.fullPlayer = false
                app.navigator.go(Page.Album("a1"))
                val list = app.library!!.index!!.songs
                app.play(list.filter { it.albumId == "a1" }.sortedBy { it.track }, 4, source = "OK Computer")
                app.playNext(list.filter { it.albumId == "a2" }.take(2))
                app.showSidePanel(SidePanel.Queue)
            }
            shot("queue", 2_000)
            // A click (or a right click) at a spot in the window, with a
            // frame drawn between each step, as a real pointer would allow.
            fun click(x: Float, y: Float, right: Boolean = false) {
                val at = Offset(x, y)
                val button = if (right) PointerButton.Secondary else PointerButton.Primary
                val steps = listOf(
                    { scene.sendPointerEvent(PointerEventType.Move, at) },
                    { scene.sendPointerEvent(PointerEventType.Press, at, buttons = PointerButtons(isPrimaryPressed = !right, isSecondaryPressed = right), button = button) },
                    { scene.sendPointerEvent(PointerEventType.Release, at, buttons = PointerButtons(), button = button) },
                )
                steps.forEach { step ->
                    SwingUtilities.invokeAndWait {
                        step()
                        scene.render()
                    }
                }
            }
            // A song under "Next from you", then the queue's More button.
            click(1250f, 463f, right = true)
            shot("queue-menu")
            SwingUtilities.invokeAndWait { app.popups.close() }
            click(1418f, 107f)
            shot("queue-options")
            // Taking a song out: the notice line, with Undo.
            SwingUtilities.invokeAndWait {
                app.popups.close()
                app.removeQueued(listOf(app.player.state.value.upcoming.last().key))
            }
            shot("queue-undo")
            // A song the server found online, not in the library: the
            // floating player offers its "+" in place of the heart.
            SwingUtilities.invokeAndWait {
                app.fullPlayer = false
                app.play(listOf(Song("x9", "A song found online", artist = "Someone new", duration = 200)))
            }
            shot("player-outside", 2_000)
            scene.close()
            player.close()
        }
    }

    // A cover of soft coloured shapes, as a PNG, in colours and places of
    // its own for each cover id.
    private fun madeUpCover(id: String = ""): ByteArray {
        val palette = listOf(0xFF1B2A4A, 0xFFE0703A, 0xFF3AA6A0, 0xFFF2D06B, 0xFF6B3A7A, 0xFFB8C4C9, 0xFF2F5D3A, 0xFFC0463F).map { it.toInt() }
        val seed = id.hashCode() and 0x7fffffff
        fun colour(n: Int) = palette[(seed / (n + 1) + n) % palette.size]
        val surface = org.jetbrains.skia.Surface.makeRasterN32Premul(300, 300)
        val canvas = surface.canvas
        canvas.clear(colour(0))
        val paint = org.jetbrains.skia.Paint()
        paint.color = colour(1)
        canvas.drawCircle(60f + seed % 90, 100f, 90f, paint)
        paint.color = colour(2)
        canvas.drawCircle(220f, 140f + seed % 80, 110f, paint)
        paint.color = colour(3)
        canvas.drawRect(org.jetbrains.skia.Rect.makeXYWH(40f, 210f, 120f + seed % 60f, 60f), paint)
        return surface.makeImageSnapshot().encodeToData(EncodedImageFormat.PNG)!!.bytes
    }
}
