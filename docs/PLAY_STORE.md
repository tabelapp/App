# Versão para a Play Store — passo a passo

> Guia completo de publicação (conta, teste fechado, Data safety, ficha da loja): `docs/PUBLICACAO.md`.

O app já está pronto para gerar o pacote da Play Store (AAB). Falta só a **chave de envio**, que
você cria e guarda. **Nunca envie a senha ou o arquivo da chave para ninguém** (nem para o Claude):
eles vão só para os secrets do GitHub, cadastrados por você.

## Como funciona a assinatura (resumo)

- A Play Store usa o **Play App Signing**: a chave que assina o app instalado nos celulares fica
  guardada pelo Google.
- Você tem só a **chave de envio** (upload key), que prova que o pacote veio de você. Se perdê-la, dá
  para pedir ao Google uma nova pelo Play Console — não se perde o app.
- O app pode ser **transferido** de uma conta de desenvolvedor para outra depois (ex.: conta pessoal →
  conta da empresa quando tiver CNPJ), sem perder usuários nem avaliações.

## 1. Criar a chave de envio (uma vez)

**Opção A — script (precisa do Java ou do Android Studio instalado):**
1. No seu computador, dentro da pasta do projeto: `bash scripts/criar-chave-envio.sh`
2. Digite uma senha forte (duas vezes) e guarde no gerenciador de senhas.
3. Guarde o arquivo `tabelapp-envio.jks` com backup fora do computador (pendrive, nuvem pessoal).

**Opção B — Android Studio:** *Build → Generate Signed App Bundle or APK → Android App Bundle →
Create new…*; alias `tabelapp`, validade 25+ anos. Depois converta o `.jks` em texto:
`base64 -i tabelapp-envio.jks | tr -d '\n' > tabelapp-envio.jks.base64.txt` (no Windows:
`certutil -encode tabelapp-envio.jks saida.txt` e apague as linhas BEGIN/END).

## 2. Cadastrar os secrets no GitHub

Repositório → *Settings → Secrets and variables → Actions → New repository secret*:

| Secret | Valor |
|---|---|
| `ASSINATURA_KEYSTORE_BASE64` | conteúdo do arquivo `.base64.txt` (apague o arquivo depois) |
| `ASSINATURA_SENHA` | a senha da chave |
| `ASSINATURA_ALIAS` | `tabelapp` |

Os secrets do app (`SUPABASE_URL`, `SUPABASE_ANON_KEY`, `GOOGLE_WEB_CLIENT_ID`, `MAPS_API_KEY`) são
os mesmos já usados no CI.

## 3. Gerar a versão

> A automação só aparece em *Actions* depois que o código estiver na branch principal (`main`) do
> GitHub — o GitHub só mostra o botão "Run workflow" de automações que estão lá. Até lá, o CI de cada
> envio já confere que o pacote da Play Store compila.

1. GitHub → *Actions → "Versão para a Play Store" → Run workflow*.
2. Informe o nome da versão (ex.: `1.0.0`) e confirme. O número interno sobe sozinho a cada execução.
3. Ao terminar, baixe o artefato **tabelapp-play-store**: tem o `.aab` (para a Play Store) e um `.apk`
   de produção (para instalar e testar no celular antes de publicar).
4. O resumo da execução mostra a **SHA-1 da chave de envio**.

## 4. Primeiro envio na Play Console

1. Crie o app (nome **Tabelapp**, gratuito) e aceite o **Play App Signing**.
2. Comece por *Testar → Teste interno*: crie uma versão, envie o `.aab`, adicione seu e-mail como
   testador e instale pelo link da Play Store.
3. Em *Configuração → Integridade do app → Assinatura do app*, copie a **SHA-1 da chave de assinatura
   do app** (a do Google).

## 5. Mapa e login com Google na versão da loja

O mapa e o login com Google só aceitam apps cujas SHA-1 estejam cadastradas. Além da SHA-1 de debug
(já em `docs/GOOGLE_CLOUD.md`), adicione no Google Cloud:

- **SHA-1 da chave de assinatura do app** (Play Console, passo 4.3) — é a dos celulares que instalam
  pela loja;
- **SHA-1 da chave de envio** (passo 3.4) — para o `.apk` de produção que você instala para testar.

Onde: na chave do Maps (*Restrições do aplicativo → Apps Android*, mesmo pacote `br.com.tabelapp`) e
um **cliente OAuth do tipo Android** para cada SHA-1 nova.

## Observações

- O pacote de produção sai **sempre ligado ao Supabase real** (a execução falha se faltar
  `SUPABASE_URL`), nunca em modo demonstração.
- O backup automático do Android está desligado: a sessão de login não vai para a nuvem.
- Antes do primeiro envio, a Play Console pede: ficha da loja (ícone 512×512, imagem de destaque
  1024×500, ao menos 2 capturas de tela), classificação etária, formulário de **Segurança dos dados**
  e o link da **Política de Privacidade** publicada.

## Formulário "Segurança dos dados" (sugestão, conferir com a Política)

- **O app coleta ou compartilha dados?** Sim. **Criptografia em trânsito:** sim (HTTPS).
  **O usuário pode pedir a exclusão?** Sim — "Minha conta → Excluir conta" e o e-mail da Política.
- **Informações pessoais:** nome e e-mail (funcionalidade do app e gerenciamento da conta);
  telefone e endereço só de estabelecimentos. Coletados, não compartilhados.
- **Localização aproximada e precisa:** usada no aparelho para distância e mapa (funcionalidade do
  app); enviada só para calcular a distância, sem guardar. Exceção: "Estou na loja" guarda a posição
  da loja (dado do estabelecimento, público na busca).
- **Fotos:** foto do alvará e arte do banner (funcionalidade do app), só de estabelecimentos.
- **Atividade no app:** preços enviados, listas, denúncias e visualizações de banner
  (funcionalidade do app, prevenção de fraude).
- **Diagnóstico:** não sai do celular (o registro de erros fica no aparelho) → não declarar.
- **Compartilhamento com terceiros:** os provedores que processam dados em nosso nome (Supabase,
  Google) não contam como "compartilhamento" no formulário. Os preços e os dados do estabelecimento
  são públicos por natureza do app.
