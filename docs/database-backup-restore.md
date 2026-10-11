# Database Backup & Restore — Sentinel

> **Etapa Pós-Roadmap 02.3.** Procedimento operacional de backup, verificação e restore do banco PostgreSQL do Sentinel, exercitado de ponta a ponta neste repositório com validação de integridade. Os scripts vivem em [`scripts/database/`](../scripts/database/) e todos os comandos abaixo foram executados e validados (exit codes reais registrados na §8).

## 1. Visão geral e decisões

| Decisão | Escolha | Motivo |
| --- | --- | --- |
| Formato do backup | `pg_dump --format=custom` (`-Fc`) | formato comprimido + suporte a `pg_restore` seletivo e listing (`-l`) |
| Escopo | banco `sentinel` inteiro | simplesto para restore fiel de schema + dados + `flyway_schema_history` |
| Privilegios | `--no-owner --no-privileges` | dump portável entre ambientes/usuários |
| Ferramenta de dump | `pg_dump` **do container** `sentinel-postgres` (`docker exec`) | paridade de versão exata com o servidor PG 17 e **nenhuma senha em argv/log** |
| Arquivo | `backups/sentinel_YYYYMMDD_HHMMSS.dump` + `.sha256` | rastreável + integridade verificável |
| Permissões | `umask 077`; dir `700`; arquivos `600` | artefato sensível no host |
| Restore | `pg_restore --no-owner`, **sem `--clean`** | nunca pré-remover no destino; ver §4 |
| Destino de teste | container dedicado `sentinel-restore-test` (PG 17, porta **5434**, volume próprio) | isolamento real da origem (5433/`sentinel_pgdata`) |
| Proteção de produção | `--force-restore-to` obrigatório; recusa destino `sentinel` **sem override possível**, exit 3 antes de qualquer conexão | impedir restore acidental no banco de produção |
| Proteção do cluster de origem | destino em host loopback (literal ou via DNS) **+ porta 5433** recusado com exit 3, antes de qualquer conexão | impedir restore dentro do cluster de origem (`sentinel-postgres`), mesmo em dbname diferente |

**Proibições operacionais deste fluxo** (além das proteções dos scripts):

- **Nunca** `docker compose down -v` / remoção do volume `sentinel_pgdata` — destrói a origem.
- **Nunca** restore contra o banco `sentinel` — o `restore.sh` recusa de forma **incondicional** (não existe override de env var). Restauração no banco original **não é suportada pelo script**; em caso de emergência real, o procedimento é manual e fora do escopo deste script.
- **Nunca** apontar o destino para o cluster de origem (host loopback + porta **5433**), mesmo com outro dbname — também recusado pelo script (exit 3). A porta é canonicalizada para decimal antes da comparação (zero à esquerda, ex.: `05433`, é normalizado para `5433` e também recusado).
- Senhas **nunca** em argv (`ps`), logs ou mensagens — o modo container usa socket local (trust dentro do container); o modo host usa `PGPASSWORD` exportada.

> **Nota sobre `--if-exists`**: o `pg_restore` exige `--clean` para aceitar `--if-exists`. Como o plano proíbe `--clean`, o restore usa apenas `--no-owner`; a proteção contra destino poluído é a checagem de **destino vazio** (§4).

## 2. Pré-requisitos

| Requisito | Como verificar | Observação |
| --- | --- | --- |
| Docker + compose v2 | `docker compose version` | origin: `docker compose up -d --wait` |
| Container de origem UP | `docker ps --filter name=sentinel-postgres` | imagem `postgres:17-alpine`, porta host **5433** |
| Schema migrado | `flyway_schema_history` com V1–V12 `success` | aplicado no startup do backend (perfil dev) |
| `sha256sum`, `bash` | presentes no Linux do projeto | sempre |
| `pg_restore`/`psql` do host | opcionais | modo host do `restore.sh` (fora de localhost:5434; loopback:5433 é **recusado**); no modo container não são necessários |

Portas do ambiente local: **5433** origem, **5434** restore isolado, 8080 backend, 3000 frontend.

## 3. Backup

