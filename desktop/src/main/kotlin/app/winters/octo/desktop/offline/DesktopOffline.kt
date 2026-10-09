package app.winters.octo.desktop.offline

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.winters.octo.desktop.server.Connection
import app.winters.octo.desktop.settings.SettingsStore
import app.winters.octo.offline.KeepChoices
import app.winters.octo.offline.keepPlan
import app.winters.octo.offline.keptPath
import app.winters.octo.offline.wantedCopies
import app.winters.octo.subsonic.OctoPurpose
import app.winters.octo.subsonic.Song
import app.winters.octo.subsonic.SubsonicException
import app.winters.octo.subsonic.markedFor
import app.winters.octo.ui.family.FamilyModel
import app.winters.octo.ui.family.OFFLINE_COPIES_OFF
import app.winters.octo.ui.family.offlineCopiesAllowed
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException

// One song kept on this computer: whose server it is from, its id there,
// and where its file is inside the offline folder.
@Serializable
data class KeptFile(
    val server: String,
    val id: String,
    val path: String,
    val size: Long = 0,
    val title: String = "",
    val artist: String = "",
)

// How the keeping is going, for the settings line.
data class KeepStatus(val kept: Int = 0, val bytes: Long = 0, val waiting: Int = 0, val failed: Int = 0, val busy: Boolean = false)

