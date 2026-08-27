# Copia o APK Android assinado para a raiz e publica seu SHA-256 correspondente.

$ErrorActionPreference = 'Stop'

$projectRoot = Split-Path -Parent $PSScriptRoot
$source = Join-Path $projectRoot 'android\app\build\outputs\apk\release\app-release.apk'
$destination = Join-Path $projectRoot 'BibliaAcessivel-Android.apk'
$checksumDestination = "$destination.sha256"

if (-not (Test-Path -LiteralPath $source)) {
    throw 'O APK release não foi encontrado. Execute assembleRelease primeiro.'
}

Copy-Item -LiteralPath $source -Destination $destination -Force
$checksum = (Get-FileHash -LiteralPath $destination -Algorithm SHA256).Hash.ToLowerInvariant()
Set-Content -LiteralPath $checksumDestination -Value "$checksum  BibliaAcessivel-Android.apk" -Encoding ascii

Write-Output "APK criado em: $destination"
Write-Output "SHA-256 criado em: $checksumDestination"
