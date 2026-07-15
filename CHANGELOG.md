# Changelog — Controle Financeiro

Todas as mudanças relevantes do app ficam registradas aqui, da mais recente para a mais antiga.
O número de versão aparece no rodapé do próprio app, então é sempre possível conferir qual versão
está publicada e comparar com o que está descrito aqui.

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
