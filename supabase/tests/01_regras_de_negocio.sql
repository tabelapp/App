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

-- Chave de acesso de teste emitida no mês de p_data (AAMM precisa bater com a data da NF).
-- Chave de NFC-e do RJ com dígito verificador correto (módulo 11).
create function pg_temp.chave(p_cnpj text, p_numero int, p_data date default null, p_uf text default '33')
returns text
language plpgsql as $$
declare
  v_base text := p_uf || to_char(coalesce(p_data, public.hoje()), 'YYMM') || p_cnpj || '65001'
                 || lpad(p_numero::text, 9, '0') || '1' || '00000000';
  v_soma integer := 0;
  v_peso integer := 2;
  v_dv integer;
begin
  for i in reverse 43..1 loop
    v_soma := v_soma + substr(v_base, i, 1)::integer * v_peso;
    v_peso := case when v_peso = 9 then 2 else v_peso + 1 end;
  end loop;
  v_dv := 11 - (v_soma % 11);
  return v_base || case when v_dv >= 10 then 0 else v_dv end;
end;
$$;
grant execute on function pg_temp.chave(text, int, date, text) to anon, authenticated, service_role;

-- Usuários de teste
insert into auth.users (id, email, raw_user_meta_data) values
  ('a0000000-0000-4000-a000-00000000000a', 'ana@teste.invalid',   '{"nome": "Ana", "tipo": "cpf"}'),
  ('b0000000-0000-4000-a000-00000000000b', 'bruno@teste.invalid', '{"nome": "Bruno", "tipo": "cnpj"}'),
  ('c0000000-0000-4000-a000-00000000000c', 'carla@teste.invalid', '{"nome": "Carla", "tipo": "admin"}'),
  ('d0000000-0000-4000-a000-00000000000d', 'davi@teste.invalid',  '{"full_name": "Davi"}'),
  ('e0000000-0000-4000-a000-00000000000e', 'edu@teste.invalid',   '{"nome": "Edu", "tipo": "cnpj"}');

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
    pg_temp.chave('11111111000191', 9999),
    null, 'Padaria Koeler', 'Avenida Koeler, 10 - Centro',
    '[{"produto": "Pão Francês kg", "preco_centavos": 1690},
      {"produto": "Leite Integral 1L", "preco_centavos": 549},
      {"produto": "Manteiga 200g", "preco_centavos": 1299}]');
  assert (v ->> 'itens')::int = 3;
  -- Sem data informada, a NF é de hoje; o preço fica na busca por 7 dias.
  assert (select bool_and(data_nf = public.hoje() and validade = public.hoje() + 7) from public.cotacoes
          where lote_id = (v ->> 'lote_id')::uuid);
  assert (select bool_and(data_nf = public.hoje()) from public.buscar_cotacoes('manteiga 200g')
          where fonte = 'usuario_nf');
  -- Estabelecimento não cadastrado: o card mostra o endereço lido da nota.
  assert (select endereco from public.buscar_cotacoes('manteiga 200g') where fonte = 'usuario_nf')
         = 'Avenida Koeler, 10 - Centro', 'endereço da nota na busca';
  assert (select count(*) from public.cotacoes
          where lote_id = (v ->> 'lote_id')::uuid and fonte = 'usuario_nf'
            and chave_acesso_nf = pg_temp.chave('11111111000191', 9999)) = 3;
  -- NF ligada a uma loja cadastrada (do mesmo CNPJ da chave).
  v := public.enviar_nota_fiscal(pg_temp.chave('22222222000191', 5555), '20000000-0000-4000-a000-000000000003', null, null,
    '[{"produto": "Detergente 500ml", "preco_centavos": 259}]');
  assert (v ->> 'itens')::int = 1;
end $$;
select pg_temp.espera_erro(
  $q$select public.enviar_nota_fiscal(pg_temp.chave('11111111000191', 9999), null,
     'Padaria Koeler', null, '[{"produto": "Pão", "preco_centavos": 100}]')$q$, 'nf_ja_enviada');

\echo '== NF: data da compra (validade = data da NF + 7 dias)'
do $$
declare v jsonb;
begin
  -- NF de 7 dias atrás ainda entra e fica visível até hoje.
  v := public.enviar_nota_fiscal(pg_temp.chave('11111111000191', 7001, public.hoje() - 7), null,
         'Padaria Koeler', null, '[{"produto": "Sonho recheado", "preco_centavos": 650}]', public.hoje() - 7);
  assert (select validade from public.cotacoes where produto = 'Sonho recheado') = public.hoje();
  assert (select count(*) from public.buscar_cotacoes('sonho recheado')) = 1;
end $$;
select pg_temp.espera_erro(
  $q$select public.enviar_nota_fiscal(pg_temp.chave('11111111000191', 7101, public.hoje() - 8), null, 'X', null,
     '[{"produto": "Pão", "preco_centavos": 100}]', public.hoje() - 8)$q$, 'nf_antiga');
select pg_temp.espera_erro(
  $q$select public.enviar_nota_fiscal(pg_temp.chave('11111111000191', 7102, public.hoje() + 1), null, 'X', null,
     '[{"produto": "Pão", "preco_centavos": 100}]', public.hoje() + 1)$q$, 'data_nf_futura');
-- Chave emitida em outro mês que não o da data informada.
select pg_temp.espera_erro(
  $q$select public.enviar_nota_fiscal(pg_temp.chave('11111111000191', 7002, public.hoje() - 40), null, 'X', null,
     '[{"produto": "Pão", "preco_centavos": 100}]')$q$, 'data_nao_confere');
-- Preço de NF sempre tem data_nf; os demais nunca.
do $$ begin
  assert not exists (select 1 from public.cotacoes where (fonte = 'usuario_nf') <> (data_nf is not null));
end $$;
\echo '== NF pelo QR Code: CNPJ da chave encontra a loja cadastrada'
do $$
declare v jsonb;
begin
  -- 22222222000191 = Mercado Quitandinha (seed), uma loja.
  assert (select count(*) from public.lojas_do_cnpj('22.222.222/0001-91')) = 1;
  assert (select pdv_nome from public.lojas_do_cnpj('22222222000191')) = 'Mercado Quitandinha';
  -- Rede com duas lojas: o app pergunta em qual foi a compra.
  assert (select count(*) from public.lojas_do_cnpj('11111111000191')) = 2;
  assert (select count(*) from public.lojas_do_cnpj('99999999000191')) = 0;

  v := public.enviar_nota_fiscal(pg_temp.chave('22222222000191', 7777),
         '20000000-0000-4000-a000-000000000003', null, null,
         '[{"produto": "Café 500g", "preco_centavos": 1799}]');
  assert (v ->> 'itens')::int = 1;
  assert (select count(*) from public.buscar_cotacoes('cafe 500g')
          where fonte = 'usuario_nf' and pdv_nome = 'Mercado Quitandinha' and telefone is not null) = 1;
end $$;
-- Chave de um CNPJ, loja de outro: recusado.
select pg_temp.espera_erro(
  $q$select public.enviar_nota_fiscal(pg_temp.chave('22222222000191', 8888),
     '20000000-0000-4000-a000-000000000004', null, null,
     '[{"produto": "Pão", "preco_centavos": 100}]')$q$, 'loja_nao_confere');
select pg_temp.espera_erro(
  $q$select public.enviar_nota_fiscal('123', null, 'X', null,
     '[{"produto": "Pão", "preco_centavos": 100}]')$q$, 'chave_acesso_invalida');
select pg_temp.espera_erro(
  $q$select public.enviar_nota_fiscal(pg_temp.chave('11111111000191', 7103), null, '  ', null,
     '[{"produto": "Pão", "preco_centavos": 100}]')$q$, 'pdv_obrigatorio');
select pg_temp.espera_erro(
  $q$select public.enviar_nota_fiscal(pg_temp.chave('11111111000191', 7104), null, 'X', null,
     '[{"produto": "Pão", "preco_centavos": 0}]')$q$, 'itens_invalidos');
-- Chave obrigatória e de verdade (dígito verificador), só do RJ.
select pg_temp.espera_erro(
  $q$select public.enviar_nota_fiscal(null, null, 'X', null, '[{"produto": "Pão", "preco_centavos": 100}]')$q$,
  'chave_obrigatoria');
select pg_temp.espera_erro(
  $q$select public.enviar_nota_fiscal(left(pg_temp.chave('11111111000191', 7105), 43) ||
       ((right(pg_temp.chave('11111111000191', 7105), 1)::int + 1) % 10)::text,
     null, 'X', null, '[{"produto": "Pão", "preco_centavos": 100}]')$q$, 'chave_acesso_invalida');
select pg_temp.espera_erro(
  $q$select public.enviar_nota_fiscal(pg_temp.chave('11111111000191', 7106, null, '35'), null, 'X', null,
     '[{"produto": "Pão", "preco_centavos": 100}]')$q$, 'nf_fora_do_rj');

\echo '== Nome do PDV da NF: sugestão só vale confirmada (2 pessoas ou Admin)'
do $$
declare v jsonb;
begin
  v := public.enviar_nota_fiscal(pg_temp.chave('77777777000191', 4321), null, 'COMERCIO DE ALIMENTOS XYZ LTDA',
         'Rua Teresa, 100', '[{"produto": "Queijo minas kg", "preco_centavos": 3990}]');
  assert public.sugerir_nome_pdv('77.777.777/0001-91', 'Empório da Teresa') = 'aguardando';
  -- Ainda não confirmado: a busca mostra a razão social da nota.
  assert (select pdv_nome from public.buscar_cotacoes('queijo minas')) = 'COMERCIO DE ALIMENTOS XYZ LTDA';
end $$;
set request.jwt.claim.sub = 'd0000000-0000-4000-a000-00000000000d';
-- Quem nunca enviou NF desse CNPJ não sugere nome (evita concorrente "batizar" a loja dos outros).
select pg_temp.espera_erro($q$select public.sugerir_nome_pdv('77777777000191', 'Loja Ruim')$q$, 'sem_permissao');
do $$
declare v jsonb;
begin
  v := public.enviar_nota_fiscal(pg_temp.chave('77777777000191', 4322), null, 'COMERCIO DE ALIMENTOS XYZ LTDA',
         null, '[{"produto": "Requeijão 200g", "preco_centavos": 899}]');
  -- Segunda pessoa, mesmo nome (sem acento/maiúscula não importa): confirmado.
  assert public.sugerir_nome_pdv('77777777000191', 'EMPORIO DA TERESA') = 'confirmado';
  assert (select pdv_nome from public.buscar_cotacoes('queijo minas')) = 'EMPORIO DA TERESA';
  -- Depois de confirmado, outra sugestão não muda o nome.
  assert public.sugerir_nome_pdv('77777777000191', 'Outro nome') = 'confirmado';
  assert (select nome from public.nomes_pdv where cnpj = '77777777000191') = 'EMPORIO DA TERESA';
end $$;
-- Ninguém grava nome direto na tabela.
select pg_temp.espera_erro(
  $q$insert into public.nomes_pdv (cnpj, nome, origem) values ('88888888000191', 'X', 'admin')$q$, 'permission denied');
select pg_temp.espera_erro($q$select * from public.nomes_sugeridos_pendentes()$q$, 'sem_permissao');
set request.jwt.claim.sub = 'a0000000-0000-4000-a000-00000000000a';

\echo '== Lista de compras: cada um com as suas; busca inteligente'
do $$
declare l uuid; i uuid;
begin
  l := public.criar_lista('  Compras do mês  ');
  assert (select nome from public.minhas_listas() where id = l) = 'Compras do mês';
  i := public.adicionar_item_lista(l, 'Arroz  5kg', 2);
  -- O mesmo produto de novo (outra grafia) soma a quantidade.
  assert public.adicionar_item_lista(l, 'arroz 5KG', 1) = i;
  assert (select quantidade from public.itens_da_lista(l) where id = i) = 3;
  perform public.adicionar_item_lista(l, 'Feijão preto');
  assert (select itens from public.minhas_listas() where id = l) = 2;
  perform public.alterar_quantidade_item(i, 1.5);
  perform public.remover_item_lista((select id from public.itens_da_lista(l) where produto = 'Feijão preto'));
  assert (select count(*) from public.itens_da_lista(l)) = 1;
  perform public.renomear_lista(l, 'Feira');
  assert (select nome from public.minhas_listas() where id = l) = 'Feira';
  -- Busca inteligente: produtos com preço válido, sem acento, todas as palavras.
  assert exists (select 1 from public.sugerir_produtos('feijao') where lugares >= 1 and menor_preco_centavos > 0);
  assert not exists (select 1 from public.sugerir_produtos('x'));
