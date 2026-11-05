package app.winters.octo.desktop.system

import androidx.compose.runtime.snapshotFlow
import app.winters.octo.desktop.AppState
import app.winters.octo.desktop.SidePanel
import app.winters.octo.animated.KnownArt
import app.winters.octo.desktop.animated.LoopShape
import app.winters.octo.desktop.animated.clip
import app.winters.octo.desktop.player.RepeatMode
import app.winters.octo.desktop.playStations
import app.winters.octo.desktop.radioId
import app.winters.octo.desktop.ui.limitOfFirst
import app.winters.octo.desktop.ui.setLimitAll
import app.winters.octo.desktop.ui.trimAllWindows
import app.winters.octo.liveradio.radioId
import app.winters.octo.subsonic.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import app.winters.octo.desktop.library.LibraryState
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.Properties
import kotlin.random.Random

// Set to a plan file, the app plays by itself for as long as it runs once
// its window is up, for measuring its memory over hours
// (scripts/soak-desktop.ps1). It plays at no volume (the player's own, for
// this run only), keeps the full player open with the lyrics beside the
// cover or not, and moves to the next song every `advance` seconds (0 lets
// each one play out), round the queue again and again. The plan is a
// properties file:
//   source=library   songs from albums with a tall animated loop first
//                    (`songs` of them, asking about at most `ask` albums),
//                    or radio: the server's stations, else the kept ones,
//                    or live: only the kept stations (internet radio)
//   lyrics=on|off    the lyrics beside the cover
//   full=on|off      the full player open, or Home behind the player bar
//   pauseAfter=20    minutes before it pauses and stays paused (0, never)
//   cacheAfter=30:32 minutes before the window's graphics card cache limit
//                    is set to that many MB, to see what it gives back
//   advance=45       seconds a song plays before the next
//   output=XG270     plays to the first output whose name holds this
//   miniOnly=true    hands over to the mini player (the window goes away)
//   purgeEvery=300   seconds between emptying every window's GPU cache
//   toggleMiniEvery=0 seconds between handing over to the mini player and
//                    back (0, never)
//   switchOutputs=30 seconds between moving the music to the next output
//                    (0, never)
//   queue=<file>     the songs picked, written by the first run and read by
//                    the next ones, so every run plays the same songs
//   calibrate=true   pauses and checks the memory log's parts instead of
//                    playing on (see calibrate)
// What it plays is written to <plan>.events, a line each.
const val SOAK_RUN = "octo.soak"

