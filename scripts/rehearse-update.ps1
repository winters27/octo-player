#Requires -Version 7
# Rehearses the desktop self-update end to end on this PC, without GitHub:
# an installed Octo (build A) finds, downloads, checks and installs a newer
# one (build B) from a pretend GitHub on 127.0.0.1. B's release is staged,
# described and signed the way the release workflow does it, with a
# throwaway key that only these two builds trust. The key, the builds and
# the backups live in %TEMP%\octo-rehearsal, never in the repository.
#
# The phases, in the order a rehearsal runs them. Each is safe to run again,
# and several can go in one call (they run in this order):
#
#   pwsh scripts/rehearse-update.ps1 -Prepare   # once: key, MSI A and B, B's signed release (builds only)
#   pwsh scripts/rehearse-update.ps1 -Backup    # before installing: a copy of %APPDATA%\Octo to compare with
#   pwsh scripts/rehearse-update.ps1 -Serve     # starts the pretend GitHub in the background
#   pwsh scripts/rehearse-update.ps1 -Install   # with Octo closed: installs A over the installed Octo
#   pwsh scripts/rehearse-update.ps1 -Launch    # opens A, pointed at the pretend GitHub
#       then in Octo: Settings > About, turn on "Try early versions" (a
#       rehearsal version is an early one), Check now, Restart to update
#   pwsh scripts/rehearse-update.ps1 -Verify    # after the update: what was fetched, installed and changed
#   pwsh scripts/rehearse-update.ps1 -Restore   # only if the settings need putting back
#
# Options:
#   -Build <n>        the MSI's third number for A; B gets n+1 (default 1).
#                     Keep it low: a real release's MSI (1.1.<commit count>)
#                     must stay higher, or Windows refuses it as older.
#   -Port <n>         the pretend GitHub's port (default 47631)
#   -ServeHours <h>   how long the pretend GitHub runs before stopping by itself (default 3)
#   -Work <folder>    where everything goes (default %TEMP%\octo-rehearsal)
#   -From <folder>    the backup -Verify and -Restore use (default the newest)
#
# After a rehearsal the installed Octo is 1.1.0-rehearsal.2 (MSI 1.1.<n+1>).
# A real release installs over it; a 1.0.x test build does not until the
# rehearsal Octo is uninstalled (Settings > Apps). Settings stay either way.
param(
    [switch]$Prepare,
    [switch]$Backup,
    [switch]$Serve,
    [switch]$Install,
    [switch]$Launch,
    [switch]$Verify,
    [switch]$Restore,
    [int]$Build = 1,
    [int]$Port = 47631,
    [double]$ServeHours = 3,
    [string]$Work = (Join-Path ([IO.Path]::GetTempPath()) "octo-rehearsal"),
    [string]$From = ""
)
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot

$VersionA = "1.1.0-rehearsal.1"
$VersionB = "1.1.0-rehearsal.2"
$TagB = "desktop-v$VersionB"
$MsiNumbersA = "1.1.$Build"
$MsiNumbersB = "1.1.$($Build + 1)"
$Url = "http://127.0.0.1:$Port/"
$Config = Join-Path $env:APPDATA "Octo"
$KeysFile = Join-Path $root "shared\core\src\commonMain\resources\app\winters\octo\update\trusted-keys.txt"
$Release = Join-Path $Work "releases\$TagB"
$MsiA = Join-Path $Work "msi\Octo-$VersionA-windows-x64.msi"
$MsiNameB = "Octo-$VersionB-windows-x64.msi"

if (-not ($Prepare -or $Backup -or $Serve -or $Install -or $Launch -or $Verify -or $Restore)) {
    foreach ($line in Get-Content $PSCommandPath | Select-Object -Skip 1) {
        if ($line -notmatch '^#') { break }
        $line -replace '^# ?', ''
    }
    exit 0
}

function Say([string]$Text) { Write-Host "rehearse: $Text" }

