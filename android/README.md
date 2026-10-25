# Bíblia Acessível para Android

Aplicativo Android nativo, sem Python embutido, compatível com Android 8 ou superior. A interface usa controles padrão do sistema para oferecer navegação previsível com TalkBack e toque.

## Recursos incluídos

- Bíblia offline com as cinco traduções completas do aplicativo desktop.
- Tela inicial curta: abrir o capítulo atual, trocar de livro ou abrir Mais opções.
- A posição exata fica salva; ao escolher um capítulo, é possível começar no início ou escolher o versículo.
- Capítulo anterior e Próximo capítulo aparecem no início da leitura. Um gesto horizontal de dois dedos também troca o capítulo e o TalkBack anuncia o destino.
- Com o TalkBack, deslizar para a direita ou esquerda percorre as opções e o toque duplo abre a opção anunciada.
- Ao fim dos versículos, há uma opção para abrir o próximo capítulo; no último capítulo, o fim do livro é anunciado.
- Antigo e Novo Testamento, continuidade da última posição e pesquisa offline.
- Caixa de combinação para a tradução padrão e outra para escolher Antigo ou Novo Testamento, com feedback pelo TalkBack.
- Comparação do versículo atual com origem, destino e comando para inverter as duas traduções.
- Um toque no versículo abre suas ações; com TalkBack, use o toque duplo normal, sem gesto de segurar.
- Aplicações do livro, capítulo e versículo, mantendo a ação de IA em primeiro lugar.
- Seção Estudos com resumos de capítulo e livro, Teólogo de IA, categorias prontas e pergunta livre editável usando a chave Gemini do usuário.
- Notas locais organizadas por dia e título e marcadores de versículos.
- Devocional com troca de referência e salvamento pelo seletor de documentos do Android.
- Vozes instaladas no Android, ajuste de velocidade e opção de voz desativada.
- Quando a voz está desativada, ativar o versículo solicita um novo anúncio ao TalkBack.
- Chave própria do Google Gemini protegida com Android Keystore.
- A opção Obter chave da API do Google abre diretamente o Google AI Studio.
- Leis, licenças, justificativa de uso e ajuda dentro do aplicativo.
- Harpa Cristã com 640 hinos e pesquisa offline por número, título ou trecho.
- Quiz bíblico com 298 perguntas de estudo e Status do quiz com acertos, erros e aproveitamento.
- Cada pergunta conta somente uma vez; o botão confirmado Recomeçar quiz do zero é a única forma de liberar todas novamente.
- Modo culto disponível desde livros, capítulos e versículos, com busca por texto ou voz. Aceita João 3:16, Pedro 2 5 e Pedro 2 5 15; nomes ambíguos abrem uma escolha acessível.
- Atualizador Android próprio, com consulta diária opcional, download cancelável, SHA-256 e backup antes da instalação.

O backup automático do aplicativo está desativado. Notas, marcadores e chave de API permanecem no armazenamento privado do aplicativo e não fazem parte do APK.

O APK de produção é reduzido e ofuscado pelo R8, verifica o certificado oficial ao abrir e atualiza o banco bíblico por troca atômica somente depois de validar traduções, livros e capítulos. Isso corrige instalações antigas que mantinham uma cópia desatualizada do banco.

## Atualizações separadas

O Android consulta somente releases com tag `android-vMAJOR.MINOR.PATCH`. Ele aceita exclusivamente os assets `BibliaAcessivel-Android.apk` e `BibliaAcessivel-Android.apk.sha256`. Releases `vMAJOR.MINOR.PATCH` e o pacote ZIP pertencem ao Windows e são ignorados.

Depois de validar o APK, o aplicativo abre o instalador oficial do Android. Na primeira utilização, o sistema pode pedir autorização para instalar aplicativos desta fonte. A atualização usa o mesmo identificador e certificado das versões Android anteriores, preservando os dados privados.

## Compilar

Requisitos: JDK 17, Android SDK Platform 35 e Build Tools. Defina `JAVA_HOME` e `ANDROID_HOME`, depois execute:

```powershell
cd android
.\gradlew.bat testDebugUnitTest lintRelease assembleRelease
powershell -ExecutionPolicy Bypass -File ..\scripts\package_android_release.ps1
```

O banco `data/biblia.db` é copiado para os assets durante a compilação; não existe uma segunda cópia versionada no código Android. O APK assinado é criado em `android/app/build/outputs/apk/release/app-release.apk` e o script o copia para a raiz com o nome reconhecido pelo atualizador e seu SHA-256.

O workflow de release reutiliza de forma protegida o certificado das versões anteriores. A impressão SHA-256 pública é conferida antes da publicação, evitando um APK incompatível com a atualização instalada. Consulte [Atualizações Android](../docs/ATUALIZACOES_ANDROID.md).