end $$;
select pg_temp.espera_erro($q$select public.adicionar_item_lista((select id from public.minhas_listas() limit 1), ' ')$q$,
  'produto_vazio');
select pg_temp.espera_erro(
  $q$select public.alterar_quantidade_item((select i.id from public.itens_da_lista((select id from public.minhas_listas() limit 1)) i limit 1), 0)$q$,
  'quantidade_invalida');
-- Outra pessoa não vê nem mexe na lista.
set request.jwt.claim.sub = 'd0000000-0000-4000-a000-00000000000d';
do $$
declare l uuid;
begin
  reset role;
  select id into l from public.listas_compra where nome = 'Feira';
  set role authenticated;
  assert (select count(*) from public.minhas_listas() where id = l) = 0;
  assert (select count(*) from public.itens_da_lista(l)) = 0;
  begin
    perform public.adicionar_item_lista(l, 'Invasão');
    raise exception 'devia recusar';
  exception when others then
    assert sqlerrm like '%lista_nao_encontrada%', sqlerrm;
  end;
  perform public.excluir_lista(l);  -- não apaga a lista dos outros
  reset role;
  assert exists (select 1 from public.listas_compra where id = l);
  set role authenticated;
end $$;
set request.jwt.claim.sub = 'a0000000-0000-4000-a000-00000000000a';

\echo '== Histórico de preços: nada se perde'
reset role;
do $$ begin
  -- Toda cotação (inclusive as do seed) tem pelo menos o registro de criação.
  assert not exists (
    select 1 from public.cotacoes c
    where not exists (select 1 from public.historico_precos h where h.cotacao_id = c.id and h.operacao = 'criado')
  );
  assert (select cnpj from public.historico_precos where produto = 'Queijo minas kg') = '77777777000191';
end $$;
set role authenticated;
-- Usuário comum não lê nem apaga o histórico.
do $$ begin
  assert (select count(*) from public.historico_precos) = 0, 'CPF não vê o histórico';
end $$;
select pg_temp.espera_erro($q$delete from public.historico_precos$q$, 'permission denied');

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

\echo '== Envio de encarte suspenso: usuário não publica encarte'
set request.jwt.claim.sub = 'd0000000-0000-4000-a000-00000000000d';
do $$
begin
  -- Busca de lojas cadastradas por nome/bairro, sem acento.
  assert (select count(*) from public.buscar_lojas('quitandinha')) = 1;
  assert (select count(*) from public.buscar_lojas('serra imperial')) = 2;
  assert (select count(*) from public.buscar_lojas('x')) = 0, 'termo curto demais';
end $$;
select pg_temp.espera_erro(
  $q$select public.publicar_encarte(array[auth.uid() || '/a.jpg'], null, 'X', null, public.hoje(),
     '[{"produto": "Uva", "preco_centavos": 100}]')$q$, 'permission denied');
select pg_temp.espera_erro(
  $q$select public.enviar_encarte(array[auth.uid() || '/x.jpg'], null, 'X', null)$q$, 'does not exist');
set request.jwt.claim.sub = 'a0000000-0000-4000-a000-00000000000a';

\echo '== Cadastro de PDV: CNPJ válido, alvará, fila do Admin'
set request.jwt.claim.sub = 'b0000000-0000-4000-a000-00000000000b';
-- Ninguém cria PDV direto na tabela (só pela função, que exige o alvará).
select pg_temp.espera_erro(
  $q$insert into public.pdvs (dono_id, cnpj, nome_fantasia) values (auth.uid(), '66666666000191', 'X')$q$,
  'permission denied');
select pg_temp.espera_erro(
  $q$select public.cadastrar_pdv('66666666000192', 'X', null, 'Rua X', null, null, null, null, null,
     auth.uid() || '/alvara.jpg')$q$, 'cnpj_invalido');
select pg_temp.espera_erro(
  $q$select public.cadastrar_pdv('66666666000191', 'X', null, 'Rua X', null, null, null, null, null,
     'outra-pessoa/alvara.jpg')$q$, 'alvara_obrigatorio');
select pg_temp.espera_erro(
  $q$select public.cadastrar_pdv('11111111000191', 'Serra', null, 'Rua X', null, null, null, null, null,
     auth.uid() || '/alvara.jpg')$q$, 'cnpj_ja_cadastrado');
do $$
declare v uuid;
begin
  v := public.cadastrar_pdv('66.666.666/0001-91', 'Padaria do Bruno', 'Bruno Pães LTDA', 'Rua do Imperador, 10',
         'Centro', 'Petrópolis', 'rj', '25620-000', '(24) 2222-9999', auth.uid() || '/alvara.jpg', true,
         '{"situacao": "ATIVA"}');
  assert (select status = 'pendente' and cnpj_conferido_no_alvara from public.meus_pdvs() where id = v);
  assert (select uf = 'RJ' and bairro = 'Centro' from public.lojas where pdv_id = v);
  -- Pendente: não publica preço e não aparece para ligar NF.
  begin
    perform public.pdv_salvar_precos(v, (select id from public.lojas where pdv_id = v),
      '[{"produto": "Pão francês kg", "preco_centavos": 1590}]');
    raise exception 'devia recusar';
  exception when others then
    assert sqlerrm like '%pdv_nao_verificado%', sqlerrm;
  end;
  assert (select count(*) from public.lojas_do_cnpj('66666666000191')) = 0;
  -- Dados de verificação não são públicos.
  begin
    perform alvara_path from public.pdvs where id = v;
    raise exception 'devia recusar';
  exception when insufficient_privilege then null;
  end;
end $$;
-- Outra pessoa não "toma" um CNPJ em análise.
set request.jwt.claim.sub = 'e0000000-0000-4000-a000-00000000000e';
select pg_temp.espera_erro(
  $q$select public.cadastrar_pdv('66666666000191', 'Golpe', null, 'Rua Y', null, null, null, null, null,
     auth.uid() || '/alvara.jpg')$q$, 'cnpj_em_analise');
select pg_temp.espera_erro($q$select * from public.pdvs_pendentes()$q$, 'sem_permissao');
select pg_temp.espera_erro(
  $q$select public.aprovar_pdv((select id from public.pdvs where cnpj = '66666666000191'))$q$, 'sem_permissao');
-- Conta CPF não cadastra PDV.
set request.jwt.claim.sub = 'a0000000-0000-4000-a000-00000000000a';
select pg_temp.espera_erro(
  $q$select public.cadastrar_pdv('77777777000191', 'X', null, 'Rua X', null, null, null, null, null,
     auth.uid() || '/alvara.jpg')$q$, 'conta_nao_cnpj');

-- Admin vê a fila, rejeita (com motivo) e, no novo pedido, aprova.
set request.jwt.claim.sub = 'c0000000-0000-4000-a000-00000000000c';
do $$
declare v uuid;
begin
  select id into v from public.pdvs_pendentes() where cnpj = '66666666000191';
  assert (select dono_nome = 'Bruno' and alvara_path like '%/alvara.jpg' and dados_receita ->> 'situacao' = 'ATIVA'
          from public.pdvs_pendentes() where id = v);
  begin
    perform public.rejeitar_pdv(v, '  ');
    raise exception 'devia recusar';
  exception when others then
    assert sqlerrm like '%motivo_obrigatorio%', sqlerrm;
  end;
  perform public.rejeitar_pdv(v, 'Alvará ilegível');
end $$;
set request.jwt.claim.sub = 'b0000000-0000-4000-a000-00000000000b';
do $$
declare v uuid;
begin
  assert (select status = 'rejeitado' and motivo_rejeicao = 'Alvará ilegível' from public.meus_pdvs());
  -- Envia de novo (nova foto): volta para a fila.
  v := public.cadastrar_pdv('66666666000191', 'Padaria do Bruno', null, 'Rua do Imperador, 10',
         null, null, null, null, null, auth.uid() || '/alvara2.jpg');
  assert (select count(*) from public.meus_pdvs()) = 1;
  assert (select status from public.meus_pdvs()) = 'pendente';
end $$;
set request.jwt.claim.sub = 'c0000000-0000-4000-a000-00000000000c';
select public.aprovar_pdv((select id from public.pdvs where cnpj = '66666666000191'));
set request.jwt.claim.sub = 'b0000000-0000-4000-a000-00000000000b';
do $$
declare v uuid := (select id from public.pdvs where cnpj = '66666666000191');
begin
  assert (select status from public.meus_pdvs() where id = v) = 'aprovado';
  assert (select count(*) from public.lojas_do_cnpj('66666666000191')) = 1;
  -- O dono muda o nome de exibição, mas não o CNPJ nem o status.
  update public.pdvs set nome_fantasia = 'Padaria Imperial' where id = v;
  begin
    update public.pdvs set status = 'aprovado' where id = v;
    raise exception 'devia recusar';
  exception when insufficient_privilege then null;
  end;
  perform public.pdv_salvar_precos(v, (select id from public.lojas where pdv_id = v),
    '[{"produto": "Pão francês kg", "preco_centavos": 1590}]');
  -- Painel: lojas, tabela de preços e modo rede.
  assert (select modo_rede from public.meus_pdvs() where id = v);
  assert (select count(*) from public.minhas_lojas(v)) = 1;
  assert (select preco_centavos from public.meus_precos(v) where produto = 'Pão francês kg') = 1590;
end $$;
select pg_temp.espera_erro(
  $q$select * from public.meus_precos('10000000-0000-4000-a000-000000000001')$q$, 'sem_permissao');
select pg_temp.espera_erro(
  $q$select * from public.minhas_lojas('10000000-0000-4000-a000-000000000001')$q$, 'sem_permissao');
-- Fraude descoberta depois: Admin suspende e os preços oficiais saem da busca.
set request.jwt.claim.sub = 'c0000000-0000-4000-a000-00000000000c';
do $$
declare v uuid := (select id from public.pdvs where cnpj = '66666666000191');
begin
  perform public.rejeitar_pdv(v, 'Denúncia confirmada');
  assert (select count(*) from public.cotacoes c join public.lojas l on l.id = c.loja_id where l.pdv_id = v) = 0;
end $$;

\echo '== PDV (CNPJ): limite por tamanho do catálogo'
set request.jwt.claim.sub = 'b0000000-0000-4000-a000-00000000000b';

-- PDV já aprovado (criado aqui direto no banco, como o Admin faria).
reset role;
insert into public.pdvs (id, dono_id, cnpj, nome_fantasia, modo_rede, status)
values ('10000000-0000-4000-b000-000000000001', 'b0000000-0000-4000-a000-00000000000b', '55555555000191',
        'Mercado do Bruno', false, 'aprovado');
do $$ begin
  -- Todo PDV novo começa no plano grátis com 30 dias de teste grátis (catálogo do plano mercado).
  assert (select plano = 'gratis' and teste_ate = public.hoje() + 30 and plano_ate is null
          from public.pdvs where id = '10000000-0000-4000-b000-000000000001');
  assert (select teste_ate from public.testes_gratis where cnpj = '55555555000191') = public.hoje() + 30;
end $$;
-- Para testar o plano grátis: o teste já acabou, e a tolerância (7 dias) também.
update public.pdvs set teste_ate = public.hoje() - 8 where id = '10000000-0000-4000-b000-000000000001';
-- Faixa menor só para o teste ficar rápido (as faixas vêm da tabela planos, não do código).
update public.planos set limite_itens = 3 where codigo = 'gratis';
set role authenticated;
insert into public.lojas (id, pdv_id, endereco, bairro, telefone) values
  ('20000000-0000-4000-b000-000000000001', '10000000-0000-4000-b000-000000000001', 'Rua A, 1', 'Centro', '(24) 1111-1111'),
  ('20000000-0000-4000-b000-000000000002', '10000000-0000-4000-b000-000000000001', 'Rua B, 2', 'Retiro', '(24) 3333-3333');

-- Não consegue mexer em loja de outro PDV.
select pg_temp.espera_erro(
  $q$insert into public.lojas (pdv_id, endereco) values ('10000000-0000-4000-a000-000000000001', 'Invasão')$q$,
  'row-level security');
-- Plano, validade do plano e teste grátis não são alterados pelo app.
select pg_temp.espera_erro(
  $q$update public.pdvs set plano = 'mercado' where id = '10000000-0000-4000-b000-000000000001'$q$, 'permission denied');
select pg_temp.espera_erro(
  $q$update public.pdvs set teste_ate = '2099-01-01' where id = '10000000-0000-4000-b000-000000000001'$q$,
  'permission denied');
