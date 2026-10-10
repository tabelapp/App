-- =============================================================================
-- Encarte publicado direto pelo usuário (decisão do fundador — sem fila do Admin)
--
-- O app lê as fotos do encarte no próprio celular (OCR), mostra ao usuário os
-- produtos e preços encontrados e, com a confirmação dele, os preços vão direto
-- para a busca (fonte usuario_encarte), valendo até a data impressa no encarte.
-- As fotos continuam guardadas no bucket privado "encartes" para auditoria, e o
-- encarte fica registrado em encartes_pendentes já com status 'aprovado'.
-- =============================================================================

create function public.publicar_encarte(
  p_fotos         text[],
  p_loja_id       uuid,
  p_pdv_nome      text,
  p_pdv_endereco  text,
  p_validade      date,    -- "válido até" impresso no encarte (obrigatório)
  p_itens         jsonb    -- [{produto, preco_centavos}] lidos das fotos e confirmados pelo usuário
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
  if exists (
    select 1 from jsonb_array_elements(p_itens) e
    where length(btrim(coalesce(e ->> 'produto', ''))) not between 2 and 200
       or coalesce((e ->> 'preco_centavos')::integer, 0) <= 0
  ) then
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
     fonte, enviado_por, encarte_id, lote_id)
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
         v_id
  from jsonb_array_elements(p_itens) with ordinality as t(e, ordem)
  order by public.normalizar(btrim(e ->> 'produto')), ordem;
  get diagnostics v_n = row_count;

  return v_n;
end;
$$;

revoke execute on function public.publicar_encarte(text[], uuid, text, text, date, jsonb) from public, anon;
grant execute on function public.publicar_encarte(text[], uuid, text, text, date, jsonb) to authenticated, service_role;

-- A fila do Admin para encartes de usuário deixa de existir no app.
drop function public.enviar_encarte(text[], uuid, text, text, date, text);

-- "Meus encartes" agora diz quantos preços cada encarte publicou.
drop function public.meus_encartes(integer);

create function public.meus_encartes(p_limite integer default 20)
returns table (
  id               uuid,
  pdv_nome         text,
  status           public.status_encarte,
  validade         date,
  motivo_rejeicao  text,
  created_at       timestamptz,
  itens            integer
)
language sql
stable
security invoker
set search_path = ''
as $$
  select e.id,
         coalesce(concat_ws(' — ', p.nome_fantasia, l.nome), e.pdv_nome),
         e.status, e.validade, e.motivo_rejeicao, e.created_at,
         (select count(*)::integer from public.cotacoes c where c.encarte_id = e.id)
  from public.encartes_pendentes e
  left join public.lojas l on l.id = e.loja_id
  left join public.pdvs p on p.id = l.pdv_id
  where e.enviado_por = auth.uid()
  order by e.created_at desc
  limit least(greatest(coalesce(p_limite, 20), 1), 100)
$$;

revoke execute on function public.meus_encartes(integer) from public, anon;
grant execute on function public.meus_encartes(integer) to authenticated, service_role;
