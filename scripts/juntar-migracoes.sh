#!/usr/bin/env bash
# Gera supabase/instalar_tudo.sql: todas as migrações, em ordem, num arquivo só —
# para colar de uma vez no SQL Editor do painel do Supabase.
# Uso: scripts/juntar-migracoes.sh           (gera o arquivo)
#      scripts/juntar-migracoes.sh --conferir (falha se o arquivo estiver desatualizado; usado na CI)
set -euo pipefail
RAIZ="$(cd "$(dirname "$0")/.." && pwd)"
DESTINO="$RAIZ/supabase/instalar_tudo.sql"

gerar() {
  echo "-- ============================================================================="
  echo "-- Tabelapp — instalação completa do banco (todas as migrações, em ordem)."
  echo "-- Cole TUDO no SQL Editor do Supabase (projeto novo, vazio) e clique em Run."
  echo "-- Arquivo gerado por scripts/juntar-migracoes.sh — não edite à mão."
  echo "-- ============================================================================="
  for f in "$RAIZ"/supabase/migrations/*.sql; do
    echo
    echo "-- >>>>>>>>>> $(basename "$f")"
    cat "$f"
  done
}

if [ "${1:-}" = "--conferir" ]; then
  diff -q <(gerar) "$DESTINO" >/dev/null || { echo "supabase/instalar_tudo.sql desatualizado: rode scripts/juntar-migracoes.sh"; exit 1; }
  echo "instalar_tudo.sql em dia."
else
  gerar > "$DESTINO"
  echo "Gerado: supabase/instalar_tudo.sql"
fi
