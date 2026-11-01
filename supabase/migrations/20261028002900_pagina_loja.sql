-- =============================================================================
-- Página da loja + leitura de cadastros só do que é público (28/10/2026)
--
--  * pagina_loja(loja): cabeçalho da página pública da loja (nome, loja,
--    endereço, WhatsApp/telefone, site, ponto no mapa). Só loja ativa de
--    estabelecimento aprovado (não suspenso) do tipo "loja".
--  * precos_da_loja(loja, termo): a tabela OFICIAL da loja (o que o próprio
--    estabelecimento publicou), só itens ativos e válidos: não pausados pelo
--    dono, não pausados pelo limite do plano, não vencidos. Busca dentro da loja.
--  * banners_da_loja(pdv): banners ativos do estabelecimento (mesmas regras da busca).
--  * Privacidade: cada usuário só lê cadastros aprovados (mais o próprio; o
--    Admin lê todos). A razão social (no MEI costuma ser o nome do dono) deixa
--    de ser legível direto pela API; o dono e o Admin continuam vendo pelas
--    funções meus_pdvs/admin_*. Lojas de anunciante (endereço só para análise)
--    também deixam de ser legíveis por terceiros.
--
-- Nada é gravado ao abrir a página. Idempotente.
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 1. Leitura de cadastros: só o público
-- -----------------------------------------------------------------------------
drop policy if exists pdvs_select on public.pdvs;
create policy pdvs_select on public.pdvs for select to authenticated
  using (status = 'aprovado' or public.eh_dono_pdv(id) or public.is_admin());

drop policy if exists lojas_select on public.lojas;
create policy lojas_select on public.lojas for select to authenticated
  using (
    public.eh_dono_pdv(pdv_id)
    or public.is_admin()
    or exists (select 1 from public.pdvs p where p.id = pdv_id and p.status = 'aprovado' and p.tipo = 'loja')
  );

revoke select (razao_social, dono_id) on public.pdvs from authenticated;

-- A busca: preço ligado a loja que o usuário não pode ver (cadastro suspenso,
-- pendente ou de anunciante) não aparece.
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
           v.obs, v.fonte, v.chave_acesso_nf, v.created_at, v.data_nf, v.unidade, v.a_partir_de, v.parte_de_combo
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


-- -----------------------------------------------------------------------------
-- 2. Página da loja
-- -----------------------------------------------------------------------------
create or replace function public.pagina_loja(p_loja_id uuid)
returns table (
  loja_id    uuid,
  pdv_id     uuid,
  pdv_nome   text,
  loja_nome  text,
  endereco   text,
  telefone   text,
  site       text,
  latitude   double precision,
  longitude  double precision
)
language sql
stable
security invoker
set search_path = ''
as $$
  select l.id, p.id, p.nome_fantasia, l.nome,
         concat_ws(', ', l.endereco, l.bairro, l.cidade || ' - ' || l.uf),
         coalesce(nullif(btrim(l.whatsapp), ''), l.telefone),
         p.site, l.latitude, l.longitude
  from public.lojas l
  join public.pdvs p on p.id = l.pdv_id
  where l.id = p_loja_id
    and l.ativa
    and p.status = 'aprovado'
    and p.tipo = 'loja'
$$;

create or replace function public.precos_da_loja(p_loja_id uuid, p_termo text default null)
returns table (
  id              uuid,
  produto         text,
  preco_centavos  integer,
  validade        date,
  obs             text,
  unidade         public.unidade_medida,
  a_partir_de     boolean,
  atualizado_em   timestamptz
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
  select c.id, c.produto, c.preco_centavos, c.validade, c.obs, c.unidade, c.a_partir_de, c.updated_at
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

create or replace function public.banners_da_loja(p_pdv_id uuid)
returns table (
  id         uuid,
  pdv_id     uuid,
  pdv_nome   text,
  titulo     text,
  descricao  text,
  arte_path  text,
  link       text
)
language sql
stable
security invoker
set search_path = ''
as $$
  select pr.id, pr.pdv_id, p.nome_fantasia, pr.titulo, pr.descricao, pr.arte_path, pr.link
  from public.promocoes pr
  join public.pdvs p on p.id = pr.pdv_id
  where pr.pdv_id = p_pdv_id
    and pr.status = 'ativa'
    and p.status = 'aprovado'
    and pr.visualizacoes_exibidas < pr.visualizacoes_contratadas
  order by pr.created_at desc
  limit 6
$$;

revoke execute on function public.pagina_loja(uuid), public.precos_da_loja(uuid, text),
  public.banners_da_loja(uuid) from public, anon;
grant execute on function public.pagina_loja(uuid), public.precos_da_loja(uuid, text),
  public.banners_da_loja(uuid) to authenticated;
