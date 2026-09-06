package com.orderflow.config;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Duration;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RateLimitingFilterTest {

    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ValueOperations<String, String> valueOps;

    private RateLimitingFilter filter;

    @BeforeEach
    void setUp() {
        filter = new RateLimitingFilter(redisTemplate, 10, 60, new SimpleMeterRegistry());
    }

    @Test
    @DisplayName("should allow requests under the limit")
    void allowUnderLimit() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/orders");
        request.setRemoteAddr("192.168.1.1");
        MockHttpServletResponse response = new MockHttpServletResponse();

        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.increment("rate_limit:ip:192.168.1.1")).thenReturn(5L);
        when(redisTemplate.getExpire("rate_limit:ip:192.168.1.1")).thenReturn(45L);

        filter.doFilterInternal(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getHeader("X-RateLimit-Limit")).isEqualTo("10");
        assertThat(response.getHeader("X-RateLimit-Remaining")).isEqualTo("5");
        assertThat(response.getHeader("X-RateLimit-Reset")).isEqualTo("45");
    }

    @Test
    @DisplayName("should reject requests over the limit with 429")
    void rejectOverLimit() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/orders");
        request.setRemoteAddr("192.168.1.1");
        MockHttpServletResponse response = new MockHttpServletResponse();

        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.increment("rate_limit:ip:192.168.1.1")).thenReturn(11L);
        when(redisTemplate.getExpire("rate_limit:ip:192.168.1.1")).thenReturn(30L);

        filter.doFilterInternal(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getContentAsString()).contains("Rate limit exceeded");
    }

    @Test
    @DisplayName("should set expiry on first request in window")
    void setExpiryOnFirstRequest() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/products");
        request.setRemoteAddr("10.0.0.1");
        MockHttpServletResponse response = new MockHttpServletResponse();

        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.increment("rate_limit:ip:10.0.0.1")).thenReturn(1L);
        when(redisTemplate.getExpire("rate_limit:ip:10.0.0.1")).thenReturn(60L);

        filter.doFilterInternal(request, response, new MockFilterChain());

        verify(redisTemplate).expire("rate_limit:ip:10.0.0.1", Duration.ofSeconds(60));
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("should skip auth endpoints")
    void skipAuthEndpoints() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/auth/login");

        assertThat(filter.shouldNotFilter(request)).isTrue();
    }

    @Test
    @DisplayName("should skip actuator endpoints")
    void skipActuatorEndpoints() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/actuator/health");

        assertThat(filter.shouldNotFilter(request)).isTrue();
    }

    @Test
    @DisplayName("should fail open when Redis is unavailable")
    void failOpenOnRedisError() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/orders");
        request.setRemoteAddr("192.168.1.1");
        MockHttpServletResponse response = new MockHttpServletResponse();

        when(redisTemplate.opsForValue()).thenThrow(new RuntimeException("Redis connection refused"));

        filter.doFilterInternal(request, response, new MockFilterChain());

        // Should pass through (fail open)
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("should use X-Forwarded-For header when present")
    void useForwardedForHeader() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/orders");
        request.setRemoteAddr("127.0.0.1");
        request.addHeader("X-Forwarded-For", "203.0.113.50, 70.41.3.18");
        MockHttpServletResponse response = new MockHttpServletResponse();

        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.increment("rate_limit:ip:203.0.113.50")).thenReturn(1L);
        when(redisTemplate.getExpire(anyString())).thenReturn(60L);

        filter.doFilterInternal(request, response, new MockFilterChain());

        verify(valueOps).increment("rate_limit:ip:203.0.113.50");
    }
}
