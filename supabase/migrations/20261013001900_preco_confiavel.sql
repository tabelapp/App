-- =============================================================================
-- Preço confiável: avisos, selo prudente, combos e denúncia leve
--
-- Caso real: NF do Burger King com "Pão, carne e queijo" a R$ 4,00 — o valor
-- rateado de um combo de R$ 31 — apareceu como "mais barato". Preço de NF pode
-- refletir combo, promoção casada, rateio, fidelidade ou preço de balcão.
--
--  * cotacoes.parte_de_combo: marcado no envio da NF; nunca recebe o selo.
--  * denuncias_preco: "Esse preço não confere" (1 por pessoa por cotação).
--    Com N denúncias (configuracao_app.denuncias_limite) o card mostra
--    "Confira na loja" e perde o selo. Nada é apagado automaticamente.
--  * Parâmetros do selo "Menor preço lançado" (preço atípico e preferência por
--    PDV cadastrado) em configuracao_app — o app aplica a regra.
--  * Visão denuncias_mais_frequentes para o Admin.
--
-- Idempotente: pode ser executada de novo sem erro e sem apagar dados.
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 1. Parâmetros (ajustáveis por SQL, sem publicar app)
-- -----------------------------------------------------------------------------
insert into public.configuracao_app (chave, valor, descricao) values
  ('preco_atipico_fracao', '0.4',
   'Preço abaixo desta fração da mediana dos demais resultados da busca é atípico (sem selo, "Confira na loja").'),
  ('preco_atipico_min_resultados', '3', 'Mínimo de resultados na busca para aplicar o detector de preço atípico.'),
  ('selo_tolerancia_pdv', '0.05', 'Selo prefere PDV cadastrado quando o preço dele é até esta fração acima do menor.'),
  ('denuncias_limite', '3', 'Denúncias "Esse preço não confere" para o card mostrar "Confira na loja" e perder o selo.')
on conflict (chave) do nothing;

-- -----------------------------------------------------------------------------
-- 2. Item de combo (marcado no envio da NF)
-- -----------------------------------------------------------------------------
alter table public.cotacoes add column if not exists parte_de_combo boolean not null default false;
grant select (parte_de_combo) on public.cotacoes to authenticated;
alter table public.historico_precos add column if not exists parte_de_combo boolean;

-- O histórico guarda também se o item era de combo.
create or replace function public._registrar_historico()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_c     public.cotacoes%rowtype;
  v_op    text;
  v_pdv   uuid;
  v_cnpj  char(14);
  v_nome  text;
  v_end   text;
begin
  if tg_op = 'DELETE' then
    v_c := old;
    v_op := 'excluido';
  else
    v_c := new;
    v_op := case when tg_op = 'INSERT' then 'criado' else 'alterado' end;
    -- Atualização que não muda nada relevante não vira histórico.
    if tg_op = 'UPDATE'
       and old.preco_centavos = new.preco_centavos
       and old.produto = new.produto
       and old.validade is not distinct from new.validade
       and old.obs is not distinct from new.obs
       and old.unidade is not distinct from new.unidade
       and old.a_partir_de is not distinct from new.a_partir_de
       and old.parte_de_combo is not distinct from new.parte_de_combo then
      return null;
    end if;
  end if;

  if v_c.loja_id is not null then
    select p.id, p.cnpj, p.nome_fantasia, concat_ws(', ', l.endereco, l.bairro, l.cidade || ' - ' || l.uf)
      into v_pdv, v_cnpj, v_nome, v_end
      from public.lojas l join public.pdvs p on p.id = l.pdv_id
     where l.id = v_c.loja_id;
  end if;
  v_cnpj := coalesce(v_cnpj, substring(v_c.chave_acesso_nf from 7 for 14));
  v_nome := coalesce(v_nome, v_c.pdv_nome_livre);
  v_end  := coalesce(v_end, v_c.pdv_endereco_livre);

  insert into public.historico_precos
    (operacao, cotacao_id, loja_id, pdv_id, cnpj, pdv_nome, pdv_endereco, produto, produto_busca,
     preco_centavos, validade, data_nf, obs, fonte, chave_acesso_nf, enviado_por, lote_id, unidade, a_partir_de, parte_de_combo)
  values
    (v_op, v_c.id, v_c.loja_id, v_pdv, v_cnpj, v_nome, v_end, v_c.produto, v_c.produto_busca,
     v_c.preco_centavos, v_c.validade, v_c.data_nf, v_c.obs, v_c.fonte, v_c.chave_acesso_nf,
     v_c.enviado_por, v_c.lote_id, v_c.unidade, v_c.a_partir_de, v_c.parte_de_combo);
  return null;
