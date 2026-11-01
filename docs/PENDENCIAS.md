# Revisão — bugs, riscos e pendências (09/10/2026)

## Corrigido nesta revisão

| # | Problema | Correção |
|---|---|---|
| 1 | **Ao ler o QR Code, o app voltava para a Busca** antes de abrir a Sefaz (era preciso voltar em "Enviar NF"). Ao retornar do leitor do Google, a biblioteca do Supabase reinicia a sessão; o app mostrava "carregando" e redesenhava as abas do zero. | Quem já está logado não volta mais ao "carregando"; e a aba escolhida fica guardada fora das telas que somem. |
| 2 | Na entrada manual da chave, a pessoa podia consultar **outra nota** dentro da página da Sefaz e enviar os produtos dela com a chave digitada. | O app confere a chave que aparece na página da Sefaz com a chave informada. |
| 3 | O servidor aceitava **quaisquer 44 números** como chave e notas **sem chave**: um app adulterado poderia encher a busca de preços falsos. | Chave obrigatória, com dígito verificador conferido no servidor, modelo NFC-e/NF-e, só do RJ; **máx. 30 notas por pessoa por dia**. |
| 4 | Qualquer usuário podia chamar o registro de visualização do banner em sequência e **"queimar" as visualizações pagas** de um concorrente. | Conta no máximo **1 visualização por pessoa por banner por dia**. |
| 5 | Qualquer usuário conseguia ver **quem enviou** cada preço (o código interno da conta). | Coluna escondida dos usuários. |
| 6 | **LGPD:** ao excluir a conta, o código do usuário continuava no histórico de preços. | O preço continua na história, mas sem identificar quem enviou. |

## Ainda em aberto

### Precisa de teste com nota/celular real
- **Entrada manual da chave:** endereço e formulário da consulta da Sefaz-RJ por chave não puderam ser
  conferidos daqui. Se a chave não for preenchida sozinha, é só colar (o app copia).
- **Cópia em PDF da nota:** técnica de "imprimir em PDF" sem a tela de impressão — conferir em aparelhos
  diferentes.
- **Leitura da página da Sefaz:** foi feita a partir do padrão conhecido do portal; se a Sefaz mudar o
  layout, a leitura falha (há o botão "Enviar página para análise").

### Crescimento para outras cidades e estados
- **NF de outros estados:** hoje o app lê só notas do RJ (código 33 na chave). Cada estado tem o seu portal
  da Sefaz, com página e formato próprios: cada novo estado precisa de um leitor e de testes com notas reais.
  O restante do app (busca, mapa, cadastro de lojas e anunciantes) já funciona em qualquer cidade.
- **Arte do banner por IA:** possível (ex.: geração de imagem por API, chamada de uma função no servidor
  para não expor a chave), com custo por imagem. Alternativa sem custo: modelos prontos com o título, o
  preço e a foto do produto montados no próprio app.

### Configuração (painel do Supabase / contas externas)
- **E-mail:** o serviço gratuito do Supabase só entrega para membros do projeto e poucos por hora.
  Sem um SMTP próprio, o **"Esqueci a senha" não chega para os usuários**. Caminho gratuito sem domínio:
  SMTP do Gmail `tabelapp.economia@gmail.com` com Senha de app (`smtp.gmail.com`, porta 465). Também
  colar o modelo do e-mail e deixar o **Email OTP Length em 6** (passo a passo em `docs/EMAIL_SENHA.md`).
  Resend/Brevo só quando houver domínio próprio.
- **Supabase gratuito pausa após 7 dias sem uso.** Para produção: plano pago (US$ 25/mês) ou uma
  rotina que acesse o projeto. Plano gratuito também não tem backup automático.
- **Login com Google e mapa:** criar as credenciais no Google Cloud — passo a passo em `docs/GOOGLE_CLOUD.md`.
- **Login por WhatsApp:** ainda sem definição (custo do provedor de SMS/WhatsApp).

