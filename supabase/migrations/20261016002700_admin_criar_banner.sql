-- =============================================================================
-- Banner criado pelo Admin
--
-- Decisão do fundador (16/10/2026, Google Play): o app não vende nada. Banners
-- são combinados fora do app (site, WhatsApp, visita) e o Admin cadastra o
-- banner já no ar, com as visualizações combinadas. O dono do PDV/anunciante
-- depois ajusta texto, link, imagem e palavras-chave em "Meu negócio"
-- (editar_promocao), como já fazia.
--
-- Não cria registro em `pagamentos`: o valor e a cobrança ficam fora do app.
-- Idempotente: pode ser executada de novo sem erro e sem apagar dados.
-- =============================================================================

create or replace function public.admin_criar_banner(
  p_pdv_id          uuid,
  p_titulo          text,
  p_descricao       text,
  p_link            text,
  p_palavras_chave  text[],
  p_visualizacoes   integer
)
returns uuid
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_promo uuid;
begin
  if not public.is_admin() then
    raise exception 'sem_permissao';
  end if;
  if not exists (select 1 from public.pdvs where id = p_pdv_id) then
    raise exception 'pdv_nao_encontrado';
  end if;
  if length(btrim(coalesce(p_titulo, ''))) = 0 then
    raise exception 'titulo_obrigatorio';
  end if;
  if p_visualizacoes is null or p_visualizacoes < 1 or p_visualizacoes > 100000 then
    raise exception 'pacote_invalido';
  end if;

  insert into public.promocoes (pdv_id, loja_id, titulo, descricao, link, origem_arte, palavras_chave,
                                visualizacoes_contratadas, status)
  values (p_pdv_id,
          (select l.id from public.lojas l where l.pdv_id = p_pdv_id and l.ativa order by l.created_at limit 1),
          left(btrim(p_titulo), 80), nullif(left(btrim(p_descricao), 500), ''),
          nullif(btrim(p_link), ''), 'propria',
          coalesce((select array_agg(distinct left(btrim(k), 40)) from unnest(p_palavras_chave) k
                    where length(btrim(k)) > 0), '{}'),
          p_visualizacoes, 'ativa')
  returning id into v_promo;
  return v_promo;
end;
$$;

revoke execute on function public.admin_criar_banner(uuid, text, text, text, text[], integer) from public, anon;
grant execute on function public.admin_criar_banner(uuid, text, text, text, text[], integer) to authenticated;
