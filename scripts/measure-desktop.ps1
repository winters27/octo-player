# Measures the packaged desktop app the way a listener meets it: how long
# the window takes to appear and the app to settle, then its memory, threads
# and processor use while it sits idle, shown and minimised. The first
# start and then warm starts, each in a test profile folder of its own, so
# the Octo in use is never touched (see "Isolation" below).
#
#   powershell -File scripts/measure-desktop.ps1 -Build
#   powershell -File scripts/measure-desktop.ps1 -App desktop\build\compose\binaries\main\app\Octo
#   powershell -File scripts/measure-desktop.ps1 -Msi path\to\Octo-1.0.400.msi -WithLibrary
#   powershell -File scripts/measure-desktop.ps1 -SelfTest
#
# Options:
#   -App <folder>     an app image (the folder holding Octo.exe and app\Octo.cfg);
#                     default: the one :desktop:createDistributable made
#   -Build            run ./gradlew :desktop:createDistributable first
#   -Msi <file>       unpack this installer (an administrative install, which
#                     installs nothing) and measure the app inside it
#   -ProfileDir <dir> the test profile; default %TEMP%\octo-measure
#   -WithLibrary      copy the settings of the Octo in use (its server, not
#                     its password: that is read from the system's store) so
#                     the test run signs in and reads the whole library.
#                     Discord, global shortcuts, play reports and queue sync
#                     are turned off in the copy. Do not sign out in the test
#                     window: signing out deletes the stored password, which
#                     the Octo in use shares.
#   -Warm <n>         warm starts after the first (default 2)
#   -IdleSeconds <n>  seconds sampled idle, shown and again minimised (default 30)
#   -QuietSeconds <n> how long the processor must stay under 2% of a core for
#                     the app to count as settled (default 8). A start waits
#                     on the server for a few seconds at a time, and 3 s of
#                     quiet there once passed for settled, so the work after
#                     it was counted as idle (9% of a core, all of it the
#                     start's own work).
#   -Out <file>       also write the results as CSV
#   -AllowInstalled   measure the installed Octo's own folder even when its
#                     build does not know OCTO_PROFILE_DIR (it shares the
#                     jump list of the Octo in use)
#   -DryRun           read the app image and make the profile, then stop
#   -SelfTest         check the script's own reading and sums without
#                     starting Octo (with -App, reads that app image too)
#
# Isolation: the run gets OCTO_PROFILE_DIR=<profile>, which builds that know
# it (desktop since 2026-09-28) take as their whole settings and cache folder,
# and APPDATA and LOCALAPPDATA pointing inside the profile, which older
# builds take the same way. Either way the one-Octo lock is in the profile,
# so the run never hands over to the Octo in use. A build that does not know
# OCTO_PROFILE_DIR may point octo:// links and Start with Windows at itself,
# so for those the two registry values are saved first and put back after.
#
# What it prints, per start: the time to the window, the time until the app
# went quiet (processor under 2% of a core for -QuietSeconds in a row), and while
# idle the working set (memory in RAM), private bytes (memory committed to
# the app, what Task Manager's "Commit size" shows), threads, handles and
# processor use as a share of one core; the same again minimised.
param(
    [string]$App = "",
    [switch]$Build,
    [string]$Msi = "",
    [string]$ProfileDir = "",
    [switch]$WithLibrary,
    [int]$Warm = 2,
    [int]$IdleSeconds = 30,
    [int]$QuietSeconds = 8,
    [int]$WindowTimeoutSeconds = 90,
    [int]$SettleTimeoutSeconds = 120,
    [string]$Out = "",
    [switch]$AllowInstalled,
    [switch]$DryRun,
    [switch]$SelfTest
)
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot

Add-Type -AssemblyName System.IO.Compression.FileSystem
if (-not ("OctoMeasure.Win" -as [type])) {
    Add-Type -Namespace OctoMeasure -Name Win -MemberDefinition @'
[DllImport("user32.dll")] public static extern bool ShowWindow(System.IntPtr hWnd, int nCmdShow);
[DllImport("user32.dll")] public static extern bool IsWindowVisible(System.IntPtr hWnd);
'@
}

