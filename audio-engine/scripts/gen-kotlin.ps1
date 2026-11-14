# Builds the engine and writes its Kotlin bindings to bindings/kotlin, or to
# -OutDir (Windows). A relative -OutDir is taken from where the script starts.
param([string]$OutDir = '')
$ErrorActionPreference = 'Stop'
if ($OutDir) { $OutDir = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($OutDir) }
Set-Location (Join-Path $PSScriptRoot '..')
if (-not $OutDir) { $OutDir = 'bindings/kotlin' }

cargo build --release --lib
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

$lib = 'target/release/octo_audio.dll'
New-Item -ItemType Directory -Force $OutDir | Out-Null
cargo run --release --features bindgen --bin uniffi-bindgen -- `
    generate --library $lib --language kotlin --out-dir $OutDir --no-format
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

# The generator's own comments use long dashes; the repo style has none.
Get-ChildItem $OutDir -Recurse -Filter *.kt | ForEach-Object {
    $text = [IO.File]::ReadAllText($_.FullName)
    [IO.File]::WriteAllText($_.FullName, $text.Replace(" $([char]0x2014) ", ', '))
}

Write-Output "Kotlin bindings written to $OutDir; native library at $lib"
