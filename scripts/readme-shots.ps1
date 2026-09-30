# The README's desktop pictures, drawn off screen from the library of the
# Octo in use (desktop/src/test/.../ui/ReadmeShotsTest.kt).
#
#   powershell -File scripts/readme-shots.ps1 -Survey
#   powershell -File scripts/readme-shots.ps1 -Album <id> -Playing <id>[:track] -Playlist <id>
#
# -Survey draws all of Home and Albums and lists the albums (most colourful
# covers first) and playlists in desktop/build/shots/readme/survey.txt, to
# pick from. Otherwise the shots are desktop/build/shots/readme/desktop-*.png:
# home, album, playlists, albums, songs, genres, library health and the full
# player (immersive and glow, with lyrics, the queue or neither), and the
# gallery's pages below when asked for, 1600x1000 at scale 2.
#
# Options:
#   -Album <id>       the album page
#   -Playing <list>   songs for the full player, "album id" or "album id:track"
#                     (from 0), split by commas; the first is in the player
#                     bar on every other page
#   -Playlist <list>  playlist ids, split by commas
#   -AlbumSort <name> the Albums page's order (MostPlayed, RecentlyAdded, ...)
#   -Pages <list>     draw only the pages whose names start with these
#   -Scale <n>        screen scale (default 2)
#   -Search <words>   the Search page for these words; -SearchScroll <list>
#                     turns of the wheel down it, split by commas, a picture
#                     each
#   -Artist <id>      the artist page
#   -Queue <id:track> an album song playing, the queue open beside its album;
#                     -QueueNext <id:count> puts songs of another album next
#   -Lyrics <spec>    "words|song id|line": the full player with timed lyrics
#                     for a song a search for the words finds (in the library
#                     or online), paused on that line (from 0)
#   -Palette <text>   what is typed in the Ctrl+K box, over the album page;
#                     several split by ";", one picture each
#   -LiveList <list>  live lists split by ";", each a starter's name or
#                     "name|genre", one picture each
#   -Find <list>      instead of drawing, list what searches (split by "|")
#                     find in desktop/build/shots/readme/find.txt;
#                     -FindLyrics <list> also says whether each song found
#                     has timed lyrics on the server
#   -ViaWmi          read the settings through a process WMI starts, for a
#                     shell whose %APPDATA% is not the real one (a packaged
#                     app's terminal sees its own copy)
#
# What the run may do: it copies settings.json (the server's address and
# user name, never the password) to a folder of its own under %TEMP%, and
# the test reads the password from the system's store and never writes or
# deletes it. Every request goes through a guard that lets only the server's
# reads through, so nothing is played, reported, starred, rated or changed,
# and no song is streamed. The copy is deleted at the end.
param(
    [switch]$Survey,
    [string]$Album = "",
    [string]$Playing = "",
    [string]$Playlist = "",
    [string]$AlbumSort = "",
    [string]$Pages = "",
    [double]$Scale = 2,
    [string]$Search = "",
    [string]$SearchScroll = "",
    [string]$Artist = "",
    [string]$Queue = "",
    [string]$QueueNext = "",
    [string]$Lyrics = "",
    [string]$Palette = "",
    [string]$LiveList = "",
    [string]$Find = "",
    [string]$FindLyrics = "",
    [switch]$ViaWmi
)
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$work = Join-Path ([System.IO.Path]::GetTempPath()) "octo-readme-shots-$PID"
$copy = Join-Path $work "settings.json"
New-Item -ItemType Directory -Force $work | Out-Null

try {
    if ($ViaWmi) {
        $done = Join-Path $work "done.txt"
        $script = "Copy-Item (Join-Path `$env:APPDATA 'Octo\settings.json') '$copy'; 'done' | Out-File '$done'"
        $encoded = [Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($script))
        $started = Invoke-CimMethod -ClassName Win32_Process -MethodName Create -Arguments @{ CommandLine = "powershell.exe -NoProfile -NonInteractive -WindowStyle Hidden -EncodedCommand $encoded" }
        if ($started.ReturnValue -ne 0) { throw "WMI could not start the copy ($($started.ReturnValue))" }
        for ($i = 0; $i -lt 60 -and -not (Test-Path $done); $i++) { Start-Sleep -Milliseconds 500 }
    } else {
        Copy-Item (Join-Path $env:APPDATA "Octo\settings.json") $copy
    }
    if (-not (Test-Path $copy)) { throw "No settings.json of the Octo in use to copy" }

    $env:OCTO_SHOTS = "1"
    $env:OCTO_README_SETTINGS = $copy
    $env:OCTO_README_SURVEY = if ($Survey) { "1" } else { $null }
    $env:OCTO_README_ALBUM = if ($Album) { $Album } else { $null }
    $env:OCTO_README_PLAYING = if ($Playing) { $Playing } else { $null }
    $env:OCTO_README_PLAYLIST = if ($Playlist) { $Playlist } else { $null }
    $env:OCTO_README_ALBUM_SORT = if ($AlbumSort) { $AlbumSort } else { $null }
    $env:OCTO_README_PAGES = if ($Pages) { $Pages } else { $null }
    $env:OCTO_README_SCALE = "$Scale"
    $env:OCTO_README_SEARCH = if ($Search) { $Search } else { $null }
    $env:OCTO_README_SEARCH_SCROLL = if ($SearchScroll) { $SearchScroll } else { $null }
    $env:OCTO_README_ARTIST = if ($Artist) { $Artist } else { $null }
    $env:OCTO_README_QUEUE = if ($Queue) { $Queue } else { $null }
    $env:OCTO_README_QUEUE_NEXT = if ($QueueNext) { $QueueNext } else { $null }
    $env:OCTO_README_LYRICS = if ($Lyrics) { $Lyrics } else { $null }
    $env:OCTO_README_PALETTE = if ($Palette) { $Palette } else { $null }
    $env:OCTO_README_LIVELIST = if ($LiveList) { $LiveList } else { $null }
    $env:OCTO_README_FIND = if ($Find) { $Find } else { $null }
    $env:OCTO_README_FIND_LYRICS = if ($FindLyrics) { $FindLyrics } else { $null }
    Push-Location $root
    try {
        # --rerun: the settings and choices are not inputs Gradle sees.
        & .\gradlew.bat :desktop:test --tests "*ReadmeShotsTest*" --rerun --console=plain
        if ($LASTEXITCODE -ne 0) { throw "The shots failed; see desktop/build/reports/tests" }
    } finally { Pop-Location }
    $report = Join-Path $root "desktop\build\test-results\test\TEST-app.winters.octo.desktop.ui.ReadmeShotsTest.xml"
    if (Test-Path $report) { Select-String -Path $report -Pattern "README shots: .*" | ForEach-Object { $_.Matches.Value } }
    Write-Host "Shots in $(Join-Path $root 'desktop\build\shots\readme')"
} finally {
    Remove-Item -Recurse -Force $work -Confirm:$false -ErrorAction SilentlyContinue
}
