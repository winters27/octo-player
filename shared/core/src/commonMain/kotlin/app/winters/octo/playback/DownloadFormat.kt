package app.winters.octo.playback

// The type a download is made as when it is made smaller than the server's
// file. `param` is the Subsonic `format` the server is asked for, `mimeType`
// what a download of it is labeled before its bytes say otherwise, and
// `label` what people read. The order is the order the choices are shown in.
enum class DownloadFormat(val param: String, val mimeType: String, val label: String) {
    Opus("opus", "audio/opus", "Opus"),
    Aac("aac", "audio/aac", "AAC"),
    Mp3("mp3", "audio/mpeg", "MP3"),
    ;

    companion object {
        // The choice stored under `name`; anything else, a name from a newer
        // app included, is Opus, the default.
        fun named(name: String?): DownloadFormat = entries.firstOrNull { it.name == name } ?: Opus
    }
}

// What one Download format choice says under its name.
val DownloadFormat.detail: String
    get() = when (this) {
        DownloadFormat.Opus -> "Best sound for its size"
        DownloadFormat.Aac -> "Close to Opus, opens almost anywhere"
        DownloadFormat.Mp3 -> "Opens anywhere, a little worse for its size"
    }

// The line under the Download format setting, and its search line.
const val DOWNLOAD_FORMAT_TITLE = "Download format"
const val DOWNLOAD_FORMAT_HELP = "What smaller downloads are saved as"

// A download quality as people read it, in the format smaller downloads
// are made as: "128 kbps Opus", or "Original" for the file as it is.
fun downloadQualityLabel(kbps: Int?, format: DownloadFormat): String = kbps?.let { "$it kbps ${format.label}" } ?: "Original"
