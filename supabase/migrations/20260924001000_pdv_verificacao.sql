-- =============================================================================
-- Cadastro do PDV com comprovação pelo alvará (decisão do fundador)
--
-- Para evitar que alguém cadastre uma empresa que não é dele:
--   1. O CNPJ precisa ser válido; o app consulta a Receita (situação, nome,
--      endereço) e guarda o resultado em dados_receita, para o Admin conferir.
--   2. O dono envia a foto do ALVARÁ (bucket privado "alvaras"). O app lê a foto
--      e diz se achou o CNPJ nela (cnpj_conferido_no_alvara) — ajuda o Admin,
--      mas não aprova sozinho: o que roda no celular pode ser adulterado.
--   3. O PDV nasce 'pendente'. Só o Admin aprova ou rejeita (com motivo).
--   4. Enquanto não estiver aprovado, o PDV não publica preço nem promoção e
--      suas lojas não aparecem para ligar notas fiscais.
-- =============================================================================

create type public.status_verificacao as enum ('pendente', 'aprovado', 'rejeitado');

alter table public.pdvs
  add column status                    public.status_verificacao not null default 'pendente',
  add column alvara_path               text,
  add column cnpj_conferido_no_alvara  boolean not null default false,
  add column dados_receita             jsonb,
  add column motivo_rejeicao           text check (motivo_rejeicao is null or length(motivo_rejeicao) <= 300),
  add column verificado_em             timestamptz,
  add column verificado_por            uuid references public.usuarios (id) on delete set null;

-- PDVs que já existiam (piloto/demonstração) continuam valendo.
update public.pdvs set status = 'aprovado', verificado_em = now();

create index pdvs_pendentes_idx on public.pdvs (created_at) where status = 'pendente';

-- PDV só nasce pela função cadastrar_pdv. O dono edita só os dados de exibição;
-- CNPJ, status e dados de verificação ninguém muda pelo app.
drop policy pdvs_insert on public.pdvs;
revoke insert, update on public.pdvs from authenticated;
grant update (nome_fantasia, site, modo_rede) on public.pdvs to authenticated;
-- Alvará, dados da Receita e motivo de rejeição não são públicos (o dono vê por meus_pdvs()).
revoke select on public.pdvs from authenticated;
grant select (id, dono_id, cnpj, razao_social, nome_fantasia, site, modo_rede, status, created_at, updated_at)
  on public.pdvs to authenticated;

-- -----------------------------------------------------------------------------
-- Dígitos verificadores do CNPJ (mesma regra do app: core/Cnpj.kt)
-- -----------------------------------------------------------------------------
create function public._cnpj_valido(p_cnpj text)
returns boolean
language plpgsql
immutable
set search_path = ''
as $$
declare
  v_pesos1 constant int[] := array[5,4,3,2,9,8,7,6,5,4,3,2];
  v_pesos2 constant int[] := array[6,5,4,3,2,9,8,7,6,5,4,3,2];
  v_soma int;
  v_dv1 int;
  v_dv2 int;
begin
  if p_cnpj is null or p_cnpj !~ '^[0-9]{14}$' or p_cnpj ~ '^(.)\1{13}$' then
    return false;
  end if;
  v_soma := 0;
  for i in 1..12 loop v_soma := v_soma + substr(p_cnpj, i, 1)::int * v_pesos1[i]; end loop;
  v_dv1 := case when v_soma % 11 < 2 then 0 else 11 - v_soma % 11 end;
  v_soma := 0;
  for i in 1..13 loop v_soma := v_soma + substr(p_cnpj, i, 1)::int * v_pesos2[i]; end loop;
  v_dv2 := case when v_soma % 11 < 2 then 0 else 11 - v_soma % 11 end;
  return substr(p_cnpj, 13, 1)::int = v_dv1 and substr(p_cnpj, 14, 1)::int = v_dv2;
end;
$$;

