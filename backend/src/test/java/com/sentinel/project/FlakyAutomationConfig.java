package com.sentinel.project;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import com.sentinel.project.alert.automation.AlertIncidentCorrelator;
import com.sentinel.project.alert.automation.AlertIncidentLinker;
import com.sentinel.project.alert.repository.AlertRepository;
import com.sentinel.project.incident.model.Incident;
import com.sentinel.project.incident.service.IncidentService;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Colaboradores da automação Alert → Incident que TRAVAM ou EXPLODEM sob
 * controle dos testes (Prompt 15, revisão P1-1/P1-2/P2-3) — é como o
 * orquestrador enxerga uma corrida forçada e uma falha transitória, sem
 * quebrar o contexto inteiro nem depender de sorte de escalonamento.
 *
 * <p>Arranjo espelhado do {@code FlakyWindowMetricsConfig}: configuração de
 * teste de nível superior, importada explicitamente APENAS pelas classes que
 * precisam dos caminhos anormais ({@code AlertIncidentConcurrencyTest} e
 * {@code AlertIncidentAutomationTest}). Os delegados são os beans reais de
 * produção — com tudo em {@code null}/{@code false} o comportamento é idêntico
 * ao normal.</p>
 *
 * <p><b>Por que decorar e não mockar:</b> os portões ficam ANTES/depois das
 * escritas reais do repositório, então o teste disputa o MESMO índice do
 * Postgres com a MESMA query de produção (o desfecho é o que o banco decide,
 * não o que um mock devolve).</p>
 *
 * <ul>
 *   <li>{@link #correlationGate} — o correlator consulta primeiro e só ENTÃO
 *       sinaliza e espera: os dois lados leem "vazio" antes de qualquer um
 *       criar, o que força a corrida real pelo índice parcial único da V12.
 *       Depois que os dois passaram, o portão fica em zero e a releitura do
 *       perdedor não trava;</li>
 *   <li>{@link #correlatorExplodes} — a correlação falha como se o armazem de
 *       incidentes estivesse fora (isolamento de falhas + recuperação no
 *       ciclo seguinte);</li>
 *   <li>{@link #linkReached}/{@link #linkProceed} — o vínculo da automação
 *       sinaliza que chegou ao {@code UPDATE} e fica preso nele, abrindo a
 *       janela em que o vínculo HUMANO vence a corrida (P1-1). O caminho
 *       manual não passa por este bean (usa o repositório direto, via
 *       {@code IncidentService.linkAlert}).</li>
 *   <li>{@link #corruptCorrelation} — a correlação lança
 *       {@link DataIntegrityViolationException} a cada tentativa, com a
 *       mensagem que o teste escolher: é assim que se prova que o
 *       orquestrador TENTA DE NOVO só para a violação da constraint da
 *       V12 e sobe a exceção para qualquer outra (Prompt 17, RES-07).</li>
 * </ul>
 */
@TestConfiguration
public class FlakyAutomationConfig {

    /** Portão da correlação ({@code null} = normal). */
    public static volatile CountDownLatch correlationGate;

    /** Falha injetada na correlação ({@code false} = normal). */
    public static volatile boolean correlatorExplodes;

    /** Sinal de que o vínculo chegou ao UPDATE ({@code null} = normal). */
    public static volatile CountDownLatch linkReached;

    /** Portão que segura o vínculo da automação ({@code null} = normal). */
    public static volatile CountDownLatch linkProceed;

    /**
     * Violação de integridade injetada na correlação ({@code null} =
     * normal). Chamada UMA vez por tentativa — é o contador de chamadas do
     * teste que prova o teto de 3 (RES-07), e a mensagem escolhida pelo
     * teste decide se é a corrida benigna (retry + silêncio) ou um defeito
     * (rethrow).
     */
    public static volatile Supplier<String> corruptCorrelation;

    /** Estado nominal entre testes — chamar em {@code @AfterEach}. */
    public static void reset() {
        correlationGate = null;
        correlatorExplodes = false;
        linkReached = null;
        linkProceed = null;
        corruptCorrelation = null;
    }

    @Bean
    @Primary
    AlertIncidentCorrelator flakyCorrelator(IncidentService delegate) {
        return new AlertIncidentCorrelator(delegate) {

            @Override
            public Optional<Incident> findOpen(UUID serviceId) {
                if (correlatorExplodes) {
                    throw new IllegalStateException("incident store down (test)");
                }
                Supplier<String> corrupt = corruptCorrelation;
                if (corrupt != null) {
                    // exatamente onde o banco acusaria a violação: dentro da
                    // transação da tentativa, antes de qualquer escrita
                    throw new DataIntegrityViolationException(corrupt.get());
                }
                // consulta ANTES do portão: cada lado enxerga "vazio" antes
                // de o outro criar — é isso que transforma a corrida em
                // determinística em vez de "depende de quem chega primeiro"
                Optional<Incident> open = super.findOpen(serviceId);
                CountDownLatch gate = correlationGate;
                if (gate != null) {
                    gate.countDown();
                    await(gate);
                }
                return open;
            }
        };
    }

    @Bean
    @Primary
    AlertIncidentLinker flakyLinker(AlertRepository alertRepository) {
        return new AlertIncidentLinker(alertRepository) {

            @Override
            public boolean link(UUID alertId, Incident incident) {
                CountDownLatch reached = linkReached;
                CountDownLatch proceed = linkProceed;
                if (reached != null && proceed != null) {
                    reached.countDown();
                    await(proceed);
                }
                return super.link(alertId, incident);
            }
        };
    }

    /**
     * Espera com teto — um portão que nunca abre é HANG do teste, não
     * flakiness: o timeout vira {@code IllegalStateException} e o caso falha
     * alto (a liberação fica no {@code finally} do teste).
     */
    private static void await(CountDownLatch gate) {
        try {
            if (!gate.await(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("test gate was never released");
            }
        }
        catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("test gate interrupted", ex);
        }
    }
}
