# Monitoring Engine — Sentinel

> **Prompt 09 de 18** — execução das checagens HTTP dos monitores. Este documento descreve **o que está construído**: o pacote `com.sentinel.monitoring` (scheduler, dispatcher, executor, revalidação SSRF e transporte), a seleção de elegíveis no banco, a classificação de desfecho/falha e o **resultado transitório em memória**. A configuração declarativa dos monitores é a do [monitors.md](monitors.md); a guarda SSRF de cadastro é a mesma `SsrfGuard` (reutilizada aqui na execução, com fonte única de política). O **Prompt 10** acrescentou a persistência do resultado e a avaliação do estado do serviço (`monitor_checks`, `V8`) — ver [operational-state.md](operational-state.md).

## 1. Escopo implementado

| Item | Estado |
| --- | --- |
| `MonitoringSchedulingConfiguration` (`@EnableScheduling` condicional a `sentinel.monitoring.enabled`) | Construído |
| `MonitoringScheduler` — tick curto: elegíveis → poda do `DueTracker` → `isDue → tryDispatch → commit` | Construído |
| `MonitorEligibilityService` + `MonitorRepository.findAllEligibleForExecution()` (filtro único no banco) | Construído |
| `DueTracker` — estado de vencimento em memória, com poda de estado obsoleto | Construído |
| `MonitoringDispatcher` — pool dedicado, guarda de in-flight, backpressure e contadores | Construído |
| `HttpMonitorExecutor` — uma checagem: revalidação SSRF → request → classificação | Construído |
| `SsrfGuardMonitorTargetValidator` — delega ao `SsrfGuard` real do Prompt 08 (sem duplicação, sem bypass) | Construído |
| `JdkHttpTransport` (`java.net.http`, redirects **nunca** seguidos, corpo descartado) | Construído |
| Classificação `MonitorOutcome` × `FailureCategory` + `MonitorExecutionResult` (in-memory) | Construído |
| `MonitoringProperties` (`sentinel.monitoring.*`) + bloco em `application.yml`; `enabled=false` no perfil `test` | Construído |
| Resultado persistido (`monitor_checks`, `V8`), `status` operacional e API de histórico | **Construído no Prompt 10** — ver [operational-state.md](operational-state.md) |
| Migration nova / entidade JPA **dentro do pacote do engine** | **Não existe** (travado por teste de fronteira — `MonitorCheck`/`V8` vivem no domínio `com.sentinel.project.*`, ver §9) |

## 2. Arquitetura

```mermaid
graph TB
    subgraph "Thread agendada (tick curto)"
        SCH[MonitoringScheduler<br/>@Scheduled fixedDelay] -->|findEligibleMonitors| DB[(PostgreSQL<br/>findAllEligibleForExecution)]
        SCH -->|retainOnly| DT[DueTracker<br/>estado due em memória]
        SCH -->|isDue → tryDispatch| DISP[MonitoringDispatcher]
    end
    subgraph "Pool dedicado sentinel-monitoring-*"
        DISP -->|execute| TASK[MonitorExecutionTask<br/>catch + finally release]
        TASK --> EXEC[HttpMonitorExecutor]
        EXEC -->|1. revalida| GUARD[MonitorTargetValidator<br/>→ SsrfGuard real]
        EXEC -->|2. envia| TRANSPORT[HttpTransport<br/>JdkHttpTransport]
        TRANSPORT -->|GET/HEAD + timeout| TARGET[Alvo externo HTTPS]
        TASK -->|MonitorExecutionResult| REC["MonitorCheckRecorder<br/>+ OperationalStateEvaluator<br/>(Prompt 10 — ver operational-state)"]
        TASK -->|MonitorExecutionResult| LOG["Log + contadores do dispatcher<br/>(transitório)"]
    end
    REC --> DB2[(monitor_checks<br/>+ services.status)]
```

O scheduler **nunca** executa HTTP: o custo de rede acontece só nas threads do pool (`sentinel-monitoring-*`), identificáveis em thread dumps. O destino do resultado (log/contadores do Prompt 09 e persistência + avaliação do Prompt 10) está em [operational-state.md](operational-state.md) §4.

