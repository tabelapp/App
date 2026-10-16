-- =============================================================================
-- Anunciante: mesmo tratamento do estabelecimento (decisão do fundador)
--
-- O comprovante do CNPJ (alvará, cartão CNPJ ou contrato social) passa a ser
-- obrigatório, e o cadastro pede o endereço da empresa. Esse endereço é só
-- para a análise do Admin: NÃO é ponto de venda (não vira loja, não aparece
-- na busca nem no mapa).
--
-- Idempotente: pode ser executada de novo sem erro e sem apagar dados.
-- =============================================================================

alter table public.pdvs add column if not exists endereco text;
alter table public.pdvs add column if not exists bairro text;
alter table public.pdvs add column if not exists cep text;

drop function if exists public.cadastrar_anunciante(text, text, text, text, text, text, text, text, jsonb);
drop function if exists public.cadastrar_anunciante(text, text, text, text, text, text, text, text, text, text, text, boolean, jsonb);
create function public.cadastrar_anunciante(
  p_cnpj            text,
  p_nome_fantasia   text,
  p_razao_social    text,
  p_endereco        text,
  p_bairro          text,
  p_cidade          text,
  p_uf              text,
  p_cep             text,
  p_telefone        text,
  p_site            text,
  p_documento_path  text,
  p_cnpj_no_documento boolean default false,
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
  if length(btrim(coalesce(p_cidade, ''))) = 0 then
    raise exception 'cidade_obrigatoria';
  end if;
  if length(regexp_replace(coalesce(p_telefone, ''), '\D', '', 'g')) < 10 then
    raise exception 'telefone_obrigatorio';
  end if;
  -- Comprovante do CNPJ: obrigatório, na pasta do próprio usuário.
  if p_documento_path is null or p_documento_path not like auth.uid()::text || '/%' then
    raise exception 'alvara_obrigatorio';
  end if;
  if to_regclass('storage.objects') is not null then
    execute 'select exists (select 1 from storage.objects where bucket_id = $1 and name = $2)'
      into v_tem_foto using 'alvaras', p_documento_path;
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
    delete from public.pdvs where id = v_existente.id;
  end if;

  insert into public.pdvs
    (dono_id, cnpj, razao_social, nome_fantasia, modo_rede, status, tipo,
     endereco, bairro, cidade, uf, cep, telefone, site,
     alvara_path, cnpj_conferido_no_alvara, dados_receita)
  values
    (auth.uid(), v_cnpj, nullif(btrim(p_razao_social), ''), left(btrim(p_nome_fantasia), 120), true, 'pendente',
     'anunciante',
     left(btrim(p_endereco), 200), nullif(btrim(p_bairro), ''), left(btrim(p_cidade), 80),
     nullif(left(upper(btrim(coalesce(p_uf, ''))), 2), ''), nullif(btrim(p_cep), ''),
     left(btrim(p_telefone), 20), nullif(btrim(p_site), ''),
     p_documento_path, coalesce(p_cnpj_no_documento, false), p_dados_receita)
  returning id into v_id;
  return v_id;
end;
$$;
revoke execute on function public.cadastrar_anunciante(text, text, text, text, text, text, text, text, text, text, text, boolean, jsonb)
  from public, anon;
grant execute on function public.cadastrar_anunciante(text, text, text, text, text, text, text, text, text, text, text, boolean, jsonb)
  to authenticated;

-- O Admin vê o endereço completo do anunciante (marcado como "não é ponto de venda" no app).
create or replace function public.pdvs_pendentes()
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
             nullif(concat_ws(', ', p.endereco, p.bairro, nullif(concat_ws(' - ', p.cidade, p.uf), '')), '')),
           coalesce((select l.telefone from public.lojas l where l.pdv_id = p.id order by l.created_at limit 1),
                    p.telefone),
           p.alvara_path, p.cnpj_conferido_no_alvara, p.dados_receita, u.nome, u.email, p.created_at, p.tipo
    from public.pdvs p
    left join public.usuarios u on u.id = p.dono_id
    where p.status = 'pendente'
    order by p.created_at;
end;
$$;
