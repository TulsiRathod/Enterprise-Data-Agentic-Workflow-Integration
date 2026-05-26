package com.commotion.onboarding.workflow;

import com.commotion.integrations.crm.LegacyCrmAdapter;
import com.commotion.onboarding.parse.LlmParserService;
import com.commotion.onboarding.schema.CustomerDto;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;

/**
 * Orchestrates the end-to-end onboarding pipeline. In production each step
 * would be a separate Conductor task worker so the workflow engine handles
 * retries, fan-out, and DLT. Here it's wired as one service call for clarity.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OnboardingService {

    private final BlobStoreClient blobStore;
    private final LlmParserService parser;
    private final LegacyCrmAdapter crm;
    private final RedissonClient redisson;

    public Result handle(String bucket, String key) {
        String text = new String(blobStore.fetch(bucket, key), StandardCharsets.UTF_8);

        CustomerDto customer;
        try {
            customer = parser.parse(text);
        } catch (LlmParserService.ParseException e) {
            log.error("Parse failed for {}/{}: {}", bucket, key, e.getMessage());
            return Result.failed("parse_failed", e.getMessage());
        }

        String idem = sha256(customer.externalId() + "|" + customer.email());
        RBucket<String> lock = redisson.getBucket("idem:" + idem);
        if (!lock.setIfAbsent("1", Duration.ofDays(1))) {
            log.info("Skipping duplicate idempotency key {}", idem);
            return Result.skipped(idem);
        }

        Map<String, Object> written;
        try {
            written = crm.upsert(customer, idem);
        } catch (CallNotPermittedException e) {
            log.warn("CRM circuit breaker open for {}/{}", bucket, key);
            return Result.failed("circuit_open", e.getMessage());
        } catch (Exception e) {
            log.error("CRM upsert failed for {}/{}", bucket, key, e);
            return Result.failed("crm_error", e.getMessage());
        }

        String crmId = String.valueOf(written.get("id"));
        if (!reconcile(crmId, customer)) {
            return Result.failed("reconcile_mismatch", crmId);
        }

        return Result.success(crmId);
    }

    private boolean reconcile(String crmId, CustomerDto expected) {
        Map<String, Object> got = crm.getById(crmId);
        return Objects.equals(got.get("email"), expected.email());
    }

    private static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                                 .digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public record Result(Status status, String crmId, String errorCode, String message) {
        public enum Status { SUCCESS, SKIPPED, FAILED }

        public static Result success(String crmId)               { return new Result(Status.SUCCESS, crmId, null, null); }
        public static Result skipped(String key)                 { return new Result(Status.SKIPPED, null, null, key); }
        public static Result failed(String code, String message) { return new Result(Status.FAILED,  null, code, message); }
    }
}
