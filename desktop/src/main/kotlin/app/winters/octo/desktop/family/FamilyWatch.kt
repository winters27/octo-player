package app.winters.octo.desktop.family

import app.winters.octo.desktop.AppState
import app.winters.octo.desktop.NoticeAction
import app.winters.octo.desktop.pages.openFamilyLink
import app.winters.octo.subsonic.SubsonicException
import app.winters.octo.subsonic.parseFamilyLink
import app.winters.octo.ui.family.FAMILY_CHECK_MS
import app.winters.octo.ui.family.FamilyNotice
import app.winters.octo.ui.family.NoticeMemory
import app.winters.octo.ui.family.checkFamilyNotices
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File

// What each server's account has been told about its family requests, in
// a file of its own beside the settings, so each decision is told once.
class NoticeFile(private val file: File) {
    private val serializer = MapSerializer(String.serializer(), NoticeMemory.serializer())
    private val json = Json { ignoreUnknownKeys = true }

    @Synchronized
    fun read(server: String): NoticeMemory = all()[server] ?: NoticeMemory()

    @Synchronized
    fun write(server: String, memory: NoticeMemory) {
        val next = all() + (server to memory)
        runCatching {
            file.parentFile?.mkdirs()
            val part = File(file.parentFile, file.name + ".part")
            part.writeText(json.encodeToString(serializer, next))
            if (!part.renameTo(file)) {
                file.delete()
                part.renameTo(file)
            }
        }
    }

    private fun all(): Map<String, NoticeMemory> =
        runCatching { json.decodeFromString(serializer, file.readText()) }.getOrDefault(emptyMap())
}

// The family's news while the app runs: requests approved, declined, added
// or failed, and for a manager how many wait, as the system's own
// notifications, checked when a server with Family is signed in and every
// 30 minutes after. Also offers a family link found on the clipboard when
// the app opens or comes to the front.
class FamilyWatch(
    private val app: AppState,
    private val notices: NoticeFile,
    private val show: (FamilyNotice) -> Unit,
    private val clipboard: () -> String? = { clipboardText() },
) {
    private var checking: Job? = null
    private var offered: String? = null

    fun start() {
        app.scope.launch {
            snapshotFlowOf { app.connection?.server?.id to (app.connection?.family == true) }.distinctUntilChanged().collect { (server, family) ->
                checking?.cancel()
                checking = null
                if (server != null && family) checking = app.scope.launch { every(server) }
            }
        }
        offerClipboard()
    }

    private suspend fun every(server: String) {
        while (isActiveNow()) {
            checkNow(server)
            delay(FAMILY_CHECK_MS)
        }
    }

    // One check: tells what is new, and remembers it.
    suspend fun checkNow(server: String) {
        val client = app.connection?.client ?: return
        try {
            val (told, memory) = withContext(Dispatchers.IO) { checkFamilyNotices(client, notices.read(server)) }
            notices.write(server, memory)
            told.forEach(show)
        } catch (e: SubsonicException) {
            // Asked again at the next turn.
        }
    }

    // A family link on the clipboard is offered once, in the notice line.
    fun offerClipboard() {
        val text = clipboard() ?: return
        if (text == offered) return
        val link = parseFamilyLink(text) ?: return
        offered = text
        val words = if (link is app.winters.octo.subsonic.FamilyInviteLink) "A family invite is on the clipboard." else "A family join link is on the clipboard."
        app.notice = words
        app.noticeDetail = null
        app.noticeAction = NoticeAction(words, "Use it") { app.openFamilyLink(link) }
    }

    private suspend fun isActiveNow(): Boolean = kotlin.coroutines.coroutineContext.isActive
}

private fun <T> snapshotFlowOf(read: () -> T) = androidx.compose.runtime.snapshotFlow(read)
