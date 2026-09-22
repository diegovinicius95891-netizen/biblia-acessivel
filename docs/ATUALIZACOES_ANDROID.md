# Publicação e teste de atualizações Android

## Separação dos canais

O Android usa tags `android-vMAJOR.MINOR.PATCH` e aceita somente:

- `BibliaAcessivel-Android.apk`;
- `BibliaAcessivel-Android.apk.sha256`.

O Windows usa tags `vMAJOR.MINOR.PATCH` e aceita somente seu ZIP e SHA-256. Dessa forma, nenhum dispositivo Android baixa EXE ou ZIP e nenhum computador Windows baixa APK.

## Segurança e preservação

A consulta usa a API pública do GitHub, ignora drafts e prereleases no canal estável, possui timeout e ocorre no máximo uma vez por dia. O APK é conferido por URL, nome, tamanho e SHA-256. Antes de abrir o instalador oficial do sistema, o banco pessoal recebe backup; somente os cinco mais recentes são mantidos.

O `applicationId` permanece `com.bibliaacessivel.app`. O workflow exige a mesma identidade de assinatura usada nas versões 1.0.0 e 1.0.1 e valida a impressão pública `99d543111b3de0a4bf7acc1f16a3760351d1827515b89b86df5c0ce815e6822a`. A chave privada fica exclusivamente nos GitHub Secrets.

## Gerar localmente

Use Android SDK 35 e JDK 17. Dentro da pasta `android`, execute `gradlew.bat testDebugUnitTest lintRelease assembleRelease`. Depois, na raiz, execute `scripts/package_android_release.ps1`.

Confira o APK com `apksigner verify --verbose --print-certs BibliaAcessivel-Android.apk`. Para instalar num aparelho conectado, use `adb install -r BibliaAcessivel-Android.apk`; a opção `-r` testa a atualização preservando os dados.

## Publicar

Altere `versionCode` e `versionName` em `android/app/build.gradle`, faça commit e crie a tag correspondente, por exemplo `android-v2.1.0`. O workflow `.github/workflows/release-android.yml` executa testes e lint, recompila, verifica o certificado, produz o SHA-256 e anexa os dois assets à release.

Os secrets necessários são:

- `ANDROID_KEYSTORE_BASE64`;
- `ANDROID_RELEASE_STORE_PASSWORD`;
- `ANDROID_RELEASE_KEY_ALIAS`;
- `ANDROID_RELEASE_KEY_PASSWORD`.

## Desativar temporariamente

No aplicativo, abra Mais opções > Configurações > Alternar atualizações automáticas. A consulta manual permanece em Mais opções > Atualizações. As notificações também podem ser desligadas separadamente.
