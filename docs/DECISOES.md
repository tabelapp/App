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

## Validade (confirmado pelo fundador)

Todo preço tem validade, e a busca só mostra preços dentro dela.

| Fonte | Validade |
|---|---|
| PDV (manual ou planilha) | de hoje até no máximo 30 dias; se não informar, 30 dias |
| Nota Fiscal | O preço é o **praticado na data da nota** (vale até a meia-noite daquele dia). Na busca, o campo validade mostra **"Preço praticado dia dd/mm/aaaa"** (a data da NF) e o preço **fica visível por 7 dias** a partir dela. Só são aceitas notas dos últimos 7 dias; a data precisa bater com o mês/ano de emissão da chave. |
| Encarte de usuário | **a data impressa no encarte** (até 30 dias). O app tenta ler a data nas fotos ("válido até 30/09", "ofertas de 20 a 26/09"); se não achar, o usuário escolhe no calendário. Sem validade, não publica. |

## Cadastro do PDV (decisão do fundador: comprovação pelo alvará)

Para ninguém cadastrar uma empresa que não é sua:

1. A conta precisa ser do tipo **CNPJ**. O dono digita o CNPJ (dígitos verificadores conferidos) e o
   app consulta a **Receita Federal** pela BrasilAPI (gratuita, sem chave): só CNPJ **ativo** segue.
   Nome, endereço e telefone vêm preenchidos; o que a Receita respondeu fica guardado para o Admin.
2. O dono envia a **foto do alvará** (bucket privado `alvaras/<id-do-usuário>/…`, só ele e o Admin
   veem). O app lê a foto no celular (OCR do Google, baixado pelo Google Play) e diz se achou o CNPJ.
3. O pedido fica **pendente**; só o **Admin aprova** (aba "Admin": dados informados × Receita, foto
   do alvará, se o app achou o CNPJ) ou **rejeita com motivo** — o dono vê o motivo e pode reenviar.
4. Enquanto não for aprovado, o PDV **não publica preço nem promoção** e suas lojas não aparecem para
   ligar notas fiscais (trava no banco, não só no app). O Admin pode suspender um PDV aprovado depois
   (fraude descoberta): os preços oficiais dele saem da busca.
5. Ninguém cria PDV direto na tabela, nem muda CNPJ/situação pelo app; alvará, dados da Receita e
   motivo de rejeição não são visíveis para outros usuários.
6. CNPJ já aprovado não pode ser cadastrado de novo; CNPJ com pedido pendente de outra pessoa também
   não (fica para o suporte resolver). Pedido rejeitado pode ser refeito por qualquer um.

- **Por que o app não aprova sozinho:** a leitura roda no celular, que pode ser adulterado — o
  resultado é só um indício para o Admin. E o alvará costuma ficar **exposto na parede da loja**:
  qualquer pessoa consegue fotografá-lo. Por isso a aprovação é humana. ⚠️ Se o volume crescer,
  dá para automatizar no servidor (OCR na nuvem, ex.: Google Cloud Vision) e/ou exigir uma prova
  mais forte (Pix de R$ 1 feito da conta bancária da empresa, que traz o CNPJ de quem pagou).

## Cota de operações do PDV (confirmado pelo fundador)

- **Cada loja tem 50 operações grátis por mês** (mês no fuso de São Paulo). Só **criar item** e
  **aumentar preço** contam. Diminuir preço, excluir e editar só OBS/validade são sempre grátis.
- **Pacote de +50 operações (R$ 10) vale 30 dias a partir do pagamento** e é comprado para uma loja
  específica. O consumo usa primeiro as grátis do mês, depois o pacote que vence antes. Cada operação
  registra de onde saiu (`operacoes_log.pagamento_id`).
- Um produto é identificado pelo **nome normalizado** dentro da loja ("Arroz 5kg" = "ARROZ  5KG").
  Mudar o nome de um produto equivale a excluir o antigo (grátis) e criar um novo (conta 1).
- **⚠️ confirmar — modo rede:** a rede inteira funciona como uma loja só: **uma cota de 50** (e pacotes
  comprados para a rede), e cada alteração replicada para todas as lojas conta **1**. Para decidir se é
  aumento, compara com o **maior** preço atual entre as lojas. Se preferir que a rede tenha 50 × número
  de lojas, é uma mudança pequena em `cota_status()`.
- Trocar o PDV entre modo rede e varejo no meio do mês começa a contar na carteira do novo modo.
- Importação de planilha é **tudo ou nada**: o app primeiro chama `pdv_salvar_precos(..., p_simular := true)`,
  que devolve quantas operações a importação vai custar, o saldo da loja e quantos pacotes faltam. Se
  não couber, o app mostra o Pix antes; se tentar gravar sem saldo, o banco recusa (`cota_excedida`).

