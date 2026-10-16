# Publicação na Google Play (conta pessoal, teste fechado)

Guia do que **você** faz para colocar o Tabelapp na Google Play. O app já está pronto do lado do
código: sem nenhuma tela de venda (decisão de 16/10/2026, em `docs/DECISOES.md`), com páginas de
privacidade, termos e exclusão de conta no site, e com o pacote (AAB) assinado gerado pelo GitHub.

> **Nunca envie senhas, a chave de envio ou o arquivo `.jks` para ninguém** (nem para o Claude).
> Eles vão só para os secrets do GitHub, cadastrados por você.

O `applicationId` é **`br.com.tabelapp`** e não pode mudar depois da primeira publicação.

---

## 1. Conta de desenvolvedor (pessoal)

1. Acesse https://play.google.com/console e crie a conta **Pessoal** (não "Organização": sem CNPJ
   ainda). Taxa única de **US$ 25** (cartão internacional).
2. Faça a **verificação de identidade** (documento com foto; o nome precisa bater com o do
   pagamento). Pode levar alguns dias.
3. Confirme o celular Android e o e-mail de contato que pedirem.
4. Depois, quando houver CNPJ, o app pode ser **transferido** para uma conta de organização sem
   perder usuários nem avaliações.

## 2. Site (GitHub Pages): privacidade, termos e exclusão de conta

O site fica em `site/` e é publicado pela automação `.github/workflows/site.yml`.

