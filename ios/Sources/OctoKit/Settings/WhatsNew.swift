import Foundation

/// What this update brings: the one line the card on Home shows, and the notes the panel shows,
/// as markdown. Both are rewritten with each update.
public enum WhatsNew {
    /// The one line the card on Home shows. Kept short.
    public static let summary = "Octo comes to iPhone, with Liquid Glass, widgets, Siri and Shortcuts"

    /// Whether the card about what is new should show: once after each new version, not again
    /// for a later build of the same version (TestFlight raises the build on every upload), and
    /// not on a fresh install, which has nothing to compare with. A version that does not read as
    /// one counts as new when it differs.
    public static func due(lastSeen: String?, current: String, freshInstall: Bool) -> Bool {
        guard let lastSeen else { return !freshInstall }
        guard let seen = PlayerVersion.parse(lastSeen), let running = PlayerVersion.parse(current) else {
            return lastSeen != current
        }
        return seen < running
    }

    /// The version counted as seen. Older builds kept the build number they showed the news
    /// for instead of a version; one found means this version's news was already read.
    public static func seenVersion(stored: String?, storedBuild: Int?, current: String) -> String? {
        if let stored { return stored }
        return storedBuild == nil ? nil : current
    }

    public static let notes = """
    Octo is here on iPhone: your music server and the music on your phone, in one player made with Apple's **Liquid Glass**.

    ## Playing

    - **Automix** blends each song into the next where they meet best, or pick Gapless or Crossfade under Between songs.
    - The full player has lyrics, the queue, and AirPlay in its More menu.
    - **Lock screen** controls, and music that keeps playing in the background.
    - Play next and Add to queue on every song, and a swipe to the right plays it next.
    - A **sleep timer**, and speed and pitch.
    - **Try another version** when a song found online plays the wrong take: your Octo server keeps the one you pick for everyone, and a heart downloads it.

    ## Your server

    - Works with **Navidrome** and any Subsonic-compatible server, and with everything an **Octo** server adds.
    - **Servers behind a sign-in page work:** Octo signs in through Authentik, Authelia or Cloudflare Access.
    - **Search** finds your own music first, then beyond your library when your Octo server offers it.
    - **A heart gets the version you heard:** an Octo server from this update downloads the same recording, checked by its sound, and a download that needs you says why.
    - Stations, **Made for you**, mixes, and **charts** from any country.
    - Playlists stay the same on your phone and your server, both ways.
    - **Playlist folders** up to 4 deep, kept by your Octo server so every Octo app shows the same ones, and songs found online keep their place in your playlists.
    - **Family**: join by link or QR code, and add to family playlists.
    - Pick up the queue where another device left off.

    ## Your phone

    - Songs from the **Music app** and the Files app play beside your server's.
    - **Download** songs, albums and playlists to play with no connection.
    - A **Download format** of your own: Opus, AAC or MP3 for songs saved at a smaller quality.
    - Songs you stream are kept for a while, so they play again offline.
    - **Copying your server's library** shows how far it is, and a long copy keeps going after you leave the app.

    ## Lyrics

    - **Lyrics** that follow the song and light up as they are sung.
    - Tap a line to play from there, or move the lyrics earlier or later.

    ## Sound

    - An **equalizer** with presets, **loudness**, and headphone correction.
    - **Crossfeed** for headphones, and a choice of crossfade curves.

    ## Look

    - **Themes**: Octo, Moss, Dusk, Daylight or Paper, or match the phone's light and dark.
    - **Animated album art**, square or full screen.

    ## iPhone

    - **Home Screen widgets** for what is playing and quick play.
    - Ask **Siri** or **Shortcuts** to play an album, Liked songs or an album mix, start a radio, or resume.
    - **Share to Octo** from other apps to bring in a playlist.
    - **Live radio** from stations near you.
    """
}
