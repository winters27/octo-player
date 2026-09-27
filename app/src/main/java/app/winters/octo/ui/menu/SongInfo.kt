package app.winters.octo.ui.menu

import android.content.ClipData
import android.content.Context
import android.media.MediaMetadataRetriever
import android.os.Build
import android.provider.MediaStore
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.winters.octo.catalog.CatalogDao
import app.winters.octo.catalog.OnlineDao
import app.winters.octo.catalog.SourceDao
import app.winters.octo.catalog.TrackEntity
import app.winters.octo.catalog.isFind
import app.winters.octo.catalog.songDetails
import app.winters.octo.data.SessionRepository
import app.winters.octo.data.SessionState
import app.winters.octo.design.OctoColors
import app.winters.octo.design.OctoType
import app.winters.octo.device.DEVICE
import app.winters.octo.discovery.asTrack
import app.winters.octo.folders.serverName
import app.winters.octo.listening.PlayHistory
import app.winters.octo.offline.DownloadStatus
import app.winters.octo.offline.OfflineDownloads
import app.winters.octo.playback.audioQuality
import app.winters.octo.ui.common.GlassMenuBack
import app.winters.octo.ui.common.GlassMenuNote
import app.winters.octo.ui.common.GlassMenuPage
import app.winters.octo.ui.common.asClock
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject

// Where the copy of a song that plays comes from.
sealed interface SongSource {
    // A file on the phone, by its path in the phone's storage when known.
    data class Phone(val path: String?) : SongSource

    // A server, by the name people know it by, and the file it was
    // downloaded to on the phone, if it was.
    data class Server(val name: String?, val downloadedTo: String?) : SongSource

    // A song found online, not in the library yet.
    data object Found : SongSource
}

// Everything the info sheet says about a song. Times are as the library
// keeps them: when it was added in seconds, when last played in
// milliseconds. Gains are in decibels; peaks where 1 is full scale.
data class SongFacts(
    val title: String,
    val artist: String,
    val album: String,
    val albumArtist: String? = null,
    val year: Int? = null,
    val genre: String = "",
    val trackNo: Int? = null,
    val discNo: Int? = null,
    val durationMs: Long = 0,
    val mimeType: String? = null,
    // Bits per second, samples per second, bits per sample.
    val bitrate: Int? = null,
    val sampleRate: Int? = null,
    val bitDepth: Int? = null,
    val sizeBytes: Long? = null,
    val source: SongSource = SongSource.Found,
    val addedAtSeconds: Long = 0,
    val plays: Int = 0,
    val lastPlayedAt: Long = 0,
    val rating: Int = 0,
    val trackGain: Float? = null,
    val albumGain: Float? = null,
    val trackPeak: Float? = null,
    val albumPeak: Float? = null,
    // What the tags or server add, from every copy of the song. The year
    // above is this edition's; the original is the first release's.
    val originalYear: Int? = null,
    val genres: List<String> = emptyList(),
    val composer: String? = null,
    val bpm: Int? = null,
    val comment: String? = null,
    val explicit: Boolean? = null,
    val discTitle: String? = null,
    val mbRecordingId: String? = null,
    val mbAlbumId: String? = null,
    val mbReleaseGroupId: String? = null,
    val mbArtistIds: List<String> = emptyList(),
)

// One line of the sheet. A copyable one gets a Copy button.
data class InfoLine(val label: String, val value: String, val copyable: Boolean = false)

