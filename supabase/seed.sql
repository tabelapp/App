-- =============================================================================
-- Dados de TESTE para o piloto em Petrópolis/RJ.
-- Estabelecimentos e preços FICTÍCIOS (nomes inventados, ruas reais da cidade).
-- Rodado automaticamente por `supabase db reset` (ambiente local).
-- NÃO rodar em produção.
-- =============================================================================

-- Dono "de mentira" dos PDVs de demonstração (não consegue fazer login).
insert into auth.users (id, email, aud, role, raw_user_meta_data)
values ('00000000-0000-4000-a000-000000000001', 'demo-pdv@tabelapp.invalid',
        'authenticated', 'authenticated', '{"nome": "PDV Demonstração", "tipo": "cnpj"}')
on conflict (id) do nothing;

insert into public.pdvs (id, dono_id, cnpj, nome_fantasia, site, modo_rede) values
  ('10000000-0000-4000-a000-000000000001', '00000000-0000-4000-a000-000000000001',
   '11111111000191', 'Supermercado Serra Imperial', 'https://exemplo.invalid/serra', true),
  ('10000000-0000-4000-a000-000000000002', '00000000-0000-4000-a000-000000000001',
   '22222222000191', 'Mercado Quitandinha', null, false),
  ('10000000-0000-4000-a000-000000000003', '00000000-0000-4000-a000-000000000001',
   '33333333000191', 'Hortifruti Bingen', null, true),
  ('10000000-0000-4000-a000-000000000004', '00000000-0000-4000-a000-000000000001',
   '44444444000191', 'Empório Itaipava', null, true)
on conflict (id) do nothing;

insert into public.lojas (id, pdv_id, nome, endereco, bairro, telefone, latitude, longitude) values
  ('20000000-0000-4000-a000-000000000001', '10000000-0000-4000-a000-000000000001',
   'Loja Centro', 'Rua do Imperador, 500', 'Centro', '(24) 2222-0001', -22.5058, -43.1790),
  ('20000000-0000-4000-a000-000000000002', '10000000-0000-4000-a000-000000000001',
   'Loja Valparaíso', 'Rua Coronel Veiga, 1200', 'Valparaíso', '(24) 2222-0002', -22.5160, -43.1730),
  ('20000000-0000-4000-a000-000000000003', '10000000-0000-4000-a000-000000000002',
   null, 'Avenida Joaquim Rolla, 300', 'Quitandinha', '(24) 2222-0003', -22.5275, -43.2105),
  ('20000000-0000-4000-a000-000000000004', '10000000-0000-4000-a000-000000000003',
   null, 'Rua Bingen, 800', 'Bingen', '(24) 2222-0004', -22.5165, -43.1960),
  ('20000000-0000-4000-a000-000000000005', '10000000-0000-4000-a000-000000000004',
   null, 'Estrada União e Indústria, 11000', 'Itaipava', '(24) 2222-0005', -22.3865, -43.1335)
on conflict (id) do nothing;

-- Preços oficiais dos PDVs (fonte pdv_manual / pdv_excel).
insert into public.cotacoes (loja_id, produto, preco_centavos, validade, obs, fonte, created_at)
select l, p, preco, (now() at time zone 'America/Sao_Paulo')::date + dias, obs, fonte::public.fonte_cotacao,
       now() - make_interval(mins => minutos)
