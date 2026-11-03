-- =============================================================================
-- Rede de lojas (03/11/2026, versão 1.0.3)
--
-- Regra comercial:
--  * Cada CNPJ é UMA loja, com um único endereço (cadastro, alvará e aprovação
--    próprios). Uma empresa com várias lojas cadastra cada CNPJ separadamente,
--    na mesma conta.
--  * "Modo rede": os CNPJs da mesma conta (mesmo dono) em modo rede dividem UMA
--    tabela de preços. Lançar, alterar, pausar ou excluir vale para todas as
--    lojas da rede. Fora do modo rede, cada CNPJ tem a sua tabela.
--  * Plano por loja: cada CNPJ (loja) tem o próprio plano, também na rede.
--    Uma rede com 5 lojas paga 5 planos. A tabela é uma só, mas cada loja
--    aparece na busca só até o limite do próprio plano (ficam no ar os itens
--    atualizados mais recentemente; o resto fica pausado pelo limite naquela
--    loja, nunca apagado). Criar ou reativar item confere o limite do CNPJ
--    que está sendo usado no painel.
--  * Entrar na rede (aprovação de um CNPJ novo, reativação ou ligar o modo
--    rede): a loja recebe a tabela da rede (no mesmo produto, vale o preço da
--    rede) e os produtos que só ela tinha vão para as outras lojas da rede.
--    Sair da rede: a loja fica com uma cópia e passa a ter tabela própria.
--  * Sem regra de raiz de CNPJ: quem responde pelas empresas é o dono da conta.
--
-- Não havia empresa cadastrada; nada é removido. Idempotente.
-- =============================================================================

-- 1. Uma loja por CNPJ ---------------------------------------------------------
-- (falha se já existir CNPJ com mais de uma loja: nesse caso, separar antes)
create unique index if not exists lojas_uma_por_pdv_uidx on public.lojas (pdv_id);
-- A loja nasce no cadastro do CNPJ; o dono não cria nem apaga loja pelo app.
drop policy if exists lojas_insert on public.lojas;
drop policy if exists lojas_delete on public.lojas;
-- Modo rede só pela função (que sincroniza a tabela).
revoke update (modo_rede) on public.pdvs from authenticated;

-- 2. Quem está na rede ------------------------------------------------------------
-- Lojas que dividem a tabela com este CNPJ: as dele e, em modo rede, as dos outros
-- CNPJs aprovados da mesma conta também em modo rede. Só lojas ativas.
create or replace function public._lojas_rede(p_pdv public.pdvs)
returns uuid[]
language sql
stable
security definer
set search_path = ''
as $$
  select coalesce(array_agg(l.id order by p.created_at, l.created_at), '{}'::uuid[])
  from public.pdvs p
  join public.lojas l on l.pdv_id = p.id
  where l.ativa
    and (p.id = p_pdv.id or (p_pdv.modo_rede and p.modo_rede and p.dono_id = p_pdv.dono_id and p.tipo = 'loja' and p.status = 'aprovado'))
$$;
revoke execute on function public._lojas_rede(public.pdvs) from public, anon, authenticated;

-- 3. Entrar na rede: junta as tabelas ---------------------------------------------
create or replace function public._sincronizar_rede(p_pdv_id uuid)
returns void
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_pdv     public.pdvs%rowtype;
  v_minhas  uuid[];
  v_outras  uuid[];
