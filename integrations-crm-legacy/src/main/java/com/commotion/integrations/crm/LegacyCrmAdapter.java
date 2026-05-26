package com.commotion.integrations.crm;

import com.commotion.onboarding.schema.CustomerDto;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class LegacyCrmAdapter {

    private final RestClient legacyCrmRestClient;

    public static class RateLimitedException extends RuntimeException {
        private final Duration retryAfter;
        public RateLimitedException(Duration d) { this.retryAfter = d; }
        public Duration retryAfter() { return retryAfter; }
    }

    public static class TransientCrmException extends RuntimeException {
        public TransientCrmException(String m) { super(m); }
    }

    @Retry(name = "legacyCrm")
    @CircuitBreaker(name = "legacyCrm")
    @RateLimiter(name = "legacyCrm")
    @SuppressWarnings("unchecked")
    public Map<String, Object> upsert(CustomerDto customer, String idempotencyKey) {
        try {
            ResponseEntity<Map> resp = legacyCrmRestClient.post()
                    .uri("/customers")
                    .header("Idempotency-Key", idempotencyKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(customer)
                    .retrieve()
                    .toEntity(Map.class);
            return resp.getBody();
        } catch (HttpClientErrorException.TooManyRequests e) {
            long ra = parseRetryAfter(e.getResponseHeaders(), 2L);
            log.warn("CRM 429, retry-after={}s", ra);
            throw new RateLimitedException(Duration.ofSeconds(ra));
        } catch (HttpServerErrorException e) {
            throw new TransientCrmException(e.getStatusCode() + ": " + e.getMessage());
        }
    }

    @Retry(name = "legacyCrm")
    @CircuitBreaker(name = "legacyCrm")
    @SuppressWarnings("unchecked")
    public Map<String, Object> getById(String id) {
        return legacyCrmRestClient.get()
                .uri("/customers/{id}", id)
                .retrieve()
                .body(Map.class);
    }

    private static long parseRetryAfter(HttpHeaders headers, long fallback) {
        if (headers == null) return fallback;
        String ra = headers.getFirst("Retry-After");
        if (ra == null) return fallback;
        try { return Long.parseLong(ra); }
        catch (NumberFormatException ignored) { return fallback; }
    }
}
