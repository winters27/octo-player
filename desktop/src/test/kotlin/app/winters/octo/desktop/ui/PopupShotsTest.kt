package app.winters.octo.desktop.ui

import app.winters.octo.desktop.FakeServer
import app.winters.octo.desktop.family.showDeviceSheet
import app.winters.octo.desktop.family.showInvite
import app.winters.octo.desktop.nav.Page
import app.winters.octo.desktop.pages.showSection
import app.winters.octo.subsonic.FamilyDeviceAdded
import app.winters.octo.subsonic.FamilyDeviceKind
import app.winters.octo.subsonic.FamilyLinkChoices
import app.winters.octo.subsonic.FamilyPreset
import app.winters.octo.ui.family.CODE_FAILED
import app.winters.octo.ui.family.DeviceSheet
import app.winters.octo.ui.family.FAMILY
import app.winters.octo.ui.family.InviteSheet
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.Instant
import javax.imageio.ImageIO
import javax.swing.SwingUtilities

// The Add a device and invite popups in each of their states, over the
// Family page, against a pretend Octo server.
// Only when asked: OCTO_SHOTS=1 ./gradlew :desktop:test --tests '*PopupShotsTest*'.
// Saved under build/shots/polish/popup/, each with a close crop beside it.
class PopupShotsTest {
    @get:Rule val folder = TemporaryFolder()

    private val owner = """"family":{"me":{"username":"winters","displayName":"Jordan","role":"Owner","managed":false,
        "abilities":{"addToLibrary":"Direct","requestQuality":"Best","autoApprove":true,"weeklyRequestLimit":0,"streamCap":0,"awayCap":0,
        "away":true,"devicesAtOnce":0,"downloadFiles":true,"offlineCopies":true,"share":true,"importPlaylists":true,"cleanOnly":false,
        "familyPlaylistsEdit":true,"manageFamily":true,"approveRequests":true,"storageLimitGb":0,"instantFromFamily":true},
        "requestsThisWeek":0,"storageUsedBytes":0,"place":"Home","deviceId":null},
        "manager":{"members":[
          {"username":"sam","displayName":"Sam","role":"Kid","suspended":false,"devices":1,"playingNow":false,"pendingRequests":0,"storageUsedBytes":800000000,"storageLimitGb":5}],
        "pendingRequests":0,"liveStreams":0}}"""

    private var made = 0

    private fun device(kind: String): String {
        made += 1
        val code = listOf("482913", "730164", "915302")[(made - 1) % 3]
        return if (kind == "SubsonicApp") {
            """"familyDeviceAdded":{"deviceId":"p_$made","kind":"SubsonicApp","appPassword":"ABCDEFGHJKMNPQRS","server":"https://music.example.com","username":"winters"}"""
        } else {
            val expires = Instant.now().plusSeconds(582)
            """"familyDeviceAdded":{"deviceId":"d_$made","kind":"OctoApp","pairCode":"$code","expires":"$expires","server":"https://music.example.com","username":"winters",
            "links":{"anywhere":"https://music.example.com/family/join#u=winters&c=$code&home=http%3A%2F%2F192.168.1.20%3A4533",
            "home":"http://192.168.1.20:4533/family/join#u=winters&c=$code"},"anywhereAvailable":true}"""
        }
    }

