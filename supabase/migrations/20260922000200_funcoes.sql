-- =============================================================================
-- Tabelapp — regras de negócio no banco (funções RPC chamadas pelo app)
--
-- Erros de negócio são levantados com mensagens estáveis, em snake_case
-- (ex.: 'cota_excedida'), que o app traduz para texto amigável.
-- =============================================================================

-- Constantes do produto -------------------------------------------------------
create function public.cota_gratis_mensal() returns integer
language sql immutable as $$ select 50 $$;

create function public.operacoes_por_pacote() returns integer
language sql immutable as $$ select 50 $$;

create function public.validade_maxima_dias() returns integer
language sql immutable as $$ select 30 $$;

-- Preço enviado por usuário comum (NF/encarte) sem validade explícita
-- continua aparecendo na busca por este número de dias.
create function public.dias_vigencia_preco_usuario() returns integer
language sql immutable as $$ select 30 $$;

-- Helpers de permissão ---------------------------------------------------------
create function public.is_admin()
returns boolean
language sql
stable
security definer
set search_path = ''
as $$
  select exists (
    select 1 from public.usuarios where id = auth.uid() and tipo = 'admin'
  )
$$;

create function public.eh_dono_pdv(p_pdv_id uuid)
returns boolean
language sql
stable
security definer
set search_path = ''
as $$
  select exists (
    select 1 from public.pdvs where id = p_pdv_id and dono_id = auth.uid()
  )
$$;

-- =============================================================================
-- Cota de operações do PDV
-- =============================================================================
create function public.cota_status(p_pdv_id uuid)
returns table (
  competencia       date,
  gratis            integer,
  compradas         integer,
  usadas            integer,
  restantes         integer
)
language plpgsql
stable
security definer
set search_path = ''
as $$
begin
  if not (public.eh_dono_pdv(p_pdv_id) or public.is_admin()) then
    raise exception 'sem_permissao';
  end if;

  return query
  with
    c_comp as (select public.competencia_atual() as c),
    c_compradas as (
      select coalesce(sum(p.quantidade), 0)::integer as n
      from public.pagamentos p, c_comp
      where p.pdv_id = p_pdv_id and p.tipo = 'pacote_operacoes'
        and p.status = 'pago' and p.competencia = c_comp.c
    ),
    c_usadas as (
      select count(*)::integer as n
      from public.operacoes_log o, c_comp
      where o.pdv_id = p_pdv_id and o.conta_na_cota and o.competencia = c_comp.c
    )
  select c_comp.c,
         public.cota_gratis_mensal(),
         c_compradas.n,
         c_usadas.n,
         greatest(public.cota_gratis_mensal() + c_compradas.n - c_usadas.n, 0)
  from c_comp, c_compradas, c_usadas;
end;
$$;

-- =============================================================================
-- PDV: salvar/importar preços
--
-- Mesma rotina para edição manual (1 item) e importação de planilha (N itens).
-- O produto (normalizado) é a chave dentro da loja: se já existe, atualiza.
--
-- Contagem de operações (regra do briefing):
--   * produto novo            -> criar_item      (conta 1)
--   * preço maior que o atual -> aumentar_preco  (conta 1)
--   * preço menor             -> diminuir_preco  (grátis)
--   * mesmo preço (só OBS/validade mudou) -> editar_dados (grátis)
--   * excluir                 -> excluir_item    (grátis, ver pdv_excluir_item)
-- Modo rede: o item é replicado para todas as lojas ativas e conta UMA vez.
-- Modo varejo: aplica só na loja informada; cada loja consome a cota do PDV.
--
-- p_simular = true não grava nada: devolve o resumo e se cabe na cota — usado
-- pelo app para mostrar a tela de pagamento ANTES de concluir a importação.
-- =============================================================================
create function public.pdv_salvar_precos(
  p_pdv_id   uuid,
  p_loja_id  uuid,
  p_itens    jsonb,
  p_fonte    public.fonte_cotacao default 'pdv_manual',
  p_simular  boolean default false
)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_pdv          public.pdvs%rowtype;
  v_lojas        uuid[];
  v_hoje         date := public.hoje();
  v_restantes    integer;
  v_resumo       record;
  v_faltam       integer;
  v_lote         uuid := gen_random_uuid();
  v_invalidos    jsonb;
