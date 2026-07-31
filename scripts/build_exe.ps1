# Gera um executável de arquivo único diretamente na raiz do projeto.
# Os arquivos intermediários ficam no diretório temporário do Windows para que
# as pastas "build" e "dist" nunca sejam criadas ao lado do aplicativo.

$ErrorActionPreference = 'Stop'

# Resolve caminhos a partir deste script, independentemente da pasta atual.
$projectRoot = Split-Path -Parent $PSScriptRoot
$temporaryRoot = Join-Path ([System.IO.Path]::GetTempPath()) 'biblia_acessivel_pyinstaller'
$workPath = Join-Path $temporaryRoot 'build'
$specPath = Join-Path $temporaryRoot 'spec'
$entryPoint = Join-Path $projectRoot 'app.py'

# PyInstaller deve estar instalado somente no ambiente usado para compilar.
python -m PyInstaller `
    --noconfirm `
    --clean `
    --onefile `
    --windowed `
    --name BibliaAcessivel `
    --distpath $projectRoot `
    --workpath $workPath `
    --specpath $specPath `
    $entryPoint

# Confirma de forma clara onde o resultado foi colocado.
$executable = Join-Path $projectRoot 'BibliaAcessivel.exe'
if (-not (Test-Path -LiteralPath $executable)) {
    throw 'O executável não foi criado.'
}
Write-Output "Executável criado em: $executable"

