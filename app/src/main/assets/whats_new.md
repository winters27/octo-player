**Automix**, **themes**, **big libraries that fit on your phone**, **playlist folders**, **songs found online in your playlists**, **Try another version**, **your own Download format**, **hearts get the version you heard**, and **Android Auto gets the whole app**.

## Sound

- Sound works as before. Nothing changed here in this update.

## Library

- **Big libraries fit.** Copying your server's library takes the same small amount of memory however big it is, and Home, lists, playlists, the phone scan and Android Auto read it a page at a time, so a library of hundreds of thousands of songs no longer closes the app.
- **A copy shows how far it is**, you can play while it saves, and a long one keeps going with a notification when you leave the app.
- Covers kept in memory take less room, so a big library leaves more for the rest of the app.
- When the phone is short of room for a copy or for this update, Octo says how much to free instead of failing.
- Your likes, favorite albums and artists, and Home pins stay with their songs when the server files them anew.
- **A heart gets the version you heard.** When you heart a song, your Octo server downloads the best copy of the take you were listening to, checked by its sound, not just its name. A remaster of it counts, but a live take, a cover or an edit doesn't.
- **Downloads that need you say so.** When the server can't prove a version yet, the song waits and says why, like "Play it once so Octo can keep the version you hear". Keep anyway keeps the copy as it is.
- **Albums too.** A hearted album keeps the tracks it can prove and holds the rest, each one proven by a play or kept with Keep these anyway.
- A download that waits for you carries the small accent dot instead of a busy ring, and Server downloads under Library says how many are on their way and how many wait for you.
- A song kept while Lidarr is still downloading its artist plays right away and joins your library once Lidarr's download is done.
- **Album search works where it used to come back empty.** In countries where the music catalog hides albums from searches, like movie soundtracks, Octo finds them through Apple's catalog instead, and a whole album downloads.
- On a partly held album, saving it to your phone counts only the songs it saves.
- **A song you delete can come back.** Search drops its check mark once your library no longer has it, and hearting it again downloads it again.
- **Playlist folders.** Make folders for your playlists, put folders inside folders up to 4 deep, and file playlists with Move to folder or a folder's Add playlists. Your Octo server keeps them, so every Octo app you sign in to shows the same folders, and folders made with no server stay on your phone.
- **Songs found online go in your playlists.** Add a song Octo found online to any of your own playlists and it keeps its place. Once it's downloaded into your library, your copy takes the same spot.
- **Library upkeep.** Review, Duplicates and Octo's library action playlists sit together in their own group at the end of Playlists, closed until you open it, with a line saying when Review or Duplicates has songs.
- Imported playlists show in Playlists with your own, and can go in folders.
- A playlist changed on two devices keeps both sets of changes. If one side changed before this update and both changed since, your phone's version is saved beside it as "(from this phone)", so nothing is lost.
- **Your server's songs are never offered as found online.** A song added to your library since the phone last copied it, or still on its way in a first copy, shows and plays as yours in radio, stations, lists and its album, with its heart and no add button, and the phone brings it into your library on its own.

## Playback

- **Try another version.** When a song found online plays the wrong take, like a live one, a remix or a sped-up edit, pick another from Try another version in the player's menu. It plays at once from the start, your server keeps it for everyone on every device, and a heart downloads the version you picked. Go back to Octo's pick undoes it. Needs Octo server 2026.11.15.
- **Automix is now its own choice next to Crossfade and is on by default:** songs blend where they meet best, and a quiet intro plays under the end of the song before it. If you never changed Crossfade, or turned it off without changing its other settings, Automix is now on. Gapless is under Between songs. A Crossfade setup you chose stays as it was. Automix now picks its own blend lengths. Match tempo is now on for Automix; turn it off under Between songs if you prefer.
- **Android Auto gets the whole app:** Home, Radio, Playlists and Library tabs, as on your phone, with Made for you, your server's stations, charts and stations near you.
- Android Auto's Playlists tab shows your playlist folders, each opening to its folders and playlists.
- Search in the car shows your library first, then what Octo found outside it, and songs from outside your library play from the car.
- Lists and albums open to their songs, every album of a big library sits under its letter, and playlist covers look as they do on your phone.
- Start radio and Play next on songs, in cars with Android built in.
- **Android 13 no longer closes the app** when you open the player.
- A stream that breaks off, or whose server lets go of the file part way, picks up where it was instead of skipping the song.

## Server

- **Servers behind a sign-in page work.** If your server sits behind Authentik, Authelia or Cloudflare Access, Octo signs in through it, and when the sign-in page turns Octo away, Octo names it instead of saying the server can't be reached. A sign-in page is never played or kept as a song.
- **A clearer sign-in page.** Your server and how you sign in come first, and a sign-in page, a home address, the method, headers and a certificate wait under Advanced. When a sign-in page turns Octo away, Advanced opens on its fields with the reason under them.
- **A family link starts clean.** A link to another server brings nothing of the last one with it, such as its headers or its certificate, and a link that arrives while you are signing in leaves that sign-in as it is.
- **Certificates from eMudhra's compromised keys are refused**, so no one can pose as your server with one. Octo says so when a server uses one.
- **Servers with their own certificate authority work.** If you installed your authority's certificate on the phone (step-ca, say), Octo trusts your server's certificates like any other, so short ones renewed every day keep playing.
- **A changed certificate is asked about again.** When a server whose certificate you trusted shows a new one, Octo asks with the new fingerprint instead of failing every song until you sign in again, and the song plays once you trust it. A renewal that keeps the same key needs no question.
- **Music goes to the shared library:** when your family's owner sends your songs there, your plan on the Family page says so instead of showing your own library's size. Needs Octo server 2026.11.15.
- Hearts that get the version you heard need Octo server 2026.11.13 or newer.
- Playlist folders kept by your server, songs found online in playlists and Try another version need Octo server 2026.11.15.
- Imports waiting for Mornye's check say so, and say when they will go on. Health names Mornye and octo-sonic, and Mornye's row offers Pass on the dashboard while its check is paused.
- A higher quality copy that waits says the server's own words for why.
- On the Octo admin screen, recent downloads show their covers, served by your Octo server, and the server's music catalog check shows by name.

## Lyrics

- Lyrics work as before. Nothing changed here in this update.

## Offline

- **Choose your Download format.** At a smaller Download quality, songs are saved as Opus (best sound for its size, the new default for everyone), AAC or MP3. Settings > Streaming and downloads > Download format.
- A song that's already small enough downloads as it is, so it's never made worse.
- Downloads are named and labeled by what really arrived, and AAC is saved as an M4A that knows its own length.
- Songs you downloaded before stay downloaded and keep playing. Nothing is downloaded again.

## Everyday touches

- A refused download's reason wraps with the row's words instead of being cut off.
- An album's download log no longer offers Find songs, since an album is no one song.
- Fetching songs ahead stops cleanly, and the memory of songs already taken in no longer grows with every song shown.

## Settings

- **Themes.** Pick Octo, Moss, Dusk, Daylight or Paper, or let Octo match the system's light and dark. Every screen draws in the theme's colors, and backups keep your theme.
- Change password names each of its fields, and its button waits until all three are filled in.
- Backups of a big library save and restore a page at a time.
