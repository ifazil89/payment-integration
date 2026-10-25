# ni-payment-integration

Payment API for downstream clients, processed by **N-Genius (Network International)**.
Step 1: **`POST /api/v1/payments`**, a two-stage authorisation with a card or a card-on-file token, including 3DS authentication.

Spring Boot 4.1.1 · Java 21 · contract-first OpenAPI (openapi-generator 7.26.0) · MapStruct 1.6.3 ·
OpenTelemetry tracing · ECS JSON logs · Flyway · PostgreSQL (H2 in sandbox)

---

## 1. Run

**Prerequisites:** JDK 21 and Maven 3.9+. Maven needs access to Maven Central.

```bash
# 1. N-Genius sandbox credentials (N-Genius portal -> Settings -> Integrations -> Service Accounts)
export NI_API_KEY='<service-account-api-key>'
export NI_OUTLET_ID='<outlet-reference>'

# 2. Build: generates the API interface and models from the YAML, runs MapStruct, runs the tests
mvn clean verify

# 3. Start (sandbox profile: N-Genius sandbox + in-memory H2), JSON logs
mvn spring-boot:run

#    ...or with human-readable logs for local debugging
mvn spring-boot:run -Dspring-boot.run.profiles=sandbox,plainlogs
```

| URL | What |
|---|---|
| `http://localhost:8085/swagger-ui.html` | Swagger UI of the contract. Use **Authorize** with API key `sandbox-key` |
| `http://localhost:8085/openapi/payment-api.yaml` | the contract file itself |
| `http://localhost:8085/actuator/health` | health |

- **Try it:** use [`api-examples.http`](api-examples.http) in IntelliJ or VS Code (REST Client), or curl:

  ```bash
  curl -i http://localhost:8085/api/v1/payments \
    -H 'X-Api-Key: sandbox-key' -H 'Idempotency-Key: SO-1001-1' -H 'X-Correlation-Id: demo-1' \
    -H 'Content-Type: application/json' -d @- <<'EOF'
  {"merchantOrderId":"SO-1001","cardType":"CARD",
   "card":{"pan":"<sandbox-test-card>","expiry":"2030-12","cvv":"123","cardholderName":"Jane Doe"},
   "amount":{"value":250.00,"currency":"AED"},
   "billingInfo":{"firstName":"Jane","lastName":"Doe","email":"jane@example.com"},
   "deviceInfo":{"ip":"203.0.113.10","userAgent":"Mozilla/5.0","acceptHeader":"text/html","language":"en"}}
  EOF
  ```

- **Tests:** `mvn test` needs no N-Genius credentials, because `NiClient` is mocked.
- **Lombok:**
  - IntelliJ has Lombok support built in. Enable *Settings → Build → Compiler → Annotation Processors → Enable annotation processing*.
  - Eclipse/VS Code need the Lombok plugin/extension.
- **IDE:** after the first `mvn compile`, mark `target/generated-sources/openapi/src/main/java` as a source folder if your IDE does not do it automatically. It holds only the `PaymentsApi` interface.
- **Production:** `SPRING_PROFILES_ACTIVE=prod` with `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, `API_KEYS`, `NI_*` (see [Configuration](#6-configuration)).

---

## 2. Structure: feature first, then layers

```
src/main/resources/static/openapi/payment-api.yaml   THE CONTRACT (documentation for Swagger UI + source of PaymentsApi)

target/generated-sources/openapi/  (generated on every build, never edited, never committed)
└── com.company.payment.api.PaymentsApi  the ONLY generated class: interface with path, headers, @Valid,
                                         using our DTOs below