// The sheet's lines, in reading order, leaving out whatever is not known.
fun infoLines(facts: SongFacts, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): List<InfoLine> = buildList {
    add(InfoLine("Title", facts.title))
    if (facts.artist.isNotBlank()) add(InfoLine("Artist", facts.artist))
    if (facts.album.isNotBlank()) add(InfoLine("Album", facts.album))
    facts.albumArtist?.takeIf { it.isNotBlank() }?.let { add(InfoLine("Album artist", it)) }
    facts.composer?.takeIf { it.isNotBlank() }?.let { add(InfoLine("Composer", it)) }
    val year = facts.year?.takeIf { it > 0 }
    year?.let { add(InfoLine("Year", it.toString())) }
    facts.originalYear?.takeIf { it > 0 && it != year }?.let { add(InfoLine("Original year", it.toString())) }
    val genres = (listOf(facts.genre) + facts.genres).filter(String::isNotBlank).distinctBy(String::lowercase)
    if (genres.isNotEmpty()) add(InfoLine(if (genres.size == 1) "Genre" else "Genres", genres.joinToString(", ")))
    facts.trackNo?.takeIf { it > 0 }?.let { add(InfoLine("Track", it.toString())) }
    facts.discNo?.takeIf { it > 0 }?.let { disc ->
        add(InfoLine("Disc", facts.discTitle?.takeIf(String::isNotBlank)?.let { "$disc · $it" } ?: disc.toString()))
    }
    facts.bpm?.takeIf { it > 0 }?.let { add(InfoLine("BPM", it.toString())) }
    facts.explicit?.let { add(InfoLine("Lyrics", if (it) "Explicit" else "Clean")) }
    facts.comment?.takeIf { it.isNotBlank() }?.let { add(InfoLine("Comment", it)) }

    if (facts.durationMs > 0) add(InfoLine("Length", (facts.durationMs / 1000).toInt().asClock()))
    formatName(facts.mimeType)?.let { add(InfoLine("Format", it)) }
    facts.mimeType?.takeIf { it.isNotBlank() }?.let { add(InfoLine("File type", it)) }
    facts.bitrate?.takeIf { it > 0 }?.let { add(InfoLine("Bitrate", bitrateText(it))) }
    facts.sampleRate?.takeIf { it > 0 }?.let { add(InfoLine("Sample rate", sampleRateText(it, locale))) }
    facts.bitDepth?.takeIf { it > 0 }?.let { add(InfoLine("Bit depth", "$it-bit")) }
    facts.sizeBytes?.takeIf { it > 0 }?.let { add(InfoLine("File size", sizeText(it, locale))) }

    when (val source = facts.source) {
        is SongSource.Phone -> {
            add(InfoLine("Source", "This phone"))
            source.path?.let { add(InfoLine("Path", it, copyable = true)) }
        }
        is SongSource.Server -> {
            add(InfoLine("Source", source.name ?: "Your server"))
            source.downloadedTo?.let { add(InfoLine("Downloaded to", it, copyable = true)) }
        }
        SongSource.Found -> add(InfoLine("Source", "Found online"))
    }
    if (facts.addedAtSeconds > 0) add(InfoLine("Added", dateText(facts.addedAtSeconds * 1000, zone, locale)))
    add(InfoLine("Plays", "%,d".format(locale, facts.plays)))
    if (facts.lastPlayedAt > 0) add(InfoLine("Last played", dateText(facts.lastPlayedAt, zone, locale)))
    if (facts.rating > 0) add(InfoLine("Rating", if (facts.rating == 1) "1 star" else "${facts.rating} stars"))

    facts.trackGain?.let { add(InfoLine("Track gain", gainText(it, locale))) }
    facts.trackPeak?.let { add(InfoLine("Track peak", peakText(it, locale))) }
    facts.albumGain?.let { add(InfoLine("Album gain", gainText(it, locale))) }
    facts.albumPeak?.let { add(InfoLine("Album peak", peakText(it, locale))) }

    // MusicBrainz ids, for looking the song up elsewhere.
    facts.mbRecordingId?.let { add(InfoLine("Recording MBID", it, copyable = true)) }
    facts.mbAlbumId?.let { add(InfoLine("Release MBID", it, copyable = true)) }
    facts.mbReleaseGroupId?.let { add(InfoLine("Release group MBID", it, copyable = true)) }
    if (facts.mbArtistIds.isNotEmpty()) {
        add(InfoLine(if (facts.mbArtistIds.size == 1) "Artist MBID" else "Artist MBIDs", facts.mbArtistIds.joinToString("\n"), copyable = true))
    }
}

// The codec, from the file's type, and whether it is lossless.
fun formatName(mimeType: String?): String? {
    val quality = audioQuality(codecMime = null, fileMime = mimeType, pcmEncoding = -1, sampleRate = -1, bitrate = -1) ?: return null
    return if (quality.lossless) "${quality.codec}, lossless" else quality.codec
}