```bash
# default: diretório <repo>/backups, host localhost, porta 5433, user/db sentinel
scripts/database/backup.sh

# opções
scripts/database/backup.sh -d /caminho/destino -h localhost -p 5433 -U sentinel -b sentinel
```

Fluxo interno (modo container, default):

1. pré-check: container `sentinel-postgres` está UP (senão **exit 1**);
2. `docker exec sentinel-postgres pg_dump -U sentinel -d sentinel --format=custom --no-owner --no-privileges > backups/sentinel_YYYYMMDD_HHMMSS.dump`;
3. valida: dump não-vazio + `pg_restore -l` legível (qualquer falha → remove o arquivo parcial e **exit ≠ 0**);
4. grava `sentinel_YYYYMMDD_HHMMSS.dump.sha256` (mesmo basename, para `sha256sum -c`);
5. imprime caminho, tamanho e checksum (sem segredos).

Exit codes: **0** ok · **1** validação · **2** erro `pg_dump` · **3** argumento inválido.

Exemplo real desta execução:

```
OK: .../backups/sentinel_20261011_040415.dump (43176 bytes)
sha256: e5feeb06c3165246cdb595e61acc4b40ad91dec96f772c3a682cc6c40d66ccdb
```

Modo host (fora de localhost:5433): usa `pg_dump` do PATH com `PGPASSWORD` vinda do ambiente — exporte `DATABASE_PASSWORD` antes; a senha nunca aparece em argv nem nos logs do script.

## 4. Restore (proteções + ambiente isolado)

```bash
# fluxo normal: destino = banco DENTRO do container isolado (criado se necessário)
scripts/database/restore.sh backups/sentinel_YYYYMMDD_HHMMSS.dump --force-restore-to sentinel_restore

# banco de destino com tabelas já existentes (só se você realmente quer sobrescrever por cima)
scripts/database/restore.sh <arquivo.dump> --force-restore-to sentinel_restore --allow-non-empty-destination
```

Ordem de proteções (importante):

1. **argumentos** → exit **4** (porta/dbname/usuário inválidos);
2. **proteção de destino** → destino `sentinel` recusado **incondicionalmente** (sem override) com exit **3 antes de qualquer conexão**; destino em host loopback (literal ou via DNS) + porta **5433** — o cluster de origem do Sentinel — também recusado com exit **3** antes de qualquer conexão, mesmo com dbname diferente (nenhum docker/psql/restore é tocado); a porta é canonicalizada para decimal antes dessa comparação (zero à esquerda, ex.: `05433`, é normalizado para `5433`);
3. **pré-validações do arquivo** → exit **1**: existe, não-vazio, magic `PGDMP` (formato custom), checksum `.sha256` presente confere (se o arquivo existir);
4. **destino** (modo isolado default) → provisiona/usa o container `sentinel-restore-test`; espera o servidor **real** ficar pronto (marca `init process complete` do entrypoint + `psql SELECT 1` — `pg_isready` sozinho aceita o servidor temporário de bootstrap e dá falso positivo); cria o dbname se necessário; **recusa destino com tabelas em `public`** sem `--allow-non-empty-destination` (exit **1**);
5. **restore** → `pg_restore -U <user> -d <dbname> --no-owner` via `docker exec -i` (socket local, sem senha); falha → exit **2**.

Container isolado (nome fixo, criado uma vez e reutilizado):

| Atributo | Valor |
| --- | --- |
| nome | `sentinel-restore-test` |
| imagem | `postgres:17-alpine` (mesma major da origem) |
| porta | `5434:5432` |
| banco/usuário | `sentinel_restore` |
| volume | `sentinel_restore_test_data` (descartável) |
| senha | gerada no provisionamento (`openssl rand`), não persistida fora do container, nunca impressa |

Limpeza do ambiente de teste (sempre permitida — é descartável):

```bash
docker rm -vf sentinel-restore-test
docker volume rm sentinel_restore_test_data
```

> O `restore.sh` **nunca remove** nem limpa o destino automaticamente — ele só cria o container/banco se não existirem.

Exit codes: **0** ok · **1** pré-validação · **2** conexão/`pg_restore` · **3** proteção destino · **4** argumento inválido.

## 5. Verificação do artefato (`verify-backup.sh`)

