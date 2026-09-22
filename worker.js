// ═══════════════════════════════════════════════════════════════
//  Worker Cloudflare: ler-documento (Controle Financeiro)
//  Recebe uma nota fiscal / comprovante (imagem, PDF, texto ou XML)
//  e extrai dados estruturados para pré-preencher o formulário de
//  lançamento.
//
//  ESTRATÉGIA DE PROVEDOR: tenta Gemini primeiro (grátis); se falhar,
//  tenta o Cloudflare Workers AI (grátis, com limite diário); e só usa
//  a Anthropic (paga) se os dois falharem (erro, rate limit, resposta
//  inválida).
//
//  As chaves de API NUNCA ficam no navegador — vivem só aqui, como
//  variáveis de ambiente GEMINI_API_KEY e ANTHROPIC_API_KEY,
//  configuradas no painel do Cloudflare (Workers & Pages → Worker →
//  Settings → Variables and Secrets), cadastradas como Secret.
//
//  Este arquivo é o "main" do Worker (ver wrangler.jsonc). Intercepta
//  apenas a rota /api/ler-documento; qualquer outra URL é entregue
//  pelos arquivos estáticos do site (binding "ASSETS").
// ═══════════════════════════════════════════════════════════════

const PROMPT_SISTEMA = `Você é um extrator de dados de notas fiscais, recibos, comprovantes de pagamento e comprovantes de transferência bancária brasileiros.

Analise o documento enviado e devolva APENAS um objeto JSON (sem markdown, sem crases, sem texto antes ou depois), com exatamente estas chaves:

{
  "data": "AAAA-MM-DD ou null",
  "data_vencimento": "AAAA-MM-DD ou null (data de vencimento do boleto/fatura, se houver — é uma data DIFERENTE da data de emissão/transação, geralmente escrita como 'vencimento', 'venc.', 'data de vencimento' ou 'pagável até')",
  "valor": número absoluto (positivo, use ponto decimal) — o valor TOTAL da transação, exatamente como consta no documento; null se não identificar,
  "tipo_transacao": "debito" | "credito" | "indefinido" (debito = despesa/pagamento/compra; credito = receita/recebimento; use "indefinido" se não tiver certeza),
  "banco": "nome do banco, instituição financeira ou cartão identificado, ou null",
  "estabelecimento": "nome do estabelecimento, loja ou empresa emissora, ou null",
  "forma_pagamento": "ex.: Pix, Cartão de Crédito, Cartão de Débito, Boleto, Dinheiro, Transferência, ou null",
  "categoria_sugerida": "uma categoria de gasto/receita sugerida em poucas palavras para o lançamento como um todo (ex.: Alimentação, Transporte, Saúde, Mercado, Lazer, Salário), ou null — só preencha se NÃO for usar o campo \"itens\" abaixo",
  "credor": "quem recebe o valor (para quem foi pago), ou null",
  "devedor": "quem paga o valor (pagador/titular do documento), ou null",
  "descricao": "descrição curta e objetiva do lançamento, ou null — só preencha se NÃO for usar o campo \"itens\" abaixo",
  "itens": [ { "valor": número positivo, "categoria_sugerida": "categoria deste item específico, ou null", "descricao": "descrição curta deste item específico, ou null" } ] — ou null,
  "pagamentos": [ { "banco": "banco/cartão usado nesta parte do pagamento", "forma_pagamento": "ex.: Cartão de Débito, Cartão de Crédito, Pix, Dinheiro, ou null", "valor": número positivo desta parte do pagamento } ] — ou null,
  "observacoes": "qualquer detalhe relevante que não se encaixe nos campos acima, ou null"
}

Regras importantes:
- Nunca invente dados que não estejam no documento. Se não tiver certeza de um campo, use null.
- "data" deve ser a data da transação/emissão do documento, não a data atual.
- "data_vencimento" só deve ser preenchida se o documento explicitamente mostrar uma data de
  vencimento (comum em boletos e faturas). Se o documento for um comprovante de pagamento já
  efetuado (Pix, recibo, etc.) sem menção a vencimento, deixe "data_vencimento" como null.
- Datas no formato brasileiro (DD/MM/AAAA) devem ser convertidas para AAAA-MM-DD.
- "valor" é sempre o TOTAL da transação, mesmo quando "itens" for preenchido.
- **Campo "itens" (múltiplos produtos/serviços)**: preencha esta lista APENAS quando o documento
  listar 2 (dois) ou mais produtos/serviços distintos com valores individuais — o caso típico é
  uma nota fiscal de mercado ou de compra com vários produtos. Cada item deve ter seu próprio
  "valor" (sempre positivo), uma "categoria_sugerida" e uma "descricao" curta do próprio item
  (ex.: nome do produto). A soma dos valores dos itens deve bater com o "valor" total informado
  no nível raiz. Quando "itens" tiver 2 ou mais elementos, deixe "categoria_sugerida" e
  "descricao" do nível raiz como null (eles são ignorados nesse caso).
  Se o documento tiver apenas 1 produto/serviço, ou for um comprovante de pagamento único (Pix,
  transferência, boleto, mensalidade), deixe "itens" como null e preencha normalmente
  "categoria_sugerida" e "descricao" no nível raiz.
- **Quantidade/peso de cada item**: se a nota indicar a quantidade (ex.: "2 UN", "3x") ou o peso
  (ex.: "0,536 kg", "536g") de um item da lista "itens", inclua essa informação dentro da própria
  "descricao" daquele item, junto com o nome do produto (ex.: "Banana Prata (0,536 kg)", "Arroz 5kg
  (2 UN)"). Não crie um campo separado para isso — deve ficar embutido no texto da descrição. Só
  inclua quando o documento mostrar esse dado explicitamente; não invente nem assuma quantidade 1
  quando a nota não a exibir.
- **Campo "pagamentos" (pagamento dividido entre bancos/cartões/formas)**: preencha esta lista
  APENAS quando o documento mostrar explicitamente que o valor total foi pago fracionado entre
  2 (duas) ou mais formas/contas de pagamento distintas — o caso típico é uma NFC-e com duas
  linhas de "FORMA PAGAMENTO" / "VALOR PAGO", cada uma com um valor diferente (ex.: "Cartão de
  Débito R$ 6,12" e "Cartão de Débito R$ 27,59"). Cada elemento deve ter seu próprio "valor"
  (sempre positivo), e "banco"/"forma_pagamento" preenchidos com o que o documento indicar para
  aquela parte específica (podem ser iguais entre si, como no exemplo acima, ou diferentes). A
  soma dos valores de "pagamentos" deve bater com o "valor" total informado no nível raiz. Quando
  "pagamentos" tiver 2 ou mais elementos, deixe "banco" e "forma_pagamento" do nível raiz como
  null (eles são ignorados nesse caso). O campo "pagamentos" é completamente independente do
  campo "itens" — um documento pode ter vários produtos (itens) E pagamento dividido
  (pagamentos) ao mesmo tempo, só um dos dois, ou nenhum. Se o documento mostrar apenas 1 forma
  de pagamento, deixe "pagamentos" como null e preencha normalmente "banco" e "forma_pagamento"
  no nível raiz.
- **Banco "Next"**: se o banco/instituição identificado for a fintech Next — mesmo que apareça no
  documento junto com o nome do parceiro emissor do cartão (ex.: "Next Bradesco", "Next 237
  Bradesco S.A.", "Next Bradescard", ou qualquer variação parecida contendo "Next") — preencha o
  campo "banco" apenas com o texto "Next", sem o restante do nome do parceiro.
- **Nomes de credor/devedor**: se o nome vier em CAIXA ALTA ou com capitalização irregular (comum
  em comprovantes bancários, ex.: "FABIO SCHEFFER MORAES"), converta para Capitalização Padrão de
  Nome Próprio (primeira letra de cada palavra em maiúscula; preposições como "de", "da", "dos",
  "e" ficam em minúscula), preservando exatamente as letras do documento — não adicione, remova
  nem "corrija" acentos que não estejam claramente legíveis no documento original.
- Responda SOMENTE com o JSON, nada mais.`;

