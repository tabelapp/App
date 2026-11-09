# Checklist do MVP (atualizado em 09/11/2026)

## A. Desenvolvimento (Claude)
Essenciais para lançar:
- [x] **Conta do usuário:** editar nome e **excluir a conta pelo app** ("Minha conta", no menu da busca).
- [x] **Política de privacidade e termos de uso:** textos em `app/src/main/res/raw/*.md`, no menu, em Minha conta
      e com aceite no cadastro. Páginas web em `site/` (GitHub Pages, ver docs/PUBLICACAO.md).
- [x] **Moderação do Admin no app:** preços denunciados (Remover/Manter), "Remover este preço" no ⓘ do card,
      suspender/reativar um PDV aprovado com motivo (o dono vê em "Meu negócio").
- [x] **Coordenadas das lojas cadastradas:** o painel do PDV localiza o endereço e grava no banco (loja nova,
      endereço editado, lojas antigas); "Estou na loja" usa o GPS; botão do Admin para as que faltam.
- [x] **Versão para a Play Store:** automação "Versão para a Play Store" (AAB + APK de produção, número de
      versão automático, assinatura pelos secrets ASSINATURA_*). 1.0.2 publicada; 1.0.3 e 1.0.4 geradas.
- [x] **1.0.4:** aba "Ler nota" amarela que abre a câmera direto; mensagem de nota incompatível com a SeFaz;
      arte de compartilhar com a
      Tabelinha, a lupa e o selo "no app". (Leitor de encartes na aba Admin: adiado.)

Importantes, podem vir logo depois:
- [ ] **Pix automático** (Mercado Pago ou Efí) — hoje o pagamento é manual (WhatsApp + confirmação do Admin).
- [ ] Lista de compras: relatório **"no mapa"** (reaproveita a tela do mapa).
- [x] PDV: cadastrar mais lojas (um CNPJ por loja) e ligar/desligar o modo rede (1.0.3).
- [ ] Promoção pausada: botão "reativar".
- [ ] Busca: "carregar mais" além de 100 resultados.
- [ ] **Dashboard do Admin:** usuários inscritos, PDVs cadastrados, notas lançadas e produtos cadastrados.

## B. Configuração e decisões (fundador) — atualizado em 15/10/2026
Contas e publicação:
- [ ] **Decidir a conta que publica** (própria, com ou sem CNPJ). Dá para transferir o app depois.
- [x] **Juntar o trabalho na branch principal (`main`)** (PRs #6, #7 e o da 1.0.4).
- [x] **Google Play Console:** conta criada; 1.0.2 publicada.
- [ ] Ficha da loja completa no **Google Play Console:** conta (US$ 25, uma vez), ficha da loja (ícone 512×512, imagem 1024×500, 2+ telas,
      descrição), classificação etária e "Segurança dos dados" (sugestão em `docs/PLAY_STORE.md`).
- [x] **Chave de envio** (`scripts/criar-chave-envio.sh`) + secrets `ASSINATURA_KEYSTORE_BASE64`,
      `ASSINATURA_SENHA`, `ASSINATURA_ALIAS` no GitHub.
- [ ] Primeiro envio em **Teste interno**; depois, cadastrar no Google Cloud a **SHA-1 da assinatura do Google**
      e a da chave de envio (mapa e login com Google).
- [ ] **Google Play e cobrança:** confirmar se planos e banners pagos fora do app (Pix pelo WhatsApp) atendem às
      regras da Play Store, antes de divulgar os planos.

Serviços:
- [ ] **Google Cloud:** chave do mapa + login com Google (`docs/GOOGLE_CLOUD.md`); secrets `MAPS_API_KEY` e
      `GOOGLE_WEB_CLIENT_ID` no GitHub; ativar o provedor Google no Supabase.
- [x] **E-mail próprio (SMTP):** Gmail `tabelapp.economia@gmail.com` com Senha de app (`smtp.gmail.com`,
      porta 465) + modelo de `docs/EMAIL_SENHA.md` + **Email OTP Length = 6** — sem isso o "Esqueci a
      senha" não chega para os usuários. (Resend/Brevo só com domínio próprio.)
- [ ] **Supabase de produção:** plano pago (não pausa, tem backup) ou rotina que mantenha ativo.
- [ ] **Sua conta como Admin** no banco (SQL do README, passo 9), se ainda não fez.

Jurídico e negócio:
- [x] **Publicar a Política de Privacidade e os Termos** num endereço web (site publicado no GitHub Pages).
- [ ] Revisão de advogado da Política de Privacidade e dos Termos.
- [ ] **Recebimento por Pix:** chave Pix e rotina de conferir o pagamento antes de ativar plano/banner no Admin.
- [ ] Conferir o **WhatsApp do Tabelapp** em `configuracao_app.whatsapp_contato` (usado em "Assinar pelo WhatsApp").
- [ ] Definir o **link de download** usado no compartilhamento.
- [ ] Ter **estabelecimentos e preços** cadastrados antes de divulgar (a busca vazia desanima quem chega).

## C. Testes no celular (fundador + Claude)
- [ ] Abertura (lupa no fundo escuro), tema escuro em todas as telas, cadastro e login (e-mail e Google),
      "Esqueci a senha", Minha conta (editar nome, excluir conta de teste).
- [ ] NF pela câmera e pela chave digitada (Sefaz) e o compartilhamento da NF-e em PDF.
- [ ] Busca: bloco de busca e autocompletar, Filtros, cards, selo e contorno do mais barato, ⓘ e "Esse preço não
      confere", WhatsApp, compartilhar, os dois banners, "Ver no mapa".
- [ ] Lista de compras e os dois relatórios.
- [ ] Cadastro de loja (alvará) e de anunciante (comprovante do CNPJ) → aprovação no Admin → painel.
- [ ] Painel da loja: itens, planilha, "Loja no mapa" / "Estou na loja", promoção (criar, pagar, editar, excluir).
- [ ] Planos: faixa do teste, avisos (fim, tolerância, pausados — simular por SQL), pausar/reativar item,
      "Assinar pelo WhatsApp", Admin ativando/renovando/cancelando plano.
- [ ] Admin: cadastros pendentes, nomes sugeridos, pagamentos, preços denunciados, suspender/reativar,
      lojas sem pino.

## Fora do MVP (decididos)
- Envio de encarte por usuário — suspenso.
- Login por WhatsApp/SMS — sem definição (custo do provedor).
