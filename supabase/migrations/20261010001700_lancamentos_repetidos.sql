-- =============================================================================
-- Lançamentos repetidos (decisão do fundador)
--
-- 1. No banco, o mesmo produto no mesmo local, no mesmo dia e pelo mesmo preço
--    é UM lançamento só: os repetidos são apagados e não entram mais. Dias ou
--    preços diferentes continuam todos guardados (é a história do preço).
-- 2. Na busca, para cada produto em cada local aparece só o lançamento mais
--    recente.
--
-- "Local": a loja cadastrada; sem loja, o CNPJ da nota fiscal; sem nota, o
-- nome + endereço digitados. "Dia": a data da compra na NF; nos outros, o dia
-- do lançamento (horário de Brasília). O preço oficial do PDV já é um item só
-- por loja (atualizado no lugar) e não entra nesta limpeza.
-- =============================================================================

create function public.local_cotacao(
  p_loja_id uuid, p_chave char(44), p_nome text, p_endereco text
)
returns text
language sql
immutable
set search_path = ''
as $$
  select case
    when p_loja_id is not null then 'loja:' || p_loja_id::text
    when p_chave is not null then 'cnpj:' || substring(p_chave from 7 for 14)
    else 'livre:' || coalesce(public.normalizar(p_nome), '') || '|' || coalesce(public.normalizar(p_endereco), '')
  end
$$;

create function public.dia_cotacao(p_data_nf date, p_criado timestamptz)
returns date
language sql
immutable
set search_path = ''
as $$
  select coalesce(p_data_nf, (p_criado at time zone 'America/Sao_Paulo')::date)
$$;

create index cotacoes_local_produto_idx on public.cotacoes
  (public.local_cotacao(loja_id, chave_acesso_nf, pdv_nome_livre, pdv_endereco_livre), produto_busca);

-- ---------------------------------------------------------------------------
-- Limpeza do que já existe: fica o primeiro lançamento de cada grupo igual.
-- Os repetidos saem também do histórico (são cópias idênticas — contariam em
-- dobro numa média de preços). O apagar não gera linha "excluido".
-- ---------------------------------------------------------------------------
create temporary table _repetidos as
select id from (
  select c.id,
         row_number() over (
           partition by public.local_cotacao(c.loja_id, c.chave_acesso_nf, c.pdv_nome_livre, c.pdv_endereco_livre),
                        c.produto_busca, c.preco_centavos, public.dia_cotacao(c.data_nf, c.created_at)
           order by c.created_at, c.id
         ) as n
  from public.cotacoes c
  where c.fonte not in ('pdv_manual', 'pdv_excel')
) x
where x.n > 1;

alter table public.cotacoes disable trigger cotacoes_historico;
delete from public.cotacoes where id in (select id from _repetidos);
alter table public.cotacoes enable trigger cotacoes_historico;
delete from public.historico_precos where cotacao_id in (select id from _repetidos);
drop table _repetidos;

-- ---------------------------------------------------------------------------
-- Daqui para a frente: o lançamento igual a um que já existe é ignorado
-- (não dá erro — a nota é aceita, só o item repetido não entra de novo).
-- ---------------------------------------------------------------------------
create function public._ignorar_lancamento_repetido()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
begin
  if new.fonte in ('pdv_manual', 'pdv_excel') then
    return new;
  end if;
  if exists (
    select 1 from public.cotacoes c
     where public.local_cotacao(c.loja_id, c.chave_acesso_nf, c.pdv_nome_livre, c.pdv_endereco_livre)
           = public.local_cotacao(new.loja_id, new.chave_acesso_nf, new.pdv_nome_livre, new.pdv_endereco_livre)
       and c.produto_busca = public.normalizar(new.produto)
       and c.preco_centavos = new.preco_centavos
       and c.fonte not in ('pdv_manual', 'pdv_excel')
       and public.dia_cotacao(c.data_nf, c.created_at) = public.dia_cotacao(new.data_nf, coalesce(new.created_at, now()))
  ) then
    return null;
  end if;
  return new;
end;
$$;

revoke execute on function public._ignorar_lancamento_repetido() from public, anon, authenticated;

create trigger cotacoes_ignorar_repetido
  before insert on public.cotacoes
  for each row execute function public._ignorar_lancamento_repetido();

-- ---------------------------------------------------------------------------
-- Busca: só o lançamento mais recente de cada produto em cada local.
-- Mais recente = maior dia (data da compra na NF; nos outros, a última
-- alteração); no empate, o preço oficial do PDV, depois o último enviado.
-- ---------------------------------------------------------------------------
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
  ),
  validas as (
    -- Colunas listadas uma a uma: quem busca não pode ler `enviado_por`.
    select c.id, c.loja_id, c.pdv_nome_livre, c.pdv_endereco_livre, c.produto, c.produto_busca,
           c.preco_centavos, c.validade, c.obs, c.fonte, c.chave_acesso_nf, c.created_at, c.updated_at, c.data_nf
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
           v.obs, v.fonte, v.chave_acesso_nf, v.created_at, v.data_nf
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
         c.data_nf
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
