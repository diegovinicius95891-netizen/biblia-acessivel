# Bíblia AGB (Audio Games Brasil) para Android

Aplicativo Android nativo, sem Python embutido, compatível com Android 8 ou superior. A interface usa controles padrão do sistema para oferecer navegação previsível com TalkBack e toque.

## Recursos incluídos

- Bíblia offline com as mesmas traduções do aplicativo para Windows.
- Tela inicial curta: abrir o capítulo atual, trocar de livro ou abrir Mais opções.
- A posição exata fica salva; ao escolher um capítulo, é possível começar no início ou escolher o versículo.
- Capítulo anterior e Próximo capítulo aparecem no início da leitura. Um gesto horizontal de dois dedos também troca o capítulo e o TalkBack anuncia o destino.
- Com o TalkBack, deslizar para a direita ou esquerda percorre as opções e o toque duplo abre a opção anunciada.
- Diálogos de escolha e telas novas estabilizam o foco depois que o conteúdo aparece, colocando o TalkBack na primeira opção utilizável.
- O gesto de dois dedos na leitura trata tanto os eventos multitoque normais quanto o fluxo transformado pela exploração por toque do TalkBack.
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
- Modo culto disponível desde livros, capítulos e versículos, com busca por texto ou voz. Todas as famílias numeradas — Samuel, Reis, Crônicas, Coríntios, Tessalonicenses, Timóteo, Pedro e João — aceitam nomes, ordinais e intervalos; buscas ambíguas abrem uma lista acessível.

O backup automático do aplicativo está desativado. Notas, marcadores e chave de API permanecem no armazenamento privado do aplicativo e não fazem parte do APK.

O APK de produção é reduzido e ofuscado pelo R8, verifica o certificado oficial ao abrir e atualiza o banco bíblico por troca atômica somente depois de validar traduções, livros e capítulos. Isso corrige instalações antigas que mantinham uma cópia desatualizada do banco.

## Atualizações

O Android é atualizado somente pela Google Play. O atualizador próprio pelo GitHub foi removido.

## Compilar

Requisitos: JDK 17 e Android SDK. Para testar:

```powershell
cd android
.\gradlew.bat testDebugUnitTest
```

Para gerar o AAB assinado da Play, use `.\build_play.ps1 -KeyDirectory C:\Winvox\Treino\chaves-play-biblia` (veja [PLAY_STORE.md](PLAY_STORE.md)). O banco `data/biblia.db` é copiado para os assets durante a compilação.
