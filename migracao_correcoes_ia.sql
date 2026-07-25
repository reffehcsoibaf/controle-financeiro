-- ═══════════════════════════════════════════════════════════════
--  Migração: tabela "correcoes_ia" (memória de correções da IA)
--  Controle Financeiro — rode este script uma única vez no
--  Supabase SQL Editor do projeto do Controle Financeiro.
-- ═══════════════════════════════════════════════════════════════

create table if not exists public.correcoes_ia (
  id            bigint generated always as identity primary key,
  user_id       uuid not null references auth.users(id) on delete cascade,
  campo         text not null check (campo in ('banco','estabelecimento','forma','categoria','credor','devedor')),
  valor_errado  text not null,
  valor_correto text not null,
  contexto      text,
  criado_em     timestamptz not null default now()
);

-- Evita duas linhas idênticas (mesmo campo + mesmo par errado/correto) para o
-- mesmo usuário — o app já checa isso antes de inserir, isto é só uma rede de
-- segurança extra a nível de banco.
create unique index if not exists correcoes_ia_unica
  on public.correcoes_ia (user_id, campo, valor_errado, valor_correto);

create index if not exists correcoes_ia_user_idx on public.correcoes_ia (user_id);

alter table public.correcoes_ia enable row level security;

create policy "Usuários gerenciam suas próprias correções aprendidas"
  on public.correcoes_ia
  for all
  using (auth.uid() = user_id)
  with check (auth.uid() = user_id);