begin
  select * into v_pdv from public.pdvs where id = p_pdv_id;
  if not found or not v_pdv.modo_rede or v_pdv.status <> 'aprovado' or v_pdv.tipo <> 'loja' then
    return;
  end if;
  select coalesce(array_agg(id), '{}'::uuid[]) into v_minhas from public.lojas where pdv_id = p_pdv_id and ativa;
  v_outras := array(select unnest(public._lojas_rede(v_pdv)) except select unnest(v_minhas));
  if cardinality(v_minhas) = 0 or cardinality(v_outras) = 0 then
    return;
  end if;

  -- O que só esta loja tinha vai para as outras lojas da rede.
  insert into public.cotacoes as c (loja_id, produto, preco_centavos, validade, obs, unidade, a_partir_de, fonte, enviado_por, lote_id, pausado, preco_normal_centavos, preco_app)
  select o.loja_id, r.produto, r.preco_centavos, r.validade, r.obs, r.unidade, r.a_partir_de, r.fonte, r.enviado_por, r.lote_id, r.pausado, r.preco_normal_centavos, r.preco_app
  from (
    select distinct on (x.produto_busca) x.*
    from public.cotacoes x
    where x.loja_id = any (v_minhas) and x.fonte in ('pdv_manual', 'pdv_excel') and x.validade >= public.hoje()
      and not exists (select 1 from public.cotacoes y
                      where y.loja_id = any (v_outras) and y.produto_busca = x.produto_busca
                        and y.fonte in ('pdv_manual', 'pdv_excel'))
    order by x.produto_busca, x.updated_at desc
  ) r
  cross join unnest(v_outras) as o(loja_id)
  on conflict (loja_id, produto_busca) where fonte in ('pdv_manual', 'pdv_excel') do nothing;

  -- A tabela da rede vem para esta loja (no mesmo produto, vale o preço da rede).
  insert into public.cotacoes as c (loja_id, produto, preco_centavos, validade, obs, unidade, a_partir_de, fonte, enviado_por, lote_id, pausado, preco_normal_centavos, preco_app)
  select m.loja_id, r.produto, r.preco_centavos, r.validade, r.obs, r.unidade, r.a_partir_de, r.fonte, r.enviado_por, r.lote_id, r.pausado, r.preco_normal_centavos, r.preco_app
  from (
    select distinct on (x.produto_busca) x.*
    from public.cotacoes x
    where x.loja_id = any (v_outras) and x.fonte in ('pdv_manual', 'pdv_excel') and x.validade >= public.hoje()
    order by x.produto_busca, x.updated_at desc
  ) r
  cross join unnest(v_minhas) as m(loja_id)
  on conflict (loja_id, produto_busca) where fonte in ('pdv_manual', 'pdv_excel')
  do update set
    produto = excluded.produto, preco_centavos = excluded.preco_centavos, validade = excluded.validade,
    obs = excluded.obs, unidade = excluded.unidade, a_partir_de = excluded.a_partir_de, fonte = excluded.fonte,
    enviado_por = excluded.enviado_por, lote_id = excluded.lote_id, pausado = excluded.pausado,
    preco_normal_centavos = excluded.preco_normal_centavos, preco_app = excluded.preco_app;
end;
$$;
revoke execute on function public._sincronizar_rede(uuid) from public, anon, authenticated;

-- Liga/desliga o modo rede de um CNPJ (só o dono).
create or replace function public.pdv_definir_modo_rede(p_pdv_id uuid, p_rede boolean)
returns void
language plpgsql
security definer
set search_path = ''
as $$
begin
  if auth.uid() is null then
    raise exception 'nao_autenticado';
  end if;
  -- Trava os CNPJs da conta (mesma ordem do pdv_salvar_precos).
  perform 1 from public.pdvs where dono_id = auth.uid() order by id for update;
  update public.pdvs set modo_rede = coalesce(p_rede, true)
   where id = p_pdv_id and dono_id = auth.uid() and tipo = 'loja';
  if not found then
    raise exception 'sem_permissao';
  end if;
  if coalesce(p_rede, true) then
    perform public._sincronizar_rede(p_pdv_id);
  end if;
end;
$$;
revoke execute on function public.pdv_definir_modo_rede(uuid, boolean) from public, anon;
grant execute on function public.pdv_definir_modo_rede(uuid, boolean) to authenticated;

