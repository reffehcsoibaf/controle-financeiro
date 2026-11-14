# Controle Financeiro para Android

App nativo (Kotlin + Jetpack Compose) ligado ao mesmo Supabase do site e do app
Windows. Usado por cegos (TalkBack) e por quem enxerga, na mesma interface.

## Regra número 1: acessibilidade

Nenhuma implementação visual pode atrapalhar o uso com leitor de tela. Se um
recurso visual não tem equivalente falado, ele não entra.

- Uma interface só para os dois públicos. Nada de "modo cego" separado.
- A estrutura vem antes do visual: títulos, campos e botões na ordem de leitura.
- Todo elemento interativo tem nome, papel e estado. Alvo de toque de pelo menos 48 dp.
- Gesto nunca é a única via: deslizar, arrastar e tocar-e-segurar têm botão
  equivalente ou ação de acessibilidade (`customActions`).
- Sem modais nem foco preso. Confirmações e filtros viram tela ou seção da página.
- Mudanças e erros são avisados por região ativa (`liveRegion`) e também visualmente.
- Cor nunca é a única informação (sinal +/−, texto "Pago"/"Pendente").
- O texto falado de um lançamento é montado à parte do visual, a partir do mesmo dado.
- Respeitar tamanho de letra, zoom e tema claro/escuro do sistema.
- Gráfico só com tabela equivalente.
- Os textos que aparecem na tela nunca falam de leitor de tela, TalkBack ou "ouvir": o app serve a
  todos, e quem não usa leitor estranharia. Instruções sobre acessibilidade ficam só nos comentários
  do código e no guia. Há um teste que confere isso (TelaTest).
- Atalhos de teclado no padrão dos outros módulos (Alt+S salva).
- Teste automático confere nome, papel e tamanho mínimo dos elementos.

## Regras do projeto

- Segurança na camada do banco (RLS no Supabase), nunca só na interface.
- Regras de cálculo iguais às do site e do Windows. A referência é
  `financeiro/regras.py` do repositório `controle-financeiro-desktop` (ramo `windows`).
  Se uma mudar, muda nos três lugares.
- Funciona sem internet: Room (SQLite) + fila de sincronização com WorkManager.
- Credenciais nunca no código nem no git.
- Versão MAJOR.MINOR.PATCH, com CHANGELOG.md e número visível na tela de informações.
- Ele testa cada mudança no celular, com o TalkBack, antes de seguir.

## Ferramentas

- JDK 17 e Android SDK em `C:\android` (fora do OneDrive). Ver `ferramentas\instalar_ferramentas.bat`.
- Compilação por linha de comando (Gradle), sem depender do Android Studio.
- Comandos para o Fábio rodar: arquivos `.bat` que gravam a saída em `C:\logs`.

## Ambiente de teste do Fábio

- Celular: Poco X3 GT (Xiaomi, modelo 21061110AG), Android 13, sem chip (usa eSIM no iPhone).
- `adb install` por USB é bloqueado (INSTALL_FAILED_USER_RESTRICTED). O caminho que funciona
  é `ferramentas\copiar_apk_para_celular.bat`, que copia o APK para Download; ele toca no
  arquivo pelo app Arquivos e instala.
- Login com Google funcionando (testado em 2026-11-13). O endereço
  `br.com.fabioscheffer.controlefinanceiro://login` já está nas Redirect URLs do projeto
  Supabase `hubapp`.