select pg_temp.espera_erro(
  $q$update public.pdvs set plano_ate = '2099-01-01' where id = '10000000-0000-4000-b000-000000000001'$q$,
  'permission denied');
select pg_temp.espera_erro($q$select * from public.testes_gratis$q$, 'permission denied');
select pg_temp.espera_erro($q$update public.planos set limite_itens = 99999$q$, 'permission denied');
select pg_temp.espera_erro($q$insert into public.configuracao_app values ('x', '1', null)$q$, 'permission denied');

do $$
declare
  pdv  constant uuid := '10000000-0000-4000-b000-000000000001';
  loja1 constant uuid := '20000000-0000-4000-b000-000000000001';
  loja2 constant uuid := '20000000-0000-4000-b000-000000000002';
  v jsonb;
  r record;
begin
  -- Varejo: cada loja tem o seu limite (aqui 3).
  select * into r from public.status_catalogo(pdv, loja1);
  assert r.plano_efetivo = 'gratis' and r.limite = 3 and r.itens_ativos = 0 and r.disponiveis = 3, r::text;

  -- Criar item: ocupa espaço.
  v := public.pdv_salvar_precos(pdv, loja1, '[{"produto": "Arroz 5kg", "preco_centavos": 2500}]');
  assert (v ->> 'criados')::int = 1 and (v ->> 'disponiveis')::int = 2, v::text;

  -- Diminuir preço (o nome pode vir com outra grafia), só OBS e aumentar: atualizações.
  v := public.pdv_salvar_precos(pdv, loja1, '[{"produto": "ARROZ  5kg", "preco_centavos": 2400, "obs": "Oferta"}]');
  assert (v ->> 'diminuidos')::int = 1 and (v ->> 'criados')::int = 0, v::text;
  assert (select count(*) from public.cotacoes where loja_id = loja1) = 1, 'upsert não pode duplicar';
  assert (select obs from public.cotacoes where loja_id = loja1) = 'Oferta';
  v := public.pdv_salvar_precos(pdv, loja1, '[{"produto": "Arroz 5kg", "preco_centavos": 2400}]');
  assert (v ->> 'inalterados')::int = 1, v::text;
  v := public.pdv_salvar_precos(pdv, loja1, '[{"produto": "Arroz 5kg", "preco_centavos": 2600}]');
  assert (v ->> 'aumentados')::int = 1, v::text;

  -- Enche o catálogo da loja 1 (3 itens).
  perform public.pdv_salvar_precos(pdv, loja1,
    '[{"produto": "Feijão 1kg", "preco_centavos": 800}, {"produto": "Óleo 900ml", "preco_centavos": 700}]');
  select * into r from public.status_catalogo(pdv, loja1);
  assert r.itens_ativos = 3 and r.disponiveis = 0, r::text;

  -- No limite: criar é recusado...
  begin
    perform public.pdv_salvar_precos(pdv, loja1, '[{"produto": "Café 500g", "preco_centavos": 1900}]');
    raise exception 'devia recusar';
  exception when others then
    assert sqlerrm like '%limite_catalogo%', sqlerrm;
  end;
  -- ...mas atualizar (subir, baixar, renovar validade, OBS, unidade) nunca é bloqueado.
  v := public.pdv_salvar_precos(pdv, loja1, format(
    '[{"produto": "Feijão 1kg", "preco_centavos": 990, "validade": "%s", "obs": "Novo lote", "unidade": "kg"},
      {"produto": "Óleo 900ml", "preco_centavos": 650}]', public.hoje() + 30)::jsonb);
  assert (v ->> 'aumentados')::int = 1 and (v ->> 'diminuidos')::int = 1 and (v ->> 'fora_do_limite')::int = 0, v::text;
  assert (select unidade from public.cotacoes where loja_id = loja1 and produto = 'Feijão 1kg') = 'kg';

  -- Modo varejo: a loja 2 tem o próprio catálogo e o próprio limite.
  v := public.pdv_salvar_precos(pdv, loja2, '[{"produto": "Arroz 5kg", "preco_centavos": 2000}]');
  assert (v ->> 'criados')::int = 1, v::text;
  assert (select itens_ativos from public.status_catalogo(pdv, loja2)) = 1;

  -- Excluir: sempre permitido e libera espaço.
  perform public.pdv_excluir_item((select id from public.cotacoes where loja_id = loja1 and produto = 'Óleo 900ml'));
  assert (select disponiveis from public.status_catalogo(pdv, loja1)) = 1;
  v := public.pdv_salvar_precos(pdv, loja1, '[{"produto": "Café 500g", "preco_centavos": 1900}]');
  assert (v ->> 'criados')::int = 1, v::text;

end $$;
-- Item vencido não conta como ativo; renovar a validade dele nunca é bloqueado.
reset role;
update public.cotacoes set validade = public.hoje() - 1
 where loja_id = '20000000-0000-4000-b000-000000000001' and produto = 'Café 500g';
set role authenticated;
do $$
declare
  pdv  constant uuid := '10000000-0000-4000-b000-000000000001';
  loja1 constant uuid := '20000000-0000-4000-b000-000000000001';
  v jsonb;
begin
  assert (select itens_ativos from public.status_catalogo(pdv, loja1)) = 2;
  perform public.pdv_salvar_precos(pdv, loja1, '[{"produto": "Açúcar 1kg", "preco_centavos": 500}]');
  v := public.pdv_salvar_precos(pdv, loja1, '[{"produto": "Café 500g", "preco_centavos": 1900}]');
  assert (v ->> 'criados')::int = 0 and (v ->> 'inalterados')::int = 1, v::text;
end $$;

\echo '== Planilha: importa até o limite, sem perder atualizações (tudo ou nada por chamada)'
do $$
declare
  pdv  constant uuid := '10000000-0000-4000-b000-000000000001';
  loja2 constant uuid := '20000000-0000-4000-b000-000000000002';
  v jsonb;
  itens jsonb;
begin
  -- Loja 2 tem 1 item (Arroz) e espaço para mais 2. Planilha: atualiza o Arroz e traz 4 novos.
  itens := '[{"produto": "Novo 1", "preco_centavos": 101},
             {"produto": "Arroz 5kg", "preco_centavos": 2100},
             {"produto": "Novo 2", "preco_centavos": 102},
             {"produto": "Novo 3", "preco_centavos": 103},
             {"produto": "Novo 4", "preco_centavos": 104}]';
  -- Simulação: mostra o que ficaria de fora e não grava nada.
  v := public.pdv_salvar_precos(pdv, loja2, itens, 'pdv_excel', true);
  assert (v ->> 'criados')::int = 2 and (v ->> 'aumentados')::int = 1 and (v ->> 'fora_do_limite')::int = 2
     and (v ->> 'limite')::int = 3, v::text;
  assert (v -> 'itens_fora' -> 0 ->> 'produto') = 'Novo 3' and (v -> 'itens_fora' -> 1 ->> 'linha')::int = 5, v::text;
  assert (select count(*) from public.cotacoes where loja_id = loja2) = 1, 'simulação não grava';

  v := public.pdv_salvar_precos(pdv, loja2, itens, 'pdv_excel');
  assert (v ->> 'criados')::int = 2 and (v ->> 'fora_do_limite')::int = 2, v::text;
  assert (select preco_centavos from public.cotacoes where loja_id = loja2 and produto = 'Arroz 5kg') = 2100,
    'a atualização nunca fica de fora';
  assert (select count(*) from public.cotacoes where loja_id = loja2) = 3;
  assert not exists (select 1 from public.cotacoes where loja_id = loja2 and produto in ('Novo 3', 'Novo 4'));

  -- Catálogo cheio: planilha só de atualizações passa inteira.
  v := public.pdv_salvar_precos(pdv, loja2,
    '[{"produto": "Novo 1", "preco_centavos": 90}, {"produto": "Novo 2", "preco_centavos": 95}]', 'pdv_excel');
  assert (v ->> 'diminuidos')::int = 2 and (v ->> 'fora_do_limite')::int = 0, v::text;
end $$;

-- Histórico (visto como superusuário: no app, só o Admin lê).
reset role;
do $$ begin
  -- Cada mudança fica no histórico (2500 -> 2400 -> só OBS -> 2600).
  assert (select array_agg(h.preco_centavos order by h.id) from public.historico_precos h
          where h.loja_id = '20000000-0000-4000-b000-000000000001' and h.produto_busca = 'arroz 5kg')
         = array[2500, 2400, 2400, 2600], 'histórico de alterações';
  -- Excluído da tabela, mas guardado no histórico (com o CNPJ do PDV).
  assert exists (select 1 from public.historico_precos h
                 where h.loja_id = '20000000-0000-4000-b000-000000000001' and h.operacao = 'excluido'
                   and h.cnpj = '55555555000191'), 'histórico de exclusão';
  assert (select unidade from public.historico_precos h where h.produto = 'Feijão 1kg' order by h.id desc limit 1) = 'kg';
  -- Medição: o log continua (sem bloquear nada) e a visão mensal resume.
  assert (select criacoes >= 6 and aumentos >= 2 and reducoes >= 2 and exclusoes = 1 from public.uso_pdv_mensal
          where pdv_id = '10000000-0000-4000-b000-000000000001' and mes = public.competencia_atual());
end $$;

\echo '== Teste grátis: catálogo do mercado; tolerância; depois, excedentes pausados (nunca apagados)'
update public.pdvs set teste_ate = public.hoje() + 10 where id = '10000000-0000-4000-b000-000000000001';
set role authenticated;
do $$
declare
  pdv  constant uuid := '10000000-0000-4000-b000-000000000001';
  loja2 constant uuid := '20000000-0000-4000-b000-000000000002';
  v jsonb;
  r record;
begin
  select * into r from public.status_catalogo(pdv, loja2);
  assert r.plano = 'gratis' and r.plano_efetivo = 'mercado' and r.limite = 3000 and r.limite_plano = 3
     and r.origem = 'teste' and not r.em_tolerancia and r.fim = public.hoje() + 10, r::text;
  v := public.pdv_salvar_precos(pdv, loja2,
    '[{"produto": "Novo 3", "preco_centavos": 103}, {"produto": "Novo 4", "preco_centavos": 104}]', 'pdv_excel');
  assert (v ->> 'criados')::int = 2 and (v ->> 'fora_do_limite')::int = 0, v::text;
end $$;
-- Terminou ontem: tolerância de 7 dias com o limite anterior (nada pausado ainda).
reset role;
update public.pdvs set teste_ate = public.hoje() - 1 where id = '10000000-0000-4000-b000-000000000001';
set role authenticated;
do $$
declare
  pdv  constant uuid := '10000000-0000-4000-b000-000000000001';
  loja2 constant uuid := '20000000-0000-4000-b000-000000000002';
  r record;
begin
  select * into r from public.status_catalogo(pdv, loja2);
  assert r.limite = 3000 and r.em_tolerancia and r.tolerancia_ate = public.hoje() + 6 and r.pausados_limite = 0, r::text;
  assert (select count(*) from public.buscar_cotacoes('novo') where loja_id = loja2) = 4;
end $$;
-- Passou a tolerância: plano grátis (aqui 3). Os 2 excedentes ficam pausados, não apagados.
reset role;
update public.pdvs set teste_ate = public.hoje() - 8 where id = '10000000-0000-4000-b000-000000000001';
set role authenticated;
do $$
declare
  pdv  constant uuid := '10000000-0000-4000-b000-000000000001';
  loja2 constant uuid := '20000000-0000-4000-b000-000000000002';
  v jsonb;
  r record;
begin
  select * into r from public.status_catalogo(pdv, loja2);
  assert r.plano_efetivo = 'gratis' and r.origem = 'gratis' and r.itens_ativos = 5 and r.itens_publicados = 3
     and r.pausados_limite = 2 and r.disponiveis = 0, r::text;
  assert (select count(*) from public.buscar_cotacoes(null, null, null, 500) where loja_id = loja2) = 3;
  assert (select count(*) from public.cotacoes where loja_id = loja2) = 5, 'nada é apagado';
  assert (select count(*) filter (where estado = 'ativo') = 3 and count(*) filter (where estado = 'pausado_limite') = 2
          from public.meus_precos(pdv, loja2));
  -- Atualizar continua livre (até o pausado); criar não.
  v := public.pdv_salvar_precos(pdv, loja2, '[{"produto": "Novo 4", "preco_centavos": 99}]');
  assert (v ->> 'diminuidos')::int = 1, v::text;
  begin
    perform public.pdv_salvar_precos(pdv, loja2, '[{"produto": "Novo 5", "preco_centavos": 105}]');
    raise exception 'devia recusar';
  exception when others then
    assert sqlerrm like '%limite_catalogo%', sqlerrm;
  end;