```bash
scripts/database/verify-backup.sh backups/sentinel_YYYYMMDD_HHMMSS.dump                # estrutura
scripts/database/verify-backup.sh backups/sentinel_YYYYMMDD_HHMMSS.dump --check-checksum  # + sha256
```

Valida: arquivo existe (exit **2** se ausente), não-vazio e magic `PGDMP` + TOC via `pg_restore -l` (exit **1** se inválido), e com `--check-checksum` o `.sha256` ao lado (exit **3** se ausente/divergente). O `pg_restore -l` usa o cliente do container de origem (paridade PG 17) quando disponível; senão, o do host — listing não conecta a banco nenhum.

## 6. Provisionamento + seed de dados descartáveis

Para exercitar backup/restore com dados reais sem tocar em dados de ninguém:

```bash
# 1. subir o banco (cria volume + container; NUNCA use down -v depois)
docker compose up -d --wait

# 2. subir o backend (aplica Flyway V1–V12 no startup) — monitoring desligado
#    para que o seed seja determinístico (nenhum check/alerta gerado pelo motor)
SPRING_PROFILES_ACTIVE=dev MONITORING_ENABLED=false \
  java -jar backend/target/sentinel-backend-0.1.0-SNAPSHOT.jar &

# 3. aguardar health
curl -s http://localhost:8080/api/v1/health   # {"status":"UP",...}

# 4. seed via API (método primário) — cobre as tabelas de domínio
#    users + refresh_tokens (register/login), organizations + memberships (create org),
#    projects, services, monitors, alert_rules, incidents + incident_events (create incident).
#    CSRF: GET /api/v1/health materializa o cookie XSRF-TOKEN; envie-o em
#    X-XSRF-TOKEN junto do Bearer em todo POST.
curl -s -c /tmp/cj http://localhost:8080/api/v1/health > /dev/null
CSRF=$(awk '$6=="XSRF-TOKEN"{print $7}' /tmp/cj | tail -1)
curl -s -b /tmp/cj -c /tmp/cj -X POST http://localhost:8080/api/v1/auth/register \
  -H 'Content-Type: application/json' -H "X-XSRF-TOKEN: $CSRF" \
  -d '{"name":"Backup Tester","email":"backup-test-<ts>@test.local","password":"<senha-forte>"}'
# ... login → accessToken; depois POST /organizations, /projects, /services,
#     /monitors (url pública, ex. https://example.com), /alert-rules, /incidents
#     com Authorization: Bearer <token> + X-XSRF-TOKEN.

# 5. fallback psql JUSTIFICADO para dados dirigidos pelo motor (não determinísticos
#    via API): monitor_checks e alerts — inserts na ordem de FKs
docker exec -i sentinel-postgres psql -U sentinel -d sentinel -v ON_ERROR_STOP=1 <<SQL
INSERT INTO monitor_checks (id, monitor_id, started_at, finished_at, duration_millis,
  http_status, outcome, failure_category, message, created_at)
VALUES (gen_random_uuid(), '<monitor_id>', now(), now(), 123, 200, 'SUCCESS', NULL,
  'Backup seed fixture check <ts>', now());
INSERT INTO alerts (id, service_id, alert_rule_id, monitor_id, status, severity,
  resolution_reason, started_at, resolved_at, last_evaluated_at, trigger_value, message,
  rule_type, rule_operator, rule_threshold_numeric, rule_threshold_status, rule_window,
  created_at, updated_at, incident_id)
VALUES (gen_random_uuid(), '<service_id>', '<rule_id>', NULL, 'FIRING', 'SEV2', NULL,
  now(), NULL, now(), 42, 'Backup seed fixture alert <ts>', 'AVAILABILITY', 'LESS_THAN',
  100, NULL, 'FIVE_MINUTES', now(), now(), NULL);
SQL

# 6. parar o backend (o backup captura um estado estável)
kill %1   # ou pkill -f sentinel-backend
```

Conventions do seed desta etapa: e-mails `backup-test-<ts>@test.local`, nomes `Backup {ts}`/`Backup Project {ts}`/…, fixtures de mensagem `Backup seed fixture …` — todos com o mesmo `<ts>` para rastreio. Resultado da execução real: **≥1 linha nas 12 tabelas de domínio** (users, refresh_tokens, organizations, memberships, projects, services, monitors, monitor_checks, alert_rules, alerts, incidents, incident_events).

