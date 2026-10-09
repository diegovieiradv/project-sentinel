package com.sentinel.security;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.core.Ordered;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RateLimitFilter implements Filter {

    @Value("${app.security.rate-limit.enabled:true}")
    private boolean rateLimitEnabled = true;

    private static final Map<String, AtomicCounter> loginLimits = new ConcurrentHashMap<>();
    private static final Map<String, AtomicCounter> registerLimits = new ConcurrentHashMap<>();
    private static final Map<String, AtomicCounter> refreshLimits = new ConcurrentHashMap<>();
    private static final Map<String, AtomicCounter> orgsLimits = new ConcurrentHashMap<>();

    private static final ScheduledExecutorService cleanup = Executors.newSingleThreadScheduledExecutor();

    static {
        cleanup.scheduleAtFixedRate(
            () -> {
                Instant now = Instant.now();
                loginLimits.entrySet().removeIf(e -> e.getValue().expired(now));
                registerLimits.entrySet().removeIf(e -> e.getValue().expired(now));
                refreshLimits.entrySet().removeIf(e -> e.getValue().expired(now));
                orgsLimits.entrySet().removeIf(e -> e.getValue().expired(now));
            }, 60, 60, TimeUnit.SECONDS);
    }

    private static final int LOGIN_LIMIT = 5;
    private static final int REGISTER_LIMIT = 3;
    private static final int REFRESH_LIMIT = 10;
    private static final int ORGS_LIMIT = 60;
    private static final int WINDOW_SECONDS = 60;

    @Override
    public void doFilter(jakarta.servlet.ServletRequest request,
                         jakarta.servlet.ServletResponse response,
                         FilterChain chain) throws java.io.IOException, ServletException {
        if (!rateLimitEnabled) {
            chain.doFilter(request, response);
            return;
        }
        HttpServletRequest httpReq = (HttpServletRequest) request;
        HttpServletResponse httpResp = (HttpServletResponse) response;
        String path = httpReq.getRequestURI();
        String ip = getClientIp(httpReq);
        String key = ip + ":" + path;

        if (path.equals("/api/v1/auth/login") && "POST".equals(httpReq.getMethod())) {
            if (!allow(ip, loginLimits, LOGIN_LIMIT)) {
                httpResp.setStatus(429);
                httpResp.setHeader("Retry-After", String.valueOf(WINDOW_SECONDS));
                httpResp.setContentType("application/json");
                httpResp.getWriter().write("{\"code\":\"RATE_LIMITED\",\"message\":\"Too many login attempts. Retry after " + WINDOW_SECONDS + " seconds.\"}");
                return;
            }
        } else if (path.equals("/api/v1/auth/register") && "POST".equals(httpReq.getMethod())) {
            if (!allow(ip, registerLimits, REGISTER_LIMIT)) {
                httpResp.setStatus(429);
                httpResp.setHeader("Retry-After", String.valueOf(WINDOW_SECONDS));
                httpResp.setContentType("application/json");
                httpResp.getWriter().write("{\"code\":\"RATE_LIMITED\",\"message\":\"Too many registration attempts. Retry after " + WINDOW_SECONDS + " seconds.\"}");
                return;
            }
        } else if (path.equals("/api/v1/auth/refresh") && "POST".equals(httpReq.getMethod())) {
            if (!allow(ip, refreshLimits, REFRESH_LIMIT)) {
                httpResp.setStatus(429);
                httpResp.setHeader("Retry-After", String.valueOf(WINDOW_SECONDS));
                httpResp.setContentType("application/json");
                httpResp.getWriter().write("{\"code\":\"RATE_LIMITED\",\"message\":\"Too many refresh attempts. Retry after " + WINDOW_SECONDS + " seconds.\"}");
                return;
            }
        } else if (path.equals("/api/v1/organizations") && "GET".equals(httpReq.getMethod())) {
            if (!allow(ip, orgsLimits, ORGS_LIMIT)) {
                httpResp.setStatus(429);
                httpResp.setHeader("Retry-After", String.valueOf(WINDOW_SECONDS));
                httpResp.setContentType("application/json");
                httpResp.getWriter().write("{\"code\":\"RATE_LIMITED\",\"message\":\"Rate limit exceeded for this endpoint. Retry after " + WINDOW_SECONDS + " seconds.\"}");
                return;
            }
        }
        chain.doFilter(request, response);
    }

    private boolean allow(String ip, Map<String, AtomicCounter> map, int limit) {
        AtomicCounter counter = map.get(ip);
        Instant now = Instant.now();
        if (counter == null || counter.expired(now)) {
            counter = new AtomicCounter(now);
            map.put(ip, counter);
            counter.increment();
            return true;
        }
        if (counter.getCount() >= limit) {
            return false;
        }
        counter.increment();
        return true;
    }

    private String getClientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            String first = forwarded.split(",")[0].trim();
            return first;
        }
        return request.getRemoteAddr();
    }

    static class AtomicCounter {
        private int count;
        private final Instant windowStart;
        AtomicCounter(Instant start) {
            this.windowStart = start;
            this.count = 0;
        }
        void increment() {
            count++;
        }
        int getCount() {
            return count;
        }
        boolean expired(Instant now) {
            return now.getEpochSecond() - windowStart.getEpochSecond() >= WINDOW_SECONDS;
        }
    }

    @Override
    public void init(FilterConfig filterConfig) {}
    @Override
    public void destroy() {}
}