suspend fun soakRun(app: AppState, plan: File) {
    val wants = Properties().apply { plan.inputStream().use(::load) }
    val events = File(plan.path + ".events")
    fun note(line: String) {
        runCatching { events.appendText("${System.currentTimeMillis()} $line\n") }
    }
    val advance = wants.getProperty("advance", "45").toLong()
    val lyrics = wants.getProperty("lyrics", "on") == "on"
    val full = wants.getProperty("full", "on") == "on"
    val pauseAfter = wants.getProperty("pauseAfter", "0").toLong() * 60_000
    val cacheAfter = wants.getProperty("cacheAfter", "").split(":").mapNotNull(String::toLongOrNull).takeIf { it.size == 2 }
    var cacheSet = false
    val source = wants.getProperty("source", "library")
    val radio = source == "radio" || source == "live"
    note("start source=$source lyrics=$lyrics full=$full advance=$advance")

    val index = withTimeoutOrNull(300_000) {
        val library = snapshotFlow { app.library }.filterNotNull().first()
        library.state.filterIsInstance<LibraryState.Ready>().first().index
    }
    if (index == null) return note("stop: the library was not read within 5 minutes")
    app.player.setVolume(0f)
    if (radio) {
        val ids = soakStations(app, liveOnly = source == "live")
        if (ids.isEmpty()) return note("stop: no radio stations")
        note("stations ${ids.size}")
        app.playStations(ids, 0, "Soak")
    } else {
        val songs = soakSongs(app, index.songs, wants, ::note)
        if (songs.isEmpty()) return note("stop: no songs")
        note("songs ${songs.size}")
        app.play(songs, 0, source = "Soak")
    }
    app.player.setRepeat(RepeatMode.All)
    if (wants.getProperty("calibrate", "false") == "true") return calibrate(app, ::note)
    // Every `purgeEvery` seconds, each window's graphics card cache is emptied
    // (its limit set to nothing, then back), so the spares it holds go and
    // the blocks they sat in can be let go. Each renderer is reached the way
    // it draws (see GpuCacheTrim), never Direct3D on the window's thread:
    // done there it raced a frame and crashed.
    val purgeEvery = wants.getProperty("purgeEvery", "0").toLong()
    if (purgeEvery > 0) CoroutineScope(Dispatchers.Default).launch {
        var purges = 0
        while (true) {
            delay(purgeEvery * 1_000)
            val trimmed = trimAllWindows()
            if (++purges % 10 == 1 || purgeEvery >= 60) note("purged $trimmed windows' caches ($purges)")
        }
    }
    // Hands the window over to the mini player and back every `toggleMiniEvery`
    // seconds.
    val toggleMiniEvery = wants.getProperty("toggleMiniEvery", "0").toLong()
    if (toggleMiniEvery > 0) CoroutineScope(Dispatchers.Default).launch {
        var toggles = 0
        while (true) {
            delay(toggleMiniEvery * 1_000)
            withContext(Dispatchers.Main) { app.toggleMiniPlayer?.invoke() }
            note("mini player toggled (${++toggles})")
        }
    }
    // Hands over to the mini player, which puts the window away.
    if (wants.getProperty("miniOnly", "false") == "true") {
        withContext(Dispatchers.Main) { app.toggleMiniPlayer?.invoke() }
        note("mini player only")
    }
    // Plays to the output whose name holds `output`, when one does.
    wants.getProperty("output", "").takeIf(String::isNotBlank)?.let { part ->
        val chosen = withTimeoutOrNull(30_000) {
            app.player.state.first { state -> state.outputs.any { it.name.contains(part, ignoreCase = true) } }
        }?.outputs?.first { it.name.contains(part, ignoreCase = true) }
        if (chosen != null) app.player.selectOutput(chosen.id)
        note("output ${chosen?.id} ${chosen?.name ?: "none matched $part"}")
    }
    // Moves the music to the next output every `switchOutputs` seconds, so
    // the engine opens a device again and again, as sleep and wake or a
    // headset coming and going make it.
    val switchEvery = wants.getProperty("switchOutputs", "0").toLong()
    if (switchEvery > 0) CoroutineScope(Dispatchers.Default).launch {
        var turn = 0
        while (true) {
            delay(switchEvery * 1000)
            val outputs = app.player.state.value.outputs
            if (outputs.size < 2) continue
            val next = outputs[++turn % outputs.size]
            withContext(Dispatchers.Main) { app.player.selectOutput(next.id) }
            note("output ${next.id} ${next.name}")
        }
    }
    var played = 0
    val started = System.currentTimeMillis()
    while (true) {
        if (cacheAfter != null && !cacheSet && System.currentTimeMillis() - started >= cacheAfter[0] * 60_000) {
            cacheSet = true
            withContext(Dispatchers.Default) { setLimitAll(cacheAfter[1] * 1024 * 1024) }
            note("cache limit set to ${cacheAfter[1]} MB, now ${gpuCacheLimit()}")
        }
        if (pauseAfter > 0 && System.currentTimeMillis() - started >= pauseAfter) {
            app.player.pause()
            note("paused gpuCache=${gpuCacheLimit()}")
            awaitCancellation()
        }
        app.player.setVolume(0f)
        app.fullPlayer = full
        if ((app.playerPanel == SidePanel.Lyrics) != lyrics) app.togglePlayerPanel(SidePanel.Lyrics)
        val now = app.player.state.value
        note("playing ${played++} ${now.current?.song?.id} album=${now.current?.song?.albumId} playing=${now.playing} gpuCache=${gpuCacheLimit()}")
        now.current?.song?.let { song ->
            val art = song.albumId?.let { (app.animated.book?.peek(it) as? KnownArt.Has)?.art }
            note("art title=${song.title} album=${song.album} tall=${art?.tall?.video} poster=${art?.tall?.poster}")
        }
        if (advance > 0) {
            delay(advance * 1000)
            app.player.next()
        } else {
            val key = now.current?.key
            withTimeoutOrNull(15 * 60_000) { app.player.state.first { it.current?.key != key } }
            // A queue that stopped at its end starts over.
            if (!app.player.state.value.playing) app.player.togglePlay()
        }
    }
}

