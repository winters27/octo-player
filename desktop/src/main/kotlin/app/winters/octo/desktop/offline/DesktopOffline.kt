package app.winters.octo.desktop.offline

import app.winters.octo.offline.AdtsToM4a
import app.winters.octo.offline.sameAudioKind
import app.winters.octo.offline.smallerKbps
import app.winters.octo.offline.sniffAudio
import app.winters.octo.playback.DownloadFormat
import app.winters.octo.ui.family.listedAudioType
import app.winters.octo.subsonic.StreamQuality
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.winters.octo.desktop.animated.KEPT_LOOPS
import app.winters.octo.desktop.animated.KeptLoops
import app.winters.octo.desktop.server.Connection
import app.winters.octo.subsonic.AnimatedArtAnswer
import app.winters.octo.desktop.settings.SettingsStore
import app.winters.octo.offline.KeepChoices
import app.winters.octo.offline.KeptCopyCheck
import app.winters.octo.offline.checkKeptCopy
import app.winters.octo.offline.keepPlan
import app.winters.octo.offline.keptPath
import app.winters.octo.offline.wantedCopies
import app.winters.octo.subsonic.OctoPurpose
import app.winters.octo.subsonic.Song
import app.winters.octo.subsonic.SubsonicException
import app.winters.octo.subsonic.gateway.isHtml
import app.winters.octo.subsonic.gateway.looksLikeHtmlFile
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
// and where its file is inside the offline folder. `fileSize` is the
// server's size for the file it was made from, so a new file under the same
// id is told apart; files kept before it was noted have none until the
// next check.
@Serializable
data class KeptFile(
    val server: String,
    val id: String,
    val path: String,
    val size: Long = 0,
    val title: String = "",
    val artist: String = "",
    val fileSize: Long? = null,
    // The song's album, so the album's animated art is kept with it.
    val album: String = "",
    // A copy made smaller than the server's file: what was asked ("opus-160"),
    // or "server" when the server sent another kind of file than listed to an
    // ask for the file as it is. Null for the server's file as it is, and for
    // every file kept before this was noted.
    val made: String? = null,
)

// The songs of a kept playlist that are kept here: its library songs. A
// song found online in it is not the library's, so it is never fetched.
fun keptEntries(entries: List<Song>): List<Song> = entries.filterNot { it.isExternal }

// Whether a kept file is the server's file as it is: it has the song's own
// type and was not made smaller (`made`). Opus is left out, since smaller
// copies were made as Opus before `made` was noted.
fun keptAsIs(path: String, suffix: String?, made: String? = null): Boolean {
    val type = path.substringAfterLast('/').substringAfterLast('.', "")
    return made == null && type.isNotEmpty() && type.equals(suffix, ignoreCase = true) && !type.equals("opus", ignoreCase = true)
}

// The ending a kept file gets for the type its bytes are, or the listed
// suffix when that type is not one it knows.
fun keptSuffix(type: String?, listed: String?): String? = when (type?.substringBefore(';')?.trim()?.lowercase()) {
    "audio/flac", "audio/x-flac" -> "flac"
    "audio/mpeg", "audio/mp3" -> "mp3"
    "audio/mp4", "audio/x-m4a", "audio/m4a" -> "m4a"
    "audio/aac" -> "aac"
    "audio/opus" -> "opus"
    "audio/ogg", "application/ogg" -> "ogg"
    "audio/webm" -> "webm"
    "audio/wav", "audio/x-wav", "audio/wave" -> "wav"
    "audio/aiff", "audio/x-aiff" -> "aiff"
    else -> null
} ?: listed

// What a download's bytes are: their sniffed type, else the type the server
// answered with when it names one, else `fallback` (what was asked).
fun arrivedType(sniffed: String?, answered: String?, fallback: String?): String? =
    sniffed ?: answered?.substringBefore(';')?.trim()?.lowercase()?.takeIf { it.isNotEmpty() && it != "application/octet-stream" } ?: fallback

// What a kept song is asked for: the file as it is when the download
// quality is Original or the song is a lossy file already at or under it,
// otherwise `format` at the quality's bitrate. Never a length estimate: a
// server's guess at the length of a copy it makes cuts or pads the file.
fun downloadParams(song: Song, quality: StreamQuality, format: DownloadFormat): Map<String, String> {
    val kbps = smallerKbps(listedAudioType(song.contentType, song.suffix), song.bitRate?.let { it * 1000 }, quality.kbps.takeIf { it > 0 })
        ?: return mapOf("format" to "raw")
    return mapOf("format" to format.param, "maxBitRate" to kbps.toString())
}