## Nota Fiscal pelo QR Code (sem digitação — decisão do fundador)

1. O usuário lê o QR Code do cupom com o **leitor do Google Play Services** (tela pronta do Google,
   não precisa pedir permissão de câmera). Se o leitor não abrir, dá para colar o **link** do QR Code.
   Só a chave de 44 dígitos não basta: a consulta na Sefaz precisa do link completo.
2. A chave dentro do QR é validada (44 dígitos + dígito verificador) e precisa ser do RJ.
3. O app abre o link da Sefaz-RJ **dentro do próprio celular** (WebView, na conexão do usuário — o portal
   bloqueia servidores, briefing seção 8) e lê produtos, preços unitários, estabelecimento e data da
   página (`LeitorNfce` no `core`). O portal redireciona para uma página em `http://`; o app libera
   texto claro **só** para `*.fazenda.rj.gov.br` (`network_security_config.xml`).
4. O CNPJ do emitente vem na própria chave. Se for de um PDV cadastrado, a nota é ligada à loja (se a
   rede tiver várias lojas, o usuário escolhe qual — a única escolha da tela). O banco recusa ligar a
   chave a uma loja de outro CNPJ (`loja_nao_confere`).
5. **Uma única tela de resumo** com tudo o que foi lido; o usuário só confirma o envio.
   **Não há como digitar produto, preço, estabelecimento ou data**: o que vai para a busca é
   exatamente o que está na nota. Se a leitura falhar, a nota não é enviada (tentar de novo / ler outra),
   e o usuário pode mandar a página para análise (sem scripts, CPF mascarado).
   Linhas repetidas (mesmo produto e preço) viram uma.

## Nota Fiscal — regras gerais

- Uma chamada `enviar_nota_fiscal` grava todos os itens da nota de uma vez (depois da tela de
  confirmação única), com a data da compra (lida da Sefaz ou informada pelo usuário; padrão hoje). Limite técnico de 500 itens por nota, só para evitar abuso.
- Chave de acesso é opcional; se vier, precisa ter 44 dígitos e **não pode repetir** (mesma nota enviada
  duas vezes é recusada). O app também valida o dígito verificador (`ChaveAcessoNfe` no `core`).
- CPF do comprador não é pedido nem guardado em lugar nenhum.

## Encartes, Admin e promoções

- **⏸️ Envio de encarte pelo usuário: SUSPENSO** (decisão do fundador — vulnerável a preço errado
  ou inventado). A aba saiu do app, a função `publicar_encarte` não pode mais ser chamada pelo app/API
  (migração `20260924000900`) e o leitor (`LeitorEncarte`, no `core`, com testes) ficou guardado. O
  app anterior com a aba está no histórico do Git (commit `e9fc8a4`). Como funcionava:
- Encarte de usuário comum (**decisão do fundador: sem fila do Admin, sem digitação**):
  1. O usuário tira ou escolhe **até 5 fotos** (câmera ou galeria).
  2. O app **lê o texto das fotos no próprio celular** com o OCR do Google (ML Kit, modelo embutido no
     app: gratuito, sem internet, nada vai para terceiros). O `LeitorEncarte` do `core` usa a
     **posição** de cada linha para ligar cada preço ao nome do produto (nome acima ou à esquerda,
     nome em duas linhas, centavos em fonte menor "4," + "99", "De R$ 6,99 Por R$ 4,99" vale o
     "por", preço "kg"), e procura a validade impressa.
  3. **Uma tela de resumo** com os produtos e preços lidos: o usuário **só pode desmarcar** o que foi
     lido errado — não digita produto nem preço. Informa o estabelecimento (busca os cadastrados, ou
     nome livre + endereço) e confere a validade (lida do encarte ou escolhida no calendário).
  4. Ao confirmar, as fotos (reduzidas para 1600 px / JPEG 80%) vão para o bucket privado
     `encartes/<id-do-usuário>/…` (guardadas para conferência) e a função `publicar_encarte` grava os
     preços **direto na busca** (fonte `usuario_encarte`), validade = data do encarte (hoje até 30
     dias). Máx. 200 produtos por encarte e 10 encartes por usuário por dia; produto repetido entra uma vez.
  5. "Meus encartes" mostra o que o usuário publicou e quantos preços cada um gerou.
  - ⚠️ OCR de encarte erra (layout livre, letras decorativas). A confirmação do usuário é a trava; se
    a qualidade não bastar, o próximo passo é ler as fotos com IA de visão (mais precisa, mas tem custo
    por foto e precisa de uma chave de API guardada numa Edge Function do Supabase).
  - As funções de fila do Admin (`aprovar_encarte` / `rejeitar_encarte`) continuam no banco, mas o
    app não manda mais encartes para a fila.
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
