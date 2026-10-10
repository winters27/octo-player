package app.winters.octo.playback

// How big a stream is: the server's file as it is, or an MP3 made on the
// way at most this many kilobits a second.
enum class StreamQuality(val kbps: Int?) {
    Original(null),
    Kbps320(320),
    Kbps256(256),
    Kbps192(192),
    Kbps128(128),
}