src/main/java/com.company.payment
├── PaymentApplication
│
├── payment/                             FEATURE: authorise a payment
│   ├── controller/PaymentController     implements PaymentsApi (one line)
│   ├── dto/                             API DTOs (records, Bean Validation): PaymentRequest, CardType, CardInfo,
│   │                                    BillingInfo, DeviceInfo, PaymentResponse, PaymentStatus
│   ├── service/PaymentService           the flow, step by step
│   ├── validation/PaymentRequestValidator   cardType vs pan/token, expiry
│   ├── mapper/PaymentMapper             MapStruct: DTO <-> entity
│   ├── mapper/PaymentNiMapper           MapStruct: DTO -> N-Genius DTOs
│   ├── entity/Payment, PaymentState, PaymentCardType
│   └── repository/PaymentRepository
│
├── ni/                                  N-Genius, SHARED by payment / threeds / token
│   ├── client/NiClient                  every N-Genius call, one method each
│   ├── client/NiAccessTokenProvider     5-minute access token, cached
│   ├── config/NiConfig                  pooled HTTP client, timeouts, pool metrics, logging interceptor
│   ├── config/NiProperties
│   ├── dto/                             N-Genius JSON (NiOrderRequest, NiCardRequest, NiPaymentResponse, NiLinks, ...)
│   └── exception/NiException
│
└── common/                              cross-cutting, used by every feature
    ├── dto/Amount                       money, shared by every feature
    ├── exception/ErrorResponse, ErrorDetail, ApiException, GlobalExceptionHandler
    ├── logging/CorrelationIdFilter, HttpLoggingFilter, RestClientLoggingInterceptor,
    │           SensitiveDataMasker, LogFields, LoggingConfig, LoggingProperties
    ├── security/ApiKeyFilter, SecurityConfig
    └── util/Amounts
