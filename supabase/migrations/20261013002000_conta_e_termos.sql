-- =============================================================================
-- Minha conta (exigência da Play Store) e aceite dos termos
--
--  * excluir_minha_conta(): apaga a conta pelo app. Em cascata saem perfil,
--    listas, denúncias, interesses e — para conta de estabelecimento — o PDV,
--    as lojas e a tabela de preços. O histórico de preços fica, sem
--    identificar quem enviou (gatilho LGPD da migração 1400).
--  * atualizar_meu_nome(): editar o nome.
--  * registrar_aceite_termos(): versão dos Termos/Política aceita e quando.
--
-- Idempotente: pode ser executada de novo sem erro e sem apagar dados.
-- =============================================================================

alter table public.usuarios add column if not exists termos_versao text;
alter table public.usuarios add column if not exists termos_aceitos_em timestamptz;

create or replace function public.atualizar_meu_nome(p_nome text)
returns void
language plpgsql
security definer
set search_path = ''
as $$
begin
  if auth.uid() is null then
    raise exception 'nao_autenticado';
  end if;
  if length(btrim(coalesce(p_nome, ''))) not between 2 and 80 then
    raise exception 'nome_invalido';
  end if;
  update public.usuarios set nome = btrim(p_nome) where id = auth.uid();
end;
$$;

create or replace function public.registrar_aceite_termos(p_versao text)
returns void
language plpgsql
security definer
set search_path = ''
as $$
begin
  if auth.uid() is null then
    raise exception 'nao_autenticado';
  end if;
  update public.usuarios
     set termos_versao = left(btrim(p_versao), 20), termos_aceitos_em = now()
   where id = auth.uid();
end;
$$;

-- Ao excluir a conta de um PDV, o banco limpa cotacoes.enviado_por (on delete
-- set null) no meio da cascata, quando o PDV já está saindo: essa limpeza não
-- publica nada e não pode ser barrada pela regra "PDV precisa estar aprovado".
create or replace function public._pdv_precisa_estar_aprovado()
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
    -- Atualização que só mexe em quem enviou (ex.: conta excluída) não publica preço.
    if tg_op = 'UPDATE'
       and (new.loja_id, new.produto, new.preco_centavos, new.validade, new.obs, new.fonte,
            new.unidade, new.a_partir_de)
           is not distinct from
           (old.loja_id, old.produto, old.preco_centavos, old.validade, old.obs, old.fonte,
            old.unidade, old.a_partir_de) then
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

-- Excluir a própria conta. Sem volta: o app pede confirmação antes.
create or replace function public.excluir_minha_conta()
returns void
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_id uuid := auth.uid();
begin
  if v_id is null then
    raise exception 'nao_autenticado';
  end if;
  delete from auth.users where id = v_id;
end;
$$;

revoke execute on function
  public.atualizar_meu_nome(text), public.registrar_aceite_termos(text), public.excluir_minha_conta()
  from public, anon;
grant execute on function
  public.atualizar_meu_nome(text), public.registrar_aceite_termos(text), public.excluir_minha_conta()
  to authenticated, service_role;
