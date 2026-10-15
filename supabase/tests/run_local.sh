#!/usr/bin/env bash
# يشغّل المخطط والاختبارات على Postgres محلي مؤقت (يُحذف بعد الانتهاء). يحتاج مستخدم root وحزم postgresql-16.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
BIN="${PG_BIN:-/usr/lib/postgresql/16/bin}"
DIR="${PGTEST_DIR:-/tmp/pgtest}"
SOCK="$DIR/sock"
rm -rf "$DIR"; mkdir -p "$DIR"; chown postgres "$DIR"
run() { runuser -u postgres -- "$@"; }
run "$BIN/initdb" -D "$DIR/data" -A trust -E UTF8 --locale=C.UTF-8 >/dev/null
run "$BIN/pg_ctl" -D "$DIR/data" -o "-c listen_addresses='' -c unix_socket_directories='$DIR'" -w -l "$DIR/log" start >/dev/null
trap 'run "$BIN/pg_ctl" -D "$DIR/data" -m immediate stop >/dev/null 2>&1; rm -rf "$DIR"' EXIT
P() { run psql -h "$DIR" -U postgres -v ON_ERROR_STOP=1 -X -q "$@"; }
P -d postgres -c "create database app" 
P -d app -f "$HERE/00_local_supabase_stub.sql" >/dev/null
for m in "$HERE"/../migrations/*.sql; do P -d app -f "$m" >/dev/null; done
echo "migration applied OK"
set +e
OUT="$(run psql -h "$DIR" -U postgres -d app -X -q -f "$HERE/rls_tests.sql" 2>&1)"
echo "$OUT"
echo "$OUT" | grep -q "RLS TESTS PASSED"