end $$;
-- O PDV escolhe quais ficam no ar: pausa uns e reativa outros (grátis, só até o limite).
do $$
declare
  pdv  constant uuid := '10000000-0000-4000-b000-000000000001';
  loja2 constant uuid := '20000000-0000-4000-b000-000000000002';
  item uuid;
begin
  item := (select id from public.cotacoes where loja_id = loja2 and produto = 'Arroz 5kg');
  perform public.pdv_pausar_item(item, true);
  assert (select estado from public.meus_precos(pdv, loja2) where produto = 'Arroz 5kg') = 'pausado';
  assert (select itens_ativos = 4 and pausados_pdv = 1 and itens_publicados = 3 from public.status_catalogo(pdv, loja2));
  assert not exists (select 1 from public.buscar_cotacoes('arroz', null, null, 500) where id = item);
  -- Reativar com 4 ativos para um limite de 3: recusado.
  begin
    perform public.pdv_pausar_item(item, false);
    raise exception 'devia recusar';
  exception when others then
    assert sqlerrm like '%limite_catalogo%', sqlerrm;
  end;
  -- Pausa mais dois: sobra espaço e o Arroz volta.
  perform public.pdv_pausar_item((select id from public.cotacoes where loja_id = loja2 and produto = 'Novo 1'), true);
  perform public.pdv_pausar_item((select id from public.cotacoes where loja_id = loja2 and produto = 'Novo 2'), true);
  perform public.pdv_pausar_item(item, false);
  assert (select estado from public.meus_precos(pdv, loja2) where produto = 'Arroz 5kg') = 'ativo';
  assert exists (select 1 from public.buscar_cotacoes('arroz', null, null, 500) where id = item);
  assert (select itens_ativos = 3 and pausados_limite = 0 and pausados_pdv = 2 from public.status_catalogo(pdv, loja2));
end $$;
-- Outra pessoa não pausa o item alheio.
set request.jwt.claim.sub = 'a0000000-0000-4000-a000-00000000000a';
select pg_temp.espera_erro(
  $q$select public.pdv_pausar_item((select id from public.cotacoes where produto = 'Novo 3' limit 1), true)$q$,
  'sem_permissao');
set request.jwt.claim.sub = 'b0000000-0000-4000-a000-00000000000b';

-- Validações de linha (validade máx. 30 dias, duplicado, preço, unidade).
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
select pg_temp.espera_erro(
  $q$select public.pdv_salvar_precos('10000000-0000-4000-b000-000000000001',
    '20000000-0000-4000-b000-000000000001', '[{"produto": "Feijão", "preco_centavos": 800, "unidade": "tonelada"}]')$q$,
  'unidade_invalida');

-- PDV não pode se dar pagamento de graça (a tabela continua, sem uso por enquanto).
select pg_temp.espera_erro(
  $q$insert into public.pagamentos (usuario_id, pdv_id, loja_id, tipo, quantidade, valor_centavos, status)
     values (auth.uid(), '10000000-0000-4000-b000-000000000001', '20000000-0000-4000-b000-000000000001',
             'pacote_operacoes', 50, 1000, 'pago')$q$, 'permission denied');

\echo '== Planos: "Quero ser avisado"'
do $$
declare pdv constant uuid := '10000000-0000-4000-b000-000000000001';
begin
  assert (select count(*) from public.planos where ativo) = 4;
  perform public.registrar_interesse_plano(pdv, 'loja');
  perform public.registrar_interesse_plano(pdv, 'loja'); -- repetir não duplica
  assert (select count(*) from public.interesse_planos where pdv_id = pdv and plano = 'loja') = 1;
end $$;

\echo '== Modo rede: um catálogo só (o limite não multiplica pelas lojas)'
reset role;
update public.pdvs set modo_rede = true where id = '10000000-0000-4000-b000-000000000001';
delete from public.cotacoes c using public.lojas l
 where l.id = c.loja_id and l.pdv_id = '10000000-0000-4000-b000-000000000001';
set role authenticated;
do $$
declare
  pdv constant uuid := '10000000-0000-4000-b000-000000000001';
  v jsonb;
begin
  v := public.pdv_salvar_precos(pdv, null,
    '[{"produto": "Cerveja Lata", "preco_centavos": 350, "obs": "Gelada"},
      {"produto": "Refrigerante 2L", "preco_centavos": 900}, {"produto": "Água 500ml", "preco_centavos": 250}]');
  assert (v ->> 'criados')::int = 3, v::text;
  -- Replicado nas 2 lojas (6 linhas), mas conta 3 itens no catálogo da rede.
  assert (select count(*) from public.cotacoes c join public.lojas l on l.id = c.loja_id where l.pdv_id = pdv) = 6;
  assert (select itens_ativos = 3 and disponiveis = 0 from public.status_catalogo(pdv));
  begin
    perform public.pdv_salvar_precos(pdv, null, '[{"produto": "Suco 1L", "preco_centavos": 700}]');
    raise exception 'devia recusar';
  exception when others then
    assert sqlerrm like '%limite_catalogo%', sqlerrm;
  end;
  -- Excluir em modo rede some de todas as lojas e libera espaço.
  perform public.pdv_excluir_item((select c.id from public.cotacoes c where c.produto = 'Cerveja Lata' limit 1));
  assert (select count(*) from public.cotacoes where produto = 'Cerveja Lata') = 0;
  assert (select disponiveis from public.status_catalogo(pdv)) = 1;
end $$;
reset role;
update public.planos set limite_itens = 5 where codigo = 'gratis';
set role authenticated;

\echo '== Outro usuário não usa o catálogo do PDV alheio'
set request.jwt.claim.sub = 'a0000000-0000-4000-a000-00000000000a';
select pg_temp.espera_erro(
  $q$select public.pdv_salvar_precos('10000000-0000-4000-b000-000000000001', null,
     '[{"produto": "Invasão", "preco_centavos": 1}]')$q$, 'sem_permissao');
select pg_temp.espera_erro($q$select * from public.status_catalogo('10000000-0000-4000-b000-000000000001')$q$,
  'sem_permissao');
select pg_temp.espera_erro(
  $q$select public.pdv_excluir_item((select id from public.cotacoes where produto = 'Refrigerante 2L' limit 1))$q$,
  'sem_permissao');
select pg_temp.espera_erro(
  $q$select public.registrar_interesse_plano('10000000-0000-4000-b000-000000000001', 'mercado')$q$,
  'row-level security');
do $$ begin
  assert (select count(*) from public.operacoes_log) = 0, 'log de operações é privado';
  assert (select count(*) from public.uso_pdv_mensal) = 0, 'medição é privada';
  assert (select count(*) from public.interesse_planos) = 0, 'interesse é privado';
  assert (select count(*) from public.encartes_pendentes) = 3, 'CPF vê só os próprios encartes';
end $$;

\echo '== Admin: fila de encartes'
set request.jwt.claim.sub = 'c0000000-0000-4000-a000-00000000000c';
do $$
declare n int;
begin
  assert (select count(*) from public.encartes_pendentes where status = 'pendente'
          and enviado_por = 'a0000000-0000-4000-a000-00000000000a') = 3;
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
  assert (select count(*) from public.encartes_pendentes where status = 'pendente'
          and enviado_por = 'a0000000-0000-4000-a000-00000000000a') = 0;
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
  -- A mesma pessoa chamando de novo no mesmo dia não "queima" visualizações do anunciante.
  perform public.registrar_visualizacao_promocao('70000000-0000-4000-a000-000000000001');
  perform public.registrar_visualizacao_promocao('70000000-0000-4000-a000-000000000001');
end $$;
set request.jwt.claim.sub = 'd0000000-0000-4000-a000-00000000000d';
select public.registrar_visualizacao_promocao('70000000-0000-4000-a000-000000000001');
reset role;
do $$ begin
  assert (select visualizacoes_exibidas from public.promocoes
          where id = '70000000-0000-4000-a000-000000000001') = 2, 'uma por pessoa por dia';
end $$;

\echo '== LGPD: conta excluída some do histórico de preços (o preço fica)'
do $$
declare n integer;
begin
  select count(*) into n from public.historico_precos where enviado_por = 'd0000000-0000-4000-a000-00000000000d';
  assert n > 0, 'Davi enviou notas';
  delete from auth.users where id = 'd0000000-0000-4000-a000-00000000000d';
  assert not exists (select 1 from public.historico_precos where enviado_por = 'd0000000-0000-4000-a000-00000000000d');
  assert (select count(*) from public.historico_precos where produto = 'Requeijão 200g') >= 1, 'o preço continua na história';
end $$;

\echo '== PDV: edita o cadastro, cria promoção paga, Admin confirma o pagamento'
set role authenticated;
set request.jwt.claim.sub = 'b0000000-0000-4000-a000-00000000000b';
do $$
declare
  pdv  constant uuid := '10000000-0000-4000-b000-000000000001';
  loja constant uuid := '20000000-0000-4000-b000-000000000001';
  pag uuid;
begin
  perform public.atualizar_pdv(pdv, 'Mercado do Bruno Ltda', 'https://exemplo.invalid');
  perform public.atualizar_loja(loja, 'Loja Centro', 'Rua A, 10', 'Centro', 'Petrópolis', '(24) 1111-1111', '(24) 99999-0000');
  assert (select whatsapp = '2499999-0000' or whatsapp = '24999990000' from public.minhas_lojas(pdv) where id = loja), 'whatsapp só números';
  -- O contato do card passa a ser o WhatsApp da loja.
  assert (select telefone from public.buscar_cotacoes('refrigerante 2l') where loja_id = loja) = '24999990000';

  pag := public.criar_promocao(pdv, 'Semana do arroz', 'Arroz com desconto', 'https://exemplo.invalid/arroz',
                               null, array['arroz', ' '], 250);
  assert (select status = 'aguardando_pagamento' and valor_pendente_centavos = 2500 and visualizacoes_pendentes = 250
                 and palavras_chave = array['arroz']
          from public.minhas_promocoes(pdv) where titulo = 'Semana do arroz');
  assert (select count(*) from public.promocoes_para_busca('arroz tio joao')) = 0, 'sem pagamento não aparece';
  perform set_config('tabelapp.pag', pag::text, false);
end $$;
select pg_temp.espera_erro(
  $q$select public.criar_promocao('10000000-0000-4000-b000-000000000001', 'X', null, null, null, null, 300)$q$,
  'pacote_invalido');
-- Outro usuário não cria promoção nem confirma pagamento.
set request.jwt.claim.sub = 'a0000000-0000-4000-a000-00000000000a';
select pg_temp.espera_erro(
  $q$select public.criar_promocao('10000000-0000-4000-b000-000000000001', 'X', null, null, null, null, 100)$q$,
  'sem_permissao');
select pg_temp.espera_erro($q$select public.admin_confirmar_pagamento(current_setting('tabelapp.pag')::uuid)$q$,
  'sem_permissao');
-- Admin vê os pendentes e confirma.
set request.jwt.claim.sub = 'c0000000-0000-4000-a000-00000000000c';
do $$ begin
  assert (select count(*) from public.pagamentos_pendentes()) >= 1;
  perform public.admin_confirmar_pagamento(current_setting('tabelapp.pag')::uuid);