## 3. Fluxo de um tick

1. **Elegibilidade no banco** — `findAllEligibleForExecution()` faz o JOIN Monitor → Service → Project → Organization e aplica, no SQL: `m.active AND s.active AND p.active`, `type = 'HTTP'`, `interval_seconds > 0`, `timeout_millis > 0` e `timeout_millis < interval_seconds * 1000`. Monitor desativado nem entra no tick.
2. **Poda** — `dueTracker.retainOnly(eligibleIds)` descarta estado de quem saiu da elegibilidade (desativado, removido, serviço/projeto inativos).
3. **Decisão por monitor** — ordem **inegociável**: `isDue → tryDispatch → commit`. O `commit` (que adia o próximo vencimento) só acontece em `ACCEPTED`; `IN_FLIGHT` e `SATURATED` deixam o monitor *due* para o próximo tick.
4. **Isolamento** — exceção em um monitor é logada e contada; o lote continua. Exceção no tick inteiro é engolida no `scheduledTick` para o agendador Spring nunca desarmar a task.
5. **Execução** — a `MonitorExecutionTask` roda no pool, chama o executor, loga o resultado, **persiste a linha e reavalia o estado do serviço** (etapas do Prompt 10 — ver [operational-state.md](operational-state.md) §4) e **sempre** libera o in-flight no `finally`.

## 4. Despacho (`MonitoringDispatcher.DispatchOutcome`)

| Desfecho | Quando | Efeito |
| --- | --- | --- |
| `ACCEPTED` | submetido ao pool | contador `dispatched` sobe; `commit` do vencimento |
| `IN_FLIGHT` | monitor já em execução (`Set.add` atômico) | nada submetido; segue *due* — nunca roda em paralelo |
| `SATURATED` | fila cheia / pool em shutdown (`RejectedExecutionException`) | contador `saturated` sobe; **nunca** conta como falha de alvo nem erro interno; segue *due* |

Contadores públicos (desde o startup): `dispatchedCount`, `saturatedCount`, `internalErrorCount`, `inFlightCount()` + `awaitIdle(timeout)` para testes/desligamento gracioso (`@PreDestroy` espera até 5s).

## 5. Classificação de uma checagem

`MonitorOutcome` (desfecho) × `FailureCategory` (o *porquê*) — sem sobreposição:

| Situação | Outcome | Category |
| --- | --- | --- |
| Resposta 2xx/3xx (redirect **não** seguido: 3xx chega como resposta) | `SUCCESS` | — |
| Resposta 4xx/5xx | `TARGET_FAILURE` | `HTTP_ERROR` |
| `HttpTimeoutException`/`SocketTimeoutException` | `TARGET_FAILURE` | `TIMEOUT` |
| `UnknownHostException` | `TARGET_FAILURE` | `DNS` |
| `SSLException` | `TARGET_FAILURE` | `TLS` |
| `ConnectException`/`NoRouteToHostException`/demais `IOException` | `TARGET_FAILURE` | `CONNECT` |
| `SsrfGuard` recusou **na hora da conexão** (revalidação/TOCTOU) | `SENTINEL_INTERNAL_FAILURE` | `SSRF_REVALIDATION` |
| Bug inesperado / `InterruptedException` (restaura o sinal) | `SENTINEL_INTERNAL_FAILURE` | `INTERNAL` |

Regras de conteúdo: nenhuma mensagem carrega corpo de resposta, URL com query string, `Authorization` ou cookie; o request leva **apenas** `User-Agent: Sentinel-Monitor/1.0` e o timeout da própria request é `Monitor.timeoutMillis` (a V7 garante `timeout < interval`). Saturação **não** gera `MonitorExecutionResult`.

O **único refinamento** feito depois desta classificação é o 3xx: o motor diz `SUCCESS` (houve resposta e a requisição completou), a persistência registra `DEGRADED_SIGNAL` — ver [operational-state.md](operational-state.md) §5.

