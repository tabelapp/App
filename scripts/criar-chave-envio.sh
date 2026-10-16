#!/usr/bin/env bash
# Cria a CHAVE DE ENVIO do Tabelapp para a Play Store (rodar no SEU computador).
#
# O keytool vai pedir uma senha: escolha uma forte e guarde num lugar seguro
# (gerenciador de senhas). Não envie a senha nem o arquivo .jks para ninguém;
# eles vão só para os secrets do GitHub, cadastrados por você.
#
# Precisa do Java (vem com o Android Studio). Uso:
#   bash scripts/criar-chave-envio.sh
set -euo pipefail

ARQUIVO="${1:-tabelapp-envio.jks}"
ALIAS="tabelapp"

if ! command -v keytool >/dev/null; then
  echo "keytool não encontrado. Instale o Java (ou o Android Studio) e rode de novo." >&2
  exit 1
fi
if [ -e "$ARQUIVO" ]; then
  echo "Já existe $ARQUIVO — não vou sobrescrever." >&2
  exit 1
fi

keytool -genkeypair -v -keystore "$ARQUIVO" -alias "$ALIAS" \
  -keyalg RSA -keysize 4096 -validity 10000 \
  -dname "CN=Tabelapp, O=Tabelapp, L=Petropolis, ST=RJ, C=BR"

base64 < "$ARQUIVO" | tr -d '\n' > "$ARQUIVO.base64.txt"

echo
echo "Pronto. Criados:"
echo "  $ARQUIVO              -> GUARDE (backup fora do computador)"
echo "  $ARQUIVO.base64.txt   -> conteúdo do secret ASSINATURA_KEYSTORE_BASE64 (apague depois de cadastrar)"
echo
echo "Secrets no GitHub (Settings → Secrets and variables → Actions):"
echo "  ASSINATURA_KEYSTORE_BASE64 = conteúdo de $ARQUIVO.base64.txt"
echo "  ASSINATURA_SENHA           = a senha que você digitou"
echo "  ASSINATURA_ALIAS           = $ALIAS"
echo
echo "Impressão digital (SHA-1) desta chave, para o Google Cloud:"
keytool -list -v -keystore "$ARQUIVO" -alias "$ALIAS" | grep -E "SHA1:|SHA-1:" | head -1
