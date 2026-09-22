-- =============================================================================
-- Testes das regras de negócio e permissões. Roda depois das migrações + seed.
-- Qualquer falha interrompe o psql (ON_ERROR_STOP) com a mensagem do assert.
-- =============================================================================
\set ON_ERROR_STOP on
\set QUIET on
\o /dev/null

-- Helper: executa um SQL e exige que ele falhe com uma mensagem específica.
create function pg_temp.espera_erro(p_sql text, p_msg text) returns void
language plpgsql as $$
declare
  v_passou boolean := false;
  v_erro   text;
begin
  begin
    execute p_sql;
    v_passou := true;
  exception when others then
    get stacked diagnostics v_erro = pg_exception_detail;
    v_erro := sqlerrm || ' | ' || coalesce(v_erro, '');
  end;
  if v_passou then
    raise exception 'ESPERAVA ERRO "%" mas o comando passou: %', p_msg, p_sql;
  end if;
  if v_erro not like '%' || p_msg || '%' then
    raise exception 'ESPERAVA ERRO "%" mas veio "%" em: %', p_msg, v_erro, p_sql;
  end if;
end;
$$;
grant execute on function pg_temp.espera_erro(text, text) to anon, authenticated, service_role;

-- Usuários de teste
insert into auth.users (id, email, raw_user_meta_data) values
  ('a0000000-0000-4000-a000-00000000000a', 'ana@teste.invalid',   '{"nome": "Ana", "tipo": "cpf"}'),
  ('b0000000-0000-4000-a000-00000000000b', 'bruno@teste.invalid', '{"nome": "Bruno", "tipo": "cnpj"}'),
  ('c0000000-0000-4000-a000-00000000000c', 'carla@teste.invalid', '{"nome": "Carla", "tipo": "admin"}'),
  ('d0000000-0000-4000-a000-00000000000d', 'davi@teste.invalid',  '{"full_name": "Davi"}');

do $$ begin
  assert (select tipo from public.usuarios where id = 'a0000000-0000-4000-a000-00000000000a') = 'cpf';
  assert (select tipo from public.usuarios where id = 'b0000000-0000-4000-a000-00000000000b') = 'cnpj';
  assert (select tipo from public.usuarios where id = 'c0000000-0000-4000-a000-00000000000c') = 'cpf',
    'cadastro nunca pode criar admin';
  assert (select nome from public.usuarios where id = 'd0000000-0000-4000-a000-00000000000d') = 'Davi';
  -- Quem entrou pelo Google (sem tipo) precisa escolher CPF/CNPJ no app.
  assert (select cadastro_completo from public.usuarios where id = 'a0000000-0000-4000-a000-00000000000a');
  assert not (select cadastro_completo from public.usuarios where id = 'd0000000-0000-4000-a000-00000000000d');
end $$;

-- Carla vira admin pelo único caminho possível: direto no banco.
update public.usuarios set tipo = 'admin' where id = 'c0000000-0000-4000-a000-00000000000c';

\echo '== anon não vê nada'
set role anon;
select pg_temp.espera_erro('select * from public.cotacoes', 'permission denied');
select pg_temp.espera_erro('select * from public.buscar_cotacoes()', 'permission denied');
reset role;

\echo '== busca (usuário CPF)'
set role authenticated;
set request.jwt.claim.sub = 'a0000000-0000-4000-a000-00000000000a';

do $$
declare r record;
begin
  -- Sem termo: últimos preços, mais recentes primeiro.
  select count(*) as n, max(created_at) as mais_novo into r from public.buscar_cotacoes();
  assert r.n > 0, 'tela inicial não pode ficar vazia';
  assert (select created_at from public.buscar_cotacoes() limit 1) = r.mais_novo;

  -- Sem acento/maiúscula; todas as palavras precisam casar.
  assert (select count(*) from public.buscar_cotacoes('FEIJAO preto')) = 4;
  assert (select count(*) from public.buscar_cotacoes('feijão 5kg')) = 0;
  assert (select count(*) from public.buscar_cotacoes('arroz 5kg')) = 4;

  -- Mais barato primeiro; PDV não cadastrado vem com nome livre e sem loja.
  select * into r from public.buscar_cotacoes('feijao') limit 1;
  assert r.preco_centavos = 759 and r.pdv_nome = 'Mercadinho Alto da Serra' and r.loja_id is null;

  -- PDV cadastrado traz telefone e endereço da loja.
  select * into r from public.buscar_cotacoes('ovos') limit 1;
  assert r.pdv_nome = 'Hortifruti Bingen' and r.telefone = '(24) 2222-0004'
     and r.endereco like 'Rua Bingen, 800, Bingen, Petrópolis - RJ';

  -- Distância: do centro de Petrópolis até Itaipava ~ 13 km.
  select * into r from public.buscar_cotacoes('acucar', -22.5046, -43.1823)
  where pdv_nome = 'Empório Itaipava';
  assert r.distancia_km between 12 and 15, format('distancia=%s', r.distancia_km);
