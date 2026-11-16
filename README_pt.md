<div align="center">

# devx para Android

**Um assistente de IA de bolso e um controle remoto do devx no seu computador.**  
Sem conta e sem servidor nosso. Suas chaves e conversas ficam no celular.

<br/>

[![Android](https://img.shields.io/badge/Android-8.0%2B-3ddc84.svg?style=flat-square&labelColor=0a0c11)](https://github.com/apvcode/Termux-Dev/releases/tag/android-app)
[![Download APK](https://img.shields.io/badge/download-APK-00c2d1.svg?style=flat-square&labelColor=0a0c11)](https://github.com/apvcode/Termux-Dev/releases/tag/android-app)
[![Languages](https://img.shields.io/badge/languages-9-4f8cff.svg?style=flat-square&labelColor=0a0c11)](#)
[![devx: MIT](https://img.shields.io/badge/devx-MIT-4f8cff.svg?style=flat-square&labelColor=0a0c11)](https://github.com/apvcode/Termux-Dev/blob/main/LICENSE)

<br/>

[English](README.md) &nbsp;•&nbsp; [Русский](README_ru.md) &nbsp;•&nbsp; [Українська](README_uk.md) &nbsp;•&nbsp; [Deutsch](README_de.md) &nbsp;•&nbsp; [Español](README_es.md) &nbsp;•&nbsp; [Français](README_fr.md) &nbsp;•&nbsp; **Português** &nbsp;•&nbsp; [中文](README_zh.md) &nbsp;•&nbsp; [日本語](README_ja.md)

</div>

---

## Em resumo

<table>
<tr>
<td valign="top" width="25%"><b>Assistente de bolso</b><br/>Qualquer modelo que escolher, voz, fotos e arquivos, memória, habilidades, MCP, busca na web.</td>
<td valign="top" width="25%"><b>Cadernos</b><br/>Aprenda ou reúna ideias: fontes, plano de estudo, cartões, resumo em áudio, respostas com citações.</td>
<td valign="top" width="25%"><b>Voz e celular</b><br/>Alarmes, chamadas, mensagens, lugares e clima, tarefas agendadas, widgets.</td>
<td valign="top" width="25%"><b>Controle do devx</b><br/>As mesmas sessões do terminal: respostas, aprovações, planos, arquivos.</td>
</tr>
</table>

## Baixar

O app tem uma página própria no Termux-Dev, separada das versões do devx (o app e o devx têm versões separadas). Cada versão é um arquivo ali, com sua soma de verificação; as novas são adicionadas ao lado das antigas e um arquivo nunca é substituído:

**[Releases → devx for Android](https://github.com/apvcode/Termux-Dev/releases/tag/android-app)** → o arquivo mais recente, `devx-android-X.Y.Z.apk`

Abra-o no celular e permita a instalação desta fonte. Android 8.0 (API 26) ou mais recente. As atualizações são instaladas sobre a versão antiga: toda versão é assinada com a mesma chave, e uma versão publicada nunca é substituída.

## Começar

1. Abra o app e diga seu nome.
2. **Configurações → Provedores → Adicionar um provedor.** Escolha uma predefinição ou informe um endereço e uma chave de API (compatível com OpenAI ou formato Anthropic; o formato é descoberto sozinho). Carregue os modelos e escolha um.
3. Escreva ou toque no microfone.

A chave de API fica criptografada no celular (Android Keystore) e vai apenas para o provedor que você adicionou.

## O que faz

### Conversa

- Vários provedores e modelos, esforço de raciocínio por modelo, ramos ao editar uma mensagem, conversas temporárias, conversas trancadas com o bloqueio de tela ou um código, projetos (trabalho contínuo com instruções e conhecimento próprios) e cadernos (abaixo).
- Fotos, vídeo, arquivos e câmera numa mensagem; visualizador de PDF e de texto; exportação para Markdown ou PDF.
- Memória: o assistente guarda notas úteis e um resumo sobre você, só no celular. Você pode lê-las, editá-las e apagá-las. O resumo se renova sozinho em segundo plano e mostra o que há de novo; uma nota que logo deixará de ser verdade (uma prova na sexta) pode expirar; quando há muitas notas, as que tocam sua pergunta são enviadas primeiro.
- Habilidades (guias escritos que o assistente lê quando uma tarefa combina): embutidas, importadas (`.skill` ou `SKILL.md`) ou escritas por você.
- Servidores MCP por https (GitHub, documentação e outros) com aprovações e uma chave de somente leitura.
- Busca na web (lê as páginas de busca sozinho; uma chave gratuita do Tavily ou Brave nas Configurações a torna bem mais precisa), arquivos e pacotes, um terminal e Python dentro do app, numa pasta própria. Cada um pode ser desligado.
- Formulários que o assistente desenha para você: perguntas com rótulos e dicas, escolhas (uma ou várias), controles deslizantes e interruptores; o que você preenche é guardado, e um formulário enviado continua enviado.
- Os nomes das conversas são escritos enquanto a resposta é escrita, com uma pequena animação; a conversa em que você está fica marcada na lista.
- Respostas ricas: cartões, tabelas, gráficos, mapas, clima, timers, listas de tarefas, diagramas Mermaid, cartões de apps do Google Play e F-Droid. Saudações que se alternam na tela inicial.
- Um editor em tela cheia com pré-visualização de Markdown para mensagens longas. Selecione várias conversas para apagar ou arquivar.
- Digite `@` no campo: um menu do que você pode apontar (um caderno, um projeto, um arquivo da biblioteca, uma tarefa agendada). Um caderno ou projeto vira parte da frase, na sua cor, e o assistente recebe o que há nele. Por voz, basta dizer o nome.
- Uma única busca em conversas, cadernos, projetos, arquivos e fontes, inclusive pelo que está escrito dentro de documentos, PDFs e notas.
- Cole imagens e arquivos direto no campo da mensagem (da área de transferência ou do teclado). «Perguntar ao devx» sobre um texto selecionado não mexe na sua área de transferência.
- Backup e restauração de conversas, cadernos, memória, habilidades e configurações num só arquivo; uma cópia semanal automática numa pasta à sua escolha (as quatro últimas ficam).
- Fixar e arquivar conversas, trancar uma conversa específica, adicionar uma conversa à tela inicial. «Perguntar ao devx» sobre um texto selecionado cita o trecho como um cartão na sua próxima mensagem.
- O assistente trabalha com arquivos numa pasta própria: cria e lê arquivos, faz e descompacta pacotes, lê um PDF página por página e olha suas imagens, edita imagens e descompacta, altera, reempacota e assina APKs. As aprovações podem ser dadas na própria notificação (Permitir / Negar).
- Quatro widgets (uma barra para perguntar, botões rápidos, as últimas conversas, os cartões para revisar) e dois blocos de configurações rápidas (perguntar, voz).

### Cadernos e a biblioteca

- Um caderno é um lugar para aprender ou reunir ideias sobre um tema. Os cadernos de *ideias* guardam fontes e conversas sobre um tema; os de *estudo* acrescentam uma meta, um plano de lições e seu progresso. Os projetos são separados: servem ao trabalho contínuo.
- Fontes: arquivos, fotos (também da câmera) e páginas da web. Cada uma recebe uma breve descrição do assistente; peça que encontre fontes na web e ele adiciona as melhores páginas.
- As respostas sobre um caderno citam suas fontes com pequenos chips (`notes.pdf · 12`); um toque abre a fonte, um PDF naquela página.
- Um caderno de estudo ensina passo a passo, confere com formulários, escreve o plano sozinho e mantém cartões: as perguntas voltam para revisão quando chega a hora (quanto melhor você sabe uma, mais tarde ela volta). Os cartões têm uma tela própria: a pergunta, um toque vira o cartão, quatro botões (de novo, difícil, bom, fácil) mostram quando ele volta, e um resumo no final; funciona sem o modelo e não custa nada. Um lembrete diário é uma notificação com o número de cartões para revisar.
- O assistente pode alterar um caderno ou projeto (nome, ícone, cor, descrição, instruções, meta) e, depois de conversarem, propor um novo caderno ou projeto: você confirma num cartão.
- Compartilhe um link, um texto, um PDF ou uma imagem de qualquer app: o devx oferece uma nova conversa ou um dos seus cadernos, onde vira uma fonte (um link é lido e guardado).
- Estatísticas de estudo na página: dias seguidos, os últimos sete dias em barras; um widget da tela inicial mostra quantos cartões estão para revisar e inicia a revisão. Os cartões e o resumo do caderno podem ser ouvidos (um *resumo em áudio* é escrito para ser ouvido e depois lido em voz alta), e um caderno é exportado como um arquivo Markdown.
- A biblioteca guarda todo arquivo que você anexou ou que o assistente criou: busca, filtros, onde foi usado, anexar a uma nova conversa, apagar em todo lugar. PDF, Word, slides, planilhas, texto, imagens e sites abrem no visualizador integrado; os originais do Office são mantidos.

### Aparência, personalidade e segurança

- Aparência: a do sistema, preta, cinza-escuro ou clara.
- Personalidade: escolha um jeito (calmo, sem enrolação, detalhado, amigável, profissional, direto, mentor), ajuste o tom, dê um nome ao assistente e escreva suas próprias instruções. Um toque redefine tudo.
- Bloqueio do app com o bloqueio de tela, um código ou biometria; conversas isoladas também podem ser trancadas, e a lista de apps recentes esconde a imagem enquanto há um bloqueio ativo.
- Fotos por mensagem, notificações de respostas e aprovações, e se um computador pode funcionar no modo «tudo» ficam nas configurações.

### Assistente de voz

- Escolha o Devx como assistente do celular: o botão de energia ou um deslize a partir de um canto abre uma janela de voz transparente sobre qualquer app.
- Alarmes, timers, lembretes; abrir apps e configurações do sistema; contatos, chamadas, mensagens e agenda (o último botão é você quem aperta); lanterna, música, volume; brilho e Não perturbe.
- Lugares, rotas e clima do OpenStreetMap e Open-Meteo, sem chaves. 2GIS e Google Maps podem ser adicionados com sua própria chave.
- Olha sua galeria, lê as notificações na tela, trabalha numa pasta que você escolhe, mas só quando você pede e só se você permitiu.
- Tarefas agendadas: «toda manhã me envie as notícias sobre IA». Elas rodam sozinhas e chegam como conversa e notificação.
- A velocidade da fala, a voz e um modelo rápido separado para conversar estão em **Configurações → Assistente de voz**.
- Toque na pílula para escrever em vez de falar; segure-a e deslize para cima para abrir o app com a mesma conversa (uma dica acima da pílula sempre mostra como). O microfone tem seus próprios sons suaves em vez do bipe do serviço de fala.
- «Continuar ouvindo após uma resposta» (mãos livres) e o idioma em que você fala, escolhido à parte do idioma do app.
- As tarefas agendadas têm uma tela própria: um horário, os dias, o modelo de cada uma, «executar agora».

### Devx Remote

- Controle o devx rodando no seu computador: cada conversa, respostas e raciocínio ao vivo, cartões de aprovação, perguntas, o plano (com *Go and compact context*), modos plan/agent/YOLO, modelo e provedor, contexto e custo, `/compact` e outros comandos. Arquivos e fotos vão nos dois sentidos.
- Funciona com o terminal fechado. O celular alcança o computador diretamente (mesmo Wi-Fi, uma VPN como Tailscale, uma porta encaminhada) ou por um relé público gratuito. Tudo é criptografado de ponta a ponta.
- Inicie uma nova sessão no computador numa pasta à sua escolha; adicione um computador por endereço (VPN, porta encaminhada) e não só por QR code.

No computador:

```
devx remote start     # o host, continua rodando sem terminal
devx remote pair      # um QR code: leia no app, confirme os seis dígitos no computador
```

Um celular é removido com `/phones` no devx ou `devx remote revoke <name>`. Mais no [README do devx](https://github.com/apvcode/Termux-Dev#readme).

## Idiomas

English, Русский, Deutsch, Español, Français, 日本語, Português, Українська, 中文: os mesmos nove do devx. **Configurações → Idioma**. O idioma também define o que o microfone escuta.

## Privacidade

- Conversas, memória, habilidades e chaves ficam no celular. Nada vai para nós; não há servidor nosso.
- Suas mensagens vão para o provedor que você escolheu, e para um servidor MCP ou serviço de mapas só quando uma ferramenta precisa.
- Galeria, notificações, localização, contatos e a pasta só são lidos quando você pede, e cada um pode ser desligado.
- Chamadas, mensagens e eventos da agenda nunca são enviados sem o seu último toque.

## Compilar

```
./gradlew assembleRelease      # app/build/outputs/apk/release/app-release.apk
```

JDK 17 e o SDK do Android. Assinatura: coloque `signing.properties` (storeFile, storePassword, keyAlias, keyPassword) em `~/.config/devx-android/`, ou aponte para ele com `DEVX_SIGNING_PROPERTIES`, ou use `keystore.properties` na pasta do projeto. Sem nenhum deles, a compilação é assinada com a chave de depuração e não consegue atualizar uma versão instalada. Guarde bem a chave: é ela que permite que as atualizações sejam instaladas sobre a versão antiga.

O app tem versões próprias. Um comando o compila, confere a chave de assinatura e adiciona `devx-android-X.Y.Z.apk` à página `android-app` do Termux-Dev (pergunta antes; um arquivo que já está lá nunca é substituído; `--dry-run` mostra o que faria):

```
node scripts/release-android.mjs      # executar na pasta devx
```

## Tecnologias

Kotlin, Jetpack Compose, OkHttp, Android Keystore. Os ícones são [Tabler Icons](https://tabler.io/icons) (MIT); o Mermaid vem incluído para diagramas (MIT). Os avisos estão em [NOTICE](NOTICE).

## Licença

O app ainda não tem um arquivo de licença próprio; o devx em si é MIT. Os avisos de terceiros (Tabler Icons, Mermaid) estão em [NOTICE](NOTICE), as licenças das fontes tipográficas em [licenses/](licenses).
