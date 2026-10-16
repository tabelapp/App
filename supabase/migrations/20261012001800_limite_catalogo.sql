-- =============================================================================
-- Limite por TAMANHO DO CATÁLOGO (substitui a cota de 50 operações/mês)
--
-- Decisão do fundador: comerciantes com muitos itens estouravam a cota só para
-- cadastrar o catálogo, e MEIs/prestadores nunca chegariam perto dela.
--
--  * Limite = itens ATIVOS (não excluídos e dentro da validade). Varejo: por
--    loja. Rede: catálogo único da rede (não multiplica pelo nº de lojas).
--  * Só CRIAR item novo é conferido. Subir/baixar preço, renovar validade,
--    mudar OBS/unidade e excluir nunca contam e nunca são bloqueados.
--  * Faixas em `planos` e prazos em `configuracao_app`: ajustáveis por SQL,
--    sem publicar app novo.
--  * Parceiro fundador (piloto Petrópolis): `fundador_ate` = cadastro + 90 dias;
--    até lá vale o plano "mercado". Depois, nada sai do ar: só não cria itens
--    novos acima do limite do plano.
--  * Cobrança: nenhuma por enquanto. "Quero ser avisado" grava em `interesse_planos`.
--    `pagamentos` fica intacta (pacotes de banner continuam iguais).
--  * Serviços (MEIs): `unidade` e `a_partir_de` nas cotações.
--  * `operacoes_log` continua só para medição (view `uso_pdv_mensal`).
--
-- Idempotente: pode ser executada de novo sem erro e sem apagar dados.
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 1. Configuração: planos e parâmetros do app (somente leitura para o app)
-- -----------------------------------------------------------------------------
create table if not exists public.planos (
  codigo                     text primary key,
  nome                       text not null,
  limite_itens               integer not null check (limite_itens > 0),
  -- Preço de referência exibido em "Ver planos" (null = a definir). Não há cobrança ainda.
  preco_referencia_centavos  integer check (preco_referencia_centavos is null or preco_referencia_centavos >= 0),
  ordem                      integer not null default 0,
  ativo                      boolean not null default true
);
insert into public.planos (codigo, nome, limite_itens, preco_referencia_centavos, ordem) values
  ('gratis',  'Grátis',  30,   0,    1),
  ('loja',    'Loja',    300,  null, 2),
  ('mercado', 'Mercado', 3000, null, 3)
on conflict (codigo) do nothing;

create table if not exists public.configuracao_app (
  chave      text primary key,
  valor      jsonb not null,
  descricao  text
);
insert into public.configuracao_app (chave, valor, descricao) values
  ('fundador_ativo', 'true', 'Piloto: PDV novo ganha o período de parceiro fundador.'),
  ('fundador_dias', '90', 'Duração do período de parceiro fundador, em dias a partir do cadastro.'),
  ('fundador_plano', '"mercado"', 'Plano cujo limite vale durante o período de parceiro fundador.'),
  ('fundador_aviso_dias', '7', 'Quantos dias antes do fim o app avisa o PDV.'),
  ('plano_inicial', '"gratis"', 'Plano de todo PDV novo.')
on conflict (chave) do nothing;

alter table public.planos enable row level security;
alter table public.configuracao_app enable row level security;
drop policy if exists planos_select on public.planos;
create policy planos_select on public.planos for select to authenticated using (true);
drop policy if exists configuracao_select on public.configuracao_app;
create policy configuracao_select on public.configuracao_app for select to authenticated using (true);
revoke insert, update, delete, truncate on public.planos, public.configuracao_app from authenticated, anon;
revoke select on public.planos, public.configuracao_app from anon;
grant select on public.planos, public.configuracao_app to authenticated;

-- Lê um parâmetro de configuracao_app (com valor padrão).
create or replace function public.config_app(p_chave text, p_padrao jsonb)
returns jsonb
language sql
stable
security definer
set search_path = ''
as $$
  select coalesce((select valor from public.configuracao_app where chave = p_chave), p_padrao)