end $$;
set request.jwt.claim.sub = 'b0000000-0000-4000-a000-00000000000b';
do $$
declare restantes_antes integer;
begin
  assert (select status = 'ativa' and visualizacoes_contratadas = 250 and pagamento_pendente_id is null
          from public.minhas_promocoes('10000000-0000-4000-b000-000000000001') where titulo = 'Semana do arroz');
  assert (select link from public.promocoes_para_busca('arroz tio joao') where titulo = 'Semana do arroz')
         = 'https://exemplo.invalid/arroz';
  -- Promoção sem pagamento é apagada; com pagamento, sai do ar mas fica no histórico.
  perform public.criar_promocao('10000000-0000-4000-b000-000000000001', 'Rascunho', null, null, null, null, 100);
  assert public.encerrar_promocao((select id from public.promocoes where titulo = 'Rascunho')) = 'excluida';
  assert not exists (select 1 from public.promocoes where titulo = 'Rascunho');
  -- Editar a promoção paga: texto, link, palavras e arte mudam; as visualizações compradas não.
  perform public.editar_promocao((select id from public.promocoes where titulo = 'Semana do arroz'),
    'Semana do arroz e feijão', 'Arroz e feijão com desconto', '', array['arroz', 'feijao'],
    true, '10000000-0000-4000-b000-000000000001/nova.jpg');
  assert (select descricao = 'Arroz e feijão com desconto' and link is null and palavras_chave @> array['feijao']
                 and arte_path = '10000000-0000-4000-b000-000000000001/nova.jpg' and visualizacoes_contratadas = 250
                 and status = 'ativa'
          from public.minhas_promocoes('10000000-0000-4000-b000-000000000001') where titulo = 'Semana do arroz e feijão');
  assert exists (select 1 from public.promocoes_para_busca('feijao preto') where titulo = 'Semana do arroz e feijão');
  -- Sem trocar a arte, a arte fica.
  perform public.editar_promocao((select id from public.promocoes where titulo = 'Semana do arroz e feijão'),
    'Semana do arroz e feijão', null, null, array['arroz', 'feijao']);
  assert (select arte_path from public.promocoes where titulo = 'Semana do arroz e feijão')
         = '10000000-0000-4000-b000-000000000001/nova.jpg';
  -- Excluir a paga: sai do ar e da lista do dono; a promoção e o pagamento ficam no histórico.
  perform set_config('tabelapp.promo_paga', (select id from public.promocoes where titulo = 'Semana do arroz e feijão')::text, false);
  assert public.encerrar_promocao(current_setting('tabelapp.promo_paga')::uuid) = 'encerrada';
  assert (select count(*) from public.promocoes_para_busca('arroz tio joao')) = 0, 'excluída sai da busca';
  assert not exists (select 1 from public.minhas_promocoes('10000000-0000-4000-b000-000000000001')
                     where id = current_setting('tabelapp.promo_paga')::uuid), 'some da lista do dono';
end $$;
select pg_temp.espera_erro(
  $q$select public.editar_promocao(current_setting('tabelapp.promo_paga')::uuid, 'X', null, null, null)$q$, 'sem_permissao');
select pg_temp.espera_erro(
  $q$select public.encerrar_promocao(current_setting('tabelapp.promo_paga')::uuid)$q$, 'sem_permissao');
select pg_temp.espera_erro(
  $q$select public.editar_promocao(current_setting('tabelapp.promo_paga')::uuid, ' ', null, null, null)$q$, 'sem_permissao');
reset role;
do $$ begin
  assert exists (select 1 from public.promocoes p join public.pagamentos g on g.promocao_id = p.id
                 where p.id = current_setting('tabelapp.promo_paga')::uuid and p.excluida_em is not null
                   and g.status = 'pago'), 'pagamento fica no histórico';
end $$;
set role authenticated;
set request.jwt.claim.sub = 'a0000000-0000-4000-a000-00000000000a';
select pg_temp.espera_erro(
  $q$select public.encerrar_promocao((select id from public.promocoes limit 1))$q$, 'sem_permissao');
select pg_temp.espera_erro(
  $q$select public.editar_promocao((select id from public.promocoes limit 1), 'X', null, null, null)$q$, 'sem_permissao');

\echo '== Lançamentos repetidos: o igual não entra; a busca mostra o mais recente'
set role authenticated;
set request.jwt.claim.sub = 'b0000000-0000-4000-a000-00000000000b';
do $$
declare v jsonb;
begin
  v := public.enviar_nota_fiscal(pg_temp.chave('44444444000191', 8001), null, 'Mercearia Quatro', 'Rua Quatro, 4',
    '[{"produto": "Leite Integral 1L", "preco_centavos": 499},
      {"produto": "Leite integral 1l", "preco_centavos": 499},
      {"produto": "Café Moído 500g", "preco_centavos": 1890}]');
  assert (v ->> 'itens')::int = 2, 'item repetido na mesma nota entra uma vez';
  v := public.enviar_nota_fiscal(pg_temp.chave('44444444000191', 8002), null, 'Mercearia Quatro', 'Rua Quatro, 4',
    '[{"produto": "Leite Integral 1L", "preco_centavos": 499},
      {"produto": "Café Moído 500g", "preco_centavos": 1990}]');
  assert (v ->> 'itens')::int = 1, 'mesmo dia e preço não entra de novo; preço diferente entra';
  v := public.enviar_nota_fiscal(pg_temp.chave('44444444000191', 8003, public.hoje() - 1), null, 'Mercearia Quatro',
    'Rua Quatro, 4', '[{"produto": "Leite Integral 1L", "preco_centavos": 499}]', public.hoje() - 1);
  assert (v ->> 'itens')::int = 1, 'outro dia entra';
  -- Busca: um card por produto neste local — o lançamento mais recente.
  assert (select count(*) from public.buscar_cotacoes('leite integral 1l') where pdv_nome = 'Mercearia Quatro') = 1;
  assert (select data_nf from public.buscar_cotacoes('leite integral 1l') where pdv_nome = 'Mercearia Quatro') = public.hoje();
  assert (select preco_centavos from public.buscar_cotacoes('cafe moido 500g') where pdv_nome = 'Mercearia Quatro') = 1990;
end $$;
reset role;
do $$ begin
  assert (select count(*) from public.cotacoes where substring(chave_acesso_nf from 7 for 14) = '44444444000191'
          and produto_busca in ('leite integral 1l', 'cafe moido 500g')) = 4,
    'no banco ficam os diferentes (dias ou preços)';
  assert (select count(*) from public.historico_precos
          where cnpj = '44444444000191' and produto in ('Leite Integral 1L', 'Café Moído 500g')) = 4;
end $$;

\echo '== Preço confiável: item de combo e denúncia "Esse preço não confere"'
set role authenticated;
set request.jwt.claim.sub = 'b0000000-0000-4000-a000-00000000000b';
do $$
declare v jsonb;
begin
  -- Item marcado como parte de combo no envio da NF.
  v := public.enviar_nota_fiscal(pg_temp.chave('44444444000191', 8101), null, 'Lanchonete Combo', 'Rua do Combo, 1',
    '[{"produto": "Pão carne e queijo", "preco_centavos": 400, "parte_de_combo": true},
      {"produto": "Refrigerante lata", "preco_centavos": 600}]');
  assert (v ->> 'itens')::int = 2, v::text;
  assert (select parte_de_combo from public.buscar_cotacoes('pao carne e queijo') where pdv_nome = 'Lanchonete Combo');
  assert not (select parte_de_combo from public.buscar_cotacoes('refrigerante lata') where pdv_nome = 'Lanchonete Combo');
  assert (select denuncias from public.buscar_cotacoes('pao carne e queijo') where pdv_nome = 'Lanchonete Combo') = 0;
  perform set_config('tabelapp.denunciada',
    (select id::text from public.buscar_cotacoes('pao carne e queijo') where pdv_nome = 'Lanchonete Combo'), false);
end $$;
set request.jwt.claim.sub = 'a0000000-0000-4000-a000-00000000000a';
do $$
declare cot uuid := current_setting('tabelapp.denunciada')::uuid;
begin
  assert public.denunciar_preco(cot, 'combo_ou_promocao');
  assert not public.denunciar_preco(cot, 'outro'), 'a mesma pessoa não denuncia duas vezes';
  assert (select count(*) from public.denuncias_preco) = 1, 'vê só a própria denúncia';
end $$;
-- Índice único: nem por insert direto.
select pg_temp.espera_erro(
  $q$insert into public.denuncias_preco (cotacao_id) values (current_setting('tabelapp.denunciada')::uuid)$q$,
  'duplicate key');
-- Não denuncia em nome de outra pessoa; motivo fora da lista é recusado.
select pg_temp.espera_erro(
  $q$insert into public.denuncias_preco (cotacao_id, usuario_id)
     values (current_setting('tabelapp.denunciada')::uuid, 'e0000000-0000-4000-a000-00000000000e')$q$,
  'row-level security');
select pg_temp.espera_erro(
  $q$select public.denunciar_preco(current_setting('tabelapp.denunciada')::uuid, 'xingamento')$q$, 'check');
set request.jwt.claim.sub = 'e0000000-0000-4000-a000-00000000000e';
do $$
declare cot uuid := current_setting('tabelapp.denunciada')::uuid;
begin
  assert (select count(*) from public.denuncias_preco) = 0, 'não lê as denúncias dos outros';
  assert (select count(*) from public.denuncias_mais_frequentes) = 0, 'visão do Admin não mostra as dos outros';
  assert public.denunciar_preco(cot, 'preco_mudou');
  -- A busca mostra só o total (2), nunca quem denunciou.
  assert (select denuncias from public.buscar_cotacoes('pao carne e queijo') where id = cot) = 2;
end $$;
select pg_temp.espera_erro($q$delete from public.denuncias_preco$q$, 'permission denied');
-- Admin vê as mais denunciadas.
set request.jwt.claim.sub = 'c0000000-0000-4000-a000-00000000000c';
do $$ begin
  assert (select denuncias = 2 and combo_ou_promocao = 1 and preco_mudou = 1 and parte_de_combo
          and pdv_nome = 'Lanchonete Combo'
          from public.denuncias_mais_frequentes where cotacao_id = current_setting('tabelapp.denunciada')::uuid);
  assert (select (valor)::text::int from public.configuracao_app where chave = 'denuncias_limite') = 3;
end $$;
reset role;

\echo '== Minha conta: editar nome, aceitar termos e excluir a conta pelo app'
set role authenticated;
set request.jwt.claim.sub = 'e0000000-0000-4000-a000-00000000000e';
select pg_temp.espera_erro($q$select public.atualizar_meu_nome(' ')$q$, 'nome_invalido');
do $$ begin
  perform public.atualizar_meu_nome('  Eva Souza ');
  perform public.registrar_aceite_termos('2026-10-13');
  assert (select nome = 'Eva Souza' and termos_versao = '2026-10-13' and termos_aceitos_em is not null
          from public.usuarios where id = auth.uid());
  -- Eva tem uma denúncia (seção anterior): some junto com a conta.
  assert (select count(*) from public.denuncias_preco) = 1;
  perform public.excluir_minha_conta();
end $$;
reset role;
do $$ begin
  assert not exists (select 1 from auth.users where id = 'e0000000-0000-4000-a000-00000000000e');
  assert not exists (select 1 from public.usuarios where id = 'e0000000-0000-4000-a000-00000000000e');
  assert not exists (select 1 from public.denuncias_preco where usuario_id = 'e0000000-0000-4000-a000-00000000000e');
end $$;
-- Estabelecimento: ao excluir a conta, o PDV e a tabela de preços saem; o histórico fica, anônimo.
set role authenticated;
set request.jwt.claim.sub = 'b0000000-0000-4000-a000-00000000000b';
select public.excluir_minha_conta();
reset role;
do $$ begin
  assert not exists (select 1 from public.pdvs where dono_id = 'b0000000-0000-4000-a000-00000000000b');
  assert not exists (select 1 from public.cotacoes c join public.lojas l on l.id = c.loja_id
                     where l.pdv_id = '10000000-0000-4000-b000-000000000001');
  assert exists (select 1 from public.historico_precos where pdv_id = '10000000-0000-4000-b000-000000000001');
  assert not exists (select 1 from public.historico_precos where enviado_por = 'b0000000-0000-4000-a000-00000000000b');
end $$;
-- Sem login, nada acontece.
set role anon;
select pg_temp.espera_erro($q$select public.excluir_minha_conta()$q$, 'permission denied');
reset role;

\echo '== Moderação do Admin: preço denunciado, remover preço, suspender e reativar PDV'
set role authenticated;
-- Quem não é Admin não modera nada.
set request.jwt.claim.sub = 'a0000000-0000-4000-a000-00000000000a';
select pg_temp.espera_erro($q$select public.admin_manter_cotacao(current_setting('tabelapp.denunciada')::uuid)$q$, 'sem_permissao');
select pg_temp.espera_erro($q$select public.admin_remover_cotacao(current_setting('tabelapp.denunciada')::uuid)$q$, 'sem_permissao');
select pg_temp.espera_erro($q$select * from public.admin_pdvs()$q$, 'sem_permissao');
select pg_temp.espera_erro($q$select public.admin_suspender_pdv('10000000-0000-4000-a000-000000000001', 'x')$q$, 'sem_permissao');
do $$ begin
  assert (select count(*) from public.moderacoes) = 0, 'registro de moderação é só do Admin';
end $$;

