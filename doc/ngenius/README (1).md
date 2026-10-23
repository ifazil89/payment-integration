# ni-payment-integration

Middleware between **our backend** and **N-Genius (Network International) Direct API**.

Our backend never talks to N-Genius. It calls this service with a clean API in **major units**
(AED 250.00), and this service handles everything N-Genius-specific: access tokens, vendor media
types, HAL links, minor units, two-stage payments, EMV 3DS 2.x, saved cards, webhooks and
reconciliation.

```
Frontend ──► Our backend ──► ni-payment-integration ──► N-Genius
   ▲                              │   ▲                    │
   │   3DS challenge (browser) ───┘   └──── webhooks ──────┘
   └────── redirect back to the shop after 3DS
```

- Spring Boot 4.1.1, Java 21
- Default profile **sandbox**: N-Genius sandbox plus in-memory H2. Only two values are needed to run.
- Profile **prod**: live N-Genius plus PostgreSQL. Every secret comes from the environment.

---

## 1. Run it against the N-Genius sandbox

**Prerequisites:** Java 21+, Maven 3.9+, an N-Genius sandbox account (service account API key + outlet reference).

```bash
export NGENIUS_API_KEY='<service-account-api-key>'   # from the N-Genius portal
export NGENIUS_OUTLET_ID='<outlet-reference>'
mvn spring-boot:run
```

The service starts on `http://localhost:8085`. In sandbox, the defaults are:

| Setting | Sandbox default | Env var |
|---|---|---|
| N-Genius URL | `https://api-gateway.sandbox.ngenius-payments.com` | `NGENIUS_BASE_URL` |
| Backend API key (`X-Api-Key`) | `sandbox-backend-key` | `BACKEND_API_KEY` |
| Webhook secret header | `X-Webhook-Token: sandbox-webhook-secret` | `NGENIUS_WEBHOOK_HEADER_NAME/VALUE` |
| Card token encryption key | dev key (**never use in prod**) | `CARD_TOKEN_KEY` |
| Public URL of this service | `http://localhost:8085` | `PUBLIC_BASE_URL` |
| Allowed shop return URLs | `http://localhost:3000/` | `ALLOWED_RETURN_URL_PREFIXES` |
| Backend status callback | none (logged only) | `BACKEND_CALLBACK_URL` |

> **3DS and webhooks need public URLs.** The bank's ACS (through the shopper's browser) and
> N-Genius must reach `/ngenius/**`. Locally, use a tunnel (ngrok, cloudflared) and set
> `PUBLIC_BASE_URL` to the tunnel URL.

Run the tests (no N-Genius credentials needed; the two N-Genius adapters are mocked):

```bash
mvn test
```

Request examples for every endpoint are in [`api-examples.http`](api-examples.http). They can be
run from IntelliJ or VS Code (REST Client).

---

## 2. Folder structure

Feature first, then layers. Small features stay flat.

```
com.company.payintegration
├── PayIntegrationApplication
│
├── payment/                          two-stage payments, status, capture, reverse, refund
│   ├── PaymentApi                    PUBLIC API (used by webhook)
│   ├── presentation/                 PaymentController, PaymentOperationsController, DTOs
│   ├── application/                  use cases, AuthorisationFlow, PaymentStatusUpdater,
│   │   └── port/                     ReconcilePendingPaymentsJob, interfaces to the outside world
│   ├── domain/                       Payment (state machine + rules), PaymentStatus …
│   └── infrastructure/
│       ├── ngenius/                  NgeniusPaymentGatewayAdapter (implements PaymentGatewayPort)
│       ├── persistence/              JPA entity + adapter (optimistic locking)
│       └── backend/                  BackendCallbackAdapter (status → our backend)
│
├── threeds/                          EMV 3DS 2.x
│   ├── ThreeDsApi, BrowserInfo, …    PUBLIC API (used by payment)
│   ├── ThreeDsCompletedEvent         PUBLIC event (payment listens; no cyclic dependency)
│   ├── presentation/                 notification (cres) + challenge page (PUBLIC endpoints)
│   ├── application/                  start / complete use cases
│   ├── domain/                       ThreeDsSession, TransStatus, ChallengeRequestFactory
│   └── infrastructure/               N-Genius 3DS adapter, persistence
│
├── savedcard/                        tokens from successful payments
│   ├── SavedCardApi, …               PUBLIC API (used by payment)
│   ├── presentation/ application/ domain/ infrastructure/   (token encrypted at rest)
│
├── webhook/                          flat: controller, secret check, dedupe, process
│
└── shared/
    ├── domain/                       Money, BusinessException
    ├── presentation/                 GlobalExceptionHandler, CorrelationIdFilter, MoneyDto
    ├── util/                         SensitiveDataMasker
    └── infrastructure/
        ├── ngenius/                  NgeniusApiClient, HTTP client + pool, MinorUnitConverter
        │   ├── auth/                 NgeniusAccessTokenProvider (5-min token, cached), 401 retry
        │   └── hal/                  order/payment resources, HalLinks, NgeniusLinkRelations
        ├── security/                 X-Api-Key filter for /api/**
        ├── crypto/                   AES-GCM cipher
        └── config/                   AppProperties
```

