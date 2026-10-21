-- =============================================================================
-- Denunciar banner + limite das artes de banner (auditoria Google Play, 21/10/2026)
--
--  * denuncias_promocao: "Denunciar este banner" (1 por pessoa por banner), com
--    motivo opcional. Ninguém lê as denúncias dos outros; o Admin lê todas.
--  * admin_banners_denunciados(): fila do Admin, os mais denunciados primeiro.
--  * admin_pausar_banner(): tira o banner do ar (status 'pausada'); o dono vê
--    em "Meu negócio". admin_manter_banner(): limpa as denúncias.
--  * Bucket "promocoes" (artes): até 5 MB, só imagem (igual aos outros buckets).
--
-- Idempotente: pode ser executada de novo sem erro e sem apagar dados.
-- =============================================================================

create table if not exists public.denuncias_promocao (
  id           bigint generated always as identity primary key,
  promocao_id  uuid not null references public.promocoes (id) on delete cascade,
  usuario_id   uuid not null default auth.uid() references public.usuarios (id) on delete cascade,
  motivo       text check (motivo is null or motivo in ('enganoso', 'ofensivo', 'golpe', 'outro')),
  created_at   timestamptz not null default now()
);
create unique index if not exists denuncias_promocao_uidx on public.denuncias_promocao (promocao_id, usuario_id);
create index if not exists denuncias_promocao_promo_idx on public.denuncias_promocao (promocao_id);

alter table public.denuncias_promocao enable row level security;
drop policy if exists denuncias_promocao_insert on public.denuncias_promocao;
create policy denuncias_promocao_insert on public.denuncias_promocao for insert to authenticated
  with check (usuario_id = auth.uid());
drop policy if exists denuncias_promocao_select on public.denuncias_promocao;
create policy denuncias_promocao_select on public.denuncias_promocao for select to authenticated
  using (usuario_id = auth.uid() or public.is_admin());
revoke all on public.denuncias_promocao from anon;
revoke update, delete, truncate on public.denuncias_promocao from authenticated;
grant select, insert on public.denuncias_promocao to authenticated;

-- Novas ações no registro de moderação.
alter table public.moderacoes drop constraint if exists moderacoes_acao_check;
alter table public.moderacoes add constraint moderacoes_acao_check
  check (acao in ('remover_preco', 'manter_preco', 'suspender_pdv', 'reativar_pdv', 'pausar_banner', 'manter_banner'));
alter table public.moderacoes add column if not exists promocao_id uuid;  -- sem FK: o registro fica

-- Registra a denúncia; repetir não duplica (devolve false).
create or replace function public.denunciar_banner(p_promocao_id uuid, p_motivo text default null)
returns boolean
language plpgsql
security invoker
set search_path = ''
as $$
declare
  v_n integer;
begin
  if auth.uid() is null then
    raise exception 'nao_autenticado';
  end if;
  insert into public.denuncias_promocao (promocao_id, motivo)
  values (p_promocao_id, nullif(btrim(p_motivo), ''))
  on conflict (promocao_id, usuario_id) do nothing;
  get diagnostics v_n = row_count;
  return v_n > 0;
end;
$$;

-- Admin: banners denunciados, os mais denunciados primeiro.
create or replace function public.admin_banners_denunciados()
returns table (
  promocao_id   uuid,
  pdv_nome      text,
  titulo        text,
  descricao     text,
  link          text,
  status        public.status_promocao,
  denuncias     integer,
  enganoso      integer,
  ofensivo      integer,
  golpe         integer,
  outros        integer,
  ultima        timestamptz
)
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
    select pr.id, p.nome_fantasia, pr.titulo, pr.descricao, pr.link, pr.status,
           count(*)::integer,
           count(*) filter (where d.motivo = 'enganoso')::integer,
           count(*) filter (where d.motivo = 'ofensivo')::integer,
           count(*) filter (where d.motivo = 'golpe')::integer,
           count(*) filter (where d.motivo is null or d.motivo = 'outro')::integer,
           max(d.created_at)
    from public.denuncias_promocao d
    join public.promocoes pr on pr.id = d.promocao_id
    join public.pdvs p on p.id = pr.pdv_id
    group by pr.id, p.nome_fantasia
    order by count(*) desc, max(d.created_at) desc;
end;
$$;

-- Admin: tira o banner do ar (as denúncias saem da fila).
create or replace function public.admin_pausar_banner(p_promocao_id uuid, p_motivo text default null)
returns void
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_titulo text;
begin
  if not public.is_admin() then
    raise exception 'sem_permissao';
  end if;
  update public.promocoes set status = 'pausada' where id = p_promocao_id returning titulo into v_titulo;
  if v_titulo is null then
    raise exception 'item_nao_encontrado';
  end if;
  delete from public.denuncias_promocao where promocao_id = p_promocao_id;
  insert into public.moderacoes (acao, promocao_id, descricao, motivo)
  values ('pausar_banner', p_promocao_id, v_titulo, nullif(btrim(p_motivo), ''));
end;
$$;

-- Admin: o banner está certo; limpa as denúncias.
create or replace function public.admin_manter_banner(p_promocao_id uuid)
returns void
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_titulo text;
begin
  if not public.is_admin() then
    raise exception 'sem_permissao';
  end if;
  select titulo into v_titulo from public.promocoes where id = p_promocao_id;
  if v_titulo is null then
    raise exception 'item_nao_encontrado';
  end if;
  delete from public.denuncias_promocao where promocao_id = p_promocao_id;
  insert into public.moderacoes (acao, promocao_id, descricao) values ('manter_banner', p_promocao_id, v_titulo);
end;
$$;

revoke execute on function public.denunciar_banner(uuid, text), public.admin_banners_denunciados(),
  public.admin_pausar_banner(uuid, text), public.admin_manter_banner(uuid) from public, anon;
grant execute on function public.denunciar_banner(uuid, text), public.admin_banners_denunciados(),
  public.admin_pausar_banner(uuid, text), public.admin_manter_banner(uuid) to authenticated;

-- Artes de banner: até 5 MB, só imagem.
do $$
begin
  if exists (select 1 from information_schema.schemata where schema_name = 'storage') then
    update storage.buckets
       set file_size_limit = 5242880,
           allowed_mime_types = array['image/jpeg', 'image/png', 'image/webp']
     where id = 'promocoes';
  end if;
end;
$$;
