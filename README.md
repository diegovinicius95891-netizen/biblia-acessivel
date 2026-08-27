# Bíblia Acessível

Aplicativo desktop em Python para leitura acessível da Bíblia. A leitura, a pesquisa e a voz funcionam offline; somente o recurso opcional de IA exige internet.

O repositório também contém uma versão Android nativa em `android/`. Ela reutiliza o mesmo banco bíblico, funciona a partir do Android 8 e foi construída com controles padrão reconhecidos pelo TalkBack. Consulte [android/README.md](android/README.md).

## Iniciar

Dê duplo clique em `BibliaAcessivel.exe`, localizado na pasta principal. O executável não exige uma instalação separada do Python.

O diretório `data`, contendo somente o banco bíblico distribuível `biblia.db`, deve permanecer ao lado do executável. Dados pessoais ficam em `%APPDATA%\BibliaAcessivel\user_data.db`. Na primeira execução da versão 2, uma base antiga encontrada ao lado do programa é copiada com segurança para esse local e preservada.

O banco distribuído contém quatro textos identificados separadamente:

- Bíblia Portuguesa Mundial — domínio público conforme eBible.org.
- João Ferreira de Almeida — domínio público; arquivo estruturado do Open Bibles.
- Open Translation Bible em português — CC BY-SA 4.0.
- World English Bible — domínio público.

As licenças, fontes, atribuições, artigos relevantes da Lei nº 9.610/1998 e a explicação jurídica ficam disponíveis na seção **Licenças e leis** do próprio aplicativo.

## Acessibilidade

- Controles com nomes e descrições acessíveis.
- Navegação por seções: Tab e Shift+Tab mudam de seção; cima e baixo navegam dentro dela.
- A tela principal mostra somente Livros, Capítulos, Versículos, Área de leitura e Mais opções.
- Mais opções reúne os recursos pessoais e de estudo em telas internas limpas; Escape volta à Bíblia.
- A tela Traduções usa itens com estado de caixa de seleção; Espaço ou Enter marca uma única edição.
- A seção Livros mostra apenas o testamento atual; esquerda seleciona o Antigo e direita seleciona o Novo.
- Enter em Livros leva aos capítulos; Enter em Capítulos abre a seção Versículos.
- Lista de leitura adequada para navegação linha a linha com NVDA, JAWS ou Narrador.
- Na seção Versículos, esquerda volta um capítulo e direita avança um capítulo do mesmo livro. A Área de leitura mostra somente a referência e o texto selecionado.
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
- Memorização em três níveis e 36 perguntas de quiz, com duas perguntas em cada combinação de dificuldade e categoria.
- Modo culto com foco direto no campo: Enter exibe a passagem sem abrir “Ler versículo”; o texto recebe foco e há comandos para copiar, trocar de capítulo ou digitar outra referência.
- Backup JSON versionado e validado para favoritos, notas, planos, orações, devocionais, histórico e configurações não secretas.

Dados de oração, notas e devocionais permanecem somente no SQLite local e nunca são enviados para a IA. A chave do Gemini não entra no backup JSON, porque a cópia protegida por DPAPI só pode ser aberta pela mesma conta do Windows.

A tela **Licenças e leis** apresenta um único texto simples, somente leitura, com a legislação, a justificativa geral, as quatro fontes e suas condições de utilização. Não há seletores ou conteúdo HTML nessa área.

## Configurações e IA opcional

A tela Configurações, aberta por Mais opções, usa uma única lista navegável com cima/baixo. Ela contém chave da API, modelo, detalhamento das respostas, tamanho do texto, escolha da voz SAPI, velocidade da voz, alto contraste, verificação automática e notificações de versão. A voz pode ser trocada entre as opções instaladas no Windows ou desativada; nesse modo, Enter repete o versículo pelo leitor de tela. Espaço ou Enter abre qualquer opção em um diálogo também navegável. Quando nenhuma chave foi informada, o botão “Obter chave da API do Google” abre a página oficial do Google AI Studio. Tab leva aos botões disponíveis; Espaço ou Enter os aciona.

A chave do Gemini é criptografada pela DPAPI para a conta atual do Windows e recuperada nas próximas aberturas; nunca é publicada no GitHub. A IA usa o endpoint `generateContent` da API Google Gemini, pode consumir a cota ou o saldo da conta e exige internet. Aplicações ou Shift+F10 oferece resumo no livro selecionado, resumo no capítulo selecionado e explicação no versículo selecionado. Resumos têm limite explícito de palavras e uma margem maior de tokens para terminarem a conclusão; respostas que ainda terminarem em `MAX_TOKENS` não são apresentadas silenciosamente como completas. O resultado é convertido para texto simples, sem asteriscos ou outras marcas de Markdown, e abre em diálogo de parágrafos navegáveis.

Ao abrir uma versão nova, bancos de notas do formato antigo são migrados automaticamente. Antes da migração, o aplicativo cria `user_data.db.pre_notes_migration.bak` na pasta pessoal como cópia de segurança local.

## Atualizações seguras

A versão está centralizada em `biblia/version.py`. Ao iniciar, o aplicativo consulta em segundo plano, no máximo uma vez por dia, somente Releases estáveis do repositório oficial. Ajuda > Verificar atualizações força uma consulta e Ajuda > Novidades da versão funciona offline. Um pacote só é oferecido quando a release contém `BibliaAcessivel-Windows.zip` e `BibliaAcessivel-Windows.zip.sha256`; o tamanho e o SHA-256 são conferidos antes da instalação. O download pode ser cancelado e nenhuma falha de rede impede a leitura local.

Antes de instalar, é criado um backup consistente na pasta pessoal. Um helper do Windows aguarda o aplicativo fechar, substitui apenas os arquivos distribuíveis e o abre novamente; o banco pessoal, configurações e chave protegida não ficam na pasta substituída. Consulte [docs/ATUALIZACOES.md](docs/ATUALIZACOES.md) para publicar e testar uma versão.

Consulte [docs/ARQUITETURA.md](docs/ARQUITETURA.md) para a explicação de todos os arquivos e fluxos.

## Recriar o banco

O script `scripts/build_database.py` baixa as fontes oficiais/indicadas e normaliza os versículos. A Open Translation Bible deve ser obtida do repositório oficial e fornecida com `--otb-dir`.

```powershell
python scripts/build_database.py --otb-dir "C:\caminho\open-bible\lang\pt-BR"
```

O processo de construção exige internet. Depois disso, somente o recurso opcional de IA precisa de conexão.

## Recriar o executável

Para desenvolvimento, instale `requirements.txt`. Para reproduzir exatamente o executável validado, use as versões de `requirements-build.txt`. Depois execute:

```powershell
powershell -ExecutionPolicy Bypass -File scripts\build_exe.ps1
```

O script coloca `BibliaAcessivel.exe` diretamente na raiz e usa a pasta temporária do Windows para arquivos intermediários. Nenhuma pasta `dist` é criada no projeto.

Depois, `scripts/package_release.ps1` gera o ZIP e seu arquivo SHA-256 diretamente na raiz.
