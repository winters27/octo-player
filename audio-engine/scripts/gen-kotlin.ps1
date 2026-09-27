# Builds the engine and writes its Kotlin bindings to bindings/kotlin (Windows).
$ErrorActionPreference = 'Stop'
Set-Location (Join-Path $PSScriptRoot '..')

cargo build --release --lib
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

$lib = 'target/release/octo_audio.dll'
cargo run --release --features bindgen --bin uniffi-bindgen -- `
    generate --library $lib --language kotlin --out-dir bindings/kotlin --no-format
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

# The generator's own comments use long dashes; the repo style has none.
Get-ChildItem bindings/kotlin -Recurse -Filter *.kt | ForEach-Object {
    $text = [IO.File]::ReadAllText($_.FullName)
    [IO.File]::WriteAllText($_.FullName, $text.Replace(" $([char]0x2014) ", ', '))
}

Write-Output "Kotlin bindings written to bindings/kotlin; native library at $lib"
