**Themes**, **big libraries that fit on your phone**, **hearts get the version you heard**, and **Android Auto gets the whole app**.

## Sound

- Sound works as before. Nothing changed here in this update.

## Library

- **Big libraries fit.** Copying your server's library takes the same small amount of memory however big it is, and Home, lists, playlists, the phone scan and Android Auto read it a page at a time, so a library of hundreds of thousands of songs no longer closes the app.
- **A copy shows how far it is**, you can play while it saves, and a long one keeps going with a notification when you leave the app.
- Covers kept in memory take less room, so a big library leaves more for the rest of the app.
- When the phone is short of room for a copy or for this update, Octo says how much to free instead of failing.
- Your likes, favorite albums and artists, and Home pins stay with their songs when the server files them anew.
- **A heart gets the version you heard.** When you heart a song, your Octo server downloads the best copy of the take you were listening to, checked by its sound, not just its name. A lossless remaster of it counts, but a live take, a cover or an edit doesn't.
- **Downloads that need you say so.** When the server can't prove a version yet, the song waits and says why, like "Play it once so Octo can keep the version you hear". Keep anyway keeps the copy as it is.
- **Albums too.** A hearted album keeps the tracks it can prove and holds the rest, each one proven by a play or kept with Keep these anyway.
- A download that waits for you carries the small accent dot instead of a busy ring, and Server downloads under Library says how many are on their way and how many wait for you.
- A song kept while Lidarr is still downloading its artist plays right away and joins your library once Lidarr's download is done.
- **Album search works where it used to come back empty.** In countries where the music catalog hides albums from searches, like movie soundtracks, Octo finds them through Apple's catalog instead, and a whole album downloads.
- On a partly held album, saving it to your phone counts only the songs it saves.
- **A song you delete can come back.** Search drops its check mark once your library no longer has it, and hearting it again downloads it again.

## Playback

- **Android Auto gets the whole app:** Home, Radio, Playlists and Library tabs, as on your phone, with Made for you, your server's stations, charts and stations near you.
- Search in the car shows your library first, then what Octo found outside it, and songs from outside your library play from the car.
- Lists and albums open to their songs, every album of a big library sits under its letter, and playlist covers look as they do on your phone.
- Start radio and Play next on songs, in cars with Android built in.
- **Android 13 no longer closes the app** when you open the player.

## Server

- **Servers behind a sign-in page work.** If your server sits behind Authentik, Authelia or Cloudflare Access, Octo signs in through it, and when the sign-in page turns Octo away, Octo names it instead of saying the server can't be reached. A sign-in page is never played or kept as a song.
- **Certificates from eMudhra's compromised keys are refused**, so no one can pose as your server with one. Octo says so when a server uses one.
- Hearts that get the version you heard need an Octo server from this update.
- Imports waiting for Mornye's check say so, and say when they will go on. Health names Mornye and octo-sonic, and Mornye's row offers Pass on the dashboard while its check is paused.
- A higher quality copy that waits says the server's own words for why.
- On the Octo admin screen, recent downloads show their covers, served by your Octo server, and the server's music catalog check shows by name.

## Lyrics

- Lyrics work as before. Nothing changed here in this update.

## Offline

- Offline listening works as before. Nothing changed here in this update.

## Everyday touches

- A refused download's reason wraps with the row's words instead of being cut off.
- An album's download log no longer offers Find songs, since an album is no one song.

## Settings

- **Themes.** Pick Octo, Moss, Dusk, Daylight or Paper, or let Octo match the system's light and dark. Every screen draws in the theme's colors, and backups keep your theme.
- Backups of a big library save and restore a page at a time.
