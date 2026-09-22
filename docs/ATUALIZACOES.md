# Publicação e teste de atualizações

Este documento descreve o canal Windows. Tags Android e assets APK são ignorados pelo Windows. Consulte também [ATUALIZACOES_ANDROID.md](ATUALIZACOES_ANDROID.md).

## Padrão obrigatório

A versão está em um único lugar: `biblia/version.py`. Para publicar `2.1.0`, altere `APP_VERSION` para `2.1.0`, execute os testes, faça commit e crie a tag `v2.1.0`. Use sempre versionamento semântico `MAJOR.MINOR.PATCH`.

Uma release estável precisa conter exatamente:

- `BibliaAcessivel.exe` para download direto;
- `BibliaAcessivel-Windows.zip` para o atualizador;
- `BibliaAcessivel-Windows.zip.sha256` para integridade.

Drafts, prereleases e pacotes sem SHA-256 não são oferecidos no canal estável. O texto da descrição da release vira a lista de novidades. Para uma atualização obrigatória excepcional, inclua `<!-- mandatory: true -->` na descrição; atualizações comuns nunca devem usar essa marca.

## Gerar localmente

```powershell
python -m pip install -r requirements-build.txt
python -m unittest discover -s tests -v
powershell -ExecutionPolicy Bypass -File scripts\build_exe.ps1
powershell -ExecutionPolicy Bypass -File scripts\package_release.ps1
```

O segundo script produz o ZIP e o `.sha256`. O workflow `release-windows.yml` repete essas etapas ao receber uma tag `v*` e interrompe a publicação se a versão não corresponder à tag ou se algum teste falhar.

## Testar sem publicar

Os testes automatizados simulam releases, rede, cancelamento e hash, sem fazer download real:

```powershell
python -m unittest tests.test_update_service -v
```

Para um teste completo, publique uma release posterior num repositório de teste, ajuste temporariamente `RELEASE_REPOSITORY`, execute o EXE e use Ajuda > Verificar atualizações. Não use uma URL digitada pelo usuário: o serviço aceita somente origens GitHub e o nome fixo do pacote.

## Desativar temporariamente

Abra Mais opções > Configurações > Atualizações automáticas, escolha Desativada e salve. A consulta manual em Ajuda continua disponível. Para testes automatizados do código-fonte, a variável `BIBLIA_DISABLE_AUTO_UPDATE=1` impede o agendamento automático.

## Dados e falhas

O banco pessoal, logs, downloads e backups ficam em `%APPDATA%\BibliaAcessivel`. A instalação substitui somente o conteúdo da pasta do programa. Os cinco backups pré-atualização mais recentes são mantidos. Falha de internet, cancelamento, pacote incompleto, hash divergente ou falha ao iniciar o helper preservam a versão atual e produzem mensagem simples; detalhes técnicos ficam apenas no log.

O helper é iniciado com a busca de DLLs do Windows restaurada e sem o estado privado do PyInstaller. Antes de reabrir o aplicativo, ele define `PYINSTALLER_RESET_ENVIRONMENT=1`; assim, uma compilação `onefile` nova não reutiliza a pasta `_MEI` que pertence à versão encerrada.
