# Remakes the PKCS#12 files EngineKeyTest opens: one key of each kind the
# audio engine signs with, each with its own client certificate valid for
# 100 years, made by keytool so each key is encoded the way the JDK encodes
# it. Then run EngineKeyTest once with OCTO_WRITE_ENGINE_KEYS=1 to write the
# .key.der and .leaf.der files the engine's own tests read.
$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot

$keytool = 'keytool'
if ($env:JAVA_HOME) { $keytool = Join-Path (Join-Path $env:JAVA_HOME 'bin') 'keytool' }

$kinds = [ordered]@{
    'rsa-2048' = @('-keyalg', 'RSA', '-keysize', '2048')
    'ec-p256'  = @('-keyalg', 'EC', '-groupname', 'secp256r1')
    'ec-p384'  = @('-keyalg', 'EC', '-groupname', 'secp384r1')
    'ed25519'  = @('-keyalg', 'Ed25519')
}
foreach ($name in $kinds.Keys) {
    $file = "$name.p12"
    if (Test-Path $file) { Remove-Item $file }
    $alg = $kinds[$name]
    & $keytool -genkeypair -alias octo @alg -dname "CN=Octo engine key $name" -validity 36500 `
        -ext 'BC=ca:false' -ext 'KU=digitalSignature' -ext 'EKU=clientAuth' `
        -storetype PKCS12 -keystore $file -storepass octo-test -keypass octo-test
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
}
Write-Output 'PKCS#12 files made; now run EngineKeyTest with OCTO_WRITE_ENGINE_KEYS=1.'
