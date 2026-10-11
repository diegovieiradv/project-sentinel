#!/usr/bin/env bash
# backup.sh — backup do banco Sentinel em formato custom do PostgreSQL.
#
# Interface:
#   backup.sh [-d <dir>] [-h <host>] [-p <porta>] [-U <user>] [-b <dbname>]
#
# Defaults: dir=<repo>/backups, host=localhost, porta=5433, user=sentinel,
# dbname=sentinel (ambiente local do compose: container sentinel-postgres).
#
# Dois modos de execução:
#   - Modo container (default): host localhost/127.0.0.1 + porta 5433 →
#     pg_dump roda DENTRO do container sentinel-postgres via `docker exec`
#     (paridade de versão com o servidor PG 17 e nenhuma senha em argv/log).
#   - Modo host: qualquer outro host/porta → usa pg_dump do PATH com
#     PGPASSWORD exportada do ambiente (nunca impressa; exporte
#     DATABASE_PASSWORD antes de chamar).
#
# Saída: <dir>/sentinel_YYYYMMDD_HHMMSS.dump + .sha256 (umask 077;
# diretório 700, arquivos 600).
#
# Exit codes:
#   0  backup concluído e validado
#   1  falha de validação (container ausente, dump vazio, TOC ilegível)
#   2  erro do pg_dump
#   3  argumento inválido
set -euo pipefail
umask 077

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"

EXIT_OK=0
EXIT_VALIDATION=1
EXIT_DUMP=2
EXIT_USAGE=3

ORIGIN_CONTAINER="sentinel-postgres"

log()  { printf '%s\n' "$*" >&2; }
die()  { local code="$1"; shift; log "$*"; exit "$code"; }

usage() {
  cat >&2 <<EOF
Usage: backup.sh [-d <dir>] [-h <host>] [-p <port>] [-U <user>] [-b <dbname>]
  -d  diretório de destino (default: ${ROOT}/backups)
  -h  host do banco (default: localhost)
  -p  porta do banco (default: 5433)
  -U  usuário do banco (default: sentinel)
  -b  banco de dados (default: sentinel)
Exit codes: 0 ok | 1 validação | 2 erro pg_dump | 3 argumento inválido
EOF
}

DIR="${ROOT}/backups"
HOST="localhost"
PORT="5433"
PGUSER="sentinel"
DBNAME="sentinel"

while getopts ":d:h:p:U:b:" opt; do
  case "$opt" in
    d) DIR="$OPTARG" ;;
    h) HOST="$OPTARG" ;;
    p) PORT="$OPTARG" ;;
    U) PGUSER="$OPTARG" ;;
    b) DBNAME="$OPTARG" ;;
    \?) log "Opção inválida: -$OPTARG"; usage; exit "$EXIT_USAGE" ;;
    :)  log "Opção -$OPTARG exige argumento."; usage; exit "$EXIT_USAGE" ;;
  esac
done

[[ "$PORT" =~ ^[0-9]{1,5}$ ]] \
  || die "$EXIT_USAGE" "Porta inválida: '$PORT'"
# Canonicaliza para decimal puro (10# força base 10): cobre zero à esquerda
# (ex.: 05433), que o libpq interpretaria como 5433 e escaparia da comparação
# de string "5433" do modo container abaixo.
PORT="$((10#$PORT))"
(( PORT >= 1 && PORT <= 65535 )) \
  || die "$EXIT_USAGE" "Porta fora da faixa válida (1-65535): '$PORT'"
[[ "$DBNAME" =~ ^[a-zA-Z_][a-zA-Z0-9_]{0,62}$ ]] \
  || die "$EXIT_USAGE" "Nome de banco inválido: '$DBNAME'"
[[ "$PGUSER" =~ ^[a-zA-Z_][a-zA-Z0-9_]{0,62}$ ]] \
  || die "$EXIT_USAGE" "Nome de usuário inválido: '$PGUSER'"

mkdir -p "$DIR"
chmod 700 "$DIR"

TS="$(date +%Y%m%d_%H%M%S)"
FILE="${DIR}/sentinel_${TS}.dump"

use_container=0
if [[ "$HOST" == "localhost" || "$HOST" == "127.0.0.1" ]] && [[ "$PORT" == "5433" ]]; then
  use_container=1
fi

if (( use_container )); then
  # inspect de container ausente imprime linha vazia no stdout; tr isola o valor.
  running="$(docker inspect -f '{{.State.Running}}' "$ORIGIN_CONTAINER" 2>/dev/null | tr -d '[:space:]' || true)"
  [[ "$running" == "true" ]] \
    || die "$EXIT_VALIDATION" "Container '$ORIGIN_CONTAINER' não está em execução (docker compose up -d --wait)."

  log "Backup via docker exec em '$ORIGIN_CONTAINER' (banco '$DBNAME')..."
  if ! docker exec "$ORIGIN_CONTAINER" \
      pg_dump -U "$PGUSER" -d "$DBNAME" --format=custom --no-owner --no-privileges \
      > "$FILE"; then
    rm -f "$FILE"
    die "$EXIT_DUMP" "pg_dump falhou. Nenhum arquivo parcial mantido."
  fi
else
  command -v pg_dump >/dev/null 2>&1 \
    || die "$EXIT_VALIDATION" "pg_dump não encontrado no PATH (modo host)."
  log "Backup via pg_dump do host ($HOST:$PORT, banco '$DBNAME')..."
  # PGPASSWORD nunca é impressa nem passada em argv (visível em ps).
  export PGPASSWORD="${DATABASE_PASSWORD:-}"
  if ! pg_dump -h "$HOST" -p "$PORT" -U "$PGUSER" -d "$DBNAME" \
      --format=custom --no-owner --no-privileges -f "$FILE"; then
    rm -f "$FILE"
    die "$EXIT_DUMP" "pg_dump falhou. Nenhum arquivo parcial mantido."
  fi
fi

# --- validações pós-dump -----------------------------------------------------
[[ -s "$FILE" ]] || { rm -f "$FILE"; die "$EXIT_VALIDATION" "Dump gerado está vazio."; }
chmod 600 "$FILE"

if (( use_container )); then
  toc_ok=0
  if docker exec -i "$ORIGIN_CONTAINER" pg_restore -l < "$FILE" >/dev/null 2>&1; then
    toc_ok=1
  fi
else
  toc_ok=0
  if command -v pg_restore >/dev/null 2>&1 && pg_restore -l "$FILE" >/dev/null 2>&1; then
    toc_ok=1
  fi
fi
(( toc_ok == 1 )) || { rm -f "$FILE"; die "$EXIT_VALIDATION" "TOC do dump ilegível (pg_restore -l falhou)."; }

# Checksum ao lado do dump (mesmo basename, para `sha256sum -c`).
( cd "$DIR" && sha256sum "$(basename "$FILE")" > "$(basename "$FILE").sha256" )
chmod 600 "${FILE}.sha256"

SIZE="$(stat -c%s "$FILE")"
SUM="$(cut -d' ' -f1 "${FILE}.sha256")"
log "OK: ${FILE} (${SIZE} bytes)"
log "sha256: ${SUM}"
printf '%s\n' "$FILE"
exit "$EXIT_OK"