end;
$$;

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
  -- certo, modelo NFC-e (65) ou NF-e (55), emitida no RJ (piloto).
  if v_chave is null then
    raise exception 'chave_obrigatoria';
  end if;
  if not public._chave_nfe_valida(v_chave) or substring(v_chave from 21 for 2) not in ('65', '55') then
    raise exception 'chave_acesso_invalida';
  end if;
  if substring(v_chave from 1 for 2) <> '33' then
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
-- 3. Denúncia leve: "Esse preço não confere"
-- -----------------------------------------------------------------------------
create table if not exists public.denuncias_preco (
  id          bigint generated always as identity primary key,
  cotacao_id  uuid not null references public.cotacoes (id) on delete cascade,
  usuario_id  uuid not null default auth.uid() references public.usuarios (id) on delete cascade,
  motivo      text check (motivo is null or motivo in ('combo_ou_promocao', 'preco_mudou', 'produto_errado', 'outro')),
  created_at  timestamptz not null default now()
);
-- Cada pessoa denuncia a mesma cotação uma vez só.
create unique index if not exists denuncias_preco_uidx on public.denuncias_preco (cotacao_id, usuario_id);
create index if not exists denuncias_preco_cotacao_idx on public.denuncias_preco (cotacao_id);

alter table public.denuncias_preco enable row level security;
drop policy if exists denuncias_insert on public.denuncias_preco;
create policy denuncias_insert on public.denuncias_preco for insert to authenticated
  with check (usuario_id = auth.uid());
-- Ninguém lê as denúncias dos outros; o Admin lê todas.
drop policy if exists denuncias_select on public.denuncias_preco;
create policy denuncias_select on public.denuncias_preco for select to authenticated
  using (usuario_id = auth.uid() or public.is_admin());
revoke all on public.denuncias_preco from anon;
revoke update, delete, truncate on public.denuncias_preco from authenticated;
grant select, insert on public.denuncias_preco to authenticated;

-- Registra a denúncia; repetir não duplica (devolve false).
create or replace function public.denunciar_preco(p_cotacao_id uuid, p_motivo text default null)
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
  insert into public.denuncias_preco (cotacao_id, motivo)
  values (p_cotacao_id, nullif(btrim(p_motivo), ''))
  on conflict (cotacao_id, usuario_id) do nothing;
  get diagnostics v_n = row_count;
  return v_n > 0;
end;
$$;

-- Total de denúncias de uma cotação (a busca mostra só o número, nunca quem denunciou).
create or replace function public._total_denuncias(p_cotacao_id uuid)
returns integer
language sql
stable
security definer
set search_path = ''
as $$
  select count(*)::integer from public.denuncias_preco where cotacao_id = p_cotacao_id
$$;

-- Admin: as cotações mais denunciadas (RLS: só o Admin enxerga as denúncias dos outros).
create or replace view public.denuncias_mais_frequentes with (security_invoker = true) as
select d.cotacao_id,
       c.produto,
       c.preco_centavos,
       c.fonte,
       c.parte_de_combo,
       coalesce(p.nome_fantasia, c.pdv_nome_livre)           as pdv_nome,
       count(*)                                               as denuncias,
       count(*) filter (where d.motivo = 'combo_ou_promocao') as combo_ou_promocao,
       count(*) filter (where d.motivo = 'preco_mudou')       as preco_mudou,
       count(*) filter (where d.motivo = 'produto_errado')    as produto_errado,
       count(*) filter (where d.motivo = 'outro' or d.motivo is null) as outros,
       max(d.created_at)                                      as ultima_denuncia
