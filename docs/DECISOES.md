# Decisões técnicas e premissas

Registro do que foi decidido ao montar a base do MVP, principalmente onde o briefing deixou margem
para interpretação. Os itens marcados com **⚠️ confirmar** são premissas que o fundador deve validar.

## Stack

| Escolha | Por quê |
|---|---|
| **Kotlin + Jetpack Compose** (Android nativo, minSdk 26 / Android 8.0) | Plataforma decidida no briefing. Compose é o padrão atual do Android. Android 8+ cobre praticamente todos os aparelhos em uso. |
| **Supabase** (Postgres + Auth + Storage) | Sem servidor próprio para manter. Login Google/e-mail pronto (e SMS/WhatsApp OTP via provedores no futuro). Postgres permite colocar as regras de cota e permissões no próprio banco. Plano gratuito cobre o piloto. |
| Regras de negócio **no banco** (funções SQL + RLS) | O app não consegue burlar a cota nem gravar preço direto: toda escrita em `cotacoes` passa por funções que validam. |
| Módulo **`core`** em Kotlin puro | Regras de apresentação e cálculo (OBS, ordenação, lista de compras, chave de NF, planilha) testáveis sem emulador. |
| Sem Hilt / sem navigation-compose por enquanto | Menos dependências e menos coisa para aprender; dá para adicionar quando o app crescer. |
| Dinheiro em **centavos** (inteiro) | Evita erro de arredondamento (R$ 0,10 + R$ 0,20 ≠ R$ 0,30 em ponto flutuante). |

## Busca de preços

- A busca ignora acento e maiúscula e exige **todas** as palavras: "feijao preto" acha
  "Feijão Preto 1kg"; "feijão 5kg" não acha nada.
- Sem termo, a tela mostra os **últimos preços lançados**, dos mais recentes para os mais antigos.
- Com termo, a ordem padrão é **menor preço**. As 5 ordenações do briefing são aplicadas no app;
  tocar de novo na ordenação ativa volta à ordem padrão.
- **Destaque do mais barato:** só quando há uma busca (na tela inicial os produtos são variados, não faz
  sentido). Em caso de empate, todos os empatados são destacados.
- **"Mais perto"**: usa a última localização conhecida do celular (pede permissão na hora). Sem
  permissão/GPS, usa o centro de Petrópolis como referência. PDV sem coordenadas vai para o fim.
- PDVs com a mesma rede aparecem uma vez **por loja** (cada loja tem endereço e telefone próprios).

## Validade

- Preço do PDV: validade de hoje até **no máximo 30 dias**. Se o PDV não informar, assume 30 dias.
- **⚠️ confirmar:** preço de usuário (NF/encarte) não tem validade informada. Assumimos que ele
  continua aparecendo na busca por **30 dias** após o envio e depois sai. Constante
  `dias_vigencia_preco_usuario()` no banco.
- O Admin pode informar uma validade ao aprovar um encarte (se o encarte trouxer).

## Cota de operações do PDV

- 50 grátis por mês (mês no fuso de São Paulo). Só **criar item** e **aumentar preço** contam.
  Diminuir preço, excluir e editar só OBS/validade são sempre grátis.
- Um produto é identificado pelo **nome normalizado** dentro da loja ("Arroz 5kg" = "ARROZ  5KG").
  Mudar o nome de um produto equivale a excluir o antigo (grátis) e criar um novo (conta 1).
- **Modo rede:** a alteração vale para todas as lojas ativas e conta **1** operação. Para decidir se é
  aumento, compara com o **maior** preço atual entre as lojas.
- **⚠️ confirmar — modo varejo:** interpretamos "a cota é contada por loja" como: cada loja editada
  consome **uma operação da mesma cota do PDV** (editar o mesmo preço em 3 lojas = 3 operações). A
  alternativa seria cada loja ter sua própria cota de 50 — é uma mudança pequena em `cota_status()`.
- **⚠️ confirmar:** o pacote de +50 operações (R$ 10) vale **só no mês em que foi comprado**.
- Importação de planilha é **tudo ou nada**: o app primeiro chama `pdv_salvar_precos(..., p_simular := true)`,
  que devolve quantas operações a importação vai custar e quantos pacotes faltam. Se não couber,
  o app mostra o Pix antes; se tentar gravar sem saldo, o banco recusa (`cota_excedida`).

## Nota Fiscal (V1 manual)

- Uma chamada `enviar_nota_fiscal` grava todos os itens da nota de uma vez (depois da tela de
  confirmação única). Limite técnico de 500 itens por nota, só para evitar abuso.
- Chave de acesso é opcional; se vier, precisa ter 44 dígitos e **não pode repetir** (mesma nota enviada
  duas vezes é recusada). O app também valida o dígito verificador (`ChaveAcessoNfe` no `core`) e já
  sabe extrair a chave da URL do QR Code da Sefaz-RJ, para quando a leitura do QR for implementada.
- CPF do comprador não é pedido nem guardado em lugar nenhum.

## Encartes, Admin e promoções

- Encarte de usuário comum: foto vai para o bucket privado `encartes/<id-do-usuário>/…` e entra na fila
  (`encartes_pendentes`). Só o Admin vê a fila e aprova (montando a lista de produtos/preços) ou rejeita.
- Encarte do próprio PDV usado como arte de banner: publicação imediata, não passa pela fila.
- Admin é definido **direto no banco** (ninguém consegue se promover pelo app).
- Promoção: segmentação por raio (a partir de uma loja) e/ou palavras-chave do termo buscado.
  Visualizações são contadas por `registrar_visualizacao_promocao`; ao esgotar, o banner sai.
- **Pix:** a criação da cobrança e o webhook do Mercado Pago ficarão numa Edge Function do Supabase
  (usa a chave secreta, nunca no app). O webhook chama `confirmar_pagamento`, que é idempotente.

## Segurança (resumo)

- Nada é acessível sem login.
- Preços são visíveis para qualquer usuário logado, mas só são gravados pelas funções do banco.
- Listas, pagamentos e encartes enviados: cada um vê só os seus.
- `operacoes_log` e `cota_status`: só o dono do PDV (e o Admin).
- Colunas sensíveis (tipo de usuário → admin, contadores de visualização, status de pagamento) não
  podem ser alteradas pelo app.

## Pendências herdadas do briefing (seção 10)

- Fluxo do login por WhatsApp (o Supabase suporta OTP por WhatsApp via Twilio — avaliar custo).
- Notificação ao Admin sobre novas pendências.
- Chave da API de mapas (para o relatório "no mapa" da lista de compras).
- Política de privacidade e termos de uso (obrigatórios para publicar na Play Store).
