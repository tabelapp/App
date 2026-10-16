-- =============================================================================
-- Painel do PDV no app: lojas, tabela de preços e se o PDV está em modo rede.
-- As escritas continuam por pdv_salvar_precos / pdv_excluir_item (cota).
-- =============================================================================

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
  modo_rede                 boolean
)
language sql
stable
security definer
set search_path = ''
as $$
  select p.id, p.cnpj::text, p.razao_social, p.nome_fantasia, p.status, p.motivo_rejeicao,
         p.cnpj_conferido_no_alvara, p.created_at, p.verificado_em, p.modo_rede
  from public.pdvs p
  where p.dono_id = auth.uid()
  order by p.created_at
$$;

-- Lojas do PDV (só o dono).
create function public.minhas_lojas(p_pdv_id uuid)
returns table (
  id        uuid,
  nome      text,
  endereco  text,
  telefone  text
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
    select l.id, l.nome, concat_ws(', ', l.endereco, l.bairro, l.cidade || ' - ' || l.uf), l.telefone
    from public.lojas l
    where l.pdv_id = p_pdv_id and l.ativa
    order by l.created_at;
end;
$$;

-- Tabela de preços oficial do PDV. Modo rede: um item por produto (é o mesmo em
-- todas as lojas). Modo varejo: os itens da loja informada.
create function public.meus_precos(p_pdv_id uuid, p_loja_id uuid default null)
returns table (
  id              uuid,
  loja_id         uuid,
  produto         text,
  preco_centavos  integer,
  validade        date,
  obs             text,
  updated_at      timestamptz
)
language plpgsql
stable
security definer
set search_path = ''
as $$
declare
  v_rede boolean;
begin
  select p.modo_rede into v_rede from public.pdvs p where p.id = p_pdv_id and p.dono_id = auth.uid();
  if not found then
    raise exception 'sem_permissao';
  end if;
  return query
    select distinct on (c.produto_busca)
           c.id, c.loja_id, c.produto, c.preco_centavos, c.validade, c.obs, c.updated_at
    from public.cotacoes c
    join public.lojas l on l.id = c.loja_id
    where l.pdv_id = p_pdv_id
      and c.fonte in ('pdv_manual', 'pdv_excel')
      and (v_rede or p_loja_id is null or c.loja_id = p_loja_id)
    order by c.produto_busca, c.preco_centavos desc;
end;
$$;

revoke execute on function public.meus_pdvs() from public, anon;
revoke execute on function public.minhas_lojas(uuid) from public, anon;
revoke execute on function public.meus_precos(uuid, uuid) from public, anon;
grant execute on function public.meus_pdvs() to authenticated, service_role;
grant execute on function public.minhas_lojas(uuid) to authenticated, service_role;
grant execute on function public.meus_precos(uuid, uuid) to authenticated, service_role;