set request.jwt.claim.sub = 'c0000000-0000-4000-a000-00000000000c';
do $$
declare cot uuid := current_setting('tabelapp.denunciada')::uuid;
begin
  -- "Manter": o preço estava certo, as denúncias somem.
  assert (select denuncias from public.denuncias_mais_frequentes where cotacao_id = cot) >= 1;
  perform public.admin_manter_cotacao(cot);
  assert not exists (select 1 from public.denuncias_mais_frequentes where cotacao_id = cot);
  assert (select denuncias from public.buscar_cotacoes('pao carne e queijo') where id = cot) = 0;
  -- "Remover": sai da busca, fica no histórico.
  perform public.admin_remover_cotacao(cot, 'Valor de combo');
  assert not exists (select 1 from public.cotacoes where id = cot);
  assert (select acao from public.moderacoes order by id desc limit 1) = 'remover_preco';
end $$;
reset role;
do $$ begin
  assert exists (select 1 from public.historico_precos
                 where cotacao_id = current_setting('tabelapp.denunciada')::uuid and operacao = 'excluido');
end $$;

set role authenticated;
set request.jwt.claim.sub = 'c0000000-0000-4000-a000-00000000000c';
select pg_temp.espera_erro($q$select public.admin_suspender_pdv('10000000-0000-4000-a000-000000000001', ' ')$q$, 'motivo_obrigatorio');
do $$
declare
  pdv constant uuid := '10000000-0000-4000-a000-000000000001';
  antes integer;
begin
  assert (select status = 'aprovado' from public.admin_pdvs('serra imperial') where id = pdv);
  assert (select count(*) from public.admin_pdvs('11111111')) = 1, 'busca por CNPJ';
  select count(*) into antes from public.buscar_cotacoes(null) b join public.lojas l on l.id = b.loja_id where l.pdv_id = pdv;
  assert antes > 0;
  perform public.admin_suspender_pdv(pdv, 'Preços fora da realidade');
  -- Some da busca, mas nada foi apagado.
  assert (select count(*) from public.buscar_cotacoes(null) b join public.lojas l on l.id = b.loja_id where l.pdv_id = pdv) = 0;
  assert (select count(*) from public.cotacoes c join public.lojas l on l.id = c.loja_id
          where l.pdv_id = pdv and c.fonte in ('pdv_manual', 'pdv_excel')) > 0;
  assert (select status = 'suspenso' and motivo_suspensao = 'Preços fora da realidade' from public.admin_pdvs() where id = pdv);
end $$;
-- O dono vê o motivo e não consegue publicar enquanto estiver suspenso.
set request.jwt.claim.sub = '00000000-0000-4000-a000-000000000001';
do $$ begin
  assert (select status = 'suspenso' and motivo_suspensao = 'Preços fora da realidade'
          from public.meus_pdvs() where id = '10000000-0000-4000-a000-000000000001');
end $$;
select pg_temp.espera_erro(
  $q$select public.pdv_salvar_precos('10000000-0000-4000-a000-000000000001', null, '[{"produto": "Arroz 5kg", "preco_centavos": 100}]')$q$,
  'pdv_nao_verificado');
-- Reativar: volta à busca com a tabela que já tinha.
set request.jwt.claim.sub = 'c0000000-0000-4000-a000-00000000000c';
do $$
declare pdv constant uuid := '10000000-0000-4000-a000-000000000001';
begin
  perform public.admin_reativar_pdv(pdv);
  assert (select count(*) from public.buscar_cotacoes(null) b join public.lojas l on l.id = b.loja_id where l.pdv_id = pdv) > 0;
  assert (select array_agg(acao order by id) from public.moderacoes)
         = array['manter_preco', 'remover_preco', 'suspender_pdv', 'reativar_pdv'];
end $$;
select pg_temp.espera_erro($q$select public.admin_reativar_pdv('10000000-0000-4000-a000-000000000001')$q$, 'pdv_nao_encontrado');
reset role;

\echo '== Coordenadas das lojas: limpar ao mudar o endereço, gravar pelo dono ou Admin'
set role authenticated;
set request.jwt.claim.sub = '00000000-0000-4000-a000-000000000001';
do $$
declare loja constant uuid := '20000000-0000-4000-a000-000000000002';
begin
  assert (select latitude is not null from public.minhas_lojas('10000000-0000-4000-a000-000000000001') where id = loja);
  -- Mudou só o telefone: a coordenada fica.
  perform public.atualizar_loja(loja, 'Loja Valparaíso', 'Rua Coronel Veiga, 1200', 'Valparaíso', null, '(24) 2222-9999', null);
  assert (select latitude is not null from public.minhas_lojas('10000000-0000-4000-a000-000000000001') where id = loja);
  -- Mudou o endereço: a coordenada antiga sai (o app localiza de novo).
  perform public.atualizar_loja(loja, 'Loja Valparaíso', 'Rua Coronel Veiga, 1500', 'Valparaíso', null, '(24) 2222-9999', null);
  assert (select latitude is null and longitude is null and coordenadas_origem is null
          from public.minhas_lojas('10000000-0000-4000-a000-000000000001') where id = loja);
  perform public.definir_coordenadas_loja(loja, -22.517, -43.172, 'aparelho');
  assert (select latitude = -22.517 and longitude = -43.172 and coordenadas_origem = 'aparelho'
          from public.minhas_lojas('10000000-0000-4000-a000-000000000001') where id = loja);
end $$;
select pg_temp.espera_erro(
  $q$select public.definir_coordenadas_loja('20000000-0000-4000-a000-000000000002', 0, 0)$q$, 'coordenada_invalida');
select pg_temp.espera_erro(
  $q$select public.definir_coordenadas_loja('20000000-0000-4000-a000-000000000002', -43.17, -22.51)$q$, 'coordenada_invalida');
select pg_temp.espera_erro(
  $q$select public.definir_coordenadas_loja('00000000-0000-4000-a000-0000000000ff', -22.5, -43.1)$q$, 'loja_nao_encontrada');
-- Outra pessoa não mexe na coordenada da loja.
set request.jwt.claim.sub = 'a0000000-0000-4000-a000-00000000000a';
select pg_temp.espera_erro(
  $q$select public.definir_coordenadas_loja('20000000-0000-4000-a000-000000000002', -22.5, -43.1)$q$, 'sem_permissao');
select pg_temp.espera_erro($q$select * from public.admin_lojas_sem_coordenadas()$q$, 'sem_permissao');
-- O Admin vê as lojas sem coordenada e grava.
reset role;
update public.lojas set latitude = null, longitude = null where id = '20000000-0000-4000-a000-000000000003';
set role authenticated;
set request.jwt.claim.sub = 'c0000000-0000-4000-a000-00000000000c';
do $$
declare loja constant uuid := '20000000-0000-4000-a000-000000000003';
begin
  assert exists (select 1 from public.admin_lojas_sem_coordenadas() where id = loja and endereco like 'Avenida Joaquim Rolla%');
  perform public.definir_coordenadas_loja(loja, -22.5275, -43.2105);
  assert not exists (select 1 from public.admin_lojas_sem_coordenadas() where id = loja);
  assert (select latitude = -22.5275 from public.buscar_cotacoes(null) where loja_id = loja limit 1);
end $$;
reset role;

\echo '== Empresa anunciante (sem ponto de venda): cadastro com comprovante, aprovação e banner'
insert into auth.users (id, email, raw_user_meta_data) values
  ('f0000000-0000-4000-a000-00000000000f', 'fazenda@teste.invalid', '{"nome": "Fazenda", "tipo": "cnpj"}');
set role authenticated;
set request.jwt.claim.sub = 'f0000000-0000-4000-a000-00000000000f';
select pg_temp.espera_erro(
  $q$select public.cadastrar_anunciante('11222333000181', 'Queijos da Serra', null, 'Estrada do Queijo, km 3', null,
     'Teresópolis', 'RJ', null, '123', null, 'f0000000-0000-4000-a000-00000000000f/doc.jpg')$q$,
  'telefone_obrigatorio');
select pg_temp.espera_erro(
  $q$select public.cadastrar_anunciante('11222333000181', 'Queijos da Serra', null, 'Estrada do Queijo, km 3', null,
     ' ', 'RJ', null, '(21) 99999-0000', null, 'f0000000-0000-4000-a000-00000000000f/doc.jpg')$q$,
  'cidade_obrigatoria');
select pg_temp.espera_erro(
  $q$select public.cadastrar_anunciante('11222333000181', 'Queijos da Serra', null, ' ', null,
     'Teresópolis', 'RJ', null, '(21) 99999-0000', null, 'f0000000-0000-4000-a000-00000000000f/doc.jpg')$q$,
  'endereco_obrigatorio');
-- Comprovante do CNPJ é obrigatório (mesmo tratamento do estabelecimento).
select pg_temp.espera_erro(
  $q$select public.cadastrar_anunciante('11222333000181', 'Queijos da Serra', null, 'Estrada do Queijo, km 3', null,
     'Teresópolis', 'RJ', null, '(21) 99999-0000', null, null)$q$,
  'alvara_obrigatorio');
select pg_temp.espera_erro(
  $q$select public.cadastrar_anunciante('11222333000181', 'Queijos da Serra', null, 'Estrada do Queijo, km 3', null,
     'Teresópolis', 'RJ', null, '(21) 99999-0000', null, 'a0000000-0000-4000-a000-00000000000a/doc.jpg')$q$,
  'alvara_obrigatorio');
-- Conta de pessoa (CPF) não cadastra empresa.
set request.jwt.claim.sub = 'a0000000-0000-4000-a000-00000000000a';
select pg_temp.espera_erro(
  $q$select public.cadastrar_anunciante('11222333000181', 'X', null, 'Rua X', null, 'Rio', 'RJ', null,
     '(21) 99999-0000', null, 'a0000000-0000-4000-a000-00000000000a/doc.jpg')$q$,
  'conta_nao_cnpj');
-- Cadastro com endereço da empresa (não vira loja) e comprovante: fica pendente para o Admin.
set request.jwt.claim.sub = 'f0000000-0000-4000-a000-00000000000f';
do $$
declare v uuid;
begin
  v := public.cadastrar_anunciante('11.222.333/0001-81', 'Queijos da Serra', 'LATICINIOS SERRA LTDA',
                                   'Estrada do Queijo, km 3', 'Vargem Grande', 'Teresópolis', 'rj', '25960000',
                                   '(21) 99999-0000', 'queijosdaserra.invalid',
                                   'f0000000-0000-4000-a000-00000000000f/doc.jpg', true);
  perform set_config('tabelapp.anunciante', v::text, false);
  assert (select tipo = 'anunciante' and status = 'pendente' and cnpj_conferido_no_alvara
          from public.meus_pdvs() where id = v);
  assert not exists (select 1 from public.lojas where pdv_id = v), 'endereço do anunciante não é ponto de venda';
end $$;
-- Antes da aprovação, não cria banner.
select pg_temp.espera_erro(
  $q$select public.criar_promocao(current_setting('tabelapp.anunciante')::uuid, 'Queijo', null, null, null, null, 100)$q$,
  'pdv_nao_verificado');
set request.jwt.claim.sub = 'c0000000-0000-4000-a000-00000000000c';
do $$
declare v constant uuid := current_setting('tabelapp.anunciante')::uuid;
begin
  assert (select tipo = 'anunciante' and telefone = '(21) 99999-0000'
                 and endereco = 'Estrada do Queijo, km 3, Vargem Grande, Teresópolis - RJ'
                 and alvara_path = 'f0000000-0000-4000-a000-00000000000f/doc.jpg'
          from public.pdvs_pendentes() where id = v);
  perform public.aprovar_pdv(v);
  assert (select tipo = 'anunciante' and cidade = 'Teresópolis' from public.admin_pdvs('queijos') where id = v);
end $$;
-- Aprovado: cria o banner (sem loja) e, depois do pagamento, ele aparece na busca.
set request.jwt.claim.sub = 'f0000000-0000-4000-a000-00000000000f';
do $$
declare
  v constant uuid := current_setting('tabelapp.anunciante')::uuid;
  pag uuid;
begin
  pag := public.criar_promocao(v, 'Queijo artesanal', 'Direto do produtor', null, null, array['queijo'], 100);
  perform set_config('tabelapp.pag_anunciante', pag::text, false);
end $$;
set request.jwt.claim.sub = 'c0000000-0000-4000-a000-00000000000c';
select public.admin_confirmar_pagamento(current_setting('tabelapp.pag_anunciante')::uuid);
set request.jwt.claim.sub = 'a0000000-0000-4000-a000-00000000000a';
do $$ begin
  assert exists (select 1 from public.promocoes_para_busca('queijo minas', null, null, 10)
                 where pdv_nome = 'Queijos da Serra' and distancia_km is null);
