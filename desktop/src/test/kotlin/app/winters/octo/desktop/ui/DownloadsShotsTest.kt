package app.winters.octo.desktop.ui

import app.winters.octo.desktop.FakeServer
import app.winters.octo.desktop.SidePanel
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import javax.swing.SwingUtilities

// The downloads drawer as it looks: the list with a download on its way, a
// higher quality copy, a picked one and a failure; the pill beside the
// player while the drawer is closed; a finished log and a running one; and Find songs
// with its copies. Only when asked:
// OCTO_SHOTS=1 ./gradlew :desktop:test --tests '*DownloadsShotsTest*'.
// Saved under build/shots/polish/downloads/.
class DownloadsShotsTest {
    @get:Rule val folder = TemporaryFolder()

    private fun row(key: String, title: String, artist: String, state: String, extra: String = "", kind: String = "download") =
        """{"id":"${key.substringAfter(':')}","artist":"$artist","title":"$title","album":"Album","state":"$state","source":"Soulseek","startedAt":"2026-10-04T18:0${key.last()}:00Z","updatedAt":"2026-10-04T18:09:00Z","key":"$key","kind":"$kind"$extra}"""

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
                        ${row("soulseek:a1", "Around the World", "Daft Punk", "downloading", ""","progress":0.62,"quality":"FLAC 16-bit 44.1 kHz","peer":"vinylhead"""")},
                        ${row("soulseek:a2", "Teardrop", "Massive Attack", "searching", ""","note":"Soulseek couldn't get it, trying YouTube"""")},
                        ${row("soulseek:a3", "Sexy Boy", "Air", "queued", ""","ahead":2""")},
                        ${row("soulseek:a4", "Holocene", "Bon Iver", "done", kind = "upgrade")},
                        ${row("soulseek:a5", "Da Funk", "Daft Punk", "done", ""","quality":"FLAC 16-bit 44.1 kHz"""", kind = "pick")},
                        ${row("soulseek:a6", "Windowlicker", "Aphex Twin", "failed", ""","error":"No Soulseek FLAC found for 'Aphex Twin - Windowlicker'"""")}
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
                        server.ok(""""acquisition":${row("soulseek:a1", "Around the World", "Daft Punk", "downloading", ""","progress":0.62,"quality":"FLAC 16-bit 44.1 kHz","peer":"vinylhead","event":[${log.take(6).joinToString(",")}]""")}""", type = "octo")
                    } else {
                        server.ok(""""acquisition":${row("soulseek:a5", "Da Funk", "Daft Punk", "done", ""","quality":"FLAC 16-bit 44.1 kHz","peer":"vinylhead","libraryId":"nd-5","event":[${log.joinToString(",")}]""", kind = "pick")}""", type = "octo")
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
}