const TEXTO_INSTRUCAO_ARQUIVO = 'Extraia os dados deste comprovante/nota conforme as instruções.';

// ---- Checagem de acesso à IA: valida o token do usuário e confere ai_enabled ----
async function checarAcessoIA(request, env) {
  const authHeader = request.headers.get('Authorization') || '';
  const accessToken = authHeader.replace(/^Bearer\s+/i, '');

  if (!accessToken) {
    return { ok: false, status: 401, message: 'Sessão não encontrada. Faça login novamente.' };
  }
  if (!env.SUPABASE_URL || !env.SUPABASE_ANON_KEY) {
    return { ok: false, status: 500, message: 'Configuração do Supabase ausente no servidor.' };
  }

  const userResp = await fetch(env.SUPABASE_URL + '/auth/v1/user', {
    headers: { apikey: env.SUPABASE_ANON_KEY, Authorization: 'Bearer ' + accessToken }
  });
  if (!userResp.ok) {
    return { ok: false, status: 401, message: 'Sessão inválida ou expirada. Faça login novamente.' };
  }
  const userData = await userResp.json();

  const profileResp = await fetch(
    env.SUPABASE_URL + '/rest/v1/profiles_modulos?user_id=eq.' + userData.id + '&modulo=eq.financeiro&select=ai_enabled',
    { headers: { apikey: env.SUPABASE_ANON_KEY, Authorization: 'Bearer ' + accessToken } }
  );
  if (!profileResp.ok) {
    return { ok: false, status: 500, message: 'Não foi possível checar sua permissão de uso da IA.' };
  }
  const rows = await profileResp.json();
  if (!rows.length || rows[0].ai_enabled !== true) {
    return { ok: false, status: 403, message: 'O acesso às funcionalidades de IA está desativado para este usuário. Fale com o administrador.' };
  }

  return { ok: true };
}