-- 4. Tabela, limite e pausas passam a olhar a rede -----------------------------------
create or replace function public._itens_ativos(p_pdv public.pdvs, p_carteira uuid)
returns integer
language sql
stable
security definer
set search_path = ''
as $$
  select count(distinct c.produto_busca)::integer
  from public.cotacoes c
  where c.loja_id = any (public._lojas_rede(p_pdv))
    and c.fonte in ('pdv_manual', 'pdv_excel')
    and c.validade >= public.hoje()
    and not c.pausado
    and (p_carteira is null or c.loja_id = p_carteira)
$$;

-- Itens fora do ar: pausados pelo PDV ou acima do limite do plano DA LOJA (cada CNPJ
-- é uma loja e tem o próprio plano, também na rede). Com uma loja por CNPJ, a carteira
-- é sempre a loja.
create or replace function public._cotacoes_ocultas(p_pdv_id uuid default null)
returns table (id uuid, motivo text)
language sql
stable
security definer
set search_path = ''
as $$
  with lim as (
    select p.id as pdv_id, e.limite
    from public.pdvs p
    cross join lateral public._limite_efetivo(p) e
    where p_pdv_id is null or p.id = p_pdv_id
  ),
  itens as (
    select c.id, c.produto_busca, c.updated_at, c.pausado, lim.limite, c.loja_id as carteira
    from public.cotacoes c
    join public.lojas l on l.id = c.loja_id
    join lim on lim.pdv_id = l.pdv_id
    where c.fonte in ('pdv_manual', 'pdv_excel') and c.validade >= public.hoje()
  ),
  produtos as (
    select carteira, produto_busca, bool_or(pausado) as pausado, max(updated_at) as atual, min(limite) as limite
    from itens
    group by carteira, produto_busca
  ),
  ordem as (
    select carteira, produto_busca, pausado, limite,
           row_number() over (partition by carteira, pausado order by atual desc, produto_busca) as n
    from produtos
  )
  select i.id, case when o.pausado then 'pdv' else 'limite' end
  from itens i
  join ordem o on o.carteira = i.carteira and o.produto_busca = i.produto_busca
  where o.pausado or o.n > o.limite
$$;

create or replace function public.status_catalogo(p_pdv_id uuid, p_loja_id uuid default null)
returns table (
  plano               text,     -- plano contratado (gratis, essencial, loja, mercado)
  plano_nome          text,
  limite_plano        integer,
  plano_ate           date,
  teste_ate           date,
  plano_efetivo       text,     -- o que vale hoje
  plano_efetivo_nome  text,
  limite              integer,
  origem              text,     -- 'pago' | 'teste' | 'gratis'
  em_tolerancia       boolean,
  fim                 date,
  tolerancia_ate      date,
  itens_ativos        integer,  -- não pausados pelo PDV e na validade (contam no limite)
  disponiveis         integer,
  itens_publicados    integer,  -- os que aparecem na busca
  pausados_limite     integer,  -- acima do limite (pausados automaticamente)
  pausados_pdv        integer,  -- pausados pelo próprio PDV
  limite_gratis       integer,
  dias_tolerancia     integer,
  aviso_dias          integer,
  whatsapp_contato    text
)
language plpgsql
stable
security definer
set search_path = ''
as $$
declare
  v_pdv       public.pdvs%rowtype;
  v_carteira  uuid;
  v_ativos    integer;
  v_pausados  integer;
  v_lim       record;
