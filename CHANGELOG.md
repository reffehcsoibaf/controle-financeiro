# Changelog — Controle Financeiro

Todas as mudanças relevantes do app ficam registradas aqui, da mais recente para a mais antiga.
O número de versão aparece no rodapé do próprio app, então é sempre possível conferir qual versão
está publicada e comparar com o que está descrito aqui.

## v1.8.0 — 26/07/2026

- **Opção "Outro (digitar uma vez)" nas caixas combinadas**: os 7 campos de lista do formulário
  de Novo Lançamento (Banco/Cartão, Estabelecimento, Forma de Pagamento, Parcela, Categoria,
  Credor, Devedor) — e também a Categoria de cada item, nos lançamentos com Itens Múltiplos —
  agora têm uma opção fixa no topo da lista: **"✏️ Outro (digitar uma vez)"**. Ao escolhê-la, um
  campo de texto aparece ao lado para digitar um valor avulso, que é usado normalmente no
  lançamento mas **nunca é gravado** nas listas permanentes (Gerenciar Listas). Serve para valores
  que você só vai usar uma única vez e não vale a pena cadastrar (ex.: um estabelecimento
  visitado uma vez só, ou uma observação pontual de banco/categoria).
  - O leitor de tela anuncia automaticamente quando o campo de texto aparece, e o foco vai
    direto para ele.
  - Se você salvar (ou tentar salvar) sem digitar nada no campo "Outro", o app avisa e mantém o
    foco ali, sem deixar o lançamento ir para a nuvem com um campo vazio.
  - Ao editar um lançamento que tenha algum desses campos preenchido com um valor que não existe
    (mais) nas listas cadastradas — seja porque foi salvo como texto avulso, seja porque a opção
    foi removida depois — o formulário agora reconhece isso automaticamente e volta a mostrar o
    texto original no modo "Outro", em vez de aparecer em branco.

## v1.7.0 — 24/07/2026

- **Leitor de Notas com IA — itens múltiplos automáticos**: quando o documento enviado (nota
  fiscal, recibo etc.) listar 2 ou mais produtos/serviços com valores individuais, o app agora
  preenche automaticamente a seção "Itens do Lançamento" com um item para cada um, já com valor,
  categoria (quando reconhecida entre as já cadastradas) e descrição. Documentos com apenas 1
  item ou comprovantes de pagamento único (Pix, transferência, boleto) continuam preenchendo o
  lançamento como simples, sem usar a seção de Itens — como sempre foi.
- **Leitor de Notas com IA — Data Vencimento**: quando o documento for um boleto ou fatura com
  data de vencimento explícita, esse campo também é preenchido automaticamente (antes só a Data
  Origem era preenchida pela IA).
- **Nova função: Memória de Correções da IA** — mesmo recurso já validado no Banca Pro, adaptado
  ao Controle Financeiro. Sempre que você corrige, antes de salvar, um valor de Banco/Cartão,
  Estabelecimento, Forma de Pagamento, Categoria, Credor ou Devedor que o Leitor de Notas sugeriu,
  o app memoriza essa correção automaticamente. Nas próximas leituras, essas preferências já
  confirmadas são enviadas para a IA priorizar em vez de repetir o mesmo erro de inferência. Nova
  seção "🧠 Correções Aprendidas (IA)" na aba Configurações, com a lista de correções memorizadas
  e um botão para apagar tudo, se quiser que a IA volte a inferir do zero.
  **Requer rodar a migração SQL `migracao_correcoes_ia.sql` uma única vez no Supabase** (cria a
  tabela `correcoes_ia`, com RLS por usuário) — sem isso, o recurso fica desativado
  silenciosamente (o resto do app continua funcionando normalmente).

## v1.6.0 — 18/07/2026