end $$;

\echo '== CPF não escreve preço direto nem vira admin'
select pg_temp.espera_erro(
  $q$insert into public.cotacoes (pdv_nome_livre, produto, preco_centavos, fonte)
     values ('X', 'Y', 100, 'usuario_nf')$q$, 'permission denied');
select pg_temp.espera_erro(
  $q$update public.usuarios set tipo = 'admin' where id = auth.uid()$q$, 'row-level security');
update public.usuarios set nome = 'Ana Maria', tipo = 'cnpj', cadastro_completo = true where id = auth.uid();
update public.usuarios set tipo = 'cpf' where id = auth.uid();
select pg_temp.espera_erro(
  $q$update public.usuarios set email = 'x@y' where id = auth.uid()$q$, 'permission denied');

\echo '== Nota Fiscal: vários produtos, uma chamada; chave repetida bloqueada'
do $$
declare v jsonb;
begin
  v := public.enviar_nota_fiscal(
    '3326 0911 1111 1100 0191 6500 1000 0099 9910 0009 9990',
    null, 'Padaria Koeler', 'Avenida Koeler, 10 - Centro',
    '[{"produto": "Pão Francês kg", "preco_centavos": 1690},
      {"produto": "Leite Integral 1L", "preco_centavos": 549},
      {"produto": "Manteiga 200g", "preco_centavos": 1299}]');
  assert (v ->> 'itens')::int = 3;
  -- Preço de NF vale 1 dia.
  assert (select bool_and(validade = public.hoje() + 1) from public.cotacoes
          where lote_id = (v ->> 'lote_id')::uuid);
  assert (select count(*) from public.cotacoes
          where lote_id = (v ->> 'lote_id')::uuid and fonte = 'usuario_nf'
            and chave_acesso_nf = '33260911111111000191650010000099991000099990') = 3;
  -- NF sem chave também é aceita (chave é opcional).
  v := public.enviar_nota_fiscal(null, '20000000-0000-4000-a000-000000000003', null, null,
    '[{"produto": "Detergente 500ml", "preco_centavos": 259}]');
  assert (v ->> 'itens')::int = 1;
end $$;
select pg_temp.espera_erro(
  $q$select public.enviar_nota_fiscal('33260911111111000191650010000099991000099990', null,
     'Padaria Koeler', null, '[{"produto": "Pão", "preco_centavos": 100}]')$q$, 'nf_ja_enviada');
select pg_temp.espera_erro(
  $q$select public.enviar_nota_fiscal('123', null, 'X', null,
     '[{"produto": "Pão", "preco_centavos": 100}]')$q$, 'chave_acesso_invalida');
select pg_temp.espera_erro(
  $q$select public.enviar_nota_fiscal(null, null, '  ', null,
     '[{"produto": "Pão", "preco_centavos": 100}]')$q$, 'pdv_obrigatorio');
select pg_temp.espera_erro(
  $q$select public.enviar_nota_fiscal(null, null, 'X', null, '[{"produto": "Pão", "preco_centavos": 0}]')$q$,
  'itens_invalidos');

\echo '== Encarte de usuário vai para a fila; CPF não aprova'
insert into public.encartes_pendentes (id, enviado_por, foto_path, pdv_nome, pdv_endereco, validade)
values ('e0000000-0000-4000-a000-000000000001', auth.uid(),
        'a0000000-0000-4000-a000-00000000000a/encarte1.jpg', 'Sacolão Cascatinha', 'Cascatinha',
        public.hoje() + 3),
       ('e0000000-0000-4000-a000-000000000002', auth.uid(),
        'a0000000-0000-4000-a000-00000000000a/encarte2.jpg', 'Loja Duvidosa', null, null),
       ('e0000000-0000-4000-a000-000000000003', auth.uid(),
        'a0000000-0000-4000-a000-00000000000a/encarte3.jpg', 'Feira do Bingen', null, null);
