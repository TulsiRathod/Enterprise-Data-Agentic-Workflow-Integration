# Enterprise Data & Agentic Workflow Integration

An AI-agent-driven workflow that ingests unstructured customer documents from
AWS S3, parses them with an LLM into a strict schema, and writes the result
into a legacy CRM whose REST API is undocumented and rate-limited.

Designed to slot into the existing **Commotion / mvement** platform stack:
Spring Boot 3.5.6 on Java 21, Gradle multi-module monorepo, Netflix Conductor
(Orkes) for workflow orchestration, gRPC between services, MongoDB + Redis +
S3 for state, and the platform's existing `ai-proxy-utils`, `cache-utils`,
`blob-store-utils`, `sqs-utils`, and `integrations/` modules.

## Architecture

```
                   ┌──────────────────────────────────────────────────────────────┐
                   │       WORKFLOW ORCHESTRATOR (Netflix Conductor / Orkes)      │
                   │   tasks: ingest → parse → validate → upsert → reconcile      │
                   └──────────────────────────────────────────────────────────────┘
                                            │
   ┌────────────┐    S3:ObjectCreated     ┌─┴──────────────┐  Kafka  ┌─────────────────┐
   │  AWS S3    │ ──────────────────────▶ │  customer-     │ ──────▶ │  ai-worker      │
   │ (raw docs) │  EventBridge → SQS      │  onboarding    │   gRPC  │  via            │
   │ blob-store-│  (sqs-utils consumer)   │  service       │ ◀────── │  ai-proxy-utils │
   │ utils      │                         │  (Spring Boot) │         │  + Jinjava tmpl │
   └────────────┘                         └────────┬───────┘         └─────────────────┘
         ▲                                         │
         │ DLQ replay                              │ Bean Validation
         │                                         │ (Hibernate Validator)
   ┌─────┴──────┐                            ┌─────▼──────┐    Spring State Machine
   │ Kafka DLT  │◀── permanent failures ──┐  │ Validator  │    INGESTED → PARSED →
   │  + Slack   │                         │  │ + business │    VALIDATED → WRITTEN →
   │  alert     │                         │  │  rules     │    RECONCILED → DONE
   └────────────┘                         │  └─────┬──────┘
                                          │        │
                                          │  ┌─────▼─────────┐
                                          │  │ Idempotency   │
                                          │  │ (cache-utils  │
                                          │  │  Redisson)    │
                                          │  └─────┬─────────┘
                                          │        │
                              ┌───────────┴────────▼──────┐
                              │  crm-adapter (integrations)│◀──── ┌─────────────────────┐
                              │  ─ Resilience4j Retry      │      │  Legacy CRM REST    │
                              │  ─ CircuitBreaker          │ ───▶ │  (undocumented)     │
                              │  ─ RateLimiter             │      └─────────────────────┘
                              │  ─ schema cache (Redis)    │
                              └────────────────────────────┘
                                            │
                              ┌─────────────▼──────────────┐
                              │ OpenTelemetry + metric-    │
                              │ utils + SLF4J/Logback +    │
                              │ audit (infra/audit)        │
                              └────────────────────────────┘
```

## Module Layout (Triple-Module Pattern)

Following the platform's `{service}` / `{service}-schema` / `{service}-grpc-client`
convention used by every other service in `core-app/platform/`:

```
core-app/platform/
├── customer-onboarding/                  # Spring Boot impl
│   └── src/main/java/com/commotion/onboarding/
│       ├── OnboardingApplication.java
│       ├── config/                       # @Configuration beans (Resilience4j, Redisson)
│       ├── workflow/                     # Conductor task workers
│       ├── parse/                        # LlmParserService (uses ai-proxy-utils)
│       ├── state/                        # Spring State Machine config
│       ├── validate/                     # Bean Validation + business rules
│       └── persistence/                  # MongoDB repositories
├── customer-onboarding-schema/           # Plain JAR — DTOs / events
│   └── com/commotion/onboarding/schema/
│       ├── CustomerDto.java
│       ├── OnboardingEvent.java          # Kafka payload
│       └── OnboardingState.java          # enum for state machine
├── customer-onboarding-grpc-client/      # gRPC stub for other services
└── integrations/crm-legacy/              # adapter pattern, per existing integrations/
    └── com/commotion/integrations/crm/
        └── LegacyCrmAdapter.java         # Resilience4j-wrapped REST client
```

