#!/usr/bin/env bash
# Testa as migrações do Supabase num Postgres puro (sem Docker/Supabase CLI).
#
# Uso:  scripts/testar-banco.sh
# Variáveis opcionais: PGHOST, PGPORT, PGUSER, PGPASSWORD (padrão: socket local, usuário postgres)
set -euo pipefail

RAIZ="$(cd "$(dirname "$0")/.." && pwd)"
BANCO="tabelapp_teste"

psql_adm() { psql -v ON_ERROR_STOP=1 -X -q "$@"; }

psql_adm -d postgres -c "drop database if exists ${BANCO};"
psql_adm -d postgres -c "create database ${BANCO};"

echo "== stub do Supabase"
psql_adm -d "$BANCO" -f "$RAIZ/supabase/tests/00_stub_supabase.sql"

for f in "$RAIZ"/supabase/migrations/*.sql; do
  echo "== migração $(basename "$f")"
  psql_adm -d "$BANCO" -f "$f"
done

echo "== seed"
psql_adm -d "$BANCO" -f "$RAIZ/supabase/seed.sql"

echo "== testes"
psql_adm -d "$BANCO" -f "$RAIZ/supabase/tests/01_regras_de_negocio.sql"
