package com.orderflow.config;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;

/**
 * Redis-backed sliding window rate limiter.
 *
 * Strategy: fixed window per user (or IP for unauthenticated requests)
 * using Redis INCR + EXPIRE for atomic counter with TTL.
 *
 * Why Redis instead of in-memory?
 * In-memory rate limiters (like Bucket4j) don't work across multiple
 * app instances. Redis provides a shared counter that survives restarts
 * and scales horizontally. This is the approach used by production APIs
 * (GitHub, Stripe, etc.).
 *
 * Rate limit headers (standard draft RFC 7231 extensions):
 *   X-RateLimit-Limit:     max requests per window
 *   X-RateLimit-Remaining: requests left in current window
 *   X-RateLimit-Reset:     seconds until window resets
 *
 * Excluded paths: /api/auth/**, /actuator/** (health checks shouldn't
 * be rate-limited)
 */
@Component
public class RateLimitingFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitingFilter.class);
    private static final String RATE_LIMIT_PREFIX = "rate_limit:";

    private final StringRedisTemplate redisTemplate;
    private final int maxRequests;
    private final int windowSeconds;
    private final Counter rateLimitedCounter;

    public RateLimitingFilter(StringRedisTemplate redisTemplate,
                              @Value("${orderflow.rate-limit.max-requests:100}") int maxRequests,
                              @Value("${orderflow.rate-limit.window-seconds:60}") int windowSeconds,
                              MeterRegistry meterRegistry) {
        this.redisTemplate = redisTemplate;
        this.maxRequests = maxRequests;
        this.windowSeconds = windowSeconds;
        this.rateLimitedCounter = Counter.builder("http.rate_limited.total")
                .description("Number of requests rejected by rate limiter")
                .register(meterRegistry);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path.startsWith("/api/auth/") || path.startsWith("/actuator/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {

        String clientKey = resolveClientKey(request);
        String redisKey = RATE_LIMIT_PREFIX + clientKey;

        try {
            Long currentCount = redisTemplate.opsForValue().increment(redisKey);
            if (currentCount == null) {
                // Redis unavailable — fail open (allow the request)
                filterChain.doFilter(request, response);
                return;
            }

            if (currentCount == 1L) {
                // First request in window — set expiry
                redisTemplate.expire(redisKey, Duration.ofSeconds(windowSeconds));
            }

            long remaining = Math.max(0, maxRequests - currentCount);
            Long ttl = redisTemplate.getExpire(redisKey);

            // Set rate limit headers
            response.setHeader("X-RateLimit-Limit", String.valueOf(maxRequests));
            response.setHeader("X-RateLimit-Remaining", String.valueOf(remaining));
            response.setHeader("X-RateLimit-Reset", String.valueOf(ttl != null ? ttl : windowSeconds));

            if (currentCount > maxRequests) {
                rateLimitedCounter.increment();
                log.warn("Rate limit exceeded for client: {}", clientKey);

                response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
                response.setContentType("application/json");
                response.getWriter().write("""
                        {"success":false,"error":"Rate limit exceeded. Try again in %d seconds."}
                        """.formatted(ttl != null ? ttl : windowSeconds));
                return;
            }

        } catch (Exception e) {
            // Redis connection failure — fail open
            log.warn("Rate limiter Redis error, failing open: {}", e.getMessage());
        }

        filterChain.doFilter(request, response);
    }

    /**
     * Resolve rate limit key: authenticated user ID, or client IP for
     * unauthenticated requests.
     */
    private String resolveClientKey(HttpServletRequest request) {
        // Check for authenticated user (JWT sets a principal)
        if (request.getUserPrincipal() != null) {
            return "user:" + request.getUserPrincipal().getName();
        }

        // Fall back to IP address
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return "ip:" + forwarded.split(",")[0].trim();
        }
        return "ip:" + request.getRemoteAddr();
    }
}