- **Seleção de linha na tabela de Lançamentos**: cada linha agora pode ser selecionada — clique
  nela, ou navegue até ela pelo teclado (Tab, ou o modo de navegação por tabela do NVDA,
  Ctrl+Alt+Setas) e pressione Enter para dar foco real a ela. Isso habilita quatro novos atalhos
  de teclado:
  - **Alt+P** — marca/desmarca a linha selecionada como Pago (equivale a clicar na caixinha).
  - **Alt+E** — edita a linha selecionada (leva até a aba Novo Lançamento já preenchida).
  - **Alt+X** — exclui a linha selecionada (pede confirmação, como o botão 🗑).
  - **Alt+S** — salva o formulário de Novo Lançamento a qualquer momento, mesmo estando em outra
    aba.
  Uma dica com essas quatro combinações aparece acima da tabela, na aba Lançamentos.
- A caixinha "Pago" e os botões "✏️ Editar"/"🗑 Excluir" de cada linha continuam funcionando
  normalmente por clique; o que mudou é que agora não fazem mais parte da navegação por Tab
  isolada — a linha inteira é o ponto de parada do Tab, e as ações ficam nos atalhos acima.

## v1.5.2 — 17/07/2026

- **Ordem das abas alterada**: "Novo Lançamento" agora é a 1ª aba (e a que abre por padrão ao
  entrar no app) e "Lançamentos" (a tabela) passou a ser a 2ª. As demais abas mantêm a mesma
  posição. Os atalhos de teclado foram remapeados de acordo: **Alt+1** agora abre Novo Lançamento
  e **Alt+2** abre Lançamentos.
- Guia de Uso atualizado para refletir a nova ordem e os atalhos corretos.

## v1.5.1 — 17/07/2026

- **Mudança no cálculo do limite de Cartão de Crédito**: agora o app usa **só o campo
  Banco/Cartão** — não exige mais que a Categoria seja "Cartão de Crédito". Isso libera a
  Categoria pra descrever o gasto de verdade (Alimentação, Transporte, etc.) em compras no
  cartão, sem prejudicar o cálculo do limite. Continua contando lançamentos Pago e Pendente
  (compra parcelada e fatura em aberto consomem limite mesmo antes de "Pago").
- Guia de Uso (Saldos → Contas e Cartões) atualizado para refletir essa mudança e a correção da
  v1.5.0 (saldo de conta corrente só considera lançamentos Pago).

## v1.5.0 — 17/07/2026

- **Correção no saldo de Conta Corrente**: o cálculo agora considera **apenas os lançamentos
  marcados como Pago**. Lançamentos Pendentes são coisas agendadas/previstas que ainda não
  aconteceram de verdade na conta, então não devem mais afetar o "Saldo atual" exibido em Saldos.
  (O limite de Cartão de Crédito **não** mudou nesse ponto — pendentes continuam contando, porque
  normalmente representam compra já feita com fatura em aberto.)
- **Correção no limite de Cartão de Crédito**: a comparação que identifica lançamentos como
  "Cartão de Crédito" (usada para calcular o limite consumido) agora ignora diferenças de acento,
  maiúscula/minúscula e espaços extras — a mesma técnica de comparação tolerante já usada pelo
  Leitor de Notas por IA. Antes, qualquer diferença sutil no texto da Categoria (por exemplo, um
  acento digitado de forma diferente) fazia a comparação falhar silenciosamente, e o limite nunca
  mudava, não importava o que fosse lançado.

## v1.4.2 — 17/07/2026

- **Correção em Saldos**: os botões "Salvar" e "🔍 Ver Lançamentos" de cada conta/cartão podiam,
  em casos raros, operar sobre um nome de banco montado de forma diferente do valor realmente
  cadastrado — o nome era remontado dentro do próprio comando de clique, um caminho separado e
  mais frágil do que o usado no resto da tela. Isso já tinha sido corrigido numa versão anterior,
  mas a correção não tinha chegado a esta base (que seguiu de um ponto anterior do
  desenvolvimento). Ambos os botões agora sempre leem o nome do banco/cartão do mesmo lugar usado
  para exibi-lo na tela, eliminando esse tipo de descompasso.

