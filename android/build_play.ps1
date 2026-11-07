param([Parameter(Mandatory=$true)][string]$KeyDirectory)
$ErrorActionPreference = 'Stop'
$credentials = Get-Content -LiteralPath (Join-Path $KeyDirectory 'credenciais.json') -Raw | ConvertFrom-Json
$names = @('ANDROID_RELEASE_KEYSTORE', 'ANDROID_RELEASE_STORE_PASSWORD', 'ANDROID_RELEASE_KEY_ALIAS', 'ANDROID_RELEASE_KEY_PASSWORD')
$previous = @{}
foreach ($name in $names) { $previous[$name] = [Environment]::GetEnvironmentVariable($name, 'Process') }
try {
    $env:ANDROID_RELEASE_KEYSTORE = (Resolve-Path -LiteralPath (Join-Path $KeyDirectory 'biblia-play-upload.jks')).Path
    $env:ANDROID_RELEASE_STORE_PASSWORD = $credentials.storePassword
    $env:ANDROID_RELEASE_KEY_ALIAS = $credentials.keyAlias
    $env:ANDROID_RELEASE_KEY_PASSWORD = $credentials.keyPassword
    Push-Location $PSScriptRoot
    try {
        & .\gradlew.bat -PplayStore=true testDebugUnitTest lintRelease bundleRelease
        if ($LASTEXITCODE -ne 0) { throw 'Falha nos testes ou na compilação para a Play.' }
    } finally { Pop-Location }
} finally {
    foreach ($name in $names) { [Environment]::SetEnvironmentVariable($name, $previous[$name], 'Process') }
}