begin
  select * into v_pdv from public.pdvs where id = p_pdv_id;
  if not found or not (v_pdv.dono_id = auth.uid() or public.is_admin()) then
    raise exception 'sem_permissao';
  end if;
  v_carteira := public._carteira_cota(v_pdv, p_loja_id);
  v_ativos := public._itens_ativos(v_pdv, v_carteira);
  select count(distinct c.produto_busca)::integer into v_pausados
  from public.cotacoes c
  where c.loja_id = any (public._lojas_rede(v_pdv)) and c.fonte in ('pdv_manual', 'pdv_excel') and c.validade >= public.hoje()
    and c.pausado and (v_carteira is null or c.loja_id = v_carteira);
  select * into v_lim from public._limite_efetivo(v_pdv);
  return query
    select v_pdv.plano, pc.nome, pc.limite_itens, v_pdv.plano_ate, v_pdv.teste_ate,
           v_lim.plano, v_lim.plano_nome, v_lim.limite, v_lim.origem, v_lim.em_tolerancia,
           v_lim.fim, v_lim.tolerancia_ate,
           v_ativos, greatest(v_lim.limite - v_ativos, 0), least(v_ativos, v_lim.limite),
           greatest(v_ativos - v_lim.limite, 0), v_pausados,
           (select g.limite_itens from public.planos g where g.codigo = 'gratis'),
           greatest((public.config_app('dias_tolerancia', '7'))::text::integer, 0),
           (public.config_app('aviso_plano_dias', '7'))::text::integer,
           public.config_app('whatsapp_contato', '"5524988029067"') #>> '{}'
    from public.planos pc
    where pc.codigo = v_pdv.plano;
end;
$$;

create or replace function public.pdv_salvar_precos(
  p_pdv_id   uuid,
  p_loja_id  uuid,
  p_itens    jsonb,
  p_fonte    public.fonte_cotacao default 'pdv_manual',
  p_simular  boolean default false
)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_pdv          public.pdvs%rowtype;
  v_lojas        uuid[];
  v_hoje         date := public.hoje();
  v_lote         uuid := gen_random_uuid();
  v_invalidos    jsonb;
  v_carteira     uuid;
  v_lim          record;
  v_efetivo      text;
  v_plano_nome   text;
  v_limite       integer;
  v_ativos       integer;
  v_disponiveis  integer;
  v_resumo       record;
  v_fora         jsonb;
  v_n_fora       integer;