The rules are enforced by `ArchitectureTest` (ArchUnit). The build fails if, for example,
`payment` imports `threeds.application.*`, or if a domain class imports Spring or JPA.

---

## 3. API for our backend

Every `/api/**` call needs `X-Api-Key`. Send `X-Correlation-Id` to follow a payment through
both services' logs. Errors use RFC 9457 `ProblemDetail` with a stable `code` and the `correlationId`.

| Method | Path | Purpose |
|---|---|---|
| POST | `/api/v1/payments/card` | New card: AUTH order + card (+ 3DS). Needs `Idempotency-Key` |
| POST | `/api/v1/payments/saved-card` | Saved card + CVV (+ 3DS). Needs `Idempotency-Key` |
| GET | `/api/v1/payments/{id}?refresh=true` | Status (`refresh=true` re-reads N-Genius) |
| POST | `/api/v1/payments/{id}/capture` | Capture all (no body) or part `{"amount":{…}}` |
| POST | `/api/v1/payments/{id}/reverse` | Void an uncaptured authorisation |
| POST | `/api/v1/payments/{id}/refunds` | Refund all or part of the captured amount |
| GET | `/api/v1/customers/{customerId}/saved-cards` | List saved cards (masked) |
| DELETE | `/api/v1/customers/{customerId}/saved-cards/{id}` | Delete a saved card |

**Payment statuses** (ours, independent of N-Genius naming):
`CREATED → AWAITING_3DS → AUTHORISED → PARTIALLY_CAPTURED → CAPTURED → PARTIALLY_REFUNDED → REFUNDED`,
or the terminal statuses `REVERSED`, `DECLINED`, `FAILED`. A status only moves forward, so a late or
duplicate webhook can never move a payment backwards.

**HTTP status vs. payment status:** `200` means the request was processed. A declined card is still
`200` with `"status": "DECLINED"` or `"FAILED"` in the body. Non-2xx means the request itself failed:

| HTTP | `code` | Meaning |
|---|---|---|
| 400 | `INVALID_REQUEST` / validation | Bad input |
| 401 | `UNAUTHORIZED` | Missing or wrong `X-Api-Key` |
| 404 | `PAYMENT_NOT_FOUND`, `SAVED_CARD_NOT_FOUND` | |
| 409 | `INVALID_PAYMENT_STATE`, `DUPLICATE_REQUEST`, `CONCURRENT_UPDATE` | e.g. capture on a reversed payment |
| 422 | `INVALID_AMOUNT`, `GATEWAY_REJECTED`, `INVALID_RETURN_URL`, `SAVED_CARD_EXPIRED` | Breaks a rule |
| 502 | `GATEWAY_ERROR` | N-Genius returned 5xx |
| 504 | `GATEWAY_TIMEOUT` | **Outcome unknown.** Do not create a new payment; retry with the **same** `Idempotency-Key` or GET the payment later |

---

## 4. The flows

### 4.1 Card payment (two-stage) with 3DS

```
Backend                     Middleware                                  N-Genius
   │ POST /payments/card       │                                            │
   │──────────────────────────►│ save Payment CREATED                       │
   │                           │ POST /transactions/outlets/{o}/orders ────►│ action=AUTH
   │                           │ save order + payment references            │
   │                           │ PUT payment:card {pan,expiry,cvv,name} ───►│ → AWAIT_3DS
   │                           │ POST cnp:3ds2-authentication ─────────────►│ → transStatus=C
   │◄── AWAITING_3DS + nextAction {redirectUrl, acsUrl, creq}               │
   │
Frontend: redirect the browser to nextAction.redirectUrl
   (our page auto-POSTs creq to the bank ACS; or post creq to acsUrl yourself, e.g. in an iframe)
   │
Bank ACS ──(browser POST cres)──► /ngenius/3ds/notification/{sessionId}
                               │ POST cnp:3ds2-challenge-response ──────────►│
                               │ ThreeDsCompletedEvent → GET order ─────────►│ → AUTHORISED
                               │ callback to backend (if configured)
Browser ◄── 303 redirect to returnUrl?paymentId=…
Frontend asks backend → backend GET /api/v1/payments/{id}
```

If the authentication is frictionless (no challenge), the response is already `AUTHORISED`
(or `DECLINED`) with no `nextAction`.

### 4.2 Saved cards

1. The first payment is sent with `"saveCard": true`. If tokenisation is enabled on the N-Genius
   account, the successful payment contains `savedCard.cardToken`. We store it **encrypted**
   with the masked PAN, scheme and expiry.
2. Later payments use `POST /payments/saved-card` with `savedCardId` and the **CVV** (N-Genius
   requires the CVV every time, and it is never stored). The flow then continues as in 4.1,
   including 3DS.

### 4.3 Capture, reverse, refund

The middleware first checks the rules in `Payment` (amount, currency, status), re-reads the order
to get fresh links, then calls `cnp:capture`, `cnp:cancel`, or `{capture self}/refund`.

### 4.4 Keeping the status correct