// Checks that the memory log's parts move as they should, with the song
// paused: memory the app takes outside every named part shows in `other`
// alone, and the graphics memory of the full player does not. Writes a
// "calib" line with the memory fields once each step has settled (or
// settled=no after 5 minutes), then "calib done".
private suspend fun calibrate(app: AppState, note: (String) -> Unit) {
    fun art(choice: String) = app.settings.update { it.copy(appearance = it.appearance.copy(animatedArt = choice)) }
    app.player.pause()
    art("off")
    app.fullPlayer = false
    // Read until two readings 30 s apart have `watched` within 8 MB, at most 5 minutes.
    suspend fun settled(step: String, watched: (MemoryReading) -> Long? = { it.privateMb }) {
        var last = withContext(Dispatchers.IO) { readMemory { null } }
        var steady = false
        for (attempt in 1..10) {
            delay(30_000)
            val now = withContext(Dispatchers.IO) { readMemory { null } }
            val before = watched(last)
            val after = watched(now)
            last = now
            if (before != null && after != null && kotlin.math.abs(after - before) <= 8) {
                steady = true
                break
            }
        }
        val fields = memoryLine("", 0, last, "", false, 0, null).substringAfter("private=").substringBefore(" window=")
        note("calib $step settled=${if (steady) "yes" else "no"} private=$fields")
    }
    settled("start")
    val block = com.sun.jna.Memory(256L * 1024 * 1024)
    try {
        var at = 0L
        while (at < block.size()) {
            block.setByte(at, 1)
            at += 4096
        }
        settled("native-256")
    } finally {
        block.close()
    }
    settled("native-freed")
    art("full_screen")
    app.fullPlayer = true
    settled("full-player") { it.gpuCommittedMb }
    note("calib done")
}

// The graphics card cache limit of the window's drawing, in bytes, as Skia
// has it: skiko keeps the context out of reach, so it is read by reflection,
// each renderer on its own thread (null when its insides differ, or it draws
// without the card).
private suspend fun gpuCacheLimit(): Long? = withContext(Dispatchers.Default) { limitOfFirst() }

// The songs a library run plays: the ones picked by an earlier run, or one
// song from each album with a tall loop, the rest from any album.
private suspend fun soakSongs(app: AppState, library: List<Song>, wants: Properties, note: (String) -> Unit): List<Song> {
    val queue = wants.getProperty("queue")?.let(::File)
    val byId = library.associateBy { it.id }
    queue?.takeIf { it.exists() }?.let { file ->
        val ids = withContext(Dispatchers.IO) { file.readLines() }.filter(String::isNotBlank)
        return ids.mapNotNull(byId::get)
    }
    val count = wants.getProperty("songs", "60").toInt()
    val ask = wants.getProperty("ask", "200").toInt()
    val random = Random(7)
    val albums = library.groupBy { it.albumId }.filterKeys { it != null }.entries.shuffled(random)
    val book = withTimeoutOrNull(60_000) { snapshotFlow { app.animated.book }.filterNotNull().first() }
    val picked = mutableListOf<Song>()
    val plain = mutableListOf<Song>()
    for ((at, entry) in albums.withIndex()) {
        if (picked.size >= count) break
        val song = entry.value.random(random)
        val art = if (book != null && at < ask) runCatching { book.art(entry.key!!) }.getOrNull() else null
        if (art?.clip(LoopShape.Tall) != null) picked += song else plain += song
    }
    val songs = picked + plain.take(count - picked.size)
    runCatching { File(wants.getProperty("events", "")).takeIf { it.path.isNotBlank() }?.appendText("${System.currentTimeMillis()} picked ${picked.size} with a tall loop of ${songs.size}\n") }
    queue?.let { file -> withContext(Dispatchers.IO) { file.writeText(songs.joinToString("\n") { it.id }) } }
    return songs
}

// The stations a radio run plays: the server's, else the ones kept here,
// without their catalog ids, so the catalog never hears of these starts.
private suspend fun soakStations(app: AppState, liveOnly: Boolean = false): List<String> {
    if (liveOnly) return app.liveRadio.kept().stations.map { it.copy(uuid = "").radioId() }
    app.liveRadio.readServerStations()
    val server = app.liveRadio.serverStations.orEmpty().filter { it.streamUrl.isNotBlank() }
    if (server.isNotEmpty()) return server.map { it.radioId() }
    return app.liveRadio.kept().stations.map { it.copy(uuid = "").radioId() }
}
