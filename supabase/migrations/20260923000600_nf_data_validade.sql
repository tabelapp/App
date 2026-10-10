-- =============================================================================
-- NF: data da compra e validade de 7 dias (decisão do fundador)
--
-- O preço de uma NF é o preço praticado NAQUELE dia (vale até a meia-noite
-- da data da nota). Na busca aparece "Preço praticado dia dd/mm/aaaa" e o
-- preço continua visível por 7 dias a partir da data da nota.
-- =============================================================================

alter table public.cotacoes add column data_nf date;

update public.cotacoes set data_nf = created_at::date
 where fonte = 'usuario_nf' and data_nf is null;

alter table public.cotacoes
  add constraint cotacoes_data_nf_so_em_nf check (
    (fonte = 'usuario_nf' and data_nf is not null) or (fonte <> 'usuario_nf' and data_nf is null)
  );

create or replace function public.validade_nf_dias() returns integer
language sql immutable as $$ select 7 $$;

-- Retorno ganha a coluna data_nf: precisa recriar.
drop function public.buscar_cotacoes(text, double precision, double precision, integer);

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
         coalesce(p.nome_fantasia, c.pdv_nome_livre),
         l.nome,
         coalesce(
           concat_ws(', ', l.endereco, l.bairro, l.cidade || ' - ' || l.uf),
           c.pdv_endereco_livre
         ),
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


grant execute on function public.buscar_cotacoes(text, double precision, double precision, integer)
  to authenticated;

-- Novo parâmetro p_data_nf: precisa recriar.
drop function public.enviar_nota_fiscal(text, uuid, text, text, jsonb);

-- Uma NF -> vários produtos, enviados de uma vez após a tela de confirmação.
-- CPF do comprador NUNCA é pedido nem armazenado.
create function public.enviar_nota_fiscal(
  p_chave_acesso  text,
  p_loja_id       uuid,
  p_pdv_nome      text,
  p_pdv_endereco  text,
  p_itens         jsonb,
  p_data_nf       date default null   -- data da compra (emissão da NF); padrão: hoje
)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_chave  text := nullif(regexp_replace(coalesce(p_chave_acesso, ''), '\D', '', 'g'), '');
  v_lote   uuid := gen_random_uuid();
  v_n      integer;
  v_data   date := coalesce(p_data_nf, public.hoje());
begin
  if auth.uid() is null then
    raise exception 'nao_autenticado';
  end if;
  if v_chave is not null and length(v_chave) <> 44 then
    raise exception 'chave_acesso_invalida';
  end if;
  if v_data > public.hoje() then
    raise exception 'data_nf_futura';
  end if;
  -- Nota mais velha que a validade já nasceria fora da busca.
  if v_data < public.hoje() - public.validade_nf_dias() then
    raise exception 'nf_antiga';
  end if;
  -- A chave traz ano/mês de emissão (AAMM, posições 3 a 6).
  if v_chave is not null and substring(v_chave from 3 for 4) <> to_char(v_data, 'YYMM') then
    raise exception 'data_nao_confere';
  end if;
  if v_chave is not null and exists (select 1 from public.cotacoes where chave_acesso_nf = v_chave) then
    raise exception 'nf_ja_enviada';
  end if;
  if p_loja_id is null and length(btrim(coalesce(p_pdv_nome, ''))) = 0 then
    raise exception 'pdv_obrigatorio';
  end if;
  if p_loja_id is not null and not exists (select 1 from public.lojas where id = p_loja_id and ativa) then
    raise exception 'loja_invalida';
  end if;
  if p_itens is null or jsonb_typeof(p_itens) <> 'array' or jsonb_array_length(p_itens) = 0 then
    raise exception 'itens_vazios';
  end if;
  if jsonb_array_length(p_itens) > 500 then
    raise exception 'itens_demais';
  end if;
  if exists (
    select 1 from jsonb_array_elements(p_itens) e
    where length(btrim(coalesce(e ->> 'produto', ''))) = 0
       or coalesce((e ->> 'preco_centavos')::integer, 0) <= 0
  ) then
    raise exception 'itens_invalidos';
  end if;

  insert into public.cotacoes
    (loja_id, pdv_nome_livre, pdv_endereco_livre, produto, preco_centavos, validade, data_nf,
     fonte, chave_acesso_nf, enviado_por, lote_id)
  select p_loja_id,
         case when p_loja_id is null then btrim(p_pdv_nome) end,
         case when p_loja_id is null then nullif(btrim(p_pdv_endereco), '') end,
         btrim(e ->> 'produto'),
         (e ->> 'preco_centavos')::integer,
         v_data + public.validade_nf_dias(),
         v_data,
         'usuario_nf',
         v_chave,
         auth.uid(),
         v_lote
  from jsonb_array_elements(p_itens) e;
  get diagnostics v_n = row_count;

  return jsonb_build_object('lote_id', v_lote, 'itens', v_n);
end;
$$;


revoke execute on function public.enviar_nota_fiscal(text, uuid, text, text, jsonb, date) from public, anon;
grant execute on function public.enviar_nota_fiscal(text, uuid, text, text, jsonb, date) to authenticated;
