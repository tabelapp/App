-- =============================================================================
-- Moderação do Admin no app
--
--  * Preços denunciados: o Admin remove o preço (sai da busca; fica no histórico)
--    ou mantém (limpa as denúncias).
--  * Qualquer preço pode ser removido pelo Admin direto da busca.
--  * Suspender um PDV já aprovado (com motivo): preços oficiais e banners saem
--    da busca, mas nada é apagado; o dono vê o motivo; o Admin pode reativar.
--  * Toda ação fica registrada em `moderacoes` (só o Admin lê).
--
-- Idempotente: pode ser executada de novo sem erro e sem apagar dados.
-- =============================================================================

alter type public.status_verificacao add value if not exists 'suspenso';

alter table public.pdvs add column if not exists motivo_suspensao text;
alter table public.pdvs add column if not exists suspenso_em timestamptz;

create table if not exists public.moderacoes (
  id          bigint generated always as identity primary key,
  admin_id    uuid references public.usuarios (id) on delete set null default auth.uid(),
  acao        text not null check (acao in ('remover_preco', 'manter_preco', 'suspender_pdv', 'reativar_pdv')),
  cotacao_id  uuid,     -- sem FK: o registro fica mesmo depois que o preço sai
  pdv_id      uuid,
  descricao   text,     -- o que foi moderado (produto e preço, ou nome do PDV)
  motivo      text,
  created_at  timestamptz not null default now()
);
alter table public.moderacoes enable row level security;
drop policy if exists moderacoes_admin on public.moderacoes;
create policy moderacoes_admin on public.moderacoes for select to authenticated using (public.is_admin());
revoke all on public.moderacoes from anon;
revoke insert, update, delete, truncate on public.moderacoes from authenticated;
grant select on public.moderacoes to authenticated;

-- -----------------------------------------------------------------------------
-- Preços
-- -----------------------------------------------------------------------------

-- Remove o preço da busca (qualquer fonte). O histórico guarda a exclusão.
create or replace function public.admin_remover_cotacao(p_cotacao_id uuid, p_motivo text default null)
returns void
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_c public.cotacoes%rowtype;
begin
  if not public.is_admin() then
    raise exception 'sem_permissao';
  end if;
  select * into v_c from public.cotacoes where id = p_cotacao_id;
  if not found then
    raise exception 'item_nao_encontrado';
  end if;
  delete from public.cotacoes where id = p_cotacao_id;
  insert into public.moderacoes (acao, cotacao_id, descricao, motivo)
  values ('remover_preco', p_cotacao_id,
          v_c.produto || ' — ' || to_char(v_c.preco_centavos / 100.0, 'FM999G999G990D00'),
          nullif(btrim(p_motivo), ''));
end;
$$;

-- O preço estava certo: limpa as denúncias (o card volta ao normal).
create or replace function public.admin_manter_cotacao(p_cotacao_id uuid)
returns void
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_produto text;
begin
  if not public.is_admin() then
    raise exception 'sem_permissao';
  end if;
  select produto into v_produto from public.cotacoes where id = p_cotacao_id;
  if v_produto is null then
    raise exception 'item_nao_encontrado';
  end if;
  delete from public.denuncias_preco where cotacao_id = p_cotacao_id;
  insert into public.moderacoes (acao, cotacao_id, descricao) values ('manter_preco', p_cotacao_id, v_produto);
end;
$$;

-- -----------------------------------------------------------------------------
-- Estabelecimentos
-- -----------------------------------------------------------------------------