begin
  if auth.uid() is null then
    raise exception 'nao_autenticado';
  end if;
  if p_fonte not in ('pdv_manual', 'pdv_excel') then
    raise exception 'fonte_invalida';
  end if;
  if p_itens is null or jsonb_typeof(p_itens) <> 'array' or jsonb_array_length(p_itens) = 0 then
    raise exception 'itens_vazios';
  end if;
  if jsonb_array_length(p_itens) > 5000 then
    raise exception 'itens_demais';
  end if;

  select * into v_pdv from public.pdvs where id = p_pdv_id;
  if not found or v_pdv.dono_id <> auth.uid() then
    raise exception 'sem_permissao';
  end if;
  -- Trava todos os CNPJs da conta (a rede): duas importações ao mesmo tempo não furam o limite.
  perform 1 from public.pdvs where dono_id = v_pdv.dono_id order by id for update;
  select * into v_pdv from public.pdvs where id = p_pdv_id;

  if v_pdv.modo_rede then
    -- Modo rede: todas as lojas dos CNPJs da conta que estão na rede.
    v_lojas := public._lojas_rede(v_pdv);
  else
    select array_agg(id) into v_lojas
    from public.lojas where id = p_loja_id and pdv_id = p_pdv_id and ativa;
  end if;
  if coalesce(cardinality(v_lojas), 0) = 0 then
    raise exception 'loja_invalida';
  end if;
  v_carteira := public._carteira_cota(v_pdv, p_loja_id);

  if to_regclass('pg_temp._itens') is not null then
    drop table pg_temp._itens;
  end if;
  create temp table _itens (
    ord bigint, produto text, norm text, preco integer, validade date, obs text,
    unidade text, a_partir_de boolean, tipo public.tipo_operacao, entra boolean default true
  ) on commit drop;

  insert into _itens (ord, produto, norm, preco, validade, obs, unidade, a_partir_de)
  select e.ord,
         btrim(e.item ->> 'produto'),
         public.normalizar(e.item ->> 'produto'),
         (e.item ->> 'preco_centavos')::integer,
         coalesce((e.item ->> 'validade')::date, v_hoje + public.validade_maxima_dias()),
         nullif(btrim(e.item ->> 'obs'), ''),
         coalesce(nullif(btrim(e.item ->> 'unidade'), ''), 'unidade'),
         coalesce((e.item ->> 'a_partir_de')::boolean, false)
  from jsonb_array_elements(p_itens) with ordinality as e(item, ord);

  -- Validação: devolve TODAS as linhas com problema de uma vez (útil p/ planilha).
  select jsonb_agg(jsonb_build_object('linha', ord, 'erro', erro) order by ord) into v_invalidos
  from (
    select ord,
      case
        when produto is null or norm = '' then 'produto_vazio'
        when length(produto) > 200 then 'produto_longo'
        when preco is null or preco <= 0 then 'preco_invalido'
        when preco >= 100000000 then 'preco_invalido'
        when validade < v_hoje then 'validade_passada'
        when validade > v_hoje + public.validade_maxima_dias() then 'validade_maior_que_30_dias'
        when length(obs) > 140 then 'obs_longa'
        when unidade not in (select unnest(enum_range(null::public.unidade_medida))::text) then 'unidade_invalida'
        when count(*) over (partition by norm) > 1 then 'produto_duplicado'
      end as erro
    from _itens
  ) v
  where erro is not null;
  if v_invalidos is not null then
    raise exception 'itens_invalidos' using detail = v_invalidos::text;
  end if;

  -- Classifica contra o que já existe nas lojas-alvo (o maior preço entre elas).
  update _itens i
     set tipo = case
           when atual.existe is null then 'criar_item'
           when i.preco > atual.preco then 'aumentar_preco'
           when i.preco < atual.preco then 'diminuir_preco'
           else 'editar_dados'
         end::public.tipo_operacao
  from (
    select i2.ord, max(c.preco_centavos) as preco, bool_or(true) as existe
    from _itens i2
    left join public.cotacoes c
      on c.loja_id = any (v_lojas) and c.produto_busca = i2.norm and c.fonte in ('pdv_manual', 'pdv_excel')
    where c.id is not null
    group by i2.ord
  ) atual
  where atual.ord = i.ord;
  update _itens set tipo = 'criar_item' where tipo is null;

  -- Limite efetivo (plano pago, teste grátis ou grátis, com a tolerância): só os itens
  -- NOVOS disputam o espaço disponível, na ordem da planilha. Itens pausados não contam.
  select * into v_lim from public._limite_efetivo(v_pdv);
  v_efetivo := v_lim.plano;
  v_limite := v_lim.limite;
  v_plano_nome := v_lim.plano_nome;
  v_ativos := public._itens_ativos(v_pdv, v_carteira);
  v_disponiveis := greatest(coalesce(v_limite, 0) - v_ativos, 0);

  update _itens i set entra = false
  from (
    select ord, row_number() over (order by ord) as n from _itens where tipo = 'criar_item'
  ) novos
  where novos.ord = i.ord and novos.n > v_disponiveis;

  select coalesce(jsonb_agg(jsonb_build_object('linha', ord, 'produto', produto) order by ord), '[]'::jsonb),
         count(*)
    into v_fora, v_n_fora
  from _itens where not entra;

  if v_n_fora > 0 and p_fonte = 'pdv_manual' and not p_simular then
    raise exception 'limite_catalogo'
      using detail = jsonb_build_object('limite', v_limite, 'plano', v_plano_nome, 'itens_ativos', v_ativos)::text;
  end if;

  select count(*) filter (where tipo = 'criar_item' and entra)  as criados,
         count(*) filter (where tipo = 'aumentar_preco')        as aumentados,
         count(*) filter (where tipo = 'diminuir_preco')        as diminuidos,
         count(*) filter (where tipo = 'editar_dados')          as inalterados
  into v_resumo
  from _itens;

  if not p_simular then
    insert into public.cotacoes as c
      (loja_id, produto, preco_centavos, validade, obs, unidade, a_partir_de, fonte, enviado_por, lote_id)
    select l.loja_id, i.produto, i.preco, i.validade, i.obs, i.unidade::public.unidade_medida, i.a_partir_de,
           p_fonte, auth.uid(), v_lote
    from _itens i
    cross join unnest(v_lojas) as l(loja_id)
    where i.entra
    on conflict (loja_id, produto_busca) where fonte in ('pdv_manual', 'pdv_excel')
    do update set
      produto        = excluded.produto,
      preco_centavos = excluded.preco_centavos,
      validade       = excluded.validade,
      obs            = excluded.obs,
      unidade        = excluded.unidade,
      a_partir_de    = excluded.a_partir_de,
      fonte          = excluded.fonte,
      enviado_por    = excluded.enviado_por,
      lote_id        = excluded.lote_id;

    -- Só medição (não bloqueia nada): uma linha por item gravado.
    insert into public.operacoes_log (pdv_id, loja_id, produto, tipo)
    select p_pdv_id, v_carteira, i.produto, i.tipo from _itens i where i.entra order by i.ord;
  end if;

  return jsonb_build_object(
    'simulacao', p_simular,
    'lote_id', case when p_simular then null else v_lote end,
    'criados', v_resumo.criados,
    'aumentados', v_resumo.aumentados,
    'diminuidos', v_resumo.diminuidos,
    'inalterados', v_resumo.inalterados,
    'fora_do_limite', v_n_fora,
    'itens_fora', v_fora,
    'limite', v_limite,
    'plano', v_efetivo,
    'plano_nome', v_plano_nome,
    'itens_ativos', v_ativos + v_resumo.criados,
    'disponiveis', greatest(v_disponiveis - v_resumo.criados, 0),
    -- Chaves da cota antiga (apps antigos): nada é cobrado.
    'operacoes', v_resumo.criados,
    'restantes', greatest(v_disponiveis - v_resumo.criados, 0),
    'cabe_na_cota', v_n_fora = 0,
    'pacotes_necessarios', 0
  );
