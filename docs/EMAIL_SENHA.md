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

⚠️ O serviço de e-mail gratuito do Supabase só entrega para os e-mails dos membros do projeto (e
poucos por hora). Antes de liberar o app para outras pessoas, configure um SMTP próprio em
**Project Settings → Authentication → SMTP Settings** (ex.: Resend ou Brevo, que têm plano gratuito).
