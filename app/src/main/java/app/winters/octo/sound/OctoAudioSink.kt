package app.winters.octo.sound

import androidx.media3.common.MediaItem
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import app.winters.octo.playback.EXTRA_ALBUM_ID
import app.winters.octo.playback.storedLoudness

// The player's audio output with Octo's shaping in it. Each time a song is
// set up, it reads the song's loudness tags and album and hands them to the
// shaping, which takes them up once the song before has played out. A song
// whose sound carries no loudness tags, such as a stream the server made
// smaller, uses the loudness it was queued with instead.
@UnstableApi
class OctoAudioSink(
    sink: AudioSink,
    private val shaping: OctoDspProcessor,
    private val albums: AlbumRun,
) : ForwardingAudioSink(sink) {
    override fun configure(config: AudioSink.AudioSinkConfig) {
        val item = songOf(config)
        val info = chooseReplayGain(ReplayGain.read(config.format.metadata), item?.mediaMetadata?.storedLoudness())
        val album = item?.let(::albumOf)
        val id = item?.mediaId?.ifEmpty { null }
        shaping.setNextSong(SongLoudness(info, albums.follows(id, album)))
        super.configure(config)
    }

    // The queue entry this sound belongs to, when the player says.
    private fun songOf(config: AudioSink.AudioSinkConfig): MediaItem? {
        val timeline = config.timeline
        val periodUid = config.mediaPeriodId?.periodUid ?: return null
        if (timeline.isEmpty) return null
        val index = timeline.getIndexOfPeriod(periodUid)
        if (index < 0) return null
        val period = timeline.getPeriod(index, Timeline.Period())
        return timeline.getWindow(period.windowIndex, Timeline.Window()).mediaItem
    }

    // The album a song belongs to: the catalog's id, or its album and
    // artist names when it has none.
    private fun albumOf(item: MediaItem): String? {
        val meta = item.mediaMetadata
        meta.extras?.getString(EXTRA_ALBUM_ID)?.let { return it }
        val title = meta.albumTitle?.toString()?.trim()?.ifEmpty { null } ?: return null
        return "$title/${meta.albumArtist ?: meta.artist ?: ""}"
    }
}