# ---- Reading an app image ----

# What an app image is: its version, JVM options, how many jars, and whether
# it knows OCTO_PROFILE_DIR.
function Get-AppInfo([string]$Folder) {
    $exe = Join-Path $Folder "Octo.exe"
    $cfg = Join-Path $Folder "app\Octo.cfg"
    if (-not (Test-Path $exe)) { throw "No Octo.exe in $Folder" }
    if (-not (Test-Path $cfg)) { throw "No app\Octo.cfg in $Folder" }
    $info = Read-OctoCfg (Get-Content -Raw $cfg)
    $info | Add-Member -NotePropertyName Exe -NotePropertyValue $exe
    $jar = Get-ChildItem (Join-Path $Folder "app") -Filter "desktop-*.jar" | Select-Object -First 1
    $knows = $false
    if ($jar) { $knows = Test-JarMentions $jar.FullName "app/winters/octo/desktop/settings/PlacesKt.class" "OCTO_PROFILE_DIR" }
    $info | Add-Member -NotePropertyName KnowsProfile -NotePropertyValue $knows
    $info | Add-Member -NotePropertyName HasCds -NotePropertyValue (Test-Path (Join-Path $Folder "runtime\bin\server\classes.jsa"))
    return $info
}

# The version, the JVM options and the jars from the text of an Octo.cfg.
function Read-OctoCfg([string]$Text) {
    $options = @()
    $jars = 0
    $version = ""
    foreach ($line in ($Text -split "`r?`n")) {
        if ($line -match '^java-options=(.*)$') {
            $options += $Matches[1]
            if ($Matches[1] -match '^-Docto\.version=(.+)$') { $version = $Matches[1] }
        } elseif ($line -match '^app\.classpath=') {
            $jars++
        }
    }
    [pscustomobject]@{
        Version = $version
        JvmOptions = @($options | Where-Object { $_ -notmatch '^-D' })
        Jars = $jars
    }
}

# Whether an entry of a jar holds a piece of text (as class files keep their
# strings).
function Test-JarMentions([string]$Jar, [string]$Entry, [string]$Text) {
    $zip = [System.IO.Compression.ZipFile]::OpenRead($Jar)
    try {
        $found = $zip.Entries | Where-Object { $_.FullName -eq $Entry } | Select-Object -First 1
        if (-not $found) { return $false }
        $stream = $found.Open()
        try {
            $memory = New-Object System.IO.MemoryStream
            $stream.CopyTo($memory)
            $body = [System.Text.Encoding]::ASCII.GetString($memory.ToArray())
            return $body.Contains($Text)
        } finally { $stream.Dispose() }
    } finally { $zip.Dispose() }
}

# ---- The test profile ----

# The settings of the Octo in use, as JSON text, with what reaches other
# apps or the server by itself turned off.
function ConvertTo-TestSettings([string]$Json) {
    $settings = $Json | ConvertFrom-Json
    foreach ($group in "discord", "hotkeys") {
        if (-not $settings.$group) { $settings | Add-Member -NotePropertyName $group -NotePropertyValue ([pscustomobject]@{}) }
        $settings.$group | Add-Member -NotePropertyName on -NotePropertyValue $false -Force
    }
    if (-not $settings.listening) { $settings | Add-Member -NotePropertyName listening -NotePropertyValue ([pscustomobject]@{}) }
    $settings.listening | Add-Member -NotePropertyName reportPlays -NotePropertyValue $false -Force
    $settings.listening | Add-Member -NotePropertyName syncQueue -NotePropertyValue $false -Force
    # Closing the window quits, and nothing else opens or starts.
    if (-not $settings.system) { $settings | Add-Member -NotePropertyName system -NotePropertyValue ([pscustomobject]@{}) }
    foreach ($name in "closeToTray", "miniPlayerOpen", "startWithWindows", "startInTray") {
        $settings.system | Add-Member -NotePropertyName $name -NotePropertyValue $false -Force
    }
    return ($settings | ConvertTo-Json -Depth 32)
}

