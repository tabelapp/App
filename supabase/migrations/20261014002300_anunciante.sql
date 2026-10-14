-- =============================================================================
-- Empresa anunciante (sem ponto de venda)
--
-- Produtores, marcas e prestadores que não vendem direto ao público podem ter
-- conta de CNPJ e anunciar no banner da busca, sem cadastrar loja nem tabela
-- de preços. Passam pela mesma aprovação do Admin (CNPJ na Receita + contato);
-- a foto de um documento da empresa é opcional.
--
-- Idempotente: pode ser executada de novo sem erro e sem apagar dados.
-- =============================================================================

alter table public.pdvs add column if not exists tipo text not null default 'loja';
do $$ begin
  if not exists (select 1 from pg_constraint where conname = 'pdvs_tipo_check') then
    alter table public.pdvs add constraint pdvs_tipo_check check (tipo in ('loja', 'anunciante'));
  end if;
end $$;
-- Contato e cidade do anunciante (a loja guarda os dela em `lojas`).
alter table public.pdvs add column if not exists telefone text;
alter table public.pdvs add column if not exists cidade text;
alter table public.pdvs add column if not exists uf char(2);
grant select (tipo) on public.pdvs to authenticated;

-- ---------------------------------------------------------------------------
-- Cadastro do anunciante
-- ---------------------------------------------------------------------------
create or replace function public.cadastrar_anunciante(
  p_cnpj            text,
  p_nome_fantasia   text,
  p_razao_social    text,
  p_telefone        text,
  p_cidade          text,
  p_uf              text,
  p_site            text default null,
  p_documento_path  text default null,
  p_dados_receita   jsonb default null
)
returns uuid
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_cnpj       text := regexp_replace(coalesce(p_cnpj, ''), '\D', '', 'g');
  v_existente  public.pdvs%rowtype;
  v_id         uuid;
begin
  if auth.uid() is null then
    raise exception 'nao_autenticado';
  end if;
  if coalesce((select tipo from public.usuarios where id = auth.uid()), 'cpf') <> 'cnpj' then
    raise exception 'conta_nao_cnpj';
  end if;
  if not public._cnpj_valido(v_cnpj) then
    raise exception 'cnpj_invalido';
  end if;
  if length(btrim(coalesce(p_nome_fantasia, ''))) = 0 then
    raise exception 'nome_obrigatorio';
  end if;
  if length(regexp_replace(coalesce(p_telefone, ''), '\D', '', 'g')) < 10 then
    raise exception 'telefone_obrigatorio';
  end if;
  if length(btrim(coalesce(p_cidade, ''))) = 0 then
    raise exception 'cidade_obrigatoria';
  end if;
  if p_documento_path is not null and p_documento_path not like auth.uid()::text || '/%' then
    raise exception 'alvara_obrigatorio';
  end if;

  select * into v_existente from public.pdvs where cnpj = v_cnpj for update;
  if found then
    if v_existente.status in ('aprovado', 'suspenso') then
      raise exception 'cnpj_ja_cadastrado';
    elsif v_existente.status = 'pendente' and v_existente.dono_id <> auth.uid() then
      raise exception 'cnpj_em_analise';
    end if;
    delete from public.pdvs where id = v_existente.id;
  end if;

  insert into public.pdvs
    (dono_id, cnpj, razao_social, nome_fantasia, modo_rede, status, tipo, telefone, cidade, uf, site,
     alvara_path, cnpj_conferido_no_alvara, dados_receita)
  values
    (auth.uid(), v_cnpj, nullif(btrim(p_razao_social), ''), left(btrim(p_nome_fantasia), 120), true, 'pendente',
     'anunciante', left(btrim(p_telefone), 20), left(btrim(p_cidade), 80),
     nullif(left(upper(btrim(coalesce(p_uf, ''))), 2), ''), nullif(btrim(p_site), ''),
     p_documento_path, false, p_dados_receita)
  returning id into v_id;
  return v_id;