end $$;
-- Loja suspensa não pode ser "recadastrada" (o que apagaria o cadastro suspenso).
set request.jwt.claim.sub = 'c0000000-0000-4000-a000-00000000000c';
select public.admin_suspender_pdv(current_setting('tabelapp.anunciante')::uuid, 'Teste');
set request.jwt.claim.sub = 'f0000000-0000-4000-a000-00000000000f';
select pg_temp.espera_erro(
  $q$select public.cadastrar_anunciante('11222333000181', 'Outra', null, 'Rua X', null, 'Rio', 'RJ', null,
     '(21) 99999-0000', null, 'f0000000-0000-4000-a000-00000000000f/doc2.jpg')$q$,
  'cnpj_ja_cadastrado');
select pg_temp.espera_erro(
  $q$select public.cadastrar_pdv('11222333000181', 'X', null, 'Rua X', null, 'Rio', 'RJ', null, null,
     'f0000000-0000-4000-a000-00000000000f/alvara.jpg')$q$,
  'cnpj_ja_cadastrado');
reset role;
do $$ begin
  assert exists (select 1 from public.pdvs where id = current_setting('tabelapp.anunciante')::uuid and status = 'suspenso');
end $$;

\echo '== Novos planos: 5 grátis / 30 / 300 / 3000, teste por CNPJ, tolerância, Admin ativa'
reset role;
do $$ begin
  assert (select array_agg(codigo || ':' || limite_itens || ':' || preco_referencia_centavos order by ordem)
          from public.planos where ativo)
         = array['gratis:5:0', 'essencial:30:999', 'loja:300:1999', 'mercado:3000:2999'];
  assert (public.config_app('dias_teste_gratis', 'null'))::text = '30';
  assert (public.config_app('dias_tolerancia', 'null'))::text = '7';
end $$;
insert into auth.users (id, email, raw_user_meta_data) values
  ('90000000-0000-4000-a000-000000000009', 'gil@teste.invalid', '{"nome": "Gil", "tipo": "cnpj"}');
insert into public.pdvs (id, dono_id, cnpj, nome_fantasia, modo_rede, status)
values ('90000000-0000-4000-b000-000000000009', '90000000-0000-4000-a000-000000000009', '11444777000161',
        'Mercearia do Gil', true, 'aprovado');
insert into public.lojas (id, pdv_id, endereco, bairro) values
  ('90000000-0000-4000-c000-000000000009', '90000000-0000-4000-b000-000000000009', 'Rua G, 9', 'Centro');
set role authenticated;
set request.jwt.claim.sub = '90000000-0000-4000-a000-000000000009';
do $$
declare
  pdv constant uuid := '90000000-0000-4000-b000-000000000009';
  r record;
  v jsonb;
begin
  -- Teste grátis de 30 dias com o catálogo do mercado (3000).
  select * into r from public.status_catalogo(pdv);
  assert r.origem = 'teste' and r.limite = 3000 and r.teste_ate = public.hoje() + 30 and r.limite_gratis = 5
     and r.whatsapp_contato = '5524988029067', r::text;
  v := public.pdv_salvar_precos(pdv, null, '[{"produto": "G1", "preco_centavos": 100}, {"produto": "G2", "preco_centavos": 100},
    {"produto": "G3", "preco_centavos": 100}, {"produto": "G4", "preco_centavos": 100},
    {"produto": "G5", "preco_centavos": 100}, {"produto": "G6", "preco_centavos": 100}]');
  assert (v ->> 'criados')::int = 6, v::text;
end $$;
-- Fim do teste e da tolerância: grátis com 5. O 6º fica pausado; criar o 7º é bloqueado.
reset role;
update public.pdvs set teste_ate = public.hoje() - 8 where id = '90000000-0000-4000-b000-000000000009';
set role authenticated;
do $$
declare
  pdv constant uuid := '90000000-0000-4000-b000-000000000009';
  r record;
  v jsonb;
begin
  select * into r from public.status_catalogo(pdv);
  assert r.origem = 'gratis' and r.limite = 5 and r.itens_ativos = 6 and r.itens_publicados = 5
     and r.pausados_limite = 1, r::text;
  begin
    perform public.pdv_salvar_precos(pdv, null, '[{"produto": "G7", "preco_centavos": 100}]');
    raise exception 'devia recusar';
  exception when others then
    assert sqlerrm like '%limite_catalogo%', sqlerrm;
  end;
  -- Subir, baixar e excluir nunca são bloqueados.
  v := public.pdv_salvar_precos(pdv, null, '[{"produto": "G1", "preco_centavos": 150}, {"produto": "G2", "preco_centavos": 50}]');
  assert (v ->> 'aumentados')::int = 1 and (v ->> 'diminuidos')::int = 1, v::text;
  perform public.pdv_excluir_item((select id from public.cotacoes where produto = 'G6'));
  select * into r from public.status_catalogo(pdv);
  assert r.itens_ativos = 5 and r.pausados_limite = 0 and r.disponiveis = 0, r::text;
  -- Com 5 ativos, o 6º item é bloqueado no grátis.
  begin
    perform public.pdv_salvar_precos(pdv, null, '[{"produto": "G6", "preco_centavos": 100}]');
    raise exception 'devia recusar';
  exception when others then
    assert sqlerrm like '%limite_catalogo%', sqlerrm;
  end;
end $$;
-- Só o Admin ativa planos (o dono não muda o próprio plano).
select pg_temp.espera_erro(
  $q$select public.admin_definir_plano('90000000-0000-4000-b000-000000000009', 'mercado', public.hoje() + 30)$q$,
  'sem_permissao');
select pg_temp.espera_erro($q$select * from public.admin_planos_pdvs()$q$, 'sem_permissao');
select pg_temp.espera_erro(
  $q$update public.pdvs set plano = 'mercado' where id = '90000000-0000-4000-b000-000000000009'$q$, 'permission denied');
select pg_temp.espera_erro(
  $q$insert into public.assinaturas_log (cnpj, acao, plano) values ('11444777000161', 'ativar', 'mercado')$q$,
  'row-level security');
set request.jwt.claim.sub = 'c0000000-0000-4000-a000-00000000000c';
select pg_temp.espera_erro(
  $q$select public.admin_definir_plano('90000000-0000-4000-b000-000000000009', 'gratis', public.hoje() + 30)$q$,
  'plano_invalido');
select pg_temp.espera_erro(
  $q$select public.admin_definir_plano('90000000-0000-4000-b000-000000000009', 'loja', public.hoje() - 1)$q$,
  'validade_invalida');
do $$
declare pdv constant uuid := '90000000-0000-4000-b000-000000000009';
begin
  perform public.admin_definir_plano(pdv, 'essencial', public.hoje() + 30, 'Pix recebido');
  assert (select plano = 'essencial' and plano_ate = public.hoje() + 30 and limite = 30 and origem = 'pago'
          from public.admin_planos_pdvs('gil'));
end $$;
-- Essencial: até 30.
set request.jwt.claim.sub = '90000000-0000-4000-a000-000000000009';
do $$
declare
  pdv constant uuid := '90000000-0000-4000-b000-000000000009';
  itens jsonb;
begin
  select jsonb_agg(jsonb_build_object('produto', 'E' || n, 'preco_centavos', 100)) into itens from generate_series(1, 25) n;
  perform public.pdv_salvar_precos(pdv, null, itens, 'pdv_excel');
  assert (select itens_ativos = 30 and limite = 30 and disponiveis = 0 from public.status_catalogo(pdv));
  begin
    perform public.pdv_salvar_precos(pdv, null, '[{"produto": "E31", "preco_centavos": 100}]');
    raise exception 'devia recusar';
  exception when others then
    assert sqlerrm like '%limite_catalogo%', sqlerrm;
  end;
end $$;
-- Loja (300) e Mercado (3000); renovar o mesmo plano.
set request.jwt.claim.sub = 'c0000000-0000-4000-a000-00000000000c';
do $$
declare pdv constant uuid := '90000000-0000-4000-b000-000000000009';
begin
  perform public.admin_definir_plano(pdv, 'loja', public.hoje() + 30);
  assert (select limite from public.admin_planos_pdvs('11444777')) = 300;
  perform public.admin_definir_plano(pdv, 'mercado', public.hoje() + 30);
  assert (select limite from public.admin_planos_pdvs('gil')) = 3000;
  perform public.admin_definir_plano(pdv, 'mercado', public.hoje() + 60, 'Renovação');
  assert (select array_agg(acao order by id) from public.assinaturas_log where pdv_id = pdv)
         = array['ativar', 'trocar', 'trocar', 'renovar'];
  assert (select validade_anterior = public.hoje() + 30 and validade_nova = public.hoje() + 60
          from public.assinaturas_log where pdv_id = pdv order by id desc limit 1);
end $$;
-- Plano pago vencido: tolerância; depois, excedentes pausados; renovar devolve tudo.
reset role;
update public.pdvs set plano_ate = public.hoje() - 1 where id = '90000000-0000-4000-b000-000000000009';
set role authenticated;
set request.jwt.claim.sub = '90000000-0000-4000-a000-000000000009';
do $$ begin
  assert (select limite = 3000 and origem = 'pago' and em_tolerancia and pausados_limite = 0
          from public.status_catalogo('90000000-0000-4000-b000-000000000009'));
end $$;
reset role;
update public.pdvs set plano_ate = public.hoje() - 8 where id = '90000000-0000-4000-b000-000000000009';
set role authenticated;
do $$ begin
  assert (select limite = 5 and origem = 'gratis' and itens_ativos = 30 and itens_publicados = 5 and pausados_limite = 25
          from public.status_catalogo('90000000-0000-4000-b000-000000000009'));
  assert (select count(*) from public.buscar_cotacoes(null, null, null, 500)
          where loja_id = '90000000-0000-4000-c000-000000000009') = 5;
end $$;
set request.jwt.claim.sub = 'c0000000-0000-4000-a000-00000000000c';
select public.admin_definir_plano('90000000-0000-4000-b000-000000000009', 'loja', public.hoje() + 30);
set request.jwt.claim.sub = '90000000-0000-4000-a000-000000000009';
do $$ begin
  assert (select limite = 300 and itens_publicados = 30 and pausados_limite = 0
          from public.status_catalogo('90000000-0000-4000-b000-000000000009')), 'renovar reativa os itens';
  assert (select count(*) from public.buscar_cotacoes(null, null, null, 500)
          where loja_id = '90000000-0000-4000-c000-000000000009') = 30;
  assert (select count(*) from public.assinaturas_log) = 0, 'registro de assinaturas é só do Admin';
end $$;
-- Tolerância configurável (0 = sem tolerância).
reset role;
update public.configuracao_app set valor = '0' where chave = 'dias_tolerancia';
update public.pdvs set plano_ate = public.hoje() - 1 where id = '90000000-0000-4000-b000-000000000009';
set role authenticated;
do $$ begin
  assert (select limite = 5 and not em_tolerancia from public.status_catalogo('90000000-0000-4000-b000-000000000009'));
end $$;
reset role;
update public.configuracao_app set valor = '7' where chave = 'dias_tolerancia';
-- Cancelar: volta ao grátis e fica registrado.
set role authenticated;
set request.jwt.claim.sub = 'c0000000-0000-4000-a000-00000000000c';
do $$
declare pdv constant uuid := '90000000-0000-4000-b000-000000000009';
begin
  perform public.admin_cancelar_plano(pdv, 'Pedido do cliente');
  assert (select plano = 'gratis' and plano_ate is null from public.admin_planos_pdvs('gil'));
  assert (select acao from public.assinaturas_log where pdv_id = pdv order by id desc limit 1) = 'cancelar';
end $$;
-- Um teste por CNPJ: excluir e recadastrar não reinicia o teste.
reset role;
update public.testes_gratis set teste_ate = public.hoje() - 20 where cnpj = '11444777000161';
delete from public.pdvs where id = '90000000-0000-4000-b000-000000000009';
insert into public.pdvs (id, dono_id, cnpj, nome_fantasia, modo_rede, status)
values ('90000000-0000-4000-b000-00000000000a', '90000000-0000-4000-a000-000000000009', '11444777000161',
        'Mercearia do Gil', true, 'aprovado');
do $$ begin
  assert (select teste_ate from public.pdvs where id = '90000000-0000-4000-b000-00000000000a') = public.hoje() - 20,
    'o teste não reinicia';
  assert exists (select 1 from public.assinaturas_log where cnpj = '11444777000161'), 'o registro de assinaturas fica';
end $$;

