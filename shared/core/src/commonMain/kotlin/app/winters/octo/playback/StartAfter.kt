package app.winters.octo.playback

// How much of a song is ready before it starts playing, the same choices on
// the phone and the desktop. A second is what players wait by default.
enum class StartAfter(val ms: Int, val label: String) {
    Instant(250, "As soon as possible"),
    Short(1_000, "1 second"),
    Steady(2_500, "2.5 seconds"),
    Safe(5_000, "5 seconds"),
    Long(10_000, "10 seconds"),
}

const val START_AFTER_SETTING = "Start playing after"
const val START_AFTER_HELP = "Waits for this much audio before a song starts. Shorter starts faster on a slow connection; longer stutters less."
