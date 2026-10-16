-- =============================================================================
-- Reforços de segurança (revisão de outubro/2026)
-- =============================================================================

-- 1. Banner pago: cada pessoa conta no máximo 1 visualização por banner por dia.
--    Antes, qualquer usuário podia chamar registrar_visualizacao_promocao em
--    sequência e "queimar" as visualizações compradas por um concorrente.
create table public.promocao_visualizacoes (
  promocao_id  uuid not null references public.promocoes (id) on delete cascade,
  usuario_id   uuid not null references public.usuarios (id) on delete cascade,
  dia          date not null default public.hoje(),
  primary key (promocao_id, usuario_id, dia)
);
alter table public.promocao_visualizacoes enable row level security;
revoke all on public.promocao_visualizacoes from authenticated, anon;

create or replace function public.registrar_visualizacao_promocao(p_promocao_id uuid)
returns void
language plpgsql
security definer
set search_path = ''
as $$
begin
  if auth.uid() is null then
    return;
  end if;
  insert into public.promocao_visualizacoes (promocao_id, usuario_id)
  select p_promocao_id, auth.uid()
  where exists (select 1 from public.promocoes where id = p_promocao_id and status = 'ativa')
  on conflict do nothing;
  if not found then
    return;  -- já contada hoje para esta pessoa (ou banner fora do ar)
  end if;
  update public.promocoes
     set visualizacoes_exibidas = visualizacoes_exibidas + 1,
         status = case when visualizacoes_exibidas + 1 >= visualizacoes_contratadas
                       then 'esgotada'::public.status_promocao else status end
   where id = p_promocao_id
     and status = 'ativa'
     and visualizacoes_exibidas < visualizacoes_contratadas;
end;
$$;

-- 2. LGPD: quem exclui a conta deixa de ser identificável no histórico de preços
--    (o preço continua na história, sem dizer quem enviou).
create function public._anonimizar_historico_usuario()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
begin
  update public.historico_precos set enviado_por = null where enviado_por = old.id;
  return old;
end;
$$;

create trigger usuarios_anonimizar_historico
  before delete on public.usuarios
  for each row execute function public._anonimizar_historico_usuario();

revoke execute on function public._anonimizar_historico_usuario() from public, anon, authenticated;

-- 3. Nota fiscal: chave obrigatória e válida, só do RJ, e limite diário por pessoa.
--    O app sempre tem a chave (QR Code ou digitada); o servidor agora confere o
--    dígito verificador em vez de aceitar quaisquer 44 números.
create function public._chave_nfe_valida(p_chave text)
returns boolean
language plpgsql
immutable
set search_path = ''
as $$
declare
  v_soma integer := 0;
  v_peso integer := 2;
  v_dv   integer;
begin
  if p_chave is null or p_chave !~ '^[0-9]{44}$' then
    return false;
  end if;
  for i in reverse 43..1 loop
    v_soma := v_soma + substr(p_chave, i, 1)::integer * v_peso;
    v_peso := case when v_peso = 9 then 2 else v_peso + 1 end;
  end loop;
  v_dv := 11 - (v_soma % 11);
  if v_dv >= 10 then
    v_dv := 0;
  end if;
  return v_dv = substr(p_chave, 44, 1)::integer;
end;
$$;

create function public.limite_notas_por_dia() returns integer language sql immutable as $$ select 30 $$;

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
     fonte, chave_acesso_nf, enviado_por, lote_id)
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
         v_lote
  from jsonb_array_elements(p_itens) e;
  get diagnostics v_n = row_count;

  return jsonb_build_object('lote_id', v_lote, 'itens', v_n);
end;
$$;



revoke execute on function public._chave_nfe_valida(text) from public, anon, authenticated;
revoke execute on function public.limite_notas_por_dia() from public, anon;
grant execute on function public.limite_notas_por_dia() to authenticated, service_role;

-- 4. Quem enviou cada preço não fica visível para os outros usuários.
revoke select on public.cotacoes from authenticated;
grant select (id, loja_id, pdv_nome_livre, pdv_endereco_livre, produto, produto_busca, preco_centavos,
              validade, obs, fonte, chave_acesso_nf, encarte_id, lote_id, created_at, updated_at, data_nf)
  on public.cotacoes to authenticated;
