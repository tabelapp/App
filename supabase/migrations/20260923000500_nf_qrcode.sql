-- =============================================================================
-- Envio de NF pelo QR Code
--
-- A chave de acesso traz o CNPJ do emitente (posições 7 a 20). Com ele o app
-- descobre se a nota é de um PDV cadastrado e liga os preços à loja certa
-- (aparece em negrito/clicável na busca, com endereço e telefone da loja).
-- =============================================================================

-- Lojas ativas do PDV com esse CNPJ (0, 1 ou várias — rede com várias lojas).
create function public.lojas_do_cnpj(p_cnpj text)
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
  order by l.nome nulls first, l.endereco
$$;

grant execute on function public.lojas_do_cnpj(text) to authenticated;

-- Antifraude simples: se a nota tem chave E foi ligada a uma loja cadastrada,
-- o CNPJ da chave precisa ser o do PDV dessa loja.
create function public._nf_confere_loja()
returns trigger
language plpgsql
set search_path = ''
as $$
begin
  if new.fonte = 'usuario_nf' and new.chave_acesso_nf is not null and new.loja_id is not null
     and not exists (
       select 1 from public.lojas l join public.pdvs p on p.id = l.pdv_id
       where l.id = new.loja_id and p.cnpj = substring(new.chave_acesso_nf from 7 for 14)
     ) then
    raise exception 'loja_nao_confere';
  end if;
  return new;
end;
$$;

create trigger cotacoes_nf_confere_loja
  before insert on public.cotacoes
  for each row execute function public._nf_confere_loja();