fun bitrateText(bitsPerSecond: Int): String = "${(bitsPerSecond + 500) / 1000} kbps"

// 44100 reads as "44.1 kHz", 48000 as "48 kHz".
fun sampleRateText(hz: Int, locale: Locale = Locale.getDefault()): String =
    if (hz % 1000 == 0) "${hz / 1000} kHz" else "%.1f kHz".format(locale, hz / 1000.0)

// Kilobytes under a megabyte, megabytes under a gigabyte, gigabytes above.
fun sizeText(bytes: Long, locale: Locale = Locale.getDefault()): String = when {
    bytes < 1_000_000 -> "%.0f KB".format(locale, bytes / 1_000.0)
    bytes < 1_000_000_000 -> "%.1f MB".format(locale, bytes / 1_000_000.0)
    else -> "%.2f GB".format(locale, bytes / 1_000_000_000.0)
}

// With its sign, like "+1.50 dB" or "-6.20 dB".
fun gainText(db: Float, locale: Locale = Locale.getDefault()): String = "%+.2f dB".format(locale, db)

fun peakText(peak: Float, locale: Locale = Locale.getDefault()): String = "%.3f".format(locale, peak)

fun dateText(epochMs: Long, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): String =
    DateTimeFormatter.ofPattern("d MMM yyyy", locale).format(Instant.ofEpochMilli(epochMs).atZone(zone))

// A song, and what the sheet says about it.
class SongInfo(val track: TrackEntity, val facts: SongFacts)

@HiltViewModel
class SongInfoViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val catalog: CatalogDao,
    private val online: OnlineDao,
    private val sources: SourceDao,
    private val history: PlayHistory,
    private val sessions: SessionRepository,
    private val offline: OfflineDownloads,
) : ViewModel() {
    // Read once as the sheet opens.
    fun info(trackId: String): Flow<SongInfo?> = flow { emit(load(trackId)) }.flowOn(Dispatchers.IO)

    private suspend fun load(trackId: String): SongInfo? {
        if (isFind(trackId)) {
            val track = online.songFlow(trackId).first()?.asTrack() ?: return null
            return SongInfo(track, track.facts(SongSource.Found))
        }
        val track = catalog.track(trackId) ?: return null
        val album = catalog.album(track.albumId).first()
        val copies = sources.copies(trackId)
        val phone = copies.firstOrNull { it.sourceId == DEVICE }
        val server = copies.firstOrNull { it.sourceId != DEVICE }
        // The copy that plays: the phone's when there is one.
        val playing = if (track.onPhone) phone ?: server else server ?: phone
        val onPhone = playing != null && playing === phone
        val fromFile = if (onPhone && playing?.bitrate == null) playing?.uri?.let(::fileQuality) else null
        val gains = playing?.takeIf { it.trackGain != null || it.albumGain != null } ?: copies.firstOrNull { it.trackGain != null }
        val played = history.tracks.first().firstOrNull { it.track.id == trackId }
        val details = songDetails(copies)
        val source = if (onPhone) {
            SongSource.Phone(playing?.uri?.let(::phonePath))
        } else {
            val download = offline.byTrack.value[trackId]?.takeIf { it.state == DownloadStatus.Done && it.path.isNotEmpty() }
            SongSource.Server(signedInServer(), download?.path)
        }
        val facts = track.facts(source).copy(
            albumArtist = album?.artist,
            mimeType = playing?.mimeType ?: track.mimeType,
            sizeBytes = playing?.sizeBytes ?: track.sizeBytes,
            bitrate = playing?.bitrate ?: fromFile?.bitrate,
            sampleRate = playing?.sampleRate ?: fromFile?.sampleRate,
            bitDepth = playing?.bitDepth ?: fromFile?.bitDepth,
            plays = played?.plays ?: 0,
            lastPlayedAt = played?.lastPlayedAt ?: 0,
            trackGain = gains?.trackGain,
            albumGain = gains?.albumGain,
            trackPeak = gains?.trackPeak,
            albumPeak = gains?.albumPeak,
            // The library's year is the original where known; the sheet
            // shows this edition's year beside it.
            year = details.year ?: track.year,
            originalYear = details.originalYear,
            genres = details.genres,
            composer = details.composer,
            bpm = details.bpm,
            comment = details.comment,
            explicit = details.explicit,
            discTitle = details.discTitle,
            mbRecordingId = details.mbRecordingId,
            mbAlbumId = details.mbAlbumId,
            mbReleaseGroupId = details.mbReleaseGroupId,
            mbArtistIds = details.mbArtistIds,
        )
        return SongInfo(track, facts)
    }

    private fun TrackEntity.facts(source: SongSource) = SongFacts(
        title = title,
        artist = artist,
        album = album,
        year = year,
        genre = genre,
        trackNo = trackNo,
        discNo = discNo,
        durationMs = durationMs,
        mimeType = mimeType,
        sizeBytes = sizeBytes,
        source = source,
        addedAtSeconds = addedAt,
        rating = rating,
    )

    private fun signedInServer(): String? = (sessions.state.value as? SessionState.SignedIn)?.session?.let {
        serverName(it.isOcto, it.serverType, it.client.primaryUrl.host)
    }

    // Where a phone file is, as its folder in the phone's storage and its name.
    private fun phonePath(uri: String): String? = runCatching {
        val columns = arrayOf(MediaStore.Audio.Media.RELATIVE_PATH, MediaStore.Audio.Media.DISPLAY_NAME)
        context.contentResolver.query(uri.toUri(), columns, null, null, null)?.use { c ->
            if (!c.moveToFirst()) return@use null
            (c.getString(0).orEmpty() + c.getString(1).orEmpty()).ifEmpty { null }
        }
    }.getOrNull()

    private class FileQuality(val bitrate: Int?, val sampleRate: Int?, val bitDepth: Int?)

    // What the file itself says about its quality, for a phone file the
    // library has no numbers for. Older phones only tell the bitrate.
    private fun fileQuality(uri: String): FileQuality? = runCatching {
        MediaMetadataRetriever().use { reader ->
            reader.setDataSource(context, uri.toUri())
            val newer = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
            FileQuality(
                bitrate = reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toIntOrNull(),
                sampleRate = if (newer) reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_SAMPLERATE)?.toIntOrNull() else null,
                bitDepth = if (newer) reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITS_PER_SAMPLE)?.toIntOrNull() else null,
            )
        }
    }.getOrNull()
}