# Git's OpenSSL when none is on the PATH.
function Get-OpenSsl {
    $found = Get-Command openssl -ErrorAction SilentlyContinue
    if ($found) { return $found.Source }
    $git = "C:\Program Files\Git\mingw64\bin\openssl.exe"
    if (Test-Path $git) { return $git }
    throw "No OpenSSL found: install Git for Windows, or put openssl on the PATH."
}

# Python in UTF-8 (this PC's default is cp1252), leaving no compiled files behind.
$env:PYTHONUTF8 = "1"
$env:PYTHONDONTWRITEBYTECODE = "1"
$Python = @(if (Get-Command python -ErrorAction SilentlyContinue) { "python" } else { "py", "-3" })
$ReleaseScript = Join-Path $root "scripts\release\release_assets.py"

# Runs a command, showing what it prints, and returns its exit code.
function Invoke-Python([string[]]$Arguments) {
    & $Python[0] @($Python | Select-Object -Skip 1) @Arguments | Out-Host
    return $LASTEXITCODE
}

function Invoke-Gradle([string[]]$Arguments) {
    Push-Location $root
    try { & (Join-Path $root "gradlew.bat") @Arguments --console=plain | Out-Host } finally { Pop-Location }
    return $LASTEXITCODE
}

# Runs a step that should be refused, and returns everything it printed.
# Throws when it was not refused.
function Assert-Refused([string]$What, [string]$Expected, [scriptblock]$Step) {
    $ErrorActionPreference = "Continue"
    Push-Location $root
    try { $said = & $Step 2>&1 | Out-String } finally { Pop-Location }
    if ($LASTEXITCODE -eq 0 -or $said -notmatch $Expected) { throw "$What was not refused, and must be:`n$said" }
    Say "guard: $What is refused"
}

# Every running Octo: the installed one (Octo.exe) and a dev one run from a
# checkout (gradlew :desktop:hotRun or :desktop:run, and the app's own JVM).
function Get-OctoProcesses {
    Get-CimInstance Win32_Process | Where-Object {
        $_.Name -ieq "Octo.exe" -or
        ($_.Name -match '^javaw?\.exe$' -and $_.CommandLine -match ':desktop:(hotRun|run)\b|app\.winters\.octo\.desktop\.MainKt')
    }
}

function Format-Process($Process) {
    # Octo.exe is a launcher that starts the app as a second Octo.exe.
    $line = if ($Process.Name -ine "Octo.exe") {
        "dev Octo: " + $Process.CommandLine
    } elseif (Get-CimInstance Win32_Process -Filter "ProcessId=$($Process.ParentProcessId) AND Name='Octo.exe'") {
        "app: " + $Process.ExecutablePath
    } else {
        "launcher: " + $Process.ExecutablePath
    }
    if ($line.Length -gt 160) { $line = $line.Substring(0, 160) + "..." }
    "  pid $($Process.ProcessId) $($Process.Name)  $line"
}

# Stops the phase while any Octo runs, naming each one. Nothing is closed:
# closing them is the listener's call.
function Assert-NoOcto([string]$Doing) {
    $running = @(Get-OctoProcesses)
    if ($running.Count -gt 0) {
        $names = ($running | ForEach-Object { Format-Process $_ }) -join "`n"
        throw "Close Octo before $Doing. Running now:`n$names"
    }
}

# The installed Octo's entry in Apps, or null.
function Get-InstalledOcto {
    $places = "HKCU:\Software\Microsoft\Windows\CurrentVersion\Uninstall", "HKLM:\Software\Microsoft\Windows\CurrentVersion\Uninstall"
    Get-ChildItem $places -ErrorAction SilentlyContinue | ForEach-Object { Get-ItemProperty $_.PSPath } |
        Where-Object { $_.DisplayName -eq "Octo" } | Select-Object -First 1
}