// Incrementa o contador de uso de IA do usuário logado. Melhor esforço:
// nunca deve quebrar a resposta já obtida para o usuário.
async function registrarUsoIA(accessToken, env) {
  try {
    await fetch(env.SUPABASE_URL + '/rest/v1/rpc/financeiro_increment_ai_calls', {
      method: 'POST',
      headers: {
        apikey: env.SUPABASE_ANON_KEY,
        Authorization: 'Bearer ' + accessToken,
        'Content-Type': 'application/json'
      },
      body: '{}'
    });
  } catch (e) {
    console.log('[uso-ia] falha ao registrar uso (ignorado):', e.message);
  }
}

export default {
  async fetch(request, env, ctx) {
    try {
      return await handleFetch(request, env, ctx);
    } catch (erroFatal) {
      // Rede de segurança: qualquer erro não previsto nos blocos abaixo cai
      // aqui, garantindo que a resposta seja sempre um JSON legível (nunca
      // a página de erro genérica do Cloudflare, que quebra o parse no
      // navegador e mostra só "(500)" sem detalhe nenhum).
      return new Response(
        JSON.stringify({ ok: false, erro: 'Erro inesperado no servidor: ' + (erroFatal && erroFatal.message) }),
        {
          status: 500,
          headers: {
            'Access-Control-Allow-Origin': '*',
            'Access-Control-Allow-Headers': 'Content-Type, Authorization',
            'Access-Control-Allow-Methods': 'POST, OPTIONS',
            'Content-Type': 'application/json',
          }
        }
      );
    }
  },
};

