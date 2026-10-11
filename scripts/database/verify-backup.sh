#!/usr/bin/env bash
# verify-backup.sh — validação de um dump gerado por backup.sh.
#
# Interface:
#   verify-backup.sh <arquivo.dump> [--check-checksum]
#
# Valida: existência, não-vazio, magic "PGDMP" (formato custom), legibilidade
# do TOC via pg_restore -l e, opcionalmente, o checksum .sha256 ao lado.
#
# Exit codes:
#   0  dump válido
#   1  dump inválido (vazio, formato errado, TOC ilegível)
#   2  arquivo não encontrado
#   3  checksum ausente ou divergente
set -euo pipefail
umask 077

EXIT_VALID=0
EXIT_INVALID=1
EXIT_NOT_FOUND=2
EXIT_CHECKSUM=3

ORIGIN_CONTAINER="sentinel-postgres"

log() { printf '%s\n' "$*" >&2; }

usage() {
  cat >&2 <<'EOF'
Usage: verify-backup.sh <arquivo.dump> [--check-checksum]
  --check-checksum  exige <arquivo.dump>.sha256 válido ao lado do dump
Exit codes: 0 válido | 1 inválido | 2 não encontrado | 3 checksum falhou
EOF
}

[[ $# -ge 1 ]] || { usage; exit "$EXIT_INVALID"; }
FILE="$1"
CHECK_CHECKSUM=0
if [[ $# -ge 2 ]]; then
  case "$2" in
    --check-checksum) CHECK_CHECKSUM=1 ;;
    *) log "Opção desconhecida: $2"; usage; exit "$EXIT_INVALID" ;;
  esac
fi

[[ -f "$FILE" ]] || { log "Arquivo não encontrado: $FILE"; exit "$EXIT_NOT_FOUND"; }
[[ -s "$FILE" ]] || { log "Arquivo vazio: $FILE"; exit "$EXIT_INVALID"; }

magic="$(head -c 5 "$FILE" || true)"
[[ "$magic" == "PGDMP" ]] \
  || { log "Formato inválido: não é dump custom do PostgreSQL (magic 'PGDMP' ausente)."; exit "$EXIT_INVALID"; }

# TOC: preferir o pg_restore do container de origem (mesma versão do servidor);
# fallback para o pg_restore do host. pg_restore -l não conecta a banco.
toc_ok=0
# inspect de container ausente imprime linha vazia no stdout; tr isola o valor.
running="$(docker inspect -f '{{.State.Running}}' "$ORIGIN_CONTAINER" 2>/dev/null | tr -d '[:space:]' || true)"
if [[ "$running" == "true" ]]; then
  if docker exec -i "$ORIGIN_CONTAINER" pg_restore -l < "$FILE" >/dev/null 2>&1; then
    toc_ok=1
  fi
elif command -v pg_restore >/dev/null 2>&1; then
  if pg_restore -l "$FILE" >/dev/null 2>&1; then
    toc_ok=1
  fi
fi
(( toc_ok == 1 )) || { log "TOC ilegível: pg_restore -l falhou para '$FILE'."; exit "$EXIT_INVALID"; }

if (( CHECK_CHECKSUM )); then
  SHA_FILE="${FILE}.sha256"
  [[ -f "$SHA_FILE" ]] || { log "Checksum ausente: $SHA_FILE"; exit "$EXIT_CHECKSUM"; }
  dir="$(cd "$(dirname "$FILE")" && pwd)"
  base="$(basename "$FILE")"
  if ! ( cd "$dir" && sha256sum -c "${base}.sha256" --quiet ); then
    log "Checksum divergente: $SHA_FILE não confere com $FILE."
    exit "$EXIT_CHECKSUM"
  fi
fi

log "OK: dump válido (${FILE})."
exit "$EXIT_VALID"
