# Versão para Google Play

Versão 4.5.3, código 23, pacote com.bibliaacessivel.app. Requer JDK 17 e SDK Android 36.

Execute `./build_play.ps1 -KeyDirectory CAMINHO_DA_PASTA_ASSINATURA` no PowerShell.
A pasta de assinatura contém biblia-play-upload.jks e credenciais.json. Guarde esses arquivos fora do repositório.
O resultado fica em app/build/outputs/bundle/release/app-release.aab.

A propriedade -PplayStore=true remove os menus e chamadas de atualização do GitHub.
O R8 elimina UpdateManager, AndroidUpdateService e a conferência do certificado antigo.
O manifesto Play não contém REQUEST_INSTALL_PACKAGES nem o provedor de APKs.
A assinatura de distribuição é gerenciada pelo Play App Signing; a chave local é a chave de upload.
Sem ANDROID_RELEASE_KEYSTORE, o bundle Play fica sem assinatura, nunca com a chave debug.
O canal GitHub continua disponível na compilação sem a propriedade playStore.

A lista de temas agora retorna diretamente a Estudos. O diálogo de escopo é aberto a partir de Estudos;
Cancelar ou voltar no diálogo mantém essa tela. StudyNavigationTest cobre ambos os escopos.
O botão Voltar também registra o callback do Android 13+ para suportar o Android 16.