from public.denuncias_preco d
join public.cotacoes c on c.id = d.cotacao_id
left join public.lojas l on l.id = c.loja_id
left join public.pdvs p on p.id = l.pdv_id
group by d.cotacao_id, c.produto, c.preco_centavos, c.fonte, c.parte_de_combo, p.nome_fantasia, c.pdv_nome_livre
order by denuncias desc, ultima_denuncia desc;
revoke all on public.denuncias_mais_frequentes from anon;
grant select on public.denuncias_mais_frequentes to authenticated;

-- -----------------------------------------------------------------------------
-- 4. Busca: devolve parte_de_combo e o total de denúncias
-- -----------------------------------------------------------------------------
drop function if exists public.buscar_cotacoes(text, double precision, double precision, integer);
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
  data_nf         date,
  unidade         public.unidade_medida,
  a_partir_de     boolean,
  parte_de_combo  boolean,
  denuncias       integer
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
  ),
  validas as (
    -- Colunas listadas uma a uma: quem busca não pode ler `enviado_por`.
    select c.id, c.loja_id, c.pdv_nome_livre, c.pdv_endereco_livre, c.produto, c.produto_busca,
           c.preco_centavos, c.validade, c.obs, c.fonte, c.chave_acesso_nf, c.created_at, c.updated_at, c.data_nf,
           c.unidade, c.a_partir_de, c.parte_de_combo
    from public.cotacoes c
    left join public.lojas l on l.id = c.loja_id
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
  ),
  recentes as (
    select distinct on (public.local_cotacao(v.loja_id, v.chave_acesso_nf, v.pdv_nome_livre, v.pdv_endereco_livre),
                        v.produto_busca)
           v.id, v.loja_id, v.pdv_endereco_livre, v.pdv_nome_livre, v.produto, v.preco_centavos, v.validade,
           v.obs, v.fonte, v.chave_acesso_nf, v.created_at, v.data_nf, v.unidade, v.a_partir_de, v.parte_de_combo
    from validas v
    order by public.local_cotacao(v.loja_id, v.chave_acesso_nf, v.pdv_nome_livre, v.pdv_endereco_livre),
             v.produto_busca,
             public.dia_cotacao(v.data_nf, v.updated_at) desc,
             (v.fonte in ('pdv_manual', 'pdv_excel')) desc,
             v.created_at desc
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
         case when l.id is not null
              then concat_ws(', ', l.endereco, l.bairro, l.cidade || ' - ' || l.uf)
              else nullif(btrim(c.pdv_endereco_livre), '')
         end,
         coalesce(nullif(btrim(l.whatsapp), ''), l.telefone),
         p.site,
         l.latitude,
         l.longitude,
         public.distancia_km(p_lat, p_lng, l.latitude, l.longitude),
         c.created_at,
         c.data_nf,
         c.unidade,
         c.a_partir_de,
         c.parte_de_combo,
         public._total_denuncias(c.id)
  from recentes c
  left join public.lojas l on l.id = c.loja_id
  left join public.pdvs p on p.id = l.pdv_id
  left join public.nomes_pdv n on c.loja_id is null and n.cnpj = substring(c.chave_acesso_nf from 7 for 14)
  cross join termo
  order by
    case when termo.t is null then c.created_at end desc,
    c.preco_centavos asc
  limit least(greatest(coalesce(p_limite, 100), 1), 500)
$$;
revoke execute on function public.buscar_cotacoes(text, double precision, double precision, integer) from public, anon;
grant execute on function public.buscar_cotacoes(text, double precision, double precision, integer)
  to authenticated, service_role;

-- A busca (security invoker) usa _total_denuncias: só devolve o número, nunca quem denunciou.
revoke execute on function public._total_denuncias(uuid) from public, anon;
grant execute on function public._total_denuncias(uuid) to authenticated, service_role;
revoke execute on function public.denunciar_preco(uuid, text) from public, anon;
grant execute on function public.denunciar_preco(uuid, text) to authenticated, service_role;