select pg_temp.espera_erro(
  $q$insert into public.encartes_pendentes (enviado_por, foto_path, pdv_nome, status)
     values (auth.uid(), 'x.jpg', 'X', 'aprovado')$q$, 'row-level security');
select pg_temp.espera_erro(
  $q$select public.aprovar_encarte('e0000000-0000-4000-a000-000000000001',
     '[{"produto": "Uva kg", "preco_centavos": 999}]')$q$, 'sem_permissao');

\echo '== PDV (CNPJ): cadastro, cota de operações'
set request.jwt.claim.sub = 'b0000000-0000-4000-a000-00000000000b';

insert into public.pdvs (id, dono_id, cnpj, nome_fantasia, modo_rede)
values ('10000000-0000-4000-b000-000000000001', auth.uid(), '55555555000191', 'Mercado do Bruno', false);
insert into public.lojas (id, pdv_id, endereco, bairro, telefone) values
  ('20000000-0000-4000-b000-000000000001', '10000000-0000-4000-b000-000000000001', 'Rua A, 1', 'Centro', '(24) 1111-1111'),
  ('20000000-0000-4000-b000-000000000002', '10000000-0000-4000-b000-000000000001', 'Rua B, 2', 'Retiro', '(24) 3333-3333');

-- Não consegue mexer em loja de outro PDV.
select pg_temp.espera_erro(
  $q$insert into public.lojas (pdv_id, endereco) values ('10000000-0000-4000-a000-000000000001', 'Invasão')$q$,
  'row-level security');

do $$
declare
  pdv  constant uuid := '10000000-0000-4000-b000-000000000001';
  loja1 constant uuid := '20000000-0000-4000-b000-000000000001';
  loja2 constant uuid := '20000000-0000-4000-b000-000000000002';
  v jsonb;
  itens jsonb;
begin
  -- Cada loja tem sua própria cota de 50 grátis.
  assert (select restantes from public.cota_status(pdv, loja1)) = 50;
  assert (select restantes from public.cota_status(pdv, loja2)) = 50;

  -- Criar item: conta 1.
  v := public.pdv_salvar_precos(pdv, loja1, '[{"produto": "Arroz 5kg", "preco_centavos": 2500}]');
  assert (v ->> 'criados')::int = 1 and (v ->> 'operacoes')::int = 1 and (v ->> 'restantes')::int = 49, v::text;

  -- Diminuir preço: grátis (o nome pode vir com outra grafia/acentuação).
  v := public.pdv_salvar_precos(pdv, loja1, '[{"produto": "ARROZ  5kg", "preco_centavos": 2400, "obs": "Oferta"}]');
  assert (v ->> 'diminuidos')::int = 1 and (v ->> 'operacoes')::int = 0, v::text;
  assert (select count(*) from public.cotacoes where loja_id = loja1) = 1, 'upsert não pode duplicar';
  assert (select obs from public.cotacoes where loja_id = loja1) = 'Oferta';

  -- Mesmo preço, só OBS: grátis.
  v := public.pdv_salvar_precos(pdv, loja1, '[{"produto": "Arroz 5kg", "preco_centavos": 2400}]');
  assert (v ->> 'inalterados')::int = 1 and (v ->> 'operacoes')::int = 0, v::text;

  -- Aumentar preço: conta 1.
  v := public.pdv_salvar_precos(pdv, loja1, '[{"produto": "Arroz 5kg", "preco_centavos": 2600}]');
  assert (v ->> 'aumentados')::int = 1 and (v ->> 'operacoes')::int = 1, v::text;

  -- Modo varejo: a outra loja tem tabela e cota independentes.
  v := public.pdv_salvar_precos(pdv, loja2, '[{"produto": "Arroz 5kg", "preco_centavos": 2000}]');
  assert (v ->> 'criados')::int = 1, v::text;
  assert (select preco_centavos from public.cotacoes where loja_id = loja1) = 2600;
  assert (select restantes from public.cota_status(pdv, loja1)) = 48;
  assert (select restantes from public.cota_status(pdv, loja2)) = 49;

  -- Excluir: grátis.
  perform public.pdv_excluir_item((select id from public.cotacoes where loja_id = loja2));
  assert (select count(*) from public.cotacoes where loja_id = loja2) = 0;
  assert (select restantes from public.cota_status(pdv, loja2)) = 49;

  -- Planilha com 60 itens novos na loja 1: simulação mostra que precisa de 1 pacote.
  select jsonb_agg(jsonb_build_object('produto', 'Produto ' || g, 'preco_centavos', 100 + g))
    into itens from generate_series(1, 60) g;
  v := public.pdv_salvar_precos(pdv, loja1, itens, 'pdv_excel', true);
  assert (v ->> 'simulacao')::boolean and not (v ->> 'cabe_na_cota')::boolean
     and (v ->> 'operacoes')::int = 60 and (v ->> 'restantes')::int = 48
     and (v ->> 'pacotes_necessarios')::int = 1, v::text;
  assert (select count(*) from public.cotacoes where loja_id = loja1) = 1, 'simulação não grava';

  -- A mesma planilha na loja 2 (que tem 49) também não cabe; loja sem cota não é aceita.
  v := public.pdv_salvar_precos(pdv, loja2, itens, 'pdv_excel', true);
  assert (v ->> 'restantes')::int = 49, v::text;