begin
  if auth.uid() is null then
    raise exception 'nao_autenticado';
  end if;
  if p_fonte not in ('pdv_manual', 'pdv_excel') then
    raise exception 'fonte_invalida';
  end if;
  if p_itens is null or jsonb_typeof(p_itens) <> 'array' or jsonb_array_length(p_itens) = 0 then
    raise exception 'itens_vazios';
  end if;
  if jsonb_array_length(p_itens) > 2000 then
    raise exception 'itens_demais';
  end if;

  -- Trava o PDV: evita duas importações simultâneas furarem a cota.
  select * into v_pdv from public.pdvs where id = p_pdv_id for update;
  if not found or v_pdv.dono_id <> auth.uid() then
    raise exception 'sem_permissao';
  end if;

  if v_pdv.modo_rede then
    select array_agg(id order by created_at) into v_lojas
    from public.lojas where pdv_id = p_pdv_id and ativa;
  else
    select array_agg(id) into v_lojas
    from public.lojas where id = p_loja_id and pdv_id = p_pdv_id and ativa;
  end if;
  if v_lojas is null then
    raise exception 'loja_invalida';
  end if;

  -- Itens de entrada, já normalizados.
  -- Tabelas temporárias de trabalho (somem no fim da transação).
  if to_regclass('pg_temp._itens') is null then
    create temp table _itens (
      ord bigint, produto text, norm text, preco integer, validade date, obs text
    ) on commit drop;
    create temp table _classif (norm text, tipo public.tipo_operacao) on commit drop;
  else
    truncate _itens, _classif;
  end if;

  insert into _itens
  select e.ord,
         btrim(e.item ->> 'produto'),
         public.normalizar(e.item ->> 'produto'),
         (e.item ->> 'preco_centavos')::integer,
         coalesce((e.item ->> 'validade')::date, v_hoje + public.validade_maxima_dias()),
         nullif(btrim(e.item ->> 'obs'), '')
  from jsonb_array_elements(p_itens) with ordinality as e(item, ord);

  -- Validação: devolve TODAS as linhas com problema de uma vez (útil p/ planilha).
  select jsonb_agg(jsonb_build_object('linha', ord, 'erro', erro) order by ord) into v_invalidos
  from (
    select ord,
      case
        when produto is null or norm = '' then 'produto_vazio'
        when length(produto) > 200 then 'produto_longo'
        when preco is null or preco <= 0 then 'preco_invalido'
        when preco >= 100000000 then 'preco_invalido'
        when validade < v_hoje then 'validade_passada'
        when validade > v_hoje + public.validade_maxima_dias() then 'validade_maior_que_30_dias'
        when length(obs) > 140 then 'obs_longa'
        when count(*) over (partition by norm) > 1 then 'produto_duplicado'
      end as erro
    from _itens
  ) v
  where erro is not null;

  if v_invalidos is not null then
    raise exception 'itens_invalidos' using detail = v_invalidos::text;
  end if;

  -- Classifica cada item contra o preço atual (maior preço entre as lojas-alvo).
  insert into _classif
  select i.norm,
         case
           when atual.preco is null then 'criar_item'
           when i.preco > atual.preco then 'aumentar_preco'
           when i.preco < atual.preco then 'diminuir_preco'
           else 'editar_dados'
         end::public.tipo_operacao
  from _itens i
  left join lateral (
    select max(c.preco_centavos) as preco
    from public.cotacoes c
    where c.loja_id = any (v_lojas)
      and c.produto_busca = i.norm
      and c.fonte in ('pdv_manual', 'pdv_excel')
  ) atual on true;

  select count(*) filter (where tipo = 'criar_item')                      as criados,
         count(*) filter (where tipo = 'aumentar_preco')                  as aumentados,
         count(*) filter (where tipo = 'diminuir_preco')                  as diminuidos,
         count(*) filter (where tipo = 'editar_dados')                    as inalterados,
         count(*) filter (where tipo in ('criar_item', 'aumentar_preco')) as operacoes
  into v_resumo
  from _classif;

  select restantes into v_restantes from public.cota_status(p_pdv_id);
  v_faltam := greatest(v_resumo.operacoes - v_restantes, 0);

  if p_simular or v_faltam > 0 then
    if not p_simular then
      raise exception 'cota_excedida'
        using detail = jsonb_build_object(
          'operacoes', v_resumo.operacoes,
          'restantes', v_restantes,
          'pacotes_necessarios', ceil(v_faltam::numeric / public.operacoes_por_pacote())
        )::text;
    end if;
    return jsonb_build_object(
      'simulacao', true,
      'criados', v_resumo.criados,
      'aumentados', v_resumo.aumentados,
      'diminuidos', v_resumo.diminuidos,
      'inalterados', v_resumo.inalterados,
      'operacoes', v_resumo.operacoes,
      'restantes', v_restantes,
      'cabe_na_cota', v_faltam = 0,
      'pacotes_necessarios', ceil(v_faltam::numeric / public.operacoes_por_pacote())
    );
  end if;

  -- Grava: um registro por (item x loja-alvo).
  insert into public.cotacoes as c
    (loja_id, produto, preco_centavos, validade, obs, fonte, enviado_por, lote_id)
  select l.loja_id, i.produto, i.preco, i.validade, i.obs, p_fonte, auth.uid(), v_lote
  from _itens i
  cross join unnest(v_lojas) as l(loja_id)
  on conflict (loja_id, produto_busca) where fonte in ('pdv_manual', 'pdv_excel')
  do update set
    produto        = excluded.produto,
    preco_centavos = excluded.preco_centavos,
    validade       = excluded.validade,
    obs            = excluded.obs,
    fonte          = excluded.fonte,
    enviado_por    = excluded.enviado_por,
    lote_id        = excluded.lote_id;

  insert into public.operacoes_log (pdv_id, loja_id, produto, tipo)
  select p_pdv_id,
         case when v_pdv.modo_rede then null else v_lojas[1] end,
         i.produto,
         c.tipo
  from _itens i
  join _classif c using (norm);

  return jsonb_build_object(
    'simulacao', false,
    'lote_id', v_lote,
    'criados', v_resumo.criados,
    'aumentados', v_resumo.aumentados,
    'diminuidos', v_resumo.diminuidos,
    'inalterados', v_resumo.inalterados,
    'operacoes', v_resumo.operacoes,
    'restantes', v_restantes - v_resumo.operacoes
  );
