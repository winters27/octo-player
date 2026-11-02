package app.winters.octo.desktop.ui

import androidx.compose.ui.ImageComposeScene
import app.winters.octo.desktop.FakeServer
import app.winters.octo.desktop.SidePanel
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.SwingUtilities

// The downloads drawer as it looks: the list with a download on its way, a
// higher quality copy, a picked one and a failure; the pill beside the
// player while the drawer is closed; a finished log and a running one; and Find songs
// with its copies. Only when asked:
// OCTO_SHOTS=1 ./gradlew :desktop:test --tests '*DownloadsShotsTest*'.
// Saved under build/shots/polish/downloads/.
class DownloadsShotsTest {
    @get:Rule val folder = TemporaryFolder()

    private fun row(key: String, title: String, artist: String, album: String, state: String, extra: String = "", kind: String = "download") =
        """{"id":"${key.substringAfter(':')}","artist":"$artist","title":"$title","album":"$album","state":"$state","source":"Soulseek","startedAt":"2026-10-04T18:0${key.last()}:00Z","updatedAt":"2026-10-04T18:09:00Z","key":"$key","kind":"$kind"$extra}"""

    // A row as getAcquisition sends it, with its log.
    private fun withLog(row: String, events: List<String>) = """"acquisition":${row.dropLast(1)},"event":[${events.joinToString(",")}]}"""

    // Around the World's log so far, as it downloads.
    private val aroundTheWorld = listOf(
        """{"at":"2026-10-04T18:01:00Z","kind":"queued","text":"Asked for","detail":"By winters"}""",
        """{"at":"2026-10-04T18:01:01Z","kind":"search","text":"Looking on Soulseek"}""",
        """{"at":"2026-10-04T18:01:01Z","kind":"search","text":"Searching Soulseek for \"Daft Punk Around the World\""}""",
        """{"at":"2026-10-04T18:01:30Z","kind":"found","text":"2 copies fit, best first","detail":"131 files from 35 peers","candidate":[
            {"source":"Soulseek","peer":"vinylhead","file":"07 - Around the World.flac","folder":"Daft Punk/Homework (1997)","title":"Around the World","album":"Homework (1997)","format":"flac","quality":"FLAC 16-bit 44.1 kHz","size":46500000,"length":429,"freeSlot":true,"speed":2100000,"rank":1},
            {"source":"Soulseek","peer":"crate_digger","file":"Daft Punk - Around the World.flac","title":"Daft Punk - Around the World","format":"flac","quality":"FLAC","size":46100000,"length":430,"queueLength":4,"speed":800000,"rank":2}]}""",
        """{"at":"2026-10-04T18:01:30Z","kind":"try","text":"Trying choice 1 of 2","detail":"lossless (FLAC 16-bit 44.1 kHz), the peer can send it now, 2.0 MB/s, 44.3 MB"}""",
        """{"at":"2026-10-04T18:01:32Z","kind":"transfer","text":"Downloading from Soulseek (vinylhead)","detail":"44.3 MB"}""",
    )