end $$;

select pg_temp.espera_erro(
  $q$select public.pdv_salvar_precos('10000000-0000-4000-b000-000000000001', '20000000-0000-4000-b000-000000000001',
       (select jsonb_agg(jsonb_build_object('produto', 'Produto ' || g, 'preco_centavos', 100 + g))
        from generate_series(1, 60) g), 'pdv_excel')$q$, 'cota_excedida');
select pg_temp.espera_erro(
  $q$select * from public.cota_status('10000000-0000-4000-b000-000000000001')$q$, 'loja_invalida');

-- Validações de linha (validade máx. 30 dias, duplicado, preço).
select pg_temp.espera_erro(
  format($q$select public.pdv_salvar_precos('10000000-0000-4000-b000-000000000001',
    '20000000-0000-4000-b000-000000000001',
    '[{"produto": "Feijão", "preco_centavos": 800, "validade": "%s"}]')$q$, public.hoje() + 31),
  'validade_maior_que_30_dias');
select pg_temp.espera_erro(
  $q$select public.pdv_salvar_precos('10000000-0000-4000-b000-000000000001',
    '20000000-0000-4000-b000-000000000001',
    '[{"produto": "Feijão", "preco_centavos": 800}, {"produto": "feijao", "preco_centavos": 900}]')$q$,
  'itens_invalidos');
select pg_temp.espera_erro(
  $q$select public.pdv_salvar_precos('10000000-0000-4000-b000-000000000001',
    '20000000-0000-4000-b000-000000000001', '[{"produto": "Feijão", "preco_centavos": -5}]')$q$,
  'itens_invalidos');

-- PDV não pode se dar pacote de graça.
select pg_temp.espera_erro(
  $q$insert into public.pagamentos (usuario_id, pdv_id, loja_id, tipo, quantidade, valor_centavos, status)
     values (auth.uid(), '10000000-0000-4000-b000-000000000001', '20000000-0000-4000-b000-000000000001',
             'pacote_operacoes', 50, 1000, 'pago')$q$, 'permission denied');

\echo '== Pix pago (+50 operações para a loja 1, válido 30 dias) libera a importação'
reset role;
set role service_role;
insert into public.pagamentos (id, usuario_id, pdv_id, loja_id, tipo, quantidade, valor_centavos)
values ('f0000000-0000-4000-a000-000000000001', 'b0000000-0000-4000-a000-00000000000b',
        '10000000-0000-4000-b000-000000000001', '20000000-0000-4000-b000-000000000001',
        'pacote_operacoes', 50, 1000);
select public.confirmar_pagamento('f0000000-0000-4000-a000-000000000001', 'mp-123');
select public.confirmar_pagamento('f0000000-0000-4000-a000-000000000001', 'mp-123'); -- idempotente
reset role;

do $$ begin
  assert (select valido_ate::date - pago_em::date from public.pagamentos
          where id = 'f0000000-0000-4000-a000-000000000001') = 30, 'pacote vale 30 dias';
end $$;

set role authenticated;
set request.jwt.claim.sub = 'b0000000-0000-4000-a000-00000000000b';
do $$
declare
  pdv  constant uuid := '10000000-0000-4000-b000-000000000001';
  loja1 constant uuid := '20000000-0000-4000-b000-000000000001';
  loja2 constant uuid := '20000000-0000-4000-b000-000000000002';
  r record;
  v jsonb;
