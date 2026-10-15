-- =============================================================================
-- Promoções: editar e excluir (também as já pagas)
--
-- Antes, "excluir" uma promoção paga só a pausava: ela continuava na lista do
-- dono e não havia como tirá-la de lá, nem como corrigir o texto ou a arte.
--
-- Agora:
--   * editar_promocao: o dono muda título, texto, link, arte e palavras-chave a
--     qualquer momento (as visualizações compradas não mudam).
--   * encerrar_promocao: sem pagamento confirmado, apaga tudo (como antes); com
--     pagamento, o banner sai do ar e some da lista do dono, mas a linha e o
--     pagamento ficam guardados (histórico financeiro). Visualizações que
--     faltavam não são devolvidas (o app avisa antes).
--
-- Idempotente: pode ser executada de novo sem erro e sem apagar dados.
-- =============================================================================

alter table public.promocoes add column if not exists excluida_em timestamptz;

create or replace function public.encerrar_promocao(p_promocao_id uuid)
returns text
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_pdv uuid;
begin
  select pdv_id into v_pdv from public.promocoes where id = p_promocao_id and excluida_em is null;
  if v_pdv is null or not public.eh_dono_pdv(v_pdv) then
    raise exception 'sem_permissao';
  end if;
  if exists (select 1 from public.pagamentos where promocao_id = p_promocao_id and status = 'pago') then
    update public.pagamentos set status = 'cancelado' where promocao_id = p_promocao_id and status = 'pendente';
    update public.promocoes set status = 'pausada', excluida_em = now() where id = p_promocao_id;
    return 'encerrada';
  end if;
  delete from public.pagamentos where promocao_id = p_promocao_id;
  delete from public.promocoes where id = p_promocao_id;
  return 'excluida';
end;
$$;

create or replace function public.editar_promocao(
  p_promocao_id     uuid,
  p_titulo          text,
  p_descricao       text,
  p_link            text,
  p_palavras_chave  text[],
  p_trocar_arte     boolean default false,
  p_arte_path       text default null
)
returns void
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_pdv uuid;
begin
  select pdv_id into v_pdv from public.promocoes where id = p_promocao_id and excluida_em is null;
  if v_pdv is null or not public.eh_dono_pdv(v_pdv) then
    raise exception 'sem_permissao';
  end if;
  if length(btrim(coalesce(p_titulo, ''))) = 0 then
    raise exception 'titulo_obrigatorio';
  end if;
  if coalesce(p_trocar_arte, false) and p_arte_path is not null and p_arte_path not like v_pdv::text || '/%' then
    raise exception 'arte_invalida';
  end if;
  update public.promocoes
     set titulo = left(btrim(p_titulo), 80),
         descricao = nullif(left(btrim(p_descricao), 500), ''),
         link = nullif(btrim(p_link), ''),
         palavras_chave = coalesce((select array_agg(distinct left(btrim(k), 40)) from unnest(p_palavras_chave) k
                                    where length(btrim(k)) > 0), '{}'),
         arte_path = case when coalesce(p_trocar_arte, false) then p_arte_path else arte_path end
   where id = p_promocao_id;
end;
$$;
revoke execute on function public.editar_promocao(uuid, text, text, text, text[], boolean, text) from public, anon;
grant execute on function public.editar_promocao(uuid, text, text, text, text[], boolean, text) to authenticated;

-- A lista do dono não mostra as excluídas (elas continuam no banco, com o pagamento).
create or replace function public.minhas_promocoes(p_pdv_id uuid)
returns table (
  id                         uuid,
  titulo                     text,
  descricao                  text,
  link                       text,
  arte_path                  text,
  palavras_chave             text[],
  status                     public.status_promocao,
  visualizacoes_contratadas  integer,
  visualizacoes_exibidas     integer,
  pagamento_pendente_id      uuid,
  valor_pendente_centavos    integer,
  visualizacoes_pendentes    integer,
  created_at                 timestamptz
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
    select pr.id, pr.titulo, pr.descricao, pr.link, pr.arte_path, pr.palavras_chave, pr.status,
           pr.visualizacoes_contratadas, pr.visualizacoes_exibidas,
           pg.id, pg.valor_centavos, pg.quantidade, pr.created_at
    from public.promocoes pr
    left join lateral (
      select g.id, g.valor_centavos, g.quantidade from public.pagamentos g
      where g.promocao_id = pr.id and g.status = 'pendente'
      order by g.created_at desc limit 1
    ) pg on true
    where pr.pdv_id = p_pdv_id and pr.excluida_em is null
    order by pr.created_at desc;
end;
$$;