function Get-InstallFolder {
    $installed = Get-InstalledOcto
    if ($installed -and $installed.InstallLocation) { return $installed.InstallLocation.TrimEnd('\') }
    return Join-Path $env:LOCALAPPDATA "Octo"
}

# The version the app itself shows, from its launcher settings.
function Get-AppVersion([string]$Folder) {
    $cfg = Join-Path $Folder "app\Octo.cfg"
    if (-not (Test-Path $cfg)) { return $null }
    $line = Select-String -Path $cfg -Pattern 'octo\.version=(\S+)' | Select-Object -First 1
    if ($line) { $line.Matches[0].Groups[1].Value } else { $null }
}

# The trusted-keys.txt inside a jar, or null.
function Get-PackedKeys([string]$Jar) {
    if (-not (Test-Path $Jar)) { return $null }
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $zip = [IO.Compression.ZipFile]::OpenRead($Jar)
    try {
        $entry = $zip.GetEntry("app/winters/octo/update/trusted-keys.txt")
        if (-not $entry) { return $null }
        $reader = [IO.StreamReader]::new($entry.Open())
        try { return $reader.ReadToEnd() } finally { $reader.Dispose() }
    } finally { $zip.Dispose() }
}

function Test-Server {
    try { (Invoke-RestMethod -Uri $Url -TimeoutSec 2).pretend -eq "github" } catch { $false }
}

# Every key path in a JSON settings file ("updates.earlyVersions"), not the values.
function Get-JsonPaths($Node, [string]$Prefix = "") {
    if ($Node -is [System.Collections.IDictionary]) {
        foreach ($key in $Node.Keys) {
            $path = if ($Prefix) { "$Prefix.$key" } else { "$key" }
            $path
            Get-JsonPaths $Node[$key] $path
        }
    }
}

function Read-Settings([string]$File) {
    if (-not (Test-Path $File)) { return $null }
    Get-Content -Raw -Encoding utf8 $File | ConvertFrom-Json -AsHashtable
}

function Get-NewestBackup {
    if ($From) {
        if (-not (Test-Path (Join-Path $From "Octo"))) { throw "$From is not a backup made by -Backup." }
        return (Resolve-Path $From).Path
    }
    $newest = Get-ChildItem $Work -Directory -Filter "backup-*" -ErrorAction SilentlyContinue | Sort-Object Name -Descending | Select-Object -First 1
    if ($newest) { $newest.FullName } else { $null }
}

# Builds one MSI trusting the rehearsal key, and checks what it carries.
function Build-Rehearsal([string]$Version, [int]$Number, [string]$Key, [string[]]$Tasks) {
    Say "building Octo $Version (MSI $("1.1.$Number")): $($Tasks -join ' ')"
    $code = Invoke-Gradle ($Tasks + @("-PoctoDesktopVersion=$Version", "-PoctoBuild=$Number", "-PoctoRehearsalKey=$Key"))
    if ($code -ne 0) { throw "The build of $Version failed ($code)." }
    # What the installer was made from: packageMsi keeps its arguments and
    # the list of jars it packed, and removes the app image itself.
    $made = Join-Path $root "desktop\build\compose\tmp"
    $said = Select-String -Path (Join-Path $made "packageMsi.args.txt") -Pattern "octo\.version=([^'`"\s]+)" | Select-Object -First 1
    $shown = if ($said) { $said.Matches[0].Groups[1].Value } else { "" }
    if ($shown -ne $Version) { throw "The installer was made to say $shown, not $Version." }
    $core = Join-Path $root "shared\core\build\libs\core-desktop.jar"
    if (-not (Select-String -Path (Join-Path $made "packageMsi\libs-mapping.txt") -SimpleMatch $core -Quiet)) { throw "The installer was not made from $core." }
    $packed = Get-PackedKeys $core
    if (-not $packed -or -not (($packed -split "`r?`n") -contains $Key)) { throw "The installer's app does not trust the rehearsal key." }
    # Named for the MSI's own version; up to date on a rerun with nothing changed.
    $msi = Get-Item (Join-Path $root "desktop\build\compose\binaries\main\msi\Octo-1.1.$Number.msi") -ErrorAction SilentlyContinue
    if (-not $msi) { throw "The build of $Version made no Octo-1.1.$Number.msi." }
    Say "  built $($msi.Name): the app shows $shown and trusts the rehearsal key"
    return $msi.FullName
}