async function handleFetch(request, env, ctx) {
    const url = new URL(request.url);

    // Só tratamos aqui a rota da API. Qualquer outra URL (o próprio site,
    // imagens, etc.) é devolvida pelos arquivos estáticos normalmente.
    if (url.pathname !== '/api/ler-documento') {
      return env.ASSETS.fetch(request);
    }

    const headers = {
      'Access-Control-Allow-Origin': '*',
      'Access-Control-Allow-Headers': 'Content-Type, Authorization',
      'Access-Control-Allow-Methods': 'POST, OPTIONS',
      'Content-Type': 'application/json',
    };

    if (request.method === 'OPTIONS') {
      return new Response(null, { status: 204, headers });
    }
    if (request.method !== 'POST') {
      return new Response(JSON.stringify({ ok: false, erro: 'Método não permitido.' }), { status: 405, headers });
    }

    let acesso;
    try {
      acesso = await checarAcessoIA(request, env);
    } catch (erroChecagem) {
      return new Response(
        JSON.stringify({ ok: false, erro: 'Erro ao checar permissão de IA: ' + erroChecagem.message }),
        { status: 500, headers }
      );
    }
    if (!acesso.ok) {
      return new Response(JSON.stringify({ ok: false, erro: acesso.message }), { status: acesso.status, headers });
    }
    const accessToken = (request.headers.get('Authorization') || '').replace(/^Bearer\s+/i, '');

    let payload;
    try { payload = await request.json(); }
    catch (e) { return new Response(JSON.stringify({ ok: false, erro: 'Corpo da requisição inválido.' }), { status: 400, headers }); }

    const { tipo, conteudo, mimeType, nomeArquivo } = payload || {};

    if (!tipo || !conteudo) {
      return new Response(JSON.stringify({ ok: false, erro: 'Arquivo ou tipo não informado.' }), { status: 400, headers });
    }

    if (!['imagem', 'pdf', 'texto', 'xml'].includes(tipo)) {
      return new Response(JSON.stringify({ ok: false, erro: 'Tipo de arquivo não suportado.' }), { status: 400, headers });
    }

    const documento = { tipo, conteudo, mimeType, nomeArquivo };

    // ---- Correções aprendidas de leituras anteriores (memória de correções) ----
    // O app envia aqui até algumas dezenas de correções que o próprio usuário já
    // fez no passado (banco, estabelecimento, forma de pagamento, categoria, credor
    // ou devedor que a IA sugeriu errado e o usuário corrigiu manualmente antes de
    // salvar). Isso é enviado como texto avulso, FORA do bloco de system prompt (que
    // fica em cache), para não invalidar o cache a cada correção nova e para não
    // misturar dado específico do usuário com a instrução genérica.
    const textoCorrecoes = montarTextoCorrecoes(payload?.correcoesConhecidas);

    // ---- 1ª TENTATIVA: GEMINI (grátis) ----
    let erroGeminiDetalhe = null;
    if (env.GEMINI_API_KEY) {
      try {
        const dados = await lerComGemini({ apiKey: env.GEMINI_API_KEY, documento, textoCorrecoes });
        ctx.waitUntil(registrarUsoIA(accessToken, env));
        return new Response(JSON.stringify({ ok: true, dados: { ...dados, _provedor: 'gemini' } }), { status: 200, headers });
      } catch (erroGemini) {
        erroGeminiDetalhe = String(erroGemini.message || erroGemini).slice(0, 500);
        console.log('[fallback] Gemini falhou, tentando Workers AI:', erroGeminiDetalhe);
        // segue para o Workers AI abaixo
      }
    } else {
      erroGeminiDetalhe = 'GEMINI_API_KEY não configurada neste Worker.';
      console.log('[fallback] GEMINI_API_KEY não configurada, indo direto para Workers AI.');
    }

    // ---- 2ª TENTATIVA: CLOUDFLARE WORKERS AI (grátis, reserva) ----
    let erroWorkersAIDetalhe = null;
    try {
      const dados = await lerComWorkersAI({ env, documento, textoCorrecoes });
      ctx.waitUntil(registrarUsoIA(accessToken, env));
      return new Response(JSON.stringify({ ok: true, dados: { ...dados, _provedor: 'workers-ai' } }), { status: 200, headers });
    } catch (erroWorkersAI) {
      erroWorkersAIDetalhe = String(erroWorkersAI.message || erroWorkersAI).slice(0, 500);
      console.log('[fallback] Workers AI falhou, tentando Anthropic:', erroWorkersAIDetalhe);
    }

    // ---- 3ª TENTATIVA: ANTHROPIC (paga, último recurso) ----
    const detalhesAnteriores =
      (erroGeminiDetalhe ? ' | Detalhe do Gemini: ' + erroGeminiDetalhe : '') +
      (erroWorkersAIDetalhe ? ' | Detalhe do Workers AI: ' + erroWorkersAIDetalhe : '');

    if (!env.ANTHROPIC_API_KEY) {
      return new Response(
        JSON.stringify({ ok: false, erro: 'Gemini e Workers AI falharam, e a ANTHROPIC_API_KEY não está configurada no Cloudflare.' + detalhesAnteriores }),
        { status: 500, headers }
      );
    }

    try {
      const dados = await lerComAnthropic({ apiKey: env.ANTHROPIC_API_KEY, documento, textoCorrecoes });
      ctx.waitUntil(registrarUsoIA(accessToken, env));
      return new Response(JSON.stringify({ ok: true, dados: { ...dados, _provedor: 'anthropic' } }), { status: 200, headers });
    } catch (erroAnthropic) {
      return new Response(
        JSON.stringify({ ok: false, erro: 'Falha ao ler documento (Gemini, Workers AI e Anthropic falharam): ' + erroAnthropic.message + detalhesAnteriores }),
        { status: 502, headers }
      );
    }
}