// Songs kept on this computer to play without a connection, as on the
// phone: picked by hand, the Liked songs, and chosen playlists, from the
// server in use. They go in a folder of the listener's choosing, with a
// list of them beside them (octo-offline.json), and play from there
// whenever they are there. Downloads say they are offline copies, so an
// Octo server does not count them as playing, and a family account with
// offline copies off gets none (files already here stay).
@Stable
class DesktopOffline(
    private val settings: SettingsStore,
    http: OkHttpClient,
    private val scope: CoroutineScope,
    private val defaultFolder: File,
    private val connection: () -> Connection?,
    // The server's Liked songs now, from the library read.
    private val liked: () -> List<Song>?,
    private val family: FamilyModel,
    private val say: (String) -> Unit,
) {
    private val http = http.markedFor(OctoPurpose.Offline)
    private val lock = Mutex()
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private var worker: Job? = null
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    // Songs picked by hand, as they were picked, so they are fetched without
    // asking the server about each again.
    private val picked = java.util.concurrent.ConcurrentHashMap<String, Song>()

    var files by mutableStateOf<List<KeptFile>>(emptyList())
        private set
    var status by mutableStateOf(KeepStatus())
        private set

    val folder: File get() = settings.current.offline.folder.takeIf(String::isNotBlank)?.let(::File) ?: defaultFolder

    private val indexFile: File get() = File(folder, INDEX)

    fun start() {
        files = readIndex()
        status = statusOf(files)
        worker = scope.launch(Dispatchers.IO) {
            for (signal in wake) runCatching { sync() }
        }
        poke()
    }

    // Looks at what should be kept again, soon.
    fun poke() {
        wake.trySend(Unit)
    }

    // The file of a song kept from the server in use, when it is here.
    fun localFile(songId: String): File? {
        val server = connection()?.server?.id ?: return null
        val kept = files.firstOrNull { it.server == server && it.id == songId } ?: return null
        return File(folder, kept.path).takeIf(File::isFile)
    }

    fun isKept(songId: String): Boolean = localFile(songId) != null

    fun choices(): KeepChoices = connection()?.server?.id?.let { settings.current.offline.servers[it] } ?: KeepChoices()

    fun isKeptByHand(songId: String): Boolean = songId in choices().byHand

    // Keeps these songs by hand, or lets them go.
    fun keep(songs: List<Song>, on: Boolean) = change { kept ->
        if (on) songs.forEach { picked[it.id] = it }
        val ids = songs.map { it.id }
        kept.copy(byHand = if (on) (kept.byHand + ids).distinct() else kept.byHand - ids.toSet())
    }

    fun keepLiked(on: Boolean) = change { it.copy(liked = on) }

    fun keepPlaylist(id: String, on: Boolean) = change { kept ->
        kept.copy(playlists = if (on) (kept.playlists + id).distinct() else kept.playlists - id)
    }

    fun isPlaylistKept(id: String): Boolean = id in choices().playlists

    // Moves the folder: kept files move with it.
    fun moveTo(target: File) {
        scope.launch(Dispatchers.IO) {
            lock.withLock {
                val from = folder
                if (from.canonicalPath == target.canonicalPath) return@withLock
                target.mkdirs()
                for (kept in files) {
                    val old = File(from, kept.path)
                    val new = File(target, kept.path)
                    new.parentFile?.mkdirs()
                    if (old.isFile && !old.renameTo(new)) {
                        old.copyTo(new, overwrite = true)
                        old.delete()
                    }
                }
                File(from, INDEX).delete()
                settings.update { it.copy(offline = it.offline.copy(folder = target.absolutePath)) }
                writeIndex(files)
            }
            poke()
        }
    }

    // Lets every kept song of the server in use go, and stops keeping any.
    fun removeAll() {
        val server = connection()?.server?.id ?: return
        settings.update { it.copy(offline = it.offline.copy(servers = it.offline.servers - server)) }
        poke()
    }

    private fun change(edit: (KeepChoices) -> KeepChoices) {
        val server = connection()?.server?.id ?: return
        settings.update { now ->
            val next = edit(now.offline.servers[server] ?: KeepChoices())
            now.copy(offline = now.offline.copy(servers = now.offline.servers + (server to next)))
        }
        poke()
    }

    // Fetches what should be kept and is not, and lets go what should not.
    private suspend fun sync() = lock.withLock {
        val connection = connection() ?: return@withLock
        val server = connection.server.id
        val client = connection.client
        val choices = settings.current.offline.servers[server] ?: KeepChoices()
        val likedIds = if (choices.liked) liked()?.filter { it.starred != null }?.map { it.id }.orEmpty() else emptyList()
        val unsure = HashSet<String>()
        val lists = HashMap<String, List<String>>()
        val songs = HashMap<String, Song>()
        songs.putAll(picked)
        liked()?.forEach { songs[it.id] = it }
        for (id in choices.playlists) {
            try {
                val list = client.playlist(id)
                lists[id] = list.entry.map { it.id }
                list.entry.forEach { songs.putIfAbsent(it.id, it) }
            } catch (e: SubsonicException) {
                // A playlist that cannot be read now keeps its songs here.
                unsure += files.filter { it.server == server }.map { it.id }
            }
        }
        val wanted = wantedCopies(choices, likedIds, lists)
        val mine = files.filter { it.server == server }
        val plan = keepPlan(wanted, mine.map { it.id }.toSet(), unsure)
        // Letting go first frees the room.
        if (plan.remove.isNotEmpty()) {
            val going = mine.filter { it.id in plan.remove }
            going.forEach { File(folder, it.path).delete() }
            files = files - going.toSet()
            writeIndex(files)
        }
        if (plan.fetch.isEmpty()) {
            status = statusOf(files)
            return@withLock
        }
        if (connection.family && !offlineCopiesAllowed(family.plan())) {
            status = statusOf(files).copy(waiting = plan.fetch.size)
            say(OFFLINE_COPIES_OFF)
            return@withLock
        }
        var failed = 0
        plan.fetch.forEachIndexed { at, id ->
            status = statusOf(files).copy(waiting = plan.fetch.size - at, failed = failed, busy = true)
            val song = songs[id] ?: runCatching { client.song(id) }.getOrNull()
            val got = song?.let { fetch(server, client, it) }
            if (got == null) failed++ else {
                files = files + got
                writeIndex(files)
            }
        }
        status = statusOf(files).copy(failed = failed)
    }

    // Downloads one song's file as it is, into the folder.
    private suspend fun fetch(server: String, client: app.winters.octo.subsonic.SubsonicClient, song: Song): KeptFile? = withContext(Dispatchers.IO) {
        val path = "${app.winters.octo.offline.fileSafe(server)}/" + keptPath(song.displayArtist ?: song.artist, song.album, song.track, song.title, song.suffix)
        val target = File(folder, path)
        val part = File(target.parentFile, target.name + ".part")
        try {
            target.parentFile?.mkdirs()
            val request = Request.Builder().url(client.url("stream", mapOf("id" to song.id, "format" to "raw"))).build()
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                part.outputStream().use { out -> response.body.byteStream().copyTo(out) }
            }
            if (target.exists()) target.delete()
            if (!part.renameTo(target)) return@withContext null
            KeptFile(server, song.id, path, target.length(), song.title, song.artist.orEmpty())
        } catch (e: IOException) {
            part.delete()
            null
        }
    }

    private fun statusOf(all: List<KeptFile>): KeepStatus {
        val server = connection()?.server?.id
        val mine = all.filter { it.server == server }
        return KeepStatus(kept = mine.size, bytes = mine.sumOf { it.size })
    }

    private fun readIndex(): List<KeptFile> =
        runCatching { json.decodeFromString(ListSerializer(KeptFile.serializer()), indexFile.readText()) }.getOrDefault(emptyList())
            .filter { File(folder, it.path).isFile }

    private fun writeIndex(all: List<KeptFile>) {
        runCatching {
            folder.mkdirs()
            val part = File(folder, "$INDEX.part")
            part.writeText(json.encodeToString(ListSerializer(KeptFile.serializer()), all))
            if (!part.renameTo(indexFile)) {
                indexFile.delete()
                part.renameTo(indexFile)
            }
        }
    }

    companion object {
        const val INDEX = "octo-offline.json"
    }
}