**Ordem no executor:** início no `Clock` → revalidação SSRF imediatamente antes da rede (a guarda do cadastro valeu no passado; aqui vale de novo — defesa contra DNS rebinding/TOCTOU; a janela residual até o resolve interno do `HttpClient` não é eliminável por completo) → monta request → `transport.send` → classifica. `execute()` **nunca lança**: tudo vira resultado (`monitorId` só é `null` em bug interno).

## 6. Configuração

| Propriedade | Default | Papel |
| --- | --- | --- |
| `sentinel.monitoring.enabled` | `true` | liga/desliga o `@Scheduled` (perfil `test` = `false` → nenhum tick agendado; testes chamam `tick()` na mão) |
| `sentinel.monitoring.scheduler-delay-ms` | `10000` | atraso entre ticks |
| `sentinel.monitoring.worker-count` | `4` | threads do pool dedicado |
| `sentinel.monitoring.queue-capacity` | `200` | fila do pool (saturação quando enche) |

Config inválida (`worker-count < 1`, `queue < 0`) derruba o startup no `initialize()` — fail-fast, sem fallback silencioso. Não existe flag que desligue a revalidação SSRF.

## 7. Estrutura de código (backend)

```
com.sentinel.monitoring                       # fronteira do Prompt 09 + 2 classes do Prompt 10 (19 classes main)
├── MonitoringSchedulingConfiguration.java    # @EnableScheduling só se enabled=true
├── MonitoringScheduler.java                  # scheduledTick (catch) → tick → dispatchIfDue
├── MonitorEligibilityService.java            # ponto único da consulta de elegibilidade
├── DueTracker.java                           # isDue/commit/retainOnly (estado em memória)
├── MonitoringDispatcher.java                 # pool dedicado + in-flight + contadores
├── MonitorExecutionTask.java                 # fronteira da thread: catch + finally release
├── MonitorCheckRecorder.java                 # Prompt 10: grava monitor_checks (commit próprio)
├── OperationalStateEvaluator.java            # Prompt 10: janela de 3 POR MONITOR + tabela §18 → Service.status
├── HttpMonitorExecutor.java                  # 1 checagem: valida → envia → classifica
├── MonitorTargetValidator.java               # interface (injeção nos testes)
├── SsrfGuardMonitorTargetValidator.java      # produção: delega ao SsrfGuard do Prompt 08
├── HttpTransport.java                        # interface (int send(HttpRequest))
├── JdkHttpTransport.java                     # java.net.http, NEVER redirects, discarding()
├── MonitorExecutionResult.java               # record transitório + factory `of`
├── MonitorOutcome.java                       # SUCCESS | TARGET_FAILURE | SENTINEL_INTERNAL_FAILURE
├── FailureCategory.java                      # DNS|CONNECT|TIMEOUT|TLS|HTTP_ERROR|SSRF_REVALIDATION|INTERNAL
├── Clock.java / SystemClock.java             # tempo injetável (testes avançam o relógio)
└── MonitoringProperties.java                 # sentinel.monitoring.*
```

- **Zero dependências novas no `pom.xml`** — JDK HTTP client, Spring Scheduling e o `SsrfGuard` já existentes (o Prompt 10 também não acrescentou nenhuma).
- `Monitor`/`MonitorRepository` ganharam o método de elegibilidade (Prompt 09) e `findActiveIdsByServiceId` (Prompt 10) — aditivo; nenhum contrato de API mudou.
- **Nenhuma `@Entity` e nenhum repositório de entidade neste pacote** — `MonitorCheckRecorder`/`OperationalStateEvaluator` usam o repositório do domínio `com.sentinel.project.monitor` (ver §9).
- A persistência do resultado é a `monitor_checks` (**V8**), descrita em [operational-state.md](operational-state.md) §3.
- `Clock` (interface própria, não `java.time.Clock`) e `HttpTransport`/`MonitorTargetValidator` são as costuras de teste: relógio controlado, transporte stub e validador poroso **por contexto de teste**, nunca por configuração de runtime.