```

**Next features follow the same shape:** `threeds/{controller,service,mapper,...}` and `token/{...}`.
Each adds its DTOs in `<feature>/dto/` and its paths to the YAML, which generates a `ThreeDsApi` / `TokenApi` interface for the controller to implement, and each reuses `ni/client/NiClient`.

**A request goes through three layers:**
- `PaymentController` (generated contract)
- `PaymentService`
- `NiClient` and `PaymentRepository`

**Lombok, where it is used:**

| Annotation | Where |
|---|---|
| `@Slf4j` | every class that logs |
| `@RequiredArgsConstructor` | constructor injection: services, controller, client, filters, interceptor, masker |
| `@Getter` | `Payment` entity, `ApiException`, `NiException` |
| `@Setter` (per field) | only the `Payment` fields copied from the request; everything else changes through methods like `orderCreated()` and `fail()` |
| `@NoArgsConstructor` | `Payment` (JPA) |

- **DTOs stay Java records.** A record already has a constructor, accessors, `equals`, `hashCode` and `toString`, so Lombok would add nothing.
- **No `@Data` on the entity.** Its `equals`/`hashCode`/`toString` break JPA entities: lazy loading, and objects changing identity once saved.
- **Explicit constructors are kept** where they do more than assign fields: `NiClient` and `PaymentRequestValidator`.

**Mapping:**
- API DTOs map to the clean `Payment` entity, which maps to the N-Genius DTOs.
- MapStruct writes the mapping code at compile time.
- `unmappedTargetPolicy=ERROR`: a new field that nobody maps fails the build.

### How the DTOs and the YAML fit together

The DTOs are normal classes in `src/main/java`, which you read and edit like any other code. In `pom.xml`,
`<schemaMappings>` tells the generator that schema `PaymentRequest` **is**
`com.company.payment.payment.dto.PaymentRequest` (and so on for every schema). So:
- it generates **no** model classes;
- the generated `PaymentsApi` interface uses our DTOs;
- Swagger UI shows the YAML.

**`ContractTest` keeps the YAML and the DTOs identical.** The build fails if:
- a schema has no DTO;
- a field exists only in one of them;
- an enum value differs;
- a request field is `required` in the YAML but not `@NotNull`/`@NotBlank` in Java, or the other way round.

### Changing the API
1. Change the DTO (for example, add a field to `PaymentRequest`).
2. Make the same change in `payment-api.yaml` (property + `required` if needed). `ContractTest` reminds you if you forget.
3. For a **new** schema: add the DTO, a `<schemaMapping>` line in `pom.xml` and a line in `ContractTest.DTOS`.
4. Run `mvn verify`.

---

## 3. POST /api/v1/payments

Headers:
- `X-Api-Key`: per client.
- `Idempotency-Key`: per payment attempt.
- `X-Correlation-Id`: optional.

`cardType` decides which card fields are required (one `card` object for both types):

| cardType | required | must be empty |
|---|---|---|
| `CARD` | `pan`, `expiry`, `cvv`, `cardholderName` | `token` |
| `CARD_ON_FILE` | `token` (the saved N-Genius card token), `expiry`, `cvv`, `cardholderName` | `pan` |

**The client decides the next call from `status`:**

| status | Meaning | Next API |
|---|---|---|
| `AUTHORISED` | money reserved (also after frictionless 3DS) | capture (step to come) |
| `CHALLENGE_REQUIRED` | shopper must complete a 3DS challenge | 3DS API (next step) |
| `PENDING` | N-Genius hasn't confirmed yet | status API (to come), or retry with the **same** Idempotency-Key |
| `DECLINED` / `FAILED` | refused | stop, or a new attempt with a new Idempotency-Key |

### Flow (`PaymentService.createPayment`)

| # | Step | N-Genius call | State after |
|---|---|---|---|
| 1 | Validate (cardType vs pan/token, expiry, amount decimals) | — | — |
| 2 | Same client + Idempotency-Key? Return that payment (409 if the request differs) | — | — |
| 3 | Save the payment | — | `CREATED` |
| 4 | Create a two-stage **AUTH** order | `POST /transactions/outlets/{outlet}/orders` | `CREATED` + order ref |
| 5 | `CARD`: card · `CARD_ON_FILE`: saved token | `PUT payment:card` · `PUT payment:saved-card` | `AUTHORISED` / `AWAITING_3DS` / `FAILED` |
| 6 | If `AWAITING_3DS`: 3DS authentication with `deviceInfo` | `POST cnp:3ds2-authentication` | `AUTHORISED` / `CHALLENGE_REQUIRED` |
| 7 | Save and return | — | |

**Failures:**
- **N-Genius 4xx:** `200` with `status: FAILED`.
- **N-Genius 5xx:** `502`.
- **Timeout:** `504`. The outcome is unknown, so the payment stays `PENDING`; retrying with the same Idempotency-Key is safe.

### Error response

Every non-200 response has the same body: **`ErrorResponse`**, plus **`ErrorDetail`** entries for field errors.

```json
{
  "code": "VALIDATION_ERROR",
  "message": "Request validation failed",
  "correlationId": "demo-1",
  "traceId": "4bf92f3577b34da6a3ce929d0e0e4736",
  "timestamp": "2026-10-24T07:00:01.412Z",
  "errors": [
    { "field": "card.token", "message": "is required for cardType CARD_ON_FILE" },
    { "field": "card.pan",   "message": "must be empty for cardType CARD_ON_FILE" }
  ]
}
```

`errors` appears only for `VALIDATION_ERROR`; null fields are omitted.

**Where the classes are:** in source, `common/exception/`:

```java
public record ErrorResponse(String code, String message, String correlationId, String traceId,
                            OffsetDateTime timestamp, List<ErrorDetail> errors) { }

public record ErrorDetail(String field, String message) { }
```

They are built in exactly one place, `GlobalExceptionHandler.build(...)`:

```java
new ErrorResponse(code, message,
        MDC.get(LogFields.CORRELATION_ID), MDC.get(LogFields.TRACE_ID),
        OffsetDateTime.now(ZoneOffset.UTC),
        errors);                                        // List<ErrorDetail>: new ErrorDetail(field, message)
