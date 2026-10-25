# Bíblia Acessível

Versões atuais: **4.3.1 para Windows** e **4.3.2 para Android**.

Em Mais opções, **Devocionais novos do site** consulta diariamente os textos publicados em
`audiogames.com.br/diego/devocionais`, mantém o histórico para leitura offline e avisa somente
quando encontra uma publicação nova. No Android, a verificação usa o agendador do sistema e a
notificação abre diretamente o histórico; no Windows, a consulta ocorre na abertura e enquanto
o aplicativo estiver em execução. A preferência pode ser desligada em Configurações.

Aplicativo desktop em Python para leitura acessível da Bíblia. A leitura, a pesquisa e a voz funcionam offline; somente o recurso opcional de IA exige internet.

O repositório também contém uma versão Android nativa em `android/`. Ela reutiliza o mesmo banco bíblico, funciona a partir do Android 8 e foi construída com controles padrão reconhecidos pelo TalkBack. Windows e Android possuem canais de atualização separados: tags `v...` distribuem ZIP Windows e tags `android-v...` distribuem somente APK Android. Consulte [android/README.md](android/README.md).

## Iniciar

Dê duplo clique em `BibliaAcessivel.exe`, localizado na pasta principal. O executável não exige uma instalação separada do Python.

O diretório `data`, contendo `biblia.db`, `harpa_crista.json` e `quiz_questions.tsv`, deve permanecer ao lado do executável. Dados pessoais ficam em `%APPDATA%\BibliaAcessivel\user_data.db`. Na primeira execução da versão 2, uma base antiga encontrada ao lado do programa é copiada com segurança para esse local e preservada.

O banco distribuído contém cinco textos completos identificados separadamente:

- Bíblia Portuguesa Mundial — domínio público conforme eBible.org.
- João Ferreira de Almeida — domínio público; arquivo estruturado do Open Bibles.
- Bíblia Livre — licença Creative Commons Atribuição 3.0 Brasil.
- Biblica Open Nova Bíblia Viva — licença Creative Commons Atribuição-CompartilhaIgual 4.0 Internacional.
- World English Bible — domínio público.

As licenças, fontes, atribuições, artigos relevantes da Lei nº 9.610/1998 e a explicação jurídica ficam disponíveis na seção **Licenças e leis** do próprio aplicativo.

## Acessibilidade

- Controles com nomes e descrições acessíveis.
- No Windows, a abertura mostra um menu com Livros, Capítulos, Versículos e Mais opções; cada item abre sozinho em uma nova tela.
- Alt+1 abre Livros, Alt+2 abre Capítulos, Alt+3 abre Versículos e Alt+4 abre Mais opções. Cima e baixo navegam nas listas e Escape volta.
- Versículos oferece botões de capítulo anterior, próximo capítulo e voltar. Toda troca anuncia a direção e o novo capítulo; Mais opções também oferece Sair do aplicativo.
- Mais opções reúne os recursos pessoais e de estudo em telas internas limpas; Escape volta à Bíblia.
- A tela Traduções usa uma caixa de combinação acessível para escolher a edição padrão.
- No Windows, a seção Livros abre diretamente na lista: esquerda mostra o Antigo Testamento e direita mostra o Novo, com anúncio da seleção e da quantidade de livros. No Android, a escolha de testamento permanece explícita para facilitar o uso pelo toque.
- Enter em Livros leva aos capítulos; Enter em Capítulos abre a seção Versículos.
- Lista de leitura adequada para navegação linha a linha com NVDA, JAWS ou Narrador.
- Na seção Versículos, o texto bíblico fica diretamente na lista; esquerda volta um capítulo e direita avança um capítulo do mesmo livro.
- Depois do último item aparece “Fim do capítulo” ou “Fim do livro. Não há capítulos seguintes”.
- Ajuda e Leis abrem em telas próprias como listas de parágrafos; cima/baixo lê o conteúdo sem repetir o nome da seção.
- Tecla Aplicações ou Shift+F10 abre “Menu do livro”, “Menu do capítulo” ou “Menu do versículo”. A ação de IA correspondente recebe o foco inicial; cima/baixo navegam e Espaço ou Enter executa. As demais ações do versículo continuam disponíveis.
- O menu `Item atual`, acessível com `Alt+I`, oferece as mesmas operações.
- `Ctrl+F` pesquisa; `Ctrl+G` abre uma passagem; `Ctrl+D` alterna favorito; `Ctrl+M` cria anotação.
- `Ctrl+L` abre planos; `Ctrl+O` abre o Diário de oração; `Ctrl+H` abre o histórico; `Ctrl+Shift+D` abre Meu momento com Deus; `Ctrl+Shift+C` copia o texto.
- Tamanho do texto ajustável com `Ctrl+mais` e `Ctrl+menos`.
- Voz offline do Windows por meio do Qt TextToSpeech, quando disponível.