// Leitura do documento pelo Workers AI. Imagem vai direto para o modelo;
// PDF é convertido em texto antes (os modelos não leem PDF); texto e XML
// já chegam como texto.
async function lerComWorkersAI({ env, documento, textoCorrecoes }) {
  const { tipo, conteudo, mimeType, nomeArquivo } = documento;
  let parteDocumento;
  if (tipo === 'imagem') {
    parteDocumento = { tipo: 'imagem', mimeType: mimeType || 'image/jpeg', base64: conteudo };
  } else if (tipo === 'pdf') {
    const textoPdf = await pdfParaTextoWorkersAI(env, conteudo, nomeArquivo);
    parteDocumento = { tipo: 'texto', texto: 'Conteúdo do PDF "' + (nomeArquivo || 'documento.pdf') + '" (convertido em texto):\n\n' + textoPdf };
  } else {
    const textoLimitado = String(conteudo).slice(0, 60000);
    parteDocumento = { tipo: 'texto', texto: 'Conteúdo do arquivo "' + (nomeArquivo || 'documento') + '":\n\n' + textoLimitado };
  }

  const partes = [];
  if (textoCorrecoes) partes.push({ tipo: 'texto', texto: textoCorrecoes });
  partes.push(parteDocumento);
  if (tipo === 'imagem') partes.push({ tipo: 'texto', texto: TEXTO_INSTRUCAO_ARQUIVO });

  const texto = await executarWorkersAI(env, { sistema: PROMPT_SISTEMA, partes, maxTokens: 4096 });
  const extraido = extrairJSON(texto);
  if (!extraido) throw new Error('Não foi possível interpretar o JSON retornado pelo Workers AI.');
  return extraido;
}

// Nomes de exibição dos campos rastreados pela memória de correções — devem
// bater com os mesmos nomes usados no front-end (CAMPOS_NOMES em index.html).
const NOMES_CAMPO_CORRECAO = {
  banco: 'Banco / Cartão',
  estabelecimento: 'Estabelecimento',
  forma: 'Forma de Pagamento',
  categoria: 'Categoria',
  credor: 'Credor',
  devedor: 'Devedor',
};