end;
$$;

create or replace function public.pdv_excluir_item(p_cotacao_id uuid)
returns integer
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_cot   public.cotacoes%rowtype;
  v_pdv   public.pdvs%rowtype;
  v_n     integer;
begin
  select c.* into v_cot from public.cotacoes c where c.id = p_cotacao_id;
  if not found or v_cot.fonte not in ('pdv_manual', 'pdv_excel') then
    raise exception 'item_nao_encontrado';
  end if;

  select p.* into v_pdv
  from public.pdvs p join public.lojas l on l.pdv_id = p.id
  where l.id = v_cot.loja_id;
  if v_pdv.dono_id is distinct from auth.uid() then
    raise exception 'sem_permissao';
  end if;

  delete from public.cotacoes c
  where c.fonte in ('pdv_manual', 'pdv_excel')
    and c.produto_busca = v_cot.produto_busca
    and (
      (v_pdv.modo_rede and c.loja_id = any (public._lojas_rede(v_pdv)))
      or (not v_pdv.modo_rede and c.loja_id = v_cot.loja_id)
    );
  get diagnostics v_n = row_count;

  insert into public.operacoes_log (pdv_id, loja_id, produto, tipo)
  values (v_pdv.id, case when v_pdv.modo_rede then null else v_cot.loja_id end,
          v_cot.produto, 'excluir_item');

  return v_n;
end;
$$;

create or replace function public.pdv_pausar_item(p_cotacao_id uuid, p_pausar boolean)
returns void
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_cot       public.cotacoes%rowtype;
  v_pdv       public.pdvs%rowtype;
  v_carteira  uuid;
  v_lim       record;