$$;

-- -----------------------------------------------------------------------------
-- 2. PDV: plano e período de parceiro fundador
-- -----------------------------------------------------------------------------
alter table public.pdvs add column if not exists plano text not null default 'gratis';
alter table public.pdvs add column if not exists fundador_ate date;
do $$ begin
  if not exists (select 1 from pg_constraint where conname = 'pdvs_plano_fk') then
    alter table public.pdvs add constraint pdvs_plano_fk foreign key (plano) references public.planos (codigo);
  end if;
end $$;
-- O dono vê, mas não altera (plano e fundador_ate não entram no grant de update).
grant select (plano, fundador_ate) on public.pdvs to authenticated;

-- PDVs que já existem: 90 dias a partir desta migração (uma vez só).
do $$ begin
  if not exists (select 1 from public.configuracao_app where chave = 'fundador_migracao_feita') then
    update public.pdvs
       set fundador_ate = public.hoje() + (public.config_app('fundador_dias', '90'))::text::integer
     where fundador_ate is null;
    insert into public.configuracao_app (chave, valor, descricao)
    values ('fundador_migracao_feita', to_jsonb(public.hoje()), 'PDVs existentes receberam o período de fundador.');
  end if;
end $$;

-- PDV novo: plano inicial e, durante o piloto, o período de fundador.
create or replace function public._pdv_plano_inicial()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
begin
  new.plano := coalesce(nullif(new.plano, ''), public.config_app('plano_inicial', '"gratis"') #>> '{}');
  if new.fundador_ate is null and (public.config_app('fundador_ativo', 'true'))::text::boolean then
    new.fundador_ate := (coalesce(new.created_at, now()) at time zone 'America/Sao_Paulo')::date
                        + (public.config_app('fundador_dias', '90'))::text::integer;
  end if;
  return new;
end;
$$;
drop trigger if exists pdvs_plano_inicial on public.pdvs;
create trigger pdvs_plano_inicial before insert on public.pdvs
  for each row execute function public._pdv_plano_inicial();

-- -----------------------------------------------------------------------------
-- 3. Interesse nos planos pagos ("Quero ser avisado")
-- -----------------------------------------------------------------------------
create table if not exists public.interesse_planos (
  id          bigint generated always as identity primary key,
  pdv_id      uuid not null references public.pdvs (id) on delete cascade,
  plano       text not null references public.planos (codigo),
  usuario_id  uuid not null default auth.uid() references public.usuarios (id) on delete cascade,
  created_at  timestamptz not null default now()
);
create unique index if not exists interesse_planos_uidx on public.interesse_planos (pdv_id, plano);
alter table public.interesse_planos enable row level security;
drop policy if exists interesse_insert on public.interesse_planos;
create policy interesse_insert on public.interesse_planos for insert to authenticated
  with check (public.eh_dono_pdv(pdv_id) and usuario_id = auth.uid());
drop policy if exists interesse_select on public.interesse_planos;
create policy interesse_select on public.interesse_planos for select to authenticated
  using (public.eh_dono_pdv(pdv_id) or public.is_admin());
revoke update, delete, truncate on public.interesse_planos from authenticated, anon;
revoke all on public.interesse_planos from anon;
grant select, insert on public.interesse_planos to authenticated;

-- Registra (uma vez por plano) o interesse do PDV. Respeita a RLS (security invoker).
create or replace function public.registrar_interesse_plano(p_pdv_id uuid, p_plano text)
returns void
language sql
security invoker
set search_path = ''
as $$
  insert into public.interesse_planos (pdv_id, plano) values (p_pdv_id, p_plano)
  on conflict (pdv_id, plano) do nothing
$$;

-- -----------------------------------------------------------------------------
-- 4. Serviços e unidades nas cotações
-- -----------------------------------------------------------------------------
do $$ begin
  if not exists (select 1 from pg_type where typname = 'unidade_medida' and typnamespace = 'public'::regnamespace) then
    create type public.unidade_medida as enum
      ('unidade', 'kg', 'litro', 'metro', 'm2', 'hora', 'visita', 'diaria', 'servico');
  end if;
end $$;
alter table public.cotacoes add column if not exists unidade public.unidade_medida not null default 'unidade';
alter table public.cotacoes add column if not exists a_partir_de boolean not null default false;
grant select (unidade, a_partir_de) on public.cotacoes to authenticated;

alter table public.historico_precos add column if not exists unidade public.unidade_medida;
alter table public.historico_precos add column if not exists a_partir_de boolean;

-- O histórico guarda também a unidade e o "a partir de".
create or replace function public._registrar_historico()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_c     public.cotacoes%rowtype;
  v_op    text;
  v_pdv   uuid;
  v_cnpj  char(14);
  v_nome  text;
  v_end   text;
begin
  if tg_op = 'DELETE' then
    v_c := old;
    v_op := 'excluido';
  else
    v_c := new;
    v_op := case when tg_op = 'INSERT' then 'criado' else 'alterado' end;
    -- Atualização que não muda nada relevante não vira histórico.
    if tg_op = 'UPDATE'
       and old.preco_centavos = new.preco_centavos
       and old.produto = new.produto
       and old.validade is not distinct from new.validade
       and old.obs is not distinct from new.obs
       and old.unidade is not distinct from new.unidade
       and old.a_partir_de is not distinct from new.a_partir_de then
      return null;
    end if;
  end if;

  if v_c.loja_id is not null then
    select p.id, p.cnpj, p.nome_fantasia, concat_ws(', ', l.endereco, l.bairro, l.cidade || ' - ' || l.uf)
      into v_pdv, v_cnpj, v_nome, v_end
      from public.lojas l join public.pdvs p on p.id = l.pdv_id
     where l.id = v_c.loja_id;
  end if;
  v_cnpj := coalesce(v_cnpj, substring(v_c.chave_acesso_nf from 7 for 14));
  v_nome := coalesce(v_nome, v_c.pdv_nome_livre);
  v_end  := coalesce(v_end, v_c.pdv_endereco_livre);

  insert into public.historico_precos
    (operacao, cotacao_id, loja_id, pdv_id, cnpj, pdv_nome, pdv_endereco, produto, produto_busca,
     preco_centavos, validade, data_nf, obs, fonte, chave_acesso_nf, enviado_por, lote_id, unidade, a_partir_de)
  values
    (v_op, v_c.id, v_c.loja_id, v_pdv, v_cnpj, v_nome, v_end, v_c.produto, v_c.produto_busca,
     v_c.preco_centavos, v_c.validade, v_c.data_nf, v_c.obs, v_c.fonte, v_c.chave_acesso_nf,
     v_c.enviado_por, v_c.lote_id, v_c.unidade, v_c.a_partir_de);
  return null;
end;
$$;

-- -----------------------------------------------------------------------------
-- 5. Limite do catálogo
-- -----------------------------------------------------------------------------

-- Plano que vale hoje: o de fundador (enquanto durar) ou o contratado.
create or replace function public._plano_efetivo(p_pdv public.pdvs)
returns text
language sql
stable
security definer
set search_path = ''
as $$
  select case
    when p_pdv.fundador_ate is not null and p_pdv.fundador_ate >= public.hoje()
      then public.config_app('fundador_plano', '"mercado"') #>> '{}'
    else p_pdv.plano
  end
$$;

-- Itens ativos da carteira: modo rede = catálogo único da rede; varejo = a loja.
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
    and (p_carteira is null or c.loja_id = p_carteira)
$$;

-- Situação do catálogo para o painel do PDV (só o dono ou o Admin).
-- p_loja_id é ignorado em modo rede e obrigatório em modo varejo.
drop function if exists public.status_catalogo(uuid, uuid);
create function public.status_catalogo(p_pdv_id uuid, p_loja_id uuid default null)
returns table (
  plano                text,
  plano_nome           text,
  limite_plano         integer,
  plano_efetivo        text,
  plano_efetivo_nome   text,
  limite               integer,
  itens_ativos         integer,
  disponiveis          integer,
  fundador_ate         date,
  aviso_fundador_dias  integer
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
  v_efetivo   text;
begin
  select * into v_pdv from public.pdvs where id = p_pdv_id;
  if not found or not (v_pdv.dono_id = auth.uid() or public.is_admin()) then
    raise exception 'sem_permissao';
  end if;
  v_carteira := public._carteira_cota(v_pdv, p_loja_id);
  v_ativos := public._itens_ativos(v_pdv, v_carteira);
  v_efetivo := public._plano_efetivo(v_pdv);
  return query
    select v_pdv.plano, pc.nome, pc.limite_itens, v_efetivo, pe.nome, pe.limite_itens, v_ativos,
           greatest(pe.limite_itens - v_ativos, 0), v_pdv.fundador_ate,
           (public.config_app('fundador_aviso_dias', '7'))::text::integer
    from public.planos pc, public.planos pe
    where pc.codigo = v_pdv.plano and pe.codigo = v_efetivo;
end;
$$;

-- -----------------------------------------------------------------------------
-- 6. Salvar/importar preços do PDV — sem cota de operações; só CRIAR é limitado
--
-- Mesma rotina para edição manual (1 item) e planilha (N itens). O produto
-- normalizado é a chave dentro da loja: se já existe (mesmo vencido), atualiza.
--   * Atualizações nunca são bloqueadas e nunca ficam de fora.
--   * Itens novos entram, na ordem da planilha, até o espaço disponível.
--     Edição manual (1 item) acima do limite: erro 'limite_catalogo'.
--     Planilha: grava as atualizações e os novos que cabem, numa transação só,
--     e devolve a lista dos que ficaram de fora (o app mostra o resumo).
--   * p_simular = true: só o resumo, nada é gravado.
-- Modo rede: o item vai para todas as lojas ativas e conta uma vez.
-- Assinatura e chaves antigas da resposta mantidas (versões antigas do app).
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

  -- Limite: só os itens NOVOS disputam o espaço disponível, na ordem da planilha.
  v_efetivo := public._plano_efetivo(v_pdv);
  select limite_itens, nome into v_limite, v_plano_nome from public.planos where codigo = v_efetivo;
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

-- Excluir: sempre permitido (libera espaço). Só troca o comentário: a função continua igual.
comment on function public.pdv_excluir_item(uuid) is
  'Exclui o item (todas as lojas em modo rede). Nunca bloqueado; libera espaço no limite do catálogo.';

-- -----------------------------------------------------------------------------
-- 7. Cota antiga desativada
-- -----------------------------------------------------------------------------
-- cota_status fica para versões antigas do app, agora mostrando o limite do
-- catálogo (gratis = limite, gratis_usadas = itens ativos, restantes = espaço livre).
create or replace function public.cota_status(p_pdv_id uuid, p_loja_id uuid default null)
returns table (
  loja_id           uuid,
  gratis            integer,
  gratis_usadas     integer,
  saldo_pacotes     integer,
  pacote_vence_em   timestamptz,
  restantes         integer
)
language plpgsql
stable
security definer
set search_path = ''
as $$
declare
  v_pdv       public.pdvs%rowtype;
  v_carteira  uuid;
begin
  select * into v_pdv from public.pdvs where id = p_pdv_id;
  if not found or not (v_pdv.dono_id = auth.uid() or public.is_admin()) then
    raise exception 'sem_permissao';
  end if;
  v_carteira := public._carteira_cota(v_pdv, p_loja_id);
  return query
    select v_carteira, s.limite, s.itens_ativos, 0, null::timestamptz, s.disponiveis
    from public.status_catalogo(p_pdv_id, p_loja_id) s;
end;
$$;
comment on function public.cota_status(uuid, uuid) is
  'OBSOLETA: só para apps antigos. Use status_catalogo().';

-- Pacote de +50 operações não existe mais (a tabela pagamentos continua intacta).
drop function if exists public.comprar_pacote_operacoes(uuid, uuid);
drop function if exists public._gratis_usadas(uuid, uuid);
drop function if exists public._pacotes_ativos(uuid, uuid);
drop function if exists public.cota_gratis_mensal();
drop function if exists public.operacoes_por_pacote();

-- -----------------------------------------------------------------------------
-- 8. Busca e painel com unidade / "a partir de"
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
  a_partir_de     boolean
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
  validas as (
    -- Colunas listadas uma a uma: quem busca não pode ler `enviado_por`.
    select c.id, c.loja_id, c.pdv_nome_livre, c.pdv_endereco_livre, c.produto, c.produto_busca,
           c.preco_centavos, c.validade, c.obs, c.fonte, c.chave_acesso_nf, c.created_at, c.updated_at, c.data_nf,
           c.unidade, c.a_partir_de
    from public.cotacoes c
    left join public.lojas l on l.id = c.loja_id
    cross join termo
    cross join palavras
    where (l.id is null or l.ativa)
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
           v.obs, v.fonte, v.chave_acesso_nf, v.created_at, v.data_nf, v.unidade, v.a_partir_de
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
         c.a_partir_de
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
revoke execute on function public.buscar_cotacoes(text, double precision, double precision, integer) from public, anon;
grant execute on function public.buscar_cotacoes(text, double precision, double precision, integer)
  to authenticated, service_role;

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
  a_partir_de     boolean
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
    select distinct on (c.produto_busca)
           c.id, c.loja_id, c.produto, c.preco_centavos, c.validade, c.obs, c.updated_at, c.unidade, c.a_partir_de
    from public.cotacoes c
    join public.lojas l on l.id = c.loja_id
    where l.pdv_id = p_pdv_id
      and c.fonte in ('pdv_manual', 'pdv_excel')
      and (v_rede or p_loja_id is null or c.loja_id = p_loja_id)
    order by c.produto_busca, c.preco_centavos desc;
end;
$$;

-- -----------------------------------------------------------------------------
-- 9. Medição de uso por PDV e mês (para ajustar as faixas com dados reais)
-- -----------------------------------------------------------------------------
create or replace view public.uso_pdv_mensal with (security_invoker = true) as
select o.pdv_id,
       p.nome_fantasia,
       o.competencia                                          as mes,
       count(*) filter (where o.tipo = 'criar_item')         as criacoes,
       count(*) filter (where o.tipo = 'aumentar_preco')     as aumentos,
       count(*) filter (where o.tipo = 'diminuir_preco')     as reducoes,
       count(*) filter (where o.tipo = 'editar_dados')       as edicoes,
       count(*) filter (where o.tipo = 'excluir_item')       as exclusoes,
       -- Itens ativos HOJE (o tamanho atual do catálogo, todas as lojas).
       (select count(distinct c.produto_busca)
          from public.cotacoes c join public.lojas l on l.id = c.loja_id
         where l.pdv_id = o.pdv_id and c.fonte in ('pdv_manual', 'pdv_excel')
           and c.validade >= public.hoje())                  as itens_ativos
from public.operacoes_log o
join public.pdvs p on p.id = o.pdv_id
group by o.pdv_id, p.nome_fantasia, o.competencia;
revoke all on public.uso_pdv_mensal from anon;
grant select on public.uso_pdv_mensal to authenticated;

-- -----------------------------------------------------------------------------
-- Permissões das funções
-- -----------------------------------------------------------------------------
revoke execute on function
  public.config_app(text, jsonb), public._plano_efetivo(public.pdvs), public._itens_ativos(public.pdvs, uuid),
  public._pdv_plano_inicial()
  from public, anon, authenticated;
revoke execute on function
  public.status_catalogo(uuid, uuid), public.registrar_interesse_plano(uuid, text),
  public.meus_precos(uuid, uuid)
  from public, anon;
grant execute on function
  public.status_catalogo(uuid, uuid), public.registrar_interesse_plano(uuid, text),
  public.meus_precos(uuid, uuid)
  to authenticated, service_role;
