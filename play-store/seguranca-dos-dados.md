# Respostas do formulário "Segurança dos dados"

Baseadas no que o código do aplicativo realmente faz (versão 4.7.0).

## Visão geral
- **O app coleta ou compartilha algum dos tipos de dados exigidos?** Sim (somente pelo recurso opcional de IA, descrito abaixo).
- **Todos os dados coletados são criptografados em trânsito?** Sim (todas as conexões usam HTTPS).
- **Você oferece uma forma de os usuários solicitarem a exclusão dos dados?** O app não tem conta nem
  servidor próprio que guarde dados do usuário. Os dados ficam no aparelho e são apagados ao limpar os
  dados ou desinstalar. Marque a opção de que os dados não são mantidos pelo desenvolvedor.

## Tipos de dados

### Mensagens do app → "Outro conteúdo gerado pelo usuário"
- **Quando:** somente quando a pessoa usa o Teólogo de IA.
- **O que:** a pergunta digitada e o trecho bíblico escolhido são enviados ao Google Gemini, com a chave da própria pessoa.
- **Coletado:** Sim. **Compartilhado:** Sim (com o Google, prestador do serviço de IA).
- **Processado temporariamente:** não garantido pelo app (segue os termos da API do Google Gemini).
- **Obrigatório ou opcional:** Opcional.
- **Finalidade:** Funcionalidade do app.

### Não coletados
- Localização, contatos, fotos, áudio gravado pelo app, identificadores de anúncio, informações financeiras,
  dados de saúde, histórico de navegação: **não coletados**.
- Anotações, orações, planos, favoritos e quiz: **ficam somente no aparelho** (não contam como coleta).
- Pesquisa por voz do Modo culto: feita pelo reconhecedor de voz do sistema Android; o app recebe só o texto.
- Relatório de erro: guardado no aparelho; só sai se a pessoa escolher enviar por e-mail.
- Devocionais, Rádio Gospel e áudios: o app **baixa** arquivos públicos do site; nada do usuário é enviado.

## Permissões declaradas
- `INTERNET`: devocionais, Rádio Gospel e IA opcional.
- `POST_NOTIFICATIONS`: aviso de devocional novo e lembrete diário de leitura.
- `RECEIVE_BOOT_COMPLETED`, `WAKE_LOCK`, `ACCESS_NETWORK_STATE`: adicionadas pela biblioteca WorkManager para
  manter os lembretes agendados depois de reiniciar o celular.
