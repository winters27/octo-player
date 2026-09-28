# Checks the Windows installer without installing it: unpacks the MSI into a
# folder (an administrative install, which changes nothing on the machine),
# then runs the app from there with the self-check, which plays a tone
# through the real player, reaches an https server and closes.
#
#   powershell -File scripts/check-msi.ps1
#   powershell -File scripts/check-msi.ps1 -Msi path\to\Octo-1.2.3.msi
param(
    [string]$Msi = "",
    [int]$TimeoutSeconds = 90
)
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
if (-not $Msi) {
    $Msi = Get-ChildItem "$root\desktop\build\compose\binaries\main\msi\*.msi" |
        Sort-Object LastWriteTime -Descending | Select-Object -First 1 -ExpandProperty FullName
}
if (-not $Msi -or -not (Test-Path $Msi)) { throw "No MSI found. Build one with ./gradlew :desktop:packageMsi" }

$work = Join-Path $root "desktop\build\check\msi"
if (Test-Path $work) { Remove-Item -Recurse -Force $work -Confirm:$false }
New-Item -ItemType Directory -Force $work | Out-Null
$unpacked = Join-Path $work "unpacked"

$unpack = Start-Process msiexec.exe -ArgumentList "/a `"$Msi`" /qn TARGETDIR=`"$unpacked`"" -Wait -PassThru
if ($unpack.ExitCode -ne 0) { throw "Unpacking the MSI failed ($($unpack.ExitCode))" }
$exe = Get-ChildItem -Recurse $unpacked -Filter Octo.exe | Select-Object -First 1
if (-not $exe) { throw "No Octo.exe in the MSI" }

Add-Type -AssemblyName System.IO.Compression.FileSystem
foreach ($library in "octo_audio.dll", "octo_system.dll") {
    $found = Get-ChildItem -Recurse "$unpacked\*\app\*.jar" | Where-Object {
        $jar = [System.IO.Compression.ZipFile]::OpenRead($_.FullName)
        try { $jar.Entries | Where-Object { $_.Name -eq $library } } finally { $jar.Dispose() }
    }
    if ($found) { "packed: $library" } else { "MISSING: $library" }
}

$tone = Join-Path $root "desktop\build\check\tone.wav"
$env:JAVA_TOOL_OPTIONS = "-Docto.checkPlay=$tone"
$out = Join-Path $work "run.out"
$run = Start-Process $exe.FullName -PassThru -RedirectStandardOutput $out -RedirectStandardError (Join-Path $work "run.err")
if (-not $run.WaitForExit($TimeoutSeconds * 1000)) {
    Stop-Process $run -Confirm:$false
    throw "The packaged app did not finish its check in $TimeoutSeconds s"
}
Remove-Item Env:JAVA_TOOL_OPTIONS
$lines = Get-Content $out | Where-Object { $_ -match "^check: (version|sound|https|FAILED)" }
$lines
"MSI: $Msi ($([math]::Round((Get-Item $Msi).Length / 1MB, 1)) MB)"
if (($lines -match "FAILED") -or ($lines.Count -lt 3)) { exit 1 }