    private val log = listOf(
        """{"at":"2026-10-04T18:00:00Z","kind":"queued","text":"Asked for","detail":"By winters"}""",
        """{"at":"2026-10-04T18:00:01Z","kind":"search","text":"Looking on Soulseek"}""",
        """{"at":"2026-10-04T18:00:01Z","kind":"search","text":"Searching Soulseek for \"Daft Punk Da Funk\""}""",
        """{"at":"2026-10-04T18:00:31Z","kind":"found","text":"3 copies fit, best first","detail":"143 files from 37 peers","candidate":[
            {"source":"Soulseek","peer":"vinylhead","file":"03 - Da Funk.flac","folder":"Daft Punk/Homework (1997)","title":"Da Funk","album":"Homework (1997)","format":"flac","quality":"FLAC 16-bit 44.1 kHz","size":36100000,"length":329,"freeSlot":true,"speed":2100000,"rank":1},
            {"source":"Soulseek","peer":"crate_digger","file":"Daft Punk - Da Funk.flac","title":"Daft Punk - Da Funk","format":"flac","quality":"FLAC","size":35800000,"length":330,"queueLength":4,"speed":800000,"rank":2}]}""",
        """{"at":"2026-10-04T18:00:31Z","kind":"try","text":"Trying choice 1 of 3","detail":"lossless (FLAC 16-bit 44.1 kHz), the peer can send it now, 2.0 MB/s, 34.4 MB"}""",
        """{"at":"2026-10-04T18:00:33Z","kind":"transfer","text":"Downloading from Soulseek (vinylhead)","detail":"34.4 MB"}""",
        """{"at":"2026-10-04T18:00:51Z","kind":"check","text":"Checking the file"}""",
        """{"at":"2026-10-04T18:00:55Z","kind":"check","text":"Passed the checks","detail":"the right length; AcoustID: the same recording; the spectrum: really lossless"}""",
        """{"at":"2026-10-04T18:00:56Z","kind":"tags","text":"Identified as Daft Punk - Da Funk","detail":"on Homework (1997); strong match; AcoustID agrees"}""",
        """{"at":"2026-10-04T18:00:58Z","kind":"cover","text":"Cover from iTunes","detail":"1500 x 1500 px, embedded in the song"}""",
        """{"at":"2026-10-04T18:00:58Z","kind":"tags","text":"Tags written","detail":"Daft Punk - Da Funk.flac"}""",
        """{"at":"2026-10-04T18:00:58Z","kind":"lyrics","text":"Looking for lyrics","detail":"In the background, so the next download need not wait"}""",
        """{"at":"2026-10-04T18:00:59Z","kind":"library","text":"In the library folder","detail":"Waiting for Navidrome to show it"}""",
        """{"at":"2026-10-04T18:01:02Z","kind":"lyrics","text":"Synced lyrics from LRCLIB","detail":"Embedded in the song"}""",
        """{"at":"2026-10-04T18:01:04Z","kind":"done","text":"In your library"}""",
    )

