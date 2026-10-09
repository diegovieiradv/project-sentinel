# Segurança Operacional — Etapa 02.1

## 1. Headers de Segurança

Implementados no `SecurityConfig.java` (frente A):

- `Content-Security-Policy`: `default-src 'self'; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; font-src 'self'; connect-src 'self'; frame-ancestors 'none';`
- `Strict-Transport-Security`: `max-age=31536000; includeSubDomains; preload`
- `Referrer-Policy`: `strict-origin-when-cross-origin`
- `Permissions-Policy`: `camera=(), microphone=(), geolocation=(), accelerometer=(), gyroscope=(), magnetometer=(), usb=(), payment=()`
- `X-Content-Type-Options`: `nosniff`
- `X-Frame-Options`: `DENY`

No frontend (`next.config.ts`): mesmos headers aplicados a `/(.*)` via `headers()`.

**Observação**: HSTS (`Strict-Transport-Security`) só tem efeito prático em conexões HTTPS. Em ambiente local (HTTP), não prejudica a operação, mas a proteção real depende do reverse proxy/proxy que serve HTTPS.

**Observação CSP**: `unsafe-inline` para `script-src` e `style-src` é necessário para o Tailwind 4 (que usa estilos inline) e scripts internos do Next.js. Em produção com HTTPS + HSTS, o CSP ainda protege contra injeção de conteúdo externo (`default-src 'self'`), mas permite execução de código inline. Se o nível de segurança exigir CSP estrita, a aplicação precisará de uma estratégia de nonce (não implementada nesta etapa).

## 2. Rate Limiting

Implementado no `RateLimitFilter.java` (frente B):

- **Login (`POST /api/v1/auth/login`)**: 5/min por IP
- **Register (`POST /api/v1/auth/register`)**: 3/min por IP
- **Refresh (`POST /api/v1/auth/refresh`)**: 10/min por IP
- **Organizations (`GET /api/v1/organizations`)**: 60/min por IP

**Não limitado**: `GET /api/v1/health` (monitoramento), `GET /actuator/health`, `OPTIONS /**`.

**Resposta 429**: `{"code":"RATE_LIMITED","message":"Too many ... attempts. Retry after 60 seconds."}` com header `Retry-After: 60`.

**Identificação do cliente**: `request.getRemoteAddr()`; se houver header `X-Forwarded-For`, usa o primeiro IP (não confia cegamente — documentado como limitação: em ambiente distribuído com proxy confiável, a identificação deve ser refinada).

**Limite por instância**: solução baseada em `ConcurrentHashMap` em memória (`ConcurrentHashMap<String, AtomicCounter>`) com limpeza periódica (`ScheduledExecutorService` a cada 60s). Não usa Redis ou outra infraestrutura externa. Em ambiente distribuído, o limite é por instância (não global).

**Segurança**: nenhuma credencial ou token registrado; o filtro não expõe internals da aplicação; não introduz bypass de auth.

## 3. Fail-Fast de Produção

Implementado no `application-prod.yml` (frente C):

Variáveis obrigatórias no perfil `prod` (com `:?`):

- `DATABASE_PASSWORD`
- `JWT_SECRET`
- `DATABASE_URL` (adicionado nesta etapa — antes sem `:?`)
- `DATABASE_USERNAME` (adicionado nesta etapa — antes sem `:?`)

Mensagens claras para cada falha; `JWT_SECRET` validado (`JwtConfiguration.java`) para ter ≥32 caracteres.

## 4. Testes

- `RateLimitTest.java`: teste básico do `AtomicCounter` (expiração após janela) + placeholder para verificação de filtro desabilitado (`enabled: false` no perfil de teste).
- `SecurityConfig.java`: `.headers()` adicionado; `mvn clean test` passa (865 testes).
- `application-test.yml`: `app.security.rate-limit.enabled: false` para não interferir nos testes existentes.

## 5. Riscos e Limitações

1. **CSP `unsafe-inline`**: necessário para Tailwind + Next.js; não é a CSP mais restritiva possível.
2. **HSTS**: só efetivo via HTTPS; em ambiente local (HTTP) a proteção depende do proxy/reverse proxy que serve HTTPS.
3. **Rate limit por instância**: não global; múltiplas instâncias do backend não compartilham limite.
4. **Rate limit via `X-Forwarded-For`**: usa o primeiro IP do header, mas não valida a origem do proxy; em produção com proxy de confiança, deve ser refinado.
5. **Rate limit em testes**: desabilitado (`enabled: false`) para não quebrar testes; testes específicos de rate limit devem ser executados com `enabled: true` (ex. via profile específico ou mock).
6. **Frontend `next.config.ts`**: headers adicionados; se o frontend for servido por um proxy/reverse proxy, o proxy deve preservar ou reforçar esses headers (especialmente CSP e HSTS).

## 6. Estado Final
- `SecurityConfig.java`: `.headers()` com CSP + HSTS + Referrer + Permissions + nosniff + DENY.
- `RateLimitFilter.java`: filtro de rate limit com `ConcurrentHashMap` + `ScheduledExecutorService`.
- `application-prod.yml`: `DATABASE_URL`, `DATABASE_USERNAME`, `DATABASE_PASSWORD`, `JWT_SECRET` com `:?`.
- `application-test.yml`: `app.security.rate-limit.enabled: false`.
- `next.config.ts`: headers de segurança adicionados.
- Documentação: `docs/operational-security.md` (este arquivo).