## v1.4.1 — 15/07/2026

- **Correção de acessibilidade**: os três botões de seleção de arquivo (Leitor de Notas IA,
  Guardar Novo Arquivo e Importar XLSX) geravam **dois pontos de foco** para uma única ação —
  o botão estilizado e, logo em seguida, o `<input type="file">` real (que o NVDA anunciava
  como "...Nenhum arquivo escolhido"). O input real agora fica fora da navegação por Tab e fora
  da árvore de acessibilidade (`tabindex="-1"` + `aria-hidden="true"`); o botão continua
  funcionando normalmente (clique e Enter/Espaço), só que sem duplicar o anúncio no leitor de
  tela.

## v1.4.0 — 15/07/2026

- **Novas abas dedicadas**: o formulário "Novo Lançamento" (com o Leitor de Notas por IA) e os
  "Filtros" saíram da aba Lançamentos e ganharam abas próprias. A aba Lançamentos agora mostra só
  a tabela. Atalhos de teclado atualizados para **Alt+1** a **Alt+6** (Lançamentos, Novo
  Lançamento, Filtros, Saldos, Documentos, Configurações). O botão "🔍 Ver Lançamentos" (aba
  Saldos) e o botão "✏️ Editar" (na tabela) agora levam automaticamente até a aba certa.
- **Leitor de Notas com IA — banco Next**: comprovantes do banco Next (que costumam vir com o
  nome do parceiro emissor junto, ex.: "Next Bradesco", "Next 237 Bradesco S.A.") agora marcam
  direto a opção "Next" já cadastrada no campo Banco/Cartão, em vez de sugerir uma opção nova.