-- -----------------------------------------------------------------------------
-- Cadastro (conta CNPJ)
-- -----------------------------------------------------------------------------
create function public.cadastrar_pdv(
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
    if v_existente.status = 'aprovado' then
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

-- Os PDVs do usuário logado, com a situação do cadastro.
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
  verificado_em             timestamptz
)
language sql
stable
security definer
set search_path = ''
as $$
  select p.id, p.cnpj::text, p.razao_social, p.nome_fantasia, p.status, p.motivo_rejeicao,
         p.cnpj_conferido_no_alvara, p.created_at, p.verificado_em
  from public.pdvs p
  where p.dono_id = auth.uid()
  order by p.created_at
$$;

-- -----------------------------------------------------------------------------
-- Admin: fila de cadastros
-- -----------------------------------------------------------------------------
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
  created_at                timestamptz
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
           (select concat_ws(', ', l.endereco, l.bairro, l.cidade || ' - ' || l.uf)
              from public.lojas l where l.pdv_id = p.id order by l.created_at limit 1),
           (select l.telefone from public.lojas l where l.pdv_id = p.id order by l.created_at limit 1),
           p.alvara_path, p.cnpj_conferido_no_alvara, p.dados_receita, u.nome, u.email, p.created_at
    from public.pdvs p
    left join public.usuarios u on u.id = p.dono_id
    where p.status = 'pendente'
    order by p.created_at;
end;
$$;

create function public.aprovar_pdv(p_pdv_id uuid)
returns void
language plpgsql
security definer
set search_path = ''
as $$
begin
  if not public.is_admin() then
    raise exception 'sem_permissao';
  end if;
  update public.pdvs
     set status = 'aprovado', motivo_rejeicao = null, verificado_em = now(), verificado_por = auth.uid()
   where id = p_pdv_id and status = 'pendente';
  if not found then
    raise exception 'pdv_nao_encontrado';
  end if;
end;
$$;

-- Rejeita um pedido pendente, ou suspende um PDV já aprovado (fraude descoberta
-- depois): nesse caso os preços oficiais dele saem da busca.
create function public.rejeitar_pdv(p_pdv_id uuid, p_motivo text)
returns void
language plpgsql
security definer
set search_path = ''
as $$
begin
  if not public.is_admin() then
    raise exception 'sem_permissao';
  end if;
  if length(btrim(coalesce(p_motivo, ''))) = 0 then
    raise exception 'motivo_obrigatorio';
  end if;
  update public.pdvs
     set status = 'rejeitado', motivo_rejeicao = left(btrim(p_motivo), 300),
         verificado_em = now(), verificado_por = auth.uid()
   where id = p_pdv_id and status <> 'rejeitado';
  if not found then
    raise exception 'pdv_nao_encontrado';
  end if;
  delete from public.cotacoes c
   using public.lojas l
   where c.loja_id = l.id and l.pdv_id = p_pdv_id and c.fonte in ('pdv_manual', 'pdv_excel');
end;
$$;

-- -----------------------------------------------------------------------------
-- PDV não aprovado não publica preço nem promoção
-- -----------------------------------------------------------------------------
create function public._pdv_precisa_estar_aprovado()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_status public.status_verificacao;
begin
  if tg_table_name = 'cotacoes' then
    if new.fonte not in ('pdv_manual', 'pdv_excel') then
      return new;
    end if;
    select p.status into v_status
      from public.lojas l join public.pdvs p on p.id = l.pdv_id
     where l.id = new.loja_id;
  else
    select p.status into v_status from public.pdvs p where p.id = new.pdv_id;
  end if;
  if v_status is distinct from 'aprovado' then
    raise exception 'pdv_nao_verificado';
  end if;
  return new;
end;
$$;

create trigger cotacoes_pdv_aprovado
  before insert or update on public.cotacoes
  for each row execute function public._pdv_precisa_estar_aprovado();

create trigger promocoes_pdv_aprovado
  before insert on public.promocoes
  for each row execute function public._pdv_precisa_estar_aprovado();