    @Test
    fun drawPopups() {
        assumeTrue(System.getenv("OCTO_SHOTS") == "1")
        FakeServer().use { server ->
            PolishShotsTest.Rig(folder, PolishData.library(100), server).use { rig ->
                server.answer(
                    "getOpenSubsonicExtensions",
                    """"openSubsonicExtensions":[{"name":"songLyrics","versions":[1]},{"name":"octoFamily","versions":[1]}]""",
                    type = "octo",
                )
                server.answer("getFamily", owner, type = "octo")
                server.answer("getFamilyRequests", """"familyRequests":{"request":[]}""", type = "octo")
                server.answer("getFamilyDevices", """"familyDevices":{"device":[]}""", type = "octo")
                server.answer("getStarred2", """"starred2":{}""", type = "octo")
                server.answerBy("addFamilyDevice") { call -> server.ok(device(call.url.queryParameter("kind").orEmpty()), type = "octo") }
                server.answerBy("members") {
                    """{"member":{"username":"sam","displayName":"Sam","role":"Kid"},"inviteLink":"https://music.example.com/family/join#invite=tok_sam_7Hq2",
                    "links":{"anywhere":"https://music.example.com/family/join#invite=tok_sam_7Hq2","home":"http://192.168.1.20:4533/family/join#invite=tok_sam_7Hq2"},"anywhereAvailable":true}"""
                }
                server.answerBy("sam") { """{"username":"sam","displayName":"Sam","role":"Kid"}""" }

                val app = rig.app
                rig.signIn()
                runBlocking { app.family.refresh() }
                val out = File("build/shots/polish/popup")
                fun shot(scene: androidx.compose.ui.ImageComposeScene, name: String, ms: Long = 2_000) {
                    rig.shot(scene, "popup/$name", ms)
                    crop(File(out, "$name.png"), File(out, "$name-crop.png"))
                }
                rig.scene(PolishShotsTest.Size.Hd) { scene ->
                    fun onFamily() = SwingUtilities.invokeAndWait {
                        showSection(FAMILY, "devices")
                        app.navigator.go(Page.Family)
                    }
                    // A fresh code, works anywhere.
                    rig.reset(scene)
                    onFamily()
                    SwingUtilities.invokeAndWait { app.family.addDevice("Living room", FamilyDeviceKind.OctoApp) }
                    shot(scene, "1-fresh", 2_500)
                    // Symfonium or another app.
                    SwingUtilities.invokeAndWait { app.family.showOtherApps() }
                    shot(scene, "3-other-apps", 2_000)
                    SwingUtilities.invokeAndWait { app.family.backToCode() }
                    rig.shot(scene, null, 800)
                    // The same code at home only.
                    rig.clickText(scene, "At home only")
                    shot(scene, "2-at-home", 1_200)
                    // Clicked straight from the switch, as a person would.
                    rig.clickText(scene, "Using Symfonium or another app instead?")
                    rig.shot(scene, null, 800)
                    rig.clickText(scene, "Back to the QR code")
                    rig.shot(scene, null, 800)
                    SwingUtilities.invokeAndWait { app.family.dismissAdded() }

                    // A fixed popup for the states that take time to reach.
                    val code = FamilyDeviceAdded(
                        deviceId = "d_9", kind = FamilyDeviceKind.OctoApp, pairCode = "730164",
                        expires = Instant.now().plusSeconds(41).toString(), server = "https://music.example.com", username = "winters",
                        links = FamilyLinkChoices("https://music.example.com/family/join#u=winters&c=730164", "http://192.168.1.20:4533/family/join#u=winters&c=730164"),
                    )
                    val fixed = mapOf(
                        "4-new-code" to DeviceSheet(id = 90, code = code, renewed = 1),
                        "5-under-a-minute" to DeviceSheet(id = 91, code = code),
                        "6-still-there" to DeviceSheet(id = 92, code = code.copy(expires = Instant.now().minusSeconds(5).toString()), stale = true),
                        "7-renew-failed" to DeviceSheet(id = 93, code = code, refreshFailed = true),
                        "8-no-outside-address" to DeviceSheet(id = 94, code = code.copy(links = FamilyLinkChoices(null, code.links!!.home), anywhereAvailable = false)),
                        "9-loading" to DeviceSheet(id = 95, loading = true),
                        "10-error" to DeviceSheet(id = 96, error = CODE_FAILED),
                    )
                    for ((name, sheet) in fixed) {
                        rig.reset(scene)
                        onFamily()
                        SwingUtilities.invokeAndWait { showDeviceSheet(app.popups, app, app.family, sheet = { sheet }) }
                        if (name == "8-no-outside-address") {
                            rig.shot(scene, null, 800)
                            rig.clickText(scene, "Works anywhere")
                        }
                        shot(scene, name, 1_500)
                    }

                    // A new member's invite, from the Members section.
                    rig.reset(scene)
                    SwingUtilities.invokeAndWait {
                        showSection(FAMILY, "members")
                        app.navigator.go(Page.Family)
                        app.family.addMember("sam", "Sam", FamilyPreset.Kid)
                    }
                    shot(scene, "11-invite", 2_500)
                    rig.reset(scene)
                    onFamily()
                    val kept = InviteSheet("Sam", "sam", "http://192.168.1.20:4533/family/join#invite=tok_sam_7Hq2", awayAllowed = false,
                        links = FamilyLinkChoices("https://music.example.com/family/join#invite=tok_sam_7Hq2", "http://192.168.1.20:4533/family/join#invite=tok_sam_7Hq2"))
                    SwingUtilities.invokeAndWait { showInvite(app.popups, app, app.family, invite = { kept }) }
                    shot(scene, "12-invite-home-only", 1_500)
                    // The Add member form with its away switch.
                    rig.reset(scene)
                    SwingUtilities.invokeAndWait {
                        showSection(FAMILY, "members")
                        app.navigator.go(Page.Family)
                    }
                    rig.shot(scene, "popup/13-add-member-form", 2_000)
                }
            }
        }
    }

    // The popup and a margin around it, out of the middle of the window.
    private fun crop(from: File, to: File) {
        val image = ImageIO.read(from) ?: return
        val w = minOf(820, image.width)
        val h = minOf(1000, image.height)
        ImageIO.write(image.getSubimage((image.width - w) / 2, (image.height - h) / 2, w, h), "png", to)
    }
}
