# Bíblia Acessível para Android

Aplicativo Android nativo, sem Python embutido, compatível com Android 8 ou superior. A interface usa controles padrão do sistema para oferecer navegação previsível com TalkBack, toque e teclado externo.

## Recursos incluídos

- Bíblia offline com as quatro traduções do aplicativo desktop.
- Tela principal com Livros, Capítulos, Versículos, Área de leitura e Mais opções.
- Antigo e Novo Testamento, continuidade da última posição e pesquisa offline.
- Aplicações do livro, capítulo e versículo, mantendo a ação de IA em primeiro lugar.
- Notas locais organizadas por dia e título e marcadores de versículos.
- Devocional com troca de referência e salvamento pelo seletor de documentos do Android.
- Vozes instaladas no Android, ajuste de velocidade e opção de voz desativada.
- Quando a voz está desativada, ativar o versículo solicita um novo anúncio ao TalkBack.
- Chave própria do Google Gemini protegida com Android Keystore.
- Leis, licenças, justificativa de uso e ajuda dentro do aplicativo.

O backup automático do aplicativo está desativado. Notas, marcadores e chave de API permanecem no armazenamento privado do aplicativo e não fazem parte do APK.

## Compilar

Requisitos: JDK 17, Android SDK Platform 35 e Build Tools. Defina `JAVA_HOME` e `ANDROID_HOME`, depois execute:

```powershell
cd android
.\gradlew.bat clean testDebugUnitTest lintDebug assembleDebug
```

O banco `data/biblia.db` é copiado para os assets durante a compilação; não existe uma segunda cópia versionada no código Android. O APK de teste é criado em `android/app/build/outputs/apk/debug/app-debug.apk`.

O APK debug é adequado para instalação e testes diretos. Uma publicação na Play Store exige gerar uma chave de assinatura de produção e um Android App Bundle assinado.
