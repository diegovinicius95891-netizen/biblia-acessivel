# Bíblia Acessível

Aplicativo desktop em Python para leitura acessível da Bíblia. A leitura, a pesquisa e a voz funcionam offline; somente o recurso opcional de IA exige internet.

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
- Tecla Aplicações ou Shift+F10 abre cópia, marcador e leitura em voz alta.
- O menu `Item atual`, acessível com `Alt+I`, oferece as mesmas operações.
- `Ctrl+Alt+C` copia o texto; `Ctrl+Alt+R` copia referência e texto; `Ctrl+Alt+M` alterna o marcador.
- Tamanho do texto ajustável com `Ctrl+mais` e `Ctrl+menos`.
- Voz offline do Windows por meio do Qt TextToSpeech, quando disponível.

## Continuidade e marcadores

O aplicativo salva automaticamente tradução, testamento, livro, capítulo e item atual e retorna a esse ponto na próxima abertura. Os marcadores ficam em `data/user_data.db`, separado do banco bíblico.

A seção **Licenças e leis** apresenta um único texto simples, somente leitura, com a legislação, a justificativa geral, as quatro fontes e suas condições de utilização. Não há seletores ou conteúdo HTML nessa área.

## Configurações e IA opcional

A seção Configurações contém somente uma lista navegável com cima/baixo: “Colar ou alterar chave da API” e “Escolher modelo”. Enter abre a opção em um diálogo; na chave, basta colar e pressionar Enter. Tab sai da lista e leva ao botão “Salvar configurações”.

A chave é criptografada pela DPAPI para a conta atual do Windows e recuperada nas próximas aberturas; nunca é publicada no GitHub. A IA usa a Responses API com `store: false`, pode consumir o saldo da conta e exige internet. Aplicações ou Shift+F10 oferece resumo no livro selecionado, resumo no capítulo selecionado e explicação no versículo selecionado. O resultado abre em um diálogo de parágrafos navegáveis e pode ser copiado.

Consulte [docs/ARQUITETURA.md](docs/ARQUITETURA.md) para a explicação de todos os arquivos e fluxos.

## Recriar o banco

O script `scripts/build_database.py` baixa as fontes oficiais/indicadas e normaliza os versículos. A Open Translation Bible deve ser obtida do repositório oficial e fornecida com `--otb-dir`.

```powershell
python scripts/build_database.py --otb-dir "C:\caminho\open-bible\lang\pt-BR"
```

O processo de construção exige internet. Depois disso, somente o recurso opcional de IA precisa de conexão.

## Recriar o executável

Para desenvolvimento, instale `requirements.txt` e PyInstaller. Depois execute:

```powershell
powershell -ExecutionPolicy Bypass -File scripts\build_exe.ps1
```

O script coloca `BibliaAcessivel.exe` diretamente na raiz e usa a pasta temporária do Windows para arquivos intermediários. Nenhuma pasta `dist` é criada no projeto.
