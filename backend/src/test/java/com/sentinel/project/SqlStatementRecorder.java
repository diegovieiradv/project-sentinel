package com.sentinel.project;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.hibernate.resource.jdbc.spi.StatementInspector;

/**
 * Gravador de SQL do Hibernate para teste (critério #73) — registra cada
 * statement emitido pela {@code SessionFactory} para que o teste observe o
 * que a resolução em massa <b>realmente</b> mandou para o banco: leitura com
 * {@code LIMIT}, ordem explícita e quantas leituras o lote fez.
 *
 * <p><b>Instanciação:</b> o Hibernate cria a instância sozinho a partir do
 * nome da classe ({@code hibernate.session_factory.statement_inspector}),
 * por isso a classe é {@code public} com construtor padrão público — o
 * registro vive em campo {@code static} e é lido pelo teste.</p>
 *
 * <p><b>Escopo:</b> só a classe de teste que declara a propriedade
 * {@code @TestPropertySource} tem o inspector ligado — nenhuma outra classe
 * grava nada. As execuções são sequenciais (sem paralelismo no Surefire), então
 * a lista não mistura janelas de testes diferentes.</p>
 */
public final class SqlStatementRecorder implements StatementInspector {

    private static final List<String> STATEMENTS = Collections.synchronizedList(new ArrayList<>());

    @Override
    public String inspect(String sql) {
        STATEMENTS.add(sql);
        return sql;
    }

    /** Zera a gravação — chamado logo ANTES da operação medida. */
    public static void clear() {
        STATEMENTS.clear();
    }

    /** Cópia imutável do que foi gravado até aqui. */
    public static List<String> recorded() {
        synchronized (STATEMENTS) {
            return List.copyOf(STATEMENTS);
        }
    }
}
