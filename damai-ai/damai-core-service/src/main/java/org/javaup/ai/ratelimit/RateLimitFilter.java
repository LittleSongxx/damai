package org.javaup.ai.ratelimit;

import com.alibaba.fastjson.JSON;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

@Component
@Order(-100)
@RequiredArgsConstructor
public class RateLimitFilter implements Filter {

    private final RateLimiter rateLimiter;

    private static final Map<String, String> PATH_ENDPOINT_MAP = Map.of(
            "/assistant/runs", "assistant.runs.create",
            "/assistant/admin/", "admin"
    );

    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain) throws IOException, ServletException {
        if (!(req instanceof HttpServletRequest request) || !(res instanceof HttpServletResponse response)) {
            chain.doFilter(req, res);
            return;
        }
        String uri = request.getRequestURI();
        String method = request.getMethod();

        String endpoint = resolveEndpoint(uri, method);
        if (endpoint != null) {
            if (!rateLimiter.tryAcquire(endpoint, request)) {
                response.setStatus(429);
                response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                response.setCharacterEncoding(StandardCharsets.UTF_8.name());
                response.getWriter().write(JSON.toJSONString(RateLimiter.rateLimitedResponse()));
                return;
            }
        }
        chain.doFilter(req, res);
    }

    private String resolveEndpoint(String uri, String method) {
        if ("POST".equalsIgnoreCase(method) && (uri.startsWith("/assistant/runs") && !uri.contains("/events"))) {
            return "assistant.runs.create";
        }
        if ("GET".equalsIgnoreCase(method) && uri.contains("/runs/") && uri.contains("/events")) {
            return "assistant.runs.stream";
        }
        if (uri.startsWith("/simple/chat") || uri.startsWith("/simple/chat/mcp")) {
            return "simple.chat";
        }
        if (uri.startsWith("/assistant/admin/")) {
            return "admin";
        }
        for (var entry : PATH_ENDPOINT_MAP.entrySet()) {
            if (uri.startsWith(entry.getKey())) {
                return entry.getValue();
            }
        }
        return null;
    }
}