function montarTextoCorrecoes(correcoesConhecidas) {
  if (!Array.isArray(correcoesConhecidas) || correcoesConhecidas.length === 0) return null;
  const linhas = correcoesConhecidas.slice(0, 60).map((c) => {
    const campoLabel = NOMES_CAMPO_CORRECAO[c.campo] || c.campo;
    const contexto = c.contexto ? ` (contexto: ${c.contexto})` : '';
    return `- [${campoLabel}] Em vez de "${c.valorErrado}", o usuário já corrigiu para "${c.valorCorreto}"${contexto}.`;
  }).join('\n');
  return `CORREÇÕES APRENDIDAS DE LEITURAS ANTERIORES — o usuário já corrigiu manualmente estas sugestões da IA em documentos passados. Quando o documento atual tratar claramente do mesmo caso (mesmo estabelecimento, banco ou contexto), priorize a preferência já confirmada pelo usuário abaixo em vez da sua própria inferência para aquele campo. Se nenhuma linha combinar com o documento atual, ignore esta lista normalmente:\n${linhas}\n\nAgora, aplicando essas preferências quando fizerem sentido, extraia os dados do documento a seguir, seguindo todas as regras do system prompt.`;
}

// ==================== PROVEDOR: GEMINI ====================
// Lista de modelos candidatos, em ordem de preferência. Se o Google
// descontinuar um (o que tem acontecido com frequência), o próximo da
// lista assume automaticamente na próxima leitura — sem precisar editar
// o Worker toda vez que um nome de modelo for aposentado.
const MODELOS_GEMINI_CANDIDATOS = [
  'gemini-3.1-flash-lite',
  'gemini-flash-latest',
  'gemini-2.5-flash-lite',
  'gemini-3.5-flash',
];

async function lerComGemini({ apiKey, documento, textoCorrecoes }) {
  const parte = montarParteConteudo(documento);
  // Correções aprendidas de leituras anteriores (memória de correções): entram como
  // uma "part" de texto adicional, antes do conteúdo do documento em si.
  const partesConteudo = textoCorrecoes ? [{ text: textoCorrecoes }, parte] : [parte];
  let ultimoErro = null;

  for (const modelo of MODELOS_GEMINI_CANDIDATOS) {
    const url = `https://generativelanguage.googleapis.com/v1beta/models/${modelo}:generateContent?key=${apiKey}`;
    try {
      const resposta = await fetch(url, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          systemInstruction: { parts: [{ text: PROMPT_SISTEMA }] },
          contents: [{ role: 'user', parts: partesConteudo }],
          generationConfig: { temperature: 0 },
        }),
      });

      if (!resposta.ok) {
        const corpoErro = await resposta.text();
        if (resposta.status === 404 || resposta.status === 503) {
          console.log(`[gemini] Modelo "${modelo}" indisponível (${resposta.status}), tentando o próximo candidato.`);
          ultimoErro = new Error(`Gemini (${modelo}) retornou ${resposta.status}: ${corpoErro}`);
          continue;
        }
        throw new Error(`Gemini (${modelo}) retornou ${resposta.status}: ${corpoErro}`);
      }

      const dados = await resposta.json();
      const texto = dados?.candidates?.[0]?.content?.parts?.[0]?.text;
      if (!texto) {
        throw new Error(`Gemini (${modelo}) não retornou texto utilizável (possível bloqueio de segurança ou resposta vazia).`);
      }

      const extraido = extrairJSON(texto);
      if (!extraido) throw new Error(`Não foi possível interpretar o JSON retornado pelo Gemini (${modelo}).`);
      return extraido;
    } catch (e) {
      ultimoErro = e;
      continue;
    }
  }

  throw ultimoErro || new Error('Nenhum modelo Gemini candidato respondeu.');
}

function montarParteConteudo(documento) {
  const { tipo, conteudo, mimeType, nomeArquivo } = documento;

  if (tipo === 'imagem') {
    return { inline_data: { mime_type: mimeType || 'image/jpeg', data: conteudo } };
  }
  if (tipo === 'pdf') {
    return { inline_data: { mime_type: 'application/pdf', data: conteudo } };
  }
  // texto ou xml: já vem como texto puro (não base64)
  const textoLimitado = String(conteudo).slice(0, 60000); // proteção contra arquivos gigantes
  return { text: 'Conteúdo do arquivo "' + (nomeArquivo || 'documento') + '":\n\n' + textoLimitado };
}