## Data Flow

| # | Stage | What happens | Platform component |
|---|-------|-------------|---------------------|
| 1 | **Trigger** | S3 `ObjectCreated` → EventBridge → SQS. | `sqs-utils` consumer |
| 2 | **Ingest** | Stream object, MIME-sniff, OCR if needed, publish `OnboardingEvent` to Kafka. | `blob-store-utils` (S3/Azure/GCP abstraction) |
| 3 | **Workflow** | Conductor workflow `customer_onboarding_v1` dispatches tasks. State tracked in Spring State Machine + Mongo. | Netflix Conductor / Orkes |
| 4 | **Parse** | gRPC call to `ai-worker` via `ai-proxy-utils`. Jinjava-templated prompt + tool-use schema = `CustomerDto`. Self-repair loop on validation failure. | `ai-worker`, `ai-proxy-utils`, Jinjava |
| 5 | **Validate** | Jakarta Bean Validation (`@Email`, `@NotBlank`) + business rules (country allowlist, etc.). Low confidence → human review queue (`chat`/`journey`). | Hibernate Validator + `error-translation-utils` |
| 6 | **API discovery** *(one-time)* | Discovery worker probes the CRM, infers JSON shape, caches in Redis via `cache-utils`. Refresh on 4xx drift. | `cache-utils` (Redisson) |
| 7 | **Idempotency** | `sha256(externalId|email)` → `SET NX EX 86400`. Skip duplicates within 24 h. | `cache-utils` (Redisson `RBucket`) |
| 8 | **CRM upsert** | `LegacyCrmAdapter` wraps every call with Resilience4j `RateLimiter` + `Retry` + `CircuitBreaker`. Honors `Retry-After`. | `integrations/crm-legacy` |
| 9 | **Reconcile** | Read-after-write GET + diff. Mismatch → re-queue once, then Kafka DLT. | Conductor task |
| 10 | **Audit + Observe** | Emit OTel spans, audit row to `infra/audit`, Slack alert on DLT. | OpenTelemetry, `metric-utils` |

## Resilience Patterns

- **Resilience4j stack** — `Retry ∘ CircuitBreaker ∘ RateLimiter`, configured via `application.yml`. Already idiomatic in Spring Boot 3.x.
- **Exponential backoff with full jitter** — `random(0, min(cap, base * 2^attempt))`.
- **Circuit breaker** — closed/open/half-open. When open, Conductor task fails fast and the workflow pauses retries rather than burning LLM tokens.
- **Honor `Retry-After`** when the legacy CRM bothers to send it.
- **`Idempotency-Key` header + Redisson `SET NX EX`** — first-writer-wins lock prevents retry-induced duplicates.
- **Kafka DLT + replay tool** — poison messages are never silently dropped; replay via Conductor admin UI.
- **Self-repair LLM loop** — Bean Validation errors are fed back to the model (max 2 retries) before paging a human.

## Sample Implementation

Java 21, Spring Boot 3.5.6, Lombok, Resilience4j, Jinjava, Conductor, Redisson.

### `CustomerDto.java` — in `customer-onboarding-schema`

```java
package com.commotion.onboarding.schema;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.Builder;

@Builder
public record CustomerDto(
        @NotBlank String externalId,
        @NotBlank String fullName,
        @Email @NotBlank String email,
        String company,
        @NotBlank String country,
        String notes
) {}
```

### `OnboardingState.java` — Spring State Machine

```java
package com.commotion.onboarding.schema;

public enum OnboardingState {
    INGESTED, PARSED, VALIDATED, WRITTEN, RECONCILED, DONE, FAILED
}

public enum OnboardingEvent {
    PARSE_OK, PARSE_FAIL, VALIDATE_OK, VALIDATE_FAIL,
    WRITE_OK, WRITE_FAIL, RECONCILE_OK, RECONCILE_FAIL
}
```

