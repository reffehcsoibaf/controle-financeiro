-- ═══════════════════════════════════════════════════════════════
--  Migração: Vínculo entre Documento e Lançamento (v1.11.0)
--  Controle Financeiro
--
--  O que faz:
--  - Adiciona a coluna lancamento_id na tabela financeiro_documentos_armazenados,
--    apontando para o lançamento (financeiro_lancamentos) ao qual aquele
--    documento foi vinculado.
--  - ON DELETE SET NULL: se o lançamento for excluído, o documento NÃO é
--    excluído — ele só perde o vínculo (volta a aparecer como "—" na
--    coluna Lançamento da aba Documentos).
--  - Cria um índice para consultas rápidas pelo lançamento vinculado.
--
--  Como aplicar:
--  1. Abra o painel do Supabase do projeto Controle Financeiro.
--  2. Vá em "SQL Editor" → "New query".
--  3. Cole o conteúdo deste arquivo inteiro e clique em "Run".
--  4. Pronto — é seguro rodar mais de uma vez (usa IF NOT EXISTS).
-- ═══════════════════════════════════════════════════════════════

alter table public.financeiro_documentos_armazenados
  add column if not exists lancamento_id bigint
    references public.financeiro_lancamentos(id)
    on delete set null;

create index if not exists idx_financeiro_documentos_lancamento_id
  on public.financeiro_documentos_armazenados (lancamento_id);

-- ───────────────────────────────────────────────────────────────
-- Garante a política de segurança (RLS) para UPDATE nesta tabela.
-- Até esta versão, o app só fazia SELECT / INSERT / DELETE em
-- financeiro_documentos_armazenados — nunca UPDATE. A partir de agora,
-- vincular/desvincular um documento faz um UPDATE (só na coluna
-- lancamento_id), então é preciso garantir que exista uma política
-- permitindo isso, sem duplicar caso já exista uma.
-- Segue o mesmo padrão (user_id = auth.uid()) das políticas de SELECT/
-- INSERT/DELETE já usadas nas demais tabelas do app.
-- ───────────────────────────────────────────────────────────────
do $$
begin
  if not exists (
    select 1 from pg_policies
    where schemaname = 'public'
      and tablename  = 'financeiro_documentos_armazenados'
      and cmd        = 'UPDATE'
  ) then
    create policy "Usuário atualiza os próprios documentos"
      on public.financeiro_documentos_armazenados
      for update
      using (auth.uid() = user_id)
      with check (auth.uid() = user_id);
  end if;
end $$;