from (values
  -- Serra Imperial (modo rede: mesmo preço nas duas lojas)
  ('20000000-0000-4000-a000-000000000001'::uuid, 'Arroz Branco Tipo 1 5kg', 2490, 20, null, 'pdv_excel', 300),
  ('20000000-0000-4000-a000-000000000002'::uuid, 'Arroz Branco Tipo 1 5kg', 2490, 20, null, 'pdv_excel', 300),
  ('20000000-0000-4000-a000-000000000001'::uuid, 'Feijão Preto 1kg', 789, 20, null, 'pdv_excel', 300),
  ('20000000-0000-4000-a000-000000000002'::uuid, 'Feijão Preto 1kg', 789, 20, null, 'pdv_excel', 300),
  ('20000000-0000-4000-a000-000000000001'::uuid, 'Café Torrado e Moído 500g', 1899, 15, 'Leve 3 pague 2', 'pdv_manual', 120),
  ('20000000-0000-4000-a000-000000000002'::uuid, 'Café Torrado e Moído 500g', 1899, 15, 'Leve 3 pague 2', 'pdv_manual', 120),
  ('20000000-0000-4000-a000-000000000001'::uuid, 'Cerveja Pilsen Lata 350ml', 399, 7, 'Cerveja gelada', 'pdv_manual', 45),
  ('20000000-0000-4000-a000-000000000002'::uuid, 'Cerveja Pilsen Lata 350ml', 399, 7, 'Cerveja gelada', 'pdv_manual', 45),
  ('20000000-0000-4000-a000-000000000001'::uuid, 'Leite Integral 1L', 569, 10, null, 'pdv_manual', 200),
  ('20000000-0000-4000-a000-000000000002'::uuid, 'Leite Integral 1L', 569, 10, null, 'pdv_manual', 200),
  -- Mercado Quitandinha (modo varejo)
  ('20000000-0000-4000-a000-000000000003'::uuid, 'Arroz Branco Tipo 1 5kg', 2290, 10, 'Entrega grátis acima de R$100', 'pdv_manual', 90),
  ('20000000-0000-4000-a000-000000000003'::uuid, 'Feijão Preto 1kg', 849, 10, null, 'pdv_manual', 90),
  ('20000000-0000-4000-a000-000000000003'::uuid, 'Óleo de Soja 900ml', 749, 10, null, 'pdv_manual', 90),
  ('20000000-0000-4000-a000-000000000003'::uuid, 'Açúcar Refinado 1kg', 489, 10, null, 'pdv_manual', 90),
  ('20000000-0000-4000-a000-000000000003'::uuid, 'Leite Integral 1L', 599, 10, null, 'pdv_manual', 90),
  ('20000000-0000-4000-a000-000000000003'::uuid, 'Ovos Brancos Dúzia', 1190, 5, null, 'pdv_manual', 30),
  -- Hortifruti Bingen
  ('20000000-0000-4000-a000-000000000004'::uuid, 'Banana Prata kg', 699, 3, 'Fresquinha da serra', 'pdv_manual', 20),
  ('20000000-0000-4000-a000-000000000004'::uuid, 'Tomate kg', 899, 3, null, 'pdv_manual', 20),
  ('20000000-0000-4000-a000-000000000004'::uuid, 'Ovos Brancos Dúzia', 1090, 5, null, 'pdv_manual', 20),
  -- Empório Itaipava
  ('20000000-0000-4000-a000-000000000005'::uuid, 'Café Torrado e Moído 500g', 2150, 30, null, 'pdv_manual', 400),
  ('20000000-0000-4000-a000-000000000005'::uuid, 'Cerveja Pilsen Lata 350ml', 459, 30, null, 'pdv_manual', 400),
  ('20000000-0000-4000-a000-000000000005'::uuid, 'Açúcar Refinado 1kg', 529, 30, null, 'pdv_manual', 400)
) as v(l, p, preco, dias, obs, fonte, minutos)
on conflict do nothing;

-- Preços enviados por usuários comuns em PDVs NÃO cadastrados.
insert into public.cotacoes (pdv_nome_livre, pdv_endereco_livre, produto, preco_centavos, fonte,
                             chave_acesso_nf, created_at)
values
  ('Mercadinho Alto da Serra', 'Rua Teresa, 1500 - Alto da Serra', 'Arroz Branco Tipo 1 5kg', 2349,
   'usuario_nf', '33260911111111000191650010000012341000012346', now() - interval '2 hours'),
  ('Mercadinho Alto da Serra', 'Rua Teresa, 1500 - Alto da Serra', 'Feijão Preto 1kg', 759,
   'usuario_nf', '33260911111111000191650010000012341000012346', now() - interval '2 hours'),
  ('Sacolão Corrêas', 'Estrada União e Indústria, 3000 - Corrêas', 'Banana Prata kg', 599,
   'usuario_encarte', null, now() - interval '5 hours'),
  ('Sacolão Corrêas', 'Estrada União e Indústria, 3000 - Corrêas', 'Tomate kg', 799,
   'usuario_encarte', null, now() - interval '5 hours');
