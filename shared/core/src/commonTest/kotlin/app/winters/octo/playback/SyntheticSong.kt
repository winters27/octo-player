package app.winters.octo.playback

import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

// A made-up song built from layers, rendered the same way by the Rust
// engine's tests from automix-vectors.json. Each layer sounds from fromMs
// to toMs with its level moving in a straight line (in dB) from db to endDb.
//
// tone:  amplitude * sin(2 pi hz n / rate), n the sample index in the song.
// pulse: a hit at firstMs + j * every * 60000 / bpm for every whole j with
//        the hit inside [fromMs, toMs); each hit adds
//        amplitude(hit time) * exp(-d / decayMs) * sin(2 pi hz d / 1000)
//        for d = sample time minus hit time in ms, 0 <= d < 8 * decayMs.
// The sample time of sample n is n * 1000 / rate ms. Every channel carries
// the same signal.
data class SongLayer(
    val kind: String,
    val fromMs: Double,
    val toMs: Double,
    val hz: Double,
    val db: Double,
    val endDb: Double = db,
    val bpm: Double = 0.0,
    val firstMs: Double = 0.0,
    val every: Int = 1,
    val decayMs: Double = 0.0,
) {
    fun amplitudeAt(ms: Double): Double {
        val u = if (toMs > fromMs) ((ms - fromMs) / (toMs - fromMs)).coerceIn(0.0, 1.0) else 0.0
        return 10.0.pow((db + (endDb - db) * u) / 20)
    }
}

data class SyntheticSong(val rate: Int, val channels: Int, val lengthMs: Long, val layers: List<SongLayer>) {
    // Mono samples for song times [fromMs, toMs).
    fun renderMono(fromMs: Double, toMs: Double): FloatArray {
        val first = floor(fromMs * rate / 1000).toLong()
        val end = floor(min(toMs, lengthMs.toDouble()) * rate / 1000).toLong()
        val out = DoubleArray(max(0L, end - first).toInt())
        for (layer in layers) {
            when (layer.kind) {
                "tone" -> {
                    val from = max(first, ceil(layer.fromMs * rate / 1000).toLong())
                    val to = min(end, ceil(layer.toMs * rate / 1000).toLong())
                    for (n in from until to) {
                        val ms = n * 1000.0 / rate
                        out[(n - first).toInt()] += layer.amplitudeAt(ms) * sin(2 * PI * layer.hz * n / rate)
                    }
                }
                "pulse" -> {
                    val gap = layer.every * 60_000.0 / layer.bpm
                    var j = ceil((layer.fromMs - layer.firstMs) / gap).toLong()
                    while (true) {
                        val hit = layer.firstMs + j * gap
                        if (hit >= layer.toMs) break
                        j++
                        if (hit < layer.fromMs) continue
                        val tail = 8 * layer.decayMs
                        if (hit + tail < fromMs || hit >= toMs) continue
                        val amplitude = layer.amplitudeAt(hit)
                        val from = max(first, ceil(hit * rate / 1000).toLong())
                        val to = min(end, ceil((hit + tail) * rate / 1000).toLong())
                        for (n in from until to) {
                            val d = n * 1000.0 / rate - hit
                            if (d < 0 || d >= tail) continue
                            out[(n - first).toInt()] += amplitude * exp(-d / layer.decayMs) * sin(2 * PI * layer.hz * d / 1000)
                        }
                    }
                }
                else -> error("unknown layer ${layer.kind}")
            }
        }
        return FloatArray(out.size) { out[it].toFloat() }
    }

    // The envelope of [fromMs, toMs), fed to the builder in blocks of
    // `block` interleaved samples so the streaming path is exercised.
    fun envelope(fromMs: Double, toMs: Double, block: Int = 4_093): SectionEnvelope {
        val mono = renderMono(fromMs, toMs)
        val interleaved = if (channels == 1) mono else FloatArray(mono.size * channels) { mono[it / channels] }
        val startMs = floor(fromMs * rate / 1000).toLong() * 1000 / rate
        val builder = EnvelopeBuilder(rate, channels, startMs)
        var at = 0
        while (at < interleaved.size) {
            val length = min(block, interleaved.size - at)
            builder.push(interleaved, at, length)
            at += length
        }
        return builder.build()
    }

    // The last 60 s and the first 30 s, as the player scouts them.
    fun tail(): SectionAnalysis = analyzeTail(envelope(max(0.0, lengthMs - 60_000.0), lengthMs.toDouble()))

    fun head(): SectionAnalysis = analyzeHead(envelope(0.0, min(30_000.0, lengthMs.toDouble())))
}