### Riscos aceitos (documentados)
- **Confirmação de e-mail desligada:** cadastro mais fácil, mas qualquer um cria várias contas. Pesa na
  confirmação de nome de estabelecimento por "2 pessoas" (mitigado: cada pessoa precisa de uma **nota
  diferente** daquele CNPJ — uma chave só pode ser enviada uma vez) e no limite diário de notas.
- **Preços de NF são lidos no celular:** um app adulterado ainda pode enviar preços falsos com uma chave
  verdadeira de nota recente. O servidor não consegue consultar a Sefaz (bloqueio a servidores).
- **Alvará:** pode estar exposto na parede da loja; por isso a aprovação do PDV é sempre humana.

### Funcionalidades que faltam para o lançamento
1. **Pix automático** (Mercado Pago ou Efí) para os banners. Hoje o pagamento é combinado pelo WhatsApp
   e o **Admin confirma** na aba Admin. **Planos do catálogo:** sem cobrança até decidir as regras da
   Google Play (`interesse_planos` guarda quem quer ser avisado).
2. **Área do PDV:** cadastro de mais lojas e troca de modo rede/varejo (editar dados, planilha e promoções já
   estão no app).
3. Promoção encerrada que já foi paga fica "Pausada" no histórico (não é apagada, por ser registro financeiro);
   ainda não há "reativar".
4. **Moderação do Admin no app:** remover preço errado, suspender um PDV já aprovado (hoje só pelo banco).
5. **Conta do usuário:** editar nome e **excluir a conta pelo app** — exigência da Play Store.
6. **Play Store:** política de privacidade e termos de uso, chave de assinatura de produção (hoje só a de
   debug), ícone/telas da loja, classificação etária.
7. **Link de download** usado no compartilhamento (hoje aponta para a futura página da Play Store).
8. **Mapa:** "Ver no mapa" na busca está pronto, mas precisa da chave do Google Maps (`docs/GOOGLE_CLOUD.md`).
   Falta o relatório "no mapa" da lista de compras (usa a mesma tela).
9. Envio de **encarte** por usuário: suspenso por decisão do fundador.

### Limitações conhecidas (menores)
- A busca mostra até 100 resultados (sem "carregar mais").
- Na lista de compras, termos genéricos ("arroz") pegam o mais barato de qualquer tamanho/marca — por
  isso a busca inteligente sugere nomes completos.
- Sair da aba "Enviar preços" no meio da leitura recarrega a página da Sefaz ao voltar.
- Arquivos (alvarás) de contas excluídas continuam no armazenamento.

## Para a produção (depois do teste fechado) — 30/10/2026

- **R8 / minificação** (`isMinifyEnabled = true`) com as regras de Supabase, Ktor e serialização, e um teste
  completo do app depois. Não entra na 1.0.2.
- **Filtro de itens proibidos** (drogas, armas, medicamentos controlados…) nos preços de nota e da tabela
  do PDV, exigido pela política de conteúdo da Play para marketplace/listas de produtos. Não entra na 1.0.2.
- **Login com Google:** cadastrar `GOOGLE_WEB_CLIENT_ID` e as SHA-1 (inclusive a do Play App Signing) no
  Google Cloud. Hoje o botão fica escondido (o secret não existe).
- **Chave do Maps (`MAPS_API_KEY`)**: depende de cartão no Google Cloud. Sem ela, "Ver no mapa" mostra o
  aviso de mapa não configurado.
- **E-mail de "Esqueci a senha"**: SMTP do Gmail com Senha de app + Email OTP Length = 6 (`docs/EMAIL_SENHA.md`).
- **NFC-e de SP (35) e MG (31)**: estrutura pronta (`core/.../EstadosNfce.kt`), estados desligados. Para ligar
  cada um, preciso de: (1) o endereço da consulta pública pela chave (o que vem impresso no cupom);
  (2) uma chave real de exemplo (44 números); (3) o HTML salvo da página de consulta já com os produtos
  (no navegador do computador: abrir o link do QR → Ctrl+S → "Página da Web, completa"); (4) a página de
  QR Code, se for diferente. Com isso: leitor + testes no app, domínio no `network_security_config.xml`
  se a Sefaz usar http, e o código no `nfce_ufs_aceitas` do banco.