### `LlmParserService.java` — uses `ai-proxy-utils` + Jinjava

```java
package com.commotion.onboarding.parse;

import com.commotion.ai.proxy.AiProxyClient;            // ai-proxy-utils
import com.commotion.ai.proxy.dto.ToolUseRequest;
import com.commotion.ai.proxy.dto.ToolUseResponse;
import com.commotion.onboarding.schema.CustomerDto;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hubspot.jinjava.Jinjava;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class LlmParserService {

    private final AiProxyClient aiProxy;            // gRPC client to ai-worker
    private final Jinjava jinjava;
    private final Validator validator;
    private final ObjectMapper mapper;

    @Value("${onboarding.parse.max-repairs:2}")
    private int maxRepairs;

    @Value("${onboarding.parse.model:claude-opus-4-7}")
    private String model;

    private static final String PROMPT_TEMPLATE = """
        Extract the customer record from the document below and call
        `emit_customer` with the structured result. Omit any field whose
        value is not present in the document.

        {% if examples %}Examples:
        {% for ex in examples %}<doc>{{ ex.doc }}</doc>
        <out>{{ ex.out }}</out>
        {% endfor %}{% endif %}

        <doc>
        {{ document }}
        </doc>
        """;

    private static final Map<String, Object> CUSTOMER_SCHEMA = Map.of(
        "type", "object",
        "properties", Map.of(
            "externalId", Map.of("type", "string"),
            "fullName",   Map.of("type", "string"),
            "email",      Map.of("type", "string", "format", "email"),
            "company",    Map.of("type", "string"),
            "country",    Map.of("type", "string"),
            "notes",      Map.of("type", "string")),
        "required", List.of("externalId", "fullName", "email", "country")
    );

    public CustomerDto parse(String docText) {
        String prompt = jinjava.render(PROMPT_TEMPLATE,
                Map.of("document", docText, "examples", List.of()));

        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(Map.of("role", "user", "content", prompt));

        for (int attempt = 0; attempt <= maxRepairs; attempt++) {
            ToolUseResponse resp = aiProxy.toolUse(ToolUseRequest.builder()
                    .model(model)
                    .toolName("emit_customer")
                    .toolSchema(CUSTOMER_SCHEMA)
                    .messages(messages)
                    .maxTokens(2048)
                    .build());

            CustomerDto candidate = mapper.convertValue(resp.toolInput(), CustomerDto.class);
            Set<ConstraintViolation<CustomerDto>> violations = validator.validate(candidate);
            if (violations.isEmpty()) return candidate;

            if (attempt == maxRepairs) {
                log.warn("LLM parse failed after {} repairs: {}", maxRepairs, violations);
                throw new ParseException("validation failed: " + violations);
            }
            // feed violation back for self-repair
            messages.add(Map.of("role", "assistant", "content", resp.rawContent()));
            messages.add(Map.of("role", "user",
                    "content", "Validation failed: " + violations
                             + ". Fix the listed fields and re-emit via emit_customer."));
        }
        throw new IllegalStateException("unreachable");
    }

    public static class ParseException extends RuntimeException {
        public ParseException(String m) { super(m); }
    }
}
```

### `LegacyCrmAdapter.java` — in `integrations/crm-legacy`

```java
package com.commotion.integrations.crm;

import com.commotion.onboarding.schema.CustomerDto;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class LegacyCrmAdapter {

    private final RestClient restClient;       // pre-configured with base URL + auth

    public static class RateLimitedException extends RuntimeException {
        public final Duration retryAfter;
        public RateLimitedException(Duration d) { this.retryAfter = d; }
    }
    public static class TransientCrmException extends RuntimeException {
        public TransientCrmException(String m) { super(m); }
    }

    @Retry(name = "legacyCrm")
    @CircuitBreaker(name = "legacyCrm")
    @RateLimiter(name = "legacyCrm")
    public Map<String, Object> upsert(CustomerDto customer, String idempotencyKey) {
        try {
            ResponseEntity<Map> resp = restClient.post()
                    .uri("/customers")
                    .header("Idempotency-Key", idempotencyKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(customer)
                    .retrieve()
                    .toEntity(Map.class);
            return resp.getBody();
        } catch (HttpClientErrorException.TooManyRequests e) {
            long ra = e.getResponseHeaders().getFirst("Retry-After") != null
                    ? Long.parseLong(e.getResponseHeaders().getFirst("Retry-After"))
                    : 2L;
            throw new RateLimitedException(Duration.ofSeconds(ra));
        } catch (HttpServerErrorException e) {
            throw new TransientCrmException(e.getStatusCode() + ": " + e.getMessage());
        }
    }

    @Retry(name = "legacyCrm")
    @CircuitBreaker(name = "legacyCrm")
    public Map<String, Object> getById(String id) {
        return restClient.get().uri("/customers/{id}", id)
                .retrieve().body(Map.class);
    }
}
```

