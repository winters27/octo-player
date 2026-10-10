package app.winters.octo.desktop.ui

import app.winters.octo.desktop.FakeServer
import app.winters.octo.subsonic.FamilyPreset
import app.winters.octo.desktop.nav.Page
import app.winters.octo.desktop.pages.askForCopy
import app.winters.octo.desktop.pages.showSection
import app.winters.octo.ui.family.FAMILY
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import javax.swing.SwingUtilities

// Family as it looks, against a pretend Octo server with Family on: joining
// with a code, a listener's plan, saved songs, requests and devices, the
// request sheet, and a manager's members and waiting requests.
// Only when asked: OCTO_SHOTS=1 ./gradlew :desktop:test --tests '*FamilyShotsTest*'.
// Saved under build/shots/polish/family/.
class FamilyShotsTest {
    @get:Rule val folder = TemporaryFolder()

    private val abilities = """"abilities":{"addToLibrary":"Request","requestQuality":"Flac","autoApprove":false,"weeklyRequestLimit":10,
        "streamCap":0,"awayCap":160,"away":true,"devicesAtOnce":2,"downloadFiles":false,"offlineCopies":false,"share":false,
        "importPlaylists":true,"cleanOnly":false,"familyPlaylistsEdit":true,"manageFamily":false,"approveRequests":false,
        "storageLimitGb":10,"instantFromFamily":true}"""

    private val listener = """"family":{"me":{"username":"winters","displayName":"Alex","role":"Listener","managed":true,$abilities,
        "requestsThisWeek":3,"storageUsedBytes":5400000000,"place":"Home","deviceId":"d_7Qm2abc",
        "quality":{"home":"Original","away":"Standard","familyLimitKbps":0,"familyAwayLimitKbps":160}}}"""

    private val owner = """"family":{"me":{"username":"winters","displayName":"Jordan","role":"Owner","managed":false,
        "abilities":{"addToLibrary":"Direct","requestQuality":"Best","autoApprove":true,"weeklyRequestLimit":0,"streamCap":0,"awayCap":0,
        "away":true,"devicesAtOnce":0,"downloadFiles":true,"offlineCopies":true,"share":true,"importPlaylists":true,"cleanOnly":false,
        "familyPlaylistsEdit":true,"manageFamily":true,"approveRequests":true,"storageLimitGb":0,"instantFromFamily":true},
        "requestsThisWeek":0,"storageUsedBytes":0,"place":"Home","deviceId":null},
        "manager":{"members":[
          {"username":"alex","displayName":"Alex","role":"Listener","suspended":false,"devices":2,"playingNow":true,"pendingRequests":2,"storageUsedBytes":5400000000,"storageLimitGb":10},
          {"username":"sam","displayName":"Sam","role":"Kid","suspended":false,"devices":1,"playingNow":false,"pendingRequests":0,"storageUsedBytes":800000000,"storageLimitGb":5},
          {"username":"riley","displayName":"Riley","role":"Member","suspended":true,"devices":0,"playingNow":false,"pendingRequests":0,"storageUsedBytes":21000000000,"storageLimitGb":50}],
        "pendingRequests":2,"liveStreams":1}}"""

    private fun request(id: String, title: String, artist: String, state: String, outcome: String? = null, quality: String = "Flac", kind: String = "Song", who: String = "Alex", note: String = "") =
        """{"id":"$id","username":"${who.lowercase()}","displayName":"$who","kind":"$kind","target":"ext-deezer-song-$id","title":"$title",
        "artist":"$artist","album":"","coverArt":null,"quality":"$quality","state":"$state","created":"2026-10-20T18:00:00Z",
        "decided":null,"decidedBy":null,"note":"$note","failure":${if (state == "Failed") "\"No real FLAC copy found\"" else "null"},
        "librarySongId":null,"outcome":${outcome?.let { "\"$it\"" } ?: "null"}}"""

