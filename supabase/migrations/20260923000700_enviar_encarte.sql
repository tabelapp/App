-- =============================================================================
-- Envio de encarte pelo usuário comum (briefing, seções 4 e 6)
--
-- O usuário fotografa o encarte (até 5 fotos, ex.: frente e verso), diz de qual
-- estabelecimento é e, se o encarte mostrar, até quando valem as ofertas.
-- Vai para a fila do Admin (encartes_pendentes); não vira preço até ser aprovado.
-- =============================================================================

alter table public.encartes_pendentes
  add column fotos text[] not null default '{}';

update public.encartes_pendentes set fotos = array[foto_path] where cardinality(fotos) = 0;

-- Quem grava só foto_path (versão anterior) continua funcionando: vira a única foto.
create function public._encarte_fotos_padrao()
returns trigger
language plpgsql
set search_path = ''
as $$
begin
  if cardinality(new.fotos) = 0 then
    new.fotos := array[new.foto_path];
  end if;
  return new;
end;
$$;

create trigger encartes_fotos_padrao
  before insert on public.encartes_pendentes
  for each row execute function public._encarte_fotos_padrao();

alter table public.encartes_pendentes
  add constraint encartes_fotos_qtd check (cardinality(fotos) between 1 and 5);

create function public.limite_encartes_por_dia() returns integer
language sql immutable as $$ select 10 $$;

create function public.enviar_encarte(
  p_fotos         text[],
  p_loja_id       uuid,
  p_pdv_nome      text,
  p_pdv_endereco  text,
  p_validade      date default null,   -- "válido até", se o encarte mostrar
  p_comentario    text default null
)
returns uuid
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_id uuid;
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
  -- As fotos precisam estar na pasta do próprio usuário no bucket "encartes".
  if exists (select 1 from unnest(p_fotos) f where f not like auth.uid()::text || '/%') then
    raise exception 'foto_invalida';
  end if;
  if p_loja_id is null and length(btrim(coalesce(p_pdv_nome, ''))) = 0 then
    raise exception 'pdv_obrigatorio';
  end if;
  if p_loja_id is not null and not exists (select 1 from public.lojas where id = p_loja_id and ativa) then
    raise exception 'loja_invalida';
  end if;
  if p_validade is not null and p_validade < public.hoje() then
    raise exception 'validade_passada';
  end if;
  if (select count(*) from public.encartes_pendentes
      where enviado_por = auth.uid() and created_at > now() - interval '1 day')
     >= public.limite_encartes_por_dia() then
    raise exception 'limite_encartes';
  end if;

  insert into public.encartes_pendentes
    (enviado_por, foto_path, fotos, loja_id, pdv_nome, pdv_endereco, validade, comentario)
  values
    (auth.uid(), p_fotos[1], p_fotos, p_loja_id,
     case when p_loja_id is null then btrim(p_pdv_nome) end,
     case when p_loja_id is null then nullif(btrim(p_pdv_endereco), '') end,
     p_validade, nullif(btrim(p_comentario), ''))
  returning id into v_id;

  return v_id;
end;
$$;

-- Estabelecimentos cadastrados, para o usuário escolher de qual é o encarte.
create function public.buscar_lojas(p_termo text, p_limite integer default 10)
returns table (
  loja_id    uuid,
  pdv_nome   text,
  loja_nome  text,
  endereco   text
)
language sql
stable
security invoker
set search_path = ''
as $$
  select l.id, p.nome_fantasia, l.nome,
         concat_ws(', ', l.endereco, l.bairro, l.cidade || ' - ' || l.uf)
  from public.lojas l
  join public.pdvs p on p.id = l.pdv_id
  where l.ativa
    and length(public.normalizar(p_termo)) >= 2
    and strpos(public.normalizar(p.nome_fantasia || ' ' || coalesce(l.nome, '') || ' ' || coalesce(l.bairro, '')),
               public.normalizar(p_termo)) > 0
  order by p.nome_fantasia, l.nome nulls first
  limit least(greatest(coalesce(p_limite, 10), 1), 30)
$$;

-- "Meus encartes": o que o usuário enviou e em que pé está.
create function public.meus_encartes(p_limite integer default 20)
returns table (
  id               uuid,
  pdv_nome         text,
  status           public.status_encarte,
  validade         date,
  motivo_rejeicao  text,
  created_at       timestamptz
)
language sql
stable
security invoker
set search_path = ''
as $$
  select e.id,
         coalesce(concat_ws(' — ', p.nome_fantasia, l.nome), e.pdv_nome),
         e.status, e.validade, e.motivo_rejeicao, e.created_at
  from public.encartes_pendentes e
  left join public.lojas l on l.id = e.loja_id
  left join public.pdvs p on p.id = l.pdv_id
  where e.enviado_por = auth.uid()
  order by e.created_at desc
  limit least(greatest(coalesce(p_limite, 20), 1), 100)
$$;

revoke execute on function public.meus_encartes(integer) from public, anon;
grant execute on function public.meus_encartes(integer) to authenticated, service_role;

revoke execute on function public.enviar_encarte(text[], uuid, text, text, date, text) from public, anon;
revoke execute on function public.buscar_lojas(text, integer) from public, anon;
revoke execute on function public.limite_encartes_por_dia() from public, anon;
grant execute on function public.enviar_encarte(text[], uuid, text, text, date, text) to authenticated, service_role;
grant execute on function public.buscar_lojas(text, integer) to authenticated, service_role;
grant execute on function public.limite_encartes_por_dia() to authenticated, service_role;

-- Fotos de encarte: até 5 MB, só imagem.
do $$
begin
  if exists (select 1 from information_schema.schemata where schema_name = 'storage') then
    update storage.buckets
       set file_size_limit = 5242880,
           allowed_mime_types = array['image/jpeg', 'image/png', 'image/webp']
     where id = 'encartes';
  end if;
end;
$$;