### `application.yml` — Resilience4j config

```yaml
resilience4j:
  retry:
    instances:
      legacyCrm:
        max-attempts: 6
        wait-duration: 1s
        enable-exponential-backoff: true
        exponential-backoff-multiplier: 2
        exponential-max-wait-duration: 30s
        randomized-wait-factor: 0.5      # full-jitter approximation
        retry-exceptions:
          - com.commotion.integrations.crm.LegacyCrmAdapter$RateLimitedException
          - com.commotion.integrations.crm.LegacyCrmAdapter$TransientCrmException
          - java.io.IOException
  circuitbreaker:
    instances:
      legacyCrm:
        failure-rate-threshold: 50
        sliding-window-size: 20
        wait-duration-in-open-state: 60s
        permitted-number-of-calls-in-half-open-state: 3
  ratelimiter:
    instances:
      legacyCrm:
        limit-for-period: 5              # 5 rps, tune from CRM metrics
        limit-refresh-period: 1s
        timeout-duration: 10s

onboarding:
  parse:
    max-repairs: 2
    model: claude-opus-4-7
  idempotency:
    ttl: 86400s
```

### `OnboardingWorkflowWorker.java` — Conductor task workers

```java
package com.commotion.onboarding.workflow;

import com.commotion.onboarding.parse.LlmParserService;
import com.commotion.onboarding.schema.*;
import com.commotion.integrations.crm.LegacyCrmAdapter;
import com.netflix.conductor.client.worker.Worker;
import com.netflix.conductor.common.metadata.tasks.Task;
import com.netflix.conductor.common.metadata.tasks.TaskResult;
import com.commotion.blobstore.BlobStoreClient;        // blob-store-utils
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class OnboardingWorkflowWorker {

    private final BlobStoreClient blobStore;
    private final LlmParserService parser;
    private final LegacyCrmAdapter crm;
    private final RedissonClient redisson;

    /** Conductor task: parse_document */
    public Worker parseTask() {
        return new Worker() {
            @Override public String getTaskDefName() { return "parse_document"; }
            @Override public TaskResult execute(Task task) {
                String bucket = (String) task.getInputData().get("bucket");
                String key    = (String) task.getInputData().get("key");
                try {
                    String text = new String(blobStore.fetch(bucket, key),
                                             StandardCharsets.UTF_8);
                    CustomerDto customer = parser.parse(text);
                    TaskResult r = new TaskResult(task);
                    r.getOutputData().put("customer", customer);
                    r.setStatus(TaskResult.Status.COMPLETED);
                    return r;
                } catch (LlmParserService.ParseException e) {
                    return failed(task, "parse_failed", e.getMessage());
                }
            }
        };
    }

    /** Conductor task: upsert_crm */
    public Worker upsertTask() {
        return new Worker() {
            @Override public String getTaskDefName() { return "upsert_crm"; }
            @Override public TaskResult execute(Task task) {
                CustomerDto customer = ((Map<String, Object>) task.getInputData().get("customer"))
                        instanceof Map<?, ?> m
                            ? new com.fasterxml.jackson.databind.ObjectMapper()
                                .convertValue(m, CustomerDto.class)
                            : null;

                String idem = sha256(customer.externalId() + "|" + customer.email());
                RBucket<String> lock = redisson.getBucket("idem:" + idem);
                if (!lock.trySet("1", Duration.ofDays(1))) {
                    log.info("skip duplicate {}", idem);
                    TaskResult r = new TaskResult(task);
                    r.setStatus(TaskResult.Status.COMPLETED);
                    r.getOutputData().put("skipped", true);
                    return r;
                }

                try {
                    Map<String, Object> written = crm.upsert(customer, idem);
                    TaskResult r = new TaskResult(task);
                    r.getOutputData().put("crmId", written.get("id"));
                    r.setStatus(TaskResult.Status.COMPLETED);
                    return r;
                } catch (io.github.resilience4j.circuitbreaker.CallNotPermittedException e) {
                    return failed(task, "circuit_open", e.getMessage());
                } catch (Exception e) {
                    return failed(task, "crm_error", e.getMessage());
                }
            }
        };
    }

    private TaskResult failed(Task task, String code, String msg) {
        TaskResult r = new TaskResult(task);
        r.setStatus(TaskResult.Status.FAILED);
        r.setReasonForIncompletion(code + ": " + msg);
        return r;
    }

    private static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                                 .digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) { throw new RuntimeException(e); }
    }
}
```

