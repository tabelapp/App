-- =============================================================================
-- Novos planos: teste grátis de 30 dias, grátis com 5 itens e 3 planos pagos
-- (substitui o "parceiro fundador" de 90 dias e o grátis de 30 itens)
--
--  * planos: gratis 5 / essencial 30 (R$ 9,99) / loja 300 (R$ 19,99) /
--    mercado 3000 (R$ 29,99) por mês. Preço em centavos na coluna
--    `preco_referencia_centavos` (nome mantido: o app já lê essa coluna).
--  * Teste grátis: `teste_ate` (era `fundador_ate`) = cadastro + dias_teste_gratis.
--    Um teste por CNPJ: a tabela `testes_gratis` guarda o prazo por CNPJ, então
--    excluir e recadastrar o mesmo CNPJ não reinicia o teste.
--  * Plano pago: `plano` + `plano_ate`, ativado pelo Admin (sem cobrança no app).
--  * Limite efetivo = o maior entre o plano pago (até plano_ate), o "mercado"
--    (até teste_ate) e o grátis. Depois do fim, `dias_tolerancia` dias com o
--    limite anterior.
--  * Itens acima do limite efetivo ficam PAUSADOS (nunca apagados). A pausa é
--    CALCULADA na leitura (função _cotacoes_ocultas): ficam no ar os itens não
--    pausados atualizados mais recentemente, até o limite. Sem job agendado:
--    renovar ou assinar devolve os itens na hora, e nada fica "preso" se um
--    job falhar. O PDV também pode pausar/reativar itens (coluna `pausado`).
--  * O limite só é conferido ao CRIAR ou REATIVAR item, no banco.
--
-- Idempotente: pode ser executada de novo sem erro e sem apagar dados.
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 1. Planos e parâmetros (aplicados uma vez; depois, editáveis por SQL)
-- -----------------------------------------------------------------------------
do $$ begin
  if not exists (select 1 from public.configuracao_app where chave = 'planos_2026_10_aplicados') then
    insert into public.planos (codigo, nome, limite_itens, preco_referencia_centavos, ordem, ativo) values
      ('gratis',    'Grátis',    5,    0,    1, true),
      ('essencial', 'Essencial', 30,   999,  2, true),
      ('loja',      'Loja',      300,  1999, 3, true),
      ('mercado',   'Mercado',   3000, 2999, 4, true)
    on conflict (codigo) do update
      set nome = excluded.nome, limite_itens = excluded.limite_itens,
          preco_referencia_centavos = excluded.preco_referencia_centavos, ordem = excluded.ordem, ativo = true;
    insert into public.configuracao_app (chave, valor, descricao)
    values ('planos_2026_10_aplicados', to_jsonb(public.hoje()), 'Planos de out/2026 aplicados (não reaplicar).');
  end if;
end $$;

insert into public.configuracao_app (chave, valor, descricao) values
  ('dias_teste_gratis', '30', 'Teste grátis do PDV (dias a partir do cadastro), com o limite do plano_teste.'),
  ('plano_teste', '"mercado"', 'Plano cujo limite vale durante o teste grátis.'),
  ('dias_tolerancia', '7', 'Dias depois do fim do teste ou do plano em que o limite anterior continua valendo (pode ser 0).'),
  ('aviso_plano_dias', '7', 'A partir de quantos dias antes do fim do teste/plano o app avisa.'),
  ('whatsapp_contato', '"5524988029067"', 'WhatsApp do Tabelapp ("Anuncie aqui" e "Assinar pelo WhatsApp").')
on conflict (chave) do nothing;
-- Regra antiga do parceiro fundador.
delete from public.configuracao_app
 where chave in ('fundador_ativo', 'fundador_dias', 'fundador_plano', 'fundador_aviso_dias');

-- -----------------------------------------------------------------------------
-- 2. PDV: teste_ate (era fundador_ate) e plano_ate
-- -----------------------------------------------------------------------------
do $$ begin
  if exists (select 1 from information_schema.columns
             where table_schema = 'public' and table_name = 'pdvs' and column_name = 'fundador_ate')
     and not exists (select 1 from information_schema.columns
                     where table_schema = 'public' and table_name = 'pdvs' and column_name = 'teste_ate') then
    alter table public.pdvs rename column fundador_ate to teste_ate;
  end if;