end;
$$;

-- Excluir é sempre grátis. Em modo rede, remove o produto de todas as lojas.
create function public.pdv_excluir_item(p_cotacao_id uuid)
returns integer
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_cot   public.cotacoes%rowtype;
  v_pdv   public.pdvs%rowtype;
  v_n     integer;
begin
  select c.* into v_cot from public.cotacoes c where c.id = p_cotacao_id;
  if not found or v_cot.fonte not in ('pdv_manual', 'pdv_excel') then
    raise exception 'item_nao_encontrado';
  end if;

  select p.* into v_pdv
  from public.pdvs p join public.lojas l on l.pdv_id = p.id
  where l.id = v_cot.loja_id;
  if v_pdv.dono_id is distinct from auth.uid() then
    raise exception 'sem_permissao';
  end if;

  delete from public.cotacoes c
  where c.fonte in ('pdv_manual', 'pdv_excel')
    and c.produto_busca = v_cot.produto_busca
    and (
      (v_pdv.modo_rede and c.loja_id in (select id from public.lojas where pdv_id = v_pdv.id))
      or (not v_pdv.modo_rede and c.loja_id = v_cot.loja_id)
    );
  get diagnostics v_n = row_count;

  insert into public.operacoes_log (pdv_id, loja_id, produto, tipo)
  values (v_pdv.id, case when v_pdv.modo_rede then null else v_cot.loja_id end,
          v_cot.produto, 'excluir_item');

  return v_n;
end;
$$;

-- =============================================================================
-- Busca de preços (tela principal do CPF)
--
-- Sem termo: últimos preços lançados (a tela nunca fica vazia).
-- Com termo: todas as palavras precisam aparecer no nome do produto,
-- sem diferenciar acento/maiúscula ("feijao preto" acha "Feijão Preto 1kg").
-- Ordenação e destaque do mais barato são feitos no app.
-- =============================================================================
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
  created_at      timestamptz
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
         c.created_at
  from public.cotacoes c
  left join public.lojas l on l.id = c.loja_id
  left join public.pdvs p on p.id = l.pdv_id
  cross join termo
  cross join palavras
  where (l.id is null or l.ativa)
    and (
      c.validade >= public.hoje()
      or (c.validade is null
          and c.created_at >= now() - make_interval(days => public.dias_vigencia_preco_usuario()))
    )
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