1. **Antes:** o código precisa estar na `main` (o PR #1 mesclado). O GitHub só roda automações
   da `main` pelo botão "Run workflow" e o Pages publica a partir dela.
2. GitHub → repositório → **Settings → Pages → Build and deployment → Source: "GitHub Actions"**.
3. **Actions → "Site" → Run workflow** (ou qualquer mudança em `site/` na `main` publica sozinha).
4. Endereços (troque se o repositório mudar de nome ou de dono):

| Página | URL |
|---|---|
| Início | https://tabelapp.github.io/App/ |
| Política de privacidade | https://tabelapp.github.io/App/privacidade.html |
| Termos de uso | https://tabelapp.github.io/App/termos.html |
| **Exclusão de conta** | https://tabelapp.github.io/App/excluir-conta.html |
| Planos (venda fora do app) | https://tabelapp.github.io/App/planos.html |

- Contato oficial: **tabelapp.economia@gmail.com** (link mailto: em todas as páginas, definido em
  `scripts/gerar-site.py`). O número dos botões de WhatsApp fica só em `site/config.js`.
- O site não cita CNPJ do Tabelapp (ainda não existe). O responsável aparece como "Tabelapp, mantido
  por Thomaz Correa Guimarães da Cunha (pessoa física)" na privacidade e nos termos.
- `python3 scripts/conferir-site.py` confere links (nenhum 404 em /App/), menu e contato; o CI roda.
- Privacidade e termos saem dos mesmos textos do app (`app/src/main/res/raw/*.md`). Mudou o texto?
  Rode `python3 scripts/gerar-site.py`; o CI falha se o site estiver desatualizado.

## 3. Chave de envio e AAB assinado

### 3.1 Criar a chave de envio (uma vez, no seu computador)

```bash
keytool -genkeypair -v -keystore tabelapp-envio.jks -alias tabelapp \
  -keyalg RSA -keysize 2048 -validity 10000
```

(ou `bash scripts/criar-chave-envio.sh`, que faz o mesmo e já gera o base64.) Digite uma senha
forte e guarde no gerenciador de senhas. Depois:

```bash
base64 -i tabelapp-envio.jks | tr -d '\n' > tabelapp-envio.jks.base64.txt
```

- **Guarde o `tabelapp-envio.jks` FORA do repositório, com backup** (pendrive + nuvem pessoal).
  Nunca faça commit dele. O `.gitignore` já ignora `*.jks`, mas não confie só nisso.

### 3.2 Secrets do GitHub

Repositório → **Settings → Secrets and variables → Actions → New repository secret**:

| Secret | Valor | Obrigatório |
|---|---|---|
| `ASSINATURA_KEYSTORE_BASE64` | conteúdo do `.base64.txt` (apague o arquivo depois) | sim |
| `ASSINATURA_SENHA` | a senha da chave | sim |
| `ASSINATURA_ALIAS` | `tabelapp` | sim |
| `ASSINATURA_SENHA_CHAVE` | só se a senha da chave for diferente da do arquivo | não |
| `SUPABASE_URL`, `SUPABASE_ANON_KEY` | os mesmos do CI | sim |
| `GOOGLE_WEB_CLIENT_ID`, `MAPS_API_KEY` | os mesmos do CI | recomendados |

### 3.3 Gerar o AAB

1. **Actions → "Versão para a Play Store" → Run workflow**, informe a versão (ex.: `1.0.0`).
2. O número interno (**versionCode**) sobe sozinho a cada execução (100 + número da execução).
3. Baixe o artefato **tabelapp-play-store**: `.aab` (para a Play) e `.apk` de produção (para testar).
4. Sem os secrets da chave, o AAB sai **sem assinatura** e a Play recusa (o resumo avisa).

### 3.4 Play App Signing

Na primeira versão, **aceite o Play App Signing**: o Google guarda a chave que assina o app nos
celulares; você só tem a chave de envio. Se perder a chave de envio, dá para pedir outra ao Google
pelo Play Console sem perder o app.

Depois, em **Configuração → Integridade do app → Assinatura do app**, copie a **SHA-1 da chave de
assinatura do app** e cadastre no Google Cloud (login com Google e Maps; veja `docs/GOOGLE_CLOUD.md`).

## 4. Teste fechado (obrigatório para conta pessoal nova)

Contas pessoais criadas depois de nov/2023 precisam de **teste fechado com pelo menos 12 testadores
inscritos por 14 dias seguidos** antes de pedir acesso à produção.

1. **Testar → Teste fechado → Criar faixa** (ou use a "Alpha").
2. **Testadores:** crie uma **lista de e-mails** (contas Google) com **pelo menos 12 pessoas** (sugiro
   15–20, porque alguém sempre esquece). Também dá para usar um Grupo do Google.
3. Crie a versão, envie o `.aab`, escreva as notas da versão e **envie para revisão**.
4. Depois de aprovada, copie o **link de participação** (aba Testadores) e mande para cada pessoa:
   ela abre o link no celular, toca em **"Tornar-se testador"** e instala pela Play Store.
5. Os **14 dias contam a partir de quando 12 pessoas estão inscritas**, sem interrupção: peça que
   **não saiam do teste** e usem o app de verdade (buscar, enviar NF, montar lista) e mandem
   comentários. Se o número cair abaixo de 12, a contagem pode recomeçar.
6. Publique 1 ou 2 atualizações durante o teste (mostra ao Google que o app está sendo cuidado).
7. Passados os 14 dias: **Painel → Solicitar acesso à produção**. Responda o questionário (como
   recrutou os testadores, o que aprendeu, o que mudou). A análise leva até ~7 dias.
8. Aprovado: **Produção → Criar versão** com o mesmo AAB (ou um novo) e envie para revisão.

## 5. Conteúdo do app (Play Console → Política → Conteúdo do app)

### 5.1 Política de privacidade
URL: https://tabelapp.github.io/App/privacidade.html

### 5.2 Exclusão de conta
- "O app permite criar conta?" **Sim.**
- Exclusão dentro do app: **Sim** (Minha conta → Excluir minha conta).
- URL para pedir exclusão fora do app: https://tabelapp.github.io/App/excluir-conta.html

### 5.3 Anúncios
"O app contém anúncios?" **Sim** — há banners patrocinados de comércios locais na busca, marcados
como "Patrocinado" (não há SDK de anúncios de terceiros). Se o painel considerar que só anúncios de
redes de anúncios contam, marque conforme a orientação dele; o importante é não esconder os banners.

### 5.4 Acesso ao app (para a revisão do Google)
O app exige login. Crie uma **conta de teste** (e-mail e senha) de pessoa e informe ao Google em
"Acesso ao app" com as instruções: "Entre com o e-mail e a senha abaixo. A busca fica na primeira
aba". Não use a sua conta de Admin. **Não coloque essa senha em nenhum arquivo do repositório.**

### 5.5 Classificação de conteúdo (questionário IARC)
- Categoria: **Utilitário, produtividade, comunicação ou outro** (não é jogo).
- Violência, sexo, linguagem imprópria, drogas, jogos de azar: **Não**.
- Os usuários interagem ou trocam conteúdo entre si? **Sim, de forma limitada**: preços enviados por
  notas fiscais e tabelas/banners de empresas aparecem para todos (com moderação e denúncia). Não há
  chat nem perfil público.
- Compartilha a localização do usuário com outros usuários? **Não.**
- Compras digitais? **Não.**
- Resultado esperado: **Livre (L)** / "Everyone".

### 5.6 Público-alvo
**18 anos ou mais** (o app não é para crianças). Não participar do programa "Feito para famílias".

### 5.7 App de notícias / governo / saúde / financeiro
Não é app de notícias, nem do governo, nem de empréstimos. Não lida com saúde.

### 5.8 Segurança dos dados (Data safety) — rascunho

Baseado no que o código realmente faz (`docs/` + banco + `politica_privacidade.md`). Confira antes
de enviar. **O CPF/CNPJ do comprador da nota nunca é coletado.**

Respostas gerais:
- Coleta ou compartilha dados? **Sim, coleta.**
- Os dados são criptografados em trânsito? **Sim** (HTTPS com o Supabase).
- O usuário pode pedir a exclusão? **Sim** (no app e pela página de exclusão).
- Compartilhamento com terceiros: **Não** (Supabase e Google atuam como operadores/prestadores de
  serviço, o que a Play não conta como "compartilhamento").

| Tipo de dado (nome na Play) | Coletado? | Obrigatório? | Para quê | Observação |
|---|---|---|---|---|
| Informações pessoais → **Nome** | Sim | Sim | Funcionalidade do app; gerenciamento da conta | nome da conta |
| Informações pessoais → **Endereço de e-mail** | Sim | Sim | Gerenciamento da conta | login (e-mail/senha ou Google) |
| Informações pessoais → **Números de telefone** | Sim | Opcional | Funcionalidade do app | só contas de empresa (telefone/WhatsApp da loja, público no card) |
| Informações pessoais → **Endereço** | Sim | Opcional | Funcionalidade do app; prevenção de fraude | só contas de empresa (endereço da loja/empresa) |
| Informações pessoais → **Outras informações** | Sim | Opcional | Prevenção de fraude, segurança | CNPJ da empresa (não da pessoa) |
| Local → **Local aproximado** e **Local exato** | Sim | Opcional | Funcionalidade do app | distância até as lojas; não é guardado, exceto "Estou na loja" (posição da loja) |
| Informações financeiras → **Histórico de compras** | Sim | Opcional | Funcionalidade do app | itens, preços e data das notas fiscais que a pessoa envia, ligados à conta que enviou (antiabuso). Sem CPF |
| Fotos e vídeos → **Fotos** | Sim | Opcional | Prevenção de fraude; funcionalidade | foto do alvará/comprovante (privada, só a equipe vê) e arte do banner |
| Atividade no app → **Interações no app** | Sim | Sim | Funcionalidade; análise | listas de compras, denúncias de preço, contagem de visualizações de banner |
| Atividade no app → **Outros conteúdos gerados pelo usuário** | Sim | Opcional | Funcionalidade do app | tabela de preços e banners das empresas |
| Informações e desempenho do app → Registros de falhas | **Não** | — | — | o registro de erros fica só no celular |
| Identificadores do dispositivo | **Não** | — | — | — |
| Contatos, mensagens, áudio, agenda, saúde | **Não** | — | — | — |

- "Os dados são processados de forma temporária?" Para **local**: sim (usado só para calcular
  distâncias). Para os demais: não.

## 6. Ficha da loja (pt-BR)

- **Nome do app (até 30 caracteres):** `Tabelapp: compare preços`
- **Descrição curta (até 80 caracteres):**
  `Compare preços de mercados, lojas e serviços perto de você antes de comprar.`
- **Categoria:** Compras. **E-mail de contato:** tabelapp.economia@gmail.com. **Site:**
  https://tabelapp.github.io/App/
- **Descrição completa:**

```
Quem pesquisa economiza. O Tabelapp reúne os preços de mercados, lojas e serviços da sua região
para você comparar antes de comprar — de graça.

PESQUISE
Digite o produto ou o serviço e veja quanto custa em cada lugar, do mais barato ao mais caro, com a
distância até você e o caminho no mapa. O menor preço ganha destaque.

ENVIE PREÇOS PELA NOTA FISCAL
Depois de uma compra, leia o QR Code da nota fiscal (ou digite a chave de 44 números). Os preços
entram no app para todo mundo. Seus dados pessoais, como o CPF, nunca são guardados.

MONTE A SUA LISTA
Junte os itens da compra e descubra quanto a lista custa em cada lugar e onde ela sai mais barata.

PREÇOS OFICIAIS DAS EMPRESAS
Lojas, mercados e prestadores verificados publicam a própria tabela de preços, com validade e
observações. Fale com a loja pelo WhatsApp direto do card de preço.

CONFIÁVEL
Empresas passam por verificação de documentos antes de publicar. Viu um preço errado? Toque em
"Esse preço não confere" e a nossa equipe confere.

Dica: confirme sempre o preço na hora da compra — os valores podem mudar sem aviso.
```

- **Gráficos:** ícone 512×512 (sai do ícone do app), **gráfico de destaque 1024×500** e pelo menos
  **2 capturas de tela de celular** (sugestão: busca, card de preço, lista, envio de NF). Nenhuma
  imagem pode mostrar preço de plano ou chamada de compra.

## 7. Antes de enviar cada versão (checklist rápido)

- [ ] CI verde no commit da versão.
- [ ] Nenhuma tela com preço de plano, "assine", Pix ou link de pagamento (o teste
      `app sem superficie de venda` em `core` confere os textos).
- [ ] Site publicado e as 3 URLs abrindo (privacidade, termos, exclusão).
- [ ] Notas da versão escritas.
