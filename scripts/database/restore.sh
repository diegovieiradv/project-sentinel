#!/usr/bin/env bash
# restore.sh — restore de dump custom do PostgreSQL em banco de DESTINO NÃO
# PRODUÇÃO, com proteções obrigatórias e provisionamento do ambiente isolado.
#
# Interface:
#   restore.sh <arquivo.dump> --force-restore-to <dbname> \
#              [--allow-non-empty-destination] [-h <host>] [-p <porta>] [-U <user>]
#
# Proteção obrigatória (incondicional, sem override possível):
#   - o destino NUNCA pode ser o banco de produção `sentinel`;
#   - o destino NUNCA pode estar no cluster de origem do Sentinel
#     (host loopback + porta 5433), mesmo com dbname diferente.
# A recusa acontece na validação de ANTES de qualquer conexão — a origem
# (e o destino recusado) nunca são tocados.
#
# Ambiente isolado (modo default): host localhost + porta 5434 roda o
# container dedicado sentinel-restore-test (imagem postgres:17-alpine, volume
# próprio sentinel_restore_test_data, credencial descartável gerada no
# provisionamento — nunca persistida nem impressa). O script CRIA o container
# se ele não existir; NUNCA remove nem limpa destination existente — banco já
# populado exige --allow-non-empty-destination.
#
# pg_restore roda SEM --clean (e portanto sem --if-exists — o pg_restore exige
# os dois juntos): o restore popula o destino como está; nada é pré-removido.
# A proteção contra destino poluído é a checagem de destino VAZIO abaixo.
#
# Exit codes:
#   0  restore concluído
#   1  falha de pré-validação (arquivo, checksum, TOC, destino populado sem flag)
#   2  erro de conexão ou do pg_restore
#   3  proteção de destino (recusado: destino é produção ou está no cluster de origem)
#   4  argumento inválido
set -euo pipefail
umask 077

EXIT_OK=0
EXIT_PREFLIGHT=1
EXIT_RESTORE=2
EXIT_PROTECTION=3
EXIT_USAGE=4

RESTORE_CONTAINER="sentinel-restore-test"
RESTORE_VOLUME="sentinel_restore_test_data"
RESTORE_IMAGE="postgres:17-alpine"
RESTORE_PORT_DEFAULT="5434"
RESTORE_USER_DEFAULT="sentinel_restore"

log() { printf '%s\n' "$*" >&2; }
die() { local code="$1"; shift; log "$*"; exit "$code"; }

# Retorna 0 se o host é loopback (literal ou via resolução DNS best-effort).
is_loopback_host() {
  local h="${1,,}" resolved r
  case "$h" in
    localhost|localhost.localdomain|127.0.0.1|::1) return 0 ;;
  esac
  resolved="$( (getent ahosts "$h" 2>/dev/null || getent hosts "$h" 2>/dev/null) \
    | awk '{print $1}' | sort -u || true)"
  while IFS= read -r r; do
    [[ -z "$r" ]] && continue
    case "$r" in
      127.*|::1) return 0 ;;
    esac
  done <<< "$resolved"
  return 1
}

usage() {
  cat >&2 <<'EOF'
Usage: restore.sh <arquivo.dump> --force-restore-to <dbname>
                  [--allow-non-empty-destination] [-h <host>] [-p <port>] [-U <user>]
  --force-restore-to <dbname>        banco de destino (obrigatório; nunca 'sentinel')
  --allow-non-empty-destination      restaura mesmo com tabelas já existentes
  -h  host destino (default: localhost)
  -p  porta destino (default: 5434 — container isolado sentinel-restore-test;
      host loopback na porta 5433 é recusado — é o cluster de origem do Sentinel)
  -U  usuário destino (default: sentinel_restore)
Exit codes: 0 ok | 1 pré-validação | 2 conexão/pg_restore | 3 proteção destino | 4 argumento inválido
EOF
}

FILE=""
FORCE_DB=""
ALLOW_NON_EMPTY=0
HOST="localhost"
PORT="$RESTORE_PORT_DEFAULT"
PGUSER="$RESTORE_USER_DEFAULT"

