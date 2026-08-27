# Arquitetura comentada

Este documento explica a responsabilidade de cada arquivo e os fluxos principais do aplicativo. O código-fonte também contém docstrings em todos os módulos, classes e funções.

## Organização da interface

O aplicativo não usa `QTabWidget` nem guias. Um `QStackedWidget` mantém uma tela principal enxuta e telas internas acessíveis. Na tela principal, o foco percorre:

1. Livros.
2. Capítulos.
3. Versículos.
4. Área de leitura do versículo atual.
5. Mais opções.

Mais opções contém as páginas pessoais e de estudo. Espaço ou Enter abre somente o recurso escolhido; Escape restaura a tela principal e o foco em Mais opções. Tab e Shift+Tab percorrem os controles. Setas cima/baixo navegam dentro de listas. Na seção Livros, esquerda seleciona o Antigo Testamento e direita seleciona o Novo.

## Arquivos de execução

- `BibliaAcessivel.exe`: aplicativo pronto para duplo clique, mantido na raiz.
- `app.py`: configura o Qt, localiza o banco bíblico ao lado do EXE, migra dados pessoais para `%APPDATA%` e abre a janela.
- `requirements.txt`: dependência da interface PySide6.
- `scripts/build_exe.ps1`: recompila o EXE na raiz e mantém intermediários fora do projeto.

## Pacote `biblia`

- `main_window.py`: telas internas, teclado, leitura, pesquisa, devocional, anotações, configurações, IA e continuidade.
- `gemini_client.py`: chamada mínima ao `generateContent` do Google Gemini, extração do texto e erros seguros.
- `secure_store.py`: proteção e recuperação da chave por DPAPI, vinculada ao usuário do Windows.
- `database.py`: consultas somente leitura ao banco bíblico.
- `user_data.py`: banco separado de marcadores e anotações por data.
- `extended_features.py`: páginas de planos, oração, devocional diário, favoritos, histórico, estudo, memorização, quiz, estatísticas, culto e backup.
- `references.py`: parser testável para livros abreviados, capítulos e intervalos.
- `topics.py`: índice temático local compartilhado pela busca e pela página Temas.
- `plans.py`: modelos, geração das leituras e cálculo de progresso dos 17 planos iniciais.
- `study_content.py`: informações cuidadosas sobre livros, personagens e perguntas locais.
- `dialogs.py`: escolhas, edição de anotações e leitura de texto com nomes e foco acessíveis.
- `legal.py`: texto jurídico único e simples.
- `version.py`: única fonte da versão instalada, repositório e nomes dos assets.
- `paths.py`: pasta pessoal e migração única da base antiga.
- `update_service.py`: versões semânticas, GitHub Releases, download e SHA-256, sem dependência da interface.
- `update_ui.py`: consulta em thread, diálogos acessíveis, progresso moderado, backup e helper de reinício.
- `changelog.py`: novidades da versão instalada para consulta offline.

## Fluxo da IA opcional

1. A pessoa escolhe chave, modelo, detalhamento, fonte, voz SAPI, velocidade ou contraste na lista de Configurações. A voz pode ser uma das instaladas no Windows ou ficar desativada; nesse caso, Enter gera um novo anúncio acessível do versículo. Espaço/Enter abre um diálogo de escolhas; sem chave, um botão abre o Google AI Studio.
2. Salvar configurações protege a chave com DPAPI e grava somente o bloco criptografado.
3. Aplicações no livro, capítulo ou leitura determina automaticamente a tarefa de IA.
4. `MainWindow` monta somente o livro, capítulo ou versículo necessário. Livros e capítulos recebem limite explícito de palavras e ordem para concluir a resposta.
5. Uma thread de segundo plano chama `create_bible_analysis`, sem bloquear teclado ou leitor de tela.
6. A requisição usa `generateContent` com a chave no cabeçalho `x-goog-api-key`.
7. A margem de `maxOutputTokens` é maior que o tamanho textual solicitado. Se a API ainda responder com `MAX_TOKENS`, o cliente informa o corte em vez de exibir o trecho como resposta completa.
8. O resultado retorna por sinais do Qt, perde marcas de Markdown e abre em diálogo de parágrafos navegáveis.

O popup da tecla Aplicações usa `ApplicationsDialog`, não um menu visual dependente da plataforma. O título identifica “Menu do livro”, “Menu do capítulo” ou “Menu do versículo”; a ação de IA fica na primeira linha e recebe foco. Todas as ações aceitam Espaço ou Enter.

O arquivo pessoal fica em `%APPDATA%\BibliaAcessivel\user_data.db`; uma cópia antiga ao lado do EXE é migrada somente se o destino ainda não existir. O Git também ignora o caminho legado para impedir publicação acidental.

## Persistência ampliada e migração

`UserDataDatabase` mantém as tabelas antigas e cria incrementalmente categorias de favoritos, histórico de pesquisa e leitura, estados e dias de planos, pedidos de oração, devocionais diários, memorização e metadados de versão. Colunas novas de `bookmarks` são acrescentadas com `ALTER TABLE`; os registros existentes recebem a categoria Favoritos gerais. Nenhuma atualização apaga notas ou marcadores antigos.