    @Test
    fun drawTheDownloadsDrawer() {
        assumeTrue(System.getenv("OCTO_SHOTS") == "1")
        FakeServer().use { server ->
            PolishShotsTest.Rig(folder, PolishData.library(100), server).use { rig ->
                server.answer(
                    "getOpenSubsonicExtensions",
                    """"openSubsonicExtensions":[{"name":"songLyrics","versions":[1]},{"name":"octoAcquisitions","versions":[1,2]},{"name":"octoLibraryActions","versions":[1,2]}]""",
                    type = "octo",
                )
                server.answer("getLibraryActions", """"libraryActions":{"enabled":true,"allowed":true,"dryRun":false,"actions":["remove","upgrade"],"keepDays":30,"parallel":3,"upgradeSource":"Soulseek"}""", type = "octo")
                server.answer(
                    "getAcquisitions",
                    """"acquisitions":{"acquisition":[
                        ${row("soulseek:a1", "Around the World", "Daft Punk", "Homework", "downloading", ""","progress":0.62,"quality":"FLAC 16-bit 44.1 kHz","peer":"vinylhead","coverArt":"$HOMEWORK_COVER"""")},
                        ${row("soulseek:a2", "Teardrop", "Massive Attack", "Mezzanine", "searching", ""","note":"Soulseek couldn't get it, trying YouTube"""")},
                        ${row("soulseek:a3", "Sexy Boy", "Air", "Moon Safari", "queued", ""","ahead":2""")},
                        ${row("soulseek:a4", "Holocene", "Bon Iver", "Bon Iver, Bon Iver", "done", kind = "upgrade")},
                        ${row("soulseek:a5", "Da Funk", "Daft Punk", "Homework", "done", ""","quality":"FLAC 16-bit 44.1 kHz","coverArt":"$HOMEWORK_COVER"""", kind = "pick")},
                        ${row("soulseek:a6", "Windowlicker", "Aphex Twin", "Windowlicker", "failed", ""","error":"No Soulseek FLAC found for 'Aphex Twin - Windowlicker'"""")}
                    ]}""",
                    type = "octo",
                )
                server.answer(
                    "getUpgrades",
                    """"upgrades":[{"id":"nd-9","title":"Holocene","artist":"Bon Iver","state":"upgraded","detail":"Now FLAC 16-bit 44.1 kHz, 31.0 MB, was MP3 220 kbps, 7.1 MB.","acquisition":"soulseek:a4","updatedAt":"2026-10-04T18:08:00Z"}]""",
                    type = "octo",
                )
                // Da Funk's whole story, and Around the World as it downloads.
                server.answerBy("getAcquisition") { request ->
                    if (request.url.queryParameter("key") == "soulseek:a1") {
                        server.ok(withLog(row("soulseek:a1", "Around the World", "Daft Punk", "Homework", "downloading", ""","progress":0.62,"quality":"FLAC 16-bit 44.1 kHz","peer":"vinylhead","coverArt":"$HOMEWORK_COVER""""), aroundTheWorld), type = "octo")
                    } else {
                        server.ok(withLog(row("soulseek:a5", "Da Funk", "Daft Punk", "Homework", "done", ""","quality":"FLAC 16-bit 44.1 kHz","peer":"vinylhead","libraryId":"nd-5","coverArt":"$HOMEWORK_COVER"""", kind = "pick"), log), type = "octo")
                    }
                }
                server.answer(
                    "findSongs",
                    """"foundSongs":{"id":"f1","state":"done","song":{"artist":"Bon Iver","title":"Holocene","album":"Bon Iver, Bon Iver","duration":337,"libraryId":"nd-9","format":"mp3","quality":"MP3 220 kbps","size":7100000},
                    "source":[{"name":"Soulseek","state":"done","text":"96 files from 31 peers; 4 fit the song","query":["Bon Iver Holocene"]},{"name":"Lidarr","state":"off","text":"Lidarr is not set up on this server"}],
                    "candidate":[
                     {"source":"Soulseek","peer":"northwoods","file":"03 Holocene.flac","folder":"Bon Iver/Bon Iver, Bon Iver","title":"Holocene","album":"Bon Iver, Bon Iver","format":"flac","quality":"FLAC 16-bit 44.1 kHz","size":38200000,"length":337,"freeSlot":true,"speed":1800000,"rank":1,"index":0},
                     {"source":"Soulseek","peer":"eau_claire","file":"Bon Iver - Holocene.flac","title":"Bon Iver - Holocene","format":"flac","quality":"FLAC 24-bit 96 kHz","size":121000000,"length":337,"queueLength":2,"speed":900000,"rank":2,"index":1},
                     {"source":"Soulseek","peer":"tapes","file":"Holocene (Live at AIR).flac","folder":"Bon Iver/Live","title":"Holocene (Live at AIR)","format":"flac","quality":"FLAC","size":40100000,"length":401,"queueLength":0,"note":"6:41 long; the song is 5:37","index":2},
                     {"source":"Soulseek","peer":"mp3land","file":"Holocene.mp3","title":"Holocene","format":"mp3","quality":"MP3 320 kbps","size":13400000,"length":337,"note":"Not FLAC, which Octo looks for first","index":3}]}""",
                    type = "octo",
                )
                rig.signIn()
                val app = rig.app
                val end = System.currentTimeMillis() + 10_000
                while (app.downloads?.rows?.value?.size != 6 && System.currentTimeMillis() < end) Thread.sleep(50)
                check(app.downloads?.rows?.value?.size == 6) { "the drawer never read the list" }
                rig.scene(PolishShotsTest.Size.Hd) { scene ->
                    rig.reset(scene)
                    // Panel closed: the pill beside the player says what is on its way.
                    rig.shot(scene, "downloads/pill", 1_500)
                    SwingUtilities.invokeAndWait { app.showSidePanel(SidePanel.Downloads) }
                    rig.shot(scene, "downloads/list", 1_500)

                    SwingUtilities.invokeAndWait { app.downloads!!.showLog("soulseek:a5") }
                    rig.shot(scene, "downloads/log", 2_000)

                    // A running one opens on its newest step.
                    SwingUtilities.invokeAndWait { app.downloads!!.showLog("soulseek:a1") }
                    rig.shot(scene, "downloads/log-running", 2_500)

                    SwingUtilities.invokeAndWait { app.downloads!!.find("nd-9", "Holocene") }
                    rig.shot(scene, "downloads/find", 2_000)
                    SwingUtilities.invokeAndWait { app.showSidePanel(null) }
                }
            }
        }
    }

    // A held download's row as a server listing octoAcquisitions 4 sends it:
    // still searching, its line in `note`, drawn with its album's cover; a
    // hearted album's row is of kind "album".
    private fun held(key: String, title: String, album: String, cover: String, at: String, note: String, extra: String = "", kind: String = "download") =
        """{"id":"${key.substringAfter(':')}","artist":"The Beatles","title":"$title","album":"$album","coverArt":"$cover","state":"searching","source":"Soulseek","quality":"FLAC 16-bit 44.1 kHz","note":"$note","startedAt":"2026-10-04T$at:00Z","updatedAt":"2026-10-04T$at:40Z","key":"$key","kind":"$kind"$extra}"""

    // Yesterday's log: fetched from Soulseek, its file waiting for AcoustID.
    private val yesterdayLog = listOf(
        """{"at":"2026-10-04T18:12:00Z","kind":"queued","text":"Asked for","detail":"By winters"}""",
        """{"at":"2026-10-04T18:12:01Z","kind":"search","text":"Looking on Soulseek"}""",
        """{"at":"2026-10-04T18:12:01Z","kind":"search","text":"Searching Soulseek for \"The Beatles Yesterday\""}""",
        """{"at":"2026-10-04T18:12:27Z","kind":"found","text":"2 copies fit, best first","detail":"118 files from 34 peers","candidate":[
            {"source":"Soulseek","peer":"pennylane","file":"13 - Yesterday.flac","folder":"The Beatles/Help! (1965)","title":"Yesterday","album":"Help!","format":"flac","quality":"FLAC 16-bit 44.1 kHz","size":13200000,"length":125,"freeSlot":true,"speed":1900000,"rank":1},
            {"source":"Soulseek","peer":"apple_corps","file":"The Beatles - Yesterday.flac","title":"The Beatles - Yesterday","format":"flac","quality":"FLAC","size":13000000,"length":126,"queueLength":3,"speed":700000,"rank":2}]}""",
        """{"at":"2026-10-04T18:12:27Z","kind":"try","text":"Trying choice 1 of 2","detail":"lossless (FLAC 16-bit 44.1 kHz), the peer can send it now, 1.8 MB/s, 12.6 MB"}""",
        """{"at":"2026-10-04T18:12:29Z","kind":"transfer","text":"Downloading from Soulseek (pennylane)","detail":"12.6 MB"}""",
        """{"at":"2026-10-04T18:12:37Z","kind":"check","text":"Checking the file"}""",
        """{"at":"2026-10-04T18:12:38Z","kind":"note","text":"Waiting for AcoustID"}""",
    )

    // Rubber Soul's log: the album fetched, twelve tracks in, two held.
    private val rubberSoulLog = listOf(
        """{"at":"2026-10-04T18:20:00Z","kind":"queued","text":"Asked for","detail":"By winters"}""",
        """{"at":"2026-10-04T18:20:01Z","kind":"search","text":"Looking on Soulseek"}""",
        """{"at":"2026-10-04T18:20:01Z","kind":"search","text":"Searching Soulseek for \"The Beatles Rubber Soul\""}""",
        """{"at":"2026-10-04T18:20:33Z","kind":"found","text":"2 copies fit, best first","detail":"212 files from 41 peers"}""",
        """{"at":"2026-10-04T18:20:33Z","kind":"try","text":"Trying choice 1 of 2","detail":"lossless (FLAC 16-bit 44.1 kHz), the peer can send it now, 2.4 MB/s, 248.1 MB"}""",
        """{"at":"2026-10-04T18:20:35Z","kind":"transfer","text":"Downloading from Soulseek (norwegian_wood)","detail":"248.1 MB"}""",
        """{"at":"2026-10-04T18:22:20Z","kind":"check","text":"Checking the files"}""",
        """{"at":"2026-10-04T18:23:05Z","kind":"library","text":"In the library folder","detail":"12 tracks, waiting for Navidrome to show them"}""",
        """{"at":"2026-10-04T18:23:05Z","kind":"note","text":"2 tracks wait: play them once, or keep them anyway"}""",
    )

    // Ticket to Ride's log: fetched from Soulseek while AcoustID is off.
    private val ticketToRideLog = listOf(
        """{"at":"2026-10-04T18:30:00Z","kind":"queued","text":"Asked for","detail":"By winters"}""",
        """{"at":"2026-10-04T18:30:01Z","kind":"search","text":"Looking on Soulseek"}""",
        """{"at":"2026-10-04T18:30:01Z","kind":"search","text":"Searching Soulseek for \"The Beatles Ticket to Ride\""}""",
        """{"at":"2026-10-04T18:30:24Z","kind":"found","text":"2 copies fit, best first","detail":"96 files from 28 peers","candidate":[
            {"source":"Soulseek","peer":"abbey_rd","file":"07 - Ticket to Ride.flac","folder":"The Beatles/Help! (1965)","title":"Ticket to Ride","album":"Help!","format":"flac","quality":"FLAC 16-bit 44.1 kHz","size":22400000,"length":190,"freeSlot":true,"speed":1700000,"rank":1},
            {"source":"Soulseek","peer":"liverpool8","file":"The Beatles - Ticket to Ride.flac","title":"The Beatles - Ticket to Ride","format":"flac","quality":"FLAC","size":22100000,"length":191,"queueLength":2,"speed":600000,"rank":2}]}""",
        """{"at":"2026-10-04T18:30:24Z","kind":"try","text":"Trying choice 1 of 2","detail":"lossless (FLAC 16-bit 44.1 kHz), the peer can send it now, 1.6 MB/s, 21.4 MB"}""",
        """{"at":"2026-10-04T18:30:26Z","kind":"transfer","text":"Downloading from Soulseek (abbey_rd)","detail":"21.4 MB"}""",
        """{"at":"2026-10-04T18:30:40Z","kind":"check","text":"Checking the file"}""",
        """{"at":"2026-10-04T18:30:41Z","kind":"note","text":"AcoustID is off, so Octo cannot check what played"}""",
    )

    // Held downloads while AcoustID is busy, on a server listing
    // octoAcquisitions 4: Yesterday from Help! waits for AcoustID with no
    // way to keep it, and the album Rubber Soul holds two tracks with Keep
    // these anyway, beside a download on its way. Then Keep these anyway
    // while it is sent, refused with the server's words, the album's log
    // with the refusal under its control line, Yesterday's log, which has
    // no control line, and the window with the drawer closed. Saved as
    // downloads/keep-anyway-1 to -5 and -10.
    @Test
    fun drawHeldWhileAcoustIdIsBusy() {
        assumeTrue(System.getenv("OCTO_SHOTS") == "1")
        val yesterday = held(YESTERDAY, "Yesterday", "Help!", HELP_COVER, "18:12", "Waiting for AcoustID")
        val rubberSoul = held(
            RUBBER_SOUL, "Rubber Soul", "Rubber Soul", RUBBER_SOUL_COVER, "18:20",
            "2 tracks wait: play them once, or keep them anyway", ""","keepAnyway":true,"heldTracks":2""", kind = "album",
        )
        val answer = CountDownLatch(1)
        drawHeld(
            rows = 3,
            answer = { server ->
                server.answer(
                    "getAcquisitions",
                    """"acquisitions":{"acquisition":[$rubberSoul,$yesterday,
                        ${row("soulseek:a1", "Around the World", "Daft Punk", "Homework", "downloading", ""","progress":0.62,"quality":"FLAC 16-bit 44.1 kHz","peer":"vinylhead","coverArt":"$HOMEWORK_COVER"""")}]}""",
                    type = "octo",
                )
                server.answerBy("getAcquisition") { request ->
                    val album = request.url.queryParameter("key") == RUBBER_SOUL
                    server.ok(if (album) withLog(rubberSoul, rubberSoulLog) else withLog(yesterday, yesterdayLog), type = "octo")
                }
                // A refusal long enough to take two lines under its button.
                server.answerBy("keepAcquisitionAnyway") {
                    answer.await(10, TimeUnit.SECONDS)
                    server.failed(50, "Only someone who hearted this can keep it")
                }
            },
        ) { scene ->
            shot(scene, "downloads/keep-anyway-1-acoustid-busy", 1_500)

            SwingUtilities.invokeAndWait { app.downloads!!.keepAnyway(RUBBER_SOUL) }
            shot(scene, "downloads/keep-anyway-2-keep-these-sending", 800)

            answer.countDown()
            shot(scene, "downloads/keep-anyway-3-keep-these-refused", 1_500)

            SwingUtilities.invokeAndWait { app.downloads!!.showLog(RUBBER_SOUL) }
            shot(scene, "downloads/keep-anyway-4-log-keep-these-refused", 2_000)

            SwingUtilities.invokeAndWait { app.downloads!!.showLog(YESTERDAY) }
            shot(scene, "downloads/keep-anyway-5-log-waiting-for-acoustid", 2_000)

            // Closed: the pill speaks for the two on their way, the sidebar's ring turns.
            SwingUtilities.invokeAndWait { app.showSidePanel(null) }
            shot(scene, "downloads/keep-anyway-10-busy-panel-closed", 1_500)
        }
    }

    // Held downloads while AcoustID is off: Ticket to Ride from Help! with
    // Keep anyway and the album Rubber Soul with Keep these anyway, above a
    // song in the library. First the window with the drawer closed, then
    // Ticket to Ride's log with Keep anyway on its control line, and the log
    // and the list once the keep is asked for and waits for YouTube, with no
    // button left. Saved as downloads/keep-anyway-11 and -6 to -9.
    @Test
    fun drawHeldWhileAcoustIdIsOff() {
        assumeTrue(System.getenv("OCTO_SHOTS") == "1")
        val asked = AtomicBoolean(false)
        val ticketToRide = {
            if (asked.get()) {
                held(TICKET_TO_RIDE, "Ticket to Ride", "Help!", HELP_COVER, "18:30", KEEP_WAITS_FOR_YOUTUBE)
            } else {
                held(TICKET_TO_RIDE, "Ticket to Ride", "Help!", HELP_COVER, "18:30", "AcoustID is off, so Octo cannot check what played", ""","keepAnyway":true""")
            }
        }
        val ticketToRideLines = {
            if (asked.get()) ticketToRideLog + """{"at":"2026-10-04T18:31:10Z","kind":"note","text":"$KEEP_WAITS_FOR_YOUTUBE"}""" else ticketToRideLog
        }
        val rubberSoul = held(
            RUBBER_SOUL, "Rubber Soul", "Rubber Soul", RUBBER_SOUL_COVER, "18:20",
            "2 tracks wait: play them once, or keep them anyway", ""","keepAnyway":true,"heldTracks":2""", kind = "album",
        )
        drawHeld(
            rows = 3,
            answer = { server ->
                server.answerBy("getAcquisitions") {
                    server.ok(
                        """"acquisitions":{"acquisition":[${ticketToRide()},$rubberSoul,
                            ${row("soulseek:7", "Here Comes the Sun", "The Beatles", "Abbey Road", "done", ""","quality":"FLAC 16-bit 44.1 kHz"""")}]}""",
                        type = "octo",
                    )
                }
                server.answerBy("getAcquisition") { request ->
                    val album = request.url.queryParameter("key") == RUBBER_SOUL
                    server.ok(if (album) withLog(rubberSoul, rubberSoulLog) else withLog(ticketToRide(), ticketToRideLines()), type = "octo")
                }
                server.answerBy("keepAcquisitionAnyway") {
                    asked.set(true)
                    server.ok(type = "octo")
                }
            },
        ) { scene ->
            // Closed, with only rows waiting for the person: no pill, the dot in the sidebar.
            SwingUtilities.invokeAndWait { app.showSidePanel(null) }
            shot(scene, "downloads/keep-anyway-11-off-panel-closed", 1_500)
            SwingUtilities.invokeAndWait { app.showSidePanel(SidePanel.Downloads) }

            shot(scene, "downloads/keep-anyway-6-acoustid-off", 1_500)

            SwingUtilities.invokeAndWait { app.downloads!!.showLog(TICKET_TO_RIDE) }
            shot(scene, "downloads/keep-anyway-7-log-keep-anyway", 2_000)

            SwingUtilities.invokeAndWait { app.downloads!!.keepAnyway(TICKET_TO_RIDE) }
            shot(scene, "downloads/keep-anyway-8-log-keep-requested", 2_000)

            SwingUtilities.invokeAndWait { app.downloads!!.showList() }
            shot(scene, "downloads/keep-anyway-9-keep-requested", 1_500)
        }
    }

    // The drawer at 1080p against a server listing octoAcquisitions 4, whose
    // downloads `answer` sets up: `shots` draws once it has read `rows` rows.
    private fun drawHeld(rows: Int, answer: (FakeServer) -> Unit, shots: PolishShotsTest.Rig.(ImageComposeScene) -> Unit) {
        FakeServer().use { server ->
            PolishShotsTest.Rig(folder, PolishData.library(100), server).use { rig ->
                server.answer(
                    "getOpenSubsonicExtensions",
                    """"openSubsonicExtensions":[{"name":"songLyrics","versions":[1]},{"name":"octoAcquisitions","versions":[1,2,3,4]},{"name":"octoLibraryActions","versions":[1,2]}]""",
                    type = "octo",
                )
                server.answer("getUpgrades", """"upgrades":[]""", type = "octo")
                answer(server)
                rig.signIn()
                val app = rig.app
                val end = System.currentTimeMillis() + 10_000
                while (app.downloads?.rows?.value?.size != rows && System.currentTimeMillis() < end) Thread.sleep(50)
                check(app.downloads?.rows?.value?.size == rows) { "the drawer never read the list" }
                rig.scene(PolishShotsTest.Size.Hd) { scene ->
                    rig.reset(scene)
                    SwingUtilities.invokeAndWait { app.showSidePanel(SidePanel.Downloads) }
                    rig.shots(scene)
                    SwingUtilities.invokeAndWait { app.showSidePanel(null) }
                }
            }
        }
    }

    private companion object {
        // The held downloads' keys on the server, and their albums' covers.
        const val YESTERDAY = "deezer:116348128"
        const val TICKET_TO_RIDE = "deezer:116348122"
        const val RUBBER_SOUL = "deezer:12047952"
        const val HELP_COVER = "al-help"
        const val RUBBER_SOUL_COVER = "al-rubber-soul"
        const val HOMEWORK_COVER = "al-homework"

        // What a song's row says once its keep is asked for and its heard
        // video's server is busy.
        const val KEEP_WAITS_FOR_YOUTUBE = "Keeping the version you heard once YouTube answers"
    }
}
