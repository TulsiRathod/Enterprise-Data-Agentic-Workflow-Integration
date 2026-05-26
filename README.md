# Enterprise Data & Agentic Workflow Integration

AI-agent-driven workflow that ingests unstructured customer documents from
AWS S3, parses them with an LLM into a strict schema, and writes the result
into a rate-limited, undocumented legacy CRM REST API.

> Full architecture, data flow, and design rationale: [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)

## Stack

- **Java 21 + Spring Boot 3.5.6** — matches the Commotion / mvement platform.
- **Gradle (Kotlin DSL) multi-module** — triple-module pattern per service:
  `{service}` / `{service}-schema` / `{service}-grpc-client`.
- **Netflix Conductor / Orkes** — workflow orchestration. Workflow definition
  lives at [customer-onboarding/app/src/main/resources/workflows/customer_onboarding_v1.json](customer-onboarding/app/src/main/resources/workflows/customer_onboarding_v1.json).
- **Spring State Machine 4.0.1** — per-record status tracking.
- **Resilience4j** — `Retry` + `CircuitBreaker` + `RateLimiter`, configured
  declaratively in [`application.yml`](customer-onboarding/app/src/main/resources/application.yml).
- **Redisson (cache-utils)** — idempotency keys via `SET NX EX`.
- **Jinjava** — prompt templates (same engine the platform already uses).
- **AWS SDK v2** — S3 fetch (via `BlobStoreClient` facade modeling `blob-store-utils`).
- **`AiProxyClient` interface** — stand-in for the platform's `ai-proxy-utils`
  gRPC client; a direct-to-Anthropic implementation is included for local dev.

## Module Layout

```
.
├── customer-onboarding/
│   ├── schema/                     # plain JAR — DTOs, enums, events
│   └── app/                        # Spring Boot impl
│       └── src/main/
│           ├── java/com/commotion/onboarding/
│           │   ├── OnboardingApplication.java
│           │   ├── config/         # @Configuration beans
│           │   ├── parse/          # LLM parser + AiProxyClient
│           │   ├── state/          # Spring State Machine
│           │   └── workflow/       # OnboardingService + REST controller
│           └── resources/
│               ├── application.yml
│               └── workflows/customer_onboarding_v1.json
├── integrations-crm-legacy/        # Resilience4j-wrapped REST adapter
└── docs/
    └── ARCHITECTURE.md             # diagram, data flow, design choices
```

## Build & Run

Requires Java 21. The Gradle wrapper points at Gradle 8.10.2; if `gradle-wrapper.jar`
isn't present, regenerate it once with a local Gradle:

```bash
gradle wrapper --gradle-version 8.10.2
```

Then:

```bash
./gradlew build
./gradlew :customer-onboarding:app:bootRun
```

Required environment for `bootRun`:

```bash
export ANTHROPIC_API_KEY=...           # for the direct-to-Anthropic dev client
export AWS_REGION=us-east-1            # for S3
export LEGACY_CRM_URL=https://...      # the legacy CRM base URL
export LEGACY_CRM_API_KEY=...
export MONGO_URI=mongodb://localhost:27017/onboarding
export REDIS_URL=redis://localhost:6379
```

## Trigger an Ingestion

```bash
curl -X POST http://localhost:8089/api/v1/onboarding/ingest \
  -H 'Content-Type: application/json' \
  -d '{"bucket":"incoming-customers","key":"acme/2025-05-26/order-form.pdf"}'
```

The handler:

1. Fetches the object via `BlobStoreClient`.
2. Sends a Jinjava-rendered prompt to the LLM with a tool-use schema bound to `CustomerDto`. On Bean Validation failure, the validator error is fed back to the model (max 2 self-repair retries).
3. Computes `sha256(externalId|email)` and acquires a 24-hour Redis lock — duplicates short-circuit.
4. Calls `LegacyCrmAdapter.upsert(...)` — protected by Resilience4j retry + circuit breaker + rate limiter, honoring `Retry-After`.
5. Reads the record back (`getById`) and compares `email` — mismatch fails the workflow.

## Resilience Summary

| Concern              | Mechanism                                                        |
|----------------------|------------------------------------------------------------------|
| 429 from CRM         | `RateLimitedException` → Resilience4j retry with exponential jitter |
| 5xx from CRM         | `TransientCrmException` → retry                                  |
| CRM down             | Circuit breaker opens after 50% failure over 20-call window      |
| Retry duplicates     | `Idempotency-Key` header + Redis `SET NX EX 86400`               |
| LLM produces garbage | Bean Validation + self-repair loop (max 2)                       |
| Permanent failures   | Kafka DLT (Conductor `failureWorkflow`) + Slack alert            |
| Silent schema drift  | Read-after-write reconcile on `email`                            |

## License

Proprietary — internal use only.