-- PDVs aprovados e suspensos, com busca por nome ou CNPJ (só o Admin).
drop function if exists public.admin_pdvs(text);
create function public.admin_pdvs(p_busca text default null)
returns table (
  id                uuid,
  nome_fantasia     text,
  cnpj              text,
  status            public.status_verificacao,
  motivo_suspensao  text,
  cidade            text,
  dono_nome         text,
  dono_email        text,
  itens_publicados  integer,
  verificado_em     timestamptz
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
    select p.id, p.nome_fantasia, p.cnpj::text, p.status, p.motivo_suspensao,
           (select l.cidade from public.lojas l where l.pdv_id = p.id order by l.created_at limit 1),
           u.nome, u.email,
           (select count(distinct c.produto_busca)::integer from public.cotacoes c
              join public.lojas l on l.id = c.loja_id
             where l.pdv_id = p.id and c.fonte in ('pdv_manual', 'pdv_excel') and c.validade >= public.hoje()),
           p.verificado_em
    from public.pdvs p
    left join public.usuarios u on u.id = p.dono_id
    where p.status::text in ('aprovado', 'suspenso')
      and (v_busca is null
           or strpos(public.normalizar(p.nome_fantasia), v_busca) > 0
           or (v_digitos is not null and strpos(p.cnpj::text, v_digitos) > 0))
    order by p.status::text = 'suspenso' desc, p.nome_fantasia
    limit 200;
end;
$$;

-- Suspende um PDV aprovado: some da busca (preços oficiais e banners), nada é apagado.
create or replace function public.admin_suspender_pdv(p_pdv_id uuid, p_motivo text)
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
  if length(btrim(coalesce(p_motivo, ''))) = 0 then
    raise exception 'motivo_obrigatorio';
  end if;
  update public.pdvs
     set status = 'suspenso', motivo_suspensao = left(btrim(p_motivo), 300), suspenso_em = now()
   where id = p_pdv_id and status = 'aprovado'
  returning nome_fantasia into v_nome;
  if v_nome is null then
    raise exception 'pdv_nao_encontrado';
  end if;
  insert into public.moderacoes (acao, pdv_id, descricao, motivo) values ('suspender_pdv', p_pdv_id, v_nome, btrim(p_motivo));
end;
$$;

-- Reativa um PDV suspenso: volta à busca com a tabela que já tinha.
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
end;
$$;

-- O dono vê o motivo da suspensão em "Meu negócio".
drop function if exists public.meus_pdvs();
create function public.meus_pdvs()
returns table (
  id                        uuid,
  cnpj                      text,
  razao_social              text,
  nome_fantasia             text,
  status                    public.status_verificacao,
  motivo_rejeicao           text,
  cnpj_conferido_no_alvara  boolean,
  created_at                timestamptz,
  verificado_em             timestamptz,
  modo_rede                 boolean,
  site                      text,
  motivo_suspensao          text
)
language sql
stable
security definer
set search_path = ''
as $$
  select p.id, p.cnpj::text, p.razao_social, p.nome_fantasia, p.status, p.motivo_rejeicao,
         p.cnpj_conferido_no_alvara, p.created_at, p.verificado_em, p.modo_rede, p.site, p.motivo_suspensao
  from public.pdvs p
  where p.dono_id = auth.uid()
  order by p.created_at
$$;

-- -----------------------------------------------------------------------------
-- Busca e banners: PDV suspenso some (sem apagar nada)
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

create or replace function public.promocoes_para_busca(
  p_termo  text default null,
  p_lat    double precision default null,
  p_lng    double precision default null,
  p_limite integer default 3
)
returns table (
  id          uuid,
  pdv_id      uuid,
  pdv_nome    text,
  titulo      text,
  descricao   text,
  arte_path   text,
  distancia_km double precision,
  link        text
)
language sql
stable
security invoker
set search_path = ''
as $$
  select pr.id, pr.pdv_id, p.nome_fantasia, pr.titulo, pr.descricao, pr.arte_path,
         public.distancia_km(p_lat, p_lng, l.latitude, l.longitude), pr.link
  from public.promocoes pr
  join public.pdvs p on p.id = pr.pdv_id
  left join public.lojas l on l.id = pr.loja_id
  where pr.status = 'ativa'
    and p.status = 'aprovado'  -- PDV suspenso: banner sai do ar
    and pr.visualizacoes_exibidas < pr.visualizacoes_contratadas
    and (
      pr.raio_km is null
      or public.distancia_km(p_lat, p_lng, l.latitude, l.longitude) <= pr.raio_km
    )
    and (
      cardinality(pr.palavras_chave) = 0
      or exists (
        select 1 from unnest(pr.palavras_chave) k
        where strpos(public.normalizar(p_termo), public.normalizar(k)) > 0
      )
    )
  order by random()
  limit least(greatest(coalesce(p_limite, 3), 1), 10)
$$;

revoke execute on function
  public.admin_remover_cotacao(uuid, text), public.admin_manter_cotacao(uuid), public.admin_pdvs(text),
  public.admin_suspender_pdv(uuid, text), public.admin_reativar_pdv(uuid), public.meus_pdvs(),
  public.buscar_cotacoes(text, double precision, double precision, integer)
  from public, anon;
grant execute on function
  public.admin_remover_cotacao(uuid, text), public.admin_manter_cotacao(uuid), public.admin_pdvs(text),
  public.admin_suspender_pdv(uuid, text), public.admin_reativar_pdv(uuid), public.meus_pdvs(),
  public.buscar_cotacoes(text, double precision, double precision, integer)
  to authenticated, service_role;