### `customer_onboarding_v1.json` — Conductor workflow definition

```json
{
  "name": "customer_onboarding_v1",
  "version": 1,
  "tasks": [
    { "name": "parse_document", "taskReferenceName": "parse",
      "inputParameters": { "bucket": "${workflow.input.bucket}",
                           "key":    "${workflow.input.key}" },
      "type": "SIMPLE" },
    { "name": "upsert_crm", "taskReferenceName": "upsert",
      "inputParameters": { "customer": "${parse.output.customer}" },
      "type": "SIMPLE" },
    { "name": "reconcile_crm", "taskReferenceName": "reconcile",
      "inputParameters": { "crmId": "${upsert.output.crmId}",
                           "expected": "${parse.output.customer}" },
      "type": "SIMPLE" }
  ],
  "failureWorkflow": "customer_onboarding_dlt_v1"
}
```

## Key Design Choices

- **Triple-module pattern** — `customer-onboarding` / `-schema` / `-grpc-client`. Other services (e.g., `journey`, `chat`) consume the gRPC client to enqueue onboarding without depending on the impl module.
- **Conductor workflow instead of bespoke orchestration** — the platform already runs Conductor/Orkes for `job`, `job-executor`, `job-scheduler`. Reuse it: visible UI, retries, DLT, replay, fan-out for free.
- **Spring State Machine 4.0.1** for per-record status (`INGESTED → … → DONE/FAILED`) persisted in MongoDB — matches the platform's existing state-machine usage and gives clean audit transitions.
- **`ai-proxy-utils` + `ai-worker` via gRPC** — don't call Anthropic directly from a feature service. Goes through the platform's central AI gateway for budget tracking, model routing, prompt-cache reuse, and safety filters.
- **Jinjava prompt templates** — same engine the platform already uses for message templating; keeps prompts versioned with the codebase and re-uses HubSpot-style filters.
- **Resilience4j via annotations + `application.yml`** — declarative, swap-tunable without redeploys (Spring Cloud Config refresh).
- **`Idempotency-Key` header + Redisson `RBucket.trySet`** — first-writer-wins lock against retry-induced duplicates, using the platform's existing `cache-utils` Redisson client.
- **`blob-store-utils`** for S3 access — provider-agnostic (S3/Azure/GCP), so the same code works if a customer ships data via Azure Blob.
- **Kafka DLT** instead of a separate DLQ store — fits the existing event-driven backbone and the `event-etl` pipeline can replay to retry once the CRM recovers.
- **Discovery agent runs once, cached** — schema cached via `cache-utils`. Refresh on 4xx drift. Avoids using an LLM on every call to "figure out the API."
- **Audit + OpenTelemetry from day one** — emit `infra/audit` rows on every state transition and OTel spans on every CRM call, so the legacy API's behavior is observable even without docs.

The main trade-off: if the CRM's undocumented API changes shape silently, the cached schema goes stale. Mitigation is the reconciliation step (read-after-write diff), which catches most field-rename surprises within one record.