# A fresh profile: config and cache folders, and the settings when asked.
function New-TestProfile([string]$Folder, [bool]$Library) {
    if (Test-Path $Folder) { Remove-Item -Recurse -Force $Folder -Confirm:$false }
    foreach ($sub in "config", "cache", "Roaming\Octo", "Local\Octo\Cache") { New-Item -ItemType Directory -Force (Join-Path $Folder $sub) | Out-Null }
    if ($Library) {
        $inUse = Join-Path $env:APPDATA "Octo\settings.json"
        if (-not (Test-Path $inUse)) { throw "No settings at $inUse to copy" }
        $text = ConvertTo-TestSettings (Get-Content -Raw -Encoding UTF8 $inUse)
        # UTF-8 without a byte order mark, as Octo writes it.
        $plain = New-Object System.Text.UTF8Encoding($false)
        foreach ($place in "config\settings.json", "Roaming\Octo\settings.json") { [System.IO.File]::WriteAllText((Join-Path $Folder $place), $text, $plain) }
    }
}

# ---- The system's links, for builds that do not know OCTO_PROFILE_DIR ----

$LinkKey = "HKCU:\Software\Classes\octo\shell\open\command"
$RunKey = "HKCU:\Software\Microsoft\Windows\CurrentVersion\Run"

function Save-SystemLinks {
    $link = $null
    if (Test-Path $LinkKey) { $link = (Get-ItemProperty -Path $LinkKey).'(default)' }
    $run = (Get-ItemProperty -Path $RunKey -ErrorAction SilentlyContinue).Octo
    [pscustomobject]@{ Link = $link; Run = $run }
}

function Restore-SystemLinks($Saved) {
    $now = Save-SystemLinks
    if ($now.Link -ne $Saved.Link -and $Saved.Link) {
        Set-ItemProperty -Path $LinkKey -Name '(default)' -Value $Saved.Link
        Set-ItemProperty -Path "HKCU:\Software\Classes\octo\DefaultIcon" -Name '(default)' -Value (($Saved.Link -split '" ')[0] + '",0')
        Write-Host "  (put octo:// links back to the Octo in use)"
    }
    if ($now.Run -ne $Saved.Run) {
        if ($Saved.Run) { Set-ItemProperty -Path $RunKey -Name Octo -Value $Saved.Run } else { Remove-ItemProperty -Path $RunKey -Name Octo -ErrorAction SilentlyContinue }
        Write-Host "  (put Start with Windows back as it was)"
    }
}

# ---- Samples ----

# One look at a process: memory, threads, handles and processor time so far.
function Get-Sample([int]$ProcessId) {
    $p = Get-Process -Id $ProcessId -ErrorAction Stop
    [pscustomobject]@{
        At = [DateTime]::UtcNow
        WorkingSet = $p.WorkingSet64
        Private = $p.PrivateMemorySize64
        Threads = $p.Threads.Count
        Handles = $p.HandleCount
        CpuMs = $p.TotalProcessorTime.TotalMilliseconds
    }
}