begin
  select * into r from public.cota_status(pdv, loja1);
  assert r.saldo_pacotes = 50 and r.restantes = 98 and r.pacote_vence_em > now() + interval '29 days', r::text;
  -- O pacote é da loja 1: não ajuda a loja 2.
  assert (select restantes from public.cota_status(pdv, loja2)) = 49;

  v := public.pdv_salvar_precos(pdv, loja1,
        (select jsonb_agg(jsonb_build_object('produto', 'Produto ' || g, 'preco_centavos', 100 + g))
         from generate_series(1, 60) g), 'pdv_excel');
  assert (v ->> 'operacoes')::int = 60 and (v ->> 'restantes')::int = 38, v::text;

  -- Gastou as 48 grátis primeiro e depois 12 do pacote.
  select * into r from public.cota_status(pdv, loja1);
  assert r.gratis_usadas = 50 and r.saldo_pacotes = 38 and r.restantes = 38, r::text;
  assert (select count(*) from public.operacoes_log
          where pagamento_id = 'f0000000-0000-4000-a000-000000000001') = 12;
end $$;

\echo '== Pacote vencido (mais de 30 dias) não vale mais'
reset role;
update public.pagamentos set valido_ate = now() - interval '1 minute'
 where id = 'f0000000-0000-4000-a000-000000000001';
set role authenticated;
set request.jwt.claim.sub = 'b0000000-0000-4000-a000-00000000000b';
do $$ begin
  assert (select restantes from public.cota_status('10000000-0000-4000-b000-000000000001',
                                                   '20000000-0000-4000-b000-000000000001')) = 0;
end $$;
select pg_temp.espera_erro(
  $q$select public.pdv_salvar_precos('10000000-0000-4000-b000-000000000001',
     '20000000-0000-4000-b000-000000000001', '[{"produto": "Novo", "preco_centavos": 100}]')$q$,
  'cota_excedida');

\echo '== Modo rede: a rede tem uma cota só, cada alteração replicada conta uma vez'
update public.pdvs set modo_rede = true where id = '10000000-0000-4000-b000-000000000001';
do $$
declare
  pdv constant uuid := '10000000-0000-4000-b000-000000000001';
  v jsonb;
begin
  assert (select restantes from public.cota_status(pdv)) = 50;
  v := public.pdv_salvar_precos(pdv, null, '[{"produto": "Cerveja Lata", "preco_centavos": 350, "obs": "Gelada"}]');
  assert (v ->> 'operacoes')::int = 1, v::text;
  assert (select count(*) from public.cotacoes c join public.lojas l on l.id = c.loja_id
          where l.pdv_id = pdv and c.produto = 'Cerveja Lata') = 2;
  assert (select restantes from public.cota_status(pdv)) = 49;

  -- Excluir em modo rede some de todas as lojas.
  perform public.pdv_excluir_item((select c.id from public.cotacoes c
                                   where c.produto = 'Cerveja Lata' limit 1));
  assert (select count(*) from public.cotacoes where produto = 'Cerveja Lata') = 0;
  assert (select restantes from public.cota_status(pdv)) = 49;
end $$;

\echo '== Outro usuário não usa a cota/tabela do PDV alheio'
set request.jwt.claim.sub = 'a0000000-0000-4000-a000-00000000000a';
select pg_temp.espera_erro(
  $q$select public.pdv_salvar_precos('10000000-0000-4000-b000-000000000001', null,
     '[{"produto": "Invasão", "preco_centavos": 1}]')$q$, 'sem_permissao');
select pg_temp.espera_erro($q$select * from public.cota_status('10000000-0000-4000-b000-000000000001')$q$,
  'sem_permissao');
select pg_temp.espera_erro(
  $q$select public.pdv_excluir_item((select id from public.cotacoes where produto = 'Arroz 5kg'))$q$,
  'sem_permissao');
do $$ begin
  assert (select count(*) from public.operacoes_log) = 0, 'log de operações é privado';
  assert (select count(*) from public.encartes_pendentes) = 3, 'CPF vê só os próprios encartes';
end $$;

