-- =============================================================================
-- 1. Histórico permanente de preços (decisão do fundador: "vital para a
--    longevidade do projeto")
--
-- A busca mostra só preços dentro da validade (NF: 7 dias). Mas NENHUM preço
-- se perde: toda criação, alteração ou exclusão em `cotacoes` vira uma linha
-- em `historico_precos`, com a foto do momento (produto, preço, loja/CNPJ,
-- nome e endereço do ponto de venda). Base para o histórico de preços futuro.
-- =============================================================================

create table public.historico_precos (
  id              bigint generated always as identity primary key,
  registrado_em   timestamptz not null default now(),
  operacao        text not null check (operacao in ('criado', 'alterado', 'excluido')),
  cotacao_id      uuid not null,           -- sem FK: a linha fica mesmo se a cotação sumir
  loja_id         uuid,
  pdv_id          uuid,
  cnpj            char(14),
  pdv_nome        text,
  pdv_endereco    text,
  produto         text not null,
  produto_busca   text not null,
  preco_centavos  integer not null,
  validade        date,
  data_nf         date,
  obs             text,
  fonte           public.fonte_cotacao not null,
  chave_acesso_nf char(44),
  enviado_por     uuid,
  lote_id         uuid
);

create index historico_precos_produto_idx on public.historico_precos (produto_busca, registrado_em);
create index historico_precos_cnpj_idx on public.historico_precos (cnpj, registrado_em);
create index historico_precos_cotacao_idx on public.historico_precos (cotacao_id);

alter table public.historico_precos enable row level security;
-- Por enquanto só o Admin consulta; ninguém altera nem apaga pelo app.
create policy historico_admin on public.historico_precos for select to authenticated using (public.is_admin());
revoke insert, update, delete, truncate on public.historico_precos from authenticated, anon;

create function public._registrar_historico()
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
       and old.obs is not distinct from new.obs then
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
     preco_centavos, validade, data_nf, obs, fonte, chave_acesso_nf, enviado_por, lote_id)
  values
    (v_op, v_c.id, v_c.loja_id, v_pdv, v_cnpj, v_nome, v_end, v_c.produto, v_c.produto_busca,
     v_c.preco_centavos, v_c.validade, v_c.data_nf, v_c.obs, v_c.fonte, v_c.chave_acesso_nf,
     v_c.enviado_por, v_c.lote_id);
  return null;
end;
$$;

create trigger cotacoes_historico
  after insert or update or delete on public.cotacoes
  for each row execute function public._registrar_historico();

-- O que já existe entra no histórico com a data em que foi lançado.
insert into public.historico_precos
  (registrado_em, operacao, cotacao_id, loja_id, pdv_id, cnpj, pdv_nome, pdv_endereco, produto,
   produto_busca, preco_centavos, validade, data_nf, obs, fonte, chave_acesso_nf, enviado_por, lote_id)
select c.created_at, 'criado', c.id, c.loja_id, p.id,
       coalesce(p.cnpj, substring(c.chave_acesso_nf from 7 for 14)),
       coalesce(p.nome_fantasia, c.pdv_nome_livre),
       coalesce(concat_ws(', ', l.endereco, l.bairro, l.cidade || ' - ' || l.uf), c.pdv_endereco_livre),
       c.produto, c.produto_busca, c.preco_centavos, c.validade, c.data_nf, c.obs, c.fonte,
       c.chave_acesso_nf, c.enviado_por, c.lote_id
from public.cotacoes c
left join public.lojas l on l.id = c.loja_id
left join public.pdvs p on p.id = l.pdv_id;

-- =============================================================================
-- 2. Nome do ponto de venda nas notas fiscais
--
-- A NF traz a razão social (muitas vezes bem diferente do nome conhecido).
-- O app procura o nome fantasia na Receita; se não houver, o usuário pode
-- SUGERIR o nome. Para não ser mal usado (concorrente, fraude), a sugestão só
-- vale depois de confirmada: 2 pessoas diferentes sugerindo o mesmo nome, ou
-- o Admin aprovando. Só sugere quem enviou uma NF daquele CNPJ.
-- =============================================================================

create table public.nomes_pdv (
  cnpj        char(14) primary key check (cnpj ~ '^[0-9]{14}$'),
  nome        text not null check (length(btrim(nome)) between 2 and 120),
  origem      text not null check (origem in ('usuarios', 'admin')),
  updated_at  timestamptz not null default now()
);

create table public.sugestoes_nome_pdv (
  id          uuid primary key default gen_random_uuid(),
  cnpj        char(14) not null check (cnpj ~ '^[0-9]{14}$'),
  nome        text not null check (length(btrim(nome)) between 2 and 120),
  nome_busca  text generated always as (public.normalizar(nome)) stored,
  usuario_id  uuid not null references public.usuarios (id) on delete cascade,
  status      text not null default 'pendente' check (status in ('pendente', 'aprovado', 'rejeitado')),
  created_at  timestamptz not null default now(),
  unique (cnpj, usuario_id)
);

alter table public.nomes_pdv enable row level security;
alter table public.sugestoes_nome_pdv enable row level security;
create policy nomes_pdv_leitura on public.nomes_pdv for select to authenticated using (true);
create policy sugestoes_proprias on public.sugestoes_nome_pdv for select to authenticated
  using (usuario_id = auth.uid() or public.is_admin());
