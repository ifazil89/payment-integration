# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project state

Freshly generated Spring Initializr skeleton (Spring Boot 4.1.1, Java 21, Maven). The only production class is `PaymentIntegrationApplication`; there is no domain code, and `application.yaml` only sets the app name. There is intentionally no relational database (JPA/Flyway were removed) for now.

## Commands

Use the Maven wrapper (`./mvnw`); Docker must be running for tests and for the local dev run, because both start an Elasticsearch Testcontainer.

- Build (with tests): `./mvnw clean verify`
- Build without tests: `./mvnw clean package -DskipTests`
- Run all tests: `./mvnw test`
- Run one test class / method: `./mvnw test -Dtest=PaymentIntegrationApplicationTests` / `./mvnw test -Dtest=PaymentIntegrationApplicationTests#contextLoads`
- Run the app for local development with Testcontainers-backed services: `./mvnw spring-boot:test-run` (launches `TestPaymentIntegrationApplication` from `src/test`)
- Run the app against externally configured services: `./mvnw spring-boot:run`

## Architecture notes

- **Stack:** Spring Web MVC, `RestClient` (for calling external payment providers), Spring Data Elasticsearch, Actuator, springdoc-openapi (Swagger UI at `/swagger-ui.html` once controllers exist), Lombok.
- **Spring Boot 4 modular starters:** each feature has a matching `*-test` starter (e.g. `spring-boot-starter-webmvc-test`, `spring-boot-starter-data-elasticsearch-test`). When adding a new starter, add its `-test` counterpart for test slices.
- **Testcontainers / service connections:** `src/test/.../TestcontainersConfiguration.java` declares containers as `@ServiceConnection` beans, so Spring Boot auto-wires connection properties — no URLs in config for tests. Elasticsearch runs `elasticsearch:9.3.3` with `@Ssl`. Integration tests opt in with `@Import(TestcontainersConfiguration.class)`; new backing services (e.g. a database) should be added as beans there.
- **Lombok** is wired explicitly as an annotation processor for both `compile` and `testCompile` executions in `maven-compiler-plugin`.
- The empty `<licenses>`, `<developers>`, `<scm>` elements in `pom.xml` are intentional overrides to block inheritance from the Spring Boot parent POM.


## NI N-Genius (one-stage Direct API)
- Spec: @doc/ngenius/ngenius-one-stage-spec.md (follow it exactly; follow _links hrefs, don't hand-build URLs)
- All NI calls live in NiGatewayClient behind the PaymentGateway interface. Bearer token added by NiBearerTokenInterceptor (cached in NiAccessTokenProvider, refreshed before 300s; one retry on 401). HTTP errors are translated by NiErrorTranslator; only idempotent calls (token, GET order) go through the RetryTemplate.
- PAN/CVV/expiry: never logged, never persisted, never in exceptions or metrics. Mask in all DTO toString().
- Treat payment `state` as an open enum; unknown values are logged, not fatal.
- Never blind-retry the payment POST. On timeout, mark payment UNKNOWN and reconcile via GET order.
- Webhook: verify secret header, dedupe by eventId, return 200 fast, process async.
- 
## Structure
- Base package: com.fazil.payments. Dependency direction: api -> service -> gateway, repository -> domain. `domain` depends on nothing in the app.
- `api/` controllers; `api/dto/{request,response}` REST contract; `api/mapper` DTO <-> domain mapping (no static `from`/`to` methods on DTOs); `api/error/GlobalExceptionHandler` (extends ResponseEntityExceptionHandler, RFC 9457); `api/validation` custom constraints (`@PaymentId`).
- `service/` use cases (PaymentService, WebhookService) plus their command/result records.
- `scheduler/` `@Scheduled` jobs (reconciliation; disable with `payments.reconciliation.enabled=false`).
- `domain/model` entities and value objects (Payment, PaymentStatus, Money, CardDetails, GatewayLinks, GatewayPaymentResult...); `domain/exception` business exceptions mapped to 404/409.
- `gateway/` the PaymentGateway port and its request/event types; `gateway/exception` the gateway failure hierarchy. All NI code stays in `gateway/ni` (package-private except `NiProperties`); NI wire DTOs in `gateway/ni/dto`. Other layers use only the PaymentGateway interface.
- `repository/` Spring Data Elasticsearch repositories. Payment writes go through the `PaymentRepositoryCustom` fragment (`insertIfAbsent`, optimistic-locking `update`), never plain `save`.
- `config/` Spring configuration and `@ConfigurationProperties` (picked up by `@ConfigurationPropertiesScan`); `common/logging` correlation id filter and MDC propagation.
- Tests mirror main packages; WireMock stubs in src/test/resources. Controller tests are `@WebMvcTest` slices; NI tests build the client through `NiGatewayConfiguration`.