## 7. Validação de integridade (pós-restore)

Após o restore, comparar origem e destino (somente leitura; origem via container de origem, destino via container de restore):

```bash
O() { docker exec sentinel-postgres psql -U sentinel -d sentinel -tAc "$1"; }
D() { docker exec sentinel-restore-test psql -U sentinel_restore -d sentinel_restore -tAc "$1"; }

# 13 tabelas (12 de domínio + flyway_schema_history)
O "SELECT count(*) FROM information_schema.tables WHERE table_schema='public'"
D "SELECT count(*) FROM information_schema.tables WHERE table_schema='public'"

# contagens idênticas por tabela
for t in users refresh_tokens organizations memberships projects services monitors \
         monitor_checks alert_rules alerts incidents incident_events flyway_schema_history; do
  printf '%-22s O=%s D=%s\n' "$t" "$(O "SELECT count(*) FROM $t")" "$(D "SELECT count(*) FROM $t")"
done

# catálogo Flyway idêntico (versão + sucesso + checksum)
O "SELECT version||'|'||success||'|'||checksum FROM flyway_schema_history WHERE type='SQL' ORDER BY installed_rank"
D "SELECT version||'|'||success||'|'||checksum FROM flyway_schema_history WHERE type='SQL' ORDER BY installed_rank"

# FKs íntegras
D "SELECT count(*) FROM memberships m JOIN users u ON u.id=m.user_id JOIN organizations o ON o.id=m.organization_id"
D "SELECT count(*) FROM alerts a JOIN alert_rules r ON r.id=a.alert_rule_id JOIN services s ON s.id=a.service_id"

# fixtures do seed presentes no destino
D "SELECT email FROM users WHERE email LIKE 'backup-test-%@test.local'"
```

**Proteção da origem (Cenário D)**: capturar os mesmos indicadores da origem **antes** e **depois** de todo o exercício (contagens, `flyway_schema_history`, fixtures) e exigir `diff` vazio — a origem deve permanecer intocada. Nesta execução: **diff vazio antes/depois do restore e antes/depois dos 12 testes negativos**.

## 8. Testes negativos

Todos devem dar **exit ≠ 0** e **não alterar a origem**. Executados nesta etapa:

| # | Cenário | Comando | Exit esperado | Exit obtido | Resultado |
| --- | --- | --- | --- | --- | --- |
| N1 | arquivo inexistente | `restore.sh /tmp/nao-existe.dump --force-restore-to sentinel_restore_neg` | 1 | 1 | **PASS** |
| N2 | arquivo vazio | `restore.sh vazio.dump --force-restore-to sentinel_restore_neg` | 1 | 1 | **PASS** |
| N3 | arquivo corrompido (1024 bytes aleatórios) | `restore.sh corrompido.dump --force-restore-to sentinel_restore_neg` | 1 | 1 | **PASS** |
| N4 | checksum divergente (dump editado + `.sha256` antigo, mesmo basename) | `restore.sh <dump-editado> --force-restore-to sentinel_restore_neg` | 1 | 1 | **PASS** |
| N4b | idem, pela ótica do verificador | `verify-backup.sh <dump-editado> --check-checksum` | 3 | 3 | **PASS** |
| N5 | destino já populado sem `--allow-non-empty-destination` | `restore.sh <dump> --force-restore-to sentinel_restore` (2ª vez) | 1 | 1 | **PASS** |
| N6 | destino == produção (`sentinel`) — recusa incondicional antes de qualquer conexão | `restore.sh <dump> --force-restore-to sentinel` | 3 | 3 | **PASS** |
| N7 | destino indisponível (porta 59999) | `restore.sh <dump> --force-restore-to sentinel_restore_neg -p 59999` | 2 | 2 | **PASS** |
| N8 | destino no cluster de origem (host loopback + porta 5433, dbname diferente) — recusa pré-conexão | `restore.sh <dump> --force-restore-to sentinel_restore -h localhost -p 5433` | 3 | 3 | **PASS** |
| N8b | idem com `127.0.0.1` | `restore.sh <dump> --force-restore-to sentinel_restore -h 127.0.0.1 -p 5433` | 3 | 3 | **PASS** |
| N8d | idem com zero à esquerda na porta (`05433`) — canonicalização `10#` converte para 5433 **antes** da comparação, então a guarda de origem dispara — recusa pré-conexão | `restore.sh <dump> --force-restore-to sentinel_restore -h localhost -p 05433` | 3 | 3 | **PASS** |
| N8e | porta com 6 dígitos (`005433`) — rejeitada pela regex `^[0-9]{1,5}$` na pré-validação de argumentos (comportamento correto) | `restore.sh <dump> --force-restore-to sentinel_restore -h localhost -p 005433` | 4 | 4 | **PASS** |

