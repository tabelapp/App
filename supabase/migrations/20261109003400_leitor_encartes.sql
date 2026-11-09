-- =============================================================================
-- Versão 1.0.4: leitor de encartes da aba Admin (09/11/2026)
--
--  * leituras_encarte: uma linha por leitura feita pela função `ler-encarte`
--    (quando, quem, nº de itens, sucesso/erro), para controlar o custo e o
--    limite diário. Só o Admin lê; ninguém escreve direto pela API (a função
--    grava com a chave de serviço). A imagem do encarte NÃO é guardada.
--  * admin_lancar_encarte(loja, endereço, itens): lança os itens conferidos
--    pelo Admin como preços de encarte (fonte usuario_encarte, loja livre),
--    um lote novo por chamada. Tudo validado aqui no servidor.
--  * admin_desfazer_lote(lote): apaga só os preços de encarte daquele lote
--    lançados nas últimas 48 h (corrigir um erro).
--  * admin_lojas_encarte(): sugestões de loja/endereço já usados em encartes.
--
-- Só itens novos (tabela e funções): apps 1.0.1 a 1.0.3 não mudam. Idempotente.
-- =============================================================================

create table if not exists public.leituras_encarte (
  id         bigint generated always as identity primary key,
  criado_em  timestamptz not null default now(),
  admin_id   uuid references public.usuarios (id) on delete set null,
  itens      integer not null default 0 check (itens >= 0),
  ignorados  integer not null default 0 check (ignorados >= 0),
  sucesso    boolean not null,
  -- Código curto do erro (ex.: falha_leitura); nunca a mensagem técnica nem a chave.
  erro       text check (erro is null or length(erro) <= 60)
);
create index if not exists leituras_encarte_admin_idx on public.leituras_encarte (admin_id, criado_em);

alter table public.leituras_encarte enable row level security;
drop policy if exists leituras_encarte_admin on public.leituras_encarte;
create policy leituras_encarte_admin on public.leituras_encarte for select to authenticated using (public.is_admin());
revoke insert, update, delete, truncate on public.leituras_encarte from public, anon, authenticated;

-- -----------------------------------------------------------------------------
-- Lançar os itens conferidos
-- -----------------------------------------------------------------------------
drop function if exists public.admin_lancar_encarte(text, text, jsonb);
create function public.admin_lancar_encarte(p_loja_nome text, p_endereco text, p_itens jsonb)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_lote      uuid := gen_random_uuid();
  v_loja      text := btrim(coalesce(p_loja_nome, ''));
  v_endereco  text := btrim(coalesce(p_endereco, ''));
  v_invalidos jsonb;
  v_n         integer;