// ==================== PROVEDOR: CLOUDFLARE WORKERS AI ====================
// Reserva GRATUITA entre o Gemini e a Anthropic. Roda na própria Cloudflare,
// sem chave de API: usa o binding "AI" declarado no wrangler.jsonc.
//
// Custo: a Cloudflare dá 10.000 "neurons" por dia de graça. No plano
// gratuito de Workers, quando esse limite acaba, a chamada apenas dá erro
// (e aí o app segue para a Anthropic) — nunca gera cobrança surpresa.
//
// Os modelos abertos são mais fracos que Gemini/Claude para documentos
// complexos, por isso ficam como reserva, não como provedor principal.
// Lista em ordem de preferência: se um modelo for aposentado ou falhar,
// o próximo assume automaticamente.
const MODELOS_WORKERS_AI_CANDIDATOS = [
  '@cf/google/gemma-4-26b-a4b-it',           // o mais barato em neurons, lê imagem
  '@cf/meta/llama-4-scout-17b-16e-instruct', // reserva, também lê imagem
];

// partes: lista de { tipo: 'texto', texto } ou { tipo: 'imagem', mimeType, base64 }.
// Devolve o texto bruto da resposta (quem chama faz o parse do JSON).
async function executarWorkersAI(env, { sistema, partes, maxTokens }) {
  if (!env.AI || typeof env.AI.run !== 'function') {
    throw new Error('Binding "AI" (Workers AI) não configurado neste Worker — confira o "ai" no wrangler.jsonc.');
  }

  const conteudo = partes.map((p) => (
    p.tipo === 'imagem'
      ? { type: 'image_url', image_url: { url: `data:${p.mimeType};base64,${p.base64}` } }
      : { type: 'text', text: p.texto }
  ));

  let ultimoErro = null;
  for (const modelo of MODELOS_WORKERS_AI_CANDIDATOS) {
    try {
      const entrada = {
        messages: [
          { role: 'system', content: sistema },
          { role: 'user', content: conteudo },
        ],
        max_tokens: maxTokens || 4096,
        temperature: 0,
        response_format: { type: 'json_object' },
      };
      // Gemma 4 é um modelo de "raciocínio": desligar o pensamento economiza
      // neurons e evita texto extra antes do JSON.
      if (modelo.includes('gemma')) entrada.chat_template_kwargs = { enable_thinking: false };

      const resposta = await env.AI.run(modelo, entrada);
      const texto = extrairTextoWorkersAI(resposta);
      if (!texto) throw new Error('resposta vazia.');
      console.log(`[workers-ai] Resposta obtida com "${modelo}".`);
      return texto;
    } catch (e) {
      ultimoErro = new Error(`Workers AI (${modelo}): ${String(e && e.message || e).slice(0, 400)}`);
      console.log('[workers-ai] ' + ultimoErro.message + ' — tentando o próximo candidato.');
    }
  }
  throw ultimoErro || new Error('Nenhum modelo do Workers AI respondeu.');
}

// Os modelos do Workers AI devolvem em dois formatos diferentes:
// { response: '...' } (Llama) ou { choices: [{ message: { content } }] } (Gemma).
function extrairTextoWorkersAI(resposta) {
  if (!resposta) return '';
  if (typeof resposta.response === 'string') return resposta.response.trim();
  if (resposta.response && typeof resposta.response === 'object') return JSON.stringify(resposta.response);
  const conteudo = resposta?.choices?.[0]?.message?.content;
  if (typeof conteudo === 'string') return conteudo.trim();
  if (Array.isArray(conteudo)) return conteudo.map((c) => c.text || '').join('').trim();
  return '';
}

