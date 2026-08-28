# Monta um ZIP de distribuição contendo somente o necessário para o usuário.
# Bancos pessoais, backups, código-fonte e arquivos intermediários não entram.

$ErrorActionPreference = 'Stop'

# Resolve os caminhos de forma independente do diretório usado para executar.
$projectRoot = Split-Path -Parent $PSScriptRoot
$temporaryBase = [System.IO.Path]::GetFullPath([System.IO.Path]::GetTempPath())
$packageRoot = Join-Path $temporaryBase ("biblia_acessivel_release_" + [guid]::NewGuid().ToString('N'))
$bundleRoot = Join-Path $packageRoot 'BibliaAcessivel'
$destination = Join-Path $projectRoot 'BibliaAcessivel-Windows.zip'
$checksumDestination = "$destination.sha256"

# Impede qualquer limpeza fora da pasta temporária criada por este script.
$resolvedPackageRoot = [System.IO.Path]::GetFullPath($packageRoot)
if (-not $resolvedPackageRoot.StartsWith($temporaryBase, [System.StringComparison]::OrdinalIgnoreCase)) {
    throw 'A pasta temporária de empacotamento não está em um local seguro.'
}

try {
    New-Item -ItemType Directory -Path (Join-Path $bundleRoot 'data') -Force | Out-Null

    # Estes quatro arquivos formam o pacote mínimo executável e documentado.
    Copy-Item -LiteralPath (Join-Path $projectRoot 'BibliaAcessivel.exe') -Destination $bundleRoot
    Copy-Item -LiteralPath (Join-Path $projectRoot 'LEIA-ME.txt') -Destination $bundleRoot
    Copy-Item -LiteralPath (Join-Path $projectRoot 'THIRD_PARTY_NOTICES.md') -Destination $bundleRoot
    Copy-Item -LiteralPath (Join-Path $projectRoot 'data\biblia.db') -Destination (Join-Path $bundleRoot 'data')
    Copy-Item -LiteralPath (Join-Path $projectRoot 'data\harpa_crista.json') -Destination (Join-Path $bundleRoot 'data')
    Copy-Item -LiteralPath (Join-Path $projectRoot 'data\quiz_questions.tsv') -Destination (Join-Path $bundleRoot 'data')

    Compress-Archive -LiteralPath $bundleRoot -DestinationPath $destination -CompressionLevel Optimal -Force
}
finally {
    if (Test-Path -LiteralPath $resolvedPackageRoot) {
        Remove-Item -LiteralPath $resolvedPackageRoot -Recurse -Force
    }
}

if (-not (Test-Path -LiteralPath $destination)) {
    throw 'O arquivo ZIP não foi criado.'
}

$checksum = (Get-FileHash -LiteralPath $destination -Algorithm SHA256).Hash.ToLowerInvariant()
Set-Content -LiteralPath $checksumDestination -Value "$checksum  BibliaAcessivel-Windows.zip" -Encoding ascii

Write-Output "Pacote criado em: $destination"
Write-Output "SHA-256 criado em: $checksumDestination"