begin
  if not public.is_admin() then
    raise exception 'sem_permissao';
  end if;
  if length(v_loja) = 0 or length(v_loja) > 120 then
    raise exception 'loja_obrigatoria';
  end if;
  if length(v_endereco) = 0 or length(v_endereco) > 200 then
    raise exception 'endereco_obrigatorio';
  end if;
  if p_itens is null or jsonb_typeof(p_itens) <> 'array' or jsonb_array_length(p_itens) = 0 then
    raise exception 'itens_vazios';
  end if;
  if jsonb_array_length(p_itens) > 300 then
    raise exception 'itens_demais';
  end if;

  -- Todas as linhas com problema de uma vez (o app mostra quais).
  select jsonb_agg(jsonb_build_object('linha', ordem, 'erro', erro) order by ordem) into v_invalidos
  from (
    -- Uma conferência por CASE (cada CASE garante a ordem, então nenhum cast falha); o COALESCE
    -- devolve o primeiro problema encontrado.
    select ordem,
      coalesce(
        case when jsonb_typeof(e) <> 'object' then 'item_invalido' end,
        case
          when length(btrim(coalesce(e ->> 'produto', ''))) = 0 then 'produto_vazio'
          when length(btrim(e ->> 'produto')) > 200 then 'produto_longo'
        end,
        case
          when coalesce(e ->> 'preco_centavos', '') !~ '^[0-9]{1,8}$' then 'preco_invalido'
          when (e ->> 'preco_centavos')::bigint <= 0 then 'preco_invalido'
        end,
        case when coalesce(e ->> 'preco_app', 'false') not in ('true', 'false') then 'preco_app_invalido' end,
        case
          when jsonb_typeof(coalesce(e -> 'preco_normal_centavos', 'null'::jsonb)) = 'null' then null
          when coalesce(e ->> 'preco_normal_centavos', '') !~ '^[0-9]{1,8}$' then 'normal_invalido'
          when coalesce(e ->> 'preco_centavos', '') !~ '^[0-9]{1,8}$' then 'preco_invalido'
          when (e ->> 'preco_normal_centavos')::bigint <= (e ->> 'preco_centavos')::bigint then 'normal_invalido'
          when coalesce(e ->> 'preco_app', 'false') <> 'true' then 'normal_sem_app'
        end,
        case when length(coalesce(e ->> 'obs', '')) > 140 then 'obs_longa' end,
        case when coalesce(e ->> 'unidade', 'unidade')
             not in (select unnest(enum_range(null::public.unidade_medida))::text) then 'unidade_invalida' end,
        case
          when coalesce(e ->> 'validade', '') !~ '^\d{4}-\d{2}-\d{2}$' then 'validade_invalida'
          when (e ->> 'validade')::date < public.hoje() - 30 then 'validade_vencida'
          when (e ->> 'validade')::date > public.hoje() + 60 then 'validade_longa'
        end
      ) as erro
    from jsonb_array_elements(p_itens) with ordinality as t(e, ordem)
  ) v
  where erro is not null;
  if v_invalidos is not null then
    raise exception 'itens_invalidos' using detail = v_invalidos::text;
  end if;

  -- O mesmo produto pelo mesmo preço no lote entra uma vez; lançamentos iguais já existentes
  -- são ignorados pelo gatilho de repetidos, e cada linha vai para o histórico.
  insert into public.cotacoes
    (loja_id, pdv_nome_livre, pdv_endereco_livre, produto, preco_centavos, preco_normal_centavos, preco_app,
     validade, obs, unidade, fonte, enviado_por, lote_id)
  select distinct on (public.normalizar(btrim(e ->> 'produto')), (e ->> 'preco_centavos')::integer)
         null, v_loja, v_endereco,
         btrim(e ->> 'produto'),
         (e ->> 'preco_centavos')::integer,
         nullif(e ->> 'preco_normal_centavos', '')::integer,
         coalesce((e ->> 'preco_app')::boolean, false),
         (e ->> 'validade')::date,
         nullif(btrim(coalesce(e ->> 'obs', '')), ''),
         coalesce(e ->> 'unidade', 'unidade')::public.unidade_medida,
         'usuario_encarte', auth.uid(), v_lote
  from jsonb_array_elements(p_itens) with ordinality as t(e, ordem)
  order by public.normalizar(btrim(e ->> 'produto')), (e ->> 'preco_centavos')::integer, ordem;
  get diagnostics v_n = row_count;

  return jsonb_build_object('lote_id', v_lote, 'lancados', v_n, 'recebidos', jsonb_array_length(p_itens));
end;
$$;
revoke execute on function public.admin_lancar_encarte(text, text, jsonb) from public, anon;
grant execute on function public.admin_lancar_encarte(text, text, jsonb) to authenticated;

-- -----------------------------------------------------------------------------
-- Desfazer um lote (só encarte, só nas últimas 48 h)
-- -----------------------------------------------------------------------------
drop function if exists public.admin_desfazer_lote(uuid);
create function public.admin_desfazer_lote(p_lote uuid)
returns integer
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_n integer;
begin
  if not public.is_admin() then
    raise exception 'sem_permissao';
  end if;
  delete from public.cotacoes
   where lote_id = p_lote
     and fonte = 'usuario_encarte'
     and created_at > now() - interval '48 hours';
  get diagnostics v_n = row_count;
  return v_n;
end;
$$;
revoke execute on function public.admin_desfazer_lote(uuid) from public, anon;
grant execute on function public.admin_desfazer_lote(uuid) to authenticated;

-- -----------------------------------------------------------------------------
-- Sugestões de loja e endereço (encartes já lançados com loja livre)
-- -----------------------------------------------------------------------------
drop function if exists public.admin_lojas_encarte();
create function public.admin_lojas_encarte()
returns table (nome text, endereco text)
language plpgsql
stable
security definer
set search_path = ''
as $$
begin
  if not public.is_admin() then
    raise exception 'sem_permissao';
  end if;
  return query
    select x.nome, x.endereco
    from (
      select c.pdv_nome_livre as nome, c.pdv_endereco_livre as endereco, max(c.created_at) as ultimo
      from public.cotacoes c
      where c.fonte = 'usuario_encarte' and c.loja_id is null and c.pdv_nome_livre is not null
      group by c.pdv_nome_livre, c.pdv_endereco_livre
    ) x
    order by x.ultimo desc
    limit 50;
end;
$$;
revoke execute on function public.admin_lojas_encarte() from public, anon;
grant execute on function public.admin_lojas_encarte() to authenticated;