## Continuidade, anotações e devocionais

O aplicativo salva automaticamente tradução, testamento, livro, capítulo e item atual e retorna a esse ponto na próxima abertura. Marcadores e anotações ficam em `data/user_data.db`, separado do banco bíblico. “Criar anotação” aparece no menu Aplicações do versículo; Mais opções organiza as anotações por dia e por título e permite ler tanto o dia completo quanto uma nota isolada.

“Fazer devocional” começa com a referência e o texto selecionados. A referência pode ser trocada dentro da própria tela. O editor oferece campos para título e reflexão e salva um arquivo `.txt` UTF-8 na pasta escolhida pela pessoa.

## Recursos pessoais e de estudo

- Busca local por palavras, frase exata, assunto, nome de livro ou dentro de um livro, com histórico, cópia e favorito. O índice de temas funciona offline.
- Dezessete planos incluídos, entre Bíblia em um ano, Novo Testamento, Salmos, Evangelhos e planos temáticos. Vários planos podem permanecer ativos e cada dia pode ser marcado ou desmarcado.
- Diário de oração com estados Orando, Respondida e Arquivada, filtros, pesquisa, resposta registrada e estatísticas simples.
- Meu momento com Deus reúne versículo diário, leitura do plano, reflexão, prática, oração, anotação e histórico por data.
- Favoritos de versículos, capítulos e passagens com categorias próprias; anotações pesquisáveis para versículos, capítulos ou intervalos.
- Histórico de leitura, posição exata para continuar, versículo diário, informações dos 66 livros, personagens e temas.
- Harpa Cristã com os 640 hinos, pesquisa offline por número, título ou trecho e leitura acessível da letra completa.
- Rádio Gospel com catálogo atualizado pelo site, player acessível, reprodução contínua e controles de faixa e volume.
- Ideias, sugestões e suporte abre o e-mail já endereçado para `diego@audiogames.com.br`.
- Memorização em três níveis e 298 perguntas de quiz sobre conteúdo, ordem e estrutura da Bíblia.
- Status do quiz com perguntas respondidas, acertos, erros, aproveitamento e detalhamento por dificuldade.
- Cada pergunta do quiz conta somente na primeira resposta. Recomeçar quiz do zero, com confirmação, é a única forma de liberar todas novamente.
- Comparação de traduções com caixas de origem e destino, duas seções de resultado e comando para inverter as edições.
- Modo culto com busca inteligente: no Android, aceita texto ou voz, nomes isolados, ordinais e intervalos. Samuel, Reis, Crônicas, Coríntios, Tessalonicenses, Timóteo, Pedro e João oferecem todos os livros possíveis em uma lista acessível.
- No Android, a tela inicial oferece continuar o capítulo atual ou trocar de livro. Ao escolher capítulo, é possível selecionar o versículo inicial; capítulo anterior e próximo ficam no começo da leitura e também podem ser acionados por gesto horizontal de dois dedos.
- Backup JSON versionado e validado para favoritos, notas, planos, orações, devocionais, histórico e configurações não secretas.

Dados de oração, notas e devocionais permanecem somente no SQLite local e nunca são enviados para a IA. A chave do Gemini não entra no backup JSON, porque a cópia protegida por DPAPI só pode ser aberta pela mesma conta do Windows.

A tela **Licenças e leis** apresenta um único texto simples, somente leitura, com a legislação, a justificativa geral, as cinco fontes e suas condições de utilização. Não há seletores ou conteúdo HTML nessa área.

## Configurações e IA opcional

