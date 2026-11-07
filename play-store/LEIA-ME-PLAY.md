# Publicar a Bíblia AGB na Google Play — guia passo a passo

Esta pasta tem tudo o que a Play Console pede. Ela foi pensada para quem vai criar
a ficha na loja, lendo com o leitor de tela.

## O que tem aqui

| Arquivo | Para que serve | Onde entra na Play Console |
| --- | --- | --- |
| `BibliaAGB-4.7.0-play.aab` | O aplicativo (Android App Bundle), versão 4.7.0, código 25 | Testes → Teste fechado → Criar versão |
| `icone-512.png` | Ícone da loja, 512 × 512 | Presença na loja → Ficha principal → Ícone do app |
| `destaque-1024x500.png` | Imagem de destaque, 1024 × 500 | Ficha principal → Gráfico de recursos |
| `capturas/*.png` | 6 capturas de tela do celular, 1080 × 1920 | Ficha principal → Capturas de tela do telefone |
| `ficha-da-loja.md` | Nome, descrição curta e descrição completa | Ficha principal |
| `seguranca-dos-dados.md` | Respostas do formulário Segurança dos dados | Conteúdo do app → Segurança dos dados |

**Política de privacidade:** https://www.audiogames.com.br/diego/biblia-agb/privacidade/

## Chave do aplicativo (assinatura)

O AAB já está assinado com a **chave de envio** (upload key) guardada em
`C:\Winvox\Treino\chaves-play-biblia` (`biblia-play-upload.jks` + `credenciais.json`).

- **Faça backup dessa pasta** (pendrive ou nuvem privada). Sem ela, não dá para enviar atualizações.
  Se perder, o Google permite redefinir a chave de envio, mas leva alguns dias.
- **Nunca coloque essa pasta no GitHub** nem mande por mensagem aberta.
- Na primeira publicação, aceite o **Play App Signing** (assinatura gerenciada pelo Google). É o padrão.
- Para gerar um AAB novo: no PowerShell, dentro de `android`, rode
  `.\build_play.ps1 -KeyDirectory C:\Winvox\Treino\chaves-play-biblia`.
  Antes, aumente `versionCode` em `android/app/build.gradle`: a Play recusa um número repetido.

A chave do Google Gemini **não vem no aplicativo**: cada pessoa coloca a sua em
Mais opções → Configurações → Colar ou alterar chave do Google Gemini.

## Passo a passo na Play Console

1. **Conta de desenvolvedor** (play.google.com/console): taxa única de 25 dólares e verificação de identidade.
2. **Criar app:** nome `Bíblia AGB Acessível`, idioma padrão Português (Brasil), tipo **App**, **Gratuito**.
3. **Conteúdo do app** (menu lateral), todos obrigatórios:
   - **Política de privacidade:** o link acima.
   - **Acesso ao app:** "Todas as funcionalidades estão disponíveis sem restrições". A IA usa a chave da própria pessoa, não precisa de login.
   - **Anúncios:** não contém anúncios.
   - **Classificação de conteúdo:** categoria "Referência, notícias ou educação". Responda "não" a violência, sexo, linguagem imprópria, drogas e jogos de azar. O app tem conteúdo gerado por IA, mas só responde a quem pergunta; não há interação entre usuários.
   - **Público-alvo:** marque 13 anos ou mais (evita as regras extras para crianças).
   - **Segurança dos dados:** copie as respostas de `seguranca-dos-dados.md`.
   - **Apps governamentais / financeiros / saúde:** não se aplica.
   - **Recursos de IA generativa:** declare que o app usa IA generativa (Google Gemini) para estudos bíblicos, que o conteúdo é marcado como gerado por IA e que há como denunciar (e-mail de suporte).
4. **Ficha principal da loja:** textos de `ficha-da-loja.md`, ícone, destaque e capturas. Categoria **Livros e referências**. E-mail de contato `diego@audiogames.com.br` e site `https://www.audiogames.com.br/diego/`.
5. **Teste fechado (obrigatório para contas pessoais novas):** o Google exige **pelo menos 12 testadores
   participando por 14 dias seguidos** antes de liberar a produção. Crie uma lista de e-mails, envie o
   AAB em Teste fechado e compartilhe o link de participação. Depois dos 14 dias, peça acesso à produção.
6. **Produção:** promova a versão testada para Produção e envie para revisão (normalmente alguns dias).

## Diferenças da versão da Play

- Sem o atualizador do GitHub: as atualizações chegam pela própria Play.
- Sem a permissão de instalar outros aplicativos.
- O pacote é `com.bibliaacessivel.app`, o mesmo do APK do GitHub. Como as assinaturas são diferentes,
  quem tem o APK do GitHub precisa **exportar o backup, desinstalar** e instalar pela Play (e importar o backup).
