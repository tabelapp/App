-- =============================================================================
-- Versão 1.0.3: selo "no app" sem preço normal e histórico para o gráfico (03/11/2026)
--
--  * Selo "no app": vale sempre que preco_app = true (fora Nota Fiscal). O preço
--    normal passa a ser opcional: quando existe e é maior, o app mostra riscado.
--    A regra da tabela (cotacoes_preco_app_check) já aceitava preco_app sem normal.
--    buscar_cotacoes e precos_da_loja mudam só o valor de preco_app (mesmas colunas):
--    o app 1.0.2 continua igual (sem o normal, ele mostra o preço comum).
--  * historico_preco_produto(cotacao): série do MESMO produto no MESMO local
--    (loja cadastrada, CNPJ da nota ou nome+endereço livres), últimos 90 dias,
--    um ponto por dia (o último do dia), no máximo 30 pontos, em ordem de data.
--    Devolve só (data, preco_centavos, preco_app): nunca quem enviou.
--
-- Nada é removido. Idempotente.
-- =============================================================================

create or replace function public.buscar_cotacoes(
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
         -- Preço no app/clube da loja: só em encarte e preço oficial; NF nunca tem. O normal é opcional (1.0.3).
         case when c.preco_app and c.fonte <> 'usuario_nf' then c.preco_normal_centavos end,
         c.preco_app and c.fonte <> 'usuario_nf'
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

create or replace function public.precos_da_loja(p_loja_id uuid, p_termo text default null)
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
         c.preco_app
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

-- -----------------------------------------------------------------------------
-- Histórico do preço (gráfico da página do produto)
-- -----------------------------------------------------------------------------
-- Local de uma linha do histórico, na mesma ideia de local_cotacao():
-- loja cadastrada > CNPJ da nota > nome + endereço livres.
create or replace function public._local_historico(p_loja_id uuid, p_cnpj text, p_nome text, p_endereco text)
returns text
language sql
immutable
set search_path = ''
as $$
  select case
    when p_loja_id is not null then 'loja:' || p_loja_id::text
    when p_cnpj is not null then 'cnpj:' || p_cnpj
    else 'livre:' || coalesce(public.normalizar(p_nome), '') || '|' || coalesce(public.normalizar(p_endereco), '')
  end
$$;
revoke execute on function public._local_historico(uuid, text, text, text) from public, anon, authenticated;

drop function if exists public.historico_preco_produto(uuid);
create function public.historico_preco_produto(p_cotacao_id uuid)
returns table (
  data            date,
  preco_centavos  integer,
  preco_app       boolean
)
language sql
stable
security definer
set search_path = ''
as $$
  with alvo as (
    -- O preço aberto na página: só se ele mesmo pode aparecer na busca (loja ativa e aprovada).
    select c.produto_busca,
           public._local_historico(c.loja_id, substring(c.chave_acesso_nf from 7 for 14),
                                   c.pdv_nome_livre, c.pdv_endereco_livre) as local
    from public.cotacoes c
    left join public.lojas l on l.id = c.loja_id
    left join public.pdvs p on p.id = l.pdv_id
    where c.id = p_cotacao_id
      and auth.uid() is not null
      and (c.loja_id is null or (l.ativa and p.status = 'aprovado'))
  ),
  pontos as (
    select coalesce(h.data_nf, (h.registrado_em at time zone 'America/Sao_Paulo')::date) as dia,
           h.preco_centavos, coalesce(h.preco_app, false) as preco_app, h.registrado_em, h.id
    from public.historico_precos h
    join alvo a on a.produto_busca = h.produto_busca
               and a.local = public._local_historico(h.loja_id, h.cnpj, h.pdv_nome, h.pdv_endereco)
    where h.operacao in ('criado', 'alterado')
  ),
  por_dia as (
    select distinct on (dia) dia, preco_centavos, preco_app
    from pontos
    where dia >= public.hoje() - 90 and dia <= public.hoje()
    order by dia, registrado_em desc, id desc
  ),
  ultimos as (
    select * from por_dia order by dia desc limit 30
  )
  select dia, preco_centavos, preco_app from ultimos order by dia
$$;
revoke execute on function public.historico_preco_produto(uuid) from public, anon;
grant execute on function public.historico_preco_produto(uuid) to authenticated;