```

The field errors come from three sources, all turned into `ErrorDetail`:
- **Bean Validation on the DTOs** (`@NotBlank`, `@Pattern`, `@Size`, `@Email`): `card.expiry`, `billingInfo.email`, ...
- **`PaymentRequestValidator`**: cardType vs pan/token, expired card. It throws `ApiException.validation(...)`.
- **Header constraints** (`Idempotency-Key`).

Swagger UI shows an example body for every error status.

To change the error shape, edit the records and the YAML schemas together (`ContractTest` checks both).

| HTTP | code |
|---|---|
| 400 | `VALIDATION_ERROR` (with field errors such as `card.pan`), `MALFORMED_REQUEST`, `MISSING_HEADER` |
| 401 | `UNAUTHORIZED` |
| 409 | `IDEMPOTENCY_KEY_REUSED`, `REQUEST_IN_PROGRESS` |
| 422 | `INVALID_AMOUNT` |
| 500 | `INTERNAL_ERROR` |
| 502 | `GATEWAY_ERROR` |
| 504 | `GATEWAY_TIMEOUT` |

---

## 4. Logging and tracing

### What every log line carries

| Field | Where it comes from |
|---|---|
| `@timestamp` | log time, ISO-8601 with milliseconds |
| `log.level`, `log.logger`, `process.thread.name`, `message` | Logback |
| `service.name`, `service.version`, `service.environment` | `application.yml` (`logging.structured.ecs.service.*`) |
| `traceId`, `spanId` | OpenTelemetry (Micrometer Tracing): one trace per request, propagated to N-Genius as `traceparent` |
| `correlationId` | `X-Correlation-Id` from the client (or generated), also returned in the response header and the error body |
| `clientId` | the authenticated client (`ApiKeyFilter`) |
| `paymentId` | our payment id, from the moment the payment exists |

Every response also returns the `X-Correlation-Id` and `X-Trace-Id` headers. A client reporting a problem quotes either one, and one search finds every line.

### What is logged for one payment

```
HTTP IN  --> POST /api/v1/payments                                         headers (X-Api-Key masked), client ip
Payment created: CARD AED 250.00 for order SO-1001                         payment.* fields
HTTP OUT --> POST https://api-gateway.sandbox.../identity/auth/access-token   (Authorization masked)
HTTP OUT <-- 200 POST https://.../identity/auth/access-token (180 ms)         (access_token masked)
HTTP OUT --> POST https://.../transactions/outlets/.../orders                  request body
HTTP OUT <-- 201 POST https://.../orders (310 ms)                             response body
NI create order -> order a1b2... (312 ms)
HTTP OUT --> PUT https://.../payments/.../card                                body: pan 411111******1111, cvv ***
HTTP OUT <-- 200 PUT https://.../card (420 ms)                                response body
NI pay with card -> AWAIT_3DS (421 ms)
HTTP OUT --> POST https://.../3ds2/authentications                            browserInfo
HTTP OUT <-- 200 POST https://.../3ds2/authentications (380 ms)
NI 3DS authentication -> AWAIT_3DS (381 ms)
3DS challenge required: transStatus C, ACS https://acs...
Payment finished: CHALLENGE_REQUIRED (N-Genius state AWAIT_3DS)
HTTP IN  <-- 200 POST /api/v1/payments (1320 ms)                           request body (masked) + response body
```

| Line | Logged by | Structured fields |
|---|---|---|
| `HTTP IN` | `HttpLoggingFilter` | `http.direction=inbound`, `http.method`, `http.path`, `http.status_code`, `http.duration_ms`, `http.request.headers`, `http.request.body`, `http.response.headers`, `http.response.body` |
| `HTTP OUT` | `RestClientLoggingInterceptor` | the same, with `http.direction=outbound` and `http.url`. A timeout logs `HTTP OUT <-- FAILED ... after 30001 ms` with `error.type` |
| `NI ...` | `NiClient` | `ni.operation`, `ni.outcome`, `ni.duration_ms` |
| `Payment ...` | `PaymentService` | `payment.card_type`, `payment.amount`, `payment.state`, `payment.ni_state`, ... |

Levels:
- `INFO` for 2xx.
- `WARN` for 4xx.
- `ERROR` for 5xx and timeouts.
- `GlobalExceptionHandler` logs every rejected request with `error.code`.

### One line as JSON (ECS, the default)

```json
{"@timestamp":"2026-10-24T07:00:01.412Z","log":{"level":"INFO","logger":"com.company.payment.common.logging.RestClientLoggingInterceptor"},
 "process":{"pid":4120,"thread":{"name":"http-nio-8085-exec-1"}},
 "service":{"name":"ni-payment-integration","version":"0.1.0-SNAPSHOT","environment":"sandbox"},
 "message":"HTTP OUT <-- 200 PUT https://api-gateway.sandbox.ngenius-payments.com/.../card (420 ms)",
 "traceId":"4bf92f3577b34da6a3ce929d0e0e4736","spanId":"00f067aa0ba902b7","correlationId":"demo-1",
 "clientId":"local-client","paymentId":"7f3c2a9e-...",
 "http":{"direction":"outbound","method":"PUT","url":"https://...","status_code":200,"duration_ms":420,
         "response":{"headers":"{Content-Type=application/vnd.ni-payment.v2+json}","body":"{\"state\":\"AWAIT_3DS\",...}"}},
 "ecs":{"version":"8.11"}}
