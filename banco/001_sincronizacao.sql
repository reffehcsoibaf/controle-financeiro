-- Acréscimos para o programa Windows. Só acrescenta: nada existente muda, e o
-- site continua funcionando igual.
--
-- client_uuid: identifica um lançamento criado sem internet. Se a conexão cair
-- depois de gravar, o reenvio não cria um lançamento duplicado.
--
-- financeiro_salvar_lancamento: grava lançamento, itens e pagamentos numa
-- transação só, e recusa a gravação se o lançamento mudou desde que foi lido.
-- Roda com as permissões de quem chama, então o RLS continua valendo.

alter table public.financeiro_lancamentos
  add column if not exists client_uuid uuid;

create unique index if not exists financeiro_lancamentos_client_uuid_unico
  on public.financeiro_lancamentos (user_id, client_uuid)
  where client_uuid is not null;

create or replace function public.financeiro_salvar_lancamento(
  p_id bigint,
  p_base timestamptz,
  p_client_uuid uuid,
  p_dados jsonb,
  p_itens jsonb,
  p_pagamentos jsonb
) returns jsonb
language plpgsql
security invoker
set search_path = public
as $$
declare
  v_user uuid := auth.uid();
  v_id bigint;
  v_atual timestamptz;
begin
  if v_user is null then
    raise exception 'sem sessão';
  end if;

  if p_id is null then
    -- Reenvio de uma criação que já chegou: devolve o que foi gravado
    select id, updated_at into v_id, v_atual
      from financeiro_lancamentos
     where user_id = v_user and client_uuid = p_client_uuid;
    if found then
      return jsonb_build_object('status', 'ok', 'id', v_id, 'updated_at', v_atual);
    end if;

    insert into financeiro_lancamentos (
      user_id, client_uuid, data_origem, data_vencimento, valor, banco,
      estabelecimento, forma, parcela, descricao, categoria, credor, devedor,
      pago)
    values (
      v_user, p_client_uuid,
      (p_dados->>'data_origem')::date, (p_dados->>'data_vencimento')::date,
      (p_dados->>'valor')::numeric, p_dados->>'banco',
      p_dados->>'estabelecimento', p_dados->>'forma', p_dados->>'parcela',
      p_dados->>'descricao', p_dados->>'categoria', p_dados->>'credor',
      p_dados->>'devedor', coalesce((p_dados->>'pago')::boolean, false))
    returning id into v_id;
  else
    select updated_at into v_atual
      from financeiro_lancamentos
     where id = p_id and user_id = v_user
       for update;
    if not found then
      return jsonb_build_object('status', 'conflito');
    end if;
    if v_atual is distinct from p_base then
      return jsonb_build_object('status', 'conflito');
    end if;

    update financeiro_lancamentos set
      data_origem = (p_dados->>'data_origem')::date,
      data_vencimento = (p_dados->>'data_vencimento')::date,
      valor = (p_dados->>'valor')::numeric,
      banco = p_dados->>'banco',
      estabelecimento = p_dados->>'estabelecimento',
      forma = p_dados->>'forma',
      parcela = p_dados->>'parcela',
      descricao = p_dados->>'descricao',
      categoria = p_dados->>'categoria',
      credor = p_dados->>'credor',
      devedor = p_dados->>'devedor',
      pago = coalesce((p_dados->>'pago')::boolean, false)
    where id = p_id and user_id = v_user;

    delete from financeiro_itens_lancamento where lancamento_id = p_id;
    delete from financeiro_pagamentos_lancamento where lancamento_id = p_id;
    v_id := p_id;
  end if;

  insert into financeiro_itens_lancamento (
    user_id, lancamento_id, ordem, valor, categoria, descricao, data_original)
  select v_user, v_id, (e.ordem - 1)::int, (e.valor->>'valor')::numeric,
         e.valor->>'categoria', e.valor->>'descricao',
         (e.valor->>'data_original')::date
    from jsonb_array_elements(coalesce(p_itens, '[]'::jsonb))
         with ordinality as e(valor, ordem);

  insert into financeiro_pagamentos_lancamento (
    user_id, lancamento_id, ordem, banco, forma, valor)
  select v_user, v_id, (e.ordem - 1)::int, e.valor->>'banco', e.valor->>'forma',
         (e.valor->>'valor')::numeric
    from jsonb_array_elements(coalesce(p_pagamentos, '[]'::jsonb))
         with ordinality as e(valor, ordem);

  select updated_at into v_atual from financeiro_lancamentos where id = v_id;
  return jsonb_build_object('status', 'ok', 'id', v_id, 'updated_at', v_atual);
end;
$$;

revoke all on function public.financeiro_salvar_lancamento(
  bigint, timestamptz, uuid, jsonb, jsonb, jsonb) from public, anon;
grant execute on function public.financeiro_salvar_lancamento(
  bigint, timestamptz, uuid, jsonb, jsonb, jsonb) to authenticated;
