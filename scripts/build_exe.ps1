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
$buildPython = if ($env:BIBLIA_BUILD_PYTHON) {
    $env:BIBLIA_BUILD_PYTHON
} else {
    (Get-Command python -ErrorAction Stop).Source
}

# Impede a criação silenciosa de um EXE com uma combinação Qt já conhecida
# por falhar depois de congelada. O workflow instala estes mesmos pinos.
$buildVersions = & $buildPython -c "import PySide6, PyInstaller; print(f'{PySide6.__version__}|{PyInstaller.__version__}')"
if ($LASTEXITCODE -ne 0 -or $buildVersions.Trim() -ne '6.8.3|6.21.0') {
    throw "Ambiente de compilação incompatível: $buildVersions. Instale requirements-build.txt ou defina BIBLIA_BUILD_PYTHON."
}

# PyInstaller deve estar instalado somente no ambiente usado para compilar.
& $buildPython -m PyInstaller `
    --noconfirm `
    --clean `
    --onefile `
    --windowed `
    --name BibliaAcessivel `
    --distpath $projectRoot `
    --workpath $workPath `
    --specpath $specPath `
    $entryPoint

# O PowerShell não interrompe automaticamente quando um programa externo
# devolve erro. Esta verificação impede que um EXE antigo seja confundido com
# uma compilação bem-sucedida, por exemplo quando o aplicativo está aberto.
if ($LASTEXITCODE -ne 0) {
    throw "O PyInstaller falhou com o código $LASTEXITCODE. Feche o aplicativo e tente novamente."
}

# Confirma de forma clara onde o resultado foi colocado.
$executable = Join-Path $projectRoot 'BibliaAcessivel.exe'
if (-not (Test-Path -LiteralPath $executable)) {
    throw 'O executável não foi criado.'
}
Write-Output "Executável criado em: $executable"