A tela Configurações, aberta por Mais opções, usa uma única lista navegável com cima/baixo. Ela contém chave da API, modelo, detalhamento das respostas, tamanho do texto, escolha da voz SAPI, velocidade da voz, alto contraste, verificação automática e notificações de versão. A voz pode ser trocada entre as opções instaladas no Windows ou desativada; nesse modo, Enter repete o versículo pelo leitor de tela. Espaço ou Enter abre qualquer opção em um diálogo também navegável. Quando nenhuma chave foi informada, o botão “Obter chave da API do Google” abre a página oficial do Google AI Studio. Tab leva aos botões disponíveis; Espaço ou Enter os aciona.

A chave do Gemini é criptografada pela DPAPI para a conta atual do Windows e recuperada nas próximas aberturas; nunca é publicada no GitHub. A IA usa o endpoint `generateContent` da API Google Gemini, pode consumir a cota ou o saldo da conta e exige internet. Aplicações ou Shift+F10 oferece resumo no livro selecionado, resumo no capítulo selecionado e explicação no versículo selecionado. Resumos têm limite explícito de palavras e uma margem maior de tokens para terminarem a conclusão; respostas que ainda terminarem em `MAX_TOKENS` não são apresentadas silenciosamente como completas. O resultado é convertido para texto simples, sem asteriscos ou outras marcas de Markdown, e abre em diálogo de parágrafos navegáveis.

Ao abrir uma versão nova, bancos de notas do formato antigo são migrados automaticamente. Antes da migração, o aplicativo cria `user_data.db.pre_notes_migration.bak` na pasta pessoal como cópia de segurança local.

## Atualizações seguras

A versão está centralizada em `biblia/version.py`. Ao iniciar, o aplicativo consulta em segundo plano, no máximo uma vez por dia, somente Releases estáveis do repositório oficial. Ajuda > Verificar atualizações força uma consulta e Ajuda > Novidades da versão funciona offline. Um pacote só é oferecido quando a release contém `BibliaAcessivel-Windows.zip` e `BibliaAcessivel-Windows.zip.sha256`; o tamanho e o SHA-256 são conferidos antes da instalação. O download pode ser cancelado e nenhuma falha de rede impede a leitura local.

Antes de instalar, é criado um backup consistente na pasta pessoal. Um helper do Windows aguarda o aplicativo fechar, substitui apenas os arquivos distribuíveis e o abre novamente; o banco pessoal, configurações e chave protegida não ficam na pasta substituída. Consulte [docs/ATUALIZACOES.md](docs/ATUALIZACOES.md) para publicar e testar uma versão.

Consulte [docs/ARQUITETURA.md](docs/ARQUITETURA.md) para a explicação de todos os arquivos e fluxos.

## Recriar o banco

O script `scripts/build_database.py` baixa as cinco fontes oficiais, normaliza os versículos e só substitui o banco depois de validar 66 livros, 1.189 capítulos, referências duplicadas, lacunas numéricas e textos vazios em cada tradução. A NTLH não é distribuída sem autorização formal da Sociedade Bíblica do Brasil. A Open Translation Bible também permanece fora do pacote porque sua fonte oficial não possui o texto de 1 Reis 19.

```powershell
python scripts/build_database.py
```

O processo de construção exige internet. Depois disso, somente o recurso opcional de IA precisa de conexão.

## Proteções dos pacotes

O Android de produção usa R8 para ofuscar, reduzir e otimizar o código e os recursos, recusa uma assinatura diferente do certificado oficial e valida o banco bíblico antes de atualizar sua cópia offline. O Windows remove docstrings, asserções e módulos de desenvolvimento do executável congelado. Os dois atualizadores continuam exigindo o nome correto do pacote e SHA-256 publicado. Essas camadas dificultam alteração e engenharia reversa, mas nenhum programa instalado no aparelho pode ser considerado impossível de analisar.

## Recriar o executável

Para desenvolvimento, instale `requirements.txt`. Para reproduzir exatamente o executável validado, use as versões de `requirements-build.txt`. Depois execute:

```powershell
powershell -ExecutionPolicy Bypass -File scripts\build_exe.ps1
```

O script coloca `BibliaAcessivel.exe` diretamente na raiz e usa a pasta temporária do Windows para arquivos intermediários. Nenhuma pasta `dist` é criada no projeto.

Depois, `scripts/package_release.ps1` gera o ZIP e seu arquivo SHA-256 diretamente na raiz.