    @Test
    fun drawFamily() {
        assumeTrue(System.getenv("OCTO_SHOTS") == "1")
        FakeServer().use { server ->
            PolishShotsTest.Rig(folder, PolishData.library(100), server).use { rig ->
                server.answer(
                    "getOpenSubsonicExtensions",
                    """"openSubsonicExtensions":[{"name":"songLyrics","versions":[1]},{"name":"octoAcquisitions","versions":[1]},{"name":"octoFamily","versions":[1]}]""",
                    type = "octo",
                )
                server.answer("getFamily", listener, type = "octo")
                server.answerBy("getFamilyRequests") { call ->
                    val all = call.url.queryParameter("all") == "true"
                    val list = if (all) {
                        listOf(request("r7", "Ivy", "Frank Ocean", "Pending"), request("r8", "Blonde", "Frank Ocean", "Pending", kind = "Album", quality = "Best"))
                    } else {
                        listOf(
                            request("r1", "Angel", "Massive Attack", "Pending"),
                            request("r2", "Dreams", "Fleetwood Mac", "Done", "AddedFromFamily", quality = "Best"),
                            request("r3", "Teardrop", "Massive Attack", "Done", "AlreadyShared"),
                            request("r4", "Time to Pretend", "MGMT", "Done", "Downloaded", quality = "Mp3"),
                            request("r5", "Ivy", "Frank Ocean", "Approved"),
                            request("r6", "Live at Pompeii", "Pink Floyd", "Declined", kind = "Album"),
                        )
                    }
                    server.ok(""""familyRequests":{"request":[${list.joinToString(",")}]}""", type = "octo")
                }
                server.answer(
                    "getFamilyDevices",
                    """"familyDevices":{"device":[
                      {"id":"d_1","username":"winters","name":"Studio PC","kind":"OctoApp","app":"Octo 1.6 (Windows)","created":"","lastSeen":"","place":"Home","playing":{"songId":"s1","title":"Angel","artist":"Massive Attack"},"current":true,"quality":"Account"},
                      {"id":"d_2","username":"winters","name":"Pixel 9","kind":"OctoApp","app":"Octo 1.6 (Android)","created":"","lastSeen":"","place":"Away","playing":null,"current":false},
                      {"id":"d_3","username":"winters","name":"Symfonium","kind":"SubsonicApp","app":"Symfonium","created":"","lastSeen":"","place":"Home","playing":null,"current":false}]}""",
                    type = "octo",
                )
                server.answer(
                    "addFamilyDevice",
                    """"familyDeviceAdded":{"deviceId":"d_9","kind":"OctoApp","pairCode":"482913","expires":"2026-10-20T18:15:00Z","server":"https://music.example.com","username":"winters"}""",
                    type = "octo",
                )
                server.answerBy("auth") { """{"username":"winters","role":"Owner"}""" }
                server.answerBy("members") { """{"member":{"username":"sam","displayName":"Sam","role":"Kid"},"inviteLink":"https://music.example.com/family/join#invite=tok_sam_7Hq2"}""" }
                server.answer(
                    "getStarred2",
                    """"starred2":{"song":[
                      {"id":"ext-deezer-song-11","title":"Pink + White","artist":"Frank Ocean","album":"Blonde","isExternal":true,"duration":184},
                      {"id":"ext-deezer-song-12","title":"Go Your Own Way","artist":"Fleetwood Mac","album":"Rumours","isExternal":true,"duration":223}],
                      "album":[{"id":"ext-deezer-album-21","name":"Mezzanine","artist":"Massive Attack","isExternal":true}]}""",
                    type = "octo",
                )

                val app = rig.app
                rig.scene(PolishShotsTest.Size.Hd) { scene ->
                    // Signing up from an invite, before signing in.
                    SwingUtilities.invokeAndWait {
                        app.popups.close()
                        app.signInForm.takeJoinLink("https://music.example.com/family/join#invite=tok_9")
                    }
                    rig.shot(scene, "family/invite", 1_500)
                    SwingUtilities.invokeAndWait { app.signInForm.leaveLink() }
                }
                rig.signIn()
                runBlocking { app.family.plan() }
                rig.scene(PolishShotsTest.Size.Hd) { scene ->
                    for (section in listOf("plan", "saved", "requests", "devices")) {
                        rig.reset(scene)
                        SwingUtilities.invokeAndWait {
                            showSection(FAMILY, section)
                            app.navigator.go(Page.Family)
                        }
                        rig.shot(scene, "family/listener-$section", 2_000)
                    }
                    rig.reset(scene)
                    SwingUtilities.invokeAndWait { askForCopy(app.popups, app, "ext-deezer-song-11", "Pink + White") }
                    rig.shot(scene, "family/request-sheet", 1_500)

                    // Audio quality and offline copies, in Settings.
                    for (section in listOf("quality", "offline")) {
                        rig.reset(scene)
                        SwingUtilities.invokeAndWait {
                            showSection("Settings", section)
                            app.navigator.go(Page.Settings)
                        }
                        rig.shot(scene, "family/settings-$section", 2_000)
                    }

                    server.answer("getFamily", owner, type = "octo")
                    runBlocking { app.family.refresh() }
                    for (section in listOf("plan", "members", "inbox")) {
                        rig.reset(scene)
                        SwingUtilities.invokeAndWait {
                            showSection(FAMILY, section)
                            app.navigator.go(Page.Family)
                        }
                        rig.shot(scene, "family/owner-$section", 2_000)
                    }
                    // A new member's invite, as a QR code.
                    rig.reset(scene)
                    SwingUtilities.invokeAndWait {
                        showSection(FAMILY, "members")
                        app.navigator.go(Page.Family)
                        app.family.addMember("sam", "Sam", FamilyPreset.Kid)
                    }
                    rig.shot(scene, "family/owner-invite-qr", 2_500)
                }
            }
        }
    }
}