Indicadores da origem capturados antes e depois da suíte negativa: **idênticos** (`diff` vazio).

## 9. Troubleshooting

| Sintoma | Causa provável | Ação |
| --- | --- | --- |
| backup exit 1: container não está em execução | compose não subiu | `docker compose up -d --wait` (nunca `down -v`) |
| restore exit 2: porta 5434 ocupada | outro processo no mapa do container | libere a porta ou remova o container legado (`docker rm -vf sentinel-restore-test` + volume, se descartável) |
| restore exit 1: destino já possui N tabela(s) | destino não está vazio | use outro dbname, ou `--allow-non-empty-destination` se sobrescrever é a intenção |
| restore exit 3: destino 'sentinel' é o banco de produção | proteção de produção atingida (incondicional, sem override) | use um dbname distinto (ex.: `sentinel_restore`); nunca faça restore na origem |
| restore exit 3: destino no cluster de origem (loopback:5433) | host de destino resolve para 127.0.0.1/localhost e porta 5433 (cluster `sentinel-postgres`) | use o container isolado (default `localhost:5434`) ou outra porta para testes |
| restore exit 2: `pg_restore falhou` | dump incompatível com o servidor ou objeto conflitante | verifique a saída do `pg_restore`; destino vazio + dump da mesma versão resolve o caso comum |
| verify exit 3 | `.sha256` ausente ou divergente | regenere o backup; não confie em artefato alterado |
| `psql ... socket ... failed` durante provisionamento | corrida com o servidor temporário de bootstrap do container novo | versões atualizadas do `restore.sh` esperam `init process complete` + `psql SELECT 1`; atualize o script |
| permissão negada no modo host | `PGPASSWORD` não exportada | `export DATABASE_PASSWORD=...` (valor real; nunca imprima) |

## 10. Cheat sheet

```bash
# --- ciclo completo local (origem compose) ---
docker compose up -d --wait                                            # banco origem (5433)
scripts/database/backup.sh                                             # dump + sha256 em backups/
scripts/database/verify-backup.sh backups/sentinel_*.dump --check-checksum
scripts/database/restore.sh backups/sentinel_<ts>.dump --force-restore-to sentinel_restore
# ... comparar origem vs destino (§7) ...
docker rm -vf sentinel-restore-test && docker volume rm sentinel_restore_test_data   # limpa isolado

# --- inspeção sem restore ---
scripts/database/verify-backup.sh backups/sentinel_<ts>.dump --check-checksum
docker exec -i sentinel-postgres pg_restore -l < backups/sentinel_<ts>.dump | head

# --- estado ---
docker ps --filter name=sentinel --format '{{.Names}} {{.Status}} {{.Ports}}'
docker exec sentinel-postgres psql -U sentinel -d sentinel -tAc \
  "SELECT version, success FROM flyway_schema_history ORDER BY installed_rank"

# --- NUNCA ---
# docker compose down -v                       # destrói o volume da origem
# restore.sh <dump> --force-restore-to sentinel           # recusado (exit 3, incondicional)
# restore.sh <dump> ... -h localhost -p 5433             # recusado (exit 3) — cluster de origem
```

Referências: [production-checklist.md](production-checklist.md) §5 · [homologation-readiness.md](homologation-readiness.md) §4 (#6) · scripts em [`scripts/database/`](../scripts/database/).
