# E-mail de "Esqueci a senha" (Supabase)

O app pede um **código de 6 números** (não um link). No painel do Supabase:
**Authentication → Email Templates → Reset Password**. Troque o assunto e o texto por:

**Assunto:**

```
Seu código para criar uma nova senha no Tabelapp
```

**Corpo (HTML):**

```html
<h2>Tabelapp — nova senha</h2>
<p>Recebemos um pedido para criar uma nova senha para a sua conta.</p>
<p>Digite este código no app:</p>
<p style="font-size:32px;font-weight:bold;letter-spacing:6px">{{ .Token }}</p>
<p>O código vale por 1 hora. Se não foi você quem pediu, ignore este e-mail.</p>
<p>Quem pesquisa economiza.<br>Equipe Tabelapp</p>
```

Clique em **Save**.

## Tamanho do código: 6 números

O app pede **6 números**. No Supabase, **Authentication → Providers → Email → Email OTP Length**
precisa ficar em **6** (o padrão de projetos novos é 8; com 8, o código do e-mail não cabe no app).

## Enviar o e-mail de verdade (SMTP)

⚠️ O serviço de e-mail gratuito do Supabase só entrega para os e-mails dos membros do projeto (e
poucos por hora). Antes de liberar o app para outras pessoas, configure um SMTP próprio.

**Caminho gratuito sem domínio próprio: o Gmail da conta tabelapp.economia@gmail.com.**

1. Na conta Google `tabelapp.economia@gmail.com`, ative a **verificação em duas etapas**
   (Conta Google → Segurança). Sem ela, o Google não deixa criar Senha de app.
2. Em **Conta Google → Segurança → Senhas de app** (ou https://myaccount.google.com/apppasswords), crie
   uma senha de app com o nome "Supabase Tabelapp". O Google mostra **16 letras**: copie na hora.
3. No Supabase, **Project Settings → Authentication → SMTP Settings → Enable Custom SMTP**:

   | Campo | Valor |
   |---|---|
   | Sender email | `tabelapp.economia@gmail.com` |
   | Sender name | `Tabelapp` |
   | Host | `smtp.gmail.com` |
   | Port | `465` |
   | Username | `tabelapp.economia@gmail.com` |
   | Password | a Senha de app de 16 letras (sem espaços) |

4. **Save**. Teste em "Esqueci a senha" com um e-mail que não seja membro do projeto.

- A **Senha de app não vai para nenhum arquivo** (nem para o repositório, nem para o chat): só para
  o campo Password do Supabase. Se vazar, apague-a na Conta Google e crie outra.
- O Gmail tem limite diário de envio (cerca de 500 e-mails por dia em conta gratuita): sobra para o piloto.
- **Resend e Brevo ficam para quando houver domínio próprio:** o Resend exige um domínio verificado e o
  Brevo não aceita remetente `@gmail.com`.