-- =============================================================================
-- Usuário comum: Nota Fiscal (manual no V1)
-- Uma NF -> vários produtos, enviados de uma vez após a tela de confirmação.
-- CPF do comprador NUNCA é pedido nem armazenado.
-- =============================================================================
create function public.enviar_nota_fiscal(
  p_chave_acesso  text,
  p_loja_id       uuid,
  p_pdv_nome      text,
  p_pdv_endereco  text,
  p_itens         jsonb
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
begin
  if auth.uid() is null then
    raise exception 'nao_autenticado';
  end if;
  if v_chave is not null and length(v_chave) <> 44 then
    raise exception 'chave_acesso_invalida';
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
    (loja_id, pdv_nome_livre, pdv_endereco_livre, produto, preco_centavos,
     fonte, chave_acesso_nf, enviado_por, lote_id)
  select p_loja_id,
         case when p_loja_id is null then btrim(p_pdv_nome) end,
         case when p_loja_id is null then nullif(btrim(p_pdv_endereco), '') end,
         btrim(e ->> 'produto'),
         (e ->> 'preco_centavos')::integer,
         'usuario_nf',
         v_chave,
         auth.uid(),
         v_lote
  from jsonb_array_elements(p_itens) e;
  get diagnostics v_n = row_count;

  return jsonb_build_object('lote_id', v_lote, 'itens', v_n);
end;
$$;

-- =============================================================================
-- Admin: fila de encartes de usuários
-- =============================================================================
create function public.aprovar_encarte(
  p_encarte_id  uuid,
  p_itens       jsonb,          -- [{produto, preco_centavos, validade?}] montado pelo Admin
  p_validade    date default null
)
returns integer
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_enc  public.encartes_pendentes%rowtype;
  v_n    integer;
begin
  if not public.is_admin() then
    raise exception 'sem_permissao';
  end if;

  select * into v_enc from public.encartes_pendentes where id = p_encarte_id for update;
  if not found then
    raise exception 'encarte_nao_encontrado';
  end if;
  if v_enc.status <> 'pendente' then
    raise exception 'encarte_ja_revisado';
  end if;
  if p_itens is null or jsonb_typeof(p_itens) <> 'array' or jsonb_array_length(p_itens) = 0 then
    raise exception 'itens_vazios';
  end if;

  insert into public.cotacoes
    (loja_id, pdv_nome_livre, pdv_endereco_livre, produto, preco_centavos, validade,
     fonte, enviado_por, encarte_id, lote_id)
  select v_enc.loja_id,
         case when v_enc.loja_id is null then v_enc.pdv_nome end,
         case when v_enc.loja_id is null then v_enc.pdv_endereco end,
         btrim(e ->> 'produto'),
         (e ->> 'preco_centavos')::integer,
         coalesce((e ->> 'validade')::date, p_validade),
         'usuario_encarte',
         v_enc.enviado_por,
         v_enc.id,
         v_enc.id
  from jsonb_array_elements(p_itens) e;
  get diagnostics v_n = row_count;

  update public.encartes_pendentes
     set status = 'aprovado', revisado_por = auth.uid(), revisado_em = now()
   where id = p_encarte_id;

  return v_n;
end;
$$;

create function public.rejeitar_encarte(p_encarte_id uuid, p_motivo text default null)
returns void
language plpgsql
security definer
set search_path = ''
as $$
begin
  if not public.is_admin() then
    raise exception 'sem_permissao';
  end if;

  update public.encartes_pendentes
     set status = 'rejeitado', revisado_por = auth.uid(), revisado_em = now(),
         motivo_rejeicao = nullif(btrim(p_motivo), '')
   where id = p_encarte_id and status = 'pendente';

  if not found then
    raise exception 'encarte_nao_encontrado';
  end if;
end;
$$;

-- =============================================================================
-- Promoções: banners exibidos na busca
-- Segmentação do MVP: raio (a partir da loja) e/ou palavra-chave do termo buscado.
-- =============================================================================
create function public.promocoes_para_busca(
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
  distancia_km double precision
)
language sql
stable
security invoker
set search_path = ''
as $$
  select pr.id, pr.pdv_id, p.nome_fantasia, pr.titulo, pr.descricao, pr.arte_path,
         public.distancia_km(p_lat, p_lng, l.latitude, l.longitude)
  from public.promocoes pr
  join public.pdvs p on p.id = pr.pdv_id
  left join public.lojas l on l.id = pr.loja_id
  where pr.status = 'ativa'
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

create function public.registrar_visualizacao_promocao(p_promocao_id uuid)
returns void
language sql
security definer
set search_path = ''
as $$
  update public.promocoes
     set visualizacoes_exibidas = visualizacoes_exibidas + 1,
         status = case when visualizacoes_exibidas + 1 >= visualizacoes_contratadas
                       then 'esgotada'::public.status_promocao else status end
   where id = p_promocao_id
     and status = 'ativa'
     and visualizacoes_exibidas < visualizacoes_contratadas
$$;

-- =============================================================================
-- Pagamentos: confirmação chamada pelo webhook do Mercado Pago (Edge Function,
-- com service_role). Nunca exposta ao app.
-- =============================================================================
create function public.confirmar_pagamento(p_pagamento_id uuid, p_provedor_pagamento_id text)
returns void
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_pag public.pagamentos%rowtype;
begin
  update public.pagamentos
     set status = 'pago', pago_em = now(),
         provedor_pagamento_id = coalesce(p_provedor_pagamento_id, provedor_pagamento_id)
   where id = p_pagamento_id and status = 'pendente'
  returning * into v_pag;

  if not found then
    return;  -- idempotente: webhook pode chegar mais de uma vez
  end if;

  if v_pag.tipo = 'pacote_visualizacoes' then
    update public.promocoes
       set visualizacoes_contratadas = visualizacoes_contratadas + v_pag.quantidade,
           status = case when status in ('aguardando_pagamento', 'esgotada')
                         then 'ativa'::public.status_promocao else status end
     where id = v_pag.promocao_id;
  end if;
  -- pacote_operacoes: nada a fazer, cota_status() já soma pagamentos pagos do mês.
end;
$$;
