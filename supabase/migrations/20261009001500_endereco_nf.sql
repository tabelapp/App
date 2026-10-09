-- =============================================================================
-- Correção: endereço do ponto de venda nos preços de nota fiscal
--
-- Para preços de NF de estabelecimento NÃO cadastrado, a busca montava o
-- endereço a partir da loja (inexistente) com concat_ws, que devolve '' (texto
-- vazio, não nulo) — e o '' vencia o endereço lido da nota no coalesce. Resultado:
-- o card mostrava só a razão social, sem endereço.
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
  data_nf         date
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
         -- (concat_ws com tudo nulo devolve '' e escondia o endereço da nota.)
         case when l.id is not null
              then concat_ws(', ', l.endereco, l.bairro, l.cidade || ' - ' || l.uf)
              else nullif(btrim(c.pdv_endereco_livre), '')
         end,
         l.telefone,
         p.site,
         l.latitude,
         l.longitude,
         public.distancia_km(p_lat, p_lng, l.latitude, l.longitude),
         c.created_at,
         c.data_nf
  from public.cotacoes c
  left join public.lojas l on l.id = c.loja_id
  left join public.pdvs p on p.id = l.pdv_id
  left join public.nomes_pdv n on c.loja_id is null and n.cnpj = substring(c.chave_acesso_nf from 7 for 14)
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
  order by
    case when termo.t is null then c.created_at end desc,
    c.preco_centavos asc
  limit least(greatest(coalesce(p_limite, 100), 1), 500)
$$;

-- O mesmo '' foi gravado no histórico na carga inicial: corrige com o endereço da nota.
update public.historico_precos h
   set pdv_endereco = c.pdv_endereco_livre
  from public.cotacoes c
 where h.cotacao_id = c.id and coalesce(h.pdv_endereco, '') = '' and c.pdv_endereco_livre is not null;
update public.historico_precos set pdv_endereco = null where pdv_endereco = '';

-- Notas antigas sem endereço: usa o endereço de outra nota do mesmo CNPJ, se houver.
update public.cotacoes c
   set pdv_endereco_livre = (
     select c2.pdv_endereco_livre from public.cotacoes c2
      where c2.chave_acesso_nf is not null and c2.pdv_endereco_livre is not null
        and substring(c2.chave_acesso_nf from 7 for 14) = substring(c.chave_acesso_nf from 7 for 14)
      order by c2.created_at desc limit 1)
 where c.loja_id is null and c.chave_acesso_nf is not null and c.pdv_endereco_livre is null;