```

(The exact envelope fields are Spring Boot's ECS formatter; the field values above are illustrative.)

**Other formats** need no code change: `LOG_FORMAT=logstash` (Logstash JSON) or `LOG_FORMAT=gelf` (Graylog).
**Human-readable console** for local debugging: the `plainlogs` profile.

### PCI DSS: what is never logged

`SensitiveDataMasker` is applied to every logged body and header:

| Data | In the log |
|---|---|
| `pan` | `411111******1111` (first 6 + last 4) |
| `cvv`, `token`, `cardToken`, `access_token`, `refresh_token`, `password` | `***` |
| `Authorization`, `X-Api-Key`, `Cookie`, `Set-Cookie` headers | `***` |

- Bodies are cut at `app.logging.http.max-body-length` (10 000).
- Set `LOG_HTTP_BODIES=false` to log only the method, URL, status and time.
- `PaymentApiTest.requestAndResponseAreLoggedWithoutCardData` fails the build if a card number, CVV or API key ever reaches the log.
- A new secret field in a future API goes into `SensitiveDataMasker` (`SECRET_FIELDS` and the regex).

### Shipping logs and traces to an observability tool (later, no code change)

The app writes **one JSON object per line to stdout**, the standard input for every log pipeline.

**Logs**, pick the one your platform uses:

| Tool | How |
|---|---|
| Elastic / OpenSearch | Filebeat or Elastic Agent reads the container stdout. ECS is Elastic's own format, so fields map 1:1 |
| Grafana Loki | Promtail / Grafana Alloy reads stdout; JSON fields become labels or are queried with `| json` |
| Datadog | Datadog Agent reads stdout; set `LOG_FORMAT=logstash` if you prefer its attribute names; link traces via `traceId` |
| Splunk / CloudWatch / Azure Monitor | their agent or log driver reads stdout |
| OpenTelemetry Collector | `filelog` receiver with a JSON parser, then any exporter |

**Traces** (already created for every request and N-Genius call) are exported over OTLP by setting one variable:

```bash
MANAGEMENT_OPENTELEMETRY_TRACING_EXPORT_OTLP_ENDPOINT=http://otel-collector:4318/v1/traces
TRACING_SAMPLING_PROBABILITY=1.0        # prod default 0.1
```

**Logs over OTLP instead of stdout** (optional) need three things:
1. The dependency `io.opentelemetry.instrumentation:opentelemetry-logback-appender-1.0`.
2. Its appender in a `logback-spring.xml`, installed at startup with `OpenTelemetryAppender.install(openTelemetry)`.
3. `MANAGEMENT_OPENTELEMETRY_LOGGING_EXPORT_OTLP_ENDPOINT=http://otel-collector:4318/v1/logs`.

Most teams keep stdout plus an agent; it survives a collector outage.

**Metrics:** `/actuator/metrics` includes `http.server.requests`, `http.client.requests` (N-Genius calls) and `httpcomponents.httpclient.pool.*` (the N-Genius connection pool).

---

## 5. Debugging

1. Take the `X-Correlation-Id` or `X-Trace-Id` from the response, or from the client's report.
2. Search the logs for it. You see the inbound request, every N-Genius request and response with bodies, the business lines and the final response, in order.
3. The `payment` table keeps the raw N-Genius state (`ni_state`), the order reference and the 3DS `trans_status`.

