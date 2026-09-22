# Verificação das instruções da Bíblia para computador

Esta revisão usa o arquivo de instruções como lista de requisitos, sem tratá-lo como código ou como autorização para alterar outros produtos. O escopo desta entrega é a Bíblia para Windows.

## Atualizador

- Estrutura analisada: Python 3, PySide6, SQLite, PyInstaller de arquivo único e pacote ZIP portátil.
- Versão centralizada, SemVer real, canal estável, drafts e prereleases ignorados.
- Consulta pública a GitHub Releases em thread, timeout, cache diário e falha silenciosa na inicialização.
- Verificação manual e novidades locais no menu Ajuda.
- Janela acessível com versão, tamanho, changelog e Atualizar, Lembrar, Ignorar e Fechar.
- Download cancelável sem congelar, anúncios moderados, nome de asset fixo e origens GitHub permitidas.
- Tamanho e SHA-256 obrigatórios; pacote divergente é apagado e nunca executado.
- Backup SQLite pré-atualização, retenção de cinco cópias, helper separado, espera do processo e reabertura.
- Dados pessoais migrados para `%APPDATA%`, fora da pasta substituída. Migrações SQLite continuam incrementais e nunca recriam a base.
- Preferências para verificação e notificação, versão ignorada e lembrete de 24 horas.
- Logs técnicos não contêm notas, orações ou devocionais.
- Workflow por tag executa testes antes de criar EXE, ZIP e SHA-256.

## Recursos bíblicos

Busca temática local, 17 planos, diário de oração, Meu momento com Deus, favoritos, anotações, histórico, continuidade, versículo diário, 66 livros, personagens, temas, memorização, estatísticas, atalhos, referências abreviadas, backup JSON e tratamento de erros permanecem implementados.

O quiz agora contém 36 perguntas: duas para cada combinação entre três dificuldades e seis categorias. O Modo culto mostra o texto imediatamente ao pressionar Enter, move o foco para a passagem e oferece capítulo anterior, próximo, cópia e retorno ao campo.

## Acessibilidade verificada

Os testes automatizados exercitam nomes acessíveis, foco, Enter, Espaço, setas, Escape e telas sem guias. A verificação real com NVDA continua recomendada em cada máquina e voz instalada, pois automação fora da tela não substitui um teste manual completo do leitor de tela.
