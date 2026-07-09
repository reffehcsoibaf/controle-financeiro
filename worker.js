// ═══════════════════════════════════════════════════════════════
//  Worker Cloudflare: ler-documento (Controle Financeiro)
//  Recebe uma nota fiscal / comprovante (imagem, PDF, texto ou XML)
//  e extrai dados estruturados para pré-preencher o formulário de
//  lançamento.
//
//  ESTRATÉGIA DE PROVEDOR: tenta Gemini primeiro (grátis), e só usa
//  a Anthropic (paga) se o Gemini falhar (erro, rate limit, resposta
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
  "valor": número absoluto (positivo, use ponto decimal), exatamente como consta no documento; null se não identificar,
  "tipo_transacao": "debito" | "credito" | "indefinido" (debito = despesa/pagamento/compra; credito = receita/recebimento; use "indefinido" se não tiver certeza),
  "banco": "nome do banco, instituição financeira ou cartão identificado, ou null",
  "estabelecimento": "nome do estabelecimento, loja ou empresa emissora, ou null",
  "forma_pagamento": "ex.: Pix, Cartão de Crédito, Cartão de Débito, Boleto, Dinheiro, Transferência, ou null",
  "categoria_sugerida": "uma categoria de gasto/receita sugerida em poucas palavras (ex.: Alimentação, Transporte, Saúde, Mercado, Lazer, Salário), ou null",
  "credor": "quem recebe o valor (para quem foi pago), ou null",
  "devedor": "quem paga o valor (pagador/titular do documento), ou null",
  "descricao": "descrição curta e objetiva do lançamento, ou null",
  "observacoes": "qualquer detalhe relevante que não se encaixe nos campos acima, ou null"
}

Regras importantes:
- Nunca invente dados que não estejam no documento. Se não tiver certeza de um campo, use null.
- "data" deve ser a data da transação/emissão do documento, não a data atual.
- Datas no formato brasileiro (DD/MM/AAAA) devem ser convertidas para AAAA-MM-DD.
- Se o documento tiver vários itens/valores, use o valor TOTAL da transação.
- Responda SOMENTE com o JSON, nada mais.`;

const TEXTO_INSTRUCAO_ARQUIVO = 'Extraia os dados deste comprovante/nota conforme as instruções.';

export default {
  async fetch(request, env, ctx) {
    const url = new URL(request.url);

    // Só tratamos aqui a rota da API. Qualquer outra URL (o próprio site,
    // imagens, etc.) é devolvida pelos arquivos estáticos normalmente.
    if (url.pathname !== '/api/ler-documento') {
      return env.ASSETS.fetch(request);
    }

    const headers = {
      'Access-Control-Allow-Origin': '*',
      'Access-Control-Allow-Headers': 'Content-Type',
      'Access-Control-Allow-Methods': 'POST, OPTIONS',
      'Content-Type': 'application/json',
    };

    if (request.method === 'OPTIONS') {
      return new Response(null, { status: 204, headers });
    }
    if (request.method !== 'POST') {
      return new Response(JSON.stringify({ ok: false, erro: 'Método não permitido.' }), { status: 405, headers });
    }

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

    // ---- 1ª TENTATIVA: GEMINI (grátis) ----
    if (env.GEMINI_API_KEY) {
      try {
        const dados = await lerComGemini({ apiKey: env.GEMINI_API_KEY, documento });
        return new Response(JSON.stringify({ ok: true, dados: { ...dados, _provedor: 'gemini' } }), { status: 200, headers });
      } catch (erroGemini) {
        console.log('[fallback] Gemini falhou, tentando Anthropic:', erroGemini.message);
        // segue para a Anthropic abaixo
      }
    } else {
      console.log('[fallback] GEMINI_API_KEY não configurada, indo direto para Anthropic.');
    }

    // ---- 2ª TENTATIVA: ANTHROPIC (paga, fallback) ----
    if (!env.ANTHROPIC_API_KEY) {
      return new Response(
        JSON.stringify({ ok: false, erro: 'Nem GEMINI_API_KEY nem ANTHROPIC_API_KEY estão configuradas no Cloudflare.' }),
        { status: 500, headers }
      );
    }

    try {
      const dados = await lerComAnthropic({ apiKey: env.ANTHROPIC_API_KEY, documento });
      return new Response(JSON.stringify({ ok: true, dados: { ...dados, _provedor: 'anthropic' } }), { status: 200, headers });
    } catch (erroAnthropic) {
      return new Response(
        JSON.stringify({ ok: false, erro: 'Falha ao ler documento (Gemini e Anthropic falharam): ' + erroAnthropic.message }),
        { status: 502, headers }
      );
    }
  },
};

// ==================== PROVEDOR: GEMINI ====================
async function lerComGemini({ apiKey, documento }) {
  const modelo = 'gemini-2.5-flash'; // GA estável, multimodal, confirmado no free tier (não usar nomes "preview", que são desativados sem aviso)
  const url = `https://generativelanguage.googleapis.com/v1beta/models/${modelo}:generateContent?key=${apiKey}`;

  const parte = montarParteConteudo(documento);

  const resposta = await fetch(url, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      systemInstruction: { parts: [{ text: PROMPT_SISTEMA }] },
      contents: [{ role: 'user', parts: [parte] }],
      generationConfig: { temperature: 0 },
    }),
  });

  if (!resposta.ok) {
    const corpoErro = await resposta.text();
    throw new Error(`Gemini retornou ${resposta.status}: ${corpoErro}`);
  }

  const dados = await resposta.json();
  const texto = dados?.candidates?.[0]?.content?.parts?.[0]?.text;
  if (!texto) {
    throw new Error('Gemini não retornou texto utilizável (possível bloqueio de segurança ou resposta vazia).');
  }

  const extraido = extrairJSON(texto);
  if (!extraido) throw new Error('Não foi possível interpretar o JSON retornado pelo Gemini.');
  return extraido;
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

// ==================== PROVEDOR: ANTHROPIC ====================
async function lerComAnthropic({ apiKey, documento }) {
  const contentBlocks = montarContentBlocksAnthropic(documento);

  const resposta = await fetch('https://api.anthropic.com/v1/messages', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'x-api-key': apiKey, 'anthropic-version': '2023-06-01' },
    body: JSON.stringify({
      model: 'claude-sonnet-5',
      max_tokens: 1024,
      system: PROMPT_SISTEMA,
      messages: [{ role: 'user', content: contentBlocks }],
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