end $$;
alter table public.pdvs add column if not exists teste_ate date;
alter table public.pdvs add column if not exists plano_ate date;
-- O dono vê, mas não altera (fora do grant de update).
grant select (plano, teste_ate, plano_ate) on public.pdvs to authenticated;

-- Um teste por CNPJ (sobrevive à exclusão e ao recadastro do PDV).
create table if not exists public.testes_gratis (
  cnpj        char(14) primary key,
  teste_ate   date not null,
  created_at  timestamptz not null default now()
);
alter table public.testes_gratis enable row level security;
revoke all on public.testes_gratis from anon, authenticated;

-- PDVs que já existem: teste de 30 dias a partir desta migração (uma vez só).
do $$
declare v_ate date;
begin
  if not exists (select 1 from public.configuracao_app where chave = 'teste_gratis_migracao_feita') then
    v_ate := public.hoje() + (public.config_app('dias_teste_gratis', '30'))::text::integer;
    update public.pdvs set teste_ate = v_ate;
    insert into public.testes_gratis (cnpj, teste_ate)
    select cnpj, v_ate from public.pdvs
    on conflict (cnpj) do update set teste_ate = excluded.teste_ate;
    insert into public.configuracao_app (chave, valor, descricao)
    values ('teste_gratis_migracao_feita', to_jsonb(public.hoje()), 'PDVs existentes receberam o teste grátis.');
  end if;
end $$;

