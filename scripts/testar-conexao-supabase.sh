#!/usr/bin/env bash
# Confere se o projeto Supabase responde e se as migrações do Tabelapp já foram aplicadas.
#
# Uso:  SUPABASE_URL=https://<id>.supabase.co SUPABASE_ANON_KEY=<chave anon> scripts/testar-conexao-supabase.sh
# (ou deixe as duas no local.properties da raiz do projeto)
set -uo pipefail

RAIZ="$(cd "$(dirname "$0")/.." && pwd)"
ler_prop() { [ -f "$RAIZ/local.properties" ] && sed -n "s/^$1=//p" "$RAIZ/local.properties" | head -1; }
URL="${SUPABASE_URL:-$(ler_prop SUPABASE_URL)}"
CHAVE="${SUPABASE_ANON_KEY:-$(ler_prop SUPABASE_ANON_KEY)}"
URL="${URL%/}"

if [ -z "$URL" ] || [ -z "$CHAVE" ]; then
  echo "❌ Faltam SUPABASE_URL e/ou SUPABASE_ANON_KEY."
  exit 1
fi

# Chaves novas (sb_publishable_...) vão só no header apikey; as antigas (JWT "eyJ...") também no Authorization.
AUTH=()
case "$CHAVE" in eyJ*) AUTH=(-H "Authorization: Bearer $CHAVE") ;; esac

echo "== 1. O projeto responde? ($URL)"
codigo=$(curl -sS -m 20 -o /dev/null -w "%{http_code}" -H "apikey: $CHAVE" "$URL/auth/v1/health")
case "$codigo" in
  200) echo "✅ Conectado: URL e chave anon válidas." ;;
  401|403) echo "❌ O projeto respondeu, mas recusou a chave (HTTP $codigo). Confira a SUPABASE_ANON_KEY."; exit 1 ;;
  000) echo "❌ Sem resposta. Confira a URL (e se a rede permite acessar *.supabase.co)."; exit 1 ;;
  *) echo "❌ Resposta inesperada: HTTP $codigo"; exit 1 ;;
esac

echo "== 2. As migrações do Tabelapp foram aplicadas?"
# Sem login, o banco deve RECUSAR (permissão) — o que prova que a função existe.
# Se a função não existir, o PostgREST responde PGRST202.
resposta=$(curl -sS -m 20 -X POST "$URL/rest/v1/rpc/buscar_cotacoes" \
  -H "apikey: $CHAVE" "${AUTH[@]}" -H "Content-Type: application/json" -d '{}')
if echo "$resposta" | grep -q '"PGRST202"'; then
  echo "❌ Funções do Tabelapp não encontradas: aplique supabase/migrations/ (README, passo 2)."
  exit 1
elif echo "$resposta" | grep -q '"42501"'; then
  echo "✅ Migrações aplicadas (e acesso sem login bloqueado, como deve ser)."
elif echo "$resposta" | grep -q '^\['; then
  echo "⚠️  A busca respondeu SEM login — as permissões (migração 0300) não foram aplicadas."
  exit 1
else
  echo "⚠️  Resposta inesperada: $resposta"
  exit 1
fi

echo "== 3. Migrações mais recentes (QR Code, data da NF, encarte, cadastro de PDV) aplicadas?"
for chamada in \
  'lojas_do_cnpj|{"p_cnpj":"0"}|20260923000500_nf_qrcode.sql' \
  'enviar_nota_fiscal|{"p_chave_acesso":null,"p_loja_id":null,"p_pdv_nome":null,"p_pdv_endereco":null,"p_itens":[],"p_data_nf":null}|20260923000600_nf_data_validade.sql' \
  'buscar_lojas|{"p_termo":"x"}|20260923000700_enviar_encarte.sql' \
  'publicar_encarte|{"p_fotos":[],"p_loja_id":null,"p_pdv_nome":null,"p_pdv_endereco":null,"p_validade":null,"p_itens":[]}|20260923000800_publicar_encarte.sql' \
  'meus_pdvs|{}|20260924001000_pdv_verificacao.sql'
do
  IFS='|' read -r funcao corpo migracao <<< "$chamada"
  resposta=$(curl -sS -m 20 -X POST "$URL/rest/v1/rpc/$funcao" \
    -H "apikey: $CHAVE" "${AUTH[@]}" -H "Content-Type: application/json" -d "$corpo")
  if echo "$resposta" | grep -q '"PGRST202"'; then
    echo "❌ Falta aplicar $migracao (e as seguintes)."
    exit 1
  fi
done
echo "✅ Tudo certo. Coloque SUPABASE_URL e SUPABASE_ANON_KEY no local.properties e rode o app."