# Sums up samples taken a second apart: memory as the mean and the most,
# processor time as a share of one core over the whole stretch, and the
# busiest second, which shows a burst the mean hides.
function Measure-Samples($Samples) {
    $list = @($Samples)
    if ($list.Count -lt 2) { throw "Need at least two samples" }
    $seconds = ($list[-1].At - $list[0].At).TotalSeconds
    $cpu = ($list[-1].CpuMs - $list[0].CpuMs) / 10.0 / $seconds
    $peak = 0.0
    for ($i = 1; $i -lt $list.Count; $i++) {
        $span = ($list[$i].At - $list[$i - 1].At).TotalSeconds
        if ($span -gt 0) { $peak = [math]::Max($peak, ($list[$i].CpuMs - $list[$i - 1].CpuMs) / 10.0 / $span) }
    }
    $mb = 1MB
    [pscustomobject]@{
        Seconds = [math]::Round($seconds, 1)
        WorkingSetMB = [math]::Round((($list | Measure-Object WorkingSet -Average).Average) / $mb)
        WorkingSetMaxMB = [math]::Round((($list | Measure-Object WorkingSet -Maximum).Maximum) / $mb)
        PrivateMB = [math]::Round((($list | Measure-Object Private -Average).Average) / $mb)
        Threads = [math]::Round(($list | Measure-Object Threads -Average).Average)
        Handles = [math]::Round(($list | Measure-Object Handles -Average).Average)
        CpuPercentOfCore = [math]::Round($cpu, 2)
        CpuPeakPercentOfCore = [math]::Round($peak, 2)
    }
}

function Measure-Idle([int]$ProcessId, [int]$Seconds) {
    $samples = @(Get-Sample $ProcessId)
    for ($i = 0; $i -lt $Seconds; $i++) {
        Start-Sleep -Seconds 1
        $samples += Get-Sample $ProcessId
    }
    Measure-Samples $samples
}

# Seconds from `$Clock` starting until the process has used under
# `$Percent` of a core for `$QuietSeconds` in a row.
function Wait-Quiet([int]$ProcessId, $Clock, [double]$Percent = 2, [int]$QuietSeconds = 3, [int]$TimeoutSeconds = 120) {
    $last = Get-Sample $ProcessId
    $quiet = 0
    $quietSince = $Clock.Elapsed.TotalSeconds
    while ($Clock.Elapsed.TotalSeconds -lt $TimeoutSeconds) {
        Start-Sleep -Milliseconds 1000
        $now = Get-Sample $ProcessId
        $share = ($now.CpuMs - $last.CpuMs) / 10.0 / ($now.At - $last.At).TotalSeconds
        $last = $now
        if ($share -lt $Percent) {
            if ($quiet -eq 0) { $quietSince = $Clock.Elapsed.TotalSeconds - 1 }
            $quiet++
            if ($quiet -ge $QuietSeconds) { return [math]::Round($quietSince, 1) }
        } else {
            $quiet = 0
        }
    }
    return $null
}

# ---- Running the app ----

# Starts the app image with the test profile; answers the process.
function Start-Octo($Info, [string]$Folder) {
    $saved = @{ OCTO_PROFILE_DIR = $env:OCTO_PROFILE_DIR; APPDATA = $env:APPDATA; LOCALAPPDATA = $env:LOCALAPPDATA }
    try {
        $env:OCTO_PROFILE_DIR = $Folder
        $env:APPDATA = Join-Path $Folder "Roaming"
        $env:LOCALAPPDATA = Join-Path $Folder "Local"
        return Start-Process -FilePath $Info.Exe -WorkingDirectory (Split-Path $Info.Exe) -PassThru
    } finally {
        foreach ($name in $saved.Keys) { Set-Item -Path "Env:$name" -Value $saved[$name] -ErrorAction SilentlyContinue }
        if (-not $saved.OCTO_PROFILE_DIR) { Remove-Item Env:OCTO_PROFILE_DIR -ErrorAction SilentlyContinue }
    }
}

# The process that is the app. The installed Octo.exe is a small launcher
# that starts the app as a second Octo.exe and waits for it, so the window,
# memory and processor use to measure are that child's. A build without
# the launcher is its own app.
function Resolve-App($Launcher, $Clock, [int]$TimeoutSeconds) {
    while ($Clock.Elapsed.TotalSeconds -lt $TimeoutSeconds) {
        $child = Get-CimInstance Win32_Process -Filter "ParentProcessId=$($Launcher.Id) AND Name='Octo.exe'" | Select-Object -First 1
        if ($child) { $app = Get-Process -Id $child.ProcessId -ErrorAction SilentlyContinue; if ($app) { return $app } }
        $Launcher.Refresh()
        if ($Launcher.HasExited) { throw "Octo ended before starting (exit $($Launcher.ExitCode))" }
        if ($Launcher.MainWindowHandle -ne [IntPtr]::Zero) { return $Launcher }
        Start-Sleep -Milliseconds 50
    }
    throw "Octo did not start within $TimeoutSeconds s"
}