// Os modelos do Workers AI não leem PDF diretamente. O toMarkdown() da
// própria Cloudflare converte o PDF em texto antes. Funciona com PDF
// "digital" (texto selecionável); PDF escaneado (só imagem) sai sem texto,
// e nesse caso a leitura falha de propósito para cair na Anthropic.
async function pdfParaTextoWorkersAI(env, base64, nomeArquivo) {
  if (!env.AI || typeof env.AI.toMarkdown !== 'function') {
    throw new Error('Binding "AI" (Workers AI) não configurado neste Worker.');
  }
  const binario = atob(base64);
  const bytes = new Uint8Array(binario.length);
  for (let i = 0; i < binario.length; i++) bytes[i] = binario.charCodeAt(i);

  const resultado = await env.AI.toMarkdown({
    name: nomeArquivo || 'documento.pdf',
    blob: new Blob([bytes], { type: 'application/pdf' }),
  });
  const item = Array.isArray(resultado) ? resultado[0] : resultado;
  if (!item || item.format === 'error') {
    throw new Error('Workers AI não conseguiu converter o PDF: ' + (item && item.error || 'sem retorno'));
  }
  const texto = String(item.data || '').trim();
  if (texto.length < 40) {
    throw new Error('PDF sem texto legível (provavelmente escaneado) — Workers AI não lê PDF de imagem.');
  }
  return texto.slice(0, 60000);
}

// ==================== PROVEDOR: ANTHROPIC ====================
async function lerComAnthropic({ apiKey, documento, textoCorrecoes }) {
  const contentBlocks = montarContentBlocksAnthropic(documento);
  // Correções aprendidas de leituras anteriores (memória de correções): entram como
  // um bloco de texto adicional, antes do conteúdo do documento em si.
  const conteudoMensagem = textoCorrecoes ? [{ type: 'text', text: textoCorrecoes }, ...contentBlocks] : contentBlocks;

  const resposta = await fetch('https://api.anthropic.com/v1/messages', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'x-api-key': apiKey, 'anthropic-version': '2023-06-01' },
    body: JSON.stringify({
      model: 'claude-sonnet-5',
      max_tokens: 1536,
      system: PROMPT_SISTEMA,
      messages: [{ role: 'user', content: conteudoMensagem }],
    }),
  });

  const dados = await resposta.json();

  if (!resposta.ok) {
    const msgErro = (dados && dados.error && dados.error.message) || 'Erro ao consultar a IA.';
    throw new Error(msgErro);
  }

  const blocoTexto = (dados.content || []).find((b) => b.type === 'text');
  const extraido = extrairJSON(blocoTexto && blocoTexto.text);
  if (!extraido) throw new Error('Não foi possível interpretar a resposta da IA.');
  return extraido;
}

function montarContentBlocksAnthropic(documento) {
  const { tipo, conteudo, mimeType, nomeArquivo } = documento;

  if (tipo === 'imagem') {
    return [
      { type: 'image', source: { type: 'base64', media_type: mimeType || 'image/jpeg', data: conteudo } },
      { type: 'text', text: TEXTO_INSTRUCAO_ARQUIVO },
    ];
  }
  if (tipo === 'pdf') {
    return [
      { type: 'document', source: { type: 'base64', media_type: 'application/pdf', data: conteudo } },
      { type: 'text', text: TEXTO_INSTRUCAO_ARQUIVO },
    ];
  }
  // texto ou xml
  const textoLimitado = String(conteudo).slice(0, 60000);
  return [{ type: 'text', text: 'Conteúdo do arquivo "' + (nomeArquivo || 'documento') + '":\n\n' + textoLimitado }];
}

// ==================== AUXILIAR ====================
function extrairJSON(texto) {
  if (!texto) return null;
  let limpo = texto.trim();
  limpo = limpo.replace(/^```json\s*/i, '').replace(/^```\s*/i, '').replace(/```\s*$/i, '');
  const inicio = limpo.indexOf('{');
  const fim = limpo.lastIndexOf('}');
  if (inicio === -1 || fim === -1) return null;
  limpo = limpo.slice(inicio, fim + 1);
  try {
    return JSON.parse(limpo);
  } catch (e) {
    return null;
  }
}
