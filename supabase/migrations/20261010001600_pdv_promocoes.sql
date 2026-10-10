-- =============================================================================
-- Área do PDV: edição do cadastro, WhatsApp da loja, promoções pagas e pagamentos
-- confirmados pelo Admin (até a integração automática do Pix).
-- =============================================================================

-- WhatsApp da loja: o contato do card de preço abre direto o WhatsApp.
alter table public.lojas add column whatsapp text check (whatsapp is null or length(whatsapp) <= 20);

-- Banner com link (site, Instagram, cardápio...).
alter table public.promocoes add column link text
  check (link is null or (link ~* '^https?://' and length(link) <= 300));
grant update (link) on public.promocoes to authenticated;

create or replace function public.buscar_cotacoes(
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
         coalesce(p.nome_fantasia, n.nome, c.pdv_nome_livre),
         l.nome,
         -- Loja cadastrada: endereço do cadastro. Senão: o endereço lido da nota.
         -- (concat_ws com tudo nulo devolve '' e escondia o endereço da nota.)
         case when l.id is not null
              then concat_ws(', ', l.endereco, l.bairro, l.cidade || ' - ' || l.uf)
              else nullif(btrim(c.pdv_endereco_livre), '')
         end,
         -- Contato do card: o WhatsApp da loja, se informado; senão o telefone.
         coalesce(nullif(btrim(l.whatsapp), ''), l.telefone),
         p.site,
         l.latitude,
         l.longitude,
         public.distancia_km(p_lat, p_lng, l.latitude, l.longitude),
         c.created_at,
         c.data_nf
  from public.cotacoes c
  left join public.lojas l on l.id = c.loja_id
  left join public.pdvs p on p.id = l.pdv_id
  left join public.nomes_pdv n on c.loja_id is null and n.cnpj = substring(c.chave_acesso_nf from 7 for 14)
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

-- -----------------------------------------------------------------------------
-- Banner: devolve também o link.
-- -----------------------------------------------------------------------------
drop function public.promocoes_para_busca(text, double precision, double precision, integer);
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
grant execute on function public.promocoes_para_busca(text, double precision, double precision, integer) to authenticated;

-- -----------------------------------------------------------------------------
-- Edição do cadastro (o dono; RLS garante que é dele)
-- -----------------------------------------------------------------------------
drop function public.minhas_lojas(uuid);
create function public.minhas_lojas(p_pdv_id uuid)
returns table (
  id           uuid,
  nome         text,
  endereco     text,   -- completo, para exibir
  logradouro   text,
  bairro       text,
  cidade       text,
  uf           text,
  telefone     text,
  whatsapp     text
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
           l.endereco, l.bairro, l.cidade, l.uf::text, l.telefone, l.whatsapp
    from public.lojas l
    where l.pdv_id = p_pdv_id and l.ativa
    order by l.created_at;
end;
$$;

drop function public.meus_pdvs();
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
  site                      text
)
language sql
stable
security definer
set search_path = ''
as $$
  select p.id, p.cnpj::text, p.razao_social, p.nome_fantasia, p.status, p.motivo_rejeicao,
         p.cnpj_conferido_no_alvara, p.created_at, p.verificado_em, p.modo_rede, p.site
  from public.pdvs p
  where p.dono_id = auth.uid()
  order by p.created_at
$$;
revoke execute on function public.meus_pdvs() from public, anon;
grant execute on function public.meus_pdvs() to authenticated, service_role;

create function public.atualizar_pdv(p_pdv_id uuid, p_nome_fantasia text, p_site text)
returns void
language plpgsql
security invoker
set search_path = ''
as $$
begin
  if length(btrim(coalesce(p_nome_fantasia, ''))) = 0 then
    raise exception 'nome_obrigatorio';
  end if;
  update public.pdvs
     set nome_fantasia = left(btrim(p_nome_fantasia), 120),
         site = nullif(btrim(p_site), '')
   where id = p_pdv_id;
  if not found then
    raise exception 'sem_permissao';
  end if;
end;
$$;

create function public.atualizar_loja(
  p_loja_id   uuid,
  p_nome      text,
  p_endereco  text,
  p_bairro    text,
  p_cidade    text,
  p_telefone  text,
  p_whatsapp  text
)
returns void
language plpgsql
security invoker
set search_path = ''
as $$
begin
  if length(btrim(coalesce(p_endereco, ''))) = 0 then
    raise exception 'endereco_obrigatorio';
  end if;
  update public.lojas
     set nome = nullif(btrim(p_nome), ''),
         endereco = left(btrim(p_endereco), 200),
         bairro = nullif(btrim(p_bairro), ''),
         cidade = coalesce(nullif(btrim(p_cidade), ''), cidade),
         telefone = nullif(left(btrim(p_telefone), 20), ''),
         whatsapp = nullif(left(regexp_replace(coalesce(p_whatsapp, ''), '[^0-9+]', '', 'g'), 20), '')
   where id = p_loja_id;
  if not found then
    raise exception 'sem_permissao';
  end if;
end;
$$;

-- -----------------------------------------------------------------------------
-- Promoções pagas e pacotes de operações
--
-- O PDV cria a promoção (fica "aguardando pagamento") e o pedido de pagamento.
-- Enquanto o Pix automático (Mercado Pago) não está ligado, o pagamento é
-- combinado pelo WhatsApp e o Admin confirma no app — o que ativa o banner ou
-- libera as +50 operações (mesma função usada pelo webhook do Pix depois).
-- -----------------------------------------------------------------------------
create function public.criar_promocao(
  p_pdv_id          uuid,
  p_titulo          text,
  p_descricao       text,
  p_link            text,
  p_arte_path       text,
  p_palavras_chave  text[],
  p_visualizacoes   integer
)
returns uuid   -- id do pagamento pendente
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_promo  uuid;
  v_pag    uuid;
  v_valor  integer;
begin
  if not public.eh_dono_pdv(p_pdv_id) then
    raise exception 'sem_permissao';
  end if;
  v_valor := case p_visualizacoes when 100 then 1000 when 250 then 2500 when 500 then 5000 end;
  if v_valor is null then
    raise exception 'pacote_invalido';
  end if;
  if length(btrim(coalesce(p_titulo, ''))) = 0 then
    raise exception 'titulo_obrigatorio';
  end if;
  if p_arte_path is not null and p_arte_path not like p_pdv_id::text || '/%' then
    raise exception 'arte_invalida';
  end if;
  if (select count(*) from public.promocoes where pdv_id = p_pdv_id and status = 'aguardando_pagamento') >= 5 then
    raise exception 'promocoes_pendentes_demais';
  end if;

  insert into public.promocoes (pdv_id, loja_id, titulo, descricao, link, arte_path, origem_arte, palavras_chave)
  values (p_pdv_id,
          (select l.id from public.lojas l where l.pdv_id = p_pdv_id and l.ativa order by l.created_at limit 1),
          left(btrim(p_titulo), 80), nullif(left(btrim(p_descricao), 500), ''),
          nullif(btrim(p_link), ''), p_arte_path, 'propria',
          coalesce((select array_agg(distinct left(btrim(k), 40)) from unnest(p_palavras_chave) k
                    where length(btrim(k)) > 0), '{}'))
  returning id into v_promo;

  insert into public.pagamentos (usuario_id, pdv_id, tipo, promocao_id, quantidade, valor_centavos, provedor)
  values (auth.uid(), p_pdv_id, 'pacote_visualizacoes', v_promo, p_visualizacoes, v_valor, 'manual')
  returning id into v_pag;
  return v_pag;
end;
$$;

create function public.comprar_pacote_operacoes(p_pdv_id uuid, p_loja_id uuid)
returns uuid
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_pdv  public.pdvs%rowtype;
  v_pag  uuid;
begin
  select * into v_pdv from public.pdvs where id = p_pdv_id;
  if not found or v_pdv.dono_id <> auth.uid() then
    raise exception 'sem_permissao';
  end if;
  if v_pdv.status <> 'aprovado' then
    raise exception 'pdv_nao_verificado';
  end if;
  insert into public.pagamentos (usuario_id, pdv_id, tipo, loja_id, quantidade, valor_centavos, provedor)
  values (auth.uid(), p_pdv_id, 'pacote_operacoes',
          case when v_pdv.modo_rede then null else p_loja_id end, 50, 1000, 'manual')
  returning id into v_pag;
  return v_pag;
end;
$$;

-- Promoções do PDV, com o pagamento pendente (se houver).
create function public.minhas_promocoes(p_pdv_id uuid)
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
    where pr.pdv_id = p_pdv_id
    order by pr.created_at desc;
end;
$$;

-- Admin: pagamentos esperando confirmação.
create function public.pagamentos_pendentes()
returns table (
  id              uuid,
  tipo            public.tipo_pagamento,
  pdv_nome        text,
  descricao       text,
  quantidade      integer,
  valor_centavos  integer,
  dono_nome       text,
  dono_email      text,
  created_at      timestamptz
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
    select g.id, g.tipo, p.nome_fantasia,
           case when g.tipo = 'pacote_visualizacoes' then 'Banner: ' || pr.titulo else '+50 operações' end,
           g.quantidade, g.valor_centavos, u.nome, u.email, g.created_at
    from public.pagamentos g
    join public.pdvs p on p.id = g.pdv_id
    left join public.promocoes pr on pr.id = g.promocao_id
    left join public.usuarios u on u.id = g.usuario_id
    where g.status = 'pendente'
    order by g.created_at;
end;
$$;

create function public.admin_confirmar_pagamento(p_pagamento_id uuid)
returns void
language plpgsql
security definer
set search_path = ''
as $$
begin
  if not public.is_admin() then
    raise exception 'sem_permissao';
  end if;
  perform public.confirmar_pagamento(p_pagamento_id, 'manual-' || p_pagamento_id::text);
end;
$$;

create function public.admin_cancelar_pagamento(p_pagamento_id uuid)
returns void
language plpgsql
security definer
set search_path = ''
as $$
begin
  if not public.is_admin() then
    raise exception 'sem_permissao';
  end if;
  update public.pagamentos set status = 'cancelado' where id = p_pagamento_id and status = 'pendente';
end;
$$;

-- Encerrar promoção pelo dono. Pedidos de pagamento em aberto são cancelados.
-- Se já houve pagamento, a promoção fica no histórico (pausada) — o registro
-- financeiro não pode perder a referência; senão, é apagada de vez.
create function public.encerrar_promocao(p_promocao_id uuid)
returns text
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_pdv uuid;
begin
  select pdv_id into v_pdv from public.promocoes where id = p_promocao_id;
  if v_pdv is null or not public.eh_dono_pdv(v_pdv) then
    raise exception 'sem_permissao';
  end if;
  if exists (select 1 from public.pagamentos where promocao_id = p_promocao_id and status = 'pago') then
    update public.pagamentos set status = 'cancelado' where promocao_id = p_promocao_id and status = 'pendente';
    update public.promocoes set status = 'pausada' where id = p_promocao_id;
    return 'pausada';
  end if;
  delete from public.pagamentos where promocao_id = p_promocao_id;
  delete from public.promocoes where id = p_promocao_id;
  return 'excluida';
end;
$$;

revoke execute on function
  public.minhas_lojas(uuid), public.atualizar_pdv(uuid, text, text),
  public.atualizar_loja(uuid, text, text, text, text, text, text),
  public.criar_promocao(uuid, text, text, text, text, text[], integer),
  public.comprar_pacote_operacoes(uuid, uuid), public.minhas_promocoes(uuid),
  public.pagamentos_pendentes(), public.admin_confirmar_pagamento(uuid), public.admin_cancelar_pagamento(uuid),
  public.encerrar_promocao(uuid)
  from public, anon;
grant execute on function
  public.minhas_lojas(uuid), public.atualizar_pdv(uuid, text, text),
  public.atualizar_loja(uuid, text, text, text, text, text, text),
  public.criar_promocao(uuid, text, text, text, text, text[], integer),
  public.comprar_pacote_operacoes(uuid, uuid), public.minhas_promocoes(uuid),
  public.pagamentos_pendentes(), public.admin_confirmar_pagamento(uuid), public.admin_cancelar_pagamento(uuid),
  public.encerrar_promocao(uuid)
  to authenticated, service_role;