// Everything known about a song, as plain labelled lines, a page of the
// song's menu. It scrolls when long.
@Composable
internal fun SongInfoPage(trackId: String, onBack: () -> Unit, vm: SongInfoViewModel = hiltViewModel()) {
    val info by remember(trackId) { vm.info(trackId) }.collectAsStateWithLifecycle(null)
    GlassMenuPage(width = InfoWidth, header = { GlassMenuBack("Song info", onBack) }) {
        val shown = info
        if (shown == null) {
            GlassMenuNote("Reading the song")
            return@GlassMenuPage
        }
        SongHeader(shown.track)
        for (line in infoLines(shown.facts)) InfoRow(line)
        Spacer(Modifier.height(8.dp))
    }
}

// A little wider than the menu, for the labels beside the values.
private val InfoWidth = 320.dp

@Composable
private fun InfoRow(line: InfoLine) {
    Row(
        Modifier.fillMaxWidth().padding(start = 14.dp, end = 6.dp, top = 5.dp, bottom = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(line.label, style = OctoType.caption, color = OctoColors.TextMuted, modifier = Modifier.width(92.dp).padding(top = 2.dp))
        Text(line.value, style = OctoType.bodySmall, color = OctoColors.TextPrimary, modifier = Modifier.weight(1f))
        if (line.copyable) CopyButton(line.label, line.value)
    }
}

// Copies the text, and says so on the button for a moment.
@Composable
private fun CopyButton(label: String, text: String) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(2_000)
            copied = false
        }
    }
    Text(
        if (copied) "Copied" else "Copy",
        style = OctoType.caption.copy(fontWeight = FontWeight.SemiBold),
        color = OctoColors.TextPrimary,
        modifier = Modifier
            .clickable(role = Role.Button) {
                scope.launch {
                    clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(label, text)))
                    copied = true
                }
            }
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}
