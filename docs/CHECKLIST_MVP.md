# Checklist do MVP (10/10/2026)

## A. Desenvolvimento (Claude)
Essenciais para lançar:
- [ ] **Conta do usuário:** editar nome e **excluir a conta pelo app** (exigência da Play Store).
- [ ] **Política de privacidade e termos de uso:** texto + link no app e no cadastro.
- [ ] **Moderação do Admin no app:** remover preço errado e suspender um PDV já aprovado.
- [ ] **Coordenadas das lojas cadastradas:** localizar o endereço no cadastro/edição e guardar no banco
      (pino certo no mapa e distância no card).
- [ ] **Versão para a Play Store:** pacote AAB assinado com a chave de produção, número de versão automático.

Importantes, podem vir logo depois:
- [ ] **Pix automático** (Mercado Pago ou Efí) — hoje o pagamento é manual (WhatsApp + confirmação do Admin).
- [ ] Lista de compras: relatório **"no mapa"** (reaproveita a tela do mapa).
- [ ] PDV: cadastrar mais lojas e trocar modo rede/varejo.
- [ ] Promoção pausada: botão "reativar".
- [ ] Busca: "carregar mais" além de 100 resultados.

## B. Configuração (fundador)
- [ ] **Google Cloud:** chave do mapa + login com Google (`docs/GOOGLE_CLOUD.md`).
- [ ] **E-mail próprio (SMTP):** Resend ou Brevo no Supabase — sem isso o "Esqueci a senha" não chega
      para os usuários; colar o modelo de `docs/EMAIL_SENHA.md`.
- [ ] **Supabase de produção:** plano pago (não pausa, tem backup) ou rotina que mantenha ativo.
- [ ] **Google Play Console:** conta de desenvolvedor (US$ 25, uma vez), ficha da loja (ícone, telas,
      descrição), classificação etária, formulário de segurança de dados.
- [ ] **Política de privacidade publicada** num endereço web (exigida pela Play Store).
- [ ] Definir o **link de download** usado no compartilhamento.

## C. Testes no celular (fundador + Claude)
- [ ] NF pela câmera e pela chave digitada (consulta na Sefaz) e o PDF por e-mail.
- [ ] Cadastro de PDV → aprovação no Admin → painel do PDV.
- [ ] Planilha (modelo .csv e Excel), promoção paga e confirmação do pagamento no Admin.
- [ ] Busca: card, WhatsApp, compartilhar, banner, lançamentos repetidos, "Ver no mapa".
- [ ] Lista de compras e os dois relatórios.
- [ ] Login com Google e "Esqueci a senha".

## Fora do MVP (decididos)
- Envio de encarte por usuário — suspenso.
- Login por WhatsApp/SMS — sem definição (custo do provedor).
