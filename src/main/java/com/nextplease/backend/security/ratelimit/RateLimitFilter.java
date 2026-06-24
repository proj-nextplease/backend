package com.nextplease.backend.security.ratelimit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nextplease.backend.dto.response.ApiResponse;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Application-layer (L7) rate limiting. Three policies, checked in order:
 *
 * <ol>
 *   <li><b>Auth</b> — login / OTP / registration endpoints, keyed by client IP. Tight,
 *       to blunt brute-force and OTP/email bombing.</li>
 *   <li><b>Write</b> — any state-changing request from an authenticated user, keyed by
 *       user id. Stops a logged-in account from spamming actions (apply, rate, wallet…).</li>
 *   <li><b>Global</b> — a per-IP backstop on every request, to dampen blind flooding.</li>
 * </ol>
 *
 * On breach it short-circuits with HTTP 429 and the standard {@link ApiResponse} envelope.
 *
 * <p>Registered after Spring Security's authorization filter so the authenticated
 * principal (if any) is already on the {@link SecurityContextHolder}.
 */
@Component
@Order
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

    private final RateLimiterService rateLimiter;
    private final RateLimitProperties properties;
    private final ObjectMapper objectMapper;

    public RateLimitFilter(
            RateLimiterService rateLimiter,
            RateLimitProperties properties,
            ObjectMapper objectMapper
    ) {
        this.rateLimiter = rateLimiter;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        if (!properties.isEnabled()) {
            filterChain.doFilter(request, response);
            return;
        }

        String path = request.getRequestURI();
        String method = request.getMethod();

        // Never throttle CORS preflight or health checks.
        if ("OPTIONS".equalsIgnoreCase(method) || isExempt(path)) {
            filterChain.doFilter(request, response);
            return;
        }

        String ip = ClientIpResolver.resolve(request);

        // 1. Global per-IP backstop.
        if (!rateLimiter.tryConsume("global:ip:" + ip, properties.getGlobalPerMinute())) {
            reject(response, "Bạn đang gửi quá nhiều yêu cầu. Vui lòng thử lại sau ít phút.", ip, path);
            return;
        }

        // 2. Auth-sensitive endpoints — strict per-IP.
        if (isAuthSensitive(path)) {
            if (!rateLimiter.tryConsume("auth:ip:" + ip, properties.getAuthPerMinute())) {
                reject(response, "Quá nhiều lần thử. Vui lòng đợi một phút rồi thử lại.", ip, path);
                return;
            }
        }

        // 3. Write actions from an authenticated user — per user id.
        if (isWriteMethod(method)) {
            String userKey = currentUserId();
            if (userKey != null
                    && !rateLimiter.tryConsume("write:user:" + userKey, properties.getWritePerMinute())) {
                reject(response, "Bạn thao tác quá nhanh. Vui lòng chậm lại một chút.", userKey, path);
                return;
            }
        }

        filterChain.doFilter(request, response);
    }

    private boolean isExempt(String path) {
        return path.startsWith("/actuator")
                || path.equals("/api/v1/health")
                || path.startsWith("/swagger-ui")
                || path.startsWith("/v3/api-docs");
    }

    private boolean isAuthSensitive(String path) {
        return path.startsWith("/api/v1/auth/")
                || path.startsWith("/api/v1/company/invitations/register");
    }

    private boolean isWriteMethod(String method) {
        return "POST".equalsIgnoreCase(method)
                || "PUT".equalsIgnoreCase(method)
                || "PATCH".equalsIgnoreCase(method)
                || "DELETE".equalsIgnoreCase(method);
    }

    /** The authenticated user identity (JWT subject), or null if the request is anonymous. */
    private String currentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return null;
        }
        String name = auth.getName();
        if (name == null || "anonymousUser".equals(name)) {
            return null;
        }
        return name;
    }

    private void reject(HttpServletResponse response, String message, String key, String path)
            throws IOException {
        log.warn("Rate limit exceeded [{}] for key={} path={}", HttpStatus.TOO_MANY_REQUESTS.value(), key, path);
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.setHeader(HttpHeaders.RETRY_AFTER, "60");
        ApiResponse<Void> body = ApiResponse.errorWithCode(message, "RATE_LIMITED");
        response.getWriter().write(objectMapper.writeValueAsString(body));
    }
}
