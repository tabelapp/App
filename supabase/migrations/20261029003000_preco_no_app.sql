-- =============================================================================
-- Preço "no app" (clube de vantagens) em preços de encarte e oficiais (29/10/2026)
--
-- Decisão do dono: num encarte, o produto pode ter 2 preços — o do app/clube da loja
-- (o menor) e o normal. `preco_centavos` continua sendo O preço de pesquisa
-- (ordenação, menor preço, filtros, histórico) e passa a guardar o do app quando
-- existir; o normal vai para `preco_normal_centavos` (não mais na OBS).
-- Preço de Nota Fiscal (usuario_nf) não muda: nunca tem preço no app.
--
-- Compatível com o app 1.0.1: colunas novas anuláveis/com padrão, nada removido; o
-- app ignora colunas que não conhece nas respostas.
-- Idempotente.
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 1. Colunas e regra
-- -----------------------------------------------------------------------------
alter table public.cotacoes add column if not exists preco_normal_centavos integer;
alter table public.cotacoes add column if not exists preco_app boolean not null default false;

alter table public.cotacoes drop constraint if exists cotacoes_preco_app_check;
alter table public.cotacoes add constraint cotacoes_preco_app_check check (
  (preco_normal_centavos is null or preco_normal_centavos > 0)
  and (preco_app = false or preco_normal_centavos is null or preco_normal_centavos > preco_centavos)
  -- Nota Fiscal não tem preço no app.
  and (fonte <> 'usuario_nf' or (preco_app = false and preco_normal_centavos is null))
);

grant select (preco_normal_centavos, preco_app) on public.cotacoes to authenticated;

-- Histórico guarda também os dois preços.
alter table public.historico_precos add column if not exists preco_normal_centavos integer;
alter table public.historico_precos add column if not exists preco_app boolean;

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
       and old.a_partir_de is not distinct from new.a_partir_de
       and old.parte_de_combo is not distinct from new.parte_de_combo
       and old.preco_normal_centavos is not distinct from new.preco_normal_centavos
       and old.preco_app is not distinct from new.preco_app then
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
     preco_centavos, validade, data_nf, obs, fonte, chave_acesso_nf, enviado_por, lote_id, unidade, a_partir_de,
     parte_de_combo, preco_normal_centavos, preco_app)
  values
    (v_op, v_c.id, v_c.loja_id, v_pdv, v_cnpj, v_nome, v_end, v_c.produto, v_c.produto_busca,
     v_c.preco_centavos, v_c.validade, v_c.data_nf, v_c.obs, v_c.fonte, v_c.chave_acesso_nf,
     v_c.enviado_por, v_c.lote_id, v_c.unidade, v_c.a_partir_de, v_c.parte_de_combo,
     v_c.preco_normal_centavos, v_c.preco_app);
  return null;
end;
$$;

-- -----------------------------------------------------------------------------
-- 2. Busca e Página da loja devolvem os dois preços (a ordem continua por preco_centavos)
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
  denuncias       integer,
  preco_normal_centavos integer,
  preco_app       boolean
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
           c.unidade, c.a_partir_de, c.parte_de_combo, c.preco_normal_centavos, c.preco_app
    from public.cotacoes c
    left join public.lojas l on l.id = c.loja_id
    left join public.pdvs pv on pv.id = l.pdv_id
    cross join termo
    cross join palavras
    -- Loja cadastrada: só aparece se ativa e com o PDV aprovado (suspenso some da busca).
    -- c.loja_id (e não l.id): com a regra de leitura nova, loja de cadastro não aprovado
    -- nem aparece no join; o preço ligado a ela também não pode aparecer.
    where (c.loja_id is null or (l.ativa and pv.status = 'aprovado'))
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
           v.obs, v.fonte, v.chave_acesso_nf, v.created_at, v.data_nf, v.unidade, v.a_partir_de, v.parte_de_combo,
           v.preco_normal_centavos, v.preco_app
    from validas v
    order by public.local_cotacao(v.loja_id, v.chave_acesso_nf, v.pdv_nome_livre, v.pdv_endereco_livre),
             v.produto_busca,
             public.dia_cotacao(v.data_nf, v.updated_at) desc,
             (v.fonte in ('pdv_manual', 'pdv_excel')) desc,
             v.created_at desc,
             -- Desempate: notas da mesma loja no mesmo dia, a de número maior (a mais recente).
             v.chave_acesso_nf desc nulls last
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
         public._total_denuncias(c.id),
         -- Preço no app/clube da loja: só em encarte e preço oficial; NF nunca tem.
         case when c.preco_app and c.fonte <> 'usuario_nf' then c.preco_normal_centavos end,
         c.preco_app and c.fonte <> 'usuario_nf' and c.preco_normal_centavos is not null
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

