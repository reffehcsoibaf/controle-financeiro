-- Aplicada em 30/10/2026 (migração endurecer_funcoes). Fecha funções que
-- respondiam sem login.
-- Nenhum dado muda. Para desfazer uma linha de "revoke", basta o "grant" igual.

-- 1. Só o Admin usa, com a chave de serviço: tira de todo o resto.
revoke execute on function public.storage_usage_por_usuario(text)
  from public, anon, authenticated;
revoke execute on function public.uso_total_projeto()
  from public, anon, authenticated;
grant execute on function public.storage_usage_por_usuario(text) to service_role;
grant execute on function public.uso_total_projeto() to service_role;

-- 2. Os Workers chamam com o token de quem está logado: basta authenticated.
revoke execute on function public.banca_increment_ai_calls(text) from public, anon;
revoke execute on function public.financeiro_increment_ai_calls() from public, anon;
revoke execute on function public.contracheque_increment_ai_calls() from public, anon;
grant execute on function public.banca_increment_ai_calls(text) to authenticated, service_role;
grant execute on function public.financeiro_increment_ai_calls() to authenticated, service_role;
grant execute on function public.contracheque_increment_ai_calls() to authenticated, service_role;

-- 3. Só roda como gatilho do cadastro; ninguém precisa chamar diretamente.
revoke execute on function public.handle_new_user()
  from public, anon, authenticated;

-- 4. Nenhum código atual chama; qualquer conta logada poderia mexer no horário.
revoke execute on function public.banca_reservar_slot_api_football(integer)
  from public, anon, authenticated;
grant execute on function public.banca_reservar_slot_api_football(integer) to service_role;

-- 5. Fixa onde cada função procura as tabelas (aviso "search_path mutável").
alter function public.uso_total_projeto() set search_path = public;
alter function public.storage_usage_por_usuario(text) set search_path = public;
alter function public.handle_new_user() set search_path = public;
alter function public.modulo_habilitado(text) set search_path = public;
alter function public.set_updated_at() set search_path = public;
alter function public.banca_increment_ai_calls(text) set search_path = public;
alter function public.financeiro_increment_ai_calls() set search_path = public;
alter function public.contracheque_increment_ai_calls() set search_path = public;
alter function public.banca_reservar_slot_api_football(integer) set search_path = public;

-- modulo_habilitado continua como está: as regras de acesso (RLS) dependem dela,
-- e sem login ela só responde "não".