begin
  select c.* into v_cot from public.cotacoes c where c.id = p_cotacao_id;
  if not found or v_cot.fonte not in ('pdv_manual', 'pdv_excel') then
    raise exception 'item_nao_encontrado';
  end if;
  select p.* into v_pdv
  from public.pdvs p join public.lojas l on l.pdv_id = p.id
  where l.id = v_cot.loja_id
  for update of p;
  if v_pdv.dono_id is distinct from auth.uid() then
    raise exception 'sem_permissao';
  end if;
  v_carteira := case when v_pdv.modo_rede then null else v_cot.loja_id end;

  if not coalesce(p_pausar, true) and v_cot.pausado and v_cot.validade >= public.hoje() then
    select * into v_lim from public._limite_efetivo(v_pdv);
    if public._itens_ativos(v_pdv, v_carteira) >= v_lim.limite then
      raise exception 'limite_catalogo'
        using detail = jsonb_build_object('limite', v_lim.limite, 'plano', v_lim.plano_nome)::text;
    end if;
  end if;

  update public.cotacoes c
     set pausado = coalesce(p_pausar, true)
   where c.fonte in ('pdv_manual', 'pdv_excel')
     and c.produto_busca = v_cot.produto_busca
     and c.pausado is distinct from coalesce(p_pausar, true)
     and (
       (v_pdv.modo_rede and c.loja_id = any (public._lojas_rede(v_pdv)))
       or (not v_pdv.modo_rede and c.loja_id = v_cot.loja_id)
     );
end;
$$;

create or replace function public.meus_precos(p_pdv_id uuid, p_loja_id uuid default null)
returns table (
  id              uuid,
  loja_id         uuid,
  produto         text,
  preco_centavos  integer,
  validade        date,
  obs             text,
  updated_at      timestamptz,
  unidade         public.unidade_medida,
  a_partir_de     boolean,
  estado          text
)
language plpgsql
stable
security definer
set search_path = ''
as $$
declare
  v_pdv  public.pdvs%rowtype;
  v_rede boolean;
begin
  select p.* into v_pdv from public.pdvs p where p.id = p_pdv_id and p.dono_id = auth.uid();
  if not found then
    raise exception 'sem_permissao';
  end if;
  v_rede := v_pdv.modo_rede;
  return query
    with ocultas as materialized (select o.id, o.motivo from public._cotacoes_ocultas(p_pdv_id) o)
    select distinct on (c.produto_busca)
           c.id, c.loja_id, c.produto, c.preco_centavos, c.validade, c.obs, c.updated_at, c.unidade, c.a_partir_de,
           case
             when c.validade < public.hoje() then 'vencido'
             when c.pausado then 'pausado'
             when o.motivo = 'limite' then 'pausado_limite'
             else 'ativo'
           end
    from public.cotacoes c
    join public.lojas l on l.id = c.loja_id
    left join ocultas o on o.id = c.id
    where (l.pdv_id = p_pdv_id or (v_rede and c.loja_id = any (public._lojas_rede(v_pdv))))
      and c.fonte in ('pdv_manual', 'pdv_excel')
      and (v_rede or p_loja_id is null or c.loja_id = p_loja_id)
    order by c.produto_busca, (l.pdv_id = p_pdv_id) desc, c.preco_centavos desc;
end;
$$;

create or replace function public.aprovar_pdv(p_pdv_id uuid)
returns void
language plpgsql
security definer
set search_path = ''
as $$
begin
  if not public.is_admin() then
    raise exception 'sem_permissao';
  end if;
  update public.pdvs
     set status = 'aprovado', motivo_rejeicao = null, verificado_em = now(), verificado_por = auth.uid()
   where id = p_pdv_id and status = 'pendente';
  if not found then
    raise exception 'pdv_nao_encontrado';
  end if;
  -- Novo CNPJ de uma conta com rede: entra com a tabela da rede.
  perform public._sincronizar_rede(p_pdv_id);
end;
$$;

create or replace function public.admin_reativar_pdv(p_pdv_id uuid)
returns void
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_nome text;
begin
  if not public.is_admin() then
    raise exception 'sem_permissao';
  end if;
  update public.pdvs
     set status = 'aprovado', motivo_suspensao = null, suspenso_em = null
   where id = p_pdv_id and status = 'suspenso'
  returning nome_fantasia into v_nome;
  if v_nome is null then
    raise exception 'pdv_nao_encontrado';
  end if;
  insert into public.moderacoes (acao, pdv_id, descricao) values ('reativar_pdv', p_pdv_id, v_nome);
  perform public._sincronizar_rede(p_pdv_id);
end;
$$;