drop function if exists public.precos_da_loja(uuid, text);
create function public.precos_da_loja(p_loja_id uuid, p_termo text default null)
returns table (
  id                     uuid,
  produto                text,
  preco_centavos         integer,
  validade               date,
  obs                    text,
  unidade                public.unidade_medida,
  a_partir_de            boolean,
  atualizado_em          timestamptz,
  preco_normal_centavos  integer,
  preco_app              boolean
)
language sql
stable
security invoker
set search_path = ''
as $$
  with loja as (
    select l.id, l.pdv_id
    from public.lojas l
    join public.pdvs p on p.id = l.pdv_id
    where l.id = p_loja_id and l.ativa and p.status = 'aprovado' and p.tipo = 'loja'
  ),
  palavras as (
    select array_remove(string_to_array(coalesce(public.normalizar(p_termo), ''), ' '), '') as ps
  )
  select c.id, c.produto, c.preco_centavos, c.validade, c.obs, c.unidade, c.a_partir_de, c.updated_at,
         case when c.preco_app then c.preco_normal_centavos end,
         c.preco_app and c.preco_normal_centavos is not null
  from public.cotacoes c
  join loja on loja.id = c.loja_id
  cross join palavras
  where c.fonte in ('pdv_manual', 'pdv_excel')
    and c.validade >= public.hoje()
    and c.id not in (select o.id from public._cotacoes_ocultas(loja.pdv_id) o)
    and not exists (select 1 from unnest(palavras.ps) w where strpos(c.produto_busca, w) = 0)
  order by c.produto_busca
  limit 3000
$$;
revoke execute on function public.precos_da_loja(uuid, text) from public, anon;
grant execute on function public.precos_da_loja(uuid, text) to authenticated;

-- -----------------------------------------------------------------------------
-- 3. Envio de encarte: cada item pode trazer o preço no app
--    Item: {produto, preco_centavos, preco_app?, preco_normal_centavos?}
--    preco_app = true  -> preco_centavos é o do app e preco_normal_centavos (obrigatório) o normal,
--                         maior que o do app.
--    preco_app ausente/false -> preço único (preco_normal_centavos é ignorado).
--    O envio pelo usuário continua SUSPENSO (sem permissão para o app; migração 0900).
-- -----------------------------------------------------------------------------
-- Item com preço no app: o normal é obrigatório e maior que o do app.
create or replace function public._precos_app_validos(p_itens jsonb)
returns boolean
language sql
immutable
set search_path = ''
as $$
  select not exists (
    select 1 from jsonb_array_elements(p_itens) e
    where coalesce((e ->> 'preco_app')::boolean, false)
      and coalesce((e ->> 'preco_normal_centavos')::integer, 0) <= coalesce((e ->> 'preco_centavos')::integer, 0)
  )
$$;
revoke execute on function public._precos_app_validos(jsonb) from public, anon, authenticated;

-- Itens do envio pelo usuário: nome, preço e a regra do preço no app.
create or replace function public._itens_encarte_validos(p_itens jsonb)
returns boolean
language sql
immutable
set search_path = ''
as $$
  select public._precos_app_validos(p_itens) and not exists (
    select 1 from jsonb_array_elements(p_itens) e
    where length(btrim(coalesce(e ->> 'produto', ''))) not between 2 and 200
       or coalesce((e ->> 'preco_centavos')::integer, 0) <= 0
  )
$$;
revoke execute on function public._itens_encarte_validos(jsonb) from public, anon, authenticated;

create or replace function public.publicar_encarte(
  p_fotos         text[],
  p_loja_id       uuid,
  p_pdv_nome      text,
  p_pdv_endereco  text,
  p_validade      date,
  p_itens         jsonb
)
returns integer
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_id  uuid;
  v_n   integer;