O backup exporta um objeto JSON identificado por `format` e `version`. A importação valida tabelas, registros e colunas antes de escrever e usa uma transação SQLite, que reverte a operação inteira se qualquer registro for inválido. Preferências não secretas do `QSettings` acompanham o backup; a chave Gemini protegida por DPAPI não acompanha, pois não seria portátil para outra conta do Windows.

## Busca e conteúdo local

A pesquisa exata continua no banco bíblico. Palavra, frase e filtro por livro são parâmetros da mesma consulta. A pesquisa temática combina referências curadas com termos relacionados de `topics.py`, elimina duplicações e permanece offline. O mesmo catálogo alimenta a página Temas.

Planos são gerados a partir dos capítulos realmente presentes na tradução, enquanto planos temáticos usam referências legíveis. O estado de cada plano usa identificador estável, permitindo vários planos ativos simultaneamente sem duplicar as definições no banco pessoal.

## Anotações e devocional

O menu do versículo oferece “Criar anotação”. `NoteEditorDialog` coleta título e corpo e `UserDataDatabase` grava referência, texto bíblico e horário local. Na inicialização, `UserDataDatabase` compara as colunas existentes; o formato antigo (`note` e `updated_at`) é copiado para uma tabela nova, preservando o conteúdo. Antes disso, o banco recebe uma cópia `.pre_notes_migration.bak`. A tela Anotações cria itens de dia seguidos pelos títulos correspondentes. Ativar um dia lê o conjunto daquele dia; ativar um título lê somente a nota.

A tela de devocional recebe a referência selecionada, mas também resolve outra referência digitada sem mover a leitura principal. Título, texto bíblico e reflexão são exportados como `.txt` UTF-8 por `QFileDialog`, permitindo escolher qualquer pasta gravável.

## Textos bíblicos

`data/biblia.db` contém somente textos e metadados distribuíveis. `scripts/build_database.py` documenta e reproduz a importação das quatro fontes. `THIRD_PARTY_NOTICES.md` registra atribuições e condições.

## Atualização

`UpdateController` agenda uma consulta diária depois que a janela já abriu. `UpdateService` usa a API pública do GitHub com timeout, descarta drafts e prereleases no canal estável e faz comparação semântica real. Apenas o ZIP Windows de nome conhecido e sua soma SHA-256 são aceitos, sempre a partir de origens GitHub permitidas. O download ocorre em thread, pode ser cancelado e anuncia somente marcos de progresso.

Após validação, o banco pessoal recebe backup SQLite consistente. Um processo PowerShell oculto espera o PID principal terminar, expande o ZIP numa pasta temporária, valida a presença do EXE, copia os arquivos distribuíveis e reabre o aplicativo. Dados pessoais e configurações permanecem no perfil do usuário. Os cinco backups automáticos mais recentes são mantidos.

O workflow `.github/workflows/release-windows.yml` é acionado por tags `v*`, exige correspondência com `APP_VERSION`, executa os testes, compila, cria o ZIP e SHA-256 e publica os assets apenas se todas as etapas passarem.

O Android possui um canal isolado em `AndroidUpdateService` e `UpdateManager`: somente tags `android-v*`, APK e SHA-256 Android são aceitos. O `FileProvider` entrega o APK validado ao instalador oficial do sistema. O workflow `release-android.yml` recompila com a identidade de assinatura já usada nas versões anteriores e rejeita qualquer certificado com impressão diferente.

## Limites e navegação da leitura

A lista acrescenta um item terminal depois do conteúdo. Nos capítulos intermediários ele contém “Fim do capítulo”. No último capítulo do livro contém “Fim do livro. Não há capítulos seguintes”. Esquerda e direita mudam capítulos somente dentro do livro atual; a seleção de outro livro continua sendo feita na seção Livros.

Ajuda e Leis usam `ReadingTextList`, que apresenta um parágrafo por item. Isso evita a repetição do nome da seção observada com controles de edição somente leitura.

## Testes

- `test_database.py`: integridade das edições e pesquisa.
- `test_user_data.py`: marcadores e anotações em banco temporário.
- `test_gemini_client.py`: contrato do cliente Gemini sem chamadas externas.
- `test_secure_store.py`: proteção e recuperação da chave pela DPAPI.
- `test_gui_smoke.py`: cinco seções principais, telas internas, teclado, configurações, IA, notas e exportação de devocional.
- `test_references.py`: capítulos, números, abreviações e intervalos.
- `test_plans.py`: catálogo mínimo, durações e progresso.
- `test_user_data.py`: também cobre categorias, planos, oração e backup transacional.
- `test_update_service.py`: SemVer, filtros de release, seleção de asset, cancelamento e SHA-256.
- `test_paths.py`: migração única sem sobrescrever a base pessoal existente.
- `test_study_content.py`: cobertura de dificuldades, categorias, respostas e referências do quiz.