## 8. Testes

O pacote de teste `backend/src/test/java/com/sentinel/monitoring/` tem hoje 8 classes / **75 testes** — 41 já estavam no fim do Prompt 09 e 34 vieram com o Prompt 10 (recorder e avaliador são novos; dispatcher, scheduler e boundary foram ampliados):

- `DueTrackerTest` (6) — primeiro vencimento, ciclo de intervalo, `retainOnly`, estado após commit;
- `MonitoringDispatcherTest` (8) — aceitação, in-flight nunca duplica, saturação (fila cheia) sem contar como falha, exceção que escapa do executor é contada e libera o monitor, contadores por delta + (Prompt 10) falha na gravação pula a avaliação e falha na avaliação preserva o histórico;
- `HttpMonitorExecutorTest` (13) — servidor `com.sun.net.httpserver` real em loopback: 2xx/4xx/5xx, GET×HEAD, timeout, `User-Agent`, redirect **não** seguido (3xx = resposta), corpo descartado;
- `HttpMonitorExecutorSsrfRuntimeTest` (7) — `SsrfGuard` real + `StubDnsResolver` local: alvo privado recusado na revalidação vira `SENTINEL_INTERNAL_FAILURE/SSRF_REVALIDATION`, nunca `TARGET_FAILURE`;
- `MonitoringSchedulerTest` (12) — integração com PostgreSQL de teste e transporte stub: execução imediata + intervalo, poda na desativação, inatividade de serviço/projeto, in-flight não duplica, falha interna contada e reexecutada no intervalo seguinte, perfil `test` sem `@Scheduled` + (Prompt 10) subida/descida da tabela de status, 500×3 nunca vira `UNAVAILABLE`, 302×3 = `DEGRADED_SIGNAL`, 1 linha por execução, `startedAt` repetido e (revisão) falhas internas persistidas **sem mover o status**;
- `MonitorCheckRecorderTest` (10, **novo no Prompt 10**) — tradução e sanitização da mensagem;
- `OperationalStateEvaluatorTest` (15, **novo no Prompt 10**) — cada linha da tabela de agregação, com a indisponibilidade testada **por monitor** (5 casos multi-monitor);
- `MonitoringEngineBoundaryTest` (4) — ver §9.

Contadores são observados por **baseline/delta** (acumulam no contexto Spring); caminhos stub são resetados por teste (`reset()`).
Nenhum teste depende de rede externa: loopback do JDK, IP público literal (`93.184.216.34`) e DNS stub.
Comando: `mvn clean test` (perfil `test`, Testcontainers PostgreSQL) e `mvn -DskipTests package`.

## 9. Fronteira do que este prompt NÃO é

Travado por `MonitoringEngineBoundaryTest` (contrato **atualizado de propósito** no Prompt 10):

1. **Nenhuma `@Entity` em `com.sentinel.monitoring.*`** — o motor orquestra; quem persiste é `com.sentinel.project.monitor.model.MonitorCheck`;
2. **UMA tabela de histórico** (`monitor_checks`, `V8`), append-only — sem `UNIQUE (monitor_id, started_at)`, sem `execution_id`, sem `service_id` redundante, sem `updated_at`;
3. **`Service.status` só é escrito pelo `OperationalStateEvaluator`** — nenhum endpoint altera status.

O resultado de uma checagem continua transitório **dentro do motor** (vive no `MonitorExecutionResult`, aparece em log e nos contadores); a linha persistida é responsabilidade do Prompt 10 e está inteira em [operational-state.md](operational-state.md).

## 10. Fora do escopo (prompts futuros)

- Séries temporais, uptime %, janelas de observação por período e retenção/expurgo do histórico.
- Alertas, notificações, incidentes e dashboards (consumidores de `Service.status`).
- Métricas de aplicação (Micrometer) para os contadores do dispatcher.
- Outros tipos de checagem (`PING`, `TCP`, `DNS`) — o `type` continua `HTTP` como invariante do cadastro.