# Seconds until the process shows a window, or null.
function Wait-Window($Process, $Clock, [int]$TimeoutSeconds) {
    while ($Clock.Elapsed.TotalSeconds -lt $TimeoutSeconds) {
        $Process.Refresh()
        if ($Process.HasExited) { throw "Octo ended before showing a window (exit $($Process.ExitCode))" }
        $handle = $Process.MainWindowHandle
        if ($handle -ne [IntPtr]::Zero -and [OctoMeasure.Win]::IsWindowVisible($handle)) { return [math]::Round($Clock.Elapsed.TotalSeconds, 2) }
        Start-Sleep -Milliseconds 50
    }
    return $null
}

function Stop-Octo($Process, $Launcher = $null) {
    $Process.Refresh()
    if (-not $Process.HasExited) {
        # The window's close; a test profile never keeps playing in the tray,
        # but anything left after that is ended.
        [void]$Process.CloseMainWindow()
        if (-not $Process.WaitForExit(10000)) { Stop-Process -Id $Process.Id -Force -Confirm:$false }
    }
    # The launcher ends with the app; one still there after that is ended too.
    if ($Launcher -and $Launcher.Id -ne $Process.Id -and -not $Launcher.WaitForExit(5000)) { Stop-Process -Id $Launcher.Id -Force -Confirm:$false }
}

# One start: window, settle, idle shown, idle minimised.
function Measure-Start($Info, [string]$Folder, [string]$Kind, [int]$Seconds) {
    $clock = [System.Diagnostics.Stopwatch]::StartNew()
    $launcher = Start-Octo $Info $Folder
    $process = $launcher
    try {
        $process = Resolve-App $launcher $clock $WindowTimeoutSeconds
        $window = Wait-Window $process $clock $WindowTimeoutSeconds
        if ($null -eq $window) { throw "No window within $WindowTimeoutSeconds s" }
        $settled = Wait-Quiet $process.Id $clock -QuietSeconds $QuietSeconds -TimeoutSeconds $SettleTimeoutSeconds
        $shown = Measure-Idle $process.Id $Seconds
        $process.Refresh()
        [void][OctoMeasure.Win]::ShowWindow($process.MainWindowHandle, 6)
        Start-Sleep -Seconds 3
        $minimised = Measure-Idle $process.Id $Seconds
        [pscustomobject]@{
            Start = $Kind
            WindowS = $window
            SettledS = $settled
            IdleWorkingSetMB = $shown.WorkingSetMB
            IdlePrivateMB = $shown.PrivateMB
            IdleCpuPctCore = $shown.CpuPercentOfCore
            IdleCpuPeakPct = $shown.CpuPeakPercentOfCore
            Threads = $shown.Threads
            Handles = $shown.Handles
            MinWorkingSetMB = $minimised.WorkingSetMB
            MinPrivateMB = $minimised.PrivateMB
            MinCpuPctCore = $minimised.CpuPercentOfCore
        }
    } finally {
        Stop-Octo $process $launcher
    }
}

# ---- Self-test: the reading and the sums, without starting Octo ----

