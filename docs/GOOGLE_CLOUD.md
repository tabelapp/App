# Google Cloud: mapa e login com Google

Tudo num projeto só do Google Cloud. Os dois usam a mesma "impressão digital" (SHA-1) do app:

- Pacote: `br.com.tabelapp`
- SHA-1: `1D:12:6E:F5:31:74:5A:C0:91:89:DA:F3:A4:09:B6:36:C6:61:2F:A3`
  (assinatura dos APKs gerados pelo GitHub. Na Play Store haverá outra — adicionar quando publicar.)

Nenhuma chave precisa ser enviada por chat: elas vão direto para os *secrets* do GitHub e para o Supabase.

## A. Projeto
1. <https://console.cloud.google.com>, logado com a conta Google do Tabelapp.
2. Seletor de projeto (topo) → **Novo projeto** → nome `Tabelapp` → **Criar** → selecione o projeto.

## B. Mapa ("Ver no mapa")
3. ☰ → **Faturamento** → vincule uma conta de faturamento (cartão). O mapa no app Android é gratuito;
   recomendado: *Orçamentos e alertas* → orçamento de R$ 10 com alerta por e-mail.
4. ☰ → **APIs e serviços** → **Biblioteca** → `Maps SDK for Android` → **Ativar**.
5. **APIs e serviços** → **Credenciais** → **+ Criar credenciais** → **Chave de API** → copie a chave.
6. Na chave: **Editar** →
   - Nome: `Tabelapp Android - Mapa`
   - *Restrições do aplicativo*: **Apps Android** → **Adicionar** → pacote e SHA-1 acima
   - *Restrições de API*: **Restringir chave** → só **Maps SDK for Android** → **Salvar**
7. GitHub → repositório → **Settings** → **Secrets and variables** → **Actions** →
   **New repository secret**: nome `MAPS_API_KEY`, valor = a chave.

## C. Login com Google
8. ☰ → **APIs e serviços** → **Tela de permissão OAuth** (ou *Google Auth Platform*) → **Começar**:
   - Nome do app: `Tabelapp`; e-mail de suporte: o seu
   - Público: **Externo**
   - Contato: seu e-mail → aceitar a política → **Criar**
   - Não envie logotipo agora (logotipo exige verificação do Google, que demora dias).
9. **Público** → **Publicar app** → confirmar. (Em "teste", só e-mails cadastrados conseguem entrar.
   Com os dados básicos — nome, e-mail e foto — não há verificação.)
10. **Clientes** → **Criar cliente** → tipo **Android** → nome `Tabelapp Android`, pacote e SHA-1 acima →
    **Criar**. (Não há nada para copiar deste.)
11. **Clientes** → **Criar cliente** → tipo **Aplicativo da Web** → nome `Tabelapp Supabase` →
    em *URIs de redirecionamento autorizados* adicione `https://<seu-projeto>.supabase.co/auth/v1/callback`
    (o endereço do seu Supabase) → **Criar** → copie o **ID do cliente** e a **Chave secreta do cliente**.
12. Supabase → **Authentication** → **Sign In / Providers** → **Google** → ativar →
    *Client IDs*: o ID do cliente **Web**; *Client Secret*: a chave secreta → **Save**.
13. GitHub → **Secrets** → **New repository secret**: nome `GOOGLE_WEB_CLIENT_ID`, valor = o ID do
    cliente **Web** (o mesmo do passo 12; não é o do Android).

## D. Novo APK
14. Peça ao Claude um build novo (ou GitHub → **Actions** → último **CI** → **Re-run all jobs**). Instale o `tabelapp-apk` novo.

## Problemas comuns
- **Mapa cinza, sem ruas:** chave errada, Maps SDK não ativado, faturamento não vinculado ou SHA-1/pacote
  diferentes na restrição da chave. Pode levar até 5 minutos para valer.
- **"Não foi possível entrar com o Google":** cliente Android sem o SHA-1 certo, ou o secret
  `GOOGLE_WEB_CLIENT_ID` com o ID do cliente Android em vez do Web, ou o app ainda "em teste".
- Sem a chave do mapa, "Ver no mapa" mostra uma lista dos locais (tocar abre o Google Maps).

**Endereços no mapa:** lojas cadastradas usam a coordenada do cadastro (se houver); os demais (notas
fiscais) são localizados pelo endereço no próprio celular (Geocoder do Android, sem chave e sem custo).