-- Lojas de PDV não aprovado não aparecem para ligar notas fiscais.
create or replace function public.lojas_do_cnpj(p_cnpj text)
returns table (
  loja_id    uuid,
  pdv_nome   text,
  loja_nome  text,
  endereco   text
)
language sql
stable
security invoker
set search_path = ''
as $$
  select l.id, p.nome_fantasia, l.nome,
         concat_ws(', ', l.endereco, l.bairro, l.cidade || ' - ' || l.uf)
  from public.pdvs p
  join public.lojas l on l.pdv_id = p.id and l.ativa
  where p.cnpj = regexp_replace(coalesce(p_cnpj, ''), '\D', '', 'g')
    and p.status = 'aprovado'
  order by l.nome nulls first, l.endereco
$$;

create or replace function public.buscar_lojas(p_termo text, p_limite integer default 10)
returns table (
  loja_id    uuid,
  pdv_nome   text,
  loja_nome  text,
  endereco   text
)
language sql
stable
security invoker
set search_path = ''
as $$
  select l.id, p.nome_fantasia, l.nome,
         concat_ws(', ', l.endereco, l.bairro, l.cidade || ' - ' || l.uf)
  from public.lojas l
  join public.pdvs p on p.id = l.pdv_id
  where l.ativa
    and p.status = 'aprovado'
    and length(public.normalizar(p_termo)) >= 2
    and strpos(public.normalizar(p.nome_fantasia || ' ' || coalesce(l.nome, '') || ' ' || coalesce(l.bairro, '')),
               public.normalizar(p_termo)) > 0
  order by p.nome_fantasia, l.nome nulls first
  limit least(greatest(coalesce(p_limite, 10), 1), 30)
$$;

revoke execute on function public._cnpj_valido(text) from public, anon, authenticated;
revoke execute on function public._pdv_precisa_estar_aprovado() from public, anon, authenticated;
revoke execute on function public.cadastrar_pdv(text, text, text, text, text, text, text, text, text, text, boolean, jsonb)
  from public, anon;
revoke execute on function public.meus_pdvs() from public, anon;
revoke execute on function public.pdvs_pendentes() from public, anon;
revoke execute on function public.aprovar_pdv(uuid) from public, anon;
revoke execute on function public.rejeitar_pdv(uuid, text) from public, anon;
grant execute on function public.cadastrar_pdv(text, text, text, text, text, text, text, text, text, text, boolean, jsonb)
  to authenticated, service_role;
grant execute on function public.meus_pdvs() to authenticated, service_role;
grant execute on function public.pdvs_pendentes() to authenticated, service_role;
grant execute on function public.aprovar_pdv(uuid) to authenticated, service_role;
grant execute on function public.rejeitar_pdv(uuid, text) to authenticated, service_role;
grant execute on function public._cnpj_valido(text) to service_role;

-- -----------------------------------------------------------------------------
-- Fotos de alvará: alvaras/<id-do-usuário>/<arquivo>. Privado: o dono e o Admin.
-- -----------------------------------------------------------------------------
do $$
begin
  if not exists (select 1 from information_schema.schemata where schema_name = 'storage') then
    raise notice 'schema storage ausente — pulando bucket de alvarás (ambiente de teste local)';
    return;
  end if;

  insert into storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
  values ('alvaras', 'alvaras', false, 5242880, array['image/jpeg', 'image/png', 'image/webp'])
  on conflict (id) do nothing;

  execute $p$
    create policy alvaras_upload on storage.objects for insert to authenticated
      with check (bucket_id = 'alvaras' and (storage.foldername(name))[1] = auth.uid()::text)
  $p$;
  execute $p$
    create policy alvaras_leitura on storage.objects for select to authenticated
      using (bucket_id = 'alvaras'
             and ((storage.foldername(name))[1] = auth.uid()::text or public.is_admin()))
  $p$;
end;
$$;