\echo '== Banner criado pelo Admin (o app não vende: combinado fora do app)'
set role authenticated;
set request.jwt.claim.sub = '90000000-0000-4000-a000-000000000009';
do $$ begin
  begin
    perform public.admin_criar_banner('90000000-0000-4000-b000-00000000000a', 'X', null, null, null, 100);
    assert false, 'só o Admin cria banner';
  exception when raise_exception then
    assert sqlerrm = 'sem_permissao';
  end;
end $$;
set request.jwt.claim.sub = 'c0000000-0000-4000-a000-00000000000c';
do $$
declare promo uuid;
begin
  promo := public.admin_criar_banner('90000000-0000-4000-b000-00000000000a', '  Semana do Gil ', 'Tudo fresco',
                                     null, array['gil', ' ', 'gil'], 250);
  assert (select status = 'ativa' and visualizacoes_contratadas = 250 and titulo = 'Semana do Gil'
                 and palavras_chave = array['gil'] from public.promocoes where id = promo);
  assert not exists (select 1 from public.pagamentos where promocao_id = promo), 'sem registro de pagamento';
  begin
    perform public.admin_criar_banner('90000000-0000-4000-b000-00000000000a', 'X', null, null, null, 0);
    assert false, 'visualizações inválidas';
  exception when raise_exception then
    assert sqlerrm = 'pacote_invalido';
  end;
end $$;
reset role;

\echo '== Denunciar banner: 1 por pessoa, fila do Admin, pausar e manter'
set role authenticated;
set request.jwt.claim.sub = '90000000-0000-4000-a000-000000000009';
do $$
declare promo uuid := (select id from public.promocoes where titulo = 'Semana do Gil');
begin
  assert public.denunciar_banner(promo, 'enganoso'), 'primeira denúncia entra';
  assert not public.denunciar_banner(promo, 'ofensivo'), 'repetir não duplica';
  begin
    perform * from public.admin_banners_denunciados();
    assert false, 'só o Admin vê a fila';
  exception when raise_exception then
    assert sqlerrm = 'sem_permissao';
  end;
  begin
    perform public.admin_pausar_banner(promo, null);
    assert false, 'só o Admin pausa';
  exception when raise_exception then
    assert sqlerrm = 'sem_permissao';
  end;
end $$;
set request.jwt.claim.sub = 'c0000000-0000-4000-a000-00000000000c';
do $$
declare promo uuid := (select id from public.promocoes where titulo = 'Semana do Gil');
begin
  assert (select denuncias = 1 and enganoso = 1 from public.admin_banners_denunciados() where promocao_id = promo);
  perform public.admin_manter_banner(promo);
  assert not exists (select 1 from public.admin_banners_denunciados()), 'manter limpa a fila';
  assert (select status from public.promocoes where id = promo) = 'ativa';
  perform public.denunciar_banner(promo, 'golpe');
  perform public.admin_pausar_banner(promo, 'Link suspeito');
  assert (select status from public.promocoes where id = promo) = 'pausada', 'pausar tira do ar';
  assert not exists (select 1 from public.admin_banners_denunciados());
  assert exists (select 1 from public.moderacoes where promocao_id = promo and acao = 'pausar_banner' and motivo = 'Link suspeito');
end $$;
reset role;

\echo '== Página da loja: só o que é público, só itens ativos e válidos, cadastros não aprovados escondidos'
reset role;
-- Cadastro pendente (com loja) e itens da Loja Centro: um vencido e um pausado pelo dono.
insert into public.pdvs (id, dono_id, cnpj, nome_fantasia, razao_social, modo_rede, status)
values ('90000000-0000-4000-b000-0000000000b1', '90000000-0000-4000-a000-000000000009', '55444777000161',
        'Loja Pendente', 'FULANO DE TAL 12345678900', true, 'pendente');
insert into public.lojas (id, pdv_id, endereco, cidade, uf)
values ('90000000-0000-4000-c000-0000000000b1', '90000000-0000-4000-b000-0000000000b1', 'Rua Particular, 1', 'Petrópolis', 'RJ');
insert into public.cotacoes (loja_id, produto, preco_centavos, validade, fonte)
values ('20000000-0000-4000-a000-000000000001', 'Produto Vencido Teste', 100, public.hoje() - 1, 'pdv_manual'),
       ('20000000-0000-4000-a000-000000000001', 'Produto Pausado Teste', 100, public.hoje() + 5, 'pdv_manual');
update public.cotacoes set pausado = true where produto = 'Produto Pausado Teste';
set role authenticated;
set request.jwt.claim.sub = 'a0000000-0000-4000-a000-00000000000a';
do $$
declare
  centro constant uuid := '20000000-0000-4000-a000-000000000001';
  n integer;
begin
  -- Cabeçalho: dados públicos da loja aprovada.
  assert (select pdv_nome = 'Supermercado Serra Imperial' and loja_nome = 'Loja Centro' and telefone is not null
          from public.pagina_loja(centro)), 'cabeçalho da loja';
  -- Preços: só a tabela oficial, ativos e válidos.
  n := (select count(*) from public.precos_da_loja(centro));
  assert n > 0, 'a loja tem preços';
  assert not exists (select 1 from public.precos_da_loja(centro) where produto in ('Produto Vencido Teste', 'Produto Pausado Teste')),
    'vencido e pausado não aparecem';
  assert not exists (select 1 from public.precos_da_loja(centro) p join public.cotacoes c on c.id = p.id
                     where c.fonte not in ('pdv_manual', 'pdv_excel')), 'só a tabela oficial';
  -- Busca dentro da loja.
  assert (select count(*) from public.precos_da_loja(centro, 'xyz inexistente')) = 0;
  assert (select count(*) from public.precos_da_loja(centro, (select split_part(produto, ' ', 1) from public.precos_da_loja(centro) limit 1))) >= 1;
  -- Cadastro pendente: nem a página, nem os preços, nem o cadastro, nem a loja.
  assert not exists (select 1 from public.pagina_loja('90000000-0000-4000-c000-0000000000b1'));
  assert not exists (select 1 from public.pdvs where id = '90000000-0000-4000-b000-0000000000b1'), 'pendente invisível';
  assert not exists (select 1 from public.lojas where id = '90000000-0000-4000-c000-0000000000b1'), 'loja do pendente invisível';
  -- Banners da loja: mesma regra da busca (função existe e não falha).
  perform * from public.banners_da_loja('10000000-0000-4000-a000-000000000001');
end $$;
-- A razão social e o id do dono não são legíveis direto pela API.
select pg_temp.espera_erro($q$select razao_social from public.pdvs limit 1$q$, 'permission denied for table pdvs');
select pg_temp.espera_erro($q$select dono_id from public.pdvs limit 1$q$, 'permission denied for table pdvs');
-- O dono do cadastro pendente continua vendo o próprio cadastro e a própria loja.
set request.jwt.claim.sub = '90000000-0000-4000-a000-000000000009';
do $$ begin
  assert exists (select 1 from public.pdvs where id = '90000000-0000-4000-b000-0000000000b1'), 'dono vê o próprio';
  assert exists (select 1 from public.lojas where id = '90000000-0000-4000-c000-0000000000b1'), 'dono vê a própria loja';
end $$;
reset role;
-- Estabelecimento suspenso: a página e os preços somem.
update public.pdvs set status = 'suspenso' where id = '10000000-0000-4000-a000-000000000001';
set role authenticated;
set request.jwt.claim.sub = 'a0000000-0000-4000-a000-00000000000a';
do $$ begin
  assert not exists (select 1 from public.pagina_loja('20000000-0000-4000-a000-000000000001')), 'suspenso: sem página';
  assert not exists (select 1 from public.precos_da_loja('20000000-0000-4000-a000-000000000001')), 'suspenso: sem preços';
end $$;
reset role;
update public.pdvs set status = 'aprovado' where id = '10000000-0000-4000-a000-000000000001';
delete from public.cotacoes where produto in ('Produto Vencido Teste', 'Produto Pausado Teste');

\echo '== Preço no app (clube de vantagens): só encarte/oficial, normal maior que o do app, NF intocada'
reset role;
-- Regra da tabela.
select pg_temp.espera_erro($q$insert into public.cotacoes (pdv_nome_livre, produto, preco_centavos, validade, fonte, preco_app, preco_normal_centavos)
  values ('Assaí Teste', 'Leite App Teste', 500, public.hoje() + 1, 'usuario_encarte', true, 500)$q$, 'cotacoes_preco_app_check');
select pg_temp.espera_erro($q$insert into public.cotacoes (pdv_nome_livre, produto, preco_centavos, validade, fonte, preco_normal_centavos)
  values ('Assaí Teste', 'Leite App Teste', 500, public.hoje() + 1, 'usuario_encarte', 0)$q$, 'cotacoes_preco_app_check');
select pg_temp.espera_erro($q$insert into public.cotacoes (pdv_nome_livre, produto, preco_centavos, validade, fonte, data_nf, preco_app, preco_normal_centavos)
  values ('Assaí Teste', 'Leite App Teste', 500, public.hoje() + 1, 'usuario_nf', public.hoje(), true, 600)$q$, 'cotacoes_preco_app_check');
insert into public.cotacoes (pdv_nome_livre, pdv_endereco_livre, produto, preco_centavos, validade, fonte, preco_app, preco_normal_centavos)
values ('Assaí Teste', 'Rua do Teste, 1', 'Leite App Teste 1L', 439, public.hoje() + 1, 'usuario_encarte', true, 489);
-- Preço oficial com preço no app aparece também na Página da loja.
insert into public.cotacoes (loja_id, produto, preco_centavos, validade, fonte, preco_app, preco_normal_centavos)
values ('20000000-0000-4000-a000-000000000001', 'Café App Teste 500g', 2389, public.hoje() + 5, 'pdv_manual', true, 2549);
set role authenticated;
set request.jwt.claim.sub = 'a0000000-0000-4000-a000-00000000000a';
do $$ begin
  assert (select preco_centavos = 439 and preco_app and preco_normal_centavos = 489
          from public.buscar_cotacoes('leite app teste')), 'busca devolve o preço do app e o normal';
  -- Ordenação e "menor preço" continuam por preco_centavos (o do app).
  assert (select preco_centavos from public.buscar_cotacoes('leite app teste') limit 1) = 439;
  assert (select preco_app and preco_normal_centavos = 2549
          from public.precos_da_loja('20000000-0000-4000-a000-000000000001', 'cafe app teste')), 'página da loja também';
  -- NF continua sem preço no app.
  assert not exists (select 1 from public.buscar_cotacoes() where fonte = 'usuario_nf' and (preco_app or preco_normal_centavos is not null));
  -- O app lê as duas colunas novas (grant por coluna).
  perform preco_normal_centavos, preco_app from public.cotacoes limit 1;
end $$;
-- Envio de encarte: suspenso para o app; o formato do item aceita o preço no app.
select pg_temp.espera_erro($q$select public.publicar_encarte(array['x/1.jpg'], null, 'X', null, public.hoje(), '[]')$q$,
  'permission denied for function publicar_encarte');
reset role;
do $$ begin
  assert public._itens_encarte_validos('[{"produto": "Açúcar 1kg", "preco_centavos": 299, "preco_app": true, "preco_normal_centavos": 359}]');
  assert public._itens_encarte_validos('[{"produto": "Açúcar 1kg", "preco_centavos": 359}]'), 'preço único continua valendo';
  assert not public._itens_encarte_validos('[{"produto": "Açúcar 1kg", "preco_centavos": 359, "preco_app": true, "preco_normal_centavos": 299}]'),
    'normal precisa ser maior que o do app';
  assert not public._itens_encarte_validos('[{"produto": "Açúcar 1kg", "preco_centavos": 299, "preco_app": true}]'),
    'preço no app exige o normal';
end $$;
-- Histórico guarda os dois preços; repetir o mesmo preço de pesquisa no mesmo dia continua ignorado.
do $$ begin
  assert exists (select 1 from public.historico_precos where produto = 'Leite App Teste 1L' and preco_app and preco_normal_centavos = 489);
  insert into public.cotacoes (pdv_nome_livre, pdv_endereco_livre, produto, preco_centavos, validade, fonte, preco_app, preco_normal_centavos)
  values ('Assaí Teste', 'Rua do Teste, 1', 'Leite App Teste 1L', 439, public.hoje() + 1, 'usuario_encarte', true, 499);
  assert (select count(*) from public.cotacoes where produto = 'Leite App Teste 1L') = 1, 'mesmo preço de pesquisa no dia: ignorado';
end $$;
delete from public.cotacoes where produto in ('Leite App Teste 1L', 'Café App Teste 500g');

\echo 'OK — todos os testes do banco passaram'
