-- =============================================================================
-- Coordenadas das lojas cadastradas (item 4 do checklist do MVP)
--
-- O app localiza o endereço da loja no aparelho (Geocoder do Android) e grava a
-- coordenada aqui: no cadastro, ao editar o endereço e ao abrir o painel (para
-- as lojas antigas). Se o endereço não for achado, o dono pode usar "Estou na
-- loja" (localização do celular). O Admin tem um botão para localizar de uma
-- vez as lojas que ainda estão sem coordenada.
--
-- Mudou o endereço sem mandar coordenada nova? A coordenada antiga é apagada
-- (pino errado é pior que pino nenhum) e o app localiza de novo.
--
-- Idempotente: pode ser executada de novo sem erro e sem apagar dados.
-- =============================================================================

alter table public.lojas add column if not exists coordenadas_origem text
  check (coordenadas_origem in ('endereco', 'aparelho'));
alter table public.lojas add column if not exists coordenadas_em timestamptz;

-- ---------------------------------------------------------------------------
-- Endereço mudou -> coordenada antiga sai
-- ---------------------------------------------------------------------------
create or replace function public._lojas_limpar_coordenadas()
returns trigger
language plpgsql
set search_path = ''
as $$
begin
  if (new.endereco, new.bairro, new.cidade, new.uf)
       is distinct from (old.endereco, old.bairro, old.cidade, old.uf)
     and (new.latitude, new.longitude) is not distinct from (old.latitude, old.longitude) then
    new.latitude := null;
    new.longitude := null;
    new.coordenadas_origem := null;
    new.coordenadas_em := null;
  end if;
  return new;
end;
$$;

drop trigger if exists lojas_limpar_coordenadas on public.lojas;
create trigger lojas_limpar_coordenadas
  before update on public.lojas
  for each row execute function public._lojas_limpar_coordenadas();

-- ---------------------------------------------------------------------------
-- Gravar a coordenada (dono do PDV ou Admin)
-- ---------------------------------------------------------------------------
create or replace function public.definir_coordenadas_loja(
  p_loja_id  uuid,
  p_lat      double precision,
  p_lng      double precision,
  p_origem   text default 'endereco'
)
returns void
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_pdv uuid;
begin
  select pdv_id into v_pdv from public.lojas where id = p_loja_id;
  if not found then
    raise exception 'loja_nao_encontrada';
  end if;
  if not (public.is_admin() or (v_pdv is not null and public.eh_dono_pdv(v_pdv))) then
    raise exception 'sem_permissao';
  end if;
  -- Só aceita ponto dentro do Brasil (evita 0,0 e coordenadas trocadas).
  if p_lat is null or p_lng is null or p_lat not between -34 and 6 or p_lng not between -74 and -28 then
    raise exception 'coordenada_invalida';
  end if;
  update public.lojas
     set latitude = p_lat,
         longitude = p_lng,
         coordenadas_origem = case when p_origem = 'aparelho' then 'aparelho' else 'endereco' end,
         coordenadas_em = now()
   where id = p_loja_id;
end;
$$;
revoke execute on function public.definir_coordenadas_loja(uuid, double precision, double precision, text) from public, anon;
grant execute on function public.definir_coordenadas_loja(uuid, double precision, double precision, text) to authenticated;

-- ---------------------------------------------------------------------------
-- Lojas sem coordenada (para o Admin localizar de uma vez)
-- ---------------------------------------------------------------------------
create or replace function public.admin_lojas_sem_coordenadas()
returns table (id uuid, nome text, endereco text)
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
    select l.id, coalesce(l.nome, p.nome_fantasia),
           concat_ws(', ', l.endereco, l.bairro, l.cidade || ' - ' || l.uf)
    from public.lojas l
    left join public.pdvs p on p.id = l.pdv_id
    where l.ativa and (l.latitude is null or l.longitude is null)
    order by l.created_at
    limit 500;
end;
$$;
revoke execute on function public.admin_lojas_sem_coordenadas() from public, anon;
grant execute on function public.admin_lojas_sem_coordenadas() to authenticated;

-- ---------------------------------------------------------------------------
-- O painel do dono passa a saber se a loja já está no mapa
-- ---------------------------------------------------------------------------
drop function if exists public.minhas_lojas(uuid);
create function public.minhas_lojas(p_pdv_id uuid)
returns table (
  id                  uuid,
  nome                text,
  endereco            text,   -- completo, para exibir
  logradouro          text,
  bairro              text,
  cidade              text,
  uf                  text,
  telefone            text,
  whatsapp            text,
  latitude            double precision,
  longitude           double precision,
  coordenadas_origem  text
)
language plpgsql
stable
security definer
set search_path = ''
as $$
begin
  if not public.eh_dono_pdv(p_pdv_id) then
    raise exception 'sem_permissao';
  end if;
  return query
    select l.id, l.nome, concat_ws(', ', l.endereco, l.bairro, l.cidade || ' - ' || l.uf),
           l.endereco, l.bairro, l.cidade, l.uf::text, l.telefone, l.whatsapp,
           l.latitude, l.longitude, l.coordenadas_origem
    from public.lojas l
    where l.pdv_id = p_pdv_id and l.ativa
    order by l.created_at;
end;
$$;
revoke execute on function public.minhas_lojas(uuid) from public, anon;
grant execute on function public.minhas_lojas(uuid) to authenticated;