# -Prepare: run once before a rehearsal, and again after changing the
# updater's code. Makes the throwaway key (kept on reruns, so an installed
# A still trusts it), proves both guards refuse a rehearsal build where it
# must never go, builds A and B, then stages, describes and signs B's
# release as publish-player-release.yml does, and checks the signature
# the way that workflow checks it. Builds only: installs nothing.
if ($Prepare) {
    $mine = @(Get-OctoProcesses | Where-Object { ($_.CommandLine -replace '/', '\') -match ([regex]::Escape("$root\") + '(desktop|gradle)\\') })
    if ($mine.Count -gt 0) {
        $names = ($mine | ForEach-Object { Format-Process $_ }) -join "`n"
        throw "A dev Octo runs from this checkout, and building here would fight it over its files. Stop it, or run this from a worktree (git worktree add ..\octo-rehearsal HEAD). Running:`n$names"
    }
    $openssl = Get-OpenSsl
    New-Item -ItemType Directory -Force $Work | Out-Null
    $keyFile = Join-Path $Work "key.pem"
    if (-not (Test-Path $keyFile)) {
        & $openssl genpkey -algorithm ed25519 -out $keyFile
        if ($LASTEXITCODE -ne 0) { throw "OpenSSL could not make a key." }
        Say "made a throwaway signing key: $keyFile"
    } else {
        Say "using the throwaway key made before: $keyFile"
    }
    $der = Join-Path $Work "public.der"
    & $openssl pkey -in $keyFile -pubout -outform DER -out $der
    if ($LASTEXITCODE -ne 0) { throw "OpenSSL could not read $keyFile." }
    $public = [Convert]::ToBase64String(([IO.File]::ReadAllBytes($der))[-32..-1])
    Set-Content -Path (Join-Path $Work "public.txt") -Value $public -Encoding ascii
    Say "its public half: $public"

    # The guards, each shown refusing before anything is built.
    Assert-Refused "a build with the rehearsal key and a real version (1.1.0)" "only for a rehearsal build" {
        & .\gradlew.bat :shared:core:help -q "-PoctoRehearsalKey=$public" "-PoctoDesktopVersion=1.1.0" "-PoctoBuild=$Build"
    }
    Assert-Refused "staging $VersionB for a real release" "is a rehearsal version" {
        & $Python[0] @($Python | Select-Object -Skip 1) $ReleaseScript stage-desktop --version $VersionB --artifacts $Work --out (Join-Path $Work "never")
    }

    $built = Build-Rehearsal $VersionA $Build $public @(":desktop:packageMsi")
    New-Item -ItemType Directory -Force (Split-Path $MsiA) | Out-Null
    Copy-Item $built $MsiA -Force
    $built = Build-Rehearsal $VersionB ($Build + 1) $public @(":desktop:packageMsi", ":desktop:packagePortableZip")

    # The files as the build job uploads them, in a folder named for the runner.
    $artifacts = Join-Path $Work "artifacts"
    $uploaded = Join-Path $artifacts "octo-desktop-Windows-X64"
    if (Test-Path $artifacts) { Remove-Item -Recurse -Force $artifacts }
    New-Item -ItemType Directory -Force $uploaded | Out-Null
    Copy-Item $built $uploaded
    $zip = Get-ChildItem (Join-Path $root "desktop\build\compose\binaries\main\zip") -Filter "Octo-$VersionB-*.zip" | Select-Object -First 1
    if (-not $zip) { throw "The build of $VersionB made no portable zip." }
    Copy-Item $zip.FullName $uploaded

    # Name the files, write the notes, the manifest and checksums, as the workflow does.
    if (Test-Path $Release) { Remove-Item -Recurse -Force $Release }
    if ((Invoke-Python @($ReleaseScript,"--rehearsal", "stage-desktop", "--version", $VersionB, "--artifacts", $artifacts, "--out", $Release)) -ne 0) { throw "Staging failed." }
    $notes = Join-Path $Work "releases\$TagB.md"
    Set-Content -Path $notes -Encoding utf8NoBOM -Value "- A rehearsal of the self-update, built on this PC and never published.`n- Seeing this under Update ready means Octo found the release and checked its signature."
    if ((Invoke-Python @($ReleaseScript,"--rehearsal", "manifest", "--app", "desktop", "--tag", $TagB, "--notes", $notes, "--dir", $Release)) -ne 0) { throw "The manifest failed." }

    # Sign the manifest's exact bytes, as the workflow's "Sign the manifest" step.
    $signature = Join-Path $Work "update.sig"
    & $openssl pkeyutl -sign -inkey $keyFile -rawin -in (Join-Path $Release "update.json") -out $signature
    if ($LASTEXITCODE -ne 0) { throw "Signing failed." }
    [IO.File]::WriteAllText((Join-Path $Release "update.json.sig"), [Convert]::ToBase64String([IO.File]::ReadAllBytes($signature)))

    # "The apps would accept it": the keys these builds trust are the
    # repository's plus the rehearsal key, and one of them must verify it.
    # The repository's keys alone must not.
    $trusted = Join-Path $Work "trusted-keys.txt"
    Set-Content -Path $trusted -Encoding utf8NoBOM -Value ((Get-Content -Raw $KeysFile).TrimEnd() + "`n$public")
    $pems = Join-Path $Work "keys"
    if (Test-Path $pems) { Remove-Item -Recurse -Force $pems }
    if ((Invoke-Python @($ReleaseScript,"public-keys", "--keys", $trusted, "--out", $pems)) -ne 0) { throw "The public keys could not be written." }
    $check = Join-Path $Work "check.sig"
    [IO.File]::WriteAllBytes($check, [Convert]::FromBase64String((Get-Content -Raw (Join-Path $Release "update.json.sig"))))
    $matched = @()
    foreach ($pem in Get-ChildItem $pems -Filter *.pem | Sort-Object Name) {
        & $openssl pkeyutl -verify -pubin -inkey $pem.FullName -rawin -in (Join-Path $Release "update.json") -sigfile $check *> $null
        if ($LASTEXITCODE -eq 0) { $matched += $pem.Name }
    }
    $last = (Get-ChildItem $pems -Filter *.pem | Sort-Object Name | Select-Object -Last 1).Name
    if ($matched.Count -ne 1 -or $matched[0] -ne $last) { throw "The signature should match the rehearsal key ($last) alone, but matched: $($matched -join ', ')" }
    Say "the signature matches $last (the rehearsal key) and no key in the repository's trusted-keys.txt"

    Say "ready:"
    Say "  MSI A to install:  $MsiA  ($VersionA, MSI $MsiNumbersA)"
    Say "  release B:         $Release  ($VersionB, MSI $MsiNumbersB)"
    Get-ChildItem $Release | ForEach-Object { Say ("    {0,-44} {1,12:N0} bytes" -f $_.Name, $_.Length) }
    Say "  notes:             $notes"
    Say "  key (throwaway):   $keyFile"
    Say "  pretend GitHub:    -Serve starts it at $Url"
}

# -Backup: before -Install, while Octo is closed. Copies %APPDATA%\Octo
# (settings, queue, plays) and records each file's hash, so -Verify can say
# what the update changed and -Restore can put it back.
if ($Backup) {
    if (-not (Test-Path $Config)) { throw "There are no settings at $Config to back up." }
    if (@(Get-OctoProcesses).Count -gt 0) { Say "Octo is running, so a file or two may be mid-change; closing it first makes a cleaner copy." }
    $target = Join-Path $Work ("backup-" + (Get-Date -Format "yyyyMMdd-HHmmss"))
    $copy = Join-Path $target "Octo"
    $rows = @()
    foreach ($file in Get-ChildItem $Config -Recurse -File -Force) {
        $relative = [IO.Path]::GetRelativePath($Config, $file.FullName)
        $to = Join-Path $copy $relative
        New-Item -ItemType Directory -Force (Split-Path $to) | Out-Null
        try {
            Copy-Item -LiteralPath $file.FullName -Destination $to -Force
            $rows += [pscustomobject]@{ Path = $relative; Length = $file.Length; Sha256 = (Get-FileHash -LiteralPath $to -Algorithm SHA256).Hash }
        } catch {
            Say "  skipped $relative (in use: $($_.Exception.Message.Trim()))"
        }
    }
    $rows | Export-Csv -NoTypeInformation -Encoding utf8NoBOM (Join-Path $target "hashes.csv")
    $settings = Read-Settings (Join-Path $Config "settings.json")
    if ($settings) { Get-JsonPaths $settings | Set-Content -Encoding utf8NoBOM (Join-Path $target "settings-keys.txt") }
    Say "backed up $($rows.Count) files from $Config to $copy"
}

# -Serve: before -Launch. Starts the pretend GitHub in the background,
# serving the release -Prepare staged. It logs every request to
# server.log and stops by itself after -ServeHours.
if ($Serve) {
    if (-not (Test-Path (Join-Path $Work "releases"))) { throw "Nothing to serve yet: run -Prepare first." }
    if (Test-Server) {
        Say "the pretend GitHub already answers at $Url"
    } else {
        $arguments = @($Python | Select-Object -Skip 1) + @(
            "`"$(Join-Path $root 'scripts\release\pretend_github.py')`"",
            "--root", "`"$(Join-Path $Work 'releases')`"", "--port", "$Port",
            "--log", "`"$(Join-Path $Work 'server.log')`"", "--hours", "$ServeHours"
        )
        $process = Start-Process -FilePath $Python[0]-ArgumentList $arguments -WindowStyle Hidden -PassThru
        Set-Content -Path (Join-Path $Work "server.pid") -Value $process.Id
        $deadline = (Get-Date).AddSeconds(10)
        while (-not (Test-Server) -and (Get-Date) -lt $deadline -and -not $process.HasExited) { Start-Sleep -Milliseconds 250 }
        if (-not (Test-Server)) { throw "The pretend GitHub did not start (is port $Port taken?)." }
        Say "the pretend GitHub answers at $Url (pid $($process.Id), stops by itself in $ServeHours h)"
    }
    Say "  releases list: ${Url}repos/winters27/octo/releases"
    Say "  request log:   $(Join-Path $Work 'server.log')"
}

# -Install: with every Octo closed. Installs MSI A for this user, quietly,
# with a log, replacing the installed Octo (settings stay). Run again, it
# sees A is already there and does nothing.
if ($Install) {
    Assert-NoOcto "installing the rehearsal build"
    if (-not (Test-Path $MsiA)) { throw "No MSI A yet: run -Prepare first." }
    $installed = Get-InstalledOcto
    $want = [version]$MsiNumbersA
    $skip = $false
    if ($installed) {
        $have = [version]$installed.DisplayVersion
        if ($have -eq $want) {
            Say "rehearsal A (MSI $want) is already installed"
            $skip = $true
        } elseif ($have -gt $want) {
            throw "Octo $have is installed, newer than rehearsal A ($want), and Windows will not install an older version over it. Uninstall it first (Settings > Apps > Octo, or msiexec /x $($installed.PSChildName)); the settings stay. Then run -Install again."
        } else {
            Say "replacing the installed Octo $have with rehearsal A ($VersionA, MSI $want)"
        }
    }
    if (-not $skip) {
        $log = Join-Path $Work "install-A.log"
        $run = Start-Process msiexec.exe -ArgumentList "/i `"$MsiA`" /qn /norestart /l*v `"$log`"" -Wait -PassThru
        if ($run.ExitCode -notin 0, 3010) { throw "msiexec ended with $($run.ExitCode); see $log" }
        $installed = Get-InstalledOcto
        Say "installed: Octo $(Get-AppVersion (Get-InstallFolder)) (MSI $($installed.DisplayVersion)) in $(Get-InstallFolder); log $log"
    }
}

# -Launch: after -Serve and -Install. Opens the installed Octo pointed at
# the pretend GitHub (-Docto.updates.api through JAVA_TOOL_OPTIONS, for
# this start only). An installed Octo updates without the force flag.
if ($Launch) {
    Assert-NoOcto "opening the rehearsal build (a second Octo hands over to the first)"
    $exe = Join-Path (Get-InstallFolder) "Octo.exe"
    if (-not (Test-Path $exe)) { throw "No installed Octo at ${exe}: run -Install first." }
    if (-not (Test-Server)) { Say "the pretend GitHub does not answer at ${Url}: run -Serve, or Octo will find nothing" }
    # -Verify counts only what was fetched after the last launch.
    Add-Content -Path (Join-Path $Work "server.log") -Value "$(Get-Date -Format 'yyyy-MM-dd HH:mm:ss') rehearse -Launch" -Encoding utf8NoBOM
    $before = $env:JAVA_TOOL_OPTIONS
    $env:JAVA_TOOL_OPTIONS = "-Docto.updates.api=$Url"
    try { $launcher = Start-Process -FilePath $exe -PassThru } finally { $env:JAVA_TOOL_OPTIONS = $before }
    # Octo.exe is a launcher that starts the app as a second Octo.exe; the
    # app inherits the setting and does the checking.
    $deadline = (Get-Date).AddSeconds(30)
    $app = $null
    while (-not $app -and (Get-Date) -lt $deadline -and -not $launcher.HasExited) {
        $app = Get-CimInstance Win32_Process -Filter "ParentProcessId=$($launcher.Id) AND Name='Octo.exe'" | Select-Object -First 1
        if (-not $app) { Start-Sleep -Milliseconds 250 }
    }
    $pids = if ($app) { "launcher pid $($launcher.Id), app pid $($app.ProcessId)" } else { "launcher pid $($launcher.Id), no app process seen yet" }
    Say "opened $exe ($(Get-AppVersion (Get-InstallFolder))) against $Url ($pids)"
    Say "in Octo: Settings > About > Updates"
    Say "  1. turn on Try early versions (a rehearsal version counts as early)"
    Say "  2. Check now: 'Update ready: $VersionB' appears with its notes, and a dot sits beside Settings"
    Say "  3. Restart to update: Octo closes, the installer's progress bar shows, Octo opens again"
    Say "then run -Verify"
}

# -Verify: after the update (or to see how far it got). Says which version
# is installed, what Octo fetched from the pretend GitHub, how the
# update's installer ended, and what changed in %APPDATA%\Octo since the backup.
if ($Verify) {
    $folder = Get-InstallFolder
    $installed = Get-InstalledOcto
    $shown = Get-AppVersion $folder
    $state = switch ($shown) { $VersionB { "UPDATED to B" } $VersionA { "still A, not updated yet" } default { "neither rehearsal build" } }
    Say "installed: Octo $shown (MSI $($installed.DisplayVersion)) in ${folder}: $state"

    $log = Join-Path $Work "server.log"
    if (Test-Path $log) {
        $lines = @(Get-Content $log)
        $launched = @(0..($lines.Count - 1) | Where-Object { $lines[$_] -match ' rehearse -Launch$' })
        if ($launched) {
            Say "  since the last -Launch ($(($lines[$launched[-1]] -split ' ')[0..1] -join ' ')):"
            $lines = @($lines | Select-Object -Skip ($launched[-1] + 1))
        }
        foreach ($want in @(
            @{ Name = "release list"; Pattern = "GET /repos/winters27/octo/releases" },
            @{ Name = "update.json"; Pattern = "GET /assets/$TagB/update\.json " },
            @{ Name = "update.json.sig"; Pattern = "GET /assets/$TagB/update\.json\.sig " },
            @{ Name = $MsiNameB; Pattern = "GET /assets/$TagB/$([regex]::Escape($MsiNameB)) " }
        )) {
            $hits = @($lines | Where-Object { $_ -match $want.Pattern })
            $last = if ($hits) { " (last: $(($hits[-1] -split ' ')[0..1] -join ' '))" } else { "" }
            Say ("  fetched {0,-44} {1} time(s){2}" -f $want.Name, $hits.Count, $last)
        }
    } else {
        Say "  no server log yet at $log"
    }

    $updates = Join-Path $env:LOCALAPPDATA "Octo\Cache\updates"
    $download = Join-Path $updates $TagB
    if (Test-Path $download) {
        Get-ChildItem $download | ForEach-Object { Say ("  downloaded {0,-40} {1,12:N0} bytes" -f $_.Name, $_.Length) }
        $installLog = Join-Path $download "install.log"
        if (Test-Path $installLog) {
            $status = Select-String -Path $installLog -Pattern "Installation success or error status: (\d+)" | Select-Object -Last 1
            if ($status) { Say "  the update's installer ended with status $($status.Matches[0].Groups[1].Value) (0 is success): $installLog" }
        }
    } else {
        Say "  nothing downloaded at $download yet"
    }

    $saved = Get-NewestBackup
    if (-not $saved) {
        Say "  no backup to compare with (-Backup makes one)"
    } else {
        $before = @{}
        Import-Csv (Join-Path $saved "hashes.csv") | ForEach-Object { $before[$_.Path] = $_.Sha256 }
        $now = @{}
        if (Test-Path $Config) {
            foreach ($file in Get-ChildItem $Config -Recurse -File -Force) {
                $relative = [IO.Path]::GetRelativePath($Config, $file.FullName)
                $now[$relative] = try { (Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash } catch { "in use" }
            }
        }
        $changed = @($before.Keys | Where-Object { $now.ContainsKey($_) -and $now[$_] -ne $before[$_] } | Sort-Object)
        $gone = @($before.Keys | Where-Object { -not $now.ContainsKey($_) } | Sort-Object)
        $new = @($now.Keys | Where-Object { -not $before.ContainsKey($_) } | Sort-Object)
        Say "  %APPDATA%\Octo against $saved"
        Say "    unchanged $($before.Count - $changed.Count - $gone.Count), changed $($changed.Count), vanished $($gone.Count), new $($new.Count)"
        $changed | ForEach-Object { Say "    changed:  $_" }
        $gone | ForEach-Object { Say "    VANISHED: $_" }
        $new | ForEach-Object { Say "    new:      $_" }
        $keysBefore = Join-Path $saved "settings-keys.txt"
        $settings = Read-Settings (Join-Path $Config "settings.json")
        if ((Test-Path $keysBefore) -and $settings) {
            $had = @(Get-Content $keysBefore)
            $has = @(Get-JsonPaths $settings)
            $lost = @($had | Where-Object { $_ -notin $has })
            if ($lost.Count -eq 0) { Say "    settings.json still has all $($had.Count) keys it had" } else { $lost | ForEach-Object { Say "    settings key LOST: $_" } }
            @($has | Where-Object { $_ -notin $had }) | ForEach-Object { Say "    settings key added: $_" }
            if ($settings.updates) { Say "    updates: $($settings.updates | ConvertTo-Json -Compress)" }
        }
    }
}

# -Restore: only when the rehearsal left the settings wrong. With Octo
# closed, moves the current %APPDATA%\Octo aside (nothing is deleted) and
# copies the backup back in its place.
if ($Restore) {
    Assert-NoOcto "restoring the settings"
    $saved = Get-NewestBackup
    if (-not $saved) { throw "No backup in $Work to restore." }
    if (Test-Path $Config) {
        $aside = Join-Path $Work ("replaced-" + (Get-Date -Format "yyyyMMdd-HHmmss"))
        Move-Item -LiteralPath $Config -Destination $aside
        Say "moved the current settings aside to $aside"
    }
    Copy-Item -LiteralPath (Join-Path $saved "Octo") -Destination $Config -Recurse
    Say "restored $Config from $saved"
}