- **Leitor de Notas com IA — nomes de Credor/Devedor**: nomes que vêm em CAIXA ALTA ou com
  acentuação diferente da cadastrada (ex.: "FABIO SCHEFFER MORAES" no comprovante vs. "Fábio
  Scheffer Moraes" já cadastrado) agora são reconhecidos como a mesma pessoa e a opção já
  cadastrada é selecionada automaticamente.

## v1.3.0 — 14/07/2026

- **Paginação na tabela de Lançamentos**: a lista agora é dividida em páginas em vez de carregar
  tudo de uma vez. Barra de navegação abaixo da tabela com botões Primeira, Anterior, Próxima e
  Última página, e indicação de "X–Y de Z registros".
- Nova opção **"Itens por página"** na aba Configurações (seção "Exibição"), com escolha entre
  10, 25, 50, 100, 200 ou "Mostrar todos (sem paginação)". A preferência fica salva no navegador.
- Aba **Saldos**: novo botão **"🔍 Ver Lançamentos"** em cada conta/cartão configurado — filtra a
  aba Lançamentos exatamente pelo mesmo conjunto de registros usado no cálculo do saldo/limite
  daquela conta, para conferir data por data se o valor calculado bate com a realidade.

## v1.2.0 — 12/07/2026

- Atalhos de teclado **Alt+1** a **Alt+4** para pular direto para qualquer aba (Lançamentos,
  Saldos, Documentos, Configurações) de qualquer lugar da tela, sem precisar navegar até a barra
  de abas primeiro. O foco vai para a aba escolhida e o leitor de tela anuncia a troca.
- Nova página `changelog.html`, com o mesmo estilo visual do guia de uso, mostrando o histórico de
  versões de forma organizada (sem precisar abrir o `CHANGELOG.md` bruto).
- Link para o changelog adicionado ao lado do link "Ajuda" no cabeçalho do app, na tela de login
  (ao lado do link do guia de uso), e no cabeçalho do próprio guia de uso — os três se
  interligam.

## v1.1.0 — 10/07/2026

- Aba Documentos: além de buscar por nome do arquivo, agora dá pra filtrar por período (data em
  que o documento foi guardado, de/até). Útil quando o nome do arquivo é genérico demais (ex.:
  "comprovante.pdf") e não ajuda a diferenciar.
- Botão "Limpar Filtro" para voltar a ver todos os documentos de uma vez.
- Wiki atualizado: nova seção "Documentos" cobrindo guardar/analisar/baixar/excluir/buscar, e
  correções na Visão Geral e na aba Saldos (o app agora tem 4 abas, não 3, e o Resumo em Tempo
  Real ficou sempre visível no topo em vez de exclusivo da aba Saldos).

## v1.0.0 — 10/07/2026

Primeira versão com número de versão rastreado. Este marco reúne tudo que já estava em produção
até esta data:

### Leitor de Notas e Comprovantes (IA)
- Leitura automática de notas fiscais e comprovantes via IA, com pré-preenchimento do formulário de
  lançamento (data, valor, banco, categoria, forma de pagamento, credor, devedor, descrição).
- Suporte a imagem, PDF, TXT, XML e XLSX, além de texto colado diretamente (sem precisar de arquivo).
- Estratégia de provedor: tenta Google Gemini primeiro (gratuito) e usa Anthropic Claude como fallback
  automático se o Gemini falhar.
- Sugestões da IA que não batem com opções já cadastradas (banco, categoria etc.) aparecem com botão
  de adicionar rápido.

### Lançamentos
- Campo de valor com seletor "Entrada / Saída" — não é mais preciso digitar sinal negativo; o sinal é
  aplicado automaticamente conforme o tipo escolhido, tanto em lançamentos simples quanto com itens.
- Lançamentos com múltiplos itens: cada item pode ter valor negativo para representar desconto,
  descontado do total antes de aplicar o sinal geral do lançamento.
- Parcelamento com o mesmo seletor Entrada/Saída, herdando o tipo do lançamento principal.
- Descrição em campo multilinhas.

### Contas e Cartões
- Configuração de cada Banco/Cartão como Conta Corrente (com saldo inicial) ou Cartão de Crédito
  (com limite total).
- Saldo de conta corrente e limite de cartão calculados automaticamente a partir dos lançamentos
  (Categoria "Cartão de Crédito" + Entrada/Saída definem o consumo e a quitação de fatura).

### Documentos Armazenados
- Upload de notas/comprovantes para um bucket privado no Supabase Storage, com metadados numa tabela
  própria (nome, tamanho, data).
- Cada usuário só acessa os próprios arquivos (RLS por pasta).
- Ações por documento: analisar com IA, baixar o arquivo original, ou excluir.
- Busca por nome de arquivo.

### Navegação e organização
- App reorganizado em abas: Lançamentos, Saldos, Documentos e Configurações — funciona igual em
  desktop e celular, com navegação por teclado (setas, Home/End) e anúncios para leitor de tela.
- Resumo em Tempo Real (saldo total, pago, pendente, contagem) sempre visível no topo, em qualquer aba.
- Aba Configurações reúne exportar/importar XLSX, remover duplicatas, e gerenciamento das listas
  (Banco, Categoria, Credor, Devedor, Forma de Pagamento, Parcela, Estabelecimento).

### Infraestrutura
- Migrado de Netlify para Cloudflare Workers (arquivos estáticos + rota `/api/ler-documento`).
- Chaves de API (Gemini, Anthropic) nunca ficam no navegador — vivem só como variáveis de ambiente
  no Cloudflare.

---

## Como usar este changelog daqui pra frente

A cada mudança relevante entregue, um novo bloco de versão é adicionado no topo deste arquivo,
seguindo o padrão:

```
## vX.Y.Z — DD/MM/AAAA
- O que mudou, em linguagem direta.
```

- **X (major)**: mudança estrutural grande ou que quebra algo do funcionamento anterior.
- **Y (minor)**: novo recurso.
- **Z (patch)**: correção de bug ou ajuste pequeno.

O rodapé do app (`index.html`) é atualizado junto, na mesma entrega, para sempre bater com este arquivo.