begin
  if auth.uid() is null then
    raise exception 'nao_autenticado';
  end if;
  if p_fotos is null or cardinality(p_fotos) = 0 then
    raise exception 'foto_obrigatoria';
  end if;
  if cardinality(p_fotos) > 5 then
    raise exception 'fotos_demais';
  end if;
  if exists (select 1 from unnest(p_fotos) f where f not like auth.uid()::text || '/%') then
    raise exception 'foto_invalida';
  end if;
  if p_loja_id is null and length(btrim(coalesce(p_pdv_nome, ''))) = 0 then
    raise exception 'pdv_obrigatorio';
  end if;
  if p_loja_id is not null and not exists (select 1 from public.lojas where id = p_loja_id and ativa) then
    raise exception 'loja_invalida';
  end if;
  if p_validade is null then
    raise exception 'validade_obrigatoria';
  end if;
  if p_validade < public.hoje() then
    raise exception 'validade_passada';
  end if;
  if p_validade > public.hoje() + public.validade_maxima_dias() then
    raise exception 'validade_longa';
  end if;
  if p_itens is null or jsonb_typeof(p_itens) <> 'array' or jsonb_array_length(p_itens) = 0 then
    raise exception 'itens_vazios';
  end if;
  if jsonb_array_length(p_itens) > 200 then
    raise exception 'itens_demais';
  end if;
  if not public._itens_encarte_validos(p_itens) then
    raise exception 'itens_invalidos';
  end if;
  if (select count(*) from public.encartes_pendentes
      where enviado_por = auth.uid() and created_at > now() - interval '1 day')
     >= public.limite_encartes_por_dia() then
    raise exception 'limite_encartes';
  end if;

  insert into public.encartes_pendentes
    (enviado_por, foto_path, fotos, loja_id, pdv_nome, pdv_endereco, validade, status, revisado_em)
  values
    (auth.uid(), p_fotos[1], p_fotos, p_loja_id,
     case when p_loja_id is null then btrim(p_pdv_nome) end,
     case when p_loja_id is null then nullif(btrim(p_pdv_endereco), '') end,
     p_validade, 'aprovado', now())
  returning id into v_id;

  -- O mesmo produto lido duas vezes (ex.: em duas fotos) entra uma vez só.
  insert into public.cotacoes
    (loja_id, pdv_nome_livre, pdv_endereco_livre, produto, preco_centavos, validade,
     fonte, enviado_por, encarte_id, lote_id, preco_app, preco_normal_centavos)
  select distinct on (public.normalizar(btrim(e ->> 'produto')))
         p_loja_id,
         case when p_loja_id is null then btrim(p_pdv_nome) end,
         case when p_loja_id is null then nullif(btrim(p_pdv_endereco), '') end,
         btrim(e ->> 'produto'),
         (e ->> 'preco_centavos')::integer,
         p_validade,
         'usuario_encarte',
         auth.uid(),
         v_id,
         v_id,
         coalesce((e ->> 'preco_app')::boolean, false),
         case when coalesce((e ->> 'preco_app')::boolean, false) then (e ->> 'preco_normal_centavos')::integer end
  from jsonb_array_elements(p_itens) with ordinality as t(e, ordem)
  order by public.normalizar(btrim(e ->> 'produto')), ordem;
  get diagnostics v_n = row_count;

  return v_n;
end;
$$;
-- Continua suspenso para o app (decisão do fundador, migração 20260924000900).
revoke execute on function public.publicar_encarte(text[], uuid, text, text, date, jsonb) from public, anon, authenticated;
grant execute on function public.publicar_encarte(text[], uuid, text, text, date, jsonb) to service_role;

-- Aprovação de encarte pelo Admin (fila antiga): mesmo formato de item.
create or replace function public.aprovar_encarte(
  p_encarte_id  uuid,
  p_itens       jsonb,
  p_validade    date default null
)
returns integer
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_enc  public.encartes_pendentes%rowtype;
  v_n    integer;
begin
  if not public.is_admin() then
    raise exception 'sem_permissao';
  end if;

  select * into v_enc from public.encartes_pendentes where id = p_encarte_id for update;
  if not found then
    raise exception 'encarte_nao_encontrado';
  end if;
  if v_enc.status <> 'pendente' then
    raise exception 'encarte_ja_revisado';
  end if;
  if p_itens is null or jsonb_typeof(p_itens) <> 'array' or jsonb_array_length(p_itens) = 0 then
    raise exception 'itens_vazios';
  end if;
  if not public._precos_app_validos(p_itens) then
    raise exception 'itens_invalidos';
  end if;
  if exists (
    select 1 from jsonb_array_elements(p_itens) e
    where coalesce((e ->> 'validade')::date, p_validade, v_enc.validade) is null
  ) then
    raise exception 'validade_obrigatoria';
  end if;
  if exists (
    select 1 from jsonb_array_elements(p_itens) e
    where coalesce((e ->> 'validade')::date, p_validade, v_enc.validade) < public.hoje()
  ) then
    raise exception 'validade_passada';
  end if;

  insert into public.cotacoes
    (loja_id, pdv_nome_livre, pdv_endereco_livre, produto, preco_centavos, validade,
     fonte, enviado_por, encarte_id, lote_id, preco_app, preco_normal_centavos)
  select v_enc.loja_id,
         case when v_enc.loja_id is null then v_enc.pdv_nome end,
         case when v_enc.loja_id is null then v_enc.pdv_endereco end,
         btrim(e ->> 'produto'),
         (e ->> 'preco_centavos')::integer,
         coalesce((e ->> 'validade')::date, p_validade, v_enc.validade),
         'usuario_encarte',
         v_enc.enviado_por,
         v_enc.id,
         v_enc.id,
         coalesce((e ->> 'preco_app')::boolean, false),
         case when coalesce((e ->> 'preco_app')::boolean, false) then (e ->> 'preco_normal_centavos')::integer end
  from jsonb_array_elements(p_itens) e;
  get diagnostics v_n = row_count;

  update public.encartes_pendentes
     set status = 'aprovado', revisado_por = auth.uid(), revisado_em = now()
   where id = p_encarte_id;

  return v_n;
end;
$$;