[[ $# -ge 1 ]] || { usage; exit "$EXIT_USAGE"; }
FILE="$1"
shift

while [[ $# -gt 0 ]]; do
  case "$1" in
    --force-restore-to)
      [[ $# -ge 2 ]] || die "$EXIT_USAGE" "--force-restore-to exige <dbname>."
      FORCE_DB="$2"; shift 2 ;;
    --allow-non-empty-destination)
      ALLOW_NON_EMPTY=1; shift ;;
    -h)
      [[ $# -ge 2 ]] || die "$EXIT_USAGE" "-h exige <host>."
      HOST="$2"; shift 2 ;;
    -p)
      [[ $# -ge 2 ]] || die "$EXIT_USAGE" "-p exige <porta>."
      PORT="$2"; shift 2 ;;
    -U)
      [[ $# -ge 2 ]] || die "$EXIT_USAGE" "-U exige <user>."
      PGUSER="$2"; shift 2 ;;
    *)
      die "$EXIT_USAGE" "Argumento desconhecido: $1" ;;
  esac
done

# --- 4. validação de argumentos ----------------------------------------------
[[ "$PORT" =~ ^[0-9]{1,5}$ ]] \
  || die "$EXIT_USAGE" "Porta inválida: '$PORT'"
# Canonicaliza para decimal puro (10# força base 10): cobre zero à esquerda
# (ex.: 05433), que o libpq interpretaria como 5433 e escaparia da guarda
# de proteção por comparação de string "5433" abaixo.
PORT="$((10#$PORT))"
(( PORT >= 1 && PORT <= 65535 )) \
  || die "$EXIT_USAGE" "Porta fora da faixa válida (1-65535): '$PORT'"
[[ "$FORCE_DB" =~ ^[a-zA-Z_][a-zA-Z0-9_]{0,62}$ ]] \
  || die "$EXIT_USAGE" "Nome de banco de destino inválido (ausente ou caracteres ilegais): '$FORCE_DB'"
[[ "$PGUSER" =~ ^[a-zA-Z_][a-zA-Z0-9_]{0,62}$ ]] \
  || die "$EXIT_USAGE" "Nome de usuário inválido: '$PGUSER'"

# --- 3. proteção de destino — ANTES de qualquer conexão -----------------------
# Bloco executado somente após parse completo e antes de qualquer docker/psql/
# pg_restore: destino 'sentinel' e destino no cluster de origem (loopback:5433)
# são recusados sem tocar origem nem destino.
if [[ "$FORCE_DB" == "sentinel" ]]; then
  die "$EXIT_PROTECTION" \
    "RECUSADO: destino 'sentinel' é o banco de produção (proibição absoluta, sem override). Use outro dbname no --force-restore-to (ex.: sentinel_restore). Nenhuma conexão foi aberta."
fi

if [[ "$PORT" == "5433" ]] && is_loopback_host "$HOST"; then
  die "$EXIT_PROTECTION" \
    "RECUSADO: destino ${HOST}:5433 é o cluster de origem do Sentinel (não restaurar dentro da origem, mesmo em outro dbname). Use o container isolado (default: localhost:5434) ou outra porta para testes. Nenhuma conexão foi aberta."
fi

# --- 1. pré-validações do arquivo ---------------------------------------------
[[ -f "$FILE" ]] || die "$EXIT_PREFLIGHT" "Arquivo não encontrado: $FILE"
[[ -s "$FILE" ]] || die "$EXIT_PREFLIGHT" "Arquivo vazio: $FILE"

magic="$(head -c 5 "$FILE" || true)"
[[ "$magic" == "PGDMP" ]] \
  || die "$EXIT_PREFLIGHT" "Formato inválido: não é dump custom do PostgreSQL (magic 'PGDMP' ausente)."

SHA_FILE="${FILE}.sha256"
if [[ -f "$SHA_FILE" ]]; then
  dir="$(cd "$(dirname "$FILE")" && pwd)"
  base="$(basename "$FILE")"
  ( cd "$dir" && sha256sum -c "${base}.sha256" --quiet ) \
    || die "$EXIT_PREFLIGHT" "Checksum divergente: $SHA_FILE não confere com o dump."
else
  log "Aviso: checksum ${SHA_FILE} ausente — seguindo sem verificação de integridade do arquivo."
fi

use_container=0
if [[ "$HOST" == "localhost" || "$HOST" == "127.0.0.1" ]] && [[ "$PORT" == "$RESTORE_PORT_DEFAULT" ]]; then
  use_container=1
fi

if (( use_container )); then
  # TOC no container de origem se disponível; senão no container de destino
  # (ambos PG 17); por último no host. pg_restore -l não conecta a banco.
  toc_ok=0
  # inspect de container ausente imprime linha vazia no stdout; tr isola o valor.
  origin_running="$(docker inspect -f '{{.State.Running}}' sentinel-postgres 2>/dev/null | tr -d '[:space:]' || true)"
  dest_running="$(docker inspect -f '{{.State.Running}}' "$RESTORE_CONTAINER" 2>/dev/null | tr -d '[:space:]' || true)"
  if [[ "$origin_running" == "true" ]] \
      && docker exec -i sentinel-postgres pg_restore -l < "$FILE" >/dev/null 2>&1; then
    toc_ok=1
  fi
  if (( toc_ok == 0 )) && [[ "$dest_running" == "true" ]] \
      && docker exec -i "$RESTORE_CONTAINER" pg_restore -l < "$FILE" >/dev/null 2>&1; then
    toc_ok=1
  fi
  if (( toc_ok == 0 )) && command -v pg_restore >/dev/null 2>&1 \
      && pg_restore -l "$FILE" >/dev/null 2>&1; then
    toc_ok=1
  fi
  (( toc_ok == 1 )) || die "$EXIT_PREFLIGHT" "TOC ilegível: pg_restore -l falhou para '$FILE'."
else
  command -v pg_restore >/dev/null 2>&1 \
    || die "$EXIT_PREFLIGHT" "pg_restore não encontrado no PATH (modo host)."
  pg_restore -l "$FILE" >/dev/null 2>&1 \
    || die "$EXIT_PREFLIGHT" "TOC ilegível: pg_restore -l falhou para '$FILE'."
fi

# --- provisionamento e conexão do destino -------------------------------------
if (( use_container )); then
  # inspect de container ausente imprime linha vazia no stdout; tr isola o valor.
  state="$(docker inspect -f '{{.State.Running}}' "$RESTORE_CONTAINER" 2>/dev/null | tr -d '[:space:]' || true)"
  if [[ -z "$state" ]]; then
    log "Provisionando container isolado '$RESTORE_CONTAINER' (porta ${PORT}, volume '$RESTORE_VOLUME')..."
    # Credencial descartável: gerada aqui, usada só pelo bootstrap do container,
    # nunca impressa nem persistida fora do container.
    bootstrap_password="$(openssl rand -hex 16)"
    if ! docker run -d \
        --name "$RESTORE_CONTAINER" \
        -e POSTGRES_DB="$RESTORE_USER_DEFAULT" \
        -e POSTGRES_USER="$RESTORE_USER_DEFAULT" \
        -e POSTGRES_PASSWORD="$bootstrap_password" \
        -p "${PORT}:5432" \
        -v "${RESTORE_VOLUME}:/var/lib/postgresql/data" \
        "$RESTORE_IMAGE" >/dev/null; then
      die "$EXIT_RESTORE" "Falha ao criar container '$RESTORE_CONTAINER' (porta ${PORT} ocupada?)."
    fi
    unset bootstrap_password
  elif [[ "$state" != "true" ]]; then
    log "Container '$RESTORE_CONTAINER' existe mas está parado — iniciando..."
    docker start "$RESTORE_CONTAINER" >/dev/null \
      || die "$EXIT_RESTORE" "Falha ao iniciar container '$RESTORE_CONTAINER'."
  fi

  # Prontidão real: o entrypoint da imagem oficial sobe um servidor TEMPORÁRIO
  # para bootstrap e depois o definitivo. pg_isready sozinho pode aceitar no
  # temporário (falso positivo) — por isso exigimos a marca "init process
  # complete" do entrypoint + um psql SELECT 1 bem-sucedido no servidor atual.
  ready=0
  for _ in $(seq 1 60); do
    if docker logs "$RESTORE_CONTAINER" 2>&1 | grep -q "init process complete" \
        && docker exec "$RESTORE_CONTAINER" psql -U "$PGUSER" -d postgres -tAc "SELECT 1" >/dev/null 2>&1; then
      ready=1; break
    fi
    sleep 1
  done
  (( ready == 1 )) || die "$EXIT_RESTORE" "Destino '$RESTORE_CONTAINER' não ficou pronto (psql SELECT 1)."

  exists="$(docker exec "$RESTORE_CONTAINER" psql -U "$PGUSER" -d postgres -tAc \
    "SELECT 1 FROM pg_database WHERE datname = '${FORCE_DB}'" 2>/dev/null || true)"
  if [[ "$exists" != "1" ]]; then
    log "Criando banco de destino '${FORCE_DB}'..."
    docker exec "$RESTORE_CONTAINER" psql -U "$PGUSER" -d postgres -c \
      "CREATE DATABASE \"${FORCE_DB}\"" >/dev/null \
      || die "$EXIT_RESTORE" "Falha ao criar banco de destino '${FORCE_DB}'."
  fi

  table_count="$(docker exec "$RESTORE_CONTAINER" psql -U "$PGUSER" -d "$FORCE_DB" -tAc \
    "SELECT count(*) FROM pg_catalog.pg_tables WHERE schemaname = 'public'" 2>/dev/null || echo "-1")"
  if [[ "$table_count" == "-1" ]]; then
    die "$EXIT_RESTORE" "Não foi possível consultar o destino '${FORCE_DB}'."
  fi
  if (( table_count > 0 )) && (( ALLOW_NON_EMPTY == 0 )); then
    die "$EXIT_PREFLIGHT" \
      "Destino '${FORCE_DB}' já possui ${table_count} tabela(s) em public. Use --allow-non-empty-destination para forçar."
  fi

  log "Restaurando '$FILE' em ${HOST}:${PORT}/${FORCE_DB} (via $RESTORE_CONTAINER)..."
  if ! docker exec -i "$RESTORE_CONTAINER" \
      pg_restore -U "$PGUSER" -d "$FORCE_DB" --no-owner < "$FILE"; then
    die "$EXIT_RESTORE" "pg_restore falhou no destino '${FORCE_DB}'."
  fi
else
  command -v psql >/dev/null 2>&1 \
    || die "$EXIT_PREFLIGHT" "psql não encontrado no PATH (modo host)."
  # PGPASSWORD exportada (nunca em argv, nunca impressa).
  export PGPASSWORD="${DATABASE_PASSWORD:-}"

  if ! pg_isready -h "$HOST" -p "$PORT" -U "$PGUSER" >/dev/null 2>&1; then
    die "$EXIT_RESTORE" "Destino inacessível: ${HOST}:${PORT}."
  fi

  exists="$(psql -h "$HOST" -p "$PORT" -U "$PGUSER" -d postgres -tAc \
    "SELECT 1 FROM pg_database WHERE datname = '${FORCE_DB}'" 2>/dev/null || echo "-1")"
  if [[ "$exists" == "-1" ]]; then
    die "$EXIT_RESTORE" "Não foi possível consultar o destino (${HOST}:${PORT})."
  fi
  if [[ "$exists" != "1" ]]; then
    log "Criando banco de destino '${FORCE_DB}'..."
    createdb -h "$HOST" -p "$PORT" -U "$PGUSER" "$FORCE_DB" \
      || die "$EXIT_RESTORE" "Falha ao criar banco de destino '${FORCE_DB}'."
  fi

  table_count="$(psql -h "$HOST" -p "$PORT" -U "$PGUSER" -d "$FORCE_DB" -tAc \
    "SELECT count(*) FROM pg_catalog.pg_tables WHERE schemaname = 'public'" 2>/dev/null || echo "-1")"
  if [[ "$table_count" == "-1" ]]; then
    die "$EXIT_RESTORE" "Não foi possível consultar o destino '${FORCE_DB}'."
  fi
  if (( table_count > 0 )) && (( ALLOW_NON_EMPTY == 0 )); then
    die "$EXIT_PREFLIGHT" \
      "Destino '${FORCE_DB}' já possui ${table_count} tabela(s) em public. Use --allow-non-empty-destination para forçar."
  fi

  log "Restaurando '$FILE' em ${HOST}:${PORT}/${FORCE_DB} (pg_restore do host)..."
  if ! pg_restore -h "$HOST" -p "$PORT" -U "$PGUSER" -d "$FORCE_DB" \
      --no-owner "$FILE"; then
    die "$EXIT_RESTORE" "pg_restore falhou no destino '${FORCE_DB}'."
  fi
fi

log "OK: restore concluído em ${HOST}:${PORT}/${FORCE_DB}."
exit "$EXIT_OK"