revoke insert, update, delete, truncate on public.nomes_pdv, public.sugestoes_nome_pdv from authenticated, anon;

create function public.sugerir_nome_pdv(p_cnpj text, p_nome text)
returns text   -- 'confirmado' | 'aguardando'
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_cnpj  text := regexp_replace(coalesce(p_cnpj, ''), '\D', '', 'g');
  v_nome  text := btrim(regexp_replace(coalesce(p_nome, ''), '\s+', ' ', 'g'));
  v_iguais integer;
begin
  if auth.uid() is null then
    raise exception 'nao_autenticado';
  end if;
  if length(v_cnpj) <> 14 then
    raise exception 'cnpj_invalido';
  end if;
  if length(v_nome) not between 2 and 120 then
    raise exception 'nome_obrigatorio';
  end if;
  -- Só quem comprou lá (enviou uma NF daquele CNPJ) pode sugerir o nome.
  if not exists (
    select 1 from public.cotacoes c
    where c.enviado_por = auth.uid() and substring(c.chave_acesso_nf from 7 for 14) = v_cnpj
  ) then
    raise exception 'sem_permissao';
  end if;
  -- Nome já confirmado não muda por sugestão.
  if exists (select 1 from public.nomes_pdv where cnpj = v_cnpj) then
    return 'confirmado';
  end if;

  insert into public.sugestoes_nome_pdv (cnpj, nome, usuario_id)
  values (v_cnpj, v_nome, auth.uid())
  on conflict (cnpj, usuario_id) do update set nome = excluded.nome, status = 'pendente', created_at = now();

  select count(distinct usuario_id) into v_iguais
    from public.sugestoes_nome_pdv
   where cnpj = v_cnpj and nome_busca = public.normalizar(v_nome) and status = 'pendente';

  if v_iguais >= 2 then
    insert into public.nomes_pdv (cnpj, nome, origem) values (v_cnpj, v_nome, 'usuarios');
    update public.sugestoes_nome_pdv set status = case when nome_busca = public.normalizar(v_nome)
                                                      then 'aprovado' else 'rejeitado' end
     where cnpj = v_cnpj and status = 'pendente';
    return 'confirmado';
  end if;
  return 'aguardando';
end;
$$;

-- Admin: sugestões esperando confirmação.
create function public.nomes_sugeridos_pendentes()
returns table (
  cnpj          text,
  nome          text,
  sugestoes     integer,
  razao_social  text,
  endereco      text,
  ultima        timestamptz
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
    select s.cnpj::text, min(s.nome), count(*)::integer,
           (select c.pdv_nome_livre from public.cotacoes c
             where substring(c.chave_acesso_nf from 7 for 14) = s.cnpj
             order by c.created_at desc limit 1),
           (select c.pdv_endereco_livre from public.cotacoes c
             where substring(c.chave_acesso_nf from 7 for 14) = s.cnpj
             order by c.created_at desc limit 1),
           max(s.created_at)
    from public.sugestoes_nome_pdv s
    where s.status = 'pendente'
      and not exists (select 1 from public.nomes_pdv n where n.cnpj = s.cnpj)
    group by s.cnpj, s.nome_busca
    order by max(s.created_at) desc;
end;
$$;

-- Admin aprova (grava o nome) ou rejeita uma sugestão.
create function public.decidir_nome_pdv(p_cnpj text, p_nome text, p_aprovar boolean)
returns void
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_cnpj text := regexp_replace(coalesce(p_cnpj, ''), '\D', '', 'g');
begin
  if not public.is_admin() then
    raise exception 'sem_permissao';
  end if;
  if p_aprovar then
    if length(btrim(coalesce(p_nome, ''))) not between 2 and 120 then
      raise exception 'nome_obrigatorio';
    end if;
    insert into public.nomes_pdv (cnpj, nome, origem) values (v_cnpj, btrim(p_nome), 'admin')
    on conflict (cnpj) do update set nome = excluded.nome, origem = 'admin', updated_at = now();
    update public.sugestoes_nome_pdv
       set status = case when nome_busca = public.normalizar(p_nome) then 'aprovado' else 'rejeitado' end
     where cnpj = v_cnpj and status = 'pendente';
  else
    update public.sugestoes_nome_pdv set status = 'rejeitado'
     where cnpj = v_cnpj and nome_busca = public.normalizar(p_nome) and status = 'pendente';
  end if;
end;
$$;

-- -----------------------------------------------------------------------------
-- Busca: preço de NF sem loja cadastrada mostra o nome confirmado do CNPJ.
-- -----------------------------------------------------------------------------
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

revoke execute on function public._registrar_historico() from public, anon, authenticated;
revoke execute on function public.sugerir_nome_pdv(text, text) from public, anon;
revoke execute on function public.nomes_sugeridos_pendentes() from public, anon;
revoke execute on function public.decidir_nome_pdv(text, text, boolean) from public, anon;
grant execute on function public.sugerir_nome_pdv(text, text) to authenticated, service_role;
grant execute on function public.nomes_sugeridos_pendentes() to authenticated, service_role;
grant execute on function public.decidir_nome_pdv(text, text, boolean) to authenticated, service_role;
