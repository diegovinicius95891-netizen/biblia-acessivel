# Copia o APK Android assinado para a raiz e publica seu SHA-256 correspondente.
# Recusa qualquer APK que não use o certificado oficial: os aparelhos já instalados
# só aceitam atualizações assinadas por ele, e o AppIntegrity confere o mesmo valor.

$ErrorActionPreference = 'Stop'

$expectedCertificate = '99d543111b3de0a4bf7acc1f16a3760351d1827515b89b86df5c0ce815e6822a'
$projectRoot = Split-Path -Parent $PSScriptRoot
$source = Join-Path $projectRoot 'android\app\build\outputs\apk\release\app-release.apk'
$destination = Join-Path $projectRoot 'BibliaAcessivel-Android.apk'
$checksumDestination = "$destination.sha256"

if (-not (Test-Path -LiteralPath $source)) {
    throw 'O APK release não foi encontrado. Execute assembleRelease primeiro.'
}

$sdk = $env:ANDROID_HOME
if (-not $sdk) { $sdk = Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
$apksigner = Get-ChildItem -Path (Join-Path $sdk 'build-tools') -Filter 'apksigner.bat' -Recurse |
    Sort-Object FullName -Descending | Select-Object -First 1
if (-not $apksigner) { throw 'O apksigner do Android SDK não foi encontrado.' }

$certificates = & $apksigner.FullName verify --print-certs $source
if ($LASTEXITCODE -ne 0) { throw 'A assinatura do APK release é inválida.' }
$fingerprint = ($certificates | Select-String 'SHA-256 digest: (\S+)').Matches[0].Groups[1].Value.ToLowerInvariant()
if ($fingerprint -ne $expectedCertificate) {
    throw "O APK foi assinado com outro certificado ($fingerprint). Restaure a chave oficial antes de publicar."
}

Copy-Item -LiteralPath $source -Destination $destination -Force
$checksum = (Get-FileHash -LiteralPath $destination -Algorithm SHA256).Hash.ToLowerInvariant()
Set-Content -LiteralPath $checksumDestination -Value "$checksum  BibliaAcessivel-Android.apk" -Encoding ascii

Write-Output "Certificado oficial confirmado: $fingerprint"
Write-Output "APK criado em: $destination"
Write-Output "SHA-256 criado em: $checksumDestination"
