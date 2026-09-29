package com.sentinel.monitoring;

import com.sentinel.project.security.SsrfGuard;

import org.springframework.stereotype.Component;

/**
 * Implementação de produção do {@link MonitorTargetValidator} — delega ao
 * {@code SsrfGuard} do Prompt 08.
 *
 * <p><b>Sem duplicação e sem bypass:</b> esta classe não reimplementa
 * nenhuma regra de range/privado/localhost; ela apenas adapta o guard à
 * fronteira do executor. Não existe propriedade de configuração que troque
 * este bean por um permissivo — em produção o guard real é o único caminho.
 * Os testes que precisam de um validador poroso injetam outro bean
 * <em>no contexto de teste</em>, o que é injeção de dependência, não
 * configuração de runtime.</p>
 */
@Component
public class SsrfGuardMonitorTargetValidator implements MonitorTargetValidator {

    private final SsrfGuard ssrfGuard;

    public SsrfGuardMonitorTargetValidator(SsrfGuard ssrfGuard) {
        this.ssrfGuard = ssrfGuard;
    }

    @Override
    public void validate(String url) {
        // Mesma exceção/contrato do cadastro: ApiException 400
        // MONITOR_TARGET_NOT_ALLOWED (mensagem estável, sem IP/range).
        ssrfGuard.validate(url);
    }
}
