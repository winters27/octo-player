package app.winters.octo.desktop.ui

import androidx.compose.runtime.key
import androidx.compose.ui.unit.dp
import app.winters.octo.desktop.FakeServer
import app.winters.octo.desktop.family.AskingCard
import app.winters.octo.desktop.family.HandOverCard
import app.winters.octo.desktop.family.SheetActions
import app.winters.octo.desktop.family.showHandOver
import app.winters.octo.desktop.family.showFamilyDialog
import app.winters.octo.desktop.nav.Page
import app.winters.octo.desktop.pages.changePassword
import app.winters.octo.desktop.pages.showSection
import app.winters.octo.subsonic.FamilyLinkChoices
import app.winters.octo.subsonic.FamilyMember
import app.winters.octo.subsonic.FamilyPreset
import app.winters.octo.subsonic.FamilySignInPending
import app.winters.octo.subsonic.FamilySignInStart
import app.winters.octo.ui.family.FAMILY
import app.winters.octo.ui.family.HandOverBox
import app.winters.octo.ui.family.HandOverSheet
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.Instant
import javax.imageio.ImageIO
import javax.swing.SwingUtilities

// One login per person, as it looks on the desktop: signing up from an
// invite, a sign-in arriving from another device, Your login, the devices
// list, "Sign in on another device" in each of its states, a manager's
// invite and reset link, and changing the password.
// Only when asked: OCTO_SHOTS=1 ./gradlew :desktop:test --tests '*PopupShotsTest*'.
// Saved under build/shots/polish/one-login/, each with a close crop beside it.
class PopupShotsTest {
    @get:Rule val folder = TemporaryFolder()

    private val abilities = """"abilities":{"addToLibrary":"Request","requestQuality":"Flac","autoApprove":false,"weeklyRequestLimit":10,
        "streamCap":0,"awayCap":160,"away":true,"devicesAtOnce":2,"downloadFiles":false,"offlineCopies":false,"share":false,
        "importPlaylists":true,"cleanOnly":false,"familyPlaylistsEdit":true,"manageFamily":false,"approveRequests":false,
        "storageLimitGb":10,"instantFromFamily":true}"""

    private val member = """"family":{"me":{"username":"alex","displayName":"Alex","role":"Member","managed":true,$abilities,
        "requestsThisWeek":3,"storageUsedBytes":5400000000,"place":"Home","deviceId":"d_1"}}"""

    private val owner = """"family":{"me":{"username":"winters","displayName":"Jordan","role":"Owner","managed":false,
        "abilities":{"addToLibrary":"Direct","requestQuality":"Best","autoApprove":true,"weeklyRequestLimit":0,"streamCap":0,"awayCap":0,
        "away":true,"devicesAtOnce":0,"downloadFiles":true,"offlineCopies":true,"share":true,"importPlaylists":true,"cleanOnly":false,
        "familyPlaylistsEdit":true,"manageFamily":true,"approveRequests":true,"storageLimitGb":0,"instantFromFamily":true},
        "requestsThisWeek":0,"storageUsedBytes":0,"place":"Home","deviceId":null},
        "manager":{"members":[
          {"username":"alex","displayName":"Alex","role":"Member","suspended":false,"devices":3,"playingNow":true,"pendingRequests":0,"storageUsedBytes":5400000000,"storageLimitGb":10},
          {"username":"sam","displayName":"Sam","role":"Kid","suspended":false,"devices":1,"playingNow":false,"pendingRequests":0,"storageUsedBytes":800000000,"storageLimitGb":5}],
        "pendingRequests":0,"liveStreams":1}}"""

    private val devices = """"familyDevices":{"device":[
        {"id":"d_1","username":"alex","name":"Studio PC","app":"Octo 1.6 (Windows)","firstSeen":"","lastSeen":"","place":"Home","playing":{"songId":"s1","title":"Angel","artist":"Massive Attack"},"current":true},
        {"id":"d_2","username":"alex","name":"Pixel 9","app":"Octo 1.6 (Android)","firstSeen":"","lastSeen":"","place":"Away","playing":null,"current":false},
        {"id":"d_3","username":"alex","name":"Symfonium","app":"Symfonium","firstSeen":"","lastSeen":"","place":"Home","playing":null,"current":false},
        {"id":"d_4","username":"alex","name":"Web player","app":"Navidrome web player","firstSeen":"","lastSeen":"","place":"Home","playing":null,"current":false}]}"""

    private val key = HandOverBox.newKey()
    private val start = FamilySignInStart(
        "tok_7Hq2",
        null,
        FamilyLinkChoices("https://music.example.com/family/signin", "http://192.168.1.20:4533/family/signin"),
        FamilyLinkChoices("https://music.example.com", "http://192.168.1.20:4533"),
    )