Breakpoints: `PaymentService.createPayment` (the whole flow) and `NiClient.call` (every N-Genius request).

---

### Checking the database

**Sandbox (H2), in the browser:**
1. Open `http://localhost:8085/h2-console`.
2. JDBC URL `jdbc:h2:mem:payment`, user `sa`, empty password, **Connect**.

**Sandbox, from IntelliJ / DBeaver:**
1. Start in file mode:
   `SANDBOX_DB_URL='jdbc:h2:file:./data/payment;MODE=PostgreSQL;AUTO_SERVER=TRUE' mvn spring-boot:run`
2. Connect the tool to the same URL, user `sa`.
3. Data survives restarts. To reset it, delete `./data/`, which is in `.gitignore`.

**Prod (PostgreSQL):** `psql "$DB_URL"`, or any SQL client.

Useful queries:

```sql
-- latest payments
SELECT id, client_id, merchant_order_id, card_type, masked_pan, amount, currency,
       state, ni_state, failure_reason, created_at
FROM payment ORDER BY created_at DESC LIMIT 20;

-- one payment (paymentId from the response or the logs)
SELECT * FROM payment WHERE id = '7f3c2a9e-...';

-- by the client's order id / the N-Genius order reference
SELECT * FROM payment WHERE merchant_order_id = 'SO-1001';
SELECT * FROM payment WHERE ni_order_reference = 'a1b2...';

-- stuck payments: outcome unknown (timeout / 5xx), to check against N-Genius
SELECT id, merchant_order_id, ni_order_reference, state, updated_at
FROM payment WHERE state IN ('CREATED', 'AWAITING_3DS') AND updated_at < now() - INTERVAL '15' MINUTE;

-- 3DS challenges waiting for the shopper
SELECT id, threeds_trans_status, threeds_acs_url, updated_at FROM payment WHERE state = 'CHALLENGE_REQUIRED';

-- applied migrations
SELECT version, description, success FROM "flyway_schema_history";
```

The `state` column holds internal states:
- `CREATED` and `AWAITING_3DS` are shown to clients as `PENDING`.
- `ni_state` is the raw state from N-Genius.
- Card numbers and CVVs are never stored, only `masked_pan`.

## 6. Configuration

| Property | Env var | Notes |
|---|---|---|
| `ni.base-url` | `NI_BASE_URL` | sandbox default set; live: `https://api-gateway.ngenius-payments.com` |
| `ni.api-key`, `ni.outlet-id` | `NI_API_KEY`, `NI_OUTLET_ID` | from the N-Genius portal |
| `ni.threeds-notification-url` | `NI_THREEDS_NOTIFICATION_URL` | public URL; `{paymentId}` is replaced. The endpoint is built with the 3DS API |
| `app.security.api-keys` | `API_KEYS` | `client1:key1,client2:key2` (key ≥ 8 chars) |
| `logging.structured.format.console` | `LOG_FORMAT` | `ecs` (default), `logstash`, `gelf` |
| `app.logging.http.log-bodies` | `LOG_HTTP_BODIES` | `true` (default, masked) / `false` |
| `logging.structured.ecs.service.environment` | `APP_ENVIRONMENT` | shown as `service.environment` |
| `management.tracing.sampling.probability` | `TRACING_SAMPLING_PROBABILITY` | 1.0 (prod 0.1) |
| `management.opentelemetry.tracing.export.otlp.endpoint` | `MANAGEMENT_OPENTELEMETRY_TRACING_EXPORT_OTLP_ENDPOINT` | not set = no export |
| `spring.datasource.*` | `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` | prod profile |

---

## 7. Notes

**Verify in the N-Genius sandbox:**
- the link names `payment:card`, `payment:saved-card` and `cnp:3ds2-authentication` (`NiLinks`)
- whether `merchantOrderReference` is accepted
- the exact N-Genius state names (`PaymentState.fromNi`)

Every N-Genius request and response is logged, so one sandbox run shows all of these.

**Next steps:**
1. 3DS API (challenge result, finish the authorisation)
2. Capture
3. Status / reconciliation
4. Webhooks
5. Token
