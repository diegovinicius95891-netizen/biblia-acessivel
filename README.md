# Bíblia Acessível

Aplicativo desktop offline em Python para leitura da Bíblia com teclado, leitor de tela e voz opcional.

## Iniciar

Dê duplo clique em `BibliaAcessivel.exe`, localizado na pasta principal. O executável não exige uma instalação separada do Python.

O diretório `data`, contendo `biblia.db`, deve permanecer ao lado do executável. O arquivo pessoal `user_data.db` é criado nessa pasta durante o uso e não é publicado no GitHub.

O banco distribuído contém quatro textos identificados separadamente:

- Bíblia Portuguesa Mundial — domínio público conforme eBible.org.
- João Ferreira de Almeida — domínio público; arquivo estruturado do Open Bibles.
- Open Translation Bible em português — CC BY-SA 4.0.
- World English Bible — domínio público.

As licenças, fontes, atribuições, artigos relevantes da Lei nº 9.610/1998 e a explicação jurídica ficam disponíveis na seção **Licenças e leis** do próprio aplicativo.

## Acessibilidade

- Controles com nomes e descrições acessíveis.
- Navegação por seções: Tab e Shift+Tab mudam de seção; cima e baixo navegam dentro dela.
- Uma única página rolável, sem guias ou páginas escondidas.
- A seção Traduções usa itens com estado de caixa de seleção; Espaço ou Enter marca uma única edição.
- A seção Livros mostra apenas o testamento atual; esquerda seleciona o Antigo e direita seleciona o Novo.
- Enter em Livros leva aos capítulos; Enter em Capítulos abre a seção Leitura.
- Lista de leitura adequada para navegação linha a linha com NVDA, JAWS ou Narrador.
- Na seção Leitura, esquerda volta um capítulo e direita avança um capítulo do mesmo livro.
- Depois do último item aparece “Fim do capítulo” ou “Fim do livro. Não há capítulos seguintes”.
- Ajuda e Leis são listas de parágrafos; cima/baixo lê o conteúdo sem repetir o nome da seção.
- Tecla Aplicações ou Shift+F10 abre nota, cópia, marcador e leitura em voz alta.
- O menu `Item atual`, acessível com `Alt+I`, oferece as mesmas operações.
- `Ctrl+Alt+N` edita a nota; `Ctrl+Alt+L` lê a nota; `Ctrl+Alt+C` copia o texto; `Ctrl+Alt+R` copia referência e texto; `Ctrl+Alt+M` alterna o marcador.
- Sem cores ou temas impostos: o aplicativo respeita o tema e o alto contraste do sistema.
- Tamanho do texto ajustável com `Ctrl+mais` e `Ctrl+menos`.
- Voz offline do Windows por meio do Qt TextToSpeech, quando disponível.

## Continuidade, notas e marcadores

O aplicativo salva automaticamente tradução, testamento, livro, capítulo e item atual. Ao abrir novamente, retorna ao mesmo ponto. Notas e marcadores ficam em `data/user_data.db`, separados do banco bíblico, para não serem apagados quando os textos forem reconstruídos.

A seção **Licenças e leis** apresenta um único texto simples, somente leitura, com a legislação, a justificativa geral, as quatro fontes e suas condições de utilização. Não há seletores ou conteúdo HTML nessa área.

## Seção Notas

Depois que a primeira nota é confirmada, a seção Notas cria um botão recolhido para o livro correspondente. Livros sem notas não aparecem. Ao expandir um livro, cima/baixo percorrem as notas e Enter abre a referência e o editor.

Consulte [docs/ARQUITETURA.md](docs/ARQUITETURA.md) para a explicação de todos os arquivos e fluxos.

## Recriar o banco

O script `scripts/build_database.py` baixa as fontes oficiais/indicadas e normaliza os versículos. A Open Translation Bible deve ser obtida do repositório oficial e fornecida com `--otb-dir`.

```powershell
python scripts/build_database.py --otb-dir "C:\caminho\open-bible\lang\pt-BR"
```

O processo de construção exige internet; a utilização do aplicativo não exige.

## Recriar o executável

Para desenvolvimento, instale `requirements.txt` e PyInstaller. Depois execute:

```powershell
powershell -ExecutionPolicy Bypass -File scripts\build_exe.ps1
```

O script coloca `BibliaAcessivel.exe` diretamente na raiz e usa a pasta temporária do Windows para arquivos intermediários. Nenhuma pasta `dist` é criada no projeto.