    @Test
    fun drawOneLogin() {
        assumeTrue(System.getenv("OCTO_SHOTS") == "1")
        FakeServer().use { server ->
            PolishShotsTest.Rig(folder, PolishData.library(100), server).use { rig ->
                server.answer(
                    "getOpenSubsonicExtensions",
                    """"openSubsonicExtensions":[{"name":"songLyrics","versions":[1]},{"name":"octoFamily","versions":[1]}]""",
                    type = "octo",
                )
                server.answer("getFamily", member, type = "octo")
                server.answer("getFamilyRequests", """"familyRequests":{"request":[]}""", type = "octo")
                server.answer("getFamilyDevices", devices, type = "octo")
                server.answer("getStarred2", """"starred2":{}""", type = "octo")
                server.answer(
                    "getFamilyLogin",
                    """"familyLogin":{"username":"alex","servers":{"anywhere":"https://music.example.com","home":"http://192.168.1.20:4533"},"anywhereAvailable":true,"awayAllowed":true}""",
                    type = "octo",
                )
                server.answerBy("startFamilySignIn") {
                    server.ok(
                        """"familySignIn":{"token":"tok_7Hq2","expires":"${Instant.now().plusSeconds(102)}","links":{"anywhere":"https://music.example.com/family/signin","home":"http://192.168.1.20:4533/family/signin"},
                        "servers":{"anywhere":"https://music.example.com","home":"http://192.168.1.20:4533"},"anywhereAvailable":true}""",
                        type = "octo",
                    )
                }
                server.answerBy("getFamilySignInPending") { server.ok(type = "octo") }
                server.answerBy("members") {
                    """{"member":{"username":"sam","displayName":"Sam","role":"Kid"},"inviteLink":"https://music.example.com/family/join#invite=tok_sam_7Hq2",
                    "links":{"anywhere":"https://music.example.com/family/join#invite=tok_sam_7Hq2","home":"http://192.168.1.20:4533/family/join#invite=tok_sam_7Hq2"},
                    "inviteDays":7,"anywhereAvailable":true,"awayAllowed":true,"homeOnly":false,"publicUrl":"https://music.example.com"}"""
                }
                server.answerBy("sam") { """{"username":"sam","displayName":"Sam","role":"Kid"}""" }
                server.answerBy("reset") {
                    """{"inviteLink":"https://music.example.com/family/join#invite=tok_alex_R9","links":{"anywhere":"https://music.example.com/family/join#invite=tok_alex_R9","home":"http://192.168.1.20:4533/family/join#invite=tok_alex_R9"},
                    "inviteDays":7,"expires":"${Instant.now().plusSeconds(7 * 86_400)}","anywhereAvailable":true,"awayAllowed":true,"homeOnly":false,"publicUrl":"https://music.example.com"}"""
                }
                // A new device waiting on its other device.
                server.answerBy("redeemFamilySignIn") { server.ok(""""familySignInRedeemed":{"id":"r_1"}""", type = "octo") }
                server.answerBy("getFamilySignInRedeem") { server.ok(""""familySignInRedeem":{"state":"Waiting"}""", type = "octo") }

                val app = rig.app
                val out = File("build/shots/polish/one-login")
                fun shot(scene: androidx.compose.ui.ImageComposeScene, name: String, ms: Long = 2_000) {
                    rig.shot(scene, "one-login/$name", ms)
                    crop(File(out, "$name.png"), File(out, "$name-crop.png"))
                }

                // Before signing in: signing up, a sign-in arriving, a sign-in link.
                rig.scene(PolishShotsTest.Size.Hd) { scene ->
                    SwingUtilities.invokeAndWait { app.signInForm.takeJoinLink("https://music.example.com/family/join#invite=tok_9") }
                    shot(scene, "01-sign-up-from-invite", 1_500)
                    SwingUtilities.invokeAndWait {
                        app.signInForm.leaveLink()
                        app.signInForm.takeJoinLink("${server.address.removeSuffix("/")}/family/signin#t=tok_1&k=$key&s=https%3A%2F%2Fmusic.example.com")
                    }
                    shot(scene, "02-sign-in-arriving", 2_000)
                    SwingUtilities.invokeAndWait {
                        app.signInForm.leaveLink()
                        app.signInForm.takeJoinLink("octo://signin?server=https%3A%2F%2Fmusic.example.com&home=http%3A%2F%2F192.168.1.20%3A4533&username=alex")
                    }
                    shot(scene, "03-sign-in-link-filled-in", 1_500)
                    SwingUtilities.invokeAndWait { app.signInForm.leaveLink() }
                }

                rig.signIn()
                runBlocking { app.family.refresh() }
                rig.scene(PolishShotsTest.Size.Hd) { scene ->
                    fun on(section: String) = SwingUtilities.invokeAndWait {
                        showSection(FAMILY, section)
                        app.navigator.go(Page.Family)
                    }
                    rig.reset(scene)
                    on("login")
                    shot(scene, "04-your-login", 2_000)
                    rig.reset(scene)
                    on("devices")
                    shot(scene, "05-devices", 2_000)

                    // Sign in on another device, as it runs.
                    rig.reset(scene)
                    on("devices")
                    SwingUtilities.invokeAndWait { showHandOver(app.popups, app, app.family) }
                    shot(scene, "06-sign-in-on-another-device", 2_500)
                    rig.clickText(scene, "At home")
                    shot(scene, "07-at-home", 1_200)
                    SwingUtilities.invokeAndWait { app.popups.close() }

                    // The states that take time to reach, drawn as they show.
                    val actions = SheetActions(owner = false)
                    val fixed = linkedMapOf(
                        "08-device-asking" to null,
                        "09-handed-over" to HandOverSheet(id = 2, start = start, key = key, done = "Sent to Pixel 9. It's signing in now."),
                        "10-still-there" to HandOverSheet(id = 3, start = start.copy(expires = Instant.now().minusSeconds(5).toString()), key = key, stale = true),
                        "11-no-outside-address" to HandOverSheet(id = 4, start = start.copy(expires = Instant.now().plusSeconds(80).toString(), links = FamilyLinkChoices(null, "http://192.168.1.20:4533/family/signin"), servers = FamilyLinkChoices(null, "http://192.168.1.20:4533"), anywhereAvailable = false), key = key),
                    )
                    for ((name, sheet) in fixed) {
                        rig.reset(scene)
                        on("devices")
                        SwingUtilities.invokeAndWait {
                            app.popups.showFamilyDialog { close ->
                                key(name) {
                                    if (sheet == null) AskingCard(FamilySignInPending("r_1", "Pixel 9", "Android"), sending = false, allow = close, deny = close)
                                    else HandOverCard(sheet, awayAllowed = true, server = "https://music.example.com", avatarName = "Alex", actions = SheetActions(done = close, owner = actions.owner), copy = {})
                                }
                            }
                        }
                        if (name == "11-no-outside-address") {
                            rig.shot(scene, null, 800)
                            rig.clickText(scene, "Anywhere")
                        }
                        shot(scene, name, 1_500)
                    }

                    // Changing the password, which signs out everywhere.
                    rig.reset(scene)
                    on("devices")
                    SwingUtilities.invokeAndWait { changePassword(app) }
                    shot(scene, "12-change-password", 1_500)
                }

                // A manager: the members, an invite, a reset link.
                server.answer("getFamily", owner, type = "octo")
                runBlocking { app.family.refresh() }
                rig.scene(PolishShotsTest.Size.Hd) { scene ->
                    rig.reset(scene)
                    SwingUtilities.invokeAndWait {
                        showSection(FAMILY, "members")
                        app.navigator.go(Page.Family)
                    }
                    shot(scene, "13-members", 2_000)
                    SwingUtilities.invokeAndWait { app.family.addMember("sam", "Sam", FamilyPreset.Kid) }
                    shot(scene, "14-invite", 2_500)
                    rig.reset(scene)
                    SwingUtilities.invokeAndWait {
                        showSection(FAMILY, "members")
                        app.navigator.go(Page.Family)
                        app.family.resetPassword(FamilyMember(username = "alex", displayName = "Alex"))
                    }
                    shot(scene, "15-new-sign-in-link", 2_500)
                }
                // A small window: the dialog scrolls inside, Done stays in sight.
                rig.scene(PolishShotsTest.Size.Min) { scene ->
                    rig.reset(scene)
                    SwingUtilities.invokeAndWait {
                        app.popups.close()
                        showSection(FAMILY, "members")
                        app.navigator.go(Page.Family)
                        showHandOver(app.popups, app, app.family)
                    }
                    rig.shot(scene, "one-login/16-small-window", 2_500)
                    SwingUtilities.invokeAndWait { app.popups.close() }
                }
            }
        }
    }

    // The dialog and a margin around it, out of the middle of the window.
    private fun crop(from: File, to: File) {
        val image = ImageIO.read(from) ?: return
        val w = minOf(860, image.width)
        val h = minOf(1000, image.height)
        ImageIO.write(image.getSubimage((image.width - w) / 2, (image.height - h) / 2, w, h), "png", to)
    }
}