\echo '== Admin: fila de encartes'
set request.jwt.claim.sub = 'c0000000-0000-4000-a000-00000000000c';
do $$
declare n int;
begin
  assert (select count(*) from public.encartes_pendentes where status = 'pendente') = 3;
  -- Sem validade informada no item, usa a do encarte (digitada pelo usuário no envio);
  -- item com validade própria mantém a dele.
  n := public.aprovar_encarte('e0000000-0000-4000-a000-000000000001',
        format('[{"produto": "Uva Thompson kg", "preco_centavos": 1499},
                 {"produto": "Manga Palmer kg", "preco_centavos": 699, "validade": "%s"}]',
               public.hoje() + 1)::jsonb);
  assert n = 2;
  assert (select validade from public.cotacoes where produto = 'Uva Thompson kg') = public.hoje() + 3;
  assert (select validade from public.cotacoes where produto = 'Manga Palmer kg') = public.hoje() + 1;
  assert (select count(*) from public.buscar_cotacoes('uva thompson')
          where fonte = 'usuario_encarte' and pdv_nome = 'Sacolão Cascatinha') = 1;
  -- Admin informa a validade impressa no encarte na hora de aprovar.
  n := public.aprovar_encarte('e0000000-0000-4000-a000-000000000003',
        '[{"produto": "Morango bandeja", "preco_centavos": 800}]', public.hoje() + 6);
  assert (select validade from public.cotacoes where produto = 'Morango bandeja') = public.hoje() + 6;
  begin
    perform public.aprovar_encarte('e0000000-0000-4000-a000-000000000002',
      '[{"produto": "X", "preco_centavos": 100}]');
    raise exception 'devia exigir validade';
  exception when others then
    assert sqlerrm = 'validade_obrigatoria', sqlerrm;
  end;
  begin
    perform public.aprovar_encarte('e0000000-0000-4000-a000-000000000002',
      '[{"produto": "X", "preco_centavos": 100}]', public.hoje() - 1);
    raise exception 'devia recusar validade vencida';
  exception when others then
    assert sqlerrm = 'validade_passada', sqlerrm;
  end;
  perform public.rejeitar_encarte('e0000000-0000-4000-a000-000000000002', 'Foto ilegível');
  -- Some da lista de pendências assim que decidido.
  assert (select count(*) from public.encartes_pendentes where status = 'pendente') = 0;
end $$;
select pg_temp.espera_erro(
  $q$select public.aprovar_encarte('e0000000-0000-4000-a000-000000000002',
     '[{"produto": "X", "preco_centavos": 1}]')$q$, 'encarte_ja_revisado');

\echo '== Promoção paga: palavra-chave e raio'
set request.jwt.claim.sub = 'b0000000-0000-4000-a000-00000000000b';
insert into public.promocoes (id, pdv_id, loja_id, titulo, origem_arte, raio_km, palavras_chave)
values ('70000000-0000-4000-a000-000000000001', '10000000-0000-4000-b000-000000000001', null,
        'Semana da cerveja', 'encarte_pdv', null, '{cerveja}');
select pg_temp.espera_erro(
  $q$insert into public.promocoes (pdv_id, titulo, origem_arte, status, visualizacoes_contratadas)
     values ('10000000-0000-4000-b000-000000000001', 'Grátis', 'propria', 'ativa', 1000)$q$,
  'row-level security');
select pg_temp.espera_erro(
  $q$update public.promocoes set visualizacoes_contratadas = 9999$q$, 'permission denied');

reset role;
set role service_role;
insert into public.pagamentos (id, usuario_id, pdv_id, tipo, promocao_id, quantidade, valor_centavos)
values ('f0000000-0000-4000-a000-000000000002', 'b0000000-0000-4000-a000-00000000000b',
        '10000000-0000-4000-b000-000000000001', 'pacote_visualizacoes',
        '70000000-0000-4000-a000-000000000001', 100, 1000);
select pg_temp.espera_erro(
  $q$insert into public.pagamentos (usuario_id, pdv_id, tipo, promocao_id, quantidade, valor_centavos)
     values ('b0000000-0000-4000-a000-00000000000b', '10000000-0000-4000-b000-000000000001',
             'pacote_visualizacoes', '70000000-0000-4000-a000-000000000001', 1000, 1000)$q$,
  'pagamento_pacote_valido');
select public.confirmar_pagamento('f0000000-0000-4000-a000-000000000002', 'mp-456');
reset role;

set role authenticated;
set request.jwt.claim.sub = 'a0000000-0000-4000-a000-00000000000a';
do $$ begin
  assert (select count(*) from public.promocoes_para_busca('cerveja gelada')) = 1;
  assert (select count(*) from public.promocoes_para_busca('arroz')) = 0;
  perform public.registrar_visualizacao_promocao('70000000-0000-4000-a000-000000000001');
end $$;
reset role;
do $$ begin
  assert (select visualizacoes_exibidas from public.promocoes
          where id = '70000000-0000-4000-a000-000000000001') = 1;
end $$;

\echo 'OK — todos os testes do banco passaram'
