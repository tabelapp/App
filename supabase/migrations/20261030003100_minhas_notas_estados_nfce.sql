-- =============================================================================
-- Versão 1.0.2: "Minhas notas" e estados da NFC-e por configuração (30/10/2026)
--
--  * minhas_notas(): as notas fiscais que a PRÓPRIA pessoa enviou (data da nota,
--    estabelecimento, itens, preços, validade e se ainda estão na busca). Só
--    leitura, só do usuário logado (filtra por enviado_por = auth.uid()). Devolve
--    apenas o que já é público no preço: nunca CPF/CNPJ do comprador (o banco
--    nunca guardou) nem a chave de acesso.
--  * Estados aceitos para NFC-e: configuracao_app.nfce_ufs_aceitas (lista de
--    códigos de UF). Começa só com o RJ (33): SP (35) e MG (31) entram quando o
--    leitor do app estiver pronto com o HTML real de cada Sefaz.
--
-- Não cria coluna nem grava dado novo. Compatível com o app 1.0.1. Idempotente.
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 1. Estados aceitos
-- -----------------------------------------------------------------------------
insert into public.configuracao_app (chave, valor, descricao) values
  ('nfce_ufs_aceitas', '["33"]', 'Códigos de UF cuja nota fiscal o app aceita (33 = RJ). Só incluir com o leitor do estado pronto no app.')
on conflict (chave) do nothing;

create or replace function public.nfce_uf_aceita(p_codigo text)
returns boolean
language sql
stable
security definer
set search_path = ''
as $$
  select coalesce(
    (select v.valor ? p_codigo from public.configuracao_app v where v.chave = 'nfce_ufs_aceitas'),
    p_codigo = '33'
  )
$$;
revoke execute on function public.nfce_uf_aceita(text) from public, anon;
grant execute on function public.nfce_uf_aceita(text) to authenticated;

create or replace function public.enviar_nota_fiscal(
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
  -- A chave é obrigatória e precisa ser de verdade: 44 números com dígito verificador
  -- certo, modelo NFC-e (65) ou NF-e (55), de um estado aceito (nfce_ufs_aceitas).
  if v_chave is null then
    raise exception 'chave_obrigatoria';
  end if;
  if not public._chave_nfe_valida(v_chave) or substring(v_chave from 21 for 2) not in ('65', '55') then
    raise exception 'chave_acesso_invalida';
  end if;
  -- Estados aceitos ficam em configuracao_app.nfce_ufs_aceitas (hoje só RJ = 33).
  -- O código do erro continua o mesmo (o app 1.0.1 já conhece a mensagem).
  if not public.nfce_uf_aceita(substring(v_chave from 1 for 2)) then
    raise exception 'nf_fora_do_rj';
  end if;
  -- Limite de notas por pessoa por dia (evita enxurrada de preços por um app adulterado).
  if (select count(distinct lote_id) from public.cotacoes
      where enviado_por = auth.uid() and fonte = 'usuario_nf' and created_at > now() - interval '1 day')
     >= public.limite_notas_por_dia() then
    raise exception 'limite_notas';
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
     fonte, chave_acesso_nf, enviado_por, lote_id, parte_de_combo)
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
         v_lote,
         -- Marcado por quem enviou: item vendido dentro de um combo (valor pode ser rateado).
         coalesce((e ->> 'parte_de_combo')::boolean, false)
  from jsonb_array_elements(p_itens) e;
  get diagnostics v_n = row_count;

  return jsonb_build_object('lote_id', v_lote, 'itens', v_n);
end;
$$;

-- -----------------------------------------------------------------------------
-- 2. Minhas notas (só consulta)
-- -----------------------------------------------------------------------------
create or replace function public.minhas_notas(p_limite integer default 50)
returns table (
  nota_id         uuid,     -- o envio (lote); nada a ver com a chave da nota
  data_nf         date,
  enviada_em      timestamptz,
  pdv_nome        text,
  endereco        text,
  produto         text,
  preco_centavos  integer,
  unidade         public.unidade_medida,
  parte_de_combo  boolean,
  validade        date,
  na_busca        boolean   -- ainda aparece na busca (dentro dos 7 dias da nota)
)
language sql
stable
security definer
set search_path = ''
as $$
  with minhas as (
    select c.*
    from public.cotacoes c
    where c.enviado_por = auth.uid()
      and c.fonte = 'usuario_nf'
      and auth.uid() is not null
  ),
  notas as (
    select m.lote_id, min(m.created_at) as enviada_em
    from minhas m
    group by m.lote_id
    order by min(m.created_at) desc
    limit least(greatest(coalesce(p_limite, 50), 1), 200)
  )
  select m.lote_id,
         m.data_nf,
         n.enviada_em,
         coalesce(p.nome_fantasia, np.nome, m.pdv_nome_livre),
         case when l.id is not null
              then concat_ws(', ', l.endereco, l.bairro, l.cidade || ' - ' || l.uf)
              else nullif(btrim(m.pdv_endereco_livre), '')
         end,
         m.produto,
         m.preco_centavos,
         m.unidade,
         m.parte_de_combo,
         m.validade,
         m.validade >= public.hoje()
  from minhas m
  join notas n on n.lote_id = m.lote_id
  left join public.lojas l on l.id = m.loja_id
  left join public.pdvs p on p.id = l.pdv_id
  left join public.nomes_pdv np on m.loja_id is null and np.cnpj = substring(m.chave_acesso_nf from 7 for 14)
  order by n.enviada_em desc, m.produto
$$;
revoke execute on function public.minhas_notas(integer) from public, anon;
grant execute on function public.minhas_notas(integer) to authenticated;
