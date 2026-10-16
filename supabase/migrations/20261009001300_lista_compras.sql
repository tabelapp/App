-- =============================================================================
-- Lista de compras (briefing, seção 3)
--
-- O usuário monta a lista no tempo dele (cada item é salvo na hora) e, quando
-- quiser, pede a pesquisa: o app busca o preço de cada item e monta os dois
-- relatórios (tudo num lugar só / item a item). As tabelas listas_compra e
-- lista_itens já existem, protegidas por RLS (cada um vê só as suas). Estas
-- funções são só atalhos para o app; rodam com as permissões de quem chama.
-- =============================================================================

create function public.limite_listas() returns integer language sql immutable as $$ select 50 $$;
create function public.limite_itens_lista() returns integer language sql immutable as $$ select 300 $$;

-- Listas do usuário, da mais recente para a mais antiga, com a quantidade de itens.
create function public.minhas_listas()
returns table (id uuid, nome text, itens integer, updated_at timestamptz)
language sql
stable
security invoker
set search_path = ''
as $$
  select l.id, l.nome, (select count(*)::integer from public.lista_itens i where i.lista_id = l.id), l.updated_at
  from public.listas_compra l
  where l.usuario_id = auth.uid()
  order by l.updated_at desc
$$;

create function public.criar_lista(p_nome text)
returns uuid
language plpgsql
security invoker
set search_path = ''
as $$
declare
  v_id uuid;
begin
  if auth.uid() is null then
    raise exception 'nao_autenticado';
  end if;
  if (select count(*) from public.listas_compra where usuario_id = auth.uid()) >= public.limite_listas() then
    raise exception 'listas_demais';
  end if;
  insert into public.listas_compra (usuario_id, nome)
  values (auth.uid(), coalesce(nullif(left(btrim(p_nome), 80), ''), 'Minha lista'))
  returning id into v_id;
  return v_id;
end;
$$;

create function public.renomear_lista(p_lista_id uuid, p_nome text)
returns void
language sql
security invoker
set search_path = ''
as $$
  update public.listas_compra
     set nome = coalesce(nullif(left(btrim(p_nome), 80), ''), nome)
   where id = p_lista_id
$$;

create function public.excluir_lista(p_lista_id uuid)
returns void
language sql
security invoker
set search_path = ''
as $$
  delete from public.listas_compra where id = p_lista_id
$$;

create function public.itens_da_lista(p_lista_id uuid)
returns table (id uuid, produto text, quantidade numeric)
language sql
stable
security invoker
set search_path = ''
as $$
  select i.id, i.produto, i.quantidade
  from public.lista_itens i
  where i.lista_id = p_lista_id
  order by i.created_at
$$;

-- Adiciona o produto; se já estiver na lista (mesmo nome), soma a quantidade.
create function public.adicionar_item_lista(p_lista_id uuid, p_produto text, p_quantidade numeric default 1)
returns uuid
language plpgsql
security invoker
set search_path = ''
as $$
declare
  v_produto text := left(btrim(regexp_replace(coalesce(p_produto, ''), '\s+', ' ', 'g')), 200);
  v_qtd     numeric := coalesce(p_quantidade, 1);
  v_id      uuid;
begin
  if not exists (select 1 from public.listas_compra where id = p_lista_id) then
    raise exception 'lista_nao_encontrada';  -- inexistente ou de outra pessoa (RLS)
  end if;
  if length(v_produto) = 0 then
    raise exception 'produto_vazio';
  end if;
  if v_qtd <= 0 or v_qtd > 9999 then
    raise exception 'quantidade_invalida';
  end if;

  select i.id into v_id from public.lista_itens i
   where i.lista_id = p_lista_id and public.normalizar(i.produto) = public.normalizar(v_produto);
  if v_id is not null then
    update public.lista_itens set quantidade = quantidade + v_qtd where id = v_id;
  else
    if (select count(*) from public.lista_itens where lista_id = p_lista_id) >= public.limite_itens_lista() then
      raise exception 'itens_demais';
    end if;
    insert into public.lista_itens (lista_id, produto, quantidade)
    values (p_lista_id, v_produto, v_qtd)
    returning id into v_id;
  end if;
  update public.listas_compra set updated_at = now() where id = p_lista_id;
  return v_id;
end;
$$;

create function public.alterar_quantidade_item(p_item_id uuid, p_quantidade numeric)
returns void
language plpgsql
security invoker
set search_path = ''
as $$
begin
  if p_quantidade is null or p_quantidade <= 0 or p_quantidade > 9999 then
    raise exception 'quantidade_invalida';
  end if;
  update public.lista_itens set quantidade = p_quantidade where id = p_item_id;
end;
$$;

create function public.remover_item_lista(p_item_id uuid)
returns void
language sql
security invoker
set search_path = ''
as $$
  delete from public.lista_itens where id = p_item_id
$$;

-- -----------------------------------------------------------------------------
-- Busca inteligente para montar a lista: produtos com preço válido que casam
-- com o que foi digitado (todas as palavras, sem acento), com o menor preço e
-- em quantos lugares aparecem. Mais encontrados primeiro.
-- -----------------------------------------------------------------------------
create function public.sugerir_produtos(p_termo text, p_limite integer default 8)
returns table (produto text, menor_preco_centavos integer, lugares integer)
language sql
stable
security invoker
set search_path = ''
as $$
  with palavras as (
    select array_remove(string_to_array(public.normalizar(p_termo), ' '), '') as ps
  )
  select min(c.produto),
         min(c.preco_centavos),
         count(distinct coalesce(c.loja_id::text, public.normalizar(c.pdv_nome_livre)))::integer
  from public.cotacoes c
  cross join palavras
  where c.validade >= public.hoje()
    and length(public.normalizar(p_termo)) >= 2
    and not exists (select 1 from unnest(palavras.ps) w where strpos(c.produto_busca, w) = 0)
  group by c.produto_busca
  order by 3 desc, 1
  limit least(greatest(coalesce(p_limite, 8), 1), 20)
$$;

revoke execute on function public.limite_listas(), public.limite_itens_lista(), public.minhas_listas(),
  public.criar_lista(text), public.renomear_lista(uuid, text), public.excluir_lista(uuid),
  public.itens_da_lista(uuid), public.adicionar_item_lista(uuid, text, numeric),
  public.alterar_quantidade_item(uuid, numeric), public.remover_item_lista(uuid),
  public.sugerir_produtos(text, integer)
  from public, anon;
grant execute on function public.limite_listas(), public.limite_itens_lista(), public.minhas_listas(),
  public.criar_lista(text), public.renomear_lista(uuid, text), public.excluir_lista(uuid),
  public.itens_da_lista(uuid), public.adicionar_item_lista(uuid, text, numeric),
  public.alterar_quantidade_item(uuid, numeric), public.remover_item_lista(uuid),
  public.sugerir_produtos(text, integer)
  to authenticated, service_role;
