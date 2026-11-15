package app.winters.octo.desktop.a11y

import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.dp
import app.winters.octo.desktop.ui.VersionsContent
import app.winters.octo.desktop.ui.viewOf
import app.winters.octo.subsonic.Song
import app.winters.octo.subsonic.StreamVersion
import app.winters.octo.subsonic.StreamVersionPlaying
import app.winters.octo.subsonic.StreamVersions
import app.winters.octo.theme.ThemeChoice
import app.winters.octo.versions.StreamVersionWords
import app.winters.octo.versions.StreamVersionsEvent
import app.winters.octo.versions.StreamVersionsSheetState
import app.winters.octo.versions.VersionsSong
import app.winters.octo.versions.on
import org.jetbrains.skia.EncodedImageFormat
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

// Pictures of "Choose the version" in each of its states, on a dark and a
// light theme and in a small window with large text, and of the player
// bar's More menu with its row: OCTO_SHOTS=1 ./gradlew :desktop:test
// --tests '*VersionsShotsTest*' writes OCTO_SHOTS_DIR (else
// build/shots/versions).
class VersionsShotsTest {
    @get:Rule val folder = TemporaryFolder()

    private val out by lazy { File(System.getenv("OCTO_SHOTS_DIR") ?: "build/shots/versions").apply { mkdirs() } }

    private val song = VersionsSong(entryId = "1", songId = "ext-1", title = "Glory Box", artist = "Portishead")

    private fun playing(picked: String, by: String? = null, mine: Boolean = false) = StreamVersionPlaying(
        videoId = "v1",
        title = "Glory Box",
        channel = "Portishead - Topic",
        duration = 306,
        kind = "release",
        picked = picked,
        pickedBy = by,
        mine = mine,
    )

    private val rows = listOf(
        StreamVersion("v2", "Portishead - Glory Box (Roseland NYC Live)", "Portishead", 362, "video"),
        StreamVersion("v3", "Glory Box (Official Video)", "PortisheadVEVO", 307, "musicVideo"),
        StreamVersion("v4", "Portishead Glory Box slowed and reverb, a much longer title that wraps onto a second line", "late night edits", 412, "video"),
    )

    private fun answer(
        playing: StreamVersionPlaying? = playing("octo"),
        versions: List<StreamVersion> = rows,
        heard: String? = null,
        missed: List<String> = emptyList(),
        playsFrom: String = "stream",
        busy: Boolean = false,
    ) = StreamVersions(id = "ext-1", playsFrom = playsFrom, playing = playing, heardVideoId = heard, busy = busy, version = versions, missed = missed, ids = listOf("ext-1"))

    private val looking: StreamVersionsSheetState = StreamVersionsSheetState.Closed.on(StreamVersionsEvent.Open(song))

    private fun shown(of: StreamVersions) = looking.on(StreamVersionsEvent.Listed(of))

    // Every state, by the name its picture gets.
    private fun states(): List<Pair<String, StreamVersionsSheetState>> {
        val mine = answer(playing("person", mine = true), heard = "v0")
        val long = answer(
            playing("person", by = "ede"),
            versions = (1..15).map { StreamVersion("w$it", "Glory Box, take $it", "Channel $it", 290 + it, if (it % 3 == 0) "musicVideo" else "video") },
        )
        return listOf(
            "looking" to looking,
            "could-not-reach-youtube" to shown(answer(versions = emptyList(), missed = listOf("youtubeMusic", "youtube"))),
            "could-not-reach-server" to looking.on(StreamVersionsEvent.ListFailed(null)),
            "unavailable" to looking.on(StreamVersionsEvent.ListFailed("This song can't be given another version.")),
            "octos-pick" to shown(answer()),
            "your-pick-next-start" to shown(mine),
            "picked-by" to shown(answer(playing("person", by = "ede"))),
            "earlier" to shown(answer(playing("earlier"))),
            "long-list" to shown(long),
            "checking" to shown(answer()).on(StreamVersionsEvent.Pick("v3", "1", "ext-1")),
            "refused" to shown(answer()).on(StreamVersionsEvent.Pick("v3", "1", "ext-1"))
                .on(StreamVersionsEvent.PickFailed("That video can't be played here. Pick another.")),
            "missed-youtube-music" to shown(answer(missed = listOf("youtubeMusic"))),
            "nothing" to shown(answer(versions = emptyList())),
            "busy" to shown(answer(playing("person", mine = true), busy = true)),
            "file" to shown(answer(playsFrom = "file", versions = emptyList())),
            "download" to shown(answer(playsFrom = "download", versions = emptyList())),
            "undo-checking" to shown(mine).on(StreamVersionsEvent.Undo("1", "ext-1")),
            "undo-refused" to shown(mine).on(StreamVersionsEvent.Undo("1", "ext-1"))
                .on(StreamVersionsEvent.PickFailed(StreamVersionWords.PICK_UNREACHABLE)),
        )
    }