end;
$$;
revoke execute on function public.cadastrar_anunciante(text, text, text, text, text, text, text, text, jsonb) from public, anon;
grant execute on function public.cadastrar_anunciante(text, text, text, text, text, text, text, text, jsonb) to authenticated;

-- ---------------------------------------------------------------------------
-- Funções que passam a devolver o tipo
-- ---------------------------------------------------------------------------
drop function if exists public.meus_pdvs();
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
  site                      text,
  motivo_suspensao          text,
  tipo                      text
)
language sql
stable
security definer
set search_path = ''
as $$
  select p.id, p.cnpj::text, p.razao_social, p.nome_fantasia, p.status, p.motivo_rejeicao,
         p.cnpj_conferido_no_alvara, p.created_at, p.verificado_em, p.modo_rede, p.site, p.motivo_suspensao, p.tipo
  from public.pdvs p
  where p.dono_id = auth.uid()
  order by p.created_at
$$;
revoke execute on function public.meus_pdvs() from public, anon;
grant execute on function public.meus_pdvs() to authenticated, service_role;

drop function if exists public.pdvs_pendentes();
create function public.pdvs_pendentes()
returns table (
  id                        uuid,
  cnpj                      text,
  razao_social              text,
  nome_fantasia             text,
  endereco                  text,
  telefone                  text,
  alvara_path               text,
  cnpj_conferido_no_alvara  boolean,
  dados_receita             jsonb,
  dono_nome                 text,
  dono_email                text,
  created_at                timestamptz,
  tipo                      text
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
    select p.id, p.cnpj::text, p.razao_social, p.nome_fantasia,
           coalesce(
             (select concat_ws(', ', l.endereco, l.bairro, l.cidade || ' - ' || l.uf)
                from public.lojas l where l.pdv_id = p.id order by l.created_at limit 1),
             nullif(concat_ws(' - ', p.cidade, p.uf), '')),
           coalesce((select l.telefone from public.lojas l where l.pdv_id = p.id order by l.created_at limit 1),
                    p.telefone),
           p.alvara_path, p.cnpj_conferido_no_alvara, p.dados_receita, u.nome, u.email, p.created_at, p.tipo
    from public.pdvs p
    left join public.usuarios u on u.id = p.dono_id
    where p.status = 'pendente'
    order by p.created_at;
end;
$$;
revoke execute on function public.pdvs_pendentes() from public, anon;
grant execute on function public.pdvs_pendentes() to authenticated;

drop function if exists public.admin_pdvs(text);
create function public.admin_pdvs(p_busca text default null)
returns table (
  id                uuid,
  nome_fantasia     text,
  cnpj              text,
  status            public.status_verificacao,
  motivo_suspensao  text,
  cidade            text,
  dono_nome         text,
  dono_email        text,
  itens_publicados  integer,
  verificado_em     timestamptz,
  tipo              text
)
language plpgsql
stable
security definer
set search_path = ''
as $$
declare
  v_busca text := nullif(public.normalizar(p_busca), '');
  v_digitos text := nullif(regexp_replace(coalesce(p_busca, ''), '\D', '', 'g'), '');
begin
  if not public.is_admin() then
    raise exception 'sem_permissao';
  end if;
  return query
    select p.id, p.nome_fantasia, p.cnpj::text, p.status, p.motivo_suspensao,
           coalesce((select l.cidade from public.lojas l where l.pdv_id = p.id order by l.created_at limit 1), p.cidade),
           u.nome, u.email,
           (select count(distinct c.produto_busca)::integer from public.cotacoes c
              join public.lojas l on l.id = c.loja_id
             where l.pdv_id = p.id and c.fonte in ('pdv_manual', 'pdv_excel') and c.validade >= public.hoje()),
           p.verificado_em, p.tipo
    from public.pdvs p
    left join public.usuarios u on u.id = p.dono_id
    where p.status::text in ('aprovado', 'suspenso')
      and (v_busca is null
           or strpos(public.normalizar(p.nome_fantasia), v_busca) > 0
           or (v_digitos is not null and strpos(p.cnpj::text, v_digitos) > 0))
    order by p.status::text = 'suspenso' desc, p.nome_fantasia
    limit 200;
end;
$$;
revoke execute on function public.admin_pdvs(text) from public, anon;
grant execute on function public.admin_pdvs(text) to authenticated;

-- ---------------------------------------------------------------------------
-- Correção: um CNPJ suspenso pelo Admin não pode ser "recadastrado" (o
-- recadastro apagaria o cadastro suspenso). Mesmo corpo de antes, só esta regra.
-- ---------------------------------------------------------------------------
create or replace function public.cadastrar_pdv(
  p_cnpj                 text,
  p_nome_fantasia        text,
  p_razao_social         text,
  p_endereco             text,
  p_bairro               text,
  p_cidade               text,
  p_uf                   text,
  p_cep                  text,
  p_telefone             text,
  p_alvara_path          text,
  p_cnpj_no_alvara       boolean default false,
  p_dados_receita        jsonb default null
)
returns uuid
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_cnpj       text := regexp_replace(coalesce(p_cnpj, ''), '\D', '', 'g');
  v_existente  public.pdvs%rowtype;
  v_id         uuid;
  v_tem_foto   boolean;
begin
  if auth.uid() is null then
    raise exception 'nao_autenticado';
  end if;
  if coalesce((select tipo from public.usuarios where id = auth.uid()), 'cpf') <> 'cnpj' then
    raise exception 'conta_nao_cnpj';
  end if;
  if not public._cnpj_valido(v_cnpj) then
    raise exception 'cnpj_invalido';
  end if;
  if length(btrim(coalesce(p_nome_fantasia, ''))) = 0 then
    raise exception 'nome_obrigatorio';
  end if;
  if length(btrim(coalesce(p_endereco, ''))) = 0 then
    raise exception 'endereco_obrigatorio';
  end if;
  if p_alvara_path is null or p_alvara_path not like auth.uid()::text || '/%' then
    raise exception 'alvara_obrigatorio';
  end if;
  -- No Supabase, a foto precisa ter sido enviada de fato.
  -- (SQL dinâmico: nos testes locais não existe o schema storage.)
  if to_regclass('storage.objects') is not null then
    execute 'select exists (select 1 from storage.objects where bucket_id = $1 and name = $2)'
      into v_tem_foto using 'alvaras', p_alvara_path;
    if not v_tem_foto then
      raise exception 'alvara_obrigatorio';
    end if;
  end if;

  select * into v_existente from public.pdvs where cnpj = v_cnpj for update;
  if found then
    if v_existente.status in ('aprovado', 'suspenso') then
      raise exception 'cnpj_ja_cadastrado';
    elsif v_existente.status = 'pendente' and v_existente.dono_id <> auth.uid() then
      raise exception 'cnpj_em_analise';
    end if;
    -- Pedido rejeitado (de qualquer pessoa) ou o próprio pedido pendente: começa de novo.
    delete from public.pdvs where id = v_existente.id;
  end if;

  insert into public.pdvs
    (dono_id, cnpj, razao_social, nome_fantasia, modo_rede, status,
     alvara_path, cnpj_conferido_no_alvara, dados_receita)
  values
    (auth.uid(), v_cnpj, nullif(btrim(p_razao_social), ''), btrim(p_nome_fantasia), true, 'pendente',
     p_alvara_path, coalesce(p_cnpj_no_alvara, false), p_dados_receita)
  returning id into v_id;

  insert into public.lojas (pdv_id, endereco, bairro, cidade, uf, cep, telefone)
  values (v_id, btrim(p_endereco), nullif(btrim(p_bairro), ''),
          coalesce(nullif(btrim(p_cidade), ''), 'Petrópolis'),
          coalesce(left(nullif(upper(btrim(p_uf)), ''), 2), 'RJ'),
          nullif(btrim(p_cep), ''), nullif(btrim(p_telefone), ''));

  return v_id;
end;
$$;
