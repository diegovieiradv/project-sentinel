package com.sentinel.monitoring;

import java.util.List;

import com.sentinel.project.monitor.model.Monitor;
import com.sentinel.project.monitor.repository.MonitorRepository;

import org.springframework.stereotype.Service;

/**
 * Seleção dos monitores que podem executar neste tick (Prompt 09).
 *
 * <p><b>Filtro único e autoritativo:</b> a query
 * {@code MonitorRepository.findAllEligibleForExecution()} faz o JOIN
 * Monitor → Service → Project → Organization e aplica, no banco:</p>
 * <ul>
 *   <li>{@code m.active} (monitor no catálogo), {@code s.active} (serviço)
 *       e {@code p.active} (projeto) — a organização não tem coluna
 *       {@code active}: o ownership continua garantido pelo próprio
 *       relacionamento;</li>
 *   <li>{@code type = HTTP} (invariante do cadastro);</li>
 *   <li>faixas coerentes: {@code intervalSeconds > 0},
 *       {@code timeoutMillis > 0} e {@code timeoutMillis < intervalSeconds *
 *       1000} (uma checagem nunca pode durar mais que o próprio
 *       intervalo).</li>
 * </ul>
 *
 * <p>Filtrar no banco (e não em memória) significa que monitores
 * desativados nem sequer entram no tick: o {@link DueTracker} usa o mesmo
 * conjunto para descartar estado obsoleto, e um monitor reativado volta a
 * ser executado imediatamente.</p>
 *
 * <p>Esta classe é um ponto único de consulta — nos testes de unidade do
 * {@link MonitoringScheduler} ela é substituída por uma subclasse que devolve
 * um conjunto fixo, mantendo o scheduler testável sem banco nem rede.</p>
 */
@Service
public class MonitorEligibilityService {

    private final MonitorRepository monitorRepository;

    public MonitorEligibilityService(MonitorRepository monitorRepository) {
        this.monitorRepository = monitorRepository;
    }

    /** @return monitores executáveis agora (ativos, HTTP e com faixas coerentes) */
    public List<Monitor> findEligibleMonitors() {
        return monitorRepository.findAllEligibleForExecution();
    }
}