    private fun scene(theme: ThemeChoice, width: Int = 1440, height: Int = 900, text: Float = 1f) = A11yScene(
        folder.newFolder(),
        reduceMotion = true,
        width = width,
        height = height,
        textScale = text,
        extensions = listOf("octoStreamVersions:1", "octoStreamVersionChanges:1"),
        look = { it.copy(appearance = it.appearance.copy(theme = theme)) },
    )

    private fun A11yScene.picture(name: String) {
        val image = settle(700)
        File(out, "$name.png").writeBytes(image.encodeToData(EncodedImageFormat.PNG)!!.bytes)
        image.close()
    }

    private fun A11yScene.open(state: StreamVersionsSheetState) {
        onUi {
            val done = FocusRequester()
            app.popups.showCentred(width = 480.dp, scrim = true, scrollsItself = true, first = done, holdTop = true) { close ->
                VersionsContent(viewOf(state), StreamVersionWords.songLine(song.title, song.artist), close, {}, {}, {}, done)
            }
        }
        render(6)
    }

    // Presses the control a screen reader calls `name` by its click action.
    private fun A11yScene.click(name: String) {
        val node = nodes(merged = true).firstOrNull { it.name() == name && it.clickable }
            ?: nodes().firstOrNull { it.config.getOrNull(SemanticsActions.OnClick)?.label == name }
            ?: error("no control $name")
        onUi { node.config[SemanticsActions.OnClick].action?.invoke() }
        render(6)
    }

    @Test(timeout = 600_000)
    fun drawEveryState() {
        assumeTrue(System.getenv("OCTO_SHOTS") == "1")
        for (theme in listOf(ThemeChoice.Octo, ThemeChoice.Daylight)) {
            scene(theme).use { s ->
                val tag = theme.name.lowercase()
                for ((name, state) in states()) {
                    s.open(state)
                    s.picture("versions-$name-$tag")
                }
                s.open(states().first { it.first == "long-list" }.second)
                s.click(StreamVersionWords.MORE_BELOW)
                s.picture("versions-long-list-end-$tag")
            }
        }
        // A small window with the largest text.
        scene(ThemeChoice.Octo, width = 900, height = 640, text = 1.5f).use { s ->
            for (name in listOf("your-pick-next-start", "long-list", "refused")) {
                s.open(states().first { it.first == name }.second)
                s.picture("versions-$name-small-large-text")
            }
        }
    }

    @Test(timeout = 300_000)
    fun drawTheMenuRow() {
        assumeTrue(System.getenv("OCTO_SHOTS") == "1")
        for (theme in listOf(ThemeChoice.Octo, ThemeChoice.Daylight)) {
            scene(theme).use { s ->
                val find = Song(id = "ext-1", title = "Glory Box", artist = "Portishead", album = "Dummy", duration = 306, isExternal = true)
                s.onUi { s.app.player.play(listOf(find)) }
                s.render(6)
                s.click("More")
                s.picture("versions-menu-${theme.name.lowercase()}")
            }
        }
    }
}