-- PDV novo: plano inicial e o teste grátis (o mesmo prazo se o CNPJ já teve teste).
create or replace function public._pdv_plano_inicial()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
declare v_ate date;
begin
  new.plano := coalesce(nullif(new.plano, ''), public.config_app('plano_inicial', '"gratis"') #>> '{}');
  select teste_ate into v_ate from public.testes_gratis where cnpj = new.cnpj;
  if v_ate is null then
    v_ate := coalesce(new.teste_ate,
                      (coalesce(new.created_at, now()) at time zone 'America/Sao_Paulo')::date
                      + (public.config_app('dias_teste_gratis', '30'))::text::integer);
    insert into public.testes_gratis (cnpj, teste_ate) values (new.cnpj, v_ate);
  end if;
  new.teste_ate := v_ate;
  return new;
end;
$$;
drop trigger if exists pdvs_plano_inicial on public.pdvs;
create trigger pdvs_plano_inicial before insert on public.pdvs
  for each row execute function public._pdv_plano_inicial();

-- -----------------------------------------------------------------------------
-- 3. Pausa de itens pelo PDV
-- -----------------------------------------------------------------------------
alter table public.cotacoes add column if not exists pausado boolean not null default false;

-- -----------------------------------------------------------------------------
-- 4. Limite efetivo
-- -----------------------------------------------------------------------------
-- O maior entre: plano pago (até plano_ate), o plano do teste (até teste_ate) e o
-- grátis. Até dias_tolerancia depois do fim, o limite anterior continua valendo
-- (em_tolerancia = true). No empate, vale o que ainda não está na tolerância.
create or replace function public._limite_efetivo(p_pdv public.pdvs)
returns table (
  limite         integer,
  plano          text,
  plano_nome     text,
  origem         text,      -- 'pago' | 'teste' | 'gratis'
  em_tolerancia  boolean,
  fim            date,      -- fim do plano pago ou do teste que vale agora
  tolerancia_ate date
)
language sql
stable
security definer
set search_path = ''
as $$
  with cfg as (
    select public.hoje() as hoje,
           greatest((public.config_app('dias_tolerancia', '7'))::text::integer, 0) as tol,
           public.config_app('plano_teste', '"mercado"') #>> '{}' as plano_teste
  ),
  cand as (
    select 'gratis'::text as codigo, 'gratis'::text as origem, null::date as fim, 3 as prio from cfg
    union all
    select p_pdv.plano, 'pago', p_pdv.plano_ate, 1 from cfg
     where p_pdv.plano <> 'gratis' and p_pdv.plano_ate is not null and p_pdv.plano_ate + cfg.tol >= cfg.hoje
    union all
    select cfg.plano_teste, 'teste', p_pdv.teste_ate, 2 from cfg
     where p_pdv.teste_ate is not null and p_pdv.teste_ate + cfg.tol >= cfg.hoje
  )
  select pl.limite_itens, c.codigo, pl.nome, c.origem,
         coalesce(c.fim < cfg.hoje, false), c.fim,
         case when c.fim is not null then c.fim + cfg.tol end
  from cand c
  join public.planos pl on pl.codigo = c.codigo
  cross join cfg
  order by pl.limite_itens desc, coalesce(c.fim < cfg.hoje, false), c.prio
  limit 1
$$;
revoke execute on function public._limite_efetivo(public.pdvs) from public, anon, authenticated;

-- Compatibilidade: o "plano efetivo" passa a ser o do limite efetivo.
create or replace function public._plano_efetivo(p_pdv public.pdvs)
returns text
language sql
stable
security definer
set search_path = ''
as $$
  select plano from public._limite_efetivo(p_pdv)
$$;

-- Itens que contam no limite: dentro da validade e NÃO pausados pelo PDV.
create or replace function public._itens_ativos(p_pdv public.pdvs, p_carteira uuid)
returns integer
language sql
stable
security definer
set search_path = ''
as $$
  select count(distinct c.produto_busca)::integer
  from public.cotacoes c
  join public.lojas l on l.id = c.loja_id
  where l.pdv_id = p_pdv.id
    and c.fonte in ('pdv_manual', 'pdv_excel')
    and c.validade >= public.hoje()
    and not c.pausado
    and (p_carteira is null or c.loja_id = p_carteira)
$$;

-- Cotações oficiais que NÃO aparecem para o público: pausadas pelo PDV ('pdv') ou
-- acima do limite efetivo ('limite'). Por carteira (rede = o PDV; varejo = a loja),
-- ficam no ar os produtos não pausados atualizados mais recentemente, até o limite.
create or replace function public._cotacoes_ocultas(p_pdv_id uuid default null)
returns table (id uuid, motivo text)
language sql
stable
security definer
set search_path = ''
as $$
  with lim as (
    select p.id as pdv_id, p.modo_rede, e.limite
    from public.pdvs p
    cross join lateral public._limite_efetivo(p) e
    where p_pdv_id is null or p.id = p_pdv_id
  ),
  itens as (
    select c.id, c.produto_busca, c.updated_at, c.pausado, lim.limite,
           case when lim.modo_rede then lim.pdv_id else c.loja_id end as carteira
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
revoke execute on function public._cotacoes_ocultas(uuid) from public, anon;
-- A busca (security invoker) precisa chamar; só devolve ids, sem dados do PDV.
grant execute on function public._cotacoes_ocultas(uuid) to authenticated;

-- -----------------------------------------------------------------------------
-- 5. Situação do catálogo para o painel do PDV (só o dono ou o Admin)
-- -----------------------------------------------------------------------------
drop function if exists public.status_catalogo(uuid, uuid);
create function public.status_catalogo(p_pdv_id uuid, p_loja_id uuid default null)
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
  from public.cotacoes c join public.lojas l on l.id = c.loja_id
  where l.pdv_id = v_pdv.id and c.fonte in ('pdv_manual', 'pdv_excel') and c.validade >= public.hoje()
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
revoke execute on function public.status_catalogo(uuid, uuid) from public, anon;
grant execute on function public.status_catalogo(uuid, uuid) to authenticated;

-- -----------------------------------------------------------------------------
-- 6. Salvar/importar preços: mesmo comportamento, agora com o limite efetivo.
--    Item pausado pelo PDV continua pausado quando o preço é atualizado.
-- -----------------------------------------------------------------------------
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

  -- Trava o PDV: duas importações ao mesmo tempo não furam o limite.
  select * into v_pdv from public.pdvs where id = p_pdv_id for update;
  if not found or v_pdv.dono_id <> auth.uid() then
    raise exception 'sem_permissao';
  end if;

  if v_pdv.modo_rede then
    select array_agg(id order by created_at) into v_lojas
    from public.lojas where pdv_id = p_pdv_id and ativa;
  else
    select array_agg(id) into v_lojas
    from public.lojas where id = p_loja_id and pdv_id = p_pdv_id and ativa;
  end if;
  if v_lojas is null then
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

-- -----------------------------------------------------------------------------
-- 7. Pausar / reativar item (grátis). Reativar só até o limite efetivo.
-- -----------------------------------------------------------------------------
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
       (v_pdv.modo_rede and c.loja_id in (select id from public.lojas where pdv_id = v_pdv.id))
       or (not v_pdv.modo_rede and c.loja_id = v_cot.loja_id)
     );
end;
$$;
revoke execute on function public.pdv_pausar_item(uuid, boolean) from public, anon;
grant execute on function public.pdv_pausar_item(uuid, boolean) to authenticated;

-- Tabela do PDV com a situação de cada item: ativo, pausado (pelo PDV),
-- pausado_limite (acima do limite do plano) ou vencido.
drop function if exists public.meus_precos(uuid, uuid);
create function public.meus_precos(p_pdv_id uuid, p_loja_id uuid default null)
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
  v_rede boolean;
begin
  select p.modo_rede into v_rede from public.pdvs p where p.id = p_pdv_id and p.dono_id = auth.uid();
  if not found then
    raise exception 'sem_permissao';
  end if;
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
    where l.pdv_id = p_pdv_id
      and c.fonte in ('pdv_manual', 'pdv_excel')
      and (v_rede or p_loja_id is null or c.loja_id = p_loja_id)
    order by c.produto_busca, c.preco_centavos desc;
end;
$$;
revoke execute on function public.meus_precos(uuid, uuid) from public, anon;
grant execute on function public.meus_precos(uuid, uuid) to authenticated;

-- -----------------------------------------------------------------------------
-- 8. Busca e sugestões: itens pausados (pelo PDV ou pelo limite) não aparecem
-- -----------------------------------------------------------------------------
drop function if exists public.buscar_cotacoes(text, double precision, double precision, integer);
create function public.buscar_cotacoes(
  p_termo   text default null,
  p_lat     double precision default null,
  p_lng     double precision default null,
  p_limite  integer default 100
)
returns table (
  id              uuid,
  produto         text,
  preco_centavos  integer,
  validade        date,
  obs             text,
  fonte           public.fonte_cotacao,
  loja_id         uuid,
  pdv_id          uuid,
  pdv_nome        text,
  loja_nome       text,
  endereco        text,
  telefone        text,
  site            text,
  latitude        double precision,
  longitude       double precision,
  distancia_km    double precision,
  created_at      timestamptz,
  data_nf         date,
  unidade         public.unidade_medida,
  a_partir_de     boolean,
  parte_de_combo  boolean,
  denuncias       integer
)
language sql
stable
security invoker
set search_path = ''
as $$
  with termo as (
    select nullif(public.normalizar(p_termo), '') as t
  ),
  palavras as (
    select array_remove(string_to_array(t, ' '), '') as ps from termo
  ),
  -- Preço oficial pausado (pelo PDV ou acima do limite do plano) não aparece.
  ocultas as materialized (
    select o.id from public._cotacoes_ocultas() o
  ),
  validas as (
    -- Colunas listadas uma a uma: quem busca não pode ler `enviado_por`.
    select c.id, c.loja_id, c.pdv_nome_livre, c.pdv_endereco_livre, c.produto, c.produto_busca,
           c.preco_centavos, c.validade, c.obs, c.fonte, c.chave_acesso_nf, c.created_at, c.updated_at, c.data_nf,
           c.unidade, c.a_partir_de, c.parte_de_combo
    from public.cotacoes c
    left join public.lojas l on l.id = c.loja_id
    left join public.pdvs pv on pv.id = l.pdv_id
    cross join termo
    cross join palavras
    -- Loja cadastrada: só aparece se ativa e com o PDV aprovado (suspenso some da busca).
    where (l.id is null or (l.ativa and pv.status = 'aprovado'))
      and c.id not in (select o.id from ocultas o)
      and c.validade >= public.hoje()
      and (
        termo.t is null
        or not exists (
          select 1 from unnest(palavras.ps) w
          where strpos(c.produto_busca, w) = 0
        )
      )
  ),
  recentes as (
    select distinct on (public.local_cotacao(v.loja_id, v.chave_acesso_nf, v.pdv_nome_livre, v.pdv_endereco_livre),
                        v.produto_busca)
           v.id, v.loja_id, v.pdv_endereco_livre, v.pdv_nome_livre, v.produto, v.preco_centavos, v.validade,
           v.obs, v.fonte, v.chave_acesso_nf, v.created_at, v.data_nf, v.unidade, v.a_partir_de, v.parte_de_combo
    from validas v
    order by public.local_cotacao(v.loja_id, v.chave_acesso_nf, v.pdv_nome_livre, v.pdv_endereco_livre),
             v.produto_busca,
             public.dia_cotacao(v.data_nf, v.updated_at) desc,
             (v.fonte in ('pdv_manual', 'pdv_excel')) desc,
             v.created_at desc
  )
  select c.id,
         c.produto,
         c.preco_centavos,
         c.validade,
         c.obs,
         c.fonte,
         c.loja_id,
         p.id,
         coalesce(p.nome_fantasia, n.nome, c.pdv_nome_livre),
         l.nome,
         -- Loja cadastrada: endereço do cadastro. Senão: o endereço lido da nota.
         case when l.id is not null
              then concat_ws(', ', l.endereco, l.bairro, l.cidade || ' - ' || l.uf)
              else nullif(btrim(c.pdv_endereco_livre), '')
         end,
         coalesce(nullif(btrim(l.whatsapp), ''), l.telefone),
         p.site,
         l.latitude,
         l.longitude,
         public.distancia_km(p_lat, p_lng, l.latitude, l.longitude),
         c.created_at,
         c.data_nf,
         c.unidade,
         c.a_partir_de,
         c.parte_de_combo,
         public._total_denuncias(c.id)
  from recentes c
  left join public.lojas l on l.id = c.loja_id
  left join public.pdvs p on p.id = l.pdv_id
  left join public.nomes_pdv n on c.loja_id is null and n.cnpj = substring(c.chave_acesso_nf from 7 for 14)
  cross join termo
  order by
    case when termo.t is null then c.created_at end desc,
    c.preco_centavos asc
  limit least(greatest(coalesce(p_limite, 100), 1), 500)
$$;

create or replace function public.sugerir_produtos(p_termo text, p_limite integer default 8)
returns table (produto text, menor_preco_centavos integer, lugares integer)
language sql
stable
security invoker
set search_path = ''
as $$
  with palavras as (
    select array_remove(string_to_array(public.normalizar(p_termo), ' '), '') as ps
  )
  select min(c.produto),
         min(c.preco_centavos),
         count(distinct coalesce(c.loja_id::text, public.normalizar(c.pdv_nome_livre)))::integer
  from public.cotacoes c
  cross join palavras
  where c.validade >= public.hoje()
    and c.id not in (select o.id from public._cotacoes_ocultas() o)
    and length(public.normalizar(p_termo)) >= 2
    and not exists (select 1 from unnest(palavras.ps) w where strpos(c.produto_busca, w) = 0)
  group by c.produto_busca
  order by 3 desc, 1
  limit least(greatest(coalesce(p_limite, 8), 1), 20)
$$;
revoke execute on function public.buscar_cotacoes(text, double precision, double precision, integer) from public, anon;
grant execute on function public.buscar_cotacoes(text, double precision, double precision, integer)
  to authenticated, service_role;

-- -----------------------------------------------------------------------------
-- 9. Assinatura manual pelo Admin (sem cobrança no app) e registro
-- -----------------------------------------------------------------------------
create table if not exists public.assinaturas_log (
  id                 bigint generated always as identity primary key,
  pdv_id             uuid references public.pdvs (id) on delete set null,
  cnpj               char(14) not null,
  acao               text not null check (acao in ('ativar', 'renovar', 'trocar', 'cancelar')),
  plano_anterior     text,
  plano              text not null,
  validade_anterior  date,
  validade_nova      date,
  admin_id           uuid references public.usuarios (id) on delete set null default auth.uid(),
  observacao         text check (observacao is null or length(observacao) <= 500),
  created_at         timestamptz not null default now()
);
alter table public.assinaturas_log enable row level security;
drop policy if exists assinaturas_log_admin_select on public.assinaturas_log;
create policy assinaturas_log_admin_select on public.assinaturas_log for select to authenticated
  using (public.is_admin());
drop policy if exists assinaturas_log_admin_insert on public.assinaturas_log;
create policy assinaturas_log_admin_insert on public.assinaturas_log for insert to authenticated
  with check (public.is_admin());
revoke all on public.assinaturas_log from anon;
revoke update, delete, truncate on public.assinaturas_log from authenticated;
grant select, insert on public.assinaturas_log to authenticated;

-- Lista para a tela "Planos dos PDVs" (estabelecimentos com loja, aprovados ou suspensos).
create or replace function public.admin_planos_pdvs(p_busca text default null)
returns table (
  id               uuid,
  nome_fantasia    text,
  cnpj             text,
  plano            text,
  plano_nome       text,
  plano_ate        date,
  teste_ate        date,
  limite           integer,
  origem           text,
  em_tolerancia    boolean,
  itens_ativos     integer
)
language plpgsql
stable
security definer
set search_path = ''
as $$
declare
  v_busca text := nullif(public.normalizar(p_busca), '');
  v_digitos text := nullif(regexp_replace(coalesce(p_busca, ''), '\D', '', 'g'), '');
begin
  if not public.is_admin() then
    raise exception 'sem_permissao';
  end if;
  return query
    select p.id, p.nome_fantasia, p.cnpj::text, p.plano, pl.nome, p.plano_ate, p.teste_ate,
           e.limite, e.origem, e.em_tolerancia, public._itens_ativos(p, null)
    from public.pdvs p
    join public.planos pl on pl.codigo = p.plano
    cross join lateral public._limite_efetivo(p) e
    where p.status::text in ('aprovado', 'suspenso') and p.tipo = 'loja'
      and (v_busca is null
           or strpos(public.normalizar(p.nome_fantasia), v_busca) > 0
           or (v_digitos is not null and strpos(p.cnpj::text, v_digitos) > 0))
    order by p.nome_fantasia
    limit 200;
end;
$$;

-- Ativa, renova ou troca o plano pago (sem cálculo proporcional).
create or replace function public.admin_definir_plano(
  p_pdv_id      uuid,
  p_plano       text,
  p_validade    date,
  p_observacao  text default null
)
returns void
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_pdv   public.pdvs%rowtype;
  v_acao  text;
begin
  if not public.is_admin() then
    raise exception 'sem_permissao';
  end if;
  if p_plano = 'gratis' or not exists (select 1 from public.planos where codigo = p_plano and ativo) then
    raise exception 'plano_invalido';
  end if;
  if p_validade is null or p_validade < public.hoje() then
    raise exception 'validade_invalida';
  end if;
  select * into v_pdv from public.pdvs where id = p_pdv_id for update;
  if not found then
    raise exception 'pdv_nao_encontrado';
  end if;
  v_acao := case
    when v_pdv.plano = 'gratis' or v_pdv.plano_ate is null then 'ativar'
    when v_pdv.plano = p_plano then 'renovar'
    else 'trocar'
  end;
  update public.pdvs set plano = p_plano, plano_ate = p_validade where id = p_pdv_id;
  insert into public.assinaturas_log
    (pdv_id, cnpj, acao, plano_anterior, plano, validade_anterior, validade_nova, observacao)
  values (p_pdv_id, v_pdv.cnpj, v_acao, v_pdv.plano, p_plano, v_pdv.plano_ate, p_validade,
          nullif(left(btrim(p_observacao), 500), ''));
end;
$$;

-- Cancela o plano pago: volta ao grátis na hora (o teste grátis, se ainda valer, continua).
create or replace function public.admin_cancelar_plano(p_pdv_id uuid, p_observacao text default null)
returns void
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_pdv public.pdvs%rowtype;
begin
  if not public.is_admin() then
    raise exception 'sem_permissao';
  end if;
  select * into v_pdv from public.pdvs where id = p_pdv_id for update;
  if not found then
    raise exception 'pdv_nao_encontrado';
  end if;
  update public.pdvs set plano = 'gratis', plano_ate = null where id = p_pdv_id;
  insert into public.assinaturas_log
    (pdv_id, cnpj, acao, plano_anterior, plano, validade_anterior, validade_nova, observacao)
  values (p_pdv_id, v_pdv.cnpj, 'cancelar', v_pdv.plano, 'gratis', v_pdv.plano_ate, null,
          nullif(left(btrim(p_observacao), 500), ''));
end;
$$;

revoke execute on function
  public.admin_planos_pdvs(text), public.admin_definir_plano(uuid, text, date, text),
  public.admin_cancelar_plano(uuid, text)
  from public, anon;
grant execute on function
  public.admin_planos_pdvs(text), public.admin_definir_plano(uuid, text, date, text),
  public.admin_cancelar_plano(uuid, text)
  to authenticated;
