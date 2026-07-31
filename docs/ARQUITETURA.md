# Arquitetura comentada

Este documento explica a responsabilidade de cada arquivo e os fluxos principais do aplicativo. O código-fonte também contém docstrings em todos os módulos, classes e funções.

## Organização da interface

O aplicativo possui uma única página rolável. Não existem `QTabWidget`, guias ou páginas escondidas. O foco percorre as seções nesta ordem:

1. Traduções.
2. Livros.
3. Capítulos.
4. Leitura.
5. Referência direta.
6. Notas, quando existirem.
7. Pesquisa.
8. Licenças e leis.
9. Ajuda.

Tab e Shift+Tab percorrem os controles. Setas cima/baixo navegam dentro de listas. Na seção Livros, esquerda seleciona o Antigo Testamento e direita seleciona o Novo.

## Arquivos de execução

- `BibliaAcessivel.exe`: aplicativo pronto para duplo clique, mantido na raiz.
- `app.py`: configura o Qt, localiza o banco ao lado do EXE e abre a janela.
- `requirements.txt`: dependência da interface PySide6.
- `scripts/build_exe.ps1`: recompila o EXE na raiz e mantém intermediários fora do projeto.

## Pacote `biblia`

- `main_window.py`: controles acessíveis, seções, teclado, voz, pesquisa, leitura, notas recolhíveis e continuidade.
- `database.py`: consultas somente leitura ao banco bíblico.
- `user_data.py`: banco separado de notas e marcadores.
- `dialogs.py`: editor de notas e leitor de texto com nomes e foco acessíveis.
- `legal.py`: texto jurídico único e simples.

## Fluxo de uma nota

1. A pessoa seleciona um item na seção Leitura.
2. Aplicações, Shift+F10, Alt+I ou Ctrl+Alt+N chama `edit_note`.
3. `NoteDialog` abre um editor associado à referência atual.
4. Confirmar chama `UserDataDatabase.save_note`.
5. `refresh_notes_section` consulta todas as notas.
6. Somente livros presentes no resultado recebem um `NotesBookWidget`.
7. Cada widget começa recolhido e sua lista entra no fluxo de foco apenas quando expandida.

O arquivo `data/user_data.db` é ignorado pelo Git para impedir a publicação de dados pessoais.

## Textos bíblicos

`data/biblia.db` contém somente textos e metadados distribuíveis. `scripts/build_database.py` documenta e reproduz a importação das quatro fontes. `THIRD_PARTY_NOTICES.md` registra atribuições e condições.

## Limites e navegação da leitura

A lista acrescenta um item terminal depois do conteúdo. Nos capítulos intermediários ele contém “Fim do capítulo”. No último capítulo do livro contém “Fim do livro. Não há capítulos seguintes”. Esquerda e direita mudam capítulos somente dentro do livro atual; a seleção de outro livro continua sendo feita na seção Livros.

Ajuda e Leis usam `ReadingTextList`, que apresenta um parágrafo por item. Isso evita a repetição do nome da seção observada com controles de edição somente leitura.

## Testes

- `test_database.py`: integridade das edições e pesquisa.
- `test_user_data.py`: notas e marcadores em banco temporário.
- `test_gui_smoke.py`: página sem guias, teclado, diálogo acessível, seção recolhível e navegação.