function Invoke-SelfTest {
    $script:failed = 0
    function Check([string]$What, [bool]$Ok) {
        if ($Ok) { Write-Host "  ok    $What" } else { Write-Host "  FAIL  $What"; $script:failed++ }
    }
    Write-Host "Self-test (Octo is not started)"
    $cfg = "[Application]`r`napp.classpath=`$APPDIR\a.jar`r`napp.mainclass=app.winters.octo.desktop.MainKt`r`napp.classpath=`$APPDIR\b.jar`r`n`r`n[JavaOptions]`r`njava-options=-Djpackage.app-version=1.0.9`r`njava-options=-Docto.version=1.0.9`r`njava-options=-Xmx768m`r`njava-options=-XX:+UseSerialGC"
    $read = Read-OctoCfg $cfg
    Check "Octo.cfg version" ($read.Version -eq "1.0.9")
    Check "Octo.cfg JVM options" (($read.JvmOptions -join " ") -eq "-Xmx768m -XX:+UseSerialGC")
    Check "Octo.cfg jars" ($read.Jars -eq 2)

    $json = '{"server":{"address":"https://music.example","username":"b"},"discord":{"on":true},"listening":{"reportPlays":true},"system":{"closeToTray":true,"miniPlayerOpen":true}}'
    $test = ConvertTo-TestSettings $json | ConvertFrom-Json
    Check "settings copy keeps the server" ($test.server.address -eq "https://music.example")
    Check "settings copy turns Discord off" ($test.discord.on -eq $false)
    Check "settings copy turns global shortcuts off" ($test.hotkeys.on -eq $false)
    Check "settings copy stops play reports and queue sync" ($test.listening.reportPlays -eq $false -and $test.listening.syncQueue -eq $false)
    Check "settings copy closes on close, no mini player" ($test.system.closeToTray -eq $false -and $test.system.miniPlayerOpen -eq $false)

    $t0 = [DateTime]::UtcNow
    $samples = @(
        [pscustomobject]@{ At = $t0; WorkingSet = 400MB; Private = 700MB; Threads = 100; Handles = 900; CpuMs = 1000 },
        [pscustomobject]@{ At = $t0.AddSeconds(5); WorkingSet = 420MB; Private = 720MB; Threads = 102; Handles = 910; CpuMs = 1050 },
        [pscustomobject]@{ At = $t0.AddSeconds(10); WorkingSet = 410MB; Private = 710MB; Threads = 104; Handles = 920; CpuMs = 1100 }
    )
    $sum = Measure-Samples $samples
    Check "sums: working set mean" ($sum.WorkingSetMB -eq 410)
    Check "sums: working set most" ($sum.WorkingSetMaxMB -eq 420)
    Check "sums: private mean" ($sum.PrivateMB -eq 710)
    Check "sums: processor, 100 ms in 10 s is 1% of a core" ($sum.CpuPercentOfCore -eq 1)
    Check "sums: the busiest second, 50 ms in 5 s is 1%" ($sum.CpuPeakPercentOfCore -eq 1)

    # Sampling a real process: this one.
    $own = Measure-Idle $PID 2
    Check "sampling this PowerShell (working set $($own.WorkingSetMB) MB, $($own.Threads) threads)" ($own.WorkingSetMB -gt 0 -and $own.Threads -gt 0)

    $built = if ($App) { $App } else { Join-Path $root "desktop\build\compose\binaries\main\app\Octo" }
    if (Test-Path (Join-Path $built "app\Octo.cfg")) {
        $info = Get-AppInfo $built
        Check "reading the built app image (version $($info.Version), $($info.Jars) jars, knows OCTO_PROFILE_DIR: $($info.KnowsProfile), CDS archive: $($info.HasCds))" ($info.Jars -gt 10)
    } else {
        Write-Host "  skip  reading the built app image (none at $built)"
    }
    $links = Save-SystemLinks
    Check "reading the octo:// link and Start with Windows (read only)" ($null -ne $links)

    $folder = Join-Path ([IO.Path]::GetTempPath()) "octo-measure-selftest"
    New-TestProfile $folder $false
    Check "a fresh test profile" ((Test-Path (Join-Path $folder "config")) -and (Test-Path (Join-Path $folder "Local\Octo\Cache")))
    Remove-Item -Recurse -Force $folder -Confirm:$false

    if ($script:failed) { throw "$($script:failed) self-test checks failed" }
    Write-Host "All self-test checks passed."
}

if ($SelfTest) {
    Invoke-SelfTest
    return
}

# ---- Measuring ----