| Source | What happens |
|---|---|
| API response | applied immediately |
| 3DS completion | `GET order` → apply |
| Webhook `/ngenius/webhooks` | secret header checked → `eventId` de-duplicated → `GET order` → apply. The payload's state is **not** trusted on its own. |
| `ReconcilePendingPaymentsJob` (every 2 min) | payments still `CREATED`/`AWAITING_3DS` after 2 min → `GET order` → apply. **Needed because N-Genius webhooks are sent once and never retried.** |

---

## 5. N-Genius setup checklist

- [ ] **Service account API key** and **outlet reference** for sandbox and live.
- [ ] **EMV 3DS 2.x** enabled on the outlet (Direct API).
- [ ] **Tokenisation** enabled if you want saved cards.
- [ ] **Webhook** in the portal (Settings → Integrations → Manage Webhooks):
      URL `https://<public-host>/ngenius/webhooks` and a custom header matching
      `NGENIUS_WEBHOOK_HEADER_NAME` / `NGENIUS_WEBHOOK_HEADER_VALUE`. Ask N-Genius to allow-list the URL.
- [ ] `PUBLIC_BASE_URL` reachable from the internet (3DS notification + challenge page).

### Verify in the sandbox (could not be confirmed from the docs)

| Item | Where in code |
|---|---|
| Link relation names `cnp:capture`, `cnp:cancel`, `cnp:refund`, `payment:saved-card` | `NgeniusLinkRelations` |
| `merchantOrderReference` accepted on create order | `NgeniusCreateOrderRequest` |
| Whether the access-token call needs a body (`realmName`) on your tenant | `NGENIUS_REALM_NAME` |
| Exact payment state names (e.g. `PURCHASED`, `PARTIALLY_CAPTURED`, `DECLINED`) | `NgeniusStateMapper` (unknown states are logged, not guessed) |
| Whether `savedCard` appears in `GET order` after a 3DS payment | `NgeniusPaymentGatewayAdapter.toResult` |

Use the test cards from the N-Genius sandbox documentation, including the 3DS challenge and
frictionless cards.

---

## 6. Security and PCI DSS

- **Direct API means full PCI DSS scope.** Card numbers and CVVs pass through the frontend, backend
  and this service. They are never logged or stored: request DTOs have masked `toString()`, the
  N-Genius logging interceptor never logs bodies, and the CVV is never persisted. To reduce scope,
  switch to N-Genius **Hosted Session / Web SDK**. Only `CardDetailsDto` and the `payment:card`
  step would change.
- `/api/**`: shared API key (skeleton). **Production:** mTLS or OAuth2 client-credentials, private network.
- `/ngenius/**`: public. 3DS uses unguessable session IDs, and `returnUrl` is restricted to
  allowed prefixes (no open redirect). Webhooks are checked with a secret header (N-Genius payloads
  are **unsigned**).
- N-Genius links are only followed if they point to the configured N-Genius host, so a bearer token
  or card data can never be sent elsewhere.
- Card tokens are encrypted with AES-256-GCM. **Production:** load the key from KMS or Vault, and plan rotation.

---

## 7. Production checklist

- [ ] `SPRING_PROFILES_ACTIVE=prod`, PostgreSQL (`DB_URL`, `DB_USERNAME`, `DB_PASSWORD`), all secrets from a secret store.
- [ ] Set `spring.jpa.hibernate.ddl-auto: validate` once the schema is verified on your database.
- [ ] Run several instances → guard `ReconcilePendingPaymentsJob` with ShedLock (or similar).
- [ ] Replace the best-effort backend callback with a transactional outbox + Kafka/SQS if the backend must never miss an update.
- [ ] Concurrent capture/refund on the same payment: add a row lock (`SELECT … FOR UPDATE`) around the operation if your backend can send them in parallel.
- [ ] Idempotency: the key is unique per payment. Consider rejecting a reused key that comes with a *different* body.
- [ ] Observability: `/actuator/health`, `/actuator/metrics`. Watch `http.client.requests` (N-Genius latency and errors), `httpcomponents.httpclient.pool.*` (leaked or exhausted connections), and the count of payments stuck in `AWAITING_3DS`.
- [ ] Data retention for `payment`, `threeds_session`, `webhook_event`.

---

## Sources (N-Genius documentation)

- Access token: https://docs.ngenius-payments.com/reference/request-an-access-token-direct
- Two-stage payments: https://docs.ngenius-payments.com/reference/two-stage-payments-orders
- EMV 3DS 2.x: https://docs.ngenius-payments.com/reference/emv-3ds-3ds-2x-integration-2
- Retrieve order status: https://docs.ngenius-payments.com/reference/retrieve-order-status
- Capture: https://docs.ngenius-payments.com/reference/capture-an-authorized-payment-1
- Reverse authorisation: https://docs.ngenius-payments.com/reference/reversing-an-authorized-payment-1
- Refund: https://docs.ngenius-payments.com/reference/refund-a-capture-1
- Tokenisation (saved cards): https://docs.ngenius-payments.com/docs/tokenization-guide
- Webhooks: https://docs.ngenius-payments.com/reference/consuming-web-hooks