// What a download is named while it arrives, before its bytes say what it
// is: "01 One.download.part".
private const val ARRIVING = "download"

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
    // The animated art of the albums kept here, and how to ask the server
    // in use about an album's (null when it offers none, or the listener
    // has animated art off).
    private val loops: KeptLoops? = null,
    private val askArt: () -> (suspend (String) -> AnimatedArtAnswer)? = { null },
) {
    private val http = http.markedFor(OctoPurpose.Offline)
    private val lock = Mutex()
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private var worker: Job? = null
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    // Songs picked by hand, as they were picked, so they are fetched without
    // asking the server about each again.
    private val picked = java.util.concurrent.ConcurrentHashMap<String, Song>()

    // Kept files already looked at this run for a sign-in page kept in a
    // song's place, by path.
    private val sniffed: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

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
            runCatching { dropParts() }
            for (signal in wake) {
                runCatching { sync() }
                runCatching { keepLoops() }
            }
        }
        poke()
    }

    // Download part files a run that stopped part way left in the servers'
    // folders. Only those folders are looked in (never the rest of the
    // listener's folder), the albums' animated art keeps its own part files
    // (its folder is passed over), and nothing but part files goes.
    private fun dropParts() {
        val servers = (settings.current.offline.servers.keys + files.map { it.server })
            .map { app.winters.octo.offline.fileSafe(it) }
            .toSet()
        for (name in servers) {
            File(folder, name).walkTopDown()
                .onEnter { it.name != KEPT_LOOPS }
                .filter { it.isFile && it.name.endsWith(".part") }
                .forEach { it.delete() }
        }
    }

    // Puts a download of AAC in ADTS into an M4A, so it says its own length.
    // A test parks it to change things while it runs.
    internal var rewrapAdts: (File, File) -> Boolean = AdtsToM4a::rewrap

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

    // The quality kept songs are fetched in from now on. Songs already here
    // stay as they are.
    val downloadQuality: StreamQuality
        get() = StreamQuality.entries.firstOrNull { it.name == settings.current.offline.downloadQuality } ?: StreamQuality.Original

    fun setDownloadQuality(quality: StreamQuality) {
        settings.update { it.copy(offline = it.offline.copy(downloadQuality = quality.name)) }
    }

    // What smaller copies are made as from now on. Songs already here stay
    // as they are.
    val downloadFormat: DownloadFormat
        get() = DownloadFormat.named(settings.current.offline.downloadFormat)

    fun setDownloadFormat(format: DownloadFormat) {
        settings.update { it.copy(offline = it.offline.copy(downloadFormat = format.name)) }
    }

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
                // The albums' animated art goes with them.
                files.map { it.server }.distinct().forEach { server ->
                    val old = File(from, "${app.winters.octo.offline.fileSafe(server)}${File.separator}$KEPT_LOOPS")
                    val new = File(target, "${app.winters.octo.offline.fileSafe(server)}${File.separator}$KEPT_LOOPS")
                    if (old.isDirectory && !new.exists()) {
                        new.parentFile?.mkdirs()
                        if (!old.renameTo(new)) {
                            old.copyRecursively(new, overwrite = true)
                            old.deleteRecursively()
                        }
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
        scope.launch(Dispatchers.IO) { lock.withLock { loops?.removeAll(server) } }
        settings.update { it.copy(offline = it.offline.copy(servers = it.offline.servers - server)) }
        poke()
    }

    // The albums' animated art kept here, for their songs.
    val keptLoops: KeptLoops? get() = loops

    private fun change(edit: (KeepChoices) -> KeepChoices) {
        val server = connection()?.server?.id ?: return
        settings.update { now ->
            val next = edit(now.offline.servers[server] ?: KeepChoices())
            now.copy(offline = now.offline.copy(servers = now.offline.servers + (server to next)))
        }
        poke()
    }

    // Keeps the animated art of every album with a song kept from the
    // server in use, and lets go of the rest.
    private suspend fun keepLoops() = lock.withLock {
        val loops = loops ?: return@withLock
        val server = connection()?.server?.id ?: return@withLock
        val albums = files.filter { it.server == server && it.album.isNotBlank() }.mapTo(HashSet()) { it.album }
        loops.keep(server, albums, askArt())
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
        // What the server says of each song now, for checking kept files.
        val now = HashMap<String, Song>()
        songs.putAll(picked)
        liked()?.forEach {
            songs[it.id] = it
            now[it.id] = it
        }
        for (id in choices.playlists) {
            try {
                val list = client.playlist(id)
                val entries = keptEntries(list.entry)
                lists[id] = entries.map { it.id }
                entries.forEach {
                    songs.putIfAbsent(it.id, it)
                    now.putIfAbsent(it.id, it)
                }
            } catch (e: SubsonicException) {
                // A playlist that cannot be read now keeps its songs here.
                unsure += files.filter { it.server == server }.map { it.id }
            }
        }
        val wanted = wantedCopies(choices, likedIds, lists)
        checkFiles(server, now)
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

    // Downloads one song into the folder, in the download quality and
    // format (see downloadParams). It arrives as a part file in the server's
    // folder; what came is named and labeled by its bytes, a copy made
    // smaller notes so (`made`), AAC in ADTS is put into an M4A so it says
    // its own length, and its size is what is kept.
    private suspend fun fetch(server: String, client: app.winters.octo.subsonic.SubsonicClient, song: Song): KeptFile? = withContext(Dispatchers.IO) {
        val format = downloadFormat
        val params = downloadParams(song, downloadQuality, format)
        val asked = params["maxBitRate"]?.let { "${format.param}-$it" }
        val listed = listedAudioType(song.contentType, song.suffix)
        val serverFolder = app.winters.octo.offline.fileSafe(server)
        val artist = song.displayArtist ?: song.artist
        val arrival = File(folder, "$serverFolder/" + keptPath(artist, song.album, song.track, song.title, ARRIVING) + ".part")
        var rewrapped: File? = null
        try {
            val request = Request.Builder().url(client.url("stream", mapOf("id" to song.id) + params)).build()
            val answered = http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                // A sign-in page that answered in the server's place is no song.
                if (isHtml(response.header("Content-Type"))) return@withContext null
                arrival.parentFile?.mkdirs()
                arrival.outputStream().use { out -> response.body.byteStream().copyTo(out) }
                response.header("Content-Type")
            }
            val head = firstBytes(arrival, HEAD_BYTES)
            val type = arrivedType(sniffAudio(head, head.size), answered, if (asked != null) format.mimeType else listed)
            val smaller = asked != null || !sameAudioKind(listed, type)
            val rewrap = type == "audio/aac"
            val suffix = when {
                rewrap -> "m4a"
                !smaller && !song.suffix.isNullOrBlank() -> song.suffix
                else -> keptSuffix(type, song.suffix)
            }
            val path = "$serverFolder/" + keptPath(artist, song.album, song.track, song.title, suffix)
            val target = File(folder, path)
            val ready = if (rewrap) {
                val part = File(target.parentFile, target.name + ".part").also { rewrapped = it }
                if (!rewrapAdts(arrival, part)) return@withContext null
                part
            } else {
                arrival
            }
            // Every song of this server let go while it arrived: nothing is kept.
            if (settings.current.offline.servers[server] == null) return@withContext null
            if (target.exists()) target.delete()
            if (!ready.renameTo(target)) return@withContext null
            KeptFile(
                server, song.id, path, target.length(), song.title, song.artist.orEmpty(), song.size?.takeIf { it > 0 }, song.albumId.orEmpty(),
                made = if (smaller) asked ?: "server" else null,
            )
        } catch (e: IOException) {
            null
        } finally {
            // Whatever was not renamed into place goes.
            arrival.delete()
            rewrapped?.delete()
        }
    }

    // The server can swap a song's file for another (a better copy, say)
    // under the same id. A kept file of an old one goes, so it never plays,
    // and is fetched again with the rest. Files kept before the server's
    // size was noted have it noted here. A family account without offline
    // copies keeps what it has. A sign-in page an older version kept in a
    // song's place goes whatever the family allows, since it never plays,
    // and is fetched again with the rest where copies are allowed.
    private suspend fun checkFiles(server: String, now: Map<String, Song>) {
        val pages = storedPages(server)
        var changed = false
        val swapped = ArrayList<KeptFile>()
        val checked = files.filterNot { it.path in pages }.map { kept ->
            val song = now[kept.id]?.takeIf { kept.server == server } ?: return@map kept
            // Files kept before their album was noted have it noted here.
            val noted = if (kept.album.isEmpty() && !song.albumId.isNullOrBlank()) kept.copy(album = song.albumId.orEmpty()).also { changed = true } else kept
            when (checkKeptCopy(noted.fileSize, song.size, noted.size, keptAsIs(noted.path, song.suffix, noted.made))) {
                KeptCopyCheck.Keep -> noted
                KeptCopyCheck.Note -> noted.copy(fileSize = song.size).also { changed = true }
                KeptCopyCheck.Refetch -> noted.also { swapped += it }
            }
        }
        val refused = swapped.isNotEmpty() && connection()?.family == true && !offlineCopiesAllowed(family.plan())
        val going = if (refused) emptySet() else swapped.toSet()
        going.forEach { File(folder, it.path).delete() }
        pages.forEach { File(folder, it).delete() }
        if (changed || going.isNotEmpty() || pages.isNotEmpty()) {
            files = checked - going
            writeIndex(files)
        }
    }

    // The paths of `server`'s kept files that hold a sign-in page instead of
    // a song. Each file is looked at once a run.
    private fun storedPages(server: String): Set<String> =
        files.filter { it.server == server && sniffed.add(it.path) && looksLikeHtmlFile(firstBytes(File(folder, it.path))) }
            .mapTo(HashSet()) { it.path }

    // The start of a file: 64 bytes tell a page from a song; a type is told
    // from more, past any tag at its start.
    private fun firstBytes(file: File, size: Int = 64): ByteArray = runCatching {
        file.inputStream().use { stream ->
            val bytes = ByteArray(size)
            val read = stream.readNBytes(bytes, 0, size)
            if (read <= 0) ByteArray(0) else bytes.copyOf(read)
        }
    }.getOrDefault(ByteArray(0))

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

        // How much of a download is read to tell its type.
        private const val HEAD_BYTES = 4096
    }
}