if ($Build) {
    Push-Location $root
    try {
        & "$root\gradlew.bat" :desktop:createDistributable
        if ($LASTEXITCODE -ne 0) { throw "The build failed" }
    } finally { Pop-Location }
}
if ($Msi) {
    $unpacked = Join-Path $root "desktop\build\measure\msi"
    if (Test-Path $unpacked) { Remove-Item -Recurse -Force $unpacked -Confirm:$false }
    $unpack = Start-Process msiexec.exe -ArgumentList "/a `"$Msi`" /qn TARGETDIR=`"$unpacked`"" -Wait -PassThru
    if ($unpack.ExitCode -ne 0) { throw "Unpacking the MSI failed ($($unpack.ExitCode))" }
    $exe = Get-ChildItem -Recurse $unpacked -Filter Octo.exe | Select-Object -First 1
    if (-not $exe) { throw "No Octo.exe in the MSI" }
    $App = $exe.DirectoryName
}
if (-not $App) { $App = Join-Path $root "desktop\build\compose\binaries\main\app\Octo" }
$App = (Resolve-Path $App).Path
if (-not $ProfileDir) { $ProfileDir = Join-Path ([IO.Path]::GetTempPath()) "octo-measure" }

$info = Get-AppInfo $App
Write-Host "App image: $App"
Write-Host "  version $($info.Version), $($info.Jars) jars, JVM options: $($info.JvmOptions -join ' ')"
Write-Host "  CDS archive in its runtime: $($info.HasCds); knows OCTO_PROFILE_DIR: $($info.KnowsProfile)"
Write-Host "Test profile: $ProfileDir$(if ($WithLibrary) { ' (with the settings of the Octo in use, signed in)' } else { ' (signed out)' })"

$installed = Join-Path $env:LOCALAPPDATA "Octo"
if (-not $info.KnowsProfile -and ((Resolve-Path $installed -ErrorAction SilentlyContinue).Path -eq $App) -and -not $AllowInstalled) {
    # The installed program shares the jump list of the Octo in use, which
    # an older build signed out would clear.
    throw "This is the installed Octo, and it does not know OCTO_PROFILE_DIR. Measure an unpacked copy (-Msi) instead, or pass -AllowInstalled."
}
$links = $null
if (-not $info.KnowsProfile) {
    $links = Save-SystemLinks
    Write-Host "This build does not know OCTO_PROFILE_DIR: the octo:// link and Start with Windows are saved and put back after each start."
}

$results = @()
New-TestProfile $ProfileDir $WithLibrary.IsPresent
if ($DryRun) {
    Write-Host "Dry run: the profile is ready and Octo was not started."
    return
}
for ($run = 0; $run -le $Warm; $run++) {
    $kind = if ($run -eq 0) { "first" } else { "warm $run" }
    Write-Host "Start: $kind ..."
    try {
        $results += Measure-Start $info $ProfileDir $kind $IdleSeconds
    } finally {
        if ($links) { Restore-SystemLinks $links }
    }
    Start-Sleep -Seconds 3
}

Write-Host ""
Write-Host "Octo $($info.Version), idle $IdleSeconds s shown and $IdleSeconds s minimised per start ($(if ($WithLibrary) { 'signed in' } else { 'signed out' }))"
$results | Format-Table -AutoSize Start, WindowS, SettledS, IdleWorkingSetMB, IdlePrivateMB, IdleCpuPctCore, IdleCpuPeakPct, Threads, Handles, MinWorkingSetMB, MinPrivateMB, MinCpuPctCore | Out-String -Width 200 | Write-Host
Write-Host "WindowS: seconds to a visible window. SettledS: seconds until the processor stayed under 2% of a core for $QuietSeconds s. IdleCpuPeakPct: the busiest second while idle."
Write-Host "WorkingSet: memory in RAM. Private: memory committed to the app (Task Manager's Commit size). CpuPctCore: share of one core."
if ($Out) {
    $results | Export-Csv -NoTypeInformation -Path $Out
    Write-Host "Written to $Out"
}
