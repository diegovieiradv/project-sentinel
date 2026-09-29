# Monitoring Engine — Sentinel

> **Prompt 09 de 18** — execução das checagens HTTP dos monitores. Este documento descreve **o que está construído**: o pacote `com.sentinel.monitoring` (scheduler, dispatcher, executor, revalidação SSRF e transporte), a seleção de elegíveis no banco, a classificação de desfecho/falha e o **resultado transitório em memória** — sem migrations, sem tabela de resultados e sem novo tipo `MonitorCheck`. A configuração declarativa dos monitores é a do [monitors.md](monitors.md); a guarda SSRF de cadastro é a mesma `SsrfGuard` (reutilizada aqui na execução, com fonte única de política).

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
| Resultado persistido (latência/status/histórico), `status` operacional, métricas e séries temporais | **Fora do escopo** (Prompt 10+) |
| Migration nova / entidade JPA no pacote do engine / tipo `MonitorCheck` | **Não existe** (travado por teste de fronteira) |

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
    end
    EXEC -->|MonitorExecutionResult| LOG["Log + contadores<br/>(transitório, sem persistência)"]
```

O scheduler **nunca** executa HTTP: o custo de rede acontece só nas threads do pool (`sentinel-monitoring-*`), identificáveis em thread dumps.

## 3. Fluxo de um tick

1. **Elegibilidade no banco** — `findAllEligibleForExecution()` faz o JOIN Monitor → Service → Project → Organization e aplica, no SQL: `m.active AND s.active AND p.active`, `type = 'HTTP'`, `interval_seconds > 0`, `timeout_millis > 0` e `timeout_millis < interval_seconds * 1000`. Monitor desativado nem entra no tick.
2. **Poda** — `dueTracker.retainOnly(eligibleIds)` descarta estado de quem saiu da elegibilidade (desativado, removido, serviço/projeto inativos).
3. **Decisão por monitor** — ordem **inegociável**: `isDue → tryDispatch → commit`. O `commit` (que adia o próximo vencimento) só acontece em `ACCEPTED`; `IN_FLIGHT` e `SATURATED` deixam o monitor *due* para o próximo tick.
4. **Isolamento** — exceção em um monitor é logada e contada; o lote continua. Exceção no tick inteiro é engolida no `scheduledTick` para o agendador Spring nunca desarmar a task.
5. **Execução** — a `MonitorExecutionTask` roda no pool, chama o executor, loga o resultado e **sempre** libera o in-flight no `finally`.

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
com.sentinel.monitoring                       # fronteira do Prompt 09 (17 classes main)
├── MonitoringSchedulingConfiguration.java    # @EnableScheduling só se enabled=true
├── MonitoringScheduler.java                  # scheduledTick (catch) → tick → dispatchIfDue
├── MonitorEligibilityService.java            # ponto único de consulta da elegibilidade
├── DueTracker.java                           # isDue/commit/retainOnly (estado em memória)
├── MonitoringDispatcher.java                 # pool dedicado + in-flight + contadores
├── MonitorExecutionTask.java                 # fronteira da thread: catch + finally release
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

- **Zero dependências novas no `pom.xml`** — JDK HTTP client, Spring Scheduling e o `SsrfGuard` já existentes.
- `Monitor`/`MonitorRepository` ganharam apenas o método de elegibilidade (aditivo; nenhum contrato de API mudou).
- **Nenhuma migration** — a fronteira não persiste nada (ver §9).
- `Clock` (interface própria, não `java.time.Clock`) e `HttpTransport`/`MonitorTargetValidator` são as costuras de teste: relógio controlado, transporte stub e validador poroso **por contexto de teste**, nunca por configuração de runtime.

## 8. Testes

- **40 testes novos** em 6 classes (`backend/src/test/java/com/sentinel/monitoring/`), zero alteração em classes de teste existentes:
  - `DueTrackerTest` (6) — primeiro vencimento, ciclo de intervalo, `retainOnly`, estado após commit;
  - `MonitoringDispatcherTest` (5) — aceitação, in-flight nunca duplica, saturação (fila cheia) sem contar como falha, exceção que escapa do executor é contada e libera o monitor, contadores por delta;
  - `HttpMonitorExecutorTest` (13) — servidor `com.sun.net.httpserver` real em loopback: 2xx/4xx/5xx, GET×HEAD, timeout, `User-Agent`, redirect **não** seguido (3xx = resposta), corpo descartado;
  - `HttpMonitorExecutorSsrfRuntimeTest` (7) — `SsrfGuard` real + `StubDnsResolver` local: alvo privado recusado na revalidação vira `SENTINEL_INTERNAL_FAILURE/SSRF_REVALIDATION`, nunca `TARGET_FAILURE`;
  - `MonitoringSchedulerTest` (6) — integração com PostgreSQL de teste e transporte stub: execução imediata + intervalo, poda na desativação, inatividade de serviço/projeto, in-flight não duplica, falha interna contada e reexecutada no intervalo seguinte, perfil `test` sem `@Scheduled`;
  - `MonitoringEngineBoundaryTest` (3) — ver §9.
- Contadores são observados por **baseline/delta** (acumulam no contexto Spring); caminhos stub são resetados por teste (`reset()`).
- Nenhum teste depende de rede externa: loopback do JDK, IP público literal (`93.184.216.34`) e DNS stub.
- Comando: `mvn clean test` (perfil `test`, Testcontainers PostgreSQL) e `mvn -DskipTests package`.

## 9. Fronteira do que este prompt NÃO é

Travado por `MonitoringEngineBoundaryTest`:

1. **Nenhum tipo `MonitorCheck`** em lugar nenhum do código (scan de `com.sentinel` no classpath);
2. **Nenhuma `@Entity` em `com.sentinel.monitoring.*`** — o motor não persiste;
3. **Nenhuma migration menciona história de checagem** (`monitor_check`/`monitor_result`/`monitor_history`/`monitor_sample` não aparecem em nome nem conteúdo de nenhuma migration — catálogo termina na `V7` do Prompt 08).

O resultado de uma checagem é **transitório**: viva no `MonitorExecutionResult`, aparece em log e nos contadores, e morre ali. Persistência de saúde/histórico é Prompt 10+ — quando existir, este contrato é atualizado **de propósito**, nunca por acidente.

## 10. Fora do escopo (prompts futuros)

- **Tabela de resultados** (latência, status, falha, séries temporais, janelas de observação) e o `status` operacional do serviço.
- Refinamento de saúde para 4xx/5xx (hoje `HTTP_ERROR` = falha do alvo), alertas, notificações, incidentes e dashboards.
- Endpoints de leitura de execução (hoje o resultado só existe em log/contador).
- Métricas de aplicação (Micrometer) para os contadores do dispatcher.
- Outros tipos de checagem (`PING`, `TCP`, `DNS`) — o `type` continua `HTTP` como invariante do cadastro.
