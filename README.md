# Tabelapp

**Quem pesquisa economiza.** App Android de pesquisa colaborativa de preços, conectando quem pesquisa
(CPF) com estabelecimentos (CNPJ/PDV). Piloto em Petrópolis/RJ.

Este repositório tem a **base do MVP**: banco de dados completo com as regras de negócio do briefing,
regras do app em Kotlin testadas, e o app Android com login/cadastro e a tela de busca de preços.

## O que já funciona

| Parte | Situação |
|---|---|
| Banco de dados (todas as tabelas da seção 7) | ✅ pronto e testado |
| Regras no banco: cota de 50 operações por loja, pacotes de 30 dias, modo rede/varejo, validades (PDV 30 dias, NF 7 dias a partir da data da nota, encarte), NF com vários itens, encarte publicado direto, promoções, Pix confirmado | ✅ pronto e testado (falta a tela no app) |
| Login por e-mail e Google, cadastro CPF/CNPJ | ✅ no app |
| Tela de busca: últimos preços, busca, 5 ordenações, destaque do mais barato, regra do OBS, PDV clicável, pop-up de aviso | ✅ no app |
| Modo demonstração (roda sem servidor, com dados fictícios de Petrópolis) | ✅ |
| Login por WhatsApp | ⏳ botão "em breve" — fluxo técnico ainda não decidido |
| Lista de compras (3 relatórios) | ⏳ cálculo pronto no `core`, falta a tela |
| Envio de NF pelo QR Code: lê produtos, preços e data na Sefaz no próprio celular; tela única de resumo e confirmação, sem digitação | ✅ no app |
| Envio de encarte: até 5 fotos, o app lê produtos, preços e validade (OCR no celular), o usuário confere (só desmarca, não digita) e os preços vão direto para a busca; "Meus encartes" | ✅ no app |
| Área do PDV, promoções, Pix | ⏳ banco pronto, falta a tela e a integração Mercado Pago |
| Painel Admin | ⏳ banco pronto, falta a tela |

## Como o projeto está organizado

```
app/        App Android (Kotlin + Jetpack Compose)
core/       Regras de negócio em Kotlin puro (sem Android) + testes
supabase/   Banco de dados: migrações, dados de teste (seed) e testes SQL
scripts/    testar-banco.sh — roda as migrações e os testes num Postgres
docs/       Decisões técnicas e premissas assumidas
```

- **Backend: [Supabase](https://supabase.com)** (Postgres + login + armazenamento de fotos). Não há
  servidor próprio para manter: as regras (cota, validade, permissões) ficam no banco, em funções SQL e
  políticas de segurança (RLS). Detalhes em [`docs/DECISOES.md`](docs/DECISOES.md).
- **App:** Kotlin + Jetpack Compose, Android 8.0 ou mais novo.

## Rodando o app

### 1. Só para ver funcionando (modo demonstração)

Abra a pasta no **Android Studio** e dê *Run*. Sem configurar nada, o app sobe em **modo demonstração**:
qualquer e-mail/senha entra e os preços são fictícios de Petrópolis.

Cada push no GitHub também gera um APK de debug (aba *Actions* → último run → artefato `tabelapp-debug-apk`).

### 2. Com o banco de verdade (Supabase)

1. Crie um projeto em [supabase.com](https://supabase.com) (plano gratuito serve para o piloto).
2. Aplique as migrações. Pelo [Supabase CLI](https://supabase.com/docs/guides/cli):
   ```bash
   supabase login
   supabase link --project-ref <id-do-projeto>
   supabase db push
   ```
   Ou, mais simples: abra `supabase/instalar_tudo.sql` (todas as migrações num arquivo só), copie
   tudo, cole no *SQL Editor* do painel e clique em *Run* — só em projeto novo/vazio.
3. (Opcional) Para ter dados de teste, rode `supabase/seed.sql` no *SQL Editor*. **Não use em produção.**
4. Crie o arquivo `local.properties` na raiz do projeto (ele não vai para o Git):
   ```properties
   SUPABASE_URL=https://<id-do-projeto>.supabase.co
   SUPABASE_ANON_KEY=<chave anon, em Project Settings → API>
   # Opcional, para o botão "Entrar com Google":
   GOOGLE_WEB_CLIENT_ID=<client id do tipo "Web application" no Google Cloud>
   ```
5. Para o **login com Google**: no Google Cloud crie um OAuth Client *Web* (o ID vai acima) e um
   *Android* (com o SHA-1 da sua chave de assinatura); no Supabase, em *Authentication → Providers →
   Google*, ative e informe o client ID Web.
6. Para o piloto, pode valer desligar *Confirm email* em *Authentication → Providers → Email* —
   senão o usuário precisa clicar no link do e-mail antes de entrar.
7. **Conferir a conexão:** `scripts/testar-conexao-supabase.sh` (lê as chaves do `local.properties`) —
   diz se o projeto responde, se a chave está certa e se todas as migrações foram aplicadas.
8. **Virar Admin:** no *SQL Editor*,
   `update public.usuarios set tipo = 'admin' where email = 'seu@email';`

## Testes

```bash
./gradlew :core:test          # regras de negócio (OBS, cota, ordenação, lista de compras, NF, planilha...)
scripts/testar-banco.sh       # migrações + seed + testes de regras e permissões no Postgres
```

`testar-banco.sh` precisa de um Postgres 14+ acessível (use `PGHOST`, `PGUSER`, `PGPASSWORD`). Numa
máquina sem Android SDK, rode só o core com `./gradlew -PsemAndroid=true :core:test`.

A CI do GitHub roda os dois e compila o APK a cada push.

## Próximos passos sugeridos

1. Testar o login e a busca com um projeto Supabase real.
2. ✅ QR Code testado com notas reais. Testar a leitura de encartes reais (OCR) e ajustar o `LeitorEncarte`.
3. Painel Admin (moderação: remover preços errados, usuários abusivos).
4. Área do PDV: cadastro de lojas, tabela de preços, importação de planilha com prévia da cota.
5. Lista de compras + mapa (precisa de chave da API de mapas).
6. Pix via Mercado Pago (Edge Function + webhook chamando `confirmar_pagamento`).
